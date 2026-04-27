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

    private val cornerRadius = 12f // Smoother corners
    private val padding = 12f
    private val footerRatio = 0.5f
    private val footerSpacing = 10f

    // 🎨 Paint
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 255, 255) // White background
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = interSemiBold
    }

    private val ptsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = interBold
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        textAlign = Paint.Align.CENTER
        typeface = interBold
    }

    private val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 0, 0) // Black, semi-transparent
        textAlign = Paint.Align.RIGHT
        typeface = interSemiBold
    }

    private val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(30, 0, 0, 0)
        strokeWidth = 2f
    }

    private val servePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    // 🔧 Ricalcolo dimensioni
    private fun recalcIfNeeded(width: Int, height: Int) {
        if (width == lastWidth && height == lastHeight) return

        lastWidth = width
        lastHeight = height

        val baseWidth = maxOf(width, height)
        val baseHeight = minOf(width, height)

        // 400x150 at 1280x720 base
        bitmapWidth = 450 * baseWidth / 1280
        bitmapHeight = 160 * baseHeight / 720

        rowHeight = bitmapHeight / (2f + footerRatio)
        footerHeight = rowHeight * footerRatio
        columnWidth = bitmapWidth / 6f // Use 6 columns for better spacing (4 for names, 1 for pts, 1 for sets)

        val maxWidth = 4f * columnWidth - 2 * padding
        textPaint.textSize = 34f
        while (
            (textPaint.measureText(team1).coerceAtLeast(textPaint.measureText(team2)))
            > maxWidth && textPaint.textSize > 14f
        ) {
            textPaint.textSize -= 1f
        }
        ptsPaint.textSize = textPaint.textSize + 6f
        highlightPaint.textSize = textPaint.textSize + 4f
        watermarkPaint.textSize = footerHeight * 0.6f
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
        team2Sets: Int,
        servingTeam: Int = 0 // 1 or 2, 0 for none
    ): Bitmap {
        recalcIfNeeded(width, height)

        val bmp = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Sfondo
        canvas.drawRoundRect(
            RectF(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat()),
            cornerRadius, cornerRadius, backgroundPaint
        )

        // 🏐 Punteggio Cell
        fun drawCell(
            text: String,
            colStart: Float,
            colSpan: Float,
            row: Float,
            paintToUse: Paint = textPaint
        ) {
            val cx = (colStart + colSpan / 2) * columnWidth
            val cy = row * rowHeight + rowHeight / 2
            canvas.drawCenteredText(text, cx, cy, paintToUse)
        }

        // Teams
        drawCell(team1, 0f, 4f, 0f)
        drawCell(team2, 0f, 4f, 1f)

        // Serve indicator
        if (servingTeam == 1) {
            canvas.drawCircle(padding * 1.5f, rowHeight / 2, 8f, servePaint)
        } else if (servingTeam == 2) {
            canvas.drawCircle(padding * 1.5f, rowHeight + rowHeight / 2, 8f, servePaint)
        }

        // Pts
        drawCell(team1Pts.toString(), 4f, 1f, 0f, ptsPaint)
        drawCell(team2Pts.toString(), 4f, 1f, 1f, ptsPaint)

        // Sets
        drawCell(team1Sets.toString(), 5f, 1f, 0f, if (team1Sets > team2Sets) highlightPaint else textPaint)
        drawCell(team2Sets.toString(), 5f, 1f, 1f, if (team2Sets > team1Sets) highlightPaint else textPaint)

        // ─── Separatore ───
        val separatorY = rowHeight * 2f
        canvas.drawLine(
            padding,
            separatorY,
            bitmapWidth - padding,
            separatorY,
            separatorPaint
        )

        val footerTop = rowHeight * 2f
        val footerBaselineY = footerTop + (footerHeight + footerSpacing) / 2
        
        canvas.drawText(
            "Powered by SPIKESTREAM",
            bitmapWidth - padding,
            footerBaselineY - (watermarkPaint.ascent() + watermarkPaint.descent()) / 2,
            watermarkPaint
        )

        return bmp
    }
}