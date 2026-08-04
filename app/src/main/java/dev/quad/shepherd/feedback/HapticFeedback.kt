package dev.quad.shepherd.feedback

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import dev.quad.shepherd.guidance.GuidanceEngine

/**
 * Parking-sensor style haptics: pulses speed up as the nearest obstacle
 * gets closer; DANGER produces a long strong buzz.
 */
class HapticFeedback(context: Context) {

    private val vibrator =
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
            .defaultVibrator

    private var lastPulseAt = 0L

    fun update(guidance: GuidanceEngine.Guidance) {
        val distance = guidance.nearest?.distanceMeters ?: return
        if (guidance.severity == GuidanceEngine.Severity.CLEAR) return

        val now = SystemClock.elapsedRealtime()
        // Pulse interval scales with distance: 3m -> ~1.2s, 1m -> ~400ms
        val interval = (distance * 400f).toLong().coerceIn(150L, 1500L)
        if (now - lastPulseAt < interval) return
        lastPulseAt = now

        val effect = when (guidance.severity) {
            GuidanceEngine.Severity.DANGER ->
                VibrationEffect.createOneShot(300L, VibrationEffect.DEFAULT_AMPLITUDE)
            else ->
                VibrationEffect.createOneShot(80L, 160)
        }
        vibrator.vibrate(effect)
    }
}
