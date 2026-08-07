package com.example.qhackgps.llm

import android.content.Context
import android.util.Log
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.ModelPullInput

/**
 * Shared GenieX plumbing: one-time SDK init plus download/lookup of the
 * companion SLM. Ported from the v3 branch, where the same model won the
 * on-device bench (12 tok/s decode, 186 ms first token on the Hexagon NPU).
 */
object GenieRuntime {

    private const val TAG = "GenieRuntime"

    /** The companion SLM — v3's bench winner on the Hexagon NPU (12 tok/s). */
    const val MODEL = "unsloth/Qwen3.5-2B-GGUF"

    /** Best Hexagon kernel coverage in llama.cpp's NPU backend. */
    const val PRECISION = "Q4_0"

    @Volatile private var initCalled = false

    @Volatile var initError: String? = null
        private set

    /** Idempotent SDK init; returns the init error, or null when healthy. */
    fun ensureInit(context: Context): String? {
        if (!initCalled) {
            synchronized(this) {
                if (!initCalled) {
                    // init() loads the runtime AND registers both plugins
                    // itself (by absolute .so path — requires extracted
                    // native libs, see useLegacyPackaging in build.gradle.kts)
                    GenieXSdk.getInstance().init(
                        context.applicationContext,
                        object : GenieXSdk.InitCallback {
                            override fun onSuccess() {
                                Log.i(TAG, "GenieX init OK")
                            }

                            override fun onFailure(message: String) {
                                Log.e(TAG, "GenieX init FAILED: $message")
                                initError = message
                            }
                        },
                    )
                    initCalled = true
                }
            }
        }
        return initError
    }

    /** True when the model is already fully downloaded on this device. */
    suspend fun modelPresent(): Boolean =
        runCatching { ModelManagerWrapper.list() }
            .getOrNull()?.any { it.contains(MODEL) } == true

    /** Downloads the model through GenieX if missing (~1.2 GB, Wi-Fi). */
    suspend fun ensureModel(context: Context, onStatus: (String) -> Unit): Throwable? {
        ensureInit(context)?.let { return RuntimeException("GenieX init: $it") }
        if (modelPresent()) return null

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

    suspend fun modelPaths() = runCatching { ModelManagerWrapper.getPaths(MODEL) }.getOrNull()
}
