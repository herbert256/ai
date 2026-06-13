package com.ai.ui.report.view.helpers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
     *  single-page HTML export). */
    val onExport: (() -> Unit)? = null,
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
    val hasToggle = spec.showAll != null && spec.onToggleOneOrAll != null
    // Whether the bar shows any non-help icon — gates the white ❔ icon-legend
    // helper (every screen with bar icons carries one).
    val hasActionIcon = onManage != null || hasToggle || spec.onViewList != null || spec.onExport != null
    var showLegend by remember { mutableStateOf(false) }
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
                    .clickable(onClick = spec.onToggleOneOrAll)
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
                    .clickable(onClick = spec.onViewList)
                    .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 8.dp)
            )
        }
        if (onManage != null) {
            Text(
                text = mi.openManage,
                fontSize = 30.sp,
                color = AppColors.TextPrimary,
                modifier = Modifier
                    .clickable(onClick = onManage)
                    .padding(8.dp)
            )
        }
        // Right-aligned cluster: optional 📤 export, then the ❓ help —
        // moved here from the View top bar.
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (spec.onExport != null) {
                Text(
                    text = mi.share,
                    fontSize = 27.sp,
                    color = AppColors.TextPrimary,
                    modifier = Modifier
                        .clickable(onClick = spec.onExport)
                        .padding(8.dp)
                )
            }
            // White ❔ → live icon-legend overlay for this View screen. Shown
            // whenever the bar carries ≥1 non-help icon, mirroring the generic
            // BottomIconBar.
            if (hasActionIcon) {
                Text(
                    text = mi.helpLegend,
                    fontSize = 27.sp,
                    color = AppColors.InfoAccent,
                    modifier = Modifier
                        .clickable { showLegend = true }
                        .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 8.dp)
                )
            }
            if (spec.helpTopic != null) {
                Text(
                    text = mi.help,
                    fontSize = 28.sp,
                    color = AppColors.InfoAccent,
                    modifier = Modifier
                        .clickable { navigateHelp(spec.helpTopic) }
                        .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 8.dp)
                )
            }
        }
    }

    if (showLegend) {
        ViewIconLegendOverlay(spec, mi, navigateHelp, onClose = { showLegend = false })
    }
}

/** Row in the View bar's icon-legend overlay. */
private data class ViewLegendRow(val glyph: String, val name: String, val desc: String, val onClick: () -> Unit)

/**
 * Live "Icons on this screen" overlay for [ViewBottomBar] — the View family's
 * counterpart to the generic [com.ai.ui.shared.BottomIconBar]'s
 * IconLegendOverlay. Lists the bar icons currently shown (big glyph + name +
 * one-line description); tapping a row closes the overlay and re-fires that
 * icon's action. Its own single glyph is a red ❓ that opens the screen's help
 * page (when the screen has one).
 */
@Composable
private fun ViewIconLegendOverlay(
    spec: ViewBottomBarSpec,
    mi: com.ai.data.MetadataIcons,
    navigateHelp: (String?) -> Unit,
    onClose: () -> Unit
) {
    val rows = buildList {
        spec.onManage?.let { add(ViewLegendRow(mi.openManage, "Manage", "Open the manage screen for this report.", it)) }
        if (spec.showAll != null && spec.onToggleOneOrAll != null) {
            add(ViewLegendRow(
                if (spec.showAll) mi.viewShowAll else mi.viewShowOne,
                "Show one / all",
                "Switch between showing every item and a single item.",
                spec.onToggleOneOrAll
            ))
        }
        spec.onViewList?.let { add(ViewLegendRow(mi.pickReport, "Pick a report", "Choose another report to view.", it)) }
        spec.onExport?.let { add(ViewLegendRow(mi.share, "Export", "Export this screen as a shareable file.", it)) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = AppColors.AppBackground) {
            Column(modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)) {
                Text(
                    "Icons on this screen", color = AppColors.TextPrimary, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp)
                )
                Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    rows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onClose(); row.onClick() }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                                Text(row.glyph, fontSize = 30.sp, color = AppColors.TextPrimary)
                            }
                            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(row.name, color = AppColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(row.desc, color = AppColors.TextTertiary, fontSize = 13.sp, lineHeight = 16.sp)
                            }
                        }
                        HorizontalDivider(color = AppColors.DividerDark)
                    }
                }
                // The overlay's own bar: a red ❓ → the screen's help page.
                if (spec.helpTopic != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 18.dp, end = 2.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = mi.help, fontSize = 28.sp, color = AppColors.DangerAccent,
                            modifier = Modifier.clickable { onClose(); navigateHelp(spec.helpTopic) }.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}
