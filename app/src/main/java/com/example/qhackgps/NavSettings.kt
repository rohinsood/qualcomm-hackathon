package com.example.qhackgps

import android.content.Context

/**
 * The knobs the HUD exposes. Persisted because each is set once and then
 * relied on: a heading trim found by walking a straight line shouldn't have to
 * be re-found every time the app restarts mid-demo, and neither should the
 * choice of routing mode or whether the camera scan runs.
 */
object NavSettings {
    private const val PREFS = "qhackgps_settings"
    private const val KEY_HEADING_OFFSET = "heading_offset_deg"
    private const val KEY_ROAD_ROUTING = "road_routing"
    private const val KEY_OBSTACLE_SCAN = "obstacle_scan"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Manual compass trim in degrees, (-180, 180]. 0 = raw sensor. */
    fun headingOffset(context: Context): Float =
        prefs(context).getFloat(KEY_HEADING_OFFSET, 0f)

    fun setHeadingOffset(context: Context, deg: Float) {
        prefs(context).edit().putFloat(KEY_HEADING_OFFSET, deg).apply()
    }

    /** True = Google walking directions, false = straight line to the destination. */
    fun roadRouting(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ROAD_ROUTING, true)

    fun setRoadRouting(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ROAD_ROUTING, enabled).apply()
    }

    /** True = camera obstacle scan (v3 screen-thirds guidance) is on. Off by default. */
    fun obstacleScan(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OBSTACLE_SCAN, false)

    fun setObstacleScan(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_OBSTACLE_SCAN, enabled).apply()
    }
}
