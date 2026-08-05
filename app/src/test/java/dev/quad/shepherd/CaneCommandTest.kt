package dev.quad.shepherd

import dev.quad.shepherd.actuator.CaneCommand
import dev.quad.shepherd.guidance.GuidanceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaneCommandTest {

    private fun g(sev: GuidanceEngine.Severity, steer: Float) =
        GuidanceEngine.Guidance(sev, steer, null, null, FloatArray(GuidanceEngine.NUM_COLUMNS))

    @Test
    fun `clear centered path keeps the wheel straight`() {
        val c = CaneCommand.from(g(GuidanceEngine.Severity.CLEAR, 0f))
        assertEquals(CaneCommand.Direction.STRAIGHT, c.direction)
        assertFalse(c.urgent)
    }

    @Test
    fun `caution steers toward the safe gap without urgency`() {
        val c = CaneCommand.from(g(GuidanceEngine.Severity.CAUTION, 0.5f))
        assertEquals(CaneCommand.Direction.RIGHT, c.direction)
        assertFalse(c.urgent)
        assertEquals(0.5f, c.turn, 1e-6f)
    }

    @Test
    fun `danger with a gap steers hard and urgently`() {
        val c = CaneCommand.from(g(GuidanceEngine.Severity.DANGER, -0.6f))
        assertEquals(CaneCommand.Direction.LEFT, c.direction)
        assertTrue(c.urgent)
    }

    @Test
    fun `danger with no better gap commands STOP`() {
        val c = CaneCommand.from(g(GuidanceEngine.Severity.DANGER, 0.1f))
        assertEquals(CaneCommand.Direction.STOP, c.direction)
        assertTrue(c.urgent)
    }

    @Test
    fun `the dead zone keeps small corrections straight`() {
        assertEquals(
            CaneCommand.Direction.STRAIGHT,
            CaneCommand.from(g(GuidanceEngine.Severity.CAUTION, 0.2f)).direction,
        )
        assertEquals(
            CaneCommand.Direction.STRAIGHT,
            CaneCommand.from(g(GuidanceEngine.Severity.CAUTION, -0.2f)).direction,
        )
    }

    @Test
    fun `turn is clamped to the wheel's range`() {
        val c = CaneCommand.from(g(GuidanceEngine.Severity.CAUTION, 1.4f))
        assertEquals(CaneCommand.Direction.RIGHT, c.direction)
        assertEquals(1f, c.turn, 1e-6f)
    }
}
