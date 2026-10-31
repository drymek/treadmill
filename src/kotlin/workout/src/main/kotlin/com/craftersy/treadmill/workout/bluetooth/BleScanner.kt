package com.craftersy.treadmill.workout.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException

/** A device seen while scanning. */
data class FoundDevice(val name: String, val address: String, val rssi: Int)

/** Bluetooth is switched off. */
class BluetoothOffException : IOException("Bluetooth is off.")

/** The phone has no Bluetooth, or the scan could not start. */
class ScanFailedException(message: String) : IOException(message)

/**
 * Scans for Bluetooth LE devices that advertise a name, for picking the
 * treadmill in the settings. Emits every device seen so far, strongest signal
 * first; the scan runs until the collector stops. The caller must hold
 * BLUETOOTH_SCAN.
 */
@SuppressLint("MissingPermission")
fun scanDevices(context: Context): Flow<List<FoundDevice>> = callbackFlow {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        ?: throw ScanFailedException("This phone has no Bluetooth adapter.")
    if (!adapter.isEnabled) throw BluetoothOffException()
    val scanner = adapter.bluetoothLeScanner ?: throw BluetoothOffException()

    val found = linkedMapOf<String, FoundDevice>()
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (name.isBlank()) return
            found[result.device.address] = FoundDevice(name, result.device.address, result.rssi)
            trySend(found.values.sortedByDescending { it.rssi })
        }

        override fun onScanFailed(errorCode: Int) {
            close(ScanFailedException("Bluetooth scan failed (error $errorCode)."))
        }
    }
    trySend(emptyList())
    scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
    awaitClose { runCatching { scanner.stopScan(callback) } }
}
