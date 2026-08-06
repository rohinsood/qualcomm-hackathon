package com.wayfinder.app.perception.seg

import com.wayfinder.app.core.loop.Frame
import com.wayfinder.app.perception.ModelRunner
import kotlin.math.abs
import kotlin.math.sin

/**
 * PLACEHOLDER model runner. Returns a synthetic walkable mask so the steering
 * loop, gap-seeker, haptics, and speech can all be exercised end-to-end BEFORE
 * the real QAIRT-compiled segmentation .tflite is dropped in.
 *
 * It simulates:
 *  - a corridor whose central GAP drifts left↔right over time (→ you'll hear/feel
 *    "steer right / steer left" cycle), and
 *  - a CENTER OBSTACLE that blocks the gap every other interval (→ triggers
 *    proximity-scaled haptics + "obstacle ahead").
 *
 * Replace with [TFLiteSegmentationRunner] once a real model is available.
 */
class SyntheticSegmentationRunner(
    private val outW: Int = 128,
    private val outH: Int = 72,
) : ModelRunner {

    override val name: String = "synthetic-seg"

    override fun segment(frame: Frame): WalkableMask {
        val t = frame.timestampMs
        val pixels = ByteArray(outW * outH)

        // Time-based scene (keyed off frame.timestampMs) so the synthetic depth
        // runner — which runs at a lower rate — stays phase-aligned with this mask.
        val gapCenter = (0.5 + 0.25 * sin(t * 0.0015)).toFloat()
        val centerBlocked = ((t / 2000L).toInt() % 2) == 1

        for (y in 0 until outH) {
            // Slight vertical taper so the band logic in Columnizer has structure.
            val yt = y / outH.toFloat()
            for (x in 0 until outW) {
                val xf = x / outW.toFloat()
                val distFromGap = abs(xf - gapCenter)
                val walkable = when {
                    centerBlocked && distFromGap < 0.18f -> false        // obstacle blocking the gap
                    distFromGap < 0.30f && yt > 0.2f -> true             // the open corridor
                    else -> false                                        // walls at the edges / ceiling
                }
                pixels[y * outW + x] = if (walkable) WalkableMask.WALKABLE else WalkableMask.BLOCKED
            }
        }
        return WalkableMask(outW, outH, pixels)
    }
}
