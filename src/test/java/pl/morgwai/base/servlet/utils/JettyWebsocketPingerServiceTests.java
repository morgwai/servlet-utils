// Copyright 2023 Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils;

import pl.morgwai.base.servlet.utils.tests.JettyServer;
import pl.morgwai.base.servlet.utils.tests.WebsocketServer;



public class JettyWebsocketPingerServiceTests extends WebsocketPingerServiceTests {



	@Override
	protected WebsocketServer createServer() {
		return new JettyServer();
	}
}
