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
    fun `danger entry speaks immediately with interrupt`() {
        val p = AnnouncementPolicy()
        val u = p.decide(guidance(GuidanceEngine.Severity.DANGER), t0)
        assertNotNull(u)
        assertTrue(u!!.interrupt)
        assertTrue(u.text.contains("obstacle"))
        assertTrue(u.text.contains("stop"))
    }

    @Test
    fun `unchanged danger frames stay silent until the repeat interval`() {
        val p = AnnouncementPolicy()
        p.decide(guidance(GuidanceEngine.Severity.DANGER), t0)

        // Frame-rate repeats with tiny distance jitter -> silence
        // (this exact pattern caused the "obs- obs- obs-" stutter)
        assertNull(p.decide(guidance(GuidanceEngine.Severity.DANGER, dist = 1.02f), t0 + 100))
        assertNull(p.decide(guidance(GuidanceEngine.Severity.DANGER, dist = 0.98f), t0 + 300))
        assertNull(p.decide(guidance(GuidanceEngine.Severity.DANGER, dist = 1.04f), t0 + 900))

        // After the repeat interval it speaks again, without interrupting
        val repeat = p.decide(guidance(GuidanceEngine.Severity.DANGER), t0 + 2100)
        assertNotNull(repeat)
        assertFalse(repeat!!.interrupt)
    }

    @Test
    fun `changed danger direction interrupts`() {
        val p = AnnouncementPolicy()
        p.decide(guidance(GuidanceEngine.Severity.DANGER, steer = -0.5f), t0)
        val u = p.decide(guidance(GuidanceEngine.Severity.DANGER, steer = 0.5f), t0 + 400)
        assertNotNull(u)
        assertTrue(u!!.interrupt)
        assertTrue(u.text.contains("move right"))
    }

    @Test
    fun `caution speaks without interrupting and repeats slowly`() {
        val p = AnnouncementPolicy()
        val u = p.decide(guidance(GuidanceEngine.Severity.CAUTION, dist = 2.5f), t0)
        assertNotNull(u)
        assertFalse(u!!.interrupt)
        assertTrue(u.text.contains("slow down"))

        assertNull(p.decide(guidance(GuidanceEngine.Severity.CAUTION, dist = 2.5f), t0 + 2000))
        assertNotNull(p.decide(guidance(GuidanceEngine.Severity.CAUTION, dist = 2.5f), t0 + 3600))
    }

    @Test
    fun `held danger without threat details keeps repeating the last instruction`() {
        val p = AnnouncementPolicy()
        p.decide(guidance(GuidanceEngine.Severity.DANGER), t0)
        // Severity hold frame: danger persists but the frame carries no label
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
        // Too soon after the last utterance -> wait
        assertNull(p.decide(guidance(GuidanceEngine.Severity.CLEAR, dist = null, label = null), t0 + 500))
        val u = p.decide(guidance(GuidanceEngine.Severity.CLEAR, dist = null, label = null), t0 + 2000)
        assertNotNull(u)
        assertEquals("Path clear.", u!!.text)
        // And only once
        assertNull(p.decide(guidance(GuidanceEngine.Severity.CLEAR, dist = null, label = null), t0 + 4000))
    }

    @Test
    fun `quiet at startup when the path is already clear`() {
        val p = AnnouncementPolicy()
        assertNull(p.decide(guidance(GuidanceEngine.Severity.CLEAR, dist = null, label = null), t0))
    }

    @Test
    fun `distance is quantized to half meters in the text`() {
        val p = AnnouncementPolicy()
        val u = p.decide(guidance(GuidanceEngine.Severity.DANGER, dist = 1.23f), t0)
        assertNotNull(u)
        assertTrue(u!!.text.contains("1 meters") || u.text.contains("1.5 meters"))
    }
}
