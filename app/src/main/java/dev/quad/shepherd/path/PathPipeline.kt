package dev.quad.shepherd.path

/**
 * Owns the v2 spatial pipeline: grid accumulation + polar planning.
 * Volatiles are fed from the service (IMU pitch, navigation goal) and read
 * on the analysis thread.
 */
class PathPipeline(
    val grid: TraversabilityGrid = TraversabilityGrid(),
    private val planner: PolarPlanner = PolarPlanner(),
) {

    /** Camera pitch below horizontal, from the gravity sensor. */
    @Volatile var pitchRad = 0.30f

    /** Camera roll about the optical axis, from the gravity sensor. */
    @Volatile var rollRad = 0f

    /**
     * Roll snapped to the nearest 90°: the frame is pre-rotated by this
     * before inference (depth/seg models are not rotation-invariant), so
     * landscape operation works natively.
     */
    val gravityUprightDeg: Int
        get() {
            val deg = Math.toDegrees(rollRad.toDouble())
            val snapped = (Math.round(deg / 90.0) * 90).toInt() % 360
            return if (snapped == -270) 90 else if (snapped == 270) -90 else snapped
        }

    /** Roll left over after the 90° snap — what the grid still corrects. */
    private fun residualRollRad(): Float =
        rollRad - Math.toRadians(gravityUprightDeg.toDouble()).toFloat()

    /** Route goal angle in degrees (from NavEngine steer), or null. */
    @Volatile var goalAngleDeg: Float? = null

    /** Compass heading (degrees, NaN when unknown) — anchors the planner's
     *  committed direction in world space while the camera pans. */
    @Volatile var headingDeg = Float.NaN
    private var lastPlanHeadingDeg = Float.NaN

    @Volatile var cameraHeightM = 1.35f

    @Volatile var hFovDeg = 70f

    /** Latest image-space walkable-fraction columns (Wayfinder signal). */
    @Volatile var segClearance: FloatArray? = null

    /** Fold a fresh metric depth frame (+ walkability mask) into the grid. */
    fun updateGrid(depthMeters: FloatArray, w: Int, h: Int, walkable: ByteArray?) {
        grid.update(
            depthMeters, w, h, walkable, pitchRad, cameraHeightM, hFovDeg,
            residualRollRad(),
        )
    }

    /** Plan on the persistent grid; callable every frame. */
    fun plan(): PolarPlanner.Plan {
        val h = headingDeg
        if (!h.isNaN()) {
            if (!lastPlanHeadingDeg.isNaN()) {
                var d = h - lastPlanHeadingDeg
                while (d > 180f) d -= 360f
                while (d < -180f) d += 360f
                if (kotlin.math.abs(d) > 0.5f) planner.rotateFrame(d)
            }
            lastPlanHeadingDeg = h
        }
        return planner.plan(grid, goalAngleDeg, segClearance, hFovDeg)
    }

    fun reset() {
        grid.clear()
        planner.reset()
        lastPlanHeadingDeg = Float.NaN
    }
}
