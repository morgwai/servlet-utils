// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.MessageHandler;
import javax.websocket.PongMessage;
import javax.websocket.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * Automatically pings and handles pongs from websocket connections.
 * <p>
 * Initial instances are usually created at app startup and stored in a location easily reachable
 * for endpoint instances (for example on static var in app's ServletContextListener). Additional
 * instances may be added later if number of connections is too big to handle for the existing ones.
 * </p>
 * <p>Endpoint instances should register themselves for pinging in their
 * {@link javax.websocket.Endpoint#onOpen(Session, javax.websocket.EndpointConfig)} method using
 * {@link #addConnection(Session)} and deregister in
 * {@link javax.websocket.Endpoint#onClose(Session, CloseReason)} using
 * {@link #removeConnection(Session)}.</p>
 */
public class WebsocketPinger {



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

	final Thread pingingThread = new Thread(() -> pingConnectionsPeriodically());

	final ConcurrentMap<Session, PingPongPlayer> connections = new ConcurrentHashMap<>();



	/**
	 * Configures and starts the service.
	 * @param pingIntervalSeconds how often to ping all connections.
	 * @param maxMalformedPongCount limit after which a given connection is closed. Each valid,
	 *     timely pong resets connection's counter. Pongs received after {@code pingIntervalSeconds}
	 *     count as malformed.
	 */
	public WebsocketPinger(int pingIntervalSeconds, int maxMalformedPongCount) {
		this.pingIntervalSeconds = pingIntervalSeconds;
		this.maxMalformedPongCount = maxMalformedPongCount;
		pingingThread.start();
		if (log.isInfoEnabled()) log.info("websockets will be pinged every " + pingIntervalSeconds
				+ "s,  malformed pong limit: " + maxMalformedPongCount);
	}



	/**
	 * Calls {@link #WebsocketPinger(int, int) WebsocketPinger}({@link #DEFAULT_PING_INTERVAL},
	 * {@link #DEFAULT_MAX_MALFORMED_PONG_COUNT}).
	 */
	public WebsocketPinger() {
		this(DEFAULT_PING_INTERVAL, DEFAULT_MAX_MALFORMED_PONG_COUNT);
	}



	/**
	 * Registers {@code connection} for pinging. Usually called in
	 * {@link javax.websocket.Endpoint#onOpen(Session, javax.websocket.EndpointConfig)}.
	 */
	public void addConnection(Session connection) {
		PingPongPlayer player = new PingPongPlayer(connection, maxMalformedPongCount);
		connection.addMessageHandler(PongMessage.class, player);
		connections.put(connection, player);
	}



	/**
	 * Deregisters {@code connection}. Usually called in
	 * {@link javax.websocket.Endpoint#onClose(Session, CloseReason)}.
	 */
	public void removeConnection(Session connection) {
		connections.remove(connection);
	}



	/**
	 * For {@link #pingingThread}.
	 */
	private void pingConnectionsPeriodically() {
		while (true) {
			try {
				Thread.sleep(pingIntervalSeconds * 1000l);
				for (PingPongPlayer player: connections.values()) player.ping();
			} catch (InterruptedException e) {
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
		} catch (InterruptedException e) {}
		return connections;
	}



	/**
	 * Plays ping-pong with a single associated connection.
	 */
	static class PingPongPlayer implements MessageHandler.Whole<PongMessage> {

		final Session connection;
		final int maxMalformedPongCount;

		PingPongPlayer(Session connection, int maxMalformedPongCount) {
			this.connection = connection;
			this.maxMalformedPongCount = maxMalformedPongCount;
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
					connection.getAsyncRemote().sendPing(wrapper);
				} catch (IllegalArgumentException | IOException e1) {
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
					} catch (IOException e) {}
				}
			} else {
				malformedCount = 0;
			}
		}
	}



	static final Logger log = LoggerFactory.getLogger(WebsocketPinger.class.getName());
}
