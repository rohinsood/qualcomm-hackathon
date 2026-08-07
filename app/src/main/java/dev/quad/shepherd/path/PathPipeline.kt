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

    /** Route goal angle in degrees (from NavEngine steer), or null. */
    @Volatile var goalAngleDeg: Float? = null

    @Volatile var cameraHeightM = 1.35f

    @Volatile var hFovDeg = 70f

    /** Fold a fresh metric depth frame (+ walkability mask) into the grid. */
    fun updateGrid(depthMeters: FloatArray, w: Int, h: Int, walkable: ByteArray?) {
        grid.update(depthMeters, w, h, walkable, pitchRad, cameraHeightM, hFovDeg)
    }

    /** Plan on the persistent grid; callable every frame. */
    fun plan(): PolarPlanner.Plan = planner.plan(grid, goalAngleDeg)

    fun reset() {
        grid.clear()
        planner.reset()
    }
}
