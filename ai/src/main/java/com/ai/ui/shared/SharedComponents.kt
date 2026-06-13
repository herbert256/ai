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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
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
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
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

/** Blocking, non-dismissable "Preparing N / M…" popup shown while a big
 *  batch (Translations / Fan Out / Tournament / Judges / Compare) builds
 *  its placeholder rows, before it dispatches any API call. Navigation is
 *  blocked until the build completes. [onCancel], when non-null, renders a
 *  Cancel button that aborts the build + cleans up the partial rows.
 *  Mirrors the "Deleting…" dialogs (FanL1 / TranslationL1) plus the export
 *  progress bar (ReportExportScreen). */
@Composable
fun BuildStageOverlay(
    progress: com.ai.viewmodel.BuildProgress,
    onCancel: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Preparing…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedHourglass(fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(progress.label.ifBlank { "Filling the queue" }, fontSize = 13.sp)
                }
                if (progress.total > 0) {
                    // Determinate once the engine has counted the items.
                    LinearProgressIndicator(
                        progress = { progress.built.toFloat() / progress.total },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Preparing ${progress.built} / ${progress.total}…",
                        fontSize = 12.sp,
                        color = AppColors.TextTertiary
                    )
                } else {
                    // Brief pre-count window: indeterminate spinner.
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { },
        dismissButton = onCancel?.let {
            { TextButton(onClick = it) { Text("Cancel", color = AppColors.DangerAccent) } }
        }
    )
}

/** Card that starts collapsed — the title row is always visible and
 *  acts as a click target; tapping reveals [content]. */
@Composable
fun CollapsibleCard(
    title: String,
    icon: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Text(
                        LocalMetadataIcons.current.forFactoryGlyph(icon),
                        fontSize = 28.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(42.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(title, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
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
    icon: String? = null,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Text(
                        LocalMetadataIcons.current.forFactoryGlyph(icon),
                        fontSize = 28.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(42.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(title, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
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

/** True when the app is in "Home bar" mode (a persistent HomeIconBar above
 *  every screen). Gates the HOME_BAR-only TitleBar tweaks in
 *  [AppTopBarChrome]: the right end icon mirrors the left instead of showing
 *  the mirrored AI logo, and the AI-logo home links go inert (the home bar
 *  navigates). False (default) = HOME_SCREEN mode — unchanged behavior. */
val LocalHomeBarMode = compositionLocalOf { false }

/** True only on Help + About screens while in Home bar mode — tells
 *  [AppTopBarChrome] to omit BOTH title-bar end icons there. */
val LocalSuppressTitleBarEndIcons = compositionLocalOf { false }

/** Top-bar broken-work badge. When the background scan finds reports
 *  with interrupted batches, AppNavHost provides a non-null value here;
 *  [AppTopBarChrome] then replaces the right-side AI logo with a ⚠️ that
 *  taps through to the Broken-work screen via [onOpen]. Null (the
 *  default) means nothing is broken — the normal AI logo shows. */
data class BrokenWorkBadge(val count: Int, val onOpen: () -> Unit)
val LocalBrokenWork = compositionLocalOf<BrokenWorkBadge?> { null }

/** Force one Broken-work scan NOW (drives the ⚠️ top-bar warning). Provided by
 *  AppNavHost; called by the Manage / Get-info / second-results screens the
 *  moment they detect an error (a red ❌), so the badge appears immediately
 *  instead of waiting for the 30-second background sweep. */
val LocalRefreshBrokenWork = compositionLocalOf<(() -> Unit)?> { null }

/** The app's GeneralSettings, provided by AppNavHost. Read by view-layer
 *  screens that need general prefs without threading them — e.g. the Value
 *  view's "Combined" ranking, which weights each ranking by
 *  GeneralSettings.rankingWeights. */
val LocalGeneralSettings = compositionLocalOf<com.ai.viewmodel.GeneralSettings?> { null }

/** Provided by AppNavHost so an AI-report screen's top-left 📝 icon can
 *  jump to the AI Reports hub. Used by the report-section screens (New
 *  report, All reports, Search, …) whose top-left glyph is the report
 *  default icon while no single report is in scope. The hub itself uses
 *  reportIconGoesHome instead. */
val LocalNavigateToReportsHub = compositionLocalOf<() -> Unit> { {} }

/** Navigate to the internal-prompt editor for a given prompt id. Provided
 *  by the report nav graph; consumed by the edit pencil on the Report -
 *  Get info detail screens' Model card. No-op default. */
val LocalEditInternalPrompt = compositionLocalOf<(String) -> Unit> { {} }

/** Re-run one Report-info metadata item — backs the 🔄 reload on the
 *  Get info detail screens. Args: (reportId, kind, agentId?) where agentId
 *  is set only for the per-model title / icon items. Provided by the report
 *  nav graph; no-op default. */
val LocalRegenerateMetaItem =
    compositionLocalOf<(String, com.ai.viewmodel.MetaRegenKind, String?) -> Unit> { { _, _, _ -> } }

/** Persist a picked Find-alt report title (short/long) with its alternative
 *  model + provenance marker, so the Get-info title card reflects the alt
 *  call. Args: (reportId, long, title, model). Provided by the report nav
 *  graph; no-op default. */
val LocalApplyAltReportTitle =
    compositionLocalOf<(String, Boolean, String, String) -> Unit> { { _, _, _, _ -> } }

/** Per-model sibling of [LocalApplyAltReportTitle].
 *  Args: (reportId, agentId, title, model). */
val LocalApplyAltModelTitle =
    compositionLocalOf<(String, String, String, String) -> Unit> { { _, _, _, _ -> } }

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
    /** Open the Manage Tournament drill-in for [reportId] (the manage-side
     *  handler sets [LocalTournamentOpenState], which ReportsScreenNav
     *  early-returns into the tournament overlay). */
    data class Tournament(val reportId: String) : ManageJump()
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
    data class Tournament(val id: String) : ViewJump()
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

/** Override for the top-right (mirrored) AI logo's tap target. Provided
 *  around the report Manage / View subtrees so the logo takes the user to
 *  the Reports hub ("Reports" home option) instead of all the way Home.
 *  Null everywhere else → the logo falls back to navigate-Home. */
val LocalReportHubNav = compositionLocalOf<(() -> Unit)?> { null }

/** Per-report system-prompt setter, provided around the AI_REPORTS
 *  composable in AppNavHost so descendants (Report - manage's Edit
 *  Row 2) can fire the per-report system-prompt picker without
 *  needing the callback threaded through 30+ function arguments —
 *  ReportsScreen's signature sits at the JVM 64 KB per-method
 *  bytecode ceiling, so a CompositionLocal is the cheapest way to
 *  surface the function to nested screens. Default no-op. */
val LocalSystemPromptChange = compositionLocalOf<(String?) -> Unit> { {} }

/** Fire AI title-generation for one user note — `(reportId, noteId,
 *  noteText)`. Invoked by the note editor on every save (add/edit).
 *  Provided around the AI_REPORTS composable, wired to
 *  `ReportViewModel.generateUserNoteTitle`. A CompositionLocal (not a
 *  threaded arg) for the same 64 KB-ceiling reason as
 *  [LocalSystemPromptChange]. Default no-op. */
val LocalGenerateNoteTitle = compositionLocalOf<(String, String, String) -> Unit> { { _, _, _ -> } }

/** Continue a META secondary result in the Chat section — `(reportId,
 *  resultId, activeLanguage?)`. The 💬 icon on the meta-item detail reads
 *  it. Provided around the report area, wired to `continueMetaInChat` +
 *  navigate. Default no-op. */
val LocalContinueMetaInChat = compositionLocalOf<(String, String, String?) -> Unit> { { _, _, _ -> } }

/** Bridge that lets the in-report "refine this answer" chat screen
 *  (🗣️ on Model-response / Fan-out-response) reach the chat engine
 *  without a view-model handle. Provided around the report area in
 *  AppNavHost, wired to ChatViewModel + AppViewModel. Null off the
 *  report area. See [com.ai.ui.report.manage.AgentChatScreen]. */
class AgentChatBridge(
    /** Stream one chat turn. [agentIdForKey] resolves the settings
     *  Agent's effective API key/endpoint when non-null; else the
     *  provider-level key is used. Returns content chunks. */
    val send: (
        service: com.ai.data.AppService,
        model: String,
        agentIdForKey: String?,
        messages: List<com.ai.data.ChatMessage>,
        params: com.ai.data.ChatParameters
    ) -> kotlinx.coroutines.flow.Flow<String>,
    /** Rough token estimate (chars/4) — for AI Usage accounting. */
    val estimateTokens: (String) -> Int,
    /** Record one turn's tokens into the global AI Usage ledger. */
    val recordUsage: (service: com.ai.data.AppService, model: String, inputTokens: Int, outputTokens: Int) -> Unit,
)
val LocalAgentChat = compositionLocalOf<AgentChatBridge?> { null }

/** Current AI [com.ai.model.Settings], provided around the report area so
 *  deep Manage screens (e.g. the 🗣️ refine chat + its 🎭/🌡️ pickers) can
 *  read agents / system prompts / parameter presets without threading
 *  uiState through every layer. Defaults to empty Settings off the report area. */
val LocalAiSettings = compositionLocalOf { com.ai.model.Settings() }

/** Opens the standalone "Report information" screen for a reportId.
 *  Provided around the AI_REPORTS composable; the Manage hub's ℹ️ icon
 *  reads it. A CompositionLocal (not a threaded arg) for the same
 *  64 KB-ceiling reason as [LocalSystemPromptChange]. Default no-op. */
val LocalNavigateToReportInfo = compositionLocalOf<(String) -> Unit> { {} }

/** Opens the "New Report" start hub (New report / Start with a previous
 *  report / Start with an example prompt). Provided around the AI_REPORTS
 *  composable; the Manage hub's 🆕 icon reads it. A CompositionLocal (not a
 *  threaded arg) for the same 64 KB-ceiling reason as [LocalNavigateToReportInfo].
 *  Default no-op. */
val LocalNavigateToNewReport = compositionLocalOf<() -> Unit> { {} }

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

/** The four "jump to a Monitor part" navigation actions — Live
 *  Dashboard / API Traces / Application log / Statistics. Provided
 *  (via [LocalMonitorNav]) around every screen in the Monitor subtree,
 *  so its [TitleBar] auto-renders the matching 📡 🐞 📜 📊 icons at the
 *  START of the bottom icon row — letting the user hop between the four
 *  Monitor sections from anywhere under Monitor without backing out to
 *  the hub. Null (default) → no jump icons, so every screen outside the
 *  Monitor subtree is unaffected. */
/** Which of the four Monitor parts a screen IS — so its own jump icon
 *  is dropped from the row (no point linking to where you already are).
 *  Null for the deeper Monitor-subtree screens (Reports, Providers, …),
 *  which aren't one of the four parts and so keep all four icons. */
enum class MonitorPart { LIVE_DASHBOARD, TRACES, APP_LOG, AUDIT, STATISTICS }

data class MonitorNav(
    val onLiveDashboard: () -> Unit,
    val onTraces: () -> Unit,
    val onAppLog: () -> Unit,
    val onAudit: () -> Unit,
    val onStatistics: () -> Unit,
    /** The part the current screen represents, whose icon is omitted. */
    val active: MonitorPart? = null,
)
val LocalMonitorNav = compositionLocalOf<MonitorNav?> { null }

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

/** [LocalRegenerateBatchEngine] analog for the Tournament drill-in — lets
 *  the Manage row read the engine without threading it through every
 *  intermediate composable. Null outside a report screen. */
val LocalTournamentEngine = compositionLocalOf<com.ai.viewmodel.TournamentEngine?> { null }

/** "Report - second results" layer open-flag, held in ReportsScreenNav (above
 *  the batch-overlay early-returns) so it SURVIVES opening a Nav-level batch
 *  drill-in (Tournament / Judges / Compare / Rank) — Back from the drill-in then
 *  re-shows the second-results list rather than dropping to Manage. The "second"
 *  row sets it; the layer + paused-flag read it. */
val LocalShowSecondResults =
    compositionLocalOf<androidx.compose.runtime.MutableState<Boolean>?> { null }

/** Shared "Tournament L1 is open for reportId X" state slot, shared by the
 *  Manage row's click handler and the overlay-mount site. */
val LocalTournamentOpenState =
    compositionLocalOf<androidx.compose.runtime.MutableState<String?>?> { null }

/** [LocalTournamentEngine] analog for the "Judge the judges" drill-in. */
val LocalJudgeEvalEngine = compositionLocalOf<com.ai.viewmodel.JudgeEvalEngine?> { null }

/** [LocalTournamentEngine] analog for the plain-meta "Meta detail" edit
 *  screen — lets [MetaDetailScreen] reach the per-report
 *  [com.ai.viewmodel.MetaEditManager] without threading ~15 callbacks. */
val LocalMetaEditManager = compositionLocalOf<com.ai.viewmodel.MetaEditManager?> { null }
/** Per-report "Switch model / agent" preview+apply for any secondary kind,
 *  surfaced from each detail screen's Change-result action. */
val LocalSecondaryModelSwitch = compositionLocalOf<com.ai.viewmodel.SecondaryModelSwitchManager?> { null }

/** Shared "Judge-the-judges L1 is open for reportId X" state slot. */
val LocalJudgeEvalOpenState =
    compositionLocalOf<androidx.compose.runtime.MutableState<String?>?> { null }

/** Request slot: the Tournament overlay sets this to a reportId to ask the
 *  (re-rendered) Manage screen to launch Judge-the-judges with the shared
 *  build-stage popup. Consumed + cleared by ConsumePendingJudgeJudges. */
val LocalPendingJudgeJudges =
    compositionLocalOf<androidx.compose.runtime.MutableState<String?>?> { null }

/** [LocalTournamentEngine] analog for the "Compare with meta" drill-in. */
val LocalCompareEngine = compositionLocalOf<com.ai.viewmodel.CompareEngine?> { null }

/** Shared "Compare L1 is open for reportId X" state slot, shared by the
 *  selection flow's Run handler and the overlay-mount site. */
val LocalCompareOpenState =
    compositionLocalOf<androidx.compose.runtime.MutableState<String?>?> { null }

/** [LocalCompareEngine] analog for the "Rank the translators" drill-in (🏅). */
val LocalTranslatorRankEngine = compositionLocalOf<com.ai.viewmodel.TranslatorRankEngine?> { null }

/** Shared "Translator-rank L1 is open" state slot — the value is the run key
 *  "$reportId|$sourceTranslationRunId" (the ranking is per language). */
val LocalTransRankOpenState =
    compositionLocalOf<androidx.compose.runtime.MutableState<String?>?> { null }

/** Carries the Broken-work "Continue" one-shot from ReportsScreenNav (which
 *  owns the engines + the [com.ai.viewmodel.AppViewModel.pendingBatchOpen]
 *  flow) down to [com.ai.ui.report.manage.ConsumePendingBatchOpen], which runs
 *  inside ReportsScreen where the build popup + fan-out/translation open-state
 *  live. [pending] is the live request; [consume] clears it (one-shot); [launch]
 *  fires the matching engine's `continueBroken…` for the request, driving the
 *  build popup off the passed buildKey and returning its Job for the popup's
 *  Cancel. */
class PendingBatchOpenController(
    val pending: com.ai.viewmodel.PendingBatchOpen?,
    val consume: () -> Unit,
    val launch: (com.ai.viewmodel.PendingBatchOpen, String) -> kotlinx.coroutines.Job?,
)
val LocalPendingBatchOpenController = compositionLocalOf<PendingBatchOpenController?> { null }

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
    val initialManageOverlay: String? = null,
    /** Report ids currently RUNNING — [ReportListRow] shows a spinning
     *  hourglass instead of the report's own icon. Set by the Reports hub. */
    val runningIds: Set<String> = emptySet(),
    /** Report ids with BROKEN WORK (also on the Broken-work screen) —
     *  [ReportListRow] shows the warning icon instead of the report's own. */
    val brokenIds: Set<String> = emptySet()
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

/** The 1️⃣ 2️⃣ 3️⃣ screen-switcher published by the three report screens
 *  (Manage / Get-info / Second-results). [active] is the on-screen one
 *  (1/2/3); buildBottomBarIcons renders it first and full-colour, the
 *  other two greyed but still clickable, jumping straight to that screen. */
data class ReportScreenNav(
    val active: Int,
    val onGoManage: () -> Unit,
    val onGoGetInfo: () -> Unit,
    val onGoSecond: () -> Unit
)

/** Captured icon state from a TitleBar — what BottomIconBar needs to
 *  render the same strip the top bar would have rendered. */
data class TitleBarIcons(
    val helpTopic: String?,
    val onChat: (() -> Unit)?,
    /** Optional 🗣️ refine-in-chat hook (Model response / Fan-out response). */
    val onAgentChat: (() -> Unit)? = null,
    /** Optional 🌡️ temperature sweep hook (Model response). */
    val onTemperatureSweep: (() -> Unit)? = null,
    /** Optional 🧠 reasoning-effort sweep hook (Model response). */
    val onReasoningEffortSweep: (() -> Unit)? = null,
    /** Optional 🧭 web-search replay hook (Model response). */
    val onWebSearchReplay: (() -> Unit)? = null,
    val onInfo: (() -> Unit)?,
    /** Optional 👁 view-report hook. Distinct from [onInfo] (ℹ️
     *  Model Info) — this one opens the View tile grid for the
     *  active report. Wired from Report - manage so the bottom-bar
     *  carries the same glyph as the per-row eye icon on every
     *  reports list. Null → glyph hidden. */
    val onOpenView: (() -> Unit)? = null,
    /** Optional 🐜 open-batch-workers hook. Wired from the type-B
     *  batch L1 screens (Tournament / Fan Meta / Translation) to push
     *  that batch's per-worker (model) grouping into its own
     *  sub-screen. Null → glyph hidden. */
    val onBatchWorkers: (() -> Unit)? = null,
    /** Optional ⚖️ "Judge the judges" hook — wired from the Tournament L1
     *  bottom bar to start the judge-eval batch with the shared build popup.
     *  Null → glyph hidden. */
    val onJudgeJudges: (() -> Unit)? = null,
    /** Grays the 🐜 ant icon when false. Set on the workers screens so the
     *  user sees they're already in workers mode; the icon still clicks
     *  (back to the models view). True (the L1 models screens) = full. */
    val batchWorkersActive: Boolean = true,
    /** Optional 🏅 "Rank the translators" hook — wired from the Translations
     *  list rows and the Translation run screens to start / open the rank
     *  batch for that translation. Null → glyph hidden. */
    val onRankTranslators: (() -> Unit)? = null,
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
    /** When true, the 💬 chat and 🔄 reload glyphs swap bottom-bar
     *  positions (🔄 takes the early chat slot, 💬 the late reload slot).
     *  Set only by the Model-response screen. */
    val swapChatAndReload: Boolean = false,
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
    /** Optional 🔤 row-label toggle. Wired from Report - manage to
     *  switch model rows between generated titles and raw model names. */
    val onToggleModelRowLabels: (() -> Unit)? = null,
    /** True when model rows currently show raw model names. */
    val modelRowLabelsShowModelNames: Boolean = false,
    /** Optional 🆕 add hook. CRUD list pages publish it so the bottom
     *  bar carries the "add new entry" action (replacing the old top-of-
     *  list Add button). Null → glyph hidden. */
    val onAdd: (() -> Unit)? = null,
    /** Glyph for the [onAdd] icon — overridable so Manage report can show
     *  the 🔗 Meta launcher instead of the generic 🆕. Blank → resolved from
     *  the user's Default icons (MetadataIcons.add) at render time. */
    val addIcon: String = "",
    /** Optional Fan Out launcher hook. Wired from Manage report to open an
     *  existing Fan Out or start a new one. Null → glyph hidden. */
    val onFanOut: (() -> Unit)? = null,
    /** Override glyph for the launcher icons. Blank → resolved from the user's
     *  Default icons (MetadataIcons) at render time rather than hard-coded. */
    val fanOutIcon: String = "",
    /** Optional tournament launcher hook. Wired from Manage report to open
     *  the dedicated Tournament creation screen. Null → glyph hidden. */
    val onTournament: (() -> Unit)? = null,
    val tournamentIcon: String = "",
    /** Optional Translate / Rerank / Moderation launcher hooks. Wired from
     *  Manage report (replacing the old Create-launcher rows). Null → hidden. */
    val onTranslate: (() -> Unit)? = null,
    val translateIcon: String = "",
    val onRerank: (() -> Unit)? = null,
    val rerankIcon: String = "",
    val onModeration: (() -> Unit)? = null,
    val moderationIcon: String = "",
    /** Optional ✏️ edit hook. CRUD view pages publish it so the bottom
     *  bar carries the "edit this entry" action. Null → glyph hidden. */
    val onEdit: (() -> Unit)? = null,
    /** Optional ✍️ add-user-note hook (report-manage screens). Null →
     *  glyph hidden. */
    val onAddNote: (() -> Unit)? = null,
    /** Optional 📒 list-all-notes hook (Manage report only). Null →
     *  glyph hidden. */
    val onListNotes: (() -> Unit)? = null,
    /** Reports-hub nav actions surfaced as leading bottom-bar icons — 🆕 New,
     *  🔍 Search, 🗂️ All, 📥 Import — which replaced the former top buttons.
     *  Null → hidden. */
    val onNewReport: (() -> Unit)? = null,
    val onSearchReports: (() -> Unit)? = null,
    val onAllReports: (() -> Unit)? = null,
    val onImportReport: (() -> Unit)? = null,
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
    /** When true, the 📈 statistics glyph trails the 🗑 delete icon
     *  instead of sitting in the nav-jump group. Set by the Application
     *  log screen. */
    val statsAfterDelete: Boolean = false,
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
    /** Optional 👷 open-worker-configuration hook (Manage report) —
     *  re-opens "Report - select workers" without the Generate button.
     *  Null → glyph hidden. */
    val onWorkerConfig: (() -> Unit)? = null,
    /** When non-null, the four "jump to a Monitor part" actions captured
     *  from [LocalMonitorNav] — rendered as 📡 🐞 📜 📊 at the START of the
     *  bottom icon row on every screen in the Monitor subtree. Null on
     *  every screen outside it. */
    val monitorNav: MonitorNav? = null,
    /** The screen's title, captured so the live "<title> - icons" overlay
     *  (white ❓ on allowlisted screens) can header itself. Null → "Icons". */
    val title: String? = null,
    /** The 1️⃣ 2️⃣ 3️⃣ report-screen switcher (Manage / Get-info / Second).
     *  Non-null only on the three report screens; rendered first in the
     *  bar. Null → no switcher. */
    val screenNav: ReportScreenNav? = null,
    /** Greys the 📒 list-notes glyph (alpha) when the report has no notes
     *  yet. Still clickable so the User-notes screen (with ✍️ Add note)
     *  stays reachable. Ignored when [onListNotes] is null. */
    val listNotesActive: Boolean = true
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

/** Like [shortModelName] but also trims the release-channel / snapshot-date
 *  suffix providers append, keeping the real version number. Validated against
 *  the whole live catalog (1399 model ids) — every one of the five forms below
 *  is a date / `-latest`, and real versions (`gpt-5.5`, `claude-opus-4-5`,
 *  `grok-4.20`, `…-24b-instruct`, `deepseek-chat-v3`, `tts-1`) are untouched:
 *
 *   - `-latest`              mistral-small-latest        → mistral-small
 *   - `-YYYY-MM-DD`          gpt-5.5-2026-04-23          → gpt-5.5      (OpenAI/Google)
 *   - `-MM-YYYY`             command-r7b-12-2024         → command-r7b  (Cohere/Command)
 *   - `-YYYYMMDD`            claude-opus-4-5-20251101    → claude-opus-4-5 (Anthropic)
 *   - `-YYMM` / `-MMDD`      codestral-2508, gpt-4-0613  → codestral, gpt-4
 *
 *  Order matters: the dashed-date forms must run before the bare 4-digit code,
 *  else `command-r7b-12-2024` would lose only `-2024`. Three-digit revisions
 *  (`babbage-002`, `text-embedding-004`) are left alone — there the number IS
 *  the version. DISPLAY ONLY — never use for keys / matching, since two
 *  snapshots collapse to one string. Opt in per site; everything else stays on
 *  [shortModelName]. */
fun shortModelName2(model: String): String =
    shortModelName(model)
        .removeSuffix("-latest")
        .replace(Regex("-\\d{4}-\\d{2}-\\d{2}$"), "")
        .replace(Regex("-\\d{2}-\\d{4}$"), "")
        .replace(Regex("-\\d{8}$"), "")
        .replace(Regex("-\\d{4}$"), "")

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
    /** The bar's second line (centre, under the main title) —
     *  the former green page subject. Blank/null → no second line. */
    subject: String? = null,
    /** When set, the subject line is a Model-Info link (matches
     *  the old clickable [HardcodedSubjectRow]). */
    subjectProviderService: com.ai.data.AppService? = null,
    subjectModel: String? = null,
    /** Independent tap target for the subject line, distinct from
     *  the icon/title click. Used by Help (orange → originating screen). */
    subjectOnClick: (() -> Unit)? = null,
    /** Optional trailing chip beside the subject line (e.g. the
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
    /** Optional 🐜 open-batch-workers hook (type-B batch L1 screens). */
    onBatchWorkers: (() -> Unit)? = null,
    /** Optional ⚖️ "Judge the judges" launcher (Tournament L1 bottom bar). */
    onJudgeJudges: (() -> Unit)? = null,
    onRankTranslators: (() -> Unit)? = null,
    /** False on the workers screens grays the 🐜 (still clicks → models). */
    batchWorkersActive: Boolean = true,
    /** Optional 🔧 open-manage hook. Wired by every View screen so
     *  the bottom-bar carries the same wrench glyph the per-row 🔧
     *  uses on every reports list. Each sub-View typically passes a
     *  closure that fires [LocalOpenManage] with the matching
     *  [ManageJump] target. Null → glyph hidden. */
    onOpenManage: (() -> Unit)? = null,
    onReload: (() -> Unit)? = null,
    /** Swap the 💬 chat and 🔄 reload glyph positions in the bottom bar.
     *  Set only by the Model-response screen. */
    swapChatAndReload: Boolean = false,
    onChat: (() -> Unit)? = null,
    /** Optional 🗣️ refine-in-chat hook — opens the in-report agent chat
     *  that lets the user iterate on this answer ("be more verbose") and
     *  Apply a reply back into the report. Distinct from [onChat] (💬),
     *  which sends the answer out to the Chat section. Null → glyph hidden. */
    onAgentChat: (() -> Unit)? = null,
    /** Optional 🌡️ temperature sweep hook — opens the transient
     *  three-temperature candidate runner for a model response. Null →
     *  glyph hidden. */
    onTemperatureSweep: (() -> Unit)? = null,
    /** Optional 🧠 reasoning-effort sweep hook — opens the transient
     *  None / Low / Medium / High candidate runner for a model response.
     *  Null → glyph hidden. */
    onReasoningEffortSweep: (() -> Unit)? = null,
    /** Optional 🧭 web-search replay hook — replays this model response
     *  with the native web-search tool enabled and lets the user apply
     *  that body back into the report. Null → glyph hidden. */
    onWebSearchReplay: (() -> Unit)? = null,
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
    /** Honor [onTitleClick] even in Home bar mode (the report screens whose
     *  title cycles to the next of the three report screens). */
    forceTitleClick: Boolean = false,
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
    /** Optional 🔤 row-label toggle. Used by Manage report to switch
     *  agent rows between generated titles and raw model names. */
    onToggleModelRowLabels: (() -> Unit)? = null,
    /** True when agent rows currently show raw model names. */
    modelRowLabelsShowModelNames: Boolean = false,
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
    /** Glyph for [onAdd] — overridable (Manage report uses 🔗). Blank →
     *  resolved from the user's Default icons (MetadataIcons.add). */
    addIcon: String = "",
    /** Optional Fan Out launcher hook. Null → glyph hidden. */
    onFanOut: (() -> Unit)? = null,
    /** Launcher glyph overrides — blank resolves from the user's Default icons. */
    fanOutIcon: String = "",
    /** Optional tournament launcher hook. Null → glyph hidden. */
    onTournament: (() -> Unit)? = null,
    tournamentIcon: String = "",
    /** Optional Translate / Rerank / Moderation launcher hooks (Manage report
     *  bottom bar). Null → glyph hidden. */
    onTranslate: (() -> Unit)? = null,
    translateIcon: String = "",
    onRerank: (() -> Unit)? = null,
    rerankIcon: String = "",
    onModeration: (() -> Unit)? = null,
    moderationIcon: String = "",
    /** When true, 🆕 leads the bar instead of sitting in the trailing
     *  group. Used by the Manage report screen. */
    addFirst: Boolean = false,
    /** Optional ✏️ edit hook (CRUD view pages). Null → glyph hidden. */
    onEdit: (() -> Unit)? = null,
    /** Optional ✍️ add-user-note hook. Wired by the report-manage
     *  screens that can carry user notes (the report, a model response,
     *  a fan-out run/pair, a secondary row). Opens the note editor for
     *  the thing on this screen. Null → glyph hidden. */
    onAddNote: (() -> Unit)? = null,
    /** Optional 📒 list-all-notes hook. Wired only by the main Manage
     *  report screen — opens the "all notes in this report" screen.
     *  Null → glyph hidden. */
    onListNotes: (() -> Unit)? = null,
    /** Optional 🌡️ parameters / 🎭 system-prompt hooks — paired config
     *  actions surfaced in the bottom bar (replacing inline buttons). */
    onParameters: (() -> Unit)? = null,
    onSystemPrompt: (() -> Unit)? = null,
    /** Optional 🧽 clear / 📎 attach / 🚩 validate-prompt hooks (New AI
     *  Report). validatePromptActive grays the 🚩 until activated. */
    onClear: (() -> Unit)? = null,
    onAttach: (() -> Unit)? = null,
    onValidatePrompt: (() -> Unit)? = null,
    /** Reports-hub leading bottom-bar actions (🆕 New / 🔍 Search / 🗂️ All /
     *  📥 Import). */
    onNewReport: (() -> Unit)? = null,
    onSearchReports: (() -> Unit)? = null,
    onAllReports: (() -> Unit)? = null,
    onImportReport: (() -> Unit)? = null,
    validatePromptActive: Boolean = false,
    /** 👷 open-worker-configuration hook (Manage report) — re-opens
     *  "Report - select workers" without the Generate button. */
    onWorkerConfig: (() -> Unit)? = null,
    /** Optional 🧹 jump-to-Housekeeping hook. Null → glyph hidden. */
    onHousekeeping: (() -> Unit)? = null,
    /** Optional ⚙️ jump-to-AI-Setup/Settings hook. Null → glyph hidden. */
    onSettings: (() -> Unit)? = null,
    /** Optional 📈 jump-to-statistics hook. Null → glyph hidden. */
    onStats: (() -> Unit)? = null,
    /** When true, the 📈 statistics glyph is placed AFTER the 🗑 delete
     *  icon instead of in its usual nav-jump position. Set by the
     *  Application log screen so its "App log statistics" jump trails the
     *  clear-all action. */
    statsAfterDelete: Boolean = false,
    /** When false, this bar renders its top chrome but does NOT publish
     *  its icons into [LocalBottomIconState]. Used by screens drawn as a
     *  visual layer ON TOP of a still-composed host (e.g. "Report - Get
     *  info" over the Manage hub) so the host's already-published bottom
     *  bar stands instead of being clobbered. */
    publishBottomBar: Boolean = true,
    /** Optional 1️⃣ 2️⃣ 3️⃣ report-screen switcher (Manage / Get-info /
     *  Second-results). Rendered first in the bottom bar — active first
     *  and full-colour, the other two greyed but clickable. Null → none. */
    screenNav: ReportScreenNav? = null,
    /** Greys the 📒 list-notes glyph when the report has no notes yet (it
     *  stays clickable). Ignored when [onListNotes] is null. */
    listNotesActive: Boolean = true,
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
                text = "AI", fontSize = 36.sp, color = AppColors.TextPrimary, fontWeight = FontWeight.Bold,
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
        onAgentChat = onAgentChat,
        onTemperatureSweep = onTemperatureSweep,
        onReasoningEffortSweep = onReasoningEffortSweep,
        onWebSearchReplay = onWebSearchReplay,
        onInfo = onInfo,
        onOpenView = onOpenView,
        onBatchWorkers = onBatchWorkers,
        onJudgeJudges = onJudgeJudges,
        batchWorkersActive = batchWorkersActive,
        onRankTranslators = onRankTranslators,
        onOpenManage = onOpenManage,
        // 🗂️ pick-another-report — auto-captured from the per-screen
        // CompositionLocal so Manage screens needn't thread it through
        // their TitleBar signatures. Null on every other screen.
        onPickReport = LocalManagePickReport.current,
        onCopy = onCopy,
        onShare = onShare,
        onReload = onReload,
        swapChatAndReload = swapChatAndReload,
        onDelete = onDelete,
        onTrace = onTrace,
        onTranslationCompare = onTranslationCompare,
        onMemo = null,
        onCopyReport = onCopyReport,
        onPin = onPin,
        isPinned = isPinned,
        onToggleModelRowLabels = onToggleModelRowLabels,
        modelRowLabelsShowModelNames = modelRowLabelsShowModelNames,
        onAdd = onAdd,
        onFanOut = onFanOut,
        fanOutIcon = fanOutIcon,
        onTournament = onTournament,
        tournamentIcon = tournamentIcon,
        onTranslate = onTranslate,
        translateIcon = translateIcon,
        onRerank = onRerank,
        rerankIcon = rerankIcon,
        onModeration = onModeration,
        moderationIcon = moderationIcon,
        addFirst = addFirst,
        addIcon = addIcon,
        onEdit = onEdit,
        onAddNote = onAddNote,
        onListNotes = onListNotes,
        onNewReport = onNewReport,
        onSearchReports = onSearchReports,
        onAllReports = onAllReports,
        onImportReport = onImportReport,
        onParameters = onParameters,
        onSystemPrompt = onSystemPrompt,
        onClear = onClear,
        onAttach = onAttach,
        onValidatePrompt = onValidatePrompt,
        validatePromptActive = validatePromptActive,
        onWorkerConfig = onWorkerConfig,
        onHousekeeping = onHousekeeping,
        onSettings = onSettings,
        onStats = onStats,
        statsAfterDelete = statsAfterDelete,
        // 📡 🐞 📜 📊 Monitor-section jump group — auto-captured from the
        // per-subtree CompositionLocal so Monitor screens needn't thread it
        // through their TitleBar signatures. Null on every other screen.
        monitorNav = LocalMonitorNav.current,
        // ❓ help moved out of the top bar into the bottom icons bar
        // (right-aligned, other icons left). View screens keep their
        // top-bar ❓ — see ViewScreenTitleBar.
        onHelp = helpTopic?.let { { navigateHelp(it) } },
        screenNav = screenNav,
        listNotesActive = listNotesActive
    )
    if (state != null && publishBottomBar) {
        // Bottom-bar publish is RESUME-anchored so the ACTIVE destination always
        // wins. The old identity-on-`captured` guard let a leaving screen
        // (e.g. Manage) keep republishing during its exit transition and then
        // null the bar on dispose, while the entering hub's TitleBar — skipped
        // on recomposition because its params are stable — never re-published,
        // so the Reports-hub bar vanished after deleting a report. Fix:
        //   - publish only while RESUMED, so a screen animating out can't
        //     override the bar;
        //   - re-publish on ON_RESUME, so the entering destination re-asserts
        //     its bar after the transition settles even if its TitleBar didn't
        //     recompose;
        //   - clear on dispose only when we still own the current value.
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        val capturedRef = androidx.compose.runtime.rememberUpdatedState(captured)
        val resumed = { lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
        SideEffect { if (resumed()) state.value = capturedRef.value }
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) state.value = capturedRef.value
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            if (resumed()) state.value = capturedRef.value
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                if (state.value === capturedRef.value) state.value = null
            }
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
                swipeCtx, swipeIds, swipeCurrentReportId,
                com.ai.ui.helpers.SwipeDirection.Prev,
                swipeFilter
            )
            if (match == null) false
            else {
                swipeOnMatch?.invoke(match)
                swipeReportSwitchHandler(match.reportId)
                true
            }
        }) else null
    val resolvedOnSwipeNext: (() -> Boolean)? = onSwipeNext
        ?: if (swipeAutoReady) ({
            val match = com.ai.ui.helpers.findSwipeMatch(
                swipeCtx, swipeIds, swipeCurrentReportId,
                com.ai.ui.helpers.SwipeDirection.Next,
                swipeFilter
            )
            if (match == null) false
            else {
                swipeOnMatch?.invoke(match)
                swipeReportSwitchHandler(match.reportId)
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
    // When the left report glyph navigates to the report's Manage page (a report
    // is in scope and the icon isn't the Manage-main → Home shortcut), Home bar
    // mode mirrors that nav onto the title + right icon too. Null otherwise, so
    // home-page links stay inert there.
    val reportNavClick: (() -> Unit)? =
        if (resolvedReportIcon != null && !reportIconGoesHome) reportIconClick else null
    AppTopBarChrome(
        screenTitle = title,
        secondLine = subject,
        thirdLine = null,
        reportIcon = resolvedReportIcon,
        onReportIconClick = reportIconClick,
        onTitleClick = effectiveTitleClick,
        forceTitleClick = forceTitleClick,
        reportNavClick = reportNavClick,
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
 *  - Centre column: [screenTitle] (auto-shrinks 24→18sp on
 *    overflow), then [secondLine] (18sp), then green [thirdLine]
 *    (24sp). Tapping the column fires [onTitleClick]. The second line
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
    /** Home-bar-mode only: the nav the left report glyph performs when it's a
     *  report destination (Manage on report screens, the View hub on View
     *  screens). The title + right icon mirror it; null otherwise → they stay
     *  inert in Home bar so home-page links don't work there. */
    reportNavClick: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)?,
    /** Honor [onTitleClick] even in Home bar mode (instead of the report-nav
     *  mirror). Set by the report screens whose title cycles to the next one. */
    forceTitleClick: Boolean = false,
    onSwipePrev: (() -> Boolean)?,
    onSwipeNext: (() -> Boolean)?,
    secondProviderService: com.ai.data.AppService? = null,
    secondModel: String? = null,
    /** Independent tap target for the 2nd line (overrides the
     *  column/title click for that line). Used by Help: orange → origin. */
    secondLineOnClick: (() -> Unit)? = null,
    secondTrailing: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier
) {
    val navigateHome = LocalNavigateHome.current
    // Home bar mode (persistent HomeIconBar): the title bar's right icon
    // mirrors the left instead of the mirrored AI logo, the AI-logo home
    // links go inert, and Help/About hide both end icons.
    val homeBar = LocalHomeBarMode.current
    val hideEnds = LocalSuppressTitleBarEndIcons.current
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
            // In Home bar mode the title only navigates when the left report
            // glyph goes to the report's Manage page (then the title mirrors
            // it); otherwise it's inert (its tap would have gone to the home
            // page, which the persistent home bar owns). [forceTitleClick]
            // opts out (the report screens whose title cycles to the next
            // screen want their onTitleClick honored even in Home bar mode).
            val titleClick = if (homeBar && !forceTitleClick) reportNavClick else (onTitleClick ?: sectionIcon?.onClick)
            var bigSizeFits by remember(screenTitle, secondLine, thirdLine) { mutableStateOf(true) }
            val hasScreenTitle = !screenTitle.isNullOrBlank()
            val topText = if (hasScreenTitle) screenTitle else secondLine.orEmpty()
            // Top row: left icon · main screen title · right icon.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Left — report glyph > section icon > AI logo. Hidden on
                // Help/About in Home bar mode.
                if (hideEnds) {
                    Spacer(Modifier.width(44.dp))
                } else if (reportIcon != null) {
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
                    // AI-logo fallback — its home link is inert in Home bar mode.
                    AiLogoButton(
                        onClick = onReportIconClick ?: (if (homeBar) ({}) else navigateHome),
                        modifier = Modifier.align(Alignment.Top),
                        size = 44.dp
                    )
                }
                Text(
                    text = topText, color = AppColors.MainTitle,
                    fontSize = if (bigSizeFits) 20.4.sp else 15.3.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                    onTextLayout = { result -> if (bigSizeFits && result.hasVisualOverflow) bigSizeFits = false },
                    modifier = Modifier.weight(1f)
                        .let { base -> if (titleClick != null) base.clickable(onClick = titleClick) else base }
                )
                // Right — broken-work ⚠️ when the background scan flagged
                // interrupted batches (taps to the Broken-work screen);
                // otherwise the mirrored AI logo → Home, or the Reports hub
                // on report Manage / View screens (via LocalReportHubNav).
                val brokenWork = LocalBrokenWork.current
                if (hideEnds) {
                    Spacer(Modifier.width(44.dp))
                } else if (brokenWork != null) {
                    ReportGlyphIcon(
                        emoji = com.ai.data.MetadataIconsHolder.current.statusWarning,
                        boxSize = 44.dp,
                        modifier = Modifier.align(Alignment.Top).clickable(onClick = brokenWork.onOpen)
                    )
                } else if (homeBar) {
                    // Home bar mode: show the SAME icon as the left. Clickable
                    // to the report's Manage page when the left glyph navigates
                    // there (reportNavClick); otherwise decorative (home
                    // links are inert).
                    val leftGlyph = reportIcon ?: sectionIcon?.glyph
                    if (leftGlyph != null) {
                        ReportGlyphIcon(emoji = leftGlyph, boxSize = 44.dp,
                            modifier = Modifier.align(Alignment.Top)
                                .then(if (reportNavClick != null) Modifier.clickable(onClick = reportNavClick) else Modifier))
                    } else {
                        AiLogoButton(onClick = {}, modifier = Modifier.align(Alignment.Top), size = 44.dp)
                    }
                } else {
                    val reportHubNav = LocalReportHubNav.current
                    AiLogoButton(
                        onClick = reportHubNav ?: navigateHome,
                        modifier = Modifier.align(Alignment.Top),
                        size = 44.dp, mirrored = true
                    )
                }
            }
            // 2nd line — full screen width (not boxed by the icons).
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
                        text = secondLine, color = AppColors.SubTitle,
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
                    text = thirdLine, color = AppColors.SuccessAccent,
                    fontSize = 20.4.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
            }
        }
        val status = swipeStatus.value
        if (status != null) {
            Text(
                text = status, color = AppColors.TextPrimary, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppColors.SurfaceDark.copy(alpha = 0.95f))
                    .border(1.dp, AppColors.InfoAccent.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
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
    /** Layout box WIDTH. Defaults to [size] (square). The glyph is a portrait
     *  62×96, so a square box letterboxes it with ~7dp of transparent margin on
     *  each side; callers that want it tight (e.g. the Home icon bar) pass a
     *  narrower width matching the rendered glyph so there's less space
     *  before/after it. */
    width: Dp = size,
    contentDescription: String = "Home",
    /** Horizontally flip the glyph so the right-edge logo is a mirror
     *  image of the left-edge one. */
    mirrored: Boolean = false
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Image(
        painter = painterResource(R.drawable.brand_glyph),
        contentDescription = contentDescription,
        modifier = modifier.size(width = width, height = size)
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
            fontSize = 32.sp, color = AppColors.SuccessAccent,
            fontWeight = FontWeight.SemiBold,
            maxLines = maxLines,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = clickableTextMod
        )
        trailing()
    }
}

/** One action icon in [BottomIconBar]'s strip. [emoji] is the live (possibly
 *  user-overridden) glyph that renders; [legendKey] is the stable factory glyph
 *  the icon-legend / help maps are keyed by, so the legend still names an icon
 *  the user has re-skinned. Defaults to [emoji] for the few glyphs that aren't
 *  configurable. */
private data class BottomBarIcon(
    val emoji: String,
    val tint: Color,
    val onClick: () -> Unit,
    val widthDp: Int,
    val fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    val alpha: Float = 1f,
    val legendKey: String = emoji,
    /** The 1️⃣ 2️⃣ 3️⃣ report-screen switcher. These get their own dedicated
     *  row (always in 1-2-3 order, never sharing with other icons). */
    val isNav: Boolean = false
)

/** Ordered list of the action icons currently present (❓ help is kept
 *  separate by [BottomIconBar] so it can stay pinned bottom-right).
 *  Order is fixed; only the non-null callbacks contribute. */
private fun buildBottomBarIcons(
    icons: TitleBarIcons,
    mi: com.ai.data.MetadataIcons,
    includeScreenTrace: Boolean = true,
    /** Home-bar mode shows 📤 share in the persistent HomeIconBar, so skip it
     *  here to avoid showing it twice. 📋 copy is NOT hoisted — it stays in the
     *  bottom bar in both modes. */
    suppressShare: Boolean = false
): List<BottomBarIcon> = buildList {
    val D = com.ai.data.MetadataDefaults
    // ----- 1️⃣ 2️⃣ 3️⃣ report-screen switcher -----
    // Always emitted in 1-2-3 order; the on-screen number is full-colour, the
    // other two greyed but still click → jump straight to that report screen.
    // Tagged isNav so BottomIconBar gives them their own exclusive row (or
    // the single bottom row when the screen has no action icons at all).
    icons.screenNav?.let { nav ->
        listOf(
            Triple(1, "1️⃣", nav.onGoManage),
            Triple(2, "2️⃣", nav.onGoGetInfo),
            Triple(3, "3️⃣", nav.onGoSecond)
        ).forEach { (idx, glyph, onClick) ->
            add(BottomBarIcon(glyph, Color.Unspecified, onClick, 28, alpha = if (idx == nav.active) 1f else 0.35f, isNav = true))
        }
    }
    // Reports-hub leading actions: 🆕 New, 🔍 Search, 🗂️ All (replaced the top buttons).
    icons.onNewReport?.let { add(BottomBarIcon(mi.add, Color.Unspecified, it, 28, legendKey = D.ADD)) }
    icons.onSearchReports?.let { add(BottomBarIcon(mi.search, Color.Unspecified, it, 28, legendKey = D.SEARCH)) }
    icons.onAllReports?.let { add(BottomBarIcon(mi.pickReport, Color.Unspecified, it, 28, legendKey = D.PICK_REPORT)) }
    icons.onImportReport?.let { add(BottomBarIcon(mi.importReport, Color.Unspecified, it, 28, legendKey = D.IMPORT)) }
    // Glyph for the add slot: the screen's per-screen override (e.g. 🔗 Meta on
    // Manage report) when set, else the user's Default-icons 🆕. legendKey =
    // that same glyph so the legend can name it.
    val addGlyph = icons.addIcon.ifBlank { mi.add }
    // ----- Monitor-section jump group (leads the strip) -----
    // On every screen in the Monitor subtree, the parts of Monitor —
    // 📡 Live Dashboard, 🐞 API Traces, 📜 Application log, 🧾 Audit,
    // 📊 Statistics — get a quick-jump icon at the very start of the row so
    // the user can hop between sections without backing out to the hub.
    icons.monitorNav?.let { mn ->
        // The screen's own part is skipped — its icon would just link to here.
        if (mn.active != MonitorPart.LIVE_DASHBOARD) add(BottomBarIcon(mi.liveDashboard, Color.Unspecified, mn.onLiveDashboard, 28, legendKey = D.LIVE_DASHBOARD))
        if (mn.active != MonitorPart.TRACES) add(BottomBarIcon(mi.traces, Color.Unspecified, mn.onTraces, 22, legendKey = D.TRACES))
        if (mn.active != MonitorPart.APP_LOG) add(BottomBarIcon(mi.appLog, Color.Unspecified, mn.onAppLog, 28, legendKey = D.APP_LOG))
        if (mn.active != MonitorPart.AUDIT) add(BottomBarIcon(mi.audit, Color.Unspecified, mn.onAudit, 28, legendKey = D.AUDIT))
        if (mn.active != MonitorPart.STATISTICS) add(BottomBarIcon(mi.statisticsMonitor, Color.Unspecified, mn.onStatistics, 28, legendKey = D.STATISTICS_MONITOR))
    }
    // ----- first-row-ish: creation / nav / share -----
    // The add glyph leads when the screen opts in (Manage report); otherwise it
    // stays in the trailing copy/edit/delete/new group below.
    if (icons.addFirst) icons.onAdd?.let { add(BottomBarIcon(addGlyph, Color.Unspecified, it, 28, legendKey = addGlyph)) }
    icons.onFanOut?.let { add(BottomBarIcon(icons.fanOutIcon.ifBlank { mi.fanOutRow }, Color.Unspecified, it, 28, legendKey = D.FAN_OUT)) }
    icons.onTournament?.let { add(BottomBarIcon(icons.tournamentIcon.ifBlank { mi.tournament }, Color.Unspecified, it, 28, legendKey = D.TOURNAMENT)) }
    icons.onTranslate?.let { add(BottomBarIcon(icons.translateIcon.ifBlank { mi.translationRow }, Color.Unspecified, it, 28, legendKey = D.TRANSLATE)) }
    icons.onRerank?.let { add(BottomBarIcon(icons.rerankIcon.ifBlank { mi.rerank }, Color.Unspecified, it, 28, legendKey = D.RERANK)) }
    icons.onModeration?.let { add(BottomBarIcon(icons.moderationIcon.ifBlank { mi.moderate }, Color.Unspecified, it, 28, legendKey = D.MODERATE)) }
    // Chat slot — normally 💬 chat; when swapped (Model response) the 🔄
    // reload glyph takes this early position instead.
    if (icons.swapChatAndReload) {
        icons.onReload?.let { add(BottomBarIcon(mi.reload, AppColors.WarningAccent, it, 28, legendKey = D.RELOAD)) }
    } else {
        icons.onChat?.let { add(BottomBarIcon(mi.chat, Color.Unspecified, it, 28, legendKey = D.CHAT)) }
    }
    icons.onAgentChat?.let { add(BottomBarIcon(mi.agentChat, Color.Unspecified, it, 28, legendKey = D.AGENT_CHAT)) }
    icons.onTemperatureSweep?.let { add(BottomBarIcon(mi.temperatureSweep, Color.Unspecified, it, 28, legendKey = D.TEMPERATURE_SWEEP)) }
    icons.onReasoningEffortSweep?.let { add(BottomBarIcon(mi.reasoningSweep, Color.Unspecified, it, 28, legendKey = D.REASONING_SWEEP)) }
    icons.onWebSearchReplay?.let { add(BottomBarIcon(mi.webSearchReplay, Color.Unspecified, it, 28, legendKey = D.WEB_SEARCH_REPLAY)) }
    // 🗂️ pick another report (same glyph as the View hub's picker) —
    // leads the nav group on the Manage screens that support it.
    icons.onPickReport?.let { add(BottomBarIcon(mi.pickReport, Color.Unspecified, it, 28, legendKey = D.PICK_REPORT)) }
    // 🔧 manage — rendered a touch smaller so 👁 leads on View screens.
    icons.onOpenManage?.let { add(BottomBarIcon(mi.openManage, Color.Unspecified, it, 28, fontSize = 15.sp, legendKey = D.OPEN_MANAGE)) }
    // 🧹 jump to the related Housekeeping screen, ⚙️ jump to the related
    // AI Setup / Settings screen — grouped with the other nav-jumps.
    icons.onHousekeeping?.let { add(BottomBarIcon(mi.housekeeping, Color.Unspecified, it, 28, legendKey = D.HOUSEKEEPING)) }
    icons.onSettings?.let { add(BottomBarIcon(mi.settings, Color.Unspecified, it, 28, legendKey = D.SETTINGS)) }
    // 📈 statistics — normally grouped with the other nav-jumps. A screen
    // can opt to push it past the trailing actions (statsAfterDelete) so it
    // sits just after 🗑 delete instead — see the second-row block below.
    if (!icons.statsAfterDelete) icons.onStats?.let { add(BottomBarIcon(mi.statistics, Color.Unspecified, it, 28, legendKey = D.STATISTICS)) }
    icons.onInfo?.let { add(BottomBarIcon(mi.info, Color.Unspecified, it, 28, legendKey = D.INFO)) }
    // 🌡️ parameters + 🎭 system prompt — paired config actions, kept
    // adjacent so they read as a couple wherever a screen exposes them.
    icons.onParameters?.let { add(BottomBarIcon(mi.parameters, Color.Unspecified, it, 28, legendKey = D.PARAMETERS)) }
    icons.onSystemPrompt?.let { add(BottomBarIcon(mi.systemPrompt, Color.Unspecified, it, 28, legendKey = D.SYSTEM_PROMPT)) }
    icons.onClear?.let { add(BottomBarIcon(mi.clear, Color.Unspecified, it, 28, legendKey = D.CLEAR)) }
    icons.onAttach?.let { add(BottomBarIcon(mi.attach, Color.Unspecified, it, 28, legendKey = D.ATTACH)) }
    // 🚩 validate prompt — grayed until the user activates it (picks a
    // moderation model), mirroring the 📌 pin alpha treatment.
    icons.onValidatePrompt?.let { add(BottomBarIcon(mi.validatePrompt, Color.Unspecified, it, 28, alpha = if (icons.validatePromptActive) 1f else 0.35f, legendKey = D.VALIDATE_PROMPT)) }
    // 👷 worker configuration — opens Report - select workers in edit mode.
    // treatment as 🚩 / 📌.
    icons.onWorkerConfig?.let { add(BottomBarIcon(mi.worker, Color.Unspecified, it, 28, legendKey = D.WORKER)) }
    icons.onCopy?.let { add(BottomBarIcon(mi.copy, Color.Unspecified, it, 28, legendKey = D.COPY)) }
    icons.onPin?.let { add(BottomBarIcon(mi.pin, Color.Unspecified, it, 28, alpha = if (icons.isPinned) 1f else 0.35f, legendKey = D.PIN)) }
    icons.onToggleModelRowLabels?.let {
        add(BottomBarIcon(mi.toggleLabels, Color.Unspecified, it, 28, alpha = if (icons.modelRowLabelsShowModelNames) 1f else 0.55f, legendKey = D.TOGGLE_LABELS))
    }
    if (!suppressShare) icons.onShare?.let { add(BottomBarIcon(mi.share, Color.Unspecified, it, 28, legendKey = D.SHARE)) }
    icons.onCopyReport?.let { add(BottomBarIcon(mi.duplicate, Color.Unspecified, it, 28, legendKey = D.DUPLICATE)) }
    // ----- second-row-ish: 👁 view leads the second row, the per-item
    // actions follow, and 🔄 regenerate sits just before 🗑 delete. -----
    icons.onOpenView?.let { add(BottomBarIcon(mi.view, Color.Unspecified, it, 32, fontSize = 18.sp, legendKey = D.VIEW)) }
    icons.onBatchWorkers?.let { add(BottomBarIcon(mi.ant, Color.Unspecified, it, 28, alpha = if (icons.batchWorkersActive) 1f else 0.35f, legendKey = D.ANT)) }
    icons.onJudgeJudges?.let { add(BottomBarIcon(mi.judges, Color.Unspecified, it, 28, legendKey = D.JUDGES)) }
    icons.onRankTranslators?.let { add(BottomBarIcon(mi.translatorRank, Color.Unspecified, it, 28, legendKey = D.TRANSLATOR_RANK)) }
    icons.onTranslationCompare?.let { add(BottomBarIcon(mi.translationCompare, Color.Unspecified, it, 28, legendKey = D.TRANSLATION_COMPARE)) }
    icons.onMemo?.let { add(BottomBarIcon(mi.memo, Color.Unspecified, it, 28, legendKey = D.MEMO)) }
    icons.onAddNote?.let { add(BottomBarIcon(mi.addNote, Color.Unspecified, it, 28, legendKey = D.ADD_NOTE)) }
    icons.onListNotes?.let { add(BottomBarIcon(mi.listNotes, Color.Unspecified, it, 28, alpha = if (icons.listNotesActive) 1f else 0.35f, legendKey = D.LIST_NOTES)) }
    icons.onEdit?.let { add(BottomBarIcon(mi.edit, Color.Unspecified, it, 28, legendKey = D.EDIT)) }
    // Reload slot — normally 🔄 reload; when swapped (Model response) the
    // 💬 chat glyph takes this late position instead.
    if (icons.swapChatAndReload) {
        icons.onChat?.let { add(BottomBarIcon(mi.chat, Color.Unspecified, it, 28, legendKey = D.CHAT)) }
    } else {
        icons.onReload?.let { add(BottomBarIcon(mi.reload, AppColors.WarningAccent, it, 28, legendKey = D.RELOAD)) }
    }
    icons.onDelete?.let { add(BottomBarIcon(mi.delete, AppColors.DangerAccent, it, 22, legendKey = D.DELETE)) }
    // 📈 statistics trailing the 🗑 delete, when the screen opted in
    // (Application log — its App-log-statistics jump sits after clear-all).
    if (icons.statsAfterDelete) icons.onStats?.let { add(BottomBarIcon(mi.statistics, Color.Unspecified, it, 28, legendKey = D.STATISTICS)) }
    if (!icons.addFirst) icons.onAdd?.let { add(BottomBarIcon(addGlyph, Color.Unspecified, it, 28, legendKey = addGlyph)) }
    // 🐞 trace hot-link sits last in the strip — hidden when the user turned
    // off "Show Ladybug icons" (traces are then reached from the API Traces
    // screen instead, via the Monitor-nav 🐞 which stays). The Monitor-section
    // jump group's 🐞 is separate and unaffected.
    if (includeScreenTrace && com.ai.data.ApiTracer.showLadybugIcons) {
        icons.onTrace?.let { add(BottomBarIcon(mi.traces, Color.Unspecified, it, 22, legendKey = D.TRACES)) }
    }
}

@Composable
fun HomeIconBar(
    icons: TitleBarIcons?,
    onReports: () -> Unit,
    onChat: () -> Unit,
    onMonitor: () -> Unit,
    onSetup: () -> Unit,
    onHousekeeping: () -> Unit,
    onSettings: () -> Unit,
    onTraceFallback: () -> Unit,
    onHelpFallback: () -> Unit,
    onAbout: () -> Unit,
    onAppLog: () -> Unit,
    /** When the "Full screen" setting hides the status bar, the bar uses the
     *  freed space by sitting at the very top edge. If there's a top camera
     *  punch-hole, the icons split into a left + right group with a gap over
     *  the hole's detected position (platform DisplayCutout, no hardcoding) so
     *  no icon hides behind the camera. No effect when the status bar is
     *  visible (the Scaffold already insets content below it). */
    fullScreen: Boolean = false,
    modifier: Modifier = Modifier
) {
    val mi = LocalMetadataIcons.current
    val traceAction = icons?.onTrace ?: onTraceFallback
    val helpAction = icons?.onHelp ?: onHelpFallback
    // App background (not the card tint). Equal-size icons spread across the
    // full width. Only renders in HOME_BAR mode.
    val w = 32.dp
    val h = 36
    val fs = 28.sp
    // One slot per icon — the About AI-logo LEADS, ❓ help trails. EVERY icon is
    // ALWAYS present so the bar's layout is fixed and nothing shifts as
    // per-screen actions publish during startup. 📤 share is NOT here — it lives
    // only in the bottom bar, exactly like Home Screen mode; 📊 Statistics took
    // its old slot. 📋 copy likewise stays only in the bottom bar.
    val slots: List<@Composable () -> Unit> = buildList {
        // Leading logo: height 44 (a little bigger), box WIDTH 32 hugs the
        // portrait glyph. Nudged up (y 0) and slightly right (x -2, was -4) so
        // there's a little less space after it (toward Reports).
        add { AiLogoButton(onClick = onAbout, modifier = Modifier.offset(x = (-2).dp, y = 0.dp), size = 44.dp, width = 32.dp, contentDescription = "About") }
        add { TitleBarIcon(mi.reportIcon, Color.Unspecified, onReports, width = w, heightDp = h, fontSize = fs, contentDescription = "Reports") }
        add { TitleBarIcon(mi.chat, Color.Unspecified, onChat, width = w, heightDp = h, fontSize = fs, contentDescription = "Chat") }
        add { TitleBarIcon(mi.liveDashboard, Color.Unspecified, onMonitor, width = w, heightDp = h, fontSize = fs, contentDescription = "Monitor") }
        add { TitleBarIcon(mi.housekeeping, Color.Unspecified, onHousekeeping, width = w, heightDp = h, fontSize = fs, contentDescription = "Housekeeping") }
        // 📜 Application log — sits before 🐞 Traces (the two were swapped).
        add { TitleBarIcon(mi.appLog, Color.Unspecified, onAppLog, width = w, heightDp = h, fontSize = fs, contentDescription = "Application log") }
        // Traces: always present; grayed + inert when the "Show Ladybug icons"
        // setting is off, active (→ this screen's traces or the list) when on.
        add {
            val tracesActive = com.ai.data.ApiTracer.ladybugLinksEnabled
            TitleBarIcon(mi.traces, Color.Unspecified, if (tracesActive) traceAction else ({}), width = w, heightDp = h, fontSize = fs,
                alpha = if (tracesActive) 1f else 0.35f, contentDescription = "API traces")
        }
        // AI Setup sits right before Settings.
        add { TitleBarIcon(mi.agent, Color.Unspecified, onSetup, width = w, heightDp = h, fontSize = fs, contentDescription = "AI setup") }
        add { TitleBarIcon(mi.settings, Color.Unspecified, onSettings, width = w, heightDp = h, fontSize = fs, contentDescription = "Settings") }
        // ❓ help (trailing): shifted left so there's MORE space after it
        // (before the right edge) and LESS space before it (next to Settings).
        add {
            androidx.compose.foundation.layout.Box(modifier = Modifier.offset(x = (-7).dp)) {
                TitleBarIcon(mi.help, AppColors.DangerAccent, helpAction, width = 26.dp, heightDp = h, fontSize = fs, contentDescription = "Help")
            }
        }
    }

    // Detect the top camera cutout (punch-hole) at runtime via the platform
    // DisplayCutout — no hardcoded position, so centre / corner / no-cutout
    // all work. Only consulted in full screen: otherwise the status bar
    // covers the hole and the Scaffold already insets content below it.
    // `cutoutTopPx` (a reactive Compose inset) is the recompute trigger so
    // the rect is picked up as soon as the window reports it.
    val density = LocalDensity.current
    val context = LocalContext.current
    val cutoutTopPx = WindowInsets.displayCutout.getTop(density)
    val holeRect: android.graphics.Rect? = remember(fullScreen, cutoutTopPx) {
        if (!fullScreen || cutoutTopPx <= 0) null
        else (context as? android.app.Activity)?.window?.decorView?.rootWindowInsets
            ?.displayCutout?.boundingRectTop?.takeIf { !it.isEmpty }
    }

    if (holeRect == null) {
        // Normal: one row spread across the full width.
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(AppColors.AppBackground)
                .padding(start = 8.dp, top = 5.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) { slots.forEach { it() } }
    } else {
        // Full screen WITH a top camera hole: sit at the very top edge (use
        // the freed space) and split the icons into a left + right group with
        // a gap exactly over the hole, so no icon hides behind the camera.
        // Each side gets a share of the icons proportional to its free width,
        // so a centred hole splits ~evenly and a corner hole keeps (almost)
        // every icon on the roomy side.
        BoxWithConstraints(
            modifier = modifier
                .fillMaxWidth()
                .background(AppColors.AppBackground)
                .padding(top = 5.dp, bottom = 4.dp)
        ) {
            val totalWpx = with(density) { maxWidth.toPx() }
            // The cutout bounding rect already carries its own safe area, so
            // no extra margin — icons sit right at the detected hole edges.
            val marginPx = 0f
            val leftWpx = (holeRect.left - marginPx).coerceAtLeast(0f)
            val rightWpx = (totalWpx - (holeRect.right + marginPx)).coerceAtLeast(0f)
            val gapWpx = (totalWpx - leftWpx - rightWpx).coerceAtLeast(0f)
            val n = slots.size
            // Split the icons proportionally to the free space on each side of
            // the hole (each slot is one visible icon). The gap sits over the
            // hole; each side spreads across its region.
            val nLeft = if (leftWpx + rightWpx <= 0f) n
                else (n * (leftWpx / (leftWpx + rightWpx))).roundToInt().coerceIn(0, n)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(with(density) { leftWpx.toDp() })) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) { slots.take(nLeft).forEach { it() } }
                }
                Spacer(Modifier.width(with(density) { gapWpx.toDp() }))
                Box(Modifier.width(with(density) { rightWpx.toDp() })) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) { slots.drop(nLeft).forEach { it() } }
                }
            }
        }
    }
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
            // Resolve a human label from the stable factory glyph (legendKey)
            // so a screen reader announces "Reload" rather than the 🔄 emoji.
            val desc = com.ai.ui.admin.DEFAULT_BAR_ICON_HELP[it.legendKey]?.first ?: it.emoji
            TitleBarIcon(it.emoji, it.tint, it.onClick, width = (cellWidthDp ?: it.widthDp).dp, heightDp = cellHeightDp, scale = scale, alpha = it.alpha, fontSize = it.fontSize, contentDescription = desc)
        }
    }
}

/**
 * The central clickable-emoji action button. Every compact icon action in the
 * app should go through this (or [TitleBarIcon], which delegates here) so the
 * glyph carries a screen-reader [contentDescription] and a Button role instead
 * of announcing the raw emoji. The emoji's own text node is cleared from the
 * semantics tree, so TalkBack speaks [contentDescription] and nothing else.
 */
@Composable
fun IconActionButton(
    emoji: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    width: Dp = 28.dp,
    heightDp: Int = 32,
    scale: Float = 1f,
    alpha: Float = 1f,
    fontSize: TextUnit = 16.sp
) {
    Box(
        modifier = modifier.size(width = width * scale, height = heightDp.dp * scale)
            .clickable(onClick = onClick)
            .alpha(alpha)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                this.role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji, fontSize = fontSize * scale,
            color = if (tint == Color.Unspecified) AppColors.TextPrimary else tint,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

/** Non-interactive labeled glyph — an emoji that conveys state (cooldown,
 *  blocked, …). Carries a [contentDescription] so screen readers announce the
 *  meaning instead of the raw emoji. */
@Composable
fun StatusIcon(
    emoji: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    fontSize: TextUnit = 16.sp
) {
    Text(
        text = emoji, fontSize = fontSize,
        color = if (tint == Color.Unspecified) AppColors.TextPrimary else tint,
        modifier = modifier.semantics { this.contentDescription = contentDescription }
    )
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
    fontSize: TextUnit = 16.sp,
    /** Screen-reader label. Defaults to the emoji when a caller has no
     *  better name; the bottom bar resolves the legend name from
     *  [com.ai.ui.admin.DEFAULT_BAR_ICON_HELP]. */
    contentDescription: String? = null
) = IconActionButton(
    emoji = emoji,
    contentDescription = contentDescription ?: emoji,
    onClick = onClick,
    tint = tint,
    width = width,
    heightDp = heightDp,
    scale = scale,
    alpha = alpha,
    fontSize = fontSize
)

/** Fixed-position bottom bar that mirrors the active TitleBar's
 *  action icons. The per-screen actions (chat / info / copy / share /
 *  reload / delete / trace / memo) sit here; the ❓ help glyph (when
 *  published via [TitleBarIcons.onHelp]) is pinned to the right. There
 *  is no visible back affordance — navigation back is the Android
 *  system back gesture, routed through each screen's [BackHandler].
 *  Icons render at a 1.25× scale by default, narrowing adaptively when
 *  the strip would otherwise overflow on a narrow screen. */
/** Screens whose white ❔ opens the live "<title> - icons" overlay instead
 *  of navigating to the icon-table help page. Covers the whole report-Manage
 *  family (the View family uses its own ViewBottomBar, so it's excluded
 *  automatically). Add a helpTopic here to roll the behaviour out to more
 *  screens. Also read by [com.ai.ui.admin.HelpScreen] to suppress the now-
 *  redundant icon-table help content on these screens. */
internal val LEGEND_OVERLAY_TOPICS = setOf(
    // Reports hub — its bottom bar carries the New / Search / All icons.
    "reports_hub",
    // Manage hub + its edit/create overlays and sub-editors.
    "report_run",
    "report_edit_overview", "report_edit_icons", "report_edit_titles",
    "report_create_overview", "report_get_info", "report_second_results",
    "report_edit_short_title", "report_edit_long_title", "report_edit_prompt",
    "report_find_alt_prompt",
    "report_edit_model_title", "report_edit_pair_title",
    // Meta / secondary creation + drill-ins.
    "report_meta", "report_meta_run", "report_fan_out_confirm", "secondary_scope",
    "secondary_list", "secondary_detail", "meta_detail",
    "secondary_fan_out_l1", "secondary_fan_out_l2", "secondary_fan_out_l3",
    "secondary_fan_out_onepage", "fan_meta", "fan_meta_workers",
    // Translation drill-ins.
    "translation_run_l1", "translation_run_l2", "translation_run_l3",
    "translation_workers",
    "translation_models", "alternative_translations",
    // Tournament drill-ins.
    "tournament_l1", "tournament_l2", "tournament_l3", "tournament_workers",
    // Judge-the-judges drill-ins.
    "judge_eval_l1", "judge_eval_l2", "judge_eval_l3", "judge_eval_match",
    // Find-alternative + icon-lookup detail screens.
    "alternative_icons", "alternative_titles",
    "icon_lookup_main", "icon_lookup_agent", "icon_lookup_meta",
    "icon_lookup_translation", "icon_lookup_language", "icon_lookup_pair",
    // Per-agent result / content / cost / misc manage screens.
    "report_single_result", "content_model_response", "content_one_page",
    "cost_view", "report_continue_in_chat", "regenerate_batch",
    // User notes.
    "report_notes",
    // In-report refine chat.
    "report_agent_chat",
)

@Composable
fun BottomIconBar(
    icons: TitleBarIcons?,
    modifier: Modifier = Modifier,
    suppressScreenTraceAndHelp: Boolean = false,
    /** Home-bar mode shows 📤 share in the persistent home bar; skip it here so
     *  it isn't shown twice. 📋 copy stays in the bottom bar in both modes. */
    suppressShare: Boolean = false
) {
    // Non-null on the non-View screens (regular TitleBar) — flips the
    // bar into the help layout: strip left-aligned, ❓ pinned right.
    val onHelp = icons?.onHelp
    val barIcons = LocalMetadataIcons.current
    val specs = if (icons != null) {
        buildBottomBarIcons(icons, barIcons, includeScreenTrace = !suppressScreenTraceAndHelp, suppressShare = suppressShare)
    } else {
        emptyList()
    }
    // The 1️⃣ 2️⃣ 3️⃣ switcher gets its OWN exclusive row above the action rows
    // (always 1-2-3, never sharing) — except when there are no action icons at
    // all (the Get-info layer), where it takes the single bottom row itself,
    // left of the right-pinned ❔/❓.
    val navSpecs = specs.filter { it.isNav }
    val actionSpecs = specs.filterNot { it.isNav }
    val navOwnRow = navSpecs.isNotEmpty() && actionSpecs.isNotEmpty()
    if (suppressScreenTraceAndHelp && specs.isEmpty()) {
        // No action icons to show — render a little breathing room instead of
        // nothing, so the screen's last item isn't flush against the bottom edge.
        Spacer(modifier = modifier.fillMaxWidth().height(24.dp))
        return
    }
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
    // FIXED icon size for every help-layout bar: each row on each screen
    // renders at exactly this scale — a crowded bar wraps to more rows
    // instead of shrinking its icons, so the glyphs are the same size
    // everywhere.
    val barIconScale = 2.1f

    androidx.compose.foundation.layout.BoxWithConstraints(
        // Bottom padding lifts the bar a touch above the gesture pill; a small
        // start inset keeps the first icon off the very left edge.
        modifier = modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp, bottom = 18.dp)
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

        // Help layout (every non-View screen). Icons render at the fixed
        // [barIconScale] size and fill each LEFT-aligned row with as many as
        // actually fit in the available width, wrapping to a new row as
        // needed; the ❓ help glyph is pinned to the right of the LAST row
        // (that row keeps room for it). A uniform per-icon cell width keeps
        // columns aligned vertically across rows.
        val helpW = 32f
        val helpGap = 4f
        // Second help glyph ❔ — a per-screen "what do these icons do?" page.
        // Shown just left of ❓ when this screen has its own "<topic>_icons"
        // help page AND more than 3 action icons (❓/❔ not counted).
        // (Suppressed on useLegend screens — the overlay's red ❓ replaces it.)
        val iconTopic = icons.helpTopic?.let { "${it}_icons" }
            ?.takeIf { com.ai.ui.admin.HELP_TOPICS.containsKey(it) }
        // White ❔ sits just left of the red ❓. On useLegend screens it opens
        // the live icon-legend overlay (shown whenever there's ≥1 icon); on
        // other screens it links to the static icon-table help page when the
        // bar is crowded (>3 icons), as before. The red ❓ always navigates to
        // the screen's help page.
        val showLegendHelp = useLegend
        val showIconPageHelp = !useLegend && iconTopic != null && actionSpecs.size > 3
        val showSecondHelp = showLegendHelp || showIconPageHelp
        val showScreenHelp = !suppressScreenTraceAndHelp
        val cell = 24                       // uniform column width (dp) — tight spacing
        // Wrap by WIDTH, not by a fixed per-row count: at the fixed
        // [barIconScale] each row takes as many icons as genuinely fit; the
        // LAST row reserves space for the right-pinned ❔/❓, and the
        // remainder (smallest) row goes on TOP so the full rows sit at the
        // bottom. The 1️⃣2️⃣3️⃣ switcher is handled separately (its own row
        // above) — except with no action icons at all, where it becomes the
        // single bottom row itself (1️⃣2️⃣3️⃣ left, ❔/❓ right).
        val scale = barIconScale
        val capacity = available / scale    // row width in unscaled dp
        val helpCount = (if (showSecondHelp) 1 else 0) + (if (showScreenHelp) 1 else 0)
        val helpReserve = if (helpCount > 0) helpGap + helpW * helpCount else 0f
        // Icons that fit in [cap] — at least 1, so a pathologically narrow
        // bar still renders rather than dividing the list by zero rows.
        fun fitCount(cap: Float) = ((cap + extraGap) / (cell + extraGap)).toInt().coerceAtLeast(1)
        val perFull = fitCount(capacity)
        val perLast = fitCount(capacity - helpReserve)
        val rows = when {
            actionSpecs.isEmpty() -> listOf(navSpecs)
            actionSpecs.size <= perLast -> listOf(actionSpecs)
            else -> {
                val above = actionSpecs.dropLast(perLast)
                val rem = above.size % perFull
                (if (rem == 0) above.chunked(perFull)
                else listOf(above.take(rem)) + above.drop(rem).chunked(perFull)) +
                    listOf(actionSpecs.takeLast(perLast))
            }
        }
        // Tighter per-row cell height when wrapped so the rows sit close
        // together vertically; full height for a single row. The nav row
        // counts as a visual row.
        val totalVisualRows = (if (navOwnRow) 1 else 0) + rows.size
        val rowCellH = if (totalVisualRows > 1) 22 else 32
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            // Dedicated 1️⃣2️⃣3️⃣ switcher row — its own row above the action
            // rows, always in 1-2-3 order, never sharing with any other icon.
            if (navOwnRow) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BottomBarIconRow(navSpecs, scale, extraGap.dp, cellWidthDp = cell, cellHeightDp = rowCellH)
                }
            }
            rows.forEachIndexed { i, rowSpecs ->
                val isLast = i == rows.lastIndex
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BottomBarIconRow(rowSpecs, scale, extraGap.dp, cellWidthDp = cell, cellHeightDp = rowCellH)
                    Spacer(modifier = Modifier.weight(1f))
                    if (isLast) {
                        if (showLegendHelp) {
                            // White ❔ → live "<screen> - icons" overlay.
                            TitleBarIcon(barIcons.helpLegend, AppColors.InfoAccent, { showLegend = true }, width = 18.dp, heightDp = rowCellH, scale = scale, contentDescription = "Icon legend")
                        } else if (showIconPageHelp) {
                            // White ❔ → static icon-table help page.
                            TitleBarIcon(barIcons.helpLegend, AppColors.InfoAccent, { navigateHelp(iconTopic) }, width = 18.dp, heightDp = rowCellH, scale = scale, contentDescription = "Icon help")
                        }
                        // Red ❓ → the screen's help page. Home bar mode
                        // moves this action to the persistent top bar.
                        if (showScreenHelp) {
                            TitleBarIcon(barIcons.help, AppColors.InfoAccent, onHelp, width = 18.dp, heightDp = rowCellH, scale = scale, contentDescription = "Help")
                        }
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
            mi = barIcons,
            navigateHelp = navigateHelp,
            onClose = { showLegend = false }
        )
    }
}

/** Full-screen "<title> - icons" overlay opened by the white ❓ on
 *  allowlisted screens ([LEGEND_OVERLAY_TOPICS]). Lists the bar icons
 *  currently visible — big glyph + name + a short description (from
 *  [com.ai.ui.admin.SCREEN_ICON_HELP]) — and re-fires each icon's action on
 *  tap. Its own single icon is a red ❓ that opens the screen's full
 *  icon-table help page (all possible icons + descriptions). */
@Composable
private fun IconLegendOverlay(
    icons: TitleBarIcons,
    specs: List<BottomBarIcon>,
    mi: com.ai.data.MetadataIcons,
    navigateHelp: (String?) -> Unit,
    onClose: () -> Unit
) {
    // emoji → (name, short description). Prefer the screen's own legend;
    // fall back to the generic per-glyph legend so legend-less screens still
    // show a name + description for the standard bar icons.
    val legend = icons.helpTopic
        ?.let { com.ai.ui.admin.SCREEN_ICON_HELP[it] }
        ?.associate { it.first to (it.second to it.third) }
        ?: emptyMap()
    val header = icons.title?.takeIf { it.isNotBlank() }?.let { "$it - icons" } ?: "Icons"
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = AppColors.AppBackground) {
            Column(modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)) {
                Text(
                    header, color = AppColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold,
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
                                    color = if (spec.tint == Color.Unspecified) AppColors.TextPrimary else spec.tint
                                )
                            }
                            // Key the legend label off the stable factory glyph
                            // (legendKey), not the live one — so a user-overridden
                            // glyph still resolves its name + description.
                            val entry = legend[spec.legendKey] ?: com.ai.ui.admin.DEFAULT_BAR_ICON_HELP[spec.legendKey]
                            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                // No maxLines / ellipsis — let the name and
                                // description wrap to as many lines as they need
                                // rather than cutting off with "…".
                                Text(
                                    entry?.first ?: spec.emoji,
                                    color = AppColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                                )
                                entry?.second?.takeIf { it.isNotBlank() }?.let { desc ->
                                    Text(
                                        desc, color = AppColors.TextTertiary, fontSize = 13.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = AppColors.DividerDark)
                    }
                    // The white ❔ (this list) and red ❓ (screen help) bottom-bar
                    // glyphs are intentionally NOT listed here — a legend
                    // shouldn't carry an entry for the icon that opens it.
                }
                // The overlay's own icons bar: just a red ❓ → the full icon table.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 18.dp, end = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Red ❓ → this screen's main help page (the live overlay
                    // already covers the icons, so it no longer points to the
                    // standalone icon-table page). Match the bottom bar's help
                    // glyph size (scales to the 2.1× ceiling) so it isn't tiny.
                    TitleBarIcon(mi.help, AppColors.DangerAccent, {
                        onClose()
                        navigateHelp(icons.helpTopic)
                    }, width = 18.dp, heightDp = 32, scale = 2.1f, contentDescription = "Help")
                }
            }
        }
    }
}
