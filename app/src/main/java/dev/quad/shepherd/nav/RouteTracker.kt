package dev.quad.shepherd.nav

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.PI

/**
 * Continually-updated route following: given the walking route's points
 * and maneuver steps, each GPS fix + compass heading produces a steering
 * bias toward a look-ahead point on the path, plus turn cues, arrival and
 * off-route detection. Off-route triggers the caller to fetch a fresh
 * route (the "path finding that gets continually updated").
 *
 * All math in a local equirectangular meter frame around the route origin
 * — plenty accurate at walking scale. Pure Kotlin for JVM unit testing.
 */
class RouteTracker(private val route: Route) {

    companion object {
        /** Aim this far ahead along the path. */
        private const val LOOKAHEAD_M = 12.0

        /** Heading error that maps to full steer deflection. */
        private const val FULL_STEER_DEG = 60.0

        /** Farther than this from the path counts as off-route. */
        private const val OFF_ROUTE_M = 30.0

        /** Consecutive off-route updates before asking for a reroute. */
        private const val OFF_ROUTE_STRIKES = 4

        /** Speak the maneuver when it is this close. */
        private const val CUE_DISTANCE_M = 22.0

        private const val ARRIVAL_M = 15.0
    }

    data class Route(
        /** [lat, lng] pairs along the whole route. */
        val points: List<DoubleArray>,
        /** Maneuvers: index into [points] where the step begins + text. */
        val steps: List<Step>,
        val totalMeters: Double,
    )

    data class Step(val pointIndex: Int, val instruction: String)

    enum class Event { TURN_CUE, ARRIVED, OFF_ROUTE }

    data class Update(
        /** -1..1 steering bias toward the path, or null with no heading. */
        val steer: Float?,
        val remainingMeters: Double,
        val event: Event?,
        val cueText: String?,
    )

    // Local meter frame around the first route point
    private val lat0 = route.points.first()[0]
    private val lng0 = route.points.first()[1]
    private val mPerLng = 111_320.0 * cos(Math.toRadians(lat0))
    private val mPerLat = 110_540.0

    private val xs = DoubleArray(route.points.size)
    private val ys = DoubleArray(route.points.size)
    private val cumulative = DoubleArray(route.points.size)

    init {
        require(route.points.size >= 2) { "route needs at least 2 points" }
        for (i in route.points.indices) {
            xs[i] = (route.points[i][1] - lng0) * mPerLng
            ys[i] = (route.points[i][0] - lat0) * mPerLat
            if (i > 0) {
                cumulative[i] = cumulative[i - 1] +
                    hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            }
        }
    }

    private var lastSegment = 0
    private var offRouteStrikes = 0
    private var cuedStep = -1

    fun update(lat: Double, lng: Double, headingDeg: Float?): Update {
        val px = (lng - lng0) * mPerLng
        val py = (lat - lat0) * mPerLat

        // Snap to the nearest point on the polyline, searching from the
        // last known segment forward (never backward past a window) so a
        // self-crossing route cannot teleport progress backwards
        var bestSeg = lastSegment
        var bestT = 0.0
        var bestDist = Double.MAX_VALUE
        val from = maxOf(0, lastSegment - 2)
        val to = minOf(xs.size - 2, lastSegment + 40)
        for (seg in from..to) {
            val ax = xs[seg]
            val ay = ys[seg]
            val bx = xs[seg + 1]
            val by = ys[seg + 1]
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0
            else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx
            val cy = ay + t * dy
            val d = hypot(px - cx, py - cy)
            if (d < bestDist) {
                bestDist = d
                bestSeg = seg
                bestT = t
            }
        }
        lastSegment = bestSeg

        val progress = cumulative[bestSeg] +
            bestT * (cumulative[bestSeg + 1] - cumulative[bestSeg])
        val remaining = (route.totalMeters - progress).coerceAtLeast(0.0)

        // Arrival
        if (remaining <= ARRIVAL_M) {
            return Update(0f, remaining, Event.ARRIVED, null)
        }

        // Off-route: far from the path for several consecutive fixes
        if (bestDist > OFF_ROUTE_M) {
            offRouteStrikes++
            if (offRouteStrikes >= OFF_ROUTE_STRIKES) {
                offRouteStrikes = 0
                return Update(null, remaining, Event.OFF_ROUTE, null)
            }
        } else {
            offRouteStrikes = 0
        }

        // Look-ahead target along the path
        val targetDist = progress + LOOKAHEAD_M
        var ti = bestSeg + 1
        while (ti < cumulative.size - 1 && cumulative[ti] < targetDist) ti++
        val (txp, typ) = pointAlong(ti, targetDist)

        val bearingToTarget = Math.toDegrees(atan2(txp - px, typ - py)) // compass: 0=N, 90=E
        val steer: Float? = headingDeg?.let { h ->
            val err = normalize(bearingToTarget - h)
            (err / FULL_STEER_DEG).coerceIn(-1.0, 1.0).toFloat()
        }

        // Turn cue for the next maneuver ahead
        var event: Event? = null
        var cue: String? = null
        for ((i, step) in route.steps.withIndex()) {
            val stepAt = cumulative[step.pointIndex.coerceIn(0, cumulative.size - 1)]
            if (stepAt > progress + 1.0) {
                if (stepAt - progress <= CUE_DISTANCE_M && i != cuedStep) {
                    cuedStep = i
                    event = Event.TURN_CUE
                    cue = step.instruction
                }
                break
            }
        }

        return Update(steer, remaining, event, cue)
    }

    private fun pointAlong(hintIndex: Int, targetDist: Double): Pair<Double, Double> {
        val i = hintIndex.coerceIn(1, cumulative.size - 1)
        val segStart = cumulative[i - 1]
        val segLen = (cumulative[i] - segStart).takeIf { it > 0.0 }
            ?: return xs[i] to ys[i]
        val t = ((targetDist - segStart) / segLen).coerceIn(0.0, 1.0)
        return (xs[i - 1] + t * (xs[i] - xs[i - 1])) to (ys[i - 1] + t * (ys[i] - ys[i - 1]))
    }

    private fun normalize(deg: Double): Double {
        var d = deg % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }
}
