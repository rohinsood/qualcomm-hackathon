package dev.quad.shepherd.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

/**
 * Dense relative depth via Depth-Anything-V2-Small, on the same ONNX
 * Runtime / QNN (Hexagon NPU) stack as [DetectionEngine].
 *
 * The model predicts *relative* inverse depth per pixel ("disparity":
 * higher = closer) with unknown per-frame scale;
 * [dev.quad.shepherd.guidance.DepthCalibrator] maps it to meters. This is
 * the class-free proximity sense the detector lacks: walls, poles, and
 * furniture register here even though YOLO has no class for them.
 *
 * The model file is optional; when absent the app runs detection-only.
 * Not thread-safe. Call from the single analysis thread; the returned map
 * buffer is reused across calls.
 */
class DepthEngine {

    companion object {
        private const val TAG = "DepthEngine"
        const val MODEL_FILE = "depth_anything_v2_small.onnx"
        const val INPUT_SIZE = 518
        // ImageNet normalization, as used by the Depth-Anything preprocessor
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }

    /** One frame's disparity map plus summary statistics. */
    class DepthMap(
        /** Row-major size x size disparity map; higher = closer. */
        val map: FloatArray,
        val size: Int,
        /** Median disparity across the frame, the scene reference level. */
        val sceneMedian: Float,
        val latencyMs: Long,
    ) {
        /**
         * Near-field disparity (~85th percentile) per vertical column,
         * sampled in the corridor band (25%..70% of frame height) so that
         * the sky and the ground at the user's feet do not dominate.
         */
        fun columnNearField(numColumns: Int): FloatArray {
            val rowLo = (size * 0.25f).toInt()
            val rowHi = (size * 0.70f).toInt()
            val colW = size / numColumns
            val scratch = FloatArray(((rowHi - rowLo) / 3 + 1) * (colW / 3 + 1))
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
                        x += 3
                    }
                    y += 3
                }
                if (n > 0) {
                    java.util.Arrays.sort(scratch, 0, n)
                    out[c] = scratch[(n * 85) / 100]
                }
            }
            return out
        }

        /** Median disparity in the central 50% of a box (map coordinates). */
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
                    x += 4
                }
                y += 4
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

    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val input: FloatBuffer = FloatBuffer.allocate(3 * INPUT_SIZE * INPUT_SIZE)
    private var output = FloatArray(INPUT_SIZE * INPUT_SIZE)
    private val medianScratch = FloatArray(INPUT_SIZE * INPUT_SIZE / 64 + 1)

    /** Loads the model if present. Call off the main thread. */
    fun initialize(context: Context): Boolean {
        val bytes = loadModelBytes(context) ?: run {
            Log.i(TAG, "$MODEL_FILE not found, running detection-only")
            return false
        }
        session = try {
            val opts = OrtSession.SessionOptions()
            opts.addQnn(
                mapOf(
                    "backend_path" to "libQnnHtp.so",
                    "htp_performance_mode" to "burst",
                    "enable_htp_fp16_precision" to "1",
                )
            )
            env.createSession(bytes, opts).also { activeProvider = "NPU" }
        } catch (e: Exception) {
            Log.w(TAG, "QNN EP unavailable for depth, trying CPU", e)
            try {
                env.createSession(bytes, OrtSession.SessionOptions())
                    .also { activeProvider = "CPU" }
            } catch (e2: Exception) {
                Log.e(TAG, "Depth session creation failed", e2)
                null
            }
        }
        session?.let {
            inputName = it.inputNames.first()
            Log.i(TAG, "Depth session ready on $activeProvider")
        }
        return available
    }

    /** @param square any square upright bitmap (e.g. the detector's 640 letterbox). */
    fun analyze(square: Bitmap): DepthMap? {
        val s = session ?: return null
        val t0 = SystemClock.elapsedRealtime()

        val scaled = if (square.width == INPUT_SIZE && square.height == INPUT_SIZE) square
        else Bitmap.createScaledBitmap(square, INPUT_SIZE, INPUT_SIZE, true)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val area = INPUT_SIZE * INPUT_SIZE
        val data = input.array()
        for (i in 0 until area) {
            val p = pixels[i]
            data[i] = (((p shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0]
            data[area + i] = (((p shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1]
            data[2 * area + i] = ((p and 0xFF) / 255f - MEAN[2]) / STD[2]
        }
        input.rewind()

        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
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
            i += 64
        }
        java.util.Arrays.sort(medianScratch, 0, n)
        val median = medianScratch[n / 2]

        return DepthMap(output, INPUT_SIZE, median, SystemClock.elapsedRealtime() - t0)
    }

    fun close() {
        session?.close()
        session = null
    }

    private fun loadModelBytes(context: Context): ByteArray? {
        val pushed = File(context.getExternalFilesDir(null), "models/$MODEL_FILE")
        if (pushed.exists()) {
            Log.i(TAG, "Loading depth model from ${pushed.absolutePath}")
            return pushed.readBytes()
        }
        return try {
            context.assets.open(MODEL_FILE).use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }
}
