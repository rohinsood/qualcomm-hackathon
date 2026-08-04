package dev.quad.shepherd.vision

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

/**
 * Runs a YOLO-family object detector via ONNX Runtime, preferring the
 * Qualcomm QNN Execution Provider so inference lands on the Hexagon NPU
 * (HTP) of the Snapdragon 8 Elite. Falls back to CPU if QNN can't load
 * (e.g. non-Qualcomm device, emulator).
 *
 * Model resolution order:
 *  1. `<external-files>/models/yolov8_det.onnx` — push with:
 *     adb push yolov8_det.onnx /sdcard/Android/data/dev.quad.shepherd/files/models/
 *  2. bundled asset `yolov8_det.onnx` (see scripts/fetch_model.ps1)
 */
class DetectionEngine {

    companion object {
        private const val TAG = "DetectionEngine"
        const val MODEL_FILE = "yolov8_det.onnx"
        const val INPUT_SIZE = 640
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private lateinit var inputName: String

    var activeProvider: String = "uninitialized"
        private set

    /** Loads the model and creates the ORT session. Call off the main thread. */
    fun initialize(context: Context): Boolean {
        val modelBytes = loadModelBytes(context) ?: run {
            Log.e(TAG, "No model found. Run scripts/fetch_model.ps1 or adb-push $MODEL_FILE.")
            return false
        }

        session = try {
            val opts = OrtSession.SessionOptions()
            opts.addQnn(
                mapOf(
                    // HTP = the Hexagon Tensor Processor (NPU) backend
                    "backend_path" to "libQnnHtp.so",
                    // Lowest latency for a live camera loop
                    "htp_performance_mode" to "burst",
                    // Lets fp32 models execute in fp16 on the NPU; ignored for
                    // pre-quantized (QDQ w8a8) models
                    "enable_htp_fp16_precision" to "1",
                )
            )
            env.createSession(modelBytes, opts).also { activeProvider = "Hexagon NPU (QNN)" }
        } catch (e: Exception) {
            Log.w(TAG, "QNN EP unavailable — falling back to CPU", e)
            env.createSession(modelBytes, OrtSession.SessionOptions())
                .also { activeProvider = "CPU" }
        }

        inputName = session!!.inputNames.first()
        Log.i(TAG, "Session ready on $activeProvider, input=$inputName")
        return true
    }

    /**
     * @param input CHW float tensor, RGB normalized to 0..1, 1x3x640x640.
     * @return detections in 640x640 model space.
     */
    fun detect(input: FloatBuffer): List<Detection> {
        val s = session ?: return emptyList()
        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        OnnxTensor.createTensor(env, input, shape).use { tensor ->
            s.run(mapOf(inputName to tensor)).use { result ->
                val tensors = ArrayList<Pair<LongArray, FloatArray>>()
                for (entry in result) {
                    val v = entry.value
                    if (v is OnnxTensor) tensors += v.info.shape to toFloatArray(v)
                }
                return YoloPostProcessor.parse(tensors)
            }
        }
    }

    fun close() {
        session?.close()
        session = null
    }

    private fun loadModelBytes(context: Context): ByteArray? {
        val pushed = File(context.getExternalFilesDir(null), "models/$MODEL_FILE")
        if (pushed.exists()) {
            Log.i(TAG, "Loading model from ${pushed.absolutePath}")
            return pushed.readBytes()
        }
        return try {
            context.assets.open(MODEL_FILE).use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    /** Class-id outputs may be int64/int32/uint8 depending on the export. */
    private fun toFloatArray(t: OnnxTensor): FloatArray = when (t.info.type) {
        OnnxJavaType.FLOAT -> {
            val buf = t.floatBuffer
            FloatArray(buf.remaining()).also { buf.get(it) }
        }
        OnnxJavaType.INT64 -> {
            val buf = t.longBuffer
            FloatArray(buf.remaining()) { buf.get(it).toFloat() }
        }
        OnnxJavaType.INT32 -> {
            val buf = t.intBuffer
            FloatArray(buf.remaining()) { buf.get(it).toFloat() }
        }
        OnnxJavaType.UINT8, OnnxJavaType.INT8 -> {
            val buf = t.byteBuffer
            FloatArray(buf.remaining()) { (buf.get(it).toInt() and 0xFF).toFloat() }
        }
        else -> {
            val buf = t.floatBuffer
            FloatArray(buf.remaining()).also { buf.get(it) }
        }
    }
}
