package com.wayfinder.app.perception.seg

/**
 * Binary walkable mask over a frame. `1 = walkable` (road, sidewalk, clear floor),
 * `0 = blocked` (wall, person, obstacle). Row 0 is the TOP of the image.
 *
 * Produced either by a real segmentation model (see [MaskBuilder]) or by the
 * [SyntheticSegmentationRunner] placeholder.
 */
class WalkableMask(
    val width: Int,
    val height: Int,
    val pixels: ByteArray, // length == width*height; 1 = walkable, 0 = blocked
) {
    init {
        require(pixels.size == width * height) {
            "mask pixels ${pixels.size} != width*height ${width * height}"
        }
    }

    fun isWalkable(x: Int, y: Int): Boolean = pixels[y * width + x] == WALKABLE

    companion object {
        const val WALKABLE: Byte = 1
        const val BLOCKED: Byte = 0
    }
}
