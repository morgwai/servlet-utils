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
 * keep-alive-only mode}. The {@code Service} can be used both on the client and the server side.
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
	 * Constructs a new {@code Service} in {@code expect-timely-pongs} mode.
	 * Each timeout adds to a given {@link Session connection}'s failure count, unmatched pongs are
	 * ignored, matching pongs received in a nonconsecutive order cause the connection to be closed
	 * immediately with {@link CloseCodes#PROTOCOL_ERROR}.
	 * @param interval interval between pings and also timeout for pongs. Specifically, the value of
	 *     this param will be passed as the 3rd param to
	 *     {@link ScheduledExecutorService#scheduleWithFixedDelay(Runnable, long, long, TimeUnit)
	 *     scheduler.scheduleWithFixedDelay(pingingTask, 0L, interval, intervalUnit)} when
	 *     scheduling pings. While this class does not enforce any hard limits, as of typical
	 *     network and CPU capabilities of 2024, values below 100ms are probably not a good idea in
	 *     most cases and anything below 20ms is pure Sparta.
	 * @param intervalUnit unit for {@code interval} passed as the 4th param to
	 *     {@link ScheduledExecutorService#scheduleWithFixedDelay(Runnable, long, long, TimeUnit)
	 *     scheduler.scheduleWithFixedDelay(pingingTask, 0L, interval, intervalUnit)} when
	 *     scheduling pings.
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
		TimeUnit intervalUnit,
		int failureLimit,
		String hashFunction,
		ScheduledExecutorService scheduler,
		boolean synchronizeSending
	) {
		this(
			interval,
			intervalUnit,
			failureLimit,
			hashFunction,
			scheduler,
			synchronizeSending,
			true
		);
	}

	/**
	 * Calls {@link #WebsocketPingerService(long, TimeUnit, int, String, ScheduledExecutorService,
	 * boolean) WebsocketPingerService} <code>(interval, intervalUnit, failureLimit,
	 * {@value #DEFAULT_HASH_FUNCTION}, {@link #newDefaultScheduler()}, false)</code>
	 * ({@code expect-timely-pongs} mode).
	 */
	public WebsocketPingerService(long interval, TimeUnit intervalUnit, int failureLimit) {
		this(
			interval,
			intervalUnit,
			failureLimit,
			DEFAULT_HASH_FUNCTION,
			newDefaultScheduler(),
			false
		);
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
	 * Constructs a new {@code Service} in {@code keep-alive-only} mode.
	 * The {@code Service} will not actively close any {@link Session connection} unless an
	 * {@link IOException} occurs, which on most container implementations cause the connection to
	 * be closed automatically anyway. The params have the similar meaning as in {@link
	 * #WebsocketPingerService(long, TimeUnit, int, String, ScheduledExecutorService, boolean)}.
	 */
	public WebsocketPingerService(
		long interval,
		TimeUnit intervalUnit,
		String hashFunction,
		ScheduledExecutorService scheduler,
		boolean synchronizeSending
	) {
		this(interval, intervalUnit, -1, hashFunction, scheduler, synchronizeSending, false);
	}

	/**
	 * Calls
	 * {@link #WebsocketPingerService(long, TimeUnit, String, ScheduledExecutorService, boolean)
	 * WebsocketPingerService}<code>(interval, intervalUnit, {@value #DEFAULT_HASH_FUNCTION},
	 * {@link #newDefaultScheduler()}, false)</code> ({@code keep-alive-only} mode).
	 */
	public WebsocketPingerService(long interval, TimeUnit intervalUnit) {
		this(interval, intervalUnit, DEFAULT_HASH_FUNCTION, newDefaultScheduler(), false);
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
		TimeUnit intervalUnit,
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
		this.intervalNanos = intervalUnit.toNanos(interval);
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
			rttObserver,
			intervalNanos,
			failureLimit,
			hashFunction,
			synchronizeSending
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
	 * Deregisters {@code connection} from this {@code Service}, so it will not be pinged anymore.
	 * Usually called in
	 * {@link javax.websocket.Endpoint#onClose(Session, CloseReason) onClose(...)}.
	 * @return {@code true} if {@code connection} had been {@link #addConnection(Session) added} to
	 *     this {@code Service} before and has been successfully removed by this method,
	 *     {@code false} if it had not been added and no action has taken place.
	 */
	public boolean removeConnection(Session connection) {
		final var pingingTask = connectionPingingTasks.remove(connection);
		if (pingingTask == null) return false;
		pingingTask.cancel(false);
		connectionPingPongPlayers.remove(connection);
		return true;
	}



	/**
	 * Whether {@code connection} is {@link #addConnection(Session) registered} in this
	 * {@code Service}.
	 */
	public boolean containsConnection(Session connection) {
		return connectionPingPongPlayers.containsKey(connection);
	}



	/** The number of currently registered {@link Session connections}. */
	public int getNumberOfConnections() {
		return connectionPingPongPlayers.size();
	}



	/**
	 * Shutdowns this {@code Service} and {@link ScheduledExecutorService#shutdown() its scheduler}.
	 * After a call to this method this instance becomes no longer usable and should be discarded:
	 * the only methods that may be called are {@code #shutdown()} (idempotent),
	 * {@link #awaitTermination(long, TimeUnit)}, {@link #tryEnforceTermination(long, TimeUnit)} and
	 * {@link #shutdownNow()}.
	 * @return {@link Session connections} that were still registered at the time this method was
	 * called.
	 */
	public Set<Session> shutdown() {
		connectionPingingTasks.values().forEach(task -> task.cancel(false));
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



	/**
	 * Plays ping-pong with a single associated {@link Session connection}.
	 * <p>
	 * Ping data structure:</p>
	 * <pre>{@code
	 * pingNumberBytes + pingTimestampBytes + hashFunction(
	 *         salt + pingNumberBytes + pingTimestampBytes)}</pre>
	 * <p>
	 * ({@code +} denotes a byte sequence concatenation)</p>
	 */
	static class PingPongPlayer implements MessageHandler.Whole<PongMessage> {

		final Session connection;
		final Async connector;
		final BiConsumer<Session, Long> rttObserver;
		final long timeoutNanos;
		final int failureLimit;
		final boolean synchronizeSending;
		final ByteBuffer pingDataBuffer;
		final PingDataSaltedHashFunction saltedHashFunction;
		/** For verifying pongs, improves concurrency. */
		final PingDataSaltedHashFunction saltedHashFunctionClone;



		/** For both modes: negative {@code failureLimit} means {@code keep-alive-only}. */
		PingPongPlayer(
			Session connection,
			BiConsumer<Session, Long> rttObserver,
			long timeoutNanos,
			int failureLimit,
			String hashFunction,
			boolean synchronizeSending
		) {
			this.connection = connection;
			this.connector = connection.getAsyncRemote();
			this.rttObserver = rttObserver;
			this.timeoutNanos = timeoutNanos;
			this.failureLimit = failureLimit;
			this.synchronizeSending = synchronizeSending;
			try {
				final var salt = ByteBuffer.allocate(Integer.BYTES * 3)
					.putInt(System.identityHashCode(this))
					.putInt(System.identityHashCode(connection))
					.putInt(System.identityHashCode(connector))
					.array();
				saltedHashFunction = new PingDataSaltedHashFunction(hashFunction, salt);
				saltedHashFunctionClone = new PingDataSaltedHashFunction(hashFunction, salt);
			} catch (NoSuchAlgorithmException neverHappens) { // verified by the Service constructor
				throw new RuntimeException(neverHappens);
			}
			pingDataBuffer = ByteBuffer.allocate(
					Long.BYTES * 2 + saltedHashFunction.getDigestLength());
			connection.addMessageHandler(PongMessage.class, this);
		}



		long lastSentPingNumber = 0L;
		long lastMatchingPongNumber = 0L;
		int failureCount = 0;



		/**
		 * Sends a ping structured as described in {@link PingPongPlayer the class-level javadoc}.
		 * In case of {@code expect-timely-pongs} mode, if the previous ping timed-out, then
		 * increments {@link #failureCount} and if it exceeds {@link #failureLimit}, then closes the
		 * connection.
		 * <p>
		 * Called by {@link #scheduler}'s worker {@code  Thread}s.</p>
		 */
		final void sendPing() {
			synchronized (this) {  // sync with onMessage(pong)
				if (failureLimit >= 0 && lastSentPingNumber > lastMatchingPongNumber) {
					// expect-timely-pongs mode && the previous ping timed-out
					failureCount++;
					if (failureCount > failureLimit) {
						closeFailedConnection("too many timed-out pongs");
						return;
					}
				}
			}

			final var pingNumber = ++lastSentPingNumber;
			final var pingTimestampNanos = System.nanoTime();
			pingDataBuffer.rewind()
				.putLong(pingNumber)
				.putLong(pingTimestampNanos)
				.put(saltedHashFunction.digest(pingNumber, pingTimestampNanos))
				.rewind();
			try {
				if (synchronizeSending) {
					synchronized (connection) {
						connector.sendPing(pingDataBuffer);
					}
				} else {
					connector.sendPing(pingDataBuffer);
				}
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
			} catch (IOException | RuntimeException connectionAlreadyClosed) {
				// this MUST mean the connection is already closed...
			}
		}



		/**
		 * If {@code pong} matches some previously sent ping, then performs all the bookkeeping and
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
				final var pingNumber = pongData.getLong();
				final var pingTimestampNanos = pongData.getLong();
				if (hasValidHash(pongData, pingNumber, pingTimestampNanos)) {  // matching pong
					if (failureLimit >= 0 && pingNumber != lastMatchingPongNumber + 1L) {
						// As websocket connection is over a reliable transport (TCP or HTTP/3),
						// nonconsecutive pongs are a symptom of a faulty implementation
						closeFailedConnection("nonconsecutive pong");
						return;
					}

					final var rttNanos = pongTimestampNanos - pingTimestampNanos;
					synchronized (this) {  // sync with sendPing()
						lastMatchingPongNumber++;
						if (rttNanos <= timeoutNanos) failureCount = 0;
					}
					if (rttObserver != null) rttObserver.accept(connection, rttNanos);
				}  // else: unsolicited pong
			} catch (BufferUnderflowException unsolicitedPongWithShortData) {}
		}

		private boolean hasValidHash(
			ByteBuffer bufferToVerify,
			long pingNumber,
			long pingTimestampNanos
		) {
			final var expectedHash = saltedHashFunctionClone.digest(pingNumber, pingTimestampNanos);
			return bufferToVerify.equals(ByteBuffer.wrap(expectedHash));
		}



		/**
		 * Removes itself from {@link #connection}'s message handlers.
		 * Called by the enclosing {@link WebsocketPingerService} for {@link Session connections}
		 * that still remain open after {@link #shutdown()}.
		 */
		void deregister() {
			try {
				connection.removeMessageHandler(this);
			} catch (RuntimeException containerWhiningAboutClosedConnection) {
				// connection was closed between calls to shutdown() and this method and some
				// container implementations throw a RuntimeException in case of any operation on a
				// closed connection
			}
		}
	}



	/**
	 * Wraps a {@link MessageDigest} together with a salt value and provides
	 * {@link #digest(long, long) digest(...)} variant specifically for ping data.
	 * <p>
	 * Similarly to {@link MessageDigest}, this class is <b>not</b> thread-safe.</p>
	 */
	static class PingDataSaltedHashFunction {

		final MessageDigest hashFunction;
		final ByteBuffer inputBuffer;



		PingDataSaltedHashFunction(String hashFunction, byte[] salt)
				throws NoSuchAlgorithmException {
			this.hashFunction = MessageDigest.getInstance(hashFunction);
			inputBuffer = ByteBuffer.allocate(Long.BYTES * 2 + salt.length)
				.put(salt)
				.mark();
		}



		final byte[] digest(long pingNumber, long pingTimestampNanos) {
			inputBuffer.reset()  // reset to the marked position right after salt
				.putLong(pingNumber)
				.putLong(pingTimestampNanos);
			return hashFunction.digest(inputBuffer.array());
		}



		int getDigestLength() {
			return hashFunction.getDigestLength();
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
