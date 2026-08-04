package dev.quad.shepherd

import dev.quad.shepherd.vision.Detection
import dev.quad.shepherd.vision.YoloPostProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloPostProcessorTest {

    @Test
    fun `decodes raw yolo head layout`() {
        // 100 anchors, 84 channels (4 box + 80 classes), channel-major layout.
        // Anchor count must exceed channel count for the raw-layout heuristic,
        // as it does in real exports (8400 anchors vs 84 channels).
        val anchors = 100
        val channels = 84
        val data = FloatArray(channels * anchors)
        // anchor 0: cx=320 cy=320 w=100 h=200, class 0 ("person") score 0.9
        data[0 * anchors + 0] = 320f
        data[1 * anchors + 0] = 320f
        data[2 * anchors + 0] = 100f
        data[3 * anchors + 0] = 200f
        data[(4 + 0) * anchors + 0] = 0.9f
        // all other anchors: zero confidence -> dropped

        val dets = YoloPostProcessor.parse(
            listOf(longArrayOf(1, channels.toLong(), anchors.toLong()) to data)
        )

        assertEquals(1, dets.size)
        val d = dets[0]
        assertEquals("person", d.label)
        assertEquals(270f, d.x1, 0.01f)
        assertEquals(220f, d.y1, 0.01f)
        assertEquals(370f, d.x2, 0.01f)
        assertEquals(420f, d.y2, 0.01f)
        assertEquals(0.9f, d.score, 0.001f)
    }

    @Test
    fun `decodes split output layout`() {
        // 3 anchors: boxes [1,3,4] xyxy, scores [1,3], classes [1,3]
        val boxes = floatArrayOf(
            10f, 10f, 110f, 210f,
            300f, 300f, 400f, 500f,
            0f, 0f, 5f, 5f,
        )
        val scores = floatArrayOf(0.8f, 0.9f, 0.1f)
        val classes = floatArrayOf(0f, 2f, 5f)

        val dets = YoloPostProcessor.parse(
            listOf(
                longArrayOf(1, 3, 4) to boxes,
                longArrayOf(1, 3) to scores,
                longArrayOf(1, 3) to classes,
            )
        )

        assertEquals(2, dets.size) // third one below confidence threshold
        assertEquals("car", dets[0].label)  // highest score first
        assertEquals("person", dets[1].label)
    }

    @Test
    fun `nms suppresses overlapping boxes of the same class`() {
        val a = Detection(0f, 0f, 100f, 100f, 0.9f, 0, "person")
        val b = Detection(5f, 5f, 105f, 105f, 0.7f, 0, "person") // heavy overlap
        val c = Detection(300f, 300f, 400f, 400f, 0.6f, 0, "person") // separate

        val kept = YoloPostProcessor.nms(listOf(a, b, c), iouThreshold = 0.5f)

        assertEquals(2, kept.size)
        assertTrue(kept.contains(a))
        assertTrue(kept.contains(c))
    }

    @Test
    fun `nms keeps overlapping boxes of different classes`() {
        val person = Detection(0f, 0f, 100f, 100f, 0.9f, 0, "person")
        val chair = Detection(5f, 5f, 105f, 105f, 0.7f, 56, "chair")

        val kept = YoloPostProcessor.nms(listOf(person, chair), iouThreshold = 0.5f)
        assertEquals(2, kept.size)
    }
}
