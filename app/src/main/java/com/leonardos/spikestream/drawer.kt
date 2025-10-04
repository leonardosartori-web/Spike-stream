package com.leonardos.spikestream

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

fun drawScoreBitmap(
    width: Int,
    height: Int,
    team1: String,
    team2: String,
    team1Pts: Int,
    team2Pts: Int,
    team1Sets: Int,
    team2Sets: Int
): Bitmap {
    // Dimensioni e configurazione
    val bitmapWidth = 400 * width / 1280
    val bitmapHeight = 150 * height / 720
    val cornerRadius = 8f
    val padding = 8f
    val columnWidth = bitmapWidth / 5f
    val rowHeight = bitmapHeight / 2f

    val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Sfondo bianco con ombreggiatura leggera
    val backgroundPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        setShadowLayer(4f, 2f, 2f, Color.argb(50, 0, 0, 0))
    }
    canvas.drawRoundRect(
        RectF(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat()),
        cornerRadius, cornerRadius, backgroundPaint
    )

    // Stili testo
    val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 32f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    val highlightPaint = Paint().apply {
        color = Color.parseColor("#E91E63") // Rosso acceso
        textSize = 36f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    fun drawAutoSizedText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        maxWidth: Float,
        paint: Paint,
        maxTextSize: Float = 32f,
        minTextSize: Float = 12f
    ) {
        paint.textSize = maxTextSize
        // Riduci la dimensione del testo finché la larghezza è troppo grande o non scendi sotto la minima
        while (paint.measureText(text) > maxWidth && paint.textSize > minTextSize) {
            paint.textSize -= 1f
        }
        // Draw text centered vertical and horizontal
        canvas.drawText(
            text,
            centerX,
            centerY - (paint.ascent() + paint.descent()) / 2,
            paint
        )
    }


    fun drawCell(
        text: String,
        colStart: Float,
        colSpan: Float,
        row: Float,
        highlight: Boolean = false,
        autoSize: Boolean = false
    ) {
        val centerX = (colStart + colSpan / 2) * columnWidth
        val centerY = row * rowHeight + rowHeight / 2 + padding
        val paintToUse = if (highlight) highlightPaint else textPaint

        if (autoSize && colSpan > 1f) {
            drawAutoSizedText(canvas, text, centerX, centerY, colSpan * columnWidth - padding * 2, paintToUse)
        } else {
            canvas.drawText(
                text,
                centerX,
                centerY - (paintToUse.ascent() + paintToUse.descent()) / 2,
                paintToUse
            )
        }
    }

    drawCell(team1, 0f, 3f, 0f, autoSize = true)
    drawCell(team2, 0f, 3f, 1f, autoSize = true)
    drawCell(team1Pts.toString(), 3f, 1f, 0f)
    drawCell(team1Sets.toString(), 4f, 1f, 0f, highlight = team1Sets > team2Sets)
    drawCell(team2Pts.toString(), 3f, 1f, 1f)
    drawCell(team2Sets.toString(), 4f, 1f, 1f, highlight = team2Sets > team1Sets)


    return bitmap
}