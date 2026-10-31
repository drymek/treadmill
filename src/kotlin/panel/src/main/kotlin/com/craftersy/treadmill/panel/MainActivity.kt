package com.craftersy.treadmill.panel

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.craftersy.treadmill.protocol.Ble

private val BLUETOOTH_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

/**
 * The virtual HMS console for RZ_TreadMil treadmills: the connect screen,
 * then the console. Leaving the console stops a moving belt first.
 */
class MainActivity : ComponentActivity() {
    private val model: PanelViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val session by model.session.collectAsStateWithLifecycle()
                val farewell by model.farewell.collectAsStateWithLifecycle()
                val current = session
                if (current == null) {
                    var name by rememberSaveable { mutableStateOf(Ble.DEVICE_NAME) }
                    var message by rememberSaveable { mutableStateOf<String?>(null) }
                    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
                        if (granted.values.all { it }) {
                            model.connectBluetooth(name.trim())
                        } else {
                            message = "The console needs the Nearby devices permission to find the treadmill."
                        }
                    }
                    ConnectScreen(
                        name = name,
                        onNameChange = { name = it },
                        message = message ?: farewell,
                        onBluetooth = {
                            message = null
                            if (hasBluetoothPermissions()) model.connectBluetooth(name.trim()) else request.launch(BLUETOOTH_PERMISSIONS)
                        },
                        onSimulator = {
                            message = null
                            model.connectSimulator()
                        },
                    )
                } else {
                    val state by current.controller.state.collectAsStateWithLifecycle()
                    LaunchedEffect(state.finished) { if (state.finished) model.close() }
                    BackHandler { current.controller.quit() }
                    Console(
                        state = state,
                        onPress = { action, value -> current.controller.press(action, value) },
                        onQuit = { current.controller.quit() },
                    )
                }
            }
        }
    }

    private fun hasBluetoothPermissions(): Boolean = BLUETOOTH_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
