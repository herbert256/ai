package com.ai.ui.shared

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

object AppColors {
    private val DefaultUiColorArgb = linkedMapOf(
        "Purple" to 0xFF8B5CF6.toInt(),
        "Indigo" to 0xFF6366F1.toInt(),
        "Blue" to 0xFF6B9BFF.toInt(),
        "Green" to 0xFF4CAF50.toInt(),
        "Red" to 0xFFFF6B6B.toInt(),
        "RedBright" to 0xFFFF5252.toInt(),
        "RedDark" to 0xFFF44336.toInt(),
        "Orange" to 0xFFFF9800.toInt(),
        "Yellow" to 0xFFFFEB3B.toInt(),
        "Brown" to 0xFFA1887F.toInt(),
        "SurfaceDark" to 0xFF2A2A2A.toInt(),
        "CardBackground" to 0xFF2A2A3A.toInt(),
        "CardBackgroundAlt" to 0xFF2A3A4A.toInt(),
        "ButtonBackground" to 0xFF2A3A4A.toInt(),
        "DisabledBackground" to 0xFF1A1A1A.toInt(),
        "IndigoHighlight" to 0xFF2A4A3A.toInt(),
        "TextPrimary" to 0xFFFFFFFF.toInt(),
        "TextSecondary" to 0xFFCCCCCC.toInt(),
        "TextTertiary" to 0xFFA0A0A0.toInt(),
        "TextDim" to 0xFF909090.toInt(),
        "TextDisabled" to 0xFF555555.toInt(),
        "TextVeryDim" to 0xFF444444.toInt(),
        "TextDarkest" to 0xFF333333.toInt(),
        "DividerDark" to 0xFF333333.toInt(),
        "BorderUnfocused" to 0xFF444444.toInt(),
        "PricingBadgeBackground" to 0xFF666666.toInt(),
        "PricingBadgeText" to 0xFF2A2A2A.toInt(),
        "CountGreen" to 0xFF00E676.toInt()
    )

    val DefaultCardBackgroundAltArgb: Int = DefaultUiColorArgb.getValue("CardBackgroundAlt")
    val DefaultButtonBackgroundArgb: Int = DefaultUiColorArgb.getValue("ButtonBackground")

    // Primary accent colors
    var Purple by mutableStateOf(colorFromArgb(defaultArgbFor("Purple")))
        private set
    var Indigo by mutableStateOf(colorFromArgb(defaultArgbFor("Indigo")))
        private set
    var Blue by mutableStateOf(colorFromArgb(defaultArgbFor("Blue")))
        private set
    var Green by mutableStateOf(colorFromArgb(defaultArgbFor("Green")))
        private set
    var Red by mutableStateOf(colorFromArgb(defaultArgbFor("Red")))
        private set
    var RedBright by mutableStateOf(colorFromArgb(defaultArgbFor("RedBright")))
        private set
    var RedDark by mutableStateOf(colorFromArgb(defaultArgbFor("RedDark")))
        private set
    var Orange by mutableStateOf(colorFromArgb(defaultArgbFor("Orange")))
        private set
    var Yellow by mutableStateOf(colorFromArgb(defaultArgbFor("Yellow")))
        private set
    var Brown by mutableStateOf(colorFromArgb(defaultArgbFor("Brown")))
        private set

    // Card and surface colors
    var SurfaceDark by mutableStateOf(colorFromArgb(defaultArgbFor("SurfaceDark")))
        private set
    var CardBackground by mutableStateOf(colorFromArgb(defaultArgbFor("CardBackground")))
        private set
    var CardBackgroundAlt by mutableStateOf(colorFromArgb(DefaultCardBackgroundAltArgb))
        private set
    var ButtonBackground by mutableStateOf(colorFromArgb(DefaultButtonBackgroundArgb))
        private set
    var DisabledBackground by mutableStateOf(colorFromArgb(defaultArgbFor("DisabledBackground")))
        private set
    var IndigoHighlight by mutableStateOf(colorFromArgb(defaultArgbFor("IndigoHighlight")))
        private set

    // Text colors — values tuned for >= 4.5:1 contrast vs SurfaceDark (WCAG AA body text).
    // #8D is the minimum gray that passes 4.5:1 against #2A2A2A; TextDim/TextTertiary bumped
    // above that, lower tiers kept for disabled/decorative roles that aren't body text.
    var TextPrimary by mutableStateOf(colorFromArgb(defaultArgbFor("TextPrimary")))
        private set
    var TextSecondary by mutableStateOf(colorFromArgb(defaultArgbFor("TextSecondary")))
        private set
    var TextTertiary by mutableStateOf(colorFromArgb(defaultArgbFor("TextTertiary")))
        private set
    var TextDim by mutableStateOf(colorFromArgb(defaultArgbFor("TextDim")))
        private set
    var TextDisabled by mutableStateOf(colorFromArgb(defaultArgbFor("TextDisabled")))
        private set
    var TextVeryDim by mutableStateOf(colorFromArgb(defaultArgbFor("TextVeryDim")))
        private set
    var TextDarkest by mutableStateOf(colorFromArgb(defaultArgbFor("TextDarkest")))
        private set

    // Divider colors
    var DividerDark by mutableStateOf(colorFromArgb(defaultArgbFor("DividerDark")))
        private set

    // Border colors
    val BorderFocused: Color get() = Purple
    var BorderUnfocused by mutableStateOf(colorFromArgb(defaultArgbFor("BorderUnfocused")))
        private set
    val BorderBlueFocused: Color get() = Blue

    // Status colors
    val StatusOk: Color get() = Green
    val StatusError: Color get() = RedBright
    val StatusInactive: Color get() = TextTertiary
    val StatusNotUsed: Color get() = TextDisabled

    // Pricing display
    val PricingReal: Color get() = Red
    val PricingDefault: Color get() = TextDim
    var PricingBadgeBackground by mutableStateOf(colorFromArgb(defaultArgbFor("PricingBadgeBackground")))
        private set
    var PricingBadgeText by mutableStateOf(colorFromArgb(defaultArgbFor("PricingBadgeText")))
        private set

    // Success count
    var CountGreen by mutableStateOf(colorFromArgb(defaultArgbFor("CountGreen")))
        private set

    fun colorFromArgb(argb: Int): Color =
        Color(argb.toLong() and 0xFFFFFFFFL)

    fun defaultUiColorMap(): Map<String, Int> = DefaultUiColorArgb.toMap()

    fun defaultArgbFor(key: String): Int = DefaultUiColorArgb.getValue(key)

    fun applyUiColors(cardBackgroundArgb: Int, buttonBackgroundArgb: Int) {
        applyUiColors(
            mapOf(
                "CardBackgroundAlt" to cardBackgroundArgb,
                "ButtonBackground" to buttonBackgroundArgb
            )
        )
    }

    fun applyUiColors(overrides: Map<String, Int>) {
        fun color(key: String): Color = colorFromArgb(overrides[key] ?: defaultArgbFor(key))
        Purple = color("Purple")
        Indigo = color("Indigo")
        Blue = color("Blue")
        Green = color("Green")
        Red = color("Red")
        RedBright = color("RedBright")
        RedDark = color("RedDark")
        Orange = color("Orange")
        Yellow = color("Yellow")
        Brown = color("Brown")
        SurfaceDark = color("SurfaceDark")
        CardBackground = color("CardBackground")
        CardBackgroundAlt = color("CardBackgroundAlt")
        ButtonBackground = color("ButtonBackground")
        DisabledBackground = color("DisabledBackground")
        IndigoHighlight = color("IndigoHighlight")
        TextPrimary = color("TextPrimary")
        TextSecondary = color("TextSecondary")
        TextTertiary = color("TextTertiary")
        TextDim = color("TextDim")
        TextDisabled = color("TextDisabled")
        TextVeryDim = color("TextVeryDim")
        TextDarkest = color("TextDarkest")
        DividerDark = color("DividerDark")
        BorderUnfocused = color("BorderUnfocused")
        PricingBadgeBackground = color("PricingBadgeBackground")
        PricingBadgeText = color("PricingBadgeText")
        CountGreen = color("CountGreen")
    }

    /** Default filled style for OutlinedButton — gives every "neutral" button a subtle
     *  background instead of the Material default transparent container. */
    @Composable
    fun outlinedButtonColors(
        containerColor: Color = ButtonBackground,
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
