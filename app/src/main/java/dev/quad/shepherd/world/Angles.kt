package dev.quad.shepherd.world

/**
 * Bearing conventions used everywhere downstream of the areamap, stated
 * once so nobody has to re-derive them from a call site.
 *
 * A *bearing* is clockwise-positive from the frame's forward axis, which
 * matches both the compass (`headingDeg`, 0 = true north, 90 = east) and
 * [dev.quad.shepherd.nav.CompassNav.goalAngleDeg]. Trigonometric angles
 * run the other way, so every conversion between the two goes through
 * here rather than through an ad-hoc sign flip.
 */
object Angles {

    /** Wrap to (-180, 180]. */
    fun wrapDeg(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /** Wrap to (-pi, pi]. */
    fun wrapRad(rad: Float): Float {
        val twoPi = (2.0 * Math.PI).toFloat()
        var r = rad % twoPi
        if (r > Math.PI) r -= twoPi
        if (r <= -Math.PI) r += twoPi
        return r
    }

    /** Signed shortest turn from [fromDeg] to [toDeg], degrees. */
    fun deltaDeg(fromDeg: Float, toDeg: Float): Float = wrapDeg(toDeg - fromDeg)

    /**
     * Bearing of the vector (right, forward) within its own frame:
     * clockwise-positive, 0 straight ahead. Note the argument order —
     * `atan2(right, forward)`, not the usual `atan2(y, x)` — which is
     * precisely what makes the result a compass bearing.
     */
    fun bearingRad(right: Double, forward: Double): Float =
        Math.atan2(right, forward).toFloat()

    fun bearingDeg(right: Double, forward: Double): Float =
        Math.toDegrees(Math.atan2(right, forward)).toFloat()
}
