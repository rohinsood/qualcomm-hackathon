package dev.quad.shepherd

import dev.quad.shepherd.guidance.AnnouncementPolicy
import dev.quad.shepherd.guidance.GuidanceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementPolicyTest {

    private val t0 = 100_000L

    private fun guidance(
        severity: GuidanceEngine.Severity,
        steer: Float = 0f,
        dist: Float? = 1.0f,
        label: String? = "obstacle",
    ) = GuidanceEngine.Guidance(
        severity = severity,
        steer = steer,
        nearestDistanceMeters = dist,
        nearestLabel = label,
        columnThreat = FloatArray(GuidanceEngine.NUM_COLUMNS),
    )

    @Test
    fun `danger entry speaks immediately, interrupting, in the urgent register`() {
        val p = AnnouncementPolicy()
        val u = p.decide(guidance(GuidanceEngine.Severity.DANGER), t0)
        assertNotNull(u)
        assertTrue(u!!.interrupt)
        assertTrue(u.urgent)
        assertTrue(u.text.contains("obstacle"))
        assertTrue(u.text.contains("Stop"))
    }

    @Test
    fun `walking jitter cannot re-trigger speech - the stutter scenario`() {
        val p = AnnouncementPolicy()
        p.decide(guidance(GuidanceEngine.Severity.DANGER), t0)

        // Frame-rate danger frames with distance jitter -> total silence
        // (danger text carries no distance, so nothing changes)
        assertNull(p.decide(guidance(GuidanceEngine.Severity.DANGER, dist = 1.02f), t0 + 100))
        assertNull(p.decide(guidance(GuidanceEngine.Severity.DANGER, dist = 0.98f), t0 + 300))
        assertNull(p.decide(guidance(GuidanceEngine.Severity.DANGER, dist = 1.4f), t0 + 900))

        // The periodic repeat never interrupts
        val repeat = p.decide(guidance(GuidanceEngine.Severity.DANGER), t0 + 2100)
        assertNotNull(repeat)
        assertFalse(repeat!!.interrupt)
        assertTrue(repeat.urgent)
    }

    @Test
    fun `direction change speaks WITHOUT interrupting, and only after the gap`() {
        val p = AnnouncementPolicy()
        p.decide(guidance(GuidanceEngine.Severity.DANGER, steer = -0.5f), t0)

        // 400 ms later the safest gap flips sides: within the minimum gap -> silence
        assertNull(p.decide(guidance(GuidanceEngine.Severity.DANGER, steer = 0.5f), t0 + 400))

        // After the gap the new instruction is spoken, queued (not clipping)
        val u = p.decide(guidance(GuidanceEngine.Severity.DANGER, steer = 0.5f), t0 + 1300)
        assertNotNull(u)
        assertFalse(u!!.interrupt)
        assertTrue(u.text.contains("Go right"))
    }

    @Test
    fun `escalation from caution into danger is the one thing that interrupts`() {
        val p = AnnouncementPolicy()
        p.decide(guidance(GuidanceEngine.Severity.CAUTION, dist = 2.5f), t0)
        // 300 ms later — inside the gap — the threat closes to danger
        val u = p.decide(guidance(GuidanceEngine.Severity.DANGER, dist = 1.2f), t0 + 300)
        assertNotNull(u)
        assertTrue(u!!.interrupt)
        assertTrue(u.urgent)
    }

    @Test
    fun `caution informs without urgency and repeats slowly`() {
        val p = AnnouncementPolicy()
        val u = p.decide(guidance(GuidanceEngine.Severity.CAUTION, dist = 2.5f), t0)
        assertNotNull(u)
        assertFalse(u!!.interrupt)
        assertFalse(u.urgent)
        assertTrue(u.text.contains("ahead"))
        assertTrue(u.text.contains("2.5"))

        assertNull(p.decide(guidance(GuidanceEngine.Severity.CAUTION, dist = 2.5f), t0 + 2000))
        assertNotNull(p.decide(guidance(GuidanceEngine.Severity.CAUTION, dist = 2.5f), t0 + 3600))
    }

    @Test
    fun `held danger without threat details keeps repeating the last instruction`() {
        val p = AnnouncementPolicy()
        p.decide(guidance(GuidanceEngine.Severity.DANGER), t0)
        val u = p.decide(
            guidance(GuidanceEngine.Severity.DANGER, dist = null, label = null),
            t0 + 2100,
        )
        assertNotNull(u)
        assertTrue(u!!.text.contains("obstacle"))
    }

    @Test
    fun `returning to clear confirms once`() {
        val p = AnnouncementPolicy()
        p.decide(guidance(GuidanceEngine.Severity.DANGER), t0)
        assertNull(p.decide(guidance(GuidanceEngine.Severity.CLEAR, dist = null, label = null), t0 + 500))
        val u = p.decide(guidance(GuidanceEngine.Severity.CLEAR, dist = null, label = null), t0 + 2000)
        assertNotNull(u)
        assertEquals("Path clear.", u!!.text)
        assertNull(p.decide(guidance(GuidanceEngine.Severity.CLEAR, dist = null, label = null), t0 + 4000))
    }

    @Test
    fun `quiet at startup when the path is already clear`() {
        val p = AnnouncementPolicy()
        assertNull(p.decide(guidance(GuidanceEngine.Severity.CLEAR, dist = null, label = null), t0))
    }

    @Test
    fun `caution distance is quantized to half meters`() {
        val p = AnnouncementPolicy()
        val u = p.decide(guidance(GuidanceEngine.Severity.CAUTION, dist = 2.3f), t0)
        assertNotNull(u)
        assertTrue(u!!.text.contains("2.5 meters"))
    }
}
