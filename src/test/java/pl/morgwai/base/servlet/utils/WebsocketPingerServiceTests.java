// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.websocket.*;
import javax.websocket.CloseReason.CloseCode;
import javax.websocket.CloseReason.CloseCodes;

import org.eclipse.jetty.websocket.javax.client.JavaxWebSocketClientContainerProvider;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketContainer;
import org.junit.*;
import pl.morgwai.base.servlet.utils.WebsocketPingerService.PingPongPlayer;
import pl.morgwai.base.servlet.utils.tests.TestServer;

import static org.junit.Assert.*;



public class WebsocketPingerServiceTests {



	org.eclipse.jetty.client.HttpClient wsHttpClient;
	WebSocketContainer clientWebsocketContainer;
	HttpClient httpClient;
	TestServer server;
	int port;
	String websocketUrl;



	@Before
	public void setup() throws Exception {
		final var cookieManager = new CookieManager();
		wsHttpClient = new org.eclipse.jetty.client.HttpClient();
		wsHttpClient.setCookieStore(cookieManager.getCookieStore());
		clientWebsocketContainer = JavaxWebSocketClientContainerProvider.getContainer(wsHttpClient);
		httpClient = HttpClient.newBuilder().cookieHandler(cookieManager).build();
		server = new TestServer(0);
		server.start();
		port = ((ServerSocketChannel) server.getConnectors()[0].getTransport())
				.socket().getLocalPort();
		websocketUrl = "ws://localhost:" + port + TestServer.APP_PATH;
	}



	@After
	public void shutdown() throws Exception {
		final var jettyWsContainer = ((JavaxWebSocketContainer) clientWebsocketContainer);
		jettyWsContainer.stop();
		jettyWsContainer.destroy();
		wsHttpClient.stop();
		wsHttpClient.destroy();
		server.stop();
		server.join();
		server.destroy();
	}



	static final ConcurrentMap<URI, CountDownLatch> serverEndpointCreated =
			new ConcurrentHashMap<>();
	static final ConcurrentMap<URI, ServerEndpoint> serverEndpointInstance =
			new ConcurrentHashMap<>();
	static final ConcurrentMap<URI, CountDownLatch> serverEndpointClosed =
			new ConcurrentHashMap<>();



	public static abstract class BaseEndpoint extends Endpoint {

		protected Session connection;
		protected String id;
		final List<Throwable> errors = new LinkedList<>();
		protected CountDownLatch closed = new CountDownLatch(1);

		@Override
		public void onOpen(Session connection, EndpointConfig config) {
			this.connection = connection;
		}

		@Override
		public void onClose(Session session, CloseReason closeReason) {
			log.fine(id + " got onClose(...), reason: " + closeReason.getCloseCode());
			closed.countDown();
		}

		@Override
		public void onError(Session session, Throwable error) {
			System.err.println(id + ": " + error);
			errors.add(error);
		}
	}



	public static class ServerEndpoint extends BaseEndpoint {

		@Override
		public void onOpen(Session connection, EndpointConfig config) {
			super.onOpen(connection, config);
			final var url = connection.getRequestURI().toString();
			this.id = "server " + url.substring(url.lastIndexOf('/'));
			log.fine(id + " got onOpen(...)");
			serverEndpointInstance.put(connection.getRequestURI(), this);
			serverEndpointCreated.get(connection.getRequestURI()).countDown();
		}
	}



	public static class ClientEndpoint extends BaseEndpoint {

		CloseCode closeCode;

		public ClientEndpoint(String path) {
			this.id = "client " + path;
		}

		@Override
		public void onOpen(Session connection, EndpointConfig config) {
			super.onOpen(connection, config);
			log.fine(id + " got onOpen(...)");
		}

		@Override
		public void onClose(Session session, CloseReason closeReason) {
			closeCode = closeReason.getCloseCode();
			super.onClose(session, closeReason);
		}
	}



	public void test(
		String path,
		boolean closeClientConnection,
		CloseCode expectedClientCloseCode,
		BiConsumer<ServerEndpoint, ClientEndpoint> test
	) throws Throwable {
		server.addEndpoint(ServerEndpoint.class, path);
		final var clientEndpoint = new ClientEndpoint(path);
		final var url = URI.create(websocketUrl + path);
		serverEndpointCreated.put(url, new CountDownLatch(1));
		serverEndpointClosed.put(url, new CountDownLatch(1));
		final ServerEndpoint serverEndpoint;
		final var clientConnection = clientWebsocketContainer.connectToServer(
				clientEndpoint, null, url);
		try {
			if ( !serverEndpointCreated.get(url).await(100L, TimeUnit.MILLISECONDS)) {
				fail("ServerEndpoint should be created");
			}
			serverEndpoint = serverEndpointInstance.get(url);
			test.accept(serverEndpoint, clientEndpoint);
		} finally {
			if (closeClientConnection) clientConnection.close();
		}
		if ( !clientEndpoint.closed.await(100L, TimeUnit.MILLISECONDS)) {
			fail("ClientEndpoint should be closed");
		}
		if ( !serverEndpoint.closed.await(100L, TimeUnit.MILLISECONDS)) {
			fail("ServerEndpoint should be closed");
		}
		assertTrue("ServerEndpoint should not receive any onError(...) calls",
				serverEndpoint.errors.isEmpty());
		assertTrue("ClientEndpoint should not receive any onError(...) calls",
				clientEndpoint.errors.isEmpty());
		assertEquals("client close code should match",
				expectedClientCloseCode.getCode(), clientEndpoint.closeCode.getCode());
	}



	@Test
	public void testKeepAlive() throws Throwable {
		final var PATH = "/testKeepAlive";
		test(PATH, true, CloseCodes.NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
			final var pongReceived = new CountDownLatch(1);
			clientEndpoint.connection.addMessageHandler(PongMessage.class, (pong) -> {
				log.fine("client " + PATH + " got pong");
				pongReceived.countDown();
			});
			final var pingPongPlayer = new PingPongPlayer(serverEndpoint.connection, false);

			pingPongPlayer.sendKeepAlive();
			try {
				if ( !pongReceived.await(100L, TimeUnit.MILLISECONDS)) {
					fail("pong should be received");
				}
			} catch (InterruptedException e) {
				fail("pong should be received");
			}
			assertEquals("failure count should still be 0", 0, pingPongPlayer.failureCount);
			assertFalse("player should not be awaiting for pong", pingPongPlayer.awaitingPong);
		});
	}



	@Test
	public void testPingPong() throws Throwable {
		final var PATH = "/testPingPong";
		test(PATH, true, CloseCodes.NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
			final var pongReceived = new CountDownLatch(1);
			final var pingPongPlayer = new PingPongPlayer(serverEndpoint.connection, 2, false);
			serverEndpoint.connection.removeMessageHandler(pingPongPlayer);
			serverEndpoint.connection.addMessageHandler(PongMessage.class, (pong) -> {
				log.fine("server " + PATH + " got pong, forwarding");
				pingPongPlayer.onMessage(pong);
				pongReceived.countDown();
			});
			pingPongPlayer.failureCount = 1;

			pingPongPlayer.sendPing("testPingData".getBytes(StandardCharsets.UTF_8));
			try {
				if ( !pongReceived.await(100L, TimeUnit.MILLISECONDS)) {
					fail("pong should be received");
				}
			} catch (InterruptedException e) {
				fail("pong should be received");
			}
			assertEquals("failure count should be reset", 0, pingPongPlayer.failureCount);
			assertFalse("player should not be awaiting pong anymore", pingPongPlayer.awaitingPong);
		});
	}



	@Test
	public void testMalformedPong() throws Throwable {
		final var PATH = "/testMalformedPong";
		test(PATH, true, CloseCodes.NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
			final var pongReceived = new CountDownLatch(1);
			final var pingPongPlayer = new PingPongPlayer(serverEndpoint.connection, 2, false);
			serverEndpoint.connection.removeMessageHandler(pingPongPlayer);
			serverEndpoint.connection.addMessageHandler(PongMessage.class, (pong) -> {
				log.fine("server " + PATH + " got pong, perverting");
				final var someOtherData =
						ByteBuffer.wrap("someOtherData".getBytes(StandardCharsets.UTF_8));
				pingPongPlayer.onMessage(() -> someOtherData);
				pongReceived.countDown();
			});

			pingPongPlayer.sendPing("originalPingData".getBytes(StandardCharsets.UTF_8));
			try {
				if ( !pongReceived.await(100L, TimeUnit.MILLISECONDS)) {
					fail("pong should be received");
				}
			} catch (InterruptedException e) {
				fail("pong should be received");
			}
			assertEquals("failure count should be increased", 1, pingPongPlayer.failureCount);
			assertFalse("player should not be awaiting pong anymore", pingPongPlayer.awaitingPong);
		});
	}



	@Test
	public void testTimedOutPong() throws Throwable {
		final var PATH = "/testTimedOutPong";
		test(PATH, true, CloseCodes.NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
			final var pingPongPlayer = new PingPongPlayer(serverEndpoint.connection, 2, false);
			serverEndpoint.connection.removeMessageHandler(pingPongPlayer);

			pingPongPlayer.sendPing("firstPingData".getBytes(StandardCharsets.UTF_8));
			assertTrue("player should be still awaiting for pong", pingPongPlayer.awaitingPong);
			assertEquals("failure count should still be 0", 0, pingPongPlayer.failureCount);

			pingPongPlayer.sendPing("secondPingData".getBytes(StandardCharsets.UTF_8));
			assertTrue("player should be still awaiting for pong", pingPongPlayer.awaitingPong);
			assertEquals("failure count should be increased", 1, pingPongPlayer.failureCount);
		});
	}



	@Test
	public void testFailureLimitExceeded() throws Throwable {
		final var PATH = "/testFailureLimitExceeded";
		test(PATH, false, CloseCodes.PROTOCOL_ERROR, (serverEndpoint, clientEndpoint) -> {
			final var pongReceived = new CountDownLatch(1);
			final var pingPongPlayer = new PingPongPlayer(serverEndpoint.connection, 0, false);
			serverEndpoint.connection.removeMessageHandler(pingPongPlayer);
			serverEndpoint.connection.addMessageHandler(PongMessage.class, (pong) -> {
				log.fine("server " + PATH + " got pong, perverting");
				final var someOtherData =
						ByteBuffer.wrap("someOtherData".getBytes(StandardCharsets.UTF_8));
				pingPongPlayer.onMessage(() -> someOtherData);
				pongReceived.countDown();
			});

			pingPongPlayer.sendPing("originalPingData".getBytes(StandardCharsets.UTF_8));
			try {
				if ( !pongReceived.await(100L, TimeUnit.MILLISECONDS)) {
					fail("pong should be received");
				}
			} catch (InterruptedException e) {
				fail("pong should be received");
			}
			assertEquals("failure count should be increased", 1, pingPongPlayer.failureCount);
			assertFalse("player should not be awaiting pong anymore", pingPongPlayer.awaitingPong);
		});
	}



	@Test
	public void testServiceKeepAliveRate() throws Throwable {
		final var PATH = "/testServiceKeepAliveRate";
		final int EXPECTED_PONGS = 3;
		final var service = new WebsocketPingerService(1, false);
		try {
			test(PATH, true, CloseCodes.NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
				final var pongsReceived = new CountDownLatch(EXPECTED_PONGS);
				clientEndpoint.connection.addMessageHandler(PongMessage.class, (pong) -> {
					log.fine("client " + PATH + " got pong");
					pongsReceived.countDown();
				});
				service.addConnection(serverEndpoint.connection);
				try {
					if (
						!pongsReceived.await(1000L * (EXPECTED_PONGS + 1), TimeUnit.MILLISECONDS)
					) {
						fail("at least " + EXPECTED_PONGS + " pongs should be received");
					}
				} catch (InterruptedException e) {
					fail("at least " + EXPECTED_PONGS + " pongs should be received");
				} finally {
					service.removeConnection(serverEndpoint.connection);
				}
				assertEquals("there should be no registered connection after removing",
						0, service.getNumberOfConnections());
			});
		} finally {
			assertTrue("there should be no registered connection after removing",
					service.stop().isEmpty());
		}
	}



	/** {@code FINE} will log all endpoint lifecycle method calls. */
	static Level LOG_LEVEL = Level.WARNING;

	static final Logger log = Logger.getLogger(WebsocketPingerServiceTests.class.getName());

	@BeforeClass
	public static void setupLogging() {
		try {
			LOG_LEVEL = Level.parse(System.getProperty(
				WebsocketPingerServiceTests.class.getPackageName() + ".level"));
		} catch (Exception ignored) {}
		log.setLevel(LOG_LEVEL);
		for (final var handler: Logger.getLogger("").getHandlers()) handler.setLevel(LOG_LEVEL);
	}
}
