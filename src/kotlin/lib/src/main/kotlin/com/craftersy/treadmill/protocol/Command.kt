package com.craftersy.treadmill.protocol

/** A decoded command frame. Byte values are 0–255. */
public data class Command(
    val group: Int,
    val type: Int,
    val master: Int,
    val data: List<Int> = emptyList(),
) {
    /** Returns the wire form of this command. */
    public fun encode(): ByteArray = encodeCommand(group, type, master, *data.toIntArray())

    public companion object {
        /**
         * Builds a command frame from its parts. It is the low-level building
         * block behind [start], [stop] and [setSpeedIncline] and is useful for
         * protocol research.
         */
        public fun encodeCommand(group: Int, type: Int, master: Int, vararg data: Int): ByteArray {
            require(data.size <= 252) { "protocol: command data too long" }
            val frame = ByteArray(7 + data.size)
            frame[0] = COMMAND_START.toByte()
            frame[1] = (3 + data.size).toByte()
            frame[2] = group.toByte()
            frame[3] = type.toByte()
            frame[4] = master.toByte()
            data.forEachIndexed { i, b -> frame[5 + i] = b.toByte() }
            frame[frame.size - 2] = checksum(frame, 1, frame.size - 2).toByte()
            frame[frame.size - 1] = COMMAND_END.toByte()
            return frame
        }

        /** Parses a command frame, verifying framing, length and checksum. */
        public fun decode(frame: ByteArray): Command {
            val n = frame.size
            if (n < 7 || frame.u8(0) != COMMAND_START || frame.u8(n - 1) != COMMAND_END) {
                throw MalformedFrameException("bad command framing")
            }
            if (frame.u8(1) != n - 4) {
                throw MalformedFrameException("command length byte ${frame.u8(1)}, frame has $n bytes")
            }
            val want = checksum(frame, 1, n - 2)
            if (frame.u8(n - 2) != want) {
                throw MalformedFrameException("command checksum %02X, want %02X".format(frame.u8(n - 2), want))
            }
            return Command(
                group = frame.u8(2),
                type = frame.u8(3),
                master = frame.u8(4),
                data = (5 until n - 2).map { frame.u8(it) },
            )
        }

        /**
         * The command that starts the belt: a 3-second countdown, then
         * 1.0 km/h. A new start resets the elapsed time, distance and calories.
         */
        public fun start(): ByteArray =
            encodeCommand(Commands.GROUP_CONTROL, Commands.TYPE_START, Commands.MASTER_APP)

        /** The command that brakes the belt to a halt. */
        public fun stop(): ByteArray =
            encodeCommand(Commands.GROUP_CONTROL, Commands.TYPE_STOP, Commands.MASTER_APP)

        /**
         * The command that sets the target speed and incline.
         *
         * Both values always apply: there is no way to change only one of
         * them, so callers that want to keep the incline must pass the current
         * one.
         */
        public fun setSpeedIncline(speed: Speed, incline: Incline): ByteArray {
            if (speed < Speed.MIN || speed > Speed.MAX) throw OutOfRangeException("speed $speed")
            if (incline < Incline.MIN || incline > Incline.MAX) throw OutOfRangeException("incline $incline")
            return encodeCommand(
                Commands.GROUP_SETTINGS, Commands.TYPE_SET_SPEED_INCLINE, Commands.MASTER_APP,
                speed.tenths, incline.level, 0x00, 0x00,
            )
        }
    }
}
