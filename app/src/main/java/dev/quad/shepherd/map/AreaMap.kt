package dev.quad.shepherd.map

import dev.quad.shepherd.world.Pose2d
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The areamap: a persistent, world-anchored occupancy map of everywhere
 * the user has walked, stored in the **AR world frame** (see [Pose2d] for
 * why that frame and not ENU).
 *
 * This is the piece [dev.quad.shepherd.path.TraversabilityGrid] only
 * pretended to be. That grid indexes cells relative to wherever the camera
 * is *right now* and is never ego-motion compensated, so it survives only
 * by decaying everything within a few seconds — which is also why it can
 * neither remember an obstacle you walked past nor represent a dead end
 * you already probed. Here every cell has a fixed world coordinate, so
 * evidence accumulates instead of smearing.
 *
 * **Sparse by tile.** Cells are 10 cm; tiles are 64x64 cells (6.4 m) held
 * in a hash keyed by tile coordinate, so memory tracks the area actually
 * explored rather than a bounding box. A kilometre of walking with a 10 m
 * swath is a few hundred tiles — single-digit megabytes.
 *
 * **Two evidence channels.** [Channel.STATIC] decays slowly and is what
 * the global planner routes over: walls, kerbs, parked cars, the shape of
 * a building. [Channel.DYNAMIC] decays in seconds and is what the reactive
 * layer reads: a person who just stepped in front of you. An observation
 * feeds both; only time separates them.
 *
 * **Rays, not points.** Each observation clears the cells *along* the beam
 * before marking the endpoint occupied. Without that, anything that moves
 * leaves a permanent ghost and the map slowly fills with obstacles that
 * are not there — the failure mode that makes naive occupancy maps useless
 * after five minutes.
 *
 * Decay is applied lazily, per tile, from an update counter: tiles nobody
 * is looking at hold their evidence rather than fading, because nothing
 * has contradicted them. That also keeps every update O(observed area)
 * instead of O(map).
 *
 * Not thread-safe: one writer (the perception thread) and readers that
 * take [snapshotWindow] under the same lock the caller already holds.
 *
 * Pure Kotlin for JVM unit testing.
 */
class AreaMap(
    val cellMeters: Float = CELL_M,
) {

    companion object {
        const val CELL_M = 0.1f
        const val TILE_CELLS = 32
        const val TILE_AREA = TILE_CELLS * TILE_CELLS

        /** Log-odds per observation. */
        const val L_OCCUPIED = 0.85f
        const val L_FREE = -0.40f
        const val L_CLAMP = 5.0f

        /** Standing somewhere is the strongest free-space evidence there is. */
        const val L_STOOD_HERE = -1.2f

        /** Above this a cell blocks; below the negative of it, it is known free. */
        const val OCCUPIED_THRESHOLD = 0.7f

        /** Per-second multiplicative decay for each channel. */
        const val STATIC_DECAY_PER_S = 0.985f
        const val DYNAMIC_DECAY_PER_S = 0.55f

        /** Beyond this a single observation is too uncertain to trust. */
        const val MAX_RANGE_M = 8.0f

        enum class Channel { STATIC, DYNAMIC }

        /** Pack signed 32-bit tile coordinates into one key. */
        fun tileKey(tx: Int, ty: Int): Long =
            (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
    }

    /**
     * One 32x32-cell patch of the world. Decay is stored as the timestamp
     * of its last touch rather than applied eagerly.
     */
    class Tile(val tx: Int, val ty: Int) {
        val static = FloatArray(TILE_AREA)
        val dynamic = FloatArray(TILE_AREA)

        /** 1 once the user has physically stood in this cell. */
        val visited = ByteArray(TILE_AREA)

        var lastTouchSec = 0.0
        var everVisited = false
    }

    private val tiles = HashMap<Long, Tile>()

    /** Monotonic seconds; the caller advances it once per integration. */
    var nowSec = 0.0
        private set

    /**
     * The AR epoch these cells belong to. Tracking that restarts into a
     * new world frame invalidates every coordinate in here.
     */
    var epoch = 0
        private set

    val tileCount: Int get() = tiles.size

    /** Cells the user has physically occupied — the walked trail. */
    var visitedCells = 0
        private set

    // ---- coordinates -----------------------------------------------------

    fun cellOf(meters: Double): Int = floor(meters / cellMeters).toInt()

    fun centerOfCell(cell: Int): Double = (cell + 0.5) * cellMeters

    private fun tileOf(cell: Int): Int = Math.floorDiv(cell, TILE_CELLS)

    private fun inTile(cell: Int): Int = Math.floorMod(cell, TILE_CELLS)

    // ---- lifecycle -------------------------------------------------------

    /** Advance the clock. Call once per integration pass, before writing. */
    fun advanceTo(seconds: Double) {
        if (seconds > nowSec) nowSec = seconds
    }

    /**
     * Tracking restarted into a new world frame: every stored coordinate
     * now refers to a frame that no longer exists. Carrying the cells over
     * would paint ghost walls across the new one.
     */
    fun resetForEpoch(newEpoch: Int) {
        tiles.clear()
        visitedCells = 0
        epoch = newEpoch
    }

    fun clear() {
        tiles.clear()
        visitedCells = 0
    }

    // ---- tile access -----------------------------------------------------

    private fun tileAt(cx: Int, cy: Int, create: Boolean): Tile? {
        val tx = tileOf(cx)
        val ty = tileOf(cy)
        val key = tileKey(tx, ty)
        var t = tiles[key]
        if (t == null) {
            if (!create) return null
            t = Tile(tx, ty)
            t.lastTouchSec = nowSec
            tiles[key] = t
        }
        decay(t)
        return t
    }

    /**
     * Apply however much decay this tile slept through. Doing it here
     * rather than sweeping every tile each frame is what keeps an update
     * proportional to the area observed rather than the area mapped.
     */
    private fun decay(t: Tile) {
        val dt = nowSec - t.lastTouchSec
        if (dt <= 0.0) return
        t.lastTouchSec = nowSec
        val fs = Math.pow(STATIC_DECAY_PER_S.toDouble(), dt).toFloat()
        val fd = Math.pow(DYNAMIC_DECAY_PER_S.toDouble(), dt).toFloat()
        // Below this the value is indistinguishable from unknown; snapping
        // to zero keeps long-idle tiles from carrying denormal noise.
        if (fs < 1e-3f && fd < 1e-3f) {
            java.util.Arrays.fill(t.static, 0f)
            java.util.Arrays.fill(t.dynamic, 0f)
            return
        }
        for (i in 0 until TILE_AREA) {
            t.static[i] *= fs
            t.dynamic[i] *= fd
        }
    }

    // ---- reads -----------------------------------------------------------

    /** Log-odds at a cell; 0 (unknown) where nothing has been observed. */
    fun logOddsAt(cx: Int, cy: Int, channel: Channel = Channel.STATIC): Float {
        val t = tileAt(cx, cy, create = false) ?: return 0f
        val i = inTile(cy) * TILE_CELLS + inTile(cx)
        return if (channel == Channel.STATIC) t.static[i] else t.dynamic[i]
    }

    fun logOddsAtMeters(x: Double, y: Double, channel: Channel = Channel.STATIC): Float =
        logOddsAt(cellOf(x), cellOf(y), channel)

    /** Occupied on either channel — what "is something there?" means. */
    fun isBlocked(cx: Int, cy: Int): Boolean =
        max(logOddsAt(cx, cy, Channel.STATIC), logOddsAt(cx, cy, Channel.DYNAMIC)) >
            OCCUPIED_THRESHOLD

    fun isKnownFree(cx: Int, cy: Int): Boolean =
        logOddsAt(cx, cy, Channel.STATIC) < -OCCUPIED_THRESHOLD

    fun hasVisited(cx: Int, cy: Int): Boolean {
        val t = tileAt(cx, cy, create = false) ?: return false
        return t.visited[inTile(cy) * TILE_CELLS + inTile(cx)].toInt() != 0
    }

    // ---- writes ----------------------------------------------------------

    private fun bump(cx: Int, cy: Int, deltaStatic: Float, deltaDynamic: Float) {
        val t = tileAt(cx, cy, create = true)!!
        val i = inTile(cy) * TILE_CELLS + inTile(cx)
        t.static[i] = (t.static[i] + deltaStatic).coerceIn(-L_CLAMP, L_CLAMP)
        t.dynamic[i] = (t.dynamic[i] + deltaDynamic).coerceIn(-L_CLAMP, L_CLAMP)
    }

    fun markOccupied(cx: Int, cy: Int, weight: Float = 1f) {
        bump(cx, cy, L_OCCUPIED * weight, L_OCCUPIED * weight)
    }

    fun markFree(cx: Int, cy: Int, weight: Float = 1f) {
        bump(cx, cy, L_FREE * weight, L_FREE * weight)
    }

    /**
     * The user is standing here, so this cell is walkable — the one piece
     * of ground truth in the whole map. Also records the trail the planner
     * uses to avoid pacing back over its own steps.
     */
    fun markStood(x: Double, y: Double, radiusM: Float = 0.25f) {
        val r = max(0, Math.round(radiusM / cellMeters))
        val c0x = cellOf(x)
        val c0y = cellOf(y)
        for (dy in -r..r) {
            for (dx in -r..r) {
                if (dx * dx + dy * dy > r * r) continue
                val cx = c0x + dx
                val cy = c0y + dy
                val t = tileAt(cx, cy, create = true)!!
                val i = inTile(cy) * TILE_CELLS + inTile(cx)
                t.static[i] = (t.static[i] + L_STOOD_HERE).coerceIn(-L_CLAMP, L_CLAMP)
                t.dynamic[i] = (t.dynamic[i] + L_STOOD_HERE).coerceIn(-L_CLAMP, L_CLAMP)
                if (t.visited[i].toInt() == 0) {
                    t.visited[i] = 1
                    t.everVisited = true
                    visitedCells++
                }
            }
        }
    }

    /**
     * Integrate one range measurement: clear the cells the beam passed
     * through, then mark where it stopped.
     *
     * @param bearingRad world bearing of the beam (clockwise from +Y).
     * @param rangeM distance to the obstacle, or [Float.NaN] for "saw
     *   nothing out to [freeToM]" — an empty beam still carries the very
     *   useful news that the corridor ahead is clear.
     * @param freeToM how far along the beam free space is trusted.
     */
    fun integrateRay(
        originX: Double,
        originY: Double,
        bearingRad: Float,
        rangeM: Float,
        freeToM: Float,
        weight: Float = 1f,
    ) {
        val hit = rangeM.isFinite() && rangeM > 0f && rangeM <= MAX_RANGE_M
        val clearTo = min(if (hit) rangeM - cellMeters else freeToM, MAX_RANGE_M)
        val dx = sin(bearingRad.toDouble())
        val dy = cos(bearingRad.toDouble())

        // The endpoint cell is computed independently of the traversal, so
        // when the hit lands within rounding distance of a cell boundary
        // the two can disagree by one cell — and the clearing pass would
        // then erase the very obstacle this ray just measured. Naming the
        // endpoint up front and skipping it while clearing makes the ray
        // self-consistent whatever the arithmetic does at the boundary.
        val endX = if (hit) cellOf(originX + dx * rangeM) else Int.MIN_VALUE
        val endY = if (hit) cellOf(originY + dy * rangeM) else Int.MIN_VALUE

        if (clearTo > cellMeters) {
            traverse(originX, originY, dx, dy, clearTo) { cx, cy ->
                if (cx != endX || cy != endY) {
                    bump(cx, cy, L_FREE * weight, L_FREE * weight)
                }
            }
        }
        if (hit) markOccupied(endX, endY, weight)
    }

    /**
     * Walk the cells a ray crosses (amanatides-woo style DDA). Visits each
     * cell the segment actually passes through — a Bresenham line would
     * skip diagonal neighbours and leave pinholes a planner can thread a
     * path through.
     */
    private inline fun traverse(
        x0: Double,
        y0: Double,
        dx: Double,
        dy: Double,
        lengthM: Float,
        visit: (Int, Int) -> Unit,
    ) {
        var cx = cellOf(x0)
        var cy = cellOf(y0)
        val stepX = if (dx > 0) 1 else -1
        val stepY = if (dy > 0) 1 else -1
        val cell = cellMeters.toDouble()

        // Distance along the ray to the first crossing of each axis, then
        // the distance between successive crossings.
        val tDeltaX = if (dx != 0.0) abs(cell / dx) else Double.MAX_VALUE
        val tDeltaY = if (dy != 0.0) abs(cell / dy) else Double.MAX_VALUE
        var tMaxX = if (dx != 0.0) {
            val nextBoundary = (if (dx > 0) (cx + 1) * cell else cx * cell)
            (nextBoundary - x0) / dx
        } else Double.MAX_VALUE
        var tMaxY = if (dy != 0.0) {
            val nextBoundary = (if (dy > 0) (cy + 1) * cell else cy * cell)
            (nextBoundary - y0) / dy
        } else Double.MAX_VALUE

        var t = 0.0
        var guard = 0
        val maxSteps = (lengthM / cellMeters).toInt() * 3 + 8
        while (t <= lengthM && guard++ < maxSteps) {
            visit(cx, cy)
            if (tMaxX < tMaxY) {
                cx += stepX
                t = tMaxX
                tMaxX += tDeltaX
            } else {
                cy += stepY
                t = tMaxY
                tMaxY += tDeltaY
            }
        }
    }

    /**
     * Integrate a whole depth scan in one pass. One ray per bearing bin is
     * not a shortcut: past the nearest obstacle along a bearing the world
     * is occluded, so a second hit in the same direction carries no
     * information the first does not.
     */
    fun integrateScan(pose: Pose2d, scan: Scan, weight: Float = 1f) {
        for (b in 0 until scan.bins) {
            val bearing = pose.bearingRad + scan.bearingOfRad(b)
            val range = scan.obstacleRangeM[b]
            val free = scan.freeRangeM[b]
            if (!range.isFinite() && !(free > 0f)) continue
            integrateRay(pose.x, pose.y, bearing, range, free, weight)
        }
    }

    /**
     * The cane's Modulino Distance ping. Its range is the only truly
     * metric measurement in the system, so it is stamped at full weight
     * and clears the near field the camera cannot see over.
     */
    fun integrateCaneRay(pose: Pose2d, distanceM: Float, bearingOffsetRad: Float = 0f) {
        integrateRay(
            pose.x, pose.y,
            pose.bearingRad + bearingOffsetRad,
            distanceM,
            freeToM = if (distanceM.isFinite()) distanceM else 1.2f,
            weight = 1.5f,
        )
    }

    // ---- windowed export -------------------------------------------------

    /**
     * A dense rectangular copy for the planner and the debug renderer.
     * Values are the per-cell max of both channels: what the map thinks is
     * in the way *now*, remembered structure included.
     */
    class Window(
        originCellX: Int,
        originCellY: Int,
        val width: Int,
        val height: Int,
        val cellMeters: Float,
        val logOdds: FloatArray,
        val visited: ByteArray,
    ) {
        var originCellX: Int = originCellX
            private set
        var originCellY: Int = originCellY
            private set

        /** Point a reused buffer at a new corner of the world. */
        fun rebase(ox: Int, oy: Int): Window {
            originCellX = ox
            originCellY = oy
            return this
        }

        fun at(ix: Int, iy: Int): Float = logOdds[iy * width + ix]

        fun blocked(ix: Int, iy: Int): Boolean = logOdds[iy * width + ix] > OCCUPIED_THRESHOLD

        fun visitedAt(ix: Int, iy: Int): Boolean = visited[iy * width + ix].toInt() != 0

        /** Cell index -> world metres (cell centre). */
        fun worldX(ix: Int): Double = (originCellX + ix + 0.5) * cellMeters
        fun worldY(iy: Int): Double = (originCellY + iy + 0.5) * cellMeters

        /** World metres -> cell index, or -1 when outside the window. */
        fun indexX(x: Double): Int {
            val i = floor(x / cellMeters).toInt() - originCellX
            return if (i in 0 until width) i else -1
        }

        fun indexY(y: Double): Int {
            val i = floor(y / cellMeters).toInt() - originCellY
            return if (i in 0 until height) i else -1
        }
    }

    /**
     * @param reuse a window of identical dimensions to overwrite. A 60 m
     *   window is 361 000 cells; allocating that per replan would churn
     *   well over a megabyte a second, so the planner keeps one and hands
     *   it back each time.
     *
     * Iterates by TILE rather than by cell: a per-cell lookup would be
     * 361 000 hash probes, while the tiles covering the same window number
     * a few hundred.
     */
    fun snapshotWindow(
        centreX: Double,
        centreY: Double,
        halfWidthM: Float,
        reuse: Window? = null,
    ): Window {
        val half = max(1, Math.round(halfWidthM / cellMeters))
        val ox = cellOf(centreX) - half
        val oy = cellOf(centreY) - half
        val n = half * 2 + 1
        val out: FloatArray
        val vis: ByteArray
        if (reuse != null && reuse.width == n && reuse.height == n) {
            out = reuse.logOdds
            vis = reuse.visited
            java.util.Arrays.fill(out, 0f)
            java.util.Arrays.fill(vis, 0)
        } else {
            out = FloatArray(n * n)
            vis = ByteArray(n * n)
        }

        val tx0 = Math.floorDiv(ox, TILE_CELLS)
        val tx1 = Math.floorDiv(ox + n - 1, TILE_CELLS)
        val ty0 = Math.floorDiv(oy, TILE_CELLS)
        val ty1 = Math.floorDiv(oy + n - 1, TILE_CELLS)
        for (ty in ty0..ty1) {
            for (tx in tx0..tx1) {
                val t = tiles[tileKey(tx, ty)] ?: continue
                decay(t)
                // Cell range of this tile clipped to the window
                val cx0 = max(tx * TILE_CELLS, ox)
                val cx1 = min(tx * TILE_CELLS + TILE_CELLS - 1, ox + n - 1)
                val cy0 = max(ty * TILE_CELLS, oy)
                val cy1 = min(ty * TILE_CELLS + TILE_CELLS - 1, oy + n - 1)
                for (cy in cy0..cy1) {
                    val srcRow = (cy - ty * TILE_CELLS) * TILE_CELLS
                    val dstRow = (cy - oy) * n
                    for (cx in cx0..cx1) {
                        val si = srcRow + (cx - tx * TILE_CELLS)
                        val di = dstRow + (cx - ox)
                        out[di] = max(t.static[si], t.dynamic[si])
                        vis[di] = t.visited[si]
                    }
                }
            }
        }
        return if (reuse != null && reuse.width == n && reuse.height == n) {
            reuse.rebase(ox, oy)
        } else {
            Window(ox, oy, n, n, cellMeters, out, vis)
        }
    }

    /**
     * The ego-centric view the existing [dev.quad.shepherd.path.PolarPlanner]
     * expects: x right of the camera, y forward, rotated out of the world
     * map. Deriving it here rather than accumulating a second grid keeps
     * one source of truth — and unlike that grid, this one is correct
     * after the user turns around.
     */
    fun egoView(pose: Pose2d, cellsWide: Int, cellsDeep: Int, egoCellM: Float): FloatArray {
        val out = FloatArray(cellsWide * cellsDeep)
        val halfW = cellsWide / 2
        val cosB = cos(pose.bearingRad.toDouble())
        val sinB = sin(pose.bearingRad.toDouble())
        for (iz in 0 until cellsDeep) {
            val forward = iz * egoCellM.toDouble()
            for (ix in 0 until cellsWide) {
                val right = (ix - halfW) * egoCellM.toDouble()
                // Rotate the ego offset into world axes by the pose bearing
                val wx = pose.x + right * cosB + forward * sinB
                val wy = pose.y - right * sinB + forward * cosB
                out[iz * cellsWide + ix] = max(
                    logOddsAtMeters(wx, wy, Channel.STATIC),
                    logOddsAtMeters(wx, wy, Channel.DYNAMIC),
                )
            }
        }
        return out
    }
}
