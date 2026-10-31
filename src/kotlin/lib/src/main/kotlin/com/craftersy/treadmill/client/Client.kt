/**
 * A high-level client for RZ_TreadMil treadmills.
 *
 * A [Client] wraps a [Transport] (the Android app has the real BLE one, and
 * [com.craftersy.treadmill.simulator.Simulator] is a simulator), keeps the
 * latest status, fans it out to collectors and waits for the treadmill to
 * acknowledge every command.
 *
 *     val tm = Client(transport, scope)
 *     tm.start()
 *     tm.setSpeed(3.5)
 *     tm.setIncline(2) // keeps 3.5 km/h
 *     tm.stop()
 *     tm.close()
 */
package com.craftersy.treadmill.client

import com.craftersy.treadmill.protocol.Command
import com.craftersy.treadmill.protocol.Incline
import com.craftersy.treadmill.protocol.MalformedFrameException
import com.craftersy.treadmill.protocol.Speed
import com.craftersy.treadmill.protocol.State
import com.craftersy.treadmill.protocol.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Controls one treadmill. It is safe for concurrent use; commands are
 * serialised.
 *
 * @param scope runs the loop that reads [Transport.frames].
 * @param ackTimeout how long commands wait for confirmation; zero disables
 *   waiting, so commands return as soon as the frame is written.
 */
public class Client(
    private val transport: Transport,
    scope: CoroutineScope,
    private val ackTimeout: Duration = DEFAULT_ACK_TIMEOUT,
    private val log: (String) -> Unit = {},
) {
    private val commandMutex = Mutex()
    private val latest = MutableStateFlow<Status?>(null)
    private val updates = MutableSharedFlow<Status>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The latest status, or null before the first frame. */
    public val status: StateFlow<Status?> = latest.asStateFlow()

    /** Every status received from now on, including repeated identical frames. */
    public val statuses: SharedFlow<Status> = updates.asSharedFlow()

    private val readLoop: Job = scope.launch {
        for (frame in transport.frames) {
            val st = try {
                Status.parse(frame)
            } catch (e: MalformedFrameException) {
                log("ignoring frame: ${e.message}")
                continue
            }
            latest.value = st
            updates.emit(st)
        }
    }

    /** Completes when the connection ends. */
    public val done: Job get() = readLoop

    /** Closes the transport and waits for the client to shut down. */
    public suspend fun close() {
        transport.close()
        readLoop.join()
    }

    /** Suspends until a status satisfies [cond], starting with the latest one. */
    public suspend fun waitFor(cond: (Status) -> Boolean): Status = coroutineScope {
        val closed = launch { readLoop.join(); throw ClosedException() }
        try {
            updates
                .onSubscription { latest.value?.let { if (cond(it)) emit(it) } }
                .first(cond)
        } finally {
            closed.cancel()
        }
    }

    /**
     * Starts the belt and waits for the countdown to begin. The belt runs at
     * 1.0 km/h after a 3-second countdown; elapsed time, distance and calories
     * restart from zero.
     */
    public suspend fun start(): Unit = commandMutex.withLock {
        send(Command.start()) { it.state == State.STARTING || it.state == State.RUNNING }
    }

    /** Brakes the belt and waits for the treadmill to confirm. */
    public suspend fun stop(): Unit = commandMutex.withLock {
        send(Command.stop()) { it.state == State.STOPPING || it.state == State.STOPPED || it.state == State.IDLE }
    }

    /** Changes the target speed in km/h (1.0–22.0, rounded to 0.1) and keeps the current incline. */
    public suspend fun setSpeed(kmh: Double) {
        val speed = Speed.fromKmh(kmh)
        set { speed to it.incline }
    }

    /** Changes the incline level (0–22) and keeps the current target speed. */
    public suspend fun setIncline(level: Int) {
        val incline = Incline.fromLevel(level)
        set { it.targetSpeed to incline }
    }

    /** Changes speed and incline in one command. */
    public suspend fun setSpeedIncline(kmh: Double, level: Int) {
        val speed = Speed.fromKmh(kmh)
        val incline = Incline.fromLevel(level)
        set { speed to incline }
    }

    private suspend fun set(target: (Status) -> Pair<Speed, Incline>): Unit = commandMutex.withLock {
        val cur = latest.value ?: throw NoStatusException()
        if (cur.state != State.RUNNING) throw NotRunningException(cur.state)
        val (speed, incline) = target(cur)
        val frame = Command.setSpeedIncline(speed, incline)
        send(frame) { !it.compact && it.targetSpeed == speed && it.incline == incline }
    }

    private suspend fun send(frame: ByteArray, acked: (Status) -> Boolean) {
        if (readLoop.isCompleted) throw ClosedException()
        transport.write(frame)
        if (ackTimeout <= Duration.ZERO) return
        withTimeoutOrNull(ackTimeout) { waitFor(acked) } ?: throw NotAcknowledgedException()
    }

    public companion object {
        /**
         * How long commands wait for confirmation. The treadmill answers
         * within about 100 ms and sends a status frame every 1.3 s.
         */
        public val DEFAULT_ACK_TIMEOUT: Duration = 3.seconds
    }
}
