package dev.quad.shepherd.feedback

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Collections

/**
 * Speech front-end — one voice, two registers (normal / urgent), and two
 * kinds of utterance: system/alert lines (may interrupt) and companion
 * chat sentences (queued, conversational). Speaks with the neural Kokoro
 * voice ([NeuralTts]) when its model files are on the phone, falling back
 * to the Android system engine otherwise. The one guard kept here is a
 * backstop against restarting an in-flight line with the exact same text.
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

    @Volatile private var neural: NeuralTts? = null

    /** Fallback-engine chat utterance ids in flight (TTS-thread callbacks). */
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
        // The neural voice loads ~100 MB of model — do it off the main thread
        reloadNeural(context)
    }

    /** (Re)try loading the Kokoro voice, e.g. after KokoroFetcher finishes. */
    fun reloadNeural(context: Context) {
        if (neural != null) return
        Thread({
            neural = NeuralTts.tryCreate(context.getExternalFilesDir(null))
        }, "kokoro-init").apply { isDaemon = true }.start()
    }

    /** True while any companion-chat sentence is speaking or queued. */
    val chatActive: Boolean
        get() = neural?.chatActive ?: activeChat.isNotEmpty()

    /**
     * System/alert line.
     * @param interrupt cut off and flush whatever is being spoken.
     * @param urgent use the clipped, faster register.
     */
    fun announce(text: String, interrupt: Boolean = false, urgent: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (text == lastText && now - lastAt < SAME_TEXT_GUARD_MS) return
        lastText = text
        lastAt = now

        neural?.let {
            it.enqueue(text, urgent = urgent, chat = false, interrupt = interrupt)
            return
        }
        if (!ready) return
        if (interrupt) activeChat.clear()
        tts.setSpeechRate(if (urgent) URGENT_RATE else NORMAL_RATE)
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, mode, null, "alert-${counter++}")
    }

    /** Companion chat sentence: queued, conversational register, no dedup guard. */
    fun announceChat(text: String) {
        neural?.let {
            it.enqueue(text, urgent = false, chat = true, interrupt = false)
            return
        }
        if (!ready) return
        tts.setSpeechRate(NORMAL_RATE)
        val id = CHAT_PREFIX + counter++
        activeChat.add(id)
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    /** Hard stop everything — the friend goes quiet to listen. */
    fun stopAll() {
        lastText = null
        neural?.stopAll()
        if (ready) {
            activeChat.clear()
            tts.stop()
        }
    }

    fun shutdown() {
        neural?.close()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }
}
