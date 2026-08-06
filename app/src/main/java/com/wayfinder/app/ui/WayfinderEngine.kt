package com.wayfinder.app.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.wayfinder.app.camera.CameraProvider
import com.wayfinder.app.core.config.Tunables
import com.wayfinder.app.core.loop.DecisionStore
import com.wayfinder.app.core.loop.FrameSlot
import com.wayfinder.app.core.loop.SteeringLoop
import com.wayfinder.app.core.safety.Watchdog
import com.wayfinder.app.output.haptics.HapticLoop
import com.wayfinder.app.output.speech.SpeechGate
import com.wayfinder.app.output.speech.TtsController
import com.wayfinder.app.perception.ModelRunner
import com.wayfinder.app.perception.columnize.Columnizer
import com.wayfinder.app.perception.depth.DepthRunner
import com.wayfinder.app.perception.depth.OnnxDepthRunner
import com.wayfinder.app.perception.depth.SyntheticDepthRunner
import com.wayfinder.app.perception.depth.TFLiteDepthRunner
import com.wayfinder.app.perception.seg.OnnxSegmentationRunner
import com.wayfinder.app.perception.seg.SyntheticSegmentationRunner
import com.wayfinder.app.perception.seg.TFLiteSegmentationRunner
import com.wayfinder.app.steering.GapSeeker

/**
 * Wires the whole M1 pipeline: camera → (synthetic or TFLite) segmentation →
 * columnizer → gap-seeker → decision store, plus the haptic/speech output loops
 * and the safety watchdog. Start/stop against the Activity lifecycle.
 *
 * Flip [USE_REAL_MODEL] after dropping `seg_model.tflite` into assets and wiring
 * the QNN delegate (see TFLiteSegmentationRunner).
 */
class WayfinderEngine(private val context: Context) {

    val tunables = Tunables()
    val frameSlot = FrameSlot()
    val decisionStore = DecisionStore()
    val watchdog = Watchdog(decisionStore, tunables, onChange = { Log.i(TAG, "watchdog: $it") })

    // Real runners are constructed defensively: if the model file or a hardware
    // delegate is missing/broken on this device, fall back to synthetic so the app
    // never crashes on the phone. Flip USE_REAL_* once the .tflite is in assets.
    private val runner: ModelRunner = buildSegRunner()
    private val depthRunner: DepthRunner = buildDepthRunner()

    private fun buildSegRunner(): ModelRunner {
        if (!USE_REAL_MODEL) return SyntheticSegmentationRunner()
        return try {
            // QAIRT-aligned path: ONNX Runtime (NNAPI → Hexagon NPU). Falls back to
            // TFLite if explicitly disabled.
            if (USE_ONNX_SEG) OnnxSegmentationRunner(context) else TFLiteSegmentationRunner(context)
        } catch (t: Throwable) {
            Log.w(TAG, "Real seg model unavailable — using synthetic", t)
            SyntheticSegmentationRunner()
        }
    }

    private fun buildDepthRunner(): DepthRunner {
        if (!USE_REAL_DEPTH) return SyntheticDepthRunner(tunables)
        return try {
            // AI-Hub-compiled int8 Depth-Anything via TFLite (GPU delegate; NPU-ready).
            TFLiteDepthRunner(context, tunables)
        } catch (t: Throwable) {
            Log.w(TAG, "Real depth model unavailable — using synthetic", t)
            SyntheticDepthRunner(tunables)
        }
    }

    private val columnizer = Columnizer(tunables)
    private val gapSeeker = GapSeeker(tunables)

    val steeringLoop = SteeringLoop(
        frameSlot = frameSlot,
        runner = runner,
        columnizer = columnizer,
        gapSeeker = gapSeeker,
        decisionStore = decisionStore,
        tunables = tunables,
        depthRunner = depthRunner,
        onFrameProcessed = { onInference() },
    )

    private val tts = TtsController(context)
    private val haptics = HapticLoop(context, decisionStore, tunables) { watchdog.isFailSafe() }
    private val speech = SpeechGate(tts, decisionStore, tunables) { watchdog.isFailSafe() }

    private val camera = CameraProvider(context, frameSlot) { watchdog.beatCamera() }

    // ---- runtime metrics for the debug overlay ----
    @Volatile private var processedFrames: Long = 0
    @Volatile private var firstFrameMs: Long = 0
    @Volatile private var lastInferMs: Long = 0

    val runnerName: String get() = "${runner.name} + ${depthRunner.name}"
    val inferenceAgeMs: Long
        get() = if (lastInferMs == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - lastInferMs
    val fps: Float
        get() {
            val elapsed = lastInferMs - firstFrameMs
            return if (processedFrames < 2 || elapsed <= 0) 0f
            else (processedFrames - 1) * 1000f / elapsed
        }

    private fun onInference() {
        val now = SystemClock.elapsedRealtime()
        if (firstFrameMs == 0L) firstFrameMs = now
        lastInferMs = now
        processedFrames++
    }

    fun start(lifecycleOwner: LifecycleOwner) {
        watchdog.start()
        steeringLoop.start()
        haptics.start()
        speech.start()
        camera.start(lifecycleOwner)
    }

    fun stop() {
        camera.stop()
        steeringLoop.stop()
        haptics.stop()
        speech.stop()
        watchdog.stop()
        tts.stop()
        decisionStore.clear()
        processedFrames = 0
        firstFrameMs = 0
        lastInferMs = 0
    }

    fun dispose() {
        stop()
        tts.shutdown()
    }

    companion object {
        private const val TAG = "WayfinderEngine"
        /**
         * Real on-device segmentation. Default = ONNX Runtime (QAIRT path) running
         * Fast-SCNN Cityscapes (road+sidewalk) via NNAPI → Hexagon NPU. Falls back
         * to synthetic on failure; set USE_ONNX_SEG=false to use the TFLite runner.
         */
        const val USE_REAL_MODEL = true
        const val USE_ONNX_SEG = false  // false → AI-Hub-compiled int8 TFLite seg (GPU delegate; NPU-ready)
        const val USE_REAL_DEPTH = true
    }
}
