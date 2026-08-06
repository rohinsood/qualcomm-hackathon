package com.wayfinder.app.perception.depth

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.wayfinder.app.core.config.Tunables
import com.wayfinder.app.core.loop.Frame
import java.nio.FloatBuffer

/**
 * ONNX Runtime monocular-depth runner = **Depth-Anything V2 Small** on the
 * QAIRT-aligned path (NNAPI EP → Hexagon NPU). Runs `.onnx` directly.
 *
 * I/O (verified):
 *  - Input  `[1, 3, 518, 518]` float32, NCHW, ImageNet-normalized RGB.
 *  - Output `[1, 518, 518]` float32 — relative **disparity** (larger = closer).
 *
 * Depth-Anything is *relative* (no true metric meters), so we map the frame's
 * disparity range to a **frame-relative pseudo-distance** in
 * [Tunables.minRangeMeters .. maxRangeMeters]: the closest surface in the body
 * band maps to `minRangeMeters`, the farthest to `maxRangeMeters`. That's enough
 * to drive proximity-scaled haptics and a relative safety override. (Swap in the
 * metric outdoor variant for real meters later.)
 */
class OnnxDepthRunner(
    private val context: Context,
    private val tunables: Tunables,
    private val modelAssetName: String = "depth_model.onnx",
    private val inputHeight: Int = 518,
    private val inputWidth: Int = 518,
    private val disparityConvention: Boolean = true, // true: larger output value = closer
    private val meanRgb: FloatArray = floatArrayOf(0.485f, 0.456f, 0.406f),
    private val stdRgb: FloatArray = floatArrayOf(0.229f, 0.224f, 0.225f),
    private val useQnn: Boolean = false,  // NPU needs AI Hub / device-matched QNN backends; stock AAR → PLATFORM_NOT_SUPPORTED. CPU for now.
) : DepthRunner {

    override val name: String = "onnx-depth"

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    private val inputData: FloatBuffer = FloatBuffer.allocate(3 * inputHeight * inputWidth)
    private val inputShape: LongArray = longArrayOf(1L, 3L, inputHeight.toLong(), inputWidth.toLong())

    init {
        val bytes = context.assets.open(modelAssetName).use { it.readBytes() }
        session = createSession(bytes)
        inputName = session.inputNames.first()
    }

    /** Run on the Adreno GPU via the QNN execution provider (GPU backend, float32 OK);
     *  fall back to CPU EP on any failure. (Hexagon NPU/HTP needs int8 quantization.) */
    private fun createSession(bytes: ByteArray): OrtSession {
        if (useQnn) {
            val opts = OrtSession.SessionOptions()
            try {
                opts.addQnn(mapOf("backend_type" to "gpu"))
                val s = env.createSession(bytes, opts)
                Log.i(TAG, "ORT depth: QNN EP enabled — running on Adreno GPU.")
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

    override fun depth(frame: Frame): DepthColumns {
        preprocess(frame)
        inputData.rewind()
        val inputTensor = OnnxTensor.createTensor(env, inputData, inputShape)
        val result = session.run(mapOf(inputName to inputTensor))
        val disp = try {
            readDisparity(result.get(0) as OnnxTensor)
        } finally {
            result.close()
            inputTensor.close()
        }
        return columnizeDisparity(disp)
    }

    /**
     * Safely reads disparity from the output tensor. Handles both [1, H, W] and
     * [1, 1, H, W] shapes.
     */
    private fun readDisparity(tensor: OnnxTensor): FloatArray {
        val shape = tensor.info.shape
        val totalElements = shape.reduce { acc, i -> acc * i }.toInt()
        val out = FloatArray(inputHeight * inputWidth)
        val buf = tensor.floatBuffer
        buf.rewind()
        for (i in 0 until minOf(totalElements, out.size)) {
            out[i] = buf.get()
        }
        return out
    }

    /** Downscale camera frame → NCHW float32, ImageNet-normalized RGB. */
    private fun preprocess(frame: Frame) {
        inputData.clear()
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
        for (c in 0 until 3) {
            val m = meanRgb[c]
            val s = stdRgb[c]
            for (i in 0 until n) inputData.put((rgb[i * 3 + c] - m) / s)
        }
    }

    /**
     * Disparity map → per-column frame-relative pseudo-distance within the body band.
     * Closest surface in the band → minRangeMeters; farthest → maxRangeMeters.
     */
    private fun columnizeDisparity(disp: FloatArray): DepthColumns {
        val t = tunables
        val n = t.numColumns
        val bandY0 = (inputHeight * t.verticalBandStart).toInt().coerceIn(0, inputHeight - 1)
        val bandY1 = (inputHeight * t.verticalBandEnd).toInt().coerceIn(bandY0 + 1, inputHeight)
        val colW = (inputWidth / n).coerceAtLeast(1)

        // Frame disparity range within the band (for normalization).
        var dMin = Float.MAX_VALUE
        var dMax = -Float.MAX_VALUE
        for (y in bandY0 until bandY1) {
            for (x in 0 until inputWidth) {
                val v = disp[y * inputWidth + x]
                if (v.isFinite()) {
                    if (v < dMin) dMin = v
                    if (v > dMax) dMax = v
                }
            }
        }
        if (!dMin.isFinite() || dMax <= dMin) {
            // Flat / no signal → treat as fully open.
            return DepthColumns(FloatArray(n) { t.maxRangeMeters }, null)
        }

        val per = FloatArray(n)
        var nearest = Float.MAX_VALUE
        val range = dMax - dMin
        for (c in 0 until n) {
            val x0 = (c * colW).coerceAtMost(inputWidth)
            val x1 = ((c + 1) * colW).coerceAtMost(inputWidth)
            // Column's closest pixel = extreme disparity (max if disparity-convention, min if depth).
            var extreme = if (disparityConvention) -Float.MAX_VALUE else Float.MAX_VALUE
            for (x in x0 until x1) {
                for (y in bandY0 until bandY1) {
                    val v = disp[y * inputWidth + x]
                    if (!v.isFinite()) continue
                    extreme = if (disparityConvention) maxOf(extreme, v) else minOf(extreme, v)
                }
            }
            val dist = if (!extreme.isFinite()) {
                t.maxRangeMeters
            } else {
                // closeness in [0,1], 1 = closest → distance from maxRange down to minRange.
                val closeness = if (disparityConvention) {
                    ((extreme - dMin) / range).coerceIn(0f, 1f)
                } else {
                    ((dMax - extreme) / range).coerceIn(0f, 1f)
                }
                t.maxRangeMeters - closeness * (t.maxRangeMeters - t.minRangeMeters)
            }
            per[c] = dist
            if (dist < nearest) nearest = dist
        }
        return DepthColumns(per, if (nearest == Float.MAX_VALUE) null else nearest)
    }

    override fun close() {
        session.close()
    }

    companion object {
        private const val TAG = "OnnxDepthRunner"
    }
}
