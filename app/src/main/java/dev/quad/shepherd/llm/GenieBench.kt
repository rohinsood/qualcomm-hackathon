package dev.quad.shepherd.llm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelPullInput

/**
 * Step-0 bake-off harness for the conversational companion: pulls the
 * candidate SLM (Qwen3.5-2B, Apache-2.0, GGUF Q4_0 — the precision with the
 * best Hexagon NPU support) through GenieX's own downloader, then runs a
 * short generation on each compute unit (NPU / GPU / CPU) and measures
 * first-token latency and decode tokens/sec.
 *
 * This is deliberately throwaway-shaped: phase 1 replaces it with a real
 * conversation wrapper, but the init/pull/create plumbing carries over.
 */
object GenieBench {

    private const val TAG = "GenieBench"
    const val MODEL = "unsloth/Qwen3.5-2B-GGUF"
    private const val PRECISION = "Q4_0"
    private val UNITS = listOf("npu", "gpu", "cpu")

    data class UnitResult(
        val unit: String,
        val ok: Boolean,
        val firstTokenMs: Long,
        val tokensPerSec: Float,
        val note: String,
    )

    @Volatile private var initialized = false

    private fun ensureInit(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            GenieXSdk.getInstance().init(context.applicationContext)
            runCatching { GenieXSdk.getInstance().registerPlugin(GenieXSdk.PLUGIN_ID_LLAMA_CPP) }
                .onFailure { Log.w(TAG, "register llama_cpp failed: ${it.message}") }
            runCatching { GenieXSdk.getInstance().registerPlugin(GenieXSdk.PLUGIN_ID_QAIRT) }
                .onFailure { Log.w(TAG, "register qairt failed: ${it.message}") }
            initialized = true
        }
    }

    /** Downloads the model through GenieX if not already present (~1.2 GB, Wi-Fi). */
    private suspend fun ensureModel(context: Context, onStatus: (String) -> Unit): Throwable? {
        ensureInit(context)
        val present = runCatching { ModelManagerWrapper.list() }
            .getOrNull()?.any { it.contains(MODEL) } == true
        if (present) return null

        onStatus("Downloading ${MODEL.substringAfterLast('/')} (~1.2 GB)…")
        var failure: Throwable? = null
        try {
            ModelManagerWrapper.pullFlow(
                ModelPullInput(
                    model_name = MODEL,
                    precision = PRECISION,
                    hub = HubSource.HUGGINGFACE,
                )
            ).collect { event ->
                when (event) {
                    is ModelManagerWrapper.PullEvent.Error ->
                        failure = RuntimeException(event.toString().take(200))
                    else -> onStatus("Downloading model… (${event.javaClass.simpleName})")
                }
            }
        } catch (e: Exception) {
            failure = e
        }
        return failure
    }

    private suspend fun bench(context: Context, computeUnit: String): UnitResult {
        ensureInit(context)
        return try {
            val paths = runCatching { ModelManagerWrapper.getPaths(MODEL) }.getOrNull()
                ?: return UnitResult(computeUnit, false, 0, 0f, "model paths missing")

            val llm = LlmWrapper.builder()
                .llmCreateInput(
                    LlmCreateInput(
                        model_name = paths.model_name,
                        model_path = paths.model_path,
                        tokenizer_path = null,
                        config = ModelConfig(nCtx = 2048),
                        runtime_id = "llama_cpp",
                        compute_unit = computeUnit,
                    )
                )
                .build()
                .getOrThrow()

            try {
                val chat = arrayOf(
                    ChatMessage(
                        "user",
                        "In one short sentence, greet the pedestrian you are guiding.",
                    )
                )
                val templated = llm.applyChatTemplate(chat, null, false).getOrThrow()

                var tokens = 0
                var firstTokenMs = -1L
                var streamError: String? = null
                val t0 = SystemClock.elapsedRealtime()
                llm.generateStreamFlow(
                    templated.formattedText,
                    GenerationConfig(maxTokens = 48),
                ).collect { r ->
                    when (r) {
                        is LlmStreamResult.Token -> {
                            if (firstTokenMs < 0) firstTokenMs = SystemClock.elapsedRealtime() - t0
                            tokens++
                        }
                        is LlmStreamResult.Error -> streamError = r.toString().take(160)
                        else -> {}
                    }
                }
                val total = SystemClock.elapsedRealtime() - t0
                val decodeMs = (total - firstTokenMs.coerceAtLeast(0)).coerceAtLeast(1)
                val tps = if (tokens > 1) (tokens - 1) * 1000f / decodeMs else 0f
                if (streamError != null) {
                    UnitResult(computeUnit, false, firstTokenMs, tps, streamError!!)
                } else {
                    UnitResult(computeUnit, true, firstTokenMs, tps, "$tokens tokens in ${total} ms")
                }
            } finally {
                runCatching { llm.close() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "bench on $computeUnit failed", e)
            UnitResult(computeUnit, false, 0, 0f, (e.message ?: e.javaClass.simpleName).take(160))
        }
    }

    /** Full suite: ensure model, then bench NPU, GPU, CPU in turn. */
    suspend fun runAll(context: Context, onStatus: (String) -> Unit): List<UnitResult> {
        ensureModel(context, onStatus)?.let { err ->
            return listOf(
                UnitResult("download", false, 0, 0f, (err.message ?: "download failed").take(160))
            )
        }
        return UNITS.map { unit ->
            onStatus("Benchmarking $unit…")
            bench(context, unit).also {
                Log.i(TAG, "$unit -> ok=${it.ok} ${it.tokensPerSec} tok/s first=${it.firstTokenMs}ms ${it.note}")
            }
        }
    }
}
