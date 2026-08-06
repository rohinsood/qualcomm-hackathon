@file:Suppress("DEPRECATION")

package com.example.qhackgps.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID

sealed interface CaneLinkState {
    data object Disconnected : CaneLinkState
    data object Scanning : CaneLinkState
    data class Connecting(val deviceName: String) : CaneLinkState
    data class Connected(val deviceName: String) : CaneLinkState
}

/** One distance report from the cane. [mm] is null when nothing is in range. */
data class CaneReading(val mm: Int?, val present: Boolean)

/**
 * BLE central for the qhackcane "Distance Watch" board (Arduino UNO Q).
 *
 * The board's ble-bridge advertises a Nordic UART Service peripheral named
 * "Distance Watch" (no pairing needed). We scan for the NUS service UUID,
 * connect, subscribe to TX notifications and reassemble newline-terminated
 * JSON lines like {"mm":842,"p":0}. Writes to RX carry UTF-8 text; a plain
 * number sets the cane's presence threshold in mm.
 *
 * [start] keeps the link alive: a watchdog rescans a few seconds after any
 * drop, and quietly waits for Bluetooth/permissions when they're missing.
 */
@SuppressLint("MissingPermission")
class CaneBleLink(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<CaneLinkState>(CaneLinkState.Disconnected)
    val state: StateFlow<CaneLinkState> = _state.asStateFlow()

    private val _reading = MutableStateFlow<CaneReading?>(null)
    val reading: StateFlow<CaneReading?> = _reading.asStateFlow()

    private var desired = false
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private val lineBuffer = StringBuilder()
    private val writeQueue = ArrayDeque<String>()
    private var writeInFlight = false

    private val adapter
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    init {
        // Watchdog: whenever the link should be up but isn't, try again.
        scope.launch {
            while (isActive) {
                delay(3000L)
                val kick = synchronized(this@CaneBleLink) {
                    desired && gatt == null && !scanning
                }
                if (kick) startScan()
            }
        }
    }

    /** Idempotent: keep the cane link up from now on. */
    fun start() {
        val kick = synchronized(this) {
            val first = !desired
            desired = true
            first && gatt == null && !scanning
        }
        if (kick) startScan()
    }

    fun stop() {
        synchronized(this) {
            desired = false
            stopScanLocked()
            teardownLocked()
        }
        _state.value = CaneLinkState.Disconnected
        _reading.value = null
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    /** Queue a short UTF-8 text write to the cane (single BLE frame, <= 20 bytes). */
    fun write(text: String) {
        synchronized(this) {
            if (gatt == null || rxChar == null) return
            writeQueue.addLast(text.take(20))
        }
        pumpWrites()
    }

    // ---------- scanning ----------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val connect = synchronized(this@CaneBleLink) {
                if (!scanning || gatt != null) return
                stopScanLocked()
                true
            }
            if (connect) connectTo(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "cane scan failed: $errorCode")
            synchronized(this@CaneBleLink) { scanning = false }
            _state.value = CaneLinkState.Disconnected
        }
    }

    private fun startScan() {
        if (!hasScanPermission(context) || !hasConnectPermission(context)) return
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        val begin = synchronized(this) {
            if (!desired || scanning || gatt != null) return
            scanning = true
            true
        }
        if (!begin) return
        _state.value = CaneLinkState.Scanning
        try {
            scanner.startScan(
                listOf(
                    ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(NUS_SERVICE_UUID))
                        .build()
                ),
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                scanCallback,
            )
        } catch (e: Exception) {
            Log.w(TAG, "cane scan start failed", e)
            synchronized(this) { scanning = false }
            _state.value = CaneLinkState.Disconnected
            return
        }
        // Duty-cycle the scan; the watchdog starts another round a few seconds later.
        scope.launch {
            delay(SCAN_WINDOW_MS)
            val stopped = synchronized(this@CaneBleLink) {
                if (scanning) {
                    stopScanLocked()
                    true
                } else false
            }
            if (stopped && gatt == null) _state.value = CaneLinkState.Disconnected
        }
    }

    private fun stopScanLocked() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    // ---------- connection ----------

    private fun connectTo(device: BluetoothDevice) {
        val name = try {
            device.name ?: device.address
        } catch (_: SecurityException) {
            device.address
        }
        _state.value = CaneLinkState.Connecting(name)
        Log.d(TAG, "cane: connecting to $name")
        try {
            val g = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            synchronized(this) { gatt = g }
        } catch (e: Exception) {
            Log.w(TAG, "cane connectGatt failed", e)
            _state.value = CaneLinkState.Disconnected
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "cane: disconnected (status=$status)")
                    synchronized(this@CaneBleLink) { teardownLocked() }
                    _state.value = CaneLinkState.Disconnected
                    _reading.value = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(NUS_SERVICE_UUID)
            val tx = service?.getCharacteristic(NUS_TX_UUID)
            val rx = service?.getCharacteristic(NUS_RX_UUID)
            if (service == null || tx == null || rx == null) {
                Log.w(TAG, "cane: NUS service/characteristics missing")
                g.disconnect()
                return
            }
            synchronized(this@CaneBleLink) { rxChar = rx }
            g.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            } else {
                onReady(g)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == CCCD_UUID) onReady(g)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            status: Int,
        ) {
            synchronized(this@CaneBleLink) { writeInFlight = false }
            pumpWrites()
        }

        // Legacy callback (used through Android 12); API 33+ adds the overload below.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { onBytes(it) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onBytes(value)
        }
    }

    private fun onReady(g: BluetoothGatt) {
        val name = try {
            g.device?.name ?: g.device?.address ?: "cane"
        } catch (_: SecurityException) {
            "cane"
        }
        Log.d(TAG, "cane: connected, notifications on")
        _state.value = CaneLinkState.Connected(name)
        // Widen the cane's presence threshold so "object in the way" fires early
        // enough to steer around it while walking (the board defaults to 300 mm).
        write(OBSTACLE_THRESHOLD_MM.toString())
    }

    private fun teardownLocked() {
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        rxChar = null
        writeQueue.clear()
        writeInFlight = false
        lineBuffer.setLength(0)
    }

    // ---------- data ----------

    private fun onBytes(bytes: ByteArray) {
        val lines = ArrayList<String>()
        synchronized(lineBuffer) {
            lineBuffer.append(String(bytes, Charsets.UTF_8))
            if (lineBuffer.length > 512) lineBuffer.setLength(0) // runaway guard
            while (true) {
                val nl = lineBuffer.indexOf("\n")
                if (nl < 0) break
                lines.add(lineBuffer.substring(0, nl).trim())
                lineBuffer.delete(0, nl + 1)
            }
        }
        lines.filter { it.isNotEmpty() }.forEach(::parseLine)
    }

    private fun parseLine(line: String) {
        try {
            val json = JSONObject(line)
            when {
                json.has("mm") || json.has("p") -> {
                    val mm = if (!json.has("mm") || json.isNull("mm")) null else json.getInt("mm")
                    _reading.value = CaneReading(mm = mm, present = json.optInt("p", 0) == 1)
                }
                json.has("thr") -> Log.d(TAG, "cane threshold ack: $line")
                json.has("err") -> Log.w(TAG, "cane error: $line")
            }
        } catch (_: Exception) {
            Log.w(TAG, "cane: unparseable line: $line")
        }
    }

    private fun pumpWrites() {
        val work: Pair<BluetoothGatt, BluetoothGattCharacteristic>? = synchronized(this) {
            val g = gatt
            val rx = rxChar
            if (g == null || rx == null || writeInFlight || writeQueue.isEmpty()) null
            else {
                writeInFlight = true
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                rx.value = writeQueue.removeFirst().toByteArray(Charsets.UTF_8)
                g to rx
            }
        }
        if (work != null) {
            val ok = try {
                work.first.writeCharacteristic(work.second)
            } catch (_: Exception) {
                false
            }
            if (!ok) synchronized(this) { writeInFlight = false }
        }
    }

    companion object {
        private const val TAG = "qhackGPS"
        private const val SCAN_WINDOW_MS = 10_000L

        /** Presence threshold (mm) pushed to the cane on connect. */
        const val OBSTACLE_THRESHOLD_MM = 1200

        val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_RX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_TX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** BLE scanning needs BLUETOOTH_SCAN on 12+, fine location before that. */
        fun hasScanPermission(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            }

        fun hasConnectPermission(context: Context): Boolean =
            BluetoothGuidanceLink.hasConnectPermission(context)
    }
}
