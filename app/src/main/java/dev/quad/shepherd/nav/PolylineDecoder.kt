package dev.quad.shepherd.nav

/**
 * Decoder for Google's encoded polyline algorithm (as returned by the
 * Routes API). Pure Kotlin for JVM unit testing.
 */
object PolylineDecoder {

    /** Returns [lat, lng] pairs. */
    fun decode(encoded: String): List<DoubleArray> {
        val points = ArrayList<DoubleArray>()
        var index = 0
        var lat = 0L
        var lng = 0L
        while (index < encoded.length) {
            var result = 0L
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1

            result = 0
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1L != 0L) (result shr 1).inv() else result shr 1

            points.add(doubleArrayOf(lat / 1e5, lng / 1e5))
        }
        return points
    }
}
