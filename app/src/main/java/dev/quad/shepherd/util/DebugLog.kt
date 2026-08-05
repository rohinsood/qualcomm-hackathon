package dev.quad.shepherd.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-screen debug console feed: every notable decision (heard transcript,
 * intent routing, SLM timings, TTS synth speed, navigation events,
 * guidance changes) lands here as one line. MainActivity renders the tail
 * when the Log toggle is on; everything also goes to logcat under the
 * "ShepherdDebug" tag for adb sessions.
 */
object DebugLog {

    private const val MAX_ENTRIES = 80

    private val entries = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile private var listener: (() -> Unit)? = null

    fun d(tag: String, msg: String) {
        synchronized(entries) {
            entries.addLast("${clock.format(Date())} ${tag.padEnd(4)} $msg")
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        Log.i("ShepherdDebug", "[$tag] $msg")
        listener?.invoke()
    }

    fun snapshot(lines: Int = 16): String = synchronized(entries) {
        entries.takeLast(lines).joinToString("\n")
    }

    fun setListener(l: (() -> Unit)?) {
        listener = l
    }
}
