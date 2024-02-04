// Copyright 2021 Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils;

import java.io.IOException;
import java.nio.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.websocket.*;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.RemoteEndpoint.Async;

import static java.util.concurrent.TimeUnit.*;



/**
 * Automatically pings and handles pongs from websocket {@link Session connections}.
 * Depending on constructor used, operates in either
 * {@link #WebsocketPingerService(long, TimeUnit, int, String, ScheduledExecutorService, boolean)
 * expect-timely-pongs mode} or
 * {@link #WebsocketPingerService(long, TimeUnit, String, ScheduledExecutorService, boolean)
 * keep-alive-only mode}. The service can be used both on the client and the server side.
 * <p>
 * Instances are usually created at app startups and stored in locations easily reachable for
 * {@code Endpoint} instances or a code that manages them (for example as a
 * {@code ServletContext} attribute, a field in a class that creates client
 * {@link Session connections} or on some static var).<br/>
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



	// design decision note: while it is possible to use unsolicited pongs for keep-alive-only,
	// some ping-pong implementations confuse them with malformed pongs and close connections.
	// Furthermore, using ping-pong allows to provide RTT reports in keep-alive-only mode also.



	/** 55s as majority of proxies and NAT routers have a timeout of at least 60s. */
	public static final int DEFAULT_INTERVAL_SECONDS = 55;
	final long intervalNanos;

	/** Default {@link MessageDigest} for ping content hashing. */
	public static final String DEFAULT_HASH_FUNCTION = "SHA3-256";
	final String hashFunction;

	final int failureLimit;  // negative value means keep-alive-only mode
	final boolean synchronizeSending;

	final ScheduledExecutorService scheduler;
	/** Periodic on {@link #scheduler}, executes {@link PingPongPlayer#sendPing()}. */
	final ConcurrentMap<Session, ScheduledFuture<?>> connectionPingingTasks =
			new ConcurrentHashMap<>();
	final ConcurrentMap<Session, PingPongPlayer> connectionPingPongPlayers =
			new ConcurrentHashMap<>();



	/**
	 * Configures and starts the service in {@code expect-timely-pongs} mode: each timeout adds to a
	 * given {@link Session connection}'s failure count, unmatched pongs are ignored.
	 * @param interval interval between pings and also timeout for pongs. While this class does not
	 *     enforce any hard limits, values below 100ms are probably not a good idea in most cases
	 *     and anything below 20ms is pure Sparta.
	 * @param unit unit for {@code interval}.
	 * @param failureLimit limit of lost or timed-out pongs: if exceeded, then the given
	 *     {@link Session connection} is closed with {@link CloseCodes#PROTOCOL_ERROR}. Each
	 *     matching, timely pong resets the {@link Session connection}'s failure counter.
	 * @param hashFunction name of the {@link MessageDigest} to use for ping content hashing. This
	 *     must be supported by a registered {@link java.security.Provider security Provider} and
	 *     {@link MessageDigest#getDigestLength() the length of produced hashes} must not exceed
	 *     {@code (125 - (2 * Long.BYTES))} bytes, otherwise an {@link IllegalArgumentException}
	 *     will be thrown.
	 * @param scheduler used for scheduling pings. Upon a call to {@link #stop()}, {@code scheduler}
	 *     will be {@link ScheduledExecutorService#shutdown() shutdown}.
	 * @param synchronizeSending whether to synchronize ping sending on a given
	 *     {@link Session connection}. Whether it is necessary depends on the container
	 *     implementation being used. For example it is not necessary on Jetty, but it is on Tomcat:
	 *     see <a href="https://bz.apache.org/bugzilla/show_bug.cgi?id=56026">this bug report</a>.
	 *     <br/>
	 *     When using containers that do require such synchronization, all other message sending by
	 *     {@code Endpoint}s must also be synchronized on the respective {@link Session connections}
	 *     (please don't shoot the messenger...).
	 * @throws IllegalArgumentException if {@code hashFunction} is not supported or produces too
	 *     long hashes.
	 */
	public WebsocketPingerService(
		long interval,
		TimeUnit unit,
		int failureLimit,
		String hashFunction,
		ScheduledExecutorService scheduler,
		boolean synchronizeSending
	) {
		this.intervalNanos = unit.toNanos(interval);
		this.failureLimit = failureLimit;
		this.synchronizeSending = synchronizeSending;
		this.hashFunction = hashFunction;
		this.scheduler = scheduler;
		final MessageDigest testInstance;
		try {
			testInstance = MessageDigest.getInstance(hashFunction);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalArgumentException(e);
		}
		if (testInstance.getDigestLength() > 125 - (2 * Long.BYTES)) {
			throw new IllegalArgumentException(hashFunction + " produces too long hashes");
		}
	}

	/**
	 * Calls {@link #WebsocketPingerService(long, TimeUnit, int, String, ScheduledExecutorService,
	 * boolean) WebsocketPingerService} <code>(interval, unit, failureLimit,
	 * {@value #DEFAULT_HASH_FUNCTION}, {@link #newDefaultScheduler()}, false)</code>
	 * ({@code expect-timely-pongs} mode).
	 */
	public WebsocketPingerService(
		long interval,
		TimeUnit unit,
		int failureLimit
	) {
		this(
			interval,
			unit,
			failureLimit,
			DEFAULT_HASH_FUNCTION,
			newDefaultScheduler(),
			false
		);
	}

	// design decision note: using interval as a timeout simplifies things A LOT. Using a separate
	// SHORTER duration for a timeout is still pretty feasible and may be implemented if there's
	// enough need for it. Allowing a timeouts longer than intervals OTOH would require scheduling
	// of on-time-out actions and is almost certainly not worth the effort.



	/**
	 * Configures and starts the service in {@code keep-alive-only} mode:
	 * {@link Session connections} will <b>not</b> be actively closed unless an {@link IOException}
	 * occurs or matching pongs are received in a nonconsecutive order. The params have the similar
	 * meaning as in {@link
	 * #WebsocketPingerService(long, TimeUnit, int, String, ScheduledExecutorService, boolean)}.
	 */
	public WebsocketPingerService(
		long interval,
		TimeUnit unit,
		String hashFunction,
		ScheduledExecutorService scheduler,
		boolean synchronizeSending
	) {
		this(interval, unit, -1, hashFunction, scheduler, synchronizeSending);
	}

	/**
	 * Calls
	 * {@link #WebsocketPingerService(long, TimeUnit, String, ScheduledExecutorService, boolean)
	 * WebsocketPingerService}<code>(interval, unit, {@value #DEFAULT_HASH_FUNCTION},
	 * {@link #newDefaultScheduler()}, false)</code> ({@code keep-alive-only} mode).
	 */
	public WebsocketPingerService(long interval, TimeUnit unit) {
		this(interval, unit, DEFAULT_HASH_FUNCTION, newDefaultScheduler(), false);
	}

	/**
	 * Calls
	 * {@link #WebsocketPingerService(long, TimeUnit, String, ScheduledExecutorService, boolean)
	 * WebsocketPingerService}<code>({@link #DEFAULT_INTERVAL_SECONDS}, SECONDS,
	 * {@value #DEFAULT_HASH_FUNCTION}, {@link #newDefaultScheduler()}, false)</code>
	 * ({@code keep-alive-only} mode).
	 */
	public WebsocketPingerService() {
		this(
			DEFAULT_INTERVAL_SECONDS,
			SECONDS,
			DEFAULT_HASH_FUNCTION,
			newDefaultScheduler(),
			false
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
	 * Registers {@code connection} for pinging and receiving round-trip time reports via
	 * {@code rttObserver}.
	 * Usually called in
	 * {@link javax.websocket.Endpoint#onOpen(Session, javax.websocket.EndpointConfig) onOpen(...)}.
	 * <p>
	 * Upon receiving a pong matching the next consecutive unanswered ping sent to a given
	 * {@link Session connection}, {@code rttObserver} will be invoked with the round-trip time in
	 * nanoseconds as the second argument and the given {@link Session connection} as the first.</p>
	 * <p>
	 * Note, that if the other side does not reply with pongs at all, {@code rttObserver} will not
	 * be called at all either. If RTT report receiving is critical for a given app,
	 * {@code expect-timely-pongs} mode should be used to disconnect misbehaving peers.</p>
	 * <p>
	 * {@code rttObserver} will be called by a container {@code Thread} bound by the websocket
	 * {@code Endpoint} concurrency contract, so as with normal websocket event handling, it should
	 * not be performing any long-running operations to not delay processing of subsequent events.
	 * Particularly, if {@code rttObserver} processing or processing of any other event blocks
	 * arrival of a pong, the corresponding RTT report will be inaccurate.</p>
	 */
	public void addConnection(Session connection, BiConsumer<Session, Long> rttObserver) {
		final var pingPongPlayer = new PingPongPlayer(
			connection,
			intervalNanos,
			failureLimit,
			synchronizeSending,
			rttObserver,
			hashFunction
		);
		connectionPingPongPlayers.put(connection, pingPongPlayer);
		connectionPingingTasks.put(
			connection,
			scheduler.scheduleAtFixedRate(pingPongPlayer::sendPing, 0L, intervalNanos, NANOSECONDS)
		);
	}

	// design decision note: it seems that in vast majority of cases it is most conveniently for
	// developers if a receiver of RTT reports is the Endpoint instance associated with the
	// connection which reports concern. Container Threads calling Endpoints are bound by a
	// concurrency contract requiring that each Endpoint instance is called by at most 1 Thread at a
	// time. Therefore it would create a lot of problems for developers if delivering  of RTT
	// reports didn't adhere to this contract either.



	/**
	 * Removes {@code connection} from this service, so it will not be pinged anymore.
	 * Usually called in
	 * {@link javax.websocket.Endpoint#onClose(Session, CloseReason) onClose(...)}.
	 * @return {@code true} if {@code connection} had been {@link #addConnection(Session) added} to
	 *     this service before and has been successfully removed by this method, {@code false} if it
	 *     had not been added and no action has taken place.
	 */
	public boolean removeConnection(Session connection) {
		final var pingingTask = connectionPingingTasks.remove(connection);
		if (pingingTask != null) {
			pingingTask.cancel(false);
			connectionPingPongPlayers.remove(connection);
			return true;
		} else {
			return false;
		}
	}



	/** Whether {@code connection} is {@link #addConnection(Session) registered} in this service. */
	public boolean containsConnection(Session connection) {
		return connectionPingPongPlayers.containsKey(connection);
	}



	/** The number of currently registered {@link Session connections}. */
	public int getNumberOfConnections() {
		return connectionPingPongPlayers.size();
	}



	/**
	 * Stops the service.
	 * After a call to this method the service becomes no longer usable and should be discarded.
	 * @return {@link Session connections} that were registered at the time this method was called.
	 */
	public Set<Session> stop(long timeout, TimeUnit unit) {
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



	/** Plays ping-pong with a single associated {@link Session connection}. */
	static class PingPongPlayer implements MessageHandler.Whole<PongMessage> {

		final Session connection;
		final Async connector;
		final int failureLimit;
		final long timeoutNanos;
		final boolean synchronizeSending;
		final BiConsumer<Session, Long> rttObserver;
		final MessageDigest hashFunction;
		final ByteBuffer hashInputBuffer;
		final ByteBuffer pingDataBuffer;

		int failureCount = 0;
		long pingSequence = 0L;
		long lastPongReceived = 0L;



		/** For both modes: negative {@code failureLimit} means {@code keep-alive-only}. */
		PingPongPlayer(
			Session connection,
			long timeoutNanos,
			int failureLimit,
			boolean synchronizeSending,
			BiConsumer<Session, Long> rttObserver,
			String hashFunction
		) {
			this.connection = connection;
			this.connector = connection.getAsyncRemote();
			this.timeoutNanos = timeoutNanos;
			this.failureLimit = failureLimit;
			this.synchronizeSending = synchronizeSending;
			this.rttObserver = rttObserver;
			try {
				this.hashFunction = MessageDigest.getInstance(hashFunction);
			} catch (NoSuchAlgorithmException neverHappens) { // verified by the service constructor
				throw new RuntimeException(neverHappens);
			}
			hashInputBuffer = ByteBuffer.allocate(Long.BYTES * 2 + Integer.BYTES);
			pingDataBuffer =
					ByteBuffer.allocate(Long.BYTES * 2 + this.hashFunction.getDigestLength());
			connection.addMessageHandler(PongMessage.class, this);
		}



		/**
		 * Sends a ping containing its "signed" sequence number and timestamp.
		 * In case of {@code expect-timely-pongs} mode, if the previous ping timed-out, then
		 * increments {@link #failureCount} and if it exceeds {@link #failureLimit}, then closes the
		 * connection.
		 * <p>
		 * Called by {@link #scheduler}'s worker {@code  Thread}s.</p>
		 * <p>
		 * The exact structure of a ping data:</p>
		 * <pre>{@code
		 * pingSequenceBytes + pingTimestampBytes + hashFunction(
		 *         this.identityHashCodeBytes + pingSequenceBytes + pingTimestampBytes)}</pre>
		 * <p>
		 * ({@code +} denotes byte sequence concatenation)</p>
		 */
		synchronized void sendPing() {
			if (failureLimit >= 0 && pingSequence > lastPongReceived) {
				// expect-timely-pongs mode && the previous ping timed-out
				failureCount++;
				if (failureCount > failureLimit) {
					closeFailedConnection("too many timed-out pongs");
					return;
				}
			}

			pingSequence++;
			hashInputBuffer.putInt(this.hashCode());  // using default identity hashCode()
			hashInputBuffer.putLong(pingSequence);
			pingDataBuffer.putLong(pingSequence);
			final var pingTimestampNanos = System.nanoTime();
			hashInputBuffer.putLong(pingTimestampNanos);
			pingDataBuffer.putLong(pingTimestampNanos);
			pingDataBuffer.put(hashFunction.digest(hashInputBuffer.array()));
			pingDataBuffer.rewind();
			try {
				if (synchronizeSending) {
					synchronized (connection) {
						connector.sendPing(pingDataBuffer);
					}
				} else {
					connector.sendPing(pingDataBuffer);
				}
				// prepare for the next ping
				hashInputBuffer.rewind();
				pingDataBuffer.rewind();
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



		/** Called by a container {@code Thread}. */
		@Override
		public void onMessage(PongMessage pong) {
			final var pongTimestampNanos = System.nanoTime();
			Long rttToReport = null;
			synchronized (this) {
				try {
					final var pongData = pong.getApplicationData();
					final var pongNumber = pongData.getLong();
					final var timestampFromPong = pongData.getLong();
					if (hasValidSignature(pongData, pongNumber, timestampFromPong)) {
						if (pongNumber != lastPongReceived + 1L) {
							// As websocket connection is over a reliable transport layer (TCP or
							// HTTP/3) nonconsecutive pongs are a symptom of a faulty implementation
							closeFailedConnection("nonconsecutive pong");
							return;
						}
						lastPongReceived++;
						final var rttNanos = pongTimestampNanos - timestampFromPong;
						rttToReport = (rttObserver != null) ? rttNanos : null;
						if (rttNanos <= timeoutNanos) failureCount = 0;
					}  // else: unsolicited pong
				} catch (BufferUnderflowException ignored) {}  // unsolicited pong with small data
			}
			if (rttToReport != null) rttObserver.accept(connection, rttToReport);
		}

		boolean hasValidSignature(ByteBuffer pongData, long pongNumber, long timestampFromPong) {
			hashInputBuffer.putInt(System.identityHashCode(this));
			hashInputBuffer.putLong(pongNumber);
			hashInputBuffer.putLong(timestampFromPong);
			hashInputBuffer.rewind();
			return pongData.equals(ByteBuffer.wrap(hashFunction.digest(hashInputBuffer.array())));
		}



		/**
		 * Removes pong handler.
		 * Called by the service on {@link Session connections} remaining after {@link #stop()}.
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



	/**
	 * Returns {@link Executors#newScheduledThreadPool(int)
	 * Executors.newScheduledThreadPool}({@link Runtime#availableProcessors() availableProcessors}).
	 */
	public static ScheduledExecutorService newDefaultScheduler() {
		return Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors());
	}



	static final Logger log = Logger.getLogger(WebsocketPingerService.class.getName());
}
