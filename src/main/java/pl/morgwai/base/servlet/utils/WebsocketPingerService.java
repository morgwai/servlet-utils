// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.websocket.*;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.RemoteEndpoint.Async;



/**
 * Automatically pings and handles pongs from websocket connections. Depending on constructor used,
 * operates in either {@link #WebsocketPingerService(int, int, int, boolean) verify-pongs mode} or
 * {@link #WebsocketPingerService(int, boolean) keep-alive-only mode}.
 * <p>
 * Instances are usually created at app startup and stored in a location easily reachable
 * for endpoint instances (for example as a {@code ServletContext} attribute).</p>
 * <p>
 * Endpoint instances should register themselves for pinging in their
 * {@link javax.websocket.Endpoint#onOpen(Session, javax.websocket.EndpointConfig)} method using
 * {@link #addConnection(Session)} and deregister in
 * {@link javax.websocket.Endpoint#onClose(Session, CloseReason)} using
 * {@link #removeConnection(Session)}.</p>
 */
public class WebsocketPingerService {



	/** Majority of proxy and NAT routers have timeout of at least 60s. */
	public static final int DEFAULT_INTERVAL = 55;
	final int intervalSeconds;

	/** Arbitrarily chosen number. */
	public static final int DEFAULT_FAILURE_LIMIT = 4;
	final int failureLimit;

	/** Economic value to reduce use of {@link Random}. */
	public static final int DEFAULT_PING_SIZE = 4;
	final int pingSize;

	final boolean synchronizeSending;
	final boolean keepAliveOnly;

	final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
	final ScheduledFuture<?> pingingTask;
	final Random random = new Random();
	final ConcurrentMap<Session, PingPongPlayer> connectionPingPongPlayers =
			new ConcurrentHashMap<>();



	/** Low-level constructor for both modes */
	private WebsocketPingerService(
		boolean keepAliveOnly,
		int intervalSeconds,
		int failureLimit,
		int pingSize,
		boolean synchronizeSending
	) {
		if (pingSize > 125 || pingSize < 1) {
			throw new IllegalArgumentException("ping size must be between 1 and 125");
		}
		if ( ( !keepAliveOnly) && failureLimit < 0) {
			throw new IllegalArgumentException("failure limit cannot be negative");
		}
		this.keepAliveOnly = keepAliveOnly;
		this.intervalSeconds = intervalSeconds;
		this.failureLimit = failureLimit;
		this.pingSize = pingSize;
		this.synchronizeSending = synchronizeSending;
		pingingTask = scheduler.scheduleAtFixedRate(
				this::pingAllConnections, 0L, intervalSeconds, TimeUnit.SECONDS);
	}



	/**
	 * Configures and starts the service in verify-pongs mode: timely pongs are expected and their
	 * contents is verified to match corresponding pings.
	 * @param intervalSeconds interval between pings and also timeout for pongs.
	 * @param failureLimit limit of lost, malformed or timed out pongs after which the given
	 *     connection is closed. Each valid, timely pong resets connection's failure counter.
	 * @param pingSize size of the ping data to send. This comes from {@link Random}, so an economic
	 *     value is recommended.
	 * @param synchronizeSending whether to synchronize packet sending on the given connection.
	 *     Whether it is necessary depends on the implementation of the container. For example it is
	 *     not necessary on Jetty, but it is on Tomcat: see
	 *     <a href="https://bz.apache.org/bugzilla/show_bug.cgi?id=56026">this bug report</a>.
	 */
	public WebsocketPingerService(
			int intervalSeconds, int failureLimit, int pingSize, boolean synchronizeSending) {
		this(false, intervalSeconds, failureLimit, pingSize, synchronizeSending);
	}

	/**
	 * Calls {@link #WebsocketPingerService(int, int, int, boolean)
	 * WebsocketPingerService(intervalSeconds, failureLimit, pingSize, false)} (ping-pong mode).
	 */
	public WebsocketPingerService(int intervalSeconds, int failureLimit, int pingSize) {
		this(intervalSeconds, failureLimit, pingSize, false);
	}

	/**
	 * Calls {@link #WebsocketPingerService(int, int, int, boolean)
	 * WebsocketPingerService}<code>({@link #DEFAULT_INTERVAL},
	 * {@link #DEFAULT_FAILURE_LIMIT}, {@link #DEFAULT_PING_SIZE}, false)</code> (ping-pong mode).
	 */
	public WebsocketPingerService() {
		this(DEFAULT_INTERVAL, DEFAULT_FAILURE_LIMIT, DEFAULT_PING_SIZE, false);
	}



	/**
	 * Configures and starts the service in keep-alive-only mode: just 1-byte ping is sent each time
	 * and responses are not verified. The params have the same meaning as in
	 * {@link #WebsocketPingerService(int, int, int, boolean)}.
	 */
	public WebsocketPingerService(int intervalSeconds, boolean synchronizeSending) {
		this(true, intervalSeconds, -1, 1, synchronizeSending);
	}

	/**
	 * Calls {@link #WebsocketPingerService(int, boolean)
	 * WebsocketPingerService}<code>(intervalSeconds, false)</code>
	 * (keep-alive-only mode).
	 */
	public WebsocketPingerService(int intervalSeconds) { this(intervalSeconds, false); }



	/**
	 * Registers {@code connection} for pinging by this service. Usually called in
	 * {@link javax.websocket.Endpoint#onOpen(Session, javax.websocket.EndpointConfig)}.
	 */
	public void addConnection(Session connection) {
		final PingPongPlayer pingPongPlayer;
		pingPongPlayer = new PingPongPlayer(connection, failureLimit, synchronizeSending);
		connectionPingPongPlayers.put(connection, pingPongPlayer);
	}



	/**
	 * Deregisters {@code connection} from this service. Usually called in
	 * {@link javax.websocket.Endpoint#onClose(Session, CloseReason)}.
	 */
	public void removeConnection(Session connection) {
		final var pingPongPlayer = connectionPingPongPlayers.remove(connection);
		if (pingPongPlayer != null) pingPongPlayer.deregister();
	}



	/** Returns the number of currently registered connections. */
	public int getNumberOfConnections() {
		return connectionPingPongPlayers.size();
	}



	/** {@link #pingingTask} */
	void pingAllConnections() {
		final var pingData = new byte[pingSize];
		if (keepAliveOnly) {
			pingData[0] = 69;
		} else {
			random.nextBytes(pingData);
		}
		for (var pingPongPlayer: connectionPingPongPlayers.values()) {
			pingPongPlayer.sendPing(pingData);
		}
	}



	/**
	 * Stops the service. After a call to this method the service becomes no longer usable and
	 * should be discarded.
	 * @return connections that were registered at the time this method was called.
	 */
	public Set<Session> stop() {
		pingingTask.cancel(true);
		scheduler.shutdown();
		for (var pingPongPlayer: connectionPingPongPlayers.values()) pingPongPlayer.deregister();
		try {
			scheduler.awaitTermination(500L, TimeUnit.MILLISECONDS);
		} catch (InterruptedException ignored) {}
		if ( !scheduler.isTerminated()) {
			scheduler.shutdownNow();
			try {
				scheduler.awaitTermination(50L, TimeUnit.MILLISECONDS);
			} catch (InterruptedException ignored) {}
			if ( !scheduler.isTerminated()) log.warning("service's executor failed to terminate");
		}
		return connectionPingPongPlayers.keySet();
	}



	/** Plays ping-pong with a single associated connection. */
	static class PingPongPlayer implements MessageHandler.Whole<PongMessage> {



		final Session connection;
		final Async connector;
		final int failureLimit;  // negative value means keep-alive-only mode
		final boolean synchronizeSending;

		ByteBuffer pingDataBuffer;
		int failureCount = 0;
		boolean awaitingPong = false;



		PingPongPlayer(Session connection, int failureLimit, boolean synchronizeSending) {
			this.connection = connection;
			this.connector = connection.getAsyncRemote();
			this.synchronizeSending = synchronizeSending;
			this.failureLimit = failureLimit;
			connection.addMessageHandler(PongMessage.class, this);
		}



		/** Called by the service's worker thread. */
		synchronized void sendPing(byte[] pingData) {
			if (awaitingPong) {  // the previous ping timed out
				failureCount++;
				if (failureCount > failureLimit) {
					closeFailedConnection();
					return;
				}
			}
			pingDataBuffer = ByteBuffer.wrap(pingData);
			try {
				if (synchronizeSending) {
					synchronized (connection) {
						connector.sendPing(pingDataBuffer);
					}
				} else {
					connector.sendPing(pingDataBuffer);
				}
				pingDataBuffer.rewind();
				if (failureLimit >= 0) awaitingPong = true;
			} catch (IOException e) {
				closeFailedConnection();
			}
		}



		@Override
		public synchronized void onMessage(PongMessage pong) {
			if (failureLimit < 0 || !awaitingPong) return;  // keep-alive only
			awaitingPong = false;
			if (pong.getApplicationData().equals(pingDataBuffer)) {
				failureCount = 0;
			} else {
				failureCount++;
				if (failureCount > failureLimit) closeFailedConnection();
			}
		}



		/** Called if ping {@link #failureLimit} is exceeded or if {@link IOException} occurs. */
		private void closeFailedConnection() {
			if (log.isLoggable(Level.FINE)) log.fine("failure on connection " + connection.getId());
			try {
				connection.close(new CloseReason(
					CloseCodes.PROTOCOL_ERROR, "ping failure limit exceeded"));
			} catch (IOException ignored) {}
		}



		/** Removes pong handler. Called by the service when connection is deregistered. */
		void deregister() {
			connection.removeMessageHandler(this);
		}
	}



	static final Logger log = Logger.getLogger(WebsocketPingerService.class.getName());
}
