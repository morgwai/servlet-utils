// Copyright 2023 Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils.tests;

import java.util.Arrays;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.websocket.Endpoint;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer;



public class JettyServer extends org.eclipse.jetty.server.Server implements WebsocketServer {



	ServerContainer endpointContainer;



	public JettyServer() {
		super(0);
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
	}



	@Override
	public void startAndAddEndpoint(Class<? extends Endpoint> endpointClass, String path)
			throws Exception {
		start();
		endpointContainer.addEndpoint(
			ServerEndpointConfig.Builder
				.create(endpointClass, path)
				.build()
		);
	}



	@Override
	public int getPort() {
		return Arrays.stream(getConnectors())
			.filter(NetworkConnector.class::isInstance)
			.findFirst()
			.map(NetworkConnector.class::cast)
			.map(NetworkConnector::getLocalPort)
			.orElseThrow();
	}



	@Override
	public void shutdown() throws Exception {
		stop();
		join();
		destroy();
	}
}
