package com.wayfinder.app.perception.depth

import com.wayfinder.app.core.loop.Frame

/** Per-column depth summary from a depth model. */
data class DepthColumns(
    /** length = numColumns; nearest surface distance (m) in each column. Float.MAX_VALUE / NaN = open column. */
    val perColumn: FloatArray,
    /** closest surface across all columns, or null if every column is open. Drives proximity magnitude. */
    val nearestMeters: Float?,
)

/**
 * Pluggable depth host. Runs at a LOWER rate than segmentation (see the depth
 * scheduling in [com.wayfinder.app.core.loop.SteeringLoop]) and feeds
 * [DepthFusion] for magnitude + the safety override.
 *
 * - M3 default: [SyntheticDepthRunner] (testable on the emulator with no model).
 * - M3 real:    [TFLiteDepthRunner] (Depth-Anything-V2-Small via QNN/HTP on the NPU).
 */
interface DepthRunner {
    val name: String
    fun depth(frame: Frame): DepthColumns
    fun warmUp() {}
    fun close() {}
}
