package com.example.funfy.theme

import androidx.compose.ui.graphics.Color

// Cookiesmo visual design theme colors - Light Mode (White Mode)
val CookiesmoBg = Color(0xFFF9F9FB)         // Light off-white background
val CookiesmoAccent = Color(0xFF0891B2)     // Cyan primary highlight (darker for readability)
val CookiesmoSurface = Color(0xFFFFFFFF)    // Pure white for cards, surfaces, buttons
val CookiesmoMuted = Color(0xFFE4E4E7)      // Zinc 200 for borders, secondary/muted bg
val CookiesmoTextPrimary = Color(0xFF09090B) // Zinc 950 / pitch black text
val CookiesmoTextMuted = Color(0xFF52525B)   // Zinc 600 / dark gray secondary text

// Backward compatibility color aliases to prevent intermediate compilation breakage
val DarkBlueBg = CookiesmoBg
val RoyalBlueNav = CookiesmoAccent
val TextMetaBlue = CookiesmoTextMuted
val ActiveTabWhite = CookiesmoTextPrimary
val InactiveTabBlue = CookiesmoTextMuted
