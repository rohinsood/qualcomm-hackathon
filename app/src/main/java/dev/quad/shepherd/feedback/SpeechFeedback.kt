package dev.quad.shepherd.feedback

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Collections

/**
 * Thin TTS executor — one voice, two registers, and two kinds of utterance:
 * guidance alerts (short, may interrupt) and companion chat sentences
 * (queued, conversational). All pacing/interruption decisions live in
 * [dev.quad.shepherd.guidance.AnnouncementPolicy] and MainActivity's
 * arbitration; the one guard kept here is a backstop against restarting an
 * in-flight alert with the exact same text.
 */
class SpeechFeedback(context: Context) {

    companion object {
        private const val TAG = "SpeechFeedback"
        private const val SAME_TEXT_GUARD_MS = 1200L
        private const val NORMAL_RATE = 1.1f
        private const val URGENT_RATE = 1.3f
        private const val CHAT_PREFIX = "chat-"
    }

    private var ready = false
    private var lastText: String? = null
    private var lastAt = 0L
    private var counter = 0L

    /** Chat utterance ids that are speaking or queued (TTS-thread callbacks). */
    private val activeChat: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) {
                Log.e(TAG, "TTS init failed: $status")
            } else {
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let(activeChat::remove)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let(activeChat::remove)
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        utteranceId?.let(activeChat::remove)
                    }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        utteranceId?.let(activeChat::remove)
                    }
                })
            }
        }
    }

    /** True while any companion-chat sentence is speaking or queued. */
    val chatActive: Boolean get() = activeChat.isNotEmpty()

    /**
     * Guidance alert.
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
        if (interrupt) activeChat.clear() // flushed chat also gets onStop; belt and braces
        tts.setSpeechRate(if (urgent) URGENT_RATE else NORMAL_RATE)
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, mode, null, "alert-${counter++}")
    }

    /** Companion chat sentence: queued, conversational register, no dedup guard. */
    fun announceChat(text: String) {
        if (!ready) return
        tts.setSpeechRate(NORMAL_RATE)
        val id = CHAT_PREFIX + counter++
        activeChat.add(id)
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    /** Hard stop everything — the friend goes quiet to listen. */
    fun stopAll() {
        if (!ready) return
        activeChat.clear()
        lastText = null
        tts.stop()
    }

    fun shutdown() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }
}
