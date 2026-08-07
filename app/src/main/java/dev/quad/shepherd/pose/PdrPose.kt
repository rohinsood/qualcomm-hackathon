package dev.quad.shepherd.pose

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import dev.quad.shepherd.world.Angles
import dev.quad.shepherd.world.Pose2d
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pedestrian dead reckoning: the fallback for when ARCore stops tracking.
 *
 * ARCore needs a lit, textured scene and some parallax. A hand over the
 * lens, a dark stairwell, a phone slipped into a pocket — all of them stop
 * it, and all of them are ordinary things a user does mid-walk. Freezing
 * guidance at that moment is the worst possible behaviour, so steps and
 * gyro carry the pose until tracking comes back.
 *
 * It counts steps and turns rather than integrating accelerometer twice,
 * because double integration diverges in seconds while step counting
 * degrades at a few percent of distance.
 *
 * **Everything here is relative.** It is seeded from the last believed
 * ARCore pose and only ever applies *deltas* to it, so the result stays in
 * the AR world frame and drops straight into the same map. The rotation
 * vector's absolute azimuth is never used — only its change — which also
 * means the fixed offset between "where the phone's top points" and "where
 * the camera looks" cancels out, as long as the grip does not change.
 */
class PdrPose(context: Context) {

    companion object {
        /** Average walking step, metres. Tune per user if it matters. */
        const val DEFAULT_STEP_M = 0.72f

        /**
         * Confidence falls to zero over this much dead-reckoned distance.
         * Past it the pose is a guess and the map should stop believing
         * anything stamped against it.
         */
        const val CONFIDENCE_RANGE_M = 25.0
    }

    var stepMeters = DEFAULT_STEP_M

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    @Volatile private var azimuthRad = Float.NaN
    @Volatile private var seedAzimuthRad = Float.NaN
    @Volatile private var seed: Pose2d? = null

    @Volatile private var x = 0.0
    @Volatile private var y = 0.0
    @Volatile private var travelled = 0.0
    @Volatile private var steps = 0
    @Volatile private var active = false
    private var registered = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    azimuthRad = orientation[0]
                }

                Sensor.TYPE_STEP_DETECTOR -> if (active) onStep()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** Keep the rotation vector live even while ARCore is healthy, so a
     *  handover has a heading reference from its very first step. */
    fun start() {
        if (registered) return
        registered = true
        sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
            sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        active = false
        sensors.unregisterListener(listener)
    }

    /** Latch onto the last pose ARCore believed and start reckoning. */
    fun seedFrom(pose: Pose2d) {
        seed = pose
        seedAzimuthRad = azimuthRad
        x = pose.x
        y = pose.y
        travelled = 0.0
        steps = 0
        active = true
    }

    /** ARCore is healthy again; stand down. */
    fun release() {
        active = false
        seed = null
    }

    val running: Boolean get() = active

    val stepCount: Int get() = steps

    private fun onStep() {
        val bearing = currentBearingRad() ?: return
        x += stepMeters * sin(bearing.toDouble())
        y += stepMeters * cos(bearing.toDouble())
        travelled += stepMeters
        steps++
    }

    /**
     * The seed bearing plus however far the phone has turned since. Null
     * until both a seed and a heading exist — a dead-reckoned pose with a
     * guessed heading is worse than none, because the map would stamp
     * obstacles in the wrong direction rather than simply not at all.
     */
    private fun currentBearingRad(): Float? {
        val s = seed ?: return null
        val a = azimuthRad
        val a0 = seedAzimuthRad
        if (a.isNaN() || a0.isNaN()) return null
        return Angles.wrapRad(s.bearingRad + Angles.wrapRad(a - a0))
    }

    /** The dead-reckoned pose, or null when it cannot be trusted at all. */
    fun current(): Pose2d? {
        val s = seed ?: return null
        val bearing = currentBearingRad() ?: return null
        val confidence = (1.0 - travelled / CONFIDENCE_RANGE_M)
            .coerceIn(0.0, 1.0).toFloat() * 0.6f
        if (confidence <= 0f) return null
        return Pose2d(
            x = x,
            y = y,
            bearingRad = bearing,
            heightM = s.heightM,
            timestampNs = SystemClock.elapsedRealtimeNanos(),
            confidence = confidence,
            epoch = s.epoch,
        )
    }

    /** Metres dead reckoned since the seed — the honest drift budget. */
    val travelledM: Double get() = travelled
}
