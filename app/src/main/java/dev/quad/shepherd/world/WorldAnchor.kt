package dev.quad.shepherd.world

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The rigid transform between the **AR world frame** (where the areamap
 * lives) and the **ENU metre frame** (where the Google route lives).
 *
 * Only three numbers separate them — a bearing and a translation — because
 * both frames are metric and gravity-aligned, so there is no scale and no
 * tilt to solve for:
 *
 * ```
 * east  =  x·cos θ + y·sin θ + tE
 * north = -x·sin θ + y·cos θ + tN
 * ```
 *
 * where θ is the true-north bearing of the AR frame's +Y axis. A bearing
 * converts by simple addition: `enuBearing = arBearing + θ`.
 *
 * θ is seeded from the compass — instantly available, but worth ±10-20°
 * anywhere near rebar or a car door. It is then refined by fitting the
 * ARCore trajectory against GPS fixes ([solve]), which is a far better
 * estimator because it uses the *shape* of a walked path rather than a
 * magnetometer reading, and gets better the further you walk.
 *
 * Corrections are applied through [slewTowards] rather than assigned. The
 * map itself never moves — it is stored in the AR frame precisely so that
 * it doesn't — but the route projected into map space does, and teleporting
 * it would yank the guidance sideways mid-stride.
 *
 * Pure Kotlin for JVM unit testing.
 */
class WorldAnchor(
    /** True-north bearing of the AR frame's +Y axis, radians. */
    var thetaRad: Float = 0f,
    /** ENU position of the AR frame's origin, metres. */
    var tEast: Double = 0.0,
    var tNorth: Double = 0.0,
) {

    companion object {
        /** Fits below this trajectory spread can't determine a bearing:
         *  rotating on the spot leaves θ unobservable, and a fit from a
         *  3 m stroll is noise wearing a confidence interval. */
        const val MIN_SPREAD_M = 12.0

        /** Correspondences retained for the fit. */
        const val MAX_SAMPLES = 240

        /** GPS worse than this never enters the fit. */
        const val MAX_GPS_ACCURACY_M = 25f

        /** Slew caps: how fast a correction is allowed to be applied. */
        const val MAX_THETA_RATE_RAD_S = 0.035f // ~2 deg/s
        const val MAX_TRANSLATION_RATE_M_S = 0.35

        /**
         * One correspondence: where ARCore said we were, and where GPS
         * said we were, at the same instant.
         */
        data class Sample(
            val x: Double,
            val y: Double,
            val east: Double,
            val north: Double,
            val weight: Double,
        )

        data class Fit(
            val thetaRad: Float,
            val tEast: Double,
            val tNorth: Double,
            /** RMS residual of the fit, metres — the honest quality signal. */
            val rmsM: Double,
            val spreadM: Double,
            val samples: Int,
        )

        /**
         * Weighted 2D Procrustes with the scale pinned at 1 (both frames
         * are already metric — letting scale float would let a bad GPS run
         * quietly rescale the world).
         *
         * Closed form: with the rotation above, the residual's only
         * θ-dependent term is `-2(A·cos θ + B·sin θ)`, maximised at
         * `θ = atan2(B, A)`.
         *
         * @return null when the samples are too few or too clustered to
         *   determine a bearing — an unobservable θ must be reported as
         *   unknown, never as a confident wrong answer.
         */
        fun solve(samples: List<Sample>): Fit? {
            if (samples.size < 8) return null
            var wSum = 0.0
            var xBar = 0.0
            var yBar = 0.0
            var eBar = 0.0
            var nBar = 0.0
            for (s in samples) {
                wSum += s.weight
                xBar += s.weight * s.x
                yBar += s.weight * s.y
                eBar += s.weight * s.east
                nBar += s.weight * s.north
            }
            if (wSum <= 0.0) return null
            xBar /= wSum; yBar /= wSum; eBar /= wSum; nBar /= wSum

            var a = 0.0
            var b = 0.0
            var spread2 = 0.0
            for (s in samples) {
                val dx = s.x - xBar
                val dy = s.y - yBar
                val de = s.east - eBar
                val dn = s.north - nBar
                a += s.weight * (de * dx + dn * dy)
                b += s.weight * (de * dy - dn * dx)
                spread2 = maxOf(spread2, dx * dx + dy * dy)
            }
            val spread = Math.sqrt(spread2)
            if (spread < MIN_SPREAD_M) return null
            if (a == 0.0 && b == 0.0) return null

            val theta = atan2(b, a)
            val c = cos(theta)
            val s0 = sin(theta)
            val tE = eBar - (xBar * c + yBar * s0)
            val tN = nBar - (-xBar * s0 + yBar * c)

            var sq = 0.0
            for (s in samples) {
                val pe = s.x * c + s.y * s0 + tE
                val pn = -s.x * s0 + s.y * c + tN
                sq += (s.east - pe) * (s.east - pe) + (s.north - pn) * (s.north - pn)
            }
            return Fit(
                thetaRad = theta.toFloat(),
                tEast = tE,
                tNorth = tN,
                rmsM = Math.sqrt(sq / samples.size),
                spreadM = spread,
                samples = samples.size,
            )
        }
    }

    private val samples = ArrayDeque<Sample>()

    /** True once a trajectory fit — not just the compass — has been taken. */
    var fitted = false
        private set

    var lastFit: Fit? = null
        private set

    // ---- transforms ------------------------------------------------------

    // The trig is evaluated in DOUBLE even though theta is a float. World
    // coordinates run to hundreds of metres, where float trig costs ~1e-5 m
    // — harmless on its own, but enough that toAr(toEastNorth(p)) != p, and
    // a transform that is not exactly its own inverse is a trap for every
    // caller that round-trips through ENU.
    private val cosTheta: Double get() = cos(thetaRad.toDouble())
    private val sinTheta: Double get() = sin(thetaRad.toDouble())

    fun toEast(x: Double, y: Double): Double = x * cosTheta + y * sinTheta + tEast

    fun toNorth(x: Double, y: Double): Double = -x * sinTheta + y * cosTheta + tNorth

    /** AR metres -> [east, north] metres. */
    fun toEastNorth(x: Double, y: Double): DoubleArray =
        doubleArrayOf(toEast(x, y), toNorth(x, y))

    /**
     * ENU metres -> AR metres. The inverse of a rotation is its transpose,
     * so this is the same three numbers read the other way.
     */
    fun toAr(east: Double, north: Double): DoubleArray {
        val de = east - tEast
        val dn = north - tNorth
        val c = cosTheta
        val s = sinTheta
        return doubleArrayOf(de * c - dn * s, de * s + dn * c)
    }

    /** AR-frame bearing -> true-north bearing, radians. */
    fun bearingToEnu(arBearingRad: Float): Float = Angles.wrapRad(arBearingRad + thetaRad)

    /** True-north bearing -> AR-frame bearing, radians. */
    fun bearingToAr(enuBearingRad: Float): Float = Angles.wrapRad(enuBearingRad - thetaRad)

    // ---- estimation ------------------------------------------------------

    /**
     * Seed θ from a simultaneous compass heading and AR-frame bearing.
     * Ignored once a trajectory fit exists — the magnetometer has nothing
     * to add to it, and indoors it actively subtracts.
     */
    fun seedFromCompass(arBearingRad: Float, trueHeadingRad: Float) {
        if (fitted) return
        thetaRad = Angles.wrapRad(trueHeadingRad - arBearingRad)
    }

    /**
     * Record one (AR pose, GPS fix) correspondence. Weight falls off with
     * the square of the reported accuracy, so a 4 m fix counts for ~39x a
     * 25 m one rather than being trusted equally.
     */
    fun addSample(x: Double, y: Double, east: Double, north: Double, accuracyM: Float) {
        if (!accuracyM.isFinite() || accuracyM <= 0f || accuracyM > MAX_GPS_ACCURACY_M) return
        val w = 1.0 / (accuracyM.toDouble() * accuracyM.toDouble())
        samples.addLast(Sample(x, y, east, north, w))
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
    }

    /**
     * Re-solve from the accumulated correspondences.
     *
     * @return the fit, or null when the trajectory still can't determine a
     *   bearing (too few fixes, or the user hasn't walked far enough).
     */
    fun refit(): Fit? {
        val fit = solve(samples.toList()) ?: return null
        lastFit = fit
        fitted = true
        return fit
    }

    /**
     * Move the live transform toward [target] by at most the slew caps.
     *
     * @return true once the remaining error is negligible.
     */
    fun slewTowards(target: Fit, dtSec: Float): Boolean {
        val dTheta = Angles.wrapRad(target.thetaRad - thetaRad)
        val maxTheta = MAX_THETA_RATE_RAD_S * dtSec
        val thetaStep = dTheta.coerceIn(-maxTheta, maxTheta)
        thetaRad = Angles.wrapRad(thetaRad + thetaStep)

        val dE = target.tEast - tEast
        val dN = target.tNorth - tNorth
        val dist = hypot(dE, dN)
        val maxT = MAX_TRANSLATION_RATE_M_S * dtSec
        if (dist > maxT && dist > 0.0) {
            tEast += dE / dist * maxT
            tNorth += dN / dist * maxT
        } else {
            tEast = target.tEast
            tNorth = target.tNorth
        }
        return abs(dTheta) <= maxTheta && dist <= maxT
    }

    /** Forget every correspondence — call when the AR epoch changes. */
    fun reset() {
        samples.clear()
        fitted = false
        lastFit = null
    }

    val sampleCount: Int get() = samples.size
}
