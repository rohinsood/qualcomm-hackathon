package com.wayfinder.app.perception.depth

import com.wayfinder.app.core.config.Tunables
import kotlin.math.max
import kotlin.math.min

/**
 * Reduces a dense depth map (per-pixel distance) to per-column nearest distances
 * within the body-height band — the same band the segmentation Columnizer uses, so
 * columns align 1:1. For each column we keep the MINIMUM valid depth (closest
 * surface), which is the depth-world analogue of Shepherd's "max average depth = gap"
 * (here, min depth = nearest obstacle per column).
 */
object DepthColumnizer {
    fun columnize(depthMap: FloatArray, w: Int, h: Int, t: Tunables): DepthColumns {
        require(depthMap.size == w * h)
        val n = t.numColumns
        val per = FloatArray(n) { Float.MAX_VALUE }

        val bandY0 = (h * t.verticalBandStart).toInt().coerceIn(0, h - 1)
        val bandY1 = (h * t.verticalBandEnd).toInt().coerceIn(bandY0 + 1, h)
        val colW = max(1, w / n)

        for (c in 0 until n) {
            val x0 = min(w, c * colW)
            val x1 = min(w, (c + 1) * colW)
            var mn = Float.MAX_VALUE
            for (x in x0 until x1) {
                for (y in bandY0 until bandY1) {
                    val v = depthMap[y * w + x]
                    if (v.isFinite() && v in 0.01f..t.maxRangeMeters && v < mn) mn = v
                }
            }
            per[c] = mn
        }

        var nearest = Float.MAX_VALUE
        for (v in per) if (v.isFinite() && v < nearest) nearest = v
        return DepthColumns(per, if (nearest == Float.MAX_VALUE) null else nearest)
    }
}
