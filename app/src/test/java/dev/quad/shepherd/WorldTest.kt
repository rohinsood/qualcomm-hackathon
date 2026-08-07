package dev.quad.shepherd

import dev.quad.shepherd.world.Angles
import dev.quad.shepherd.world.LocalFrame
import dev.quad.shepherd.world.WorldAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The frame conventions the whole areamap rests on. Every one of these is
 * a sign error waiting to happen, and a sign error here does not crash —
 * it quietly builds a mirrored map and walks the user into traffic.
 */
class WorldTest {

    private val eps = 1e-6

    // ---- LocalFrame -----------------------------------------------------

    @Test
    fun `local frame round-trips lat lng`() {
        val f = LocalFrame(37.4220, -122.0841) // Mountain View
        val (e, n) = f.toEastNorth(37.4231, -122.0825).let { it[0] to it[1] }
        val back = f.toLatLng(e, n)
        assertEquals(37.4231, back[0], 1e-9)
        assertEquals(-122.0841 + 0.0016, back[1], 1e-9)
    }

    @Test
    fun `north is positive for increasing latitude`() {
        val f = LocalFrame(37.0, -122.0)
        assertTrue(f.northOf(37.001) > 0)
        assertTrue(f.eastOf(-121.999) > 0)
        // 0.001 deg latitude is ~110.54 m
        assertEquals(110.54, f.northOf(37.001), 1e-6)
    }

    @Test
    fun `longitude metres shrink with latitude`() {
        val equator = LocalFrame(0.0, 0.0)
        val high = LocalFrame(60.0, 0.0)
        // cos(60 deg) = 0.5, so a degree of longitude is half as wide
        assertEquals(0.5, high.eastOf(1.0) / equator.eastOf(1.0), 1e-6)
    }

    // ---- Angles ---------------------------------------------------------

    @Test
    fun `bearing is clockwise from forward`() {
        assertEquals(0f, Angles.bearingDeg(0.0, 1.0), 1e-4f)    // straight ahead
        assertEquals(90f, Angles.bearingDeg(1.0, 0.0), 1e-4f)   // to the right
        assertEquals(-90f, Angles.bearingDeg(-1.0, 0.0), 1e-4f) // to the left
        assertEquals(180f, abs(Angles.bearingDeg(0.0, -1.0)), 1e-4f)
    }

    @Test
    fun `wrap keeps angles in the half-open turn`() {
        assertEquals(-179f, Angles.wrapDeg(181f), 1e-4f)
        assertEquals(180f, Angles.wrapDeg(180f), 1e-4f)
        assertEquals(1f, Angles.wrapDeg(361f), 1e-4f)
        assertEquals(-90f, Angles.deltaDeg(10f, 280f), 1e-4f)
    }

    // ---- WorldAnchor transforms ----------------------------------------

    @Test
    fun `identity anchor maps AR axes onto ENU axes`() {
        val a = WorldAnchor()
        // +Y (session-start forward) is north, +X (right) is east
        assertEquals(1.0, a.toNorth(0.0, 1.0), eps)
        assertEquals(0.0, a.toEast(0.0, 1.0), eps)
        assertEquals(1.0, a.toEast(1.0, 0.0), eps)
        assertEquals(0.0, a.toNorth(1.0, 0.0), eps)
    }

    @Test
    fun `theta is the true-north bearing of the AR forward axis`() {
        // AR +Y points due EAST
        val a = WorldAnchor(thetaRad = Math.toRadians(90.0).toFloat())
        assertEquals(1.0, a.toEast(0.0, 1.0), 1e-6)
        assertEquals(0.0, a.toNorth(0.0, 1.0), 1e-6)
        // then AR +X (its right) points due SOUTH
        assertEquals(0.0, a.toEast(1.0, 0.0), 1e-6)
        assertEquals(-1.0, a.toNorth(1.0, 0.0), 1e-6)
    }

    @Test
    fun `bearings convert by adding theta`() {
        val a = WorldAnchor(thetaRad = Math.toRadians(90.0).toFloat())
        // Facing along AR +Y, which points east -> true bearing 90
        assertEquals(90.0, Math.toDegrees(a.bearingToEnu(0f).toDouble()), 1e-4)
        // A true bearing of due north is a -90 turn within the AR frame
        assertEquals(-90.0, Math.toDegrees(a.bearingToAr(0f).toDouble()), 1e-4)
    }

    @Test
    fun `AR and ENU transforms are inverses`() {
        val a = WorldAnchor(thetaRad = 0.9f, tEast = 123.0, tNorth = -45.0)
        for (p in listOf(0.0 to 0.0, 3.0 to -7.5, -19.0 to 42.0)) {
            val en = a.toEastNorth(p.first, p.second)
            val back = a.toAr(en[0], en[1])
            assertEquals(p.first, back[0], 1e-9)
            assertEquals(p.second, back[1], 1e-9)
        }
    }

    @Test
    fun `compass seed makes the AR bearing read as the true heading`() {
        val a = WorldAnchor()
        val arBearing = Math.toRadians(30.0).toFloat()
        val trueHeading = Math.toRadians(200.0).toFloat()
        a.seedFromCompass(arBearing, trueHeading)
        // Compared as a wrapped delta: bearings live on a circle, and 200
        // deg comes back as -160 deg, which is the same direction.
        val enu = Math.toDegrees(a.bearingToEnu(arBearing).toDouble()).toFloat()
        assertEquals(0f, Angles.deltaDeg(200f, enu), 1e-3f)
    }

    // ---- WorldAnchor trajectory fit -------------------------------------

    /** Walk an L: 20 m along AR +Y, then 20 m along AR +X. */
    private fun lShape(): List<Pair<Double, Double>> =
        (0..20).map { 0.0 to it.toDouble() } + (1..20).map { it.toDouble() to 20.0 }

    private fun project(
        pts: List<Pair<Double, Double>>,
        thetaDeg: Double,
        tE: Double,
        tN: Double,
    ): List<DoubleArray> {
        val t = Math.toRadians(thetaDeg)
        return pts.map {
            doubleArrayOf(
                it.first * cos(t) + it.second * sin(t) + tE,
                -it.first * sin(t) + it.second * cos(t) + tN,
            )
        }
    }

    @Test
    fun `fit recovers a known rotation and translation`() {
        val pts = lShape()
        val enu = project(pts, 30.0, 100.0, 200.0)
        val samples = pts.indices.map {
            WorldAnchor.Companion.Sample(
                pts[it].first, pts[it].second, enu[it][0], enu[it][1], 1.0,
            )
        }
        val fit = WorldAnchor.solve(samples)
        assertNotNull(fit)
        fit!!
        assertEquals(30.0, Math.toDegrees(fit.thetaRad.toDouble()), 1e-4)
        assertEquals(100.0, fit.tEast, 1e-6)
        assertEquals(200.0, fit.tNorth, 1e-6)
        assertEquals(0.0, fit.rmsM, 1e-6)
    }

    @Test
    fun `fit survives GPS noise and reports it as residual`() {
        val pts = lShape()
        val enu = project(pts, -75.0, -20.0, 8.0)
        val rng = java.util.Random(7)
        val samples = pts.indices.map {
            WorldAnchor.Companion.Sample(
                pts[it].first, pts[it].second,
                enu[it][0] + rng.nextGaussian() * 3.0,
                enu[it][1] + rng.nextGaussian() * 3.0,
                1.0,
            )
        }
        val fit = WorldAnchor.solve(samples)!!
        // 3 m sigma on 40 m of walking still pins the bearing within a few degrees
        assertEquals(-75.0, Math.toDegrees(fit.thetaRad.toDouble()), 5.0)
        assertTrue("residual should surface the noise", fit.rmsM > 1.0)
        assertTrue("but the fit should still be sane", fit.rmsM < 8.0)
    }

    @Test
    fun `standing still leaves the bearing unobservable`() {
        // Every sample within a couple of metres: theta cannot be solved,
        // and returning a confident wrong answer here is the failure mode
        // that rotates the whole map.
        val rng = java.util.Random(3)
        val samples = (0 until 60).map {
            val x = rng.nextGaussian() * 0.8
            val y = rng.nextGaussian() * 0.8
            WorldAnchor.Companion.Sample(x, y, x + 50.0, y - 10.0, 1.0)
        }
        assertNull(WorldAnchor.solve(samples))
    }

    @Test
    fun `too few samples is not a fit`() {
        val pts = lShape().take(5)
        val enu = project(pts, 10.0, 0.0, 0.0)
        val samples = pts.indices.map {
            WorldAnchor.Companion.Sample(
                pts[it].first, pts[it].second, enu[it][0], enu[it][1], 1.0,
            )
        }
        assertNull(WorldAnchor.solve(samples))
    }

    @Test
    fun `bad accuracy fixes are refused outright`() {
        val a = WorldAnchor()
        a.addSample(0.0, 0.0, 0.0, 0.0, 50f)          // worse than the cap
        a.addSample(0.0, 1.0, 0.0, 1.0, Float.NaN)    // no accuracy at all
        a.addSample(0.0, 2.0, 0.0, 2.0, -1f)
        assertEquals(0, a.sampleCount)
        a.addSample(0.0, 3.0, 0.0, 3.0, 5f)
        assertEquals(1, a.sampleCount)
    }

    @Test
    fun `accurate fixes outweigh vague ones`() {
        val pts = lShape()
        val truth = project(pts, 45.0, 0.0, 0.0)
        val samples = pts.indices.map {
            // Every other fix is badly wrong AND reported as vague
            val bad = it % 2 == 1
            WorldAnchor.Companion.Sample(
                pts[it].first, pts[it].second,
                truth[it][0] + if (bad) 30.0 else 0.0,
                truth[it][1] + if (bad) -30.0 else 0.0,
                if (bad) 1.0 / (20.0 * 20.0) else 1.0 / (3.0 * 3.0),
            )
        }
        val fit = WorldAnchor.solve(samples)!!
        assertEquals(45.0, Math.toDegrees(fit.thetaRad.toDouble()), 6.0)
    }

    @Test
    fun `refit only succeeds once enough ground is covered`() {
        val a = WorldAnchor()
        val pts = lShape()
        val enu = project(pts, 20.0, 5.0, 5.0)
        // First few metres: not enough spread
        for (i in 0 until 8) a.addSample(pts[i].first, pts[i].second, enu[i][0], enu[i][1], 4f)
        assertNull(a.refit())
        assertFalse(a.fitted)
        // Walk the rest of the L
        for (i in 8 until pts.size) {
            a.addSample(pts[i].first, pts[i].second, enu[i][0], enu[i][1], 4f)
        }
        val fit = a.refit()
        assertNotNull(fit)
        assertTrue(a.fitted)
        assertEquals(20.0, Math.toDegrees(fit!!.thetaRad.toDouble()), 1e-3)
    }

    @Test
    fun `compass seeding stops once a trajectory fit exists`() {
        val a = WorldAnchor()
        val pts = lShape()
        val enu = project(pts, 20.0, 5.0, 5.0)
        for (i in pts.indices) a.addSample(pts[i].first, pts[i].second, enu[i][0], enu[i][1], 4f)
        a.refit()
        val before = a.thetaRad
        a.seedFromCompass(0f, Math.toRadians(123.0).toFloat()) // magnetically confused
        assertEquals(before, a.thetaRad, 1e-9f)
    }

    // ---- slew limiting ---------------------------------------------------

    @Test
    fun `a correction is applied gradually, not teleported`() {
        val a = WorldAnchor(thetaRad = 0f, tEast = 0.0, tNorth = 0.0)
        val target = WorldAnchor.Companion.Fit(
            thetaRad = Math.toRadians(40.0).toFloat(),
            tEast = 20.0, tNorth = 0.0, rmsM = 0.0, spreadM = 30.0, samples = 40,
        )
        // One 100 ms tick moves a few thousandths of a radian, not 40 degrees
        val done = a.slewTowards(target, 0.1f)
        assertFalse(done)
        assertEquals(WorldAnchor.MAX_THETA_RATE_RAD_S * 0.1f, a.thetaRad, 1e-6f)
        assertEquals(WorldAnchor.MAX_TRANSLATION_RATE_M_S * 0.1, a.tEast, 1e-9)

        // ...and converges if given enough time
        repeat(2000) { a.slewTowards(target, 0.1f) }
        assertEquals(target.thetaRad.toDouble(), a.thetaRad.toDouble(), 1e-4)
        assertEquals(target.tEast, a.tEast, 1e-6)
    }

    @Test
    fun `slew takes the short way round the circle`() {
        val a = WorldAnchor(thetaRad = Math.toRadians(179.0).toFloat())
        val target = WorldAnchor.Companion.Fit(
            thetaRad = Math.toRadians(-179.0).toFloat(),
            tEast = 0.0, tNorth = 0.0, rmsM = 0.0, spreadM = 30.0, samples = 40,
        )
        a.slewTowards(target, 1f)
        // Only 2 degrees apart across the wrap. Going the short way lands on
        // the target in one tick; going the long way would head for +177.
        val deg = Math.toDegrees(a.thetaRad.toDouble()).toFloat()
        assertEquals("went the long way round: $deg", 0f, Angles.deltaDeg(-179f, deg), 0.05f)
    }

    @Test
    fun `reset clears the fit for a new AR epoch`() {
        val a = WorldAnchor()
        val pts = lShape()
        val enu = project(pts, 20.0, 5.0, 5.0)
        for (i in pts.indices) a.addSample(pts[i].first, pts[i].second, enu[i][0], enu[i][1], 4f)
        a.refit()
        assertTrue(a.fitted)
        a.reset()
        assertFalse(a.fitted)
        assertEquals(0, a.sampleCount)
        assertNull(a.lastFit)
    }
}
