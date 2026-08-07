package dev.quad.shepherd.path

import dev.quad.shepherd.actuator.CaneCommand
import dev.quad.shepherd.guidance.GuidanceEngine

/**
 * Aggregates the planner's per-frame commands into one motor letter per
 * 200 ms period: every estimate in the window votes for its direction,
 * weighted by severity (danger counts most). Ties break by safety order —
 * STOP beats turns, turns beat STRAIGHT. An empty window (vision stalled)
 * yields STOP: no evidence means don't march the user forward.
 *
 * Wire letters (BLE NUS to the UNO Q, forwarded to the Motor Modulino):
 * L = left, R = right, S = straight, X = stop.
 *
 * Pure Kotlin for JVM unit testing.
 */
class CommandAggregator {

    companion object {
        const val PERIOD_MS = 200L

        private fun weight(severity: GuidanceEngine.Severity): Float = when (severity) {
            GuidanceEngine.Severity.DANGER -> 3f
            GuidanceEngine.Severity.CAUTION -> 2f
            GuidanceEngine.Severity.CLEAR -> 1f
        }
    }

    private val votes = HashMap<CaneCommand.Direction, Float>()
    private var lastLetter = 'X'
    private var emptyStreak = 0

    /** Consecutive empty windows tolerated before failing safe to stop. */
    private val maxEmptyStreak = 3

    /** Feed one planner output (called per processed frame). */
    @Synchronized
    fun offer(guidance: GuidanceEngine.Guidance) {
        val direction = CaneCommand.from(guidance).direction
        votes[direction] = (votes[direction] ?: 0f) + weight(guidance.severity)
    }

    /**
     * Close the current period: winning direction as its wire letter, and
     * reset for the next window. The ~11 Hz frame cadence beats against
     * the 200 ms window, so an occasional empty window is normal — repeat
     * the last decision and only fail to STOP after [maxEmptyStreak]
     * consecutive empties (a true vision stall); the Arduino's own 1 s
     * failsafe backstops the link itself.
     */
    @Synchronized
    fun decide(): Char {
        val w = winner()
        if (w == null) {
            emptyStreak++
            if (emptyStreak >= maxEmptyStreak) lastLetter = 'X'
            return lastLetter
        }
        emptyStreak = 0
        lastLetter = when (w) {
            CaneCommand.Direction.STOP -> 'X'
            CaneCommand.Direction.LEFT -> 'L'
            CaneCommand.Direction.RIGHT -> 'R'
            CaneCommand.Direction.STRAIGHT -> 'S'
        }
        votes.clear()
        return lastLetter
    }

    private fun winner(): CaneCommand.Direction? {
        if (votes.isEmpty()) return null
        val max = votes.values.max()
        val top = votes.filterValues { it >= max - 1e-6f }.keys
        // Safety order on ties
        return listOf(
            CaneCommand.Direction.STOP,
            CaneCommand.Direction.LEFT,
            CaneCommand.Direction.RIGHT,
            CaneCommand.Direction.STRAIGHT,
        ).first { it in top }
    }
}
