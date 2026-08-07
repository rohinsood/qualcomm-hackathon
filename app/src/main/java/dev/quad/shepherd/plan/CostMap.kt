package dev.quad.shepherd.plan

import dev.quad.shepherd.map.AreaMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The areamap turned into something A* can search: a coarser grid whose
 * every cell carries a traversal cost.
 *
 * Two deliberate reductions happen here.
 *
 * **Resolution.** The map stores 10 cm cells because that is what it takes
 * to tell a kerb from a step. Searching at 10 cm over tens of metres is
 * pointless — a person is not a 10 cm object and cannot follow a 10 cm
 * decision. Planning runs at 40 cm, taking the *worst* occupancy in each
 * block so downsampling can only ever be conservative.
 *
 * **Extent.** The window is local (~60 m), not "as far as the
 * destination", because the destination is not this planner's problem.
 * Google's walking route already solved streets and crossings; what it
 * cannot know is that a van is parked across the pavement. So the goal
 * handed to A* is the route's look-ahead point, and the search only has
 * to be big enough to get around whatever is in the way of it. That keeps
 * the grid at ~22 000 cells — replannable several times a second — instead
 * of the millions a plan-to-destination window would need.
 *
 * Costs, all additive on a base of 1 per cell:
 *
 *  - **Inflation.** Cells within a body radius of an obstacle are
 *    impassable; beyond that the cost falls off linearly over a clearance
 *    band, so paths prefer the middle of a corridor to scraping a wall.
 *  - **Unknown.** Penalised but *passable*. Refusing unseen ground sounds
 *    safe and is not: at startup the entire world is unknown, and a
 *    planner that will not enter it never moves.
 *  - **Visited.** A small discount for ground the user has physically
 *    walked on — the only cells in the map with ground truth behind them.
 *  - **Corridor.** A gentle pull toward the route polyline, so the planner
 *    deviates around an obstacle and then comes back rather than
 *    wandering off down an open plaza.
 *
 * Pure Kotlin for JVM unit testing.
 */
class CostMap(
    /** World metres at the centre of plan cell (0, 0). */
    originX: Double,
    originY: Double,
    val width: Int,
    val height: Int,
    val cellMeters: Float,
) {

    companion object {
        const val IMPASSABLE = Float.POSITIVE_INFINITY
        const val BASE_COST = 1f

        const val DEFAULT_PLAN_CELL_M = 0.4f
        const val DEFAULT_HALF_SPAN_M = 30f

        /** Half the shoulder width of a walking adult, plus a margin. */
        const val ROBOT_RADIUS_M = 0.35f

        /** Falloff band beyond the hard radius. */
        const val CLEARANCE_M = 0.9f
        const val INFLATION_WEIGHT = 2.2f

        const val UNKNOWN_PENALTY = 0.6f
        const val VISITED_BONUS = 0.25f

        /** No corridor cost within this distance of the route. */
        const val CORRIDOR_FREE_M = 5f
        const val CORRIDOR_WEIGHT = 0.06f

        /** Any evidence at all above this magnitude counts as "seen". */
        private const val KNOWN_EPS = 0.15f

        /**
         * Build from an areamap window.
         *
         * @param window a 10 cm snapshot centred on the user; reuse it
         *   across replans rather than reallocating.
         * @param routeAr the walking route in AR-frame metres, or null.
         */
        fun build(
            window: AreaMap.Window,
            planCellM: Float = DEFAULT_PLAN_CELL_M,
            robotRadiusM: Float = ROBOT_RADIUS_M,
            clearanceM: Float = CLEARANCE_M,
            unknownPenalty: Float = UNKNOWN_PENALTY,
            visitedBonus: Float = VISITED_BONUS,
            routeAr: List<DoubleArray>? = null,
            corridorFreeM: Float = CORRIDOR_FREE_M,
            corridorWeight: Float = CORRIDOR_WEIGHT,
            reuse: CostMap? = null,
        ): CostMap {
            val ratio = max(1, Math.round(planCellM / window.cellMeters))
            val w = window.width / ratio
            val h = window.height / ratio
            val cellM = window.cellMeters * ratio
            val ox = (window.originCellX + ratio / 2.0) * window.cellMeters
            val oy = (window.originCellY + ratio / 2.0) * window.cellMeters

            val cm = if (reuse != null && reuse.width == w && reuse.height == h) {
                reuse.rebase(ox, oy)
            } else {
                CostMap(ox, oy, w, h, cellM)
            }

            // ---- downsample, worst case wins --------------------------
            for (py in 0 until h) {
                for (px in 0 until w) {
                    var worst = 0f
                    var visited = false
                    var known = false
                    val bx = px * ratio
                    val by = py * ratio
                    for (sy in by until min(by + ratio, window.height)) {
                        val row = sy * window.width
                        for (sx in bx until min(bx + ratio, window.width)) {
                            val v = window.logOdds[row + sx]
                            if (v > worst) worst = v
                            if (v > KNOWN_EPS || v < -KNOWN_EPS) known = true
                            if (window.visited[row + sx].toInt() != 0) visited = true
                        }
                    }
                    val i = py * w + px
                    cm.occupancy[i] = worst
                    cm.blocked[i] = worst > AreaMap.OCCUPIED_THRESHOLD
                    cm.known[i] = known
                    cm.visited[i] = visited
                }
            }

            cm.distanceTransform()

            // ---- costs -------------------------------------------------
            val segs = routeAr?.let { clipRoute(it, cm) }
            for (i in 0 until w * h) {
                if (cm.blocked[i] || cm.distM[i] < robotRadiusM) {
                    cm.cost[i] = IMPASSABLE
                    continue
                }
                var c = BASE_COST
                val slack = cm.distM[i] - robotRadiusM
                if (slack < clearanceM) {
                    c += INFLATION_WEIGHT * (1f - slack / clearanceM)
                }
                if (!cm.known[i]) c += unknownPenalty
                if (cm.visited[i]) c -= visitedBonus
                if (segs != null && segs.isNotEmpty()) {
                    val px = i % w
                    val py = i / w
                    val d = distanceToRoute(cm.worldX(px), cm.worldY(py), segs)
                    if (d > corridorFreeM) c += corridorWeight * (d - corridorFreeM).toFloat()
                }
                cm.cost[i] = max(0.05f, c)
            }
            return cm
        }

        /** Route segments with any chance of touching the window. */
        private fun clipRoute(route: List<DoubleArray>, cm: CostMap): List<DoubleArray> {
            if (route.size < 2) return emptyList()
            val pad = 40.0
            val minX = cm.x0 - pad
            val maxX = cm.x0 + cm.width * cm.cellMeters + pad
            val minY = cm.y0 - pad
            val maxY = cm.y0 + cm.height * cm.cellMeters + pad
            val out = ArrayList<DoubleArray>()
            for (i in 0 until route.size - 1) {
                val a = route[i]
                val b = route[i + 1]
                if (max(a[0], b[0]) < minX || min(a[0], b[0]) > maxX) continue
                if (max(a[1], b[1]) < minY || min(a[1], b[1]) > maxY) continue
                out += doubleArrayOf(a[0], a[1], b[0], b[1])
            }
            return out
        }

        /** Shortest distance from a point to any of the clipped segments. */
        fun distanceToRoute(x: Double, y: Double, segs: List<DoubleArray>): Double {
            var best = Double.MAX_VALUE
            for (s in segs) {
                val dx = s[2] - s[0]
                val dy = s[3] - s[1]
                val len2 = dx * dx + dy * dy
                val t = if (len2 <= 0.0) 0.0
                else (((x - s[0]) * dx + (y - s[1]) * dy) / len2).coerceIn(0.0, 1.0)
                val cx = s[0] + t * dx
                val cy = s[1] + t * dy
                val d = Math.hypot(x - cx, y - cy)
                if (d < best) best = d
            }
            return if (best == Double.MAX_VALUE) 0.0 else best
        }
    }

    val cost = FloatArray(width * height)
    val occupancy = FloatArray(width * height)
    val blocked = BooleanArray(width * height)
    val known = BooleanArray(width * height)
    val visited = BooleanArray(width * height)

    /** Metres to the nearest blocked cell. */
    val distM = FloatArray(width * height)

    private var ox = originX
    private var oy = originY

    fun rebase(newOriginX: Double, newOriginY: Double): CostMap {
        ox = newOriginX
        oy = newOriginY
        return this
    }

    val x0: Double get() = ox
    val y0: Double get() = oy

    fun worldX(ix: Int): Double = ox + ix * cellMeters
    fun worldY(iy: Int): Double = oy + iy * cellMeters

    fun indexX(x: Double): Int = Math.round((x - ox) / cellMeters).toInt()
    fun indexY(y: Double): Int = Math.round((y - oy) / cellMeters).toInt()

    fun inBounds(ix: Int, iy: Int): Boolean = ix in 0 until width && iy in 0 until height

    fun costAt(ix: Int, iy: Int): Float =
        if (inBounds(ix, iy)) cost[iy * width + ix] else IMPASSABLE

    fun passable(ix: Int, iy: Int): Boolean = costAt(ix, iy) < IMPASSABLE

    /** Cheapest cell cost anywhere — keeps the A* heuristic admissible. */
    fun minCost(): Float {
        var m = Float.MAX_VALUE
        for (c in cost) if (c < m) m = c
        return if (m == Float.MAX_VALUE) BASE_COST else m
    }

    /**
     * Two-pass chamfer distance to the nearest blocked cell. Exact enough
     * for inflation (a few percent) at a fraction of the cost of a true
     * Euclidean transform, and inflation is a soft preference anyway.
     */
    private fun distanceTransform() {
        val big = 1e9f
        val d = distM
        for (i in 0 until width * height) d[i] = if (blocked[i]) 0f else big

        val ortho = 1f
        val diag = sqrt(2f)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                var v = d[i]
                if (x > 0) v = min(v, d[i - 1] + ortho)
                if (y > 0) v = min(v, d[i - width] + ortho)
                if (x > 0 && y > 0) v = min(v, d[i - width - 1] + diag)
                if (x < width - 1 && y > 0) v = min(v, d[i - width + 1] + diag)
                d[i] = v
            }
        }
        for (y in height - 1 downTo 0) {
            for (x in width - 1 downTo 0) {
                val i = y * width + x
                var v = d[i]
                if (x < width - 1) v = min(v, d[i + 1] + ortho)
                if (y < height - 1) v = min(v, d[i + width] + ortho)
                if (x < width - 1 && y < height - 1) v = min(v, d[i + width + 1] + diag)
                if (x > 0 && y < height - 1) v = min(v, d[i + width - 1] + diag)
                d[i] = v
            }
        }
        for (i in 0 until width * height) d[i] *= cellMeters
    }
}
