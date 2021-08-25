// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.Endpoint;
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
 * {@link Endpoint#onOpen(Session, javax.websocket.EndpointConfig)} method using
 * {@link #addConnection(Session)} and de-register in {@link Endpoint#onClose(Session, CloseReason)}
 * using {@link #removeConnection(Session)}.</p>
 */
public class WebsocketPinger {



	/**
	 * @param pingIntervalSeconds how often to ping all connections.
	 * @param maxMalformedPongCount limit after which a given connection is closed. Each valid,
	 *     timely pong resets connection's counter. Pongs received after {@code pingIntervalSeconds}
	 *     count as malformed.
	 */
	public WebsocketPinger(int pingIntervalSeconds, int maxMalformedPongCount) {
		this.pingIntervalSeconds = pingIntervalSeconds;
		this.maxMalformedPongCount = maxMalformedPongCount;
		pingingThread.start();
	}

	final int pingIntervalSeconds;
	final int maxMalformedPongCount;
	final Thread pingingThread = new Thread(() -> pingPeriodically());



	/**
	 * Uses {@link #DEFAULT_PING_INTERVAL} and {@link #DEFAULT_MAX_MALFORMED_PONG_COUNT} to call
	 * {@link #WebsocketPinger(int, int)}.
	 */
	public WebsocketPinger() {
		this(DEFAULT_PING_INTERVAL, DEFAULT_MAX_MALFORMED_PONG_COUNT);
	}

	/**
	 * Majority of proxy and NAT routers have timeout of at least 60s.
	 */
	public static final int DEFAULT_PING_INTERVAL = 55;

	/**
	 * Arbitrarily chosen number.
	 */
	public static final int DEFAULT_MAX_MALFORMED_PONG_COUNT = 5;



	public void addConnection(Session connection) {
		PongHandler handler = new PongHandler(connection);
		connection.addMessageHandler(PongMessage.class, handler);
		connections.put(connection, handler);
	}

	final ConcurrentMap<Session, PongHandler> connections = new ConcurrentHashMap<>();



	public void removeConnection(Session connection) {
		connections.remove(connection);
	}



	public void stop() {
		pingingThread.interrupt();
		try {
			pingingThread.join();
			log.info("pinger stopped");
		} catch (InterruptedException e) {}
	}



	/**
	 * For {@link #pingingThread}.
	 */
	private void pingPeriodically() {
		while (true) {
			try {
				Thread.sleep(pingIntervalSeconds * 1000l);
				for (PongHandler handler: connections.values()) handler.ping();
			} catch (InterruptedException e) {
				return;  // stop() was called
			}
		}
	}



	private class PongHandler implements MessageHandler.Whole<PongMessage> {

		final Session connection;

		PongHandler(Session connection) { this.connection = connection; }



		int malformedCount = 0;
		byte[] pingData = new byte[1];  // buffer refuses to wrap null and it must wrap something
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
