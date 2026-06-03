package com.ai.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.ai.ui.shared.AppColors

@Composable
fun AppTheme(
    content: @Composable () -> Unit
) {
    // Every Material colour role is sourced from AppColors so that
    // MaterialTheme.colorScheme.* consumers follow the UI Colors settings.
    val colorScheme = darkColorScheme(
        primary = AppColors.InfoAccent,
        onPrimary = AppColors.TextPrimary,
        secondary = AppColors.InfoAccent,
        onSecondary = AppColors.TextPrimary,
        background = AppColors.AppBackground,
        onBackground = AppColors.TextPrimary,
        surface = AppColors.AppBackground,
        onSurface = AppColors.TextPrimary,
        surfaceVariant = AppColors.CardBackground,
        onSurfaceVariant = AppColors.TextDim,
        error = AppColors.DangerAccent,
        onError = AppColors.TextPrimary
    )
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
