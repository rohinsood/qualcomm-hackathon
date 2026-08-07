package dev.quad.shepherd.pose

import android.content.Context
import android.media.Image
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableException
import dev.quad.shepherd.Loadout
import dev.quad.shepherd.world.Pose2d
import java.nio.ByteOrder

/**
 * ARCore as the areamap's perception backbone: 6-DoF pose to stamp
 * obstacles against, and metric depth to stamp.
 *
 * Runs headless on its own thread behind an [OffscreenGl] context, so
 * guidance survives the activity going away. The frame rate is ARCore's
 * own — `UpdateMode.BLOCKING` parks the thread until the camera produces
 * a frame, which is both accurate and cheaper than polling.
 *
 * ## Why this replaces the model stack
 *
 * ARCore's depth is **metric by construction**, so the
 * [dev.quad.shepherd.guidance.DepthCalibrator] scale estimate — which
 * needed YOLO boxes and a pinhole height prior to bootstrap — is not
 * needed at all. Its horizontal planes give a *measured* floor, replacing
 * the percentile self-calibration that latched onto a desk surface and
 * turned a whole room into an obstacle field.
 *
 * ## What it costs
 *
 * ARCore depth is motion stereo. It needs texture and parallax, so a blank
 * wall in still air is exactly where it is weakest — and exactly the
 * obstacle that matters. The cane's Modulino ray covers the near field
 * regardless, and [Loadout.MONO_DEPTH] can be switched back on to run
 * Depth-Anything alongside.
 *
 * Tracking also stops when the camera is covered or the phone goes in a
 * pocket. That is reported honestly through [Listener.onTrackingLost]
 * rather than papered over: the map freezes rather than accumulating
 * evidence against a pose nobody believes.
 */
class ArCoreTracker(private val appContext: Context) {

    companion object {
        private const val TAG = "ArCoreTracker"

        /**
         * A pose discontinuity larger than this is relocalisation, not
         * walking. Nobody covers 3.5 m between two 30 Hz camera frames,
         * and integrating depth across the jump smears a second copy of
         * the world into the map.
         */
        const val JUMP_THRESHOLD_M = 3.5

        /**
         * Jumps are ignored for this long after tracking starts. ARCore
         * refines its world estimate hard while it initialises, and a
         * correction then is normal rather than a relocalisation — the
         * first field run dropped the map 26 s in for exactly this.
         * There is nothing in the map worth protecting that early anyway.
         */
        const val SETTLE_MS = 8_000L

        /** Fallback if no floor plane has been found yet. */
        const val DEFAULT_CAMERA_HEIGHT_M = 1.4f

        /**
         * ARCore's DEPTH16 buffer. Android's format spec puts depth in the
         * low 13 bits and confidence in the top 3, but ARCore's smooth
         * depth documents the whole 16 bits as millimetres. Reading the
         * full value is the SAFE reading of that ambiguity: if the top
         * bits did turn out to hold confidence, a reading would inflate to
         * tens of metres and be discarded as out of range — a lost
         * measurement. Masking instead would alias anything past 8.19 m
         * down into the near field and invent an obstacle at 1.8 m.
         * Losing a reading is survivable; hallucinating a near obstacle in
         * a blind-navigation aid is not.
         */
        const val DEPTH_MAX_VALID_MM = 12_000
    }

    interface Listener {
        /** A tracked frame: pose plus, when available, metric depth. */
        fun onArFrame(
            pose: Pose2d,
            pitchRad: Float,
            rollRad: Float,
            depthMeters: FloatArray?,
            depthWidth: Int,
            depthHeight: Int,
            depthFx: Float,
        )

        /** Tracking stopped; dead reckoning should take over. */
        fun onTrackingLost(reason: String)

        /** Tracking resumed in a NEW world frame — drop the map. */
        fun onEpochChanged(epoch: Int)
    }

    @Volatile var listener: Listener? = null

    @Volatile var available = false
        private set

    @Volatile var tracking = false
        private set

    @Volatile var status: String = "not started"
        private set

    @Volatile var depthSupported = false
        private set

    /** Camera height above the measured floor; falls back to a guess. */
    @Volatile var cameraHeightM = DEFAULT_CAMERA_HEIGHT_M
        private set

    @Volatile var epoch = 0
        private set

    private var session: Session? = null
    private val gl = OffscreenGl()
    private var thread: Thread? = null
    @Volatile private var running = false

    private var lastTx = Float.NaN
    private var lastTz = Float.NaN
    private var trackingSinceMs = 0L
    private var groundY = Float.NaN
    private var lastPlaneScanMs = 0L

    /**
     * Bring ARCore up. Returns false when it is unavailable or disabled —
     * the caller keeps navigating by compass either way.
     *
     * ARCore may need installing or updating, which only an Activity can
     * prompt for; here we simply decline rather than pretending.
     */
    fun start(): Boolean {
        if (!Loadout.ARCORE) {
            status = "disabled by Loadout.ARCORE"
            Log.i(TAG, status)
            return false
        }
        if (running) return available

        val availability = try {
            ArCoreApk.getInstance().checkAvailability(appContext)
        } catch (e: Exception) {
            status = "availability check failed: ${e.message}"
            Log.w(TAG, status, e)
            return false
        }
        if (!availability.isSupported) {
            status = "ARCore unsupported on this device ($availability)"
            Log.w(TAG, status)
            return false
        }
        if (availability != ArCoreApk.Availability.SUPPORTED_INSTALLED) {
            status = "ARCore needs installing/updating ($availability)"
            Log.w(TAG, status)
            return false
        }

        running = true
        thread = Thread({ loop() }, "arcore").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
        return true
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }

    // ---- the update loop -------------------------------------------------

    private fun loop() {
        if (!gl.create()) {
            status = "offscreen GL failed"
            running = false
            return
        }
        val s = try {
            Session(appContext).also { configure(it) }
        } catch (e: UnavailableException) {
            status = "session unavailable: ${e.message}"
            Log.w(TAG, status, e)
            gl.release()
            running = false
            return
        } catch (e: Exception) {
            status = "session failed: ${e.message}"
            Log.e(TAG, status, e)
            gl.release()
            running = false
            return
        }
        session = s
        s.setCameraTextureName(gl.textureId)
        try {
            s.resume()
        } catch (e: CameraNotAvailableException) {
            status = "camera unavailable: ${e.message}"
            Log.e(TAG, status, e)
            teardown()
            return
        }
        available = true
        status = "tracking" + if (depthSupported) " +depth" else " (no depth)"
        Log.i(TAG, "ARCore up, depth supported=$depthSupported")

        while (running) {
            val frame = try {
                s.update()
            } catch (e: CameraNotAvailableException) {
                Log.w(TAG, "camera lost", e)
                markLost("camera unavailable")
                break
            } catch (e: Exception) {
                Log.w(TAG, "update failed", e)
                markLost(e.message ?: "update failed")
                continue
            }
            handleFrame(frame)
        }
        teardown()
    }

    private fun configure(s: Session) {
        val config = Config(s)
        depthSupported = s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        config.depthMode =
            if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        // Horizontal planes give a measured floor. Vertical plane finding
        // would cost more and tell us nothing the depth map does not.
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
        // Park the thread until a frame exists rather than spinning.
        config.updateMode = Config.UpdateMode.BLOCKING
        config.lightEstimationMode = Config.LightEstimationMode.DISABLED
        config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
        s.configure(config)
    }

    private fun handleFrame(frame: Frame) {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            if (tracking) {
                markLost(camera.trackingFailureReason.toString())
            }
            return
        }

        val pose = camera.pose
        val tx = pose.tx()
        val ty = pose.ty()
        val tz = pose.tz()

        if (!tracking) {
            tracking = true
            trackingSinceMs = SystemClock.elapsedRealtime()
            status = "tracking"
        }

        // Relocalisation check BEFORE anything is stamped.
        //
        // Dropping the map is the safe response, not the clever one: a
        // correction of this size means every cell already stamped is
        // metres out of place, and a map that is confidently wrong steers
        // someone into what it thinks is clear. The map rebuilds in
        // seconds, so the cost of dropping it is small. (The better fix
        // is to carry a cumulative pose offset so the map keeps its own
        // self-consistent frame and only the route needs re-projecting —
        // worth doing if corrections turn out to be common in the field.)
        val settled = SystemClock.elapsedRealtime() - trackingSinceMs > SETTLE_MS
        if (!lastTx.isNaN()) {
            val jump = ArPoseMath.planarJumpM(lastTx, lastTz, tx, tz)
            if (jump > JUMP_THRESHOLD_M) {
                if (settled) {
                    epoch++
                    Log.w(TAG, "pose jumped %.1f m - new epoch %d".format(jump, epoch))
                    listener?.onEpochChanged(epoch)
                    groundY = Float.NaN
                } else {
                    Log.i(TAG, "pose jumped %.1f m while settling - ignored".format(jump))
                }
            }
        }
        lastTx = tx
        lastTz = tz

        val zAxis = pose.zAxis
        val xAxis = pose.xAxis
        val yAxis = pose.yAxis

        updateGround(frame, ty)
        val height = if (groundY.isNaN()) DEFAULT_CAMERA_HEIGHT_M else (ty - groundY)
        cameraHeightM = height.coerceIn(0.3f, 2.5f)

        val p = Pose2d(
            x = ArPoseMath.planarX(tx),
            y = ArPoseMath.planarY(tz),
            bearingRad = ArPoseMath.bearingRad(zAxis),
            heightM = cameraHeightM,
            timestampNs = SystemClock.elapsedRealtimeNanos(),
            confidence = 1f,
            epoch = epoch,
        )

        var depth: FloatArray? = null
        var dw = 0
        var dh = 0
        var fx = 0f
        if (depthSupported) {
            try {
                frame.acquireDepthImage16Bits().use { img ->
                    dw = img.width
                    dh = img.height
                    depth = toMetres(img)
                    val intr = camera.imageIntrinsics
                    fx = ArPoseMath.scaleFocalLength(
                        intr.focalLength[0], intr.imageDimensions[0], dw,
                    )
                }
            } catch (e: NotYetAvailableException) {
                // Normal early on: depth needs a little parallax first
            } catch (e: Exception) {
                Log.w(TAG, "depth acquire failed", e)
            }
        }

        listener?.onArFrame(
            p,
            ArPoseMath.pitchRad(zAxis),
            ArPoseMath.rollRad(xAxis, yAxis),
            depth, dw, dh, fx,
        )
    }

    /**
     * Take the lowest large horizontal upward-facing plane as the floor.
     * Rescanned at 1 Hz — planes are not free, and floors do not move.
     */
    private fun updateGround(frame: Frame, cameraY: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlaneScanMs < 1000L) return
        lastPlaneScanMs = now
        val session = this.session ?: return
        var best = Float.NaN
        for (plane in session.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState != TrackingState.TRACKING) continue
            if (plane.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
            // Ignore table tops: the floor is below the camera, by a lot
            val y = plane.centerPose.ty()
            if (cameraY - y < 0.6f) continue
            if (best.isNaN() || y < best) best = y
        }
        if (!best.isNaN()) {
            groundY = if (groundY.isNaN()) best else groundY + 0.2f * (best - groundY)
        }
    }

    /**
     * DEPTH16 -> metres, with invalid samples as NaN so every downstream
     * consumer's `isFinite` gate degrades to "no reading" rather than to
     * a confident zero.
     */
    private fun toMetres(img: Image): FloatArray {
        val plane = img.planes[0]
        val buf = plane.buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val w = img.width
        val h = img.height
        val strideShorts = plane.rowStride / 2
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val row = y * strideShorts
            val dst = y * w
            for (x in 0 until w) {
                val raw = buf.get(row + x).toInt() and 0xFFFF
                out[dst + x] = if (raw == 0 || raw > DEPTH_MAX_VALID_MM) {
                    Float.NaN
                } else {
                    raw / 1000f
                }
            }
        }
        return out
    }

    private fun markLost(reason: String) {
        if (tracking) {
            tracking = false
            status = "tracking lost: $reason"
            Log.w(TAG, status)
            listener?.onTrackingLost(reason)
        }
        lastTx = Float.NaN
        lastTz = Float.NaN
    }

    private fun teardown() {
        markLost("stopped")
        available = false
        try {
            session?.pause()
        } catch (e: Exception) {
            Log.w(TAG, "pause failed", e)
        }
        session?.close()
        session = null
        gl.release()
        running = false
        status = "stopped"
    }
}
