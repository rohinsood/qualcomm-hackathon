package dev.quad.shepherd.guidance

/**
 * Maps the depth model's relative disparity (higher = closer, unknown
 * per-frame scale and shift) to meters by fitting
 * `disparity = a * (1/distance) + b` against reference points from
 * known-height detections, whose pinhole distances are metric.
 *
 * Until enough references have been seen, falls back to a scene-relative
 * heuristic: a column whose near-field disparity towers over the scene
 * median is close, whatever the absolute scale is.
 *
 * Pure Kotlin for JVM unit testing. Not thread-safe (analysis thread only).
 */
class DepthCalibrator {

    private val invZ = ArrayList<Float>()
    private val disp = ArrayList<Float>()
    private var a = 0f
    private var b = 0f

    var isCalibrated = false
        private set

    fun addSample(disparity: Float, distanceMeters: Float) {
        if (distanceMeters !in 0.3f..20f || !disparity.isFinite()) return
        invZ.add(1f / distanceMeters)
        disp.add(disparity)
        if (invZ.size > 40) {
            invZ.removeAt(0)
            disp.removeAt(0)
        }
        refit()
    }

    private fun refit() {
        val n = invZ.size
        if (n < 4) return
        // Least squares for disp = a * invZ + b
        var sx = 0f; var sy = 0f; var sxx = 0f; var sxy = 0f
        for (i in 0 until n) {
            sx += invZ[i]; sy += disp[i]
            sxx += invZ[i] * invZ[i]; sxy += invZ[i] * disp[i]
        }
        val denom = n * sxx - sx * sx
        if (denom <= 1e-6f) return
        val newA = (n * sxy - sx * sy) / denom
        val newB = (sy - newA * sx) / n
        // Physically, disparity must increase as things get closer
        if (newA <= 1e-3f) return
        if (!isCalibrated) {
            a = newA; b = newB
            isCalibrated = true
        } else {
            // EMA so one bad reference cannot swing the mapping
            a = 0.7f * a + 0.3f * newA
            b = 0.7f * b + 0.3f * newB
        }
    }

    /** @return meters, or null when uncalibrated or the reading means "far". */
    fun toMeters(disparity: Float): Float? {
        if (!isCalibrated) return null
        val inv = (disparity - b) / a
        if (inv < 1f / 40f) return null
        return (1f / inv).coerceIn(0.15f, 40f)
    }

    /**
     * Uncalibrated fallback: conservative pseudo-distance from how far the
     * column's near field sticks out above the scene median.
     */
    fun relativeToMeters(columnDisparity: Float, sceneMedian: Float): Float? {
        if (sceneMedian <= 1e-4f) return null
        val ratio = columnDisparity / sceneMedian
        return when {
            ratio > 2.4f -> 1.0f
            ratio > 1.7f -> 2.4f
            else -> null
        }
    }

    /** The single entry point: disparity to meters, or null for far / no signal. */
    fun convert(columnDisparity: Float, sceneMedian: Float): Float? =
        if (isCalibrated) toMeters(columnDisparity)
        else relativeToMeters(columnDisparity, sceneMedian)
}
