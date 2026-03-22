package com.marinenavigator.ui.map

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Brújula de cinta horizontal estilo aviación/náutica.
 *
 * - Muestra el heading actual centrado con una línea roja fija.
 * - Si hay rumbo de ruta activo, muestra una flecha verde offset al heading.
 * - Las marcas mayores (cada 10°) llevan etiqueta numérica y los cardinales
 *   (N, NE, E, SE, S, SW, W, NW) sustituyen al número.
 */
class CompassTapeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var heading = 0f          // heading propio (línea roja)
    private var routeBearing: Float? = null  // rumbo de ruta (flecha verde)

    // ── Paints ────────────────────────────────────────────────────────────────
    private val paintBg = Paint().apply {
        color = Color.parseColor("#0D2137")
        style = Paint.Style.FILL
    }
    private val paintTickMajor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00CFFF")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val paintTickMinor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5599BB")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }
    private val paintTickSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#335577")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val paintCardinal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00CFFF")
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    // Línea central fija roja = heading propio
    private val paintHeadingLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3333")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    // Flecha verde = rumbo de ruta
    private val paintRoute = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33CC33")
        style = Paint.Style.FILL
    }
    private val paintRouteStem = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33CC33")
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    private val cardinals = mapOf(
        0 to "N", 45 to "NE", 90 to "E", 135 to "SE",
        180 to "S", 225 to "SW", 270 to "W", 315 to "NW"
    )

    // ── API pública ───────────────────────────────────────────────────────────
    fun setHeading(degrees: Float) {
        heading = (degrees + 360f) % 360f
        invalidate()
    }

    fun setRouteBearing(degrees: Float?) {
        routeBearing = degrees?.let { (it + 360f) % 360f }
        invalidate()
    }

    // ── Dibujo ────────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val density = resources.displayMetrics.density

        canvas.drawRect(0f, 0f, w, h, paintBg)

        val pxPerDeg = w / 60f          // 60° visibles en el ancho total
        val cx = w / 2f

        // Alturas de ticks
        val tickMajorH = h * 0.55f
        val tickMinorH = h * 0.35f
        val tickSmallH = h * 0.20f

        // Tamaño de texto
        paintLabel.textSize   = 11f * density
        paintCardinal.textSize = 13f * density

        // Rango visible: heading ± 30°
        val startDeg = heading - 31f
        val endDeg   = heading + 31f

        var deg = Math.floor(startDeg.toDouble()).toInt()
        while (deg <= endDeg.toInt() + 1) {
            val normDeg = ((deg % 360) + 360) % 360
            val x = cx + (deg - heading) * pxPerDeg

            when {
                normDeg % 10 == 0 -> {
                    canvas.drawLine(x, 0f, x, tickMajorH, paintTickMajor)
                    val label = cardinals[normDeg]
                    if (label != null) {
                        canvas.drawText(label, x, h - 4f * density, paintCardinal)
                    } else {
                        canvas.drawText(normDeg.toString(), x, h - 4f * density, paintLabel)
                    }
                }
                normDeg % 5 == 0 -> {
                    canvas.drawLine(x, 0f, x, tickMinorH, paintTickMinor)
                }
                else -> {
                    canvas.drawLine(x, 0f, x, tickSmallH, paintTickSmall)
                }
            }
            deg++
        }

        // ── Flecha verde de rumbo de ruta ─────────────────────────────────────
        routeBearing?.let { rb ->
            var diff = rb - heading
            while (diff > 180f)  diff -= 360f
            while (diff < -180f) diff += 360f

            if (diff in -30f..30f) {
                val rx = cx + diff * pxPerDeg
                val arrowH = h * 0.50f
                val arrowW = 7f * density

                // Triángulo apuntando hacia arriba
                val path = Path().apply {
                    moveTo(rx, 2f * density)
                    lineTo(rx - arrowW, arrowH)
                    lineTo(rx + arrowW, arrowH)
                    close()
                }
                canvas.drawPath(path, paintRoute)
                canvas.drawLine(rx, arrowH, rx, h, paintRouteStem)
            }
        }

        // ── Línea roja central (heading propio) ───────────────────────────────
        canvas.drawLine(cx, 0f, cx, h, paintHeadingLine)
    }
}
