package dev.quad.shepherd.map

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A depth frame reduced to a 2-D range scan — one nearest-obstacle range
 * and one trusted-free range per bearing bin.
 *
 * Reducing before mapping is not a shortcut. Past the nearest obstacle
 * along a bearing the world is occluded, so a farther hit in the same
 * direction adds nothing; and casting one ray per bin instead of one per
 * depth pixel turns ~19 000 rays a frame into ~180.
 *
 * Bearings are relative to where the camera is pointing, clockwise
 * positive, so bin 0 is the far left of the field of view.
 */
class Scan(
    val bins: Int,
    val fovRad: Float,
    /** Nearest obstacle per bin, metres; NaN where none was seen. */
    val obstacleRangeM: FloatArray,
    /** Farthest range along the bin that is trusted free; 0 if none. */
    val freeRangeM: FloatArray,
) {
    /** Bin index -> bearing relative to camera forward, radians. */
    fun bearingOfRad(bin: Int): Float =
        if (bins <= 1) 0f else -fovRad / 2f + fovRad * bin / (bins - 1)

    /** Bins carrying any evidence at all. */
    fun populated(): Int =
        (0 until bins).count { obstacleRangeM[it].isFinite() || freeRangeM[it] > 0f }
}

/**
 * Projects a metric depth image onto the ground plane and folds it into a
 * [Scan].
 *
 * The projection matches
 * [dev.quad.shepherd.path.TraversabilityGrid] exactly — same un-roll,
 * then pitch, then height-above-ground test — because that math is
 * field-tested and two subtly different projections in one app is a bug
 * generator. What differs is where the result goes: there, into a grid
 * that forgets; here, into a world map that does not.
 *
 * Pure Kotlin for JVM unit testing.
 */
object ScanBuilder {

    /** Points this far above ground count as obstacles. */
    const val OBSTACLE_MIN_HEIGHT_M = 0.18f

    /** Ignore overhead structure above this — doorframes stay walkable. */
    const val OBSTACLE_MAX_HEIGHT_M = 2.3f

    /** |height| below this is ground, and therefore free to walk on. */
    const val GROUND_TOLERANCE_M = 0.16f

    const val MIN_DEPTH_M = 0.25f
    const val DEFAULT_BINS = 121

    /**
     * @param depth row-major metric depth, [w] x [h], metres. Non-finite
     *   entries are "no reading" and are skipped, which is how ARCore's
     *   confidence gating and the letterbox mask both signal absence.
     * @param fx focal length in pixels for the depth image geometry.
     * @param pitchRad camera pitch below horizontal, positive = down.
     * @param rollRad camera roll about the optical axis.
     * @param cameraHeightM camera height above the ground plane.
     * @param groundOffsetM correction to that height, when the floor has
     *   been measured (ARCore's detected plane) rather than assumed.
     */
    fun fromDepth(
        depth: FloatArray,
        w: Int,
        h: Int,
        fx: Float,
        pitchRad: Float,
        rollRad: Float,
        cameraHeightM: Float,
        groundOffsetM: Float = 0f,
        fovRad: Float = 2f * atan2(w / 2f, fx),
        bins: Int = DEFAULT_BINS,
        stride: Int = 2,
        maxRangeM: Float = AreaMap.MAX_RANGE_M,
    ): Scan {
        val obstacle = FloatArray(bins) { Float.NaN }
        val free = FloatArray(bins)

        val cx = w / 2f
        val cy = h / 2f
        val cosP = cos(pitchRad)
        val sinP = sin(pitchRad)
        val cosR = cos(rollRad)
        val sinR = sin(rollRad)

        var v = 0
        while (v < h) {
            var u = 0
            while (u < w) {
                val d = depth[v * w + u]
                if (d.isFinite() && d >= MIN_DEPTH_M && d <= maxRangeM) {
                    // Camera frame: xi right, yi up, d forward
                    val xi = (u - cx) / fx * d
                    val yi = -((v - cy) / fx * d)
                    // Un-roll about the optical axis, then pitch about X
                    val xc = xi * cosR + yi * sinR
                    val yUp = -xi * sinR + yi * cosR
                    val yW = yUp * cosP - d * sinP
                    val zW = yUp * sinP + d * cosP
                    val height = cameraHeightM + yW - groundOffsetM

                    if (zW > 0f) {
                        val range = hypot(xc.toDouble(), zW.toDouble()).toFloat()
                        if (range in MIN_DEPTH_M..maxRangeM) {
                            val bearing = atan2(xc.toDouble(), zW.toDouble()).toFloat()
                            val bin = binOf(bearing, fovRad, bins)
                            if (bin >= 0) {
                                when {
                                    height > OBSTACLE_MIN_HEIGHT_M &&
                                        height < OBSTACLE_MAX_HEIGHT_M -> {
                                        val cur = obstacle[bin]
                                        if (!cur.isFinite() || range < cur) {
                                            obstacle[bin] = range
                                        }
                                    }

                                    abs(height) <= GROUND_TOLERANCE_M -> {
                                        if (range > free[bin]) free[bin] = range
                                    }
                                    // Otherwise: overhead, or below-floor
                                    // noise. Neither is evidence about
                                    // whether the ground is walkable.
                                }
                            }
                        }
                    }
                }
                u += stride
            }
            v += stride
        }

        // Ground seen BEYOND an obstacle is a projection artifact — you
        // cannot see through a wall. Clamp free space to the obstacle.
        for (b in 0 until bins) {
            val o = obstacle[b]
            if (o.isFinite() && free[b] > o) free[b] = o
        }
        return Scan(bins, fovRad, obstacle, free)
    }

    /** Bearing -> bin, or -1 when outside the field of view. */
    fun binOf(bearingRad: Float, fovRad: Float, bins: Int): Int {
        if (bins <= 1) return 0
        val t = (bearingRad + fovRad / 2f) / fovRad
        if (t < 0f || t > 1f) return -1
        return Math.round(t * (bins - 1)).coerceIn(0, bins - 1)
    }
}
