// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils.tests;

import javax.websocket.Endpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.glassfish.tyrus.server.TyrusServerContainer;
import org.glassfish.tyrus.spi.ServerContainerFactory;



public class TyrusServer implements WebsocketServer {



	final TyrusServerContainer tyrus;
	boolean started = false;



	public TyrusServer() {
		tyrus = (TyrusServerContainer) ServerContainerFactory.createServerContainer();
	}



	@Override
	public void startAndAddEndpoint(Class<? extends Endpoint> endpointClass, String path)
			throws Exception {
		tyrus.addEndpoint(
			ServerEndpointConfig.Builder
				.create(endpointClass, path)
				.build()
		);
		tyrus.start(APP_PATH, 0);
		started = true;
	}



	@Override
	public int getPort() {
		return tyrus.getPort();
	}



	@Override
	public void stopz() {
		if (started) tyrus.stop();
	}
}
