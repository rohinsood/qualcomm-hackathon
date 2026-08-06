package com.example.qhackgps.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.OutputStream
import java.util.UUID

sealed interface BtLinkState {
    data object Disconnected : BtLinkState
    data class Connecting(val deviceName: String) : BtLinkState
    data class Connected(val deviceName: String) : BtLinkState
}

/**
 * Bluetooth Classic (SPP/RFCOMM) client that streams guidance frames to an Arduino
 * fitted with an HC-05/HC-06 style serial module.
 *
 * Usage: pair the module in Android Settings first (PIN is usually 1234 or 0000),
 * then [connect] to one of [bondedDevices]. The UI calls [sendOrReconnect] on a
 * steady cadence; if the link drops, it retries every few seconds on its own.
 */
class BluetoothGuidanceLink(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<BtLinkState>(BtLinkState.Disconnected)
    val state: StateFlow<BtLinkState> = _state.asStateFlow()

    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var desiredDevice: BluetoothDevice? = null
    private var lastAttemptMs = 0L

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    val isBluetoothEnabled: Boolean
        get() = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDevice> {
        if (!hasConnectPermission(context)) return emptyList()
        return try {
            adapter?.bondedDevices?.toList() ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    fun connect(device: BluetoothDevice) {
        scope.launch {
            mutex.withLock {
                desiredDevice = device
                closeLocked()
                doConnectLocked()
            }
        }
    }

    fun disconnect() {
        scope.launch {
            mutex.withLock {
                desiredDevice = null
                closeLocked()
                _state.value = BtLinkState.Disconnected
            }
        }
    }

    /**
     * Called on a steady cadence (~5 Hz) by the sender loop. Writes the frame if
     * connected; if the link is down but a device was chosen, retries the
     * connection at most once every [RETRY_INTERVAL_MS].
     */
    fun sendOrReconnect(line: String) {
        scope.launch {
            mutex.withLock {
                if (output == null) {
                    if (desiredDevice == null) return@withLock
                    if (System.currentTimeMillis() - lastAttemptMs < RETRY_INTERVAL_MS) return@withLock
                    doConnectLocked()
                }
                val out = output ?: return@withLock
                try {
                    out.write(line.toByteArray(Charsets.US_ASCII))
                    out.flush()
                } catch (_: Exception) {
                    closeLocked()
                    _state.value = BtLinkState.Disconnected
                }
            }
        }
    }

    fun shutdown() {
        scope.launch {
            mutex.withLock {
                desiredDevice = null
                closeLocked()
                _state.value = BtLinkState.Disconnected
            }
        }.invokeOnCompletion { scope.cancel() }
    }

    @SuppressLint("MissingPermission")
    private fun doConnectLocked() {
        val device = desiredDevice ?: return
        if (!hasConnectPermission(context)) return
        lastAttemptMs = System.currentTimeMillis()
        val name = try {
            device.name ?: device.address
        } catch (_: SecurityException) {
            device.address
        }
        _state.value = BtLinkState.Connecting(name)
        try {
            // Discovery slows RFCOMM connects badly; best-effort cancel.
            try {
                adapter?.cancelDiscovery()
            } catch (_: SecurityException) {
            }
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            socket = s
            output = s.outputStream
            _state.value = BtLinkState.Connected(name)
        } catch (_: Exception) {
            closeLocked()
            _state.value = BtLinkState.Disconnected
        }
    }

    private fun closeLocked() {
        try {
            output?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        output = null
        socket = null
    }

    companion object {
        /** Well-known Serial Port Profile UUID — what HC-05/HC-06 modules speak. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val RETRY_INTERVAL_MS = 4000L

        /** BLUETOOTH_CONNECT is only a runtime permission on Android 12+. */
        fun hasConnectPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
    }
}
