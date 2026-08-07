package com.example.qhackgps.scan

/**
 * Decodes YOLO-family detector outputs into [Detection]s.
 *
 * Handles the two layouts Qualcomm AI Hub exports produce:
 *  - "split" exports: boxes [1, N, 4] (xyxy), scores [1, N], class ids [1, N]
 *  - raw head output: [1, 4 + numClasses, N] (cx, cy, w, h + per-class scores)
 *
 * Pure Kotlin so it can be unit-tested on the JVM.
 */
object YoloPostProcessor {

    const val INPUT_SIZE = 640f

    /**
     * @param tensors model outputs in the order the runtime returned them,
     *   each as (shape, flattened float data).
     */
    fun parse(
        tensors: List<Pair<LongArray, FloatArray>>,
        confThreshold: Float = 0.45f,
        iouThreshold: Float = 0.5f,
    ): List<Detection> {
        val raw = tensors.firstOrNull { (shape, _) ->
            shape.size == 3 && shape[0] == 1L && shape[1] in 5..300 && shape[2] > shape[1]
        }
        val detections = if (raw != null) {
            decodeRaw(raw.second, channels = raw.first[1].toInt(), anchors = raw.first[2].toInt(), confThreshold)
        } else {
            decodeSplit(tensors, confThreshold)
        }
        return nms(detections, iouThreshold)
    }

    /** Raw head: data laid out channel-major, [4 + numClasses][anchors]. */
    internal fun decodeRaw(
        data: FloatArray,
        channels: Int,
        anchors: Int,
        confThreshold: Float,
    ): List<Detection> {
        val numClasses = channels - 4
        val out = ArrayList<Detection>()
        for (a in 0 until anchors) {
            var bestScore = 0f
            var bestClass = -1
            for (c in 0 until numClasses) {
                val s = data[(4 + c) * anchors + a]
                if (s > bestScore) {
                    bestScore = s
                    bestClass = c
                }
            }
            if (bestScore < confThreshold) continue
            val cx = data[a]
            val cy = data[anchors + a]
            val w = data[2 * anchors + a]
            val h = data[3 * anchors + a]
            out += Detection(
                x1 = cx - w / 2f, y1 = cy - h / 2f,
                x2 = cx + w / 2f, y2 = cy + h / 2f,
                score = bestScore, classId = bestClass,
                label = CocoLabels.label(bestClass),
            )
        }
        return out
    }

    /** Split export: (boxes [1,N,4] xyxy, scores [1,N], class ids [1,N]) in output order. */
    internal fun decodeSplit(
        tensors: List<Pair<LongArray, FloatArray>>,
        confThreshold: Float,
    ): List<Detection> {
        val boxesT = tensors.firstOrNull { (s, _) -> s.isNotEmpty() && s.last() == 4L && elementCount(s) % 4 == 0L }
            ?: return emptyList()
        val flat = tensors.filter { it !== boxesT && elementCount(it.first) * 4 == elementCount(boxesT.first) }
        if (flat.isEmpty()) return emptyList()
        val scores = flat[0].second
        val classes = flat.getOrNull(1)?.second

        val boxes = boxesT.second
        val n = (elementCount(boxesT.first) / 4).toInt()
        // Some exports emit normalized 0..1 boxes; scale up if so.
        val scale = if (boxes.take(minOf(boxes.size, 400)).all { it <= 1.5f }) INPUT_SIZE else 1f

        val out = ArrayList<Detection>()
        for (i in 0 until n) {
            val score = scores[i]
            if (score < confThreshold) continue
            val classId = classes?.get(i)?.toInt() ?: 0
            out += Detection(
                x1 = boxes[i * 4] * scale,
                y1 = boxes[i * 4 + 1] * scale,
                x2 = boxes[i * 4 + 2] * scale,
                y2 = boxes[i * 4 + 3] * scale,
                score = score, classId = classId,
                label = CocoLabels.label(classId),
            )
        }
        return out
    }

    /** Standard per-class non-maximum suppression. */
    internal fun nms(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        val kept = ArrayList<Detection>()
        for ((_, group) in detections.groupBy { it.classId }) {
            val sorted = group.sortedByDescending { it.score }.toMutableList()
            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                kept += best
                sorted.removeAll { it.iou(best) > iouThreshold }
            }
        }
        return kept.sortedByDescending { it.score }
    }

    private fun elementCount(shape: LongArray): Long =
        shape.fold(1L) { acc, d -> acc * d }
}
