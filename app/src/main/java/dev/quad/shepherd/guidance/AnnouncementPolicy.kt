package dev.quad.shepherd.guidance

import kotlin.math.roundToInt

/**
 * The voice arbiter's alert side: decides what the voice says about
 * threats, and — critically — when it is allowed to CUT OFF speech.
 *
 * Field-tested rule set (v2, after the walking "obs- obs-" stutter):
 *
 *  - The ONLY event allowed to interrupt speech in progress is an
 *    escalation into DANGER. Everything else — direction changes, distance
 *    drift, repeats — speaks without interrupting, and only after a minimum
 *    gap since the last utterance started.
 *  - Danger lines are friend-short and carry no distance: "person. Go left."
 *  - Caution lines are informational: "obstacle ahead, 2.5 meters."
 *  - Danger repeats every 2 s, caution every 3.5 s; a recovered path is
 *    confirmed once with "Path clear."
 *  - `urgent` marks danger utterances so the TTS can use the clipped, faster
 *    register (one voice, two registers).
 *
 * Pure Kotlin; the caller supplies the clock.
 */
class AnnouncementPolicy {

    companion object {
        private const val DANGER_REPEAT_MS = 2000L
        private const val CAUTION_REPEAT_MS = 3500L
        private const val CLEAR_CONFIRM_MS = 1500L
        /** Minimum spacing between utterance starts (except escalation). */
        private const val MIN_GAP_MS = 1200L
    }

    data class Utterance(val text: String, val interrupt: Boolean, val urgent: Boolean)

    private var lastText: String? = null
    private var lastAt = 0L
    private var lastSeverity = GuidanceEngine.Severity.CLEAR
    private var clearConfirmed = true

    fun decide(g: GuidanceEngine.Guidance, nowMs: Long): Utterance? {
        val result = when (g.severity) {
            GuidanceEngine.Severity.DANGER ->
                threat(g, nowMs, DANGER_REPEAT_MS, escalating = lastSeverity != GuidanceEngine.Severity.DANGER)
            GuidanceEngine.Severity.CAUTION ->
                threat(g, nowMs, CAUTION_REPEAT_MS, escalating = false)
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
        escalating: Boolean,
    ): Utterance? {
        // During a severity hold the frame may carry no threat details;
        // keep repeating the last instruction rather than going silent
        val text = compose(g) ?: lastText ?: return null
        val changed = text != lastText
        val due = nowMs - lastAt >= repeatMs
        val gapOk = nowMs - lastAt >= MIN_GAP_MS
        val urgent = g.severity == GuidanceEngine.Severity.DANGER
        return when {
            escalating -> emit(text, interrupt = true, urgent, nowMs)
            (changed || due) && gapOk -> emit(text, interrupt = false, urgent, nowMs)
            else -> null
        }
    }

    private fun clear(nowMs: Long): Utterance? {
        if (clearConfirmed) return null
        if (nowMs - lastAt < CLEAR_CONFIRM_MS) return null
        clearConfirmed = true
        lastText = null
        lastAt = nowMs
        return Utterance("Path clear.", interrupt = false, urgent = false)
    }

    private fun emit(text: String, interrupt: Boolean, urgent: Boolean, nowMs: Long): Utterance {
        lastText = text
        lastAt = nowMs
        return Utterance(text, interrupt, urgent)
    }

    private fun compose(g: GuidanceEngine.Guidance): String? {
        val label = g.nearestLabel ?: return null
        return when (g.severity) {
            GuidanceEngine.Severity.DANGER -> {
                // Friend-short: no distance, just the thing and what to do
                val direction = when {
                    g.steer < -0.2f -> "Go left"
                    g.steer > 0.2f -> "Go right"
                    else -> "Stop"
                }
                "$label. $direction."
            }
            else -> {
                val dist = g.nearestDistanceMeters ?: return null
                val half = (dist * 2).roundToInt() / 2f
                val distText =
                    if (half == half.toInt().toFloat()) half.toInt().toString()
                    else String.format("%.1f", half)
                "$label ahead, $distText meters."
            }
        }
    }
}
