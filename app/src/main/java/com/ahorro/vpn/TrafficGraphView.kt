package com.ahorro.vpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Gráfico de tráfico en vivo: dos líneas (bajada lima, subida esmeralda)
 * con relleno suave, sobre una rejilla tenue. La app le pasa una muestra
 * por segundo (bytes/segundo) y el gráfico se desplaza solo.
 * Sin dependencias, solo Canvas: no se puede romper por una librería.
 */
class TrafficGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val maxSamples = 48
    private val rxSamples = ArrayDeque<Float>()
    private val txSamples = ArrayDeque<Float>()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val rxLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A3E635")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val txLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val rxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2EA3E635")
        style = Paint.Style.FILL
    }
    private val txFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E10B981")
        style = Paint.Style.FILL
    }

    /** Una muestra por segundo: bytes/segundo de bajada y de subida. */
    fun addSample(rxBytesPerSec: Float, txBytesPerSec: Float) {
        rxSamples.addLast(rxBytesPerSec.coerceAtLeast(0f))
        txSamples.addLast(txBytesPerSec.coerceAtLeast(0f))
        while (rxSamples.size > maxSamples) rxSamples.removeFirst()
        while (txSamples.size > maxSamples) txSamples.removeFirst()
        invalidate()
    }

    fun clear() {
        rxSamples.clear()
        txSamples.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val padTop = 8f
        val padBottom = 8f
        val graphH = h - padTop - padBottom

        // Rejilla horizontal (3 líneas).
        for (i in 0..3) {
            val y = padTop + graphH * i / 3f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        if (rxSamples.size < 2) return

        var max = 1024f // mínimo 1 KB/s para que la escala no se vuelva loca
        for (v in rxSamples) if (v > max) max = v
        for (v in txSamples) if (v > max) max = v

        drawSeries(canvas, rxSamples, max, padTop, graphH, rxLinePaint, rxFillPaint)
        drawSeries(canvas, txSamples, max, padTop, graphH, txLinePaint, txFillPaint)
    }

    private fun drawSeries(
        canvas: Canvas,
        samples: ArrayDeque<Float>,
        max: Float,
        padTop: Float,
        graphH: Float,
        linePaint: Paint,
        fillPaint: Paint
    ) {
        val w = width.toFloat()
        val n = samples.size
        if (n < 2) return
        val stepX = w / (maxSamples - 1)
        // Las muestras se pegan a la derecha: las viejas salen por la izquierda.
        val startX = w - stepX * (n - 1)
        val linePath = Path()
        val fillPath = Path()
        var first = true
        var i = 0
        for (v in samples) {
            val x = startX + stepX * i
            val y = padTop + graphH * (1f - (v / max).coerceIn(0f, 1f))
            if (first) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, padTop + graphH)
                fillPath.lineTo(x, y)
                first = false
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            i++
        }
        val lastX = startX + stepX * (n - 1)
        fillPath.lineTo(lastX, padTop + graphH)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
    }
}
