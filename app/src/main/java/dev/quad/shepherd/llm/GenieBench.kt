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

/**
 * Step-0 bake-off harness: runs a short generation with the companion SLM
 * on each compute unit (NPU / GPU / CPU) and measures first-token latency
 * and decode tokens/sec. Verdict on the S25 Ultra: NPU 12.1 tok/s / 186 ms
 * first token — the winner, and it leaves the Adreno GPU to the vision
 * models. [GenieChat] is the production wrapper built on that result; this
 * stays reachable (long-press the status line) for regression checks.
 */
object GenieBench {

    private const val TAG = "GenieBench"
    private val UNITS = listOf("npu", "gpu", "cpu")

    data class UnitResult(
        val unit: String,
        val ok: Boolean,
        val firstTokenMs: Long,
        val tokensPerSec: Float,
        val note: String,
    )

    private suspend fun bench(context: Context, computeUnit: String): UnitResult {
        GenieRuntime.ensureInit(context)?.let {
            return UnitResult(computeUnit, false, 0, 0f, it.take(160))
        }
        return try {
            val paths = GenieRuntime.modelPaths()
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
                val templated = llm.applyChatTemplate(chat, null, false, true).getOrThrow()

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
        GenieRuntime.ensureModel(context, onStatus)?.let { err ->
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
