package com.craftersy.treadmill.protocol

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The treadmill state reported in every status frame. */
@JvmInline
public value class State(public val code: Int) {
    override fun toString(): String = when (this) {
        IDLE -> "idle"
        STARTING -> "starting"
        RUNNING -> "running"
        STOPPING -> "stopping"
        STOPPED -> "stopped"
        else -> "state(0x%02X)".format(code)
    }

    public companion object {
        /** Belt stopped; the display shows "WELCOME" or a program or target is being picked. */
        public val IDLE: State = State(0x00)

        /** 3-second countdown after a start command. */
        public val STARTING: State = State(0x01)

        /** Belt moving. */
        public val RUNNING: State = State(0x02)

        /** Belt braking after a stop command. */
        public val STOPPING: State = State(0x04)

        /** Belt halted; this frame still carries the final metrics. */
        public val STOPPED: State = State(0x05)
    }
}

/**
 * A decoded status notification.
 *
 * While the belt is idle and a program or target is being picked on the
 * console, the metric fields hold that target rather than a counter, and
 * [incline] holds the program number.
 */
public data class Status(
    val state: State,
    /**
     * True for the 7-byte frame sent while idle and right after a start
     * command. Compact frames carry no speed, incline or metrics.
     */
    val compact: Boolean = false,
    /** Speed the belt is heading to; changes as soon as a command is accepted. */
    val targetSpeed: Speed = Speed(0),
    /** Actual belt speed; ramps towards [targetSpeed] in about 2 s. */
    val currentSpeed: Speed = Speed(0),
    /** Target incline; the treadmill does not report the motor position. */
    val incline: Incline = Incline(0),
    /** Whole seconds since start. */
    val elapsed: Duration = Duration.ZERO,
    /** Metres, 10 m resolution. */
    val distance: Int = 0,
    /** kcal, 0.1 kcal resolution. */
    val calories: Double = 0.0,
    /** Beats per minute from the hand grip sensors, 0 without contact. */
    val heartRate: Int = 0,
    /** The frame as received. */
    val raw: List<Byte> = emptyList(),
) {
    public companion object {
        /** Decodes a status notification, verifying framing, length and checksum. */
        public fun parse(frame: ByteArray): Status {
            val n = frame.size
            if (n < 4 || frame.u8(0) != STATUS_START || frame.u8(n - 1) != STATUS_END) {
                throw MalformedFrameException("bad status framing")
            }
            if (frame.u8(1) != n - 2) {
                throw MalformedFrameException("status length byte ${frame.u8(1)}, frame has $n bytes")
            }
            val want = checksum(frame, 1, n - 2)
            if (frame.u8(n - 2) != want) {
                throw MalformedFrameException("status checksum %02X, want %02X".format(frame.u8(n - 2), want))
            }
            if (frame.u8(2) != STATUS_CODE) {
                throw MalformedFrameException("unknown status code %02X".format(frame.u8(2)))
            }
            val state = State(frame.u8(3))
            val raw = frame.toList()
            return when (n) {
                COMPACT_STATUS_LEN -> Status(state = state, compact = true, raw = raw)
                // frame[7] always mirrors frame[6].
                // frame[8] has always been 0x00; it may hold hours.
                FULL_STATUS_LEN -> Status(
                    state = state,
                    targetSpeed = Speed(frame.u8(4)),
                    currentSpeed = Speed(frame.u8(5)),
                    incline = Incline(frame.u8(6)),
                    elapsed = frame.u8(9).minutes + frame.u8(10).seconds,
                    distance = frame.be16(11) * 10,
                    calories = frame.be16(13) / 10.0,
                    heartRate = frame.u8(15),
                    raw = raw,
                )
                else -> throw MalformedFrameException("unsupported status length $n")
            }
        }
    }
}

private fun ByteArray.be16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
