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
 * operates in either {@link #WebsocketPingerService(int, int, int, boolean) ping-pong mode} or
 * {@link #WebsocketPingerService(int, boolean) keep-alive-only mode}.
 * <p>
 * Instances are usually created at app startup and stored in a location easily reachable
 * for endpoint instances (for example on static var in app's ServletContextListener).</p>
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
		if (log.isLoggable(Level.INFO)) {
			log.info("websockets will be pinged every " + intervalSeconds
					+ "s,  failure limit: " + failureLimit + ", ping size: "
					+ pingSize + "B, synchronize ping sending: " + synchronizeSending);
		}
	}



	/**
	 * Configures and starts the service in ping-pong mode: timely pongs are expected and validated.
	 * @param intervalSeconds interval between pings.
	 * @param failureLimit limit of lost or malformed pongs after which the given connection is
	 *     closed. Pongs received after {@code pingIntervalSeconds} count as failures. Each valid,
	 *     timely pong resets connection's failure counter.
	 * @param pingSize size of the ping data to send. This comes from {@link Random}, so an economic
	 *     value is recommended.
	 * @param synchronizeSending whether to synchronize ping sending on the given connection.
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
	 * Configures and starts the service in keep-alive-only mode: just 1 byte is sent each time
	 * and responses are not expected. Remaining params have the same meaning as in
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
	 * Registers {@code connection} for pinging. Usually called in
	 * {@link javax.websocket.Endpoint#onOpen(Session, javax.websocket.EndpointConfig)}.
	 */
	public void addConnection(Session connection) {
		final PingPongPlayer pingPongPlayer;
		if (keepAliveOnly) {
			pingPongPlayer = new PingPongPlayer(connection, synchronizeSending);
		} else {
			pingPongPlayer = new PingPongPlayer(connection, failureLimit, synchronizeSending);
		}
		connectionPingPongPlayers.put(connection, pingPongPlayer);
	}



	/**
	 * Deregisters {@code connection}. Usually called in
	 * {@link javax.websocket.Endpoint#onClose(Session, CloseReason)}.
	 */
	public void removeConnection(Session connection) {
		final var pingPongPlayer = connectionPingPongPlayers.remove(connection);
		if ( !keepAliveOnly) pingPongPlayer.deregister();
	}



	/** Returns the number of currently registered connections. */
	public int getNumberOfConnections() {
		return connectionPingPongPlayers.size();
	}



	/** {@link #pingingTask} */
	private void pingAllConnections() {
		if (keepAliveOnly) {
			for (var pingPongPlayer: connectionPingPongPlayers.values()) {
				pingPongPlayer.sendKeepAlive();
			}
		} else {
			final var pingData = new byte[pingSize];
			random.nextBytes(pingData);
			for (var pingPongPlayer: connectionPingPongPlayers.values()) {
				pingPongPlayer.pingConnection(pingData);
			}
		}
	}



	/**
	 * Stops the service. After a call to this method the service becomes no longer usable and
	 * should be discarded.
	 * @return remaining registered connections.
	 */
	public Set<Session> stop() {
		pingingTask.cancel(true);
		scheduler.shutdown();
		if ( !keepAliveOnly) {
			for (var pingPongPlayer: connectionPingPongPlayers.values()) {
				pingPongPlayer.deregister();
			}
		}
		try {
			scheduler.awaitTermination(500L, TimeUnit.MILLISECONDS);
		} catch (InterruptedException ignored) {}
		if ( !scheduler.isTerminated()) scheduler.shutdownNow();
		log.info("websocket pinger service stopped");
		return connectionPingPongPlayers.keySet();
	}



	/** Plays ping-pong with a single associated connection (or sends just keep-alives to it). */
	static class PingPongPlayer implements MessageHandler.Whole<PongMessage> {

		final Session connection;
		final Async connector;
		final boolean synchronizeSending;
		ByteBuffer packetDataBuffer;

		final int failureLimit;  // only used in ping-pong mode



		private PingPongPlayer(
			Session connection,
			int failureLimit,
			boolean synchronizeSending,
			byte[] initialPacketData
		) {
			this.connection = connection;
			connector = connection.getAsyncRemote();
			this.failureLimit = failureLimit;
			this.synchronizeSending = synchronizeSending;
			packetDataBuffer = ByteBuffer.wrap(initialPacketData);  // needed in ping-pong mode also
					// to not crash on pongs received before the 1st ping
		}



		/**
		 *  References either {@link Async#sendPong(ByteBuffer) sendPong(...)} or
		 * {@link Async#sendPing(ByteBuffer) sendPing(...)}.
		 */
		@FunctionalInterface
		private interface Connector { void sendPacket(ByteBuffer data) throws IOException; }

		/**
		 * Uses {@code connector} to send a packet either synchronizing on {@link #connection} or
		 * not depending on {@link #synchronizeSending}.
		 */
		private void sendPacket(Connector connector) {
			try {
				if (synchronizeSending) {
					synchronized (connection) {
						connector.sendPacket(packetDataBuffer);
					}
				} else {
					connector.sendPacket(packetDataBuffer);
				}
			} catch (IOException e) {
				// connection was probably closed in a meantime, try formally close just in case
				closeFailedConnection();
			}
		}



		/** Constructor for keep-alive-only mode. */
		PingPongPlayer(Session connection, boolean synchronizeSending) {
			this(connection, -1, synchronizeSending, new byte[]{(byte) connection.hashCode()});
		}

		void sendKeepAlive() {
			sendPacket(connector::sendPong);
			packetDataBuffer.rewind();  // reuse the same buffer in future keep-alives
		}



		/**
		 * Constructor for ping-pong mode.
		 */
		PingPongPlayer(Session connection, int failureLimit, boolean synchronizeSending) {
			this(connection, failureLimit, synchronizeSending, new byte[1]);
			connection.addMessageHandler(PongMessage.class, this);
		}



		int failureCount = 0;
		boolean awaitingPong = false;



		synchronized void pingConnection(byte[] pingData) {
			if (awaitingPong) failureCount++;
			if (failureCount > failureLimit) {
				closeFailedConnection();
				return;
			}
			packetDataBuffer = ByteBuffer.wrap(pingData);
			sendPacket(connector::sendPing);
			packetDataBuffer.rewind();
			awaitingPong = true;  // send failure counts as failure, so flip the flag in both cases
				// send failures are usually caused by connection closing, so no point bothering
		}



		@Override
		public synchronized void onMessage(PongMessage pong) {
			if ( !awaitingPong) return;  // keep-alive from the remote end: don't count as failure
			awaitingPong = false;
			if (pong.getApplicationData().equals(packetDataBuffer)) {
				failureCount = 0;
			} else {
				failureCount++;
				if (failureCount > failureLimit) closeFailedConnection();
			}
		}



		private void closeFailedConnection() {
			if (log.isLoggable(Level.FINE)) log.fine("failure on connection " + connection.getId());
			try {
				connection.close(new CloseReason(
						CloseCodes.PROTOCOL_ERROR, "communication failure"));
			} catch (IOException ignored) {}
		}



		void deregister() {
			connection.removeMessageHandler(this);
		}
	}



	static final Logger log = Logger.getLogger(WebsocketPingerService.class.getName());
}
