// Copyright 2021 Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
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

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;



/**
 * Automatically pings and handles pongs from websocket {@link Session connections}.
 * Depending on constructor used, operates in either
 * {@link #WebsocketPingerService(int, int, boolean) expect-timely-pongs mode} or
 * {@link #WebsocketPingerService(int, boolean) keep-alive-only mode}.
 * The service can be used both on the client and the server side.
 * <p>
 * Instances are usually created at app startups and stored in locations easily reachable for
 * {@code Endpoint} instances or a code that manages them (for example as a
 * {@code ServletContext} attribute, a field in a class that creates client connections or on some
 * static var).<br/>
 * At app shutdowns, {@link #stop()} should be called to terminate the pinging
 * {@link ScheduledExecutorService scheduler}.</p>
 * <p>
 * Connections can be registered for pinging using {@link #addConnection(Session)}
 * and deregister using {@link #removeConnection(Session)}.</p>
 * <p>
 * If round-trip time discovery is needed, {@link #addConnection(Session, BiConsumer)} variant may
 * be used to receive RTT reports on each pong.</p>
 */
public class WebsocketPingerService {



	/** 55s as majority of proxies and NAT routers have a timeout of at least 60s. */
	public static final int DEFAULT_INTERVAL_SECONDS = 55;

	final int failureLimit;  // negative value means keep-alive-only mode
	final boolean synchronizeSending;

	final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
	final ScheduledFuture<?> pingingTask;
	final Random random = new Random();  // for ping content
	final ConcurrentMap<Session, PingPongPlayer> connectionPingPongPlayers =
			new ConcurrentHashMap<>();



	/**
	 * Configures and starts the service in expect-timely-pongs mode: each timeout adds to a given
	 * connection's failure count, unmatched pongs are ignored.
	 * @param interval interval between pings and also timeout for pongs. While this class does not
	 *     enforce any hard limits, values below 100ms are probably not a good idea in most cases
	 *     and anything below 20ms is pure Sparta.
	 * @param unit unit for {@code interval}.
	 * @param failureLimit limit of lost or timed-out pongs: if exceeded the given connection is
	 *     closed with {@link CloseCodes#PROTOCOL_ERROR}. Each matching, timely pong resets the
	 *     connection's failure counter.
	 * @param synchronizeSending whether to synchronize ping sending on a given connection.
	 *     Whether it is necessary depends on the container implementation being used. For example
	 *     it is not necessary on Jetty, but it is on Tomcat: see
	 *     <a href="https://bz.apache.org/bugzilla/show_bug.cgi?id=56026">this bug report</a>.
	 * @throws IllegalArgumentException if {@code interval} is smaller than 1ms.
	 */
	public WebsocketPingerService(
		long interval,
		TimeUnit unit,
		int failureLimit,
		boolean synchronizeSending
	) {
		this.failureLimit = failureLimit;
		this.synchronizeSending = synchronizeSending;
		pingingTask = scheduler.scheduleAtFixedRate(this::pingAllConnections, 0L, interval, unit);
	}



	/**
	 * Configures and starts the service in keep-alive-only mode: connection will not be actively
	 * closed unless an {@link IOException} occurs. The params have the similar meaning as in
	 * {@link #WebsocketPingerService(long, TimeUnit, int, boolean)}.
	 */
	public WebsocketPingerService(long interval, TimeUnit unit, boolean synchronizeSending) {
		this(interval, unit, -1, synchronizeSending);
	}



	// block of constructor variants using some default values:
	/**
	 * Calls {@link #WebsocketPingerService(long, TimeUnit, int, boolean)
	 * WebsocketPingerService(intervalSeconds, SECONDS, failureLimit, synchronizeSending)}
	 * (expect-timely-pongs mode).
	 */
	public WebsocketPingerService(int intervalSeconds, int failureLimit, boolean synchronizeSending)
	{
		this(intervalSeconds, SECONDS, failureLimit, synchronizeSending);
	}
	/**
	 * Calls {@link #WebsocketPingerService(long, TimeUnit, int, boolean)
	 * WebsocketPingerService(intervalSeconds, SECONDS, failureLimit, false)}
	 * (expect-timely-pongs mode).
	 */
	public WebsocketPingerService(int intervalSeconds, int failureLimit) {
		this(intervalSeconds, SECONDS, failureLimit, false);
	}
	/**
	 * Calls {@link #WebsocketPingerService(long, TimeUnit, int, boolean)
	 * WebsocketPingerService(interval, unit, failureLimit, false)} (expect-timely-pongs mode).
	 */
	public WebsocketPingerService(long interval, TimeUnit unit, int failureLimit) {
		this(interval, unit, failureLimit, false);
	}
	/**
	 * Calls {@link #WebsocketPingerService(long, TimeUnit, boolean)
	 * WebsocketPingerService(intervalSeconds, SECONDS, synchronizeSending)} (keep-alive-only mode).
	 */
	public WebsocketPingerService(int intervalSeconds, boolean synchronizeSending) {
		this(intervalSeconds, SECONDS, synchronizeSending);
	}
	/**
	 * Calls {@link #WebsocketPingerService(long, TimeUnit, boolean)
	 * WebsocketPingerService(intervalSeconds, SECONDS, false)} (keep-alive-only mode).
	 */
	public WebsocketPingerService(int intervalSeconds) {
		this(intervalSeconds, SECONDS, false);
	}
	/**
	 * Calls {@link #WebsocketPingerService(long, TimeUnit, boolean)
	 * WebsocketPingerService(interval, unit, false)} (keep-alive-only mode).
	 */
	public WebsocketPingerService(long interval, TimeUnit unit) {
		this(interval, unit, false);
	}
	/**
	 * Calls {@link #WebsocketPingerService(long, TimeUnit, int, boolean)
	 * WebsocketPingerService}<code>({@link #DEFAULT_INTERVAL_SECONDS}, SECONDS,
	 * {@link #DEFAULT_FAILURE_LIMIT}, false)</code> (expect-timely-pongs mode).
	 * @deprecated this constructor will be switched to keep-alive-only mode in the next major
	 *     version after #DEFAULT_FAILURE_LIMIT is removed. Use
	 *     {@link #WebsocketPingerService(int, int)} with {@link #DEFAULT_INTERVAL_SECONDS} instead
	 *     if you need to retain expect-timely-pongs mode.
	 */
	@Deprecated
	public WebsocketPingerService() {
		this(DEFAULT_INTERVAL_SECONDS, SECONDS, DEFAULT_FAILURE_LIMIT, false);
	}



	/**
	 * Registers {@code connection} for pinging and receiving round-trip time reports via
	 * {@code rttObserver}.
	 * Usually called in
	 * {@link javax.websocket.Endpoint#onOpen(Session, javax.websocket.EndpointConfig) onOpen(...)}.
	 * <p>
	 * {@code rttObserver} will be called by a container {@code Thread} bound by the websocket
	 * {@code Endpoint} concurrency contract, so it must not be performing operations that may
	 * exceed ping interval as it will block processing of the next pong.</p>
	 */
	public void addConnection(Session connection, BiConsumer<Session, Long> rttObserver) {
		connectionPingPongPlayers.put(
			connection,
			new PingPongPlayer(connection, failureLimit, synchronizeSending, rttObserver)
		);
	}

	/**
	 * Registers {@code connection} for pinging.
	 * Usually called in
	 * {@link javax.websocket.Endpoint#onOpen(Session, javax.websocket.EndpointConfig) onOpen(...)}.
	 */
	public void addConnection(Session connection) {
		addConnection(connection, null);
	}



	/**
	 * Removes {@code connection} from this service, so it will not be pinged anymore.
	 * Usually called in
	 * {@link javax.websocket.Endpoint#onClose(Session, CloseReason) onClose(...)}.
	 * @return {@code true} if {@code connection} had been {@link #addConnection(Session) added} to
	 *     this service before and has been successfully removed by this method, {@code false} if it
	 *     had not been added and no action has taken place.
	 */
	public boolean removeConnection(Session connection) {
		return connectionPingPongPlayers.remove(connection) != null;
	}



	/** Whether {@code connection} is {@link #addConnection(Session) registered} in this service. */
	public boolean containsConnection(Session connection) {
		return connectionPingPongPlayers.containsKey(connection);
	}



	/** The number of currently registered connections. */
	public int getNumberOfConnections() {
		return connectionPingPongPlayers.size();
	}



	/**
	 * Stops the service.
	 * After a call to this method the service becomes no longer usable and should be discarded.
	 * @return connections that were registered at the time this method was called.
	 */
	public Set<Session> stop(long timeout, TimeUnit unit) {
		pingingTask.cancel(true);
		scheduler.shutdown();
		for (var pingPongPlayer: connectionPingPongPlayers.values()) pingPongPlayer.deregister();
		try {
			scheduler.awaitTermination(timeout, unit);
		} catch (InterruptedException ignored) {}
		if ( !scheduler.isTerminated()) {  // this probably never happens
			log.warning("pinging scheduler failed to terminate");
			scheduler.shutdownNow();  // probably won't help as the task was cancelled already
		}
		final var remaining = Set.copyOf(connectionPingPongPlayers.keySet());
		connectionPingPongPlayers.clear();
		return remaining;
	}

	/** Calls {@link #stop(long, TimeUnit)} with a 500ms timeout. */
	public Set<Session> stop() {
		return stop(500L, MILLISECONDS);
	}



	/** {@link #pingingTask} */
	void pingAllConnections() {
		if (connectionPingPongPlayers.isEmpty()) return;
		final var pingData = new byte[8];  // enough to avoid collisions, but not overuse random
		random.nextBytes(pingData);
		for (var pingPongPlayer: connectionPingPongPlayers.values()) {
			if (Thread.interrupted()) break;
			pingPongPlayer.sendPing(pingData);
		}
	}



	/** Plays ping-pong with a single associated connection. */
	static class PingPongPlayer implements MessageHandler.Whole<PongMessage> {

		final Session connection;
		final Async connector;
		final int failureLimit;
		final boolean synchronizeSending;
		final BiConsumer<Session, Long> rttObserver;

		ByteBuffer pingDataBuffer;
		int failureCount = 0;
		/**
		 * Send timestamp of the most recent ping to which a matching pong has not been received
		 * yet. {@code null} means that a matching pong to the most recent ping has been already
		 * received.
		 */
		Long pingTimestampNanos = null;



		/** Constructor for both modes: negative {@code failureLimit} means keep-alive-only. */
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



		/** Called by the service's worker {@code  Thread}. */
		synchronized void sendPing(byte[] pingData) {
			if (failureLimit >= 0 && pingTimestampNanos != null) {
				// the previous ping has timed out
				failureCount++;
				if (failureCount > failureLimit) {
					closeFailedConnection("too many lost or timed-out pongs");
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
				pingTimestampNanos = System.nanoTime();
				pingDataBuffer.rewind();  // for comparing with incoming pong data
			} catch (IOException e) {
				// on most container implementations the connection is PROBABLY already closed, but
				// just in case:
				closeFailedConnection("failed to send ping");
			}
		}

		private void closeFailedConnection(String reason) {
			if (log.isLoggable(Level.FINE)) {
				log.fine("failure on connection " + connection.getId() + ": " + reason);
			}
			try {
				connection.close(new CloseReason(CloseCodes.PROTOCOL_ERROR, reason));
			} catch (IOException ignored) {}  // this MUST mean the connection is already closed...
		}



		@Override
		public void onMessage(PongMessage pong) {
			final var pongTimestampNanos = System.nanoTime();
			Long rttToReport = null;
			synchronized (this) {
				if (pong.getApplicationData().equals(pingDataBuffer)) {
					rttToReport = rttObserver != null && !(/*collision*/ pingTimestampNanos == null)
							? pongTimestampNanos - pingTimestampNanos
							: null;
					pingTimestampNanos = null;  // indicate the expected pong was received on time
					failureCount = 0;
				}
			}
			if (rttToReport != null) rttObserver.accept(connection, rttToReport);
		}



		/**
		 * Removes pong handler.
		 * Called by the service on connections remaining after {@link #stop()}.
		 */
		void deregister() {
			try {
				connection.removeMessageHandler(this);
			} catch (RuntimeException ignored) {
				// connection was closed in the mean time and some container implementations
				// throw a RuntimeException in case of any operation on a closed connection
			}
		}
	}



	static final Logger log = Logger.getLogger(WebsocketPingerService.class.getName());



	@Deprecated(forRemoval = true)
	public static final int DEFAULT_FAILURE_LIMIT = 4;
}
