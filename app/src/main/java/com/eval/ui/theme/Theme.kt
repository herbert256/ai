package com.eval.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4A9EFF),
    onPrimary = Color.White,
    secondary = Color(0xFF3A8EEF),
    onSecondary = Color.White,
    background = Color(0xFF1A1A2E),
    onBackground = Color(0xFFEEEEEE),
    surface = Color(0xFF16213E),
    onSurface = Color(0xFFEEEEEE),
    surfaceVariant = Color(0xFF0F3460),
    onSurfaceVariant = Color(0xFF888888),
    error = Color(0xFFFF4757),
    onError = Color.White
)

@Composable
fun EvalTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography(),
        content = content
    )
}
