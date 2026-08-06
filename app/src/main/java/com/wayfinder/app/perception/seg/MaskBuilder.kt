package com.wayfinder.app.perception.seg

/**
 * Collapses a per-pixel class-index map (from a real semantic-segmentation model)
 * into a binary [WalkableMask]. Which classes count as "walkable" is configurable.
 *
 * Cityscapes class indices (common FastSCNN / DeepLabV3+ training) that we treat
 * as walkable ground: road(7), sidewalk(8). Everything else (wall, building,
 * person, pole, vegetation, fence, ...) is an obstacle.
 */
object MaskBuilder {

    val DEFAULT_WALKABLE_CLASSES: Set<Int> = setOf(
        7,  // road
        8,  // sidewalk
    )

    /**
     * @param classes  class index per pixel, length == width*height, row-major (row 0 = top)
     */
    fun build(
        classes: IntArray,
        width: Int,
        height: Int,
        walkable: Set<Int> = DEFAULT_WALKABLE_CLASSES,
    ): WalkableMask {
        require(classes.size == width * height)
        val pixels = ByteArray(width * height)
        for (i in classes.indices) {
            pixels[i] = if (classes[i] in walkable) WalkableMask.WALKABLE else WalkableMask.BLOCKED
        }
        return WalkableMask(width, height, pixels)
    }
}
