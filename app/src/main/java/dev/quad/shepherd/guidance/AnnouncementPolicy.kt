package dev.quad.shepherd.guidance

import kotlin.math.roundToInt

/**
 * Decides what the voice actually says, and when. [GuidanceEngine] emits
 * raw state at frame rate; speaking that directly restarts the utterance
 * every frame (the infamous "obs- obs- obs-" stutter, because the spoken
 * distance changed a decimal each frame and urgent messages flushed the
 * queue). This policy quantizes, deduplicates, and paces:
 *
 *  - entering danger speaks immediately and interrupts;
 *  - a *changed* danger instruction (new direction/label/half-meter band)
 *    interrupts; caution changes speak without interrupting;
 *  - otherwise danger repeats every 2 s and caution every 3.5 s, never
 *    interrupting what is already being spoken;
 *  - returning to a clear path is confirmed once ("Path clear.").
 *
 * Pure Kotlin; the caller supplies the clock.
 */
class AnnouncementPolicy {

    companion object {
        private const val DANGER_REPEAT_MS = 2000L
        private const val CAUTION_REPEAT_MS = 3500L
        private const val CLEAR_CONFIRM_MS = 1500L
    }

    data class Utterance(val text: String, val interrupt: Boolean)

    private var lastText: String? = null
    private var lastAt = 0L
    private var lastSeverity = GuidanceEngine.Severity.CLEAR
    private var clearConfirmed = true

    fun decide(g: GuidanceEngine.Guidance, nowMs: Long): Utterance? {
        val result = when (g.severity) {
            GuidanceEngine.Severity.DANGER ->
                threat(g, nowMs, DANGER_REPEAT_MS, interruptOnEntry = true)
            GuidanceEngine.Severity.CAUTION ->
                threat(g, nowMs, CAUTION_REPEAT_MS, interruptOnEntry = false)
            GuidanceEngine.Severity.CLEAR -> clear(nowMs)
        }
        if (g.severity != GuidanceEngine.Severity.CLEAR) clearConfirmed = false
        lastSeverity = g.severity
        return result
    }

    private fun threat(
        g: GuidanceEngine.Guidance,
        nowMs: Long,
        repeatMs: Long,
        interruptOnEntry: Boolean,
    ): Utterance? {
        val entering = lastSeverity != g.severity
        // During a severity hold the frame may carry no threat details;
        // keep repeating the last instruction rather than going silent
        val text = compose(g) ?: lastText ?: return null
        val changed = text != lastText
        val due = nowMs - lastAt >= repeatMs
        if (!entering && !changed && !due) return null
        lastText = text
        lastAt = nowMs
        val interrupt = (entering && interruptOnEntry) ||
            (changed && g.severity == GuidanceEngine.Severity.DANGER)
        return Utterance(text, interrupt)
    }

    private fun clear(nowMs: Long): Utterance? {
        if (clearConfirmed) return null
        if (nowMs - lastAt < CLEAR_CONFIRM_MS) return null
        clearConfirmed = true
        lastText = null
        lastAt = nowMs
        return Utterance("Path clear.", interrupt = false)
    }

    private fun compose(g: GuidanceEngine.Guidance): String? {
        val label = g.nearestLabel ?: return null
        val dist = g.nearestDistanceMeters ?: return null
        val direction = when {
            g.steer < -0.2f -> "move left"
            g.steer > 0.2f -> "move right"
            g.severity == GuidanceEngine.Severity.DANGER -> "stop"
            else -> "slow down"
        }
        // Half-meter quantization keeps the text stable across frames
        val half = (dist * 2).roundToInt() / 2f
        val distText =
            if (half == half.toInt().toFloat()) half.toInt().toString()
            else String.format("%.1f", half)
        return "$label, $distText meters ahead. Please $direction."
    }
}
