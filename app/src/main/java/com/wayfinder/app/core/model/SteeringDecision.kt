package com.wayfinder.app.core.model

import com.wayfinder.app.perception.seg.WalkableMask

/**
 * The result of one steering cycle. Stored atomically in DecisionStore and polled
 * independently by the haptic and speech loops.
 *
 * @property command        -1 (hard LEFT) .. +1 (hard RIGHT); 0 = neutral/clear
 * @property proximity      0..1 urgency (0 = clear, 1 = imminent contact)
 * @property gapDirection   smoothed gap column direction, -1..+1 (debug)
 * @property nearestObstacleMeters  closest detected obstacle, or null if nothing in range
 * @property clearance      per-column walkable fraction (length = numColumns), for debug viz
 * @property mask           the walkable mask this decision came from, for debug viz
 */
data class SteeringDecision(
    val command: Float,
    val proximity: Float,
    val gapDirection: Float,
    val nearestObstacleMeters: Float?,
    val clearance: FloatArray,
    val mask: WalkableMask,
    val reason: String,
    val timestampMs: Long,
    val depthPerColumn: FloatArray? = null, // per-column depth distance for debug viz; null if depth inactive
    val overrides: Int = 0,                 // # columns depth overrode this cycle
    val depthActive: Boolean = false,       // whether the depth pipeline is running
)

enum class Direction { CLEAR, LEFT, RIGHT, NEUTRAL }

val SteeringDecision.direction: Direction
    get() = when {
        proximity <= 0f -> Direction.CLEAR
        command < -0.1f -> Direction.LEFT
        command > 0.1f -> Direction.RIGHT
        else -> Direction.NEUTRAL
    }
