package com.craftersy.treadmill.workout.data

import com.craftersy.treadmill.workout.format.EXTENSION
import com.craftersy.treadmill.workout.format.ParseResult
import com.craftersy.treadmill.workout.format.Problem
import com.craftersy.treadmill.workout.format.Workout
import com.craftersy.treadmill.workout.format.WorkoutParser
import com.craftersy.treadmill.workout.format.WorkoutWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.text.Normalizer

/** A workout file in the library. */
data class StoredWorkout(
    /** The file name, which identifies the workout. */
    val id: String,
    val workout: Workout,
    val text: String,
    val modified: Long,
)

/** Thrown by imports when the text is not a valid workout file. */
class InvalidWorkoutException(val problems: List<Problem>) : Exception(problems.joinToString("\n"))

/**
 * The workout library: one .workout.yaml file per workout in [dir], newest
 * first. Files that no longer parse (edited by hand, written by a newer
 * version) are left alone and not listed.
 */
class WorkoutRepository(private val dir: File) {
    private val mutableWorkouts = MutableStateFlow<List<StoredWorkout>>(emptyList())
    val workouts: StateFlow<List<StoredWorkout>> = mutableWorkouts.asStateFlow()

    private val mutableLoaded = MutableStateFlow(false)

    /** False until the first [load] finished, so an empty list is not mistaken for an empty library. */
    val loaded: StateFlow<Boolean> = mutableLoaded.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        dir.mkdirs()
        mutableWorkouts.value = scan()
        mutableLoaded.value = true
    }

    fun get(id: String): StoredWorkout? = mutableWorkouts.value.firstOrNull { it.id == id }

    /** Writes [workout] to the file [id], or to a new file, and returns its id. */
    suspend fun save(workout: Workout, id: String? = null): String = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val name = id ?: freeName(workout.name)
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(WorkoutWriter.write(workout))
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Could not save ${workout.name}.")
        }
        mutableWorkouts.value = scan()
        name
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, id).delete()
        mutableWorkouts.value = scan()
    }

    /** Parses [text] and adds it to the library as a new workout. */
    suspend fun import(text: String): StoredWorkout {
        val workout = when (val r = WorkoutParser.parse(text.trim().trimStart(BOM) + "\n")) {
            is ParseResult.Success -> r.workout
            is ParseResult.Failure -> throw InvalidWorkoutException(r.problems)
        }
        val id = save(workout)
        return get(id)!!
    }

    /** Downloads a workout file from an http(s) URL. */
    suspend fun download(url: String): String = withContext(Dispatchers.IO) {
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            throw IOException("That is not a valid URL.")
        }
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrEmpty()) throw IOException("Use an http:// or https:// URL.")
        val conn = uri.toURL().openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/yaml, text/yaml, text/plain, */*")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("The server answered $code ${conn.responseMessage.orEmpty()}.".trim())
            conn.inputStream.use { input ->
                readLimited(input)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun scan(): List<StoredWorkout> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) } ?: emptyArray())
            .mapNotNull { f ->
                val text = runCatching { f.readText() }.getOrNull() ?: return@mapNotNull null
                when (val r = WorkoutParser.parse(text)) {
                    is ParseResult.Success -> StoredWorkout(f.name, r.workout, text, f.lastModified())
                    is ParseResult.Failure -> null
                }
            }
            .sortedByDescending { it.modified }

    private fun freeName(title: String): String {
        val base = slug(title).ifEmpty { "workout" }
        var name = "$base$EXTENSION"
        var n = 2
        while (File(dir, name).exists()) name = "$base-${n++}$EXTENSION"
        return name
    }

    companion object {
        private const val BOM = '\uFEFF'

        /** Reads a workout file of at most [WorkoutParser.MAX_SIZE] bytes. */
        fun readLimited(input: InputStream): String {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > WorkoutParser.MAX_SIZE) throw IOException("The file is larger than ${WorkoutParser.MAX_SIZE / 1024} KB.")
            }
            return out.toByteArray().decodeToString()
        }

        /** "Interwały 3 min" → "interwaly-3-min". */
        fun slug(s: String): String =
            Normalizer.normalize(s.replace('ł', 'l').replace('Ł', 'L'), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(48)
    }
}
