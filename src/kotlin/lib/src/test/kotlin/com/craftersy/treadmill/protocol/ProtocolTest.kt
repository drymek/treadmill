package com.craftersy.treadmill.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ProtocolTest {
    @Test
    fun commandsMatchCapturedFrames() {
        val cases = listOf(
            "start" to Command.start() to "FB-03-A2-01-01-A7-FC",
            "stop" to Command.stop() to "FB-03-A2-04-01-AA-FC",
            "set 2.0 km/h, incline 0" to Command.setSpeedIncline(Speed(20), Incline(0)) to "FB-07-A1-02-01-14-00-00-00-BF-FC",
            "set 3.0 km/h, incline 2" to Command.setSpeedIncline(Speed(30), Incline(2)) to "FB-07-A1-02-01-1E-02-00-00-CB-FC",
            "set 3.5 km/h, incline 0" to Command.setSpeedIncline(Speed(35), Incline(0)) to "FB-07-A1-02-01-23-00-00-00-CE-FC",
            "set 22.0 km/h, incline 0" to Command.setSpeedIncline(Speed(220), Incline(0)) to "FB-07-A1-02-01-DC-00-00-00-87-FC",
            "start as slave" to Command.encodeCommand(Commands.GROUP_CONTROL, Commands.TYPE_START, 0x00) to "FB-03-A2-01-00-A6-FC",
        )
        for ((named, want) in cases) {
            val (name, got) = named
            assertEquals(want, got.toHex(), name)
        }
    }

    @Test
    fun setSpeedInclineRejectsValuesTheTreadmillIgnores() {
        for ((s, i) in listOf(9 to 0, 221 to 0, 0 to 0, 30 to 23, 30 to 255, 30 to -1)) {
            assertFailsWith<OutOfRangeException>("speed $s, incline $i") {
                Command.setSpeedIncline(Speed(s), Incline(i))
            }
        }
    }

    @Test
    fun speedFromKmh() {
        val ok = mapOf(1.0 to 10, 1.04 to 10, 0.95 to 10, 3.5 to 35, 12.345 to 123, 22.0 to 220, 22.04 to 220)
        for ((kmh, want) in ok) assertEquals(Speed(want), Speed.fromKmh(kmh), "$kmh km/h")
        for (kmh in listOf(0.0, 0.9, 0.94, 22.05, 25.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<OutOfRangeException>("$kmh km/h") { Speed.fromKmh(kmh) }
        }
        assertEquals(3.5, Speed(35).kmh)
        assertEquals("3.5 km/h", Speed(35).toString())
    }

    @Test
    fun inclineFromLevel() {
        for (l in listOf(0, 1, 12, 22)) assertEquals(l, Incline.fromLevel(l).level)
        for (l in listOf(-1, 23, 256)) assertFailsWith<OutOfRangeException>("incline $l") { Incline.fromLevel(l) }
    }

    @Test
    fun decodeCommandRoundTrip() {
        val cmd = Command(Commands.GROUP_SETTINGS, Commands.TYPE_SET_SPEED_INCLINE, Commands.MASTER_APP, listOf(0x1E, 0x02, 0, 0))
        assertEquals(cmd, Command.decode(cmd.encode()))
    }

    @Test
    fun decodeCommandRejectsMalformed() {
        for (s in listOf(
            "",
            "FB-03-A2-01-01-A7", // no end byte
            "FB-03-A2-01-01-A8-FC", // bad checksum
            "FB-04-A2-01-01-A8-FC", // bad length
            "FD-03-A2-01-01-A7-FC", // status start byte
            "FB-07-A1-02-01-1E-02-CB-FC", // truncated data
        )) {
            assertFailsWith<MalformedFrameException>(s) { Command.decode(hexToBytes(s)) }
        }
    }

    // Frames below were captured from a physical treadmill; see the
    // measurement log in docs/protocol.md and docs/measurements.
    @Test
    fun parseStatusCapturedFrames() {
        val cases = mapOf(
            "FD-05-A1-00-00-A6-FE" to Status(State.IDLE, compact = true),
            "FD-05-A1-01-03-AA-FE" to Status(State.STARTING, compact = true),
            "FD-10-A1-01-0A-05-00-00-00-00-00-00-00-00-00-00-C1-FE" to
                Status(State.STARTING, targetSpeed = Speed(10), currentSpeed = Speed(5)),
            "FD-10-A1-02-14-0D-00-00-00-00-16-00-00-00-04-00-EE-FE" to
                Status(State.RUNNING, targetSpeed = Speed(20), currentSpeed = Speed(13), elapsed = 22.seconds, calories = 0.4),
            "FD-10-A1-02-1E-1E-02-02-00-01-00-00-03-00-17-00-0E-FE" to
                Status(
                    State.RUNNING, targetSpeed = Speed(30), currentSpeed = Speed(30), incline = Incline(2),
                    elapsed = 1.minutes, distance = 30, calories = 2.3,
                ),
            "FD-10-A1-02-1E-1E-03-03-00-02-00-00-06-00-2C-55-7E-FE" to
                Status(
                    State.RUNNING, targetSpeed = Speed(30), currentSpeed = Speed(30), incline = Incline(3),
                    elapsed = 2.minutes, distance = 60, calories = 4.4, heartRate = 85,
                ),
            "FD-10-A1-04-1A-1A-00-00-00-02-12-00-07-00-37-00-3B-FE" to
                Status(
                    State.STOPPING, targetSpeed = Speed(26), currentSpeed = Speed(26),
                    elapsed = 2.minutes + 18.seconds, distance = 70, calories = 5.5,
                ),
            "FD-10-A1-05-00-00-00-00-00-01-2D-00-07-00-35-00-20-FE" to
                Status(State.STOPPED, elapsed = 105.seconds, distance = 70, calories = 5.3),
            // program P02 picked on the console
            "FD-10-A1-00-14-00-02-02-00-1E-00-00-00-00-00-00-E7-FE" to
                Status(State.IDLE, targetSpeed = Speed(20), incline = Incline(2), elapsed = 30.minutes),
        )
        for ((hex, want) in cases) {
            val frame = hexToBytes(hex)
            val got = Status.parse(frame)
            assertContentEquals(frame.toList(), got.raw, hex)
            assertEquals(want, got.copy(raw = emptyList()), hex)
        }
    }

    @Test
    fun parseStatusCopiesInput() {
        val frame = hexToBytes("FD-05-A1-00-00-A6-FE")
        val st = Status.parse(frame)
        frame[3] = 0xFF.toByte()
        assertEquals(0.toByte(), st.raw[3])
    }

    @Test
    fun parseStatusRejectsMalformed() {
        for (s in listOf(
            "",
            "FD-05",
            "FD-05-A1-00-00-A7-FE", // bad checksum
            "FD-06-A1-00-00-A6-FE", // bad length byte
            "FB-05-A1-00-00-A6-FE", // command start byte
            "FD-05-A1-00-00-A6-FC", // command end byte
            "FD-05-A2-00-00-A7-FE", // unknown code
            "FD-06-A1-00-00-00-A7-FE", // unsupported length
            "FD-10-A1-02-14-0D-00-00-00-00-16-00-00-00-04-00-EF-FE", // full frame, bad checksum
        )) {
            assertFailsWith<MalformedFrameException>(s) { Status.parse(hexToBytes(s)) }
        }
    }

    @Test
    fun stateString() {
        assertEquals("running", State.RUNNING.toString())
        assertEquals("state(0x42)", State(0x42).toString())
    }

    /** Every notification in the session log must decode: framing, length and checksum all hold. */
    @Test
    fun everyFrameInTheMeasurementLogParses() {
        val log = MeasurementLog.lines()
        val frames = log.mapNotNull { Regex("""^\S+ RX\s+([0-9A-F-]+)""").find(it)?.groupValues?.get(1) }
        assertEquals(3280, frames.size, "notifications in docs/measurements/2026-10-31.log")
        for (hex in frames) {
            // FD-04-A1-06-AB-FE arrives about every 10 minutes while idle. Its
            // checksum is valid but docs/protocol.md does not describe it yet,
            // so it is rejected as an unsupported length, like in Go.
            if (hex == UNDOCUMENTED_IDLE_FRAME) {
                val e = assertFailsWith<MalformedFrameException> { Status.parse(hexToBytes(hex)) }
                assertTrue("unsupported status length 6" in e.message!!)
                continue
            }
            Status.parse(hexToBytes(hex))
        }
    }

    /** Every command written during the sessions must decode. */
    @Test
    fun everyCommandInTheMeasurementLogDecodes() {
        val sent = MeasurementLog.lines().mapNotNull { Regex("""^\S+ TX\s+([0-9A-F-]+)""").find(it)?.groupValues?.get(1) }
        check(sent.isNotEmpty())
        for (hex in sent) Command.decode(hexToBytes(hex))
    }
}

private const val UNDOCUMENTED_IDLE_FRAME = "FD-04-A1-06-AB-FE"

/** The raw session log in docs/measurements, shared with the Go tests' evidence. */
internal object MeasurementLog {
    fun lines(): List<String> {
        val path = System.getProperty("treadmill.measurementLog") ?: error("treadmill.measurementLog is not set")
        return java.io.File(path).readLines()
    }
}
