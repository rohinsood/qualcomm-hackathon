package dev.quad.shepherd.guidance

/**
 * Light-touch refinement for the metric depth model's output.
 *
 * The model (Depth-Anything-V2 *metric*, indoor-tuned) already predicts
 * distance in meters — a wall you are facing reads as its actual distance
 * with no references needed, which is what makes the standing-still case
 * work. This class only trims systematic scale bias: whenever a detection
 * with a known-height pinhole distance is visible, the ratio between the
 * two estimates nudges a bounded global scale factor.
 *
 * (Historical note: the previous relative-depth design fitted a global
 * disparity-to-meters mapping, which is unsound for models with per-frame
 * scale — that is why walls used to go unreported.)
 *
 * Pure Kotlin for JVM unit testing. Not thread-safe (analysis thread only).
 */
class DepthCalibrator {

    private var scale = 1f
    private var samples = 0

    val currentScale: Float get() = scale

    /**
     * @param modelMeters depth-model reading for an object (meters)
     * @param referenceMeters trusted pinhole distance for the same object
     */
    fun addSample(modelMeters: Float, referenceMeters: Float) {
        if (modelMeters !in 0.2f..25f || referenceMeters !in 0.3f..20f) return
        val ratio = (referenceMeters / modelMeters).coerceIn(0.4f, 2.5f)
        scale = if (samples == 0) ratio else 0.9f * scale + 0.1f * ratio
        scale = scale.coerceIn(0.5f, 2f)
        samples++
    }

    /** @return refined meters, or null when the reading is implausible. */
    fun convert(modelMeters: Float): Float? {
        if (!modelMeters.isFinite() || modelMeters <= 0.05f || modelMeters > 35f) return null
        return (modelMeters * scale).coerceIn(0.15f, 40f)
    }
}
