package com.zandero.rest;

import com.zandero.rest.injection.InjectionProvider;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxTestContext;
import org.junit.Ignore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;

import javax.validation.Validator;

/**
 *
 */
@Ignore
public class VertxTest {

    public static final String API_ROOT = "/";

    protected static final int PORT = 4444;

    public static final String HOST = "localhost";

    protected static final String ROOT_PATH = "http://" + HOST + ":" + PORT;

    protected static Vertx vertx = null;
    protected static VertxTestContext VertxTestContext;
    protected static WebClient client;

    public static void before() {

        vertx = Vertx.vertx();
        VertxTestContext = new VertxTestContext();

        // clear all registered writers or reader and handlers
        RestRouter.getReaders().clear();
        RestRouter.getWriters().clear();
        RestRouter.getExceptionHandlers().clear();
        RestRouter.getContextProviders().clear();

        // clear
        RestRouter.validateWith((Validator) null);
        RestRouter.injectWith((InjectionProvider) null);

        client = WebClient.create(vertx);
    }

    @AfterEach
    void lastChecks(Vertx vertx) {
        vertx.close(VertxTestContext.succeeding());
    }

    @AfterAll
    static void close() {
        vertx.close();
    }
}
