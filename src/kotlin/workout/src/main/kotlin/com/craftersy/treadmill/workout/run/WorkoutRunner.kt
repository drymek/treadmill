package com.craftersy.treadmill.workout.run

import com.craftersy.treadmill.client.Client
import com.craftersy.treadmill.client.ConnectionEvent
import com.craftersy.treadmill.client.ConnectionState
import com.craftersy.treadmill.client.DisconnectedException
import com.craftersy.treadmill.client.NotAcknowledgedException
import com.craftersy.treadmill.client.NotRunningException
import com.craftersy.treadmill.protocol.State
import com.craftersy.treadmill.protocol.Status
import com.craftersy.treadmill.workout.bluetooth.NotFoundException
import com.craftersy.treadmill.workout.format.Length
import com.craftersy.treadmill.workout.format.PlannedStep
import com.craftersy.treadmill.workout.format.Workout
import com.craftersy.treadmill.workout.format.expand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Opens a client. Connection changes are reported to `onEvent`. */
typealias Connector = suspend (onEvent: (ConnectionEvent) -> Unit) -> Client

enum class Phase {
    /** Looking for the treadmill and connecting. */
    CONNECTING,

    /** Start sent; the treadmill counts down. */
    COUNTDOWN,

    /** A step is running. */
    RUNNING,

    /** The user asked to stop; the belt is braking. */
    STOPPING,

    /** Every step ran and the belt was stopped. */
    COMPLETED,

    /** The workout ended early: stopped by the user, on the console, or by an error. */
    ENDED,
}

/** Everything the run screen and the notification show. */
data class RunState(
    val workout: Workout,
    val steps: List<PlannedStep>,
    val phase: Phase = Phase.CONNECTING,
    /** Index into [steps] of the running step; -1 before the first one. */
    val index: Int = -1,
    /** Time and treadmill distance since the running step began. */
    val stepSeconds: Double = 0.0,
    val stepMetres: Int = 0,
    /** Time since the first step began, on the app's clock. */
    val totalSeconds: Double = 0.0,
    val status: Status? = null,
    val connected: Boolean = false,
    /** What to tell the user, in order; empty while a step runs normally. */
    val message: List<Msg> = emptyList(),
    val messageIsError: Boolean = false,
) {
    val step: PlannedStep? get() = steps.getOrNull(index)
    val next: PlannedStep? get() = steps.getOrNull(index + 1)
    val finished: Boolean get() = phase == Phase.COMPLETED || phase == Phase.ENDED

    /** Fraction of the running step that is done, 0–1. */
    val stepProgress: Float
        get() = when (val l = step?.step?.length) {
            is Length.Time -> (stepSeconds / l.seconds).toFloat().coerceIn(0f, 1f)
            is Length.Distance -> (stepMetres.toFloat() / l.metres).coerceIn(0f, 1f)
            null -> 0f
        }

    /** What is left of the running step: seconds for a time step, metres for a distance step. */
    val remaining: Int
        get() = when (val l = step?.step?.length) {
            is Length.Time -> (l.seconds - stepSeconds).coerceAtLeast(0.0).let { kotlin.math.ceil(it).toInt() }
            is Length.Distance -> (l.metres - stepMetres).coerceAtLeast(0)
            null -> 0
        }
}

/**
 * Runs a workout on a treadmill, following docs/workout.md, section 6: it
 * starts the belt, waits for the countdown, sends every step's speed and
 * incline and moves on when the step's time has passed on the app's clock or
 * the treadmill's distance counter has grown by the step's distance. After the
 * last step it stops the belt.
 *
 * If the belt stops for any other reason, or the connection drops, the
 * workout ends and is not resumed. After a dropped connection the runner
 * stops the belt as soon as the connection is back.
 *
 * Android-free, so it runs against the simulator in unit tests.
 */
class WorkoutRunner(
    private val scope: CoroutineScope,
    workout: Workout,
    private val connect: Connector,
    private val tick: Duration = 200.milliseconds,
    private val commandTimeout: Duration = 5.seconds,
    private val time: TimeSource = TimeSource.Monotonic,
) {
    private val mutableState = MutableStateFlow(RunState(workout, workout.expand(), message = listOf(Msg.Searching)))
    val state: StateFlow<RunState> = mutableState.asStateFlow()

    @Volatile
    var client: Client? = null
        private set

    private val skips = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    /** Connects and runs the workout. Call once. */
    fun start() {
        check(job == null) { "already started" }
        job = scope.launch { run() }
    }

    /** Ends the running step early and moves on to the next one. */
    fun skip() {
        skips.trySend(Unit)
    }

    /** Ends the workout and stops the belt. */
    fun stop() {
        val s = mutableState.value
        if (s.finished || s.phase == Phase.STOPPING) return
        mutableState.update { it.copy(phase = Phase.STOPPING, message = listOf(Msg.StoppingBelt), messageIsError = false) }
        scope.launch {
            // Wait for the run to end first: otherwise it could still see the belt braking from our own Stop
            // and end the workout as if the belt had been stopped on the console.
            job?.cancelAndJoin()
            val outcome = stopBelt()
            end(outcome.ifEmpty { listOf(Msg.WorkoutStopped) }, Msg.CouldNotStop in outcome)
        }
    }

    private suspend fun run() {
        val c = try {
            connect(::onConnection).also { client = it }
        } catch (e: TimeoutCancellationException) {
            end(listOf(Msg.NoAnswer), error = true)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            end(listOf(describe(e)), error = true)
            return
        }
        mutableState.update { it.copy(connected = true, message = listOf(Msg.Connected)) }
        scope.launch { c.statuses.collect { st -> mutableState.update { it.copy(status = st) } } }
        scope.launch {
            c.done.join()
            if (!mutableState.value.finished) end(listOf(Msg.ConnectionClosed), error = true)
        }

        try {
            c.waitForFirstStatus()
            mutableState.update { it.copy(phase = Phase.COUNTDOWN, message = listOf(Msg.GetReady)) }
            command { c.start() }
            withTimeout(15.seconds) { c.waitFor { it.state == State.RUNNING } }

            val started = time.markNow()
            val steps = mutableState.value.steps
            for ((i, step) in steps.withIndex()) {
                runStep(c, i, step, started)
            }
            mutableState.update { it.copy(phase = Phase.STOPPING, message = listOf(Msg.CoolingDown)) }
            command { c.stop() }
            end(listOf(Msg.Complete), error = false, phase = Phase.COMPLETED)
        } catch (e: TimeoutCancellationException) {
            val outcome = stopBelt()
            end(listOf(describe(e)) + outcome, error = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BeltStoppedException) {
            end(listOf(Msg.BeltStoppedOnConsole), error = false)
        } catch (e: Exception) {
            val outcome = stopBelt()
            end(listOf(describe(e)) + outcome, error = true)
        }
    }

    private suspend fun runStep(c: Client, index: Int, step: PlannedStep, started: kotlin.time.TimeMark) {
        command { c.setSpeedIncline(step.speed.kmh, step.incline.level) }
        val begun = time.markNow()
        val startMetres = c.status.value?.distance ?: 0
        mutableState.update {
            it.copy(phase = Phase.RUNNING, index = index, stepSeconds = 0.0, stepMetres = 0, message = emptyList(), messageIsError = false)
        }
        while (true) {
            val st = c.status.value
            if (st != null && st.state != State.RUNNING && st.state != State.STARTING) throw BeltStoppedException()
            val seconds = begun.elapsedNow().inWholeMilliseconds / 1000.0
            val metres = ((st?.distance ?: startMetres) - startMetres).coerceAtLeast(0)
            mutableState.update {
                it.copy(stepSeconds = seconds, stepMetres = metres, totalSeconds = started.elapsedNow().inWholeMilliseconds / 1000.0)
            }
            val done = when (val l = step.step.length) {
                is Length.Time -> seconds >= l.seconds
                is Length.Distance -> metres >= l.metres
            }
            if (done) return
            val skipped = select {
                skips.onReceive { true }
                onTimeout(tick) { false }
            }
            if (skipped) return
        }
    }

    /** Runs [block] within the command timeout, once more if the treadmill did not confirm it. */
    private suspend fun command(block: suspend () -> Unit) {
        try {
            withTimeout(commandTimeout) { block() }
        } catch (e: Exception) {
            if (e !is NotAcknowledgedException && e !is TimeoutCancellationException) throw e
            withTimeout(commandTimeout) { block() }
        }
    }

    private fun onConnection(event: ConnectionEvent) {
        when (event.state) {
            ConnectionState.DISCONNECTED -> {
                mutableState.update { it.copy(connected = false) }
                if (!mutableState.value.finished) {
                    job?.cancel()
                    end(listOf(Msg.ConnectionLost), error = true)
                }
            }
            ConnectionState.CONNECTED -> {
                mutableState.update { it.copy(connected = true) }
                if (mutableState.value.phase == Phase.ENDED) {
                    scope.launch {
                        val outcome = stopBelt()
                        if (outcome.isNotEmpty()) {
                            mutableState.update { it.copy(message = listOf(Msg.Reconnected) + outcome, messageIsError = Msg.CouldNotStop in outcome) }
                        }
                    }
                }
            }
            ConnectionState.RECONNECT_FAILED -> Unit
        }
    }

    /** Stops a belt that may still be moving; returns what happened, or nothing if it was not moving. */
    private suspend fun stopBelt(): List<Msg> {
        val c = client ?: return emptyList()
        val st = c.status.value ?: return emptyList()
        if (st.state != State.STARTING && st.state != State.RUNNING) return emptyList()
        return try {
            command { c.stop() }
            listOf(Msg.BeltStopped)
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            listOf(Msg.CouldNotStop)
        } catch (e: Exception) {
            listOf(describe(e), Msg.CouldNotStop)
        }
    }

    private fun end(message: List<Msg>, error: Boolean, phase: Phase = Phase.ENDED) {
        mutableState.update { if (it.finished) it else it.copy(phase = phase, message = message, messageIsError = error) }
    }

    private suspend fun Client.waitForFirstStatus() {
        try {
            withTimeout(10.seconds) { waitFor { true } }
        } catch (e: TimeoutCancellationException) {
            throw NoStatusException()
        }
    }

    private class BeltStoppedException : Exception()

    internal class NoStatusException : Exception()
}

/** Turns an error into a message for the user. */
private fun describe(error: Throwable): Msg = when (error) {
    is NotRunningException -> Msg.NotRunning
    is DisconnectedException -> Msg.NotConnected
    is NotAcknowledgedException -> Msg.NotConfirmed
    is TimeoutCancellationException -> Msg.NoAnswer
    is NotFoundException -> Msg.NotFound(error.name)
    is WorkoutRunner.NoStatusException -> Msg.NoStatus
    else -> error.message?.let(Msg::Text) ?: Msg.CouldNotConnect
}
