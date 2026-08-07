package dev.quad.shepherd

import dev.quad.shepherd.map.AreaMap
import dev.quad.shepherd.plan.AStarPlanner
import dev.quad.shepherd.plan.CostMap
import dev.quad.shepherd.plan.PathFollower
import dev.quad.shepherd.world.Pose2d
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PlannerTest {

    private val planner = AStarPlanner()

    // ---- synthetic worlds ------------------------------------------------

    private fun AreaMap.wall(x0: Double, y0: Double, x1: Double, y1: Double) {
        val steps = (Math.hypot(x1 - x0, y1 - y0) / 0.04).toInt().coerceAtLeast(1)
        for (s in 0..steps) {
            val t = s.toDouble() / steps
            val x = x0 + (x1 - x0) * t
            val y = y0 + (y1 - y0) * t
            repeat(2) { markOccupied(cellOf(x), cellOf(y)) }
        }
    }

    private fun costMapAround(m: AreaMap, cx: Double, cy: Double, halfSpanM: Float = 20f) =
        CostMap.build(m.snapshotWindow(cx, cy, halfSpanM))

    /**
     * Where the path crosses the horizontal line y = [wallY], interpolated
     * along the segment. Reading the segment's start x instead is wrong by
     * as much as the whole segment — and after smoothing, segments are
     * metres long.
     */
    private fun crossingX(path: AStarPlanner.Path, wallY: Double): Double? {
        for ((a, b) in path.points.zipWithNext()) {
            if ((a[1] - wallY) * (b[1] - wallY) <= 0.0 && a[1] != b[1]) {
                val t = (wallY - a[1]) / (b[1] - a[1])
                return a[0] + (b[0] - a[0]) * t
            }
        }
        return null
    }

    private fun pose(x: Double, y: Double, bearingDeg: Double = 0.0) = Pose2d(
        x = x, y = y, bearingRad = Math.toRadians(bearingDeg).toFloat(),
        heightM = 1.4f, timestampNs = 0L, confidence = 1f, epoch = 0,
    )

    // ---- basics ----------------------------------------------------------

    @Test
    fun `an unmapped world still yields a plan`() {
        // Everything unknown. A planner that refuses unseen ground never
        // takes the first step of the first walk.
        val m = AreaMap()
        val cm = costMapAround(m, 0.0, 0.0)
        val path = planner.plan(cm, 0.0, 0.0, 0.0, 12.0)
        assertNotNull("unknown space must be traversable", path)
        assertFalse(path!!.partial)
        // With nothing in the way it should be essentially a straight line
        assertEquals(12.0, path.lengthM(), 1.5)
    }

    @Test
    fun `a plain wall is routed around through its gap`() {
        val m = AreaMap()
        m.wall(-12.0, 5.0, 1.0, 5.0)
        m.wall(3.0, 5.0, 12.0, 5.0)
        val cm = costMapAround(m, 0.0, 0.0)
        val path = planner.plan(cm, 0.0, 0.0, 0.0, 12.0)
        assertNotNull(path)
        assertFalse(path!!.partial)

        // It must actually thread the gap, not walk through the wall
        val xAtWall = crossingX(path, 5.0)
        assertNotNull("path never crossed the wall line", xAtWall)
        assertTrue("crossed at x=$xAtWall, outside the gap", xAtWall!! > 0.5 && xAtWall < 3.5)
    }

    @Test
    fun `a gap narrower than a person is not a gap`() {
        val m = AreaMap()
        // 30 cm opening: a point robot fits, a person does not
        m.wall(-12.0, 5.0, -0.15, 5.0)
        m.wall(0.15, 5.0, 12.0, 5.0)
        val cm = costMapAround(m, 0.0, 0.0)
        val path = planner.plan(cm, 0.0, 0.0, 0.0, 12.0)
        // Either no route, or one that goes the long way round the ends
        if (path != null && !path.partial) {
            val xAtWall = crossingX(path, 5.0)
            if (xAtWall != null) {
                assertTrue(
                    "squeezed through a 30 cm gap at x=$xAtWall",
                    abs(xAtWall) > 2.0,
                )
            }
        }
    }

    // ---- the case the reactive planner cannot do -------------------------

    @Test
    fun `a U-shaped trap is escaped by planning backwards`() {
        // Three walls around the user, opening behind them, goal beyond.
        // The polar planner steers at the best-looking gap in view: it
        // walks into the pocket, finds every sector blocked and stops.
        val m = AreaMap()
        m.wall(-3.0, 0.0, -3.0, 8.0)   // left
        m.wall(3.0, 0.0, 3.0, 8.0)     // right
        m.wall(-3.0, 8.0, 3.0, 8.0)    // closed end, between user and goal
        val cm = costMapAround(m, 0.0, 4.0)

        val path = planner.plan(cm, 0.0, 4.0, 0.0, 16.0)
        assertNotNull("no way out of the pocket was found", path)
        assertFalse("should be a complete route, not a partial", path!!.partial)

        // The proof: the first move is AWAY from the goal, down and out of
        // the opening. No gap-seeker can produce that.
        val p = pose(0.0, 4.0, bearingDeg = 0.0) // facing the closed end
        val angle = PathFollower.goalAngleDeg(path, p)
        assertNotNull(angle)
        assertTrue(
            "first move should head back out, got ${angle}deg",
            abs(angle!!) > 90f,
        )

        // And the route must dip below the mouth of the U
        val minY = path.points.minOf { it[1] }
        assertTrue("never left the pocket (min y = $minY)", minY < 0.5)
    }

    @Test
    fun `being sealed in is reported, not papered over`() {
        val m = AreaMap()
        m.wall(-3.0, 0.0, -3.0, 8.0)
        m.wall(3.0, 0.0, 3.0, 8.0)
        m.wall(-3.0, 8.0, 3.0, 8.0)
        m.wall(-3.0, 0.0, 3.0, 0.0) // sealed
        val cm = costMapAround(m, 0.0, 4.0)
        val path = planner.plan(cm, 0.0, 4.0, 0.0, 16.0)
        assertTrue(
            "a sealed box must not yield a confident route out",
            path == null || path.partial,
        )
    }

    @Test
    fun `standing inside an obstacle still produces a plan`() {
        // Stale evidence or a pose jump can put the user's own cell in an
        // obstacle. Guidance dying at that exact moment is the worst
        // possible behaviour, so the search nudges to open ground.
        val m = AreaMap()
        repeat(4) { m.markOccupied(m.cellOf(0.0), m.cellOf(0.0)) }
        val cm = costMapAround(m, 0.0, 0.0)
        val path = planner.plan(cm, 0.0, 0.0, 0.0, 10.0)
        assertNotNull("must not give up because the user is 'inside' a wall", path)
    }

    // ---- cost shaping ----------------------------------------------------

    @Test
    fun `paths keep their distance from walls`() {
        val m = AreaMap()
        m.wall(-1.0, 0.0, -1.0, 12.0) // wall just to the left of the route
        val cm = costMapAround(m, 0.0, 0.0)
        val path = planner.plan(cm, 0.0, 0.0, 0.0, 10.0)!!
        val closest = path.points.minOf { abs(it[0] - (-1.0)) }
        assertTrue(
            "path hugged the wall at ${closest} m",
            closest >= CostMap.ROBOT_RADIUS_M - 0.05,
        )
    }

    @Test
    fun `the route corridor pulls a detour back on line`() {
        val m = AreaMap()
        // A route running straight up x = 0
        val route = listOf(
            doubleArrayOf(0.0, -5.0), doubleArrayOf(0.0, 5.0), doubleArrayOf(0.0, 20.0),
        )
        val withCorridor = CostMap.build(
            m.snapshotWindow(0.0, 0.0, 20f), routeAr = route,
        )
        // A goal off to one side: the corridor should make the wide swing
        // more expensive than the direct line
        val far = withCorridor.costAt(
            withCorridor.indexX(15.0), withCorridor.indexY(0.0),
        )
        val near = withCorridor.costAt(
            withCorridor.indexX(0.0), withCorridor.indexY(0.0),
        )
        assertTrue("corridor did not penalise straying: $near vs $far", far > near)
    }

    @Test
    fun `ground already walked on is cheaper than unknown ground`() {
        val m = AreaMap()
        m.markStood(2.0, 2.0, radiusM = 0.6f)
        val cm = costMapAround(m, 0.0, 0.0)
        val walked = cm.costAt(cm.indexX(2.0), cm.indexY(2.0))
        val unknown = cm.costAt(cm.indexX(-8.0), cm.indexY(-8.0))
        assertTrue("walked ground should be preferred: $walked vs $unknown", walked < unknown)
    }

    // ---- line of sight ---------------------------------------------------

    @Test
    fun `line of sight is blocked by a wall and clear without one`() {
        val m = AreaMap()
        m.wall(-5.0, 3.0, 5.0, 3.0)
        val cm = costMapAround(m, 0.0, 0.0)
        val a = cm.indexY(0.0) * cm.width + cm.indexX(0.0)
        val b = cm.indexY(6.0) * cm.width + cm.indexX(0.0)
        assertFalse(planner.lineOfSight(cm, a, b))

        val empty = costMapAround(AreaMap(), 0.0, 0.0)
        val a2 = empty.indexY(0.0) * empty.width + empty.indexX(0.0)
        val b2 = empty.indexY(6.0) * empty.width + empty.indexX(0.0)
        assertTrue(planner.lineOfSight(empty, a2, b2))
    }

    @Test
    fun `smoothing collapses a clear run to its endpoints`() {
        val cm = costMapAround(AreaMap(), 0.0, 0.0)
        val path = planner.plan(cm, 0.0, 0.0, 0.0, 12.0)!!
        assertTrue(
            "a straight clear run should not need many waypoints: ${path.points.size}",
            path.points.size <= 4,
        )
    }

    // ---- follower --------------------------------------------------------

    @Test
    fun `look-ahead walks the requested distance along the path`() {
        val path = AStarPlanner.Path(
            points = listOf(
                doubleArrayOf(0.0, 0.0),
                doubleArrayOf(0.0, 10.0),
            ),
            costToGo = 0f, expansions = 0, partial = false,
        )
        val p = PathFollower.lookAheadPoint(path, 0.0, 2.0, 3.0)!!
        assertEquals(0.0, p[0], 1e-9)
        assertEquals(5.0, p[1], 1e-9)
    }

    @Test
    fun `look-ahead past the end aims at the final waypoint`() {
        val path = AStarPlanner.Path(
            points = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 4.0)),
            costToGo = 0f, expansions = 0, partial = false,
        )
        val p = PathFollower.lookAheadPoint(path, 0.0, 3.0, 5.0)!!
        assertEquals(4.0, p[1], 1e-9)
    }

    @Test
    fun `goal angle is signed the way the rest of the app turns`() {
        val path = AStarPlanner.Path(
            points = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(10.0, 0.0)),
            costToGo = 0f, expansions = 0, partial = false,
        )
        // Path runs along +X; facing +Y means it is 90 deg to the RIGHT
        assertEquals(90f, PathFollower.goalAngleDeg(path, pose(0.0, 0.0, 0.0))!!, 1e-3f)
        // Facing +X, it is straight ahead
        assertEquals(0f, PathFollower.goalAngleDeg(path, pose(0.0, 0.0, 90.0))!!, 1e-3f)
        // Facing -Y, it is 90 deg to the LEFT
        assertEquals(-90f, PathFollower.goalAngleDeg(path, pose(0.0, 0.0, 180.0))!!, 1e-3f)
    }

    @Test
    fun `cross-track error measures the distance off the path`() {
        val path = AStarPlanner.Path(
            points = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 10.0)),
            costToGo = 0f, expansions = 0, partial = false,
        )
        assertEquals(2.0, PathFollower.crossTrackErrorM(path, 2.0, 5.0), 1e-9)
        assertEquals(0.0, PathFollower.crossTrackErrorM(path, 0.0, 5.0), 1e-9)
    }

    @Test
    fun `a degenerate path yields no angle rather than a wrong one`() {
        val path = AStarPlanner.Path(
            points = listOf(doubleArrayOf(1.0, 1.0)),
            costToGo = 0f, expansions = 0, partial = false,
        )
        assertNull(PathFollower.goalAngleDeg(path, pose(0.0, 0.0)))
    }

    // ---- budget ----------------------------------------------------------

    @Test
    fun `a replan stays well inside its search budget`() {
        val m = AreaMap()
        // A maze-ish world to make the search work for it
        for (k in 0..8) {
            val y = k * 2.0
            if (k % 2 == 0) m.wall(-15.0, y, 8.0, y) else m.wall(-8.0, y, 15.0, y)
        }
        val cm = costMapAround(m, 0.0, 0.0)
        val path = planner.plan(cm, 0.0, -1.0, 0.0, 17.0)
        assertNotNull(path)
        assertTrue(
            "expansions ${path!!.expansions} exceeded the budget",
            path.expansions < AStarPlanner.MAX_EXPANSIONS,
        )
    }
}
