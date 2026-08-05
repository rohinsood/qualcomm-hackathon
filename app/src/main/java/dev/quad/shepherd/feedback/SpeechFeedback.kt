package dev.quad.shepherd.feedback

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log

/**
 * Thin TTS executor — one voice, two registers. All pacing/interruption
 * decisions live in [dev.quad.shepherd.guidance.AnnouncementPolicy]; the one
 * guard kept here is a backstop against restarting an in-flight utterance
 * with the exact same text.
 */
class SpeechFeedback(context: Context) {

    companion object {
        private const val TAG = "SpeechFeedback"
        private const val SAME_TEXT_GUARD_MS = 1200L
        private const val NORMAL_RATE = 1.1f
        private const val URGENT_RATE = 1.3f
    }

    private var ready = false
    private var lastText: String? = null
    private var lastAt = 0L

    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) Log.e(TAG, "TTS init failed: $status")
        }
    }

    /**
     * @param interrupt flush whatever is being spoken (reserved for danger
     *   escalation and explicit user actions).
     * @param urgent use the clipped, faster alert register.
     */
    fun announce(text: String, interrupt: Boolean = false, urgent: Boolean = false) {
        if (!ready) return
        val now = SystemClock.elapsedRealtime()
        if (text == lastText && now - lastAt < SAME_TEXT_GUARD_MS) return
        lastText = text
        lastAt = now
        tts.setSpeechRate(if (urgent) URGENT_RATE else NORMAL_RATE)
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, mode, null, "shepherd-$now")
    }

    fun shutdown() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }
}
