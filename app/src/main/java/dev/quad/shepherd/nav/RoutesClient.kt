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

    /**
     * Destination text -> place. Geocoding first (addresses, landmarks),
     * biased to a box around the walker; ZERO_RESULTS falls back to Places
     * Text Search so spoken business names ("the pharmacy", "Joe's cafe")
     * resolve to the nearest match.
     */
    fun geocode(text: String, nearLat: Double, nearLng: Double): Destination? {
        Log.i(TAG, "resolving destination: \"$text\" near $nearLat,$nearLng")
        try {
            val bias = "&bounds=${nearLat - 0.35},${nearLng - 0.35}%7C" +
                "${nearLat + 0.35},${nearLng + 0.35}"
            val url = "https://maps.googleapis.com/maps/api/geocode/json?address=" +
                URLEncoder.encode(text, "UTF-8") + bias + "&key=" + BuildConfig.MAPS_API_KEY
            val body = get(url) ?: throw RuntimeException("no response")
            val json = JSONObject(body)
            val results = json.getJSONArray("results")
            if (results.length() > 0) {
                val first = results.getJSONObject(0)
                val loc = first.getJSONObject("geometry").getJSONObject("location")
                return Destination(
                    lat = loc.getDouble("lat"),
                    lng = loc.getDouble("lng"),
                    label = first.optString("formatted_address", text).substringBefore(','),
                )
            }
            Log.w(TAG, "geocode: zero results (${json.optString("status")}), trying Places")
        } catch (e: Exception) {
            Log.w(TAG, "geocode failed", e)
        }
        return placesSearch(text, nearLat, nearLng)
    }

    /** Places Text Search (New), biased to a 3 km circle around the walker. */
    private fun placesSearch(text: String, nearLat: Double, nearLng: Double): Destination? = try {
        val request = JSONObject()
            .put("textQuery", text)
            .put("pageSize", 1)
            .put(
                "locationBias",
                JSONObject().put(
                    "circle",
                    JSONObject()
                        .put(
                            "center",
                            JSONObject()
                                .put("latitude", nearLat)
                                .put("longitude", nearLng),
                        )
                        .put("radius", 3000.0),
                ),
            )
        val body = post(
            "https://places.googleapis.com/v1/places:searchText",
            request.toString(),
            fieldMask = "places.displayName,places.location",
        ) ?: throw RuntimeException("no response")
        val places = JSONObject(body).optJSONArray("places")
        if (places == null || places.length() == 0) {
            Log.w(TAG, "places: zero results")
            null
        } else {
            val place = places.getJSONObject(0)
            val loc = place.getJSONObject("location")
            Destination(
                lat = loc.getDouble("latitude"),
                lng = loc.getDouble("longitude"),
                label = place.optJSONObject("displayName")?.optString("text") ?: text,
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "places search failed", e)
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
