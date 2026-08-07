package dev.quad.shepherd.path

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tan

/**
 * Ego-centric bird's-eye-view traversability grid — the v2 answer to
 * per-frame jitter. Every frame, metric depth pixels (optionally gated by
 * the segmentation walkability mask) are projected through the camera
 * model onto the ground plane and accumulated into per-cell log-odds with
 * exponential decay. Because evidence persists across frames in WORLD
 * space, a single wobbly frame cannot flip the map: "same scene → same
 * answer" becomes true by construction.
 *
 * Frame convention: x = meters right of the camera, z = meters forward.
 * Cell (ix, iz): ix spans [-halfWidth, +halfWidth], iz spans [0, depth].
 * logOdds > 0 leans obstacle, < 0 leans free, 0 unknown.
 *
 * The forthcoming cane-mounted short-range depth sensor (0.25 m) plugs in
 * through [markNearObstacle].
 *
 * Pure Kotlin for JVM unit testing.
 */
class TraversabilityGrid(
    val cellsWide: Int = 61,
    val cellsDeep: Int = 60,
    val cellMeters: Float = 0.1f,
) {

    companion object {
        /** Log-odds increments per observation. */
        const val L_OBSTACLE = 0.9f
        const val L_FREE = -0.4f

        /** Flat-but-not-walkable (per segmentation): soft obstacle. */
        const val L_SOFT_OBSTACLE = 0.35f

        /** Seg-only evidence saturates below the blocking threshold. */
        const val SOFT_CAP = 0.55f

        const val L_CLAMP = 4f

        /** Per-update multiplicative decay (~0.5 s half-life at 11 Hz). */
        const val DECAY = 0.94f

        /** Points this far above ground count as obstacles. */
        const val OBSTACLE_MIN_HEIGHT_M = 0.18f

        /** Ignore overhead structure above this (doorframes are kept). */
        const val OBSTACLE_MAX_HEIGHT_M = 2.3f

        /** |height| below this counts as ground level. */
        const val GROUND_TOLERANCE_M = 0.16f

        /** Cells with log-odds above this are treated as blocking. */
        const val OBSTACLE_THRESHOLD = 0.7f

        private const val MIN_DEPTH_M = 0.25f
        private const val MAX_DEPTH_M = 8f
        private const val SAMPLE_STRIDE = 2
    }

    val logOdds = FloatArray(cellsWide * cellsDeep)

    /** Smoothed ground-plane offset (m); exposed for debug logging. */
    var groundOffsetEma = 0f
        private set
    private var groundOffsetInit = false

    fun isObstacle(ix: Int, iz: Int): Boolean =
        logOdds[iz * cellsWide + ix] > OBSTACLE_THRESHOLD

    /**
     * Fold one frame's observations into the grid.
     *
     * @param depth     row-major metric depth in meters, depthW x depthH.
     * @param walkable  optional walkability per depth pixel (same geometry
     *   as [depth]); null entries mean "no segmentation opinion".
     * @param pitchRad  camera pitch below horizontal (positive = down).
     * @param cameraHeightM  camera height above the ground plane.
     * @param hFovDeg   horizontal field of view of the depth image.
     */
    fun update(
        depth: FloatArray,
        depthW: Int,
        depthH: Int,
        walkable: ByteArray?, // 1 walkable, 0 not, -1 unknown
        pitchRad: Float,
        cameraHeightM: Float,
        hFovDeg: Float,
        rollRad: Float = 0f,
    ) {
        decay()

        val fx = depthW / (2f * tan(Math.toRadians(hFovDeg / 2.0)).toFloat())
        val cx = depthW / 2f
        val cy = depthH / 2f
        val cosP = cos(pitchRad)
        val sinP = sin(pitchRad)
        // Roll: a tilted (or sideways-propped) phone rotates the image about
        // the optical axis; un-roll image-plane coords into gravity alignment
        val cosR = cos(rollRad)
        val sinR = sin(rollRad)
        val halfWidthCells = cellsWide / 2

        // Self-calibrate the ground level: the nominal camera height is a
        // guess, and a ±10 cm error would reclassify the whole floor as an
        // obstacle band. The sample stride is derived from the frame size
        // so coverage spans the WHOLE image — a fixed stride filled the
        // sample cap from the top rows alone, and with the camera pitched
        // at a desk the "ground" latched onto the desk surface, turning
        // the entire room into an obstacle field. A low percentile finds
        // the floor even when it is a minority surface, and an EMA keeps
        // one bad frame from repainting the map.
        run {
            val samples = FloatArray(512)
            var n = 0
            val strideV = (depthH / 22).coerceAtLeast(3)
            val strideU = (depthW / 22).coerceAtLeast(3)
            var sv = strideV / 2
            while (sv < depthH && n < samples.size) {
                var su = strideU / 2
                while (su < depthW && n < samples.size) {
                    val d = depth[sv * depthW + su]
                    if (d.isFinite() && d in MIN_DEPTH_M..5f) {
                        val xi = (su - cx) / fx * d
                        val yi = -((sv - cy) / fx * d)
                        val yUp = -xi * sinR + yi * cosR
                        samples[n++] = cameraHeightM + (yUp * cosP - d * sinP)
                    }
                    su += strideU
                }
                sv += strideV
            }
            if (n >= 80) {
                val sorted = samples.copyOf(n)
                sorted.sort()
                val p12 = sorted[n * 12 / 100]
                if (abs(p12) < 0.7f) {
                    groundOffsetEma = if (!groundOffsetInit) p12
                    else groundOffsetEma + 0.25f * (p12 - groundOffsetEma)
                    groundOffsetInit = true
                }
            }
        }
        val groundOffset = groundOffsetEma

        var v = 0
        while (v < depthH) {
            var u = 0
            while (u < depthW) {
                val d = depth[v * depthW + u]
                if (d.isFinite() && d in MIN_DEPTH_M..MAX_DEPTH_M) {
                    // Camera frame: xi right, yi up (image), zc forward
                    val xi = (u - cx) / fx * d
                    val yi = -((v - cy) / fx * d)
                    // Un-roll about the optical axis, then pitch about X
                    val xc = xi * cosR + yi * sinR
                    val yUp = -xi * sinR + yi * cosR
                    val yW = yUp * cosP - d * sinP
                    val zW = yUp * sinP + d * cosP
                    val height = cameraHeightM + yW - groundOffset

                    if (zW > 0f) {
                        val ix = halfWidthCells + round(xc / cellMeters).toInt()
                        val iz = round(zW / cellMeters).toInt()
                        if (ix in 0 until cellsWide && iz in 0 until cellsDeep) {
                            val i = iz * cellsWide + ix
                            val delta = when {
                                height > OBSTACLE_MIN_HEIGHT_M &&
                                    height < OBSTACLE_MAX_HEIGHT_M -> L_OBSTACLE
                                kotlin.math.abs(height) <= GROUND_TOLERANCE_M -> {
                                    when (walkable?.get(v * depthW + u)?.toInt()) {
                                        // Flat but seg says not walkable:
                                        // suspicious, yet NEVER allowed to
                                        // cross the blocking threshold on
                                        // seg evidence alone (Cityscapes is
                                        // out-of-domain indoors)
                                        0 -> if (logOdds[i] < SOFT_CAP) L_SOFT_OBSTACLE else 0f
                                        else -> L_FREE
                                    }
                                }
                                else -> 0f // below-ground noise or overhead
                            }
                            if (delta != 0f) {
                                logOdds[i] = (logOdds[i] + delta)
                                    .coerceIn(-L_CLAMP, L_CLAMP)
                            }
                        }
                    }
                }
                u += SAMPLE_STRIDE
            }
            v += SAMPLE_STRIDE
        }
    }

    /** Hard near-field evidence from the cane's short-range depth sensor. */
    fun markNearObstacle(distanceM: Float, bearingDeg: Float = 0f) {
        val rad = Math.toRadians(bearingDeg.toDouble())
        val ix = cellsWide / 2 + round(distanceM * sin(rad) / cellMeters).toInt()
        val iz = round(distanceM * cos(rad) / cellMeters).toInt()
        if (ix in 0 until cellsWide && iz in 0 until cellsDeep) {
            val i = iz * cellsWide + ix
            logOdds[i] = (logOdds[i] + L_OBSTACLE * 2).coerceIn(-L_CLAMP, L_CLAMP)
        }
    }

    fun clear() {
        logOdds.fill(0f)
        groundOffsetEma = 0f
        groundOffsetInit = false
    }

    /**
     * Overwrite every cell from an externally computed ego view.
     *
     * The areamap supersedes this class's own accumulation: it holds the
     * same evidence in fixed world coordinates, so a window rotated out of
     * it is this grid's content done properly — in particular it is still
     * correct after the user turns around, which self-accumulated content
     * is not. [PolarPlanner] and everything downstream stay untouched;
     * only where the numbers come from changes.
     */
    fun loadFrom(src: FloatArray) {
        require(src.size == logOdds.size) {
            "ego view ${src.size} does not match grid ${logOdds.size}"
        }
        System.arraycopy(src, 0, logOdds, 0, src.size)
    }

    private fun decay() {
        for (i in logOdds.indices) logOdds[i] *= DECAY
    }

    /** ARGB render for the on-screen BEV debug view (row 0 = nearest). */
    fun renderDebug(): IntArray {
        val px = IntArray(cellsWide * cellsDeep)
        for (iz in 0 until cellsDeep) {
            for (ix in 0 until cellsWide) {
                val l = logOdds[iz * cellsWide + ix]
                // Draw with far rows at the top: flip z
                val outRow = cellsDeep - 1 - iz
                px[outRow * cellsWide + ix] = when {
                    l > OBSTACLE_THRESHOLD -> 0xFFE53935.toInt() // red obstacle
                    l < -0.4f -> 0xFF43A047.toInt()              // green free
                    l > 0.2f -> 0xFFFB8C00.toInt()               // amber suspicious
                    else -> 0xFF263238.toInt()                   // unknown
                }
            }
        }
        return px
    }
}
