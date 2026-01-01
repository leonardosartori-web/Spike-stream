package com.leonardos.spikestream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

class ScoreOverlayRenderer(
    private val team1: String,
    private val team2: String,
    context: Context
) {

    private val interSemiBold: Typeface =
        ResourcesCompat.getFont(context, R.font.inter_semibold) ?: Typeface.DEFAULT_BOLD

    private val interBold: Typeface =
        ResourcesCompat.getFont(context, R.font.inter_bold) ?: Typeface.DEFAULT_BOLD

    private val interRegular: Typeface =
        ResourcesCompat.getFont(context, R.font.inter_regular) ?: Typeface.DEFAULT

    private var lastWidth = -1
    private var lastHeight = -1

    private var bitmapWidth = 0
    private var bitmapHeight = 0
    private var columnWidth = 0f
    private var rowHeight = 0f
    private var footerHeight = 0f

    private val cornerRadius = 6f
    private val padding = 6f
    private val footerRatio = 0.6f
    private val footerSpacing = 8f

    // 🎨 Paint
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    /*private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(233, 30, 99)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }*/

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = interSemiBold
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(233, 30, 99)
        textAlign = Paint.Align.CENTER
        typeface = interBold
    }

    private val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textAlign = Paint.Align.RIGHT
        typeface = interSemiBold
    }

    private val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        strokeWidth = 1f
    }

    // 🔧 Ricalcolo dimensioni
    private fun recalcIfNeeded(width: Int, height: Int) {
        if (width == lastWidth && height == lastHeight) return

        lastWidth = width
        lastHeight = height

        bitmapWidth = 400 * width / 1280
        bitmapHeight = 150 * height / 720

        rowHeight = bitmapHeight / (2f + footerRatio)
        footerHeight = rowHeight * footerRatio
        columnWidth = bitmapWidth / 5f

        val maxWidth = 3f * columnWidth - 2 * padding
        textPaint.textSize = 32f
        while (
            (textPaint.measureText(team1).coerceAtLeast(textPaint.measureText(team2)))
            > maxWidth && textPaint.textSize > 12f
        ) {
            textPaint.textSize -= 1f
        }
        highlightPaint.textSize = textPaint.textSize + 4f
        watermarkPaint.textSize = footerHeight * 0.7f
    }

    // 🧠 Testo centrato verticalmente
    private fun Canvas.drawCenteredText(
        text: String,
        cx: Float,
        cy: Float,
        paint: Paint
    ) {
        drawText(text, cx, cy - (paint.ascent() + paint.descent()) / 2, paint)
    }

    // 🖼️ Render bitmap
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

        // Sfondo
        canvas.drawRoundRect(
            RectF(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat()),
            cornerRadius, cornerRadius, backgroundPaint
        )

        fun drawCell(
            text: String,
            colStart: Float,
            colSpan: Float,
            row: Float,
            highlight: Boolean = false
        ) {
            val paint = if (highlight) highlightPaint else textPaint
            val cx = (colStart + colSpan / 2) * columnWidth
            val cy = row * rowHeight + rowHeight / 2
            canvas.drawCenteredText(text, cx, cy, paint)
        }

        // 🏐 Punteggio
        drawCell(team1, 0f, 3f, 0f)
        drawCell(team2, 0f, 3f, 1f)
        drawCell(team1Pts.toString(), 3f, 1f, 0f)
        drawCell(team1Sets.toString(), 4f, 1f, 0f, team1Sets > team2Sets)
        drawCell(team2Pts.toString(), 3f, 1f, 1f)
        drawCell(team2Sets.toString(), 4f, 1f, 1f, team2Sets > team1Sets)

        // ─── Separatore ───
        val separatorY = rowHeight * 2f + footerSpacing / 2
        canvas.drawLine(
            padding,
            separatorY,
            bitmapWidth - padding,
            separatorY,
            separatorPaint
        )

        val footerTop = rowHeight * 2f + footerSpacing
        val footerBottomPadding = 6f

        val footerBaselineY =
            footerTop + footerHeight - footerBottomPadding - watermarkPaint.descent()

        canvas.drawText(
            "Spikestream",
            bitmapWidth - padding,
            footerBaselineY,
            watermarkPaint
        )


        return bmp
    }
}
