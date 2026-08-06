package com.wayfinder.app.core.loop

import android.os.SystemClock
import android.util.Log
import com.wayfinder.app.core.config.Tunables
import com.wayfinder.app.core.model.SteeringDecision
import com.wayfinder.app.perception.ModelRunner
import com.wayfinder.app.perception.columnize.ColumnSignal
import com.wayfinder.app.perception.columnize.Columnizer
import com.wayfinder.app.perception.depth.DepthFusion
import com.wayfinder.app.perception.depth.DepthRunner
import com.wayfinder.app.perception.depth.FusedSignal
import com.wayfinder.app.steering.GapSeeker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The perception→steering loop. Runs on a dedicated background dispatcher.
 *
 * Each cycle:
 *   1. Pull the latest frame from [FrameSlot] (drop if none).
 *   2. Run the segmentation [ModelRunner] → walkable mask → columnize → seg signal.
 *   3. Run the [DepthRunner] at a LOW rate and/or when the seg signal flags a near
 *      obstacle (ROI), feeding [DepthFusion].
 *   4. Fuse seg + depth → final [ColumnSignal] (seg direction, depth magnitude, override).
 *   5. Run the [GapSeeker] → steering command → publish [SteeringDecision].
 *
 * Depth is optional: when [depthRunner] is null or [Tunables.depthEnabled] is false,
 * fusion passes the seg signal straight through, so this degrades to the M1 behavior.
 *
 * @param onFrameProcessed heartbeat callback (feeds FPS / camera-freshness metrics).
 */
class SteeringLoop(
    private val frameSlot: FrameSlot,
    private val runner: ModelRunner,
    private val columnizer: Columnizer,
    private val gapSeeker: GapSeeker,
    private val decisionStore: DecisionStore,
    private val tunables: Tunables,
    private val depthRunner: DepthRunner? = null,
    private val onFrameProcessed: () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val fusion = DepthFusion(tunables)

    @Volatile var running = false
        private set

    @Volatile private var depthFrameCounter = 0
    @Volatile private var depthRunning = false // gate: at most one async depth job in flight

    fun start() {
        if (running) return
        running = true
        job = scope.launch {
            // Warm up on the background thread so heavy init (interpreter creation,
            // GPU shader compile) never blocks the UI thread / ANRs.
            try {
                runner.warmUp()
                depthRunner?.warmUp()
            } catch (t: Throwable) {
                Log.e(TAG, "Runner warmup failed", t)
            }
            while (isActive && running) {
                val frame = frameSlot.takeOrNull()
                if (frame == null) {
                    delay(5)
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                try {
                    val mask = runner.segment(frame)
                    val segSignal = columnizer.columnize(mask)

                    // Depth runs ASYNC (a child of this loop coroutine) so a slow CPU depth
                    // inference never stalls the per-frame decision cadence. Gated to one job
                    // at a time; the loop keeps publishing seg-based decisions at its own rate.
                    if (depthRunner != null && tunables.depthEnabled && !depthRunning && shouldRunDepth(segSignal)) {
                        depthRunning = true
                        val depthFrame = frame
                        launch {
                            try {
                                fusion.updateDepth(depthRunner.depth(depthFrame))
                            } catch (t: Throwable) {
                                Log.w(TAG, "depth inference failed", t)
                            } finally {
                                depthRunning = false
                            }
                        }
                    }

                    // Only fuse when depth is active; otherwise pass the seg signal straight
                    // through (so toggling depth off also drops any stale cached depth).
                    val fused = if (depthRunner != null && tunables.depthEnabled) {
                        fusion.fuse(segSignal)
                    } else {
                        FusedSignal(segSignal, 0, null)
                    }
                    val steer = gapSeeker.compute(fused.signal, now)
                    decisionStore.set(
                        SteeringDecision(
                            command = steer.command,
                            proximity = steer.proximity,
                            gapDirection = steer.gap,
                            nearestObstacleMeters = steer.nearestObstacleMeters,
                            clearance = fused.signal.clearance.copyOf(),
                            mask = mask,
                            reason = steer.reason,
                            timestampMs = now,
                            depthPerColumn = fused.depthPerColumn,
                            overrides = fused.overrideCount,
                            depthActive = depthRunner != null && tunables.depthEnabled,
                        )
                    )
                    onFrameProcessed()
                } catch (t: Throwable) {
                    // Model failure: do not publish a stale "all clear" — the watchdog
                    // will detect the stale decision and trip fail-safe.
                    Log.w(TAG, "inference cycle failed", t)
                }
            }
        }
    }

    /**
     * Depth scheduling: run every [Tunables.depthEveryNFrames] cycles, OR immediately
     * when segmentation already sees something within [Tunables.depthRoiTriggerMeters]
     * (ROI-triggered — spend the depth budget where it matters).
     */
    private fun shouldRunDepth(segSignal: ColumnSignal): Boolean {
        depthFrameCounter++
        val periodic = depthFrameCounter % tunables.depthEveryNFrames.coerceAtLeast(1) == 0
        val roi = segSignal.nearestObstacleMeters?.let { it < tunables.depthRoiTriggerMeters } ?: false
        return periodic || roi
    }

    fun stop() {
        running = false
        job?.cancel()
        decisionStore.clear()
    }

    companion object {
        private const val TAG = "SteeringLoop"
    }
}
