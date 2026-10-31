package com.craftersy.treadmill.panel

import android.view.KeyEvent
import com.craftersy.treadmill.client.Client
import com.craftersy.treadmill.client.ConnectionEvent
import com.craftersy.treadmill.client.ConnectionState
import com.craftersy.treadmill.client.DisconnectedException
import com.craftersy.treadmill.client.NotAcknowledgedException
import com.craftersy.treadmill.protocol.Command
import com.craftersy.treadmill.protocol.Incline
import com.craftersy.treadmill.protocol.Speed
import com.craftersy.treadmill.protocol.State
import com.craftersy.treadmill.protocol.Status
import com.craftersy.treadmill.protocol.toHex
import com.craftersy.treadmill.simulator.Simulator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PanelTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val connection = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        main.cancel()
        connection.cancel()
        executor.shutdown()
    }

    private class Harness(val panel: PanelController, val sim: Simulator, val events: CompletableDeferred<(ConnectionEvent) -> Unit>)

    private fun harness(): Harness {
        val sim = Simulator(connection, 2.milliseconds)
        val events = CompletableDeferred<(ConnectionEvent) -> Unit>()
        val panel = PanelController(main, "simulator", { onEvent ->
            events.complete(onEvent)
            Client(sim, connection)
        })
        return Harness(panel, sim, events)
    }

    /** Runs [block] on the panel's main thread, so queued commands cannot run in between. */
    private suspend fun <T> PanelController.on(block: PanelController.() -> T): T = withContext(main.coroutineContext) { block() }

    private suspend fun PanelController.until(cond: (PanelState) -> Boolean): PanelState = state.first(cond)

    private fun test(body: suspend () -> Unit) = runBlocking { withTimeout(10.seconds) { body() } }

    private suspend fun Harness.running() {
        panel.until { it.link == Link.CONNECTED && it.status != null }
        panel.on { press(Action.START) }
        panel.until { it.status?.state == State.RUNNING }
    }

    private fun Harness.settings(): List<String> =
        sim.writes().map { Command.decode(it) }.filter { it.group == 0xA1 }.map { "${it.data[0]}/${it.data[1]}" }

    @Test
    fun startAndQuickSpeedPresses() = test {
        val h = harness()
        h.running()
        h.panel.on { repeat(5) { press(Action.SPEED_UP) } }
        val st = h.panel.until { it.status?.targetSpeed == Speed(15) && it.note.startsWith("Set 1.5") }
        assertFalse(st.noteIsError)
        assertEquals(listOf("11/0", "12/0", "13/0", "14/0", "15/0"), h.settings())
    }

    @Test
    fun inclineKeepsSpeedAndPresets() = test {
        val h = harness()
        h.running()
        h.panel.on {
            press(Action.SPEED_PRESET, 30)
            press(Action.INCLINE_UP)
            press(Action.INCLINE_PRESET, 6)
            press(Action.INCLINE_DOWN)
        }
        h.panel.until { it.status?.incline == Incline(5) && it.status?.targetSpeed == Speed(30) }
        assertEquals(listOf("30/0", "30/1", "30/6", "30/5"), h.settings())
    }

    @Test
    fun limitsAreCheckedBeforeSending() = test {
        val h = harness()
        h.running()
        h.panel.on { press(Action.SPEED_DOWN) }
        assertEquals("Speed must stay within 1.0 km/h – 22.0 km/h.", h.panel.state.value.note)
        h.panel.on { press(Action.INCLINE_DOWN) }
        assertEquals("Incline is already at its minimum.", h.panel.state.value.note)
        h.panel.on { press(Action.INCLINE_PRESET, 23) }
        assertEquals("Incline must stay within 0 – 22.", h.panel.state.value.note)
        assertTrue(h.panel.state.value.noteIsError)
        assertTrue(h.settings().isEmpty())
    }

    @Test
    fun speedButtonsNeedARunningBelt() = test {
        val h = harness()
        h.panel.until { it.status != null }
        h.panel.on { press(Action.SPEED_UP) }
        assertEquals("The belt is not running. Press START.", h.panel.state.value.note)
        assertTrue(h.sim.writes().isEmpty())
    }

    @Test
    fun stopButton() = test {
        val h = harness()
        h.running()
        h.panel.on { press(Action.STOP) }
        h.panel.until { it.note == "Stopped." }
        h.panel.until { it.status?.state == State.IDLE }
        assertEquals(Command.stop().toHex(), h.sim.writes().last().toHex())
    }

    @Test
    fun quitStopsTheBeltFirst() = test {
        val h = harness()
        h.running()
        // Read the state before the queued STOP can run: the simulator confirms it at once.
        val asked = h.panel.on { quit(); state.value }
        assertTrue(asked.quitting)
        assertFalse(asked.finished)
        h.panel.until { it.finished }
        assertEquals(Command.stop().toHex(), h.sim.writes().last().toHex())
    }

    @Test
    fun secondQuitLeavesAtOnce() = test {
        val h = harness()
        h.running()
        h.panel.on { quit(); quit() }
        assertTrue(h.panel.state.value.finished)
    }

    @Test
    fun quitWhenIdleIsImmediate() = test {
        val h = harness()
        h.panel.until { it.status != null }
        h.panel.on { quit() }
        assertTrue(h.panel.state.value.finished)
        assertTrue(h.sim.writes().isEmpty())
    }

    @Test
    fun safetyStopStopsAMovingBelt() = test {
        val h = harness()
        h.running()
        assertEquals("Belt stopped.", h.panel.safetyStop())
        h.panel.until { it.status?.state != State.RUNNING }
        assertNull(h.panel.safetyStop())
    }

    @Test
    fun connectionEvents() = test {
        val h = harness()
        h.panel.until { it.link == Link.CONNECTED }
        val emit = h.events.await()
        emit(ConnectionEvent(ConnectionState.DISCONNECTED))
        h.panel.until { it.link == Link.LOST }
        emit(ConnectionEvent(ConnectionState.RECONNECT_FAILED, 2, IllegalStateException("out of range")))
        h.panel.until { it.note == "Reconnect attempt 2 failed: out of range" && it.noteIsError }
        emit(ConnectionEvent(ConnectionState.CONNECTED, 3))
        h.panel.until { it.link == Link.CONNECTED && it.note == "Reconnected." }
    }

    @Test
    fun failedConnectShowsTheError() = test {
        val panel = PanelController(main, "RZ_TreadMil", { error("Bluetooth is off. Turn it on and try again.") })
        val st = panel.until { it.link == Link.FAILED }
        assertEquals("Bluetooth is off. Turn it on and try again.", st.note)
        assertTrue(st.noteIsError)
    }

    @Test
    fun describeErrors() {
        assertEquals("Not connected. The belt keeps its last setting!", describe(DisconnectedException()))
        assertEquals("The treadmill did not confirm the command.", describe(NotAcknowledgedException()))
    }

    @Test
    fun readoutsOfCapturedFrames() {
        assertEquals(Readouts(), readouts(null))
        assertEquals(Readouts(), readouts(Status(State.IDLE, compact = true)))
        val running = Status(
            State.RUNNING, targetSpeed = Speed(30), currentSpeed = Speed(24), incline = Incline(3),
            elapsed = 2.minutes + 5.seconds, distance = 60, calories = 4.4, heartRate = 85,
        )
        assertEquals(Readouts("3", "3.0", "belt 2.4 km/h", "85", "2:05", "0.06", "4.4"), readouts(running))
        assertEquals("", readouts(running.copy(currentSpeed = Speed(30))).belt)
        assertEquals("--", readouts(running.copy(heartRate = 0)).pulse)
        assertEquals("12.34", readouts(running.copy(distance = 12340)).distance)
    }

    @Test
    fun keysMatchTheTerminalPanel() {
        val cases = mapOf(
            (KeyEvent.KEYCODE_ENTER to 0) to KeyCommand.Press(Action.START),
            (KeyEvent.KEYCODE_SPACE to ' '.code) to KeyCommand.Press(Action.STOP),
            (0 to 'x'.code) to KeyCommand.Press(Action.STOP),
            (0 to 'p'.code) to KeyCommand.Press(Action.SPEED_UP),
            (0 to '+'.code) to KeyCommand.Press(Action.SPEED_UP),
            (KeyEvent.KEYCODE_DPAD_LEFT to 0) to KeyCommand.Press(Action.SPEED_DOWN),
            (0 to 'm'.code) to KeyCommand.Press(Action.SPEED_DOWN),
            (KeyEvent.KEYCODE_DPAD_UP to 0) to KeyCommand.Press(Action.INCLINE_UP),
            (0 to 'j'.code) to KeyCommand.Press(Action.INCLINE_DOWN),
            (0 to '0'.code) to KeyCommand.Press(Action.SPEED_PRESET, 120),
            (0 to 'd'.code) to KeyCommand.Press(Action.INCLINE_PRESET, 9),
            (0 to '?'.code) to KeyCommand.Press(Action.HELP),
            (0 to 'q'.code) to KeyCommand.Quit,
            (KeyEvent.KEYCODE_ESCAPE to 0) to KeyCommand.Quit,
            (0 to 'z'.code) to null,
        )
        for ((key, want) in cases) assertEquals(want, keyCommand(key.first, key.second), "key $key")
    }

    @Test
    fun segments() {
        assertEquals(SEG_B or SEG_C, segmentsOf('1'))
        assertEquals(127, segmentsOf('8'))
        assertEquals(SEG_G, segmentsOf('-'))
        assertEquals(0, segmentsOf(' '))
        assertEquals(0, segmentsOf('?'))
        // Every digit lights a distinct pattern.
        assertEquals(10, ('0'..'9').map(::segmentsOf).toSet().size)
    }
}
