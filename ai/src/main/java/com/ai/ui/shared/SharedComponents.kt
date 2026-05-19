package com.ai.ui.shared

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.R

/** Horizontal swipe-to-navigate, the pattern first introduced on the
 *  Model response screen: swipe **left** for the next item, **right**
 *  for the previous one. Uses [detectHorizontalDragGestures], which
 *  only consumes events after horizontal touch slop — so a vertical
 *  scroll inside the gestured region keeps working untouched.
 *
 *  [key1] / [key2] feed [pointerInput]'s keys so the lambdas always
 *  close over fresh state when the current item or its surrounding
 *  list changes. When [atFirst] / [atLast] is true the matching
 *  edge swipe shows a "First page reached" / "Last page reached"
 *  toast instead of calling the lambda — same shape as the
 *  Import-result toasts so the user gets feedback that the gesture
 *  registered. */
fun Modifier.horizontalSwipeNavigation(
    key1: Any?,
    key2: Any? = Unit,
    thresholdDp: Dp = 60.dp,
    atFirst: Boolean = false,
    atLast: Boolean = false,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
): Modifier = this.composed {
    val context = androidx.compose.ui.platform.LocalContext.current
    val thresholdPx = with(LocalDensity.current) { thresholdDp.toPx() }
    pointerInput(key1, key2, atFirst, atLast) {
        var totalDrag = 0f
        detectHorizontalDragGestures(
            onDragStart = { totalDrag = 0f },
            onDragEnd = {
                when {
                    totalDrag > thresholdPx -> {
                        if (atFirst) android.widget.Toast.makeText(context, "First page reached", android.widget.Toast.LENGTH_SHORT).show()
                        else onSwipeRight()
                    }
                    totalDrag < -thresholdPx -> {
                        if (atLast) android.widget.Toast.makeText(context, "Last page reached", android.widget.Toast.LENGTH_SHORT).show()
                        else onSwipeLeft()
                    }
                }
            },
            onDragCancel = { totalDrag = 0f }
        ) { _, dragAmount -> totalDrag += dragAmount }
    }
}

/** Returns a state that increments every time the host's
 *  [androidx.lifecycle.Lifecycle] reaches [Lifecycle.State.RESUMED].
 *  Drop into a screen and key your `produceState` / disk read on it
 *  so the data refreshes when the user navigates back to a hub
 *  whose composable was preserved across the trip. */
@Composable
fun resumeRefreshTick(): Int {
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var tick by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return tick
}

/** Spinning ⏳ glyph. Used wherever a screen wants to convey "this
 *  thing is in flight" without the visual weight of a full
 *  CircularProgressIndicator — secondary row status columns, the
 *  hub "X reports running" pill, the icon-gen row on the cost table,
 *  the Refresh-all step list, the per-row indicator on the
 *  Translation run detail screen, etc. Single canonical animation
 *  spec (1500 ms / linear / infinite) so the cadence stays uniform
 *  across the app. [modifier] composes BEFORE the rotation so
 *  caller padding etc. is unaffected by the spin. */
@Composable
fun AnimatedHourglass(
    fontSize: TextUnit = 12.sp,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "hourglass")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1500, easing = LinearEasing)),
        label = "hourglass-rotation"
    )
    Text(text = "⏳", fontSize = fontSize, modifier = modifier.rotate(angle))
}

/** Card that starts collapsed — the title row is always visible and
 *  acts as a click target; tapping reveals [content]. */
@Composable
fun CollapsibleCard(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                Text(if (expanded) "▾" else "▸", color = AppColors.TextTertiary)
            }
            if (expanded) content()
        }
    }
}

/** Variant of [CollapsibleCard] where the open/closed state lives in
 *  the caller — enables accordion behaviour: parent decides which card
 *  is open and only one can be open at a time. Same visual shape as
 *  the unmodified [CollapsibleCard]. */
@Composable
fun ControlledCollapsibleCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                Text(if (expanded) "▾" else "▸", color = AppColors.TextTertiary)
            }
            if (expanded) content()
        }
    }
}

/** App-wide layout choice for combined provider+model labels.
 *  Provided once at the top of the composition tree by AppNavHost
 *  from GeneralSettings; consumed by [modelLabel] and any caller
 *  that wants to honour the user's preference. */
val LocalModelNameLayout = compositionLocalOf {
    com.ai.viewmodel.ModelNameLayout.MODEL_ONLY
}

/** Provided by AppNavHost so any model-name Text in the tree can
 *  jump to the Model Info screen without prop-drilling a callback.
 *  Default no-op covers previews and unit tests. */
val LocalNavigateToModelInfo = compositionLocalOf<(com.ai.data.AppService, String) -> Unit> {
    { _, _ -> }
}

/** View-flavoured sibling of [LocalNavigateToModelInfo] — routes to
 *  the read-only Model Info "view" screen instead of the management
 *  one. Wired by the View family's model-name labels via
 *  [modelInfoViewClickable]. Default no-op so non-View screens
 *  that don't override silently fall through. */
val LocalNavigateToModelInfoView = compositionLocalOf<(com.ai.data.AppService, String) -> Unit> {
    { _, _ -> }
}

/** View-flavoured nav to a read-only Agent screen. Wired from the
 *  Workers card on the View Model Info screen + any other place
 *  a View context shows an Agent label. */
val LocalNavigateToAgentView = compositionLocalOf<(String) -> Unit> { {} }

/** View-flavoured nav to a read-only Flock screen. Same shape as
 *  [LocalNavigateToAgentView]. */
val LocalNavigateToFlockView = compositionLocalOf<(String) -> Unit> { {} }

/** View-flavoured nav to a read-only Swarm screen. Same shape as
 *  [LocalNavigateToAgentView]. */
val LocalNavigateToSwarmView = compositionLocalOf<(String) -> Unit> { {} }

/** Provided by AppNavHost so the title-bar Help icon can navigate
 *  to a help page without prop-drilling a callback. The argument is
 *  the screen-specific topic ID (e.g., "agents", "report_result");
 *  null / blank routes to the compact General Help overview. */
val LocalNavigateToHelp = compositionLocalOf<(String?) -> Unit> { {} }

/** Provided by AppNavHost so the title-bar Home icon can navigate
 *  to the Hub without prop-drilling a callback. Replaces the role
 *  the removed "AI" text-button used to play. */
val LocalNavigateHome = compositionLocalOf<() -> Unit> { {} }

/** Ids of reports whose translation runs are currently in-flight
 *  (not yet finished and not cancelled). Provided at the AI_REPORTS
 *  composable root so descendants — currently the Main View screen's
 *  bottom-anchored "still running" notice — can match the same
 *  in-flight criterion the AI Reports hub uses without re-collecting
 *  the StateFlow themselves. Default empty (no-op). */
val LocalActiveTranslationReportIds = compositionLocalOf<Set<String>> { emptySet() }

/** Targets the View screens' new bottom-bar 🔧 manage icon can jump
 *  to. [Main] lands on the Report - Manage tile screen; the other
 *  variants land on a specific Manage sub-overlay (Meta-result
 *  detail, Translation-run detail, the per-agent ReportsViewer
 *  scrolled to a section). Sealed so a future Manage target is a
 *  one-line addition here + one branch in the dispatcher. */
sealed class ManageJump {
    object Main : ManageJump()
    data class MetaResult(val id: String) : ManageJump()
    data class TranslationRun(val id: String) : ManageJump()
    data class ReportsViewer(
        val initialAgentId: String?,
        val section: String?
    ) : ManageJump()
}

/** Dispatcher installed by `ReportScreen` around the View overlay
 *  block — bridges a sub-View screen's 🔧 tap to the corresponding
 *  Manage state-var flip (showViewReportScreen, openMetaResultId,
 *  openTranslationRunId, showViewer + viewerSection +
 *  selectedAgentForViewer). Null on screens outside a report
 *  context (standalone View routes get their `onOpenManage` wired
 *  directly from AppNavHost instead). */
val LocalOpenManage = compositionLocalOf<((ManageJump) -> Unit)?> { null }

/** Mirror of [ManageJump] — targets the Manage screens' 👁 button
 *  can jump to. Each variant mounts the matching View sub-screen
 *  on top of the currently-open Manage overlay; back returns to
 *  the Manage overlay because its flag was left set (see
 *  feedback_overlay_back_stack.md). */
sealed class ViewJump {
    object Main : ViewJump()
    /** PromptViewScreen — there's only one prompt per report. */
    object Prompt : ViewJump()
    /** CostsViewScreen — there's only one costs roll-up per report. */
    object Costs : ViewJump()
    data class Rerank(val id: String) : ViewJump()
    data class Moderation(val id: String) : ViewJump()
    data class Meta(val id: String) : ViewJump()
    data class FanIn(val id: String) : ViewJump()
    data class FanInModel(val id: String) : ViewJump()
    data class FanOut(val metaPromptName: String) : ViewJump()
    data class TranslationRun(val runId: String) : ViewJump()
    data class Reports(val agentId: String?) : ViewJump()
}

/** Mutable holder for the active Manage → View jump request.
 *  Installed by `ReportsScreenNav` once per AI_REPORTS mount so
 *  the state lives OUTSIDE `ReportsScreen` (which sits at the JVM
 *  64 KB per-method bytecode ceiling). The new top-of-chain block
 *  in `ReportPrimaryOverlays` reads `.value` to decide whether to
 *  render a View sub-screen on top of whatever Manage overlay is
 *  active; the Manage screens write `.value = ViewJump.X(...)`
 *  from their bottom-bar 👁 button (via [TitleBar.onOpenView]).
 *  Null on screens outside a report context (the Settings edit
 *  routes hit `navController.navigate(...)` directly). */
val LocalPendingViewOverManage = compositionLocalOf<MutableState<ViewJump?>?> { null }

/** Counter bumped by `ReportPrimaryOverlays`' layered-View "go to
 *  main View" path (the Report-title tap on a View sub-screen that
 *  was opened ON TOP of a Manage overlay). [com.ai.ui.report.ViewAiReportScreen]
 *  reads this and keys its inner overlay state on it, so a bump
 *  resets every sub-View overlay (rerank / moderation / fan-in / …)
 *  back to the tile grid in the same composition pass — no
 *  flicker, no leftover sub-View from before the round-trip
 *  through Manage. Null on screens outside the AI_REPORTS route. */
val LocalMainViewResetTick = compositionLocalOf<MutableState<Int>?> { null }

/** Set on every screen that's "deeper" than the AI Report Result
 *  page (overlay screens inside the result page — Edit Prompt /
 *  Title / Models / Parameters / Export / Translation Compare /
 *  Secondary Results / Translation Run / Call / Language picker /
 *  Scope picker / Meta picker / etc., plus the per-report Trace
 *  list / detail routes). Defaults to null; the title-bar 📝
 *  Memo icon renders only when this is non-null. The callback
 *  takes the user back to the active report's result page. */
val LocalNavigateToCurrentReport = compositionLocalOf<(() -> Unit)?> { null }

/** Per-report system-prompt setter, provided around the AI_REPORTS
 *  composable in AppNavHost so descendants (Report - manage's Edit
 *  Row 2) can fire the per-report system-prompt picker without
 *  needing the callback threaded through 30+ function arguments —
 *  ReportsScreen's signature sits at the JVM 64 KB per-method
 *  bytecode ceiling, so a CompositionLocal is the cheapest way to
 *  surface the function to nested screens. Default no-op. */
val LocalSystemPromptChange = compositionLocalOf<(String?) -> Unit> { {} }

/** Prev / next callbacks for the chronologically surrounding reports
 *  on disk. Provided by [ReportsScreenNav] (it builds the lambdas
 *  alongside the same callbacks ReportsScreen uses for its < / >
 *  chevrons) so descendants — currently the View tile grid's
 *  horizontal-swipe handler — can fire them without threading two
 *  more args through ReportsScreen → ReportPrimaryOverlays →
 *  ViewAiReportScreen. Null when there's no surrounding context
 *  (default), or when the caller doesn't supply the lambdas. */
data class ReportNeighborNav(
    /** Chronologically previous = older report. No-op when there
     *  isn't one. */
    val onPrev: () -> Unit,
    /** Chronologically next = newer report. No-op when there
     *  isn't one. */
    val onNext: () -> Unit,
    /** True when an older neighbour exists. The swipe handler on
     *  the main View grid reads this to decide whether to flash
     *  "Loading report" or "No more reports". */
    val hasPrev: Boolean = false,
    /** True when a newer neighbour exists. */
    val hasNext: Boolean = false
)

val LocalReportNeighborNav = compositionLocalOf<ReportNeighborNav?> { null }

/** Newest-first list of report ids on disk. Provided by
 *  [com.ai.ui.report.ReportsScreenNav] at the same site that supplies
 *  [LocalReportNeighborNav]. Used by [ViewScreenTitleBar]'s swipe
 *  gesture: each sub-View screen walks this list outward from the
 *  current report to find the nearest one that matches its filter
 *  (e.g. "has a rerank entry", "has a meta result for prompt X").
 *  Empty when the navigation context isn't a report screen. */
val LocalReportIdsNewestFirst = compositionLocalOf<List<String>> { emptyList() }

/** Swap the app-wide "current report" after a sub-View swipe. The
 *  provider wraps [com.ai.viewmodel.ReportViewModel.restoreCompletedReport]
 *  so the rest of the app (Manage screens, hub badges, neighbour
 *  navigation) stays in sync once the user backs out of the sub-View.
 *  Null when no provider has wired it. */
val LocalReportSwitchHandler = compositionLocalOf<((String) -> Unit)?> { null }

/** Optional handle to the per-report Regenerate batch engine.
 *  Provided by [com.ai.ui.report.ReportsScreenNav] so deep
 *  descendants (the Manage screen's Regenerate row + the
 *  full-screen detail) can read state without threading it
 *  through every intermediate composable's parameter list —
 *  same trick used for [LocalReportNeighborNav]. Null when the
 *  current navigation context isn't a report screen. */
val LocalRegenerateBatchEngine = compositionLocalOf<com.ai.viewmodel.RegenerateBatchEngine?> { null }

/** Shared state slot for the "Regenerate batch detail screen is
 *  open for reportId X" overlay. Provided by ReportsScreenNav so
 *  both the Regenerate row's click handler (sets it to a reportId)
 *  AND the overlay-mount site (reads it + clears it on back)
 *  share the same value. */
val LocalRegenerateBatchOpenState =
    compositionLocalOf<androidx.compose.runtime.MutableState<String?>?> { null }

/** Per-row 🔧 / 👁 callbacks surfaced to nested report-list
 *  pickers (the +Report previous-report picker on the report
 *  screen) and the first-composition seed for the View tile-grid
 *  overlay (`initialView` flag from the AI_REPORTS route's
 *  query-param). Bundled into a single CompositionLocal so
 *  [ReportsScreen]'s parameter list stays under the JVM 64 KB
 *  per-method bytecode limit. Provided by ReportsScreenNav at the
 *  call into ReportsScreen, defaulted to no-op + Manage entry so
 *  call sites that don't wire the navigation behave the same as
 *  before. */
data class ReportListIconBundle(
    val onOpenManage: (String) -> Unit = {},
    val onOpenView: (String) -> Unit = {},
    /** Per-row 🗑 delete target. Default no-op keeps the icon hidden
     *  in [ReportListRow] (the row only renders 🗑 when this is
     *  non-default-equivalent, i.e. callers pass a real lambda).
     *  Wired from AppNavHost so the delete runs on Dispatchers.IO
     *  + bumps a refresh tick to re-list. */
    val onDelete: (String) -> Unit = {},
    /** When true, the report screen flips its [showViewReportScreen]
     *  saveable flag on first composition so the user lands on the
     *  View tile grid instead of Manage. */
    val initialView: Boolean = false,
    /** When non-null AND [initialView] is true, the View tile grid's
     *  Reports sub-overlay is also seeded — opens directly to that
     *  agent's per-model Reports page. Used by Model Info View's
     *  Last-Usage rows to jump straight from a row tap to the
     *  matching agent's response without the user manually clicking
     *  the Reports tile and swiping. */
    val initialReportsAgentId: String? = null,
    /** Route-pop callback used by the View overlay's onBack when the
     *  user arrived directly on View via the per-row 👁 icon
     *  ([initialView] == true). Without this, back from the View
     *  overlay would fall through to the underlying Manage screen
     *  instead of returning to the list the user tapped from. */
    val onExitToList: (() -> Unit)? = null
)
val LocalReportListIconBundle = compositionLocalOf { ReportListIconBundle() }

/** Master switch for the per-report icon-gen feature, mirrored from
 *  GeneralSettings.iconGenEnabled. Provided once by AppNavHost so
 *  every screen that renders a report-icon prefix (hub Existing
 *  reports, History, search hits, picker rows, the result-page icon
 *  row, the leftmost title-bar icon, the 📝 memo icon) can short-
 *  circuit when false. Default true keeps the feature live. */
val LocalIconGenEnabled = compositionLocalOf { true }

/** Mirrors GeneralSettings.showBackArrow into the composition tree.
 *  Read only by [BottomIconBar] to decide whether to paint the ←
 *  back arrow and whether to right-align or centre the action icons.
 *  Default false matches the data-class default. */
val LocalShowBackArrow = compositionLocalOf { false }

/** Resolved per-report emoji propagated to every TitleBar inside a
 *  report-scoped composition tree. Provided by ReportsScreen at every
 *  inline overlay's CompositionLocalProvider so picker / viewer / etc.
 *  composables that don't have the [com.ai.data.Report] in scope can
 *  still render the report icon as the leftmost glyph in their
 *  TitleBar. TitleBar uses this as a fallback when no explicit
 *  `reportIcon` parameter is passed. Default null = not on a
 *  report-scoped screen. */
val LocalReportIcon = compositionLocalOf<String?> { null }

/** Title of the active AI Report. Provided by ReportsScreen at every
 *  inline overlay's CompositionLocalProvider, alongside [LocalReportIcon].
 *  TitleBar reads this as a subject fallback in BOTH mode when the
 *  caller passes a hardcoded title but no per-screen subject — the
 *  bar then renders the report's title on the left and the screen's
 *  fixed title on the right, so the user always knows which report
 *  they're inside even on deep sub-screens whose subject would
 *  otherwise be blank. Null = not on a report-scoped screen. */
val LocalReportTitle = compositionLocalOf<String?> { null }

/** Snapshot of the icons the *currently composed* TitleBar would paint
 *  on its right. TitleBar fills this via SideEffect on every
 *  recomposition when bottom-bar mode is on; clears it via
 *  DisposableEffect on screen exit. The single [BottomIconBar] at
 *  AppNavHost scope reads from this so the same per-screen visibility
 *  rules carry through. Null when bottom-bar mode is off — TitleBar
 *  short-circuits the publish path. */
val LocalBottomIconState = compositionLocalOf<MutableState<TitleBarIcons?>?> { null }

/** Copy [text] to the system clipboard with [label] as the ClipData
 *  label and surface a "Copied" Toast. Empty text is a no-op so the
 *  caller doesn't have to pre-check. Used by every TitleBar `onCopy`
 *  callback so the wiring at each screen stays one line. */
fun copyToClipboard(context: android.content.Context, text: String, label: String = "AI app") {
    if (text.isEmpty()) return
    val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as android.content.ClipboardManager
    clip.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
}

/** Fire the standard Android Share sheet (ACTION_SEND) with [text] as
 *  the plain-text payload. Optional [subject] becomes EXTRA_SUBJECT
 *  (used by Email / Drive / a few other targets). Empty text no-ops
 *  so callers don't need to pre-check. Distinct from the file-
 *  attachment shareExport / shareReportAsHtml helpers — this one
 *  ships text only via EXTRA_TEXT. */
fun shareText(context: android.content.Context, text: String, subject: String? = null) {
    if (text.isBlank()) return
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
        if (subject != null) putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Share"))
}

/** Captured icon state from a TitleBar — what BottomIconBar needs to
 *  render the same strip the top bar would have rendered. */
data class TitleBarIcons(
    val helpTopic: String?,
    val onBack: (() -> Unit)?,
    val onChat: (() -> Unit)?,
    val onInfo: (() -> Unit)?,
    /** Optional 👁 view-report hook. Distinct from [onInfo] (ℹ️
     *  Model Info) — this one opens the View tile grid for the
     *  active report. Wired from Report - manage so the bottom-bar
     *  carries the same glyph as the per-row eye icon on every
     *  reports list. Null → glyph hidden. */
    val onOpenView: (() -> Unit)? = null,
    /** Optional 🔧 open-manage hook. Renders the same wrench glyph
     *  every reports-list row uses for "open Report - manage". Used
     *  by every View screen so the bottom-bar carries the per-row
     *  manage icon, with each sub-View jumping to the matching
     *  Manage sub-overlay (via [LocalOpenManage]). Null → glyph
     *  hidden. */
    val onOpenManage: (() -> Unit)? = null,
    val onCopy: (() -> Unit)?,
    val onShare: (() -> Unit)?,
    val onReload: (() -> Unit)?,
    val onDelete: (() -> Unit)?,
    val onTrace: (() -> Unit)?,
    /** Open the source / translation split-view compare overlay.
     *  Non-null only when the active screen is rendering a
     *  translation of some content — e.g. a per-language TRANSLATE
     *  overlay on top of a META detail, a back-translation row, or
     *  an agent body shown in a non-source language. */
    val onTranslationCompare: (() -> Unit)?,
    /** Captured from [LocalNavigateToCurrentReport] at TitleBar render
     *  time. Cannot be re-read from the local inside BottomIconBar
     *  itself — the bar lives at AppNavHost scope where the per-screen
     *  CompositionLocalProvider override isn't visible. */
    val onMemo: (() -> Unit)?,
    /** Optional 👯 duplicate-report hook. Distinct from [onCopy] which
     *  is clipboard-copy: this one calls `ReportViewModel.copyReport`
     *  to make a `(Copy)`-suffixed duplicate of the underlying report.
     *  Wired from Report - manage. Null → icon hidden. */
    val onCopyReport: (() -> Unit)? = null,
    /** Optional 📌 pin / unpin hook. Toggles `Report.pinned` so the
     *  report surfaces in the Hub's Pinned section. Wired from
     *  Report - manage. Null → icon hidden. */
    val onPin: (() -> Unit)? = null,
    /** Current pinned state, read by the bottom bar to colour the 📌
     *  glyph (orange when pinned, white when not). Ignored when
     *  [onPin] is null. */
    val isPinned: Boolean = false
)

/** Make a model-name Text clickable so tapping it opens the Model
 *  Info screen for [providerService] / [model]. No-op when the
 *  provider can't be resolved or the model is blank. Stack on top
 *  of any existing modifier; existing parent clickables on the
 *  same Row continue to work because Compose merges pointer-input
 *  layers per-element, not by inheritance. */
@Composable
fun Modifier.modelInfoClickable(
    providerService: com.ai.data.AppService?,
    model: String
): Modifier {
    if (providerService == null || model.isBlank()) return this
    val nav = LocalNavigateToModelInfo.current
    return this.clickable { nav(providerService, model) }
}

/** View-flavoured sibling of [modelInfoClickable] — routes to the
 *  read-only Model Info "view" screen via [LocalNavigateToModelInfoView].
 *  Used by every View Report screen so a model-name tap opens the
 *  fancy view-style sibling instead of the management screen. */
@Composable
fun Modifier.modelInfoViewClickable(
    providerService: com.ai.data.AppService?,
    model: String
): Modifier {
    if (providerService == null || model.isBlank()) return this
    val nav = LocalNavigateToModelInfoView.current
    return this.clickable { nav(providerService, model) }
}

/** Format a "provider · model" label according to the current
 *  [LocalModelNameLayout]. The default is MODEL_ONLY (just the model
 *  id); PROVIDER_AND_MODEL prepends the provider's display name with
 *  the chosen separator. The separator defaults to " · " — the most
 *  common one across the app — but call sites can override it (e.g.,
 *  to keep " — " or " / " when the layout shows both). */
@Composable
fun modelLabel(
    providerDisplay: String,
    model: String,
    separator: String = " · "
): String {
    val layout = LocalModelNameLayout.current
    val short = shortModelName(model)
    return when (layout) {
        com.ai.viewmodel.ModelNameLayout.MODEL_ONLY -> short
        com.ai.viewmodel.ModelNameLayout.PROVIDER_AND_MODEL -> "$providerDisplay$separator$short"
    }
}

/** Strip namespace / route / hf-org prefixes from a model id so the
 *  user only sees the leaf — e.g. `anthropic/claude-sonnet-4-5` →
 *  `claude-sonnet-4-5`, `meta-llama/Llama-3-8B-Instruct` →
 *  `Llama-3-8B-Instruct`. The Model Info screen deliberately uses
 *  the raw string so the user can still see the canonical id; every
 *  other display site should call this. */
fun shortModelName(model: String): String =
    if (model.contains('/')) model.substringAfterLast('/') else model

/** Translate a SecondaryResult's stored `errorMessage` into something
 *  the user wants to read. Today the only rewrite is the legacy
 *  "Interrupted by app restart" marker (stamped by the resume-on-open
 *  sweep before commit d2cbf97c renamed it to "No data yet") → the
 *  user-friendly equivalent. Anything else passes through unchanged.
 *  Apply at render time so persisted rows from before the rename
 *  don't keep showing the system-y wording. */
fun friendlyErrorMessage(raw: String?): String = when (raw) {
    null -> ""
    "Interrupted by app restart" -> "No data yet"
    else -> raw
}

/**
 * Tiny "vision-capable" badge for model lists. Renders nothing when the
 * model isn't flagged so the row stays compact for the long tail of
 * text-only models.
 */
@Composable
fun VisionBadge(isVisionCapable: Boolean) {
    if (!isVisionCapable) return
    Text(
        text = "👁",
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp)
    )
}

/**
 * Tiny "web-search-tool-capable" badge for model lists — same shape as
 * VisionBadge, parallel data source (Settings.isWebSearchCapable).
 */
@Composable
fun WebSearchBadge(isWebSearchCapable: Boolean) {
    if (!isWebSearchCapable) return
    Text(
        text = "🌐",
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp)
    )
}

/**
 * Tiny "thinking / reasoning_effort capable" badge for model lists —
 * same shape as VisionBadge / WebSearchBadge, parallel data source
 * (Settings.isReasoningCapable). 🧠 matches the per-report
 * reasoning-effort indicator already in use elsewhere.
 */
@Composable
fun ReasoningBadge(isReasoningCapable: Boolean) {
    if (!isReasoningCapable) return
    Text(
        text = "🧠",
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp)
    )
}

/**
 * Tiny "on a >1h-429 cooldown" badge for model lists. ⏳ matches the
 * Model cooldowns card / SetupScreen icon. Same shape as VisionBadge.
 */
@Composable
fun CooldownBadge(onCooldown: Boolean) {
    if (!onCooldown) return
    Text(
        text = "⏳",
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp)
    )
}

/**
 * Tiny "user-blocked" badge for model lists. 🚫 matches the Blocked
 * models card icon. Same shape as VisionBadge.
 */
@Composable
fun BlockedBadge(isBlocked: Boolean) {
    if (!isBlocked) return
    Text(
        text = "🚫",
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp)
    )
}

/**
 * Tiny "tier-gated / inaccessible" badge for model lists. 🔒 matches
 * the Inaccessible models card icon. Selecting one will usually fail
 * unless the user has dedicated capacity on that provider.
 */
@Composable
fun InaccessibleBadge(isInaccessible: Boolean) {
    if (!isInaccessible) return
    Text(
        text = "🔒",
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp)
    )
}

/**
 * Tiny "not a chat model" badge for the main model pickers. 🖼️ stands
 * in for the family — image / TTS / STT / moderation / classify / OCR.
 * Selecting one of these for a chat flow will fail at runtime; the
 * badge + dimmed row warn the user before they tap.
 */
@Composable
fun NonTestableBadge(isNonTestable: Boolean) {
    if (!isNonTestable) return
    Text(
        text = "🖼️",
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp)
    )
}

/**
 * Generic title bar — three-section anatomy. Left: AI logo (→ Hub)
 * + ❓ Help (→ screen's help topic). Centre (only when a per-report
 * emoji is in scope): the dynamic report icon, centred between the
 * left button group and the right title. Right: the hardcoded screen
 * title, top-aligned so the label sits high on the row.
 *
 * The visible back arrow lives on [BottomIconBar], not here. This
 * function still registers a [BackHandler] when [onBackClick] is
 * non-null so the system / gesture back routes through whatever the
 * screen passed — overlay close, drill-in pop, edit-cancel, etc.
 *
 * Every action callback (chat / info / copy / share / reload / delete
 * / trace) is captured into [LocalBottomIconState]; the global
 * [BottomIconBar] paints them at the screen bottom.
 */
@Composable
fun TitleBar(
    title: String? = null,
    /** Legacy slot — every caller draws its own green subject row via
     *  [HardcodedSubjectRow] now, so this parameter is accepted-but-
     *  unused while the call-site sweep finishes. New callers should
     *  not pass it. */
    @Suppress("UNUSED_PARAMETER") subject: String? = null,
    onBackClick: (() -> Unit)? = null,
    centered: Boolean = false,
    helpTopic: String? = null,
    onTrace: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onInfo: (() -> Unit)? = null,
    /** Optional 👁 view-report hook. Opens the per-report View tile
     *  grid. Wired from Report - manage; replaces the old ℹ️ slot
     *  there so the bottom bar carries the same glyph as the per-row
     *  eye icon on every reports list. Null → glyph hidden. */
    onOpenView: (() -> Unit)? = null,
    /** Optional 🔧 open-manage hook. Wired by every View screen so
     *  the bottom-bar carries the same wrench glyph the per-row 🔧
     *  uses on every reports list. Each sub-View typically passes a
     *  closure that fires [LocalOpenManage] with the matching
     *  [ManageJump] target. Null → glyph hidden. */
    onOpenManage: (() -> Unit)? = null,
    onReload: (() -> Unit)? = null,
    onChat: (() -> Unit)? = null,
    /** Optional 📋 copy-to-clipboard hook. Wire it from screens that
     *  display substantial copyable text (agent response, raw JSON,
     *  prompt body, translated text, redacted trace bytes, …). Null →
     *  icon hidden. Use the top-level [copyToClipboard] helper inside
     *  the lambda for the standard "set ClipData + Toast" behaviour. */
    onCopy: (() -> Unit)? = null,
    /** Optional 📤 share-as hook. Most screens use the top-level
     *  [shareText] helper to fire the Android share sheet with the
     *  same body the copy icon uses. The main AI Report screen reuses
     *  this slot for the export-format-picker flow — the previous
     *  in-action-row Export button is gone. Null → icon hidden. */
    onShare: (() -> Unit)? = null,
    /** Optional ↔ split-view compare hook. Non-null only when the
     *  active screen is rendering a translation of some content;
     *  the lambda typically pushes a [TranslationCompareScreen]
     *  with the source and translated text. Null → icon hidden. */
    onTranslationCompare: (() -> Unit)? = null,
    /** Resolved per-report emoji (e.g. 📊). When non-null the icon
     *  renders centred between the left button group and the right
     *  title on every report-scoped screen. Tap navigates back to the
     *  main report (via [LocalNavigateToCurrentReport]) when one is
     *  provided; otherwise the icon is decorative. Callsites should
     *  pass `report.icon ?: "📝"` so the slot is filled while
     *  icon-gen is in flight or after it errored. */
    reportIcon: String? = null,
    /** Optional tap target on the title text itself. Used by paired
     *  sub-screens (e.g. Report - manage ↔ Report - view) to let the
     *  title double as a navigation toggle between them. Null →
     *  title is non-interactive. */
    onTitleClick: (() -> Unit)? = null,
    /** Optional 👯 duplicate-report hook. Different from [onCopy]
     *  (which is clipboard copy) — this one duplicates the underlying
     *  report. Used by Report - manage. Null → icon hidden. */
    onCopyReport: (() -> Unit)? = null,
    /** Optional 📌 pin / unpin hook. Toggles `Report.pinned`. Used by
     *  Report - manage. Null → icon hidden. */
    onPin: (() -> Unit)? = null,
    /** Current pinned state, drives the 📌 glyph colour in the bottom
     *  bar (orange when pinned). Ignored when [onPin] is null. */
    isPinned: Boolean = false,
    /** Applied to the bar's outer Row. */
    modifier: Modifier = Modifier
) {
    if (onBackClick != null) {
        androidx.activity.compose.BackHandler { onBackClick() }
    }
    val navigateHome = LocalNavigateHome.current
    val navigateHelp = LocalNavigateToHelp.current
    val resolvedReportIcon = reportIcon ?: LocalReportIcon.current
    if (centered) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "AI", fontSize = 36.sp, color = Color.White, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { navigateHome() }
            )
        }
        return
    }
    // Publish action callbacks into LocalBottomIconState; the global
    // BottomIconBar at AppNavHost scope paints the strip. SideEffect
    // re-publishes on every recomposition; DisposableEffect clears on
    // screen exit with an identity check so a racing nav doesn't
    // clobber the next screen's just-published state.
    val state = LocalBottomIconState.current
    val captured = TitleBarIcons(
        helpTopic = helpTopic,
        onBack = onBackClick,
        onChat = onChat,
        onInfo = onInfo,
        onOpenView = onOpenView,
        onOpenManage = onOpenManage,
        onCopy = onCopy,
        onShare = onShare,
        onReload = onReload,
        onDelete = onDelete,
        onTrace = onTrace,
        onTranslationCompare = onTranslationCompare,
        onMemo = null,
        onCopyReport = onCopyReport,
        onPin = onPin,
        isPinned = isPinned
    )
    if (state != null) {
        SideEffect { state.value = captured }
        DisposableEffect(Unit) {
            onDispose { if (state.value === captured) state.value = null }
        }
    }
    val titleStyle = MaterialTheme.typography.titleLarge
    val barFontSize = titleStyle.fontSize * 1.35f
    val reportIconTap = LocalNavigateToCurrentReport.current
    // Pull the whole bar up 10dp AND shrink its measured height by
    // the same amount so the next composable starts where the bar
    // visually ends. Plain Modifier.offset only shifts paint — it
    // leaves the row's measured height alone, which surfaced as 10dp
    // of empty space under every title that had no HardcodedSubjectRow
    // filling it.
    Row(
        modifier = modifier.fillMaxWidth().layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val shift = 16.dp.roundToPx()
            layout(placeable.width, (placeable.height - shift).coerceAtLeast(0)) {
                placeable.place(0, -shift)
            }
        },
        verticalAlignment = Alignment.CenterVertically
    ) {
        AiLogoButton(
            onClick = navigateHome,
            modifier = Modifier.offset(x = (-14).dp, y = (-7).dp)
        )
        // Spacer 1 — pushes the report icon to the midpoint
        // between the AI logo and the right-side title group.
        Spacer(modifier = Modifier.weight(1f))
        if (resolvedReportIcon != null) {
            Box(modifier = Modifier.offset(y = (-10).dp)) {
                TitleBarIcon(
                    resolvedReportIcon, Color.Unspecified,
                    onClick = reportIconTap ?: {},
                    width = 32.dp, scale = 2.0f
                )
            }
        }
        // Spacer 2 — equal weight to spacer 1 so the report icon
        // sits centred between the AI logo (left) and the title
        // text (right). User-requested layout: AI ─── 📝 ─── Title ❓.
        Spacer(modifier = Modifier.weight(1f))
        if (title != null) {
            // Long titles shrink rather than truncate: start at the
            // normal bar size, drop ~5 % per layout pass whenever the
            // measured text overflows the available width, with a
            // floor at half the base size so the label never becomes
            // illegible. Resets when the title text changes.
            val minFontSize = (barFontSize.value * 0.55f).sp
            var titleFontSize by remember(title) { mutableStateOf(barFontSize) }
            // Title text falls back to the report-icon tap when the
            // caller doesn't pass an explicit click handler, so on
            // every Manage sub-overlay both icon and title close the
            // overlay → land on main Manage. Outside a report
            // context [LocalNavigateToCurrentReport] is null so the
            // title stays non-interactive (matches today).
            val effectiveTitleClick = onTitleClick ?: reportIconTap
            val titleMod = Modifier.align(Alignment.Top).padding(top = 4.dp)
                .let { base -> if (effectiveTitleClick != null) base.clickable(onClick = effectiveTitleClick) else base }
            Text(
                text = title, style = titleStyle, color = Color.White,
                fontSize = titleFontSize, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                maxLines = 1, softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
                onTextLayout = { result ->
                    if (result.didOverflowWidth && titleFontSize.value > minFontSize.value) {
                        titleFontSize = (titleFontSize.value * 0.95f).coerceAtLeast(minFontSize.value).sp
                    }
                },
                modifier = titleMod
            )
        }
        // ❓ help button moved to the rightmost slot of the bar
        // (was second-from-left, after the AI logo). Same callback
        // and topic — only the position changes.
        Spacer(modifier = Modifier.width(8.dp))
        HelpButton(
            onClick = { navigateHelp(helpTopic) },
            modifier = Modifier.offset(y = (-10).dp)
        )
    }
}

@Composable
private fun AiLogoButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Image(
        painter = painterResource(R.drawable.brand_glyph),
        contentDescription = "Home",
        modifier = modifier.size(52.dp).clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
    )
}

@Composable
private fun HelpButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = "❓",
        fontSize = 28.sp,
        color = AppColors.Blue,
        modifier = modifier.clickable(onClick = onClick)
    )
}

/**
 * Green subject sub-header rendered just below [TitleBar]. The only
 * shared definition of the green page-subject line — every screen
 * paints it via this helper so font / colour / y-position stay
 * identical app-wide.
 *
 * Self-gates on blank text so callers can drop it in unconditionally.
 * [providerService] + [model] make the subject clickable → Model Info
 * when both are non-null. [horizontalPadding] pads start/end for
 * screens whose outer Column doesn't already inset by 16 dp.
 * [trailing] slot at the right edge — used by Fan out L3 to surface
 * the role indicator beside the answerer label.
 */
@Composable
fun HardcodedSubjectRow(
    text: String?,
    providerService: com.ai.data.AppService? = null,
    model: String? = null,
    horizontalPadding: Dp = 0.dp,
    maxLines: Int = 1,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    if (text.isNullOrBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth()
            .offset(y = (-8).dp)
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val clickableTextMod = if (providerService != null && !model.isNullOrBlank()) {
            Modifier.weight(1f, fill = true).modelInfoClickable(providerService, model)
        } else {
            Modifier.weight(1f, fill = true)
        }
        Text(
            text = text,
            fontSize = 32.sp, color = AppColors.Green,
            fontWeight = FontWeight.SemiBold,
            maxLines = maxLines,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = clickableTextMod
        )
        trailing()
    }
}

/** Action strip rendered on the right of [BottomIconBar]. Home / Help
 *  live in the top bar, so they're absent here. Every slot is
 *  conditional — null means the icon is omitted entirely so the strip
 *  stays tight. */
@Composable
private fun TitleBarActionStrip(
    onReload: (() -> Unit)?,
    onChat: (() -> Unit)?,
    onInfo: (() -> Unit)?,
    onOpenView: (() -> Unit)?,
    onOpenManage: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onTranslationCompare: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onTrace: (() -> Unit)?,
    onMemo: (() -> Unit)?,
    onCopyReport: (() -> Unit)? = null,
    onPin: (() -> Unit)? = null,
    isPinned: Boolean = false,
    scale: Float = 1f,
    /** Extra horizontal gap inserted between every adjacent icon —
     *  on top of the per-pair Spacers already coded into the strip. */
    extraSpacing: Dp = 0.dp
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(extraSpacing)
    ) {
        // 👁 view lives at the leftmost slot of the strip on every
        // Manage screen — the user's "View icon must be the
        // leftmost icon" rule. Larger glyph (18 sp vs the standard
        // 16 sp) mirrors the slightly-bigger eye on every per-row
        // report list.
        if (onOpenView != null) TitleBarIcon("👁", Color.Unspecified, onOpenView, width = 32.dp, scale = scale, fontSize = 18.sp)
        if (onChat != null) TitleBarIcon("💬", Color.Unspecified, onChat, width = 28.dp, scale = scale)
        // 🔧 manage — same glyph the per-row 🔧 uses on every reports
        // list. Wired by every View screen so the bottom-bar lets
        // the user jump back to Report - manage (or a specific
        // Manage sub-overlay) without re-opening a list. Sized a
        // touch smaller than the 👁 view icon (15 sp vs 18 sp)
        // per the user — the wrench is secondary to "view the
        // content again" on every View screen, so the eye should
        // visually lead.
        if (onOpenManage != null) TitleBarIcon("🔧", Color.Unspecified, onOpenManage, width = 28.dp, scale = scale, fontSize = 15.sp)
        if (onInfo != null) TitleBarIcon("ℹ️", Color.Unspecified, onInfo, width = 28.dp, scale = scale)
        if (onCopy != null) TitleBarIcon("📋", Color.Unspecified, onCopy, width = 28.dp, scale = scale)
        if (onPin != null) TitleBarIcon("📌", Color.Unspecified, onPin, width = 28.dp, scale = scale, alpha = if (isPinned) 1f else 0.35f)
        if (onShare != null) TitleBarIcon("📤", Color.Unspecified, onShare, width = 28.dp, scale = scale)
        if (onReload != null) TitleBarIcon("🔄", AppColors.Orange, onReload, width = 28.dp, scale = scale)
        // 🌐 globe: the screen is rendering a translation. Tapping
        // opens the side-by-side original ↔ translated compare view.
        // The callback is only ever non-null on translated screens,
        // so the icon's presence already implies translation content.
        if (onTranslationCompare != null) TitleBarIcon("🌐", Color.Unspecified, onTranslationCompare, width = 28.dp, scale = scale)
        // 👯 Copy-report sits immediately before 🗑 Delete — both are
        // "operates on the report itself" actions, and grouping them
        // keeps the destructive icon flanked by its sibling action
        // rather than mixed in with the per-content viewers.
        if (onCopyReport != null) TitleBarIcon("👯", Color.Unspecified, onCopyReport, width = 28.dp, scale = scale)
        if (onDelete != null) TitleBarIcon("🗑", AppColors.Red, onDelete, width = 22.dp, scale = scale)
        if (onDelete != null && onTrace != null) {
            Spacer(modifier = Modifier.width(2.dp * scale))
        }
        if (onTrace != null) TitleBarIcon("🐞", Color.Unspecified, onTrace, width = 22.dp, scale = scale)
        if (onDelete != null && onTrace == null) {
            Spacer(modifier = Modifier.width(2.dp * scale))
        }
        if (onMemo != null) TitleBarIcon("📝", Color.Unspecified, onMemo, width = 28.dp, scale = scale)
    }
}

@Composable
private fun TitleBarIcon(
    emoji: String,
    tint: Color,
    onClick: () -> Unit,
    width: Dp = 28.dp,
    scale: Float = 1f,
    /** Render alpha for the glyph. Defaults to fully opaque. Used by
     *  the bottom-bar 📌 pin icon to fade itself when the report isn't
     *  pinned — emoji glyphs ignore [tint] on Android (they're bitmap-
     *  rendered) so alpha is the reliable way to show an "off" state. */
    alpha: Float = 1f,
    /** Glyph point size before scaling. Defaults to the strip's
     *  standard 16 sp; callers that need a slightly larger icon
     *  (👁 view) can bump it. */
    fontSize: androidx.compose.ui.unit.TextUnit = 16.sp
) {
    Box(
        modifier = Modifier.size(width = width * scale, height = 32.dp * scale)
            .clickable(onClick = onClick)
            .alpha(alpha),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji, fontSize = fontSize * scale,
            color = if (tint == Color.Unspecified) Color.White else tint
        )
    }
}

/** Fixed-position bottom bar that mirrors the active TitleBar's
 *  action icons + back arrow. Home + Help live in the top bar; this
 *  strip carries the per-screen actions only (chat / info / copy /
 *  share / reload / delete / trace / memo + the ← back arrow on the
 *  left). Icons render at a 1.25× scale by default, narrowing
 *  adaptively when the strip would otherwise overflow on a narrow
 *  screen. */
@Composable
fun BottomIconBar(icons: TitleBarIcons?, modifier: Modifier = Modifier) {
    val onBack = icons?.onBack
    val onChat = icons?.onChat
    val onInfo = icons?.onInfo
    val onOpenView = icons?.onOpenView
    val onOpenManage = icons?.onOpenManage
    val onCopy = icons?.onCopy
    val onShare = icons?.onShare
    val onReload = icons?.onReload
    val onDelete = icons?.onDelete
    val onTrace = icons?.onTrace
    val onTranslationCompare = icons?.onTranslationCompare
    val onMemo = icons?.onMemo
    val onCopyReport = icons?.onCopyReport
    val onPin = icons?.onPin
    val isPinned = icons?.isPinned == true
    val extraGap = 2
    var stripBase = 0
    var slotCount = 0
    fun slot(w: Int) { stripBase += w; slotCount++ }
    if (onChat != null) slot(28)
    // 👁 renders a touch wider so the slightly larger glyph (matches
    // the per-row eye on every reports list) doesn't collide with
    // its neighbours.
    if (onOpenView != null) slot(32)
    // 🔧 manage uses the standard 28 dp slot — the glyph itself
    // is rendered a couple of points smaller than 👁 so the eye
    // visually leads on every View screen.
    if (onOpenManage != null) slot(28)
    if (onInfo != null) slot(28)
    if (onCopy != null) slot(28)
    if (onPin != null) slot(28)
    if (onShare != null) slot(28)
    if (onReload != null) slot(28)
    if (onTranslationCompare != null) slot(28)
    if (onCopyReport != null) slot(28)
    if (onDelete != null) slot(22)
    if (onDelete != null && onTrace != null) stripBase += 2
    if (onTrace != null) slot(22)
    if (onDelete != null && onTrace == null) stripBase += 2
    if (onMemo != null) slot(28)
    val stripIntrinsic = (stripBase + (slotCount - 1).coerceAtLeast(0) * extraGap).coerceAtLeast(1)
    androidx.compose.foundation.layout.BoxWithConstraints(
        // Bottom padding lifts the bar a touch above the gesture
        // pill — without it the strip sat dead flush against the
        // physical edge once the system-bars inset was dropped.
        modifier = modifier.fillMaxWidth().padding(start = 0.dp, end = 8.dp, bottom = 12.dp)
    ) {
        // Settings → UI tweaks → "Show back arrow". When off the
        // arrow is hidden AND the action strip is horizontally
        // centred instead of right-aligned (Android system back
        // gesture / button still works as usual).
        val showBack = LocalShowBackArrow.current && onBack != null
        val backW = if (showBack) 45 else 0
        val backGap = if (showBack) 4 else 0
        val available = maxWidth.value
        val desired = (available - backW - backGap) / stripIntrinsic
        // Floor at 1.0× so the strip never shrinks below the previous
        // top-bar size; ceiling at 1.875× = 1.5× × 1.25× preserves the
        // original headroom multiplier on roomy screens.
        val scale = desired.coerceIn(1.0f, 1.875f)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBack) {
                // Big arrow needs a Box at least the glyph's intrinsic
                // height; smaller Boxes clipped the upper / lower
                // stroke of "←" and made it look like the arrow had
                // disappeared entirely on some screens.
                Box(
                    modifier = Modifier.size(width = 50.dp, height = 56.dp).clickable(onClick = onBack!!),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "←", color = Color.White, fontSize = 48.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
            // Right-align (back-arrow ON) by pushing the strip to
            // the end with one stretched Spacer; centre (back-arrow
            // OFF) by sandwiching the strip between two equal-weight
            // Spacers.
            Spacer(modifier = Modifier.weight(1f))
            TitleBarActionStrip(
                onReload = onReload,
                onChat = onChat,
                onInfo = onInfo,
                onOpenView = onOpenView,
                onOpenManage = onOpenManage,
                onCopy = onCopy,
                onShare = onShare,
                onTranslationCompare = onTranslationCompare,
                onDelete = onDelete,
                onTrace = onTrace,
                onMemo = onMemo,
                onCopyReport = onCopyReport,
                onPin = onPin,
                isPinned = isPinned,
                scale = scale,
                extraSpacing = extraGap.dp
            )
            if (!showBack) Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/**
 * Reusable delete confirmation dialog.
 */
@Composable
fun DeleteConfirmationDialog(
    entityType: String,
    entityName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $entityType") },
        text = { Text("Are you sure you want to delete \"$entityName\"?") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", maxLines = 1, softWrap = false) }
        }
    )
}

/**
 * Reusable confirmation dialog for the title-bar Reload icon. The
 * confirm button re-fires whatever the screen calls "reload"; the
 * dismiss leaves things alone.
 *
 * Single-call screens can pass [target] for the default copy
 * ("Re-run API call? This will fire a new API call for X and replace
 * the current result."). Multi-call / list screens that want to spell
 * out exactly which rows get re-fired can override [title] and
 * [message] directly — pass [target] = "" in that case.
 */
@Composable
fun ReloadConfirmationDialog(
    target: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    title: String = "Re-run API call?",
    message: String = "This will fire a new API call for $target and replace the current result.",
    confirmLabel: String = "Re-run"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = AppColors.Blue, maxLines = 1, softWrap = false) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", maxLines = 1, softWrap = false) }
        }
    )
}

/**
 * Reusable list item card with title, subtitle, and delete button.
 */
@Composable
fun SettingsListItemCard(
    title: String,
    subtitle: String,
    extraLine: String? = null,
    subtitleColor: Color = AppColors.TextTertiary,
    onClick: () -> Unit,
    /** Null hides the trailing X — used by fixed-list screens (e.g.
     *  Other Internal prompts) where rows are editable but not deletable. */
    onDelete: (() -> Unit)?,
    /** Optional widget rendered just before the trailing delete X —
     *  e.g. a 🐞 trace link on a model-cooldown row. */
    trailing: (@Composable () -> Unit)? = null
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color.White)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = subtitle, fontSize = 14.sp, color = subtitleColor)
                if (extraLine != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = extraLine, fontSize = 12.sp, color = AppColors.TextDim)
                }
            }
            trailing?.invoke()
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Text("X", color = AppColors.Red, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Reusable collapsible card with a clickable title header.
 *
 * [helpTopic] — when non-null, an ❓ icon sits between the summary
 * and the chevron. Tapping it routes through [LocalNavigateToHelp]
 * to the full-screen help page for that topic. Tap stays inside
 * the icon — does NOT propagate to the row's expand/collapse
 * handler.
 */
@Composable
fun CollapsibleCard(
    title: String,
    defaultExpanded: Boolean = false,
    summary: String? = null,
    helpTopic: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    val navigateHelp = LocalNavigateToHelp.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f)
                )
                if (summary != null && !expanded) {
                    Text(
                        text = summary, style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextTertiary, modifier = Modifier.padding(end = 8.dp)
                    )
                }
                if (helpTopic != null) {
                    Text(
                        text = "❓", fontSize = 14.sp, color = AppColors.Blue,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clickable { navigateHelp(helpTopic) }
                    )
                }
                Text(text = if (expanded) "▾" else "▸", color = AppColors.TextTertiary, fontSize = 16.sp)
            }
            if (expanded) { content() }
        }
    }
}

/**
 * Hub navigation card - clickable card with title and description.
 */
@Composable
fun HubCard(
    title: String,
    description: String,
    onClick: () -> Unit,
    containerColor: Color = AppColors.CardBackground
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color.White)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = description, fontSize = 14.sp, color = AppColors.TextTertiary)
        }
    }
}

/** Two right-aligned per-row icons that every list of AI reports
 *  carries: 🔧 opens the report at the Manage screen (the historical
 *  default), 👁 opens it at the View tile grid. Each icon is its own
 *  clickable Text — tapping an icon does NOT bubble up to the row's
 *  outer clickable (Compose stops the outer click when an inner
 *  clickable fires), so callers can keep their existing row-tap
 *  behaviour as the primary action. Sized at 18 sp with 6 dp padding
 *  between the two and 6 dp from the row's trailing edge so the pair
 *  reads as a paired action group without dominating the row.
 *
 *  Used by every report-list renderer the app exposes: History, the
 *  home-screen "Running reports" / "Reports with problems" cards,
 *  the four search screens (Quick / Extended local / Semantic /
 *  Local semantic), the Trace Detail "Open report" button, and the
 *  +Report previous-report picker. */
@Composable
fun ReportRowActionIcons(
    onOpenManage: () -> Unit,
    onOpenView: () -> Unit,
    /** Optional 🗑 row-delete callback. When both [onDelete] and
     *  [reportId] are non-null the trailing 🗑 icon renders; tap
     *  fires the lambda with [reportId]. Existing call sites that
     *  omit these get the same two-icon behaviour as before. */
    onDelete: ((String) -> Unit)? = null,
    reportId: String? = null
) {
    // 👁 leads the strip and renders a couple of points larger than
    // the other action icons — view is the dominant action on every
    // report list, so the eye sits first and a little bigger to
    // pull focus.
    Text(
        "👁", fontSize = 22.sp,
        modifier = Modifier
            .clickable { onOpenView() }
            .padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
    )
    Text(
        "🔧", fontSize = 18.sp,
        modifier = Modifier
            .clickable { onOpenManage() }
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
    if (onDelete != null && reportId != null) {
        Text(
            "🗑", fontSize = 18.sp, color = AppColors.Red,
            modifier = Modifier
                .clickable { onDelete(reportId) }
                .padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
        )
    }
}

/** One full report-list row. Renders the per-report icon (when
 *  icon-gen is on), the report title (single-line, ellipsised),
 *  and the trailing 🔧 / 👁 / 🗑 action triplet. The outer row is
 *  clickable → [onOpenManage], matching the historical "tap a row
 *  to manage" default. 🗑 owns its own confirm dialog that calls
 *  [onDelete] only after the user confirms — the dialog state is
 *  internal to the row.
 *
 *  Mirrors the visual shape of `HistoryReportRow` and the home
 *  Running / Problems list rows so every report list across the
 *  app reads the same. */
@Composable
fun ReportListRow(
    report: com.ai.data.Report,
    onOpenManage: (String) -> Unit,
    onOpenView: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var showDelete by remember(report.id) { mutableStateOf(false) }
    if (showDelete) {
        DeleteConfirmationDialog(
            entityType = "Report",
            entityName = report.title.ifBlank { "Untitled" },
            onConfirm = { showDelete = false; onDelete(report.id) },
            onDismiss = { showDelete = false }
        )
    }
    val iconGenEnabled = LocalIconGenEnabled.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onOpenManage(report.id) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconGenEnabled) {
            Text(
                text = report.icon?.takeIf { it.isNotBlank() } ?: "📝",
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = report.title.ifBlank { "Untitled" },
            fontSize = 14.sp, color = Color.White,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        ReportRowActionIcons(
            onOpenManage = { onOpenManage(report.id) },
            onOpenView = { onOpenView(report.id) },
            onDelete = { showDelete = true },
            reportId = report.id
        )
    }
}
