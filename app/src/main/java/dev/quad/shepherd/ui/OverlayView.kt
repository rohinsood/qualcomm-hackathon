package dev.quad.shepherd.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import dev.quad.shepherd.guidance.GuidanceEngine
import dev.quad.shepherd.vision.FrameResult

/**
 * Draws detection boxes (with label + distance) and the per-column threat
 * bar over the camera preview; the steering command itself is rendered by
 * [SteerView]. In depth-debug mode the colorized depth map replaces the
 * camera view, with the analysis band and per-column distances rendered on
 * top. Assumes the PreviewView underneath uses fitCenter scaling, and
 * applies the same transform.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var result: FrameResult? = null
    private var guidance: GuidanceEngine.Guidance? = null

    /** Height of the system gesture/navigation bar, set from window insets. */
    var bottomInset: Int = 0

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
    }
    private val textBgPaint = Paint().apply { color = 0xAA000000.toInt() }
    private val threatPaint = Paint()
    private val bandPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val columnTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 30f
        isAntiAlias = true
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    fun render(result: FrameResult, guidance: GuidanceEngine.Guidance) {
        this.result = result
        this.guidance = guidance
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = result ?: return
        val g = guidance ?: return

        // fitCenter transform from frame space to view space
        val scale = minOf(width.toFloat() / r.frameWidth, height.toFloat() / r.frameHeight)
        val offsetX = (width - r.frameWidth * scale) / 2f
        val offsetY = (height - r.frameHeight * scale) / 2f
        val frameRect = RectF(
            offsetX, offsetY,
            offsetX + r.frameWidth * scale, offsetY + r.frameHeight * scale,
        )

        // Depth-debug mode: colorized depth map instead of the camera image
        r.depthDebug?.let { db ->
            canvas.drawBitmap(db, null, frameRect, null)

            val yTop = offsetY + r.corridorTop * scale
            val yBottom = offsetY + r.corridorBottom * scale
            canvas.drawLine(frameRect.left, yTop, frameRect.right, yTop, bandPaint)
            canvas.drawLine(frameRect.left, yBottom, frameRect.right, yBottom, bandPaint)

            r.columnDistances?.let { cols ->
                val colW = frameRect.width() / cols.size
                for ((i, dcol) in cols.withIndex()) {
                    val label = if (dcol > 0f) String.format("%.1f", dcol) else "-"
                    val x = frameRect.left + i * colW + colW / 2f -
                        columnTextPaint.measureText(label) / 2f
                    canvas.drawText(label, x, yBottom + 40f, columnTextPaint)
                }
            }
        }

        for (d in r.detections) {
            val dist = d.distanceMeters
            boxPaint.color = when {
                dist != null && dist < 1.5f -> Color.RED
                dist != null && dist < 3f -> Color.YELLOW
                else -> Color.GREEN
            }
            val x1 = d.x1 * scale + offsetX
            val y1 = d.y1 * scale + offsetY
            val x2 = d.x2 * scale + offsetX
            val y2 = d.y2 * scale + offsetY
            canvas.drawRect(x1, y1, x2, y2, boxPaint)

            val label = buildString {
                append(d.label)
                dist?.let { append(String.format(" %.1fm", it)) }
            }
            val tw = textPaint.measureText(label)
            canvas.drawRect(x1, y1 - 44f, x1 + tw + 16f, y1, textBgPaint)
            canvas.drawText(label, x1 + 8f, y1 - 10f, textPaint)
        }

        drawThreatBar(canvas, g.columnThreat)
        drawGrid(canvas, r)
    }

    /** v2 BEV traversability grid, bottom-left (debug mode only). */
    private fun drawGrid(canvas: Canvas, r: FrameResult) {
        val grid = r.gridDebug ?: return
        if (r.gridW <= 0 || r.gridH <= 0) return
        val bmp = Bitmap.createBitmap(grid, r.gridW, r.gridH, Bitmap.Config.ARGB_8888)
        val cellPx = 4f
        val left = 16f
        val top = height - bottomInset - 40f - r.gridH * cellPx
        val dst = RectF(left, top, left + r.gridW * cellPx, top + r.gridH * cellPx)
        canvas.drawBitmap(bmp, null, dst, null)
        // Walker position marker at the bottom-center of the grid
        textPaint.textSize = 28f
        canvas.drawText("▲", left + r.gridW * cellPx / 2f - 8f, dst.bottom - 4f, textPaint)
    }

    private fun drawThreatBar(canvas: Canvas, threat: FloatArray) {
        if (threat.isEmpty()) return
        val barHeight = 14f
        val y = height - bottomInset - barHeight
        val colWidth = width.toFloat() / threat.size
        for ((i, t) in threat.withIndex()) {
            val level = (t * 255).toInt().coerceIn(0, 255)
            threatPaint.color = Color.argb(180, level, 255 - level, 0)
            canvas.drawRect(i * colWidth, y, (i + 1) * colWidth, (height - bottomInset).toFloat(), threatPaint)
        }
    }
}
