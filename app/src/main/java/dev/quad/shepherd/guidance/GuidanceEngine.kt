package dev.quad.shepherd.guidance

import dev.quad.shepherd.vision.Detection

/**
 * Shepherd-style gap-seeking, adapted from LiDAR depth columns to
 * detector output: the frame is split into vertical columns, each column
 * accumulates a threat score from the detections overlapping it (closer =
 * quadratically more threatening), and the safest contiguous window
 * determines the steering direction.
 *
 * Pure Kotlin for JVM unit testing.
 */
class GuidanceEngine(
    private val numColumns: Int = 9,
    private val dangerDistance: Float = 1.5f,
    private val cautionDistance: Float = 3.0f,
) {

    enum class Severity { CLEAR, CAUTION, DANGER }

    /**
     * @param steer -1.0 (hard left) .. 0.0 (straight) .. +1.0 (hard right)
     * @param message spoken guidance, null when there is nothing new to say
     * @param columnThreat per-column threat, normalized 0..1, for the overlay
     */
    data class Guidance(
        val severity: Severity,
        val steer: Float,
        val nearest: Detection?,
        val message: String?,
        val columnThreat: FloatArray,
    )

    /** Distance assumed for detections whose class has no height prior. */
    private val defaultDistance = 4.0f

    private var lastSteer = 0f

    fun update(detections: List<Detection>, frameWidth: Int): Guidance {
        val threat = FloatArray(numColumns)
        val colWidth = frameWidth.toFloat() / numColumns

        var nearest: Detection? = null
        var nearestDist = Float.MAX_VALUE

        for (d in detections) {
            val dist = d.distanceMeters ?: defaultDistance
            val weight = 1f / (dist * dist).coerceAtLeast(0.04f)
            val first = (d.x1 / colWidth).toInt().coerceIn(0, numColumns - 1)
            val last = (d.x2 / colWidth).toInt().coerceIn(0, numColumns - 1)
            for (c in first..last) threat[c] += weight

            // Track the nearest obstacle in the central corridor (the walking path)
            val centerBand = frameWidth * 0.5f
            val bandLo = (frameWidth - centerBand) / 2f
            val bandHi = bandLo + centerBand
            if (d.centerX in bandLo..bandHi && dist < nearestDist) {
                nearestDist = dist
                nearest = d
            }
        }

        val severity = when {
            nearest != null && nearestDist < dangerDistance -> Severity.DANGER
            nearest != null && nearestDist < cautionDistance -> Severity.CAUTION
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

        val message = buildMessage(severity, steer, nearest, nearestDist)

        val maxThreat = threat.maxOrNull()?.takeIf { it > 0f } ?: 1f
        val normalized = FloatArray(numColumns) { threat[it] / maxThreat }

        return Guidance(severity, steer, nearest, message, normalized)
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
        nearest: Detection?,
        nearestDist: Float,
    ): String? {
        if (severity == Severity.CLEAR || nearest == null) return null
        val direction = when {
            steer < -0.2f -> "move left"
            steer > 0.2f -> "move right"
            severity == Severity.DANGER -> "stop"
            else -> "slow down"
        }
        val dist = if (nearestDist < 3f) String.format("%.1f", nearestDist)
        else String.format("%.0f", nearestDist)
        return "${nearest.label}, $dist meters ahead. Please $direction."
    }
}
