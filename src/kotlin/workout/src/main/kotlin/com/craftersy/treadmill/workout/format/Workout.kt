/**
 * The workout file format (.workout.yaml) specified in docs/workout.md: the
 * model, conversions between pace and the treadmill's 0.1 km/h speed steps,
 * and validation of values that the editor can produce.
 *
 * [WorkoutParser] reads files and [WorkoutWriter] writes them.
 */
package com.craftersy.treadmill.workout.format

import com.craftersy.treadmill.protocol.Incline
import com.craftersy.treadmill.protocol.Speed

/** The format version this app reads and writes. */
const val FORMAT_VERSION = 1

/** File name suffix of workout files. */
const val EXTENSION = ".workout.yaml"

/** How long a step lasts. */
sealed interface Length {
    /** A duration on the app's clock, from the moment the step's command is sent. */
    data class Time(val seconds: Int) : Length

    /** A distance on the treadmill's counter, a multiple of 10 m. */
    data class Distance(val metres: Int) : Length
}

/** What the treadmill is told to run at. */
sealed interface Target {
    /** The speed sent to the treadmill. */
    val speed: Speed

    /** A pace per kilometre, converted to the nearest 0.1 km/h. */
    data class Pace(val secondsPerKm: Int) : Target {
        override val speed: Speed get() = Speed(paceToTenths(secondsPerKm))
    }

    /** A speed in tenths of km/h. */
    data class Velocity(val tenths: Int) : Target {
        override val speed: Speed get() = Speed(tenths)
    }
}

/** One step of a workout. */
data class Step(
    val name: String? = null,
    val length: Length = Length.Time(60),
    val target: Target = Target.Pace(390),
    /** The incline level, or null to keep the level of the step executed before. */
    val incline: Int? = null,
    val note: String? = null,
)

/** A top-level entry of a workout: a step or a block of steps run several times. */
sealed interface Entry {
    data class Single(val step: Step) : Entry

    data class Repeat(val times: Int, val steps: List<Step>) : Entry
}

data class Workout(
    val name: String,
    val description: String? = null,
    val entries: List<Entry>,
    val version: Int = FORMAT_VERSION,
)

/** A step as it runs: its position, resolved name and incline, and the round of its repeat block. */
data class PlannedStep(
    val step: Step,
    /** The step name, or "Step <n>" with n counting steps in file order. */
    val name: String,
    /** n in "Step <n>"; the app shows unnamed steps in its own language. */
    val number: Int,
    /** False when the file gives no name and [name] is the generated "Step <n>". */
    val named: Boolean,
    val speed: Speed,
    val incline: Incline,
    /** 1-based round within its repeat block and the number of rounds; 1 and 1 for a plain step. */
    val round: Int = 1,
    val rounds: Int = 1,
    /** Index of the top-level entry the step belongs to. */
    val entry: Int = 0,
)

/**
 * Returns the steps in execution order, with repeat blocks unrolled and every
 * incline resolved: a step without an incline keeps the level of the step
 * executed before it, and the first one starts at 0.
 */
fun Workout.expand(): List<PlannedStep> {
    val out = mutableListOf<PlannedStep>()
    var incline = 0
    var counter = 0
    entries.forEachIndexed { index, entry ->
        val (times, steps) = when (entry) {
            is Entry.Single -> 1 to listOf(entry.step)
            is Entry.Repeat -> entry.times to entry.steps
        }
        val numbers = steps.map { ++counter }
        for (round in 1..times) {
            steps.forEachIndexed { i, step ->
                step.incline?.let { incline = it }
                val name = step.name?.takeIf(String::isNotEmpty)
                out += PlannedStep(step, name ?: "Step ${numbers[i]}", numbers[i], name != null, step.target.speed, Incline(incline), round, times, index)
            }
        }
    }
    return out
}

/** Estimated time and distance of a step: distance steps take distance / speed, time steps cover speed × time. */
fun PlannedStep.estimate(): Estimate {
    val metresPerSecond = speed.kmh / 3.6
    return when (val l = step.length) {
        is Length.Time -> Estimate(l.seconds.toDouble(), metresPerSecond * l.seconds)
        is Length.Distance -> Estimate(if (metresPerSecond > 0) l.metres / metresPerSecond else 0.0, l.metres.toDouble())
    }
}

data class Estimate(val seconds: Double, val metres: Double) {
    operator fun plus(other: Estimate): Estimate = Estimate(seconds + other.seconds, metres + other.metres)
}

/** The estimated total time and distance of the whole workout. */
fun Workout.estimate(): Estimate = expand().fold(Estimate(0.0, 0.0)) { sum, step -> sum + step.estimate() }

/** Converts a pace in seconds per kilometre to tenths of km/h, rounding halves up. */
fun paceToTenths(secondsPerKm: Int): Int = (36000 * 2 + secondsPerKm) / (2 * secondsPerKm)

/** The pace per kilometre that [speed] results in, rounded to the nearest second. */
fun paceOf(speed: Speed): Int = if (speed.tenths <= 0) 0 else (36000 * 2 + speed.tenths) / (2 * speed.tenths)

/** Fastest and slowest pace the treadmill can run, in seconds per kilometre. */
val PACE_RANGE: IntRange = 164..3600

/** A problem [Workout.validate] finds, with its values, so the app can word it in its own language. */
sealed interface Issue {
    data object NameMissing : Issue
    data object NoSteps : Issue
    data class RepeatTooFew(val times: Int) : Issue
    data object RepeatEmpty : Issue
    data object DurationZero : Issue
    data object DistanceZero : Issue
    data object DistanceNot10 : Issue
    data class PaceRange(val secondsPerKm: Int, val speed: Speed) : Issue
    data class SpeedRange(val speed: Speed) : Issue
    data class InclineRange(val level: Int) : Issue
}

/** One problem in a workout. */
data class Problem(
    /** 1-based line in the file; 0 when it is not known, e.g. for a workout built in the editor. */
    val line: Int,
    /** Locates the step, e.g. "steps[1].steps[0] (Fast)"; empty for document-level problems. */
    val path: String,
    /** The problem in English, worded like the Go validator. */
    val message: String,
    /** Set by [Workout.validate]; the parser's messages exist in English only. */
    val issue: Issue? = null,
) {
    override fun toString(): String = buildString {
        if (line > 0) append("line $line: ")
        if (path.isNotEmpty()) append("$path: ")
        append(message)
    }
}

/** Checks the values of a workout against the ranges in docs/workout.md, section 5. */
fun Workout.validate(): List<Problem> {
    val problems = mutableListOf<Problem>()
    if (name.isBlank()) problems += Problem(0, "", "the workout needs a name", Issue.NameMissing)
    if (entries.isEmpty()) problems += Problem(0, "", "add at least one step", Issue.NoSteps)
    entries.forEachIndexed { i, entry ->
        when (entry) {
            is Entry.Single -> entry.step.validate("steps[$i]", problems)
            is Entry.Repeat -> {
                if (entry.times < 2) problems += Problem(0, "steps[$i]", "repeat must be at least 2, got ${entry.times}", Issue.RepeatTooFew(entry.times))
                if (entry.steps.isEmpty()) problems += Problem(0, "steps[$i]", "repeat block has no steps", Issue.RepeatEmpty)
                entry.steps.forEachIndexed { j, s -> s.validate("steps[$i].steps[$j]", problems) }
            }
        }
    }
    return problems
}

private fun Step.validate(path: String, problems: MutableList<Problem>) {
    val at = if (name.isNullOrEmpty()) path else "$path ($name)"
    when (val l = length) {
        is Length.Time -> if (l.seconds <= 0) problems += Problem(0, at, "duration must be longer than zero", Issue.DurationZero)
        is Length.Distance -> when {
            l.metres <= 0 -> problems += Problem(0, at, "distance must be longer than zero", Issue.DistanceZero)
            l.metres % 10 != 0 -> problems += Problem(0, at, "distance must be a multiple of 10 m", Issue.DistanceNot10)
        }
    }
    when (val t = target) {
        is Target.Pace -> if (t.secondsPerKm <= 0 || t.speed !in Speed.MIN..Speed.MAX) {
            problems += Problem(0, at, "pace ${formatPace(t.secondsPerKm)}/km is ${t.speed}, outside the treadmill range ${Speed.MIN}–${Speed.MAX}", Issue.PaceRange(t.secondsPerKm, t.speed))
        }
        is Target.Velocity -> if (t.speed !in Speed.MIN..Speed.MAX) {
            problems += Problem(0, at, "speed ${t.speed} is outside the treadmill range ${Speed.MIN}–${Speed.MAX}", Issue.SpeedRange(t.speed))
        }
    }
    incline?.let {
        if (it !in Incline.MIN.level..Incline.MAX.level) problems += Problem(0, at, "incline $it is outside ${Incline.MIN}–${Incline.MAX}", Issue.InclineRange(it))
    }
}

/** Formats seconds per kilometre as "6:30". */
fun formatPace(secondsPerKm: Int): String = "%d:%02d".format(secondsPerKm / 60, secondsPerKm % 60)

/** Formats seconds as "m:ss", or "h:mm:ss" from one hour. */
fun formatClock(seconds: Int): String =
    if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)

/** Formats metres as "400 m" or "2.5 km". */
fun formatDistance(metres: Int): String =
    if (metres < 1000) "$metres m"
    else "%s km".format(("%.2f".format(java.util.Locale.ROOT, metres / 1000.0)).trimEnd('0').trimEnd('.'))
