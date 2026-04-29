package com.leonardos.spikestream.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// --- Utility per calcolare facilmente la palette ---

/**
 * Miscela due colori. [weight] rappresenta la percentuale del primo colore (da 0.0 a 1.0).
 * Es: color1.mix(color2, 0.2f) = 20% color1 + 80% color2
 */
fun Color.mix(other: Color, weight: Float = 0.5f): Color {
    val w1 = weight.coerceIn(0f, 1f)
    val w2 = 1f - w1
    return Color(
        red = this.red * w1 + other.red * w2,
        green = this.green * w1 + other.green * w2,
        blue = this.blue * w1 + other.blue * w2,
        alpha = this.alpha * w1 + other.alpha * w2
    )
}

// Generatore automatico della palette chiara basato su 3 colori principali
private fun generateLightPalette(primary: Color, secondary: Color, tertiary: Color) = lightColorScheme(
    primary = primary,
    onPrimary = Color.White,
    primaryContainer = primary.mix(Color.White, 0.15f), // Tinta molto chiara
    onPrimaryContainer = primary.mix(Color.Black, 0.3f), // Tinta scura per contrasto
    
    secondary = secondary,
    onSecondary = Color.White,
    secondaryContainer = secondary.mix(Color.White, 0.15f),
    onSecondaryContainer = secondary.mix(Color.Black, 0.3f),
    
    tertiary = tertiary,
    onTertiary = Color.White,
    tertiaryContainer = tertiary.mix(Color.White, 0.15f),
    onTertiaryContainer = tertiary.mix(Color.Black, 0.3f),
    
    background = LightBackground,
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    
    outline = Color(0xFF94A3B8),
    error = Error,
    onError = Color.White,
    errorContainer = Error.mix(Color.White, 0.15f),
    onErrorContainer = Error.mix(Color.Black, 0.3f)
)

// Generatore automatico della palette scura basato su 3 colori principali
private fun generateDarkPalette(primary: Color, secondary: Color, tertiary: Color) = darkColorScheme(
    primary = primary.mix(Color.White, 0.8f), // Schiarisce i primari per la dark mode
    onPrimary = primary.mix(Color.Black, 0.2f),
    primaryContainer = primary.mix(Color.Black, 0.4f), // Tinta scura
    onPrimaryContainer = primary.mix(Color.White, 0.2f), // Testo chiaro (20% primario, 80% bianco)
    
    secondary = secondary.mix(Color.White, 0.8f),
    onSecondary = secondary.mix(Color.Black, 0.2f),
    secondaryContainer = secondary.mix(Color.Black, 0.4f),
    onSecondaryContainer = secondary.mix(Color.White, 0.2f),
    
    tertiary = tertiary.mix(Color.White, 0.8f),
    onTertiary = tertiary.mix(Color.Black, 0.2f),
    tertiaryContainer = tertiary.mix(Color.Black, 0.4f),
    onTertiaryContainer = tertiary.mix(Color.White, 0.2f),
    
    background = DarkBackground,
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF1E293B),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = Color(0xFFCBD5E1),
    
    outline = Color(0xFF64748B),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2)
)

private val LightColorScheme = generateLightPalette(OceanBlue, SunsetCoral, MintGreen)
private val DarkColorScheme = generateDarkPalette(OceanBlue, SunsetCoral, MintGreen)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
