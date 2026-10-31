package com.craftersy.treadmill.client

import com.craftersy.treadmill.protocol.Command
import com.craftersy.treadmill.protocol.Incline
import com.craftersy.treadmill.protocol.OutOfRangeException
import com.craftersy.treadmill.protocol.Speed
import com.craftersy.treadmill.protocol.State
import com.craftersy.treadmill.protocol.toHex
import com.craftersy.treadmill.simulator.Simulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal val TICK = 2.milliseconds

/** Runs [body] with a scope for clients and simulators that is cancelled afterwards. */
internal fun scoped(body: suspend CoroutineScope.() -> Unit) = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        withTimeout(10.seconds) { scope.body() }
    } finally {
        scope.cancel()
    }
}

private fun CoroutineScope.newClient(ackTimeout: Duration = Client.DEFAULT_ACK_TIMEOUT): Pair<Client, Simulator> {
    val sim = Simulator(this, TICK)
    return Client(sim, this, ackTimeout) to sim
}

private suspend fun Client.running() {
    start()
    waitFor { it.state == State.RUNNING }
}

class ClientTest {
    @Test
    fun startRunsAtMinimumSpeed() = scoped {
        val (c, _) = newClient()
        c.running()
        val st = c.waitFor { it.currentSpeed == Speed.MIN }
        assertEquals(Speed.MIN, st.targetSpeed)
    }

    @Test
    fun setSpeedKeepsIncline() = scoped {
        val (c, _) = newClient()
        c.running()
        c.setSpeedIncline(3.0, 2)
        c.setSpeed(3.5)
        val st = c.status.value!!
        assertEquals(Speed(35), st.targetSpeed)
        assertEquals(Incline(2), st.incline)
    }

    @Test
    fun setInclineKeepsSpeed() = scoped {
        val (c, _) = newClient()
        c.running()
        c.setSpeed(2.5)
        c.setIncline(4)
        val st = c.status.value!!
        assertEquals(Speed(25), st.targetSpeed)
        assertEquals(Incline(4), st.incline)
    }

    @Test
    fun beltRampsToTargetSpeed() = scoped {
        val (c, _) = newClient()
        c.running()
        c.setSpeed(3.0)
        c.waitFor { it.currentSpeed == Speed(30) }
    }

    @Test
    fun stopBrakesToIdle() = scoped {
        val (c, _) = newClient()
        c.running()
        c.setSpeed(2.0)
        c.waitFor { it.currentSpeed == Speed(20) }
        c.stop()
        val stopped = c.waitFor { it.state == State.STOPPED }
        assertEquals(Speed(0), stopped.currentSpeed)
        assertTrue(stopped.elapsed > Duration.ZERO, "stopped frame carries the final metrics")
        c.waitFor { it.state == State.IDLE && it.compact }
    }

    @Test
    fun setRequiresRunningBelt() = scoped {
        val (c, sim) = newClient()
        c.waitFor { it.state == State.IDLE }
        val e = assertFailsWith<NotRunningException> { c.setSpeed(3.0) }
        assertEquals(State.IDLE, e.state)
        assertTrue(sim.writes().isEmpty(), "nothing must be written")
    }

    @Test
    fun setRejectsOutOfRangeWithoutWriting() = scoped {
        val (c, sim) = newClient()
        c.running()
        val before = sim.writes().size
        assertFailsWith<OutOfRangeException> { c.setSpeed(0.5) }
        assertFailsWith<OutOfRangeException> { c.setSpeed(22.1) }
        assertFailsWith<OutOfRangeException> { c.setIncline(23) }
        assertFailsWith<OutOfRangeException> { c.setIncline(-1) }
        assertEquals(before, sim.writes().size)
    }

    @Test
    fun noStatusYet() = scoped {
        val c = Client(SilentTransport(), this)
        assertFailsWith<NoStatusException> { c.setSpeed(3.0) }
    }

    @Test
    fun unacknowledgedCommand() = scoped {
        val c = Client(SilentTransport(), this, ackTimeout = 50.milliseconds)
        assertFailsWith<NotAcknowledgedException> { c.start() }
    }

    @Test
    fun ackDisabledReturnsAfterWrite() = scoped {
        val tr = SilentTransport()
        val c = Client(tr, this, ackTimeout = Duration.ZERO)
        c.start()
        assertEquals(listOf(Command.start().toHex()), tr.written.map { it.toHex() })
    }

    @Test
    fun writeErrorIsReturned() = scoped {
        val c = Client(SilentTransport(fail = DisconnectedException()), this)
        assertFailsWith<DisconnectedException> { c.start() }
    }

    @Test
    fun heartRate() = scoped {
        val (c, sim) = newClient()
        c.running()
        sim.setHeartRate(85)
        assertEquals(85, c.waitFor { it.heartRate > 0 }.heartRate)
    }

    @Test
    fun closeEndsCommands() = scoped {
        val (c, _) = newClient()
        c.waitFor { true }
        c.close()
        assertTrue(c.done.isCompleted)
        assertFailsWith<ClosedException> { c.start() }
        assertFailsWith<ClosedException> { c.waitFor { false } }
    }

    @Test
    fun malformedFramesAreIgnored() = scoped {
        val tr = SilentTransport()
        val c = Client(tr, this)
        tr.out.send(byteArrayOf(0xFD.toByte(), 0x05, 0x00))
        tr.out.send(com.craftersy.treadmill.protocol.hexToBytes("FD-05-A1-00-00-A6-FE"))
        assertEquals(State.IDLE, c.waitFor { true }.state)
    }
}

/** A transport that records writes and never answers unless a test sends frames. */
internal class SilentTransport(private val fail: Exception? = null) : Transport {
    val out = Channel<ByteArray>(16)
    val written = mutableListOf<ByteArray>()
    override val frames: ReceiveChannel<ByteArray> get() = out

    override suspend fun write(frame: ByteArray) {
        fail?.let { throw it }
        written += frame
    }

    override fun close() {
        out.close()
    }
}
