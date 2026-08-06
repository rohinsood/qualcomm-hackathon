package com.wayfinder.app.core.loop

import java.util.concurrent.atomic.AtomicReference

/**
 * A single camera frame as an RGBA byte buffer, ready for inference + debug drawing.
 *
 * NOTE (perf): for M1 we copy the RGBA plane out of the ImageProxy and close it
 * immediately. This is simple and correct; a later optimization can keep YUV and
 * feed the model directly to avoid the copy.
 */
data class Frame(
    val rgba: ByteArray,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val timestampMs: Long,
)

/**
 * Latest-wins, 1-capacity frame buffer. A new frame overwrites the old; consumers
 * takeOrNull() to drain. This is the backpressure strategy: **drop stale frames,
 * never queue and never block the camera thread.** Matches the architecture's
 * "latest-wins, never queue" golden rule.
 */
class FrameSlot {
    private val ref = AtomicReference<Frame?>(null)

    /** Called from the CameraX analyzer thread. Overwrites any unconsumed frame. */
    fun put(frame: Frame) {
        ref.set(frame)
    }

    /** Called from the inference loop. Drains (returns then clears) the latest frame. */
    fun takeOrNull(): Frame? = ref.getAndSet(null)
}
