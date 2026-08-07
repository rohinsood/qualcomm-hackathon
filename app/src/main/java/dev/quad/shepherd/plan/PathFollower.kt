package dev.quad.shepherd.plan

import dev.quad.shepherd.world.Angles
import dev.quad.shepherd.world.Pose2d
import kotlin.math.hypot

/**
 * Turns a planned path into the one number the rest of the app already
 * speaks: a signed angle to turn, exactly like
 * [dev.quad.shepherd.nav.CompassNav.goalAngleDeg].
 *
 * Pure pursuit rather than "aim at the next waypoint": chasing the nearest
 * waypoint produces a hard turn the moment one is passed, and on a path
 * smoothed to any-angle the waypoints can be metres apart. Aiming at a
 * point a fixed distance ahead *along* the path gives a continuous
 * bearing that eases through corners the way a person walks them.
 *
 * The look-ahead distance is the whole character of the follower. Short is
 * twitchy and hugs corners; long cuts them and ignores detail. About three
 * metres suits walking pace.
 *
 * Pure Kotlin for JVM unit testing.
 */
object PathFollower {

    const val DEFAULT_LOOKAHEAD_M = 3.0

    /**
     * The pure-pursuit target: the point [lookAheadM] further along the
     * path than the projection of ([x], [y]) onto it.
     *
     * @return [x, y] world metres, or null for a degenerate path.
     */
    fun lookAheadPoint(
        path: AStarPlanner.Path,
        x: Double,
        y: Double,
        lookAheadM: Double = DEFAULT_LOOKAHEAD_M,
    ): DoubleArray? {
        val pts = path.points
        if (pts.size < 2) return null

        // Closest point on the polyline, and how far along it that is
        var bestSeg = 0
        var bestT = 0.0
        var bestD = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val ax = pts[i][0]
            val ay = pts[i][1]
            val dx = pts[i + 1][0] - ax
            val dy = pts[i + 1][1] - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 0.0) 0.0
            else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(x - (ax + t * dx), y - (ay + t * dy))
            if (d < bestD) {
                bestD = d
                bestSeg = i
                bestT = t
            }
        }

        // Walk forward along the path from there
        var remaining = lookAheadM
        var seg = bestSeg
        var t = bestT
        while (seg < pts.size - 1) {
            val ax = pts[seg][0]
            val ay = pts[seg][1]
            val bx = pts[seg + 1][0]
            val by = pts[seg + 1][1]
            val segLen = hypot(bx - ax, by - ay)
            val leftOnSeg = segLen * (1.0 - t)
            if (leftOnSeg >= remaining) {
                val f = t + (if (segLen > 0) remaining / segLen else 0.0)
                return doubleArrayOf(ax + (bx - ax) * f, ay + (by - ay) * f)
            }
            remaining -= leftOnSeg
            seg++
            t = 0.0
        }
        // Ran off the end: aim at the last waypoint
        return pts.last().copyOf()
    }

    /**
     * Signed degrees to turn toward the path — the drop-in replacement for
     * the compass-derived goal angle the polar planner already consumes.
     *
     * @return null when the path is unusable, which the caller should read
     *   as "fall back to the route bearing", never as "go straight".
     */
    fun goalAngleDeg(
        path: AStarPlanner.Path,
        pose: Pose2d,
        lookAheadM: Double = DEFAULT_LOOKAHEAD_M,
    ): Float? {
        val target = lookAheadPoint(path, pose.x, pose.y, lookAheadM) ?: return null
        val dx = target[0] - pose.x
        val dy = target[1] - pose.y
        if (hypot(dx, dy) < 1e-6) return null
        val worldBearing = Angles.bearingRad(dx, dy)
        return Math.toDegrees(
            Angles.wrapRad(worldBearing - pose.bearingRad).toDouble(),
        ).toFloat()
    }

    /** How far off the planned path the user currently is, metres. */
    fun crossTrackErrorM(path: AStarPlanner.Path, x: Double, y: Double): Double {
        val pts = path.points
        if (pts.isEmpty()) return Double.NaN
        if (pts.size == 1) return hypot(x - pts[0][0], y - pts[0][1])
        var best = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val ax = pts[i][0]
            val ay = pts[i][1]
            val dx = pts[i + 1][0] - ax
            val dy = pts[i + 1][1] - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 0.0) 0.0
            else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(x - (ax + t * dx), y - (ay + t * dy))
            if (d < best) best = d
        }
        return best
    }
}
