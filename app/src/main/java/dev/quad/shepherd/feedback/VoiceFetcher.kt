package dev.quad.shepherd.feedback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.URL

/**
 * Self-provisioning for the neural voice: when no voice model is on disk
 * (fresh phone), this downloads sherpa's Supertonic 3 int8 package
 * (~130 MB, unmetered networks only) and unpacks it into the external
 * files dir, then asks [SpeechFeedback] to switch over from the system
 * voice mid-session. Phones that already carry Kokoro keep it unless
 * Supertonic is added (Supertonic wins when both exist — it synthesizes
 * roughly 10x faster).
 */
object VoiceFetcher {

    private const val TAG = "VoiceFetcher"
    private const val PACKAGE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
            "sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"

    @Volatile private var running = false

    /** No-op when a voice already exists on disk or a fetch is in flight. */
    fun ensureAsync(context: Context, scope: CoroutineScope, onReady: () -> Unit) {
        if (!dev.quad.shepherd.Loadout.NEURAL_TTS) return // no 130 MB download
        val base = context.getExternalFilesDir(null) ?: return
        val supertonic = File(base, "models/supertonic")
        if (File(supertonic, "vocoder.int8.onnx").isFile) return
        if (File(base, "models/kokoro/model.int8.onnx").isFile) return
        if (running) return
        if (!onUnmetered(context)) {
            Log.i(TAG, "no voice on disk but no unmetered network — will retry next launch")
            return
        }
        running = true
        scope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "downloading Supertonic 3 voice (~130 MB)…")
                val tmp = File(context.cacheDir, "supertonic.tar.bz2")
                URL(PACKAGE_URL).openStream().use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                Log.i(TAG, "download done (${tmp.length() / 1_000_000} MB), unpacking…")
                supertonic.mkdirs()
                BZip2CompressorInputStream(tmp.inputStream().buffered()).use { bz ->
                    TarArchiveInputStream(bz).use { tar ->
                        var entry = tar.nextEntry
                        while (entry != null) {
                            // Strip the top-level package directory
                            val rel = entry.name.substringAfter('/', "")
                            if (rel.isNotEmpty() && !rel.contains("..")) {
                                val out = File(supertonic, rel)
                                if (entry.isDirectory) {
                                    out.mkdirs()
                                } else {
                                    out.parentFile?.mkdirs()
                                    out.outputStream().use { tar.copyTo(it) }
                                }
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
                tmp.delete()
                Log.i(TAG, "supertonic unpacked to $supertonic")
                onReady()
            } catch (e: Exception) {
                Log.w(TAG, "voice fetch failed", e)
            } finally {
                running = false
            }
        }
    }

    private fun onUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
