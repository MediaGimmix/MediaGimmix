package com.example.mediaplayer.companion

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The app's accent: a dark cyan. */
val DarkCyan = Color(0xFF1A6E5C)

/** A darker shade of the accent (dividers). */
val DarkAccent = Color(0xFF0F4A3D)

private val DarkColors = darkColorScheme(
    primary = DarkCyan,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0F4A3D),
    onPrimaryContainer = Color(0xFFB8F0E0),
    // Lighter cyan for text highlights, which need more contrast on a dark background.
    secondary = Color(0xFF5FB8A2),
    onSecondary = Color(0xFF00382D),
    secondaryContainer = Color(0xFF1F3B35),
    onSecondaryContainer = Color(0xFFCDE9E0),
    tertiary = Color(0xFF7CC8B5),
    onTertiary = Color(0xFF00382D),
    background = Color(0xFF0E1312),
    onBackground = Color(0xFFDDE4E1),
    surface = Color(0xFF0E1312),
    onSurface = Color(0xFFDDE4E1),
    surfaceVariant = Color(0xFF26302D),
    onSurfaceVariant = Color(0xFFA9B5B1),
    surfaceTint = DarkCyan,
    outline = Color(0xFF6F7A77),
    outlineVariant = Color(0xFF3F4946),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val LightColors = lightColorScheme(
    primary = DarkCyan,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F0E0),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = DarkCyan,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE9E0),
    onSecondaryContainer = Color(0xFF06201A),
    tertiary = Color(0xFF3D6373),
    onTertiary = Color.White,
    background = Color(0xFFF5FBF8),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFF5FBF8),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFDBE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    surfaceTint = DarkCyan,
    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBFC9C5),
)

@Composable
fun CompanionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
