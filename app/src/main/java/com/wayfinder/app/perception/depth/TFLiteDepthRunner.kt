package com.wayfinder.app.perception.depth

import android.content.Context
import android.util.Log
import com.wayfinder.app.core.config.Tunables
import com.wayfinder.app.core.loop.Frame
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real depth runner: monocular depth (Depth-Anything-V2-Small or similar) on the
 * Hexagon NPU via the QNN delegate. Mirrors [com.wayfinder.app.perception.seg.TFLiteSegmentationRunner].
 *
 * STATUS (M3 scaffold): structure complete; the model file + QNN delegate are the
 * two things you drop in (marked TODO). Output assumed `[1, H, W]` float depth.
 *
 * ⚠️ Depth-Anything's base output is RELATIVE (scale-invariant). If you need true
 * metric meters for the proximity/haptic cadence, use the metric-head variant or
 * apply a learned scale (TODO) — otherwise distances are only ordinal.
 */
class TFLiteDepthRunner(
    private val context: Context,
    private val tunables: Tunables,
    private val modelAssetName: String = "depth_model.tflite",
    private val inputWidth: Int = 518,
    private val inputHeight: Int = 518,
    private val tryGpu: Boolean = true,
    private val tryQnn: Boolean = true,
) : DepthRunner {

    override val name: String = "tflite-depth"

    private val interpreter: Interpreter
    private val inputRgb =
        ByteBuffer.allocateDirect(inputWidth * inputHeight * 3).order(ByteOrder.nativeOrder())
    private val outDepth =
        ByteBuffer.allocateDirect(inputWidth * inputHeight * 4).order(ByteOrder.nativeOrder())

    init {
        val buffer = context.assets.open(modelAssetName).use { it.readBytes() }
        val options = Interpreter.Options()
        var delegateAttached = false

        if (tryGpu && CompatibilityList().isDelegateSupportedOnThisDevice) {
            options.addDelegate(GpuDelegate())
            delegateAttached = true
            Log.i(TAG, "Depth: using GPU delegate.")
        }
        if (!delegateAttached) Log.w(TAG, "Depth: CPU fallback.")

        options.setNumThreads(4)
        val bb = ByteBuffer.allocateDirect(buffer.size).order(ByteOrder.nativeOrder())
        bb.put(buffer)
        bb.rewind()
        interpreter = Interpreter(bb, options)
        Log.i(TAG, "loaded $modelAssetName; input=${interpreter.getInputTensor(0).shape().toList()} " +
            "output=${interpreter.getOutputTensor(0).shape().toList()}")
    }

    override fun warmUp() {
        inputRgb.rewind()
        while (inputRgb.hasRemaining()) inputRgb.put(0)
        runInference()
    }

    override fun depth(frame: Frame): DepthColumns {
        preprocess(frame)
        runInference()
        val map = FloatArray(inputWidth * inputHeight)
        outDepth.rewind()
        outDepth.asFloatBuffer().get(map)
        return DepthColumnizer.columnize(map, inputWidth, inputHeight, tunables)
    }

    private fun preprocess(frame: Frame) {
        inputRgb.rewind()
        val sxA = frame.width.toFloat() / inputWidth
        val syA = frame.height.toFloat() / inputHeight
        for (y in 0 until inputHeight) {
            val sy = (y * syA).toInt().coerceIn(0, frame.height - 1)
            for (x in 0 until inputWidth) {
                val sx = (x * sxA).toInt().coerceIn(0, frame.width - 1)
                val p = (sy * frame.width + sx) * 4
                // Raw uint8 (common TFLite image input). TODO: match model quantization.
                inputRgb.put((frame.rgba[p].toInt() and 0xFF).toByte())
                inputRgb.put((frame.rgba[p + 1].toInt() and 0xFF).toByte())
                inputRgb.put((frame.rgba[p + 2].toInt() and 0xFF).toByte())
            }
        }
    }

    private fun runInference() {
        interpreter.runForMultipleInputsOutputs(
            arrayOf<Any>(inputRgb),
            mutableMapOf<Int, Any>(0 to outDepth),
        )
    }

    override fun close() {
        interpreter.close()
    }

    companion object {
        private const val TAG = "TFLiteDepthRunner"
    }
}
