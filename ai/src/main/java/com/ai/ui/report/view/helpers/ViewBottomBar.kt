package com.ai.ui.report.view.helpers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors

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
     *  true = ☝️ (showing all items), false = ✋ (showing one). Null = no
     *  toggle for this screen. The 🔧 manage icon stays centred regardless. */
    val showAll: Boolean? = null,
    val onToggleOneOrAll: (() -> Unit)? = null,
    /** When non-null, a left-aligned 🗂️ icon is shown (View hub only) →
     *  the "pick a report to view" screen. */
    val onViewList: (() -> Unit)? = null,
    /** When non-null, a right-aligned ❓ help icon is shown, opening this
     *  screen's help topic. The View top bar moved help down here. */
    val helpTopic: String? = null,
    /** When non-null, a right-aligned 📤 icon is shown left of the ❓ —
     *  exports the current screen as a shareable file (Value view's
     *  single-page HTML export) or shares the prose screens' text. */
    val onExport: (() -> Unit)? = null,
    /** When non-null, a right-aligned 📋 icon is shown left of the 📤 —
     *  copies the current screen's text to the clipboard (the prose
     *  View screens; their Manage twins have carried copy all along). */
    val onCopy: (() -> Unit)? = null,
    /** Identity token of the title-bar instance that published this spec.
     *  A leaving screen clears the shared state only when it still owns it
     *  — without this, the leaving screen's onDispose can null the spec the
     *  *entering* screen just published when navigating between View screens
     *  on different Navigation routes (the bottom bar then vanishes). */
    val owner: Any? = null,
    /** True when published by the report View family ([ViewTitleBar]); false
     *  for the entity-info View bars ([com.ai.ui.shared.ViewScreenTitleBar] —
     *  Model Info / provider / flock / swarm / HTML preview). AppNavHost
     *  suppresses the persistent Home icon bar only on report View screens. */
    val reportView: Boolean = false
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
    val navigateHelp = com.ai.ui.shared.LocalNavigateToHelp.current
    // Glyphs resolve from the user's Default icons (Settings → Default icons)
    // rather than being hard-coded here.
    val mi = com.ai.ui.shared.LocalMetadataIcons.current
    Box(
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        // Left-aligned ☝️/✋ "one vs all" toggle — shows the current mode;
        // tapping flips it. Independent of the centred 🔧.
        if (spec.showAll != null && spec.onToggleOneOrAll != null) {
            Text(
                text = if (spec.showAll) mi.viewShowAll else mi.viewShowOne,
                fontSize = 28.sp,
                color = AppColors.TextPrimary,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .minimumInteractiveComponentSize()
                    .clickable(
                        onClick = spec.onToggleOneOrAll,
                        role = Role.Button,
                        onClickLabel = if (spec.showAll) "show one at a time" else "show all at once"
                    )
                    .semantics {
                        contentDescription = if (spec.showAll) "Showing all items" else "Showing one item"
                    }
                    .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 8.dp)
            )
        }
        // Left-aligned 🗂️ → pick-a-report-to-view (View hub only; the hub
        // has no ☝️/✋ toggle, so CenterStart is free). A card-index glyph,
        // not 📋 — that read as copy-to-clipboard.
        if (spec.onViewList != null) {
            Text(
                text = mi.pickReport,
                fontSize = 27.sp,
                color = AppColors.TextPrimary,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .minimumInteractiveComponentSize()
                    .clickable(
                        onClick = spec.onViewList,
                        role = Role.Button,
                        onClickLabel = "pick a report to view"
                    )
                    .semantics { contentDescription = "Pick report" }
                    .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 8.dp)
            )
        }
        if (onManage != null) {
            Text(
                text = mi.openManage,
                fontSize = 30.sp,
                color = AppColors.TextPrimary,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clickable(
                        onClick = onManage,
                        role = Role.Button,
                        onClickLabel = "open the manage screen"
                    )
                    .semantics { contentDescription = "Manage report" }
                    .padding(8.dp)
            )
        }
        // Right-aligned cluster: optional 📤 export, then the ❓ help —
        // moved here from the View top bar.
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (spec.onCopy != null) {
                Text(
                    text = mi.copy,
                    fontSize = 26.sp,
                    color = AppColors.TextPrimary,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clickable(
                            onClick = spec.onCopy,
                            role = Role.Button,
                            onClickLabel = "copy this screen's text"
                        )
                        .semantics { contentDescription = "Copy" }
                        .padding(8.dp)
                )
            }
            if (spec.onExport != null) {
                Text(
                    text = mi.share,
                    fontSize = 27.sp,
                    color = AppColors.TextPrimary,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clickable(
                            onClick = spec.onExport,
                            role = Role.Button,
                            onClickLabel = "export this view"
                        )
                        .semantics { contentDescription = "Export" }
                        .padding(8.dp)
                )
            }
            // Red ❓ → the screen's help page. The white ❔ icon-legend helper
            // was removed from the View family — these screens show only the
            // red help icon.
            if (spec.helpTopic != null) {
                Text(
                    text = mi.help,
                    fontSize = 28.sp,
                    color = AppColors.InfoAccent,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "open help for this screen"
                        ) { navigateHelp(spec.helpTopic) }
                        .semantics { contentDescription = "Help" }
                        .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 8.dp)
                )
            }
        }
    }
}
