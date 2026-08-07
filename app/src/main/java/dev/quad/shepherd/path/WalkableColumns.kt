package dev.quad.shepherd.path

/**
 * The Wayfinder branch's proven signal, ported: per-column walkable
 * FRACTION from the segmentation mask, computed in IMAGE space within a
 * body-height band. Its virtue is being projection-free — no camera
 * height, pitch, or depth scale involved — which makes it immune to the
 * failure modes that can poison the geometric grid. Used by the planner
 * as a second opinion: a STOP stands only when this channel also sees no
 * way out.
 *
 * Pure Kotlin for JVM unit testing.
 */
object WalkableColumns {

    const val NUM_COLUMNS = 16

    /** Body-height band, fractions of mask height (Wayfinder's tunables). */
    private const val BAND_TOP = 0.35f
    private const val BAND_BOTTOM = 0.90f

    /**
     * @param classMask row-major class ids, maskW x maskH.
     * @param walkableClass per-class walkability lookup.
     * @return per-column walkable fraction, 0..1, left to right.
     */
    fun clearance(
        classMask: ByteArray,
        maskW: Int,
        maskH: Int,
        walkableClass: BooleanArray,
    ): FloatArray {
        val out = FloatArray(NUM_COLUMNS)
        val y0 = (maskH * BAND_TOP).toInt().coerceIn(0, maskH - 1)
        val y1 = (maskH * BAND_BOTTOM).toInt().coerceIn(y0 + 1, maskH)
        val colW = maskW.toFloat() / NUM_COLUMNS
        for (c in 0 until NUM_COLUMNS) {
            val x0 = (c * colW).toInt()
            val x1 = ((c + 1) * colW).toInt().coerceAtMost(maskW)
            var walkable = 0
            var total = 0
            for (y in y0 until y1) {
                val row = y * maskW
                for (x in x0 until x1) {
                    total++
                    val cls = classMask[row + x].toInt()
                    if (cls in walkableClass.indices && walkableClass[cls]) walkable++
                }
            }
            out[c] = if (total > 0) walkable.toFloat() / total else 0f
        }
        return out
    }
}
