package com.ai.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * Renders a country / globe / flag emoji with a thin white backplate
 * so flags whose stripes include black (Germany, Estonia, the Vatican
 * etc.) stay visible against the app's full-black background. Plain
 * `Text(flag)` over `#000000` loses those stripes entirely.
 *
 * Used by the language-flag overlays on the View-family card screens
 * (Prompt, Meta, Fan-in, Reports) and any other place that paints a
 * flag emoji directly onto the app background.
 */
@Composable
fun LanguageFlagBadge(
    flag: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier
) {
    Text(
        text = flag,
        fontSize = fontSize,
        modifier = modifier
            .background(
                color = Color.White,
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 2.dp)
    )
}
