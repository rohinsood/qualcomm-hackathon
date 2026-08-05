package dev.quad.shepherd.vision

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log

/**
 * Session factory shared by both engines, encoding two hard-won lessons:
 *
 * 1. QNN's SoC auto-detection fails on the S25 Ultra's overclocked
 *    "Snapdragon 8 Elite for Galaxy" (SM8750-AC) with
 *    QNN_DEVICE_ERROR_INVALID_CONFIG, so the SoC (69) and Hexagon
 *    generation (v79) are passed explicitly.
 *
 * 2. When QNN fails to claim graph nodes, ONNX Runtime silently runs the
 *    whole session on CPU — a session "created with QNN" proves nothing.
 *    Tier 1 therefore sets `session.disable_cpu_ep_fallback`, which makes
 *    session creation FAIL unless the entire graph compiled for the NPU;
 *    only that earns the "NPU" label. Tier 2 allows mixed placement,
 *    tier 3 is plain CPU.
 */
object OrtSessions {

    class Created(val session: OrtSession, val providerLabel: String)

    private val QNN_OPTIONS = mapOf(
        "backend_path" to "libQnnHtp.so",
        "htp_performance_mode" to "burst",
        "enable_htp_fp16_precision" to "1",
        "soc_model" to "69", // SM8750 (Snapdragon 8 Elite, incl. -AC for Galaxy)
        "htp_arch" to "79",  // Hexagon v79
    )

    fun create(env: OrtEnvironment, modelBytes: ByteArray, tag: String): Created? {
        // Tier 1: the whole graph must run on the Hexagon NPU
        try {
            val opts = OrtSession.SessionOptions()
            opts.addConfigEntry("session.disable_cpu_ep_fallback", "1")
            opts.addQnn(QNN_OPTIONS)
            return Created(env.createSession(modelBytes, opts), "NPU")
        } catch (e: Exception) {
            Log.w(tag, "Full-NPU session rejected: ${e.message?.take(200)}")
        }
        // Tier 2: NPU for supported ops, CPU for the rest (may be mostly CPU
        // if the QNN device itself failed — watch logcat for SetupBackend)
        try {
            val opts = OrtSession.SessionOptions()
            opts.addQnn(QNN_OPTIONS)
            return Created(env.createSession(modelBytes, opts), "NPU/CPU mixed")
        } catch (e: Exception) {
            Log.w(tag, "Mixed-NPU session rejected: ${e.message?.take(200)}")
        }
        // Tier 3: plain CPU
        return try {
            Created(env.createSession(modelBytes, OrtSession.SessionOptions()), "CPU")
        } catch (e: Exception) {
            Log.e(tag, "CPU session creation failed", e)
            null
        }
    }
}
