package com.craftersy.treadmill.panel.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import com.craftersy.treadmill.client.ClosedException
import com.craftersy.treadmill.client.Transport
import com.craftersy.treadmill.protocol.Ble
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Selects the treadmill to connect to. */
data class BleOptions(
    /** Matched case-insensitively as a substring of the advertised name. */
    val name: String = Ble.DEVICE_NAME,
    /** MAC address; when set, it takes precedence over [name] and no scan runs. */
    val address: String? = null,
    val scanTimeout: Duration = 25.seconds,
    val connectTimeout: Duration = 15.seconds,
    val writeTimeout: Duration = 5.seconds,
)

/** No matching treadmill advertised during the scan. */
class NotFoundException(name: String) :
    IOException("Treadmill \"$name\" not found. Is it switched on, close by, and not connected to another device?")

private val SERVICE: UUID = UUID.fromString(Ble.SERVICE_UUID)
private val NOTIFY: UUID = UUID.fromString(Ble.NOTIFY_CHARACTERISTIC_UUID)
private val WRITE: UUID = UUID.fromString(Ble.WRITE_CHARACTERISTIC_UUID)
private val CCCD: UUID = UUID.fromString(Ble.CCCD_UUID)

/**
 * An open BLE connection to a treadmill: commands go to FFF2, status frames
 * arrive as notifications on FFF1.
 *
 * The caller must hold BLUETOOTH_SCAN and BLUETOOTH_CONNECT; MainActivity
 * asks for both before it connects.
 */
@SuppressLint("MissingPermission")
class BleTransport private constructor(
    private val gatt: BluetoothGatt,
    private val writeCharacteristic: BluetoothGattCharacteristic,
    private val callback: GattCallback,
    private val writeTimeout: Duration,
) : Transport {
    private val writeLock = Mutex()

    override val frames: ReceiveChannel<ByteArray> get() = callback.frames

    override suspend fun write(frame: ByteArray): Unit = writeLock.withLock {
        if (callback.isShutDown) throw ClosedException()
        val done = CompletableDeferred<Int>()
        callback.pendingWrite = done
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(writeCharacteristic, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            writeCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            writeCharacteristic.value = frame
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(writeCharacteristic)
        }
        if (!accepted) throw IOException("bluetooth: write rejected by the Bluetooth stack")
        val status = try {
            withTimeout(writeTimeout) { done.await() }
        } catch (e: TimeoutCancellationException) {
            throw IOException("bluetooth: write not confirmed in time")
        }
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("bluetooth: write failed (GATT status $status)")
    }

    override fun close() {
        callback.shutdown(ClosedException())
    }

    companion object {
        /** Scans for a treadmill, connects to it and subscribes to status frames. */
        suspend fun dial(context: Context, options: BleOptions = BleOptions()): BleTransport {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw IOException("This phone has no Bluetooth adapter.")
            if (!adapter.isEnabled) throw IOException("Bluetooth is off. Turn it on and try again.")

            val device = if (options.address != null) {
                adapter.getRemoteDevice(options.address)
            } else {
                try {
                    withTimeout(options.scanTimeout) { scan(adapter.bluetoothLeScanner ?: throw IOException("Bluetooth is off."), options.name) }
                } catch (e: TimeoutCancellationException) {
                    throw NotFoundException(options.name)
                }
            }

            val callback = GattCallback()
            val gatt = device.connectGatt(context.applicationContext, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw IOException("bluetooth: could not start connecting")
            callback.gatt = gatt
            val write = try {
                withTimeout(options.connectTimeout) { callback.ready.await() }
            } catch (e: TimeoutCancellationException) {
                callback.shutdown(IOException("bluetooth: connecting timed out"))
                throw IOException("Connecting to ${device.address} timed out.")
            } catch (e: Throwable) {
                callback.shutdown(e)
                throw e
            }
            return BleTransport(gatt, write, callback, options.writeTimeout)
        }

        private suspend fun scan(scanner: android.bluetooth.le.BluetoothLeScanner, name: String): BluetoothDevice =
            suspendCancellableCoroutine { cont ->
                val finished = AtomicBoolean(false)
                val cb = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        val advertised = result.scanRecord?.deviceName ?: result.device.name ?: return
                        if (advertised.contains(name, ignoreCase = true) && finished.compareAndSet(false, true)) {
                            scanner.stopScan(this)
                            cont.resume(result.device)
                        }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        if (finished.compareAndSet(false, true)) {
                            cont.resumeWithException(IOException("bluetooth: scan failed (error $errorCode)"))
                        }
                    }
                }
                val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                scanner.startScan(null, settings, cb)
                cont.invokeOnCancellation {
                    if (finished.compareAndSet(false, true)) scanner.stopScan(cb)
                }
            }
    }
}

/** Turns GATT callbacks, which arrive on a binder thread, into coroutine-friendly signals. */
@SuppressLint("MissingPermission")
private class GattCallback : BluetoothGattCallback() {
    @Volatile
    var gatt: BluetoothGatt? = null

    /** Completes with the write characteristic once notifications are enabled. */
    val ready = CompletableDeferred<BluetoothGattCharacteristic>()
    val frames = Channel<ByteArray>(64)

    @Volatile
    var pendingWrite: CompletableDeferred<Int>? = null

    private val down = AtomicBoolean(false)
    val isShutDown: Boolean get() = down.get()

    private var write: BluetoothGattCharacteristic? = null

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        when {
            newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> gatt.discoverServices()
            newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS ->
                shutdown(IOException("Connection lost (GATT status $status)."), gatt)
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        val service = gatt.getService(SERVICE)
        val notify = service?.getCharacteristic(NOTIFY)
        val write = service?.getCharacteristic(WRITE)
        val cccd = notify?.getDescriptor(CCCD)
        if (status != BluetoothGatt.GATT_SUCCESS || notify == null || write == null || cccd == null) {
            shutdown(IOException("This device does not offer the treadmill service FFF0."), gatt)
            return
        }
        this.write = write
        gatt.setCharacteristicNotification(notify, true)
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        val w = write
        if (status == BluetoothGatt.GATT_SUCCESS && w != null) {
            ready.complete(w)
        } else {
            shutdown(IOException("bluetooth: enabling notifications failed (GATT status $status)"), gatt)
        }
    }

    // Android 13 and later.
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid == NOTIFY) frames.trySend(value.copyOf())
    }

    // Android 12.
    @Deprecated("Replaced by the overload with a value on Android 13")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        @Suppress("DEPRECATION")
        val value = characteristic.value ?: return
        if (characteristic.uuid == NOTIFY) frames.trySend(value.copyOf())
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        pendingWrite?.complete(status)
    }

    /** Ends the connection once: fails waiters, closes the GATT client and the frame channel. */
    fun shutdown(cause: Throwable, g: BluetoothGatt? = gatt) {
        if (!down.compareAndSet(false, true)) return
        ready.completeExceptionally(cause)
        pendingWrite?.completeExceptionally(cause)
        g?.disconnect()
        g?.close()
        frames.close()
    }
}
