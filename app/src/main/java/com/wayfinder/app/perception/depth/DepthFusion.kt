package com.wayfinder.app.perception.depth

import com.wayfinder.app.core.config.Tunables
import com.wayfinder.app.perception.columnize.ColumnSignal

/** The fused per-cycle signal the gap-seeker consumes, plus debug info. */
data class FusedSignal(
    val signal: ColumnSignal,         // direction (clearance) + magnitude (nearest)
    val overrideCount: Int,           // # columns depth overrode this cycle
    val depthPerColumn: FloatArray?,  // for the debug overlay; null when depth is inactive
)

/**
 * Principled segmentation × depth fusion (architecture.md §6.1):
 *
 *  - DIRECTION  ← segmentation clearance (robust outdoors; monocular depth is weak at it).
 *  - MAGNITUDE  ← depth's nearest obstacle when available (accurate), else seg's reach estimate.
 *  - SAFETY OVERRIDE ← if depth sees a close surface (< [Tunables.depthOverrideMeters]) in a
 *    column, that column's clearance is forced to 0 regardless of what segmentation said.
 *    This catches obstacles segmentation mislabeled as walkable (glass, clutter, drop-offs).
 *
 * The output is a normal [ColumnSignal], so [com.wayfinder.app.steering.GapSeeker] is unchanged.
 */
class DepthFusion(private val t: Tunables) {

    @Volatile
    private var latestDepth: DepthColumns? = null

    /** Called by the steering loop whenever a fresh depth result is produced (low rate). */
    fun updateDepth(d: DepthColumns?) {
        if (d != null) latestDepth = d
    }

    fun fuse(seg: ColumnSignal): FusedSignal {
        val depth = latestDepth ?: return FusedSignal(seg, 0, null)

        val fusedClearance = seg.clearance.copyOf()
        var overrides = 0
        for (c in fusedClearance.indices) {
            val dv = depth.perColumn.getOrNull(c)
            if (dv != null && dv.isFinite() && dv < t.depthOverrideMeters) {
                fusedClearance[c] = 0f
                overrides++
            }
        }

        // Magnitude from depth (accurate), falling back to the seg reach estimate.
        val nearest = depth.nearestMeters ?: seg.nearestObstacleMeters
        return FusedSignal(ColumnSignal(fusedClearance, nearest), overrides, depth.perColumn.copyOf())
    }
}
