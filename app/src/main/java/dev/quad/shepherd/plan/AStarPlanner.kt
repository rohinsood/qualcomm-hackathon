package dev.quad.shepherd.plan

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Grid A* over a [CostMap], plus the string-pulling that turns its output
 * into something a person can actually walk.
 *
 * This is the piece [dev.quad.shepherd.path.PolarPlanner] cannot be. That
 * planner picks the best-looking gap in the current field of view, which
 * is the correct *reflex* and a hopeless *plan*: walk it into a doorway
 * recess, a fenced corner, or a van parked across the pavement and it will
 * steer into the concavity, find every direction blocked, and stop. A
 * search over remembered space goes round.
 *
 * Three details earn their keep:
 *
 *  - **Corner cutting is forbidden.** A diagonal step between two blocked
 *    orthogonal neighbours is free on an 8-connected grid and impossible
 *    in a corridor. Allowing it plans paths through the gap between a wall
 *    and a bollard.
 *  - **The heuristic is scaled by the cheapest cell in the map**, not by 1.
 *    With a discount for visited ground the minimum cost drops below 1,
 *    and an unscaled octile heuristic stops being admissible — A* would
 *    return confidently sub-optimal paths.
 *  - **The result is smoothed by line of sight.** Raw 8-connected output
 *    is a staircase, and a staircase bearing oscillates ±45 deg every few
 *    steps. Pulling the string taut against the obstacle map gives the
 *    any-angle path the follower needs.
 *
 * Pure Kotlin for JVM unit testing.
 */
class AStarPlanner {

    companion object {
        /** Search ceiling; a 150x150 window is 22 500 cells total. */
        const val MAX_EXPANSIONS = 60_000

        val SQRT2 = sqrt(2f)
    }

    /** A planned route through the costmap, in world metres. */
    class Path(
        /** Waypoints as [x, y] world metres, start first. */
        val points: List<DoubleArray>,
        val costToGo: Float,
        val expansions: Int,
        /** True when the goal cell itself was unreachable and the plan
         *  aims at the closest reachable cell instead. */
        val partial: Boolean,
    ) {
        val isEmpty: Boolean get() = points.size < 2

        /** Total path length in metres. */
        fun lengthM(): Double {
            var d = 0.0
            for (i in 1 until points.size) {
                d += Math.hypot(
                    points[i][0] - points[i - 1][0],
                    points[i][1] - points[i - 1][1],
                )
            }
            return d
        }
    }

    // Reusable search buffers — a replan several times a second should not
    // allocate three arrays the size of the map each time.
    private var g = FloatArray(0)
    private var f = FloatArray(0)
    private var parent = IntArray(0)
    private var closed = BooleanArray(0)
    private var heap = IntArray(0)
    private var heapPos = IntArray(0)
    private var heapSize = 0

    private fun ensureCapacity(n: Int) {
        if (g.size >= n) return
        g = FloatArray(n)
        f = FloatArray(n)
        parent = IntArray(n)
        closed = BooleanArray(n)
        heap = IntArray(n + 1)
        heapPos = IntArray(n)
    }

    /**
     * @param startX/[startY] and [goalX]/[goalY] in world metres.
     * @return null when the start itself is untenable or nothing at all
     *   was reachable — the caller should fall back to the raw route
     *   bearing and let the reactive layer cope.
     */
    fun plan(
        cm: CostMap,
        startX: Double,
        startY: Double,
        goalX: Double,
        goalY: Double,
    ): Path? {
        val n = cm.width * cm.height
        ensureCapacity(n)

        val sx = cm.indexX(startX)
        val sy = cm.indexY(startY)
        if (!cm.inBounds(sx, sy)) return null

        // The user is where the user is. If the map says they are standing
        // inside an obstacle — stale evidence, a pose jump, someone brushing
        // past — the search must still start, or guidance dies exactly when
        // it is needed. Nudge to the nearest tenable cell instead.
        val start = nearestPassable(cm, sx, sy, maxRadius = 6)
            ?: return null

        val gx0 = cm.indexX(goalX).coerceIn(0, cm.width - 1)
        val gy0 = cm.indexY(goalY).coerceIn(0, cm.height - 1)
        val goal = nearestPassable(cm, gx0, gy0, maxRadius = 12) ?: -1

        java.util.Arrays.fill(g, 0, n, Float.MAX_VALUE)
        java.util.Arrays.fill(closed, 0, n, false)
        java.util.Arrays.fill(parent, 0, n, -1)
        heapSize = 0

        val hScale = max(0.05f, cm.minCost())
        val goalIx = if (goal >= 0) goal % cm.width else gx0
        val goalIy = if (goal >= 0) goal / cm.width else gy0

        g[start] = 0f
        f[start] = heuristic(start % cm.width, start / cm.width, goalIx, goalIy, hScale)
        push(start)

        var expansions = 0
        var best = start
        var bestH = f[start]
        var reached = false

        while (heapSize > 0 && expansions < MAX_EXPANSIONS) {
            val cur = pop()
            if (closed[cur]) continue
            closed[cur] = true
            expansions++

            val cx = cur % cm.width
            val cy = cur / cm.width
            if (goal >= 0 && cur == goal) {
                reached = true
                best = cur
                break
            }
            val h = heuristic(cx, cy, goalIx, goalIy, hScale)
            if (h < bestH) {
                bestH = h
                best = cur
            }

            for (k in 0 until 8) {
                val nx = cx + DX[k]
                val ny = cy + DY[k]
                if (!cm.inBounds(nx, ny)) continue
                val ni = ny * cm.width + nx
                if (closed[ni]) continue
                val stepCost = cm.cost[ni]
                if (stepCost == CostMap.IMPASSABLE) continue

                val diagonal = k >= 4
                if (diagonal) {
                    // No squeezing between two blocked orthogonals
                    if (!cm.passable(cx + DX[k], cy) || !cm.passable(cx, cy + DY[k])) continue
                }
                val step = if (diagonal) SQRT2 else 1f
                val tentative = g[cur] + stepCost * step
                if (tentative < g[ni]) {
                    g[ni] = tentative
                    parent[ni] = cur
                    f[ni] = tentative + heuristic(nx, ny, goalIx, goalIy, hScale)
                    push(ni)
                }
            }
        }

        // Reached the goal, or ran out of map/budget and took the closest
        // cell the search actually got to. A partial plan toward the goal
        // still beats no plan at all.
        val end = best
        if (end == start && !reached) return null

        val cells = ArrayList<Int>()
        var c = end
        var guard = 0
        while (c >= 0 && guard++ < n) {
            cells.add(c)
            c = parent[c]
        }
        cells.reverse()
        if (cells.size < 2) return null

        val smoothed = smooth(cm, cells)
        return Path(
            points = smoothed.map {
                doubleArrayOf(cm.worldX(it % cm.width), cm.worldY(it / cm.width))
            },
            costToGo = g[end],
            expansions = expansions,
            partial = !reached,
        )
    }

    /**
     * Spiral outward for a cell that can be stood in. Bounded, because an
     * unbounded search here would happily walk the user across the map to
     * reach a goal that is inside a building.
     */
    private fun nearestPassable(cm: CostMap, ix: Int, iy: Int, maxRadius: Int): Int? {
        if (cm.passable(ix, iy)) return iy * cm.width + ix
        for (r in 1..maxRadius) {
            for (dy in -r..r) {
                for (dx in -r..r) {
                    if (max(abs(dx), abs(dy)) != r) continue
                    val nx = ix + dx
                    val ny = iy + dy
                    if (cm.inBounds(nx, ny) && cm.passable(nx, ny)) return ny * cm.width + nx
                }
            }
        }
        return null
    }

    /** Octile distance, scaled so it can never overestimate. */
    private fun heuristic(x: Int, y: Int, gx: Int, gy: Int, scale: Float): Float {
        val dx = abs(x - gx).toFloat()
        val dy = abs(y - gy).toFloat()
        return scale * (max(dx, dy) + (SQRT2 - 1f) * min(dx, dy))
    }

    /**
     * String-pulling: keep only the cells where the straight line to the
     * next kept cell would clip something.
     */
    private fun smooth(cm: CostMap, cells: List<Int>): List<Int> {
        if (cells.size <= 2) return cells
        val out = ArrayList<Int>()
        out.add(cells.first())
        var anchor = 0
        var probe = 1
        while (probe < cells.size) {
            if (probe == cells.size - 1) {
                out.add(cells[probe])
                break
            }
            val next = probe + 1
            if (!lineOfSight(cm, cells[anchor], cells[next])) {
                out.add(cells[probe])
                anchor = probe
            }
            probe = next
        }
        return out
    }

    /** Supercover line walk: any impassable cell on the segment fails. */
    fun lineOfSight(cm: CostMap, from: Int, to: Int): Boolean {
        var x0 = from % cm.width
        var y0 = from / cm.width
        val x1 = to % cm.width
        val y1 = to / cm.width
        var dx = abs(x1 - x0)
        var dy = abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx - dy
        var guard = 0
        val limit = dx + dy + 2
        while (guard++ < limit) {
            if (!cm.passable(x0, y0)) return false
            if (x0 == x1 && y0 == y1) return true
            val e2 = 2 * err
            if (e2 > -dy && e2 < dx) {
                // Stepping diagonally: both orthogonal neighbours must be
                // clear, same reason the search refuses corner cuts.
                if (!cm.passable(x0 + sx, y0) || !cm.passable(x0, y0 + sy)) return false
            }
            if (e2 > -dy) {
                err -= dy
                x0 += sx
            }
            if (e2 < dx) {
                err += dx
                y0 += sy
            }
        }
        // Ran out of steps without arriving. Fail CLOSED: claiming a clear
        // line here would let the smoother replace a path that went round
        // an obstacle with one that goes through it.
        return false
    }

    // ---- binary heap on f ------------------------------------------------

    private fun push(i: Int) {
        heapSize++
        var k = heapSize
        heap[k] = i
        heapPos[i] = k
        while (k > 1) {
            val p = k / 2
            if (f[heap[p]] <= f[heap[k]]) break
            val t = heap[p]; heap[p] = heap[k]; heap[k] = t
            heapPos[heap[p]] = p
            heapPos[heap[k]] = k
            k = p
        }
    }

    private fun pop(): Int {
        val top = heap[1]
        heap[1] = heap[heapSize]
        heapPos[heap[1]] = 1
        heapSize--
        var k = 1
        while (true) {
            val l = k * 2
            val r = l + 1
            var m = k
            if (l <= heapSize && f[heap[l]] < f[heap[m]]) m = l
            if (r <= heapSize && f[heap[r]] < f[heap[m]]) m = r
            if (m == k) break
            val t = heap[m]; heap[m] = heap[k]; heap[k] = t
            heapPos[heap[m]] = m
            heapPos[heap[k]] = k
            k = m
        }
        return top
    }

    private val DX = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
    private val DY = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
}
