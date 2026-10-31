package com.craftersy.treadmill.client

import com.craftersy.treadmill.protocol.State
import com.craftersy.treadmill.simulator.Simulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Hands out simulators and fails dials on demand. */
private class Dialer(private val scope: CoroutineScope) {
    val sims = mutableListOf<Simulator>()

    @Volatile
    var fails = 0

    @Synchronized
    fun dial(): Transport {
        if (fails > 0) {
            fails--
            error("out of range")
        }
        return Simulator(scope, TICK).also { sims += it }
    }

    @Synchronized
    fun last(): Simulator = sims.last()
}

private suspend fun CoroutineScope.reconnecting(d: Dialer, events: Channel<ConnectionEvent>) =
    ReconnectingTransport.dial(this, minDelay = 5.milliseconds, maxDelay = 20.milliseconds, onChange = { events.trySend(it) }) { d.dial() }

class ReconnectingTransportTest {
    @Test
    fun reconnectsAfterConnectionLoss() = scoped {
        val d = Dialer(this)
        val events = Channel<ConnectionEvent>(32)
        val tr = reconnecting(d, events)
        val c = Client(tr, this)
        assertEquals(ConnectionEvent(ConnectionState.CONNECTED, 0), events.receive())
        c.start()

        d.fails = 2
        d.last().close() // the treadmill drops the connection
        assertEquals(ConnectionState.DISCONNECTED, events.receive().state)
        assertEquals(ConnectionState.RECONNECT_FAILED, events.receive().state)
        val failed = events.receive()
        assertEquals(ConnectionState.RECONNECT_FAILED, failed.state)
        assertEquals(2, failed.attempt)
        assertEquals(ConnectionEvent(ConnectionState.CONNECTED, 3), events.receive())
        assertTrue(tr.connected)

        // The client keeps working on the new connection.
        c.waitFor { it.state == State.IDLE }
        c.start()
        assertEquals(2, d.sims.size)
        c.close()
    }

    @Test
    fun writeWhileDisconnected() = scoped {
        val d = Dialer(this)
        val events = Channel<ConnectionEvent>(32)
        val tr = ReconnectingTransport.dial(this, minDelay = 10_000.milliseconds, onChange = { events.trySend(it) }) { d.dial() }
        events.receive()
        d.last().close()
        assertEquals(ConnectionState.DISCONNECTED, events.receive().state)
        assertFalse(tr.connected)
        assertFailsWith<DisconnectedException> { tr.write(byteArrayOf(1)) }
        tr.close()
    }

    @Test
    fun firstDialErrorIsThrown() = scoped {
        val d = Dialer(this).apply { fails = 1 }
        assertFailsWith<IllegalStateException> { reconnecting(d, Channel(32)) }
    }

    @Test
    fun closeStopsReconnecting() = scoped {
        val d = Dialer(this)
        val tr = reconnecting(d, Channel(32))
        tr.close()
        for (unused in tr.frames) Unit // the channel closes
        assertFailsWith<ClosedException> { tr.write(byteArrayOf(1)) }
        assertEquals(1, d.sims.size)
    }

    @Test
    fun scopeEndStopsTransport() = scoped {
        val d = Dialer(this)
        val child = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val tr = child.reconnecting(d, Channel(32))
        child.coroutineContext[Job]!!.cancel()
        for (unused in tr.frames) Unit
        assertFailsWith<ClosedException> { tr.write(byteArrayOf(1)) }
    }
}
