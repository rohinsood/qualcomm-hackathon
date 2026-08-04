package dev.quad.shepherd.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import dev.quad.shepherd.guidance.DistanceEstimator
import java.nio.FloatBuffer

/** One processed camera frame: detections in camera-frame pixel space. */
data class FrameResult(
    val detections: List<Detection>,
    val frameWidth: Int,
    val frameHeight: Int,
    val latencyMs: Long,
    /** The upright camera frame — reused for LLM scene description. */
    val frame: Bitmap,
)

/**
 * CameraX analyzer: YUV frame -> upright bitmap -> 640x640 letterbox ->
 * CHW float tensor -> [DetectionEngine] -> detections remapped to frame
 * coordinates, with a distance estimate attached per detection.
 *
 * Runs on the single analysis executor; with STRATEGY_KEEP_ONLY_LATEST the
 * camera naturally drops frames while inference is busy, so effective FPS
 * self-throttles to what the NPU sustains.
 */
class FrameAnalyzer(
    private val engine: DetectionEngine,
    private val onResult: (FrameResult) -> Unit,
) : ImageAnalysis.Analyzer {

    private val size = DetectionEngine.INPUT_SIZE
    private val inputBuffer: FloatBuffer = FloatBuffer.allocate(3 * size * size)
    private val pixels = IntArray(size * size)
    private val letterboxBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(letterboxBitmap)
    private val clearPaint = Paint().apply { color = android.graphics.Color.BLACK }

    override fun analyze(image: ImageProxy) {
        val rotation = image.imageInfo.rotationDegrees
        val bitmap = image.toBitmap()
        image.close()

        val upright = if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, false)
        } else bitmap

        // Letterbox into 640x640, preserving aspect ratio
        val scale = size.toFloat() / maxOf(upright.width, upright.height)
        val scaledW = (upright.width * scale)
        val scaledH = (upright.height * scale)
        val padX = (size - scaledW) / 2f
        val padY = (size - scaledH) / 2f

        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), clearPaint)
        val m = Matrix().apply {
            postScale(scale, scale)
            postTranslate(padX, padY)
        }
        canvas.drawBitmap(upright, m, null)

        fillTensor(letterboxBitmap)

        val t0 = SystemClock.elapsedRealtime()
        val modelSpace = engine.detect(inputBuffer)
        val latency = SystemClock.elapsedRealtime() - t0

        // Map 640-space boxes back into camera-frame space and attach distances
        val detections = modelSpace.map { d ->
            val x1 = ((d.x1 - padX) / scale).coerceIn(0f, upright.width.toFloat())
            val y1 = ((d.y1 - padY) / scale).coerceIn(0f, upright.height.toFloat())
            val x2 = ((d.x2 - padX) / scale).coerceIn(0f, upright.width.toFloat())
            val y2 = ((d.y2 - padY) / scale).coerceIn(0f, upright.height.toFloat())
            d.copy(
                x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                // Distance is estimated in model space, where the focal constant is calibrated
                distanceMeters = DistanceEstimator.estimate(d.label, d.height),
            )
        }

        onResult(FrameResult(detections, upright.width, upright.height, latency, upright))
    }

    /** ARGB bitmap -> CHW float tensor, RGB in 0..1. */
    private fun fillTensor(bitmap: Bitmap) {
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        inputBuffer.rewind()
        val area = size * size
        val data = inputBuffer.array()
        for (i in 0 until area) {
            val p = pixels[i]
            data[i] = ((p shr 16) and 0xFF) / 255f            // R
            data[area + i] = ((p shr 8) and 0xFF) / 255f      // G
            data[2 * area + i] = (p and 0xFF) / 255f          // B
        }
        inputBuffer.rewind()
    }
}
