package dev.quad.shepherd

import dev.quad.shepherd.guidance.DistanceEstimator
import dev.quad.shepherd.guidance.GuidanceEngine
import dev.quad.shepherd.vision.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceEngineTest {

    private val frameWidth = 900

    private fun det(
        x1: Float, x2: Float,
        distance: Float?,
        label: String = "person",
    ) = Detection(x1, 100f, x2, 500f, 0.9f, 0, label, distanceMeters = distance)

    @Test
    fun `empty frame is clear`() {
        val g = GuidanceEngine().update(emptyList(), frameWidth)
        assertEquals(GuidanceEngine.Severity.CLEAR, g.severity)
        assertEquals(0f, g.steer, 0.001f)
        assertNull(g.message)
        assertNull(g.nearestDistanceMeters)
    }

    @Test
    fun `close obstacle dead ahead is danger and steers away`() {
        // Obstacle slightly right of center, 1m away -> danger, steer left
        val g = GuidanceEngine().update(
            listOf(det(x1 = 500f, x2 = 700f, distance = 1.0f)),
            frameWidth,
        )
        assertEquals(GuidanceEngine.Severity.DANGER, g.severity)
        assertTrue("expected leftward steer, got ${g.steer}", g.steer < 0f)
        assertNotNull(g.message)
        assertTrue(g.message!!.contains("person"))
    }

    @Test
    fun `mid distance obstacle is caution`() {
        val g = GuidanceEngine().update(
            listOf(det(x1 = 350f, x2 = 550f, distance = 2.5f)),
            frameWidth,
        )
        assertEquals(GuidanceEngine.Severity.CAUTION, g.severity)
    }

    @Test
    fun `far obstacle is clear`() {
        val g = GuidanceEngine().update(
            listOf(det(x1 = 400f, x2 = 500f, distance = 8f)),
            frameWidth,
        )
        assertEquals(GuidanceEngine.Severity.CLEAR, g.severity)
    }

    @Test
    fun `obstacle at edge does not trigger danger`() {
        // Far left of frame — outside the central walking corridor
        val g = GuidanceEngine().update(
            listOf(det(x1 = 0f, x2 = 100f, distance = 1.0f)),
            frameWidth,
        )
        assertEquals(GuidanceEngine.Severity.CLEAR, g.severity)
    }

    @Test
    fun `depth-only wall ahead triggers danger without any detections`() {
        // The detector sees nothing (a blank wall has no COCO class), but
        // the depth map says the central column is 1 m away
        val cols = FloatArray(GuidanceEngine.NUM_COLUMNS)
        cols[GuidanceEngine.NUM_COLUMNS / 2] = 1.0f
        val g = GuidanceEngine().update(emptyList(), frameWidth, cols)
        assertEquals(GuidanceEngine.Severity.DANGER, g.severity)
        assertEquals("obstacle", g.nearestLabel)
        assertNotNull(g.message)
        assertTrue(g.message!!.contains("obstacle"))
        assertTrue(kotlin.math.abs(g.steer) > 0f)
    }

    @Test
    fun `far depth columns stay clear`() {
        val cols = FloatArray(GuidanceEngine.NUM_COLUMNS) { 8f }
        val g = GuidanceEngine().update(emptyList(), frameWidth, cols)
        assertEquals(GuidanceEngine.Severity.CLEAR, g.severity)
    }

    @Test
    fun `zero depth entries mean no signal, not danger`() {
        val cols = FloatArray(GuidanceEngine.NUM_COLUMNS)   // all 0 = no signal
        val g = GuidanceEngine().update(emptyList(), frameWidth, cols)
        assertEquals(GuidanceEngine.Severity.CLEAR, g.severity)
    }

    @Test
    fun `depth beats detection when it is closer`() {
        val cols = FloatArray(GuidanceEngine.NUM_COLUMNS)
        cols[GuidanceEngine.NUM_COLUMNS / 2] = 0.8f          // wall at 0.8 m
        val g = GuidanceEngine().update(
            listOf(det(x1 = 400f, x2 = 500f, distance = 2.5f)),  // person at 2.5 m
            frameWidth,
            cols,
        )
        assertEquals(GuidanceEngine.Severity.DANGER, g.severity)
        assertEquals("obstacle", g.nearestLabel)
        assertEquals(0.8f, g.nearestDistanceMeters!!, 0.01f)
    }

    @Test
    fun `distance estimator uses pinhole model`() {
        // person (1.7m) filling 400px at focal 400px -> 1.7m away
        val d = DistanceEstimator.estimate("person", 400f)
        assertNotNull(d)
        assertEquals(1.7f, d!!, 0.05f)

        // unknown class -> null
        assertNull(DistanceEstimator.estimate("kite", 200f))
    }
}
