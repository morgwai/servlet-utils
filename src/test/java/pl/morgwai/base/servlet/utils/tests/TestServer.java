// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils.tests;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer;



public class TestServer extends org.eclipse.jetty.server.Server {



	public static final String APP_PATH = "/test";

	ServerContainer endpointContainer;



	public TestServer(int port) throws Exception {
		super(port);
		final var appHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
		appHandler.setContextPath(APP_PATH);
		appHandler.addEventListener(new ServletContextListener() {
			@Override public void contextInitialized(ServletContextEvent initializationEvent) {
				endpointContainer = ((ServerContainer)
						initializationEvent.getServletContext().getAttribute(
								ServerContainer.class.getName()));
			}
		});
		setHandler(appHandler);
		JavaxWebSocketServletContainerInitializer.configure(
			appHandler,
			(servletContainer, websocketContainer) ->
					websocketContainer.setDefaultMaxTextMessageBufferSize(1023)
		);
		start();
	}



	public void addEndpoint(Class<? extends Endpoint> endpointClass, String path)
			throws DeploymentException {
		endpointContainer.addEndpoint(
			ServerEndpointConfig.Builder
				.create(endpointClass, path)
				.build()
		);
	}
}
