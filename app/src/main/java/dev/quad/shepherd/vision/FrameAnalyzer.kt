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
     * Depth runs time-gated, so between depth frames this carries the most
     * recent depth result.
     */
    val columnDistances: FloatArray?,
    val frameWidth: Int,
    val frameHeight: Int,
    val latencyMs: Long,
    /** Depth model latency for frames where it ran; 0 on gated frames. */
    val depthLatencyMs: Long,
    /** The upright camera frame — reused for LLM scene description. */
    val frame: Bitmap,
    /** Colorized depth map cropped to the camera frame (debug mode only). */
    val depthDebug: Bitmap? = null,
    /** Depth analysis band boundaries, in camera-frame Y coordinates. */
    val corridorTop: Float = 0f,
    val corridorBottom: Float = 0f,
)

/**
 * CameraX analyzer: YUV frame -> upright bitmap -> 640x640 letterbox ->
 * [DetectionEngine] every frame, [DepthEngine] time-gated (walls do not
 * move at frame rate; detection guidance must). Detections are remapped to
 * frame coordinates with distance estimates, and the guidance engine
 * receives per-column metric depth from the freshest depth frame.
 *
 * Detections with known-height pinhole distances nudge the depth model's
 * scale via [DepthCalibrator]; unknown-class detections get their distance
 * read *from* the depth map. With [depthDebugEnabled] the colorized depth
 * map is attached to each result for the on-screen debug view.
 */
class FrameAnalyzer(
    private val engine: DetectionEngine,
    private val depthEngine: DepthEngine?,
    private val onResult: (FrameResult) -> Unit,
) : ImageAnalysis.Analyzer {

    companion object {
        /** Minimum interval between depth model runs. */
        private const val DEPTH_INTERVAL_MS = 300L

        /**
         * Detection duty-cycle cap (~11 fps). Running back-to-back on every
         * camera frame kept the GPU pinned and thermal-throttled the whole
         * SoC — guidance needs 10 Hz, not 30.
         */
        private const val MIN_FRAME_INTERVAL_MS = 90L
        /** Fixed color ramp range for the debug view (meters). */
        private const val DEBUG_NEAR_M = 0.3f
        private const val DEBUG_FAR_M = 6.0f
    }

    /** Toggled from the UI; when true, results carry a colorized depth map. */
    @Volatile var depthDebugEnabled = false

    private val size = DetectionEngine.INPUT_SIZE
    private val calibrator = DepthCalibrator()
    private val inputBuffer: FloatBuffer = FloatBuffer.allocate(3 * size * size)
    private val pixels = IntArray(size * size)
    private val letterboxBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(letterboxBitmap)
    private val clearPaint = Paint().apply { color = android.graphics.Color.BLACK }

    private var lastRunAt = 0L
    private var lastDepthAt = 0L
    private var lastColumnDistances: FloatArray? = null
    private var lastDepthDebug: Bitmap? = null

    override fun analyze(image: ImageProxy) {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastRunAt < MIN_FRAME_INTERVAL_MS) {
            image.close()
            return
        }
        lastRunAt = nowMs

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

        // Dense metric depth, time-gated: walls don't move at frame rate
        val depth = if (
            depthEngine?.available == true &&
            SystemClock.elapsedRealtime() - lastDepthAt >= DEPTH_INTERVAL_MS
        ) {
            depthEngine.analyze(letterboxBitmap)?.also {
                lastDepthAt = SystemClock.elapsedRealtime()
            }
        } else null

        // Scale refinement: untruncated detections with a pinhole distance
        // give (model meters, reference meters) pairs
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

        if (depth != null) {
            val near = depth.columnNearField(GuidanceEngine.NUM_COLUMNS)
            lastColumnDistances = FloatArray(near.size) { c ->
                calibrator.convert(near[c]) ?: 0f
            }
            lastDepthDebug = if (depthDebugEnabled) {
                colorizeDepth(depth, padX, padY)
            } else null
        }
        if (!depthDebugEnabled) lastDepthDebug = null

        // Map 640-space boxes back into camera-frame space; attach distances
        // (pinhole estimate + close-range corrections + metric depth lookup
        // for classes without a height prior)
        val sizeF = size.toFloat()
        val detections = modelSpace.map { d ->
            val x1 = ((d.x1 - padX) / scale).coerceIn(0f, upright.width.toFloat())
            val y1 = ((d.y1 - padY) / scale).coerceIn(0f, upright.height.toFloat())
            val x2 = ((d.x2 - padX) / scale).coerceIn(0f, upright.width.toFloat())
            val y2 = ((d.y2 - padY) / scale).coerceIn(0f, upright.height.toFloat())
            val areaFraction = (d.width * d.height) / (sizeF * sizeF)
            var dist = DistanceEstimator.applyCloseness(
                estimate = DistanceEstimator.estimate(d.label, d.height),
                areaFraction = areaFraction,
                touchesTop = d.y1 < 6f,
                touchesBottom = d.y2 > sizeF - 6f,
            )
            if (dist == null && depth != null) {
                val toDepth = depth.size.toFloat() / size
                dist = depth.boxMedian(
                    d.x1 * toDepth, d.y1 * toDepth,
                    d.x2 * toDepth, d.y2 * toDepth,
                )?.let { calibrator.convert(it) }
            }
            d.copy(x1 = x1, y1 = y1, x2 = x2, y2 = y2, distanceMeters = dist)
        }

        onResult(
            FrameResult(
                detections = detections,
                columnDistances = lastColumnDistances,
                frameWidth = upright.width,
                frameHeight = upright.height,
                latencyMs = detectLatency,
                depthLatencyMs = depth?.latencyMs ?: 0L,
                frame = upright,
                depthDebug = lastDepthDebug,
                corridorTop = (size * DepthEngine.CORRIDOR_TOP - padY) / scale,
                corridorBottom = (size * DepthEngine.CORRIDOR_BOTTOM - padY) / scale,
            )
        )
    }

    /**
     * Colorize the depth map (red = near .. blue = far, fixed 0.3-6 m ramp)
     * and crop it to the camera-frame region inside the letterbox square so
     * it aligns 1:1 with the preview.
     */
    private fun colorizeDepth(dm: DepthEngine.DepthMap, padX: Float, padY: Float): Bitmap {
        val ds = dm.size
        val rs = ds.toFloat() / size
        val cropX = (padX * rs).toInt().coerceIn(0, ds / 2 - 1)
        val cropY = (padY * rs).toInt().coerceIn(0, ds / 2 - 1)
        val cropW = ds - 2 * cropX
        val cropH = ds - 2 * cropY
        val px = IntArray(cropW * cropH)
        val scaleFactor = calibrator.currentScale
        for (y in 0 until cropH) {
            val row = (y + cropY) * ds
            for (x in 0 until cropW) {
                px[y * cropW + x] = depthColor(dm.map[row + x + cropX] * scaleFactor)
            }
        }
        return Bitmap.createBitmap(px, cropW, cropH, Bitmap.Config.ARGB_8888)
    }

    private fun depthColor(meters: Float): Int {
        if (!meters.isFinite()) return 0xFF000000.toInt()
        val t = ((meters - DEBUG_NEAR_M) / (DEBUG_FAR_M - DEBUG_NEAR_M)).coerceIn(0f, 1f)
        val r: Int
        val g: Int
        val b: Int
        when {
            t < 0.33f -> { val u = t / 0.33f; r = 255; g = (255 * u).toInt(); b = 0 }
            t < 0.66f -> { val u = (t - 0.33f) / 0.33f; r = (255 * (1 - u)).toInt(); g = 255; b = 0 }
            else -> { val u = (t - 0.66f) / 0.34f; r = 0; g = (255 * (1 - u)).toInt(); b = (255 * u).toInt() }
        }
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
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
