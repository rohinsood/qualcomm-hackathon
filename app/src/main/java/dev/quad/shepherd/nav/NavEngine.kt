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
import kotlinx.coroutines.tasks.await
import kotlin.math.roundToInt

/**
 * Walking navigation: geocode a spoken destination, fetch a walking route
 * ([RoutesClient]), then feed each GPS fix + true-north compass heading
 * through [RouteTracker] to produce a continuous steering bias toward the
 * path. Off-route triggers an automatic re-route — the "path finding that
 * gets continually updated". Turn cues, arrival, and errors are spoken
 * through the alert channel; [goalSteer] is fused with obstacle avoidance
 * downstream by SteerFusion, Shepherd-style.
 *
 * Callers must hold ACCESS_FINE_LOCATION before [start].
 */
class NavEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val speak: (String) -> Unit,
) {

    companion object {
        private const val TAG = "NavEngine"
        private const val FIX_INTERVAL_MS = 1000L
    }

    /** Steering bias toward the route, -1..1; null when not navigating. */
    @Volatile var goalSteer: Float? = null
        private set

    /** One-line progress summary for the scene digest, or null. */
    @Volatile var summary: String? = null
        private set

    val active: Boolean get() = tracker != null

    private var tracker: RouteTracker? = null
    private var destination: RoutesClient.Destination? = null
    @Volatile private var headingDeg = Float.NaN
    @Volatile private var declination = 0f
    private var lastFix: Location? = null
    @Volatile private var busyRouting = false

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
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::onFix)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(destinationText: String) {
        if (!RoutesClient.available) {
            speak("Navigation needs a Google Maps API key. See the project readme.")
            return
        }
        if (busyRouting) return
        busyRouting = true
        speak("Finding a route to $destinationText.")
        scope.launch(Dispatchers.IO) {
            try {
                val here = fused
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .await() ?: run {
                    speak("I can't get a GPS fix yet. Try again outside.")
                    return@launch
                }
                val dest = RoutesClient.geocode(destinationText) ?: run {
                    speak("I couldn't find $destinationText.")
                    return@launch
                }
                val route = RoutesClient.walkingRoute(here.latitude, here.longitude, dest)
                    ?: run {
                        speak("No walking route to ${dest.label}.")
                        return@launch
                    }
                destination = dest
                tracker = RouteTracker(route)
                declination = GeomagneticField(
                    here.latitude.toFloat(), here.longitude.toFloat(),
                    here.altitude.toFloat(), System.currentTimeMillis(),
                ).declination
                speak(
                    "Route found: ${fmt(route.totalMeters)} to ${dest.label}. " +
                        "Starting guidance."
                )
                withNav { startSensors() }
            } catch (e: Exception) {
                Log.w(TAG, "start failed", e)
                speak("Navigation failed to start.")
            } finally {
                busyRouting = false
            }
        }
    }

    fun stop(announce: Boolean = true) {
        tracker = null
        destination = null
        goalSteer = null
        summary = null
        withNav { stopSensors() }
        if (announce) speak("Navigation stopped.")
    }

    private fun onFix(location: Location) {
        lastFix = location
        val t = tracker ?: return
        // Prefer the compass; fall back to GPS course when moving
        val heading = when {
            !headingDeg.isNaN() -> headingDeg
            location.hasBearing() -> location.bearing
            else -> null
        }
        val update = t.update(location.latitude, location.longitude, heading)
        goalSteer = update.steer
        summary = destination?.let { d ->
            "Navigating to ${d.label}: ${fmt(update.remainingMeters)} remaining"
        }
        when (update.event) {
            RouteTracker.Event.TURN_CUE ->
                update.cueText?.let { speak(it) }
            RouteTracker.Event.ARRIVED -> {
                speak("You have arrived at ${destination?.label ?: "your destination"}.")
                stop(announce = false)
            }
            RouteTracker.Event.OFF_ROUTE -> reroute()
            null -> {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun reroute() {
        val dest = destination ?: return
        val here = lastFix ?: return
        if (busyRouting) return
        busyRouting = true
        speak("Rerouting.")
        scope.launch(Dispatchers.IO) {
            try {
                RoutesClient.walkingRoute(here.latitude, here.longitude, dest)?.let {
                    tracker = RouteTracker(it)
                } ?: speak("I couldn't find a new route.")
            } finally {
                busyRouting = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSensors() {
        fused.requestLocationUpdates(
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, FIX_INTERVAL_MS).build(),
            locationCallback,
            Looper.getMainLooper(),
        )
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun stopSensors() {
        fused.removeLocationUpdates(locationCallback)
        sensorManager.unregisterListener(sensorListener)
    }

    /** Sensor (un)registration on the main thread. */
    private fun withNav(block: () -> Unit) {
        scope.launch(Dispatchers.Main) { block() }
    }

    private fun fmt(meters: Double): String = when {
        meters >= 950 -> "${(meters / 100).roundToInt() / 10.0} kilometers"
        else -> "${(meters / 10).roundToInt() * 10} meters"
    }
}
