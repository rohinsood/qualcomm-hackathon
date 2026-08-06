package com.wayfinder.app.output.haptics

import com.wayfinder.app.core.config.Tunables

/**
 * Maps an obstacle distance (meters) to a haptic pulse interval. Direct port of
 * Shepherd's ESP32 `distanceToPulseIntervalMs` idea — closer obstacle = faster
 * pulses — but rescaled to real meters instead of the BLE distance field.
 *
 *   minRange            → nearPulseIntervalMs   (fast, urgent)
 *   sensitivityMeters   → farPulseIntervalMs    (slow)
 *   null (all clear)    → clearPulseIntervalMs  (calm heartbeat)
 */
object DistanceToCadence {
    fun intervalMs(meters: Float?, t: Tunables): Long {
        if (meters == null) return t.clearPulseIntervalMs
        val span = (t.sensitivityMeters - t.minRangeMeters).coerceAtLeast(0.0001f)
        val frac = ((meters - t.minRangeMeters) / span).coerceIn(0f, 1f) // 0 near .. 1 far
        return (t.nearPulseIntervalMs + frac * (t.farPulseIntervalMs - t.nearPulseIntervalMs)).toLong()
    }
}
