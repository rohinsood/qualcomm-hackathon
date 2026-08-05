package dev.quad.shepherd

import dev.quad.shepherd.guidance.GuidanceEngine
import dev.quad.shepherd.guidance.SceneBlackboard
import dev.quad.shepherd.vision.Detection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneBlackboardTest {

    private val t0 = 50_000L

    private fun det(label: String, cx: Float, dist: Float?, frameWidth: Int = 1000) = Detection(
        x1 = cx * frameWidth - 50f,
        y1 = 100f,
        x2 = cx * frameWidth + 50f,
        y2 = 300f,
        score = 0.9f,
        classId = 0,
        label = label,
        distanceMeters = dist,
    )

    private fun guidance(
        sev: GuidanceEngine.Severity,
        label: String?,
        dist: Float?,
    ) = GuidanceEngine.Guidance(sev, 0f, dist, label, FloatArray(GuidanceEngine.NUM_COLUMNS))

    @Test
    fun `digest lists objects nearest first with bearing and distance`() {
        val b = SceneBlackboard()
        b.updateFrame(listOf(det("chair", 0.5f, 2.5f), det("person", 0.3f, 1.2f)), 1000)
        b.updateGuidance(guidance(GuidanceEngine.Severity.CAUTION, "person", 1.2f))
        val d = b.digest(t0)
        assertTrue(d, d.contains("Path: caution — nearest: person, 1.2 m"))
        assertTrue(d, d.indexOf("person 1.2 m slightly left") in 0 until d.indexOf("chair 2.5 m ahead"))
    }

    @Test
    fun `clear empty scene reads clear`() {
        val b = SceneBlackboard()
        val d = b.digest(t0)
        assertTrue(d, d.contains("Path: clear."))
        assertTrue(d, d.contains("no labeled objects"))
        assertFalse(d, d.contains("nearest:"))
    }

    @Test
    fun `unknown distances and edge bearings are spelled out`() {
        val b = SceneBlackboard()
        b.updateFrame(listOf(det("backpack", 0.95f, null), det("person", 0.05f, 1.0f)), 1000)
        b.updateGuidance(guidance(GuidanceEngine.Severity.CLEAR, null, null))
        val d = b.digest(t0)
        assertTrue(d, d.contains("backpack on your right, distance unknown"))
        assertTrue(d, d.contains("person 1 m on your left"))
    }

    @Test
    fun `depth-only wall shows up through the guidance verdict`() {
        val b = SceneBlackboard()
        b.updateFrame(emptyList(), 1000)
        b.updateGuidance(guidance(GuidanceEngine.Severity.DANGER, "obstacle", 0.8f))
        val d = b.digest(t0)
        assertTrue(d, d.contains("Path: danger — nearest: obstacle, 0.8 m"))
        assertTrue(d, d.contains("no labeled objects"))
    }

    @Test
    fun `alerts appear with age and expire after thirty seconds`() {
        val b = SceneBlackboard()
        b.noteAlert("person. Go left.", t0)
        val d = b.digest(t0 + 4_000)
        assertTrue(d, d.contains("\"person. Go left.\" 4 s ago"))
        assertFalse(b.digest(t0 + 31_000).contains("person. Go left."))
    }

    @Test
    fun `only the most recent alerts are kept`() {
        val b = SceneBlackboard()
        repeat(6) { i -> b.noteAlert("alert $i.", t0 + i) }
        val d = b.digest(t0 + 1_000)
        assertFalse(d, d.contains("alert 0."))
        assertFalse(d, d.contains("alert 1."))
        assertTrue(d, d.contains("alert 2."))
        assertTrue(d, d.contains("alert 5."))
    }
}
