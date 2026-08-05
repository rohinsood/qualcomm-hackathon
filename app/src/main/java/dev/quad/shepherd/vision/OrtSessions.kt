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
 * 2. QNN's accelerator backends dlopen VENDOR libraries (libcdsprpc.so for
 *    the Hexagon DSP, libOpenCL.so for Adreno) which Android blocks unless
 *    the manifest declares them via <uses-native-library> — without that,
 *    HTP fails with QNN_DEVICE_ERROR_INVALID_CONFIG and GPU with
 *    QNN_COMMON_ERROR_PLATFORM_NOT_SUPPORTED, and everything lands on CPU.
 *
 * 3. The SoC is spelled out (soc_model=69 / htp_arch=79, from the AI Hub
 *    catalog) because QNN's auto-detection of the SM8750-AC "for Galaxy"
 *    variant is unproven.
 *
 * Tier order: NPU (full) -> GPU (full) -> GPU/CPU -> NPU/CPU -> CPU.
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
        attempt(env, modelBytes, GPU_OPTIONS, strict = false, tag, "mixed-GPU")
            ?.let { return Created(it, "GPU/CPU mixed") }
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
