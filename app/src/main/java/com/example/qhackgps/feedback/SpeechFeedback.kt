package com.example.qhackgps.feedback

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Voice guidance front-end: the Android system TTS with two registers
 * (normal / urgent), a same-text guard so repeated state cannot stammer,
 * and interrupt semantics for safety lines ("Stop. Obstacle ahead") that
 * must not wait in the queue behind routine turn prompts. Companion chat
 * sentences go through [announceChat] — queued, no dedup guard, so a
 * streamed reply is spoken in order and repeated phrasings survive.
 *
 * Trimmed from the Shepherd app's SpeechFeedback (v3 branch) — same
 * announce contract, without the neural-voice stack.
 */
class SpeechFeedback(context: Context) {

    companion object {
        private const val TAG = "SpeechFeedback"
        private const val SAME_TEXT_GUARD_MS = 2000L
        private const val NORMAL_RATE = 1.05f
        private const val URGENT_RATE = 1.25f
    }

    @Volatile private var ready = false
    private var lastText: String? = null
    private var lastAt = 0L
    private var counter = 0L

    private lateinit var tts: TextToSpeech

    init {
        // The init callback is posted asynchronously, after the constructor
        // returns, so referencing `tts` inside it is safe (the v3 lineage
        // hit the self-referencing-initializer bug once already).
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                if (tts.setLanguage(Locale.US) < TextToSpeech.LANG_AVAILABLE) {
                    Log.w(TAG, "US English voice unavailable; using device default")
                }
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    /**
     * Speak one line.
     * @param interrupt cut off whatever is being spoken (safety lines).
     * @param urgent faster, clipped register.
     */
    fun announce(text: String, interrupt: Boolean = false, urgent: Boolean = false) {
        if (!ready) return
        val now = SystemClock.elapsedRealtime()
        if (text == lastText && now - lastAt < SAME_TEXT_GUARD_MS) return
        lastText = text
        lastAt = now
        tts.setSpeechRate(if (urgent) URGENT_RATE else NORMAL_RATE)
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, mode, null, "qg-${counter++}")
    }

    /** Companion chat sentence: queued behind whatever is speaking, no dedup guard. */
    fun announceChat(text: String) {
        if (!ready) return
        tts.setSpeechRate(NORMAL_RATE)
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "chat-${counter++}")
    }

    /** Hard stop everything — the companion goes quiet to listen. */
    fun stopAll() {
        lastText = null
        if (ready) runCatching { tts.stop() }
    }

    fun shutdown() {
        runCatching {
            tts.stop()
            tts.shutdown()
        }
    }
}
