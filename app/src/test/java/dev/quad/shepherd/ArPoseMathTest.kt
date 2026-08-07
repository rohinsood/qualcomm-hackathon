package dev.quad.shepherd

import dev.quad.shepherd.map.ScanBuilder
import dev.quad.shepherd.pose.ArPoseMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * ARCore's frame conventions against ours. Nothing here can be verified by
 * running the app and squinting — a mirrored bearing or an inverted roll
 * produces a map that looks entirely plausible and is wrong.
 */
class ArPoseMathTest {

    /**
     * Camera axes in world coordinates for a camera yawed by [yawDeg]
     * (clockwise from the session-start direction), pitched [pitchDeg]
     * down, and rolled [rollDeg] clockwise about its optical axis.
     *
     * Built by composing the rotations explicitly rather than by calling
     * the same helpers under test.
     */
    private fun axes(
        yawDeg: Double = 0.0,
        pitchDeg: Double = 0.0,
        rollDeg: Double = 0.0,
    ): Triple<FloatArray, FloatArray, FloatArray> {
        // Start with the identity camera: looks along -Z, right +X, up +Y
        var x = doubleArrayOf(1.0, 0.0, 0.0)
        var y = doubleArrayOf(0.0, 1.0, 0.0)
        var z = doubleArrayOf(0.0, 0.0, 1.0)

        // INTRINSIC order, yaw then pitch then roll, each about the axes
        // as they stand after the previous step. Applying them the other
        // way round mixes roll into pitch and makes the bearing appear to
        // wander when the hand tilts -- which it does not.

        // 1. Yaw clockwise about world up: a right turn swings forward
        //    toward +X
        val a = Math.toRadians(yawDeg)
        x = yawVec(x, a); y = yawVec(y, a); z = yawVec(z, a)

        // 2. Pitch down about the current right axis: rotate (y, z) so the
        //    optical axis picks up a positive world-Y component
        val p = Math.toRadians(pitchDeg)
        val zp = add(scale(z, cos(p)), scale(y, sin(p)))
        val yp = add(scale(y, cos(p)), scale(z, -sin(p)))
        y = yp; z = zp

        // 3. Roll clockwise about the current optical axis, swinging the
        //    camera's right edge downward
        val r = Math.toRadians(rollDeg)
        val xr = add(scale(x, cos(r)), scale(y, -sin(r)))
        val yr = add(scale(x, sin(r)), scale(y, cos(r)))
        x = xr; y = yr

        return Triple(toF(x), toF(y), toF(z))
    }

    private fun yawVec(v: DoubleArray, a: Double) = doubleArrayOf(
        v[0] * cos(a) - v[2] * sin(a),
        v[1],
        v[0] * sin(a) + v[2] * cos(a),
    )

    private fun add(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])

    private fun scale(a: DoubleArray, s: Double) =
        doubleArrayOf(a[0] * s, a[1] * s, a[2] * s)

    private fun toF(a: DoubleArray) =
        floatArrayOf(a[0].toFloat(), a[1].toFloat(), a[2].toFloat())

    private fun deg(rad: Float) = Math.toDegrees(rad.toDouble())

    // ---- position --------------------------------------------------------

    @Test
    fun `planar position takes X and negated Z`() {
        assertEquals(3.0, ArPoseMath.planarX(3f), 1e-9)
        // Walking along the session-start view direction is world -Z, and
        // must read as POSITIVE y in the map frame
        assertEquals(5.0, ArPoseMath.planarY(-5f), 1e-9)
        assertEquals(-2.0, ArPoseMath.planarY(2f), 1e-9)
    }

    // ---- bearing ---------------------------------------------------------

    @Test
    fun `session start is bearing zero`() {
        val (_, _, z) = axes()
        assertEquals(0.0, deg(ArPoseMath.bearingRad(z)), 1e-6)
    }

    @Test
    fun `turning right increases the bearing`() {
        for (yaw in listOf(0.0, 30.0, 90.0, 135.0, -45.0, -90.0, 179.0)) {
            val (_, _, z) = axes(yawDeg = yaw)
            assertEquals("yaw $yaw", yaw, deg(ArPoseMath.bearingRad(z)), 1e-4)
        }
    }

    @Test
    fun `a right angle turn faces world plus X`() {
        val (_, _, z) = axes(yawDeg = 90.0)
        // Facing +X means forward = (1,0,0), so zAxis = (-1,0,0)
        assertEquals(-1.0, z[0].toDouble(), 1e-6)
        assertEquals(90.0, deg(ArPoseMath.bearingRad(z)), 1e-4)
    }

    @Test
    fun `bearing survives pitch and roll`() {
        // Looking 25 deg down with the phone rolled: the compass bearing
        // must not wander, or the map rotates every time the hand moves.
        val (_, _, z) = axes(yawDeg = 60.0, pitchDeg = 25.0, rollDeg = 20.0)
        assertEquals(60.0, deg(ArPoseMath.bearingRad(z)), 0.5)
    }

    // ---- pitch -----------------------------------------------------------

    @Test
    fun `pitch is positive looking down`() {
        assertEquals(0.0, deg(ArPoseMath.pitchRad(axes().third)), 1e-6)
        for (p in listOf(10.0, 30.0, 60.0)) {
            val (_, _, z) = axes(pitchDeg = p)
            assertEquals("pitch $p", p, deg(ArPoseMath.pitchRad(z)), 1e-4)
        }
        // ...and negative looking up
        val (_, _, up) = axes(pitchDeg = -20.0)
        assertTrue(ArPoseMath.pitchRad(up) < 0f)
    }

    @Test
    fun `pitch is independent of which way you are facing`() {
        val flat = ArPoseMath.pitchRad(axes(yawDeg = 0.0, pitchDeg = 30.0).third)
        val turned = ArPoseMath.pitchRad(axes(yawDeg = 145.0, pitchDeg = 30.0).third)
        assertEquals(deg(flat), deg(turned), 1e-4)
    }

    // ---- roll ------------------------------------------------------------

    @Test
    fun `roll is zero when upright`() {
        val (x, y, _) = axes()
        assertEquals(0.0, deg(ArPoseMath.rollRad(x, y)), 1e-6)
    }

    @Test
    fun `roll tracks the phone turning about its optical axis`() {
        for (r in listOf(15.0, 45.0, 90.0, -30.0, -90.0)) {
            val (x, y, _) = axes(rollDeg = r)
            assertEquals("roll $r", r, deg(ArPoseMath.rollRad(x, y)), 1e-4)
        }
    }

    /**
     * The contract that actually matters: feed the reported roll into
     * ScanBuilder's un-roll and gravity must come back upright.
     */
    @Test
    fun `the reported roll is the one ScanBuilder cancels`() {
        for (rollDeg in listOf(0.0, 20.0, 90.0, -35.0)) {
            val (x, y, _) = axes(rollDeg = rollDeg)
            val r = ArPoseMath.rollRad(x, y)

            // A feature straight up in the image, one unit away
            val xi = 0.0
            val yi = 1.0
            // ScanBuilder's un-roll, verbatim
            val yUp = -xi * sin(r.toDouble()) + yi * cos(r.toDouble())

            // Its true world-vertical component is xi*xAxis.y + yi*yAxis.y
            val trueUp = xi * x[1] + yi * y[1]
            assertEquals("roll $rollDeg", trueUp, yUp, 1e-5)
        }
    }

    @Test
    fun `a quarter turn puts image-up out to the side`() {
        // Phone rolled 90 deg clockwise: the camera's right edge points at
        // the floor, so what looks like "up" in the image is really "right"
        val (x, y, _) = axes(rollDeg = 90.0)
        assertEquals(-1.0, x[1].toDouble(), 1e-6) // right axis points down
        val r = ArPoseMath.rollRad(x, y)
        assertEquals(90.0, deg(r), 1e-4)

        val xi = 0.0
        val yi = 1.0
        val xc = xi * cos(r.toDouble()) + yi * sin(r.toDouble())
        val yUp = -xi * sin(r.toDouble()) + yi * cos(r.toDouble())
        assertEquals("image-up should un-roll to pure sideways", 1.0, xc, 1e-5)
        assertEquals(0.0, yUp, 1e-5)
    }

    // ---- intrinsics and jumps -------------------------------------------

    @Test
    fun `focal length scales with the depth image`() {
        // 1440-wide colour frame, 160-wide depth frame, same field of view
        assertEquals(80f, ArPoseMath.scaleFocalLength(720f, 1440, 160), 1e-4f)
        // Degenerate input must not produce a zero focal length
        assertEquals(720f, ArPoseMath.scaleFocalLength(720f, 0, 160), 1e-4f)
    }

    @Test
    fun `relocalisation jumps are measured on the ground plane`() {
        // 3-4-5: height changes are irrelevant to a 2-D map
        assertEquals(5.0, ArPoseMath.planarJumpM(0f, 0f, 3f, 4f), 1e-6)
        assertEquals(0.0, ArPoseMath.planarJumpM(2f, 7f, 2f, 7f), 1e-9)
    }

    @Test
    fun `turn takes the short way round`() {
        val a = Math.toRadians(179.0).toFloat()
        val b = Math.toRadians(-179.0).toFloat()
        assertEquals(2.0, ArPoseMath.turnDeg(a, b).toDouble(), 0.01)
        assertEquals(-2.0, ArPoseMath.turnDeg(b, a).toDouble(), 0.01)
    }

    // ---- end to end ------------------------------------------------------

    @Test
    fun `a wall ahead lands ahead after a full pose round trip`() {
        // Camera yawed 40 deg right, pitched 15 deg down, rolled 25 deg,
        // 1.4 m up, looking at a wall 3 m away. Whatever the hand is doing,
        // the wall must come out on a bearing of 40 deg.
        val (x, y, z) = axes(yawDeg = 40.0, pitchDeg = 15.0, rollDeg = 25.0)
        val bearing = ArPoseMath.bearingRad(z)
        val pitch = ArPoseMath.pitchRad(z)
        val roll = ArPoseMath.rollRad(x, y)

        val w = 160
        val h = 120
        val fx = 100f
        // Fronto-parallel wall: every pixel at the same Z distance
        val depth = FloatArray(w * h) { 3f }
        val scan = ScanBuilder.fromDepth(
            depth, w, h, fx,
            pitchRad = pitch, rollRad = roll, cameraHeightM = 1.4f,
        )
        val centre = scan.bins / 2
        assertEquals(
            "range to the wall should survive the projection",
            3.0, scan.obstacleRangeM[centre].toDouble(), 0.35,
        )
        // Bin 0 is the far left of the field of view, so the centre bin is
        // straight ahead: adding the camera bearing gives the world bearing
        val worldBearing = bearing + scan.bearingOfRad(centre)
        assertEquals(40.0, deg(worldBearing), 0.5)
    }
}
