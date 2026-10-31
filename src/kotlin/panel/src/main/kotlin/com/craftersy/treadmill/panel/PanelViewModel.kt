package com.craftersy.treadmill.panel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.craftersy.treadmill.client.Client
import com.craftersy.treadmill.client.ConnectionEvent
import com.craftersy.treadmill.client.ReconnectingTransport
import com.craftersy.treadmill.panel.bluetooth.BleOptions
import com.craftersy.treadmill.panel.bluetooth.BleTransport
import com.craftersy.treadmill.simulator.Simulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One connection to a treadmill or the simulator, shown on the console screen. */
class Session(val controller: PanelController, val ui: CoroutineScope, val connection: CoroutineScope)

/**
 * Owns the current [Session]. The connection lives in its own scope rather
 * than the view model's, so that a moving belt can still be stopped after the
 * screen is gone.
 */
class PanelViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableSession = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = mutableSession.asStateFlow()

    private val mutableFarewell = MutableStateFlow<String?>(null)

    /** The outcome of the safety stop when the last session ended with a moving belt. */
    val farewell: StateFlow<String?> = mutableFarewell.asStateFlow()

    /** Connects over Bluetooth to the treadmill advertising [name]. */
    fun connectBluetooth(name: String) {
        val context = getApplication<Application>()
        open(name) { scope, onEvent ->
            val transport = ReconnectingTransport.dial(scope, onChange = onEvent) {
                BleTransport.dial(context, BleOptions(name = name))
            }
            Client(transport, scope)
        }
    }

    /** Runs the console against the built-in simulator, no hardware needed. */
    fun connectSimulator() {
        open("simulator") { scope, _ -> Client(Simulator(scope), scope) }
    }

    private fun open(device: String, connect: suspend (CoroutineScope, (ConnectionEvent) -> Unit) -> Client) {
        close()
        mutableFarewell.value = null
        val ui = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        val connection = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = PanelController(ui, device, { onEvent -> connect(connection, onEvent) })
        mutableSession.value = Session(controller, ui, connection)
    }

    /** Ends the current session, stopping a belt that may still be moving. */
    fun close() {
        val s = mutableSession.value ?: return
        mutableSession.value = null
        s.ui.cancel()
        s.connection.launch {
            s.controller.safetyStop()?.let { mutableFarewell.value = it }
            s.controller.client?.close()
            s.connection.cancel()
        }
    }

    override fun onCleared() {
        close()
    }
}
