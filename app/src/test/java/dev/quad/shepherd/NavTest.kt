package dev.quad.shepherd

import dev.quad.shepherd.guidance.GuidanceEngine
import dev.quad.shepherd.guidance.SteerFusion
import dev.quad.shepherd.nav.PolylineDecoder
import dev.quad.shepherd.nav.RouteTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

class NavTest {

    // ---- PolylineDecoder ------------------------------------------------

    @Test
    fun `decodes google's reference polyline`() {
        val pts = PolylineDecoder.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0][0], 1e-5)
        assertEquals(-120.2, pts[0][1], 1e-5)
        assertEquals(43.252, pts[2][0], 1e-5)
        assertEquals(-126.453, pts[2][1], 1e-5)
    }

    // ---- RouteTracker ---------------------------------------------------

    private val lat0 = 40.0
    private val lng0 = -74.0
    private val mPerLat = 110_540.0
    private val mPerLng = 111_320.0 * cos(Math.toRadians(lat0))

    /** 100 m north, then 100 m east. */
    private fun lRoute() = RouteTracker.Route(
        points = listOf(
            doubleArrayOf(lat0, lng0),
            doubleArrayOf(lat0 + 100 / mPerLat, lng0),
            doubleArrayOf(lat0 + 100 / mPerLat, lng0 + 100 / mPerLng),
        ),
        steps = listOf(
            RouteTracker.Step(0, "Head north"),
            RouteTracker.Step(1, "Turn right onto East Street"),
        ),
        totalMeters = 200.0,
    )

    @Test
    fun `on the path facing along it steers straight`() {
        val t = RouteTracker(lRoute())
        val u = t.update(lat0 + 20 / mPerLat, lng0, headingDeg = 0f)
        assertNotNull(u.steer)
        assertTrue("steer ${u.steer}", abs(u.steer!!) < 0.1f)
        assertEquals(180.0, u.remainingMeters, 3.0)
    }

    @Test
    fun `drift west of the path steers right, back toward it`() {
        val t = RouteTracker(lRoute())
        val u = t.update(lat0 + 20 / mPerLat, lng0 - 8 / mPerLng, headingDeg = 0f)
        assertTrue("steer ${u.steer}", u.steer!! > 0.15f)
    }

    @Test
    fun `approaching the corner cues the turn once`() {
        val t = RouteTracker(lRoute())
        val nearTurn = t.update(lat0 + 85 / mPerLat, lng0, headingDeg = 0f)
        assertEquals(RouteTracker.Event.TURN_CUE, nearTurn.event)
        assertEquals("Turn right onto East Street", nearTurn.cueText)
        // Next fix: same step must not cue again
        val again = t.update(lat0 + 90 / mPerLat, lng0, headingDeg = 0f)
        assertNull(again.event)
    }

    @Test
    fun `reaching the end reports arrival`() {
        val t = RouteTracker(lRoute())
        t.update(lat0 + 95 / mPerLat, lng0, headingDeg = 0f)
        val u = t.update(lat0 + 100 / mPerLat, lng0 + 92 / mPerLng, headingDeg = 90f)
        assertEquals(RouteTracker.Event.ARRIVED, u.event)
    }

    @Test
    fun `persistent distance from the path asks for a reroute`() {
        val t = RouteTracker(lRoute())
        var event: RouteTracker.Event? = null
        repeat(4) {
            event = t.update(lat0 + 40 / mPerLat, lng0 - 50 / mPerLng, headingDeg = 0f).event
        }
        assertEquals(RouteTracker.Event.OFF_ROUTE, event)
    }

    @Test
    fun `no heading means no steer but progress still tracks`() {
        val t = RouteTracker(lRoute())
        val u = t.update(lat0 + 20 / mPerLat, lng0, headingDeg = null)
        assertNull(u.steer)
        assertEquals(180.0, u.remainingMeters, 3.0)
    }

    // ---- SteerFusion ----------------------------------------------------

    private fun g(sev: GuidanceEngine.Severity, steer: Float, dist: Float?) =
        GuidanceEngine.Guidance(sev, steer, dist, dist?.let { "obstacle" }, FloatArray(GuidanceEngine.NUM_COLUMNS))

    @Test
    fun `without a route the obstacle steer passes through`() {
        assertEquals(0.4f, SteerFusion.fuse(g(GuidanceEngine.Severity.CAUTION, 0.4f, 2f), null), 1e-6f)
    }

    @Test
    fun `clear path follows the route`() {
        assertEquals(0.6f, SteerFusion.fuse(g(GuidanceEngine.Severity.CLEAR, 0f, null), 0.6f), 1e-6f)
    }

    @Test
    fun `danger ignores the route entirely`() {
        assertEquals(-0.7f, SteerFusion.fuse(g(GuidanceEngine.Severity.DANGER, -0.7f, 1f), 0.9f), 1e-6f)
    }

    @Test
    fun `a very close obstacle mutes the navigation bias`() {
        val fusedClose = SteerFusion.fuse(g(GuidanceEngine.Severity.CAUTION, -0.3f, 0.8f), 0.8f)
        assertEquals(-0.3f, fusedClose, 1e-6f)
    }

    @Test
    fun `a distant obstacle lets navigation contribute fully`() {
        val fused = SteerFusion.fuse(g(GuidanceEngine.Severity.CAUTION, -0.2f, 3f), 0.5f)
        assertEquals(0.3f, fused, 1e-6f)
    }
}
