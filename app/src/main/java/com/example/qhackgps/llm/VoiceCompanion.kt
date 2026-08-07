package com.example.qhackgps.llm

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.example.qhackgps.ResolvedPlace
import com.example.qhackgps.feedback.SpeechFeedback
import com.example.qhackgps.mapsApiKey
import com.example.qhackgps.resolveDestination
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The talking side of the app, ported from v3's ShepherdService.ask() flow:
 * one spoken utterance in, one action or spoken reply out.
 *
 * Intent order matters and is kept from v3 — navigation commands are
 * handled by real code BEFORE the SLM ever sees them (the model is
 * explicitly told it cannot start or stop navigation), read requests never
 * need the SLM, and only what remains becomes a grounded chat turn:
 *
 *  1. routing-mode intents ("map mode" / "line mode", with v3's
 *     outdoor/indoor phrasings as aliases for this app's MAP/LINE toggle);
 *  2. "take me to X" — geocode X near the walker and set the destination;
 *  3. "stop navigation" — clear it;
 *  4. "read that sign" and friends — OCR the scan's latest frame (works
 *     with or without the SLM; needs the SCAN toggle on for a frame);
 *  5. everything else — a [GenieChat] turn grounded in the
 *     [SceneBlackboard] digest, streamed sentence-by-sentence to speech.
 *
 * Call [ask] from a main-dispatched coroutine: listener callbacks fire on
 * the caller's context, heavy work hops to Dispatchers.IO internally.
 */
class VoiceCompanion(
    private val context: Context,
    private val blackboard: SceneBlackboard,
    private val speech: SpeechFeedback,
    /** The camera scan's most recent upright frame; null while scan is off. */
    private val latestFrame: () -> Bitmap?,
    private val listener: Listener,
) {

    /** Navigation hooks onto the map screen's state; called on the caller's thread. */
    interface Listener {
        fun currentLocation(): LatLng?
        fun onSetDestination(place: ResolvedPlace)
        fun onClearDestination()
        fun onSetRoadRouting(enabled: Boolean)
    }

    companion object {
        /** Spoken navigation commands, handled before the SLM sees them.
         *  Unanchored: "hey, can you take me to…" must match too. */
        private val NAV_START = Regex(
            "(?:take me to|navigate to|navigate me to|guide me to|walk me to|" +
                "bring me to|directions to|how do i get to|i want to go to|" +
                "let's go to)\\s+(.{3,80})",
            RegexOption.IGNORE_CASE,
        )
        private val NAV_STOP = Regex(
            "stop (?:the )?(?:navigation|navigating|guiding|route)|cancel (?:the )?(?:navigation|route)",
            RegexOption.IGNORE_CASE,
        )

        /** Words in a question that trigger a one-shot OCR pass. */
        private val OCR_TRIGGERS = listOf(
            "read", "sign", "text", "written", "writing", "label", "menu", "says", "say on",
        )

        private val ROUTE_MODE_ON = listOf("outdoor mode", "outside mode", "map mode", "route mode")
        private val ROUTE_MODE_OFF = listOf("indoor mode", "inside mode", "line mode", "straight line mode")
    }

    val genie = GenieChat()
    private val ocr = OcrReader()
    @Volatile private var warming = false

    /** Owns the fire-and-forget warm-up so a model download never blocks a
     *  caller (and with it the talk button); cancelled in [close]. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Startup warm-up, silent when there is nothing to do: loads the SLM
     * only if its model is ALREADY on the phone. The ~1.2 GB download is
     * never started implicitly — that happens on the first explicit talk
     * request, announced (see [ask]).
     */
    suspend fun warmIfModelPresent() {
        val present = withContext(Dispatchers.IO) {
            GenieRuntime.ensureInit(context) == null && GenieRuntime.modelPresent()
        }
        if (present) warmUp()
    }

    /** The user pressed talk: the friend goes quiet and listens. */
    fun onPttDown() {
        genie.requestStop(null)
        speech.stopAll()
    }

    /** One spoken utterance. Call from a main-dispatched coroutine. */
    suspend fun ask(text: String) {
        val lower = text.lowercase()

        // Routing-mode intents: v3's indoor/outdoor split maps onto this
        // app's MAP/LINE toggle (walking routes vs straight-line bearing).
        if (ROUTE_MODE_OFF.any { lower.contains(it) }) {
            listener.onSetRoadRouting(false)
            speech.announce("Straight line mode.")
            return
        }
        if (ROUTE_MODE_ON.any { lower.contains(it) }) {
            listener.onSetRoadRouting(true)
            speech.announce("Walking route mode.")
            return
        }

        NAV_START.find(text.trim())?.let { m ->
            val dest = m.groupValues[1].trim()
                .trimEnd('.', '!', '?', ',')
                .replace(Regex("\\s+(please|now|thanks|thank you)$", RegexOption.IGNORE_CASE), "")
            val here = listener.currentLocation()
            if (here == null) {
                speech.announce("I don't have a GPS fix yet.", interrupt = true)
                return
            }
            speech.announce("Looking for $dest.")
            val place = resolveDestination(dest, here, mapsApiKey(context))
            if (place == null) {
                // Route failures are loud on purpose — silence reads as success.
                speech.announce("I couldn't find $dest.", interrupt = true)
            } else {
                listener.onSetDestination(place)
                speech.announce("Taking you to ${place.label}.")
            }
            return
        }

        if (NAV_STOP.containsMatchIn(text)) {
            listener.onClearDestination()
            speech.announce("Navigation stopped.")
            return
        }

        val wantsOcr = OCR_TRIGGERS.any { lower.contains(it) }

        if (!genie.ready) {
            // Reading text never needed the SLM.
            if (wantsOcr) {
                readAloud()
                return
            }
            when (genie.status) {
                GenieChat.Status.LOADING ->
                    speech.announce("The companion is still loading, one moment.", interrupt = true)
                GenieChat.Status.FAILED ->
                    speech.announce("Companion unavailable. ${genie.failure ?: ""}", interrupt = true)
                else -> {
                    speech.announce(
                        "Getting the companion ready — downloading its model, " +
                            "about one gigabyte. Ask me again once I say it's ready.",
                        interrupt = true,
                    )
                    // Fire-and-forget, mirroring v3's warmChat(): the download
                    // must not hold this ask() — and the talk button — hostage.
                    ioScope.launch { warmUp() }
                }
            }
            return
        }

        // A barged-in reply may still be winding down after stopStream.
        var waited = 0
        while (genie.busy && waited < 2000) {
            delay(50)
            waited += 50
        }

        var digest = blackboard.digest(SystemClock.elapsedRealtime())
        if (wantsOcr) {
            latestFrame()?.let { frame ->
                withContext(Dispatchers.IO) { ocr.read(frame) }?.let { seen ->
                    digest += " Text visible through the camera: \"$seen\"."
                }
            }
        }
        val reply = withContext(Dispatchers.IO) {
            genie.ask(text, digest) { sentence -> speech.announceChat(sentence) }
        }
        if (reply == null) speech.announce("Sorry, I lost my train of thought.")
    }

    /** Standalone OCR read — the no-SLM path, straight to speech. */
    private suspend fun readAloud() {
        val frame = latestFrame()
        if (frame == null) {
            speech.announce("Turn the camera scan on and I can read it.", interrupt = true)
            return
        }
        val seen = withContext(Dispatchers.IO) { ocr.read(frame) }
        speech.announce(
            if (seen.isNullOrBlank()) "I don't see any text." else "It says: $seen",
            interrupt = true,
        )
    }

    private suspend fun warmUp() {
        if (warming) return
        warming = true
        try {
            val ok = withContext(Dispatchers.IO) {
                genie.warmUp(context) { status -> speech.announce(status) }
            }
            speech.announce(
                if (ok) "Companion ready. Hold talk to ask me anything."
                else "Companion failed to load. ${genie.failure ?: ""}"
            )
        } finally {
            warming = false
        }
    }

    fun close() {
        ioScope.cancel()
        genie.close()
        ocr.close()
    }
}
