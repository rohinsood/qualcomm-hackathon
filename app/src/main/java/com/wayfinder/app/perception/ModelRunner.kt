package com.wayfinder.app.perception

import com.wayfinder.app.core.loop.Frame
import com.wayfinder.app.perception.seg.WalkableMask

/**
 * Pluggable perception host. The steering loop depends only on this interface,
 * so the model is a swappable QAIRT artifact behind it.
 *
 * - M1 default: [com.wayfinder.app.perception.seg.SyntheticSegmentationRunner]
 *   (returns a synthetic mask so the full loop runs before the real .tflite lands).
 * - M1 real:    [com.wayfinder.app.perception.seg.TFLiteSegmentationRunner]
 *   (TFLite + QNN/HTP delegate → Hexagon NPU).
 */
interface ModelRunner {
    val name: String

    /** Produce a walkable mask from a frame. Synchronous; called on the inference thread. */
    fun segment(frame: Frame): WalkableMask

    /** One-time warm-up before the live loop starts (pays NPU context-init cost). */
    fun warmUp() {}

    fun close() {}
}
