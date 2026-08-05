package dev.quad.shepherd

import dev.quad.shepherd.guidance.DepthCalibrator
import dev.quad.shepherd.guidance.DistanceEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthCalibratorTest {

    @Test
    fun `passes metric readings through by default`() {
        val cal = DepthCalibrator()
        assertEquals(1.4f, cal.convert(1.4f)!!, 0.001f)
        assertEquals(6f, cal.convert(6f)!!, 0.001f)
    }

    @Test
    fun `implausible readings are rejected`() {
        val cal = DepthCalibrator()
        assertNull(cal.convert(0f))
        assertNull(cal.convert(-1f))
        assertNull(cal.convert(50f))
        assertNull(cal.convert(Float.NaN))
    }

    @Test
    fun `reference samples trim systematic scale bias`() {
        val cal = DepthCalibrator()
        // Model consistently reads 20% short of trusted pinhole distances
        repeat(30) { cal.addSample(2.0f, 2.4f) }
        assertEquals(1.2f, cal.currentScale, 0.05f)
        assertEquals(2.4f, cal.convert(2.0f)!!, 0.15f)
    }

    @Test
    fun `scale is bounded against absurd references`() {
        val cal = DepthCalibrator()
        repeat(50) { cal.addSample(1.0f, 19f) }
        assertTrue(cal.currentScale <= 2f)

        val cal2 = DepthCalibrator()
        repeat(50) { cal2.addSample(10f, 0.5f) }
        assertTrue(cal2.currentScale >= 0.5f)
    }

    @Test
    fun `out of range samples are ignored`() {
        val cal = DepthCalibrator()
        cal.addSample(0.05f, 2f)    // model reading too close to be real
        cal.addSample(2f, 30f)      // reference outside trusted band
        assertEquals(1f, cal.currentScale, 0.001f)
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
