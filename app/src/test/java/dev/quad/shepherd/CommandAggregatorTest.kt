package dev.quad.shepherd

import dev.quad.shepherd.guidance.GuidanceEngine
import dev.quad.shepherd.path.CommandAggregator
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandAggregatorTest {

    private fun g(sev: GuidanceEngine.Severity, steer: Float) =
        GuidanceEngine.Guidance(sev, steer, null, null, FloatArray(GuidanceEngine.NUM_COLUMNS))

    @Test
    fun `majority direction wins the period`() {
        val a = CommandAggregator()
        a.offer(g(GuidanceEngine.Severity.CLEAR, -0.6f))
        a.offer(g(GuidanceEngine.Severity.CLEAR, -0.5f))
        a.offer(g(GuidanceEngine.Severity.CLEAR, 0f))
        assertEquals('L', a.decide())
    }

    @Test
    fun `severity weighting lets one danger outvote two clears`() {
        val a = CommandAggregator()
        a.offer(g(GuidanceEngine.Severity.CLEAR, 0f))
        a.offer(g(GuidanceEngine.Severity.CLEAR, 0f))
        a.offer(g(GuidanceEngine.Severity.DANGER, 0.05f)) // danger + centered = STOP
        assertEquals('X', a.decide())
    }

    @Test
    fun `ties break toward safety`() {
        val a = CommandAggregator()
        a.offer(g(GuidanceEngine.Severity.CLEAR, -0.5f)) // LEFT, weight 1
        a.offer(g(GuidanceEngine.Severity.CLEAR, 0f))    // STRAIGHT, weight 1
        assertEquals('L', a.decide())
    }

    @Test
    fun `single empty period repeats the last decision`() {
        val a = CommandAggregator()
        a.offer(g(GuidanceEngine.Severity.CLEAR, 0f))
        assertEquals('S', a.decide())
        // Frame-cadence beat: one empty window must not twitch the motor
        assertEquals('S', a.decide())
    }

    @Test
    fun `sustained empty periods fail safe with stop`() {
        val a = CommandAggregator()
        a.offer(g(GuidanceEngine.Severity.CLEAR, 0f))
        assertEquals('S', a.decide())
        a.decide()
        a.decide()
        assertEquals('X', a.decide()) // third consecutive empty
    }

    @Test
    fun `never-fed aggregator stops`() {
        val a = CommandAggregator()
        repeat(3) { a.decide() }
        assertEquals('X', a.decide())
    }

    @Test
    fun `windows reset between periods`() {
        val a = CommandAggregator()
        a.offer(g(GuidanceEngine.Severity.DANGER, 0.05f))
        assertEquals('X', a.decide())
        a.offer(g(GuidanceEngine.Severity.CLEAR, 0f))
        assertEquals('S', a.decide())
    }
}
