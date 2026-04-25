package com.dualframe.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF4CAF50),
    onPrimary = Color.White,
    secondary = Color(0xFFFFD54A),
    onSecondary = Color(0xFF111111),
    background = Color(0xFF050505),
    surface = Color(0xFF111111),
    onBackground = Color.White,
    onSurface = Color.White,
    error = Color(0xFFCF6679),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFFCCCCCC),
    outline = Color(0xFF8A8A8A),
)

@Composable
fun DualFrameTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = DualFrameTypography,
        content = content,
    )
}
