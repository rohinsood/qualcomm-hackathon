package dev.quad.shepherd.guidance

import dev.quad.shepherd.vision.Detection
import java.util.Locale

/**
 * Rolling, thread-safe scene state shared between the vision pipeline and
 * the conversational companion: the latest detections and guidance verdict,
 * plus a short memory of safety alerts that were actually spoken. [digest]
 * renders it as the compact plain-text block each SLM turn is grounded
 * with, so the companion answers from what the camera actually sees.
 *
 * Pure Kotlin (no Android imports) so it stays JVM-unit-testable.
 */
class SceneBlackboard {

    companion object {
        private const val MAX_OBJECTS = 5
        private const val MAX_EVENTS = 4
        private const val EVENT_WINDOW_MS = 30_000L
    }

    private data class Event(val text: String, val atMs: Long)

    private var detections: List<Detection> = emptyList()
    private var frameWidth = 0
    private var severity = GuidanceEngine.Severity.CLEAR
    private var nearestLabel: String? = null
    private var nearestDistance: Float? = null
    private val events = ArrayDeque<Event>()

    @Synchronized
    fun updateFrame(detections: List<Detection>, frameWidth: Int) {
        this.detections = detections
        this.frameWidth = frameWidth
    }

    @Synchronized
    fun updateGuidance(g: GuidanceEngine.Guidance) {
        severity = g.severity
        nearestLabel = g.nearestLabel
        nearestDistance = g.nearestDistanceMeters
    }

    /** Record a safety alert that was actually spoken aloud. */
    @Synchronized
    fun noteAlert(text: String, nowMs: Long) {
        events.addLast(Event(text, nowMs))
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    @Synchronized
    fun digest(nowMs: Long): String = buildString {
        append("Path: ").append(severity.name.lowercase(Locale.US))
        val label = nearestLabel
        val dist = nearestDistance
        if (label != null && dist != null && severity != GuidanceEngine.Severity.CLEAR) {
            append(" — nearest: ").append(label).append(", ").append(fmt(dist)).append(" m")
        }
        append(". ")

        val objs = detections
            .sortedBy { it.distanceMeters ?: Float.MAX_VALUE }
            .take(MAX_OBJECTS)
        if (objs.isEmpty()) {
            append("Camera sees no labeled objects.")
        } else {
            append("Camera sees: ")
            append(objs.joinToString("; ") { d ->
                val where = bearing(d.centerX, frameWidth)
                val dd = d.distanceMeters
                if (dd != null) "${d.label} ${fmt(dd)} m $where"
                else "${d.label} $where, distance unknown"
            })
            append('.')
        }

        while (events.isNotEmpty() && nowMs - events.first().atMs > EVENT_WINDOW_MS) {
            events.removeFirst()
        }
        if (events.isNotEmpty()) {
            append(" Recent alerts: ")
            append(events.joinToString("; ") { e ->
                "\"${e.text}\" ${(nowMs - e.atMs) / 1000} s ago"
            })
            append('.')
        }
    }

    private fun bearing(centerX: Float, frameWidth: Int): String {
        if (frameWidth <= 0) return "ahead"
        val f = centerX / frameWidth
        return when {
            f < 0.2f -> "on your left"
            f < 0.4f -> "slightly left"
            f <= 0.6f -> "ahead"
            f <= 0.8f -> "slightly right"
            else -> "on your right"
        }
    }

    private fun fmt(v: Float): String {
        val r = kotlin.math.round(v * 10f) / 10f
        return if (r == r.toInt().toFloat()) r.toInt().toString()
        else String.format(Locale.US, "%.1f", r)
    }
}
