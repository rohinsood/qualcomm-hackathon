package dev.quad.shepherd.llm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import dev.quad.shepherd.util.DebugLog

/**
 * Phase-1 conversational companion: a persistent Qwen3.5-2B session on the
 * Hexagon NPU (step-0 winner: 12 tok/s, 186 ms first token — and it leaves
 * the Adreno GPU to the vision models).
 *
 * Each user turn is grounded with a scene digest from
 * [dev.quad.shepherd.guidance.SceneBlackboard], and the reply streams out
 * sentence-by-sentence so the voice starts speaking after the first
 * sentence, not after the whole reply. A safety alert can cut a reply off
 * at any time via [requestStop]; the interruption is replayed to the model
 * on the next turn so the companion can pick the thread back up — like a
 * friend who paused mid-sentence to say "car on your right".
 */
class GenieChat {

    companion object {
        private const val TAG = "GenieChat"
        private const val COMPUTE_UNIT = "npu"
        private const val N_CTX = 4096

        /** ~8 s of speech at the NPU's 12 tok/s — a hard cap on rambling. */
        private const val MAX_REPLY_TOKENS = 96

        /** Verbatim turns kept; older ones simply fall out of the window.
         *  Kept tight — every history token is prefill latency each turn. */
        private const val MAX_HISTORY_MESSAGES = 6

        private val SYSTEM_PROMPT = """
            You are Shepherd, a friendly walking companion for a blind or low-vision pedestrian, speaking out loud through their phone. You are a normal, knowledgeable assistant: general questions — facts, capitals, math, advice, small talk — get a direct answer from your own knowledge, exactly like any assistant.

            You also see through the phone camera. Each user message begins with [Scene right now: ...] — live facts about the CURRENT surroundings. Those facts matter ONLY when the user asks about their surroundings (what's ahead, describe the scene, is there a door, read that sign). For surroundings questions, stick to the scene facts and say plainly when the camera can't tell. For every other question the camera is irrelevant — never refuse because something isn't visible.

            Style: relaxed spoken language, like a friend on a walk. One to three short sentences, no lists, no markdown, no emoji — everything is read aloud. Keep distances and directions when they matter ("about two meters, slightly left").

            You cannot start or stop navigation yourself, and you must never claim a route was found or not found. If the user wants to be guided somewhere, tell them to say: take me to, followed by the address or place name. To end guidance: stop navigation.
        """.trimIndent()
    }

    enum class Status { IDLE, LOADING, READY, FAILED }

    @Volatile var status = Status.IDLE
        private set

    @Volatile var failure: String? = null
        private set

    /** True while a reply is being generated. */
    @Volatile var busy = false
        private set

    val ready: Boolean get() = status == Status.READY

    private var llm: LlmWrapper? = null
    private val history = ArrayDeque<Pair<String, String>>() // role to content
    @Volatile private var cancelRequested = false
    @Volatile private var pendingNote: String? = null

    /** One-time model load; call on Dispatchers.IO. Callable again after FAILED. */
    suspend fun warmUp(context: Context, onStatus: (String) -> Unit): Boolean {
        if (status == Status.READY || status == Status.LOADING) return ready
        status = Status.LOADING
        failure = null
        return try {
            GenieRuntime.ensureInit(context)?.let { throw RuntimeException("GenieX init: $it") }
            GenieRuntime.ensureModel(context, onStatus)?.let {
                throw RuntimeException(it.message ?: "model download failed", it)
            }
            val paths = GenieRuntime.modelPaths() ?: throw RuntimeException("model paths missing")
            onStatus("Loading ${paths.model_name.substringAfterLast('/')} on $COMPUTE_UNIT…")
            llm = LlmWrapper.builder()
                .llmCreateInput(
                    LlmCreateInput(
                        model_name = paths.model_name,
                        model_path = paths.model_path,
                        tokenizer_path = null,
                        config = ModelConfig(nCtx = N_CTX),
                        runtime_id = "llama_cpp",
                        compute_unit = COMPUTE_UNIT,
                    )
                )
                .build()
                .getOrThrow()
            status = Status.READY
            Log.i(TAG, "companion ready on $COMPUTE_UNIT")
            true
        } catch (e: Exception) {
            Log.e(TAG, "warmUp failed", e)
            failure = (e.message ?: e.javaClass.simpleName).take(160)
            status = Status.FAILED
            false
        }
    }

    /**
     * One conversation turn; call on Dispatchers.IO. Completed sentences
     * stream through [onSentence] as they finish. Returns the full reply
     * (possibly cut short by [requestStop]), or null when generation failed.
     */
    suspend fun ask(
        userText: String,
        sceneDigest: String,
        onSentence: (String) -> Unit,
    ): String? {
        val llm = this.llm ?: return null
        if (busy) return null
        busy = true
        cancelRequested = false
        var userAdded = false
        try {
            val note = pendingNote
            pendingNote = null
            val content = buildString {
                append("[Scene right now: ").append(sceneDigest).append(']')
                if (note != null) {
                    append("\n[Your previous reply was cut off by the safety alert \"")
                    append(note)
                    append("\". Pick the thread back up naturally.]")
                }
                append('\n')
                append(userText.trim())
                // Qwen3-family soft switch: belt and braces alongside
                // enableThinking=false in the template call
                append(" /no_think")
            }
            history.addLast("user" to content)
            userAdded = true
            trimHistory()

            val messages = ArrayList<ChatMessage>(history.size + 1)
            messages.add(ChatMessage("system", SYSTEM_PROMPT))
            for ((role, text) in history) messages.add(ChatMessage(role, text))

            // enableThinking=false: Qwen3.5's hybrid reasoning would sit in
            // silence for seconds before the first spoken word
            val prompt = llm.applyChatTemplate(messages.toTypedArray(), null, false, true)
                .getOrThrow()
                .formattedText

            val filter = ThinkFilter()
            val pending = StringBuilder()
            val full = StringBuilder()
            var stopIssued = false
            var firstTokenMs = -1L
            val t0 = SystemClock.elapsedRealtime()

            llm.generateStreamFlow(prompt, GenerationConfig(maxTokens = MAX_REPLY_TOKENS))
                .collect { r ->
                    when (r) {
                        is LlmStreamResult.Token -> {
                            if (cancelRequested) {
                                if (!stopIssued) {
                                    stopIssued = true
                                    runCatching { llm.stopStream() }
                                }
                            } else {
                                if (firstTokenMs < 0) {
                                    firstTokenMs = SystemClock.elapsedRealtime() - t0
                                }
                                val clean = filter.feed(r.text)
                                if (clean.isNotEmpty()) {
                                    full.append(clean)
                                    pending.append(clean)
                                    drainSentences(pending, onSentence)
                                }
                            }
                        }
                        is LlmStreamResult.Error ->
                            throw RuntimeException("stream error", r.throwable)
                        else -> {}
                    }
                }

            if (!cancelRequested) {
                val tail = filter.finish()
                full.append(tail)
                pending.append(tail)
                val rest = pending.toString().trim()
                if (rest.length > 1) onSentence(rest)
            }
            val reply = full.toString().trim()
            val totalMs = SystemClock.elapsedRealtime() - t0
            Log.i(
                TAG,
                "reply ${reply.length} chars in $totalMs ms " +
                    "(first token $firstTokenMs ms, cancelled=$cancelRequested)",
            )
            DebugLog.d(
                "SLM",
                "${reply.length}ch first=${firstTokenMs}ms total=${totalMs}ms" +
                    if (cancelRequested) " (cut)" else "",
            )
            if (reply.isNotEmpty()) history.addLast("assistant" to reply)
            return reply
        } catch (e: Exception) {
            Log.e(TAG, "ask failed", e)
            if (userAdded) history.removeLastOrNull()
            return null
        } finally {
            busy = false
        }
    }

    /**
     * Stop an in-flight reply (safety alert or the user pressing talk).
     * With [alertText] set, the model is told about the interruption on its
     * next turn.
     */
    fun requestStop(alertText: String? = null) {
        if (alertText != null) pendingNote = alertText
        if (busy) cancelRequested = true
    }

    fun close() {
        requestStop(null)
        if (!busy) {
            runCatching { llm?.close() }
            llm = null
            status = Status.IDLE
        }
    }

    private fun trimHistory() {
        while (history.size > MAX_HISTORY_MESSAGES) history.removeFirst()
        // Chat templates want the transcript to start on a user turn
        while (history.isNotEmpty() && history.first().first != "user") history.removeFirst()
    }
}

/**
 * Streaming filter that removes `<think>…</think>` blocks even when the
 * tags arrive split across tokens. Belt-and-braces: thinking is disabled at
 * the template level, but a stray reasoning block must never be spoken.
 */
internal class ThinkFilter {

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }

    private val buf = StringBuilder()
    private var inThink = false

    /** Feed a new chunk; returns the text that is now safe to emit. */
    fun feed(chunk: String): String {
        buf.append(chunk)
        val out = StringBuilder()
        while (true) {
            if (inThink) {
                val close = buf.indexOf(CLOSE)
                if (close < 0) {
                    // Drop hidden reasoning, keep only a possible partial tag
                    val keep = partialSuffix(CLOSE)
                    buf.delete(0, buf.length - keep)
                    return out.toString()
                }
                buf.delete(0, close + CLOSE.length)
                inThink = false
            } else {
                val open = buf.indexOf(OPEN)
                if (open < 0) {
                    val keep = partialSuffix(OPEN)
                    out.append(buf, 0, buf.length - keep)
                    buf.delete(0, buf.length - keep)
                    return out.toString()
                }
                out.append(buf, 0, open)
                buf.delete(0, open + OPEN.length)
                inThink = true
            }
        }
    }

    /** Flush at end-of-stream; an unclosed think block is dropped. */
    fun finish(): String {
        val rest = if (inThink) "" else buf.toString()
        buf.setLength(0)
        inThink = false
        return rest
    }

    /** Length of the longest strict prefix of [tag] that ends the buffer. */
    private fun partialSuffix(tag: String): Int {
        for (k in minOf(tag.length - 1, buf.length) downTo 1) {
            var match = true
            for (i in 0 until k) {
                if (buf[buf.length - k + i] != tag[i]) {
                    match = false
                    break
                }
            }
            if (match) return k
        }
        return 0
    }
}

/**
 * Emit every completed sentence in [pending] through [emit] and leave the
 * unfinished remainder buffered. A sentence ends at `.`, `!` or `?`
 * followed by whitespace (so decimals like "2.5" never split), or at a
 * newline; the end of the stream is flushed by the caller.
 */
internal fun drainSentences(pending: StringBuilder, emit: (String) -> Unit) {
    var start = 0
    var i = 0
    while (i < pending.length) {
        val ch = pending[i]
        val isBreak = ch == '\n' ||
            ((ch == '.' || ch == '!' || ch == '?') &&
                i + 1 < pending.length && pending[i + 1].isWhitespace())
        if (isBreak) {
            val sentence = pending.substring(start, i + 1).trim()
            if (sentence.length > 1) emit(sentence)
            i++
            while (i < pending.length && pending[i].isWhitespace()) i++
            start = i
        } else {
            i++
        }
    }
    pending.delete(0, start)
}
