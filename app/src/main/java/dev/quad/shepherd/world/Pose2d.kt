package dev.quad.shepherd.world

/**
 * The user's planar pose in the **AR world frame** — the frame the
 * areamap is stored in.
 *
 * ARCore's world frame is gravity-aligned (Y up) with an arbitrary but
 * *rigid* horizontal orientation fixed at session start. Flattening it,
 * the two map axes are:
 *
 *  - [x] — ARCore's +X: where the camera's right pointed at session start
 *  - [y] — ARCore's -Z: where the camera looked at session start
 *
 * so a bearing within this frame is `atan2(x, y)`, clockwise-positive
 * from +Y, exactly like a compass bearing but about a local "north" that
 * happens to be wherever the session began. [WorldAnchor] converts to
 * true east/north when the route needs it.
 *
 * Storing the map in this frame rather than in ENU is deliberate: the AR
 * frame is internally consistent forever, so obstacle evidence
 * accumulated ten seconds ago still lines up with evidence from now. A
 * GPS-derived ENU frame is not — every drift correction would silently
 * displace every cell already stamped.
 *
 * Pure Kotlin for JVM unit testing.
 */
data class Pose2d(
    val x: Double,
    val y: Double,
    /** Camera bearing in the AR frame: clockwise from +Y, radians. */
    val bearingRad: Float,
    /** Camera height above the estimated ground plane, metres. */
    val heightM: Float,
    /** `SystemClock.elapsedRealtimeNanos` when this pose was observed. */
    val timestampNs: Long,
    /** 1 = ARCore tracking; degrades through dead reckoning to 0. */
    val confidence: Float,
    /**
     * Bumped whenever tracking restarts into a **new** world frame. Every
     * cell stamped under an older epoch is meaningless — the map must be
     * dropped or re-anchored when this changes, and silently carrying on
     * would paint ghost obstacles across the new frame.
     */
    val epoch: Int,
) {
    val bearingDeg: Float get() = Math.toDegrees(bearingRad.toDouble()).toFloat()

    /** Planar distance to another pose, metres. */
    fun distanceTo(other: Pose2d): Double = Math.hypot(other.x - x, other.y - y)
}
