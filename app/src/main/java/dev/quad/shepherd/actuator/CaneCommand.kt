package dev.quad.shepherd.actuator

import dev.quad.shepherd.guidance.GuidanceEngine
import kotlin.math.abs

/**
 * The steering command for the cane's front wheel — and, until that
 * hardware exists, for the on-screen steering panel that stands in for it.
 * The mapping mirrors the retired voice rules: danger with no better gap
 * means STOP; otherwise steer toward the safest window once the smoothed
 * steer clears the dead zone.
 *
 * Pure Kotlin for JVM unit testing.
 */
data class CaneCommand(
    val direction: Direction,
    /** Wheel deflection, -1 (hard left) .. +1 (hard right). */
    val turn: Float,
    /** True in danger: the actuator should move at full authority. */
    val urgent: Boolean,
) {
    enum class Direction { STRAIGHT, LEFT, RIGHT, STOP }

    companion object {
        /** Steer magnitudes below this are noise — keep the wheel centered. */
        const val DEAD_ZONE = 0.2f

        fun from(g: GuidanceEngine.Guidance): CaneCommand {
            val urgent = g.severity == GuidanceEngine.Severity.DANGER
            val direction = when {
                urgent && abs(g.steer) <= DEAD_ZONE -> Direction.STOP
                g.steer < -DEAD_ZONE -> Direction.LEFT
                g.steer > DEAD_ZONE -> Direction.RIGHT
                else -> Direction.STRAIGHT
            }
            return CaneCommand(direction, g.steer.coerceIn(-1f, 1f), urgent)
        }
    }
}
