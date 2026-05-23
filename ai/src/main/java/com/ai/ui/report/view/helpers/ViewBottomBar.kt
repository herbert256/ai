package com.ai.ui.report.view.helpers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bottom-bar spec published by the View family's [ViewTitleBar] while a
 * View screen is mounted. Decoupled from the app's generic
 * `LocalBottomIconState` / `BottomIconBar` on purpose — the View
 * subsystem owns its own lightweight chrome.
 *
 * A non-null spec signals "a View screen is active" (so AppNavHost
 * renders [ViewBottomBar] in place of the generic bar). [onManage] null
 * = a drill-deeper View screen with no manage affordance (empty bar).
 */
data class ViewBottomBarSpec(
    val onManage: (() -> Unit)?,
    /** When non-null, a left-aligned ☝️/✋ "one vs all" toggle is shown:
     *  true = ✋ (showing all items), false = ☝️ (showing one). Null = no
     *  toggle for this screen. The 🔧 manage icon stays centred regardless. */
    val showAll: Boolean? = null,
    val onToggleOneOrAll: (() -> Unit)? = null
)

/** Set by AppNavHost; written by [ViewTitleBar] while a View screen is
 *  on screen. Null when no View screen is active. */
val LocalViewBottomBar = compositionLocalOf<MutableState<ViewBottomBarSpec?>?> { null }

/**
 * The View family's bottom bar: a single centred 🔧 manage icon (same
 * behaviour as the generic bottom bar showed for View screens). Renders
 * nothing when [ViewBottomBarSpec.onManage] is null (drill-deeper View
 * screens). Deliberately does NOT share code with the generic
 * `BottomIconBar`.
 */
@Composable
fun ViewBottomBar(spec: ViewBottomBarSpec, modifier: Modifier = Modifier) {
    val onManage = spec.onManage
    Box(
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        // Left-aligned ☝️/✋ "one vs all" toggle — shows the current mode;
        // tapping flips it. Independent of the centred 🔧.
        if (spec.showAll != null && spec.onToggleOneOrAll != null) {
            Text(
                text = if (spec.showAll) "✋" else "☝️",
                fontSize = 28.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .clickable(onClick = spec.onToggleOneOrAll)
                    .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 8.dp)
            )
        }
        if (onManage != null) {
            Text(
                text = "🔧",
                fontSize = 30.sp,
                color = Color.White,
                modifier = Modifier
                    .clickable(onClick = onManage)
                    .padding(8.dp)
            )
        }
    }
}
