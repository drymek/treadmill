package com.craftersy.treadmill.workout.format

/**
 * Writes workouts in the style of the examples in docs/workout.md: block
 * mappings, one key per line, values quoted only where YAML needs it.
 */
object WorkoutWriter {
    fun write(workout: Workout): String = buildString {
        appendLine("version: ${workout.version}")
        appendLine("name: ${scalar(workout.name)}")
        workout.description?.takeIf { it.isNotEmpty() }?.let { appendLine("description: ${scalar(it)}") }
        appendLine("steps:")
        for (entry in workout.entries) {
            when (entry) {
                is Entry.Single -> step(entry.step, "  ")
                is Entry.Repeat -> {
                    appendLine("  - repeat: ${entry.times}")
                    appendLine("    steps:")
                    entry.steps.forEach { step(it, "      ") }
                }
            }
        }
    }

    private fun StringBuilder.step(step: Step, indent: String) {
        val lines = buildList {
            step.name?.takeIf { it.isNotEmpty() }?.let { add("name: ${scalar(it)}") }
            add(
                when (val l = step.length) {
                    is Length.Time -> "duration: ${formatDuration(l.seconds)}"
                    is Length.Distance -> "distance: ${l.metres}m"
                },
            )
            add(
                when (val t = step.target) {
                    is Target.Pace -> "pace: ${formatPace(t.secondsPerKm)}/km"
                    is Target.Velocity -> "speed: ${t.tenths / 10}${if (t.tenths % 10 != 0) ".${t.tenths % 10}" else ""} km/h"
                },
            )
            step.incline?.let { add("incline: $it") }
            step.note?.takeIf { it.isNotEmpty() }?.let { add("note: ${scalar(it)}") }
        }
        lines.forEachIndexed { i, line -> appendLine(indent + (if (i == 0) "- " else "  ") + line) }
    }

    /** Formats seconds as "1h5m30s", leaving out zero parts. */
    fun formatDuration(seconds: Int): String = buildString {
        val h = seconds / 3600
        val m = seconds / 60 % 60
        val s = seconds % 60
        if (h > 0) append("${h}h")
        if (m > 0) append("${m}m")
        if (s > 0 || isEmpty()) append("${s}s")
    }

    private val PLAIN = Regex("""^[\p{L}\p{N}][\p{L}\p{N} ,.'()/!?+&-]*$""")
    private val RESERVED = Regex("""^(?i:true|false|yes|no|on|off|null|~|y|n)$|^[-+]?[0-9.]+([eE][-+]?[0-9]+)?$|^0x|^0o""")

    /** Writes [s] as a plain scalar when that reads back as the same string, otherwise double-quoted. */
    fun scalar(s: String): String {
        if (PLAIN.matches(s) && !RESERVED.containsMatchIn(s) && !s.endsWith(' ')) return s
        val escaped = buildString {
            for (c in s) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\x%02x".format(c.code)) else append(c)
                }
            }
        }
        return "\"$escaped\""
    }
}
