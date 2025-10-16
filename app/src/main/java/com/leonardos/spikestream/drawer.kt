package com.leonardos.spikestream

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PaintFlagsDrawFilter
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface

class ScoreOverlayRenderer(
    private val team1: String,
    private val team2: String
) {
    private var lastWidth = -1
    private var lastHeight = -1

    private var bitmapWidth = 0
    private var bitmapHeight = 0
    private var columnWidth = 0f
    private var rowHeight = 0f
    private val cornerRadius = 6f
    private val padding = 6f

    // 🎨 Paint precalcolati (creati una sola volta)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(233, 30, 99) // #E91E63
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    // 🔧 Ricalcola solo se cambiano le dimensioni
    private fun recalcIfNeeded(width: Int, height: Int) {
        if (width == lastWidth && height == lastHeight) return

        lastWidth = width
        lastHeight = height

        bitmapWidth = 400 * width / 1280
        bitmapHeight = 150 * height / 720
        columnWidth = bitmapWidth / 5f
        rowHeight = bitmapHeight / 2f

        val maxWidth = 3f * columnWidth - 2 * padding
        textPaint.textSize = 32f
        while ((textPaint.measureText(team1).coerceAtLeast(textPaint.measureText(team2))) > maxWidth && textPaint.textSize > 12f) {
            textPaint.textSize -= 1f
        }
        highlightPaint.textSize = textPaint.textSize + 4f
    }

    // 🧠 Funzione privata per testo centrato verticalmente
    private fun Canvas.drawCenteredText(text: String, cx: Float, cy: Float, paint: Paint) {
        drawText(text, cx, cy - (paint.ascent() + paint.descent()) / 2, paint)
    }

    // 🖼️ Genera la bitmap ottimizzata
    fun render(
        width: Int,
        height: Int,
        team1Pts: Int,
        team2Pts: Int,
        team1Sets: Int,
        team2Sets: Int
    ): Bitmap {
        recalcIfNeeded(width, height)

        val bmp = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)

        // Sfondo (niente ombre per performance)
        canvas.drawRoundRect(
            RectF(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat()),
            cornerRadius, cornerRadius, backgroundPaint
        )

        fun drawCell(text: String, colStart: Float, colSpan: Float, row: Float, highlight: Boolean = false) {
            val paint = if (highlight) highlightPaint else textPaint
            val cx = (colStart + colSpan / 2) * columnWidth
            val cy = row * rowHeight + rowHeight / 2 + padding
            canvas.drawCenteredText(text, cx, cy, paint)
        }

        // ⚡ Disegna solo il necessario
        drawCell(team1, 0f, 3f, 0f)
        drawCell(team2, 0f, 3f, 1f)
        drawCell(team1Pts.toString(), 3f, 1f, 0f)
        drawCell(team1Sets.toString(), 4f, 1f, 0f, highlight = team1Sets > team2Sets)
        drawCell(team2Pts.toString(), 3f, 1f, 1f)
        drawCell(team2Sets.toString(), 4f, 1f, 1f, highlight = team2Sets > team1Sets)

        return bmp
    }
}
