package com.ai.ui.report.view.helpers

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResultStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.R
import com.ai.data.ReportDataVersion
import com.ai.data.SecondaryDataVersion
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.AiLogoButton
import com.ai.ui.shared.ReportGlyphIcon
import com.ai.ui.shared.LocalNavigateHome
import com.ai.ui.shared.LocalNavigateToCurrentReport
import com.ai.ui.shared.LocalReportIcon
import com.ai.ui.shared.LocalNavigateToHelp

/**
 * The View family's own 3-column top bar — a self-contained copy of the
 * old `shared.ViewScreenTitleBar`, kept under `report/view/helpers` so
 * the View subsystem owns its chrome and shares no code with the app's
 * generic title bar.
 *
 * Three stacked rows: AI logo (left → Home) · centred white screen
 * title + orange report title + optional green subject · ❓ help (right).
 * Horizontal swipe loads the prev/next report of the same View kind
 * silently (no transient message).
 *
 * Instead of the generic `LocalBottomIconState`, it publishes a
 * [ViewBottomBarSpec] into [LocalViewBottomBar] while mounted, so
 * AppNavHost renders the View-owned [ViewBottomBar] (centred 🔧).
 */
@Composable
fun ViewTitleBar(
    reportTitle: String?,
    screenTitle: String?,
    subject: String?,
    helpTopic: String,
    onBack: () -> Unit,
    /** Optional 🔧 manage hook for the View bottom bar. Null on
     *  drill-deeper screens (FanOutPair / Icons) → the
     *  bottom bar renders empty. */
    onOpenManage: (() -> Unit)? = null,
    /** Optional override for the centre-title tap target. Null → falls
     *  back to [LocalNavigateToCurrentReport] then [onBack]. */
    onTitleClick: (() -> Unit)? = null,
    /** Horizontal swipe handlers; return true if a matching prev/next
     *  report was found. Every View screen wires these (drill-deeper
     *  screens included); null only when a screen's filter can't be
     *  built (e.g. a Meta view with no resolvable meta filter). */
    onSwipePrev: (() -> Boolean)? = null,
    onSwipeNext: (() -> Boolean)? = null,
    /** Optional left-aligned ☝️/✋ "one vs all" bottom-bar toggle.
     *  [oneOrAll] = current state (true = ✋ all, false = ☝️ one); null =
     *  no toggle. [onToggleOneOrAll] flips it. */
    oneOrAll: Boolean? = null,
    onToggleOneOrAll: (() -> Unit)? = null,
    /** Optional left-aligned 🗂️ bottom-bar icon → pick-a-report-to-view
     *  (View hub only). */
    onViewList: (() -> Unit)? = null,
    /** Optional right-aligned 📤 bottom-bar icon (left of the ❓) —
     *  exports this screen as a shareable file. */
    onExport: (() -> Unit)? = null,
    /** When set together with a non-blank [activeLanguage], the orange
     *  report title swaps to its translated variant for that language —
     *  the report long title's TITLE_LONG translation (when the report
     *  has a long title) else the short TITLE translation, falling back
     *  to [reportTitle] when no translation row exists. Left null on
     *  screens with no language context (title stays original). */
    reportId: String? = null,
    activeLanguage: String? = null
) {
    val navigateHome = LocalNavigateHome.current
    val logoInteractionSource = remember { MutableInteractionSource() }
    val effectiveLogoClick: () -> Unit = { navigateHome() }
    // Publish the View bottom-bar spec while mounted (always non-null so
    // the View bar — not the generic one — renders for this screen; the
    // 🔧 itself only shows when onOpenManage is non-null).
    val viewBottomBarState = LocalViewBottomBar.current
    if (viewBottomBarState != null) {
        // Republish every recomposition (onOpenManage may change). The
        // clear-on-dispose is OWNERSHIP-GUARDED: across a Navigation route
        // change (e.g. the report picker → the AI_REPORTS View), the
        // leaving screen's onDispose can run AFTER the entering screen's
        // SideEffect, so an unconditional null would wipe the new screen's
        // spec and the bar would vanish. We only null it when we still own it.
        val ownerToken = remember { Any() }
        SideEffect { viewBottomBarState.value = ViewBottomBarSpec(onManage = onOpenManage, showAll = oneOrAll, onToggleOneOrAll = onToggleOneOrAll, onViewList = onViewList, helpTopic = helpTopic, onExport = onExport, owner = ownerToken, reportView = true) }
        DisposableEffect(viewBottomBarState) {
            onDispose { if (viewBottomBarState.value?.owner === ownerToken) viewBottomBarState.value = null }
        }
    }
    // Report-icon + title tap follow the same target: Manage on the
    // hub, the View hub on every other screen (resolved via
    // LocalNavigateToCurrentReport / onTitleClick).
    val navToCurrentReport = LocalNavigateToCurrentReport.current
    val titleClick: () -> Unit = onTitleClick ?: navToCurrentReport ?: onBack
    // When a non-Original language is active, swap the orange report
    // title to its translated variant (TITLE_LONG when the report has a
    // long title, else TITLE). produceState is called unconditionally
    // and re-keys on (reportId, activeLanguage); it resolves to null —
    // leaving the original [reportTitle] — when there's no language
    // context or no translation row.
    val context = LocalContext.current
    val reportDataVersion by ReportDataVersion.versionFor(reportId).collectAsState()
    val secondaryDataVersion by SecondaryDataVersion.versionFor(reportId, SecondaryKind.TRANSLATE).collectAsState()
    val translatedTitle by produceState<String?>(null, reportId, activeLanguage, reportDataVersion, secondaryDataVersion) {
        value = if (reportId != null && !activeLanguage.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                val rep = ViewReportCache.get(context, reportId)
                val rows = SecondaryResultStorage
                    .listForReport(context, reportId, SecondaryKind.TRANSLATE)
                    .filter { it.targetLanguage == activeLanguage && !it.content.isNullOrBlank() }
                val shortT = rows.firstOrNull { it.translateSourceKind == "TITLE" }?.content
                val longT = rows.firstOrNull { it.translateSourceKind == "TITLE_LONG" }?.content
                if (!rep?.titleLong.isNullOrBlank()) (longT ?: shortT) else shortT
            }
        } else null
    }
    val effectiveReportTitle = translatedTitle ?: reportTitle
    // Full screen hides the status bar and the Home icon bar is suppressed on
    // report View screens, so this title bar is the topmost element. Push it
    // below any top camera punch-hole so the title never sits on the cutout —
    // same platform DisplayCutout inset the Home icon bar uses; 0 when there's
    // no top cutout. No effect when the status bar is visible (the Scaffold
    // already insets content below it).
    val fullScreen = com.ai.ui.shared.LocalGeneralSettings.current?.fullScreen == true
    val density = LocalDensity.current
    val cutoutTopPx = WindowInsets.displayCutout.getTop(density)
    val cutoutTopPad = if (fullScreen && cutoutTopPx > 0) with(density) { cutoutTopPx.toDp() } else 0.dp
    // The single shared chrome: report glyph left, white screen title /
    // orange report title / green subject centre, AI logo right.
    com.ai.ui.shared.AppTopBarChrome(
        modifier = Modifier.padding(top = cutoutTopPad),
        screenTitle = screenTitle,
        secondLine = effectiveReportTitle,
        thirdLine = subject,
        reportIcon = LocalReportIcon.current?.takeIf { it.isNotBlank() } ?: com.ai.ui.shared.LocalMetadataIcons.current.reportIcon,
        onReportIconClick = titleClick,
        onTitleClick = titleClick,
        // Home bar mode: the left report glyph links to the View hub, so the
        // title + right icon mirror that nav too.
        reportNavClick = titleClick,
        onSwipePrev = onSwipePrev,
        onSwipeNext = onSwipeNext
    )
}
