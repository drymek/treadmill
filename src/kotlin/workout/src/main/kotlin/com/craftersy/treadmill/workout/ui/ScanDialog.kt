package com.craftersy.treadmill.workout.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.craftersy.treadmill.protocol.Ble
import com.craftersy.treadmill.workout.R
import com.craftersy.treadmill.workout.bluetooth.BluetoothOffException
import com.craftersy.treadmill.workout.bluetooth.FoundDevice
import com.craftersy.treadmill.workout.bluetooth.scanDevices
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withTimeoutOrNull

private val SCAN = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

/** How long one search runs. The treadmill advertises several times a second while nothing is connected to it. */
private const val SCAN_MILLIS = 12_000L

/** Searches for nearby Bluetooth devices and returns the name of the one tapped. */
@Composable
fun ScanDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(SCAN.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) }
    var denied by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.all { it }
        denied = !granted
    }
    var devices by remember { mutableStateOf(emptyList<FoundDevice>()) }
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableIntStateOf(0) }
    val bluetoothOff = stringResource(R.string.scan_bluetooth_off)

    LaunchedEffect(Unit) { if (!granted) request.launch(SCAN.toTypedArray()) }
    LaunchedEffect(granted, round) {
        if (!granted) return@LaunchedEffect
        scanning = true
        error = null
        withTimeoutOrNull(SCAN_MILLIS) {
            scanDevices(context)
                .catch { e -> error = if (e is BluetoothOffException) bluetoothOff else e.message }
                .collect { devices = it }
        }
        scanning = false
    }

    // Names that look like a treadmill come first, the rest by signal strength.
    val sorted = devices.sortedByDescending { looksLikeTreadmill(it.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.surfaceHigh,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.scan_title), modifier = Modifier.weight(1f))
                if (scanning) CircularProgressIndicator(Modifier.size(20.dp), color = Ink.accent, strokeWidth = 2.dp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    denied -> MutedText(stringResource(R.string.scan_permission))
                    error != null -> Text(error.orEmpty(), color = Ink.danger, style = MaterialTheme.typography.bodyMedium)
                    sorted.isEmpty() && scanning -> MutedText(stringResource(R.string.scan_searching))
                    sorted.isEmpty() -> MutedText(stringResource(R.string.scan_none))
                }
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(sorted, key = { it.address }) { d -> DeviceRow(d, looksLikeTreadmill(d.name)) { onPick(d.name) } }
                }
            }
        },
        confirmButton = {
            if (!scanning && granted) {
                TextButton({ devices = emptyList(); round++ }) { Text(stringResource(R.string.scan_again), color = Ink.accent) }
            } else if (denied) {
                TextButton({ request.launch(SCAN.toTypedArray()) }) { Text(stringResource(R.string.scan_allow), color = Ink.accent) }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel), color = Ink.text) } },
    )
}

@Composable
private fun DeviceRow(device: FoundDevice, treadmill: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (treadmill) Ink.accent.copy(alpha = 0.10f) else Ink.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Bluetooth, null, tint = if (treadmill) Ink.accent else Ink.textMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.titleSmall, color = Ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (treadmill) stringResource(R.string.scan_treadmill) else device.address,
                style = MaterialTheme.typography.bodySmall,
                color = if (treadmill) Ink.accent else Ink.textFaint,
            )
        }
        Spacer(Modifier.width(8.dp))
        SignalBars(device.rssi)
    }
}

/** Four bars for the signal strength: -55 dBm and better is full, -95 dBm and worse is one bar. */
@Composable
private fun SignalBars(rssi: Int) {
    val level = ((rssi + 95) / 13 + 1).coerceIn(1, 4)
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..4) {
            Box(
                Modifier
                    .width(4.dp)
                    .size(width = 4.dp, height = (4 + i * 3).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i <= level) Ink.text else Ink.outlineStrong),
            )
        }
    }
}

private fun looksLikeTreadmill(name: String) = name.contains(Ble.DEVICE_NAME, ignoreCase = true) || name.contains("tread", ignoreCase = true)
