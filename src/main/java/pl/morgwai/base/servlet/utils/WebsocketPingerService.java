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
 * {@link Session connections} or on some static var).</p>
 * <p>
 * At app shutdowns, {@link #shutdown()} should be called to terminate the pinging
 * {@link ScheduledExecutorService scheduler}, followed by {@link #awaitTermination(long, TimeUnit)}
 * and if it fails then also {@link #shutdownNow()} similarly as with {@link ExecutorService}s.
 * For convenience {@link #tryEnforceTermination()} method was provided that combines the previous
 * 3.</p>
 * <p>
 * Connections can be registered for pinging using {@link #addConnection(Session)}
 * and deregister using {@link #removeConnection(Session)}.</p>
 * <p>
 * If round-trip time discovery is needed, {@link #addConnection(Session, BiConsumer)} variant may
 * be used to receive RTT reports on each matching pong.</p>
 */
public class WebsocketPingerService {



	/**
	 * {@value #DEFAULT_INTERVAL_SECONDS}s as majority of proxies and NAT routers have a timeout of
	 * at least 60s.
	 */
	public static final int DEFAULT_INTERVAL_SECONDS = 55;
	final long intervalNanos;

	/** The maximum length of ping data in bytes as per websocket spec. */
	public static final int MAX_PING_DATA_BYTES = 125;
	/**
	 * The maximum allowed length of hashes produced by {@link MessageDigest hashFunction} passed to
	 * {@link
	 * #WebsocketPingerService(long, TimeUnit, int, String, ScheduledExecutorService, boolean)
	 * the constructor}.
	 */
	public static final int MAX_HASH_LENGTH_BYTES = MAX_PING_DATA_BYTES - (2 * Long.BYTES);

	/** Default {@link MessageDigest} for hashing ping content. */
	public static final String DEFAULT_HASH_FUNCTION = "SHA3-256";
	final String hashFunction;

	final int failureLimit;  // negative value means keep-alive-only mode
	final boolean synchronizeSending;
	final ScheduledExecutorService scheduler;



	/**
	 * Constructs a new service in {@code expect-timely-pongs} mode.
	 * Each timeout adds to a given {@link Session connection}'s failure count, unmatched pongs are
	 * ignored, matching pongs received in a nonconsecutive order cause the connection to be closed
	 * immediately with {@link CloseCodes#PROTOCOL_ERROR}.
	 * @param interval interval between pings and also timeout for pongs. Specifically, the value of
	 *     this param will be passed as the 3rd param to
	 *     {@link ScheduledExecutorService#scheduleWithFixedDelay(Runnable, long, long, TimeUnit)
	 *     scheduler.scheduleWithFixedDelay(pingingTask, 0L, interval, unit)} when scheduling pings.
	 *     While this class does not enforce any hard limits, as of typical network and CPU
	 *     capabilities of 2024, values below 100ms are probably not a good idea in most cases and
	 *     anything below 20ms is pure Sparta.
	 * @param unit unit for {@code interval} passed as the 4th param to
	 *     {@link ScheduledExecutorService#scheduleWithFixedDelay(Runnable, long, long, TimeUnit)
	 *     scheduler.scheduleWithFixedDelay(pingingTask, 0L, interval, unit)} when scheduling pings.
	 * @param failureLimit limit of timed-out pongs: if exceeded, then the given
	 *     {@link Session connection} is closed with {@link CloseCodes#PROTOCOL_ERROR}. Each
	 *     matching, timely pong resets the {@link Session connection}'s failure counter.
	 * @param hashFunction name of a {@link MessageDigest} to use for ping content hashing. This
	 *     must be supported by a registered {@link java.security.Provider security Provider} and
	 *     {@link MessageDigest#getDigestLength() the length of produced hashes} must not exceed
	 *     {@value #MAX_HASH_LENGTH_BYTES} bytes, otherwise an {@link IllegalArgumentException}
	 *     will be thrown.
	 * @param scheduler used for scheduling pings. Generally the {@code Service} takes the ownership
	 *     of the {@code scheduler}: see {@link #shutdown()}, {@link #shutdownNow()},
	 *     {@link #awaitTermination(long, TimeUnit)} and {@link #tryEnforceTermination()}.
	 * @param synchronizeSending whether to synchronize ping sending on a given
	 *     {@link Session connection}. Whether it is necessary depends on the container
	 *     implementation being used. For example it is not necessary on Jetty, but it is on Tomcat:
	 *     see <a href="https://bz.apache.org/bugzilla/show_bug.cgi?id=56026">this bug report</a>.
	 *     <br/>
	 *     When using containers that do require such synchronization, all other message sending by
	 *     {@code Endpoint}s must also be synchronized on the respective {@link Session connections}
	 *     (please don't shoot the messenger...).
	 * @throws IllegalArgumentException if {@code failureLimit} is negative or if
	 *     {@code hashFunction} is not supported or produces too long hashes.
	 */
	public WebsocketPingerService(
		long interval,
		TimeUnit unit,
		int failureLimit,
		String hashFunction,
		ScheduledExecutorService scheduler,
		boolean synchronizeSending
	) {
		this(interval, unit, failureLimit, hashFunction, scheduler, synchronizeSending, true);
	}

	/**
	 * Calls {@link #WebsocketPingerService(long, TimeUnit, int, String, ScheduledExecutorService,
	 * boolean) WebsocketPingerService} <code>(interval, unit, failureLimit,
	 * {@value #DEFAULT_HASH_FUNCTION}, {@link #newDefaultScheduler()}, false)</code>
	 * ({@code expect-timely-pongs} mode).
	 */
	public WebsocketPingerService(long interval, TimeUnit unit, int failureLimit) {
		this(interval, unit, failureLimit, DEFAULT_HASH_FUNCTION, newDefaultScheduler(), false);
	}

	/**
	 * Calls {@link
	 * #WebsocketPingerService(long, TimeUnit, int, String, ScheduledExecutorService, boolean)
	 * WebsocketPingerService}<code>({@link #DEFAULT_INTERVAL_SECONDS}, SECONDS, failureLimit,
	 * {@value #DEFAULT_HASH_FUNCTION}, {@link #newDefaultScheduler()}, false)</code>
	 * ({@code expect-timely-pongs} mode).
	 */
	public WebsocketPingerService(int failureLimit) {
		this(
			DEFAULT_INTERVAL_SECONDS,
			SECONDS,
			failureLimit,
			DEFAULT_HASH_FUNCTION,
			newDefaultScheduler(),
			false
		);
	}

	// design decision note: using interval as a timeout simplifies things A LOT. Using a separate
	// SHORTER duration for a timeout is still pretty feasible and may be implemented if there's
	// enough need for it. Allowing timeouts longer than intervals OTOH would require scheduling
	// (and then cancelling) of on-timeout actions and is almost certainly not worth the effort.



	/**
	 * Constructs a new service in {@code keep-alive-only} mode.
	 * The service will not actively close any {@link Session connection} unless an
	 * {@link IOException} occurs, which on most container implementations cause the connection to
	 * be closed automatically anyway. The params have the similar meaning as in {@link
	 * #WebsocketPingerService(long, TimeUnit, int, String, ScheduledExecutorService, boolean)}.
	 */
	public WebsocketPingerService(
		long interval,
		TimeUnit unit,
		String hashFunction,
		ScheduledExecutorService scheduler,
		boolean synchronizeSending
	) {
		this(interval, unit, -1, hashFunction, scheduler, synchronizeSending, false);
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

	// design decision note: while it is possible to use unsolicited pongs for keep-alive-only,
	// some ping-pong implementations confuse them with malformed pongs and close connections.
	// Furthermore, using ping-pong allows to provide RTT reports in keep-alive-only mode also.



	/** Low-level constructor that performs the actual initialization. */
	WebsocketPingerService(
		long interval,
		TimeUnit unit,
		int failureLimit,
		String hashFunction,
		ScheduledExecutorService scheduler,
		boolean synchronizeSending,
		boolean expectTimelyPongsMode
	) {
		if (expectTimelyPongsMode && failureLimit < 0) {
			throw new IllegalArgumentException("failureLimit < 0");
		}
		try {
			if (MessageDigest.getInstance(hashFunction).getDigestLength() > MAX_HASH_LENGTH_BYTES) {
				throw new IllegalArgumentException(hashFunction + " produces too long hashes");
			}
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalArgumentException(e);
		}
		this.intervalNanos = unit.toNanos(interval);
		this.failureLimit = failureLimit;
		this.synchronizeSending = synchronizeSending;
		this.hashFunction = hashFunction;
		this.scheduler = scheduler;
	}



	final ConcurrentMap<Session, PingPongPlayer> connectionPingPongPlayers =
			new ConcurrentHashMap<>();
	/** Tasks periodic on {@link #scheduler}, that execute {@link PingPongPlayer#sendPing()}. */
	final ConcurrentMap<Session, ScheduledFuture<?>> connectionPingingTasks =
			new ConcurrentHashMap<>();



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
	 * Upon receiving a pong matching some ping previously sent to {@code connection},
	 * {@code rttObserver} will be invoked with the round-trip time in nanoseconds as the second
	 * argument and {@code  connection} as the first.</p>
	 * <p>
	 * Note that if the other side does not reply with pongs at all, {@code rttObserver} will not
	 * be called at all either. If RTT report receiving is critical for a given app,
	 * {@code expect-timely-pongs} mode should be used to disconnect misbehaving peers.</p>
	 * <p>
	 * {@code rttObserver} will be called by a container {@code Thread} bound by the websocket
	 * {@code Endpoint} concurrency contract. Thus as with normal websocket event handling, it
	 * should not be performing any long-running operations to not delay processing of subsequent
	 * events. Particularly, if {@code rttObserver} processing or processing of any other event
	 * blocks arrival of a pong, the corresponding RTT report will be inaccurate.</p>
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
			scheduler.scheduleWithFixedDelay(
					pingPongPlayer::sendPing, 0L, intervalNanos, NANOSECONDS)
		);
	}

	// design decision note: it seems that in vast majority of cases it is most conveniently for
	// developers if a receiver of RTT reports is the Endpoint instance associated with the
	// connection which reports concern. Container Threads calling Endpoints are bound by a
	// concurrency contract requiring that each Endpoint instance is called by at most 1 Thread at a
	// time. Therefore it would create a lot of problems for developers if delivering of RTT
	// reports didn't adhere to this contract either.



	/**
	 * Deregisters {@code connection} from this service, so it will not be pinged anymore.
	 * Usually called in
	 * {@link javax.websocket.Endpoint#onClose(Session, CloseReason) onClose(...)}.
	 * @return {@code true} if {@code connection} had been {@link #addConnection(Session) added} to
	 *     this service before and has been successfully removed by this method, {@code false} if it
	 *     had not been added and no action has taken place.
	 */
	public boolean removeConnection(Session connection) {
		final var pingingTask = connectionPingingTasks.remove(connection);
		if (pingingTask == null) return false;
		pingingTask.cancel(false);
		connectionPingPongPlayers.remove(connection);
		return true;
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
	 * Shutdowns the service and {@link ScheduledExecutorService#shutdown() its scheduler}.
	 * After a call to this method the service becomes no longer usable and should be discarded: the
	 * only methods that may be called are {@code #shutdown()} (idempotent),
	 * {@link #awaitTermination(long, TimeUnit)}, {@link #tryEnforceTermination(long, TimeUnit)} and
	 * {@link #shutdownNow()}.
	 * @return {@link Session connections} that were still registered at the time this method was
	 * called.
	 */
	public Set<Session> shutdown() {
		scheduler.shutdown();
		for (var pingPongPlayer: connectionPingPongPlayers.values()) pingPongPlayer.deregister();
		final var remaining = Set.copyOf(connectionPingPongPlayers.keySet());
		connectionPingPongPlayers.clear();
		return remaining;
	}

	/**
	 * Calls {@link ScheduledExecutorService#awaitTermination(long, TimeUnit)
	 * scheduler.awaitTermination(...)}.
	 */
	public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
		return scheduler.awaitTermination(timeout, unit);
	}

	/** Calls {@link ScheduledExecutorService#shutdownNow() scheduler.shutdownNow()}. */
	public void shutdownNow() {
		scheduler.shutdownNow();
	}

	/** Calls {@link ScheduledExecutorService#isTerminated() scheduler.isTerminated()}. */
	public boolean isTerminated() {
		return scheduler.isTerminated();
	}

	/**
	 * Calls {@link #shutdown()} and returns the result of
	 * {@link #awaitTermination(long, TimeUnit) awaitTermination(timeout, unit)}, if the scheduler
	 * {@link ScheduledExecutorService#isTerminated() fails to terminate}, {@link #shutdownNow()} is
	 * called {@code finally}.
	 */
	public boolean tryEnforceTermination(long timeout, TimeUnit unit) throws InterruptedException {
		shutdown();
		try {
			return scheduler.awaitTermination(timeout, unit);
		} finally {
			if ( !scheduler.isTerminated()) scheduler.shutdownNow();
		}
	}

	/**
	 * Calls {@link #tryEnforceTermination(long, TimeUnit) tryEnforceTermination(}{@value
	 * #TERMINATION_TIMEOUT_MILLIS}, {@code MILLISECONDS)}.
	 */
	public boolean tryEnforceTermination() throws InterruptedException {
		return tryEnforceTermination(TERMINATION_TIMEOUT_MILLIS, MILLISECONDS);
	}

	static final long TERMINATION_TIMEOUT_MILLIS = 500L;



	/** Plays ping-pong with a single associated {@link Session connection}. */
	static class PingPongPlayer implements MessageHandler.Whole<PongMessage> {

		final Session connection;
		final Async connector;
		final int failureLimit;
		final long timeoutNanos;
		final boolean synchronizeSending;
		final BiConsumer<Session, Long> rttObserver;
		final ByteBuffer pingDataBuffer;

		// separate instances for better concurrency (MessageDigest is NOT thread-safe)
		final MessageDigest pingHashFunction;
		final MessageDigest pongHashFunction;
		final ByteBuffer pingHashInputBuffer;
		final ByteBuffer pongHashInputBuffer;

		int failureCount = 0;
		long pingSequence = 0L;
		long lastMatchingPongReceived = 0L;



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
				pingHashFunction = MessageDigest.getInstance(hashFunction);
				pongHashFunction = MessageDigest.getInstance(hashFunction);
			} catch (NoSuchAlgorithmException neverHappens) { // verified by the service constructor
				throw new RuntimeException(neverHappens);
			}
			pingHashInputBuffer = ByteBuffer.allocate(Long.BYTES * 2 + Integer.BYTES);
			pongHashInputBuffer = ByteBuffer.allocate(Long.BYTES * 2 + Integer.BYTES);
			pingDataBuffer =
					ByteBuffer.allocate(Long.BYTES * 2 + pingHashFunction.getDigestLength());
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
		 * ({@code +} denotes a byte sequence concatenation)</p>
		 */
		final void sendPing() {
			synchronized (this) {  // sync with onMessage(pong)
				if (failureLimit >= 0 && pingSequence > lastMatchingPongReceived) {
					// expect-timely-pongs mode && the previous ping timed-out
					failureCount++;
					if (failureCount > failureLimit) {
						closeFailedConnection("too many timed-out pongs");
						return;
					}
				}
			}

			pingSequence++;
			pingHashInputBuffer.putInt(this.hashCode());  // using the default identity hashCode()
			pingHashInputBuffer.putLong(pingSequence);
			pingDataBuffer.putLong(pingSequence);
			final var pingTimestampNanos = System.nanoTime();
			pingHashInputBuffer.putLong(pingTimestampNanos);
			pingDataBuffer.putLong(pingTimestampNanos);
			pingDataBuffer.put(pingHashFunction.digest(pingHashInputBuffer.array()));
			pingDataBuffer.rewind();
			try {
				if (synchronizeSending) {
					synchronized (connection) {
						connector.sendPing(pingDataBuffer);
					}
				} else {
					connector.sendPing(pingDataBuffer);
				}
				// prepare for the next ping:
				pingHashInputBuffer.rewind();
				pingDataBuffer.rewind();
			} catch (IOException e) {
				// on most container implementations the connection is PROBABLY already closed, but
				// just in case:
				closeFailedConnection("failed to send a ping");
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



		/**
		 * If {@code pong} matches some previously sent ping, then does all the bookkeeping and
		 * reports RTT if requested.
		 * Ignores unsolicited {@code pong}s.
		 * <p>
		 * Called by a container {@code Thread}.</p>
		 */
		@Override
		public void onMessage(PongMessage pong) {
			final var pongTimestampNanos = System.nanoTime();
			final var pongData = pong.getApplicationData();
			try {
				final var pongNumber = pongData.getLong();
				final var timestampFromPong = pongData.getLong();
				if (hasValidHash(pongData, pongNumber, timestampFromPong)) {  // matching pong
					if (failureLimit >= 0 && pongNumber != lastMatchingPongReceived + 1L) {
						// As websocket connection is over a reliable transport (TCP or HTTP/3),
						// nonconsecutive pongs are a symptom of a faulty implementation
						closeFailedConnection("nonconsecutive pong");
						return;
					}

					final var rttNanos = pongTimestampNanos - timestampFromPong;
					synchronized (this) {  // sync with sendPing()
						lastMatchingPongReceived++;
						if (rttNanos <= timeoutNanos) failureCount = 0;
					}
					if (rttObserver != null) rttObserver.accept(connection, rttNanos);
				}  // else: unsolicited pong
			} catch (BufferUnderflowException ignored) {}  // unsolicited pong with a small data
		}

		private boolean hasValidHash(
			ByteBuffer bufferToVerify,
			long pongNumber,
			long timestampFromPong
		) {
			pongHashInputBuffer.putInt(this.hashCode());
			pongHashInputBuffer.putLong(pongNumber);
			pongHashInputBuffer.putLong(timestampFromPong);
			pongHashInputBuffer.rewind();
			return bufferToVerify.equals(ByteBuffer.wrap(
					pongHashFunction.digest(pongHashInputBuffer.array())));
		}



		/**
		 * Removes itself from {@link #connection}'s message handlers.
		 * Called by the enclosing service for {@link Session connections} that still remain open
		 * after {@link #shutdown()}.
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
