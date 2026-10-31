package com.craftersy.treadmill.workout

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.craftersy.treadmill.workout.run.ActiveRun
import com.craftersy.treadmill.workout.run.Device
import com.craftersy.treadmill.workout.run.WorkoutService
import com.craftersy.treadmill.workout.ui.EditorScreen
import com.craftersy.treadmill.workout.ui.ImportFailureDialog
import com.craftersy.treadmill.workout.ui.Ink
import com.craftersy.treadmill.workout.ui.LibraryScreen
import com.craftersy.treadmill.workout.ui.RunScreen
import com.craftersy.treadmill.workout.ui.WorkoutTheme

/**
 * The workout app: a library of .workout.yaml files, an editor for every
 * option of the format, and the run screen. Workouts run in
 * [com.craftersy.treadmill.workout.run.WorkoutService], so they keep going in
 * the background.
 */
class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()

    /** A run waiting for the permissions dialog. */
    private var pendingRun: Pair<String, Device>? = null

    private val permissions = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val run = pendingRun ?: return@registerForActivityResult
        pendingRun = null
        // Notifications are optional; Bluetooth is not.
        if (BLUETOOTH.all { granted[it] ?: hasPermission(it) }) model.run(run.first, run.second)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The UI is always dark: light status bar icons, no scrim behind the navigation buttons.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        if (savedInstanceState == null) handleIntent(intent)
        WorkoutService.clearStale(this)
        setContent {
            WorkoutTheme {
                val screen by model.screen.collectAsStateWithLifecycle()
                val runner by ActiveRun.runner.collectAsStateWithLifecycle()
                val failure by model.importFailure.collectAsStateWithLifecycle()
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(Unit) { model.messages.collect { snackbar.showSnackbar(it) } }

                Box(Modifier.fillMaxSize().background(Ink.background)) {
                    val current = runner
                    AnimatedContent(
                        targetState = if (current != null) "run" else if (screen is Screen.Editor) "editor" else "library",
                        transitionSpec = { (fadeIn() + slideInVertically { it / 12 }) togetherWith fadeOut() },
                        label = "screen",
                    ) { target ->
                        when (target) {
                            "run" -> current?.let { r ->
                                RunScreen(runner = r, onClose = { ActiveRun.dismiss() })
                            }
                            "editor" -> {
                                BackHandler { model.closeEditor() }
                                EditorScreen(model = model)
                            }
                            else -> LibraryScreen(
                                model = model,
                                onRun = { id, device -> requestRun(id, device) },
                                onShare = { id -> model.shareIntent(id)?.let(::startActivity) },
                                onLanguage = { language ->
                                    AppLocale.set(this@MainActivity, language)
                                    recreate()
                                },
                            )
                        }
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp)) { data ->
                        Snackbar(
                            data,
                            shape = RoundedCornerShape(18.dp),
                            containerColor = Ink.surfaceHigher,
                            contentColor = Ink.text,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    failure?.let { ImportFailureDialog(it, onDismiss = model::dismissImportFailure) }
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Workouts shared from other apps or opened as files. */
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val stream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (stream != null) model.importUri(stream) else model.importText(intent.getStringExtra(Intent.EXTRA_TEXT), R.string.source_shared)
            }
            Intent.ACTION_VIEW -> intent.data?.let(model::importUri)
        }
    }

    private fun requestRun(id: String, device: Device) {
        val missing = (BLUETOOTH + NOTIFICATIONS).filterNot(::hasPermission)
        if (missing.isEmpty()) {
            model.run(id, device)
        } else {
            pendingRun = id to device
            permissions.launch(missing.toTypedArray())
        }
    }

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private companion object {
        // The foreground service type "connectedDevice" needs a Bluetooth permission even for the simulator.
        val BLUETOOTH = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val NOTIFICATIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    }
}
