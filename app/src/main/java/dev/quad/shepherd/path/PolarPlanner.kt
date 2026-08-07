package dev.quad.shepherd.path

import dev.quad.shepherd.guidance.GuidanceEngine
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/**
 * VFH+-style local planner over the [TraversabilityGrid]: raycast free
 * distance per polar sector, mark sectors blocked with HYSTERESIS
 * (separate enter/exit thresholds), find candidate valleys, and choose a
 * heading by cost = goal deviation + previous-direction deviation - valley
 * width. The previous-direction term plus hysteresis is what makes the
 * output COMMITTED: near-identical frames cannot flip the decision, which
 * was the failure mode of the per-frame column approach.
 *
 * Emits a [GuidanceEngine.Guidance] so the entire downstream (SteerView,
 * haptics, CaneCommand, blackboard) is unchanged.
 *
 * Pure Kotlin for JVM unit testing.
 */
class PolarPlanner(
    private val sectors: Int = 37,
    private val maxRangeM: Float = 5.5f,
) {

    companion object {
        const val SPAN_DEG = 180f

        /** Sector becomes blocked below this free distance (matches the
         *  original Shepherd's ~2 m steering sensitivity)… */
        const val BLOCK_ENTER_M = 1.8f

        /** …and must clear this to unblock (hysteresis). */
        const val BLOCK_EXIT_M = 2.2f

        const val DANGER_M = 1.05f
        const val CAUTION_M = 2.2f

        /** ~15°: a valley narrower than this isn't a walkable corridor. */
        const val MIN_VALLEY_SECTORS = 3

        const val W_GOAL = 1.0f
        const val W_PREV = 1.6f
        const val W_WIDTH = 0.8f

        /** Low-pass on the chosen heading (commitment). */
        const val COMMIT_ALPHA = 0.35f

        const val STEER_FULL_DEG = 60f
        const val FORWARD_CONE_DEG = 16f
        const val RAY_START_M = 0.15f
    }

    data class Plan(
        val guidance: GuidanceEngine.Guidance,
        val chosenAngleDeg: Float,
        val stop: Boolean,
        val sectorFreeM: FloatArray,
    )

    private val blocked = BooleanArray(sectors)
    private var committedAngle = 0f

    fun plan(grid: TraversabilityGrid, goalAngleDeg: Float?): Plan {
        // 1) Free distance per sector
        val free = FloatArray(sectors)
        for (s in 0 until sectors) free[s] = raycast(grid, sectorAngle(s))

        // 2) Neighbor smoothing
        val sm = FloatArray(sectors)
        for (s in 0 until sectors) {
            val l = free[maxOf(0, s - 1)]
            val r = free[min(sectors - 1, s + 1)]
            sm[s] = 0.25f * l + 0.5f * free[s] + 0.25f * r
        }

        // 3) Blocked state with hysteresis
        for (s in 0 until sectors) {
            blocked[s] = if (blocked[s]) sm[s] < BLOCK_EXIT_M else sm[s] < BLOCK_ENTER_M
        }

        // 4) Nearest obstacle in the forward cone (severity + magnitude)
        var nearestForward = Float.MAX_VALUE
        for (s in 0 until sectors) {
            if (abs(sectorAngle(s)) <= FORWARD_CONE_DEG) {
                nearestForward = min(nearestForward, sm[s])
            }
        }
        val nearest = nearestForward.takeIf { it < maxRangeM - 0.01f }

        // 5) Valleys = contiguous unblocked runs wide enough to walk
        var bestAngle: Float? = null
        var bestCost = Float.MAX_VALUE
        val goal = goalAngleDeg ?: 0f
        var runStart = -1
        for (s in 0..sectors) {
            val open = s < sectors && !blocked[s]
            if (open && runStart < 0) runStart = s
            if (!open && runStart >= 0) {
                val runEnd = s - 1
                val width = runEnd - runStart + 1
                if (width >= MIN_VALLEY_SECTORS) {
                    val step = SPAN_DEG / (sectors - 1)
                    val lo = sectorAngle(runStart) + step
                    val hi = sectorAngle(runEnd) - step
                    val cand = if (lo <= hi) goal.coerceIn(lo, hi)
                    else (sectorAngle(runStart) + sectorAngle(runEnd)) / 2f
                    val cost = W_GOAL * abs(cand - goal) / 90f +
                        W_PREV * abs(cand - committedAngle) / 90f -
                        W_WIDTH * width.toFloat() / sectors
                    if (cost < bestCost) {
                        bestCost = cost
                        bestAngle = cand
                    }
                }
                runStart = -1
            }
        }

        // 6) No valley at all: stop.
        if (bestAngle == null) {
            committedAngle = 0f
            val g = GuidanceEngine.Guidance(
                severity = GuidanceEngine.Severity.DANGER,
                steer = 0f,
                nearestDistanceMeters = nearest,
                nearestLabel = "obstacle",
                columnThreat = threatColumns(sm),
            )
            return Plan(g, 0f, stop = true, sectorFreeM = sm)
        }

        // 7) Commit gradually toward the chosen heading
        committedAngle += COMMIT_ALPHA * (bestAngle - committedAngle)

        val severity = when {
            nearestForward < DANGER_M -> GuidanceEngine.Severity.DANGER
            nearestForward < CAUTION_M -> GuidanceEngine.Severity.CAUTION
            else -> GuidanceEngine.Severity.CLEAR
        }

        // On a clear path with no route goal, relax back to straight
        if (severity == GuidanceEngine.Severity.CLEAR && goalAngleDeg == null) {
            committedAngle *= 0.85f
        }

        val g = GuidanceEngine.Guidance(
            severity = severity,
            steer = (committedAngle / STEER_FULL_DEG).coerceIn(-1f, 1f),
            nearestDistanceMeters = if (severity == GuidanceEngine.Severity.CLEAR) null else nearest,
            nearestLabel = if (severity == GuidanceEngine.Severity.CLEAR) null else "obstacle",
            columnThreat = threatColumns(sm),
        )
        return Plan(g, committedAngle, stop = false, sectorFreeM = sm)
    }

    fun reset() {
        blocked.fill(false)
        committedAngle = 0f
    }

    /** Sector index -> compass-relative angle; negative = LEFT. */
    private fun sectorAngle(s: Int): Float =
        -SPAN_DEG / 2f + SPAN_DEG * s / (sectors - 1)

    private fun raycast(grid: TraversabilityGrid, angleDeg: Float): Float {
        val rad = Math.toRadians(angleDeg.toDouble())
        val sinA = sin(rad).toFloat()
        val cosA = cos(rad).toFloat()
        val step = grid.cellMeters * 0.6f
        var r = RAY_START_M
        while (r < maxRangeM) {
            val ix = grid.cellsWide / 2 + round(r * sinA / grid.cellMeters).toInt()
            val iz = round(r * cosA / grid.cellMeters).toInt()
            if (iz !in 0 until grid.cellsDeep || ix !in 0 until grid.cellsWide) {
                return maxRangeM // left the mapped area without hitting anything
            }
            if (grid.isObstacle(ix, iz)) return r
            r += step
        }
        return maxRangeM
    }

    /** Compress sectors into the 9-column threat bar the overlay draws. */
    private fun threatColumns(sm: FloatArray): FloatArray {
        val cols = FloatArray(GuidanceEngine.NUM_COLUMNS)
        for (c in 0 until GuidanceEngine.NUM_COLUMNS) {
            val s0 = c * sectors / GuidanceEngine.NUM_COLUMNS
            val s1 = ((c + 1) * sectors / GuidanceEngine.NUM_COLUMNS - 1).coerceAtLeast(s0)
            var worst = maxRangeM
            for (s in s0..s1) worst = min(worst, sm[s])
            cols[c] = (1f - worst / maxRangeM).coerceIn(0f, 1f)
        }
        return cols
    }
}
