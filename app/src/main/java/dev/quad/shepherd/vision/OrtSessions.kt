package dev.quad.shepherd.vision

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log

/**
 * Session factory shared by both engines, encoding hard-won lessons:
 *
 * 1. When QNN fails to claim graph nodes, ONNX Runtime silently runs the
 *    whole session on CPU — a session "created with QNN" proves nothing.
 *    Strict tiers set `session.disable_cpu_ep_fallback`, which makes
 *    session creation FAIL unless the entire graph compiled for that
 *    accelerator; only those earn the "NPU"/"GPU" labels.
 *
 * 2. On this S25 Ultra the HTP (NPU) device refuses to initialize
 *    (QNN_DEVICE_ERROR_INVALID_CONFIG) even with the SoC spelled out
 *    (soc_model=69 / htp_arch=79, from the AI Hub catalog) — cause still
 *    unknown. The GPU tier exists because QNN's Adreno backend does not
 *    need the failing HTP device config at all.
 *
 * Tier order: NPU (full) -> GPU (full) -> NPU/CPU mixed -> CPU.
 */
object OrtSessions {

    class Created(val session: OrtSession, val providerLabel: String)

    private val HTP_OPTIONS = mapOf(
        "backend_path" to "libQnnHtp.so",
        "htp_performance_mode" to "burst",
        "enable_htp_fp16_precision" to "1",
        "soc_model" to "69", // SM8750 (Snapdragon 8 Elite, incl. -AC for Galaxy)
        "htp_arch" to "79",  // Hexagon v79
    )

    private val GPU_OPTIONS = mapOf(
        "backend_path" to "libQnnGpu.so",
    )

    fun create(env: OrtEnvironment, modelBytes: ByteArray, tag: String): Created? {
        attempt(env, modelBytes, HTP_OPTIONS, strict = true, tag, "full-NPU")
            ?.let { return Created(it, "NPU") }
        attempt(env, modelBytes, GPU_OPTIONS, strict = true, tag, "full-GPU")
            ?.let { return Created(it, "GPU") }
        attempt(env, modelBytes, HTP_OPTIONS, strict = false, tag, "mixed-NPU")
            ?.let { return Created(it, "NPU/CPU mixed") }
        return try {
            Created(env.createSession(modelBytes, OrtSession.SessionOptions()), "CPU")
        } catch (e: Exception) {
            Log.e(tag, "CPU session creation failed", e)
            null
        }
    }

    private fun attempt(
        env: OrtEnvironment,
        modelBytes: ByteArray,
        qnnOptions: Map<String, String>,
        strict: Boolean,
        tag: String,
        label: String,
    ): OrtSession? = try {
        val opts = OrtSession.SessionOptions()
        if (strict) opts.addConfigEntry("session.disable_cpu_ep_fallback", "1")
        opts.addQnn(qnnOptions)
        env.createSession(modelBytes, opts)
    } catch (e: Exception) {
        Log.w(tag, "$label session rejected: ${e.message?.take(200)}")
        null
    }
}
