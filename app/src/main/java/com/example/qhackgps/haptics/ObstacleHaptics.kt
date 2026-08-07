package com.example.qhackgps.haptics

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.CombinedVibration
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

    private val manager: VibratorManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        } else null

    private val vibrator: Vibrator? = manager?.defaultVibrator
        ?: @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)

    private var buzzing = false

    val isAvailable: Boolean
        get() = vibrator?.hasVibrator() == true

    /** Idempotent: start (or keep) the repeating stop alert. */
    @Synchronized
    fun startStopAlert() {
        val v = vibrator ?: return
        if (buzzing || !v.hasVibrator()) return
        buzzing = true
        val mgr = manager
        try {
            when {
                // Drive *every* actuator on the device at once, at alarm usage.
                // This is the loudest a phone can legally buzz.
                mgr != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    mgr.vibrate(
                        CombinedVibration.createParallel(stopWaveform()),
                        VIBRATION_ALARM_ATTRIBUTES,
                    )

                mgr != null ->
                    mgr.vibrate(CombinedVibration.createParallel(stopWaveform()))

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
            manager?.cancel() ?: vibrator?.cancel()
        } catch (_: Exception) {
        }
    }

    companion object {
        /**
         * Maximum amplitude throughout, and mostly on: a 1.2 s slam to make you
         * plant your feet, then a relentless long-buzz/short-gap loop. The gaps
         * are only long enough to keep it feeling like an alarm rather than a
         * ringtone — nobody walks through this by accident.
         *
         * index:            0    1    2    3    4    5    6
         */
        private val STOP_PATTERN = longArrayOf(0, 1200, 110, 900, 110, 900, 220)
        private val STOP_AMPLITUDES = intArrayOf(0, 255, 0, 255, 0, 255, 0)

        /** Loop from index 3, so the long opening slam plays once. */
        private const val REPEAT_FROM = 3

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
