// Copyright 2023 Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils.tests;

import javax.websocket.Endpoint;



/** Jetty or Tyrus */
public interface WebsocketServer {

	String APP_PATH = "/test";

	void startAndAddEndpoint(Class<? extends Endpoint> endpointClass, String path) throws Exception;
	int getPort();
	void shutdown() throws Exception;
}
