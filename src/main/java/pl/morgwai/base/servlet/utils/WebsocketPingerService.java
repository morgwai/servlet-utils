// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;
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
	public static final int DEFAULT_PING_INTERVAL = 55;
	final int pingIntervalSeconds;

	/**
	 * Arbitrarily chosen number.
	 */
	public static final int DEFAULT_MAX_MALFORMED_PONG_COUNT = 5;
	final int maxMalformedPongCount;

	final Thread pingingThread = new Thread(this::pingConnectionsPeriodically);

	final ConcurrentMap<Session, PingPongPlayer> connections = new ConcurrentHashMap<>();

	final boolean synchronizePingSending;



	/**
	 * Configures and starts the service.
	 * @param pingIntervalSeconds how often to ping all connections.
	 * @param maxMalformedPongCount limit after which a given connection is closed. Each valid,
	 *     timely pong resets connection's counter. Pongs received after {@code pingIntervalSeconds}
	 *     count as malformed.
	 * @param synchronizePingSending whether to synchronize ping sending on the given connection.
	 *     Whether it is necessary depends on the implementation of the container. For example it is
	 *     not necessary on Jetty, but it is on Tomcat: see
	 *     <a href='https://bz.apache.org/bugzilla/show_bug.cgi?id=56026'>this bug report</a>.
	 */
	public WebsocketPingerService(
			int pingIntervalSeconds, int maxMalformedPongCount, boolean synchronizePingSending) {
		this.pingIntervalSeconds = pingIntervalSeconds;
		this.maxMalformedPongCount = maxMalformedPongCount;
		this.synchronizePingSending = synchronizePingSending;
		pingingThread.start();
		if (log.isInfoEnabled()) log.info("websockets will be pinged every " + pingIntervalSeconds
				+ "s,  malformed pong limit: " + maxMalformedPongCount);
	}



	/**
	 * Calls {@link #WebsocketPingerService(int, int, boolean)
	 * WebsocketPingerService}(pingIntervalSeconds, maxMalformedPongCount, false).
	 */
	public WebsocketPingerService(int pingIntervalSeconds, int maxMalformedPongCount) {
		this(pingIntervalSeconds, maxMalformedPongCount, false);
	}



	/**
	 * Calls {@link #WebsocketPingerService(int, int, boolean)
	 * WebsocketPingerService}({@link #DEFAULT_PING_INTERVAL},
	 * {@link #DEFAULT_MAX_MALFORMED_PONG_COUNT}, false).
	 */
	public WebsocketPingerService() {
		this(DEFAULT_PING_INTERVAL, DEFAULT_MAX_MALFORMED_PONG_COUNT, false);
	}



	/**
	 * Registers {@code connection} for pinging. Usually called in
	 * {@link javax.websocket.Endpoint#onOpen(Session, javax.websocket.EndpointConfig)}.
	 */
	public void addConnection(Session connection) {
		PingPongPlayer player =
				new PingPongPlayer(connection, maxMalformedPongCount, synchronizePingSending);
		connection.addMessageHandler(PongMessage.class, player);
		connections.put(connection, player);
	}



	/**
	 * Deregisters {@code connection}. Usually called in
	 * {@link javax.websocket.Endpoint#onClose(Session, CloseReason)}.
	 */
	public void removeConnection(Session connection) {
		connection.removeMessageHandler(connections.get(connection));
		connections.remove(connection);
	}



	/**
	 * For {@link #pingingThread}.
	 */
	private void pingConnectionsPeriodically() {
		while (true) {
			try {
				var startMillis = System.currentTimeMillis();
				for (PingPongPlayer player: connections.values()) player.ping();
				Thread.sleep(Math.max(0l,
						pingIntervalSeconds * 1000l - System.currentTimeMillis() + startMillis));
			} catch (InterruptedException ignored) {
				return;  // stop() was called
			}
		}
	}



	/**
	 * Returns the number of currently registered connections.
	 */
	public int getConnectionsSize() {
		return connections.size();
	}



	/**
	 * Stops the service. After a call to this method the service becomes no longer usable and
	 * should be discarded.
	 * @return remaining registered connections.
	 */
	public ConcurrentMap<Session, PingPongPlayer> stop() {
		pingingThread.interrupt();
		try {
			pingingThread.join();
			log.info("pinger stopped");
		} catch (InterruptedException ignored) {}
		return connections;
	}



	/**
	 * Plays ping-pong with a single associated connection.
	 */
	static class PingPongPlayer implements MessageHandler.Whole<PongMessage> {

		final Session connection;
		final int maxMalformedPongCount;
		final boolean synchronizePingSending;



		PingPongPlayer(
				Session connection, int maxMalformedPongCount, boolean synchronizePingSending) {
			this.connection = connection;
			this.maxMalformedPongCount = maxMalformedPongCount;
			this.synchronizePingSending = synchronizePingSending;
		}



		int malformedCount = 0;
		byte[] pingData = new byte[1];  // to not crash on some random pong before 1st ping
		ByteBuffer wrapper = ByteBuffer.wrap(this.pingData);
		final Random random = new Random();



		void ping() {
			byte[] newPingData = new byte[64];
			random.nextBytes(newPingData);
			synchronized (this) {
				pingData = newPingData;
				wrapper = ByteBuffer.wrap(pingData);
				try {
					if (synchronizePingSending) {
						synchronized (connection) {
							connection.getAsyncRemote().sendPing(wrapper);
						}
					} else {
						connection.getAsyncRemote().sendPing(wrapper);
					}
				} catch (IllegalArgumentException | IOException ignored) {
					// connection was closed in a meantime
				}
				wrapper.rewind();
			}
		}



		@Override
		public synchronized void onMessage(PongMessage pong) {
			if ( ! pong.getApplicationData().equals(wrapper)) {
				malformedCount++;
				if (malformedCount >= maxMalformedPongCount) {
					if (log.isInfoEnabled()) {
						log.info("malformed pong count from " + connection.getId()
								+ " exceeded, closing connection");
					}
					try {
						connection.close(new CloseReason(
								CloseCodes.PROTOCOL_ERROR, "malformed pong count exceeded"));
					} catch (IOException ignored) {}
				}
			} else {
				malformedCount = 0;
			}
		}
	}



	static final Logger log = LoggerFactory.getLogger(WebsocketPingerService.class.getName());
}
