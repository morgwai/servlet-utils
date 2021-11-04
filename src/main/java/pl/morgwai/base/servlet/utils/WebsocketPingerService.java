// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import jakarta.websocket.CloseReason;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.PongMessage;
import jakarta.websocket.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * Automatically pings and handles pongs from websocket connections.
 * <p>
 * Instances are usually created at app startup and stored in a location easily reachable
 * for endpoint instances (for example on static var in app's ServletContextListener).</p>
 * <p>
 * Endpoint instances should register themselves for pinging in their
 * {@link jakarta.websocket.Endpoint#onOpen(Session, jakarta.websocket.EndpointConfig)} method using
 * {@link #addConnection(Session)} and deregister in
 * {@link jakarta.websocket.Endpoint#onClose(Session, CloseReason)} using
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



	/**
	 * Configures and starts the service.
	 * @param pingIntervalSeconds how often to ping all connections.
	 * @param maxMalformedPongCount limit after which a given connection is closed. Each valid,
	 *     timely pong resets connection's counter. Pongs received after {@code pingIntervalSeconds}
	 *     count as malformed.
	 */
	public WebsocketPingerService(int pingIntervalSeconds, int maxMalformedPongCount) {
		this.pingIntervalSeconds = pingIntervalSeconds;
		this.maxMalformedPongCount = maxMalformedPongCount;
		pingingThread.start();
		if (log.isInfoEnabled()) log.info("websockets will be pinged every " + pingIntervalSeconds
				+ "s,  malformed pong limit: " + maxMalformedPongCount);
	}



	/**
	 * Calls {@link #WebsocketPingerService(int, int)
	 * WebsocketPingerService}({@link #DEFAULT_PING_INTERVAL},
	 * {@link #DEFAULT_MAX_MALFORMED_PONG_COUNT}).
	 */
	public WebsocketPingerService() {
		this(DEFAULT_PING_INTERVAL, DEFAULT_MAX_MALFORMED_PONG_COUNT);
	}



	/**
	 * Registers {@code connection} for pinging. Usually called in
	 * {@link jakarta.websocket.Endpoint#onOpen(Session, jakarta.websocket.EndpointConfig)}.
	 */
	public void addConnection(Session connection) {
		PingPongPlayer player = new PingPongPlayer(connection, maxMalformedPongCount);
		connection.addMessageHandler(PongMessage.class, player);
		connections.put(connection, player);
	}



	/**
	 * Deregisters {@code connection}. Usually called in
	 * {@link jakarta.websocket.Endpoint#onClose(Session, CloseReason)}.
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
