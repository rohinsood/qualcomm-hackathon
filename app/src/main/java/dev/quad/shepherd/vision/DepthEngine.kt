package dev.quad.shepherd.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

/**
 * Dense **metric** depth via Depth-Anything-V2-Metric-Indoor-Small, on the
 * same ONNX Runtime / QNN stack as [DetectionEngine].
 *
 * The model predicts distance in METERS per pixel (lower = closer) — a wall
 * or door directly ahead reads as its actual distance with no calibration
 * references, including while standing still. This is the class-free
 * proximity sense the detector lacks: walls, poles, and furniture register
 * even though YOLO has no class for them.
 * [dev.quad.shepherd.guidance.DepthCalibrator] only trims scale bias.
 *
 * The input resolution is read from the model itself, so re-exports at a
 * different size (see scripts/export_depth_model.sh) are drop-in.
 *
 * The model file is optional; when absent the app runs detection-only.
 * Not thread-safe. Call from the single analysis thread; the returned map
 * buffer is reused across calls.
 */
class DepthEngine(
    /** ONNX file under `<external-files>/models/` (or an asset name). The
     *  default is the INDOOR (Hypersim) metric export; the service also
     *  loads the OUTDOOR (VKITTI) sibling and picks per nav mode. */
    private val modelFile: String = "depth_anything_v2_small.onnx",
) {

    companion object {
        private const val TAG = "DepthEngine"
        private const val DEFAULT_INPUT_SIZE = 294
        /**
         * Depth analysis band, as fractions of frame height: the top cut
         * excludes sky/ceiling, the bottom cut keeps the floor right in
         * front of the user's feet from reading as an "obstacle".
         */
        const val CORRIDOR_TOP = 0.25f
        const val CORRIDOR_BOTTOM = 0.65f
        // ImageNet normalization, as used by the Depth-Anything preprocessor
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }

    /** One frame's metric depth map plus summary statistics. */
    class DepthMap(
        /** Row-major size x size depth map in meters; LOWER = closer. */
        val map: FloatArray,
        val size: Int,
        /** Median depth across the frame (diagnostics). */
        val sceneMedian: Float,
        val latencyMs: Long,
    ) {
        /**
         * Near-field depth (~20th percentile, i.e. the close tail) per
         * vertical column, sampled in the corridor band so the sky and the
         * ground at the user's feet do not dominate.
         */
        fun columnNearField(numColumns: Int): FloatArray {
            val rowLo = (size * CORRIDOR_TOP).toInt()
            val rowHi = (size * CORRIDOR_BOTTOM).toInt()
            val colW = size / numColumns
            val scratch = FloatArray(((rowHi - rowLo) / 2 + 1) * (colW / 2 + 1))
            val out = FloatArray(numColumns)
            for (c in 0 until numColumns) {
                val xLo = c * colW
                val xHi = if (c == numColumns - 1) size else (c + 1) * colW
                var n = 0
                var y = rowLo
                while (y < rowHi) {
                    var x = xLo
                    while (x < xHi && n < scratch.size) {
                        scratch[n++] = map[y * size + x]
                        x += 2
                    }
                    y += 2
                }
                if (n > 0) {
                    java.util.Arrays.sort(scratch, 0, n)
                    out[c] = scratch[(n * 20) / 100]
                }
            }
            return out
        }

        /** Median depth in the central 50% of a box (map coordinates). */
        fun boxMedian(x1: Float, y1: Float, x2: Float, y2: Float): Float? {
            val cx1 = x1 + (x2 - x1) * 0.25f
            val cx2 = x2 - (x2 - x1) * 0.25f
            val cy1 = y1 + (y2 - y1) * 0.25f
            val cy2 = y2 - (y2 - y1) * 0.25f
            val xa = cx1.toInt().coerceIn(0, size - 2)
            val xb = cx2.toInt().coerceIn(xa + 1, size)
            val ya = cy1.toInt().coerceIn(0, size - 2)
            val yb = cy2.toInt().coerceIn(ya + 1, size)
            val vals = ArrayList<Float>(256)
            var y = ya
            while (y < yb) {
                var x = xa
                while (x < xb) {
                    vals.add(map[y * size + x])
                    x += 2
                }
                y += 2
            }
            if (vals.isEmpty()) return null
            vals.sort()
            return vals[vals.size / 2]
        }
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private lateinit var inputName: String

    var activeProvider: String = "unavailable"
        private set
    val available: Boolean get() = session != null

    var inputSize: Int = DEFAULT_INPUT_SIZE
        private set

    private var pixels = IntArray(0)
    private var input: FloatBuffer = FloatBuffer.allocate(0)
    private var output = FloatArray(0)
    private var medianScratch = FloatArray(0)

    /** Loads the model if present. Call off the main thread. */
    fun initialize(context: Context): Boolean {
        val bytes = loadModelBytes(context) ?: run {
            Log.i(TAG, "$modelFile not found, running without it")
            return false
        }
        val created = OrtSessions.create(env, bytes, TAG) ?: return false
        session = created.session
        activeProvider = created.providerLabel
        inputName = created.session.inputNames.first()

        // The input resolution comes from the model (1 x 3 x H x W)
        val shape = (created.session.inputInfo[inputName]?.info as? TensorInfo)?.shape
        inputSize = shape?.getOrNull(2)?.toInt()?.takeIf { it in 70..1030 } ?: DEFAULT_INPUT_SIZE

        pixels = IntArray(inputSize * inputSize)
        input = FloatBuffer.allocate(3 * inputSize * inputSize)
        output = FloatArray(inputSize * inputSize)
        medianScratch = FloatArray(inputSize * inputSize / 16 + 1)

        Log.i(TAG, "$modelFile ready on $activeProvider, input ${inputSize}x$inputSize")
        return true
    }

    /** @param square any square upright bitmap (e.g. the detector's 640 letterbox). */
    fun analyze(square: Bitmap): DepthMap? {
        val s = session ?: return null
        val t0 = SystemClock.elapsedRealtime()

        val scaled = if (square.width == inputSize && square.height == inputSize) square
        else Bitmap.createScaledBitmap(square, inputSize, inputSize, true)
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        val area = inputSize * inputSize
        val data = input.array()
        for (i in 0 until area) {
            val p = pixels[i]
            data[i] = (((p shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0]
            data[area + i] = (((p shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1]
            data[2 * area + i] = ((p and 0xFF) / 255f - MEAN[2]) / STD[2]
        }
        input.rewind()

        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        OnnxTensor.createTensor(env, input, shape).use { tensor ->
            s.run(mapOf(inputName to tensor)).use { result ->
                var got = false
                for (entry in result) {
                    val v = entry.value
                    if (v is OnnxTensor) {
                        val buf = v.floatBuffer
                        if (output.size != buf.remaining()) output = FloatArray(buf.remaining())
                        buf.get(output)
                        got = true
                        break
                    }
                }
                if (!got) return null
            }
        }

        // Scene median from a uniform subsample
        var n = 0
        var i = 0
        while (i < output.size && n < medianScratch.size) {
            medianScratch[n++] = output[i]
            i += 16
        }
        java.util.Arrays.sort(medianScratch, 0, n)
        val median = medianScratch[n / 2]

        return DepthMap(output, inputSize, median, SystemClock.elapsedRealtime() - t0)
    }

    fun close() {
        session?.close()
        session = null
    }

    private fun loadModelBytes(context: Context): ByteArray? {
        val pushed = File(context.getExternalFilesDir(null), "models/$modelFile")
        if (pushed.exists()) {
            Log.i(TAG, "Loading depth model from ${pushed.absolutePath}")
            return pushed.readBytes()
        }
        return try {
            context.assets.open(modelFile).use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }
}
