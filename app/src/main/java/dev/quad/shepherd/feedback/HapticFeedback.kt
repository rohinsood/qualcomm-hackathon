package dev.quad.shepherd.feedback

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * Haptics carry exactly ONE message now: STOP — two strong long buzzes —
 * fired only when the cane's short-range distance Modulino (over BLE)
 * reports an obstacle. All directional/planner-driven patterns were
 * removed on user request: steering is the wheel's and SteerView's job,
 * so a buzz always means "something is right in front of the cane".
 */
class HapticFeedback(context: Context) {

    companion object {
        /** Re-buzz cadence while the sensor keeps seeing the obstacle. */
        private const val REPEAT_MS = 1200L
    }

    private val vibrator =
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
            .defaultVibrator

    private var lastPulseAt = 0L

    /** Cane sensor sees an obstacle: STOP buzz, repeated while it stays. */
    fun caneStop() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPulseAt < REPEAT_MS) return
        lastPulseAt = now
        vibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 300, 120, 300), -1)
        )
    }
}
