package com.craftersy.treadmill.simulator

import com.craftersy.treadmill.client.ClosedException
import com.craftersy.treadmill.client.scoped
import com.craftersy.treadmill.protocol.Command
import com.craftersy.treadmill.protocol.Commands
import com.craftersy.treadmill.protocol.Incline
import com.craftersy.treadmill.protocol.Speed
import com.craftersy.treadmill.protocol.State
import com.craftersy.treadmill.protocol.Status
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private suspend fun Simulator.next(): Status = Status.parse(frames.receive())

private suspend fun Simulator.until(cond: (Status) -> Boolean): Status {
    repeat(500) { next().let { if (cond(it)) return it } }
    error("condition never met")
}

private suspend fun CoroutineScope.startedSimulator(): Simulator {
    val s = Simulator(this, 1.milliseconds)
    s.next().let { assertTrue(it.state == State.IDLE && it.compact, "first frame $it, want compact idle") }
    s.write(Command.start())
    // Idle frames queued before the command may still be buffered.
    s.until { it.state != State.IDLE }.let {
        assertTrue(it.state == State.STARTING && it.compact, "start acknowledgement $it, want compact starting")
    }
    s.until { it.state == State.RUNNING }
    return s
}

class SimulatorTest {
    @Test
    fun ignoresWhatTheDeviceIgnores() = scoped {
        val s = startedSimulator()
        // Out of range, without the master flag, and an unknown settings type.
        s.write(Command.encodeCommand(Commands.GROUP_SETTINGS, Commands.TYPE_SET_SPEED_INCLINE, Commands.MASTER_APP, 221, 0, 0, 0))
        s.write(Command.encodeCommand(Commands.GROUP_SETTINGS, Commands.TYPE_SET_SPEED_INCLINE, Commands.MASTER_APP, 30, 23, 0, 0))
        s.write(Command.encodeCommand(Commands.GROUP_SETTINGS, Commands.TYPE_SET_SPEED_INCLINE, 0x00, 30, 2, 0, 0))
        s.write(Command.encodeCommand(Commands.GROUP_SETTINGS, 0x01, Commands.MASTER_APP, 30, 2, 0, 0))
        s.write(Command.encodeCommand(Commands.GROUP_CONTROL, Commands.TYPE_STOP, 0x00))
        s.write(byteArrayOf(1, 2, 3))
        repeat(10) {
            val st = s.next()
            assertEquals(State.RUNNING, st.state)
            assertEquals(Speed.MIN, st.targetSpeed)
            assertEquals(Incline(0), st.incline)
        }
    }

    @Test
    fun slaveStartStartsTheBelt() = scoped {
        val s = Simulator(this, 1.milliseconds)
        s.write(Command.encodeCommand(Commands.GROUP_CONTROL, Commands.TYPE_START, 0x00))
        s.until { it.state == State.RUNNING }
    }

    @Test
    fun stopWhileIdleAnswersStopped() = scoped {
        val s = Simulator(this, 1.milliseconds)
        s.until { it.state == State.IDLE }
        s.write(Command.stop())
        val st = s.until { it.state != State.IDLE }
        assertEquals(State.STOPPED, st.state)
        assertEquals(Duration.ZERO, st.elapsed)
        s.until { it.state == State.IDLE && it.compact }
    }

    @Test
    fun countersAndRestart() = scoped {
        val s = startedSimulator()
        s.write(Command.setSpeedIncline(Speed(36), Incline(0))) // 10 m per simulated second
        s.until { it.distance >= 30 }
        s.write(Command.stop())
        val stopped = s.until { it.state == State.STOPPED }
        assertTrue(stopped.elapsed > Duration.ZERO && stopped.distance >= 30, "final metrics $stopped")
        s.write(Command.start())
        val running = s.until { it.state == State.RUNNING }
        assertTrue(running.distance == 0 && running.elapsed <= 1.milliseconds * 1000, "counters restart: $running")
    }

    @Test
    fun writeAfterClose() = scoped {
        val s = Simulator(this, 1.milliseconds)
        s.close()
        assertFailsWith<ClosedException> { s.write(Command.start()) }
    }
}
