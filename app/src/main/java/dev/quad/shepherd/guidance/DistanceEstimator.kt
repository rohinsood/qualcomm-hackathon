package dev.quad.shepherd.guidance

/**
 * Monocular distance estimation from bounding-box height via the pinhole
 * model: distance = realHeight * focalPx / boxHeightPx.
 *
 * The S25 Ultra has no LiDAR (unlike the iPhone Shepherd targets), so v1
 * uses known real-world object heights. Phase 2 replaces this with a depth
 * model (Depth-Anything-V2 from AI Hub, converted via QUAD) for per-pixel
 * distance on arbitrary obstacles.
 *
 * Pure Kotlin for JVM unit testing.
 */
object DistanceEstimator {

    /**
     * Approximate focal length in pixels for the 640px letterboxed frame.
     * Derived from the S25 Ultra main camera's ~85-degree diagonal FOV:
     * f = (w/2) / tan(hfov/2) with hfov ~ 77deg -> ~400px at 640 wide.
     * Calibrate against a known target for production accuracy.
     */
    const val FOCAL_PX = 400f

    /** Typical real-world heights (meters) for classes that matter on a sidewalk. */
    private val CLASS_HEIGHTS = mapOf(
        "person" to 1.70f,
        "bicycle" to 1.05f,
        "motorcycle" to 1.10f,
        "car" to 1.45f,
        "bus" to 3.00f,
        "truck" to 3.20f,
        "traffic light" to 0.90f,
        "stop sign" to 0.75f,
        "fire hydrant" to 0.75f,
        "bench" to 0.85f,
        "chair" to 0.90f,
        "couch" to 0.85f,
        "potted plant" to 0.60f,
        "dining table" to 0.75f,
        "dog" to 0.55f,
        "cat" to 0.30f,
        "backpack" to 0.50f,
        "suitcase" to 0.65f,
    )

    /**
     * @param boxHeightPx detection height in 640-model-space pixels.
     * @return estimated distance in meters, or null for classes without a
     *   reliable height prior.
     */
    fun estimate(label: String, boxHeightPx: Float): Float? {
        if (boxHeightPx <= 1f) return null
        val realHeight = CLASS_HEIGHTS[label] ?: return null
        return (realHeight * FOCAL_PX / boxHeightPx).coerceIn(0.1f, 50f)
    }
}
