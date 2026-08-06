package com.wayfinder.app.steering

import com.wayfinder.app.core.config.Tunables
import com.wayfinder.app.perception.columnize.ColumnSignal
import kotlin.math.abs
import kotlin.math.pow

/** Lightweight result from the gap-seeker; the loop wraps this into a full SteeringDecision. */
data class SteerResult(
    val command: Float,             // -1 (hard LEFT) .. +1 (hard RIGHT)
    val proximity: Float,           // 0..1 urgency
    val gap: Float,                 // smoothed gap direction, -1..+1
    val nearestObstacleMeters: Float?,
    val reason: String,
)

/**
 * Gap-seeking steering algorithm — a port of Shepherd's shipped SteeringEngine
 * (see gap-seeking-algorithm.md §8), adapted to the segmentation world:
 *
 *   • In Shepherd (depth)  the gap = column of MAX average DEPTH (deepest = clearest).
 *   • Here (walkable mask) the gap = column of MAX walkable FRACTION (most open).
 *
 * Both are "steer toward the direction of maximum clearance." The magnitude math
 * is identical: cube-root gap boost × proximity scaling × close-obstacle floor,
 * with a moving-average window on the gap direction to kill sign-flip jitter.
 *
 * `clearance` drives DIRECTION; `nearestObstacleMeters` drives MAGNITUDE. They are
 * deliberately decoupled so M3 can swap the reach-based proximity for real depth.
 */
class GapSeeker(private val t: Tunables) {

    private val gapHistory = ArrayDeque<Float>()

    fun compute(signal: ColumnSignal, nowMs: Long): SteerResult {
        val clearance = signal.clearance
        val n = clearance.size

        // 1) [0.25, 0.5, 0.25] smoothing of the columns (reduces single-column noise)
        val smoothed = FloatArray(n)
        for (i in 0 until n) {
            val l = if (i > 0) clearance[i - 1] else clearance[i]
            val r = if (i < n - 1) clearance[i + 1] else clearance[i]
            smoothed[i] = 0.25f * l + 0.5f * clearance[i] + 0.25f * r
        }

        // 2) Gap = column with MAX clearance (clearest path). Map its index to [-1, +1].
        var bestC = n / 2
        var bestVal = -1f
        for (i in 0 until n) {
            if (smoothed[i] > bestVal) {
                bestVal = smoothed[i]
                bestC = i
            }
        }
        val rawGap = if (bestVal > 0f) (bestC.toFloat() / (n - 1) - 0.5f) * 2f else 0f

        // 3) Moving average on gapDirection (kills frame-to-frame sign flips)
        gapHistory.addLast(rawGap)
        while (gapHistory.size > t.gapHistorySize) gapHistory.removeFirst()
        val gap = gapHistory.average().toFloat()

        // 4) If nothing is in range → CLEAR (straight, no urgency).
        val nearest = signal.nearestObstacleMeters
        if (nearest == null || nearest >= t.sensitivityMeters) {
            return SteerResult(0f, 0f, gap, nearest, "Clear")
        }

        // 5) Proximity: 0 at the sensitivity threshold, 1 at minRange.
        val linearProximity =
            ((t.sensitivityMeters - nearest) / (t.sensitivityMeters - t.minRangeMeters))
                .coerceIn(0f, 1f)
        val proximity = linearProximity.toDouble().pow(t.proximityExponent.toDouble()).toFloat()

        // 6) Cube-root gap boost so moderate gaps produce meaningful output.
        val boost = if (gap >= 0f) {
            gap.toDouble().pow(t.gapExponent.toDouble()).toFloat()
        } else {
            -((-gap).toDouble().pow(t.gapExponent.toDouble())).toFloat()
        }

        // 7) Steer toward the gap, scaled by urgency.
        var command = (boost * proximity).coerceIn(-1f, 1f)

        // 8) Close-obstacle floor: enforce a minimum command so the nudge is actually felt.
        if (nearest < t.closeObstacleMeters && abs(command) < t.closeFloor && abs(gap) > 0.02f) {
            command = if (command >= 0f) maxOf(command, t.closeFloor) else minOf(command, -t.closeFloor)
        }

        val dir = when {
            command < -0.1f -> "LEFT"
            command > 0.1f -> "RIGHT"
            else -> "NEUTRAL"
        }
        val reason = "$dir gap=${"%.2f".format(gap)} prox=${"%.2f".format(proximity)} d=${"%.2f".format(nearest)}m"
        return SteerResult(command, proximity, gap, nearest, reason)
    }

    fun reset() {
        gapHistory.clear()
    }
}
