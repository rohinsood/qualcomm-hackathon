package dev.quad.shepherd.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import dev.quad.shepherd.guidance.GuidanceEngine
import dev.quad.shepherd.vision.FrameResult

/**
 * Draws detection boxes (with label + distance), the per-column threat bar,
 * and a steering arrow over the camera preview. Assumes the PreviewView
 * underneath uses fitCenter scaling, and applies the same transform.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var result: FrameResult? = null
    private var guidance: GuidanceEngine.Guidance? = null

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
    private val arrowPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
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
        drawSteerArrow(canvas, g)
    }

    private fun drawThreatBar(canvas: Canvas, threat: FloatArray) {
        if (threat.isEmpty()) return
        val barHeight = 14f
        val y = height - barHeight
        val colWidth = width.toFloat() / threat.size
        for ((i, t) in threat.withIndex()) {
            val level = (t * 255).toInt().coerceIn(0, 255)
            threatPaint.color = Color.argb(180, level, 255 - level, 0)
            canvas.drawRect(i * colWidth, y, (i + 1) * colWidth, height.toFloat(), threatPaint)
        }
    }

    private fun drawSteerArrow(canvas: Canvas, g: GuidanceEngine.Guidance) {
        if (g.severity == GuidanceEngine.Severity.CLEAR) return
        arrowPaint.color =
            if (g.severity == GuidanceEngine.Severity.DANGER) Color.RED else Color.YELLOW

        val cx = width / 2f
        val cy = height - 140f
        val size = 60f
        val tip = cx + g.steer * (width / 3f)

        val path = Path().apply {
            moveTo(tip, cy)                       // arrow tip points toward safe gap
            lineTo(cx - size / 2f, cy + size)
            lineTo(cx + size / 2f, cy + size)
            close()
        }
        canvas.drawPath(path, arrowPaint)
    }
}
