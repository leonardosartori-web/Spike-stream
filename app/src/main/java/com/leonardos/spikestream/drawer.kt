package com.leonardos.spikestream

import android.content.Context
import android.graphics.*
import android.graphics.Paint.Align
import android.graphics.Paint.Style
import androidx.core.content.res.ResourcesCompat

// ----------------------------------------------------
// 🎨 THEME MODEL
// ----------------------------------------------------

data class TeamOverlayStyle(
    val background: Int,
    val text: Int,
    val secondaryText: Int,
    val accent: Int,
    val card: Int,
    val border: Int,
    val win: Int,
    val lose: Int
)

data class OverlayStyle(
    val team1: TeamOverlayStyle,
    val team2: TeamOverlayStyle,
    val watermark: Int
)

object DefaultOverlayStyle {

    private val base = TeamOverlayStyle(
        background = Color.WHITE,
        text = Color.rgb(10, 10, 10),
        secondaryText = Color.rgb(120, 120, 120),
        accent = Color.rgb(220, 38, 38),
        card = Color.rgb(245, 245, 245),
        border = Color.rgb(230, 230, 230),
        win = Color.rgb(220, 38, 38),
        lose = Color.rgb(120, 120, 120)
    )

    val classic = OverlayStyle(
        team1 = base.copy(accent = Color.rgb(220, 38, 38)),
        team2 = base.copy(accent = Color.rgb(220, 38, 38)),
        watermark = Color.rgb(140, 140, 140)
    )
}

// ----------------------------------------------------
// 🧱 RENDERER
// ----------------------------------------------------

class ScoreOverlayRenderer(
    private val team1: String,
    private val team2: String,
    context: Context
) {

    // ----------------------------------------------------
    // FONTS
    // ----------------------------------------------------

    private val interSemiBold =
        ResourcesCompat.getFont(context, R.font.inter_semibold) ?: Typeface.DEFAULT_BOLD

    private val interBold =
        ResourcesCompat.getFont(context, R.font.inter_bold) ?: Typeface.DEFAULT_BOLD

    // ----------------------------------------------------
    // STATE
    // ----------------------------------------------------

    private val REF_W = 1280
    private val REF_H = 720

    private var bitmapWidth = 0
    private var bitmapHeight = 0

    private var outerPadding = 0f
    private var rowHeight = 0f
    private var footerHeight = 0f

    val hd = 2.2f

    // ----------------------------------------------------
    // THEME (🔥 NEW)
    // ----------------------------------------------------

    private var style: OverlayStyle = DefaultOverlayStyle.classic

    fun setStyle(newStyle: OverlayStyle) {
        style = newStyle
    }

    private val tmpRect = RectF()

    // ----------------------------------------------------
    // PAINTS
    // ----------------------------------------------------

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Style.STROKE
        strokeWidth = 1f * hd
    }

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f * hd
    }

    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Style.FILL
    }

    private val pointsCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Style.FILL
    }

    private val teamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Align.LEFT
        typeface = interSemiBold
    }

    private val pointsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Align.CENTER
        typeface = interBold
    }

    private val setsWinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Align.CENTER
        typeface = interBold
    }

    private val setsLosePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Align.CENTER
        typeface = interSemiBold
    }

    private val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Align.RIGHT
        typeface = interSemiBold
    }

    private val sideAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val servePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ----------------------------------------------------
    // SCALE
    // ----------------------------------------------------

    private fun computeScale(width: Int): Float {
        val base = maxOf(width.toFloat(), 1280f)
        return base / 1280f
    }

    private fun recalc(scale: Float) {
        bitmapWidth = ((340 * REF_W / 1280f) * hd).toInt()
        bitmapHeight = ((105 * REF_H / 720f) * hd).toInt()

        outerPadding = 4f * hd

        val innerH = bitmapHeight - outerPadding * 2
        rowHeight = innerH * 0.38f
        footerHeight = innerH * 0.18f

        val textScale = hd * 0.8f * scale

        teamPaint.textSize = 24f * textScale
        pointsPaint.textSize = 32f * textScale
        setsWinPaint.textSize = 20f * textScale
        setsLosePaint.textSize = 20f * textScale
        watermarkPaint.textSize = 12f * textScale
    }

    // ----------------------------------------------------
    // UTILS
    // ----------------------------------------------------

    private fun Canvas.drawCenteredText(
        text: String,
        x: Float,
        y: Float,
        paint: Paint
    ) {
        drawText(text, x, y - (paint.ascent() + paint.descent()) / 2, paint)
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt()

        return Color.argb(
            alpha,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    // ----------------------------------------------------
    // RENDER
    // ----------------------------------------------------

    fun render(
        width: Int,
        height: Int,
        team1Pts: Int,
        team2Pts: Int,
        team1Sets: Int,
        team2Sets: Int,
        servingTeam: Int = 0
    ): Bitmap {

        val scale = computeScale(width)
        recalc(scale)

        // ----------------------------
        // 🎨 GLOBAL STYLE
        // ----------------------------
        backgroundPaint.color = style.team1.background
        borderPaint.color = style.team1.border
        dividerPaint.color = style.team1.border

        watermarkPaint.color = style.watermark

        val bmp = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val left = outerPadding
        val right = bitmapWidth - outerPadding
        val top = outerPadding

        val row0Top = top
        val row1Top = top + rowHeight

        val row0Center = row0Top + rowHeight / 2f
        val row1Center = row1Top + rowHeight / 2f

        val row0Bottom = row0Top + rowHeight
        val row1Bottom = row1Top + rowHeight

        val yBoost = 2f * hd

        // ----------------------------
        // 🧱 MAIN BACKGROUND
        // ----------------------------
        val mainRect = RectF(
            left,
            top,
            right,
            row1Bottom + footerHeight
        )

        canvas.drawRect(mainRect, backgroundPaint)
        canvas.drawRect(mainRect, borderPaint)

        /*val rowAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = adjustAlpha(teamStyle.accent, 0.08f)
            }

            canvas.drawRect(
                left,
                rowTop,
                right,
                rowBottom,
                rowAccentPaint
            )*/

        // ----------------------------
        // 👥 ROWS
        // ----------------------------
        for (row in 0..1) {

            val isTeam1 = row == 0
            val teamStyle = if (isTeam1) style.team1 else style.team2

            val name = if (isTeam1) team1 else team2
            val pts = if (isTeam1) team1Pts else team2Pts
            val sets = if (isTeam1) team1Sets else team2Sets
            val oppSets = if (isTeam1) team2Sets else team1Sets

            val centerY = if (isTeam1) row0Center else row1Center
            val rowTop = if (isTeam1) row0Top else row1Top
            val rowBottom = rowTop + rowHeight

            // ----------------------------
            // ACCENT BAR (REUSED PAINT)
            // ----------------------------
            sideAccentPaint.color = teamStyle.accent

            canvas.drawRect(
                left,
                rowTop,
                left + 6f * hd,
                rowBottom,
                sideAccentPaint
            )

            // ----------------------------
            // SERVING INDICATOR
            // ----------------------------
            if (servingTeam == row + 1) {
                servePaint.color = teamStyle.accent

                canvas.drawCircle(
                    left + 16f * hd,
                    centerY,
                    4f * hd,
                    servePaint
                )
            }

            // ----------------------------
            // TEAM NAME
            // ----------------------------
            teamPaint.color = teamStyle.text

            canvas.drawText(
                name.uppercase(),
                left + 30f * hd,
                centerY - (teamPaint.ascent() + teamPaint.descent()) / 2 + yBoost,
                teamPaint
            )

            // ----------------------------
            // POINTS CARD
            // ----------------------------
            tmpRect.set(
                right - 110f * hd,
                rowTop + 6f * hd,
                right - 50f * hd,
                rowBottom - 6f * hd
            )

            pointsCardPaint.color = teamStyle.card

            canvas.drawRoundRect(
                tmpRect,
                8f * hd,
                8f * hd,
                pointsCardPaint
            )

            pointsPaint.color = teamStyle.text

            canvas.drawCenteredText(
                pts.toString(),
                tmpRect.centerX(),
                centerY + yBoost,
                pointsPaint
            )

            // ----------------------------
            // SETS (NO PAINT ALLOCATION)
            // ----------------------------
            setsWinPaint.color = teamStyle.win
            setsLosePaint.color = teamStyle.lose

            val setsPaint = if (sets > oppSets) setsWinPaint else setsLosePaint

            canvas.drawCenteredText(
                sets.toString(),
                right - 20f * hd,
                centerY + yBoost,
                setsPaint
            )
        }

        // ----------------------------
        // 🔖 WATERMARK
        // ----------------------------
        val footerCenter = row1Bottom + footerHeight / 2f

        canvas.drawText(
            "Powered by SpikeStream",
            right - 8f * hd,
            footerCenter - (watermarkPaint.ascent() + watermarkPaint.descent()) / 2,
            watermarkPaint
        )

        return bmp
    }
}