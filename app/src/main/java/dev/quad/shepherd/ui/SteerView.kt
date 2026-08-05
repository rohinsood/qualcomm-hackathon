package dev.quad.shepherd.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import dev.quad.shepherd.actuator.CaneCommand
import dev.quad.shepherd.guidance.GuidanceEngine

/**
 * The cane-wheel command, visualized: a large arrow deflected by the
 * commanded wheel turn, the command name, and a severity-colored panel.
 * This renders exactly the [CaneCommand] the cane hardware will receive,
 * so the display doubles as the actuator's bench simulator.
 */
class SteerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        private val GREEN = 0xCC1B5E20.toInt()
        private val AMBER = 0xE0E65100.toInt()
        private val RED = 0xE6B71C1C.toInt()

        /** Full-lock wheel deflection shown as this many degrees. */
        private const val MAX_ANGLE_DEG = 60f
    }

    private var command = CaneCommand(CaneCommand.Direction.STRAIGHT, 0f, false)
    private var severity = GuidanceEngine.Severity.CLEAR

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val arrow = Path()
    private val bgRect = RectF()

    fun render(g: GuidanceEngine.Guidance) {
        command = CaneCommand.from(g)
        severity = g.severity
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        bgPaint.color = when (severity) {
            GuidanceEngine.Severity.CLEAR -> GREEN
            GuidanceEngine.Severity.CAUTION -> AMBER
            GuidanceEngine.Severity.DANGER -> RED
        }
        bgRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(bgRect, h * 0.12f, h * 0.12f, bgPaint)

        val label = when (command.direction) {
            CaneCommand.Direction.STRAIGHT -> "STRAIGHT"
            CaneCommand.Direction.LEFT -> "LEFT"
            CaneCommand.Direction.RIGHT -> "RIGHT"
            CaneCommand.Direction.STOP -> "STOP"
        }
        textPaint.textSize = h * 0.16f
        canvas.drawText(label, w / 2f, h * 0.9f, textPaint)

        val cx = w / 2f
        val cy = h * 0.42f
        strokePaint.strokeWidth = h * 0.06f

        if (command.direction == CaneCommand.Direction.STOP) {
            // "No entry": circle with a bar — the wheel brakes hard
            canvas.drawCircle(cx, cy, h * 0.22f, strokePaint)
            canvas.drawLine(cx - h * 0.13f, cy, cx + h * 0.13f, cy, strokePaint)
        } else {
            canvas.save()
            canvas.rotate(command.turn * MAX_ANGLE_DEG, cx, cy)
            val len = h * 0.26f
            arrow.reset()
            arrow.moveTo(cx, cy + len)
            arrow.lineTo(cx, cy - len)
            arrow.moveTo(cx - len * 0.55f, cy - len * 0.35f)
            arrow.lineTo(cx, cy - len)
            arrow.lineTo(cx + len * 0.55f, cy - len * 0.35f)
            canvas.drawPath(arrow, strokePaint)
            canvas.restore()
        }
    }
}
