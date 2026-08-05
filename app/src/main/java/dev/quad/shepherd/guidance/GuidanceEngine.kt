package dev.quad.shepherd.guidance

import dev.quad.shepherd.vision.Detection

/**
 * Shepherd-style gap-seeking, adapted from LiDAR depth columns to what the
 * S25 Ultra gives us: the frame is split into vertical columns, each column
 * accumulates a threat score from two sources — object detections (with
 * pinhole distances) and the dense depth map's per-column distances (which
 * cover walls, poles, and anything the detector has no class for) — and the
 * safest contiguous window determines the steering direction.
 *
 * Pure Kotlin for JVM unit testing.
 */
class GuidanceEngine(
    private val numColumns: Int = NUM_COLUMNS,
    private val dangerDistance: Float = 1.5f,
    private val cautionDistance: Float = 3.0f,
) {

    companion object {
        const val NUM_COLUMNS = 9
    }

    enum class Severity { CLEAR, CAUTION, DANGER }

    /**
     * @param steer -1.0 (hard left) .. 0.0 (straight) .. +1.0 (hard right)
     * @param nearestDistanceMeters distance to the closest threat in the
     *   walking corridor, whether it came from a detection or from depth
     * @param nearestLabel spoken label for that threat; "obstacle" when it
     *   was found by depth alone (no class available)
     * @param message spoken guidance, null when there is nothing new to say
     * @param columnThreat per-column threat, normalized 0..1, for the overlay
     */
    data class Guidance(
        val severity: Severity,
        val steer: Float,
        val nearestDistanceMeters: Float?,
        val nearestLabel: String?,
        val message: String?,
        val columnThreat: FloatArray,
    )

    /** Distance assumed for detections whose class has no height prior. */
    private val defaultDistance = 4.0f

    private var lastSteer = 0f

    /**
     * @param columnDistances optional per-column obstacle distance in meters
     *   from the dense depth map; entries <= 0 or > 30 mean "no signal".
     */
    fun update(
        detections: List<Detection>,
        frameWidth: Int,
        columnDistances: FloatArray? = null,
    ): Guidance {
        val threat = FloatArray(numColumns)
        val colWidth = frameWidth.toFloat() / numColumns

        var nearestDist = Float.MAX_VALUE
        var nearestLabel: String? = null

        // Central walking corridor: middle 60% of the frame
        val centerBand = frameWidth * 0.6f
        val bandLo = (frameWidth - centerBand) / 2f
        val bandHi = bandLo + centerBand

        for (d in detections) {
            val dist = d.distanceMeters ?: defaultDistance
            val weight = 1f / (dist * dist).coerceAtLeast(0.04f)
            val first = (d.x1 / colWidth).toInt().coerceIn(0, numColumns - 1)
            val last = (d.x2 / colWidth).toInt().coerceIn(0, numColumns - 1)
            for (c in first..last) threat[c] += weight

            if (d.centerX in bandLo..bandHi && dist < nearestDist) {
                nearestDist = dist
                nearestLabel = d.label
            }
        }

        // Dense depth: class-free proximity per column
        if (columnDistances != null && columnDistances.size == numColumns) {
            val loCol = (numColumns * 0.2f).toInt()
            val hiCol = numColumns - 1 - loCol
            for (c in 0 until numColumns) {
                val dz = columnDistances[c]
                if (dz <= 0f || dz > 30f) continue
                threat[c] += 1.2f / (dz * dz).coerceAtLeast(0.04f)
                if (c in loCol..hiCol && dz < nearestDist) {
                    nearestDist = dz
                    nearestLabel = "obstacle"
                }
            }
        }

        val severity = when {
            nearestLabel != null && nearestDist < dangerDistance -> Severity.DANGER
            nearestLabel != null && nearestDist < cautionDistance -> Severity.CAUTION
            else -> Severity.CLEAR
        }

        val steer = if (severity == Severity.CLEAR) {
            lastSteer = 0f
            0f
        } else {
            val proposed = safestWindowSteer(threat)
            // Hysteresis so the command doesn't flip-flop on small differences —
            // but danger always takes the fresh direction immediately.
            if (severity == Severity.DANGER ||
                kotlin.math.abs(proposed - lastSteer) > 0.15f
            ) {
                lastSteer = proposed
            }
            lastSteer
        }

        val message = buildMessage(severity, steer, nearestLabel, nearestDist)

        val maxThreat = threat.maxOrNull()?.takeIf { it > 0f } ?: 1f
        val normalized = FloatArray(numColumns) { threat[it] / maxThreat }

        return Guidance(
            severity = severity,
            steer = steer,
            nearestDistanceMeters = if (nearestLabel != null) nearestDist else null,
            nearestLabel = nearestLabel,
            message = message,
            columnThreat = normalized,
        )
    }

    /** Slide a 3-column window; steer toward the center of the least-threatening one. */
    private fun safestWindowSteer(threat: FloatArray): Float {
        val window = 3
        var bestStart = 0
        var bestSum = Float.MAX_VALUE
        for (start in 0..numColumns - window) {
            var sum = 0f
            for (c in start until start + window) sum += threat[c]
            // Prefer windows closer to center on ties (small center bias)
            val centerOffset = kotlin.math.abs(start + window / 2f - numColumns / 2f)
            sum += centerOffset * 0.001f
            if (sum < bestSum) {
                bestSum = sum
                bestStart = start
            }
        }
        val windowCenter = bestStart + window / 2f
        val frameCenter = (numColumns - 1) / 2f
        return ((windowCenter - frameCenter) / frameCenter).coerceIn(-1f, 1f)
    }

    private fun buildMessage(
        severity: Severity,
        steer: Float,
        label: String?,
        nearestDist: Float,
    ): String? {
        if (severity == Severity.CLEAR || label == null) return null
        val direction = when {
            steer < -0.2f -> "move left"
            steer > 0.2f -> "move right"
            severity == Severity.DANGER -> "stop"
            else -> "slow down"
        }
        val dist = if (nearestDist < 3f) String.format("%.1f", nearestDist)
        else String.format("%.0f", nearestDist)
        return "$label, $dist meters ahead. Please $direction."
    }
}
