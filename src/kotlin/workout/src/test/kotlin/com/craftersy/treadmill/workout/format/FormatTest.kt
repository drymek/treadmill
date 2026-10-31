package com.craftersy.treadmill.workout.format

import com.craftersy.treadmill.protocol.Incline
import com.craftersy.treadmill.protocol.Speed
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class FormatTest {
    /** The first YAML example in docs/workout.md. */
    private val specExample: String by lazy {
        val spec = File(System.getProperty("workout.spec") ?: "../../../docs/workout.md").readText()
        spec.substringAfter("```yaml\n").substringBefore("```")
    }

    private fun parse(text: String): Workout = when (val r = WorkoutParser.parse(text)) {
        is ParseResult.Success -> r.workout
        is ParseResult.Failure -> fail(r.problems.joinToString("\n"))
    }

    private fun problems(text: String): List<String> {
        val r = WorkoutParser.parse(text)
        assertIs<ParseResult.Failure>(r, "no error")
        return r.problems.map { it.toString() }
    }

    @Test
    fun parsesTheSpecificationExample() {
        val w = parse(specExample)
        assertEquals("3-minute intervals", w.name)
        assertEquals(4, w.entries.size)
        val repeat = assertIs<Entry.Repeat>(w.entries[1])
        assertEquals(5, repeat.times)

        val steps = w.expand()
        assertEquals(13, steps.size)
        data class Row(val name: String, val length: Length, val speed: Speed, val incline: Incline)
        val want = buildList {
            add(Row("Warm-up", Length.Time(600), Speed(92), Incline(1)))
            repeat(5) {
                add(Row("Fast", Length.Time(180), Speed(133), Incline(2)))
                add(Row("Jog", Length.Distance(400), Speed(86), Incline(2)))
            }
            add(Row("Walk", Length.Time(120), Speed(50), Incline(2)))
            add(Row("Cool-down", Length.Time(600), Speed(92), Incline(0)))
        }
        assertEquals(want, steps.map { Row(it.name, it.step.length, it.speed, it.incline) })
        assertEquals("Keep your cadence up", steps[1].step.note)
        assertEquals(3 to 5, steps[5].round to steps[5].rounds)
    }

    @Test
    fun paceRounding() {
        // The table in docs/workout.md, section 4.3.
        for ((pace, tenths, actual) in listOf(
            Triple("6:30/km", 92, 391), Triple("4:30/km", 133, 271), Triple("5:00/km", 120, 300),
            Triple("2:44/km", 220, 164), Triple("60:00/km", 10, 3600),
        )) {
            val seconds = parsePace(pace).getOrThrow()
            assertEquals(tenths, paceToTenths(seconds), pace)
            assertEquals(actual, paceOf(Speed(tenths)), pace)
        }
        for (pace in listOf("2:43/km", "70:00/km", "6:30", "6:60/km", "06:30/km", "6:3/km")) {
            assertTrue(parsePace(pace).isFailure, pace)
        }
    }

    @Test
    fun valueFormats() {
        mapOf("90s" to 90, "3m" to 180, "1m30s" to 90, "1h5m" to 3900).forEach { (s, want) -> assertEquals(want, parseDuration(s).getOrThrow(), s) }
        listOf("", "0s", "3", "3 m", "30s1m", "1.5m").forEach { assertTrue(parseDuration(it).isFailure, it) }
        mapOf("400m" to 400, "1km" to 1000, "2.5km" to 2500, "1.25km" to 1250).forEach { (s, want) -> assertEquals(want, parseDistance(s).getOrThrow(), s) }
        listOf("0m", "405m", "1.255km", "1.005km", "400", "1 km").forEach { assertTrue(parseDistance(it).isFailure, it) }
        mapOf("5 km/h" to 50, "10.5 km/h" to 105, "22km/h" to 220, "1.0 km/h" to 10).forEach { (s, want) -> assertEquals(want, parseSpeed(s).getOrThrow(), s) }
        listOf("0.9 km/h", "22.1 km/h", "10.55 km/h", "10", "10 kmh").forEach { assertTrue(parseSpeed(it).isFailure, it) }
    }

    @Test
    fun inclineCarriesAcrossRounds() {
        val w = parse(
            """
            version: 1
            name: Hills
            steps:
              - { duration: 1m, speed: 5 km/h }
              - repeat: 2
                steps:
                  - { duration: 1m, speed: 5 km/h }
                  - { duration: 1m, speed: 5 km/h, incline: 4 }
            """.trimIndent(),
        )
        val steps = w.expand()
        assertEquals(listOf(0, 0, 4, 4, 4), steps.map { it.incline.level })
        assertEquals(listOf("Step 1", "Step 2", "Step 3", "Step 2", "Step 3"), steps.map { it.name })
    }

    @Test
    fun reportsEveryProblemLikeTheGoValidator() {
        val got = problems(
            """
            version: 2
            name: Broken
            colour: red
            steps:
              - name: Both
                duration: 1m
                distance: 1km
                pace: 5:00/km
              - name: Neither
                incline: 23
              - repeat: 1
                steps:
                  - { duration: 1m, pace: 2:00/km }
                  - repeat: 2
                    steps: [{ duration: 1m, speed: 5 km/h }]
              - { duration: 1m, speed: 5, inclin: 2 }
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                """line 1: unsupported version 2 (this reader supports 1)""",
                """line 3: unknown key "colour"""",
                """line 7: steps[0] (Both): a step has either duration or distance, not both""",
                """line 9: steps[1] (Neither): missing duration or distance""",
                """line 9: steps[1] (Neither): missing pace or speed""",
                """line 10: steps[1] (Neither): incline 23 is outside 0–22""",
                """line 11: steps[2]: repeat must be at least 2, got 1""",
                """line 13: steps[2].steps[0]: pace: 2:00/km is 30.0 km/h, outside the treadmill range 1.0 km/h–22.0 km/h""",
                """line 14: steps[2].steps[1]: a repeat block cannot contain another repeat block""",
                """line 16: steps[3]: unknown key "inclin"""",
                """line 16: steps[3]: speed: "5" is not like 10.5 km/h""",
            ),
            got,
        )
    }

    @Test
    fun rejectsMalformedDocuments() {
        for ((name, doc) in mapOf(
            "empty" to "",
            "not mapping" to "- 1",
            "bad yaml" to "name: [",
            "no steps" to "version: 1\nname: x",
            "empty steps" to "version: 1\nname: x\nsteps: []",
            "string version" to "version: \"1\"\nname: x\nsteps: [{duration: 1m, speed: 5 km/h}]",
            "null name" to "version: 1\nname:\nsteps: [{duration: 1m, speed: 5 km/h}]",
            "scalar step" to "version: 1\nname: x\nsteps: [3m]",
            "duplicate" to "version: 1\nname: x\nname: y\nsteps: [{duration: 1m, speed: 5 km/h}]",
            "float incline" to "version: 1\nname: x\nsteps: [{duration: 1m, speed: 5 km/h, incline: 1.5}]",
            "empty repeat" to "version: 1\nname: x\nsteps: [{repeat: 2, steps: []}]",
        )) {
            assertIs<ParseResult.Failure>(WorkoutParser.parse(doc), name)
        }
    }

    @Test
    fun writerRoundTrips() {
        val original = parse(specExample)
        val text = WorkoutWriter.write(original)
        assertEquals(original, parse(text), text)

        val tricky = Workout(
            name = "yes",
            description = "Line one\nline \"two\": #hash",
            entries = listOf(
                Entry.Single(Step(name = "123", length = Length.Time(3725), target = Target.Velocity(105), incline = 0, note = "- dash")),
                Entry.Repeat(3, listOf(Step(name = "Śmigło, ćwicz!", length = Length.Distance(1250), target = Target.Pace(164)))),
            ),
        )
        val written = WorkoutWriter.write(tricky)
        assertEquals(tricky, parse(written), written)
        assertTrue("duration: 1h2m5s" in written, written)
        assertTrue("name: Śmigło, ćwicz!" in written, written)
    }

    @Test
    fun validateMatchesTheRanges() {
        val w = Workout(
            name = " ",
            entries = listOf(
                Entry.Single(Step(length = Length.Distance(405), target = Target.Velocity(221), incline = 23)),
                Entry.Repeat(1, emptyList()),
            ),
        )
        assertEquals(
            listOf(
                "the workout needs a name",
                "steps[0]: distance must be a multiple of 10 m",
                "steps[0]: speed 22.1 km/h is outside the treadmill range 1.0 km/h–22.0 km/h",
                "steps[0]: incline 23 is outside 0–22",
                "steps[1]: repeat must be at least 2, got 1",
                "steps[1]: repeat block has no steps",
            ),
            w.validate().map { it.toString() },
        )
        assertTrue(parse(specExample).validate().isEmpty())
    }

    @Test
    fun estimate() {
        val e = parse(specExample).estimate()
        assertEquals(3057, e.seconds.toInt())
        assertEquals(8.56, e.metres / 1000, 0.01)
        assertEquals("2.5 km", formatDistance(2500))
        assertEquals("1:02:05", formatClock(3725))
    }
}
