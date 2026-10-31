package com.craftersy.treadmill.workout.format

import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.composer.Composer
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.NodeTuple
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode
import org.snakeyaml.engine.v2.nodes.Tag
import org.snakeyaml.engine.v2.parser.ParserImpl
import org.snakeyaml.engine.v2.scanner.StreamReader
import org.snakeyaml.engine.v2.schema.CoreSchema

/** The outcome of [WorkoutParser.parse]: a workout, or every problem found in the file. */
sealed interface ParseResult {
    data class Success(val workout: Workout) : ParseResult

    data class Failure(val problems: List<Problem>) : ParseResult
}

/**
 * Parses and validates workout files, reporting every problem at once with
 * its line and step position, like the Go validator in src/go/tools/workout.
 */
object WorkoutParser {
    /** Files larger than this are refused before parsing. */
    const val MAX_SIZE: Int = 256 * 1024

    fun parse(text: String): ParseResult {
        if (text.length > MAX_SIZE) return ParseResult.Failure(listOf(Problem(0, "", "file is larger than ${MAX_SIZE / 1024} KB")))
        val root = try {
            val settings = LoadSettings.builder().setSchema(CoreSchema()).setAllowDuplicateKeys(true).build()
            val composer = Composer(settings, ParserImpl(settings, StreamReader(settings, text)))
            if (composer.hasNext()) composer.next() else null
        } catch (e: MarkedYamlEngineException) {
            val line = e.problemMark.map { it.line + 1 }.orElse(0)
            return ParseResult.Failure(listOf(Problem(line, "", "YAML: ${e.problem ?: e.message}")))
        } catch (e: YamlEngineException) {
            return ParseResult.Failure(listOf(Problem(0, "", "YAML: ${e.message}")))
        }
        val p = Parser()
        val workout = p.document(root)
        return if (p.problems.isEmpty() && workout != null) {
            ParseResult.Success(workout)
        } else {
            ParseResult.Failure(p.problems.sortedBy { it.line })
        }
    }
}

private val DURATION = Regex("""^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$""")
private val METRES = Regex("""^(\d+)m$""")
private val KILOMETRES = Regex("""^(\d+)(?:\.(\d{1,2}))?km$""")
private val PACE = Regex("""^([1-9]\d*):([0-5]\d)/km$""")
private val SPEED = Regex("""^(\d+)(?:\.(\d))? ?km/h$""")

private val Node.line: Int get() = startMark.map { it.line + 1 }.orElse(0)

private class Parser {
    val problems = mutableListOf<Problem>()
    private var steps = 0

    fun fail(node: Node, path: String, message: String) {
        problems += Problem(node.line, path, message)
    }

    /** The keys of mapping [node], reporting unknown and duplicate ones. */
    fun fields(node: MappingNode, path: String, vararg allowed: String): Map<String, NodeTuple> {
        val out = linkedMapOf<String, NodeTuple>()
        for (tuple in node.value) {
            val key = (tuple.keyNode as? ScalarNode)?.value
            when {
                key == null || key !in allowed -> fail(tuple.keyNode, path, "unknown key \"${key ?: "?"}\"")
                key in out -> fail(tuple.keyNode, path, "duplicate key \"$key\"")
                else -> out[key] = tuple
            }
        }
        return out
    }

    fun document(root: Node?): Workout? {
        if (root == null) {
            problems += Problem(0, "", "file is empty")
            return null
        }
        if (root !is MappingNode) {
            fail(root, "", "top level must be a mapping with version, name and steps")
            return null
        }
        val f = fields(root, "", "version", "name", "description", "steps")
        var version = FORMAT_VERSION
        val v = f["version"]
        if (v == null) {
            fail(root, "", "missing version")
        } else {
            integer(v, "")?.let {
                if (it != FORMAT_VERSION) fail(v.valueNode, "", "unsupported version $it (this reader supports $FORMAT_VERSION)")
                version = it
            }
        }
        val name = f["name"]?.let { text(it, "") }
        if (f["name"] == null) fail(root, "", "missing name")
        val description = f["description"]?.let { text(it, "") }
        val entries = mutableListOf<Entry>()
        val s = f["steps"]
        if (s == null) {
            fail(root, "", "missing steps")
        } else {
            list(s, "")?.forEachIndexed { i, item -> entry(item, "steps[$i]")?.let(entries::add) }
        }
        return Workout(name ?: "", description, entries, version)
    }

    fun entry(node: Node, path: String): Entry? {
        if (node !is MappingNode) {
            fail(node, path, "must be a step or a repeat block")
            return null
        }
        return if (node.hasKey("repeat")) repeat(node, path) else step(node, path)?.let { Entry.Single(it) }
    }

    fun repeat(node: MappingNode, path: String): Entry? {
        val f = fields(node, path, "repeat", "steps")
        var times = 0
        f["repeat"]?.let { r ->
            integer(r, path)?.let {
                if (it < 2) fail(r.valueNode, path, "repeat must be at least 2, got $it")
                times = it
            }
        }
        val s = f["steps"]
        if (s == null) {
            fail(node, path, "repeat block has no steps")
            return null
        }
        val items = list(s, path) ?: return null
        val steps = mutableListOf<Step>()
        items.forEachIndexed { i, item ->
            val itemPath = "$path.steps[$i]"
            when {
                item !is MappingNode -> fail(item, itemPath, "must be a step")
                item.hasKey("repeat") -> fail(item, itemPath, "a repeat block cannot contain another repeat block")
                else -> step(item, itemPath)?.let(steps::add)
            }
        }
        return Entry.Repeat(times, steps)
    }

    fun step(node: MappingNode, at: String): Step? {
        steps++
        var path = at
        val nameNode = node.value.firstOrNull { (it.keyNode as? ScalarNode)?.value == "name" }?.valueNode as? ScalarNode
        if (nameNode != null && nameNode.tag != Tag.NULL && nameNode.value.isNotEmpty()) path += " (${nameNode.value})"
        val before = problems.size
        val f = fields(node, path, "name", "duration", "distance", "pace", "speed", "incline", "note")

        val name = f["name"]?.let { text(it, path) }
        val note = f["note"]?.let { text(it, path) }

        val duration = f["duration"]
        val distance = f["distance"]
        var length: Length? = null
        when {
            duration != null && distance != null -> fail(distance.keyNode, path, "a step has either duration or distance, not both")
            duration != null -> text(duration, path)?.let { t ->
                parseDuration(t).fold({ length = Length.Time(it) }, { fail(duration.valueNode, path, "duration: ${it.message}") })
            }
            distance != null -> text(distance, path)?.let { t ->
                parseDistance(t).fold({ length = Length.Distance(it) }, { fail(distance.valueNode, path, "distance: ${it.message}") })
            }
            else -> fail(node, path, "missing duration or distance")
        }

        val pace = f["pace"]
        val speed = f["speed"]
        var target: Target? = null
        when {
            pace != null && speed != null -> fail(speed.keyNode, path, "a step has either pace or speed, not both")
            pace != null -> text(pace, path)?.let { t ->
                parsePace(t).fold({ target = Target.Pace(it) }, { fail(pace.valueNode, path, "pace: ${it.message}") })
            }
            speed != null -> text(speed, path)?.let { t ->
                parseSpeed(t).fold({ target = Target.Velocity(it) }, { fail(speed.valueNode, path, "speed: ${it.message}") })
            }
            else -> fail(node, path, "missing pace or speed")
        }

        var incline: Int? = null
        f["incline"]?.let { tuple ->
            integer(tuple, path)?.let {
                if (it !in 0..22) fail(tuple.valueNode, path, "incline $it is outside 0–22") else incline = it
            }
        }
        val l = length
        val t = target
        if (problems.size != before || l == null || t == null) return null
        return Step(name, l, t, incline, note)
    }

    fun text(tuple: NodeTuple, path: String): String? {
        val v = tuple.valueNode
        if (v !is ScalarNode || v.tag == Tag.NULL) {
            fail(v, path, "${tuple.key} must be a text value")
            return null
        }
        return v.value
    }

    fun integer(tuple: NodeTuple, path: String): Int? {
        val v = tuple.valueNode
        val n = if (v is ScalarNode && v.tag == Tag.INT) v.value.toIntOrNull() else null
        if (n == null) fail(v, path, "${tuple.key} must be a whole number")
        return n
    }

    fun list(tuple: NodeTuple, path: String): List<Node>? {
        val v = tuple.valueNode
        if (v !is SequenceNode) {
            fail(v, path, "${tuple.key} must be a list")
            return null
        }
        if (v.value.isEmpty()) {
            fail(v, path, "${tuple.key} must not be empty")
            return null
        }
        return v.value
    }

    private val NodeTuple.key: String get() = (keyNode as? ScalarNode)?.value ?: "?"

    private fun MappingNode.hasKey(key: String) = value.any { (it.keyNode as? ScalarNode)?.value == key }
}

private fun problem(message: String): Result<Nothing> = Result.failure(IllegalArgumentException(message))

private val outOfRange = "outside the treadmill range 1.0 km/h–22.0 km/h"

/** Parses "1h5m", "3m", "90s", "1m30s" into seconds. */
fun parseDuration(s: String): Result<Int> {
    val m = DURATION.matchEntire(s)
    if (m == null || s.isEmpty()) return problem("\"$s\" is not like 90s, 3m or 1m30s")
    var total = 0L
    listOf(3600L, 60L, 1L).forEachIndexed { i, unit ->
        val g = m.groupValues[i + 1]
        if (g.isNotEmpty()) total += (g.toLongOrNull() ?: return problem("\"$s\" is too long")) * unit
    }
    if (total <= 0) return problem("\"$s\" must be longer than zero")
    if (total > Int.MAX_VALUE) return problem("\"$s\" is too long")
    return Result.success(total.toInt())
}

/** Parses "400m" or "2.5km" into metres. */
fun parseDistance(s: String): Result<Int> {
    val metres = METRES.matchEntire(s)?.let { it.groupValues[1].toIntOrNull() ?: return problem("\"$s\" is too long") }
        ?: KILOMETRES.matchEntire(s)?.let {
            val km = it.groupValues[1].toIntOrNull()?.takeIf { k -> k < 1_000_000 } ?: return problem("\"$s\" is too long")
            km * 1000 + it.groupValues[2].padEnd(3, '0').toInt()
        }
        ?: return problem("\"$s\" is not like 400m or 2.5km")
    if (metres <= 0) return problem("\"$s\" must be longer than zero")
    if (metres % 10 != 0) return problem("\"$s\" is not a multiple of 10 m, the treadmill's distance unit")
    return Result.success(metres)
}

/** Parses "6:30/km" into seconds per kilometre. */
fun parsePace(s: String): Result<Int> {
    val m = PACE.matchEntire(s) ?: return problem("\"$s\" is not like 6:30/km")
    val minutes = m.groupValues[1].toIntOrNull()?.takeIf { it <= 999 } ?: return problem("\"$s\" is too slow")
    val total = minutes * 60 + m.groupValues[2].toInt()
    val tenths = paceToTenths(total)
    if (tenths !in 10..220) return problem("$s is ${tenths / 10}.${tenths % 10} km/h, $outOfRange")
    return Result.success(total)
}

/** Parses "5 km/h" or "10.5km/h" into tenths of km/h. */
fun parseSpeed(s: String): Result<Int> {
    val m = SPEED.matchEntire(s) ?: return problem("\"$s\" is not like 10.5 km/h")
    val whole = m.groupValues[1].toIntOrNull()?.takeIf { it <= 999 } ?: return problem("\"$s\" is $outOfRange")
    val tenths = whole * 10 + (m.groupValues[2].firstOrNull()?.digitToInt() ?: 0)
    if (tenths !in 10..220) return problem("\"$s\" is $outOfRange")
    return Result.success(tenths)
}
