package com.wayfinder.app.perception.seg

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.wayfinder.app.core.loop.Frame
import com.wayfinder.app.perception.ModelRunner
import java.nio.FloatBuffer

/**
 * ONNX Runtime semantic-segmentation runner. This is the QAIRT-aligned path:
 * it runs `.onnx` directly (no TFLite conversion) and uses ORT's **NNAPI execution
 * provider**, which routes supported ops to the **Hexagon NPU** on the S25 Ultra
 * via the Qualcomm vendor driver. (The QNN execution provider can be swapped in
 * later for tighter NPU control.)
 *
 * Default config = **Fast-SCNN (Cityscapes)**:
 *  - Input  `[1, 3, 384, 576]` float32, NCHW, ImageNet-normalized RGB.
 *  - Output `[1, 1, 384, 576]` int64 — already argmax'd label map, Cityscapes
 *    19-class trainId scheme (road=0, sidewalk=1, …, bicycle=18).
 *  - Walkable = {road(0), sidewalk(1)}.
 *
 * NCHW means channel-first layout; preprocess lays the camera frame out as
 * [C, H, W] with per-channel ImageNet normalization.
 */
class OnnxSegmentationRunner(
    private val context: Context,
    private val modelAssetName: String = "seg_model.onnx",
    private val inputHeight: Int = 384,
    private val inputWidth: Int = 576,
    private val walkableClasses: Set<Int> = setOf(0, 1), // Cityscapes trainId: road, sidewalk
    private val meanRgb: FloatArray = floatArrayOf(0.485f, 0.456f, 0.406f),
    private val stdRgb: FloatArray = floatArrayOf(0.229f, 0.224f, 0.225f),
    private val useQnn: Boolean = false,  // NPU needs AI Hub / device-matched QNN backends; stock AAR → PLATFORM_NOT_SUPPORTED. CPU for now.
) : ModelRunner {

    override val name: String = "onnx-seg"

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    private val inputData: FloatBuffer = FloatBuffer.allocate(3 * inputHeight * inputWidth)
    private val inputShape: LongArray = longArrayOf(1L, 3L, inputHeight.toLong(), inputWidth.toLong())

    init {
        val bytes = context.assets.open(modelAssetName).use { it.readBytes() }
        session = createSession(bytes)
        inputName = session.inputNames.first()
        Log.i(TAG, "loaded $modelAssetName; input='$inputName' ${inputShape.toList()}")
    }

    /** Run on the Adreno GPU via the QNN execution provider (GPU backend, float32 OK);
     *  fall back to CPU EP on any failure. (Hexagon NPU/HTP needs int8 quantization —
     *  see README / quantization step.) */
    private fun createSession(bytes: ByteArray): OrtSession {
        if (useQnn) {
            val opts = OrtSession.SessionOptions()
            try {
                opts.addQnn(mapOf("backend_type" to "gpu"))
                val s = env.createSession(bytes, opts)
                Log.i(TAG, "ORT seg: QNN EP enabled — running on Adreno GPU.")
                return s
            } catch (t: Throwable) {
                Log.w(TAG, "QNN EP unavailable, using CPU EP", t)
            }
        }
        return env.createSession(bytes, OrtSession.SessionOptions())
    }

    override fun warmUp() {
        inputData.rewind()
        while (inputData.hasRemaining()) inputData.put(0f)
        inputData.rewind()
        val tensor = OnnxTensor.createTensor(env, inputData, inputShape)
        session.run(mapOf(inputName to tensor)).close()
        tensor.close()
    }

    override fun segment(frame: Frame): WalkableMask {
        preprocess(frame)
        inputData.rewind()
        val inputTensor = OnnxTensor.createTensor(env, inputData, inputShape)
        val result = session.run(mapOf(inputName to inputTensor))
        val labels = try {
            readLabels(result.get(0) as OnnxTensor)
        } finally {
            result.close()
            inputTensor.close()
        }
        return MaskBuilder.build(labels, inputWidth, inputHeight, walkableClasses)
    }

    /**
     * Safely reads labels from the output tensor. Handles both [1, 1, H, W] and
     * [1, H, W] shapes, and supports both INT64 (argmax'd) and FLOAT32 (logits)
     * output types.
     */
    private fun readLabels(tensor: OnnxTensor): IntArray {
        val shape = tensor.info.shape
        val totalElements = shape.reduce { acc, i -> acc * i }.toInt()
        val out = IntArray(inputWidth * inputHeight)

        if (tensor.info.type == ai.onnxruntime.OnnxJavaType.INT64) {
            val buf = tensor.longBuffer
            buf.rewind()
            // If it's argmax'd [1, (1), H, W], just copy.
            for (i in 0 until minOf(totalElements, out.size)) {
                out[i] = buf.get().toInt()
            }
        } else {
            // If it's FLOAT32 [1, C, H, W] logits, apply argmax.
            val buf = tensor.floatBuffer
            buf.rewind()
            val numClasses = if (shape.size == 4) shape[1].toInt() else 1
            if (numClasses > 1) {
                for (i in 0 until (inputWidth * inputHeight)) {
                    var bestC = 0
                    var maxV = -Float.MAX_VALUE
                    for (c in 0 until numClasses) {
                        val v = buf.get(c * (inputWidth * inputHeight) + i)
                        if (v > maxV) {
                            maxV = v
                            bestC = c
                        }
                    }
                    out[i] = bestC
                }
            } else {
                for (i in 0 until minOf(totalElements, out.size)) {
                    out[i] = if (buf.get() > 0.5f) 1 else 0
                }
            }
        }
        return out
    }

    /** Downscale camera frame → NCHW float32, ImageNet-normalized RGB. */
    private fun preprocess(frame: Frame) {
        inputData.clear()
        val sxA = frame.width.toFloat() / inputWidth
        val syA = frame.height.toFloat() / inputHeight
        val n = inputHeight * inputWidth

        // Pass 1: downscale to HxWx3 normalized [0,1] RGB.
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
        // Pass 2: lay out as CHW with per-channel ImageNet normalization.
        for (c in 0 until 3) {
            val mean = meanRgb[c]
            val std = stdRgb[c]
            for (i in 0 until n) {
                inputData.put((rgb[i * 3 + c] - mean) / std)
            }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        private const val TAG = "OnnxSegRunner"
    }
}
