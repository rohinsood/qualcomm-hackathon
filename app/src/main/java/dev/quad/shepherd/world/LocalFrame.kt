package dev.quad.shepherd.world

import kotlin.math.cos

/**
 * A local east/north meter frame anchored at one reference coordinate.
 *
 * Equirectangular, which is exact enough at walking scale (sub-centimetre
 * over a few hundred metres) and, unlike a projected CRS, costs nothing.
 * The constants are deliberately IDENTICAL to the ones
 * [dev.quad.shepherd.nav.RouteTracker] uses for its own internal frame, so
 * a route point and a map coordinate that came through different paths
 * agree to the last bit rather than to "about a metre".
 *
 * Pure Kotlin for JVM unit testing.
 */
class LocalFrame(val lat0: Double, val lng0: Double) {

    companion object {
        const val M_PER_LAT = 110_540.0
        const val M_PER_LNG_EQUATOR = 111_320.0
    }

    private val mPerLng = M_PER_LNG_EQUATOR * cos(Math.toRadians(lat0))

    fun eastOf(lng: Double): Double = (lng - lng0) * mPerLng

    fun northOf(lat: Double): Double = (lat - lat0) * M_PER_LAT

    /** [lat, lng] -> [east, north] metres. */
    fun toEastNorth(lat: Double, lng: Double): DoubleArray =
        doubleArrayOf(eastOf(lng), northOf(lat))

    /** [east, north] metres -> [lat, lng]. */
    fun toLatLng(east: Double, north: Double): DoubleArray =
        doubleArrayOf(lat0 + north / M_PER_LAT, lng0 + east / mPerLng)
}
