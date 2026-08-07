package dev.quad.shepherd

import dev.quad.shepherd.map.AreaMap
import dev.quad.shepherd.map.Scan
import dev.quad.shepherd.map.ScanBuilder
import dev.quad.shepherd.world.Pose2d
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AreaMapTest {

    private fun pose(
        x: Double = 0.0,
        y: Double = 0.0,
        bearingDeg: Double = 0.0,
    ) = Pose2d(
        x = x, y = y,
        bearingRad = Math.toRadians(bearingDeg).toFloat(),
        heightM = 1.4f, timestampNs = 0L, confidence = 1f, epoch = 0,
    )

    /**
     * Is anything blocked within a cell of this world point?
     *
     * Cell membership at an exact boundary is decided by the last bit of
     * a float bearing — `toRadians(90).toFloat()` has a cosine of -4.4e-8,
     * which puts a "straight along +X" endpoint 87 nanometres below y=0
     * and therefore in the cell below. That is arithmetic, not behaviour:
     * real depth returns never land on a boundary, and a planner does not
     * care which side of one a wall sits. So assert the metre, not the
     * cell index.
     */
    private fun AreaMap.blockedNear(x: Double, y: Double, tolCells: Int = 1): Boolean {
        val cx = cellOf(x)
        val cy = cellOf(y)
        for (dy in -tolCells..tolCells) {
            for (dx in -tolCells..tolCells) {
                if (isBlocked(cx + dx, cy + dy)) return true
            }
        }
        return false
    }

    /** Enough looks at the same thing to cross the belief threshold. */
    private fun AreaMap.observeRay(
        p: Pose2d,
        bearingDeg: Double,
        rangeM: Float,
        times: Int = 3,
    ) {
        repeat(times) {
            integrateRay(p.x, p.y, Math.toRadians(bearingDeg).toFloat(), rangeM, rangeM)
        }
    }

    // ---- ray integration -------------------------------------------------

    @Test
    fun `a ray marks its endpoint and clears the path to it`() {
        val m = AreaMap()
        m.observeRay(pose(), bearingDeg = 0.0, rangeM = 3f)

        assertTrue("endpoint should be occupied", m.isBlocked(m.cellOf(0.0), m.cellOf(3.0)))
        // Everything the beam passed through is now known free
        for (d in listOf(0.5, 1.0, 2.0, 2.5)) {
            assertTrue("should be clear at $d m", m.isKnownFree(m.cellOf(0.0), m.cellOf(d)))
            assertFalse(m.isBlocked(m.cellOf(0.0), m.cellOf(d)))
        }
    }

    @Test
    fun `clearing along the beam erases a ghost that moved away`() {
        val m = AreaMap()
        val p = pose()
        // Someone stands 2 m ahead, is seen a few times, then leaves
        m.observeRay(p, 0.0, 2f, times = 4)
        assertTrue(m.isBlocked(m.cellOf(0.0), m.cellOf(2.0)))

        // Now the camera sees straight through to 6 m
        repeat(8) { m.observeRay(p, 0.0, 6f, times = 1) }
        assertFalse(
            "a point-stamping map would keep this ghost forever",
            m.isBlocked(m.cellOf(0.0), m.cellOf(2.0)),
        )
        assertTrue(m.isBlocked(m.cellOf(0.0), m.cellOf(6.0)))
    }

    @Test
    fun `an empty beam still reports free space`() {
        val m = AreaMap()
        repeat(3) { m.integrateRay(0.0, 0.0, 0f, Float.NaN, freeToM = 5f) }
        assertTrue(m.isKnownFree(m.cellOf(0.0), m.cellOf(4.0)))
        assertFalse(m.isBlocked(m.cellOf(0.0), m.cellOf(5.0)))
    }

    @Test
    fun `beams work in every quadrant`() {
        val m = AreaMap()
        // Negative world coordinates exercise floorDiv/floorMod tiling
        for (deg in listOf(0.0, 90.0, 180.0, -90.0, 135.0, -135.0)) {
            m.observeRay(pose(), deg, 2f)
            val rad = Math.toRadians(deg)
            assertTrue(
                "missed the endpoint at $deg deg",
                m.blockedNear(Math.sin(rad) * 2.0, Math.cos(rad) * 2.0),
            )
        }
    }

    // ---- memory ----------------------------------------------------------

    @Test
    fun `obstacles are remembered after the user walks on`() {
        val m = AreaMap()
        m.observeRay(pose(), 0.0, 3f, times = 4)
        val ox = m.cellOf(0.0)
        val oy = m.cellOf(3.0)
        assertTrue(m.isBlocked(ox, oy))

        // Ten seconds later, having walked 12 m away and turned around.
        // The ego grid this replaces decays to nothing in about three.
        m.advanceTo(10.0)
        assertTrue(
            "the whole point of the areamap is that this survives",
            m.isBlocked(ox, oy),
        )
    }

    @Test
    fun `dynamic evidence fades fast while static persists`() {
        val m = AreaMap()
        m.observeRay(pose(), 0.0, 3f, times = 4)
        val ox = m.cellOf(0.0)
        val oy = m.cellOf(3.0)

        m.advanceTo(8.0)
        val dyn = m.logOddsAt(ox, oy, AreaMap.Companion.Channel.DYNAMIC)
        val stat = m.logOddsAt(ox, oy, AreaMap.Companion.Channel.STATIC)
        assertTrue("dynamic should have faded: $dyn", abs(dyn) < 0.05f)
        assertTrue("static should remain: $stat", stat > AreaMap.OCCUPIED_THRESHOLD)
    }

    @Test
    fun `standing somewhere is strong free-space evidence and leaves a trail`() {
        val m = AreaMap()
        m.markStood(5.0, -3.0)
        val cx = m.cellOf(5.0)
        val cy = m.cellOf(-3.0)
        assertTrue(m.hasVisited(cx, cy))
        assertTrue(m.isKnownFree(cx, cy))
        assertFalse(m.isBlocked(cx, cy))
        assertTrue(m.visitedCells > 0)
        // Somewhere the user never went
        assertFalse(m.hasVisited(m.cellOf(50.0), m.cellOf(50.0)))
    }

    @Test
    fun `an epoch change drops the map`() {
        val m = AreaMap()
        m.observeRay(pose(), 0.0, 3f, times = 4)
        m.markStood(0.0, 0.0)
        assertTrue(m.tileCount > 0)

        m.resetForEpoch(1)
        assertEquals(1, m.epoch)
        assertEquals(0, m.tileCount)
        assertEquals(0, m.visitedCells)
        assertFalse(m.isBlocked(m.cellOf(0.0), m.cellOf(3.0)))
    }

    // ---- sparsity --------------------------------------------------------

    @Test
    fun `memory tracks explored area, not bounding box`() {
        val m = AreaMap()
        // Two observations 200 m apart must not allocate the 200 m between
        m.observeRay(pose(0.0, 0.0), 0.0, 2f, times = 1)
        m.observeRay(pose(200.0, 200.0), 0.0, 2f, times = 1)
        assertTrue("sparse map ballooned to ${m.tileCount} tiles", m.tileCount < 20)
    }

    // ---- ego view --------------------------------------------------------

    private fun FloatArray.egoBlockedNear(wide: Int, ix: Int, iz: Int, tol: Int = 2): Boolean {
        for (dz in -tol..tol) {
            for (dx in -tol..tol) {
                val x = ix + dx
                val z = iz + dz
                val i = z * wide + x
                if (x in 0 until wide && z >= 0 && i in indices &&
                    this[i] > AreaMap.OCCUPIED_THRESHOLD
                ) return true
            }
        }
        return false
    }

    @Test
    fun `the ego view rotates with the user`() {
        val m = AreaMap()
        // A wall spanning world x in [-2, 2] at y ~ 3, three cells thick
        var x = -2.0
        while (x <= 2.0) {
            for (yc in 0..2) {
                repeat(4) { m.markOccupied(m.cellOf(x), m.cellOf(2.95 + yc * 0.1)) }
            }
            x += 0.05
        }
        val wide = 61
        val deep = 60
        val cell = 0.1f
        val half = wide / 2

        // Facing +Y: the wall is dead ahead at 3 m -> row ~30, centre column
        val ahead = m.egoView(pose(bearingDeg = 0.0), wide, deep, cell)
        assertTrue("wall should be straight ahead", ahead.egoBlockedNear(wide, half, 30))

        // Facing +X: the same wall is now 3 m to the LEFT, at zero range
        val turned = m.egoView(pose(bearingDeg = 90.0), wide, deep, cell)
        assertTrue("wall should be off to the left", turned.egoBlockedNear(wide, half - 30, 0))
        assertFalse(
            "wall must not still read as straight ahead after a 90 deg turn",
            turned.egoBlockedNear(wide, half, 30),
        )
    }

    // ---- windowing -------------------------------------------------------

    @Test
    fun `a window reads back the world coordinates it was cut from`() {
        val m = AreaMap()
        repeat(4) { m.markOccupied(m.cellOf(1.25), m.cellOf(-2.75)) }
        val w = m.snapshotWindow(0.0, 0.0, 5f)
        var found = false
        for (iy in 0 until w.height) {
            for (ix in 0 until w.width) {
                if (w.blocked(ix, iy)) {
                    assertEquals(1.25, w.worldX(ix), 0.06)
                    assertEquals(-2.75, w.worldY(iy), 0.06)
                    found = true
                }
            }
        }
        assertTrue("window lost the obstacle", found)
    }

    // ---- ScanBuilder -----------------------------------------------------

    private val fx = 200f
    private val dw = 320
    private val dh = 240

    @Test
    fun `a fronto-parallel wall becomes one range across the scan`() {
        val depth = FloatArray(dw * dh) { 3f }
        val scan = ScanBuilder.fromDepth(
            depth, dw, dh, fx,
            pitchRad = 0f, rollRad = 0f, cameraHeightM = 1.4f,
        )
        val centre = scan.bins / 2
        assertEquals(3f, scan.obstacleRangeM[centre], 0.05f)
        assertTrue(scan.populated() > scan.bins / 2)
    }

    @Test
    fun `flat ground reads as free space, not obstacles`() {
        // Depth of the floor for each row below the horizon, camera 1.4 m up
        val depth = FloatArray(dw * dh) { Float.NaN }
        val cy = dh / 2
        for (v in (cy + 1) until dh) {
            val d = 1.4f * fx / (v - cy)
            if (d > 8f) continue
            for (u in 0 until dw) depth[v * dw + u] = d
        }
        val scan = ScanBuilder.fromDepth(
            depth, dw, dh, fx,
            pitchRad = 0f, rollRad = 0f, cameraHeightM = 1.4f,
        )
        val centre = scan.bins / 2
        assertFalse(
            "the floor is not an obstacle",
            scan.obstacleRangeM[centre].isFinite(),
        )
        assertTrue("floor should clear space ahead", scan.freeRangeM[centre] > 2f)
    }

    @Test
    fun `free space never extends past the obstacle that blocks it`() {
        val scan = Scan(
            bins = 3,
            fovRad = 1f,
            obstacleRangeM = floatArrayOf(2f, Float.NaN, 4f),
            freeRangeM = floatArrayOf(5f, 5f, 1f),
        )
        // Built by hand here; the builder enforces the same invariant
        val built = ScanBuilder.fromDepth(
            FloatArray(dw * dh) { 3f }, dw, dh, fx,
            pitchRad = 0f, rollRad = 0f, cameraHeightM = 1.4f,
        )
        for (b in 0 until built.bins) {
            val o = built.obstacleRangeM[b]
            if (o.isFinite()) {
                assertTrue(
                    "free ${built.freeRangeM[b]} exceeded obstacle $o in bin $b",
                    built.freeRangeM[b] <= o + 1e-4f,
                )
            }
        }
        assertEquals(3, scan.bins)
    }

    @Test
    fun `bearings outside the field of view have no bin`() {
        val fov = 1.2f
        assertEquals(-1, ScanBuilder.binOf(1.0f, fov, 121))
        assertEquals(-1, ScanBuilder.binOf(-1.0f, fov, 121))
        assertEquals(60, ScanBuilder.binOf(0f, fov, 121))
        assertEquals(0, ScanBuilder.binOf(-fov / 2, fov, 121))
        assertEquals(120, ScanBuilder.binOf(fov / 2, fov, 121))
    }

    @Test
    fun `a scan integrates into the map at the right world bearing`() {
        val m = AreaMap()
        // Single bin dead ahead, obstacle at 4 m
        val scan = Scan(
            bins = 1, fovRad = 0f,
            obstacleRangeM = floatArrayOf(4f),
            freeRangeM = floatArrayOf(4f),
        )
        val p = pose(bearingDeg = 90.0) // facing world +X
        repeat(4) { m.integrateScan(p, scan) }
        assertTrue("obstacle should land 4 m along +X", m.blockedNear(4.0, 0.0))
        assertFalse("and nothing 4 m along +Y", m.blockedNear(0.0, 4.0))
    }

    @Test
    fun `the cane ray is stamped ahead of the user`() {
        val m = AreaMap()
        val p = pose(x = 10.0, y = 10.0, bearingDeg = 180.0) // facing -Y
        repeat(3) { m.integrateCaneRay(p, 0.8f) }
        assertTrue(m.blockedNear(10.0, 9.2))
    }
}
