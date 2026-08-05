package dev.quad.shepherd.guidance

/**
 * Merges obstacle-avoidance steering with the navigation goal bias, the
 * way the original Shepherd does: the navigation term is added scaled by
 * (1 - proximity), so the closer the nearest obstacle, the less the route
 * matters — and in DANGER navigation is ignored entirely. With no route
 * active the obstacle steer passes through untouched.
 *
 * Pure Kotlin for JVM unit testing.
 */
object SteerFusion {

    /** Obstacle distance at/below which navigation influence hits zero. */
    private const val NEAR_M = 0.8f

    /** Obstacle distance at/above which navigation has full influence. */
    private const val FAR_M = 3.0f

    fun fuse(g: GuidanceEngine.Guidance, goalSteer: Float?): Float {
        if (goalSteer == null) return g.steer
        if (g.severity == GuidanceEngine.Severity.DANGER) return g.steer

        val proximity = when (val d = g.nearestDistanceMeters) {
            null -> if (g.severity == GuidanceEngine.Severity.CLEAR) 0f else 0.5f
            else -> (1f - (d - NEAR_M) / (FAR_M - NEAR_M)).coerceIn(0f, 1f)
        }
        return (g.steer + goalSteer * (1f - proximity)).coerceIn(-1f, 1f)
    }
}
