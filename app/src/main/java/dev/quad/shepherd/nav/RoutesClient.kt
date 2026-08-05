package dev.quad.shepherd.nav

import android.util.Log
import dev.quad.shepherd.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Thin REST client for Google's Geocoding API (destination text -> lat/lng)
 * and Routes API v2 (walking route with per-step polylines + spoken
 * instructions) — the same routing source the original Shepherd uses.
 * Requires `maps.apiKey` in local.properties (Geocoding + Routes enabled);
 * without it [available] is false and navigation politely declines.
 */
object RoutesClient {

    private const val TAG = "RoutesClient"
    private const val TIMEOUT_MS = 12_000

    val available: Boolean get() = BuildConfig.MAPS_API_KEY.isNotBlank()

    data class Destination(val lat: Double, val lng: Double, val label: String)

    fun geocode(text: String): Destination? = try {
        val url = "https://maps.googleapis.com/maps/api/geocode/json?address=" +
            URLEncoder.encode(text, "UTF-8") + "&key=" + BuildConfig.MAPS_API_KEY
        val body = get(url) ?: throw RuntimeException("no response")
        val json = JSONObject(body)
        val results = json.getJSONArray("results")
        if (results.length() == 0) {
            Log.w(TAG, "geocode: zero results (${json.optString("status")})")
            null
        } else {
            val first = results.getJSONObject(0)
            val loc = first.getJSONObject("geometry").getJSONObject("location")
            Destination(
                lat = loc.getDouble("lat"),
                lng = loc.getDouble("lng"),
                label = first.optString("formatted_address", text).substringBefore(','),
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "geocode failed", e)
        null
    }

    fun walkingRoute(fromLat: Double, fromLng: Double, dest: Destination): RouteTracker.Route? = try {
        val request = JSONObject()
            .put("origin", latLng(fromLat, fromLng))
            .put("destination", latLng(dest.lat, dest.lng))
            .put("travelMode", "WALK")
            .put("polylineQuality", "HIGH_QUALITY")
        val body = post(
            "https://routes.googleapis.com/directions/v2:computeRoutes",
            request.toString(),
            fieldMask = "routes.distanceMeters," +
                "routes.legs.steps.polyline.encodedPolyline," +
                "routes.legs.steps.navigationInstruction.instructions," +
                "routes.legs.steps.distanceMeters",
        ) ?: throw RuntimeException("no response")

        val routes = JSONObject(body).optJSONArray("routes")
        if (routes == null || routes.length() == 0) {
            Log.w(TAG, "no routes in response")
            null
        } else {
            val route = routes.getJSONObject(0)
            val points = ArrayList<DoubleArray>()
            val steps = ArrayList<RouteTracker.Step>()
            val legs = route.getJSONArray("legs")
            for (l in 0 until legs.length()) {
                val stepArr = legs.getJSONObject(l).getJSONArray("steps")
                for (s in 0 until stepArr.length()) {
                    val step = stepArr.getJSONObject(s)
                    val encoded = step.optJSONObject("polyline")
                        ?.optString("encodedPolyline").orEmpty()
                    val instruction = step.optJSONObject("navigationInstruction")
                        ?.optString("instructions").orEmpty()
                    if (instruction.isNotEmpty()) {
                        steps.add(RouteTracker.Step(points.size, instruction))
                    }
                    if (encoded.isNotEmpty()) {
                        val decoded = PolylineDecoder.decode(encoded)
                        // Consecutive step polylines share their joint point
                        points.addAll(
                            if (points.isNotEmpty() && decoded.isNotEmpty()) decoded.drop(1)
                            else decoded
                        )
                    }
                }
            }
            val total = route.optDouble("distanceMeters", 0.0)
            if (points.size < 2) null
            else RouteTracker.Route(points, steps, total)
        }
    } catch (e: Exception) {
        Log.w(TAG, "walkingRoute failed", e)
        null
    }

    private fun latLng(lat: Double, lng: Double): JSONObject =
        JSONObject().put(
            "location",
            JSONObject().put(
                "latLng",
                JSONObject().put("latitude", lat).put("longitude", lng),
            ),
        )

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            if (conn.responseCode != 200) {
                Log.w(TAG, "GET ${conn.responseCode}")
                null
            } else conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun post(url: String, body: String, fieldMask: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Goog-Api-Key", BuildConfig.MAPS_API_KEY)
            conn.setRequestProperty("X-Goog-FieldMask", fieldMask)
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText()?.take(300)
                Log.w(TAG, "POST ${conn.responseCode}: $err")
                null
            } else conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
