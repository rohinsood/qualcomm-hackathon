package dev.quad.shepherd.feedback

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import dev.quad.shepherd.actuator.CaneCommand
import dev.quad.shepherd.guidance.GuidanceEngine

/**
 * Direction-coded haptics (the phone has one actuator, so direction is a
 * pattern, not a side):
 *
 *  - LEFT  = two short taps        ("ta-ta")
 *  - RIGHT = one long buzz         ("taaaa")
 *  - STOP  = two strong long buzzes
 *  - straight under caution = single short tick, cadence rising as the
 *    obstacle gets closer (parking-sensor style)
 *  - straight on a clear path = silence; with navigation active, gentle
 *    LEFT/RIGHT hints fire when the route wants a turn
 *
 * Fed with the FUSED guidance (obstacles + route), so what you feel is
 * what the cane wheel would do.
 */
class HapticFeedback(context: Context) {

    companion object {
        private const val NAV_HINT_INTERVAL_MS = 1500L
        private const val DANGER_INTERVAL_MS = 700L
    }

    private val vibrator =
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
            .defaultVibrator

    private var lastPulseAt = 0L

    fun update(guidance: GuidanceEngine.Guidance) {
        val command = CaneCommand.from(guidance)
        val now = SystemClock.elapsedRealtime()

        val interval = when {
            guidance.severity == GuidanceEngine.Severity.DANGER -> DANGER_INTERVAL_MS
            guidance.severity == GuidanceEngine.Severity.CAUTION -> {
                val d = guidance.nearestDistanceMeters ?: 3f
                (d * 400f).toLong().coerceIn(300L, 1500L)
            }
            command.direction != CaneCommand.Direction.STRAIGHT -> NAV_HINT_INTERVAL_MS
            else -> return // clear path, no turn wanted: stay quiet
        }
        if (now - lastPulseAt < interval) return
        lastPulseAt = now

        val effect = when (command.direction) {
            CaneCommand.Direction.STOP ->
                VibrationEffect.createWaveform(longArrayOf(0, 300, 120, 300), -1)
            CaneCommand.Direction.LEFT ->
                VibrationEffect.createWaveform(longArrayOf(0, 70, 90, 70), -1)
            CaneCommand.Direction.RIGHT ->
                VibrationEffect.createOneShot(260L, VibrationEffect.DEFAULT_AMPLITUDE)
            CaneCommand.Direction.STRAIGHT ->
                if (guidance.severity == GuidanceEngine.Severity.DANGER) {
                    VibrationEffect.createOneShot(300L, VibrationEffect.DEFAULT_AMPLITUDE)
                } else {
                    VibrationEffect.createOneShot(80L, 160)
                }
        }
        vibrator.vibrate(effect)
    }
}
