package com.wayfinder.app.core.safety

import android.os.SystemClock
import com.wayfinder.app.core.config.Tunables
import com.wayfinder.app.core.loop.DecisionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Safety watchdog. This is a navigation aid — it must NEVER silently output a
 * stale "all clear." If the decision stream or camera stalls (model crash, NPU
 * hang, camera disconnect), the watchdog flips the system into [FailSafe] and the
 * output loops switch to continuous alert.
 *
 * Mirrors Shepherd's dual watchdogs (iOS frame watchdog 500ms, ESP32 packet 250ms).
 */
class Watchdog(
    private val store: DecisionStore,
    private val tunables: Tunables,
    private val onChange: (FailSafe) -> Unit,
) {
    enum class FailSafe { NOMINAL, DECISION_STALE, CAMERA_STALE }

    @Volatile var lastCameraFrameMs: Long = 0L
        private set
    @Volatile private var state = FailSafe.NOMINAL

    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null

    /** Called by the camera analyzer on every frame. */
    fun beatCamera() {
        lastCameraFrameMs = SystemClock.elapsedRealtime()
    }

    fun isFailSafe(): Boolean = state != FailSafe.NOMINAL

    fun currentState(): FailSafe = state

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                delay(100)
                val now = SystemClock.elapsedRealtime()
                val lastDecision = store.lastUpdateMs
                val lastCamera = lastCameraFrameMs

                val decisionStale = lastDecision == 0L || (now - lastDecision) > tunables.decisionTimeoutMs
                val cameraStale = lastCamera == 0L || (now - lastCamera) > tunables.cameraTimeoutMs

                val next = when {
                    cameraStale -> FailSafe.CAMERA_STALE
                    decisionStale -> FailSafe.DECISION_STALE
                    else -> FailSafe.NOMINAL
                }
                if (next != state) {
                    state = next
                    onChange(next)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        state = FailSafe.NOMINAL
    }
}
