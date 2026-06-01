package com.ai.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.ai.ui.shared.AppColors

@Composable
fun AppTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = darkColorScheme(
        primary = Color(0xFF4A9EFF),
        onPrimary = Color.White,
        secondary = Color(0xFF3A8EEF),
        onSecondary = Color.White,
        background = AppColors.AppBackground,
        onBackground = Color(0xFFEEEEEE),
        surface = AppColors.AppBackground,
        onSurface = Color(0xFFEEEEEE),
        surfaceVariant = Color(0xFF0F3460),
        onSurfaceVariant = Color(0xFF888888),
        error = Color(0xFFFF4757),
        onError = Color.White
    )
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
