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
 * Two-mode navigation:
 *
 *  - [Mode.INDOOR] — straight-line compass bearing to the destination
 *    (qhackgps semantics). GPS routing knows nothing about corridors, so
 *    inside a building the beeline is the honest signal.
 *  - [Mode.OUTDOOR] — a Google walking route ([RoutesClient] +
 *    [RouteTracker]): the goal angle aims at a look-ahead point along the
 *    actual street path, with spoken turn cues, off-route rerouting and
 *    arrival. Falls back to the beeline when no route can be fetched.
 *
 * Either way the signed goal-minus-heading delta feeds the polar planner,
 * so obstacles bend the path locally and the goal pulls it back on line.
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

    enum class Mode { OUTDOOR, INDOOR }

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

    /** Horizontal accuracy of the last fix, metres; NaN when unknown.
     *  The areamap's ENU alignment weights fixes by this, so a 20 m
     *  urban-canyon fix cannot drag the frame bearing around. */
    @Volatile var lastAccuracyM: Float = Float.NaN
        private set

    @Volatile var headingDeg = Float.NaN
        private set

    val active: Boolean get() = destination != null

    @Volatile var mode = Mode.OUTDOOR
        private set

    /** Route polyline ([lat, lng] points) for the map, or null (beeline). */
    @Volatile var routePoints: List<DoubleArray>? = null
        private set

    @Volatile private var tracker: RouteTracker? = null
    @Volatile private var routeFetching = false
    @Volatile private var routeRemainingM = Double.NaN
    @Volatile private var targetBearingDeg = Float.NaN
    @Volatile private var lastRouteAttemptAt = 0L
    @Volatile private var routeFallbackAnnounced = false

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

    /**
     * Heading + fixes without a destination: keeps the map's facing arrow
     * and the scene digest live from app start.
     */
    fun startPassive() = startSensors()

    /** Set the destination directly (map tap). Main thread. */
    @SuppressLint("MissingPermission")
    fun setDestination(lat: Double, lng: Double, label: String? = null) {
        destination = doubleArrayOf(lat, lng)
        destinationLabel = label ?: "your destination"
        clearRoute()
        speak("Destination set: ${destinationLabel}. Follow the guidance.")
        startSensors()
        if (mode == Mode.OUTDOOR) fetchRoute()
        recompute()
    }

    /** Switch between street routing and straight-line guidance. */
    fun setMode(m: Mode) {
        if (mode == m) return
        mode = m
        clearRoute()
        speak(
            if (m == Mode.OUTDOOR) "Outdoor mode: following walking routes."
            else "Indoor mode: straight line guidance.",
        )
        if (m == Mode.OUTDOOR && destination != null) fetchRoute()
        recompute()
    }

    private fun clearRoute() {
        tracker = null
        routePoints = null
        routeRemainingM = Double.NaN
        targetBearingDeg = Float.NaN
        lastRouteAttemptAt = 0L
        routeFallbackAnnounced = false
    }

    /**
     * Fetch the Google walking route for the current destination. Any
     * failure (no key, offline, no route) leaves the beeline fallback in
     * charge. Retried from [onFix] while a destination waits for a fix.
     */
    private fun fetchRoute() {
        if (routeFetching || !RoutesClient.available) return
        val here = lastLatLng ?: return
        val dest = destination ?: return
        routeFetching = true
        lastRouteAttemptAt = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            val route = RoutesClient.walkingRoute(
                here[0], here[1],
                RoutesClient.Destination(dest[0], dest[1], destinationLabel ?: "destination"),
            )
            scope.launch(Dispatchers.Main) {
                routeFetching = false
                if (destination == null || mode != Mode.OUTDOOR) return@launch
                if (route != null) {
                    tracker = RouteTracker(route)
                    routePoints = route.points
                    routeRemainingM = route.totalMeters
                    speak("Walking route found, ${fmt(route.totalMeters)}.")
                } else if (!routeFallbackAnnounced) {
                    // Retries continue (backed off in onFix) but only say so once
                    routeFallbackAnnounced = true
                    speak("No walking route found. Using the straight line.")
                }
                recompute()
            }
        }
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
        clearRoute()
        stopSensors()
        if (announce) speak("Navigation stopped.")
    }

    private fun onFix(location: Location) {
        lastLatLng = doubleArrayOf(location.latitude, location.longitude)
        lastAccuracyM = if (location.hasAccuracy()) location.accuracy else Float.NaN
        if (declination == 0f) {
            declination = GeomagneticField(
                location.latitude.toFloat(), location.longitude.toFloat(),
                location.altitude.toFloat(), System.currentTimeMillis(),
            ).declination
        }
        // A destination set before the first fix could not fetch its route;
        // failed fetches retry silently every 20 s
        if (mode == Mode.OUTDOOR && destination != null && tracker == null &&
            System.currentTimeMillis() - lastRouteAttemptAt > 20_000
        ) {
            fetchRoute()
        }
        trackerStep(location)
        recompute()
    }

    /**
     * Advance the route follower once per GPS FIX — events (turn cues,
     * arrival, off-route strikes) are paced by position updates, not the
     * ~15 Hz compass stream [recompute] rides on.
     */
    private fun trackerStep(location: Location) {
        val t = tracker ?: return
        val u = t.update(
            location.latitude, location.longitude,
            headingDeg.takeIf { !it.isNaN() },
        )
        routeRemainingM = u.remainingMeters
        targetBearingDeg = u.targetBearingDeg
        when (u.event) {
            RouteTracker.Event.ARRIVED -> {
                speak("You have arrived at ${destinationLabel ?: "your destination"}.")
                stop(announce = false)
            }
            RouteTracker.Event.TURN_CUE -> u.cueText?.let { speak(it) }
            RouteTracker.Event.OFF_ROUTE -> {
                speak("Rerouting.")
                clearRoute()
                fetchRoute()
            }
            null -> {}
        }
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

        // Outdoor with a live route: aim at the look-ahead point on the
        // street path instead of the crow-flies destination
        val tb = targetBearingDeg
        if (mode == Mode.OUTDOOR && tracker != null && !tb.isNaN()) {
            goalAngleDeg = if (headingDeg.isNaN()) null else {
                var delta = tb - headingDeg
                while (delta > 180f) delta -= 360f
                while (delta < -180f) delta += 360f
                delta
            }
            val remaining = if (routeRemainingM.isNaN()) distance else routeRemainingM
            summary = "Navigating to ${destinationLabel}: ${fmt(remaining)} " +
                "along the walking route"
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
