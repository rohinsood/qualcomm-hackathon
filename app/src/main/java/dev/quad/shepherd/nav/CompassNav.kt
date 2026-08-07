package dev.quad.shepherd.nav

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The team-default navigation (qhackgps semantics): straight-line compass
 * bearing from the current GPS fix to a destination. The signed
 * bearing-minus-heading delta feeds the polar planner as its goal angle,
 * so when the bearing line runs into an obstacle the planner walks the
 * user around it and the ever-updating bearing pulls them back on line.
 *
 * Destinations come from a map tap or a spoken "take me to X" (geocoded).
 */
class CompassNav(
    context: Context,
    private val scope: CoroutineScope,
    private val speak: (String) -> Unit,
) {

    companion object {
        private const val TAG = "CompassNav"
        private const val FIX_INTERVAL_MS = 1000L
        private const val ARRIVAL_M = 12.0
    }

    /** Signed degrees to turn toward the destination bearing, or null. */
    @Volatile var goalAngleDeg: Float? = null
        private set

    /** One-line progress summary for the scene digest, or null. */
    @Volatile var summary: String? = null
        private set

    @Volatile var destination: DoubleArray? = null // [lat, lng]
        private set

    @Volatile var destinationLabel: String? = null
        private set

    @Volatile var lastLatLng: DoubleArray? = null
        private set

    @Volatile var headingDeg = Float.NaN
        private set

    val active: Boolean get() = destination != null

    @Volatile private var declination = 0f
    @Volatile private var sensorsUp = false

    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientation)
            val magnetic = Math.toDegrees(orientation[0].toDouble()).toFloat()
            headingDeg = (magnetic + declination + 360f) % 360f
            recompute()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::onFix)
        }
    }

    /** Set the destination directly (map tap). Main thread. */
    @SuppressLint("MissingPermission")
    fun setDestination(lat: Double, lng: Double, label: String? = null) {
        destination = doubleArrayOf(lat, lng)
        destinationLabel = label ?: "your destination"
        speak("Destination set: ${destinationLabel}. Follow the guidance.")
        startSensors()
        recompute()
    }

    /** Geocode a spoken destination, then set it. */
    fun setSpokenDestination(text: String) {
        scope.launch(Dispatchers.IO) {
            val here = lastLatLng
            val dest = RoutesClient.geocode(
                text, here?.get(0) ?: 0.0, here?.get(1) ?: 0.0,
            )
            if (dest == null) {
                speak("I couldn't find \"$text\" nearby.")
            } else {
                scope.launch(Dispatchers.Main) {
                    setDestination(dest.lat, dest.lng, dest.label)
                }
            }
        }
    }

    fun stop(announce: Boolean = true) {
        destination = null
        destinationLabel = null
        goalAngleDeg = null
        summary = null
        stopSensors()
        if (announce) speak("Navigation stopped.")
    }

    private fun onFix(location: Location) {
        lastLatLng = doubleArrayOf(location.latitude, location.longitude)
        if (declination == 0f) {
            declination = GeomagneticField(
                location.latitude.toFloat(), location.longitude.toFloat(),
                location.altitude.toFloat(), System.currentTimeMillis(),
            ).declination
        }
        recompute()
    }

    private fun recompute() {
        val here = lastLatLng ?: return
        val dest = destination ?: return
        val results = FloatArray(2)
        Location.distanceBetween(here[0], here[1], dest[0], dest[1], results)
        val distance = results[0].toDouble()
        val bearing = (results[1] + 360f) % 360f

        if (distance <= ARRIVAL_M) {
            speak("You have arrived at ${destinationLabel ?: "your destination"}.")
            stop(announce = false)
            return
        }

        goalAngleDeg = if (headingDeg.isNaN()) null else {
            var delta = bearing - headingDeg
            while (delta > 180f) delta -= 360f
            while (delta < -180f) delta += 360f
            delta
        }
        summary = "Navigating to ${destinationLabel}: ${fmt(distance)} away, " +
            "bearing ${bearing.roundToInt()}"
    }

    @SuppressLint("MissingPermission")
    private fun startSensors() {
        if (sensorsUp) return
        sensorsUp = true
        try {
            fused.requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, FIX_INTERVAL_MS)
                    .build(),
                locationCallback,
                Looper.getMainLooper(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "location updates failed", e)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun stopSensors() {
        if (!sensorsUp) return
        sensorsUp = false
        fused.removeLocationUpdates(locationCallback)
        sensorManager.unregisterListener(sensorListener)
    }

    private fun fmt(meters: Double): String = when {
        meters >= 950 -> "${(meters / 100).roundToInt() / 10.0} kilometers"
        else -> "${(meters / 10).roundToInt() * 10} meters"
    }
}
