package com.zandero.rest;

import com.zandero.rest.context.ContextProvider;
import com.zandero.rest.context.ContextProviderFactory;
import com.zandero.rest.data.*;
import com.zandero.rest.events.RestEventExecutor;
import com.zandero.rest.exception.*;
import com.zandero.rest.injection.InjectionProvider;
import com.zandero.rest.reader.ReaderFactory;
import com.zandero.rest.reader.ValueReader;
import com.zandero.rest.writer.GenericResponseWriter;
import com.zandero.rest.writer.HttpResponseWriter;
import com.zandero.rest.writer.NotFoundResponseWriter;
import com.zandero.rest.writer.WriterFactory;
import com.zandero.utils.Assert;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.Session;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CookieHandler;
import io.vertx.ext.web.handler.CorsHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.validation.executable.ExecutableValidator;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds up a vert.x route based on JAX-RS annotation provided in given class
 */
public class RestRouter {

	public static final int ORDER_CORS_HANDLER = -10;
	public static final int ORDER_PROVIDER_HANDLER = -5;

	private final static Logger log = LoggerFactory.getLogger(RestRouter.class);

	private static final WriterFactory writers = new WriterFactory();

	private static final ReaderFactory readers = new ReaderFactory();

	private static final ExceptionHandlerFactory handlers = new ExceptionHandlerFactory();

	private static final ContextProviderFactory providers = new ContextProviderFactory();

	private static final RestEventExecutor eventExecutor = new RestEventExecutor();

	private static InjectionProvider injectionProvider;
	private static Validator validator;

	/**
	 * Searches for annotations to register routes ...
	 *
	 * @param vertx   Vert.X instance
	 * @param restApi instance to search for annotations
	 * @return Router new Router with routes as defined in {@code restApi} class
	 */
	public static Router register(Vertx vertx, Object... restApi) {

		Assert.notNull(vertx, "Missing vertx!");

		Router router = Router.router(vertx);
		return register(router, restApi);
	}

	/**
	 * Searches for annotations to register routes ...
	 *
	 * @param restApi instance to search for annotations
	 * @param router  to add additional routes from {@code restApi} class
	 * @return Router with routes as defined in {@code restApi} class
	 */
	public static Router register(Router router, Object... restApi) {

		Assert.notNull(router, "Missing vertx router!");
		Assert.isTrue(restApi != null && restApi.length > 0, "Missing REST API class object!");
		assert restApi != null;

		for (Object api : restApi) {

			if (api == null) {
				continue;
			}

			if (api instanceof Handler) {

				Handler handler = (Handler) api;
				router.route().handler(handler);
				continue;
			}

			// check if api is an instance of a class or a class type
			if (api instanceof Class) {

				Class inspectApi = (Class) api;

				try {
					api = ClassFactory.newInstanceOf(inspectApi, injectionProvider, null);
				}
				catch (ClassFactoryException | ContextException e) {
					throw new IllegalArgumentException(e.getMessage());
				}
			}

			Map<RouteDefinition, Method> definitions = AnnotationProcessor.get(api.getClass());

			for (RouteDefinition definition : definitions.keySet()) {

				// bind method execution
				Route route;
				if (definition.pathIsRegEx()) {
					route = router.routeWithRegex(definition.getMethod(), definition.getRoutePath());
				} else {
					route = router.route(definition.getMethod(), definition.getRoutePath());
				}

				log.info("Registering route: " + definition);

				if (definition.requestHasBody() && definition.getConsumes() != null) { // only register if request with body
					for (MediaType item : definition.getConsumes()) {
						route.consumes(MediaTypeHelper.getKey(item)); // ignore charset when binding
					}
				}

				if (definition.getProduces() != null) {
					for (MediaType item : definition.getProduces()) {
						route.produces(MediaTypeHelper.getKey(item)); // ignore charset when binding
					}
				}

				if (definition.getOrder() != 0) {
					route.order(definition.getOrder());
				}

				// check body and reader compatibility beforehand
				checkBodyReader(definition);

				// add BodyHandler in case request has a body ...
				if (definition.requestHasBody()) {
					route.handler(BodyHandler.create());
				}

				// add CookieHandler in case cookies are expected
				if (definition.hasCookies()) {
					route.handler(CookieHandler.create());
				}

				// add security check handler in front of regular route handler
				if (definition.checkSecurity()) {
					route.handler(getSecurityHandler(definition));
				}

				// bind handler // blocking or async
				Handler<RoutingContext> handler;
				Method method = definitions.get(definition);

				if (definition.isAsync()) {
					handler = getAsyncHandler(api, definition, method);
				} else {
					checkWriterCompatibility(definition);
					handler = getHandler(api, definition, method);
				}

				route.handler(handler);
			}
		}

		return router;
	}

	// Check writer compatibility if possible
	private static void checkWriterCompatibility(RouteDefinition definition) {
		try { // no way to know the accept content at this point
			getWriter(injectionProvider, definition.getReturnType(), definition, null);
		}
		catch (ClassFactoryException e) {
			// ignoring instance creation ... but leaving Illegal argument exceptions to pass
		}
	}

	public static void provide(Router output, Class<? extends ContextProvider> provider) {

		try {
			Class clazz = (Class) ClassFactory.getGenericType(provider);
			ContextProvider instance = getContextProviders().getContextProvider(injectionProvider,
			                                                                    clazz,
			                                                                    provider,
			                                                                    null);
			// set before other routes ...
			output.route().order(ORDER_PROVIDER_HANDLER).handler(getContextHandler(instance));
		}
		catch (Throwable e) {
			throw new IllegalArgumentException(e);
		}
	}

	public static void provide(Router output, ContextProvider<?> provider) {

		output.route().order(ORDER_PROVIDER_HANDLER).handler(getContextHandler(provider));
	}

	private static Handler<RoutingContext> getContextHandler(ContextProvider instance) {

		return context -> {

			if (instance != null) {
				try {
					Object provided = instance.provide(context.request());

					if (provided instanceof User) {
						context.setUser((User) provided);
					}

					if (provided instanceof Session) {
						context.setSession((Session) provided);
					}

					if (provided != null) { // push provided context into request data
						context.data().put(ContextProviderFactory.getContextKey(provided), provided);
					}
				}
				catch (Throwable e) {
					handleException(e, context, null); // no definition
				}
			}

			context.next();
		};
	}

	@SuppressWarnings("unchecked")
	public static void handler(Router output, Class<? extends Handler<RoutingContext>> handler) {

		try {
			Handler<RoutingContext> instance = (Handler<RoutingContext>) ClassFactory.newInstanceOf(handler, injectionProvider, null);
			output.route().handler(instance);
		}
		catch (ClassFactoryException | ContextException e) {
			throw new IllegalArgumentException(e.getMessage());
		}
	}

	/**
	 * Handles not found route for all requests
	 *
	 * @param router   to add route to
	 * @param notFound handler
	 */
	public static void notFound(Router router, Class<? extends NotFoundResponseWriter> notFound) {
		notFound(router, null, notFound);
	}

	/**
	 * Handles not found route for all requests
	 *
	 * @param router   to add route to
	 * @param notFound handler
	 */
	public static void notFound(Router router, NotFoundResponseWriter notFound) {
		notFound(router, null, notFound);
	}

	/**
	 * Handles not found route in case request regExPath mathes given regExPath prefix
	 *
	 * @param router   to add route to
	 * @param regExPath     prefix
	 * @param notFound handler
	 */
	public static void notFound(Router router, String regExPath, NotFoundResponseWriter notFound) {

		Assert.notNull(router, "Missing router!");
		Assert.notNull(notFound, "Missing not found handler!");

		addLastHandler(router, regExPath, getNotFoundHandler(notFound));
	}

	/**
	 * Handles not found route in case request regExPath mathes given regExPath prefix
	 *
	 * @param router   to add route to
	 * @param regExPath     prefix
	 * @param notFound hander
	 */
	public static void notFound(Router router, String regExPath, Class<? extends NotFoundResponseWriter> notFound) {

		Assert.notNull(router, "Missing router!");
		Assert.notNull(notFound, "Missing not found handler!");

		addLastHandler(router, regExPath, getNotFoundHandler(notFound));
	}

	private static void addLastHandler(Router router, String path, Handler<RoutingContext> notFoundHandler) {
		if (path == null) {
			router.route().last().handler(notFoundHandler);
		} else {
			router.routeWithRegex(path).last().handler(notFoundHandler);
		}
	}

	/**
	 * @param router               to add handler to
	 * @param allowedOriginPattern origin pattern
	 * @param allowCredentials     allowed credentials
	 * @param maxAge               in seconds
	 * @param allowedHeaders       set of headers or null for none
	 * @param methods              list of methods or empty for all
	 */
	public void enableCors(Router router,
	                       String allowedOriginPattern,
	                       boolean allowCredentials,
	                       int maxAge,
	                       Set<String> allowedHeaders,
	                       HttpMethod... methods) {

		CorsHandler handler = CorsHandler.create(allowedOriginPattern)
		                                 .allowCredentials(allowCredentials)
		                                 .maxAgeSeconds(maxAge);

		if (methods == null || methods.length == 0) { // if not given than all
			methods = HttpMethod.values();
		}

		for (HttpMethod method : methods) {
			handler.allowedMethod(method);
		}

		handler.allowedHeaders(allowedHeaders);
		router.route().handler(handler).order(ORDER_CORS_HANDLER);
	}

	private static void checkBodyReader(RouteDefinition definition) {

		if (!definition.requestHasBody() || !definition.hasBodyParameter()) {
			return;
		}

		ValueReader bodyReader = readers.get(definition.getBodyParameter(), definition.getReader(), injectionProvider,
		                                     null,
		                                     definition.getConsumes());

		if (bodyReader != null && definition.checkCompatibility()) {

			Type readerType = ClassFactory.getGenericType(bodyReader.getClass());
			MethodParameter bodyParameter = definition.getBodyParameter();

			ClassFactory.checkIfCompatibleTypes(bodyParameter.getDataType(), readerType,
			                                    definition.toString().trim() + " - Parameter type: '" +
			                                    bodyParameter.getDataType() + "' not matching reader type: '" +
			                                    readerType + "' in: '" + bodyReader.getClass() + "'!");
		}
	}

	private static HttpResponseWriter getWriter(InjectionProvider injectionProvider,
	                                            Class returnType,
	                                            RouteDefinition definition,
	                                            RoutingContext context) throws ClassFactoryException {

		if (returnType == null) {
			returnType = definition.getReturnType();
		}

		MediaType acceptHeader = null;
		if (context != null) {
			acceptHeader = MediaTypeHelper.valueOf(context.getAcceptableContentType());
		}

		HttpResponseWriter writer = writers.getResponseWriter(returnType, definition, injectionProvider, context, acceptHeader);

		if (writer == null) {
			log.error("No writer could be provided. Falling back to " + GenericResponseWriter.class.getSimpleName() + " instead!");
			return (HttpResponseWriter) ClassFactory.newInstanceOf(GenericResponseWriter.class);
		}

		if (definition.checkCompatibility() &&
		    ClassFactory.checkCompatibility(writer.getClass())) {

			Type writerType = ClassFactory.getGenericType(writer.getClass());
			ClassFactory.checkIfCompatibleTypes(returnType,
			                                    writerType,
			                                    definition.toString().trim() + " - Response type: '" +
			                                    returnType + "' not matching writer type: '" +
			                                    writerType + "' in: '" + writer.getClass() + "'!");
		}

		return writer;
	}

	private static Handler<RoutingContext> getSecurityHandler(final RouteDefinition definition) {

		return context -> {

			boolean allowed = isAllowed(context.user(), definition);
			if (allowed) {
				context.next();
			} else {
				handleException(new ExecuteException(Response.Status.UNAUTHORIZED.getStatusCode(), "HTTP 401 Unauthorized"), context, definition);
			}
		};
	}

	private static boolean isAllowed(User user, RouteDefinition definition) {

		if (definition.getPermitAll() != null) {
			// allow all or deny all
			return definition.getPermitAll();
		}

		if (user == null) {
			return false; // no user present ... can't check
		}

		// check if given user is authorized for given role ...
		List<Future> list = new ArrayList<>();

		for (String role : definition.getRoles()) {

			Future<Boolean> future = Future.future();
			user.isAuthorized(role, future.completer());

			list.add(future);
		}

		// compose multiple futures ... and return true if any of those return true
		Future<CompositeFuture> output = Future.future();
		CompositeFuture.all(list).setHandler(output.completer());

		if (output.result() != null) {

			for (int index = 0; index < output.result().size(); index++) {
				if (output.result().succeeded(index)) {

					Object result = output.result().resultAt(index);
					if (result instanceof Boolean && ((Boolean) result)) {
						return true;
					}
				}
			}
		}

		return false;
	}

	private static Handler<RoutingContext> getHandler(final Object toInvoke, final RouteDefinition definition, final Method method) {

		return context -> context.vertx().executeBlocking(
			fut -> {
				try {
					Object[] args = ArgumentProvider.getArguments(method, definition, context, readers, providers, injectionProvider);
					validate(method, definition, validator, toInvoke, args);

					fut.complete(method.invoke(toInvoke, args));
				}
				catch (Throwable e) {
					fut.fail(e);
				}
			},
			definition.executeBlockingOrdered(), // false by default
			res -> {
				if (res.succeeded()) {
					try {
						Object result = res.result();
						Class returnType = result != null ? result.getClass() : definition.getReturnType();

						HttpResponseWriter writer = getWriter(injectionProvider,
						                                      returnType,
						                                      definition,
						                                      context);


						validateResult(result, method, definition, validator, toInvoke);
						produceResponse(result, context, definition, writer);
					}
					catch (Throwable e) {
						handleException(e, context, definition);
					}
				} else {
					handleException(res.cause(), context, definition);
				}
			}
		);
	}

	private static Handler<RoutingContext> getAsyncHandler(final Object toInvoke, final RouteDefinition definition, final Method method) {

		return context -> {

			try {
				Object[] args = ArgumentProvider.getArguments(method, definition, context, readers, providers, injectionProvider);
				validate(method, definition, validator, toInvoke, args);

				Object result = method.invoke(toInvoke, args);

				if (result instanceof Future) {
					Future<?> fut = (Future) result;

					// wait for future to complete ... don't block vertx event bus in the mean time
					fut.setHandler(handler -> {

						if (fut.succeeded()) {

							try {
								Object futureResult = fut.result();

								HttpResponseWriter writer;
								if (futureResult != null) { // get writer from result type otherwise we don't know
									writer = getWriter(injectionProvider,
									                   futureResult.getClass(),
									                   definition,
									                   context);
								} else { // due to limitations of Java generics we can't tell the type if response is null
									Class<?> writerClass = definition.getWriter() == null ? GenericResponseWriter.class : definition.getWriter();
									writer = (HttpResponseWriter) WriterFactory.newInstanceOf(writerClass);
								}

								validateResult(futureResult, method, definition, validator, toInvoke);
								produceResponse(futureResult, context, definition, writer);
							}
							catch (Throwable e) {
								handleException(e, context, definition);
							}
						} else {
							handleException(fut.cause(), context, definition);
						}
					});
				}
			}
			catch (Throwable e) {
				handleException(e, context, definition);
			}
		};
	}

	private static void validate(Method method, RouteDefinition definition, Validator validator, Object toInvoke, Object[] args) {

		// check method params first (if any)
		if (validator != null && args != null) {
			ExecutableValidator executableValidator = validator.forExecutables();
			Set<ConstraintViolation<Object>> result = executableValidator.validateParameters(toInvoke, method, args);
			if (result != null && result.size() > 0) {
				throw new ConstraintException(definition, result);
			}
		}
	}

	private static void validateResult(Object result, Method method, RouteDefinition definition, Validator validator, Object toInvoke) {

		if (validator != null) {
			ExecutableValidator executableValidator = validator.forExecutables();
			Set<ConstraintViolation<Object>> validationResult = executableValidator.validateReturnValue(toInvoke, method, result);
			if (validationResult != null && validationResult.size() > 0) {
				throw new ConstraintException(definition, validationResult);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static Handler<RoutingContext> getNotFoundHandler(Object notFoundWriter) {
		return context -> {

			try {
				// fill up definition (response headers) from request
				RouteDefinition definition = new RouteDefinition(context);

				HttpResponseWriter writer;
				if (notFoundWriter instanceof Class) {
					writer = writers.getClassInstance((Class<? extends HttpResponseWriter>) notFoundWriter, injectionProvider, context);
				} else {
					writer = (HttpResponseWriter) notFoundWriter;
				}

				produceResponse(null, context, definition,  writer);
			}
			catch (Throwable e) {
				handleException(e, context, null);
			}
		};
	}

	private static void handleException(Throwable e, RoutingContext context, final RouteDefinition definition) {

		ExecuteException ex = getExecuteException(e);

		// get appropriate exception handler/writer ...
		ExceptionHandler handler;
		try {
			Class<? extends Throwable> clazz;
			if (ex.getCause() == null) {
				clazz = ex.getClass();
			} else {
				clazz = ex.getCause().getClass();
			}

			Class<? extends ExceptionHandler>[] exHandlers = null;
			if (definition != null) {
				exHandlers = definition.getExceptionHandlers();
			}

			handler = handlers.getExceptionHandler(clazz, exHandlers, injectionProvider, context);
		}
		catch (ClassFactoryException classException) {
			// Can't provide exception handler ... rethrow
			log.error("Can't provide exception handler!", classException);
			// fall back to generic ...
			handler = new GenericExceptionHandler();
			ex = new ExecuteException(500, classException);
		}
		catch (ContextException contextException) {
			// Can't provide @Context for handler ... rethrow
			log.error("Can't provide @Context!", contextException);
			// fall back to generic ...
			handler = new GenericExceptionHandler();
			ex = new ExecuteException(500, contextException);
		}

		if (handler instanceof GenericExceptionHandler) {
			log.error("Handling exception: ", e);
		}
		else {
			log.debug("Handling exception, with: " + handler.getClass().getName(), e);
		}

		HttpServerResponse response = context.response();
		response.setStatusCode(ex.getStatusCode());
		handler.addResponseHeaders(definition, response);

		try {
			handler.write(ex.getCause(), context.request(), context.response());

			eventExecutor.triggerEvents(ex.getCause(), response.getStatusCode(), definition, context, injectionProvider);
		}
		catch (Throwable handlerException) {
			// this should not happen
			log.error("Failed to write out handled exception: " + e.getMessage(), e);
		}

		// end response ...
		if (!response.ended()) {
			response.end();
		}
	}

	private static ExecuteException getExecuteException(Throwable e) {

		if (e instanceof ExecuteException) {
			ExecuteException ex = (ExecuteException) e;
			return new ExecuteException(ex.getStatusCode(), ex.getMessage(), ex);
		}

		// unwrap invoke exception ...
		if (e instanceof IllegalAccessException || e instanceof InvocationTargetException) {

			if (e.getCause() != null) {
				return getExecuteException(e.getCause());
			}
		}

		if (e instanceof IllegalArgumentException) {
			return new ExecuteException(400, e);
		}

		return new ExecuteException(500, e);
	}

	@SuppressWarnings("unchecked")
	private static void produceResponse(Object result,
	                                    RoutingContext context,
	                                    RouteDefinition definition,
	                                    HttpResponseWriter writer) throws Throwable {

		HttpServerResponse response = context.response();
		HttpServerRequest request = context.request();

		// add default response headers per definition (or from writer definition)
		writer.addResponseHeaders(definition, response);
		writer.write(result, request, response);

		// find and trigger events from // result / response
		eventExecutor.triggerEvents(result, response.getStatusCode(), definition, context, injectionProvider);

		// finish if not finished by writer
		// and is not an Async REST (Async RESTs must finish responses on their own)
		if (!definition.isAsync() && !response.ended()) {
			response.end();
		}
	}

	public static WriterFactory getWriters() {

		return writers;
	}

	public static ReaderFactory getReaders() {

		return readers;
	}

	public static ExceptionHandlerFactory getExceptionHandlers() {

		return handlers;
	}

	/**
	 * Registers a context provider for given type of class
	 *
	 * @param provider clazz type to be registered
	 */
	public static void addProvider(Class<? extends ContextProvider> provider) {

		Class clazz = (Class) ClassFactory.getGenericType(provider);
		addProvider(clazz, provider);
	}

	public static void addProvider(Class clazz, Class<? extends ContextProvider> provider) {

		Assert.notNull(clazz, "Missing provided class type!");
		Assert.notNull(provider, "Missing provider class type!!");

		providers.register(clazz, provider);
		log.info("Registering '" + clazz + "' provider '" + provider.getName() + "'");
	}

	public static void addProvider(ContextProvider provider) {

		Class clazz = (Class) ClassFactory.getGenericType(provider.getClass());
		addProvider(clazz, provider);
	}

	public static void addProvider(Class clazz, ContextProvider provider) {

		Assert.notNull(clazz, "Missing provider class type!");
		Assert.notNull(provider, "Missing provider instance!");

		providers.register(clazz, provider);
		log.info("Registering '" + clazz + "' provider '" + provider.getClass().getName() + "'");
	}

	public static ContextProviderFactory getContextProviders() {
		return providers;
	}

	static void pushContext(RoutingContext context, Object object) {

		Assert.notNull(context, "Missing context!");
		Assert.notNull(object, "Can't push null into context!");

		context.put(ContextProviderFactory.getContextKey(object), object);
	}

	/**
	 * Provide an injector to getInstance classes where needed
	 *
	 * @param provider to create/inject classes
	 */
	public static void injectWith(InjectionProvider provider) {

		injectionProvider = provider;
		if (injectionProvider != null) {
			log.info("Registered injection provider: " + injectionProvider.getClass().getName());
		}
		else {
			log.warn("No injection provider specified!");
		}
	}

	/**
	 * Provide an injector to getInstance classes where needed
	 *
	 * @param provider class type
	 */
	public static void injectWith(Class<InjectionProvider> provider) {

		try {
			injectionProvider = (InjectionProvider) ClassFactory.newInstanceOf(provider);
			log.info("Registered injection provider: " + injectionProvider.getClass().getName());
		}
		catch (ClassFactoryException e) {
			log.error("Failed to instantiate injection provider: ", e);
			throw new IllegalArgumentException(e);
		}
	}

	/**
	 * Provide an validator to validate arguments
	 *
	 * @param provider to validate
	 */
	public static void validateWith(Validator provider) {

		validator = provider;
		if (validator != null) {
			log.info("Registered validation provider: " + validator.getClass().getName());
		}
		else {
			log.warn("No validation provider specified!");
		}
	}

	/**
	 * Provide an validator to validate arguments
	 *
	 * @param provider class type
	 */
	public static void validateWith(Class<Validator> provider) {

		try {
			validator = (Validator) ClassFactory.newInstanceOf(provider);
			log.info("Registered validation provider: " + validator.getClass().getName());
		}
		catch (ClassFactoryException e) {
			log.error("Failed to instantiate validation provider: ", e);
			throw new IllegalArgumentException(e);
		}
	}
}
