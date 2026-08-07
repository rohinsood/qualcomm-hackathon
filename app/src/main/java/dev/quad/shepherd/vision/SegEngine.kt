package dev.quad.shepherd.vision

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

/**
 * Walkability segmentation: Qualcomm FFNet-78S-LowRes (Cityscapes, 19
 * classes) through ONNX Runtime QNN. Prefers the Hexagon NPU — the int8
 * model runs in ~3 ms there, a negligible tax on the SLM — falling back
 * through the usual tiers. Input is the upright camera frame resized to
 * 1024x512 (values 0..1, normalization baked into the graph); output is a
 * 256x128 argmax class map consumed by the walkability mask builder.
 *
 * Model files (`ffnet_78s_lowres.onnx` + `.data`) live in
 * `<external-files>/models/`; without them [available] stays false and the
 * app falls back to the v1 column guidance.
 */
class SegEngine {

    companion object {
        private const val TAG = "SegEngine"
        const val IN_W = 1024
        const val IN_H = 512
        const val OUT_W = 256
        const val OUT_H = 128
        const val NUM_CLASSES = 19
        private const val MODEL_FILE = "models/ffnet_78s_lowres.onnx"

        /** Cityscapes train ids considered walkable: road, sidewalk, terrain. */
        val WALKABLE_CLASSES = booleanArrayOf(
            true, true, false, false, false, false, false, false, false, true,
            false, false, false, false, false, false, false, false, false,
        )
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String? = null

    /** The w8a8 export quantizes at the boundary: uint8 in, uint8 out. */
    private var uint8Io = false

    var activeProvider: String = "none"
        private set

    val available: Boolean get() = session != null

    private val inputBuffer: FloatBuffer = FloatBuffer.allocate(3 * IN_H * IN_W)
    private val uint8Buffer: java.nio.ByteBuffer =
        java.nio.ByteBuffer.allocateDirect(3 * IN_H * IN_W)
    private val pixels = IntArray(IN_W * IN_H)
    private val scaled = Bitmap.createBitmap(IN_W, IN_H, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(scaled)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect(0, 0, IN_W, IN_H)
    private val classOut = ByteArray(OUT_W * OUT_H)

    fun initialize(context: Context): Boolean {
        val f = File(context.getExternalFilesDir(null), MODEL_FILE)
        if (!f.isFile) {
            Log.i(TAG, "no $MODEL_FILE — walkability segmentation off")
            return false
        }
        return try {
            val e = OrtEnvironment.getEnvironment()
            env = e
            val created = OrtSessions.createFromPath(e, f.absolutePath, TAG, preferNpu = true)
                ?: return false
            session = created.session
            inputName = created.session.inputNames.first()
            val info = created.session.inputInfo[inputName]?.info as? TensorInfo
            uint8Io = info?.type == OnnxJavaType.UINT8
            activeProvider = created.providerLabel
            Log.i(TAG, "FFNet ready on ${created.providerLabel} (uint8Io=$uint8Io)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "init failed", e)
            false
        }
    }

    /**
     * Segment the upright camera frame. Returns a row-major OUT_H x OUT_W
     * array of Cityscapes class ids, or null when unavailable/failed.
     * Single-threaded caller (the analysis thread) — buffers are reused.
     */
    fun segment(frame: Bitmap): ByteArray? {
        val s = session ?: return null
        val name = inputName ?: return null
        return try {
            srcRect.set(0, 0, frame.width, frame.height)
            canvas.drawBitmap(frame, srcRect, dstRect, paint)

            val shape = longArrayOf(1, 3, IN_H.toLong(), IN_W.toLong())
            val tensor = if (uint8Io) {
                fillUint8(scaled)
                OnnxTensor.createTensor(env, uint8Buffer, shape, OnnxJavaType.UINT8)
            } else {
                fillFloat(scaled)
                OnnxTensor.createTensor(env, inputBuffer, shape)
            }
            tensor.use {
                s.run(mapOf(name to it)).use { results ->
                    val out = results[0] as OnnxTensor
                    if (out.info.type == OnnxJavaType.UINT8) argmaxU8(out.byteBuffer)
                    else argmaxF32(out.floatBuffer)
                }
            }
            classOut
        } catch (e: Exception) {
            Log.w(TAG, "segment failed: ${e.message?.take(120)}")
            null
        }
    }

    private fun argmaxF32(logits: FloatBuffer) {
        val area = OUT_W * OUT_H
        for (p in 0 until area) {
            var best = 0
            var bestV = logits.get(p)
            for (c in 1 until NUM_CLASSES) {
                val v = logits.get(c * area + p)
                if (v > bestV) {
                    bestV = v
                    best = c
                }
            }
            classOut[p] = best.toByte()
        }
    }

    /** uint8 logits share one quantization scale: raw argmax is valid. */
    private fun argmaxU8(logits: java.nio.ByteBuffer) {
        val area = OUT_W * OUT_H
        for (p in 0 until area) {
            var best = 0
            var bestV = logits.get(p).toInt() and 0xFF
            for (c in 1 until NUM_CLASSES) {
                val v = logits.get(c * area + p).toInt() and 0xFF
                if (v > bestV) {
                    bestV = v
                    best = c
                }
            }
            classOut[p] = best.toByte()
        }
    }

    private fun fillFloat(bitmap: Bitmap) {
        bitmap.getPixels(pixels, 0, IN_W, 0, 0, IN_W, IN_H)
        inputBuffer.rewind()
        val area = IN_W * IN_H
        val data = inputBuffer.array()
        for (i in 0 until area) {
            val p = pixels[i]
            data[i] = ((p shr 16) and 0xFF) / 255f
            data[area + i] = ((p shr 8) and 0xFF) / 255f
            data[2 * area + i] = (p and 0xFF) / 255f
        }
        inputBuffer.rewind()
    }

    private fun fillUint8(bitmap: Bitmap) {
        bitmap.getPixels(pixels, 0, IN_W, 0, 0, IN_W, IN_H)
        uint8Buffer.rewind()
        val area = IN_W * IN_H
        for (i in 0 until area) {
            val p = pixels[i]
            uint8Buffer.put(i, ((p shr 16) and 0xFF).toByte())
            uint8Buffer.put(area + i, ((p shr 8) and 0xFF).toByte())
            uint8Buffer.put(2 * area + i, (p and 0xFF).toByte())
        }
        uint8Buffer.rewind()
    }

    fun close() {
        runCatching { session?.close() }
        session = null
    }
}
