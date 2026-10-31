/**
 * Encodes commands for, and decodes status frames from, treadmills that
 * advertise themselves over Bluetooth Low Energy as "RZ_TreadMil" (service
 * FFF0).
 *
 * The package is pure: it performs no I/O and has no Bluetooth dependency, so
 * it can be reused with any BLE stack. Every constant here was confirmed on a
 * physical treadmill; the measurement log lives in docs/protocol.md in the
 * repository root.
 *
 * Commands (written to characteristic FFF2):
 *
 *     FB  LEN  GROUP  TYPE  MASTER  DATA...  CHK  FC
 *
 * LEN counts GROUP, TYPE, MASTER and DATA. CHK is the low byte of the sum of
 * LEN through the last DATA byte.
 *
 * Status frames (notifications on characteristic FFF1):
 *
 *     FD  LEN  A1  PAYLOAD...  CHK  FE
 *
 * LEN counts every byte except FD and FE. CHK is the low byte of the sum of
 * LEN through the last PAYLOAD byte. Two lengths exist: a 7-byte compact frame
 * (idle, start acknowledgement) and an 18-byte full frame with metrics.
 */
package com.craftersy.treadmill.protocol

/** BLE identifiers of the treadmill. */
public object Ble {
    /** The local name the treadmill advertises. */
    public const val DEVICE_NAME: String = "RZ_TreadMil"

    /** The only service that carries commands. */
    public const val SERVICE_UUID: String = "0000fff0-0000-1000-8000-00805f9b34fb"

    /** Streams status frames (notify). */
    public const val NOTIFY_CHARACTERISTIC_UUID: String = "0000fff1-0000-1000-8000-00805f9b34fb"

    /** Accepts command frames (write). */
    public const val WRITE_CHARACTERISTIC_UUID: String = "0000fff2-0000-1000-8000-00805f9b34fb"

    /** Client Characteristic Configuration descriptor, written to enable notifications. */
    public const val CCCD_UUID: String = "00002902-0000-1000-8000-00805f9b34fb"
}

/** Command groups, types and the master flag. */
public object Commands {
    public const val GROUP_SETTINGS: Int = 0xA1
    public const val GROUP_CONTROL: Int = 0xA2

    /** In [GROUP_SETTINGS]. */
    public const val TYPE_SET_SPEED_INCLINE: Int = 0x02

    /** In [GROUP_CONTROL]. */
    public const val TYPE_START: Int = 0x01

    /** In [GROUP_CONTROL]. */
    public const val TYPE_STOP: Int = 0x04

    /**
     * Marks a command as coming from the app. The treadmill ignores set-speed
     * and stop commands sent with any other value.
     */
    public const val MASTER_APP: Int = 0x01
}

internal const val COMMAND_START = 0xFB
internal const val COMMAND_END = 0xFC
internal const val STATUS_START = 0xFD
internal const val STATUS_END = 0xFE
internal const val STATUS_CODE = 0xA1

/** Length of the compact status frame. */
public const val COMPACT_STATUS_LEN: Int = 7

/** Length of the full status frame. */
public const val FULL_STATUS_LEN: Int = 18

/** A speed or incline value the treadmill would silently ignore. */
public class OutOfRangeException(message: String) : IllegalArgumentException("protocol: value out of range: $message")

/** A frame with broken framing, length or checksum. */
public class MalformedFrameException(message: String) : IllegalArgumentException("protocol: malformed frame: $message")

/** A belt speed in tenths of km/h, the unit used on the wire. */
@JvmInline
public value class Speed(public val tenths: Int) : Comparable<Speed> {
    /** The speed in km/h. */
    public val kmh: Double get() = tenths / 10.0

    public operator fun plus(tenths: Int): Speed = Speed(this.tenths + tenths)

    public operator fun minus(tenths: Int): Speed = Speed(this.tenths - tenths)

    override fun compareTo(other: Speed): Int = tenths.compareTo(other.tenths)

    /** Formats the speed like the console, e.g. "3.5 km/h". */
    override fun toString(): String = "${tenths / 10}.${tenths % 10} km/h"

    public companion object {
        /** 1.0 km/h, the lowest target the treadmill accepts. */
        public val MIN: Speed = Speed(10)

        /** 22.0 km/h, the highest target the treadmill accepts. */
        public val MAX: Speed = Speed(220)

        /** Converts km/h to a [Speed], rounding to the nearest 0.1 km/h. */
        public fun fromKmh(kmh: Double): Speed {
            val tenths = kmh * 10 + 0.5
            if (!(tenths >= MIN.tenths && tenths < MAX.tenths + 1)) {
                throw OutOfRangeException("speed %.2f km/h (allowed %s–%s)".format(kmh, MIN, MAX))
            }
            return Speed(tenths.toInt())
        }
    }
}

/** An incline level as shown on the treadmill display. */
@JvmInline
public value class Incline(public val level: Int) : Comparable<Incline> {
    override fun compareTo(other: Incline): Int = level.compareTo(other.level)

    override fun toString(): String = level.toString()

    public companion object {
        public val MIN: Incline = Incline(0)
        public val MAX: Incline = Incline(22)

        /** Validates an incline level. */
        public fun fromLevel(level: Int): Incline {
            if (level < MIN.level || level > MAX.level) {
                throw OutOfRangeException("incline $level (allowed $MIN–$MAX)")
            }
            return Incline(level)
        }
    }
}

internal fun checksum(bytes: ByteArray, from: Int, to: Int): Int {
    var sum = 0
    for (i in from until to) sum += bytes[i].toInt() and 0xFF
    return sum and 0xFF
}

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

/** Formats bytes the way the measurement log does: "FD-05-A1-00-00-A6-FE". */
public fun ByteArray.toHex(): String = joinToString("-") { "%02X".format(it.toInt() and 0xFF) }

/** Parses "FD-05-A1-…" or "FD 05 A1 …" notation. */
public fun hexToBytes(hex: String): ByteArray {
    val digits = hex.filter { it.isLetterOrDigit() }
    require(digits.length % 2 == 0) { "odd number of hex digits in \"$hex\"" }
    return ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
