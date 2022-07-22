// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.websocket.*;
import javax.websocket.CloseReason.CloseCodes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * Automatically pings and handles pongs from websocket connections.
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



	/**
	 * Majority of proxy and NAT routers have timeout of at least 60s.
	 */
	public static final int DEFAULT_INTERVAL = 55;
	final int intervalSeconds;

	/**
	 * Arbitrarily chosen number.
	 */
	public static final int DEFAULT_FAILURE_LIMIT = 4;
	final int failureLimit;

	/**
	 * Economic value to reduce use of {@link Random}.
	 */
	public static final int DEFAULT_PING_SIZE = 4;
	final int pingSize;

	final boolean synchronizeSending;

	final boolean keepAliveOnly;

	final Thread pingingThread;

	final ConcurrentMap<Session, PingPongPlayer> connections = new ConcurrentHashMap<>();

	final Random random = new Random();



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
		if ( ( ! keepAliveOnly) && failureLimit < 0) {
			throw new IllegalArgumentException("failure limit cannot be negative");
		}
		this.keepAliveOnly = keepAliveOnly;
		this.intervalSeconds = intervalSeconds;
		this.failureLimit = failureLimit;
		this.pingSize = pingSize;
		this.synchronizeSending = synchronizeSending;
		if (keepAliveOnly) {
			pingingThread = new Thread(this::sendKeepAlivePeriodically);
		} else {
			pingingThread = new Thread(this::pingConnectionsPeriodically);
		}
		pingingThread.start();
		if (log.isInfoEnabled()) {
			log.info("websockets will be pinged every " + intervalSeconds
					+ "s,  failure limit: " + failureLimit + ", ping size: "
					+ pingSize + "B, synchronize ping sending: " + synchronizeSending);
		}
	}



	/**
	 * Configures and starts the service in the standard ping-pong mode.
	 * @param intervalSeconds interval between pings.
	 * @param failureLimit limit of lost or malformed pongs after which the given connection is
	 *     closed. Pongs received after {@code pingIntervalSeconds} count as failures. Each valid,
	 *     timely pong resets connection's failure counter.
	 * @param pingSize size of the ping data to send. This comes from {@link Random}, so an economic
	 *     value is recommended.
	 * @param synchronizeSending whether to synchronize ping sending on the given connection.
	 *     Whether it is necessary depends on the implementation of the container. For example it is
	 *     not necessary on Jetty, but it is on Tomcat: see
	 *     <a href='https://bz.apache.org/bugzilla/show_bug.cgi?id=56026'>this bug report</a>.
	 */
	public WebsocketPingerService(
			int intervalSeconds, int failureLimit, int pingSize, boolean synchronizeSending) {
		this(false, intervalSeconds, failureLimit, pingSize, synchronizeSending);
	}

	/**
	 * Calls {@link #WebsocketPingerService(int, int, int, boolean)
	 * WebsocketPingerService(intervalSeconds, failureLimit, pingSize, false)} (ping-pong mode).
	 */
	public WebsocketPingerService(int intervalSeconds, int failureLimit, int pingSize)
	{
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
	 * Configures and starts the service in keep-alive-only mode: failures are not counted.
	 * See {@link #WebsocketPingerService(int, int, int, boolean)} for param descriptions.
	 */
	public WebsocketPingerService(int intervalSeconds, boolean synchronizeSending) {
		this(true, intervalSeconds, -1, 1, synchronizeSending);
	}

	/**
	 * Calls {@link #WebsocketPingerService(int, boolean)
	 * WebsocketPingerService}<code>(intervalSeconds, false)</code>
	 * (keep-alive-only mode).
	 */
	public WebsocketPingerService(int intervalSeconds) {
		this(intervalSeconds, false);
	}



	/**
	 * Registers {@code connection} for pinging. Usually called in
	 * {@link javax.websocket.Endpoint#onOpen(Session, javax.websocket.EndpointConfig)}.
	 */
	public void addConnection(Session connection) {
		// ConcurrentHashMap doesn't allow null values so player needs to be created in both modes
		PingPongPlayer player = new PingPongPlayer(connection, failureLimit, synchronizeSending);
		if ( ! keepAliveOnly) {
			connection.addMessageHandler(PongMessage.class, player);
		}
		connections.put(connection, player);
	}



	/**
	 * Deregisters {@code connection}. Usually called in
	 * {@link javax.websocket.Endpoint#onClose(Session, CloseReason)}.
	 */
	public void removeConnection(Session connection) {
		if ( ! keepAliveOnly) connection.removeMessageHandler(connections.get(connection));
		connections.remove(connection);
	}



	/**
	 * Returns the number of currently registered connections.
	 */
	public int getNumberOfConnections() {
		return connections.size();
	}



	/**
	 * For {@link #pingingThread}.
	 */
	private void sendKeepAlivePeriodically() {
		var pingData = new byte[1];
		pingData[0] = (byte) 69;  // arbitrarily chosen byte ;-)
		var wrapper = ByteBuffer.wrap(pingData);
		while (true) {
			try {
				var startMillis = System.currentTimeMillis();
				for (var connection: connections.keySet()) {
					try {
						if (synchronizeSending) {
							synchronized (connection) {
								connection.getAsyncRemote().sendPong(wrapper);
							}
						} else {
							connection.getAsyncRemote().sendPong(wrapper);
						}
					} catch (IOException ignored) {}  // connection was closed in a meantime
					wrapper.rewind();
				}
				Thread.sleep(Math.max(0l,
						intervalSeconds * 1000l - System.currentTimeMillis() + startMillis));
			} catch (InterruptedException ignored) {
				return;  // stop() was called
			}
		}
	}



	/**
	 * For {@link #pingingThread}.
	 */
	private void pingConnectionsPeriodically() {
		while (true) {
			try {
				var startMillis = System.currentTimeMillis();
				byte[] pingData = new byte[pingSize];
				random.nextBytes(pingData);
				for (PingPongPlayer player: connections.values()) player.ping(pingData);
				Thread.sleep(Math.max(0l,
						intervalSeconds * 1000l - System.currentTimeMillis() + startMillis));
			} catch (InterruptedException ignored) {
				return;  // stop() was called
			}
		}
	}



	/**
	 * Stops the service. After a call to this method the service becomes no longer usable and
	 * should be discarded.
	 * @return remaining registered connections.
	 */
	public Set<Session> stop() {
		pingingThread.interrupt();
		try {
			pingingThread.join();
			log.info("pinger stopped");
		} catch (InterruptedException ignored) {}
		for (var entry: connections.entrySet()) {
			entry.getKey().removeMessageHandler(entry.getValue());
		}
		return connections.keySet();
	}



	/**
	 * Plays ping-pong with a single associated connection.
	 */
	static class PingPongPlayer implements MessageHandler.Whole<PongMessage> {

		final Session connection;
		final int failureLimit;
		final boolean synchronizeSending;



		PingPongPlayer(Session connection, int failureLimit, boolean synchronizeSending) {
			this.connection = connection;
			this.failureLimit = failureLimit;
			this.synchronizeSending = synchronizeSending;
		}



		int failureCount = 0;
		boolean awaitingPong = false;
		byte[] pingData = new byte[1];  // to not crash on some random pong before 1st ping
		ByteBuffer wrapper = ByteBuffer.wrap(this.pingData);



		synchronized void ping(byte[] pingData) {
			if (awaitingPong) failureCount++;
			if (failureCount > failureLimit) {
				closeFailedConnection(connection);
				return;
			}
			this.pingData = pingData;
			wrapper = ByteBuffer.wrap(this.pingData);
			try {
				if (synchronizeSending) {
					synchronized (connection) {
						connection.getAsyncRemote().sendPing(wrapper);
					}
				} else {
					connection.getAsyncRemote().sendPing(wrapper);
				}
				awaitingPong = true;
			} catch (IOException ignored) {}  // connection was closed in a meantime
			wrapper.rewind();
		}



		@Override
		public synchronized void onMessage(PongMessage pong) {
			awaitingPong = false;
			if (pong.getApplicationData().equals(wrapper)) {
				failureCount = 0;
			} else {
				failureCount++;
				if (failureCount > failureLimit) closeFailedConnection(connection);
			}
		}



		void closeFailedConnection(Session connection) {
			if (log.isDebugEnabled()) {
				log.debug("failure limit from " + connection.getId()
						+ " exceeded, closing connection");
			}
			try {
				connection.close(new CloseReason(
						CloseCodes.PROTOCOL_ERROR, "ping failure limit exceeded"));
			} catch (IOException ignored) {}
		}
	}



	static final Logger log = LoggerFactory.getLogger(WebsocketPingerService.class.getName());
}
