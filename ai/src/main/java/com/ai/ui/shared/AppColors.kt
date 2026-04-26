package com.ai.ui.shared

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object AppColors {
    // Primary accent colors
    val Purple = Color(0xFF8B5CF6)
    val Indigo = Color(0xFF6366F1)
    val Blue = Color(0xFF6B9BFF)
    val Green = Color(0xFF4CAF50)
    val Red = Color(0xFFFF6B6B)
    val RedBright = Color(0xFFFF5252)
    val RedDark = Color(0xFFF44336)
    val Orange = Color(0xFFFF9800)

    // Card and surface colors
    val SurfaceDark = Color(0xFF2A2A2A)
    val CardBackground = Color(0xFF2A2A3A)
    val CardBackgroundAlt = Color(0xFF2A3A4A)
    val DisabledBackground = Color(0xFF1A1A1A)
    val IndigoHighlight = Color(0xFF2A4A3A)

    // Text colors — values tuned for >= 4.5:1 contrast vs SurfaceDark (WCAG AA body text).
    // #8D is the minimum gray that passes 4.5:1 against #2A2A2A; TextDim/TextTertiary bumped
    // above that, lower tiers kept for disabled/decorative roles that aren't body text.
    val TextPrimary = Color.White
    val TextSecondary = Color(0xFFCCCCCC)
    val TextTertiary = Color(0xFFA0A0A0)
    val TextDim = Color(0xFF909090)
    val TextDisabled = Color(0xFF555555)
    val TextVeryDim = Color(0xFF444444)
    val TextDarkest = Color(0xFF333333)

    // Divider colors
    val DividerDark = Color(0xFF333333)

    // Border colors
    val BorderFocused = Purple
    val BorderUnfocused = Color(0xFF444444)
    val BorderBlueFocused = Blue

    // Status colors
    val StatusOk = Green
    val StatusError = RedBright
    val StatusInactive = TextTertiary
    val StatusNotUsed = TextDisabled

    // Pricing display
    val PricingReal = Red
    val PricingDefault = TextDim
    val PricingBadgeBackground = Color(0xFF666666)
    val PricingBadgeText = Color(0xFF2A2A2A)

    // Success count
    val CountGreen = Color(0xFF00E676)

    /** Default filled style for OutlinedButton — gives every "neutral" button a subtle
     *  background instead of the Material default transparent container. */
    @Composable
    fun outlinedButtonColors(
        containerColor: Color = CardBackgroundAlt,
        contentColor: Color = Color.White
    ): ButtonColors = ButtonDefaults.outlinedButtonColors(
        containerColor = containerColor, contentColor = contentColor
    )

    @Composable
    fun outlinedFieldColors() = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Blue,
        unfocusedBorderColor = BorderUnfocused,
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        cursorColor = Color.White,
        focusedLabelColor = Blue,
        unfocusedLabelColor = Color.Gray
    )
}
