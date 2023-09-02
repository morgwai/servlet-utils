// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils;

import java.io.IOException;
import java.lang.reflect.*;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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



	/**
	 * A static point of synchronization between a given test method and the corresponding
	 * container-created {@link ServerEndpoint} instance. As each test method deploys its
	 * {@link ServerEndpoint} at a different URI/path, tests may safely run in parallel.
	 */
	static final ConcurrentMap<URI, CountDownLatch> serverEndpointCreated =
			new ConcurrentHashMap<>();

	/**
	 * A static place for a given test method to find its corresponding container-created
	 * {@link ServerEndpoint} instance.
	 * @see #serverEndpointCreated
	 */
	static final ConcurrentMap<URI, ServerEndpoint> serverEndpointInstance =
			new ConcurrentHashMap<>();



	public static abstract class BaseEndpoint extends Endpoint {

		protected Session connection;

		/** For logging/debugging: {@code ("client " | "server ") + PATH}. */
		protected String id;

		/** {@link #onError(Session, Throwable)} stores received errors here. */
		protected final List<Throwable> errors = new LinkedList<>();

		/** Switched in {@link #onClose(Session, CloseReason)}. */
		protected CountDownLatch closed = new CountDownLatch(1);
		/** Set in {@link #onClose(Session, CloseReason)}. */
		protected CloseCode closeCode;



		@Override
		public void onOpen(Session connection, EndpointConfig config) {
			this.connection = connection;
			final var url = connection.getRequestURI().toString();
			this.id = (isServer() ? "server " : "client ") + url.substring(url.lastIndexOf('/'));
			log.fine(id + " got onOpen(...)");
		}

		protected abstract boolean isServer();



		@Override
		public void onClose(Session session, CloseReason closeReason) {
			log.fine(id + " got onClose(...), reason: " + closeReason.getCloseCode());
			closeCode = closeReason.getCloseCode();
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
		protected boolean isServer() {
			return true;
		}



		/**
		 * Stores itself into {@link #serverEndpointInstance} and switches its
		 * {@link #serverEndpointCreated}.
		 */
		@Override
		public void onOpen(Session connection, EndpointConfig config) {
			super.onOpen(connection, config);
			serverEndpointInstance.put(connection.getRequestURI(), this);
			serverEndpointCreated.get(connection.getRequestURI()).countDown();
		}
	}



	public static class ClientEndpoint extends BaseEndpoint {

		@Override
		protected boolean isServer() {
			return false;
		}
	}



	/**
	 * Performs {@code test} over a websocket connection.
	 * <ol>
	 *   <li>establishes a connection between a {@link ClientEndpoint} and {@link #server}</li>
	 *   <li>calls {@code test}</li>
	 *   <li>depending on the value of {@code closeClientConnection} closes the connections from the
	 *       client side</li>
	 *    <li>regardless of {@code closeClientConnection}, verifies if the connection was closed on
	 *        both endpoints with {@code expectedClientCloseCode} on the client side</li>
	 *    <li>verifies if there were no calls to {@link Endpoint#onError(Session, Throwable)} on
	 *        either endpoint</li>
	 * </ol>
	 */
	public void performTest(
		String path,
		boolean closeClientConnection,
		CloseCode expectedClientCloseCode,
		BiConsumer<ServerEndpoint, ClientEndpoint> test
	) throws Exception {
		server.addEndpoint(ServerEndpoint.class, path);
		final var clientEndpoint = new ClientEndpoint();
		final var url = URI.create(websocketUrl + path);
		serverEndpointCreated.put(url, new CountDownLatch(1));
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
		assertEquals("client close code should match",
				expectedClientCloseCode.getCode(), clientEndpoint.closeCode.getCode());
		assertTrue("ServerEndpoint should not receive any onError(...) calls",
				serverEndpoint.errors.isEmpty());
		assertTrue("ClientEndpoint should not receive any onError(...) calls",
				clientEndpoint.errors.isEmpty());
	}



	@Test
	public void testKeepAlive() throws Exception {
		final var PATH = "/testKeepAlive";
		performTest(PATH, true, CloseCodes.NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
			final var pongReceived = new CountDownLatch(1);
			final var pingPongPlayer = new PingPongPlayer(serverEndpoint.connection, -1, false);
			serverEndpoint.connection.removeMessageHandler(pingPongPlayer);
			serverEndpoint.connection.addMessageHandler(PongMessage.class, (pong) -> {
				log.fine("server " + PATH + " got pong");
				final var someOtherData =
						ByteBuffer.wrap("someOtherData".getBytes(StandardCharsets.UTF_8));
				pingPongPlayer.onMessage(() -> someOtherData);
				pongReceived.countDown();
			});

			pingPongPlayer.sendPing(new byte[]{69});
			try {
				if ( !pongReceived.await(100L, TimeUnit.MILLISECONDS)) {
					fail("pong should be received");
				}
			} catch (InterruptedException e) {
				fail("test interrupted");
			}
			assertEquals("failure count should not increase", 0, pingPongPlayer.failureCount);
			assertFalse("pingPongPlayer should not be awaiting for pong",
					pingPongPlayer.awaitingPong);
		});
	}



	@Test
	public void testPingPong() throws Exception {
		final var PATH = "/testPingPong";
		performTest(PATH, true, CloseCodes.NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
			final var postPingVerificationsDone = new CountDownLatch(1);
			final var pongReceived = new CountDownLatch(1);
			final var pingPongPlayer = new PingPongPlayer(serverEndpoint.connection, 2, false);
			serverEndpoint.connection.removeMessageHandler(pingPongPlayer);
			serverEndpoint.connection.addMessageHandler(PongMessage.class, (pong) -> {
				log.fine("server " + PATH + " got pong, forwarding");
				try {
					if ( !postPingVerificationsDone.await(100L, TimeUnit.MILLISECONDS)) {
						fail("post ping verifications should take just few ms");
					}
				} catch (InterruptedException ignored) {}
				pingPongPlayer.onMessage(pong);
				pongReceived.countDown();
			});
			pingPongPlayer.failureCount = 1;

			pingPongPlayer.sendPing("testPingData".getBytes(StandardCharsets.UTF_8));
			try {
				assertTrue("pingPongPlayer should be awaiting for pong",
						pingPongPlayer.awaitingPong);
			} finally {
				postPingVerificationsDone.countDown();
			}
			try {
				if ( !pongReceived.await(100L, TimeUnit.MILLISECONDS)) {
					fail("pong should be received");
				}
			} catch (InterruptedException e) {
				fail("test interrupted");
			}
			assertEquals("failure count should be reset", 0, pingPongPlayer.failureCount);
			assertFalse("pingPongPlayer should not be awaiting for pong anymore",
					pingPongPlayer.awaitingPong);
		});
	}



	@Test
	public void testKeepAlivePongFromClient() throws Exception {
		final var PATH = "/testKeepAlivePongFromClient";
		performTest(PATH, true, CloseCodes.NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
			final var pongReceived = new CountDownLatch(1);
			final var pingPongPlayer = new PingPongPlayer(serverEndpoint.connection, 2, false);
			serverEndpoint.connection.removeMessageHandler(pingPongPlayer);
			serverEndpoint.connection.addMessageHandler(PongMessage.class, (pong) -> {
				log.fine("server " + PATH + " got pong, forwarding");
				pingPongPlayer.onMessage(pong);
				pongReceived.countDown();
			});
			final var pongData = ByteBuffer.wrap("keepAlive".getBytes(StandardCharsets.UTF_8));

			try {
				clientEndpoint.connection.getAsyncRemote().sendPong(pongData);
				if ( !pongReceived.await(100L, TimeUnit.MILLISECONDS)) {
					fail("pong should be received");
				}
			} catch (InterruptedException e) {
				fail("test interrupted");
			} catch (IOException e) {
				fail("unexpected connection problem " + e);
			}
			assertEquals("failure count should not increase", 0, pingPongPlayer.failureCount);
			assertFalse("pingPongPlayer should not be awaiting for pong",
					pingPongPlayer.awaitingPong);
		});
	}



	@Test
	public void testMalformedPong() throws Exception {
		final var PATH = "/testMalformedPong";
		performTest(PATH, true, CloseCodes.NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
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
				fail("test interrupted");
			}
			assertEquals("failure count should be increased", 1, pingPongPlayer.failureCount);
			assertFalse("pingPongPlayer should not be awaiting for pong anymore",
					pingPongPlayer.awaitingPong);
		});
	}



	@Test
	public void testTimedOutPong() throws Exception {
		final var PATH = "/testTimedOutPong";
		performTest(PATH, true, CloseCodes.NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
			final var pingPongPlayer = new PingPongPlayer(serverEndpoint.connection, 2, false);
			serverEndpoint.connection.removeMessageHandler(pingPongPlayer);

			pingPongPlayer.sendPing("firstPingData".getBytes(StandardCharsets.UTF_8));
			assertTrue("pingPongPlayer should be still awaiting for pong",
					pingPongPlayer.awaitingPong);
			assertEquals("failure count should still be 0", 0, pingPongPlayer.failureCount);

			pingPongPlayer.sendPing("secondPingData".getBytes(StandardCharsets.UTF_8));
			assertTrue("pingPongPlayer should be still awaiting for pong",
					pingPongPlayer.awaitingPong);
			assertEquals("failure count should be increased", 1, pingPongPlayer.failureCount);
		});
	}



	@Test
	public void testFailureLimitExceeded() throws Exception {
		final var PATH = "/testFailureLimitExceeded";
		performTest(PATH, false, CloseCodes.PROTOCOL_ERROR, (serverEndpoint, clientEndpoint) -> {
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
				fail("test interrupted");
			}
			assertEquals("failure count should be increased", 1, pingPongPlayer.failureCount);
			assertFalse("pingPongPlayer should not be awaiting for pong anymore",
					pingPongPlayer.awaitingPong);
		});
	}



	@Test
	public void testServiceKeepAliveRate() throws Exception {
		final var PATH = "/testServiceKeepAliveRate";
		final int NUM_EXPECTED_PONGS = 3;
		final var service = new WebsocketPingerService(1, false);
		try {
			performTest(PATH, true, CloseCodes.NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
				final var pongCounter = new AtomicInteger(0);
				assertEquals("there should be no registered connection initially",
						0, service.getNumberOfConnections());
				service.addConnection(serverEndpoint.connection);
				final var pingPongPlayer = serverEndpoint.connection.getMessageHandlers().stream()
					.filter(PingPongPlayer.class::isInstance)
					.map(PingPongPlayer.class::cast)
					.findFirst()
					.orElseThrow();
				final var decoratedHandler = new MessageHandler.Whole<PongMessage>() {
					@Override public void onMessage(PongMessage pong) {
						log.fine("server " + PATH + " got pong");
						pongCounter.incrementAndGet();
						pingPongPlayer.onMessage(pong);
					}
				};
				serverEndpoint.connection.removeMessageHandler(pingPongPlayer);
				serverEndpoint.connection.addMessageHandler(decoratedHandler);
				try {
					assertEquals("there should be 1 registered connection after adding",
							1, service.getNumberOfConnections());
					Thread.sleep(1000L * NUM_EXPECTED_PONGS);
				} catch (InterruptedException e) {
					fail("test interrupted");
				} finally {
					serverEndpoint.connection.removeMessageHandler(decoratedHandler);
					serverEndpoint.connection.addMessageHandler(pingPongPlayer);
					assertTrue("registered connection should indeed be removed",
							service.removeConnection(serverEndpoint.connection));
				}
				assertEquals("correct number of pongs should be received within the timeframe",
						NUM_EXPECTED_PONGS, pongCounter.get());
				assertEquals("there should be no registered connection after removing",
						0, service.getNumberOfConnections());
				assertTrue("pong handler should be removed",
						serverEndpoint.connection.getMessageHandlers().isEmpty());
			});
		} finally {
			assertTrue("there should be no remaining connections in the service",
					service.stop().isEmpty());
		}
	}



	@Test
	public void testRemoveUnregisteredConnection() {
		final var service = new WebsocketPingerService(1, false);
		final InvocationHandler handler =
				(proxy, method, args) -> method.getDeclaringClass().equals(Object.class)
						? method.invoke(this, args) : null;
		final Session connectionMock = (Session) Proxy.newProxyInstance(
				getClass().getClassLoader(), new Class[]{Session.class}, handler);
		assertFalse("removing unregistered connection should indicate no action took place",
				service.removeConnection(connectionMock));
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
