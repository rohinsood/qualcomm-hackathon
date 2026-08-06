package com.wayfinder.app.perception.seg

import android.content.Context
import android.util.Log
import com.wayfinder.app.core.loop.Frame
import com.wayfinder.app.perception.ModelRunner
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import com.qualcomm.qti.QnnDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the **AI-Hub-compiled int8 Fast-SCNN** (.tflite, device-matched for the S25 Ultra HTP).
 *
 * I/O (verified from the compiled model):
 *  - Input  `[1, 3, 384, 576]` float32, NCHW, ImageNet-normalized RGB.
 *  - Output `[1, 1, 384, 576]` int32 — Cityscapes trainId labels; walkable = {road(0), sidewalk(1)}.
 *
 * Acceleration: tries the **Adreno GPU delegate**, falls back to CPU/XNNPACK if the GPU rejects
 * the model (e.g. NCHW/int8 quirks). **NPU-ready**: when the QNN delegate AAR is available, create
 * it in [buildDelegate] and it runs on the Hexagon NPU (proven 0.6 ms via AI Hub profile).
 */
class TFLiteSegmentationRunner(
    private val context: Context,
    private val modelAssetName: String = "seg_model.tflite",
    private val inputHeight: Int = 384,
    private val inputWidth: Int = 576,
    private val walkableClasses: Set<Int> = setOf(0, 1), // Cityscapes trainId: road, sidewalk
    private val meanRgb: FloatArray = floatArrayOf(0.485f, 0.456f, 0.406f),
    private val stdRgb: FloatArray = floatArrayOf(0.229f, 0.224f, 0.225f),
    private val useQnn: Boolean = false,  // NPU gated on retail S25 (CDSP transport err 14001); works on dev-unlocked devices / AI Hub farm.
    private val useGpu: Boolean = true,
) : ModelRunner {

    override val name: String = "tflite-seg"

    private val input: ByteBuffer =
        ByteBuffer.allocateDirect(inputHeight * inputWidth * 3 * 4).order(ByteOrder.nativeOrder())
    private val output: ByteBuffer =
        ByteBuffer.allocateDirect(inputHeight * inputWidth * 4).order(ByteOrder.nativeOrder()) // int32 labels

    private val interpreter: Interpreter by lazy { createInterpreter() }

    private fun createInterpreter(): Interpreter {
        val bytes = context.assets.open(modelAssetName).use { it.readBytes() }
        val modelBuf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        modelBuf.put(bytes); modelBuf.rewind()

        val delegate = buildDelegate()
        if (delegate != null) {
            try {
                val interp = Interpreter(modelBuf, Interpreter.Options().addDelegate(delegate))
                if (smokeTest(interp)) {
                    Log.i(TAG, "GPU delegate active.")
                    return interp
                }
                interp.close()
                Log.w(TAG, "GPU delegate rejected model — CPU fallback.")
            } catch (t: Throwable) {
                Log.w(TAG, "GPU delegate init failed — CPU fallback.", t)
            }
            // Re-make the model buffer (Interpreter consumed it) for the CPU path.
            val mb2 = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            mb2.put(bytes); mb2.rewind()
            return Interpreter(mb2, Interpreter.Options().setNumThreads(4))
        }
        return Interpreter(modelBuf, Interpreter.Options().setNumThreads(4))
    }

    /** Create the acceleration delegate. Today: Adreno GPU. NPU: swap in the QNN delegate here. */
    private fun buildDelegate(): Delegate? {
        // NPU first (QNN HTP delegate), then Adreno GPU, then CPU.
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

    /** Run a zero-input inference to confirm the delegate actually accepts this model. */
    private fun smokeTest(interp: Interpreter): Boolean = try {
        input.rewind(); while (input.hasRemaining()) input.put(0)
        output.rewind()
        interp.runForMultipleInputsOutputs(arrayOf<Any>(input), mutableMapOf<Int, Any>(0 to output))
        true
    } catch (t: Throwable) {
        Log.w(TAG, "smoke test failed", t); false
    }

    override fun warmUp() = smokeTest(interpreter).let { }

    override fun segment(frame: Frame): WalkableMask {
        preprocess(frame)
        output.rewind()
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), mutableMapOf<Int, Any>(0 to output))
        val labels = IntArray(inputHeight * inputWidth)
        output.rewind()
        output.asIntBuffer().get(labels)
        return MaskBuilder.build(labels, inputWidth, inputHeight, walkableClasses)
    }

    /** Downscale camera frame → float32 NCHW, ImageNet-normalized RGB. */
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

    override fun close() = interpreter.close()

    companion object { private const val TAG = "TFLiteSegRunner" }
}
