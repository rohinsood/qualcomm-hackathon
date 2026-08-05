package dev.quad.shepherd.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Push-to-talk speech capture on the phone's ON-DEVICE recognizer (API
 * 31+): start on button press, finalize on release, one final transcript
 * per hold. Fully offline — audio never leaves the phone, matching the
 * app-wide offline-only decision.
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
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    onError("Microphone permission needed.")
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // A wedged session; rebuild the recognizer for next time
                    recognizer?.destroy()
                    recognizer = null
                    onNoSpeech()
                }
                else -> onError("Speech recognition error $error.")
            }
        }
    }

    fun start() {
        if (listening) return
        if (!supported) {
            onError("On-device speech recognition is not available on this phone.")
            return
        }
        val r = recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            // Belt and braces: even the on-device recognizer is told offline
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        listening = true
        r.startListening(intent)
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
