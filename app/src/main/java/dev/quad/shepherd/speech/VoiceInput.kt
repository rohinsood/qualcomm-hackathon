package dev.quad.shepherd.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.Locale

/**
 * Push-to-talk speech capture on the phone's ON-DEVICE recognizer (API
 * 31+): start on button press, finalize on release, one final transcript
 * per hold. Fully offline — audio never leaves the phone, matching the
 * app-wide offline-only decision.
 *
 * The on-device recognizer needs a per-language model pack on disk.
 * [ensureModel] checks for the current locale's pack at startup and kicks
 * off the system download when it is missing — the cause of
 * ERROR_LANGUAGE_UNAVAILABLE (code 13) on an otherwise healthy phone.
 *
 * All methods are main-thread only (a SpeechRecognizer requirement).
 */
class VoiceInput(
    private val context: Context,
    private val onTranscript: (String) -> Unit,
    private val onNoSpeech: () -> Unit,
    private val onError: (String) -> Unit,
) {

    companion object {
        private const val TAG = "VoiceInput"
    }

    private var recognizer: SpeechRecognizer? = null
    private var downloadTriggered = false

    /** True from start() until the recognizer delivers a result or error. */
    var listening = false
        private set

    val supported: Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isEmpty()) onNoSpeech() else onTranscript(text)
        }

        override fun onError(error: Int) {
            listening = false
            Log.w(TAG, "recognizer error $error")
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_CLIENT -> onNoSpeech()
                SpeechRecognizer.ERROR_AUDIO -> onError("Microphone error.")
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    onError("Microphone permission needed.")
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // A wedged session; rebuild the recognizer for next time
                    recognizer?.destroy()
                    recognizer = null
                    onNoSpeech()
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> onError(
                    "Offline speech recognition does not support " +
                        "${Locale.getDefault().displayLanguage} on this phone."
                )
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    val started = triggerDownload()
                    onError(
                        "The offline speech model for ${Locale.getDefault().displayLanguage} " +
                            "is not on this phone yet." +
                            if (started) " I started the download. Try again in a minute or two."
                            else " Check on-device speech settings."
                    )
                }
                else -> onError("Speech recognition failed, code $error.")
            }
        }
    }

    private fun obtainRecognizer(): SpeechRecognizer? {
        if (!supported) return null
        recognizer?.let { return it }
        return SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
    }

    private fun listenIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        // Belt and braces: even the on-device recognizer is told offline
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    /**
     * Startup check: is the current locale's on-device model installed?
     * If not, kick off the system download so push-to-talk works by the
     * time the user needs it. Progress is reported through [onError] so it
     * gets spoken.
     */
    fun ensureModel() {
        if (Build.VERSION.SDK_INT < 33) return
        val r = obtainRecognizer() ?: return
        checkAndDownload(r)
    }

    @RequiresApi(33)
    private fun checkAndDownload(r: SpeechRecognizer) {
        runCatching {
            r.checkRecognitionSupport(
                listenIntent(),
                context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        val tag = Locale.getDefault().toLanguageTag()
                        val lang = tag.substringBefore('-')
                        val installed = recognitionSupport.installedOnDeviceLanguages
                        Log.i(
                            TAG,
                            "on-device ASR for $tag: installed=$installed " +
                                "pending=${recognitionSupport.pendingOnDeviceLanguages} " +
                                "supported=${recognitionSupport.supportedOnDeviceLanguages} " +
                                "online=${recognitionSupport.onlineLanguages}"
                        )
                        val have = installed.any {
                            it.equals(tag, ignoreCase = true) ||
                                it.substringBefore('-').equals(lang, ignoreCase = true)
                        }
                        if (!have && triggerDownload()) {
                            onError(
                                "Downloading the offline speech model for " +
                                    "${Locale.getDefault().displayLanguage}. " +
                                    "Hold to talk starts working once it finishes."
                            )
                        }
                    }

                    override fun onError(error: Int) {
                        Log.w(TAG, "recognition support check failed: $error")
                    }
                },
            )
        }.onFailure { Log.w(TAG, "checkRecognitionSupport threw", it) }
    }

    /** Ask the system to download the on-device pack; true if requested. */
    private fun triggerDownload(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        if (downloadTriggered) return true
        val r = obtainRecognizer() ?: return false
        return runCatching {
            r.triggerModelDownload(listenIntent())
            Log.i(TAG, "triggered model download for ${Locale.getDefault().toLanguageTag()}")
            downloadTriggered = true
            true
        }.getOrDefault(false)
    }

    fun start() {
        if (listening) return
        if (!supported) {
            onError("On-device speech recognition is not available on this phone.")
            return
        }
        val r = obtainRecognizer() ?: return
        listening = true
        r.startListening(listenIntent())
    }

    /** Finalize the utterance; the transcript arrives via the callbacks. */
    fun stop() {
        if (!listening) return
        recognizer?.stopListening()
    }

    fun cancel() {
        listening = false
        recognizer?.cancel()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}
