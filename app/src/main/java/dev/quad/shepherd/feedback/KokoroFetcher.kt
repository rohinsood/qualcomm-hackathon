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
 * Self-provisioning for the neural voice: when the Kokoro model files are
 * missing (fresh phone — previously they had to be pushed over adb), this
 * downloads the sherpa-onnx release package (~160 MB, unmetered networks
 * only) and unpacks it into the external files dir, then asks
 * [SpeechFeedback] to switch over from the system voice mid-session.
 */
object KokoroFetcher {

    private const val TAG = "KokoroFetcher"
    private const val PACKAGE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
            "kokoro-int8-multi-lang-v1_0.tar.bz2"

    @Volatile private var running = false

    /** No-op when the model is already on disk or a fetch is in flight. */
    fun ensureAsync(context: Context, scope: CoroutineScope, onReady: () -> Unit) {
        val dir = File(context.getExternalFilesDir(null), "models/kokoro")
        if (File(dir, "model.int8.onnx").isFile) return
        if (running) return
        if (!onUnmetered(context)) {
            Log.i(TAG, "kokoro missing but no unmetered network — will retry next launch")
            return
        }
        running = true
        scope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "downloading kokoro voice (~160 MB)…")
                val tmp = File(context.cacheDir, "kokoro.tar.bz2")
                URL(PACKAGE_URL).openStream().use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                Log.i(TAG, "download done (${tmp.length() / 1_000_000} MB), unpacking…")
                dir.mkdirs()
                BZip2CompressorInputStream(tmp.inputStream().buffered()).use { bz ->
                    TarArchiveInputStream(bz).use { tar ->
                        var entry = tar.nextEntry
                        while (entry != null) {
                            // Strip the top-level "kokoro-int8-multi-lang-v1_0/"
                            val rel = entry.name.substringAfter('/', "")
                            if (rel.isNotEmpty() && !rel.contains("..")) {
                                val out = File(dir, rel)
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
                Log.i(TAG, "kokoro unpacked to $dir")
                onReady()
            } catch (e: Exception) {
                Log.w(TAG, "kokoro fetch failed", e)
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
