package com.wayfinder.app.output.haptics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.wayfinder.app.core.config.Tunables
import com.wayfinder.app.core.loop.DecisionStore
import com.wayfinder.app.core.model.Direction
import com.wayfinder.app.core.model.direction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The "actuator" of our navigation aid (we have no motor; the S25 Ultra has one
 * vibration motor). Encodes DIRECTION as a waveform pattern and PROXIMITY as the
 * pulse cadence:
 *
 *   LEFT     = two quick pulses
 *   RIGHT    = one long pulse
 *   NEUTRAL  = one short pulse
 *   CLEAR    = a slow, soft heartbeat (liveness — never fully silent)
 *   FAILSAFE = urgent repeating double-pulse + speech handles the warning
 *
 * Pulsing continues even when speech is silent, so the user always has a heartbeat.
 */
class HapticLoop(
    context: Context,
    private val store: DecisionStore,
    private val tunables: Tunables,
    private val isFailSafe: () -> Boolean,
) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        vibrator.cancel()
    }

    private suspend fun CoroutineScope.loop() {
        val pollMs = (1000f / tunables.hapticPollHz).toLong().coerceAtLeast(10)
        var nextPulseAt = 0L
        while (isActive) {
            val now = SystemClock.elapsedRealtime()
            if (now >= nextPulseAt) {
                val decision = store.latest()
                when {
                    isFailSafe() -> {
                        vibrate(WAVE_FAILSAFE)
                        nextPulseAt = now + 450
                    }
                    decision == null || decision.proximity <= 0f -> {
                        vibrate(WAVE_HEARTBEAT)
                        nextPulseAt = now + tunables.clearPulseIntervalMs
                    }
                    else -> {
                        vibrate(waveForDirection(decision.direction))
                        nextPulseAt = now + DistanceToCadence.intervalMs(decision.nearestObstacleMeters, tunables)
                    }
                }
            }
            delay(pollMs)
        }
    }

    private fun waveForDirection(dir: Direction): VibrationEffect =
        when (dir) {
            Direction.LEFT -> VibrationEffect.createWaveform(T_LEFT, A_LEFT, -1)
            Direction.RIGHT -> VibrationEffect.createWaveform(T_RIGHT, A_RIGHT, -1)
            Direction.NEUTRAL -> VibrationEffect.createWaveform(T_NEUTRAL, A_NEUTRAL, -1)
            Direction.CLEAR -> VibrationEffect.createWaveform(T_HEARTBEAT, A_HEARTBEAT, -1)
        }

    private fun vibrate(effect: VibrationEffect) {
        if (vibrator.hasVibrator()) vibrator.vibrate(effect)
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    companion object {
        // timings (ms) and amplitudes (0..255) pairs — kept gentle to avoid a "heavy" feel
        private val T_LEFT = longArrayOf(0, 35, 70, 35); private val A_LEFT = intArrayOf(0, 170, 0, 170)
        private val T_RIGHT = longArrayOf(0, 140); private val A_RIGHT = intArrayOf(0, 170)
        private val T_NEUTRAL = longArrayOf(0, 55); private val A_NEUTRAL = intArrayOf(0, 150)
        private val T_HEARTBEAT = longArrayOf(0, 20); private val A_HEARTBEAT = intArrayOf(0, 80)
        private val T_FAILSAFE = longArrayOf(0, 90, 70, 90); private val A_FAILSAFE = intArrayOf(0, 255, 0, 255)
        private val WAVE_FAILSAFE = VibrationEffect.createWaveform(T_FAILSAFE, A_FAILSAFE, -1)
        private val WAVE_HEARTBEAT = VibrationEffect.createWaveform(T_HEARTBEAT, A_HEARTBEAT, -1)
    }
}
