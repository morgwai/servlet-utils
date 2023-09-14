// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.websocket.*;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.RemoteEndpoint.Async;



/**
 * Automatically pings and handles pongs from websocket {@link Session connections}. Depending on
 * constructor used, operates in either {@link #WebsocketPingerService(int, int, boolean)
 * expect-timely-pongs mode} or {@link #WebsocketPingerService(int, boolean) keep-alive-only mode}.
 * The service can be used both on a client and a server side.
 * <p>
 * Instances are usually created at app startups and stored in locations easily reachable for
 * {@code Endpoint} instances or the code that manages them (for example as a
 * {@code ServletContext} attribute, a member variable in a class that creates client connections or
 * on some static var).<br/>
 * At an app shutdown {@link #stop()} should be called to terminate the pinging thread.</p>
 * <p>
 * Connections can be registered for pinging using {@link #addConnection(Session)}
 * and deregister using {@link #removeConnection(Session)}.</p>
 * <p>
 * If round-trip time discovery is desired, {@link #addConnection(Session, BiConsumer)} may be used
 * instead to receive RTT reports on each pong.</p>
 */
public class WebsocketPingerService {



	/** Majority of proxy and NAT routers have timeout of at least 60s. */
	public static final int DEFAULT_INTERVAL_SECONDS = 55;
	final long intervalMillis;

	/** Arbitrarily chosen number. */
	public static final int DEFAULT_FAILURE_LIMIT = 4;
	final int failureLimit;

	final boolean synchronizeSending;

	final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
	final ScheduledFuture<?> pingingTask;
	final Random random = new Random();
	final ConcurrentMap<Session, PingPongPlayer> connectionPingPongPlayers =
			new ConcurrentHashMap<>();



	/**
	 * Configures and starts the service in expect-timely-pongs mode: timely matching pongs are
	 * expected, unmatched pongs are ignored.
	 * @param interval interval between pings and also timeout for pongs.
	 *     Cannot be smaller than 1ms.
	 * @param unit unit for {@code interval}.
	 * @param failureLimit limit of lost or timed-out pongs after which the given
	 *     connection is closed. Each matching, timely pong resets connection's failure counter.
	 * @param synchronizeSending whether to synchronize packet sending on the given connection.
	 *     Whether it is necessary depends on the implementation of the container. For example it is
	 *     not necessary on Jetty, but it is on Tomcat: see
	 *     <a href="https://bz.apache.org/bugzilla/show_bug.cgi?id=56026">this bug report</a>.
	 * @throws IllegalArgumentException if {@code interval} is smaller than 1ms.
	 */
	public WebsocketPingerService(
		long interval,
		TimeUnit unit,
		int failureLimit,
		boolean synchronizeSending
	) {
		this.intervalMillis = unit.toMillis(interval);
		if (intervalMillis < 1L) {
			throw new IllegalArgumentException("interval must be at least 1ms");
		}
		this.failureLimit = failureLimit;
		this.synchronizeSending = synchronizeSending;
		pingingTask = scheduler.scheduleAtFixedRate(
				this::pingAllConnections, 0L, intervalMillis, TimeUnit.MILLISECONDS);
	}

	/**
	 * Calls {@link #WebsocketPingerService(long, TimeUnit, int, boolean)
	 * WebsocketPingerService(intervalSeconds, SECONDS, failureLimit, synchronizeSending)}
	 * (expect-timely-pongs mode).
	 */
	public WebsocketPingerService(
		int intervalSeconds,
		int failureLimit,
		boolean synchronizeSending
	) {
		this(intervalSeconds*1000L, TimeUnit.MILLISECONDS, failureLimit, synchronizeSending);
	}

	/**
	 * Calls {@link #WebsocketPingerService(long, TimeUnit, int, boolean)
	 * WebsocketPingerService(intervalSeconds, SECONDS, failureLimit, false)}
	 * (expect-timely-pongs mode).
	 */
	public WebsocketPingerService(int intervalSeconds, int failureLimit) {
		this(intervalSeconds, TimeUnit.SECONDS, failureLimit, false);
	}

	/**
	 * Calls {@link #WebsocketPingerService(long, TimeUnit, int, boolean)
	 * WebsocketPingerService(interval, unit, failureLimit, false)} (expect-timely-pongs mode).
	 */
	public WebsocketPingerService(long interval, TimeUnit unit, int failureLimit) {
		this(interval, unit, failureLimit, false);
	}

	/**
	 * Calls {@link #WebsocketPingerService(long, TimeUnit, int, boolean)
	 * WebsocketPingerService}<code>({@link #DEFAULT_INTERVAL_SECONDS}, SECONDS,
	 * {@link #DEFAULT_FAILURE_LIMIT}, false)</code> (expect-timely-pongs mode).
	 */
	public WebsocketPingerService() {
		this(DEFAULT_INTERVAL_SECONDS, TimeUnit.SECONDS, DEFAULT_FAILURE_LIMIT, false);
	}



	/**
	 * Configures and starts the service in keep-alive-only mode: connection will not be actively
	 * closed unless an {@link IOException} occurs. The params have the same meaning as in
	 * {@link #WebsocketPingerService(long, TimeUnit, int, boolean)}.
	 */
	public WebsocketPingerService(long interval, TimeUnit unit, boolean synchronizeSending) {
		this(interval, unit, -1, synchronizeSending);
	}

	/**
	 * Calls {@link #WebsocketPingerService(long, TimeUnit, boolean)
	 * WebsocketPingerService(intervalSeconds, SECONDS, synchronizeSending)} (keep-alive-only mode).
	 */
	public WebsocketPingerService(int intervalSeconds, boolean synchronizeSending) {
		this(intervalSeconds, TimeUnit.SECONDS, synchronizeSending);
	}

	/**
	 * Calls {@link #WebsocketPingerService(long, TimeUnit, boolean)
	 * WebsocketPingerService(intervalSeconds, SECONDS, false)} (keep-alive-only mode).
	 */
	public WebsocketPingerService(int intervalSeconds) {
		this(intervalSeconds, TimeUnit.SECONDS, false);
	}

	/**
	 * Calls {@link #WebsocketPingerService(long, TimeUnit, boolean)
	 * WebsocketPingerService(interval, unit, false)} (keep-alive-only mode).
	 */
	public WebsocketPingerService(long interval, TimeUnit unit) {
		this(interval, unit, false);
	}



	/**
	 * Registers {@code connection} for pinging by this service and {@code rttObserver} that will
	 * receive round-trip time reports each time a matched pong arrives on {@code connection}.
	 * Usually called in
	 * {@link javax.websocket.Endpoint#onOpen(Session, javax.websocket.EndpointConfig) onOpen(...)}.
	 */
	public void addConnection(Session connection, BiConsumer<Session, Long> rttObserver) {
		connectionPingPongPlayers.put(
			connection,
			new PingPongPlayer(connection, failureLimit, synchronizeSending, rttObserver)
		);
	}

	/**
	 * Registers {@code connection} for pinging by this service. Usually called in
	 * {@link javax.websocket.Endpoint#onOpen(Session, javax.websocket.EndpointConfig) onOpen(...)}.
	 */
	public void addConnection(Session connection) {
		addConnection(connection, null);
	}



	/**
	 * Removes {@code connection} from this service, so it will not be pinged anymore. Usually
	 * called in {@link javax.websocket.Endpoint#onClose(Session, CloseReason) onClose(...)}.
	 * @return {@code true} if {@code connection} had been {@link #addConnection(Session) added} to
	 *     this service before and has been successfully removed by this method, {@code false} if it
	 *     had not been added and no action has taken place.
	 */
	public boolean removeConnection(Session connection) {
		final var pingPongPlayer = connectionPingPongPlayers.remove(connection);
		if (pingPongPlayer != null) {
			pingPongPlayer.deregister();
			return true;
		} else {
			return false;
		}
	}



	/**
	 * Tells whether {@code connection} was {@link #addConnection(Session) registered} in this
	 * service.
	 */
	public boolean containsConnection(Session connection) {
		return connectionPingPongPlayers.containsKey(connection);
	}



	/** Returns the number of currently registered connections. */
	public int getNumberOfConnections() {
		return connectionPingPongPlayers.size();
	}



	/** {@link #pingingTask} */
	void pingAllConnections() {
		if (connectionPingPongPlayers.isEmpty()) return;
		final var pingData = new byte[8];
		random.nextBytes(pingData);
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
		final BiConsumer<Session, Long> rttObserver;

		ByteBuffer pingDataBuffer;
		int failureCount = 0;
		Long pingNanos = null;



		PingPongPlayer(
			Session connection,
			int failureLimit,
			boolean synchronizeSending,
			BiConsumer<Session, Long> rttObserver
		) {
			this.connection = connection;
			this.connector = connection.getAsyncRemote();
			this.synchronizeSending = synchronizeSending;
			this.rttObserver = rttObserver;
			this.failureLimit = failureLimit;
			connection.addMessageHandler(PongMessage.class, this);
		}



		/** Called by the service's worker thread. */
		synchronized void sendPing(byte[] pingData) {
			if (failureLimit >= 0 && pingNanos != null) {  // the previous ping timed out
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
				pingNanos = System.nanoTime();
				pingDataBuffer.rewind();
			} catch (IOException e) {
				closeFailedConnection();
			}
		}

		private void closeFailedConnection() {
			if (log.isLoggable(Level.FINE)) log.fine("failure on connection " + connection.getId());
			try {
				connection.close(new CloseReason(
					CloseCodes.PROTOCOL_ERROR, "communication failure"));
			} catch (IOException ignored) {}
		}



		@Override
		public synchronized void onMessage(PongMessage pong) {
			if (pong.getApplicationData().equals(pingDataBuffer)) {
				if (rttObserver != null) {
					rttObserver.accept(connection, System.nanoTime() - pingNanos);
				}
				pingNanos = null;
				failureCount = 0;
			}
		}



		/** Removes pong handler. Called by the service when connection is deregistered. */
		void deregister() {
			connection.removeMessageHandler(this);
		}
	}



	static final Logger log = Logger.getLogger(WebsocketPingerService.class.getName());
}
