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
        "MainTitle" to 0xFFFFFFFF.toInt(),
        "SubTitle" to 0xFFFF9800.toInt(),
        "PrimaryAccent" to 0xFF8B5CF6.toInt(),
        "InfoAccent" to 0xFF6B9BFF.toInt(),
        "SuccessAccent" to 0xFF4CAF50.toInt(),
        "DangerAccent" to 0xFFFF6B6B.toInt(),
        "WarningAccent" to 0xFFFF9800.toInt(),
        "QueueAccent" to 0xFFA1887F.toInt(),
        "SurfaceDark" to 0xFF2A2A2A.toInt(),
        "CardBackground" to 0xFF2A2A3A.toInt(),
        "CardBackgroundAlt" to 0xFF2A3A4A.toInt(),
        "ButtonBackground" to 0xFF27594E.toInt(),
        "DisabledBackground" to 0xFF1A1A1A.toInt(),
        "SelectionHighlight" to 0xFF2A4A3A.toInt(),
        "TextPrimary" to 0xFFFFFFFF.toInt(),
        "TextSecondary" to 0xFFCCCCCC.toInt(),
        "TextDim" to 0xFF909090.toInt(),
        "BorderUnfocused" to 0xFF444444.toInt()
    )

    /** Day (light) factory palette — the counterpart to the dark
     *  [DefaultUiColorArgb] (which is the Night set). Same keys; tuned
     *  for a light background with readable text and accents. Editable
     *  per-key on the UI Colors screen with the Day ☀️ tab selected. */
    val DefaultUiColorArgbDay = linkedMapOf(
        "AppBackground" to 0xFFF7F7FA.toInt(),
        "MainTitle" to 0xFF161616.toInt(),
        "SubTitle" to 0xFFB26A00.toInt(),
        "PrimaryAccent" to 0xFF7C3AED.toInt(),
        "InfoAccent" to 0xFF2563EB.toInt(),
        "SuccessAccent" to 0xFF2E7D32.toInt(),
        "DangerAccent" to 0xFFD32F2F.toInt(),
        "WarningAccent" to 0xFFB26A00.toInt(),
        "QueueAccent" to 0xFF6D4C41.toInt(),
        "SurfaceDark" to 0xFFE9E9EE.toInt(),
        "CardBackground" to 0xFFFFFFFF.toInt(),
        "CardBackgroundAlt" to 0xFFEAF0F7.toInt(),
        "ButtonBackground" to 0xFFD6E8E1.toInt(),
        "DisabledBackground" to 0xFFE2E2E2.toInt(),
        "SelectionHighlight" to 0xFFD7EADF.toInt(),
        "TextPrimary" to 0xFF161616.toInt(),
        "TextSecondary" to 0xFF454545.toInt(),
        "TextDim" to 0xFF767676.toInt(),
        "BorderUnfocused" to 0xFFB9B9B9.toInt()
    )

    private val UiColorAliasFallbacks = mapOf(
        "PrimaryAccent" to listOf("Purple"),
        "InfoAccent" to listOf("SecondaryAccent", "Blue", "Indigo"),
        "SuccessAccent" to listOf("SuccessCountAccent", "Green", "CountGreen"),
        "DangerAccent" to listOf("ErrorAccent", "DestructiveActionBackground", "Red", "RedBright", "RedDark"),
        "WarningAccent" to listOf("CautionAccent", "Orange", "Yellow"),
        "QueueAccent" to listOf("Brown"),
        "SelectionHighlight" to listOf("IndigoHighlight"),
        "TextSecondary" to listOf("TextTertiary"),
        "TextDim" to listOf("TextDisabled", "TextVeryDim", "TextDarkest"),
        "BorderUnfocused" to listOf("DividerDark")
    )

    val DefaultCardBackgroundAltArgb: Int = DefaultUiColorArgb.getValue("CardBackgroundAlt")
    val DefaultButtonBackgroundArgb: Int = DefaultUiColorArgb.getValue("ButtonBackground")

    // App shell colors
    var AppBackground by mutableStateOf(colorFromArgb(defaultArgbFor("AppBackground")))
        private set
    var MainTitle by mutableStateOf(colorFromArgb(defaultArgbFor("MainTitle")))
        private set
    var SubTitle by mutableStateOf(colorFromArgb(defaultArgbFor("SubTitle")))
        private set

    // Role accent colors
    var PrimaryAccent by mutableStateOf(colorFromArgb(defaultArgbFor("PrimaryAccent")))
        private set
    var InfoAccent by mutableStateOf(colorFromArgb(defaultArgbFor("InfoAccent")))
        private set
    val SecondaryAccent: Color get() = InfoAccent
    var SuccessAccent by mutableStateOf(colorFromArgb(defaultArgbFor("SuccessAccent")))
        private set
    val SuccessCountAccent: Color get() = SuccessAccent
    var DangerAccent by mutableStateOf(colorFromArgb(defaultArgbFor("DangerAccent")))
        private set
    val ErrorAccent: Color get() = DangerAccent
    val DestructiveActionBackground: Color get() = DangerAccent
    var WarningAccent by mutableStateOf(colorFromArgb(defaultArgbFor("WarningAccent")))
        private set
    val CautionAccent: Color get() = WarningAccent
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
    // #8D is the minimum gray that passes 4.5:1 against #2A2A2A.
    var TextPrimary by mutableStateOf(colorFromArgb(defaultArgbFor("TextPrimary")))
        private set
    var TextSecondary by mutableStateOf(colorFromArgb(defaultArgbFor("TextSecondary")))
        private set
    val TextTertiary: Color get() = TextSecondary
    var TextDim by mutableStateOf(colorFromArgb(defaultArgbFor("TextDim")))
        private set
    val TextDisabled: Color get() = TextDim
    val TextVeryDim: Color get() = TextDim
    val TextDarkest: Color get() = TextDim

    // Divider colors
    val DividerDark: Color get() = BorderUnfocused

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

    fun colorFromArgb(argb: Int): Color =
        Color(argb.toLong() and 0xFFFFFFFFL)

    fun defaultUiColorMap(): Map<String, Int> = DefaultUiColorArgb.toMap()

    fun defaultArgbFor(key: String): Int = DefaultUiColorArgb.getValue(key)

    fun normalizeUiColorOverrides(overrides: Map<String, Int>): Map<String, Int> {
        val normalized = DefaultUiColorArgb.toMutableMap().apply {
            overrides.forEach { (rawKey, value) ->
                if (rawKey in DefaultUiColorArgb) put(rawKey, value)
            }
        }
        UiColorAliasFallbacks.forEach { (key, aliases) ->
            if (key !in overrides) {
                aliases.firstNotNullOfOrNull { alias -> overrides[alias] }?.let { normalized[key] = it }
            }
        }
        if ("SubTitle" !in overrides) {
            val legacySubTitle = overrides["WarningAccent"] ?: overrides["Orange"]
            if (legacySubTitle != null) normalized["SubTitle"] = legacySubTitle
        }
        return normalized
    }

    fun applyUiColors(cardBackgroundArgb: Int, buttonBackgroundArgb: Int) {
        applyUiColors(
            mapOf(
                "CardBackgroundAlt" to cardBackgroundArgb,
                "ButtonBackground" to buttonBackgroundArgb
            )
        )
    }

    /** Night-base factory default for a key (the original behaviour). */
    fun defaultArgbForDay(key: String): Int = DefaultUiColorArgbDay.getValue(key)

    /** Factory default for a key in the requested set. */
    fun defaultArgbFor(key: String, day: Boolean): Int =
        if (day) DefaultUiColorArgbDay.getValue(key) else DefaultUiColorArgb.getValue(key)

    /** Resolve whether the Day set is active for [mode] given the
     *  current system day/night state ([systemDark]). */
    fun isDayActive(mode: com.ai.viewmodel.UiColorMode, systemDark: Boolean): Boolean = when (mode) {
        com.ai.viewmodel.UiColorMode.DAY -> true
        com.ai.viewmodel.UiColorMode.NIGHT -> false
        com.ai.viewmodel.UiColorMode.AUTO -> !systemDark
    }

    /** Paint the live colour state from [overrides] layered over the Day
     *  or Night factory base. Missing keys fall back to that base. */
    fun applyResolved(day: Boolean, overrides: Map<String, Int>) {
        val base = if (day) DefaultUiColorArgbDay else DefaultUiColorArgb
        fun color(key: String): Color = colorFromArgb(overrides[key] ?: base.getValue(key))
        AppBackground = color("AppBackground")
        MainTitle = color("MainTitle")
        SubTitle = color("SubTitle")
        PrimaryAccent = color("PrimaryAccent")
        InfoAccent = color("InfoAccent")
        SuccessAccent = color("SuccessAccent")
        DangerAccent = color("DangerAccent")
        WarningAccent = color("WarningAccent")
        QueueAccent = color("QueueAccent")
        SurfaceDark = color("SurfaceDark")
        CardBackground = color("CardBackground")
        CardBackgroundAlt = color("CardBackgroundAlt")
        ButtonBackground = color("ButtonBackground")
        DisabledBackground = color("DisabledBackground")
        SelectionHighlight = color("SelectionHighlight")
        TextPrimary = color("TextPrimary")
        TextSecondary = color("TextSecondary")
        TextDim = color("TextDim")
        BorderUnfocused = color("BorderUnfocused")
    }

    /** Apply the effective theme: pick Day or Night per [mode] +
     *  [systemDark], then paint from that set's overrides. */
    fun applyTheme(
        dayOverrides: Map<String, Int>,
        nightOverrides: Map<String, Int>,
        mode: com.ai.viewmodel.UiColorMode,
        systemDark: Boolean,
    ) {
        val day = isDayActive(mode, systemDark)
        applyResolved(day, if (day) dayOverrides else normalizeUiColorOverrides(nightOverrides))
    }

    /** Legacy single-set apply (Night base). Kept for the import path. */
    fun applyUiColors(overrides: Map<String, Int>) =
        applyResolved(false, normalizeUiColorOverrides(overrides))

    /** Default filled style for OutlinedButton — gives every "neutral" button a subtle
     *  background instead of the Material default transparent container. */
    @Composable
    fun outlinedButtonColors(
        containerColor: Color = ButtonBackground,
        contentColor: Color = TextPrimary
    ): ButtonColors = ButtonDefaults.outlinedButtonColors(
        containerColor = containerColor, contentColor = contentColor
    )

    @Composable
    fun outlinedFieldColors() = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = InfoAccent,
        unfocusedBorderColor = BorderUnfocused,
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = TextPrimary,
        focusedLabelColor = InfoAccent,
        unfocusedLabelColor = TextDim
    )
}
