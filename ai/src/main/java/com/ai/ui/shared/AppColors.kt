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
        "AppBackground" to 0xFF000000.toInt(),
        "PrimaryAccent" to 0xFF8B5CF6.toInt(),
        "SecondaryAccent" to 0xFF6366F1.toInt(),
        "InfoAccent" to 0xFF6B9BFF.toInt(),
        "SuccessAccent" to 0xFF4CAF50.toInt(),
        "DangerAccent" to 0xFFFF6B6B.toInt(),
        "ErrorAccent" to 0xFFFF5252.toInt(),
        "DestructiveActionBackground" to 0xFFF44336.toInt(),
        "WarningAccent" to 0xFFFF9800.toInt(),
        "CautionAccent" to 0xFFFFEB3B.toInt(),
        "QueueAccent" to 0xFFA1887F.toInt(),
        "SurfaceDark" to 0xFF2A2A2A.toInt(),
        "CardBackground" to 0xFF2A2A3A.toInt(),
        "CardBackgroundAlt" to 0xFF2A3A4A.toInt(),
        "ButtonBackground" to 0xFF2A3A4A.toInt(),
        "DisabledBackground" to 0xFF1A1A1A.toInt(),
        "SelectionHighlight" to 0xFF2A4A3A.toInt(),
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
        "SuccessCountAccent" to 0xFF00E676.toInt()
    )

    private val LegacyUiColorKeys = mapOf(
        "Purple" to "PrimaryAccent",
        "Indigo" to "SecondaryAccent",
        "Blue" to "InfoAccent",
        "Green" to "SuccessAccent",
        "Red" to "DangerAccent",
        "RedBright" to "ErrorAccent",
        "RedDark" to "DestructiveActionBackground",
        "Orange" to "WarningAccent",
        "Yellow" to "CautionAccent",
        "Brown" to "QueueAccent",
        "IndigoHighlight" to "SelectionHighlight",
        "CountGreen" to "SuccessCountAccent"
    )

    val DefaultCardBackgroundAltArgb: Int = DefaultUiColorArgb.getValue("CardBackgroundAlt")
    val DefaultButtonBackgroundArgb: Int = DefaultUiColorArgb.getValue("ButtonBackground")

    // App shell colors
    var AppBackground by mutableStateOf(colorFromArgb(defaultArgbFor("AppBackground")))
        private set

    // Role accent colors
    var PrimaryAccent by mutableStateOf(colorFromArgb(defaultArgbFor("PrimaryAccent")))
        private set
    var SecondaryAccent by mutableStateOf(colorFromArgb(defaultArgbFor("SecondaryAccent")))
        private set
    var InfoAccent by mutableStateOf(colorFromArgb(defaultArgbFor("InfoAccent")))
        private set
    var SuccessAccent by mutableStateOf(colorFromArgb(defaultArgbFor("SuccessAccent")))
        private set
    var DangerAccent by mutableStateOf(colorFromArgb(defaultArgbFor("DangerAccent")))
        private set
    var ErrorAccent by mutableStateOf(colorFromArgb(defaultArgbFor("ErrorAccent")))
        private set
    var DestructiveActionBackground by mutableStateOf(colorFromArgb(defaultArgbFor("DestructiveActionBackground")))
        private set
    var WarningAccent by mutableStateOf(colorFromArgb(defaultArgbFor("WarningAccent")))
        private set
    var CautionAccent by mutableStateOf(colorFromArgb(defaultArgbFor("CautionAccent")))
        private set
    var QueueAccent by mutableStateOf(colorFromArgb(defaultArgbFor("QueueAccent")))
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
    var SelectionHighlight by mutableStateOf(colorFromArgb(defaultArgbFor("SelectionHighlight")))
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
    val BorderFocused: Color get() = PrimaryAccent
    var BorderUnfocused by mutableStateOf(colorFromArgb(defaultArgbFor("BorderUnfocused")))
        private set
    val BorderInfoFocused: Color get() = InfoAccent

    // Status colors
    val StatusOk: Color get() = SuccessAccent
    val StatusError: Color get() = ErrorAccent
    val StatusInactive: Color get() = TextTertiary
    val StatusNotUsed: Color get() = TextDisabled

    // Pricing display
    val PricingReal: Color get() = DangerAccent
    val PricingDefault: Color get() = TextDim
    var PricingBadgeBackground by mutableStateOf(colorFromArgb(defaultArgbFor("PricingBadgeBackground")))
        private set
    var PricingBadgeText by mutableStateOf(colorFromArgb(defaultArgbFor("PricingBadgeText")))
        private set

    // Success count
    var SuccessCountAccent by mutableStateOf(colorFromArgb(defaultArgbFor("SuccessCountAccent")))
        private set

    fun colorFromArgb(argb: Int): Color =
        Color(argb.toLong() and 0xFFFFFFFFL)

    fun defaultUiColorMap(): Map<String, Int> = DefaultUiColorArgb.toMap()

    fun defaultArgbFor(key: String): Int = DefaultUiColorArgb.getValue(key)

    fun normalizeUiColorOverrides(overrides: Map<String, Int>): Map<String, Int> =
        DefaultUiColorArgb.toMutableMap().apply {
            overrides.forEach { (rawKey, value) ->
                val key = LegacyUiColorKeys[rawKey] ?: rawKey
                if (key in DefaultUiColorArgb) put(key, value)
            }
        }

    fun applyUiColors(cardBackgroundArgb: Int, buttonBackgroundArgb: Int) {
        applyUiColors(
            mapOf(
                "CardBackgroundAlt" to cardBackgroundArgb,
                "ButtonBackground" to buttonBackgroundArgb
            )
        )
    }

    fun applyUiColors(overrides: Map<String, Int>) {
        val colors = normalizeUiColorOverrides(overrides)
        fun color(key: String): Color = colorFromArgb(colors[key] ?: defaultArgbFor(key))
        AppBackground = color("AppBackground")
        PrimaryAccent = color("PrimaryAccent")
        SecondaryAccent = color("SecondaryAccent")
        InfoAccent = color("InfoAccent")
        SuccessAccent = color("SuccessAccent")
        DangerAccent = color("DangerAccent")
        ErrorAccent = color("ErrorAccent")
        DestructiveActionBackground = color("DestructiveActionBackground")
        WarningAccent = color("WarningAccent")
        CautionAccent = color("CautionAccent")
        QueueAccent = color("QueueAccent")
        SurfaceDark = color("SurfaceDark")
        CardBackground = color("CardBackground")
        CardBackgroundAlt = color("CardBackgroundAlt")
        ButtonBackground = color("ButtonBackground")
        DisabledBackground = color("DisabledBackground")
        SelectionHighlight = color("SelectionHighlight")
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
        SuccessCountAccent = color("SuccessCountAccent")
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
        focusedBorderColor = InfoAccent,
        unfocusedBorderColor = BorderUnfocused,
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        cursorColor = Color.White,
        focusedLabelColor = InfoAccent,
        unfocusedLabelColor = Color.Gray
    )
}
