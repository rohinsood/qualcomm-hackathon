package com.example.qhackgps.guidance

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TurnDirection(val wire: String) {
    /** No destination set or no compass/GPS yet. */
    NONE("N"),
    /** Pointing the right way — the green light. */
    STRAIGHT("S"),
    LEFT("L"),
    RIGHT("R"),
}

/**
 * One snapshot of navigation guidance. This is the app's export contract:
 * [toWireLine] is what goes over Bluetooth to the Arduino, [toBroadcastIntent]
 * is what other apps/components receive.
 */
data class GuidanceUpdate(
    val direction: TurnDirection,
    /** Magnitude of the turn needed, degrees 0..180. 0 when there is no target. */
    val deltaDeg: Int,
    /** True when pointing within the alignment threshold (the green light). */
    val aligned: Boolean,
    /** Straight-line meters to the destination, -1 if no destination. */
    val distanceM: Int,
    /** Compass heading of the back camera, degrees 0..359 true north, -1 if unknown. */
    val headingDeg: Int,
    /** Bearing you should be pointing at, degrees 0..359 true north, -1 if none. */
    val bearingDeg: Int,
    val lat: Double,
    val lng: Double,
    val destLat: Double,
    val destLng: Double,
    /** True while the cane reports an object inside the obstacle threshold. */
    val obstaclePresent: Boolean = false,
    /** Latest cane distance in mm, -1 when unknown / nothing in range. */
    val obstacleMm: Int = -1,
) {
    /**
     * Serial frame for the Arduino, one ASCII line per frame:
     * `QG,<dir>,<deltaDeg>,<distanceM>,<headingDeg>,<bearingDeg>,<aligned>,<obst>,<obstMM>\n`
     * e.g. `QG,L,37,171,147,183,0,1,842` — turn left 37 deg, 171 m to go, not
     * aligned, obstacle 842 mm ahead. When an obstacle is present, `dir`/`deltaDeg`
     * already carry the avoidance turn, so old 6-field parsers stay correct.
     */
    fun toWireLine(): String =
        "QG,${direction.wire},$deltaDeg,$distanceM,$headingDeg,$bearingDeg," +
            "${if (aligned) 1 else 0},${if (obstaclePresent) 1 else 0},$obstacleMm\n"

    /** Broadcast for other apps. Register a runtime receiver for [ACTION_GUIDANCE]. */
    fun toBroadcastIntent(): Intent = Intent(ACTION_GUIDANCE)
        .putExtra(EXTRA_DIRECTION, direction.name)
        .putExtra(EXTRA_DELTA_DEG, deltaDeg)
        .putExtra(EXTRA_ALIGNED, aligned)
        .putExtra(EXTRA_DISTANCE_M, distanceM)
        .putExtra(EXTRA_HEADING_DEG, headingDeg)
        .putExtra(EXTRA_BEARING_DEG, bearingDeg)
        .putExtra(EXTRA_LAT, lat)
        .putExtra(EXTRA_LNG, lng)
        .putExtra(EXTRA_DEST_LAT, destLat)
        .putExtra(EXTRA_DEST_LNG, destLng)
        .putExtra(EXTRA_OBSTACLE, obstaclePresent)
        .putExtra(EXTRA_OBSTACLE_MM, obstacleMm)
        .putExtra(EXTRA_TIMESTAMP_MS, System.currentTimeMillis())

    companion object {
        const val ACTION_GUIDANCE = "com.example.qhackgps.GUIDANCE"
        const val EXTRA_DIRECTION = "direction"       // String: NONE|STRAIGHT|LEFT|RIGHT
        const val EXTRA_DELTA_DEG = "deltaDeg"        // Int
        const val EXTRA_ALIGNED = "aligned"           // Boolean
        const val EXTRA_DISTANCE_M = "distanceM"      // Int, -1 if none
        const val EXTRA_HEADING_DEG = "headingDeg"    // Int, -1 if unknown
        const val EXTRA_BEARING_DEG = "bearingDeg"    // Int, -1 if none
        const val EXTRA_LAT = "lat"                   // Double, NaN if unknown
        const val EXTRA_LNG = "lng"                   // Double, NaN if unknown
        const val EXTRA_DEST_LAT = "destLat"          // Double, NaN if none
        const val EXTRA_DEST_LNG = "destLng"          // Double, NaN if none
        const val EXTRA_OBSTACLE = "obstacle"         // Boolean, cane sees an object
        const val EXTRA_OBSTACLE_MM = "obstacleMm"    // Int, -1 unknown/none
        const val EXTRA_TIMESTAMP_MS = "timestampMs"  // Long, wall clock
    }
}

/**
 * In-process bus: the map screen publishes here; the Bluetooth link, the broadcast
 * exporter, and any future in-app component (camera, object detection, ...) observe it.
 */
object GuidanceBus {
    private val _updates = MutableStateFlow<GuidanceUpdate?>(null)
    val updates: StateFlow<GuidanceUpdate?> = _updates.asStateFlow()

    fun publish(update: GuidanceUpdate) {
        _updates.value = update
    }
}
