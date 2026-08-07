package dev.quad.shepherd

import dev.quad.shepherd.guidance.GuidanceEngine
import dev.quad.shepherd.path.PolarPlanner
import dev.quad.shepherd.path.TraversabilityGrid
import dev.quad.shepherd.path.WalkableColumns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class PathTest {

    // ---- TraversabilityGrid --------------------------------------------
    // Synthetic camera: 100x100 depth image, 90° FOV (fx = 50), camera
    // 1.4 m above ground, pitch 0.

    private val camH = 1.4f
    private val fx = 50f

    private fun freshGrid() = TraversabilityGrid()

    /** A frontal wall filling the view at [d] meters. */
    private fun wallDepth(d: Float) = FloatArray(100 * 100) { d }

    /**
     * A true ground plane: pixels below the horizon see the floor at
     * d = camH * fx / (v - cy); everything else is far/no-signal.
     */
    private fun groundDepth(): FloatArray {
        val out = FloatArray(100 * 100) { Float.NaN }
        for (v in 51 until 100) {
            val d = camH * fx / (v - 50f)
            for (u in 0 until 100) out[v * 100 + u] = d
        }
        return out
    }

    @Test
    fun `a wall at two meters accumulates obstacle cells`() {
        val grid = freshGrid()
        repeat(3) { grid.update(wallDepth(2f), 100, 100, null, 0f, camH, 90f) }
        val iz = (2f / grid.cellMeters).toInt()
        assertTrue(grid.isObstacle(grid.cellsWide / 2, iz))
    }

    @Test
    fun `ground-level readings accumulate free cells`() {
        val grid = freshGrid()
        repeat(3) { grid.update(groundDepth(), 100, 100, null, 0f, camH, 90f) }
        var anyFree = false
        var anyObstacleCenter = false
        for (iz in 15..35) {
            val l = grid.logOdds[iz * grid.cellsWide + grid.cellsWide / 2]
            if (l < -0.3f) anyFree = true
            if (l > TraversabilityGrid.OBSTACLE_THRESHOLD) anyObstacleCenter = true
        }
        assertTrue(anyFree)
        assertFalse(anyObstacleCenter)
    }

    @Test
    fun `obstacle evidence decays when no longer observed`() {
        val grid = freshGrid()
        repeat(3) { grid.update(wallDepth(2f), 100, 100, null, 0f, camH, 90f) }
        val iz = (2f / grid.cellMeters).toInt()
        assertTrue(grid.isObstacle(grid.cellsWide / 2, iz))
        val empty = FloatArray(100 * 100) { Float.NaN }
        repeat(60) { grid.update(empty, 100, 100, null, 0f, camH, 90f) }
        assertFalse(grid.isObstacle(grid.cellsWide / 2, iz))
    }

    @Test
    fun `segmentation veto marks flat ground as soft obstacle`() {
        val grid = freshGrid()
        val notWalkable = ByteArray(100 * 100) // all zeros = "not walkable"
        repeat(4) { grid.update(groundDepth(), 100, 100, notWalkable, 0f, camH, 90f) }
        var anyPositive = false
        for (iz in 15..35) {
            if (grid.logOdds[iz * grid.cellsWide + grid.cellsWide / 2] > 0.3f) anyPositive = true
        }
        assertTrue(anyPositive)
    }

    // ---- PolarPlanner ---------------------------------------------------

    /** Paint an obstacle wall segment directly into the grid. */
    private fun wall(grid: TraversabilityGrid, zMeters: Float, xFromM: Float, xToM: Float) {
        val iz = (zMeters / grid.cellMeters).toInt()
        val from = grid.cellsWide / 2 + (xFromM / grid.cellMeters).toInt()
        val to = grid.cellsWide / 2 + (xToM / grid.cellMeters).toInt()
        for (ix in from..to) {
            for (dz in -1..1) {
                val z = (iz + dz).coerceIn(0, grid.cellsDeep - 1)
                grid.logOdds[z * grid.cellsWide + ix.coerceIn(0, grid.cellsWide - 1)] = 3f
            }
        }
    }

    /** Surround the walker with obstacles inside [radiusM] — no way out. */
    private fun enclosure(grid: TraversabilityGrid, radiusM: Float) {
        for (iz in 0 until grid.cellsDeep) {
            for (ix in 0 until grid.cellsWide) {
                val x = (ix - grid.cellsWide / 2) * grid.cellMeters
                val z = iz * grid.cellMeters
                val r = sqrt(x * x + z * z)
                if (r in 0.3f..radiusM) {
                    grid.logOdds[iz * grid.cellsWide + ix] = 3f
                }
            }
        }
    }

    @Test
    fun `open grid plans straight and clear`() {
        val planner = PolarPlanner()
        val plan = planner.plan(freshGrid(), null)
        assertEquals(GuidanceEngine.Severity.CLEAR, plan.guidance.severity)
        assertTrue(abs(plan.guidance.steer) < 0.05f)
        assertFalse(plan.stop)
    }

    @Test
    fun `wall ahead with a left opening commits left`() {
        val grid = freshGrid()
        // Wall at 1.5 m covering center and right; the left stays open
        wall(grid, 1.5f, -0.6f, 2.9f)
        val planner = PolarPlanner()
        var steer = 0f
        repeat(10) { steer = planner.plan(grid, null).guidance.steer }
        assertTrue("steer $steer", steer < -0.15f)
    }

    @Test
    fun `enclosed on all sides stops`() {
        val grid = freshGrid()
        enclosure(grid, 1.4f)
        val planner = PolarPlanner()
        val plan = planner.plan(grid, null)
        assertTrue(plan.stop)
        assertEquals(GuidanceEngine.Severity.DANGER, plan.guidance.severity)
    }

    @Test
    fun `decision does not flip across near-identical frames`() {
        val grid = freshGrid()
        wall(grid, 1.5f, -0.6f, 2.9f)
        val planner = PolarPlanner()
        repeat(6) { planner.plan(grid, null) }
        val committed = planner.plan(grid, null).guidance.steer
        // Wobble the wall by one cell and re-plan: no sign flip, no jump
        wall(grid, 1.6f, -0.5f, 2.9f)
        val after = planner.plan(grid, null).guidance.steer
        assertTrue(
            "before=$committed after=$after",
            committed < 0f && after < 0f && abs(after - committed) < 0.3f,
        )
    }

    @Test
    fun `route goal biases valley choice`() {
        val grid = freshGrid()
        // Obstacle blob dead ahead at 1.5 m; both sides open
        wall(grid, 1.5f, -0.5f, 0.5f)
        val planner = PolarPlanner()
        var steer = 0f
        repeat(10) { steer = planner.plan(grid, 45f).guidance.steer }
        assertTrue("steer $steer", steer > 0.2f)
    }

    @Test
    fun `clear plan carries no obstacle label`() {
        val planner = PolarPlanner()
        val plan = planner.plan(freshGrid(), null)
        assertNull(plan.guidance.nearestLabel)
        assertNull(plan.guidance.nearestDistanceMeters)
    }

    // ---- Walkable-fraction columns (Wayfinder signal) -------------------

    @Test
    fun `walkable columns measure per-column fractions`() {
        // 32x16 mask: left half sidewalk (1), right half building (2)
        val mask = ByteArray(32 * 16) { i -> if (i % 32 < 16) 1 else 2 }
        val walkable = BooleanArray(19).also { it[1] = true }
        val cols = WalkableColumns.clearance(mask, 32, 16, walkable)
        assertEquals(WalkableColumns.NUM_COLUMNS, cols.size)
        assertTrue(cols[0] > 0.95f)
        assertTrue(cols[WalkableColumns.NUM_COLUMNS - 1] < 0.05f)
    }

    @Test
    fun `seg columns override a projection-artifact stop with cautious steer`() {
        val grid = freshGrid()
        enclosure(grid, 1.4f) // geometry says: nowhere to go
        val planner = PolarPlanner()
        // Segmentation clearly sees an opening on the right side
        val seg = FloatArray(WalkableColumns.NUM_COLUMNS) { c -> if (c >= 12) 0.9f else 0.1f }
        val plan = planner.plan(grid, null, seg, 70f)
        assertFalse(plan.stop)
        assertEquals(GuidanceEngine.Severity.CAUTION, plan.guidance.severity)
        assertTrue("steer ${plan.guidance.steer}", plan.guidance.steer > 0.05f)
        // Without the seg signal the same grid must still STOP
        val planNoSeg = PolarPlanner().plan(grid, null)
        assertTrue(planNoSeg.stop)
    }
}
