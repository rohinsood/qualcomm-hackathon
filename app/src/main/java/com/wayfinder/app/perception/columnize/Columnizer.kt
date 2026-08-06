package com.wayfinder.app.perception.columnize

import com.wayfinder.app.core.config.Tunables
import com.wayfinder.app.perception.seg.WalkableMask
import kotlin.math.max
import kotlin.math.min

/**
 * Per-column summary of the walkable mask, in the body-height band.
 *
 * @property clearance         walkable fraction per column (0..1). The gap-seeker
 *                             picks the column with the MAX clearance = the gap.
 * @property nearestObstacleMeters  estimated distance to the closest obstacle
 *                             (from how low in the band it sits), or null if the
 *                             band is fully walkable. Drives proximity magnitude.
 */
data class ColumnSignal(
    val clearance: FloatArray,
    val nearestObstacleMeters: Float?,
)

/**
 * Slices the walkable mask into [Tunables.numColumns] vertical columns within the
 * body-height band and computes per-column clearance + a reach-based nearest-
 * obstacle distance proxy.
 *
 * Geometry assumption (placeholder for M1; calibrate on-device in M3 with depth):
 * an obstacle lower in the image is closer to the user. So the lowest blocked row
 * in a column → that column's closest obstacle, and the global nearest obstacle is
 * the one sitting lowest across all columns.
 */
class Columnizer(private val t: Tunables) {

    fun columnize(mask: WalkableMask): ColumnSignal {
        val n = t.numColumns
        val clearance = FloatArray(n)

        val bandY0 = (mask.height * t.verticalBandStart).toInt().coerceIn(0, mask.height - 1)
        val bandY1 = (mask.height * t.verticalBandEnd).toInt().coerceIn(bandY0 + 1, mask.height)
        val bandH = (bandY1 - bandY0).coerceAtLeast(1)
        val colW = max(1, mask.width / n)

        // Track the LOWEST blocked row across all columns (largest y = closest obstacle).
        var lowestBlockedRow = -1

        for (c in 0 until n) {
            val x0 = min(mask.width, c * colW)
            val x1 = min(mask.width, (c + 1) * colW)
            var walkable = 0
            var total = 0
            var colLowestBlocked = -1
            for (x in x0 until x1) {
                for (y in bandY0 until bandY1) {
                    total++
                    if (mask.isWalkable(x, y)) {
                        walkable++
                    } else {
                        if (y > colLowestBlocked) colLowestBlocked = y
                    }
                }
            }
            clearance[c] = if (total > 0) walkable.toFloat() / total else 0f
            if (colLowestBlocked > lowestBlockedRow) lowestBlockedRow = colLowestBlocked
        }

        val nearest = if (lowestBlockedRow < 0) null else reachToMeters(lowestBlockedRow, bandY0, bandH)
        return ColumnSignal(clearance, nearest)
    }

    /**
     * Map the lowest blocked row to an approximate distance. reachFraction is 0 at
     * the top of the band (far) and 1 at the bottom (near), so distance decreases
     * as the obstacle sits lower. Replace with a calibrated ground-plane fit in M3.
     */
    private fun reachToMeters(row: Int, bandY0: Int, bandH: Int): Float {
        val reachFraction = (row - bandY0).toFloat() / bandH // 0 = far, 1 = near
        val dist = t.maxRangeMeters - (t.maxRangeMeters - t.minRangeMeters) * reachFraction
        return dist.coerceIn(t.minRangeMeters, t.maxRangeMeters)
    }
}
