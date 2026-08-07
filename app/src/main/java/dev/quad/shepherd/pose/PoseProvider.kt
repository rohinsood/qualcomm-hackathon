package dev.quad.shepherd.pose

import android.content.Context
import android.util.Log
import dev.quad.shepherd.world.Pose2d

/**
 * The single place anything asks "where is the user, and which way are
 * they facing" — ARCore when it is tracking, dead reckoning when it is
 * not, and honestly nothing at all when neither can answer.
 *
 * The map and the planner never learn which of the two produced a pose.
 * They only read [Pose2d.confidence], which is what decides how strongly
 * an observation is allowed to mark the map: a dead-reckoned pose still
 * steers, but it should not carve confident walls into a map that outlives
 * it.
 *
 * The other half of the job is **epochs**. ARCore can resume tracking
 * having decided it is somewhere else entirely, and every cell stamped
 * before that moment then refers to a frame that no longer exists.
 * Carrying on would paint a ghost copy of the world across the new one, so
 * an epoch change is propagated and the map is expected to drop itself.
 */
class PoseProvider(context: Context) {

    companion object {
        private const val TAG = "PoseProvider"
    }

    interface Listener {
        /**
         * A pose with, when the source had one, a depth frame to stamp
         * against it. Dead-reckoned poses arrive with no depth.
         */
        fun onPose(
            pose: Pose2d,
            pitchRad: Float,
            rollRad: Float,
            depthMeters: FloatArray?,
            depthWidth: Int,
            depthHeight: Int,
            depthFx: Float,
        )

        /** The AR frame restarted; everything stamped so far is void. */
        fun onEpochChanged(epoch: Int)
    }

    val arCore = ArCoreTracker(context)
    val pdr = PdrPose(context)

    @Volatile var listener: Listener? = null

    /** Most recent pose from either source, or null before the first fix. */
    @Volatile var pose: Pose2d? = null
        private set

    /** Where the current pose came from, for the status line. */
    @Volatile var sourceLabel: String = "none"
        private set

    val tracking: Boolean get() = arCore.tracking

    private val arListener = object : ArCoreTracker.Listener {
        override fun onArFrame(
            pose: Pose2d,
            pitchRad: Float,
            rollRad: Float,
            depthMeters: FloatArray?,
            depthWidth: Int,
            depthHeight: Int,
            depthFx: Float,
        ) {
            if (pdr.running) {
                // Handover back: ARCore is authoritative again
                pdr.release()
                Log.i(TAG, "ARCore recovered after %.1f m dead reckoning".format(pdr.travelledM))
            }
            this@PoseProvider.pose = pose
            sourceLabel = "arcore"
            listener?.onPose(
                pose, pitchRad, rollRad, depthMeters, depthWidth, depthHeight, depthFx,
            )
        }

        override fun onTrackingLost(reason: String) {
            // Hand the last believed pose to dead reckoning so guidance
            // keeps working across a pocket, a dark corridor, or a hand
            // over the lens.
            pose?.let {
                pdr.seedFrom(it)
                sourceLabel = "pdr"
                Log.i(TAG, "tracking lost ($reason) - dead reckoning from here")
            }
        }

        override fun onEpochChanged(epoch: Int) {
            pdr.release()
            listener?.onEpochChanged(epoch)
        }
    }

    fun start() {
        pdr.start()
        arCore.listener = arListener
        val ok = arCore.start()
        if (!ok) {
            Log.w(TAG, "ARCore unavailable: ${arCore.status}")
            sourceLabel = "pdr-only"
        }
    }

    fun stop() {
        arCore.stop()
        pdr.stop()
        arCore.listener = null
    }

    /**
     * Pump the dead-reckoned pose. ARCore pushes its own frames; PDR has
     * no natural cadence, so whoever owns the guidance loop calls this.
     *
     * @return the pose published, or null when nothing is believable.
     */
    fun tickDeadReckoning(): Pose2d? {
        if (arCore.tracking) return null
        val p = pdr.current() ?: run {
            if (sourceLabel != "lost") {
                sourceLabel = "lost"
                Log.w(TAG, "no believable pose - map stamping suspended")
            }
            return null
        }
        pose = p
        sourceLabel = "pdr"
        listener?.onPose(p, 0f, 0f, null, 0, 0, 0f)
        return p
    }

    /** One-line status for the HUD. */
    fun status(): String = when {
        arCore.tracking -> "pose arcore" + if (arCore.depthSupported) "+depth" else ""
        pdr.running -> "pose dead-reckoned %.0f m".format(pdr.travelledM)
        else -> "pose ${arCore.status}"
    }
}
