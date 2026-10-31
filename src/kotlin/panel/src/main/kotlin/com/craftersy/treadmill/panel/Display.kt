package com.craftersy.treadmill.panel

import android.view.KeyEvent
import com.craftersy.treadmill.protocol.Status

/** The six readouts of the console, formatted like its LED digits. */
data class Readouts(
    val incline: String = "0",
    val speed: String = "0.0",
    /** Actual belt speed while it ramps towards the target, e.g. "belt 2.4 km/h"; empty otherwise. */
    val belt: String = "",
    val pulse: String = "--",
    val elapsed: String = "0:00",
    val distance: String = "0.00",
    val calories: String = "0.0",
)

/** Formats a status for the display. Compact frames carry no metrics, so they show zeros. */
fun readouts(status: Status?): Readouts {
    if (status == null || status.compact) return Readouts()
    val seconds = status.elapsed.inWholeSeconds
    return Readouts(
        incline = status.incline.level.toString(),
        speed = "${status.targetSpeed.tenths / 10}.${status.targetSpeed.tenths % 10}",
        belt = if (status.currentSpeed != status.targetSpeed) "belt ${status.currentSpeed}" else "",
        pulse = if (status.heartRate > 0) status.heartRate.toString() else "--",
        elapsed = "%d:%02d".format(seconds / 60, seconds % 60),
        distance = "%d.%02d".format(status.distance / 1000, status.distance % 1000 / 10),
        calories = "%.1f".format(java.util.Locale.ROOT, status.calories),
    )
}

/** Speed quick keys in tenths of km/h, as on the console. */
val SPEED_PRESETS = listOf(30, 60, 90, 120)

/** Incline quick keys. */
val INCLINE_PRESETS = listOf(3, 6, 9, 12)

/** What a hardware key press asks for. */
sealed interface KeyCommand {
    data class Press(val action: Action, val value: Int = 0) : KeyCommand
    data object Quit : KeyCommand
}

/**
 * Maps a hardware keyboard key to the same command as in the terminal panel:
 * START and STOP, +/− for speed (p and m work without Shift), arrows for
 * incline, and the four quick keys of each row.
 */
fun keyCommand(keyCode: Int, unicodeChar: Int): KeyCommand? {
    when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> return KeyCommand.Press(Action.START)
        KeyEvent.KEYCODE_SPACE -> return KeyCommand.Press(Action.STOP)
        KeyEvent.KEYCODE_DPAD_RIGHT -> return KeyCommand.Press(Action.SPEED_UP)
        KeyEvent.KEYCODE_DPAD_LEFT -> return KeyCommand.Press(Action.SPEED_DOWN)
        KeyEvent.KEYCODE_DPAD_UP -> return KeyCommand.Press(Action.INCLINE_UP)
        KeyEvent.KEYCODE_DPAD_DOWN -> return KeyCommand.Press(Action.INCLINE_DOWN)
        KeyEvent.KEYCODE_ESCAPE -> return KeyCommand.Quit
    }
    return when (unicodeChar.toChar()) {
        'x' -> KeyCommand.Press(Action.STOP)
        '+', '=', 'p' -> KeyCommand.Press(Action.SPEED_UP)
        '-', '_', 'm' -> KeyCommand.Press(Action.SPEED_DOWN)
        'k' -> KeyCommand.Press(Action.INCLINE_UP)
        'j' -> KeyCommand.Press(Action.INCLINE_DOWN)
        '3' -> KeyCommand.Press(Action.SPEED_PRESET, 30)
        '6' -> KeyCommand.Press(Action.SPEED_PRESET, 60)
        '9' -> KeyCommand.Press(Action.SPEED_PRESET, 90)
        '0' -> KeyCommand.Press(Action.SPEED_PRESET, 120)
        'a' -> KeyCommand.Press(Action.INCLINE_PRESET, 3)
        's' -> KeyCommand.Press(Action.INCLINE_PRESET, 6)
        'd' -> KeyCommand.Press(Action.INCLINE_PRESET, 9)
        'f' -> KeyCommand.Press(Action.INCLINE_PRESET, 12)
        '?', 'h' -> KeyCommand.Press(Action.HELP)
        'q' -> KeyCommand.Quit
        else -> null
    }
}

// Seven-segment bits: a (top), b (top right), c (bottom right), d (bottom),
// e (bottom left), f (top left), g (middle).
const val SEG_A = 1
const val SEG_B = 2
const val SEG_C = 4
const val SEG_D = 8
const val SEG_E = 16
const val SEG_F = 32
const val SEG_G = 64

/** The lit segments of a character, like the red LED digits on the console; unknown characters are blank. */
fun segmentsOf(c: Char): Int = when (c) {
    '0' -> SEG_A or SEG_B or SEG_C or SEG_D or SEG_E or SEG_F
    '1' -> SEG_B or SEG_C
    '2' -> SEG_A or SEG_B or SEG_G or SEG_E or SEG_D
    '3' -> SEG_A or SEG_B or SEG_G or SEG_C or SEG_D
    '4' -> SEG_F or SEG_G or SEG_B or SEG_C
    '5' -> SEG_A or SEG_F or SEG_G or SEG_C or SEG_D
    '6' -> SEG_A or SEG_F or SEG_G or SEG_E or SEG_D or SEG_C
    '7' -> SEG_A or SEG_B or SEG_C
    '8' -> SEG_A or SEG_B or SEG_C or SEG_D or SEG_E or SEG_F or SEG_G
    '9' -> SEG_A or SEG_B or SEG_C or SEG_D or SEG_F or SEG_G
    '-' -> SEG_G
    else -> 0
}
