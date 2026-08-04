package dev.quad.shepherd.vision

/**
 * A single detected object. Coordinates are pixels in whatever frame of
 * reference the producer documents (the engine emits 640x640 model space;
 * [FrameAnalyzer] remaps to camera-frame space before publishing).
 *
 * Pure Kotlin — no Android imports — so guidance logic and post-processing
 * stay JVM-unit-testable.
 */
data class Detection(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val score: Float,
    val classId: Int,
    val label: String,
    /** Estimated distance from the camera, if the class has a known height. */
    val distanceMeters: Float? = null,
) {
    val width: Float get() = x2 - x1
    val height: Float get() = y2 - y1
    val centerX: Float get() = (x1 + x2) / 2f

    fun iou(other: Detection): Float {
        val ix1 = maxOf(x1, other.x1)
        val iy1 = maxOf(y1, other.y1)
        val ix2 = minOf(x2, other.x2)
        val iy2 = minOf(y2, other.y2)
        val inter = maxOf(0f, ix2 - ix1) * maxOf(0f, iy2 - iy1)
        if (inter <= 0f) return 0f
        val union = width * height + other.width * other.height - inter
        return if (union <= 0f) 0f else inter / union
    }
}
