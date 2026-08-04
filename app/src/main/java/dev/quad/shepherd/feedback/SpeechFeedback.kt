package dev.quad.shepherd.feedback

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log

/**
 * Spoken guidance via Android TTS, with rate limiting so the user isn't
 * flooded: identical messages are deduplicated, non-urgent messages respect
 * a minimum interval, urgent (danger) messages interrupt whatever is playing.
 */
class SpeechFeedback(context: Context) {

    companion object {
        private const val TAG = "SpeechFeedback"
        private const val MIN_INTERVAL_MS = 2500L
    }

    private var ready = false
    private var lastMessage: String? = null
    private var lastSpokenAt = 0L

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.setSpeechRate(1.15f)
        } else {
            Log.e(TAG, "TTS init failed: $status")
        }
    }

    fun announce(message: String, urgent: Boolean = false) {
        if (!ready) return
        val now = SystemClock.elapsedRealtime()
        if (!urgent) {
            if (now - lastSpokenAt < MIN_INTERVAL_MS) return
            if (message == lastMessage && now - lastSpokenAt < MIN_INTERVAL_MS * 3) return
        }
        lastMessage = message
        lastSpokenAt = now
        val queueMode = if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(message, queueMode, null, "shepherd-${now}")
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
