package com.leonardos.spikestream.ui.theme

import androidx.compose.ui.graphics.Color

// --- Palette Base (Chiara e Accattivante) ---
// Modificando solo questi 3 colori si ricalcola in automatico tutto il tema in Theme.kt

val OceanBlue = Color(0xFF0EA5E9)   // Primary: un blu cielo vivace e fresco
val SunsetCoral = Color(0xFFF43F5E) // Secondary: un corallo acceso, perfetto per staccare
val MintGreen = Color(0xFF10B981)   // Tertiary: un verde smeraldo morbido

// Sfondi di base
val LightBackground = Color(0xFFF8FAFC) // Slate 50, un bianco "sporco" molto pulito ed elegante
val DarkBackground = Color(0xFF0F172A)  // Slate 900, un blu notte profondo per la dark mode

// ---------------------------------------------------------
// Legacy colors (mantenuti per compatibilità col codice esistente)
// ---------------------------------------------------------
val VolleyballOrange = OceanBlue
val VolleyballBlue = MintGreen
val VolleyballYellow = SunsetCoral
val CourtGreen = Color(0xFF10B981)

val Primary = VolleyballOrange
val OnPrimary = Color.White
val Secondary = VolleyballBlue
val OnSecondary = Color.White

val Background = LightBackground
val OnBackground = Color(0xFF0F172A)

val Surface = Color.White
val OnSurface = Color(0xFF0F172A)

val Error = Color(0xFFEF4444)
val Outline = Color(0xFF94A3B8)
