package com.zandero.rest.events;

import com.zandero.rest.annotation.Event;
import com.zandero.rest.data.ClassFactory;
import com.zandero.rest.data.RouteDefinition;
import com.zandero.rest.exception.ClassFactoryException;
import com.zandero.rest.exception.ContextException;
import com.zandero.rest.injection.InjectionProvider;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;

/**
 *
 */
public class RestEventExecutor {

	private final static Logger log = LoggerFactory.getLogger(RestEventExecutor.class);

	public RestEventExecutor() {
	}

	/**
	 * Matches events to result / response
	 *
	 * @param result       of method either returned class or exception
	 * @param responseCode produced by writer
	 * @param definition route definition
	 * @param context routing context
	 * @param injectionProvider injection provider to instantiate event processor
	 * @throws Throwable exception in case triggered event execution failed
	 */
	public void triggerEvents(Object result,
	                          int responseCode,
	                          RouteDefinition definition,
	                          RoutingContext context,
	                          InjectionProvider injectionProvider) throws Throwable {

		if (definition.getEvents() == null) {
			return;
		}

		for (Event event : definition.getEvents()) {
			// status code match OR all
			if (event.response() == responseCode ||
			    (event.response() == Event.DEFAULT_EVENT_STATUS && responseCode >= 200 && responseCode < 300) ||
			    (event.response() == Event.DEFAULT_EVENT_STATUS && result instanceof Throwable)) {

				// type fit
				boolean trigger = shouldTriggerEvent(event, result);

				if (trigger) {
					try {
						Class<? extends RestEvent> processor = event.value();
						RestEvent instance = (RestEvent) ClassFactory.newInstanceOf(processor, injectionProvider, context);

						log.debug("Triggering event: " + event.value());
						instance.execute(result, context);
					}
					catch (ClassFactoryException | ContextException e) {
						log.error("Failed to provide RestEvent for: " + definition + " ", e);
					}
				}
			}
		}
	}

	static boolean shouldTriggerEvent(Event event, Object result) {

		if (result == null) {
			return false;
		}

		if (event.exception() == NoRestException.class) {
			Type type = ClassFactory.getGenericType(event.value());
			return ClassFactory.checkIfCompatibleTypes(result.getClass(), type);
		}

		return ClassFactory.checkIfCompatibleTypes(result.getClass(), event.exception());
	}

}
