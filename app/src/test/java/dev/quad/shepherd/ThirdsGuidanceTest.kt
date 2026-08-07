package dev.quad.shepherd

import dev.quad.shepherd.guidance.GuidanceEngine
import dev.quad.shepherd.guidance.ThirdsGuidance
import dev.quad.shepherd.guidance.ThirdsGuidance.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdsGuidanceTest {

    /** 16 columns, every third at one walkable fraction. */
    private fun cols(left: Float, mid: Float, right: Float): FloatArray =
        FloatArray(16) { c ->
            when {
                c < 5 -> left
                c < 11 -> mid
                else -> right
            }
        }

    private fun near(cx: Float) = ThirdsGuidance.Obstacle(cx, near = true)
    private fun far(cx: Float) = ThirdsGuidance.Obstacle(cx, near = false)

    private fun ThirdsGuidance.run(
        obstacles: List<ThirdsGuidance.Obstacle>,
        seg: FloatArray?,
        frames: Int = ThirdsGuidance.DEBOUNCE_FRAMES + 1,
    ): Decision {
        var d = Decision.STRAIGHT
        repeat(frames) { d = update(obstacles, seg) }
        return d
    }

    // ---- forward decision from the middle third -------------------------

    @Test
    fun `clear middle means straight`() {
        val g = ThirdsGuidance()
        assertEquals(Decision.STRAIGHT, g.run(emptyList(), cols(0.9f, 0.9f, 0.9f)))
    }

    @Test
    fun `a far object in the middle does not block`() {
        val g = ThirdsGuidance()
        assertEquals(Decision.STRAIGHT, g.run(listOf(far(0.5f)), cols(0.9f, 0.9f, 0.9f)))
    }

    @Test
    fun `objects in the outer thirds do not block forward`() {
        val g = ThirdsGuidance()
        assertEquals(
            Decision.STRAIGHT,
            g.run(listOf(near(0.1f), near(0.92f)), cols(0.9f, 0.9f, 0.9f)),
        )
    }

    @Test
    fun `a near object in the middle third blocks`() {
        val g = ThirdsGuidance()
        val d = g.run(listOf(near(0.5f)), cols(0.9f, 0.9f, 0.9f))
        assertTrue(d == Decision.LEFT || d == Decision.RIGHT)
    }

    @Test
    fun `unwalkable middle blocks even with no detections`() {
        val g = ThirdsGuidance()
        val d = g.run(emptyList(), cols(0.9f, 0.2f, 0.9f))
        assertTrue(d == Decision.LEFT || d == Decision.RIGHT)
    }

    // ---- side choice ----------------------------------------------------

    @Test
    fun `dodges toward the more walkable side`() {
        val g = ThirdsGuidance()
        assertEquals(Decision.LEFT, g.run(emptyList(), cols(0.85f, 0.2f, 0.35f)))

        val g2 = ThirdsGuidance()
        assertEquals(Decision.RIGHT, g2.run(emptyList(), cols(0.35f, 0.2f, 0.85f)))
    }

    @Test
    fun `near objects on a side count against it`() {
        val g = ThirdsGuidance()
        // Both sides equally walkable, but two objects stand on the right
        val d = g.run(
            listOf(near(0.5f), near(0.85f), near(0.95f)),
            cols(0.8f, 0.9f, 0.8f),
        )
        assertEquals(Decision.LEFT, d)
    }

    @Test
    fun `without segmentation the side with fewer objects wins`() {
        val g = ThirdsGuidance()
        val d = g.run(listOf(near(0.5f), near(0.2f)), seg = null)
        assertEquals(Decision.RIGHT, d)
    }

    // ---- stop -----------------------------------------------------------

    @Test
    fun `both sides bad means stop`() {
        val g = ThirdsGuidance()
        assertEquals(Decision.STOP, g.run(emptyList(), cols(0.1f, 0.15f, 0.12f)))
    }

    @Test
    fun `objects everywhere with tight sides means stop`() {
        val g = ThirdsGuidance()
        val d = g.run(
            listOf(near(0.15f), near(0.5f), near(0.9f)),
            cols(0.5f, 0.5f, 0.5f),
        )
        assertEquals(Decision.STOP, d)
    }

    // ---- hysteresis and latching ----------------------------------------

    @Test
    fun `one noisy frame does not trigger a dodge`() {
        val g = ThirdsGuidance()
        g.run(emptyList(), cols(0.9f, 0.9f, 0.9f))
        // Single blocked frame, then clear again: no dodge
        assertEquals(
            Decision.STRAIGHT,
            g.update(listOf(near(0.5f)), cols(0.9f, 0.9f, 0.9f)),
        )
        assertEquals(Decision.STRAIGHT, g.run(emptyList(), cols(0.9f, 0.9f, 0.9f)))
    }

    @Test
    fun `the dodge side stays latched while the middle is blocked`() {
        val g = ThirdsGuidance()
        assertEquals(Decision.LEFT, g.run(emptyList(), cols(0.9f, 0.2f, 0.6f)))
        // The right side becomes marginally better; both remain viable —
        // the instruction must not flip mid-dodge
        assertEquals(Decision.LEFT, g.run(emptyList(), cols(0.6f, 0.2f, 0.7f)))
    }

    @Test
    fun `the latched side is abandoned when it stops being viable`() {
        val g = ThirdsGuidance()
        assertEquals(Decision.LEFT, g.run(emptyList(), cols(0.9f, 0.2f, 0.5f)))
        // The left side collapses (wall), the right stays open
        assertEquals(Decision.RIGHT, g.run(emptyList(), cols(0.1f, 0.2f, 0.5f)))
    }

    @Test
    fun `clearing the middle returns to straight after the debounce`() {
        val g = ThirdsGuidance()
        assertTrue(g.run(listOf(near(0.5f)), cols(0.9f, 0.9f, 0.9f)) != Decision.STRAIGHT)
        assertEquals(Decision.STRAIGHT, g.run(emptyList(), cols(0.9f, 0.9f, 0.9f)))
    }

    @Test
    fun `seg recovery must clear the exit threshold, not just the entry one`() {
        val g = ThirdsGuidance()
        assertTrue(g.run(emptyList(), cols(0.9f, 0.30f, 0.9f)) != Decision.STRAIGHT)
        // 0.5 is above the 0.45 entry threshold but below the 0.60 exit:
        // still avoiding
        assertTrue(g.run(emptyList(), cols(0.9f, 0.50f, 0.9f)) != Decision.STRAIGHT)
        assertEquals(Decision.STRAIGHT, g.run(emptyList(), cols(0.9f, 0.75f, 0.9f)))
    }

    // ---- obstacle mapping ------------------------------------------------

    @Test
    fun `obstacle mapping gates on distance and falls back to box height`() {
        // Near by distance
        assertTrue(
            ThirdsGuidance.obstacle(400f, 600f, 0f, 200f, 1000, 1000, 1.5f).near,
        )
        // Far by distance
        assertFalse(
            ThirdsGuidance.obstacle(400f, 600f, 0f, 200f, 1000, 1000, 6f).near,
        )
        // No distance: tall box is near, small box is not
        assertTrue(
            ThirdsGuidance.obstacle(0f, 100f, 0f, 500f, 1000, 1000, null).near,
        )
        assertFalse(
            ThirdsGuidance.obstacle(0f, 100f, 0f, 200f, 1000, 1000, null).near,
        )
        // Centre lands in the right band
        assertEquals(
            0.5f,
            ThirdsGuidance.obstacle(400f, 600f, 0f, 100f, 1000, 1000, 1f).centerXFrac,
            1e-6f,
        )
    }

    // ---- wire mapping ----------------------------------------------------

    @Test
    fun `decisions map to the letters the wheel expects`() {
        val g = ThirdsGuidance()
        val seg = cols(0.9f, 0.9f, 0.9f)
        fun dir(d: Decision) = dev.quad.shepherd.actuator.CaneCommand
            .from(g.toGuidance(d, seg)).direction

        assertEquals(dev.quad.shepherd.actuator.CaneCommand.Direction.STRAIGHT, dir(Decision.STRAIGHT))
        assertEquals(dev.quad.shepherd.actuator.CaneCommand.Direction.LEFT, dir(Decision.LEFT))
        assertEquals(dev.quad.shepherd.actuator.CaneCommand.Direction.RIGHT, dir(Decision.RIGHT))
        assertEquals(dev.quad.shepherd.actuator.CaneCommand.Direction.STOP, dir(Decision.STOP))
    }

    @Test
    fun `severity mirrors the decision`() {
        val g = ThirdsGuidance()
        assertEquals(
            GuidanceEngine.Severity.DANGER,
            g.toGuidance(Decision.STOP, null).severity,
        )
        assertEquals(
            GuidanceEngine.Severity.CLEAR,
            g.toGuidance(Decision.STRAIGHT, null).severity,
        )
    }
}
