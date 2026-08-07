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
 * Walkability segmentation engine, spec-driven so two models can run as an
 * ENSEMBLE with complementary domains:
 *
 *  - [FFNET]: Qualcomm FFNet-78S-LowRes int8 (Cityscapes, 19 classes) —
 *    the outdoor expert (road/sidewalk/terrain), ~3 ms on the Hexagon.
 *  - [ADE]: SegFormer-B0 (ADE20K, 150 classes) — knows what a FLOOR is;
 *    the indoor member (floor/rug/path/earth + the outdoor classes).
 *
 * Both prefer the NPU and fall through the usual tiers. Model files live
 * in `<external-files>/models/`; a missing file just disables that member.
 */
class SegEngine(private val spec: Spec) {

    data class Spec(
        val name: String,
        val modelFile: String,
        val inW: Int,
        val inH: Int,
        val outW: Int,
        val outH: Int,
        val numClasses: Int,
        val walkable: BooleanArray,
        /** Apply ImageNet mean/std to the 0..1 input (HF exports). */
        val imagenetNorm: Boolean,
    )

    companion object {
        private const val TAG = "SegEngine"

        /** Cityscapes walkable: road, sidewalk, terrain. */
        val FFNET = Spec(
            name = "ffnet",
            modelFile = "models/ffnet_78s_lowres.onnx",
            inW = 1024, inH = 512, outW = 256, outH = 128, numClasses = 19,
            walkable = BooleanArray(19).also { it[0] = true; it[1] = true; it[9] = true },
            imagenetNorm = false,
        )

        /** ADE20K walkable: floor, road, grass, sidewalk, earth, rug, path. */
        val ADE = Spec(
            name = "segformer-ade",
            modelFile = "models/segformer_b0_ade.onnx",
            inW = 512, inH = 512, outW = 128, outH = 128, numClasses = 150,
            walkable = BooleanArray(150).also {
                intArrayOf(3, 6, 9, 11, 13, 28, 52).forEach { i -> it[i] = true }
            },
            imagenetNorm = true,
        )

        private val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String? = null

    /** The w8a8 export quantizes at the boundary: uint8 in, uint8 out. */
    private var uint8Io = false

    var activeProvider: String = "none"
        private set

    val available: Boolean get() = session != null
    val name: String get() = spec.name
    val outW: Int get() = spec.outW
    val outH: Int get() = spec.outH
    val walkable: BooleanArray get() = spec.walkable

    private val inputBuffer: FloatBuffer = FloatBuffer.allocate(3 * spec.inH * spec.inW)
    private val uint8Buffer: java.nio.ByteBuffer =
        java.nio.ByteBuffer.allocateDirect(3 * spec.inH * spec.inW)
    private val pixels = IntArray(spec.inW * spec.inH)
    private val scaled = Bitmap.createBitmap(spec.inW, spec.inH, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(scaled)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect(0, 0, spec.inW, spec.inH)
    private val classOut = ByteArray(spec.outW * spec.outH)

    fun initialize(context: Context): Boolean {
        if (!dev.quad.shepherd.Loadout.SEGMENTATION) {
            Log.i(TAG, "${spec.name} disabled by Loadout.SEGMENTATION")
            return false
        }
        val f = File(context.getExternalFilesDir(null), spec.modelFile)
        if (!f.isFile) {
            Log.i(TAG, "no ${spec.modelFile} — ${spec.name} off")
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
            Log.i(TAG, "${spec.name} ready on ${created.providerLabel} (uint8Io=$uint8Io)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "${spec.name} init failed", e)
            false
        }
    }

    /**
     * Segment the upright camera frame. Returns row-major outH x outW class
     * ids, or null. Single-threaded caller — buffers are reused, and the
     * returned array is reused between calls.
     */
    fun segment(frame: Bitmap): ByteArray? {
        val s = session ?: return null
        val name = inputName ?: return null
        return try {
            srcRect.set(0, 0, frame.width, frame.height)
            canvas.drawBitmap(frame, srcRect, dstRect, paint)

            val shape = longArrayOf(1, 3, spec.inH.toLong(), spec.inW.toLong())
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
            Log.w(TAG, "${spec.name} segment failed: ${e.message?.take(120)}")
            null
        }
    }

    private fun argmaxF32(logits: FloatBuffer) {
        val area = spec.outW * spec.outH
        for (p in 0 until area) {
            var best = 0
            var bestV = logits.get(p)
            for (c in 1 until spec.numClasses) {
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
        val area = spec.outW * spec.outH
        for (p in 0 until area) {
            var best = 0
            var bestV = logits.get(p).toInt() and 0xFF
            for (c in 1 until spec.numClasses) {
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
        bitmap.getPixels(pixels, 0, spec.inW, 0, 0, spec.inW, spec.inH)
        inputBuffer.rewind()
        val area = spec.inW * spec.inH
        val data = inputBuffer.array()
        if (spec.imagenetNorm) {
            for (i in 0 until area) {
                val p = pixels[i]
                data[i] = (((p shr 16) and 0xFF) / 255f - IMAGENET_MEAN[0]) / IMAGENET_STD[0]
                data[area + i] = (((p shr 8) and 0xFF) / 255f - IMAGENET_MEAN[1]) / IMAGENET_STD[1]
                data[2 * area + i] = ((p and 0xFF) / 255f - IMAGENET_MEAN[2]) / IMAGENET_STD[2]
            }
        } else {
            for (i in 0 until area) {
                val p = pixels[i]
                data[i] = ((p shr 16) and 0xFF) / 255f
                data[area + i] = ((p shr 8) and 0xFF) / 255f
                data[2 * area + i] = (p and 0xFF) / 255f
            }
        }
        inputBuffer.rewind()
    }

    private fun fillUint8(bitmap: Bitmap) {
        bitmap.getPixels(pixels, 0, spec.inW, 0, 0, spec.inW, spec.inH)
        uint8Buffer.rewind()
        val area = spec.inW * spec.inH
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
