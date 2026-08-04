package dev.quad.shepherd.llm

import android.graphics.Bitmap
import dev.quad.shepherd.vision.Detection

/**
 * On-demand scene narration ("What's around me?"). Implementations:
 *
 *  - [ClaudeSceneDescriber] — cloud, multimodal (sees the actual frame).
 *  - On-device (planned): a Genie NPU LLM bundle staged via the QUAD MCP
 *    (`aihub_select action=ensure`) narrating from the structured detection
 *    list. Text-only, but fully offline — the same offline-first principle
 *    Shepherd applies to obstacle avoidance.
 */
interface SceneDescriber {
    /**
     * @param frame the current camera frame
     * @param detections what the on-device detector currently sees, used to
     *   ground the narration
     * @return one short spoken-style paragraph
     */
    suspend fun describe(frame: Bitmap, detections: List<Detection>): String
}
