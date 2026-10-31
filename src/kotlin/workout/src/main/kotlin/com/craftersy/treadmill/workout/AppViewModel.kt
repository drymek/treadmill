package com.craftersy.treadmill.workout

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.craftersy.treadmill.protocol.Ble
import com.craftersy.treadmill.workout.data.InvalidWorkoutException
import com.craftersy.treadmill.workout.data.samples
import com.craftersy.treadmill.workout.data.StoredWorkout
import com.craftersy.treadmill.workout.data.WorkoutRepository
import com.craftersy.treadmill.workout.format.Entry
import com.craftersy.treadmill.workout.format.Length
import com.craftersy.treadmill.workout.format.Problem
import com.craftersy.treadmill.workout.format.Step
import com.craftersy.treadmill.workout.format.Target
import com.craftersy.treadmill.workout.format.Workout
import com.craftersy.treadmill.workout.run.Device
import com.craftersy.treadmill.workout.run.WorkoutService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Which screen is shown. The run screen is shown on top of any of them while a workout runs. */
sealed interface Screen {
    data object Library : Screen

    /** The editor, for the workout [id] or a new one. */
    data class Editor(val id: String?) : Screen
}

/** Where Run sends a workout. Kept in settings: day to day it is always the same treadmill. */
data class RunSettings(val treadmillName: String = Ble.DEVICE_NAME, val simulator: Boolean = false) {
    val device: Device get() = if (simulator) Device.Simulator else Device.Treadmill(treadmillName.trim().ifEmpty { Ble.DEVICE_NAME })
}

/** A failed import: where it came from (a string resource) and every problem found. */
data class ImportFailure(@StringRes val source: Int, val problems: List<Problem>)

/** A new workout's starting point: warm-up, one effort, cool-down, named in the app's language. */
fun blankWorkout(context: Context): Workout = Workout(
    name = "",
    entries = listOf(
        Entry.Single(Step(context.getString(R.string.default_warmup), Length.Time(600), Target.Pace(390), incline = 1)),
        Entry.Single(Step(context.getString(R.string.default_run), Length.Time(1200), Target.Pace(330))),
        Entry.Single(Step(context.getString(R.string.default_cooldown), Length.Time(600), Target.Pace(420), incline = 0)),
    ),
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val repository = WorkoutRepository(File(application.filesDir, "workouts"))
    val workouts: StateFlow<List<StoredWorkout>> = repository.workouts

    private val mutableScreen = MutableStateFlow<Screen>(Screen.Library)
    val screen: StateFlow<Screen> = mutableScreen.asStateFlow()

    /** The workout being edited; kept here so it survives rotation. */
    private val mutableDraft = MutableStateFlow(Workout(name = "", entries = emptyList()))
    val draft: StateFlow<Workout> = mutableDraft.asStateFlow()

    private val mutableImportFailure = MutableStateFlow<ImportFailure?>(null)
    val importFailure: StateFlow<ImportFailure?> = mutableImportFailure.asStateFlow()

    private val prefs = application.getSharedPreferences("run", Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(
        RunSettings(prefs.getString("name", null) ?: Ble.DEVICE_NAME, prefs.getBoolean("simulator", false)),
    )
    val settings: StateFlow<RunSettings> = mutableSettings.asStateFlow()

    fun updateSettings(s: RunSettings) {
        mutableSettings.value = s
        prefs.edit { putString("name", s.treadmillName).putBoolean("simulator", s.simulator) }
    }

    private val mutableBusy = MutableStateFlow(false)

    /** True while a URL is being downloaded. */
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)

    /** Short confirmations for the snackbar. */
    val messages = messageChannel.receiveAsFlow()

    init {
        viewModelScope.launch { repository.load() }
    }

    /** The application context in the language chosen in the settings. */
    private val res: Context get() = AppLocale.wrap(getApplication())

    fun edit(id: String?) {
        mutableDraft.value = id?.let { repository.get(it)?.workout } ?: blankWorkout(res)
        mutableScreen.value = Screen.Editor(id)
    }

    fun updateDraft(workout: Workout) {
        mutableDraft.value = workout
    }

    fun closeEditor() {
        mutableScreen.value = Screen.Library
    }

    fun save() {
        val s = mutableScreen.value as? Screen.Editor ?: return
        val w = mutableDraft.value
        viewModelScope.launch {
            runCatching { repository.save(w.copy(name = w.name.trim()), s.id) }
                .onSuccess {
                    mutableScreen.value = Screen.Library
                    messageChannel.send(res.getString(R.string.msg_saved, w.name.trim()))
                }
                .onFailure { messageChannel.send(res.getString(R.string.msg_save_failed)) }
        }
    }

    fun delete(id: String) {
        val name = repository.get(id)?.workout?.name ?: return
        viewModelScope.launch {
            repository.delete(id)
            messageChannel.send(res.getString(R.string.msg_deleted, name))
        }
    }

    fun duplicate(id: String) {
        val w = repository.get(id)?.workout ?: return
        viewModelScope.launch {
            repository.save(w.copy(name = res.getString(R.string.copy_suffix, w.name)))
            messageChannel.send(res.getString(R.string.msg_duplicated, w.name))
        }
    }

    fun addSamples() {
        viewModelScope.launch { samples(res).forEach { repository.import(it) } }
    }

    /** Imports workout text pasted from the clipboard or shared by another app. */
    fun importText(text: String?, @StringRes source: Int) {
        if (text.isNullOrBlank()) {
            viewModelScope.launch { messageChannel.send(res.getString(R.string.msg_empty, res.getString(source))) }
            return
        }
        viewModelScope.launch { import(source) { text } }
    }

    fun importUrl(url: String) {
        viewModelScope.launch { import(R.string.source_url) { repository.download(url) } }
    }

    /** Imports a file opened from another app or picked from storage. */
    fun importUri(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            import(R.string.source_file) {
                withContext(Dispatchers.IO) {
                    // Files may arrive without a type (see the manifest); refuse anything that is not YAML by name.
                    val name = runCatching {
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        }
                    }.getOrNull()
                    if (name != null && !name.endsWith(".yaml", ignoreCase = true) && !name.endsWith(".yml", ignoreCase = true)) {
                        error(res.getString(R.string.msg_not_yaml, name))
                    }
                    resolver.openInputStream(uri)?.use(WorkoutRepository::readLimited) ?: error(res.getString(R.string.msg_open_failed))
                }
            }
        }
    }

    private suspend fun import(@StringRes source: Int, text: suspend () -> String) {
        mutableBusy.value = true
        try {
            val stored = repository.import(text())
            messageChannel.send(res.getString(R.string.msg_imported, stored.workout.name))
        } catch (e: InvalidWorkoutException) {
            mutableImportFailure.value = ImportFailure(source, e.problems)
        } catch (e: Exception) {
            mutableImportFailure.value = ImportFailure(source, listOf(Problem(0, "", e.message ?: e.toString())))
        } finally {
            mutableBusy.value = false
        }
    }

    fun dismissImportFailure() {
        mutableImportFailure.value = null
    }

    /** A share-sheet intent carrying the workout as a .workout.yaml file and as text. */
    fun shareIntent(id: String): Intent? {
        val stored = repository.get(id) ?: return null
        val app = getApplication<Application>()
        val dir = File(app.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, stored.id).apply { writeText(stored.text) }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, stored.text)
            .putExtra(Intent.EXTRA_SUBJECT, stored.workout.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, res.getString(R.string.share_title, stored.workout.name))
    }

    fun yaml(id: String): String? = repository.get(id)?.text

    fun run(id: String, device: Device) {
        val w = repository.get(id)?.workout ?: return
        WorkoutService.start(getApplication(), w, device)
    }
}
