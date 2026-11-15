package com.leonardos.spikestream.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Opt in from navigation screens only; the live production UI keeps its theme.
internal val LocalRefinedStyle = staticCompositionLocalOf { false }

@Composable
fun RefinedApplicationTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MyApplicationTheme {
        val colors = MaterialTheme.colorScheme
        val refined = colors.copy(
            primary = if (dark) Color(0xFF7DD3FC) else Color(0xFF0369A1),
            onPrimary = if (dark) Color(0xFF082F49) else Color.White,
            secondary = if (dark) Color(0xFFFDA4AF) else Color(0xFFBE123C),
            onSecondary = if (dark) Color(0xFF4C0519) else Color.White,
            outlineVariant = if (dark) Color(0xFF334155) else Color(0xFFE2E8F0)
        )
        val typography = AppTypography.copy(
            headlineMedium = AppTypography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
            titleLarge = AppTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
            titleMedium = AppTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            labelLarge = AppTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
        )
        CompositionLocalProvider(LocalRefinedStyle provides true) {
            MaterialTheme(colorScheme = refined, typography = typography, content = content)
        }
    }
}
