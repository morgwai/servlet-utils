/*
 * Copyright (c) Piotr Morgwai Kotarbinski
 */
package pl.morgwai.base.servlet.websocket.pinger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import javax.websocket.PongMessage;
import javax.websocket.Session;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.CloseReason;
import javax.websocket.MessageHandler;



/**
 * Automatically pings and handles pongs from websocket connections.
 */
public class WebsocketPinger {



	/**
	 * Majority of proxy and NAT routers have timeout of at least 60s.
	 */
	public static final int DEFAULT_PING_INTERVAL = 55;

	public static final int DEFAULT_MAX_MALFORMED_PONG_COUNT = 5;



	int pingIntervalSeconds;
	int maxMalformedPongCount;
	Map<Session, PongHandler> connections;
	Thread pingingThread;



	public void addConnection(Session connection) {
		PongHandler handler = new PongHandler(connection);
		connection.addMessageHandler(PongMessage.class, handler);
		connections.put(connection, handler);
	}



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



	public WebsocketPinger() {
		this(DEFAULT_PING_INTERVAL, DEFAULT_MAX_MALFORMED_PONG_COUNT);
	}



	public WebsocketPinger(int pingIntervalSeconds, int maxMalformedPongCount) {
		this.pingIntervalSeconds = pingIntervalSeconds;
		this.maxMalformedPongCount = maxMalformedPongCount;
		this.connections = new ConcurrentHashMap<>();
		this.pingingThread = new Thread(() -> pingPeriodically());
		this.pingingThread.start();
	}



	private class PongHandler implements MessageHandler.Whole<PongMessage> {



		Session connection;
		byte[] pingData;
		ByteBuffer wrapper;
		int malformedCount;
		Random random;



		void ping() {
			byte[] newPingData = new byte[64];
			random.nextBytes(newPingData);
			synchronized(this) {
				pingData = newPingData;
				wrapper = ByteBuffer.wrap(pingData);
				try {
					synchronized (connections) {
						connection.getAsyncRemote().sendPing(wrapper);
					}
				} catch (IllegalArgumentException | IOException e) {
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
					log.info("malformed pong count from " + connection.getId()
							+ " exceeded, closing connection");
					try {
						connection.close(new CloseReason(
								CloseCodes.PROTOCOL_ERROR, "malformed pong count exceeded"));
					} catch (IOException e) {}
				}
			} else {
				malformedCount = 0;
			}
		}



		PongHandler(Session connection) {
			this.connection = connection;
			this.pingData = new byte[1];
			this.wrapper = ByteBuffer.wrap(this.pingData);
			this.malformedCount = 0;
			this.random = new Random();
		}
	}



	static final Logger log = Logger.getLogger(WebsocketPinger.class.getName());
}
