package com.vsc.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VscColorScheme = darkColorScheme(
    primary = VscAccent,
    onPrimary = Color.White,
    primaryContainer = VscAccentDark,
    onPrimaryContainer = Color.White,
    secondary = VscGreen,
    onSecondary = VscBackground,
    background = VscBackground,
    onBackground = VscTextPrimary,
    surface = VscSurface,
    onSurface = VscTextPrimary,
    surfaceVariant = VscSurfaceRaised,
    onSurfaceVariant = VscTextMuted,
    outline = VscHairline,
    error = VscRed,
    onError = Color.White,
)

@Composable
fun VscTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VscColorScheme,
        typography = VscTypography,
        content = content,
    )
}
