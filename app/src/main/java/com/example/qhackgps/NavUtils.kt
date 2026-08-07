package com.example.qhackgps

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.util.Log
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/** Initial great-circle bearing from [from] to [to], degrees clockwise from true north in [0, 360). */
fun bearingBetween(from: LatLng, to: LatLng): Float {
    val results = FloatArray(2)
    Location.distanceBetween(from.latitude, from.longitude, to.latitude, to.longitude, results)
    return (results[1] + 360f) % 360f
}

/** Distance in meters between two points. */
fun distanceMeters(from: LatLng, to: LatLng): Float {
    val results = FloatArray(1)
    Location.distanceBetween(from.latitude, from.longitude, to.latitude, to.longitude, results)
    return results[0]
}

/** Shortest signed angle to rotate from [current] to [target], in [-180, 180). Negative = turn left. */
fun shortestSignedDelta(target: Float, current: Float): Float {
    var d = (target - current) % 360f
    if (d < -180f) d += 360f
    if (d >= 180f) d -= 360f
    return d
}

/** Wraps any angle into a compass reading in [0, 360). Safe for large negatives. */
fun wrap360(deg: Float): Float = ((deg % 360f) + 360f) % 360f

/** Wraps any angle into a signed trim in (-180, 180], so "+350°" reads as "-10°". */
fun wrap180(deg: Float): Float {
    var d = deg % 360f
    if (d <= -180f) d += 360f
    if (d > 180f) d -= 360f
    return d
}

/**
 * Point on the route to aim the phone at: the first route vertex at least [lookaheadMeters]
 * ahead of the vertex nearest to [current], so guidance follows the path instead of
 * pointing at a corner you are standing on.
 */
fun guidanceTarget(route: List<LatLng>, current: LatLng, lookaheadMeters: Float = 25f): LatLng {
    if (route.isEmpty()) return current
    var nearestIndex = 0
    var nearestDist = Float.MAX_VALUE
    route.forEachIndexed { i, p ->
        val d = distanceMeters(current, p)
        if (d < nearestDist) {
            nearestDist = d
            nearestIndex = i
        }
    }
    var i = nearestIndex
    while (i < route.lastIndex && distanceMeters(current, route[i]) < lookaheadMeters) i++
    return route[i]
}

fun formatDistance(meters: Float): String =
    if (meters < 1000f) "${meters.roundToInt()} m"
    else String.format(Locale.US, "%.2f km", meters / 1000f)

/** Reads the Maps key from the manifest so the Directions request reuses the same key. */
fun mapsApiKey(context: Context): String = try {
    val info = context.packageManager
        .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
    info.metaData?.getString("com.google.android.geo.API_KEY") ?: ""
} catch (_: Exception) {
    ""
}

/**
 * Fetches a walking route from the Google Directions API.
 * Returns null on any failure (no key, API not enabled, offline...) so the caller
 * can fall back to a straight line.
 */
suspend fun fetchWalkingRoute(origin: LatLng, dest: LatLng, apiKey: String): List<LatLng>? {
    if (apiKey.isBlank() || apiKey == "YOUR_API_KEY_HERE") {
        Log.w(ROUTE_TAG, "No Maps key — set MAPS_API_KEY in local.properties. Straight line only.")
        return null
    }
    return withContext(Dispatchers.IO) {
        try {
            val url = URL(
                "https://maps.googleapis.com/maps/api/directions/json" +
                    "?origin=${origin.latitude},${origin.longitude}" +
                    "&destination=${dest.latitude},${dest.longitude}" +
                    "&mode=walking&key=$apiKey"
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
            }
            // On a non-2xx, inputStream throws and the body (which carries Google's
            // reason) is on errorStream instead — read whichever applies.
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            if (code !in 200..299) {
                Log.w(ROUTE_TAG, "Directions HTTP $code: ${body.take(300)}")
                return@withContext null
            }
            val json = JSONObject(body)
            val status = json.optString("status")
            if (status != "OK") {
                // REQUEST_DENIED almost always means either the Directions API isn't
                // enabled on the key, or the key is restricted to Android apps — this
                // is a web-service call, so an Android restriction rejects it.
                Log.w(ROUTE_TAG, "Directions status=$status ${json.optString("error_message")}")
                return@withContext null
            }
            val routes = json.optJSONArray("routes")
            if (routes == null || routes.length() == 0) {
                Log.w(ROUTE_TAG, "Directions returned OK but no routes (unreachable on foot?)")
                return@withContext null
            }
            val encoded = routes.getJSONObject(0)
                .getJSONObject("overview_polyline").getString("points")
            decodePolyline(encoded).takeIf { it.size >= 2 }
                ?: null.also { Log.w(ROUTE_TAG, "Directions polyline had < 2 points") }
        } catch (e: Exception) {
            Log.w(ROUTE_TAG, "Directions request failed", e)
            null
        }
    }
}

private const val ROUTE_TAG = "qhackGPS.route"

/** Standard Google encoded-polyline decoder. */
fun decodePolyline(encoded: String): List<LatLng> {
    val poly = ArrayList<LatLng>()
    var index = 0
    var lat = 0
    var lng = 0
    while (index < encoded.length) {
        var result = 0
        var shift = 0
        var b: Int
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        result = 0
        shift = 0
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        poly.add(LatLng(lat / 1e5, lng / 1e5))
    }
    return poly
}

/**
 * Arrow bitmap for the "where am I pointing" marker. Drawn tip-up so that the marker's
 * rotation (set to the device heading) makes it point the way the phone faces on a
 * north-up map.
 */
fun headingArrowDescriptor(fillColor: Int): BitmapDescriptor {
    val size = 110
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val path = Path().apply {
        moveTo(size / 2f, size * 0.04f)
        lineTo(size * 0.86f, size * 0.90f)
        lineTo(size / 2f, size * 0.68f)
        lineTo(size * 0.14f, size * 0.90f)
        close()
    }
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    })
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = android.graphics.Color.WHITE
    })
    return BitmapDescriptorFactory.fromBitmap(bmp)
}
