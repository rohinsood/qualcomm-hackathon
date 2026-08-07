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
import dev.quad.shepherd.path.PathPipeline
import dev.quad.shepherd.path.PolarPlanner
import dev.quad.shepherd.path.WalkableColumns
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
    /** The upright camera frame — reused for OCR and (later) the VLM. */
    val frame: Bitmap,
    /** Colorized depth map cropped to the camera frame (debug mode only). */
    val depthDebug: Bitmap? = null,
    /** Depth analysis band boundaries, in camera-frame Y coordinates. */
    val corridorTop: Float = 0f,
    val corridorBottom: Float = 0f,
    /** v2: the polar plan from the traversability grid, when active. */
    val plan: PolarPlanner.Plan? = null,
    /** v2: BEV grid debug colors (gridW x gridH), debug mode only. */
    val gridDebug: IntArray? = null,
    val gridW: Int = 0,
    val gridH: Int = 0,
    /** Gravity snap applied to this frame; the overlay counter-rotates. */
    val gravityDeg: Int = 0,
)

/**
 * CameraX analyzer: YUV frame -> upright bitmap -> 640x640 letterbox ->
 * [DetectionEngine] every (throttled) frame; [DepthEngine] + [SegEngine]
 * time-gated. v2: on every depth frame the metric depth map and the FFNet
 * walkability mask are fused into the [PathPipeline]'s bird's-eye grid,
 * and the polar planner runs per frame on the accumulated grid — steering
 * comes from persistent world-space evidence, not a single frame.
 */
class FrameAnalyzer(
    private val engine: DetectionEngine,
    /** INDOOR metric depth (Hypersim fine-tune). */
    private val depthEngine: DepthEngine?,
    private val onResult: (FrameResult) -> Unit,
    /** FFNet, Cityscapes — the OUTDOOR walkability expert. */
    private val segEngine: SegEngine? = null,
    private val path: PathPipeline? = null,
    /** SegFormer ADE20K — the INDOOR walkability expert. */
    private val segEngine2: SegEngine? = null,
    /** OUTDOOR metric depth (VKITTI fine-tune), used when [indoorMode] off. */
    private val depthEngineOutdoor: DepthEngine? = null,
) : ImageAnalysis.Analyzer {

    /**
     * Mirrors the nav mode: each domain gets ITS models — indoor runs
     * ADE seg + Hypersim depth, outdoor runs FFNet seg + VKITTI depth
     * (falling back to whichever member is actually loaded).
     */
    @Volatile var indoorMode = false

    companion object {
        /** Minimum interval between depth model runs. */
        private const val DEPTH_INTERVAL_MS = 300L

        /**
         * Detection duty-cycle cap (~11 fps). Running back-to-back on every
         * camera frame kept the GPU pinned and thermal-throttled the whole
         * SoC — guidance needs 10 Hz, not 30.
         */
        private const val MIN_FRAME_INTERVAL_MS = 90L

        /**
         * Detection interval. Steering no longer uses YOLO (the grid does
         * that job); boxes only feed labels, the blackboard, and the depth
         * scale calibration — 1 Hz is plenty, and the freed GPU time goes
         * to the depth model, which IS latency-critical.
         */
        private const val DETECT_INTERVAL_MS = 1000L

        /** Fixed color ramp range for the debug view (meters). */
        private const val DEBUG_NEAR_M = 0.3f
        private const val DEBUG_FAR_M = 6.0f
    }

    /** Toggled from the UI; when true, results carry debug renderings. */
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
    private var lastDetectAt = 0L
    private var lastModelSpace: List<Detection> = emptyList()
    private var lastDetectLatency = 0L
    private var lastColumnDistances: FloatArray? = null
    private var lastDepthDebug: Bitmap? = null

    /**
     * The ADE20K ensemble member runs on its own thread: in NPU/CPU mixed
     * mode it is far slower than FFNet, and inline it stalled the depth →
     * grid → plan hot path. Walkability is soft evidence, so a mask a few
     * hundred ms stale is fine. The engine reuses its output buffer, so the
     * worker publishes a copy.
     */
    private val adeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ade-seg").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    @Volatile private var adeBusy = false
    @Volatile private var lastAdeSeg: ByteArray? = null
    @Volatile private var lastAdeMs = 0L
    private var timeLogCounter = 0

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

        val sensorUpright = if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, false)
        } else bitmap

        // Gravity-upright: when the phone is held landscape (or upside
        // down), rotate the frame so the depth and seg models — which are
        // NOT rotation-invariant — always see an upright world. The angle
        // comes from the gravity roll, snapped to 90 degrees.
        val gravityDeg = path?.gravityUprightDeg ?: 0
        val upright = if (gravityDeg != 0) {
            val gm = Matrix().apply { postRotate(-gravityDeg.toFloat()) }
            Bitmap.createBitmap(
                sensorUpright, 0, 0, sensorUpright.width, sensorUpright.height, gm, false,
            )
        } else sensorUpright

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

        // Detection at ~1 Hz: it only feeds labels / blackboard / scale
        // calibration; the grid owns steering. Between runs the previous
        // boxes are reused.
        val runDetect = nowMs - lastDetectAt >= DETECT_INTERVAL_MS
        if (runDetect) {
            fillTensor(letterboxBitmap)
            val t0 = SystemClock.elapsedRealtime()
            lastModelSpace = engine.detect(inputBuffer)
            lastDetectLatency = SystemClock.elapsedRealtime() - t0
            lastDetectAt = nowMs
        }
        val modelSpace = lastModelSpace
        val detectLatency = lastDetectLatency

        // Dense metric depth, time-gated: walls don't move at frame rate.
        // The engine is domain-matched to the nav mode when both are loaded.
        val indoor = indoorMode
        val activeDepth = when {
            indoor -> depthEngine ?: depthEngineOutdoor
            else -> depthEngineOutdoor ?: depthEngine
        }
        val depth = if (
            activeDepth?.available == true &&
            SystemClock.elapsedRealtime() - lastDepthAt >= DEPTH_INTERVAL_MS
        ) {
            activeDepth.analyze(letterboxBitmap)?.also {
                lastDepthAt = SystemClock.elapsedRealtime()
                // The letterbox bars are black padding, yet the depth model
                // hallucinates geometry for them (up to 25% of the square!)
                // — poison for the grid and the ground estimate. Mask them.
                maskLetterboxBars(it, padX, padY)
            }
        } else null

        // Scale refinement: untruncated detections with a pinhole distance
        // give (model meters, reference meters) pairs. Only when the boxes
        // are from THIS frame — stale boxes against fresh depth mislead.
        if (depth != null && runDetect) {
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

        var ffnetMs = 0L
        var gridMs = 0L
        if (depth != null) {
            val near = depth.columnNearField(GuidanceEngine.NUM_COLUMNS)
            lastColumnDistances = FloatArray(near.size) { c ->
                calibrator.convert(near[c]) ?: 0f
            }
            lastDepthDebug = if (depthDebugEnabled) {
                colorizeDepth(depth, padX, padY)
            } else null

            // ---- v2: fold this depth frame into the traversability grid.
            // Segmentation is DOMAIN-MATCHED to the nav mode: outdoor runs
            // FFNet (Cityscapes, inline on the NPU), indoor runs the ADE20K
            // member (knows floors, async) — each expert on its own turf,
            // and half the seg compute of the always-both ensemble.
            path?.let { p ->
                val engA = if (indoor) null else segEngine
                val engB = if (indoor) segEngine2 else null
                val tSeg = SystemClock.elapsedRealtime()
                val segA = engA?.segment(upright)
                ffnetMs = SystemClock.elapsedRealtime() - tSeg
                if (engB != null && !adeBusy) {
                    adeBusy = true
                    val frameForAde = upright // fresh bitmap, read-only use
                    adeExecutor.execute {
                        try {
                            val t = SystemClock.elapsedRealtime()
                            val r = engB.segment(frameForAde)
                            lastAdeMs = SystemClock.elapsedRealtime() - t
                            if (r != null) lastAdeSeg = r.copyOf()
                        } finally {
                            adeBusy = false
                        }
                    }
                }
                val tGrid = SystemClock.elapsedRealtime()
                val merged = mergedWalkable(
                    segA, engA, if (engB != null) lastAdeSeg else null, engB,
                    depth.size, padX, padY, scale, upright,
                )
                // The Wayfinder columns follow the ENSEMBLE view: FFNet
                // alone is out-of-domain indoors and steered the fallback
                // toward Cityscapes hallucinations.
                if (merged != null) {
                    p.segClearance =
                        WalkableColumns.clearanceFromMask(merged, depth.size, depth.size)
                } else if (segA != null && engA != null) {
                    p.segClearance = WalkableColumns.clearance(
                        segA, engA.outW, engA.outH, engA.walkable,
                    )
                }
                p.updateGrid(metricDepth(depth), depth.size, depth.size, merged)
                gridMs = SystemClock.elapsedRealtime() - tGrid
            }
        }
        if (!depthDebugEnabled) lastDepthDebug = null

        // Map 640-space boxes back into camera-frame space; attach distances
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

        // v2: plan every frame on the persistent grid (cheap raycasts)
        val tPlan = SystemClock.elapsedRealtime()
        val plan = path?.plan()
        val planMs = SystemClock.elapsedRealtime() - tPlan
        // Always rendered: the map-first UI floats the BEV grid over the map
        val gridDebug = path?.grid?.renderDebug()

        if (depth != null && ++timeLogCounter % 5 == 0) {
            android.util.Log.i(
                "ShepherdTime",
                "mode=${if (indoor) "in" else "out"} " +
                    "yolo=${detectLatency}ms(1Hz) depth=${depth.latencyMs}ms " +
                    "ffnet=${ffnetMs}ms ade=${lastAdeMs}ms(async) " +
                    "grid=${gridMs}ms plan=${planMs}ms " +
                    "e2e=${SystemClock.elapsedRealtime() - nowMs}ms " +
                    "ground=%.2fm scale=%.2f steer=%.0f°".format(
                        path?.grid?.groundOffsetEma ?: 0f,
                        calibrator.currentScale,
                        plan?.chosenAngleDeg ?: 0f,
                    ),
            )
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
                plan = plan,
                gridDebug = gridDebug,
                gridW = path?.grid?.cellsWide ?: 0,
                gridH = path?.grid?.cellsDeep ?: 0,
                gravityDeg = gravityDeg,
            )
        )
    }

    /**
     * NaN out depth pixels that fall on the letterbox padding, so every
     * consumer (grid projection, ground estimate, near-field columns, box
     * medians) sees only real scene geometry. NaN sorts last and fails
     * isFinite gates, so downstream percentile/median code degrades to
     * "no signal" rather than garbage.
     */
    private fun maskLetterboxBars(dm: DepthEngine.DepthMap, padX: Float, padY: Float) {
        val ds = dm.size
        val rs = ds.toFloat() / size
        val barX = kotlin.math.ceil(padX * rs).toInt().coerceIn(0, ds / 2)
        val barY = kotlin.math.ceil(padY * rs).toInt().coerceIn(0, ds / 2)
        if (barX == 0 && barY == 0) return
        val map = dm.map
        for (y in 0 until ds) {
            val row = y * ds
            if (y < barY || y >= ds - barY) {
                java.util.Arrays.fill(map, row, row + ds, Float.NaN)
            } else if (barX > 0) {
                java.util.Arrays.fill(map, row, row + barX, Float.NaN)
                java.util.Arrays.fill(map, row + ds - barX, row + ds, Float.NaN)
            }
        }
    }

    /** Depth map in meters (calibrator-refined; raw is already metric). */
    private fun metricDepth(dm: DepthEngine.DepthMap): FloatArray {
        val n = dm.size * dm.size
        val out = FloatArray(n)
        for (i in 0 until n) {
            val raw = dm.map[i]
            out[i] = calibrator.convert(raw) ?: raw
        }
        return out
    }

    /**
     * Sample both ensemble members' class maps into depth-pixel geometry:
     * depth px -> 640 letterbox space -> source frame -> each model's
     * output coords. 1 = either model votes walkable, 0 = every available
     * model votes unwalkable, -1 = outside the frame / no opinion.
     */
    private fun mergedWalkable(
        segA: ByteArray?,
        engA: SegEngine?,
        segB: ByteArray?,
        engB: SegEngine?,
        ds: Int,
        padX: Float,
        padY: Float,
        scale: Float,
        upright: Bitmap,
    ): ByteArray? {
        if (segA == null && segB == null) return null
        val out = ByteArray(ds * ds)
        val to640 = size.toFloat() / ds
        val fw = upright.width.toFloat()
        val fh = upright.height.toFloat()
        for (dv in 0 until ds) {
            val sy = (dv * to640 - padY) / scale
            for (du in 0 until ds) {
                val sx = (du * to640 - padX) / scale
                val i = dv * ds + du
                if (sx < 0f || sy < 0f || sx >= fw || sy >= fh) {
                    out[i] = -1
                    continue
                }
                val a = vote(segA, engA, sx, sy, fw, fh)
                val b = vote(segB, engB, sx, sy, fw, fh)
                out[i] = when {
                    a == 1 || b == 1 -> 1
                    a == 0 || b == 0 -> 0
                    else -> -1
                }
            }
        }
        return out
    }

    /** One model's walkability vote at a frame position: 1/0, or -1 n/a. */
    private fun vote(
        seg: ByteArray?,
        eng: SegEngine?,
        sx: Float,
        sy: Float,
        fw: Float,
        fh: Float,
    ): Int {
        if (seg == null || eng == null) return -1
        val gx = (sx / fw * eng.outW).toInt().coerceIn(0, eng.outW - 1)
        val gy = (sy / fh * eng.outH).toInt().coerceIn(0, eng.outH - 1)
        val cls = seg[gy * eng.outW + gx].toInt()
        return if (cls in eng.walkable.indices && eng.walkable[cls]) 1 else 0
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
