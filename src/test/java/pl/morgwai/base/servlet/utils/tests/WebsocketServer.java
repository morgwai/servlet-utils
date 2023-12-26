// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils.tests;

import jakarta.websocket.Endpoint;



/** Jetty or Tyrus */
public interface WebsocketServer {

	String APP_PATH = "/test";
	void startAndAddEndpoint(Class<? extends Endpoint> endpointClass, String path) throws Exception;
	int getPort();
	void stopz() throws Exception;
}
