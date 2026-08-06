package com.wayfinder.app.core.config

/**
 * Single source of all tunable parameters. Drives the gap-seeker, columnizer,
 * haptics, speech, and safety subsystems.
 *
 * Deliberately a **class with `var` fields** (not an immutable data class): ONE
 * shared instance is passed to every subsystem, so the debug sliders in the UI can
 * mutate a field and every loop sees the new value on its next read — live tuning,
 * exactly how Shepherd got "from works to feels-good."
 *
 * Reads happen on background (loop) threads; writes from the UI thread. 32-bit
 * field reads/writes are atomic on the JVM; for a debug tool this is acceptable.
 */
class Tunables(
    // ---- Gap-seeker (port of Shepherd §8) ----
    var numColumns: Int = 16,
    var sensitivityMeters: Float = 2.0f,   // start steering when nearest obstacle < this
    var minRangeMeters: Float = 0.2f,      // proximity = 1.0 here
    var proximityExponent: Float = 0.6f,   // <1 ramps faster near the sensitivity threshold
    var gapExponent: Float = 0.33f,        // cube-root boost so moderate gaps move output
    var closeFloor: Float = 0.5f,          // min |command| enforced when something is < closeObstacleMeters
    var closeObstacleMeters: Float = 1.0f,
    var gapHistorySize: Int = 5,           // moving-average window on gapDirection

    // ---- Columnizer (mask → clearance + reach) ----
    var verticalBandStart: Float = 0.35f,  // body-height band (fraction of mask height)
    var verticalBandEnd: Float = 0.65f,
    var maxRangeMeters: Float = 6.0f,      // reach→distance clamp; TODO calibrate on-device

    // ---- Haptics ----
    var hapticPollHz: Float = 20f,
    var nearPulseIntervalMs: Long = 450,   // pulse cadence at minRange (calm ~2 Hz, not a buzz)
    var farPulseIntervalMs: Long = 2200,   // pulse cadence near the sensitivity edge
    var clearPulseIntervalMs: Long = 4000, // "all clear" heartbeat

    // ---- Speech ----
    var speechMinIntervalMs: Long = 3000,  // don't repeat guidance more often than this (avoid chatter)

    // ---- Depth layer (M3) ----
    var depthEnabled: Boolean = true,          // master toggle for the depth pipeline
    var depthEveryNFrames: Int = 4,            // run depth every Nth inference cycle (low rate)
    var depthRoiTriggerMeters: Float = 1.0f,   // also run depth now when seg nearest < this
    var depthOverrideMeters: Float = 1.0f,     // safety-override: suppress columns closer than this

    // ---- Safety ----
    var decisionTimeoutMs: Long = 3000,    // no fresh decision for this long → fail-safe (tolerant of CPU/GPU jitter)
    var cameraTimeoutMs: Long = 3000,      // no camera frame for this long → fail-safe
)
