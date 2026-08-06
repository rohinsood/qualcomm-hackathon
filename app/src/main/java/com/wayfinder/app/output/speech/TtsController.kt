package com.wayfinder.app.output.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Thin wrapper around Android [TextToSpeech]. Initialization is asynchronous, so
 * utterances requested before the engine is ready are queued and flushed once it
 * is. [speak] never blocks and never throws.
 */
class TtsController(context: Context) {

    @Volatile private var ready = false
    private val pending = ConcurrentLinkedQueue<Pair<String, Boolean>>() // text, isPriority
    private var currentUtterance: String? = null

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val res = tts.setLanguage(Locale.US)
            ready = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            if (ready) {
                tts.setSpeechRate(1.05f)
                flushPending()
            } else {
                Log.w(TAG, "TTS language unavailable ($res)")
            }
        } else {
            Log.w(TAG, "TTS init failed (status=$status)")
        }
    }

    fun isReady(): Boolean = ready

    /** @param priority if true, flush the current utterance and speak immediately */
    fun speak(text: String, priority: Boolean = false) {
        if (!ready) {
            pending.add(text to priority)
            return
        }
        currentUtterance = text
        val mode = if (priority) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, mode, null, "wayfinder-${System.nanoTime()}")
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() {
        try {
            tts.stop()
            tts.shutdown()
        } catch (_: Throwable) {
        }
    }

    private fun flushPending() {
        while (ready) {
            val (text, priority) = pending.poll() ?: break
            speak(text, priority)
        }
    }

    companion object {
        private const val TAG = "TtsController"
    }
}
