package com.marinenavigator.ui.map

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class CompassView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bearing = 0f

    private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A3A5C")
        style = Paint.Style.FILL
    }
    private val paintRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00CFFF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val paintNorth = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4444")
        style = Paint.Style.FILL
    }
    private val paintSouth = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCCCCC")
        style = Paint.Style.FILL
    }
    private val paintCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D2137")
        style = Paint.Style.FILL
    }
    private val paintN = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun setBearing(degrees: Float) {
        bearing = degrees
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - paintRing.strokeWidth

        paintN.textSize = r * 0.38f

        canvas.drawCircle(cx, cy, r, paintBg)
        canvas.drawCircle(cx, cy, r, paintRing)

        canvas.save()
        canvas.rotate(-bearing, cx, cy)

        // North needle (red, pointing up)
        val needleLen = r * 0.72f
        val needleWidth = r * 0.14f
        val northPath = Path().apply {
            moveTo(cx, cy - needleLen)
            lineTo(cx - needleWidth, cy)
            lineTo(cx + needleWidth, cy)
            close()
        }
        canvas.drawPath(northPath, paintNorth)

        // South needle (grey, pointing down)
        val southPath = Path().apply {
            moveTo(cx, cy + needleLen)
            lineTo(cx - needleWidth, cy)
            lineTo(cx + needleWidth, cy)
            close()
        }
        canvas.drawPath(southPath, paintSouth)

        // N label above north needle tip
        val textY = cy - needleLen - paintN.ascent() * 0.1f
        canvas.drawText("N", cx, textY, paintN)

        canvas.restore()

        // Center dot
        canvas.drawCircle(cx, cy, r * 0.1f, paintCenter)
    }
}
