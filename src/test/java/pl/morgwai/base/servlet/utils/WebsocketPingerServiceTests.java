// Copyright 2023 Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.CookieManager;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.logging.*;

import javax.websocket.*;
import javax.websocket.CloseReason.CloseCode;
import javax.websocket.CloseReason.CloseCodes;

import org.eclipse.jetty.websocket.javax.client.JavaxWebSocketClientContainerProvider;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketContainer;
import org.junit.*;
import pl.morgwai.base.jul.JulFormatter;
import pl.morgwai.base.servlet.utils.WebsocketPingerService.PingPongPlayer;
import pl.morgwai.base.servlet.utils.tests.WebsocketServer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.logging.Level.FINEST;
import static java.util.logging.Level.WARNING;

import static org.junit.Assert.*;
import static pl.morgwai.base.jul.JulConfigurator.*;
import static pl.morgwai.base.jul.JulFormatter.FORMATTER_SUFFIX;
import static pl.morgwai.base.servlet.utils.tests.WebsocketServer.APP_PATH;



public abstract class WebsocketPingerServiceTests {



	org.eclipse.jetty.client.HttpClient wsHttpClient;
	WebSocketContainer clientContainer;
	WebsocketServer server;



	@Before
	public void setup() {
		final var cookieManager = new CookieManager();
		wsHttpClient = new org.eclipse.jetty.client.HttpClient();
		wsHttpClient.setCookieStore(cookieManager.getCookieStore());
		clientContainer = JavaxWebSocketClientContainerProvider.getContainer(wsHttpClient);
		server = createServer();
	}

	protected abstract WebsocketServer createServer();



	@After
	public void shutdown() throws Exception {
		final var jettyWsContainer = ((JavaxWebSocketContainer) clientContainer);
		jettyWsContainer.stop();
		jettyWsContainer.destroy();
		wsHttpClient.stop();
		wsHttpClient.destroy();
		server.shutdown();
	}



	@Test
	public void testPinging1000ConnectionsDoesNotExceedMinInterval() throws Exception {
		final var PATH = "/testPingingTime";
		final int NUM_CONNECTIONS = 1000;
		final long PING_DURATION_LIMIT_MILLIS = 300L;
		final var service = new WebsocketPingerService();
		service.stop();
		server.startAndAddEndpoint(DumbEndpoint.class, PATH);
		final var url = URI.create("ws://localhost:" + server.getPort() + APP_PATH + PATH);
		final var warmupPongsReceived = new CountDownLatch(NUM_CONNECTIONS);
		final boolean[] warmupFlag = {true};
		try {
			for (int i = 0; i < NUM_CONNECTIONS; i++) {
				service.addConnection(
					clientContainer.connectToServer(DumbEndpoint.class, null, url),
					(connection, rttNanos) -> {
						if (warmupFlag[0]) warmupPongsReceived.countDown();
					}
				);
			}
			log.fine("connections established, proceeding to warmup");
			service.pingAllConnections();  // warmup
			assertTrue("all warmup pongs should be received",
					warmupPongsReceived.await(PING_DURATION_LIMIT_MILLIS * 5, MILLISECONDS));
			log.fine("warmup completed, proceeding to the actual test");
			warmupFlag[0] = false;

			final var startMillis = System.currentTimeMillis();
			service.pingAllConnections();
			final var durationMillis = System.currentTimeMillis() - startMillis;
			log.info("pinging " + NUM_CONNECTIONS + " connections took " + durationMillis + "ms");
			assertTrue(
				"pinging " + NUM_CONNECTIONS + " connections should take less than "
						+ PING_DURATION_LIMIT_MILLIS + "ms (was " + durationMillis + "ms)",
				durationMillis < PING_DURATION_LIMIT_MILLIS
			);
		} finally {
			for (var connection: service.connectionPingPongPlayers.keySet()) {
				try {
					connection.close();
				} catch (Exception ignored) {}
			}
		}
	}

	public static class DumbEndpoint extends Endpoint{
		@Override public void onOpen(Session session, EndpointConfig config) {}
	}



	/**
	 * Performs {@code test} over a websocket connection.
	 * <ol>
	 *   <li>establishes a connection between a new {@link ClientEndpoint} and {@link #server}.</li>
	 *   <li>waits for a {@link ServerEndpoint} to be {@link ServerEndpoint#created created} and
	 *       obtains its {@link ServerEndpoint#instances instance}.</li>
	 *   <li>calls {@code test}.</li>
	 *   <li>depending on the value of {@code closeClientConnection} closes the connections from the
	 *       client side.</li>
	 *    <li>regardless of {@code closeClientConnection}, verifies if the connection was closed on
	 *        both endpoints with {@code expectedClientCloseCode} on the client side.</li>
	 *    <li>verifies if there were no calls to {@link Endpoint#onError(Session, Throwable)} on
	 *        either endpoint.</li>
	 * </ol>
	 */
	public void performTest(
		String path,
		boolean closeClientConnection,
		CloseCode expectedClientCloseCode,
		BiConsumer<ServerEndpoint, ClientEndpoint> test
	) throws Exception {
		server.startAndAddEndpoint(ServerEndpoint.class, path);
		final var url = URI.create("ws://localhost:" + server.getPort() + APP_PATH + path);
		ServerEndpoint.created.put(url.getPath(), new CountDownLatch(1));
		final ServerEndpoint serverEndpoint;
		final var clientEndpoint = new ClientEndpoint();
		final var clientConnection = clientContainer.connectToServer(clientEndpoint, null, url);
		try {
			assertTrue("ServerEndpoint should be created",
					ServerEndpoint.created.get(url.getPath()).await(100L, MILLISECONDS));
			serverEndpoint = ServerEndpoint.instances.get(url.getPath());
			test.accept(serverEndpoint, clientEndpoint);
		} finally {
			if (closeClientConnection) clientConnection.close();
		}
		assertTrue("ClientEndpoint should be closed",
				clientEndpoint.closed.await(100L, MILLISECONDS));
		assertTrue("ServerEndpoint should be closed",
				serverEndpoint.closed.await(100L, MILLISECONDS));
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
			boolean[] rttReportedHolder = {false};
			final var pongReceived = new CountDownLatch(1);
			final var player = new PingPongPlayer(
				serverEndpoint.connection,
				-1,
				false,
				(connection, rttNanos) -> rttReportedHolder[0] = true
			) {
				@Override public void onMessage(PongMessage pong) {
					log.fine("server " + PATH + " got pong, modifying data");
					super.onMessage(() -> ByteBuffer.wrap("someOtherData".getBytes(UTF_8)));
					pongReceived.countDown();
				}
			};

			player.sendPing("firstPing".getBytes(UTF_8));
			try {
				assertTrue("pong should be received",
						pongReceived.await(100L, MILLISECONDS));
			} catch (InterruptedException e) {
				fail("test interrupted");
			}
			assertEquals("failure count should not increase",
					0, player.failureCount);
			assertNotNull("player should still be awaiting for matching pong",
					player.pingTimestampNanos);
			assertFalse("rtt should not be reported",
					rttReportedHolder[0]);

			serverEndpoint.connection.removeMessageHandler(player);  // pongs won't be registered
			player.sendPing("secondPing".getBytes(UTF_8));
			assertEquals("failure count should not increase",
					0, player.failureCount);
		});
	}



	@Test
	public void testServerPingPongWithRttReporting() throws Exception {
		final var PATH = "/testServerPingPongWithRttReporting";
		performTest(PATH, true, CloseCodes.NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
			final long[] pongNanosHolder = {0};
			final long[] reportedRttNanosHolder = {0};
			final var postPingVerificationsDone = new CountDownLatch(1);
			final var pongReceived = new CountDownLatch(1);
			final var player = new PingPongPlayer(
				serverEndpoint.connection,
				2,
				false,
				(connection, rttNanos) -> reportedRttNanosHolder[0] = rttNanos
			) {
				@Override public void onMessage(PongMessage pong) {
					log.fine("server " + PATH + " got pong, forwarding");
					try {
						postPingVerificationsDone.await(100L, MILLISECONDS);
					} catch (InterruptedException ignored) {}
					pongNanosHolder[0] = System.nanoTime();
					super.onMessage(pong);
					pongReceived.countDown();
				}
			};
			player.failureCount = 1;

			player.sendPing("testPing".getBytes(UTF_8));
			final var pingNanos = System.nanoTime();
			try {
				assertNotNull("player should be awaiting for pong",
						player.pingTimestampNanos);
			} finally {
				postPingVerificationsDone.countDown();
			}
			try {
				assertTrue("pong should be received",
						pongReceived.await(100L, MILLISECONDS));
			} catch (InterruptedException e) {
				fail("test interrupted");
			}
			assertEquals("failure count should be reset",
					0, player.failureCount);
			assertNull("player should not be awaiting for pong anymore",
					player.pingTimestampNanos);
			final var rttInaccuracyNanos =
					reportedRttNanosHolder[0] - (pongNanosHolder[0] - pingNanos);
			log.info("RTT inaccuracy: " + rttInaccuracyNanos + "ns");
			assertTrue(
				"RTT should be accurately reported (" + rttInaccuracyNanos + "ns, expected <50_000"
						+ "ns. This may fail due to CPU usage spikes by other processes, so try to "
						+ "rerun few times, but if the failure persists it probably means a bug)",
				rttInaccuracyNanos < 50_000L
			);
		});
	}



	@Test
	public void testKeepAlivePongFromClient() throws Exception {
		final var PATH = "/testKeepAlivePongFromClient";
		performTest(PATH, true, CloseCodes.NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
			final var pongReceived = new CountDownLatch(1);
			final var player = new PingPongPlayer(serverEndpoint.connection, 2, false, null) {
				@Override public void onMessage(PongMessage pong) {
					log.fine("server " + PATH + " got pong, forwarding");
					super.onMessage(pong);
					pongReceived.countDown();
				}
			};
			final var pongData = ByteBuffer.wrap("keepAlive".getBytes(UTF_8));

			try {
				clientEndpoint.connection.getAsyncRemote().sendPong(pongData);
				assertTrue("pong should be received",
						pongReceived.await(100L, MILLISECONDS));
			} catch (InterruptedException e) {
				fail("test interrupted");
			} catch (IOException e) {
				fail("unexpected connection problem " + e);
			}
			assertEquals("failure count should not increase",
					0, player.failureCount);
			assertNull("player should not be awaiting for pong",
					player.pingTimestampNanos);
		});
	}



	@Test
	public void testUnmatchedPongAfterPing() throws Exception {
		final var PATH = "/testUnmatchedPong";
		performTest(PATH, true, CloseCodes.NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
			final var pongReceived = new CountDownLatch(1);
			final var player = new PingPongPlayer(serverEndpoint.connection, 2, false, null) {
				@Override public void onMessage(PongMessage pong) {
					log.fine("server " + PATH + " got pong, modifying data");
					super.onMessage(() -> ByteBuffer.wrap("someOtherData".getBytes(UTF_8)));
					pongReceived.countDown();
				}
			};

			player.sendPing("originalPingData".getBytes(UTF_8));
			try {
				assertTrue("pong should be received",
						pongReceived.await(100L, MILLISECONDS));
			} catch (InterruptedException e) {
				fail("test interrupted");
			}
			assertEquals("failure count should not increase",
					0, player.failureCount);
			assertNotNull("player should still be awaiting for matching pong",
					player.pingTimestampNanos);
		});
	}



	@Test
	public void testTimedOutPongAndFailureLimitExceeded() throws Exception {
		final var PATH = "/testTimedOutPongAndFailureLimitExceeded";
		performTest(PATH, false, CloseCodes.PROTOCOL_ERROR, (serverEndpoint, clientEndpoint) -> {
			final var player = new PingPongPlayer(serverEndpoint.connection, 1, false, null);
			serverEndpoint.connection.removeMessageHandler(player);  // pongs won't be registered

			player.sendPing("firstPing".getBytes(UTF_8));
			assertNotNull("player should be still awaiting for pong",
					player.pingTimestampNanos);
			assertEquals("failure count should still be 0",
					0, player.failureCount);

			player.sendPing("secondPing".getBytes(UTF_8));
			assertNotNull("player should be still awaiting for pong",
					player.pingTimestampNanos);
			assertEquals("failure count should be increased",
					1, player.failureCount);

			player.sendPing("thirdPing".getBytes(UTF_8));  // exceed failure limit
		});
	}



	@Test
	public void testServiceKeepAliveRate() throws Exception {
		final var PATH = "/testServiceKeepAliveRate";
		final int NUM_EXPECTED_PONGS = 3;
		final long INTERVAL_MILLIS = 200L;
		final var service = new WebsocketPingerService(INTERVAL_MILLIS, MILLISECONDS);
		boolean serviceEmpty;
		try {
			performTest(PATH, true, CloseCodes.NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
				assertEquals("there should be no registered connection initially",
						0, service.getNumberOfConnections());

				service.addConnection(serverEndpoint.connection);
				assertTrue("connection should be successfully added",
						service.containsConnection(serverEndpoint.connection));
				assertEquals("there should be 1 registered connection after adding",
						1, service.getNumberOfConnections());

				final var pongCounter = new AtomicInteger(0);
				final var player = service.connectionPingPongPlayers.get(serverEndpoint.connection);
				final var decoratedHandler = new MessageHandler.Whole<PongMessage>() {
					@Override public void onMessage(PongMessage pong) {
						log.fine("server " + PATH + " got pong");
						pongCounter.incrementAndGet();
						player.onMessage(pong);
					}
				};
				serverEndpoint.connection.removeMessageHandler(player);

				serverEndpoint.connection.addMessageHandler(decoratedHandler);
				try {
					Thread.sleep(INTERVAL_MILLIS * NUM_EXPECTED_PONGS - 1);
							// sleep may sleep a bit longer sometimes, so -1 to significantly
							// decrease the chance of an extra pong while very slightly increasing
							// the chance of a missed pong
					assertTrue("connection removal should succeed",
							service.removeConnection(serverEndpoint.connection));
					Thread.sleep(20L);  // assumed max RTT millis
				} catch (InterruptedException e) {
					service.removeConnection(serverEndpoint.connection);
					fail("test interrupted");
				}
				assertFalse("service should indicate that connection was removed",
						service.containsConnection(serverEndpoint.connection));
				assertEquals("there should be no registered connection after removing",
						0, service.getNumberOfConnections());
				assertEquals("correct number of pongs should be received within the timeframe",
						NUM_EXPECTED_PONGS, pongCounter.get());
			});
		} finally {
			serviceEmpty = service.stop().isEmpty();
		}
		// verify after finally block to not suppress earlier errors
		assertTrue("there should be no remaining connections in the service",
				serviceEmpty);
	}



	@Test
	public void testRemoveUnregisteredConnection() {
		final var service = new WebsocketPingerService();
		final var connectionMock = (Session) Proxy.newProxyInstance(  // poor man's mock
			getClass().getClassLoader(),
			new Class[] {Session.class},
			(proxy, method, args) -> {
				if (method.getDeclaringClass().equals(Object.class)) {
					return method.invoke(this, args);
				} else {
					throw new UnsupportedOperationException();
				}
			}
		);
		try {
			assertFalse("removing unregistered connection should indicate no action took place",
					service.removeConnection(connectionMock));
		} finally {
			service.stop();
		}
	}



	@Test
	public void testClientPingPong() throws Exception {
		final var PATH = "/testClientPingPong";
		performTest(PATH, true, CloseCodes.NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
			final var postPingVerificationsDone = new CountDownLatch(1);
			final var pongReceived = new CountDownLatch(1);
			final var player = new PingPongPlayer(clientEndpoint.connection, 2, false, null) {
				@Override public void onMessage(PongMessage pong) {
					log.fine("client " + PATH + " got pong, forwarding");
					try {
						postPingVerificationsDone.await(100L, MILLISECONDS);
					} catch (InterruptedException ignored) {}
					super.onMessage(pong);
					pongReceived.countDown();
				}
			};
			player.failureCount = 1;

			player.sendPing("testPing".getBytes(UTF_8));
			try {
				assertNotNull("player should be awaiting for pong",
						player.pingTimestampNanos);
			} finally {
				postPingVerificationsDone.countDown();
			}
			try {
				assertTrue("pong should be received",
						pongReceived.await(100L, MILLISECONDS));
			} catch (InterruptedException e) {
				fail("test interrupted");
			}
			assertEquals("failure count should be reset",
					0, player.failureCount);
			assertNull("player should not be awaiting for pong anymore",
					player.pingTimestampNanos);
		});
	}



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



		@Override public void onOpen(Session connection, EndpointConfig config) {
			this.connection = connection;
			final var url = connection.getRequestURI().toString();
			this.id = (isServer() ? "server " : "client ") + url.substring(url.lastIndexOf('/'));
			log.fine(id + " got onOpen(...)");
		}

		protected abstract boolean isServer();



		@Override public void onClose(Session session, CloseReason closeReason) {
			log.fine(id + " got onClose(...), reason: " + closeReason.getCloseCode());
			closeCode = closeReason.getCloseCode();
			closed.countDown();
		}



		@Override public void onError(Session session, Throwable error) {
			log.log(WARNING, id + " got onError(...)", error);
			errors.add(error);
		}
	}



	public static class ClientEndpoint extends BaseEndpoint {

		@Override protected boolean isServer() {
			return false;
		}
	}



	public static class ServerEndpoint extends BaseEndpoint {

		/**
		 * A static point of synchronization between a given test method and the corresponding
		 * container-created {@link ServerEndpoint} instance. As each test method deploys its
		 * {@link ServerEndpoint} at a different URI/path, tests may safely run in parallel.
		 */
		static final ConcurrentMap<String, CountDownLatch> created = new ConcurrentHashMap<>();

		/**
		 * A static place for a given test method to find its corresponding container-created
		 * {@link ServerEndpoint} instance.
		 */
		static final ConcurrentMap<String, ServerEndpoint> instances = new ConcurrentHashMap<>();



		/** Stores itself into {@link #instances} and switches its {@link #created}. */
		@Override public void onOpen(Session connection, EndpointConfig config) {
			super.onOpen(connection, config);
			instances.put(connection.getRequestURI().getPath(), this);
			created.get(connection.getRequestURI().getPath()).countDown();
		}



		@Override protected boolean isServer() {
			return true;
		}
	}



	static final Logger log = Logger.getLogger(WebsocketPingerServiceTests.class.getName());



	/** {@code FINE} will log all endpoint lifecycle method calls. */
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
