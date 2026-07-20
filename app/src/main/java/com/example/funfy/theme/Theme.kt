package com.example.funfy.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = CookiesmoAccent,
    onPrimary = CookiesmoBg,
    background = CookiesmoBg,
    onBackground = CookiesmoTextPrimary,
    surface = CookiesmoSurface,
    onSurface = CookiesmoTextPrimary,
    secondary = CookiesmoTextMuted,
    tertiary = CookiesmoMuted,
    surfaceVariant = CookiesmoMuted,
    onSurfaceVariant = CookiesmoTextMuted,
    outline = CookiesmoMuted
)

private val LightColorScheme = lightColorScheme(
    primary = CookiesmoAccent,
    onPrimary = CookiesmoSurface,
    background = CookiesmoBg,
    onBackground = CookiesmoTextPrimary,
    surface = CookiesmoSurface,
    onSurface = CookiesmoTextPrimary,
    secondary = CookiesmoTextMuted,
    tertiary = CookiesmoMuted,
    surfaceVariant = CookiesmoBg,
    onSurfaceVariant = CookiesmoTextMuted,
    outline = CookiesmoMuted
)

@Composable
fun FunfyTheme(
  darkTheme: Boolean = false, // Default to light mode (white mode)
  dynamicColor: Boolean = false, // Disable dynamic colors to keep brand colors
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
