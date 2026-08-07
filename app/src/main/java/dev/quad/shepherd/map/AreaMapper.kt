package dev.quad.shepherd.map

import android.os.SystemClock
import android.util.Log
import dev.quad.shepherd.plan.AStarPlanner
import dev.quad.shepherd.plan.CostMap
import dev.quad.shepherd.plan.PathFollower
import dev.quad.shepherd.world.LocalFrame
import dev.quad.shepherd.world.Pose2d
import dev.quad.shepherd.world.WorldAnchor
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Everything the areamap needs to turn poses and depth into one steering
 * number, in one place: integrate observations, keep the Google route
 * anchored into map coordinates, replan, and hand the result to the
 * existing guidance chain as a [goalAngleDeg].
 *
 * The division of labour is deliberate and worth stating once:
 *
 *  - **Google's route** solves global navigation — streets, crossings,
 *    which side of the road. It is fetched once and re-fetched when the
 *    user leaves it.
 *  - **This planner** solves the next ~30 m: the van parked across the
 *    pavement, the roadworks, the wheelie bins. Its goal is a look-ahead
 *    point *on* the route, never the final destination.
 *  - **PolarPlanner** stays the reflex — the thing that reacts inside one
 *    frame when someone steps out. It is fed an ego view derived from this
 *    map rather than its own grid, so there is one source of truth.
 *
 * Threading: [onPose] runs on the ARCore thread; the UI and the service
 * read the volatiles.
 */
class AreaMapper {

    companion object {
        private const val TAG = "AreaMapper"

        /** How far along the route to aim the search. */
        const val GOAL_LOOKAHEAD_M = 20.0

        /** Replan interval. The map changes slowly; A* is cheap but not free. */
        const val REPLAN_INTERVAL_MS = 400L

        /** Half-width of the planning window. */
        const val PLAN_HALF_SPAN_M = 30f

        /** Re-fit the ENU alignment this often. */
        const val REFIT_INTERVAL_MS = 3000L

        /**
         * Below this pose confidence, observations are not written at all.
         * A dead-reckoned pose can still steer the user, but carving walls
         * into a persistent map against a guessed position poisons every
         * plan that comes after it.
         */
        const val MIN_STAMP_CONFIDENCE = 0.35f
    }

    val map = AreaMap()
    val anchor = WorldAnchor()
    private val planner = AStarPlanner()

    /**
     * One coarse lock over map + anchor + route state. Writers arrive on
     * the ARCore thread ([onPose]), the main thread (cane readings, route
     * updates), and the UI reads snapshots — [AreaMap] is explicitly not
     * thread-safe, so everything goes through here. The rates involved
     * (30 Hz writes, 1 Hz UI reads) make contention irrelevant.
     */
    private val lock = Any()

    private var window: AreaMap.Window? = null
    private var costMap: CostMap? = null
    private var overlayWindow: AreaMap.Window? = null

    /** Breadcrumb trail in AR metres, one point per metre walked. */
    private val breadcrumbs = ArrayDeque<DoubleArray>()

    /** Freshest pose regardless of trust — the overlay centres on it. */
    @Volatile private var latestPose: Pose2d? = null

    /** ENU frame anchored at the first usable GPS fix. */
    @Volatile var localFrame: LocalFrame? = null
        private set

    /** The walking route, projected into AR-frame metres. */
    @Volatile private var routeAr: List<DoubleArray>? = null
    private var routeLatLng: List<DoubleArray>? = null

    /** Straight-line destination in AR metres, when there is no route. */
    @Volatile private var destAr: DoubleArray? = null
    private var destLatLng: DoubleArray? = null

    /** Signed degrees to turn, from the planned path. Null = no opinion. */
    @Volatile var goalAngleDeg: Float? = null
        private set

    @Volatile var lastPath: AStarPlanner.Path? = null
        private set

    @Volatile var status: String = "no pose"
        private set

    @Volatile var lastPlanMs = 0L
        private set

    private var lastPlanAt = 0L
    private var lastRefitAt = 0L
    private var lastStampedPose: Pose2d? = null
    private var integrations = 0

    // ---- observations ----------------------------------------------------

    /**
     * One tracked frame. Integrates depth, records the trail, and replans
     * on its own schedule.
     */
    fun onPose(
        pose: Pose2d,
        pitchRad: Float,
        rollRad: Float,
        depthMeters: FloatArray?,
        depthWidth: Int,
        depthHeight: Int,
        depthFx: Float,
    ): Unit = synchronized(lock) {
        latestPose = pose
        if (pose.epoch != map.epoch) {
            Log.w(TAG, "epoch ${map.epoch} -> ${pose.epoch}, dropping the map")
            map.resetForEpoch(pose.epoch)
            anchor.reset()
            lastPath = null
            goalAngleDeg = null
            reprojectRoute()
        }

        val nowMs = SystemClock.elapsedRealtime()
        map.advanceTo(nowMs / 1000.0)

        val trusted = pose.confidence >= MIN_STAMP_CONFIDENCE
        if (trusted) {
            map.markStood(pose.x, pose.y)
            val last = breadcrumbs.lastOrNull()
            if (last == null || Math.hypot(pose.x - last[0], pose.y - last[1]) >= 1.0) {
                breadcrumbs.addLast(doubleArrayOf(pose.x, pose.y))
                while (breadcrumbs.size > 400) breadcrumbs.removeFirst()
            }
            if (depthMeters != null && depthWidth > 0 && depthFx > 0f) {
                val scan = ScanBuilder.fromDepth(
                    depthMeters, depthWidth, depthHeight, depthFx,
                    pitchRad = pitchRad,
                    rollRad = rollRad,
                    cameraHeightM = pose.heightM,
                )
                map.integrateScan(pose, scan, weight = pose.confidence)
                integrations++
            }
            lastStampedPose = pose
        }

        if (nowMs - lastPlanAt >= REPLAN_INTERVAL_MS) {
            lastPlanAt = nowMs
            replan(pose)
        }
    }

    /**
     * The cane's Modulino ping. It is the only genuinely metric range in
     * the system and it sees the near field the camera looks over, so it
     * is stamped at more than full weight.
     */
    fun onCaneDistance(pose: Pose2d?, mm: Int?, present: Boolean): Unit = synchronized(lock) {
        val p = pose ?: return
        if (p.confidence < MIN_STAMP_CONFIDENCE) return
        map.advanceTo(SystemClock.elapsedRealtime() / 1000.0)
        if (present && mm != null && mm > 0) {
            map.integrateCaneRay(p, mm / 1000f)
        } else {
            // A clear reading is evidence too: nothing within the cane's
            // reach, so clear the near field ahead.
            map.integrateCaneRay(p, Float.NaN)
        }
    }

    // ---- ENU alignment ---------------------------------------------------

    /**
     * A GPS fix. Anchors the ENU frame on the first usable one, then
     * accumulates correspondences so [WorldAnchor] can fit the AR frame's
     * true-north bearing from the shape of the walked path.
     */
    fun onGpsFix(pose: Pose2d?, lat: Double, lng: Double, accuracyM: Float): Unit =
        synchronized(lock) {
        val p = pose ?: return
        val frame = localFrame ?: LocalFrame(lat, lng).also {
            localFrame = it
            Log.i(TAG, "ENU frame anchored at %.6f, %.6f".format(lat, lng))
        }
        val en = frame.toEastNorth(lat, lng)
        anchor.addSample(p.x, p.y, en[0], en[1], accuracyM)

        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastRefitAt >= REFIT_INTERVAL_MS) {
            lastRefitAt = nowMs
            anchor.refit()?.let { fit ->
                anchor.slewTowards(fit, REFIT_INTERVAL_MS / 1000f)
                reprojectRoute()
            }
        }
    }

    /**
     * Seed the frame bearing from the compass before enough ground has
     * been covered to fit it properly. Ignored once a fit exists.
     */
    fun seedHeading(pose: Pose2d?, trueHeadingDeg: Float): Unit = synchronized(lock) {
        val p = pose ?: return
        if (trueHeadingDeg.isNaN()) return
        anchor.seedFromCompass(p.bearingRad, Math.toRadians(trueHeadingDeg.toDouble()).toFloat())
        reprojectRoute()
    }

    /** The walking route as [lat, lng] points, or null to clear it. */
    fun setRoute(points: List<DoubleArray>?): Unit = synchronized(lock) {
        routeLatLng = points
        reprojectRoute()
    }

    /** Straight-line destination for indoor/beeline mode. */
    fun setDestination(lat: Double?, lng: Double?): Unit = synchronized(lock) {
        destLatLng = if (lat != null && lng != null) doubleArrayOf(lat, lng) else null
        reprojectRoute()
    }

    private fun reprojectRoute() {
        val frame = localFrame ?: return
        routeAr = routeLatLng?.map { p ->
            val en = frame.toEastNorth(p[0], p[1])
            anchor.toAr(en[0], en[1])
        }
        destAr = destLatLng?.let { d ->
            val en = frame.toEastNorth(d[0], d[1])
            anchor.toAr(en[0], en[1])
        }
    }

    // ---- planning --------------------------------------------------------

    /**
     * @param fallbackGoalAngleDeg the compass/route bearing the app would
     *   have used anyway. When there is no route in map coordinates yet,
     *   the goal is projected along it so the planner still gets to route
     *   around obstacles from the very first metre.
     */
    fun replan(pose: Pose2d, fallbackGoalAngleDeg: Float? = null): Unit = synchronized(lock) {
        val goal = goalPoint(pose, fallbackGoalAngleDeg)
        if (goal == null) {
            goalAngleDeg = null
            lastPath = null
            status = "no destination"
            return
        }

        val t0 = SystemClock.elapsedRealtime()
        val win = map.snapshotWindow(pose.x, pose.y, PLAN_HALF_SPAN_M, window)
        window = win
        val cm = CostMap.build(win, routeAr = routeAr, reuse = costMap)
        costMap = cm

        val path = planner.plan(cm, pose.x, pose.y, goal[0], goal[1])
        lastPlanMs = SystemClock.elapsedRealtime() - t0
        lastPath = path

        goalAngleDeg = path?.let { PathFollower.goalAngleDeg(it, pose) }
        status = when {
            path == null -> "no route through the map"
            path.partial -> "partial plan, %.0f m".format(path.lengthM())
            else -> "plan %.0f m, %d ms".format(path.lengthM(), lastPlanMs)
        }
    }

    /**
     * Where the search should aim: a point on the route about
     * [GOAL_LOOKAHEAD_M] ahead, or failing that a point projected along
     * whatever bearing the compass layer already believes.
     */
    private fun goalPoint(pose: Pose2d, fallbackGoalAngleDeg: Float?): DoubleArray? {
        routeAr?.takeIf { it.size >= 2 }?.let { route ->
            return lookAheadOnRoute(route, pose.x, pose.y, GOAL_LOOKAHEAD_M)
        }
        destAr?.let { d ->
            val dist = hypot(d[0] - pose.x, d[1] - pose.y)
            if (dist <= GOAL_LOOKAHEAD_M) return d
            val t = GOAL_LOOKAHEAD_M / dist
            return doubleArrayOf(
                pose.x + (d[0] - pose.x) * t,
                pose.y + (d[1] - pose.y) * t,
            )
        }
        // Nothing anchored yet: follow the bearing the app already has, so
        // obstacle avoidance works before GPS has pinned the frame down.
        fallbackGoalAngleDeg?.let { deg ->
            val b = pose.bearingRad + Math.toRadians(deg.toDouble()).toFloat()
            return doubleArrayOf(
                pose.x + GOAL_LOOKAHEAD_M * sin(b.toDouble()),
                pose.y + GOAL_LOOKAHEAD_M * cos(b.toDouble()),
            )
        }
        return null
    }

    private fun lookAheadOnRoute(
        route: List<DoubleArray>,
        x: Double,
        y: Double,
        aheadM: Double,
    ): DoubleArray {
        var bestSeg = 0
        var bestT = 0.0
        var bestD = Double.MAX_VALUE
        for (i in 0 until route.size - 1) {
            val ax = route[i][0]
            val ay = route[i][1]
            val dx = route[i + 1][0] - ax
            val dy = route[i + 1][1] - ay
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
        var remaining = aheadM
        var seg = bestSeg
        var t = bestT
        while (seg < route.size - 1) {
            val ax = route[seg][0]
            val ay = route[seg][1]
            val bx = route[seg + 1][0]
            val by = route[seg + 1][1]
            val segLen = hypot(bx - ax, by - ay)
            val left = segLen * (1.0 - t)
            if (left >= remaining) {
                val f = t + (if (segLen > 0) remaining / segLen else 0.0)
                return doubleArrayOf(ax + (bx - ax) * f, ay + (by - ay) * f)
            }
            remaining -= left
            seg++
            t = 0.0
        }
        return route.last().copyOf()
    }

    // ---- readouts --------------------------------------------------------

    /**
     * The ego-centric occupancy the reflex planner expects, rotated out of
     * the world map. Correct after a turn, which its own grid was not.
     */
    fun egoView(pose: Pose2d, cellsWide: Int, cellsDeep: Int, cellM: Float): FloatArray =
        synchronized(lock) { map.egoView(pose, cellsWide, cellsDeep, cellM) }

    fun debugLine(): String =
        "map ${map.tileCount}t/${map.visitedCells}v · $status · " +
            "anchor ${if (anchor.fitted) "fit" else "compass"}(${anchor.sampleCount})"

    // ---- debug overlay ---------------------------------------------------

    /**
     * Everything the activity needs to draw the areamap over the Google
     * map: an ARGB tile of the occupancy around the user, positioned in
     * lat/lng, plus the walked trail and the planned path as coordinate
     * lists.
     *
     * This doubles as the end-to-end frame check — the render pipeline is
     * AR metres → [WorldAnchor] → ENU → [LocalFrame] → lat/lng, so if the
     * red cells sit on the building footprints of the base map, every
     * transform in that chain is right. If the overlay is rotated against
     * the streets, the anchor bearing is off (walk further; the trajectory
     * fit will pull it in).
     */
    class OverlayState(
        val centerLat: Double,
        val centerLng: Double,
        /** Rotation of the tile, clockwise from north (= anchor theta). */
        val bearingDeg: Float,
        /** Edge length of the square tile, metres. */
        val widthM: Float,
        val pixels: IntArray,
        val pxWide: Int,
        val pxHigh: Int,
        /** [lat, lng] per point. */
        val trail: List<DoubleArray>,
        val path: List<DoubleArray>,
    )

    fun overlayState(halfSpanM: Float = 20f): OverlayState? = synchronized(lock) {
        val frame = localFrame ?: return null
        val pose = latestPose ?: return null

        val win = map.snapshotWindow(pose.x, pose.y, halfSpanM, overlayWindow)
        overlayWindow = win
        val n = win.width
        val px = IntArray(n * win.height)
        for (iy in 0 until win.height) {
            // Bitmap row 0 is the TOP of the image; the window's iy grows
            // with world +y, so the render flips vertically.
            val row = (win.height - 1 - iy) * n
            for (ix in 0 until n) {
                val l = win.at(ix, iy)
                px[row + ix] = when {
                    l > AreaMap.OCCUPIED_THRESHOLD -> 0xD9E53935.toInt() // obstacle
                    l > 0.2f -> 0x80FB8C00.toInt()                       // suspicious
                    l < -AreaMap.OCCUPIED_THRESHOLD -> 0x3D43A047        // known free
                    l < -0.2f -> 0x2643A047                              // leaning free
                    else -> 0x00000000                                   // unknown
                }
            }
        }

        // Centre of the window (cell-snapped, so the tile does not wobble
        // by sub-cell amounts between renders)
        val cx = map.centerOfCell(map.cellOf(pose.x))
        val cy = map.centerOfCell(map.cellOf(pose.y))
        val en = anchor.toEastNorth(cx, cy)
        val ll = frame.toLatLng(en[0], en[1])

        fun toLatLng(p: DoubleArray): DoubleArray {
            val e = anchor.toEastNorth(p[0], p[1])
            return frame.toLatLng(e[0], e[1])
        }

        OverlayState(
            centerLat = ll[0],
            centerLng = ll[1],
            bearingDeg = Math.toDegrees(anchor.thetaRad.toDouble()).toFloat(),
            widthM = n * map.cellMeters,
            pixels = px,
            pxWide = n,
            pxHigh = win.height,
            trail = breadcrumbs.map(::toLatLng),
            path = lastPath?.points?.map(::toLatLng) ?: emptyList(),
        )
    }
}
