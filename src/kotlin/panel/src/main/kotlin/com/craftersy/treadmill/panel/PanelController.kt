package com.craftersy.treadmill.panel

import com.craftersy.treadmill.client.Client
import com.craftersy.treadmill.client.ConnectionEvent
import com.craftersy.treadmill.client.ConnectionState
import com.craftersy.treadmill.client.DisconnectedException
import com.craftersy.treadmill.client.NotAcknowledgedException
import com.craftersy.treadmill.client.NotRunningException
import com.craftersy.treadmill.protocol.Incline
import com.craftersy.treadmill.protocol.Speed
import com.craftersy.treadmill.protocol.State
import com.craftersy.treadmill.protocol.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Bounds one command, including its acknowledgement. */
val COMMAND_TIMEOUT: Duration = 5.seconds

/** Opens a client. Connection changes are reported to `onEvent`. */
typealias Connector = suspend (onEvent: (ConnectionEvent) -> Unit) -> Client

enum class Link { CONNECTING, CONNECTED, LOST, FAILED }

/** What a button or key asks the treadmill to do. */
enum class Action { START, STOP, SPEED_UP, SPEED_DOWN, INCLINE_UP, INCLINE_DOWN, SPEED_PRESET, INCLINE_PRESET, HELP }

/** Everything the console screen shows. */
data class PanelState(
    val device: String,
    val link: Link = Link.CONNECTING,
    val status: Status? = null,
    val note: String = "",
    val noteIsError: Boolean = false,
    val help: Boolean = false,
    val quitting: Boolean = false,
    /** The user asked to leave and nothing is left to do; the screen should close. */
    val finished: Boolean = false,
) {
    /** Whether the belt is moving or about to, so leaving must stop it first. */
    val moving: Boolean get() = status?.state == State.STARTING || status?.state == State.RUNNING
}

/**
 * The console's behaviour, independent of Android: it connects, follows the
 * status and turns button presses into acknowledged commands.
 *
 * All methods must be called on [scope]'s dispatcher, which must be
 * single-threaded (the main thread in the app).
 *
 * Commands run one at a time, in the order of the presses: a later "incline 2"
 * must never be overtaken by an earlier "incline 1", nor a STOP by anything.
 * Quick presses build on the targets of commands still in flight instead of
 * on a status frame that is not there yet, so five quick "+" give exactly
 * +0.5 km/h.
 */
class PanelController(
    private val scope: CoroutineScope,
    device: String,
    connect: Connector,
    private val commandTimeout: Duration = COMMAND_TIMEOUT,
) {
    private val mutableState = MutableStateFlow(PanelState(device = device, note = "Searching for $device…"))
    val state: StateFlow<PanelState> = mutableState.asStateFlow()

    /** The client once connected. */
    @Volatile
    var client: Client? = null
        private set

    private var pending = 0
    private var want: Pair<Speed, Incline>? = null

    private class Queued(val label: String, val quit: Boolean, val run: suspend () -> Unit)

    private val jobs = Channel<Queued>(32)

    init {
        scope.launch {
            for (job in jobs) {
                val error = try {
                    withTimeout(commandTimeout) { job.run() }
                    null
                } catch (e: TimeoutCancellationException) {
                    e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }
                onResult(job, error)
            }
        }
        scope.launch {
            val c = try {
                connect { event -> scope.launch { onConnectionEvent(event) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(link = Link.FAILED) }
                setNote(e.message ?: e.toString(), true)
                return@launch
            }
            client = c
            mutableState.update { it.copy(link = Link.CONNECTED) }
            setNote("Connected. Press START.", false)
            launch { c.status.filterNotNull().collect(::onStatus) }
            launch {
                c.done.join()
                mutableState.update { it.copy(link = Link.FAILED) }
                if (!state.value.finished) setNote("Connection closed.", true)
            }
        }
    }

    private fun onStatus(status: Status) {
        mutableState.update { it.copy(status = status) }
        if (pending == 0) want = null
    }

    private fun onConnectionEvent(event: ConnectionEvent) {
        when (event.state) {
            ConnectionState.CONNECTED -> {
                mutableState.update { it.copy(link = Link.CONNECTED) }
                if (event.attempt > 0) setNote("Reconnected.", false)
            }
            ConnectionState.DISCONNECTED -> mutableState.update { it.copy(link = Link.LOST) }
            ConnectionState.RECONNECT_FAILED -> {
                mutableState.update { it.copy(link = Link.LOST) }
                setNote("Reconnect attempt ${event.attempt} failed: ${event.error?.message}", true)
            }
        }
    }

    private fun onResult(job: Queued, error: Exception?) {
        pending--
        if (error != null) {
            want = null
            setNote(describe(error), true)
        } else if (job.label.isNotEmpty()) {
            setNote(job.label, false)
        }
        if (job.quit) mutableState.update { it.copy(finished = true) }
    }

    private fun setNote(note: String, isError: Boolean) {
        mutableState.update { it.copy(note = note, noteIsError = isError) }
    }

    /**
     * Leaves the console. A moving belt is stopped first and [PanelState.finished]
     * turns true once the stop is confirmed; asking again leaves at once.
     */
    fun quit() {
        val st = state.value
        val c = client
        if (st.quitting || c == null || !st.moving) {
            mutableState.update { it.copy(finished = true) }
            return
        }
        mutableState.update { it.copy(quitting = true) }
        setNote("Stopping the belt before leaving… (press back again to leave now)", false)
        enqueue("", quit = true) { c.stop() }
    }

    /** Handles a button press; [value] is the target of a preset (tenths of km/h for speed, level for incline). */
    fun press(action: Action, value: Int = 0) {
        if (action == Action.HELP) {
            mutableState.update { it.copy(help = !it.help) }
            return
        }
        val c = client
        if (c == null) {
            setNote("Not connected yet.", true)
            return
        }
        when (action) {
            Action.START -> {
                setNote("Starting: 3-second countdown…", false)
                enqueue("Running. Use − / + or the quick keys for speed, ▽ / △ for incline.") { c.start() }
                return
            }
            Action.STOP -> {
                want = null
                setNote("Stopping…", false)
                enqueue("Stopped.") { c.stop() }
                return
            }
            else -> Unit
        }

        val status = state.value.status
        if (status == null || status.state != State.RUNNING) {
            setNote("The belt is not running. Press START.", true)
            return
        }
        var (speed, incline) = want ?: (status.targetSpeed to status.incline)
        when (action) {
            Action.SPEED_UP -> speed += 1
            Action.SPEED_DOWN -> speed -= 1
            Action.SPEED_PRESET -> speed = Speed(value)
            Action.INCLINE_UP -> incline = Incline(incline.level + 1)
            Action.INCLINE_DOWN -> {
                if (incline == Incline.MIN) {
                    setNote("Incline is already at its minimum.", true)
                    return
                }
                incline = Incline(incline.level - 1)
            }
            Action.INCLINE_PRESET -> incline = Incline(value)
            else -> Unit
        }
        if (speed < Speed.MIN || speed > Speed.MAX) {
            setNote("Speed must stay within ${Speed.MIN} – ${Speed.MAX}.", true)
            return
        }
        if (incline > Incline.MAX) {
            setNote("Incline must stay within ${Incline.MIN} – ${Incline.MAX}.", true)
            return
        }

        want = speed to incline
        enqueue("Set $speed, incline $incline.") { c.setSpeedIncline(speed.kmh, incline.level) }
    }

    /** Queues a treadmill command; its result updates the note. */
    private fun enqueue(label: String, quit: Boolean = false, run: suspend () -> Unit) {
        if (jobs.trySend(Queued(label, quit, run)).isSuccess) {
            pending++
        } else {
            want = null
            setNote("Too many commands queued; wait a moment.", true)
        }
    }

    /**
     * Stops the belt if it may still be moving, e.g. when the app closes
     * while a command is still queued. Returns a message for the user, or
     * null when nothing had to be done.
     */
    suspend fun safetyStop(): String? {
        val c = client ?: return null
        val st = c.status.value ?: return null
        if (st.state != State.STARTING && st.state != State.RUNNING) return null
        return try {
            withTimeout(commandTimeout) { c.stop() }
            "Belt stopped."
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            "Could not stop the belt. Stop it on the console or pull the safety key!"
        } catch (e: Exception) {
            "Could not stop the belt: ${e.message}. Stop it on the console or pull the safety key!"
        }
    }
}

/** Turns a command error into a message for the user. */
fun describe(error: Throwable): String = when (error) {
    is NotRunningException -> "The belt is not running. Press START."
    is DisconnectedException -> "Not connected. The belt keeps its last setting!"
    is NotAcknowledgedException -> "The treadmill did not confirm the command."
    is TimeoutCancellationException -> "The treadmill did not answer in time."
    else -> error.message ?: error.toString()
}
