package com.wayfinder.app.perception.depth

import com.wayfinder.app.core.config.Tunables
import com.wayfinder.app.core.loop.Frame
import kotlin.math.abs
import kotlin.math.sin

/**
 * PLACEHOLDER depth runner. Time-synchronized with [com.wayfinder.app.perception.seg.SyntheticSegmentationRunner]
 * (both key off `frame.timestampMs`, so they agree regardless of depth's lower call rate)
 * and periodically injects a PHANTOM close obstacle exactly in the gap column to
 * demonstrate the depth safety-override — i.e. depth catching something segmentation
 * "missed." Replace with [TFLiteDepthRunner] once a real model is available.
 */
class SyntheticDepthRunner(
    private val tunables: Tunables,
) : DepthRunner {

    override val name: String = "synthetic-depth"

    override fun depth(frame: Frame): DepthColumns {
        val t = frame.timestampMs
        val n = tunables.numColumns
        val per = FloatArray(n)

        // Same scene timing as the synthetic segmentation runner → coherent gap drift.
        val gapCenter = (0.5 + 0.25 * sin(t * 0.0015)).toFloat()
        val centerBlocked = ((t / 2000L).toInt() % 2) == 1
        // Every ~5s, drop a phantom close obstacle in the (walkable) gap column.
        val phantomOn = ((t / 5000L).toInt() % 3) == 1
        val phantomCol = (gapCenter * (n - 1)).toInt().coerceIn(0, n - 1)

        var nearest = Float.MAX_VALUE
        for (c in 0 until n) {
            val xf = c / (n - 1).toFloat()
            val dist = when {
                phantomOn && !centerBlocked && c == phantomCol -> 0.6f   // seg missed this (safety override)
                centerBlocked && abs(xf - gapCenter) < 0.18f -> 0.5f     // center obstacle
                else -> 5.0f                                             // open
            }
            per[c] = dist
            if (dist < nearest) nearest = dist
        }
        return DepthColumns(per, if (nearest == Float.MAX_VALUE) null else nearest)
    }
}
