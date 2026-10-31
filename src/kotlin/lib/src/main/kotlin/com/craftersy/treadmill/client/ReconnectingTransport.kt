package com.craftersy.treadmill.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Kind of a connection change reported by [ReconnectingTransport]. */
public enum class ConnectionState {
    /** A connection has been established (initially or again). */
    CONNECTED,

    /** The connection dropped; reconnection attempts follow. */
    DISCONNECTED,

    /** One reconnection attempt failed; [ConnectionEvent.error] says why. */
    RECONNECT_FAILED,
}

/** Describes a change of the connection. */
public data class ConnectionEvent(
    val state: ConnectionState,
    /** Reconnection attempt number, starting at 1; 0 for the first connection. */
    val attempt: Int = 0,
    /** Set for [ConnectionState.RECONNECT_FAILED]. */
    val error: Throwable? = null,
)

/**
 * A [Transport] that dials again whenever the connection drops, until its
 * scope ends or [close] is called.
 *
 * The treadmill keeps running at its last setting while the connection is
 * down. Use `onChange` to react, for example by warning the user. While
 * disconnected, [write] fails with [DisconnectedException] and [Client]
 * commands fail with it as well; [Client.status] keeps the last status
 * received until the next frame arrives.
 */
public class ReconnectingTransport private constructor(
    scope: CoroutineScope,
    private val dial: suspend () -> Transport,
    private val minDelay: Duration,
    private val maxDelay: Duration,
    private val onChange: (ConnectionEvent) -> Unit,
    first: Transport,
) : Transport {
    private val out = Channel<ByteArray>(64)

    @Volatile
    private var current: Transport? = null

    @Volatile
    private var closing = false

    override val frames: ReceiveChannel<ByteArray> get() = out

    /** Whether a connection is currently up. */
    public val connected: Boolean get() = current != null

    private val job: Job = scope.launch(start = CoroutineStart.ATOMIC) { // so that finally always runs
        var tr = first
        var attempt = 0
        try {
            while (true) {
                current = tr
                onChange(ConnectionEvent(ConnectionState.CONNECTED, attempt))
                for (f in tr.frames) out.send(f)
                current = null
                onChange(ConnectionEvent(ConnectionState.DISCONNECTED))

                var wait = minDelay
                attempt = 1
                while (true) {
                    delay(wait)
                    try {
                        tr = dial()
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onChange(ConnectionEvent(ConnectionState.RECONNECT_FAILED, attempt, e))
                        wait = minOf(wait * 2, maxDelay)
                        attempt++
                    }
                }
            }
        } finally {
            closing = true
            current?.close()
            current = null
            out.close()
        }
    }

    override suspend fun write(frame: ByteArray) {
        val cur = current ?: throw if (closing || !job.isActive) ClosedException() else DisconnectedException()
        cur.write(frame)
    }

    /** Stops reconnecting and closes the current connection. */
    override fun close() {
        closing = true
        job.cancel()
    }

    public companion object {
        /**
         * Dials once and throws if that fails. Afterwards it reconnects in the
         * background, waiting [minDelay] after a loss and doubling the delay up
         * to [maxDelay] between failed attempts. [scope] bounds the whole
         * lifetime of the transport, reconnections included. [onChange] runs
         * on the transport's coroutine and must not block.
         */
        public suspend fun dial(
            scope: CoroutineScope,
            minDelay: Duration = 1.seconds,
            maxDelay: Duration = 30.seconds,
            onChange: (ConnectionEvent) -> Unit = {},
            dial: suspend () -> Transport,
        ): ReconnectingTransport {
            val first = dial()
            return ReconnectingTransport(scope, dial, minDelay, maxDelay, onChange, first)
        }
    }
}
