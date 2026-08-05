package dev.quad.shepherd.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import dev.quad.shepherd.guidance.DepthCalibrator
import dev.quad.shepherd.guidance.DistanceEstimator
import dev.quad.shepherd.guidance.GuidanceEngine
import java.nio.FloatBuffer

/** One processed camera frame: detections in camera-frame pixel space. */
data class FrameResult(
    val detections: List<Detection>,
    /**
     * Per-guidance-column obstacle distance in meters from the dense depth
     * map (entries <= 0 mean no signal); null when no depth model is loaded.
     */
    val columnDistances: FloatArray?,
    val frameWidth: Int,
    val frameHeight: Int,
    val latencyMs: Long,
    val depthLatencyMs: Long,
    /** The upright camera frame — reused for LLM scene description. */
    val frame: Bitmap,
)

/**
 * CameraX analyzer: YUV frame -> upright bitmap -> 640x640 letterbox ->
 * [DetectionEngine] (+ optional [DepthEngine] on the same letterbox) ->
 * detections remapped to frame coordinates with distance estimates, plus
 * per-column depth distances for the guidance engine.
 *
 * Depth-to-meters calibration happens here: every untruncated detection
 * with a pinhole distance contributes a (disparity, meters) reference pair.
 *
 * Runs on the single analysis executor; with STRATEGY_KEEP_ONLY_LATEST the
 * camera drops frames while inference is busy, so FPS self-throttles.
 */
class FrameAnalyzer(
    private val engine: DetectionEngine,
    private val depthEngine: DepthEngine?,
    private val onResult: (FrameResult) -> Unit,
) : ImageAnalysis.Analyzer {

    private val size = DetectionEngine.INPUT_SIZE
    private val calibrator = DepthCalibrator()
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
        val detectLatency = SystemClock.elapsedRealtime() - t0

        // Dense depth on the same letterboxed frame (skipped without a model)
        val depth = depthEngine?.takeIf { it.available }?.analyze(letterboxBitmap)

        // Calibration: untruncated detections with a pinhole distance give
        // (disparity, meters) reference pairs anchoring the depth scale
        if (depth != null) {
            val toDepth = depth.size.toFloat() / size
            for (d in modelSpace) {
                val est = DistanceEstimator.estimate(d.label, d.height) ?: continue
                if (d.y1 < 8f || d.y2 > size - 8f) continue
                depth.boxMedian(
                    d.x1 * toDepth, d.y1 * toDepth,
                    d.x2 * toDepth, d.y2 * toDepth,
                )?.let { calibrator.addSample(it, est) }
            }
        }

        val columnDistances = depth?.let { dm ->
            val near = dm.columnNearField(GuidanceEngine.NUM_COLUMNS)
            FloatArray(near.size) { c -> calibrator.convert(near[c], dm.sceneMedian) ?: 0f }
        }

        // Map 640-space boxes back into camera-frame space; attach distances
        // (pinhole estimate + close-range corrections)
        val sizeF = size.toFloat()
        val detections = modelSpace.map { d ->
            val x1 = ((d.x1 - padX) / scale).coerceIn(0f, upright.width.toFloat())
            val y1 = ((d.y1 - padY) / scale).coerceIn(0f, upright.height.toFloat())
            val x2 = ((d.x2 - padX) / scale).coerceIn(0f, upright.width.toFloat())
            val y2 = ((d.y2 - padY) / scale).coerceIn(0f, upright.height.toFloat())
            val areaFraction = (d.width * d.height) / (sizeF * sizeF)
            val dist = DistanceEstimator.applyCloseness(
                estimate = DistanceEstimator.estimate(d.label, d.height),
                areaFraction = areaFraction,
                touchesTop = d.y1 < 6f,
                touchesBottom = d.y2 > sizeF - 6f,
            )
            d.copy(x1 = x1, y1 = y1, x2 = x2, y2 = y2, distanceMeters = dist)
        }

        onResult(
            FrameResult(
                detections = detections,
                columnDistances = columnDistances,
                frameWidth = upright.width,
                frameHeight = upright.height,
                latencyMs = detectLatency,
                depthLatencyMs = depth?.latencyMs ?: 0L,
                frame = upright,
            )
        )
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
