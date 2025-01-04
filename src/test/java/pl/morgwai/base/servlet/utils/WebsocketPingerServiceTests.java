// Copyright 2023 Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.CookieManager;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.logging.ConsoleHandler;
import java.util.logging.Logger;
import javax.websocket.*;
import javax.websocket.CloseReason.CloseCode;
import org.junit.*;

import org.eclipse.jetty.websocket.javax.client.JavaxWebSocketClientContainerProvider;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketContainer;
import pl.morgwai.base.jul.JulFormatter;
import pl.morgwai.base.servlet.utils.WebsocketPingerService.PingPongPlayer;
import pl.morgwai.base.servlet.utils.tests.WebsocketServer;

import static java.lang.Math.abs;
import static java.lang.Math.max;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.logging.Level.FINEST;
import static java.util.logging.Level.WARNING;
import static javax.websocket.CloseReason.CloseCodes.NORMAL_CLOSURE;
import static javax.websocket.CloseReason.CloseCodes.PROTOCOL_ERROR;
import static javax.websocket.CloseReason.CloseCodes.VIOLATED_POLICY;
import static org.junit.Assert.*;
import static pl.morgwai.base.jul.JulConfigurator.*;
import static pl.morgwai.base.jul.JulFormatter.FORMATTER_SUFFIX;
import static pl.morgwai.base.servlet.utils.WebsocketPingerService.DEFAULT_HASH_FUNCTION;
import static pl.morgwai.base.servlet.utils.WebsocketPingerService.PingDataSaltedHashFunction;
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



	static void await(CountDownLatch condition, String description) {
		try {
			assertTrue(description, condition.await(100L, MILLISECONDS));
		} catch (InterruptedException e) {
			fail("test interrupted");
		}
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
			try {
				await(ServerEndpoint.created.get(url.getPath()),
						"ServerEndpoint should be created");
				serverEndpoint = ServerEndpoint.instances.get(url.getPath());
				test.accept(serverEndpoint, clientEndpoint);
			} finally {
				if (closeClientConnection) clientConnection.close();
			}
			await(clientEndpoint.closed, "ClientEndpoint should be closed");
			await(serverEndpoint.closed, "ServerEndpoint should be closed");
			assertEquals("client close code should match",
					expectedClientCloseCode, clientEndpoint.closeCode);
			assertTrue("ServerEndpoint should not receive any onError(...) calls",
					serverEndpoint.errors.isEmpty());
			assertTrue("ClientEndpoint should not receive any onError(...) calls",
					clientEndpoint.errors.isEmpty());
		} finally {
			try {
				if (clientConnection.isOpen()) clientConnection.close();
			} catch (Exception ignored) {}  // don't suppress previous errors
		}
	}



	@Test
	public void testKeepAliveOnlyMode() throws Exception {
		final var PATH = "/testKeepAliveOnlyMode";
		performTest(PATH, true, NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
			boolean[] rttReportedHolder = {false};
			final var pongReceived = new CountDownLatch(1);
			final var player = new PingPongPlayer(
				serverEndpoint.connection,
				(connection, rttNanos) -> rttReportedHolder[0] = true,
				Long.MAX_VALUE,
				-1,
				DEFAULT_HASH_FUNCTION,
				false
			) {
				@Override public void onMessage(PongMessage pong) {
					log.fine("server " + PATH + " got pong, modifying data");
					super.onMessage(() -> ByteBuffer.wrap("someOtherData".getBytes(UTF_8)));
					pongReceived.countDown();
				}
			};

			player.sendPing();
			await(pongReceived, "pong should be received");
			assertEquals("failure count should not increase",
					0, player.failureCount);
			assertTrue("player should still be awaiting for matching pong",
					player.lastSentPingNumber > player.lastMatchingPongNumber);
			assertFalse("rtt should not be reported",
					rttReportedHolder[0]);

			serverEndpoint.connection.removeMessageHandler(player);  // pongs won't be registered
			player.sendPing();
			assertEquals("failure count should not increase",
					0, player.failureCount);
		});
	}



	@Test
	public void testServerExpectTimelyPongsModeWithRttReporting() throws Exception {
		final var PATH = "/testServerExpectTimelyPongsModeWithRttReporting";
		final long EXPECTED_RTT_INACCURACY_NANOS = 6000L;
		performTest(PATH, true, NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
			final long[] pongNanosHolder = {0};
			final long[] reportedRttNanosHolder = {0};
			final var postPingVerificationsDone = new CountDownLatch(1);
			final var pongReceived = new CountDownLatch(1);
			final var player = new PingPongPlayer(
				serverEndpoint.connection,
				(connection, rttNanos) -> reportedRttNanosHolder[0] = rttNanos,
				Long.MAX_VALUE,
				2,
				DEFAULT_HASH_FUNCTION,
				false
			) {
				@Override public void onMessage(PongMessage pong) {
					log.fine("server " + PATH + " got pong, forwarding");
					await(postPingVerificationsDone, "post-ping verifications should complete");
					pongNanosHolder[0] = System.nanoTime();
					super.onMessage(pong);
					pongReceived.countDown();
				}
			};
			player.failureCount = 1;

			final long pingNanos;
			synchronized (player) {  // increases test accuracy as sendPing() is synchronized
				//warmup
				player.pingDataBuffer.putLong(666L);
				player.pingDataBuffer.putLong(System.nanoTime());
				player.pingDataBuffer.put(player.saltedHashFunction.digest(666L, 123L));
				player.pingDataBuffer.rewind();

				pingNanos = System.nanoTime();
				player.sendPing();
			}
			try {
				assertTrue("player should be awaiting for pong",
						player.lastSentPingNumber > player.lastMatchingPongNumber);
			} finally {
				postPingVerificationsDone.countDown();
			}
			await(pongReceived, "pong should be received");
			assertEquals("failure count should be reset",
					0, player.failureCount);
			assertEquals("player should not be awaiting for pong anymore",
					player.lastSentPingNumber, player.lastMatchingPongNumber);
			final var rttInaccuracyNanos =
					pongNanosHolder[0] - pingNanos - reportedRttNanosHolder[0];
			log.info("RTT inaccuracy: " + rttInaccuracyNanos + "ns");
			assertTrue(
				"RTT should be accurately reported (" + rttInaccuracyNanos + "ns, expected <"
						+ EXPECTED_RTT_INACCURACY_NANOS + "ns. This may fail due to CPU usage "
						+ "spikes by other processes, so try to rerun a few times, but a persisting"
						+ " inaccuracy several orders of magnitude higher than expected probably "
						+ "means a bug)",
				abs(rttInaccuracyNanos) < EXPECTED_RTT_INACCURACY_NANOS
			);
		});
	}



	@Test
	public void testUnsolicitedPong() throws Exception {
		final var PATH = "/testUnsolicitedPong";
		performTest(PATH, true, NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
			final var pongReceived = new CountDownLatch(1);
			final var player = new PingPongPlayer(
				serverEndpoint.connection,
				null,
				Long.MAX_VALUE,
				2,
				DEFAULT_HASH_FUNCTION,
				false
			) {
				@Override public void onMessage(PongMessage pong) {
					log.fine("server " + PATH + " got pong, forwarding");
					super.onMessage(pong);
					pongReceived.countDown();
				}
			};
			final var pongData = ByteBuffer.wrap("keepAlive".getBytes(UTF_8));

			try {
				clientEndpoint.connection.getAsyncRemote().sendPong(pongData);
			} catch (IOException e) {
				fail("unexpected connection problem " + e);
			}
			await(pongReceived, "pong should be received");
			assertEquals("failure count should not increase",
					0, player.failureCount);
			assertEquals("player should not be awaiting for pong",
					player.lastSentPingNumber, player.lastMatchingPongNumber);
		});
	}



	@Test
	public void testUnsolicitedPongAfterPing() throws Exception {
		final var PATH = "/testUnsolicitedPongAfterPing";
		performTest(PATH, true, NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
			final var pongReceived = new CountDownLatch(1);
			final var player = new PingPongPlayer(
				serverEndpoint.connection,
				null,
				Long.MAX_VALUE,
				2,
				DEFAULT_HASH_FUNCTION,
				false
			) {
				@Override public void onMessage(PongMessage pong) {
					log.fine("server " + PATH + " got pong, modifying data");
					super.onMessage(() -> ByteBuffer.wrap("someOtherData".getBytes(UTF_8)));
					pongReceived.countDown();
				}
			};

			player.sendPing();
			await(pongReceived, "pong should be received");
			assertEquals("failure count should not increase",
					0, player.failureCount);
			assertTrue("player should still be awaiting for matching pong",
					player.lastSentPingNumber > player.lastMatchingPongNumber);
		});
	}



	@Test
	public void testTimedOutPongAndFailureLimitExceeded() throws Exception {
		final var PATH = "/testTimedOutPongAndFailureLimitExceeded";
		performTest(PATH, false, VIOLATED_POLICY, (serverEndpoint, clientEndpoint) -> {
			final var postPingVerificationsDone = new CountDownLatch(1);
			final var rttReportReceived = new CountDownLatch(1);
			final var player = new PingPongPlayer(
				serverEndpoint.connection,
				(connection, rttNanos) -> rttReportReceived.countDown(),
				10L,
				1,
				DEFAULT_HASH_FUNCTION,
				false
			) {
				byte[] savedPongData = null;

				@Override public void onMessage(PongMessage pong) {
					if (savedPongData == null) {
						log.fine("server " + PATH + " got pong, NOT forwarding, saving for later");
						savedPongData = new byte[
								Long.BYTES * 2 + saltedHashFunction.getDigestLength()];
						pong.getApplicationData().get(savedPongData);
					} else {
						await(postPingVerificationsDone, "post-ping verifications should complete");
						log.fine("server " + PATH
								+ " got pong, sending the previous timed-out one instead");
						final var firstPongBuffer = ByteBuffer.wrap(savedPongData);
						super.onMessage(() -> firstPongBuffer);
						savedPongData = null;
					}
				}
			};

			player.sendPing();
			assertEquals("player should be awaiting for pong",
					player.lastSentPingNumber, 1 + player.lastMatchingPongNumber);
			assertEquals("failure count should still be 0",
					0, player.failureCount);

			try {
				Thread.sleep(1L);  // make sure the first pong is timed-out
			} catch (InterruptedException ignored) {}
			player.sendPing();
			assertEquals("player should be still awaiting for pong",
					player.lastSentPingNumber, 2 + player.lastMatchingPongNumber);
			assertEquals("failure count should be increased",
					1, player.failureCount);
			postPingVerificationsDone.countDown();
			await(rttReportReceived, "rttReport should be received");
			assertEquals("failure count should remain unchanged",
					1, player.failureCount);
			assertEquals("player should be still awaiting for pong",
					player.lastSentPingNumber, 1 + player.lastMatchingPongNumber);

			player.sendPing();  // exceed failure limit
			assertEquals("failure count should be increased",
					2, player.failureCount);
		});
	}



	@Test
	public void testNonconsecutivePongExpectTimelyPongsMode() throws Exception {
		final var PATH = "/testNonconsecutivePongExpectTimelyPongsMode";
		performTest(
			PATH,
			false,
			PROTOCOL_ERROR,
			(serverEndpoint, clientEndpoint) -> testNonconsecutivePong(serverEndpoint, 1, PATH)
		);
	}

	@Test
	public void testNonconsecutivePongKeepAliveOnlyMode() throws Exception {
		final var PATH = "/testNonconsecutivePongKeepAliveOnlyMode";
		performTest(
			PATH,
			true,
			NORMAL_CLOSURE,
			(serverEndpoint, clientEndpoint) -> testNonconsecutivePong(serverEndpoint, -1, PATH)
		);
	}

	public void testNonconsecutivePong(ServerEndpoint serverEndpoint, int failureLimit, String path)
	{
		final CountDownLatch[] pongReceived = {
			new CountDownLatch(1),
			null,  // the second pong will be skipped
			new CountDownLatch(1)
		};
		final var player = new PingPongPlayer(
			serverEndpoint.connection,
			null,
			Long.MAX_VALUE,
			failureLimit,
			DEFAULT_HASH_FUNCTION,
			false
		) {
			int pongCounter = 0;

			@Override public void onMessage(PongMessage pong) {
				pongCounter++;
				if (pongCounter == 2) {
					log.fine("server " + path + " got pong-" + pongCounter + ", SKIPPING");
					return;
				}

				log.fine("server " + path + " got pong-" + pongCounter + ", forwarding");
				super.onMessage(pong);
				pongReceived[pongCounter - 1].countDown();
			}
		};

		player.sendPing();
		await(pongReceived[0], "the 1st pong should be received");
		player.sendPing();
		player.sendPing();
		await(pongReceived[2], "the 3rd pong should be received");
	}



	@Test
	public void testServiceKeepAliveRate() throws Exception {
		final var PATH = "/testServiceKeepAliveRate";
		final int NUM_EXPECTED_PONGS = 3;
		final long INTERVAL_MILLIS = 200L;
		final var service = new WebsocketPingerService(INTERVAL_MILLIS, MILLISECONDS);
		boolean serviceEmpty;
		try {
			performTest(PATH, true, NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
				assertEquals("there should be no registered connection initially",
						0, service.getNumberOfConnections());
				final var pongCounter = new AtomicInteger(0);
				service.addConnection(
					serverEndpoint.connection,
					(connection, rttNanos) -> pongCounter.incrementAndGet()
				);
				assertTrue("connection should be successfully added",
						service.containsConnection(serverEndpoint.connection));
				assertEquals("there should be 1 registered connection after adding",
						1, service.getNumberOfConnections());

				try {
					Thread.sleep(INTERVAL_MILLIS * (NUM_EXPECTED_PONGS - 1) + 20);
							// +20ms is for workers synchronization overhead
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
			serviceEmpty = service.shutdown().isEmpty();
			service.tryEnforceTermination();
		}
		// verify after finally block to not suppress earlier errors
		assertTrue("there should be no remaining connections in the service",
				serviceEmpty);
	}



	@Test
	public void testRemoveUnregisteredConnection() throws InterruptedException {
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
			service.tryEnforceTermination();
		}
	}



	@Test
	public void testClientExpectTimelyPongsMode() throws Exception {
		final var PATH = "/testClientExpectTimelyPongsMode";
		performTest(PATH, true, NORMAL_CLOSURE, (serverEndpoint, clientEndpoint) -> {
			final var postPingVerificationsDone = new CountDownLatch(1);
			final var pongReceived = new CountDownLatch(1);
			final var player = new PingPongPlayer(
				clientEndpoint.connection,
				null,
				Long.MAX_VALUE,
				2,
				DEFAULT_HASH_FUNCTION,
				false
			) {
				@Override public void onMessage(PongMessage pong) {
					log.fine("client " + PATH + " got pong, forwarding");
					await(postPingVerificationsDone, "post-ping verifications should complete");
					super.onMessage(pong);
					pongReceived.countDown();
				}
			};
			player.failureCount = 1;

			player.sendPing();
			try {
				assertTrue("player should be awaiting for pong",
						player.lastSentPingNumber > player.lastMatchingPongNumber);
			} finally {
				postPingVerificationsDone.countDown();
			}
			await(pongReceived, "pong should be received");
			assertEquals("failure count should be reset",
					0, player.failureCount);
			assertEquals("player should not be awaiting for pong anymore",
					player.lastSentPingNumber, player.lastMatchingPongNumber);
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



	@Test
	public void testPinging1000ConnectionsDoesNotExceedDurationLimit() throws Exception {
		final var PATH = "/testPingingTime";
		final int NUM_CONNECTIONS = 1000;
		server.startAndAddEndpoint(DumbServerEndpoint.class, PATH);
		final var url = "ws://localhost:" + server.getPort() + APP_PATH + PATH;
		testPingingDoesNotExceedDurationLimit(
				clientContainer, PING_DURATION_LIMIT_MILLIS, NUM_CONNECTIONS, url);
	}

	/**
	 * Note: when both the client and the server are on the same host, the limiting factor seems to
	 * be <i>replying to pings</i>. See {@link WebsocketPingerServiceExternalTests} where the
	 * service performs ~10x better when pinging external servers.
	 */
	static final long PING_DURATION_LIMIT_MILLIS = 300L;

	public static void testPingingDoesNotExceedDurationLimit(
		WebSocketContainer clientContainer,
		long durationLimitMillis,
		int connectionsPerUrl,
		String... urls
	) throws Exception {
		final var service = new WebsocketPingerService(500L, MILLISECONDS);
		try {
			final var totalConnections = connectionsPerUrl * urls.length;
			final CountDownLatch[] pongsReceived = {null};
			final var maxRtt = new AtomicLong(0L);
			for (var urlString: urls) {
				final var url = URI.create(urlString);
				for (int i = 0; i < connectionsPerUrl; i++) {
					service.addConnection(
						clientContainer.connectToServer(new DumbEndpoint(service), null, url),
						(connection, rttNanos) -> {
							maxRtt.getAndUpdate(currentMax -> max(currentMax, rttNanos));
							if (pongsReceived[0] != null) pongsReceived[0].countDown();
						}
					);
				}
				log.fine("established " + connectionsPerUrl + " connections to " + url);
			}
			service.scheduler.shutdown();
			assertEquals("correct number of connections should be registered",
					totalConnections, service.getNumberOfConnections());
			log.fine("established all " + totalConnections + " connections");
			Thread.sleep(500L); // UGLY UGLY but I'm lazy: wait for the old pongs to come down
			log.info("max RTT during connecting: " + NANOSECONDS.toMillis(maxRtt.get()) + "ms");
			maxRtt.set(0L);

			pongsReceived[0] = new CountDownLatch(totalConnections);
			final var startMillis = System.currentTimeMillis();
			for (var pingPongPlayer: service.connectionPingPongPlayers.values()) {
				pingPongPlayer.sendPing();
			}
			final var durationMillis = System.currentTimeMillis() - startMillis;
			log.info("pinging " + totalConnections + " connections took " + durationMillis + "ms");
			assertTrue(
				"pinging " + totalConnections + " connections should take less than "
						+ durationLimitMillis + "ms (was " + durationMillis + "ms)",
				durationMillis < durationLimitMillis
			);

			assertTrue("all pongs should be received",
					pongsReceived[0].await(5000L, MILLISECONDS));
			final var pongDurationMillis = System.currentTimeMillis() - startMillis;
			log.info("whole ping-pong took " + pongDurationMillis + "ms, max RTT: "
					+ NANOSECONDS.toMillis(maxRtt.get()) + "ms");
		} finally {
			log.fine("closing " + service.getNumberOfConnections() + " connections");
			for (var connection: service.shutdown()) {
				try {
					connection.close();
				} catch (Exception ignored) {}
			}
		}
	}

	public static class DumbEndpoint extends Endpoint {

		final WebsocketPingerService pingerService;

		public DumbEndpoint(WebsocketPingerService pingerService) {
			this.pingerService = pingerService;
		}

		@Override public void onOpen(Session connection, EndpointConfig config) {}

		@Override public void onClose(Session connection, CloseReason closeReason) {
			log.finer("removing connection " + connection.getId() + " from service");
			pingerService.removeConnection(connection);
		}
	}

	public static class DumbServerEndpoint extends Endpoint {
		@Override public void onOpen(Session connection, EndpointConfig config) {}
	}



	@Test
	public void testPingDataSaltedHashFunction() throws NoSuchAlgorithmException {
		final var salt = "randomSalt!".getBytes(UTF_8);
		final var hashFunction = new PingDataSaltedHashFunction(DEFAULT_HASH_FUNCTION, salt);
		final var timestamp = System.nanoTime();
		final var pingNumber = 135L;
		final var firstHash = hashFunction.digest(pingNumber, timestamp);
		assertEquals("hash length should be the same as reported by hashFunction",
				hashFunction.getDigestLength(), firstHash.length);
        assertArrayEquals("hashFunction should produce consistent results",
				firstHash, hashFunction.digest(pingNumber, timestamp));
		assertFalse("changing pingNumber should change the hash",
				Arrays.equals(firstHash, hashFunction.digest(pingNumber + 1L, timestamp)));
		assertFalse("changing timestamp should change the hash",
				Arrays.equals(firstHash, hashFunction.digest(pingNumber, System.nanoTime())));

		final var hashFunctionClone = new PingDataSaltedHashFunction(DEFAULT_HASH_FUNCTION, salt);
		assertArrayEquals("hashFunctionClone should produce the same results",
				firstHash, hashFunctionClone.digest(pingNumber, timestamp));

		final var anotherSaltFunction = new PingDataSaltedHashFunction(
				DEFAULT_HASH_FUNCTION, "anotherSalt".getBytes(UTF_8));
		assertFalse("changing salt should change the hash",
				Arrays.equals(firstHash, anotherSaltFunction.digest(pingNumber, timestamp)));
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
