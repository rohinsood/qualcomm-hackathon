package com.wayfinder.app.output.speech

import android.os.SystemClock
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
 * Throttles + dedups spoken guidance. Per the architecture: speak at most once per
 * ~speechMinIntervalMs, suppress repeats of the same bucket, and let CAUTION
 * messages interrupt. This is what keeps the app from being annoyingly chatty
 * while still surfacing changes that matter.
 *
 * v1 uses templated messages; M4 swaps these for SLM-generated narration.
 */
class SpeechGate(
    private val tts: TtsController,
    private val store: DecisionStore,
    private val tunables: Tunables,
    private val isFailSafe: () -> Boolean,
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null

    private var lastSpokenMs = 0L
    private var lastBucket: String? = null
    private var lastFailSafeSpeakMs = 0L

    fun start() {
        if (job != null) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun CoroutineScope.loop() {
        while (isActive) {
            val now = SystemClock.elapsedRealtime()

            if (isFailSafe()) {
                if (now - lastFailSafeSpeakMs > FAILSAFE_REPEAT_MS) {
                    tts.speak("Guidance paused. Caution.", priority = true)
                    lastFailSafeSpeakMs = now
                    lastSpokenMs = now
                    lastBucket = FAILSAFE_BUCKET
                }
                delay(200)
                continue
            }

            val decision = store.latest()
            if (decision != null) {
                val msg = messageFor(decision.direction, decision.nearestObstacleMeters)
                if (msg != null) {
                    val bucketChanged = msg.bucket != lastBucket
                    val intervalElapsed = now - lastSpokenMs > tunables.speechMinIntervalMs
                    if (bucketChanged || (msg.priority && intervalElapsed)) {
                        tts.speak(msg.text, priority = msg.priority)
                        lastBucket = msg.bucket
                        lastSpokenMs = now
                    }
                }
            }
            delay(200)
        }
    }

    private data class Msg(val text: String, val bucket: String, val priority: Boolean)

    private fun messageFor(dir: Direction, nearest: Float?): Msg? = when (dir) {
        Direction.CLEAR -> null // heartbeat haptic covers "clear"; stay silent to avoid chatter
        Direction.LEFT -> {
            val close = (nearest != null && nearest < 0.7f)
            if (close) Msg("Obstacle right. Steer left.", "left-close", priority = true)
            else Msg("Steer left.", "left", priority = false)
        }
        Direction.RIGHT -> {
            val close = (nearest != null && nearest < 0.7f)
            if (close) Msg("Obstacle left. Steer right.", "right-close", priority = true)
            else Msg("Steer right.", "right", priority = false)
        }
        Direction.NEUTRAL -> {
            val close = (nearest != null && nearest < 0.7f)
            if (close) Msg("Stop. Obstacle ahead.", "ahead-close", priority = true)
            else null
        }
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    companion object {
        private const val FAILSAFE_BUCKET = "failsafe"
        private const val FAILSAFE_REPEAT_MS = 3000L
    }
}
