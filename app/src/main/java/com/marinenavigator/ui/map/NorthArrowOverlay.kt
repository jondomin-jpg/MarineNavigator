package com.marinenavigator.ui.map

import android.graphics.*
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Dibuja una aguja de Norte en la esquina inferior izquierda del mapa.
 * Se actualiza con el rumbo del magnetómetro del dispositivo.
 */
class NorthArrowOverlay : Overlay() {

    private var bearing = 0f

    private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC0D2137")
        style = Paint.Style.FILL
    }
    private val paintRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00CFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val paintNorth = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4444")
        style = Paint.Style.FILL
    }
    private val paintSouth = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAAAAA")
        style = Paint.Style.FILL
    }
    private val paintCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D2137")
        style = Paint.Style.FILL
    }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun updateBearing(newBearing: Float) {
        bearing = newBearing
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        val density = mapView.resources.displayMetrics.density
        val radius = 32f * density
        val margin = 16f * density
        // Esquina inferior izquierda, por encima de la barra de escala
        val cx = margin + radius
        val cy = canvas.height - margin - radius - 48f * density

        paintLabel.textSize = radius * 0.45f

        canvas.drawCircle(cx, cy, radius, paintBg)
        canvas.drawCircle(cx, cy, radius, paintRing)

        canvas.save()
        canvas.rotate(-bearing, cx, cy)

        val needleLen = radius * 0.70f
        val needleW = radius * 0.18f

        // Aguja norte (roja)
        val northPath = Path().apply {
            moveTo(cx, cy - needleLen)
            lineTo(cx - needleW, cy)
            lineTo(cx + needleW, cy)
            close()
        }
        canvas.drawPath(northPath, paintNorth)

        // Aguja sur (gris)
        val southPath = Path().apply {
            moveTo(cx, cy + needleLen)
            lineTo(cx - needleW, cy)
            lineTo(cx + needleW, cy)
            close()
        }
        canvas.drawPath(southPath, paintSouth)

        // Letra N sobre la aguja norte
        canvas.drawText("N", cx, cy - needleLen - paintLabel.ascent() * 0.2f, paintLabel)

        canvas.restore()

        // Punto central
        canvas.drawCircle(cx, cy, radius * 0.12f, paintCenter)
    }
}
