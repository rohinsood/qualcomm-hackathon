package com.example.qhackgps.scan

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log

/**
 * ONNX Runtime session factory, ported from the v3 branch. It encodes
 * hard-won lessons from getting vision onto the Snapdragon accelerators:
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
 *    (The NPU additionally needs the libQnnHtpV*Skel.so extracted to disk —
 *    useLegacyPackaging — because the DSP opens it by file path.)
 *
 * 3. The SoC is spelled out (soc_model=69 / htp_arch=79, from the AI Hub
 *    catalog) because QNN's auto-detection of the SM8750-AC "for Galaxy"
 *    variant is unproven.
 *
 * Tier order: GPU (full) -> NPU (full) -> GPU/CPU -> NPU/CPU -> CPU.
 * GPU-first is kept from v3 (there it left the Hexagon free for the SLM);
 * here it simply means the scan never competes with anything for the NPU.
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
        val strictTiers = listOf(GPU_OPTIONS to "GPU", HTP_OPTIONS to "NPU")
        val mixedTiers = listOf(GPU_OPTIONS to "GPU/CPU mixed", HTP_OPTIONS to "NPU/CPU mixed")
        val make = { opts: OrtSession.SessionOptions -> env.createSession(modelBytes, opts) }

        for ((options, label) in strictTiers) {
            attempt(tag, "full-$label", strict = true, options, make)
                ?.let { return Created(it, label) }
        }
        for ((options, label) in mixedTiers) {
            attempt(tag, "mixed-$label", strict = false, options, make)
                ?.let { return Created(it, label) }
        }
        return try {
            Created(make(OrtSession.SessionOptions()), "CPU")
        } catch (e: Exception) {
            Log.e(tag, "CPU session creation failed", e)
            null
        }
    }

    private fun attempt(
        tag: String,
        label: String,
        strict: Boolean,
        qnnOptions: Map<String, String>,
        make: (OrtSession.SessionOptions) -> OrtSession,
    ): OrtSession? = try {
        val opts = OrtSession.SessionOptions()
        if (strict) opts.addConfigEntry("session.disable_cpu_ep_fallback", "1")
        opts.addQnn(qnnOptions)
        make(opts)
    } catch (e: Exception) {
        Log.w(tag, "$label session rejected: ${e.message?.take(200)}")
        null
    }
}
