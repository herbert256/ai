package com.ai.ui.shared

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

/** Vertical sibling of [horizontalSwipeNavigation]: swipe **up** =
 *  previous, swipe **down** = next. Uses [detectVerticalDragGestures]
 *  (own vertical touch slop), so it coexists with a horizontal
 *  swipe modifier on the same element — whichever axis the user
 *  commits to wins. Edge toasts mirror the horizontal version
 *  ([atFirst] guards the up swipe, [atLast] the down swipe). */
fun Modifier.verticalSwipeNavigation(
    key1: Any?,
    key2: Any? = Unit,
    thresholdDp: Dp = 60.dp,
    atFirst: Boolean = false,
    atLast: Boolean = false,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
): Modifier = this.composed {
    val context = androidx.compose.ui.platform.LocalContext.current
    val thresholdPx = with(LocalDensity.current) { thresholdDp.toPx() }
    pointerInput(key1, key2, atFirst, atLast) {
        var totalDrag = 0f
        detectVerticalDragGestures(
            onDragStart = { totalDrag = 0f },
            onDragEnd = {
                when {
                    totalDrag < -thresholdPx -> {
                        if (atFirst) android.widget.Toast.makeText(context, "First page reached", android.widget.Toast.LENGTH_SHORT).show()
                        else onSwipeUp()
                    }
                    totalDrag > thresholdPx -> {
                        if (atLast) android.widget.Toast.makeText(context, "Last page reached", android.widget.Toast.LENGTH_SHORT).show()
                        else onSwipeDown()
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

/** Provided by AppNavHost so an AI-report screen's top-left 📝 icon can
 *  jump to the AI Reports hub. Used by the report-section screens (New
 *  report, All reports, Search, …) whose top-left glyph is the report
 *  default icon while no single report is in scope. The hub itself uses
 *  reportIconGoesHome instead. */
val LocalNavigateToReportsHub = compositionLocalOf<() -> Unit> { {} }

/** Provided by AppNavHost — navigate to any route by its NavRoutes
 *  constant. Backs the bottom-bar 🧹 "jump to Housekeeping" and ⚙️
 *  "jump to AI Setup / Settings" icons on dispatcher sub-screens that
 *  can't easily prop-drill a NavController-backed callback. */
val LocalNavigateToRoute = compositionLocalOf<(String) -> Unit> { {} }

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
 *  was opened ON TOP of a Manage overlay). [com.ai.ui.report.view.ViewAiReportScreen]
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

/** Opens the standalone "Report information" screen for a reportId.
 *  Provided around the AI_REPORTS composable; the Manage hub's ℹ️ icon
 *  reads it. A CompositionLocal (not a threaded arg) for the same
 *  64 KB-ceiling reason as [LocalSystemPromptChange]. Default no-op. */
val LocalNavigateToReportInfo = compositionLocalOf<(String) -> Unit> { {} }

/** Opens the standalone per-model "Report model" screen for a
 *  (reportId, agentId). Provided around the AI_REPORTS composable; the
 *  Manage hub's 'report' row tap reads it. A CompositionLocal (not a
 *  threaded arg) for the 64 KB-ceiling reason. Default no-op. */
val LocalNavigateToReportModel = compositionLocalOf<(String, String) -> Unit> { { _, _ -> } }

/** Opens the "pick a report to view" screen from the View hub's 📋.
 *  Provided around the AI_REPORTS composable. Default no-op. */
val LocalNavigateToReportPicker = compositionLocalOf<() -> Unit> { {} }

/** Opens the filtered "pick a report" screen from a Manage screen's
 *  🗂️ bottom-bar icon, carrying the source screen's [ManagePickKind]
 *  so the picker can filter the list and return to that same screen
 *  for the chosen report. Provided around the AI_REPORTS composable.
 *  Default no-op. (Typed as a String arg — the kind's route token —
 *  to avoid a navigation-package dependency from this shared file.) */
val LocalNavigateToManagePicker = compositionLocalOf<(String) -> Unit> { {} }

/** The fully-formed 🗂️ "pick another report" action for the active
 *  Manage screen — `{ navigateToManagePicker(kind) }`. Each Manage
 *  screen that wants the icon provides this (with its own kind) around
 *  its [TitleBar]; the bar auto-captures it into the bottom bar. Null
 *  (default) → no 🗂️, so every non-Manage screen is unaffected. */
val LocalManagePickReport = compositionLocalOf<(() -> Unit)?> { null }

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
 *  [com.ai.ui.report.manage.ReportsScreenNav] at the same site that supplies
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

/** Current report id used by the standard [TitleBar] swipe gesture
 *  on the **Manage** flow (the non-View counterpart of the View
 *  title-bar swipe). Provided by [com.ai.ui.report.manage.ReportScreen]
 *  with the app-state's `currentReportId` so every Manage sub-overlay
 *  (Edit prompt, Edit title, Export, HTML preview, Meta screen,
 *  Translation run, Run-confirm screens, …) inherits it for free.
 *  Null outside the report-detail tree, which gates the gesture off
 *  on Settings / Admin / Hub / Knowledge / Models / Search / History /
 *  Chat / ReportManage. Picker overlays (Language / Model /
 *  Internal-prompt / Rerank-Moderation model) explicitly override
 *  this back to `null` because the same picker composables get
 *  re-used outside the report flow (e.g. future AI Chat surfaces). */
val LocalCurrentReportIdForSwipe = compositionLocalOf<String?> { null }

/** Per-Manage-screen filter consumed by the standard [TitleBar]
 *  swipe auto-wire. Default is [com.ai.ui.helpers.ViewSwipeFilter.Any]
 *  — every report matches — which suits the bulk of Manage screens
 *  (every report has a prompt / title / parameters / export / HTML /
 *  agents). Sub-screens with a stricter "data for this screen"
 *  requirement override it locally:
 *
 *  - `ReportMetaScreen` → `HasKind(SecondaryKind.META)` (skip
 *    reports without any meta result).
 *  - `TranslationRunScreen` → handled by an explicit swipe lambda
 *    (the auto-wire is bypassed because the screen also needs to
 *    update its `runId` when crossing reports). */
val LocalManageSwipeFilter = compositionLocalOf<com.ai.ui.helpers.ViewSwipeFilter> {
    com.ai.ui.helpers.ViewSwipeFilter.Any
}

/** Optional callback fired by the standard [TitleBar] auto-wire when
 *  a swipe match is found, **before** the report switch handler. The
 *  callback receives the full [com.ai.ui.helpers.SwipeMatch] so a
 *  sub-screen can update extra per-screen state that's keyed to the
 *  destination report (e.g. `TranslationRunScreen` flips its parent's
 *  `openTranslationRunId` to the new report's first TRANSLATE row's
 *  run before the report id itself swaps in). Null by default. */
val LocalManageSwipeOnMatch =
    compositionLocalOf<((com.ai.ui.helpers.SwipeMatch) -> Unit)?> { null }

/** Optional handle to the per-report Regenerate batch engine.
 *  Provided by [com.ai.ui.report.manage.ReportsScreenNav] so deep
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
    val onExitToList: (() -> Unit)? = null,
    /** Route token (a [ManagePickKind.arg]) seeding a Manage overlay on
     *  first composition, set when a report is picked from a Manage
     *  screen's 🗂️ so the user lands back on that same overlay for the
     *  chosen report. Null = land on the Manage hub. Consumed once by
     *  [SeedInitialManageOverlay]. */
    val initialManageOverlay: String? = null
)
val LocalReportListIconBundle = compositionLocalOf { ReportListIconBundle() }

/** Master switch for the per-report icon-gen feature, mirrored from
 *  GeneralSettings.iconGenEnabled. Provided once by AppNavHost so
 *  every screen that renders a report-icon prefix (hub Existing
 *  reports, History, search hits, picker rows, the result-page icon
 *  row, the leftmost title-bar icon, the 📝 memo icon) can short-
 *  circuit when false. Default true keeps the feature live. */
val LocalIconGenEnabled = compositionLocalOf { true }

/** Grand-master metadata switch
 *  ([com.ai.viewmodel.GeneralSettings.metadataEnabled]) propagated to the
 *  composition tree so control surfaces that have no per-item sub-flag — the
 *  Fan Out **Icons** / **Titles** entry buttons — can hide themselves when the
 *  user turns all optional metadata off. Default true keeps everything live. */
val LocalMetadataEnabled = compositionLocalOf { true }

/** User-editable fallback emoji ([com.ai.data.MetadataIcons]) propagated to the
 *  composition tree so every view-screen / row fallback renders the configured
 *  glyph (edited on Settings → Default icons) rather than a hardcoded literal.
 *  Defaults to the factory set. */
val LocalMetadataIcons = compositionLocalOf { com.ai.data.MetadataIcons() }

/** Resolved per-report emoji propagated to every TitleBar inside a
 *  report-scoped composition tree. Provided by ReportsScreen at every
 *  inline overlay's CompositionLocalProvider so picker / viewer / etc.
 *  composables that don't have the [com.ai.data.Report] in scope can
 *  still render the report icon as the leftmost glyph in their
 *  TitleBar. TitleBar uses this as a fallback when no explicit
 *  `reportIcon` parameter is passed. Default null = not on a
 *  report-scoped screen. */
val LocalReportIcon = compositionLocalOf<String?> { null }

/** A section's top-left icon + tap target. When set (and no report
 *  icon is in scope) the shared top bar swaps its left AI logo for
 *  [glyph], and tapping the icon OR the screen title fires [onClick]
 *  (home from a section's main screen, or the section's main screen
 *  from a sub-screen). Provided per-section (Settings/AI Setup inside
 *  SettingsScreen; Housekeeping/Chat by route in AppNavHost). */
data class TopBarLeftIcon(val glyph: String, val onClick: () -> Unit)
val LocalTopBarLeftIcon = compositionLocalOf<TopBarLeftIcon?> { null }

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
    /** Optional 🗂️ pick-another-report hook. Wired (via
     *  [LocalManagePickReport]) by the Manage screens that support
     *  switching to a different report while staying on the same
     *  screen. Opens a filtered report picker. Null → glyph hidden. */
    val onPickReport: (() -> Unit)? = null,
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
    val isPinned: Boolean = false,
    /** Optional 🆕 add hook. CRUD list pages publish it so the bottom
     *  bar carries the "add new entry" action (replacing the old top-of-
     *  list Add button). Null → glyph hidden. */
    val onAdd: (() -> Unit)? = null,
    /** Optional ✏️ edit hook. CRUD view pages publish it so the bottom
     *  bar carries the "edit this entry" action. Null → glyph hidden. */
    val onEdit: (() -> Unit)? = null,
    /** Optional 🧹 jump-to-Housekeeping hook. Screens with a clear
     *  counterpart Housekeeping screen (e.g. AI Setup → Costs ↔
     *  Housekeeping → Costs) publish it to navigate there. Null →
     *  glyph hidden. */
    val onHousekeeping: (() -> Unit)? = null,
    /** Optional ⚙️ jump-to-AI-Setup/Settings hook — the mirror of
     *  [onHousekeeping] (e.g. Housekeeping → Test ↔ AI Setup →
     *  Test-excluded models). Null → glyph hidden. */
    val onSettings: (() -> Unit)? = null,
    /** Optional 📈 jump-to-statistics hook. Screens with a matching
     *  aggregate-stats page (API Traces → API trace statistics,
     *  Application log → App log statistics) publish it so the bottom
     *  bar carries the action. Null → glyph hidden. */
    val onStats: (() -> Unit)? = null,
    /** Optional ❓ help hook. Set by the regular [TitleBar] (every
     *  non-View screen), which moved its top-bar help glyph down here.
     *  When non-null the bottom bar uses the help layout — action
     *  strip left-aligned, ❓ pinned to the right. Null on the View
     *  screens, whose [ViewScreenTitleBar] keeps ❓ in the top bar. */
    val onHelp: (() -> Unit)? = null,
    /** When true, the 🆕 add glyph is placed first (leftmost) instead of
     *  in the trailing copy/edit/delete/new group. Set by the Manage
     *  report screen. */
    val addFirst: Boolean = false,
    /** Optional cost readout shown right-aligned in the bottom bar's top
     *  row (above the ❓). Set by the Manage report screen. Null → hidden. */
    val costText: String? = null,
    /** Tap handler for [costText] — opens the costs screen. */
    val onCostClick: (() -> Unit)? = null,
    /** Optional 🌡️ parameters hook. Screens that let you attach a
     *  Parameters preset publish it so the bottom bar carries the action
     *  (replacing the old inline "Parameters" button). Null → glyph hidden. */
    val onParameters: (() -> Unit)? = null,
    /** Optional 🎭 system-prompt hook — the paired sibling of
     *  [onParameters]. Opens the system-prompt selector. Null → glyph hidden. */
    val onSystemPrompt: (() -> Unit)? = null,
    /** Optional 🧽 clear-form hook (New AI Report). Null → glyph hidden. */
    val onClear: (() -> Unit)? = null,
    /** Optional 📎 attach hook (New AI Report). Null → glyph hidden. */
    val onAttach: (() -> Unit)? = null,
    /** Optional 🚩 validate-prompt (moderation) hook. Grayed when
     *  [validatePromptActive] is false. Null → glyph hidden. */
    val onValidatePrompt: (() -> Unit)? = null,
    val validatePromptActive: Boolean = false,
    /** The screen's title, captured so the live "<title> - icons" overlay
     *  (white ❓ on allowlisted screens) can header itself. Null → "Icons". */
    val title: String? = null
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
    /** The bar's ORANGE second line (centre, under the white title) —
     *  the former green page subject. Blank/null → no second line. */
    subject: String? = null,
    /** When set, the orange subject line is a Model-Info link (matches
     *  the old clickable [HardcodedSubjectRow]). */
    subjectProviderService: com.ai.data.AppService? = null,
    subjectModel: String? = null,
    /** Independent tap target for the orange subject line, distinct from
     *  the icon/title click. Used by Help (orange → originating screen). */
    subjectOnClick: (() -> Unit)? = null,
    /** Optional trailing chip beside the orange subject line (e.g. the
     *  Fan-out L3 role indicator). */
    subjectTrailing: @Composable RowScope.() -> Unit = {},
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
    /** When true, tapping the left report icon navigates to app Home
     *  instead of [LocalNavigateToCurrentReport]. Set only by the main
     *  "Manage report" screen — there the report icon would otherwise be
     *  inert (already on Manage), so it doubles as a Home shortcut. */
    reportIconGoesHome: Boolean = false,
    /** Optional explicit tap target for the left report icon, taking
     *  precedence over [reportIconGoesHome] / [LocalNavigateToCurrentReport].
     *  Set only by the main "Manage report" screen, where the icon opens
     *  the View hub. Null → fall back to the default resolution. */
    onReportIconClick: (() -> Unit)? = null,
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
    /** Explicit swipe-right (older report) handler. Pass `null` to
     *  let the bar auto-wire itself from [LocalCurrentReportIdForSwipe]
     *  + [LocalReportIdsNewestFirst] + [LocalReportSwitchHandler]
     *  when those are all set (the standard Manage-flow case). The
     *  callback must return `true` if a destination was found and
     *  the navigation was kicked off; `false` triggers the "No more
     *  reports" pill. */
    onSwipePrev: (() -> Boolean)? = null,
    /** Explicit swipe-left (newer report) counterpart of [onSwipePrev]. */
    onSwipeNext: (() -> Boolean)? = null,
    /** Optional 🆕 add hook (CRUD list pages). Null → glyph hidden. */
    onAdd: (() -> Unit)? = null,
    /** When true, 🆕 leads the bar instead of sitting in the trailing
     *  group. Used by the Manage report screen. */
    addFirst: Boolean = false,
    /** Optional cost readout for the bottom bar (top row, right, above ❓). */
    costText: String? = null,
    /** Tap handler for [costText] → costs screen. */
    onCostClick: (() -> Unit)? = null,
    /** Optional ✏️ edit hook (CRUD view pages). Null → glyph hidden. */
    onEdit: (() -> Unit)? = null,
    /** Optional 🌡️ parameters / 🎭 system-prompt hooks — paired config
     *  actions surfaced in the bottom bar (replacing inline buttons). */
    onParameters: (() -> Unit)? = null,
    onSystemPrompt: (() -> Unit)? = null,
    /** Optional 🧽 clear / 📎 attach / 🚩 validate-prompt hooks (New AI
     *  Report). validatePromptActive grays the 🚩 until activated. */
    onClear: (() -> Unit)? = null,
    onAttach: (() -> Unit)? = null,
    onValidatePrompt: (() -> Unit)? = null,
    validatePromptActive: Boolean = false,
    /** Optional 🧹 jump-to-Housekeeping hook. Null → glyph hidden. */
    onHousekeeping: (() -> Unit)? = null,
    /** Optional ⚙️ jump-to-AI-Setup/Settings hook. Null → glyph hidden. */
    onSettings: (() -> Unit)? = null,
    /** Optional 📈 jump-to-statistics hook. Null → glyph hidden. */
    onStats: (() -> Unit)? = null,
    /** When false, this bar renders its top chrome but does NOT publish
     *  its icons into [LocalBottomIconState]. Used by screens drawn as a
     *  visual layer ON TOP of a still-composed host (e.g. "Report - Get
     *  info" over the Manage hub) so the host's already-published bottom
     *  bar stands instead of being clobbered. */
    publishBottomBar: Boolean = true,
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
        title = title,
        onChat = onChat,
        onInfo = onInfo,
        onOpenView = onOpenView,
        onOpenManage = onOpenManage,
        // 🗂️ pick-another-report — auto-captured from the per-screen
        // CompositionLocal so Manage screens needn't thread it through
        // their TitleBar signatures. Null on every other screen.
        onPickReport = LocalManagePickReport.current,
        onCopy = onCopy,
        onShare = onShare,
        onReload = onReload,
        onDelete = onDelete,
        onTrace = onTrace,
        onTranslationCompare = onTranslationCompare,
        onMemo = null,
        onCopyReport = onCopyReport,
        onPin = onPin,
        isPinned = isPinned,
        onAdd = onAdd,
        addFirst = addFirst,
        costText = costText,
        onCostClick = onCostClick,
        onEdit = onEdit,
        onParameters = onParameters,
        onSystemPrompt = onSystemPrompt,
        onClear = onClear,
        onAttach = onAttach,
        onValidatePrompt = onValidatePrompt,
        validatePromptActive = validatePromptActive,
        onHousekeeping = onHousekeeping,
        onSettings = onSettings,
        onStats = onStats,
        // ❓ help moved out of the top bar into the bottom icons bar
        // (right-aligned, other icons left). View screens keep their
        // top-bar ❓ — see ViewScreenTitleBar.
        onHelp = helpTopic?.let { { navigateHelp(it) } }
    )
    if (state != null && publishBottomBar) {
        SideEffect { state.value = captured }
        DisposableEffect(Unit) {
            onDispose { if (state.value === captured) state.value = null }
        }
    }
    val reportIconTap = LocalNavigateToCurrentReport.current
    // ----- Title-bar swipe (Manage-flow counterpart of ViewScreenTitleBar) -----
    // Resolve the swipe handlers: explicit caller lambdas win;
    // otherwise auto-wire from the report-context CompositionLocals
    // (currentReportId + newest-first list + switch-handler + per-
    // screen filter). Behaviour matches the View flow:
    //   - Use [LocalManageSwipeFilter] (default Any) so each Manage
    //     sub-screen can require its own kind of data (e.g. the
    //     Meta hub skips reports without any META row).
    //   - Stay on the same sub-screen — only call the switch
    //     handler, do NOT pop the overlay. The sub-screen's
    //     rememberSaveable / produceState inputs re-derive from the
    //     new uiState and reload naturally.
    val swipeCtx = LocalContext.current
    val swipeIds = LocalReportIdsNewestFirst.current
    val swipeCurrentReportId = LocalCurrentReportIdForSwipe.current
    val swipeReportSwitchHandler = LocalReportSwitchHandler.current
    val swipeFilter = LocalManageSwipeFilter.current
    val swipeOnMatch = LocalManageSwipeOnMatch.current
    val swipeAutoReady = swipeCurrentReportId != null
        && swipeIds.isNotEmpty()
        && swipeReportSwitchHandler != null
    val resolvedOnSwipePrev: (() -> Boolean)? = onSwipePrev
        ?: if (swipeAutoReady) ({
            val match = com.ai.ui.helpers.findSwipeMatch(
                swipeCtx, swipeIds, swipeCurrentReportId!!,
                com.ai.ui.helpers.SwipeDirection.Prev,
                swipeFilter
            )
            if (match == null) false
            else {
                swipeOnMatch?.invoke(match)
                swipeReportSwitchHandler!!(match.reportId)
                true
            }
        }) else null
    val resolvedOnSwipeNext: (() -> Boolean)? = onSwipeNext
        ?: if (swipeAutoReady) ({
            val match = com.ai.ui.helpers.findSwipeMatch(
                swipeCtx, swipeIds, swipeCurrentReportId!!,
                com.ai.ui.helpers.SwipeDirection.Next,
                swipeFilter
            )
            if (match == null) false
            else {
                swipeOnMatch?.invoke(match)
                swipeReportSwitchHandler!!(match.reportId)
                true
            }
        }) else null
    // The dynamic report icon tap: explicit handler wins, else Home
    // (Manage-main shortcut) when flagged, else "go to current report"
    // (which on a Manage sub-overlay lands on Manage main). Title tap
    // falls back to the same current-report target.
    val reportIconClick: (() -> Unit)? = onReportIconClick
        ?: (if (reportIconGoesHome) navigateHome else reportIconTap)
    val effectiveTitleClick = onTitleClick ?: reportIconTap
    AppTopBarChrome(
        screenTitle = title,
        secondLine = subject,
        thirdLine = null,
        reportIcon = resolvedReportIcon,
        onReportIconClick = reportIconClick,
        onTitleClick = effectiveTitleClick,
        onSwipePrev = resolvedOnSwipePrev,
        onSwipeNext = resolvedOnSwipeNext,
        secondProviderService = subjectProviderService,
        secondModel = subjectModel,
        secondLineOnClick = subjectOnClick,
        secondTrailing = subjectTrailing,
        modifier = modifier
    )
}

/**
 * The single shared top-bar chrome for the whole app (View family +
 * every standard screen). Purely visual — it never touches any
 * bottom-bar state; each caller publishes its own bottom bar.
 *
 * Layout (matches the Manage TitleBar's perfected icon placement):
 *  - Left: the dynamic report glyph when [reportIcon] is non-null,
 *    else the AI logo. 66dp, offset(x=-10), top-aligned.
 *  - Centre column: white [screenTitle] (auto-shrinks 24→18sp on
 *    overflow), then orange [secondLine] (18sp), then green [thirdLine]
 *    (24sp). Tapping the column fires [onTitleClick]. The orange line
 *    can instead be a Model-Info link ([secondProviderService] +
 *    [secondModel]) and/or carry a right-edge [secondTrailing] chip.
 *  - Right: the mirrored AI logo → Home. 66dp, offset(x=+10), top.
 *  - Optional horizontal swipe (prev/next report) with a transient
 *    "No more reports" pill.
 */
@Composable
internal fun AppTopBarChrome(
    screenTitle: String?,
    secondLine: String?,
    thirdLine: String?,
    reportIcon: String?,
    onReportIconClick: (() -> Unit)?,
    onTitleClick: (() -> Unit)?,
    onSwipePrev: (() -> Boolean)?,
    onSwipeNext: (() -> Boolean)?,
    secondProviderService: com.ai.data.AppService? = null,
    secondModel: String? = null,
    /** Independent tap target for the orange 2nd line (overrides the
     *  column/title click for that line). Used by Help: orange → origin. */
    secondLineOnClick: (() -> Unit)? = null,
    secondTrailing: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier
) {
    val navigateHome = LocalNavigateHome.current
    // A section icon (Settings/AI Setup/Housekeeping/Chat) takes the
    // left slot when no report glyph is in scope; its onClick also
    // backs the screen-title tap.
    val sectionIcon = LocalTopBarLeftIcon.current
    val swipeDensity = LocalDensity.current
    val swipeThresholdPx = with(swipeDensity) { 80.dp.toPx() }
    val swipeDragX = remember { mutableFloatStateOf(0f) }
    val swipeEnabled = onSwipePrev != null || onSwipeNext != null
    val swipeStatus = remember { mutableStateOf<String?>(null) }
    val statusTick = remember { mutableIntStateOf(0) }
    LaunchedEffect(statusTick.intValue) {
        if (swipeStatus.value != null) {
            kotlinx.coroutines.delay(1000)
            swipeStatus.value = null
        }
    }
    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .then(
                    if (swipeEnabled) {
                        Modifier.pointerInput(onSwipePrev, onSwipeNext) {
                            detectHorizontalDragGestures(
                                onDragStart = { swipeDragX.floatValue = 0f },
                                onDragEnd = {
                                    val dx = swipeDragX.floatValue
                                    when {
                                        dx > swipeThresholdPx -> {
                                            val found = onSwipePrev?.invoke() ?: false
                                            if (!found) { swipeStatus.value = "No more reports"; statusTick.intValue++ }
                                        }
                                        dx < -swipeThresholdPx -> {
                                            val found = onSwipeNext?.invoke() ?: false
                                            if (!found) { swipeStatus.value = "No more reports"; statusTick.intValue++ }
                                        }
                                    }
                                    swipeDragX.floatValue = 0f
                                },
                                onDragCancel = { swipeDragX.floatValue = 0f },
                                onHorizontalDrag = { _, d -> swipeDragX.floatValue += d }
                            )
                        }
                    } else Modifier
                )
                // Break ~10dp past the screen's 16dp side padding so the
                // edge icons + the full-width orange line reach close to
                // the screen edges.
                .layout { measurable, constraints ->
                    val outsetPx = 10.dp.roundToPx()
                    val widenedMax = if (constraints.maxWidth == androidx.compose.ui.unit.Constraints.Infinity)
                        constraints.maxWidth else constraints.maxWidth + outsetPx * 2
                    val placeable = measurable.measure(
                        constraints.copy(
                            minWidth = (constraints.minWidth + outsetPx * 2).coerceAtMost(widenedMax),
                            maxWidth = widenedMax
                        )
                    )
                    layout((placeable.width - outsetPx * 2).coerceAtLeast(0), placeable.height) {
                        placeable.place(-outsetPx, 0)
                    }
                }
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val shift = 16.dp.roundToPx()
                    layout(placeable.width, (placeable.height - shift).coerceAtLeast(0)) {
                        placeable.place(0, -shift)
                    }
                }
        ) {
            val titleClick = onTitleClick ?: sectionIcon?.onClick
            var bigSizeFits by remember(screenTitle, secondLine, thirdLine) { mutableStateOf(true) }
            val hasScreenTitle = !screenTitle.isNullOrBlank()
            val topText = if (hasScreenTitle) screenTitle!! else secondLine.orEmpty()
            // Top row: left icon · white screen title · right icon.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Left — report glyph > section icon > AI logo.
                if (reportIcon != null) {
                    ReportGlyphIcon(
                        emoji = reportIcon, boxSize = 44.dp,
                        modifier = Modifier.align(Alignment.Top)
                            .then(if (onReportIconClick != null) Modifier.clickable(onClick = onReportIconClick) else Modifier)
                    )
                } else if (sectionIcon != null) {
                    ReportGlyphIcon(
                        emoji = sectionIcon.glyph, boxSize = 44.dp,
                        modifier = Modifier.align(Alignment.Top).clickable(onClick = sectionIcon.onClick)
                    )
                } else {
                    AiLogoButton(
                        onClick = onReportIconClick ?: navigateHome,
                        modifier = Modifier.align(Alignment.Top),
                        size = 44.dp
                    )
                }
                Text(
                    text = topText, color = Color.White,
                    fontSize = if (bigSizeFits) 20.4.sp else 15.3.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                    onTextLayout = { result -> if (bigSizeFits && result.hasVisualOverflow) bigSizeFits = false },
                    modifier = Modifier.weight(1f)
                        .let { base -> if (titleClick != null) base.clickable(onClick = titleClick) else base }
                )
                // Right — mirrored AI logo → Home.
                AiLogoButton(
                    onClick = navigateHome,
                    modifier = Modifier.align(Alignment.Top),
                    size = 44.dp, mirrored = true
                )
            }
            // Orange 2nd line — full screen width (not boxed by the icons).
            // Drawn a few dp higher (less gap above the white title) and
            // followed by a small spacer (more gap below, before the green
            // line / content).
            if (hasScreenTitle && !secondLine.isNullOrBlank()) {
                Row(modifier = Modifier.fillMaxWidth().offset(y = (-3).dp), verticalAlignment = Alignment.CenterVertically) {
                    val textMod = Modifier.weight(1f, fill = true)
                        .let { base ->
                            when {
                                secondProviderService != null && !secondModel.isNullOrBlank() ->
                                    base.modelInfoClickable(secondProviderService, secondModel)
                                secondLineOnClick != null -> base.clickable(onClick = secondLineOnClick)
                                else -> base
                            }
                        }
                    Text(
                        text = secondLine, color = AppColors.Orange,
                        fontSize = 15.3.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, modifier = textMod
                    )
                    secondTrailing()
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
            // Green 3rd line — full screen width.
            if (!thirdLine.isNullOrBlank()) {
                Text(
                    text = thirdLine, color = AppColors.Green,
                    fontSize = 20.4.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
            }
        }
        val status = swipeStatus.value
        if (status != null) {
            Text(
                text = status, color = Color.White, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppColors.SurfaceDark.copy(alpha = 0.95f))
                    .border(1.dp, AppColors.Blue.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
    }
}

/** Render [emoji] into a [renderPx]² bitmap and crop to its actual
 *  non-transparent pixels, so a wide-short glyph (🚗) and a tall glyph
 *  (🏆) both come back trimmed to exactly their visible content.
 *  `getTextBounds` can't do this for colour (bitmap) emoji — they all
 *  report the full em square — so we scan the rendered alpha instead. */
private fun renderTrimmedEmoji(emoji: String, renderPx: Int): android.graphics.Bitmap? {
    if (renderPx <= 0) return null
    val bmp = android.graphics.Bitmap.createBitmap(renderPx, renderPx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = renderPx * 0.82f
    }
    val fm = paint.fontMetrics
    canvas.drawText(emoji, renderPx / 2f, renderPx / 2f - (fm.ascent + fm.descent) / 2f, paint)
    val w = bmp.width; val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    var minX = w; var minY = h; var maxX = -1; var maxY = -1
    var i = 0
    for (y in 0 until h) {
        for (x in 0 until w) {
            if ((px[i] ushr 24) and 0xFF > 16) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
            i++
        }
    }
    if (maxX < minX || maxY < minY) return bmp
    return android.graphics.Bitmap.createBitmap(bmp, minX, minY, maxX - minX + 1, maxY - minY + 1)
}

/** Draw a report-icon emoji centred by its *visible* (alpha-trimmed)
 *  pixels inside a [boxSize] square (contain-fit), so glyphs with
 *  differing internal transparent padding all land with their visible
 *  centre on the box centre — matching the AI logo on the opposite edge. */
@Composable
internal fun ReportGlyphIcon(emoji: String, boxSize: Dp, modifier: Modifier = Modifier) {
    val boxPx = with(LocalDensity.current) { boxSize.roundToPx() }
    val trimmed = remember(emoji, boxPx) { renderTrimmedEmoji(emoji, boxPx * 2) }
    androidx.compose.foundation.Canvas(modifier = modifier.size(boxSize)) {
        val bmp = trimmed ?: return@Canvas
        // Cap the drawn height to the AI logo's visible-height ratio (the
        // brand glyph fills ~75% of its box), so the report icon's visible
        // height never exceeds the AI logo's. Width is free to the box.
        val maxH = size.height * 0.74f
        val scale = minOf(size.width / bmp.width, maxH / bmp.height)
        val dw = bmp.width * scale; val dh = bmp.height * scale
        val left = (size.width - dw) / 2f; val top = (size.height - dh) / 2f
        drawContext.canvas.nativeCanvas.drawBitmap(
            bmp, null,
            android.graphics.RectF(left, top, left + dw, top + dh),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        )
    }
}

@Composable
internal fun AiLogoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    /** Horizontally flip the glyph so the right-edge logo is a mirror
     *  image of the left-edge one. */
    mirrored: Boolean = false
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Image(
        painter = painterResource(R.drawable.brand_glyph),
        contentDescription = "Home",
        modifier = modifier.size(size)
            .then(if (mirrored) Modifier.graphicsLayer(scaleX = -1f) else Modifier)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
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

/** One action icon in [BottomIconBar]'s strip. */
private data class BottomBarIcon(
    val emoji: String,
    val tint: Color,
    val onClick: () -> Unit,
    val widthDp: Int,
    val fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    val alpha: Float = 1f
)

/** Ordered list of the action icons currently present (❓ help is kept
 *  separate by [BottomIconBar] so it can stay pinned bottom-right).
 *  Order is fixed; only the non-null callbacks contribute. */
private fun buildBottomBarIcons(icons: TitleBarIcons): List<BottomBarIcon> = buildList {
    // ----- first-row-ish: creation / nav / share -----
    // 🆕 leads when the screen opts in (Manage report); otherwise it
    // stays in the trailing copy/edit/delete/new group below.
    if (icons.addFirst) icons.onAdd?.let { add(BottomBarIcon("🆕", Color.Unspecified, it, 28)) }
    icons.onChat?.let { add(BottomBarIcon("💬", Color.Unspecified, it, 28)) }
    // 🗂️ pick another report (same glyph as the View hub's picker) —
    // leads the nav group on the Manage screens that support it.
    icons.onPickReport?.let { add(BottomBarIcon("🗂️", Color.Unspecified, it, 28)) }
    // 🔧 manage — rendered a touch smaller so 👁 leads on View screens.
    icons.onOpenManage?.let { add(BottomBarIcon("🔧", Color.Unspecified, it, 28, fontSize = 15.sp)) }
    // 🧹 jump to the related Housekeeping screen, ⚙️ jump to the related
    // AI Setup / Settings screen — grouped with the other nav-jumps.
    icons.onHousekeeping?.let { add(BottomBarIcon("🧹", Color.Unspecified, it, 28)) }
    icons.onSettings?.let { add(BottomBarIcon("⚙️", Color.Unspecified, it, 28)) }
    icons.onStats?.let { add(BottomBarIcon("📈", Color.Unspecified, it, 28)) }
    icons.onInfo?.let { add(BottomBarIcon("ℹ️", Color.Unspecified, it, 28)) }
    // 🌡️ parameters + 🎭 system prompt — paired config actions, kept
    // adjacent so they read as a couple wherever a screen exposes them.
    icons.onParameters?.let { add(BottomBarIcon("🌡️", Color.Unspecified, it, 28)) }
    icons.onSystemPrompt?.let { add(BottomBarIcon("🎭", Color.Unspecified, it, 28)) }
    icons.onClear?.let { add(BottomBarIcon("🧽", Color.Unspecified, it, 28)) }
    icons.onAttach?.let { add(BottomBarIcon("📎", Color.Unspecified, it, 28)) }
    // 🚩 validate prompt — grayed until the user activates it (picks a
    // moderation model), mirroring the 📌 pin alpha treatment.
    icons.onValidatePrompt?.let { add(BottomBarIcon("🚩", Color.Unspecified, it, 28, alpha = if (icons.validatePromptActive) 1f else 0.35f)) }
    icons.onCopy?.let { add(BottomBarIcon("📋", Color.Unspecified, it, 28)) }
    icons.onPin?.let { add(BottomBarIcon("📌", Color.Unspecified, it, 28, alpha = if (icons.isPinned) 1f else 0.35f)) }
    icons.onShare?.let { add(BottomBarIcon("📤", Color.Unspecified, it, 28)) }
    icons.onCopyReport?.let { add(BottomBarIcon("👯", Color.Unspecified, it, 28)) }
    // ----- second-row-ish: 👁 view leads the second row, the per-item
    // actions follow, and 🔄 regenerate sits just before 🗑 delete. -----
    icons.onOpenView?.let { add(BottomBarIcon("👁", Color.Unspecified, it, 32, fontSize = 18.sp)) }
    icons.onTranslationCompare?.let { add(BottomBarIcon("🌐", Color.Unspecified, it, 28)) }
    icons.onMemo?.let { add(BottomBarIcon("📝", Color.Unspecified, it, 28)) }
    icons.onEdit?.let { add(BottomBarIcon("✏️", Color.Unspecified, it, 28)) }
    icons.onReload?.let { add(BottomBarIcon("🔄", AppColors.Orange, it, 28)) }
    icons.onDelete?.let { add(BottomBarIcon("🗑", AppColors.Red, it, 22)) }
    if (!icons.addFirst) icons.onAdd?.let { add(BottomBarIcon("🆕", Color.Unspecified, it, 28)) }
    // 🐞 trace always sits last in the strip.
    icons.onTrace?.let { add(BottomBarIcon("🐞", Color.Unspecified, it, 22)) }
}

/** Renders one row of bottom-bar action icons via [TitleBarIcon]. When
 *  [cellWidthDp] is set, every icon uses that fixed width so columns
 *  line up vertically across the two-row layout. */
@Composable
private fun BottomBarIconRow(specs: List<BottomBarIcon>, scale: Float, gap: Dp, cellWidthDp: Int? = null, cellHeightDp: Int = 32) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap)
    ) {
        specs.forEach {
            TitleBarIcon(it.emoji, it.tint, it.onClick, width = (cellWidthDp ?: it.widthDp).dp, heightDp = cellHeightDp, scale = scale, alpha = it.alpha, fontSize = it.fontSize)
        }
    }
}

@Composable
private fun TitleBarIcon(
    emoji: String,
    tint: Color,
    onClick: () -> Unit,
    width: Dp = 28.dp,
    /** Box height before scaling. The two-row bottom bar passes a
     *  smaller value to tighten the vertical gap between the rows. */
    heightDp: Int = 32,
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
        modifier = Modifier.size(width = width * scale, height = heightDp.dp * scale)
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
 *  action icons. The per-screen actions (chat / info / copy / share /
 *  reload / delete / trace / memo) sit here; the ❓ help glyph (when
 *  published via [TitleBarIcons.onHelp]) is pinned to the right. There
 *  is no visible back affordance — navigation back is the Android
 *  system back gesture, routed through each screen's [BackHandler].
 *  Icons render at a 1.25× scale by default, narrowing adaptively when
 *  the strip would otherwise overflow on a narrow screen. */
/** Screens whose white ❓ opens the live "<title> - icons" overlay instead
 *  of navigating to the help page. Piloted on Manage report; add a
 *  helpTopic here to roll the behaviour out to that screen. */
private val LEGEND_OVERLAY_TOPICS = setOf("report_run")

@Composable
fun BottomIconBar(icons: TitleBarIcons?, modifier: Modifier = Modifier) {
    // Non-null on the non-View screens (regular TitleBar) — flips the
    // bar into the help layout: strip left-aligned, ❓ pinned right.
    val onHelp = icons?.onHelp
    val costText = icons?.costText
    val onCostClick = icons?.onCostClick
    // Font size shrinks as the amount grows more digits so a 3-digit
    // value still fits and doesn't touch the icon to its left.
    val costBaseSp = costText?.let {
        when (it.substringBefore('.').trimStart('-').length) {
            0, 1 -> 12f    // < 10
            2 -> 10f       // < 100
            3 -> 8.5f      // < 1000
            else -> 7f
        }
    } ?: 0f
    val specs = if (icons != null) buildBottomBarIcons(icons) else emptyList()
    val navigateHelp = LocalNavigateToHelp.current
    // On allowlisted screens the white ❓ opens a live icon-legend overlay
    // (this screen's visible bar icons) instead of the help page. The
    // overlay's own red ❓ then opens the full icon-table help page.
    val useLegend = (icons?.helpTopic in LEGEND_OVERLAY_TOPICS) && specs.isNotEmpty()
    var showLegend by remember { mutableStateOf(false) }
    val extraGap = 2
    fun intrinsicOf(list: List<BottomBarIcon>): Float {
        if (list.isEmpty()) return 1f
        return (list.sumOf { it.widthDp } + (list.size - 1) * extraGap).toFloat()
    }
    // Uniform icon size across 1-row and 2-row bars: the two-row layout
    // naturally lands around 2.1×, so capping both branches here makes a
    // sparse single-row bar render its icons at that same size instead of
    // blowing them up. Floor stays 1.0× so a crowded row still shrinks to
    // fit rather than overflowing.
    val ceiling = 2.1f

    androidx.compose.foundation.layout.BoxWithConstraints(
        // Bottom padding lifts the bar a touch above the gesture pill.
        modifier = modifier.fillMaxWidth().padding(start = 0.dp, end = 2.dp, bottom = 18.dp)
    ) {
        val available = maxWidth.value

        if (onHelp == null) {
            // View-style centered single row (effectively unused — the
            // Report View family has its own ViewBottomBar). Unchanged.
            val scale = (available / intrinsicOf(specs)).coerceIn(1.0f, 1.875f)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.weight(1f))
                BottomBarIconRow(specs, scale, extraGap.dp)
                Spacer(modifier = Modifier.weight(1f))
            }
            return@BoxWithConstraints
        }

        // Help layout (every non-View screen). Icons fill up to 7 per
        // row, then wrap to a new LEFT-aligned row; the ❓ help glyph is
        // pinned to the right of the LAST row and never counts toward the
        // 7-per-row cap. The cost readout (when present) sits at the right
        // of the FIRST row. A uniform per-icon cell width keeps columns
        // aligned vertically across rows.
        val helpW = 32f
        val helpGap = 4f
        // Second help glyph ❔ — a per-screen "what do these icons do?" page.
        // Shown just left of ❓ when this screen has its own "<topic>_icons"
        // help page AND more than 3 action icons (❓/❔ not counted).
        // (Suppressed on useLegend screens — the overlay's red ❓ replaces it.)
        val iconTopic = icons?.helpTopic?.let { "${it}_icons" }
            ?.takeIf { com.ai.ui.admin.HELP_TOPICS.containsKey(it) }
        val showIconHelp = iconTopic != null && specs.size > 3 && !useLegend
        val cell = 24                       // uniform column width (dp) — tight spacing
        // Fill rows of up to 7, but put the SMALLEST (remainder) row on
        // TOP so the full rows sit at the bottom. ❓ pins to the right of
        // the last (bottom) row; the cost readout to the first (top) row.
        val per = 7
        val rem = specs.size % per
        val rows = when {
            specs.isEmpty() -> listOf(emptyList())
            rem == 0 -> specs.chunked(per)
            else -> listOf(specs.take(rem)) + specs.drop(rem).chunked(per)
        }
        // Rough width (unscaled) the cost readout needs, reserved on the
        // first row so the scale shrinks enough to keep it on-screen.
        val costReserve = if (costText != null) costText.length * costBaseSp * 0.62f + 16f else 0f
        fun rowWidth(count: Int, withHelp: Boolean, withCost: Boolean) =
            (count * cell + (count - 1).coerceAtLeast(0) * extraGap).toFloat() +
                (if (withHelp) helpGap + helpW * (if (showIconHelp) 2 else 1) else 0f) +
                (if (withCost) costReserve else 0f)
        val widest = rows.mapIndexed { i, r ->
            rowWidth(r.size, i == rows.lastIndex, i == 0)
        }.maxOrNull() ?: helpW
        val scale = (available / widest).coerceIn(1.0f, ceiling)
        // Tighter per-row cell height when wrapped so the rows sit close
        // together vertically; full height for a single row.
        val rowCellH = if (rows.size > 1) 22 else 32
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            rows.forEachIndexed { i, rowSpecs ->
                val isFirst = i == 0
                val isLast = i == rows.lastIndex
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BottomBarIconRow(rowSpecs, scale, extraGap.dp, cellWidthDp = cell, cellHeightDp = rowCellH)
                    Spacer(modifier = Modifier.weight(1f))
                    if (isFirst && costText != null) {
                        Text(
                            text = costText,
                            color = AppColors.Blue,
                            fontSize = (costBaseSp * scale).sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp,
                            maxLines = 1, softWrap = false,
                            // Shift left so the right edge lines up with the
                            // per-row cost column (rows inset ~16dp; the bar
                            // only 8dp).
                            modifier = Modifier
                                .let { m -> if (onCostClick != null) m.clickable(onClick = onCostClick) else m }
                                .padding(end = 13.dp)
                        )
                    }
                    if (isLast) {
                        if (showIconHelp && iconTopic != null) {
                            TitleBarIcon("❔", AppColors.Blue, { navigateHelp(iconTopic) }, width = 18.dp, heightDp = rowCellH, scale = scale)
                        }
                        TitleBarIcon("❓", AppColors.Blue, if (useLegend) ({ showLegend = true }) else onHelp, width = 18.dp, heightDp = rowCellH, scale = scale)
                    }
                }
            }
        }
    }

    // Live icon-legend overlay — white ❓ on allowlisted screens opens this
    // instead of navigating to the help page.
    if (showLegend && icons != null) {
        IconLegendOverlay(
            icons = icons,
            specs = specs,
            navigateHelp = navigateHelp,
            onClose = { showLegend = false }
        )
    }
}

/** Full-screen "<title> - icons" overlay opened by the white ❓ on
 *  allowlisted screens ([LEGEND_OVERLAY_TOPICS]). Lists the bar icons
 *  currently visible — big glyph + purpose (the name from
 *  [com.ai.ui.admin.SCREEN_ICON_HELP]) — and re-fires each icon's action on
 *  tap. Its own single icon is a red ❓ that opens the screen's full
 *  icon-table help page (all possible icons + descriptions). */
@Composable
private fun IconLegendOverlay(
    icons: TitleBarIcons,
    specs: List<BottomBarIcon>,
    navigateHelp: (String?) -> Unit,
    onClose: () -> Unit
) {
    val purposes = icons.helpTopic
        ?.let { com.ai.ui.admin.SCREEN_ICON_HELP[it] }
        ?.associate { it.first to it.second }
        ?: emptyMap()
    val header = icons.title?.takeIf { it.isNotBlank() }?.let { "$it - icons" } ?: "Icons"
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)) {
                Text(
                    header, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                ) {
                    specs.forEach { spec ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onClose(); spec.onClick() }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    spec.emoji, fontSize = 30.sp,
                                    color = if (spec.tint == Color.Unspecified) Color.White else spec.tint
                                )
                            }
                            Text(
                                purposes[spec.emoji] ?: spec.emoji,
                                color = Color.White, fontSize = 16.sp,
                                modifier = Modifier.padding(start = 12.dp).weight(1f),
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                        }
                        HorizontalDivider(color = AppColors.DividerDark)
                    }
                }
                // The overlay's own icons bar: just a red ❓ → the full icon table.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 18.dp, end = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TitleBarIcon("❓", AppColors.Red, {
                        onClose()
                        icons.helpTopic?.let { base ->
                            val iconsTopic = "${base}_icons"
                            navigateHelp(if (com.ai.ui.admin.HELP_TOPICS.containsKey(iconsTopic)) iconsTopic else base)
                        }
                    }, width = 22.dp, heightDp = 32)
                }
            }
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
    /** Accepted for call-site compatibility but no longer rendered:
     *  AI-report lists now show only 👁 view + 🔧 manage. Deleting a
     *  report happens from Report - manage. */
    @Suppress("UNUSED_PARAMETER") onDelete: ((String) -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") reportId: String? = null
) {
    // 👁 leads the strip and renders a couple of points larger than
    // 🔧 — view is the dominant action on every report list, so the
    // eye sits first and a little bigger to pull focus. No 🗑 here.
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
            .padding(start = 6.dp, end = 0.dp, top = 4.dp, bottom = 4.dp)
    )
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
    // Always show a leading logo: the generated per-report icon when
    // metadata is on and present, otherwise the configurable default
    // report logo (so rows aren't blank when metadata is off).
    val defaultLogo = LocalMetadataIcons.current.reportIcon
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onOpenManage(report.id) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = (if (iconGenEnabled) report.icon?.takeIf { it.isNotBlank() } else null) ?: defaultLogo,
            fontSize = 22.sp
        )
        Spacer(modifier = Modifier.width(8.dp))
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
