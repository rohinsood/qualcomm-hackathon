package dev.quad.shepherd

/**
 * Which heavy subsystems this build actually loads.
 *
 * The areamap work moves the perception backbone from a stack of ONNX
 * models to ARCore: its Depth API gives metric depth that is correct by
 * construction (no [dev.quad.shepherd.guidance.DepthCalibrator] scale
 * hack), its plane detection gives a real ground plane instead of the
 * percentile self-calibration in TraversabilityGrid, and it hands back
 * the 6-DoF pose those depth points have to be stamped with anyway. Once
 * the map owns obstacle evidence, the model stack is redundant weight on
 * the NPU and the thermal budget.
 *
 * Nothing is deleted — every engine still compiles, still has its call
 * sites, and comes back by flipping one flag here. The engines all guard
 * on a null session, so a disabled [initialize] simply leaves `available`
 * false and each `takeIf { it.available }` downstream resolves to null.
 *
 * Known trade-offs of the current loadout, so they are a choice and not a
 * surprise:
 *
 *  - ARCore depth is motion stereo. It degrades on textureless surfaces —
 *    a blank wall is exactly the obstacle that matters most. [MONO_DEPTH]
 *    is the fallback/complement for that case; the cane's Modulino
 *    Distance ray covers the near field regardless.
 *  - With [SEGMENTATION] off there is no walkability semantics, so the
 *    map is pure geometry: it can tell a kerb from flat ground but not
 *    road from pavement. The route corridor carries that job for now.
 */
object Loadout {

    /** YOLOv8 detection. Fed labels + depth-scale calibration; the map
     *  owns steering and ARCore depth is already metric, so neither
     *  consumer survives. Turn on to get object labels back. */
    const val OBJECT_DETECTION = false

    /** Depth-Anything-V2-Metric (indoor Hypersim + outdoor VKITTI).
     *  Superseded by ARCore's Depth API; see the textureless-wall note. */
    const val MONO_DEPTH = false

    /** FFNet (Cityscapes) + SegFormer-B0 (ADE20K) walkability masks. */
    const val SEGMENTATION = false

    /** sherpa-onnx neural voice (Supertonic/Kokoro) and its ~130 MB
     *  auto-download. Off means the Android system TTS engine speaks
     *  instead — navigation cues, turn-by-turn and arrival all still
     *  work, which is not optional in a blind-navigation app. */
    const val NEURAL_TTS = false

    /** Conversational SLM on the NPU (GenieX). Was already parked. */
    const val COMPANION_SLM = false

    /** ML Kit on-demand sign/menu reading. */
    const val OCR = false

    /** ARCore: 6-DoF motion tracking, Depth API, plane detection — the
     *  pose and obstacle source the areamap is built on. */
    const val ARCORE = true
}
