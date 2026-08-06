package com.example.qhackgps.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.OutputStream
import java.util.Locale
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
 * Pair the module in Android Settings once (PIN is usually 1234 or 0000) and the
 * link comes up by itself: [startAutoConnect] picks the last device that worked,
 * else a paired device that looks like a serial module, and keeps retrying. The
 * UI calls [sendOrReconnect] on a steady cadence, which doubles as the retry
 * clock; [connect] is only needed to override the choice by hand.
 */
class BluetoothGuidanceLink(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<BtLinkState>(BtLinkState.Disconnected)
    val state: StateFlow<BtLinkState> = _state.asStateFlow()

    private val _autoConnecting = MutableStateFlow(false)
    /** True while we're hunting for the Arduino on our own (no device chosen yet). */
    val autoConnecting: StateFlow<Boolean> = _autoConnecting.asStateFlow()

    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var readerJob: Job? = null
    private var desiredDevice: BluetoothDevice? = null
    private var lastAttemptMs = 0L

    /** True when [desiredDevice] was our guess, so a failure may rotate to the next. */
    private var autoPicked = false
    private var autoEnabled = false
    private val autoTried = mutableSetOf<String>()

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

    /**
     * Idempotent: from now on, keep the Arduino link up without any tapping.
     * Safe to call before Bluetooth is on or the permission is granted — the
     * retry inside [sendOrReconnect] picks it up as soon as they arrive.
     */
    fun startAutoConnect() {
        scope.launch {
            mutex.withLock {
                if (autoEnabled) return@withLock
                autoEnabled = true
                _autoConnecting.value = output == null && desiredDevice == null
                if (output == null && desiredDevice == null) autoPickLocked()
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        scope.launch {
            mutex.withLock {
                desiredDevice = device
                autoPicked = false
                autoEnabled = true
                autoTried.clear()
                _autoConnecting.value = false
                closeLocked()
                doConnectLocked()
            }
        }
    }

    /** An explicit disconnect also switches auto-connect off — otherwise we'd
     *  immediately reconnect to the device the user just dismissed. */
    fun disconnect() {
        scope.launch {
            mutex.withLock {
                desiredDevice = null
                autoEnabled = false
                autoPicked = false
                _autoConnecting.value = false
                closeLocked()
                _state.value = BtLinkState.Disconnected
            }
        }
    }

    /**
     * Called on a steady cadence (~5 Hz) by the sender loop. Writes the frame if
     * connected; if the link is down, retries at most once every
     * [RETRY_INTERVAL_MS] — re-picking a device first when we're auto-connecting.
     */
    fun sendOrReconnect(line: String) {
        scope.launch {
            mutex.withLock {
                if (output == null) {
                    if (desiredDevice == null && !autoEnabled) return@withLock
                    if (System.currentTimeMillis() - lastAttemptMs < RETRY_INTERVAL_MS) return@withLock
                    if (desiredDevice == null) {
                        lastAttemptMs = System.currentTimeMillis()
                        if (!autoPickLocked()) return@withLock
                    }
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
                autoEnabled = false
                _autoConnecting.value = false
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
        // Discovery slows RFCOMM connects badly; best-effort cancel.
        try {
            adapter?.cancelDiscovery()
        } catch (_: SecurityException) {
        }
        val opened = openSocket(device)
        if (opened != null) {
            val (s, method) = opened
            socket = s
            output = try {
                s.outputStream
            } catch (_: Exception) {
                null
            }
            if (output == null) {
                closeLocked()
                _state.value = BtLinkState.Disconnected
                return
            }
            _state.value = BtLinkState.Connected(name)
            // Whatever answered SPP is the Arduino: come straight back to it —
            // same device, same handshake — next time.
            prefs.edit()
                .putString(KEY_LAST_DEVICE, device.address)
                .putString(KEY_LAST_METHOD, method)
                .apply()
            autoTried.clear()
            _autoConnecting.value = false
            Log.d(TAG, "arduino link up: $name (${device.address}) via $method")
            startReaderLocked(s)
        } else {
            closeLocked()
            _state.value = BtLinkState.Disconnected
            if (autoPicked) {
                // Our guess didn't answer — drop it so the next retry tries another.
                autoTried += device.address
                desiredDevice = null
                _autoConnecting.value = true
            }
        }
    }

    /**
     * Open an RFCOMM socket, trying every handshake these modules are known to
     * need. HC-05/HC-06 clones are famously fussy: the documented SPP lookup
     * fails outright on some module/phone pairs, an insecure channel (no
     * PIN-encrypted link) often succeeds where it didn't, and a few only ever
     * answer on raw channel 1. Whichever works is remembered and tried first
     * next time, so the steady state is a single fast attempt.
     *
     * Returns the connected socket and the name of the method that opened it.
     */
    @SuppressLint("MissingPermission")
    private fun openSocket(device: BluetoothDevice): Pair<BluetoothSocket, String>? {
        val attempts = linkedMapOf<String, () -> BluetoothSocket>(
            METHOD_SPP to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            METHOD_INSECURE to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            METHOD_CHANNEL1 to { rawChannelSocket(device) },
        )
        val preferred = prefs.getString(KEY_LAST_METHOD, null)
        val order = (listOfNotNull(preferred) + attempts.keys).distinct()

        for (method in order) {
            val open = attempts[method] ?: continue
            val s = try {
                open()
            } catch (e: Exception) {
                // e.g. channel-1 reflection blocked by non-SDK restrictions.
                Log.d(TAG, "arduino: $method unavailable (${e.message})")
                continue
            }
            try {
                s.connect()
                return s to method
            } catch (e: Exception) {
                Log.d(TAG, "arduino: $method connect failed (${e.message})")
                try {
                    s.close()
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    /** Non-SDK API, restricted on recent Android — last resort, always guarded. */
    private fun rawChannelSocket(device: BluetoothDevice): BluetoothSocket {
        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        return method.invoke(device, 1) as BluetoothSocket
    }

    /**
     * Drain the module's input until it ends.
     *
     * This is the drop detector. Writes into a dead RFCOMM socket can sit in a
     * buffer and succeed for a long time after the module is switched off, so
     * waiting for a write to fail leaves the UI claiming "connected" while
     * nothing arrives. A read returning EOF is immediate and unambiguous.
     * Anything the sketch prints back is logged as a bonus.
     */
    private fun startReaderLocked(s: BluetoothSocket) {
        readerJob?.cancel()
        readerJob = scope.launch {
            val buf = ByteArray(128)
            try {
                val input = s.inputStream
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) break // EOF: module gone
                    if (n > 0) {
                        val text = String(buf, 0, n, Charsets.US_ASCII).trim()
                        if (text.isNotEmpty()) Log.d(TAG, "arduino says: $text")
                    }
                }
            } catch (_: Exception) {
                // Socket closed under us, or the module dropped.
            }
            mutex.withLock {
                if (socket === s) {
                    readerJob = null // it's this coroutine; don't cancel ourselves
                    closeLocked()
                    _state.value = BtLinkState.Disconnected
                    Log.d(TAG, "arduino link lost; retrying")
                }
            }
        }
    }

    /**
     * Choose the Arduino ourselves: the device that worked last time, else a
     * paired device whose name looks like a serial module, else any paired
     * device that isn't obviously something else (headset, watch, car...).
     * Candidates that fail are skipped until every one has been tried once.
     * Returns true when [desiredDevice] was set.
     */
    @SuppressLint("MissingPermission")
    private fun autoPickLocked(): Boolean {
        if (!hasConnectPermission(context)) return false
        val bonded = bondedDevices().takeIf { it.isNotEmpty() } ?: return false
        val remembered = prefs.getString(KEY_LAST_DEVICE, null)
        val ordered = bonded.sortedBy {
            when {
                it.address == remembered -> 0
                looksLikeSerialModule(it) -> 1
                else -> 2
            }
        }.filter { it.address == remembered || looksLikeSerialModule(it) || isPlausible(it) }

        val pick = ordered.firstOrNull { it.address !in autoTried }
            ?: run {
                // Everything failed once — start the rotation over.
                autoTried.clear()
                ordered.firstOrNull()
            }
            ?: return false
        desiredDevice = pick
        autoPicked = true
        _autoConnecting.value = true
        return true
    }

    @SuppressLint("MissingPermission")
    private fun deviceName(device: BluetoothDevice): String = try {
        device.name.orEmpty()
    } catch (_: SecurityException) {
        ""
    }

    private fun looksLikeSerialModule(device: BluetoothDevice): Boolean {
        val name = deviceName(device).lowercase(Locale.US)
        return name.isNotEmpty() && SERIAL_NAME_HINTS.any { it in name }
    }

    /** Not a headset/phone/watch/car — i.e. it could plausibly be a serial module. */
    @SuppressLint("MissingPermission")
    private fun isPlausible(device: BluetoothDevice): Boolean {
        val major = try {
            device.bluetoothClass?.majorDeviceClass
        } catch (_: SecurityException) {
            null
        } ?: return true // unknown class: give it a chance
        return major == BluetoothClass.Device.Major.UNCATEGORIZED ||
            major == BluetoothClass.Device.Major.MISC
    }

    private fun closeLocked() {
        val reader = readerJob
        readerJob = null
        reader?.cancel()
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
        private const val TAG = "qhackGPS"

        /** Well-known Serial Port Profile UUID — what HC-05/HC-06 modules speak. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val RETRY_INTERVAL_MS = 4000L

        private const val PREFS = "qhackgps_bt"
        private const val KEY_LAST_DEVICE = "last_device_address"
        private const val KEY_LAST_METHOD = "last_connect_method"

        private const val METHOD_SPP = "spp"
        private const val METHOD_INSECURE = "insecure"
        private const val METHOD_CHANNEL1 = "channel1"

        /** Name fragments of the usual Arduino-side serial modules. */
        private val SERIAL_NAME_HINTS = listOf(
            "hc-05", "hc05", "hc-06", "hc06", "arduino", "qhack", "esp32", "esp-32",
            "jdy", "bt04", "bt-04", "bt05", "mlt-bt", "linvor", "rnbt", "spp",
            "dsd tech", "bluno", "uno", "nano", "mega", "serial", "cane",
        )

        /** BLUETOOTH_CONNECT is only a runtime permission on Android 12+. */
        fun hasConnectPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
    }
}
