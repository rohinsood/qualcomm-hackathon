package com.example.qhackgps.haptics

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi

/**
 * The "stop!" buzz.
 *
 * While the cane reports something in the way we vibrate a repeating triple
 * pulse until the path is clear. The user is walking and may not be looking at
 * the screen, so the phone itself has to be the alarm: three sharp pulses then
 * a gap, over and over, which reads as *stop* rather than as a notification.
 *
 * Plays with alarm attributes so it still fires when the ringer is silenced.
 */
class ObstacleHaptics(context: Context) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private var buzzing = false

    val isAvailable: Boolean
        get() = vibrator?.hasVibrator() == true

    /** Idempotent: start (or keep) the repeating stop alert. */
    @Synchronized
    fun startStopAlert() {
        val v = vibrator ?: return
        if (buzzing || !v.hasVibrator()) return
        buzzing = true
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    v.vibrate(stopWaveform(), VIBRATION_ALARM_ATTRIBUTES)

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                    @Suppress("DEPRECATION")
                    v.vibrate(stopWaveform(), AUDIO_ALARM_ATTRIBUTES)

                else ->
                    @Suppress("DEPRECATION")
                    v.vibrate(STOP_PATTERN, REPEAT_FROM, AUDIO_ALARM_ATTRIBUTES)
            }
        } catch (_: Exception) {
            buzzing = false
        }
    }

    /** Idempotent: silence the alert (path clear, screen gone, app closing). */
    @Synchronized
    fun stop() {
        if (!buzzing) return
        buzzing = false
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
    }

    companion object {
        /** wait, buzz, gap, buzz, gap, buzz, long gap — then repeat. */
        private val STOP_PATTERN = longArrayOf(0, 250, 120, 250, 120, 250, 600)
        private val STOP_AMPLITUDES = intArrayOf(0, 255, 0, 255, 0, 255, 0)
        private const val REPEAT_FROM = 0

        @RequiresApi(Build.VERSION_CODES.O)
        private fun stopWaveform(): VibrationEffect =
            VibrationEffect.createWaveform(STOP_PATTERN, STOP_AMPLITUDES, REPEAT_FROM)

        /** Alarm usage so the buzz survives silent mode / Do Not Disturb. */
        private val AUDIO_ALARM_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        @get:RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private val VIBRATION_ALARM_ATTRIBUTES: VibrationAttributes
            get() = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_ALARM)
                .build()
    }
}
