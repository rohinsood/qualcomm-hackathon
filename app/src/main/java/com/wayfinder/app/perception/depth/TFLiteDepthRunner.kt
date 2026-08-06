package com.wayfinder.app.perception.depth

import android.content.Context
import android.util.Log
import com.wayfinder.app.core.config.Tunables
import com.wayfinder.app.core.loop.Frame
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import com.qualcomm.qti.QnnDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the **AI-Hub-compiled int8 Depth-Anything V2 Small** (.tflite, device-matched for the S25
 * Ultra HTP).
 *
 * I/O: input `[1,3,518,518]` float32 NCHW (ImageNet-norm); output `[1,518,518]` float32 relative
 * disparity (larger = closer). Mapped to frame-relative pseudo-meters for the fusion.
 *
 * Acceleration: Adreno GPU delegate now (→ Hexagon NPU via the QNN delegate in [buildDelegate]
 * later; proven 13.5 ms on the NPU via AI Hub profile), CPU/XNNPACK fallback.
 */
class TFLiteDepthRunner(
    private val context: Context,
    private val tunables: Tunables,
    private val modelAssetName: String = "depth_model.tflite",
    private val inputHeight: Int = 518,
    private val inputWidth: Int = 518,
    private val disparityConvention: Boolean = true, // true: larger output value = closer
    private val meanRgb: FloatArray = floatArrayOf(0.485f, 0.456f, 0.406f),
    private val stdRgb: FloatArray = floatArrayOf(0.229f, 0.224f, 0.225f),
    private val useQnn: Boolean = false,  // NPU gated on retail S25 (CDSP transport err 14001); works on dev-unlocked devices / AI Hub farm.
    private val useGpu: Boolean = true,
) : DepthRunner {

    override val name: String = "tflite-depth"

    private val input: ByteBuffer =
        ByteBuffer.allocateDirect(inputHeight * inputWidth * 3 * 4).order(ByteOrder.nativeOrder())
    private val output: ByteBuffer =
        ByteBuffer.allocateDirect(inputHeight * inputWidth * 4).order(ByteOrder.nativeOrder()) // float disparity

    private val interpreter: Interpreter by lazy { createInterpreter() }

    private fun createInterpreter(): Interpreter {
        val bytes = context.assets.open(modelAssetName).use { it.readBytes() }
        fun buf(): ByteBuffer {
            val b = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()); b.put(bytes); b.rewind(); return b
        }
        val delegate = buildDelegate()
        if (delegate != null) {
            try {
                val interp = Interpreter(buf(), Interpreter.Options().addDelegate(delegate))
                if (smokeTest(interp)) { Log.i(TAG, "GPU delegate active."); return interp }
                interp.close(); Log.w(TAG, "GPU rejected model — CPU fallback.")
            } catch (t: Throwable) { Log.w(TAG, "GPU init failed — CPU fallback.", t) }
            return Interpreter(buf(), Interpreter.Options().setNumThreads(4))
        }
        return Interpreter(buf(), Interpreter.Options().setNumThreads(4))
    }

    private fun buildDelegate(): Delegate? {
        if (useQnn) {
            try {
                val opts = QnnDelegate.Options().apply {
                    setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
                    setSkelLibraryDir(context.applicationInfo.nativeLibraryDir)
                }
                return QnnDelegate(opts).also { Log.i(TAG, "QNN HTP (NPU) delegate created.") }
            } catch (t: Throwable) {
                Log.w(TAG, "QnnDelegate failed — trying GPU/CPU.", t)
            }
        }
        if (useGpu) return try { GpuDelegate() } catch (t: Throwable) { null }
        return null
    }

    private fun smokeTest(interp: Interpreter): Boolean = try {
        input.rewind(); while (input.hasRemaining()) input.put(0)
        output.rewind()
        interp.runForMultipleInputsOutputs(arrayOf<Any>(input), mutableMapOf<Int, Any>(0 to output)); true
    } catch (t: Throwable) { false }

    override fun warmUp() { smokeTest(interpreter) }

    override fun depth(frame: Frame): DepthColumns {
        preprocess(frame)
        output.rewind()
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), mutableMapOf<Int, Any>(0 to output))
        val disp = FloatArray(inputHeight * inputWidth)
        output.rewind()
        output.asFloatBuffer().get(disp)
        return columnizeDisparity(disp)
    }

    private fun preprocess(frame: Frame) {
        input.rewind()
        val sxA = frame.width.toFloat() / inputWidth
        val syA = frame.height.toFloat() / inputHeight
        val n = inputHeight * inputWidth
        val rgb = FloatArray(n * 3)
        for (y in 0 until inputHeight) {
            val sy = (y * syA).toInt().coerceIn(0, frame.height - 1)
            for (x in 0 until inputWidth) {
                val sx = (x * sxA).toInt().coerceIn(0, frame.width - 1)
                val p = (sy * frame.width + sx) * 4
                val idx = (y * inputWidth + x) * 3
                rgb[idx] = (frame.rgba[p].toInt() and 0xFF) / 255f
                rgb[idx + 1] = (frame.rgba[p + 1].toInt() and 0xFF) / 255f
                rgb[idx + 2] = (frame.rgba[p + 2].toInt() and 0xFF) / 255f
            }
        }
        val fb = input.asFloatBuffer()
        for (c in 0 until 3) {
            val m = meanRgb[c]; val s = stdRgb[c]
            for (i in 0 until n) fb.put((rgb[i * 3 + c] - m) / s)
        }
    }

    /** Disparity map → per-column frame-relative pseudo-distance within the body band. */
    private fun columnizeDisparity(disp: FloatArray): DepthColumns {
        val t = tunables
        val n = t.numColumns
        val bandY0 = (inputHeight * t.verticalBandStart).toInt().coerceIn(0, inputHeight - 1)
        val bandY1 = (inputHeight * t.verticalBandEnd).toInt().coerceIn(bandY0 + 1, inputHeight)
        val colW = (inputWidth / n).coerceAtLeast(1)

        var dMin = Float.MAX_VALUE; var dMax = -Float.MAX_VALUE
        for (y in bandY0 until bandY1) for (x in 0 until inputWidth) {
            val v = disp[y * inputWidth + x]
            if (v.isFinite()) { if (v < dMin) dMin = v; if (v > dMax) dMax = v }
        }
        if (!dMin.isFinite() || dMax <= dMin) return DepthColumns(FloatArray(n) { t.maxRangeMeters }, null)

        val per = FloatArray(n); var nearest = Float.MAX_VALUE; val range = dMax - dMin
        for (c in 0 until n) {
            val x0 = (c * colW).coerceAtMost(inputWidth); val x1 = ((c + 1) * colW).coerceAtMost(inputWidth)
            var extreme = if (disparityConvention) -Float.MAX_VALUE else Float.MAX_VALUE
            for (x in x0 until x1) for (y in bandY0 until bandY1) {
                val v = disp[y * inputWidth + x]
                if (!v.isFinite()) continue
                extreme = if (disparityConvention) maxOf(extreme, v) else minOf(extreme, v)
            }
            val dist = if (!extreme.isFinite()) t.maxRangeMeters else {
                val closeness = if (disparityConvention) ((extreme - dMin) / range).coerceIn(0f, 1f)
                                else ((dMax - extreme) / range).coerceIn(0f, 1f)
                t.maxRangeMeters - closeness * (t.maxRangeMeters - t.minRangeMeters)
            }
            per[c] = dist; if (dist < nearest) nearest = dist
        }
        return DepthColumns(per, if (nearest == Float.MAX_VALUE) null else nearest)
    }

    override fun close() = interpreter.close()

    companion object { private const val TAG = "TFLiteDepthRunner" }
}
