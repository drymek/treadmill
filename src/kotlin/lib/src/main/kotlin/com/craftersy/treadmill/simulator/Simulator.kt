/**
 * An in-memory treadmill simulator that implements [Transport], so
 * applications can be developed and tested without the hardware.
 *
 * The simulator reproduces the behaviour measured on the real device: a
 * compact status frame while idle, a countdown after start, the belt ramping
 * towards the target speed, braking after stop, out-of-range values being
 * ignored and commands without the master flag being ignored (except start).
 * It does not simulate calories.
 */
package com.craftersy.treadmill.simulator

import com.craftersy.treadmill.client.ClosedException
import com.craftersy.treadmill.client.Transport
import com.craftersy.treadmill.protocol.Command
import com.craftersy.treadmill.protocol.Commands
import com.craftersy.treadmill.protocol.Incline
import com.craftersy.treadmill.protocol.MalformedFrameException
import com.craftersy.treadmill.protocol.Speed
import com.craftersy.treadmill.protocol.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// Ramp rates per tick, in tenths of km/h, as measured on the device.
private const val ACCELERATION_PER_TICK = 5
private const val BRAKING_PER_TICK = 3
private const val COUNTDOWN_TICKS = 3

/**
 * A simulated treadmill. Each tick it advances the belt by one simulated
 * second and emits a status frame. The real treadmill ticks every ~1.3 s;
 * tests usually pass a few milliseconds.
 */
public class Simulator(scope: CoroutineScope, tick: Duration = DEVICE_TICK) : Transport {
    private val lock = Any()
    private var state = State.IDLE
    private var target = Speed(0)
    private var current = Speed(0)
    private var incline = Incline(0)
    private var countdown = 0
    private var seconds = 0
    private var metres = 0.0
    private var heartRate = 0
    private val written = mutableListOf<ByteArray>()
    private var closed = false

    private val out = Channel<ByteArray>(64)

    override val frames: ReceiveChannel<ByteArray> get() = out

    private val ticker = scope.launch(start = CoroutineStart.ATOMIC) { // so that finally always runs
        try {
            while (isActive) {
                delay(tick)
                synchronized(lock) {
                    advance()
                    emit()
                }
            }
        } finally {
            synchronized(lock) {
                closed = true
                out.close()
            }
        }
    }

    override fun close() {
        synchronized(lock) { closed = true }
        ticker.cancel()
    }

    override suspend fun write(frame: ByteArray): Unit = synchronized(lock) {
        if (closed) throw ClosedException()
        written += frame.copyOf()

        val cmd = try {
            Command.decode(frame)
        } catch (_: MalformedFrameException) {
            return // the device silently drops garbage
        }
        when {
            cmd.group == Commands.GROUP_CONTROL && cmd.type == Commands.TYPE_START -> {
                if (state == State.IDLE || state == State.STOPPED) {
                    state = State.STARTING
                    countdown = COUNTDOWN_TICKS
                    target = Speed(0); current = Speed(0); incline = Incline(0)
                    seconds = 0; metres = 0.0
                    push(0xFD, 0x05, 0xA1, State.STARTING.code, 0x03)
                }
            }
            cmd.group == Commands.GROUP_CONTROL && cmd.type == Commands.TYPE_STOP && cmd.master == Commands.MASTER_APP -> {
                when (state) {
                    State.STARTING, State.RUNNING -> {
                        state = State.STOPPING
                        target = Speed(0)
                    }
                    State.IDLE -> state = State.STOPPED
                }
                emit()
            }
            cmd.group == Commands.GROUP_SETTINGS -> {
                if (cmd.type == Commands.TYPE_SET_SPEED_INCLINE && cmd.master == Commands.MASTER_APP &&
                    cmd.data.size == 4 && state == State.RUNNING
                ) {
                    val speed = Speed(cmd.data[0])
                    val level = Incline(cmd.data[1])
                    if (speed >= Speed.MIN && speed <= Speed.MAX && level <= Incline.MAX) {
                        target = speed
                        incline = level
                    }
                }
                emit() // the device answers every settings command, even ignored ones
            }
        }
    }

    /** Every frame written so far. */
    public fun writes(): List<ByteArray> = synchronized(lock) { written.map { it.copyOf() } }

    /** Simulates hands on the grip sensors; 0 means no contact. */
    public fun setHeartRate(bpm: Int): Unit = synchronized(lock) { heartRate = bpm }

    /** Moves the simulation one second forward. Caller holds [lock]. */
    private fun advance() {
        when (state) {
            State.STOPPED -> state = State.IDLE
            State.STARTING -> {
                countdown--
                target = Speed.MIN
                current = Speed(Speed.MIN.tenths / 2)
                if (countdown <= 0) state = State.RUNNING
            }
            State.RUNNING -> {
                seconds++
                current = approach(current, target, ACCELERATION_PER_TICK)
                metres += current.kmh / 3.6
            }
            State.STOPPING -> {
                current = approach(current, Speed(0), BRAKING_PER_TICK)
                target = current
                incline = Incline(0)
                if (current.tenths == 0) state = State.STOPPED
            }
        }
    }

    /** Queues the current status. Caller holds [lock]. */
    private fun emit() {
        if (state == State.IDLE) {
            push(0xFD, 0x05, 0xA1, State.IDLE.code, 0x00)
            return
        }
        val dist = metres.toInt() / 10
        push(
            0xFD, 0x10, 0xA1, state.code,
            target.tenths, current.tenths, incline.level, incline.level,
            0x00, seconds / 60, seconds % 60,
            dist shr 8, dist and 0xFF,
            0x00, 0x00, // calories are not simulated
            heartRate,
        )
    }

    /**
     * Adds checksum and end byte to a frame body and queues it, dropping it
     * when nobody reads, like a radio would. Caller holds [lock].
     */
    private fun push(vararg body: Int) {
        if (closed) return
        var sum = 0
        for (b in body.drop(1)) sum += b
        val frame = ByteArray(body.size + 2)
        body.forEachIndexed { i, b -> frame[i] = b.toByte() }
        frame[body.size] = sum.toByte()
        frame[body.size + 1] = 0xFE.toByte()
        out.trySend(frame)
    }

    public companion object {
        /** The status interval of the real treadmill. */
        public val DEVICE_TICK: Duration = 1290.milliseconds
    }
}

private fun approach(from: Speed, to: Speed, step: Int): Speed {
    val d = (to.tenths - from.tenths).coerceIn(-step, step)
    return Speed(from.tenths + d)
}
