package com.wayfinder.app.perception.seg

import android.content.Context
import android.util.Log
import com.wayfinder.app.core.loop.Frame
import com.wayfinder.app.perception.ModelRunner
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real semantic-segmentation runner.
 *
 * Acceleration order: **NNAPI → GPU → CPU**. On the S25 Ultra, NNAPI routes
 * supported int8 ops to the **Hexagon NPU** via the Qualcomm vendor driver — this
 * is the on-device NPU path with NO QNN AAR required (the QNN delegate would give
 * tighter control; see the TODO block). Each delegate is wrapped so a failure
 * cleanly falls back.
 *
 * I/O handled here:
 *  - Input: float32 NHWC, resized from the camera frame, values in [0,255]
 *    (channel order configurable via [inputIsBgr]).
 *  - Output: NHWC logits [1,H,W,numClasses] → argmax over the class axis → label
 *    map → [MaskBuilder] with the configured [walkableClasses].
 *
 * The default config is the **Intel ADAS road-segmentation stopgap** (512×896,
 * 4 classes, road=walkable) — present ONLY to prove on-device NPU inference works.
 * It has no `sidewalk` class and is dashcam-trained, so it is NOT safe for real
 * pedestrian navigation. Swap to a Cityscapes model + `walkableClasses = setOf(7,8)`
 * for real use.
 *
 * ── QNN delegate (optional, tighter than NNAPI) ─────────────────────────────
 *  Add the QNN delegate AAR to app/libs/ + build.gradle.kts, then in [init]:
 *    options.addDelegate(QnnDelegate(QnnDelegate.Options().apply { setBackend("htp") }))
 * ─────────────────────────────────────────────────────────────────────────────
 */
class TFLiteSegmentationRunner(
    private val context: Context,
    private val modelAssetName: String = "seg_model.tflite",
    private val inputWidth: Int = 896,
    private val inputHeight: Int = 512,
    private val numClasses: Int = 4,
    private val walkableClasses: Set<Int> = setOf(1), // ADAS: road
    private val inputIsBgr: Boolean = false,
    private val tryNnapi: Boolean = true,
    private val tryGpu: Boolean = true,
) : ModelRunner {

    override val name: String = "tflite-seg"

    private val interpreter: Interpreter

    // float32 NHWC input + pre-allocated NHWC logits output (reused every frame).
    private val inputBuffer =
        ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * 4).order(ByteOrder.nativeOrder())
    private val logitsOut = Array(1) { Array(inputHeight) { Array(inputWidth) { FloatArray(numClasses) } } }

    init {
        val modelBytes = context.assets.open(modelAssetName).use { it.readBytes() }
        val options = Interpreter.Options()
        var delegateAttached = false

        // 1) NNAPI → Hexagon NPU on the S25 Ultra (no QNN AAR needed).
        if (tryNnapi) {
            try {
                options.addDelegate(NnApiDelegate(NnApiDelegate.Options().apply { setAllowFp16(true) }))
                delegateAttached = true
                Log.i(TAG, "Using NNAPI delegate (targets Hexagon NPU on S25 Ultra).")
            } catch (t: Throwable) {
                Log.w(TAG, "NNAPI delegate unavailable — will try GPU/CPU", t)
            }
        }
        // 2) GPU fallback.
        if (!delegateAttached && tryGpu && CompatibilityList().isDelegateSupportedOnThisDevice) {
            options.addDelegate(GpuDelegate())
            delegateAttached = true
            Log.i(TAG, "Using GPU delegate.")
        }
        if (!delegateAttached) {
            Log.w(TAG, "No hardware delegate — CPU only, expect low FPS.")
        }

        options.setNumThreads(4)
        val bb = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
        bb.put(modelBytes)
        bb.rewind()
        interpreter = Interpreter(bb, options)
    }

    override fun warmUp() {
        // First call pays NPU context-init cost — run it on a neutral frame before live use.
        inputBuffer.rewind()
        while (inputBuffer.hasRemaining()) inputBuffer.putFloat(0f)
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(inputBuffer), mutableMapOf<Int, Any>(0 to logitsOut))
    }

    override fun segment(frame: Frame): WalkableMask {
        preprocess(frame)
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(inputBuffer), mutableMapOf<Int, Any>(0 to logitsOut))

        val classes = IntArray(inputWidth * inputHeight)
        for (y in 0 until inputHeight) {
            for (x in 0 until inputWidth) {
                val row = logitsOut[0][y][x]
                var bestC = 0
                var bestV = row[0]
                for (c in 1 until numClasses) {
                    if (row[c] > bestV) {
                        bestV = row[c]
                        bestC = c
                    }
                }
                classes[y * inputWidth + x] = bestC
            }
        }
        return MaskBuilder.build(classes, inputWidth, inputHeight, walkableClasses)
    }

    /** Resize the camera frame into the float32 NHWC input buffer as [0,255] values. */
    private fun preprocess(frame: Frame) {
        inputBuffer.rewind()
        val sxA = frame.width.toFloat() / inputWidth
        val syA = frame.height.toFloat() / inputHeight
        for (y in 0 until inputHeight) {
            val sy = (y * syA).toInt().coerceIn(0, frame.height - 1)
            for (x in 0 until inputWidth) {
                val sx = (x * sxA).toInt().coerceIn(0, frame.width - 1)
                val p = (sy * frame.width + sx) * 4
                val r = (frame.rgba[p].toInt() and 0xFF).toFloat()
                val g = (frame.rgba[p + 1].toInt() and 0xFF).toFloat()
                val b = (frame.rgba[p + 2].toInt() and 0xFF).toFloat()
                if (inputIsBgr) {
                    inputBuffer.putFloat(b); inputBuffer.putFloat(g); inputBuffer.putFloat(r)
                } else {
                    inputBuffer.putFloat(r); inputBuffer.putFloat(g); inputBuffer.putFloat(b)
                }
            }
        }
    }

    override fun close() {
        interpreter.close()
    }

    companion object {
        private const val TAG = "TFLiteSegRunner"
    }
}
