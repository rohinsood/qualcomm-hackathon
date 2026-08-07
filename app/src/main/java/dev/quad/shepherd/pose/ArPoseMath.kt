package dev.quad.shepherd.pose

import dev.quad.shepherd.world.Angles
import kotlin.math.asin
import kotlin.math.atan2

/**
 * ARCore's 6-DoF camera pose reduced to the four numbers the areamap and
 * the depth projection need.
 *
 * Kept pure and separate from [ArCoreTracker] precisely because this is
 * the part that silently ruins everything downstream if a sign is wrong,
 * and it is the only part that can be tested without a phone in hand.
 *
 * ## Conventions being bridged
 *
 * **ARCore world**: Y up (gravity-aligned), right-handed, origin at
 * session start. The camera looks down its own **-Z**, with +X right and
 * +Y up. `xAxis`/`yAxis`/`zAxis` are those camera axes expressed in world
 * coordinates.
 *
 * **[dev.quad.shepherd.world.Pose2d]**: `x` = world X, `y` = world -Z,
 * bearings clockwise from +y. So at session start the camera sits at the
 * origin on a bearing of zero.
 *
 * **[dev.quad.shepherd.map.ScanBuilder]**: wants pitch positive *down*
 * from horizontal, and a roll that its un-roll step can cancel.
 */
object ArPoseMath {

    /** Planar position: world X, and world -Z as "forward at start". */
    fun planarX(tx: Float): Double = tx.toDouble()

    fun planarY(tz: Float): Double = -tz.toDouble()

    /**
     * Camera bearing within the AR frame, clockwise from +y.
     *
     * Forward is `-zAxis`, so in planar terms `(-zAxis.x, +zAxis.z)`, and
     * a compass-style bearing is `atan2(right, forward)`.
     *
     * Session start has `zAxis = (0, 0, 1)` giving zero; turning to face
     * world +X gives `zAxis = (-1, 0, 0)` and a bearing of +90.
     */
    fun bearingRad(zAxis: FloatArray): Float =
        atan2(-zAxis[0].toDouble(), zAxis[2].toDouble()).toFloat()

    /**
     * Pitch below horizontal, positive = pointing down.
     *
     * The forward vector's vertical component is `-zAxis.y`, so a camera
     * tilted toward the floor has `zAxis.y > 0`.
     */
    fun pitchRad(zAxis: FloatArray): Float =
        asin(zAxis[1].coerceIn(-1f, 1f).toDouble()).toFloat()

    /**
     * Roll about the optical axis, in the sense ScanBuilder cancels.
     *
     * ScanBuilder un-rolls with
     * ```
     * xc  =  xi*cosR + yi*sinR
     * yUp = -xi*sinR + yi*cosR
     * ```
     * which is a rotation of image coordinates by **-R**. For `yUp` to
     * come out as the true vertical, the coefficients must line up with
     * the world-Y components of the camera's own axes: a point at image
     * offset `(xi, yi)` points along `xi*xAxis + yi*yAxis`, whose vertical
     * part is `xi*xAxis.y + yi*yAxis.y`. Matching term by term gives
     * `sin R ∝ -xAxis.y` and `cos R ∝ yAxis.y`.
     *
     * Upright is `xAxis.y = 0, yAxis.y = 1` -> zero. Rolled a quarter turn
     * clockwise (the camera's right edge swung down to `xAxis = (0,-1,0)`)
     * gives +90, and ScanBuilder then rotates the image back the other
     * way, which is what puts gravity back where it belongs.
     */
    fun rollRad(xAxis: FloatArray, yAxis: FloatArray): Float =
        atan2(-xAxis[1].toDouble(), yAxis[1].toDouble()).toFloat()

    /**
     * Horizontal distance between two ARCore translations. Used to catch
     * relocalisation jumps: ARCore can resume tracking having decided it
     * is somewhere else, and stamping depth through a discontinuity like
     * that smears a copy of the world across the map.
     */
    fun planarJumpM(
        fromTx: Float, fromTz: Float,
        toTx: Float, toTz: Float,
    ): Double = Math.hypot((toTx - fromTx).toDouble(), (toTz - fromTz).toDouble())

    /**
     * Scale a camera-image focal length to a depth image of a different
     * resolution. ARCore's depth frames are much smaller than the colour
     * frames but cover the same field of view, so the intrinsics scale
     * linearly — and getting this wrong scales the whole map.
     */
    fun scaleFocalLength(imageFx: Float, imageWidth: Int, depthWidth: Int): Float =
        if (imageWidth <= 0) imageFx else imageFx * depthWidth / imageWidth

    /**
     * Signed turn from one AR-frame bearing to another, degrees. Wrapping
     * lives in [Angles]; this exists so callers do not re-implement it.
     */
    fun turnDeg(fromRad: Float, toRad: Float): Float =
        Math.toDegrees(Angles.wrapRad(toRad - fromRad).toDouble()).toFloat()
}
