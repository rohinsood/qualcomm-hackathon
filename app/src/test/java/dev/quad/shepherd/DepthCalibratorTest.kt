package dev.quad.shepherd

import dev.quad.shepherd.guidance.DepthCalibrator
import dev.quad.shepherd.guidance.DistanceEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthCalibratorTest {

    @Test
    fun `fits the linear disparity model and inverts it`() {
        val cal = DepthCalibrator()
        // Ground truth mapping: disparity = 3/z + 0.5
        for (z in listOf(0.8f, 1.5f, 2f, 3f, 5f, 8f)) {
            cal.addSample(3f / z + 0.5f, z)
        }
        assertTrue(cal.isCalibrated)
        val z = cal.toMeters(3f / 2.5f + 0.5f)
        assertNotNull(z)
        assertEquals(2.5f, z!!, 0.3f)
    }

    @Test
    fun `calibrated far disparity reads as null`() {
        val cal = DepthCalibrator()
        for (z in listOf(0.8f, 1.5f, 2f, 3f, 5f, 8f)) {
            cal.addSample(3f / z + 0.5f, z)
        }
        // disparity at the shift value b corresponds to infinity
        assertNull(cal.toMeters(0.5f))
    }

    @Test
    fun `uncalibrated relative mode flags looming columns only`() {
        val cal = DepthCalibrator()
        assertNull(cal.convert(1.0f, 1.0f))          // at scene level -> no signal
        val close = cal.convert(3.0f, 1.0f)          // 3x the median -> close
        assertNotNull(close)
        assertTrue(close!! <= 1.5f)
        val mid = cal.convert(2.0f, 1.0f)            // 2x -> caution-ish
        assertNotNull(mid)
        assertTrue(mid!! in 1.5f..3f)
    }

    @Test
    fun `temporal baseline catches a wall that fills the view`() {
        // Without history, a wall that IS the scene median gives no signal...
        val fresh = DepthCalibrator()
        assertNull(fresh.convert(3.0f, 3.0f))

        // ...but with a remembered normal scene, the same reading warns
        val cal = DepthCalibrator()
        repeat(30) { cal.updateBaseline(1.0f) }
        val d = cal.convert(3.0f, 3.0f)
        assertNotNull(d)
        assertEquals(1.0f, d!!, 0.01f)
    }

    @Test
    fun `baseline drifts toward a persistently changed scene`() {
        val cal = DepthCalibrator()
        repeat(30) { cal.updateBaseline(1.0f) }
        // Scene legitimately becomes nearer overall (e.g. walked indoors);
        // after enough frames the baseline follows and stops warning
        repeat(400) { cal.updateBaseline(3.0f) }
        assertNull(cal.convert(3.0f, 3.0f))
    }

    @Test
    fun `too few or degenerate samples never calibrate`() {
        val cal = DepthCalibrator()
        cal.addSample(2f, 2f)
        cal.addSample(2f, 2f)
        assertTrue(!cal.isCalibrated)
    }

    @Test
    fun `closeness corrections catch truncated and frame-filling boxes`() {
        // Truncated top+bottom: pinhole overestimates -> capped to very close
        assertEquals(1.0f, DistanceEstimator.applyCloseness(5f, 0.2f, true, true)!!, 0.01f)
        // Frame-filling unknown class -> very close
        assertEquals(1.1f, DistanceEstimator.applyCloseness(null, 0.5f, false, false)!!, 0.01f)
        // Large unknown class -> conservative caution distance
        assertEquals(2.2f, DistanceEstimator.applyCloseness(null, 0.3f, false, false)!!, 0.01f)
        // Small unknown class -> still unknown
        assertNull(DistanceEstimator.applyCloseness(null, 0.1f, false, false))
        // Normal estimate passes through untouched
        assertEquals(4f, DistanceEstimator.applyCloseness(4f, 0.1f, false, false))
    }
}
