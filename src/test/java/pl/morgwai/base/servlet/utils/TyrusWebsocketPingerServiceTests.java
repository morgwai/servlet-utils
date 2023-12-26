// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils;

import jakarta.websocket.Endpoint;

import pl.morgwai.base.servlet.utils.tests.TyrusServer;
import pl.morgwai.base.servlet.utils.tests.WebsocketServer;



public class TyrusWebsocketPingerServiceTests extends WebsocketPingerServiceTests {



	@Override
	protected WebsocketServer createServer() {
		return new TyrusServer();
	}



	@Override
	protected long getAllowedRttInaccuracyNanos() {
		// Tyrus-2.0.x does some weird stuff that introduces a delay during the test execution
		return Endpoint.class.getPackageName().contains("javax") ? 1_000_000L : 2_000_000;
	}
}
