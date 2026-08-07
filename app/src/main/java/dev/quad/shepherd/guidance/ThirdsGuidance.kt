package dev.quad.shepherd.guidance

/**
 * Screen-thirds obstacle steering, qhackgps-style.
 *
 * The screen splits into three vertical bands. The decision of whether the
 * user can keep going forward is taken from the MIDDLE third alone: a near
 * object detection there, or too few walkable pixels there, blocks it.
 * While blocked, the dodge side is whichever outer third looks better —
 * most walkable pixels, fewest near objects — and that side is LATCHED
 * until the middle clears, so near-identical frames cannot flip the
 * instruction mid-dodge. If neither side is viable the instruction is
 * STOP.
 *
 * The caller speaks the decision on every change ("left" / "right" /
 * "stop", then "straight" when the way clears), mirroring the qhackgps
 * dodge-and-return semantics: this class only decides.
 *
 * Inputs are deliberately cheap and already computed by the pipeline:
 * YOLO boxes (with their distance estimates) and the per-column walkable
 * fractions from the segmentation ensemble. No grid, no planner.
 *
 * Pure Kotlin for JVM unit testing.
 */
class ThirdsGuidance {

    enum class Decision { STRAIGHT, LEFT, RIGHT, STOP }

    /**
     * One detection reduced to what the thirds logic needs: where its
     * centre sits across the frame (0..1) and whether it is close enough
     * to obstruct.
     */
    data class Obstacle(val centerXFrac: Float, val near: Boolean)

    companion object {
        /** Detections closer than this obstruct. */
        const val OBSTRUCT_DIST_M = 2.5f

        /** No distance estimate: a box this tall (fraction of the frame)
         *  is treated as near — big things in front of the lens are close. */
        const val OBSTRUCT_HEIGHT_FRAC = 0.35f

        /** Middle-third walkable fraction below this blocks forward… */
        const val MIDDLE_BLOCK_ENTER = 0.45f

        /** …and must recover past this to unblock (hysteresis). */
        const val MIDDLE_BLOCK_EXIT = 0.60f

        /** A side scoring below this is not a dodge, it is a wall. */
        const val SIDE_VIABLE_SCORE = 0.30f

        /** Score penalty per near object on a side, in walkable units. */
        const val OBJECT_PENALTY = 0.35f

        /** With no segmentation opinion a side starts from this score. */
        const val UNKNOWN_SIDE_SCORE = 0.55f

        /** Consecutive frames a state change must persist before it takes
         *  effect (~300 ms at the ~10 Hz frame cadence). */
        const val DEBOUNCE_FRAMES = 3

        /** Map a detection to an [Obstacle]. */
        fun obstacle(
            x1: Float,
            x2: Float,
            y1: Float,
            y2: Float,
            frameWidth: Int,
            frameHeight: Int,
            distanceMeters: Float?,
        ): Obstacle {
            val cx = ((x1 + x2) / 2f / frameWidth).coerceIn(0f, 1f)
            val near = when {
                distanceMeters != null -> distanceMeters < OBSTRUCT_DIST_M
                frameHeight > 0 -> (y2 - y1) / frameHeight > OBSTRUCT_HEIGHT_FRAC
                else -> false
            }
            return Obstacle(cx, near)
        }
    }

    /** Latched avoidance state. */
    private var avoiding = false
    private var side = Decision.RIGHT
    private var blockedStreak = 0
    private var clearStreak = 0
    private var sideSwitchStreak = 0

    var lastDecision = Decision.STRAIGHT
        private set

    /** Mean walkable fraction over columns [lo, hi) of a 16-column array,
     *  or NaN with no segmentation. */
    private fun walkable(cols: FloatArray?, lo: Int, hi: Int): Float {
        if (cols == null || cols.isEmpty()) return Float.NaN
        val l = (lo * cols.size / 16).coerceIn(0, cols.size - 1)
        val h = (hi * cols.size / 16).coerceIn(l + 1, cols.size)
        var acc = 0f
        for (i in l until h) acc += cols[i]
        return acc / (h - l)
    }

    private fun score(walkableFrac: Float, nearObjects: Int): Float {
        val base = if (walkableFrac.isNaN()) UNKNOWN_SIDE_SCORE else walkableFrac
        return base - OBJECT_PENALTY * nearObjects
    }

    /**
     * One frame's decision.
     *
     * @param obstacles detections mapped through [Companion.obstacle].
     * @param segClearance per-column walkable fraction, left to right
     *   (16 columns from WalkableColumns), or null when segmentation has
     *   no opinion yet.
     */
    fun update(obstacles: List<Obstacle>, segClearance: FloatArray?): Decision {
        var midObjects = 0
        var leftObjects = 0
        var rightObjects = 0
        for (o in obstacles) {
            if (!o.near) continue
            when {
                o.centerXFrac < 1f / 3f -> leftObjects++
                o.centerXFrac < 2f / 3f -> midObjects++
                else -> rightObjects++
            }
        }

        val midWalk = walkable(segClearance, 5, 11)
        val leftScore = score(walkable(segClearance, 0, 5), leftObjects)
        val rightScore = score(walkable(segClearance, 11, 16), rightObjects)

        // Middle-third obstruction, with hysteresis on the seg fraction
        val threshold = if (avoiding) MIDDLE_BLOCK_EXIT else MIDDLE_BLOCK_ENTER
        val segBlocked = !midWalk.isNaN() && midWalk < threshold
        val blockedNow = midObjects > 0 || segBlocked

        // Debounced mode machine
        if (blockedNow) {
            blockedStreak++
            clearStreak = 0
        } else {
            clearStreak++
            blockedStreak = 0
        }
        if (!avoiding && blockedStreak >= DEBOUNCE_FRAMES) {
            avoiding = true
            side = if (rightScore >= leftScore) Decision.RIGHT else Decision.LEFT
            sideSwitchStreak = 0
        } else if (avoiding && clearStreak >= DEBOUNCE_FRAMES) {
            avoiding = false
        }

        if (!avoiding) {
            lastDecision = Decision.STRAIGHT
            return lastDecision
        }

        // While avoiding: stay on the latched side unless it stops being
        // viable and the other side is (debounced, so one noisy frame
        // cannot flip the instruction).
        val latchedScore = if (side == Decision.LEFT) leftScore else rightScore
        val otherScore = if (side == Decision.LEFT) rightScore else leftScore
        if (latchedScore < SIDE_VIABLE_SCORE && otherScore >= SIDE_VIABLE_SCORE) {
            if (++sideSwitchStreak >= DEBOUNCE_FRAMES) {
                side = if (side == Decision.LEFT) Decision.RIGHT else Decision.LEFT
                sideSwitchStreak = 0
            }
        } else {
            sideSwitchStreak = 0
        }

        lastDecision = when {
            maxOf(leftScore, rightScore) < SIDE_VIABLE_SCORE -> Decision.STOP
            else -> side
        }
        return lastDecision
    }

    /** The wire/UI form of a decision; STRAIGHT callers keep their own. */
    fun toGuidance(decision: Decision, segClearance: FloatArray?): GuidanceEngine.Guidance {
        val threat = FloatArray(GuidanceEngine.NUM_COLUMNS)
        if (segClearance != null && segClearance.isNotEmpty()) {
            for (c in 0 until GuidanceEngine.NUM_COLUMNS) {
                val s = c * segClearance.size / GuidanceEngine.NUM_COLUMNS
                threat[c] = (1f - segClearance[s.coerceIn(0, segClearance.size - 1)])
                    .coerceIn(0f, 1f)
            }
        }
        return when (decision) {
            Decision.STRAIGHT -> GuidanceEngine.Guidance(
                severity = GuidanceEngine.Severity.CLEAR,
                steer = 0f,
                nearestDistanceMeters = null,
                nearestLabel = null,
                columnThreat = threat,
            )

            Decision.LEFT, Decision.RIGHT -> GuidanceEngine.Guidance(
                severity = GuidanceEngine.Severity.CAUTION,
                steer = if (decision == Decision.LEFT) -0.75f else 0.75f,
                nearestDistanceMeters = null,
                nearestLabel = "obstacle",
                columnThreat = threat,
            )

            Decision.STOP -> GuidanceEngine.Guidance(
                severity = GuidanceEngine.Severity.DANGER,
                steer = 0f,
                nearestDistanceMeters = null,
                nearestLabel = "obstacle",
                columnThreat = threat,
            )
        }
    }

    fun reset() {
        avoiding = false
        blockedStreak = 0
        clearStreak = 0
        sideSwitchStreak = 0
        lastDecision = Decision.STRAIGHT
    }
}
