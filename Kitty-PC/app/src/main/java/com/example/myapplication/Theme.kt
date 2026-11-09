package com.example.myapplication

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

object Palette {
    val Good = Color(0xFF4CAF50)
    val Warn = Color(0xFFFFB300)
    val Bad = Color(0xFFE5484D)
}

/** User overrides on top of a theme. null = use the theme's colour. */
data class ThemeOverrides(
    val bg: Color?,
    val surface: Color?,
    val text: Color?,
    val outline: Color?,
    val accent: Color?,
) {
    fun byRole(role: String): Color? = when (role) {
        "bg" -> bg
        "surface" -> surface
        "text" -> text
        "outline" -> outline
        else -> accent
    }

    companion object {
        val NONE = ThemeOverrides(null, null, null, null, null)
    }
}

/** "dark" (default), "midnight" (pure black for AMOLED screens) or "light". */
fun schemeFor(name: String, o: ThemeOverrides = ThemeOverrides.NONE): ColorScheme {
    val base = when (name) {
        "light" -> lightColorScheme(
            primary = Color(0xFF2456B3),
            onPrimary = Color.White,
            background = Color(0xFFF4F5F7),
            onBackground = Color(0xFF1A1C20),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF1A1C20),
            onSurfaceVariant = Color(0xFF5A6070),
            outline = Color(0xFFC4C9D2),
            primaryContainer = Color(0xFFD6E3FF),
            onPrimaryContainer = Color(0xFF0B2A5E),
        )
        "midnight" -> darkColorScheme(
            primary = Color(0xFF8AB4FF),
            onPrimary = Color(0xFF0A0A0A),
            background = Color.Black,
            onBackground = Color(0xFFE6E8EC),
            surface = Color(0xFF0B0B0C),
            onSurface = Color(0xFFE6E8EC),
            onSurfaceVariant = Color(0xFF9AA0AA),
            outline = Color(0xFF2A2D34),
            primaryContainer = Color(0xFF2D4A7A),
            onPrimaryContainer = Color(0xFFDCE6FF),
        )
        else -> darkColorScheme(
            primary = Color(0xFF8AB4FF),
            onPrimary = Color(0xFF0A0A0A),
            background = Color(0xFF0E0F12),
            onBackground = Color(0xFFE6E8EC),
            surface = Color(0xFF181A1F),
            onSurface = Color(0xFFE6E8EC),
            onSurfaceVariant = Color(0xFF9AA0AA),
            outline = Color(0xFF2C2F36),
            primaryContainer = Color(0xFF2D4A7A),
            onPrimaryContainer = Color(0xFFDCE6FF),
        )
    }
    // the accent drives headings, switches, sliders, selected chips and (unless set separately) outlines
    val accent = o.accent
    return base.copy(
        primary = accent ?: base.primary,
        primaryContainer = accent?.copy(alpha = 0.35f) ?: base.primaryContainer,
        background = o.bg ?: base.background,
        surface = o.surface ?: base.surface,
        onBackground = o.text ?: base.onBackground,
        onSurface = o.text ?: base.onSurface,
        outline = o.outline ?: accent?.copy(alpha = 0.6f) ?: base.outline,
    )
}

@Composable
fun RemoteTheme(name: String = "dark", overrides: ThemeOverrides = ThemeOverrides.NONE, content: @Composable () -> Unit) {
    val scheme = schemeFor(name, overrides)
    MaterialTheme(colorScheme = scheme) {
        // plain Text() picks this colour, so it follows the theme too
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}
