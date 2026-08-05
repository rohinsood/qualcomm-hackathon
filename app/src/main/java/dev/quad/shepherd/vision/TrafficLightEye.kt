package dev.quad.shepherd.vision

import android.graphics.Bitmap

/**
 * Cheap traffic-light state read: YOLO already finds "traffic light"
 * boxes; this samples the crop's pixels and votes red / yellow / green by
 * dominant saturated hue. Inconclusive crops keep the plain label. The
 * decorated label flows into the scene digest ("traffic light (green)
 * ahead"), giving the companion crossing awareness without a new model.
 */
object TrafficLightEye {

    private const val MIN_W = 8f
    private const val MIN_H = 16f
    private const val STRIDE = 3
    private const val MIN_VOTES = 12

    fun decorate(detections: List<Detection>, frame: Bitmap): List<Detection> {
        if (detections.none { it.label == "traffic light" }) return detections
        return detections.map { d ->
            if (d.label == "traffic light" && d.width >= MIN_W && d.height >= MIN_H) {
                stateOf(frame, d)?.let { s -> d.copy(label = "traffic light ($s)") } ?: d
            } else d
        }
    }

    private fun stateOf(frame: Bitmap, d: Detection): String? {
        val x1 = d.x1.toInt().coerceIn(0, frame.width - 1)
        val y1 = d.y1.toInt().coerceIn(0, frame.height - 1)
        val x2 = d.x2.toInt().coerceIn(x1 + 1, frame.width)
        val y2 = d.y2.toInt().coerceIn(y1 + 1, frame.height)

        var red = 0
        var yellow = 0
        var green = 0
        var y = y1
        while (y < y2) {
            var x = x1
            while (x < x2) {
                val p = frame.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val max = maxOf(r, g, b)
                // Only bright, saturated pixels vote — lit lamps, not housing
                if (max > 140 && max - minOf(r, g, b) > 60) {
                    when {
                        r > g * 14 / 10 && r > b * 14 / 10 -> red++
                        g > r * 12 / 10 && g > b -> green++
                        r > 160 && g > 120 && b < g * 6 / 10 -> yellow++
                    }
                }
                x += STRIDE
            }
            y += STRIDE
        }

        val top = maxOf(red, yellow, green)
        if (top < MIN_VOTES) return null
        return when (top) {
            red -> "red"
            green -> "green"
            else -> "yellow"
        }
    }
}
