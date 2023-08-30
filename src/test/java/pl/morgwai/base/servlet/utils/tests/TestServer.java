// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils.tests;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;



public class TestServer extends org.eclipse.jetty.server.Server {



	public static final String APP_PATH = "/test";

	ServerContainer endpointContainer;
	final CountDownLatch serverStarted = new CountDownLatch(1);



	public TestServer(int port) {
		super(port);
		final var appHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
		appHandler.setContextPath(APP_PATH);
		appHandler.addEventListener(new ServletContextListener() {
			@Override public void contextInitialized(ServletContextEvent initializationEvent) {
				endpointContainer = ((ServerContainer)
						initializationEvent.getServletContext().getAttribute(
								"jakarta.websocket.server.ServerContainer"));
				serverStarted.countDown();
			}
		});
		setHandler(appHandler);
		JakartaWebSocketServletContainerInitializer.configure(
			appHandler,
			(servletContainer, websocketContainer) ->
					websocketContainer.setDefaultMaxTextMessageBufferSize(1023)
		);
	}



	public void addEndpoint(Class<? extends Endpoint> endpointClass, String path)
			throws DeploymentException, InterruptedException {
		if ( !serverStarted.await(500L, TimeUnit.MILLISECONDS)) {
			throw new DeploymentException("the server failed to start");
		}
		endpointContainer.addEndpoint(
			ServerEndpointConfig.Builder
				.create(endpointClass, path)
				.build()
		);
	}
}
