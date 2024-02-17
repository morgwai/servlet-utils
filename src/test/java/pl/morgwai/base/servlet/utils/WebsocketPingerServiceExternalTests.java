// Copyright 2024 Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils;

import java.net.CookieManager;
import java.util.Map;
import java.util.logging.ConsoleHandler;

import jakarta.websocket.WebSocketContainer;

import org.eclipse.jetty.websocket.jakarta.client.JakartaWebSocketClientContainerProvider;
import org.eclipse.jetty.websocket.jakarta.common.JakartaWebSocketContainer;
import org.junit.*;
import pl.morgwai.base.jul.JulFormatter;

import static java.util.logging.Level.FINEST;
import static java.util.logging.Level.WARNING;

import static pl.morgwai.base.jul.JulConfigurator.*;
import static pl.morgwai.base.jul.JulFormatter.FORMATTER_SUFFIX;
import static pl.morgwai.base.servlet.utils.WebsocketPingerServiceTests
		.testPingingDoesNotExceedDurationLimit;



public class WebsocketPingerServiceExternalTests {



	org.eclipse.jetty.client.HttpClient wsHttpClient;
	WebSocketContainer clientContainer;



	@Before
	public void setup() {
		final var cookieManager = new CookieManager();
		wsHttpClient = new org.eclipse.jetty.client.HttpClient();
		wsHttpClient.setCookieStore(cookieManager.getCookieStore());
		wsHttpClient.setConnectTimeout(5000L);
		clientContainer = JakartaWebSocketClientContainerProvider.getContainer(wsHttpClient);
	}

	@After
	public void shutdown() throws Exception {
		final var jettyWsContainer = ((JakartaWebSocketContainer) clientContainer);
		jettyWsContainer.stop();
		jettyWsContainer.destroy();
		wsHttpClient.stop();
		wsHttpClient.destroy();
	}



	// Run this test manually only to not abuse external services.
//	@Test
	public void testPingingExternalConnectionsDoesNotExceedDurationLimit() throws Exception {
		final int NUM_CONNECTIONS = 250;
		testPingingDoesNotExceedDurationLimit(
			clientContainer,
			100L,
			NUM_CONNECTIONS,
			"wss://echo.websocket.org/",
			"wss://ws.postman-echo.com/raw",
			"wss://demo.piesocket.com/v3/channel_123?api_key=XXX"
					// get your key at https://piehost.com/websocket-tester
		);
	}



	@BeforeClass
	public static void setupLogging() {
		addOrReplaceLoggingConfigProperties(Map.of(
			LEVEL_SUFFIX, WARNING.toString(),
			ConsoleHandler.class.getName() + FORMATTER_SUFFIX, JulFormatter.class.getName(),
			ConsoleHandler.class.getName() + LEVEL_SUFFIX, FINEST.toString()
		));
		overrideLogLevelsWithSystemProperties("pl.morgwai");
	}
}
