package com.ai.ui.report.manage
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*
import com.ai.ui.shared.AppColors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import com.ai.data.AppService
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AnalysisResponse
import com.ai.data.ReportAgent
import com.ai.data.ReportDataVersion
import com.ai.data.ReportStorage
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.data.UserNote
import com.ai.data.notesFor
import androidx.compose.runtime.collectAsState
import com.ai.model.ReportModel
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.ReportViewModel
import com.ai.viewmodel.TranslationRunState
import com.ai.viewmodel.UiState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Post-Generate page in the report flow — the per-report manage
 *  view. Shows per-agent rows, the Action row (View / Edit /
 *  Regenerate / Export / Translate / Meta / Fan out), the running
 *  cost subject row, the regenerate-confirm dialog, and every
 *  surface that hangs off an already-fired report.
 *
 *  Sibling of [ReportSelectModelsScreen]; the dispatch between the
 *  two lives in [ReportScreen] and keys on `isGenerating`. */
@Composable
internal fun ReportRunScreen(
    uiState: UiState,
    isComplete: Boolean,
    reportsProgress: Int,
    reportsTotal: Int,
    reportsAgentResults: Map<String, AnalysisResponse>,
    currentReportId: String?,
    iconGenEnabled: Boolean,
    showRegenerateConfirm: Boolean,
    models: List<ReportModel>,
    /** Manage screen state — read for the "Report - Get info" layer
     *  (st.showGetInfo) drawn on top of this hub, and to set the
     *  icon / title detail flags its rows open. */
    st: ReportsScreenState,
    generationHandlers: GenerationPhaseHandlers,
    secondaryCounts: SecondaryResultStorage.Counts,
    costsFromDeletedItems: Double,
    secondaryRuns: List<SecondaryResult>,
    translateRows: List<SecondaryResult>,
    secondaryTotals: SecondaryTotals,
    translationRuns: List<TranslationRunState>,
    translationRunSummaries: List<TranslationRunSummary>,
    fanOutSummaries: List<FanOutRunSummary>,
    loaded: Boolean = false,
    reportIcon: String?,
    reportIconError: String?,
    reportIconCost: Double,
    reportIconModel: String?,
    languageIconCost: Double,
    languageDetectCost: Double,
    languageName: String?,
    languageIcon: String?,
    agentIconRows: Map<String, AgentIconRow>,
    agentModelTitles: Map<String, AgentModelTitle> = emptyMap(),
    agentRecordsByAgentId: Map<String, ReportAgent> = emptyMap(),
    infoEnabled: Boolean = false,
    infoState: InfoJobState = InfoJobState.DONE,
    infoMetaTotal: Double = 0.0,
    secondEnabled: Boolean = false,
    secondState: InfoJobState = InfoJobState.DONE,
    secondTotal: Double = 0.0,
    /** Combined main-response cost of every model — the "report"
     *  cross-link row's cost on Get-info / second-results. */
    mainResponseTotal: Double = 0.0,
    hasPrevReport: Boolean,
    hasNextReport: Boolean,
    onDismiss: () -> Unit,
    onOpenViewReport: () -> Unit,
    onRequestRegenerate: () -> Unit,
    onDismissRegenerateConfirm: () -> Unit,
    onRegenerate: (String) -> Unit,
    /** Metadata-only regenerate — used by the 🔄 while the Get-info
     *  layer is open (re-runs the page's icon/title/language jobs). */
    onRegenerateInfo: (String) -> Unit = {},
    /** "Restart errors" on Get-info — re-fire only the errored info jobs. */
    onRestartInfoErrors: (String) -> Unit = {},
    /** Report-level info jobs whose call is actively in flight. */
    runningInfoJobs: Set<String> = emptySet(),
    onChatWithReportPrompt: (String) -> Unit,
    /** Launch a worker-judged pairwise tournament on the report (reportId,
     *  build-stage key). */
    onRunTournament: (String, String?, List<com.ai.model.Worker>?) -> Unit = { _, _, _ -> },
    /** Launch the "Judge the judges" batch on the report. */
    onRunJudgeJudges: (String, String?, List<com.ai.model.Worker>?) -> Unit = { _, _, _ -> },
    /** Arm the build-stage popup: (buildKey, label, on-done nav, on-cancel). */
    onArmBuildStage: (String, String, () -> Unit, () -> Unit) -> Unit = { _, _, _, _ -> },
    /** Delete a tournament / judges run (build-stage Cancel cleanup). */
    onDeleteTournamentRun: (String) -> Unit = { },
    onDeleteJudgeRun: (String) -> Unit = { },
    /** Pending 🏅 rank-the-translators launch → the shared confirm dialog.
     *  Hoisted to ReportsScreen: the runtime worker picker's early-return
     *  overlay unmounts this composable, so state remembered here would
     *  lose the pick (the Main.kt translation-run site already hoists its
     *  sibling rankPending the same way). */
    pendingRank: androidx.compose.runtime.MutableState<com.ai.ui.report.manage.PendingRankRequest?> =
        androidx.compose.runtime.mutableStateOf(null)
) {
    val aiSettings = uiState.aiSettings
    val context = LocalContext.current
    // Tournament is started from the Create launcher with just a confirm
    // dialog showing the match count — judging runs on the worker engine,
    // so there is no judge model to pick.
    var confirmTournament by rememberSaveable { mutableStateOf(false) }
    var confirmJudgeJudges by rememberSaveable { mutableStateOf(false) }
    var showTournamentOverview by rememberSaveable { mutableStateOf(false) }
    // 🌐 Translate icon → when the report already has translations, show the
    // Translations list (drill into a run, or 🆕 a new one); with none, the
    // icon opens the create flow directly.
    var showTranslationsList by rememberSaveable { mutableStateOf(false) }
    // Open-state for the Tournament L1 overlay — set on Run so the user lands
    // on the batch screen immediately instead of staying on Manage.
    val tournamentOpenState = com.ai.ui.shared.LocalTournamentOpenState.current
    val judgeEvalOpenState = com.ai.ui.shared.LocalJudgeEvalOpenState.current
    // "Report - second results" layer flag — survivable (held in ReportsScreenNav).
    val showSecondResults = com.ai.ui.shared.LocalShowSecondResults.current
    // Title-tap cycles the three report screens, wrapping with no edge:
    // Manage → Get-info → second results → Manage.
    val cycleReportScreens: () -> Unit = {
        when {
            showSecondResults?.value == true -> showSecondResults.value = false // second → Manage
            st.showGetInfo.value -> {                                           // Get-info → second
                st.showGetInfo.value = false
                showSecondResults?.value = true
            }
            else -> st.showGetInfo.value = true                                 // Manage → Get-info
        }
    }
    // Which of the three report screens is on top right now. The hub is the
    // sole bottom-bar publisher (the Get-info / second-results overlays keep
    // publishBottomBar=false and draw only their top chrome), so it publishes
    // a DIFFERENT icon set per layer: 1 = Manage, 2 = Get-info, 3 = second
    // results. Read here so the hub recomposes + re-publishes on layer change.
    val activeReportLayer = when {
        showSecondResults?.value == true -> 3
        st.showGetInfo.value -> 2
        else -> 1
    }
    // View/Edit/Delete/Regenerate/Copy/Pin/Export + the kept extras live on
    // Manage only; the six secondary launchers live on second-results only.
    val manageLayer = activeReportLayer == 1
    val secondLayer = activeReportLayer == 3
    // Direct jumps for the 1️⃣ 2️⃣ 3️⃣ switcher (cycleReportScreens only steps
    // forward). Each clears the others so exactly one layer is shown.
    val goManageScreen: () -> Unit = { st.showGetInfo.value = false; showSecondResults?.value = false }
    val goGetInfoScreen: () -> Unit = { showSecondResults?.value = false; st.showGetInfo.value = true }
    val goSecondScreen: () -> Unit = { st.showGetInfo.value = false; showSecondResults?.value = true }
    val tournamentResponseCount = reportsAgentResults.values.count { it.error == null && !it.analysis.isNullOrBlank() }
    // "Compare with meta" — two-page selection flow (meta items → prompt) then
    // the worker-judged grid. compareStep: 0 = none, 1 = select meta, 2 = select
    // prompt. Enabled only when the report has plain-meta prose to compare
    // against and at least one successful answer.
    val compareEngine = com.ai.ui.shared.LocalCompareEngine.current
    val compareOpenState = com.ai.ui.shared.LocalCompareOpenState.current
    // 🏅 Rank the translators — engine + open-state (run key "$reportId|$translationRunId").
    val translatorRankEngine = com.ai.ui.shared.LocalTranslatorRankEngine.current
    val transRankOpenState = com.ai.ui.shared.LocalTransRankOpenState.current
    // Pending 🏅 launch (translationRunId, lang, native) → shared confirm
    // dialog. Hoisted into ReportsScreen (the pendingRank param) because the
    // runtime worker picker's early-return overlay unmounts THIS composable —
    // a rememberSaveable here would re-initialise to null and lose the pick.
    // Compare runs the meta-compare prompt with the SAME NAME as the meta item,
    // so only show meta items that actually have a matching meta-compare prompt;
    // the rest can't be compared.
    val metaCompareNames = aiSettings.internalPrompts
        .filter { it.category == "meta_compare" }
        .mapTo(HashSet()) { it.name.lowercase() }
    val compareMetaItems = secondaryRuns.filter {
        it.kind == com.ai.data.SecondaryKind.META &&
            it.fanInOf == null && it.fanOutSourceAgentId == null &&
            !it.content.isNullOrBlank() &&
            it.metaPromptName?.lowercase() in metaCompareNames
    }
    val compareEnabled = compareMetaItems.isNotEmpty() && tournamentResponseCount >= 1
    var compareStep by rememberSaveable { mutableStateOf(0) }
    val navigateToReportInfo = com.ai.ui.shared.LocalNavigateToReportInfo.current
    val navigateToNewReport = com.ai.ui.shared.LocalNavigateToNewReport.current
    // Bumped every time the user taps the bottom-bar 📌 icon so the
    // isPinned produceState re-reads from disk and the 📌 tint flips
    // immediately (orange when pinned). Keyed on currentReportId so
    // switching reports also reseeds the read.
    var pinTick by remember(currentReportId) { mutableStateOf(0) }
    val isPinned by produceState(initialValue = false, currentReportId, pinTick) {
        value = currentReportId?.let { rid ->
            withContext(Dispatchers.IO) { ReportStorage.getReport(context, rid)?.pinned == true }
        } ?: false
    }
    // Per-report worker config for the Manage 👷 edit overlay — same
    // disk-read + tick pattern as isPinned, but NULL until the read lands
    // so the overlay can't open on (and Save can't persist) the default
    // config during the cold window. The launch sites don't read this —
    // launchWithWorkerPlan reads the config fresh from disk per launch.
    var workerConfigTick by remember(currentReportId) { mutableStateOf(0) }
    val workerCfg by produceState<com.ai.data.ReportWorkerConfig?>(initialValue = null, currentReportId, workerConfigTick) {
        value = currentReportId?.let { rid ->
            withContext(Dispatchers.IO) { ReportStorage.getReport(context, rid)?.workerConfig ?: com.ai.data.ReportWorkerConfig() }
        }
    }
    // 🏅 handler: open an existing rank run for this translation, else confirm-start.
    // When a picker applies, the runtime worker pick runs BEFORE the confirm so
    // the dialog's call count matches the chosen judges (audit bug 6).
    val onRankMedal: (String, String, String) -> Unit = handler@{ runId, ln, lnn ->
        val rid = currentReportId ?: return@handler
        val key = com.ai.data.transRankRunKey(rid, runId)
        if (translatorRankEngine?.runByKey(key) != null) { transRankOpenState?.value = key; return@handler }
        launchWithWorkerPlan(
            st.runtimeWorkerPick, context, st.screenScope, rid,
            aiSettings.workerPromptByName("translate-rank"),
            "Rank translators — pick workers"
        ) { picked -> pendingRank.value = com.ai.ui.report.manage.PendingRankRequest(runId, ln, lnn, picked) }
    }
    // The select callback is pulled from LocalSystemPromptChange so we
    // don't thread it through the call site as another arg.
    val systemPromptChange = com.ai.ui.shared.LocalSystemPromptChange.current
    // Per-report system-prompt picker — opens the full-screen
    // "Define AI model system prompt" overlay. The early return keeps
    // this screen's remember state underneath.
    var showEditSystemPrompt by rememberSaveable { mutableStateOf(false) }
    val editSystemPromptTrigger: () -> Unit = { showEditSystemPrompt = true }
    if (showEditSystemPrompt) {
        com.ai.ui.shared.SystemPromptSelectScreen(
            aiSettings = aiSettings,
            selectedId = uiState.reportSystemPromptId,
            onSelect = systemPromptChange,
            onBack = { showEditSystemPrompt = false }, onNavigateHome = onDismiss
        )
        return
    }
    // 👷 "Report - select workers" re-edit — the same screen the pre-generation
    // flow shows, without the Generate button. Draft-edit semantics: Save
    // persists + bumps the tick; back discards the draft. Early return keeps
    // this hub's remember state underneath (overlay back-stack rule). Gated
    // on the loaded config (workerCfg null while the disk read is in flight)
    // so a Save can never overwrite the stored config with cold defaults.
    var showWorkerConfig by rememberSaveable(currentReportId) { mutableStateOf(false) }
    val loadedWorkerCfg = workerCfg
    if (showWorkerConfig && currentReportId != null && loadedWorkerCfg != null) {
        var workerConfigDraft by remember(loadedWorkerCfg) { mutableStateOf(loadedWorkerCfg) }
        com.ai.ui.report.start.ReportSelectWorkersScreen(
            aiSettings = aiSettings,
            config = workerConfigDraft,
            onConfigChange = { workerConfigDraft = it },
            onGenerate = null,
            onSave = {
                val rid = currentReportId
                val draft = workerConfigDraft
                st.screenScope.launch(Dispatchers.IO) {
                    ReportStorage.setWorkerConfig(context, rid, draft)
                    withContext(Dispatchers.Main) { workerConfigTick++ }
                }
                showWorkerConfig = false
            },
            onDismiss = { showWorkerConfig = false }
        )
        return
    }
    // "Compare with meta" selection flow — a single full-screen overlay layered
    // over the still-remembered hub state (early return preserves the values
    // declared above). Pick a meta item → Compare runs the same-named
    // meta-compare prompt and lands on the L1 grid (no prompt picker).
    if (compareStep == 1 && currentReportId != null) {
        CompareSelectMetaScreen(
            metaItems = compareMetaItems,
            aiSettings = aiSettings,
            onPick = { id ->
                val rid = currentReportId
                // No prompt picker — Compare always uses the meta-compare prompt
                // with the SAME NAME as the picked meta item. compareMetaItems
                // only lists items that have one, so this resolves.
                val comparePrompt = compareMetaItems.firstOrNull { it.id == id }?.metaPromptName?.let { name ->
                    aiSettings.internalPrompts.firstOrNull { it.category == "meta_compare" && it.name.equals(name, ignoreCase = true) }
                }
                if (comparePrompt != null) {
                    // Build stage: block behind "Preparing…" while the cell grid
                    // is created, then open the L1 once done.
                    val key = java.util.UUID.randomUUID().toString()
                    val arm = { ws: List<com.ai.model.Worker>? ->
                        onArmBuildStage(key, "Building compare", { compareOpenState?.value = rid }, { compareEngine?.deleteRun(context, rid) })
                        compareEngine?.startRun(context, rid, listOf(id), comparePrompt.id, key, ws)
                    }
                    launchWithWorkerPlan(
                        st.runtimeWorkerPick, context, st.screenScope, rid,
                        comparePrompt, "Compare — pick workers"
                    ) { picked -> arm(picked) }
                }
                compareStep = 0
            },
            onBack = { compareStep = 0 },
            onNavigateHome = onDismiss
        )
        return
    }
    // ✍️ / 📒 user notes. 📒 opens the all-notes list (overlay); ✍️ opens
    // the editor for a REPORT-level note. Both are early-return overlays so
    // this hub's remember state survives the round-trip.
    var showNotesList by rememberSaveable { mutableStateOf(false) }
    if (showNotesList && currentReportId != null) {
        ReportNotesListScreen(reportId = currentReportId, onBack = { showNotesList = false })
        return
    }
    var noteEdit by remember { mutableStateOf<NoteEdit?>(null) }
    if (noteEdit != null && currentReportId != null) {
        UserNoteEditorOverlay(currentReportId, "REPORT", currentReportId, noteEdit!!) { noteEdit = null }
        return
    }
    val noteDataVersion by ReportDataVersion.versionFor(currentReportId).collectAsState()
    val reportNotes by produceState(emptyList<UserNote>(), currentReportId, noteDataVersion) {
        value = currentReportId?.let { rid ->
            withContext(Dispatchers.IO) { ReportStorage.getReport(context, rid)?.notesFor("REPORT", rid) ?: emptyList() }
        } ?: emptyList()
    }
    // 👯 duplicate-report tap shows a yes/no first so an accidental
    // hit on the bottom bar doesn't silently spawn a "(Copy)" report.
    var showCopyConfirm by rememberSaveable(currentReportId) { mutableStateOf(false) }
    if (showCopyConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCopyConfirm = false },
            title = { androidx.compose.material3.Text("Duplicate report?") },
            text = {
                androidx.compose.material3.Text(
                    "Make a copy of this report — same prompt, agents, parameters, and every existing response. The copy opens immediately; the original stays put."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showCopyConfirm = false; generationHandlers.onCopy() }
                ) { androidx.compose.material3.Text("Duplicate", color = com.ai.ui.shared.AppColors.InfoAccent) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showCopyConfirm = false }
                ) { androidx.compose.material3.Text("Cancel") }
            }
        )
    }
    // Whole-screen prev/next-report swipe — extends the title-bar
    // swipe from [com.ai.ui.shared.TitleBar] to the entire Manage
    // surface. Reuses the same locals (newest-first id list +
    // current id + switch handler), the same 80.dp threshold, and
    // the same "Loading report" / "No more reports" pill. The pill
    // is rendered at the same TopCenter Y as the title-bar's pill
    // so the visual feedback is identical no matter where the
    // gesture started. Gestures that begin *inside* the title-bar
    // row never reach this column-level detector — the bar's own
    // pointerInput child consumes them first.
    val swipeCtx = LocalContext.current
    val swipeIds = com.ai.ui.shared.LocalReportIdsNewestFirst.current
    val swipeReportId = com.ai.ui.shared.LocalCurrentReportIdForSwipe.current
    val swipeSwitch = com.ai.ui.shared.LocalReportSwitchHandler.current
    val swipePopToManage = com.ai.ui.shared.LocalNavigateToCurrentReport.current
    val bodySwipeReady = swipeReportId != null && swipeIds.isNotEmpty() && swipeSwitch != null
    val bodySwipeStatus = remember { mutableStateOf<String?>(null) }
    val bodyStatusTick = remember { mutableIntStateOf(0) }
    LaunchedEffect(bodyStatusTick.intValue) {
        if (bodySwipeStatus.value != null) {
            kotlinx.coroutines.delay(1000)
            bodySwipeStatus.value = null
        }
    }
    val bodyThresholdPx = with(LocalDensity.current) { 80.dp.toPx() }
    val bodyDragX = remember { mutableFloatStateOf(0f) }
    val triggerBodySwipe: (SwipeDirection) -> Unit = { dir ->
        val match = findSwipeMatch(
            swipeCtx, swipeIds, swipeReportId!!,
            dir, ViewSwipeFilter.Any
        )
        if (match == null) {
            bodySwipeStatus.value = "No more reports"
            bodyStatusTick.intValue++
        } else {
            swipePopToManage?.invoke()
            swipeSwitch!!(match.reportId)
        }
    }
    // Running total cost, reported up from GenerationPhase, shown in the
    // statistics line under the title bar. Hoisted to this scope so the
    // Get-info / second-results overlay layers (mounted outside the hub
    // Column below) can render the same live number in their stats lines.
    var totalCostForBar by remember { mutableStateOf(0.0) }
    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .then(
                if (bodySwipeReady) {
                    Modifier.pointerInput(swipeReportId, swipeIds, swipeSwitch) {
                        detectHorizontalDragGestures(
                            onDragStart = { bodyDragX.floatValue = 0f },
                            onDragEnd = {
                                val dx = bodyDragX.floatValue
                                when {
                                    dx > bodyThresholdPx -> triggerBodySwipe(SwipeDirection.Prev)
                                    dx < -bodyThresholdPx -> triggerBodySwipe(SwipeDirection.Next)
                                }
                                bodyDragX.floatValue = 0f
                            },
                            onDragCancel = { bodyDragX.floatValue = 0f },
                            onHorizontalDrag = { _, d -> bodyDragX.floatValue += d }
                        )
                    }
                } else Modifier
            )
    ) {
        val promptTitle = uiState.genericPromptTitle
        // Orange line shows the AI long title when present, else the short
        // working title. The short title still drives editing / saving.
        val promptTitleForBar = uiState.genericPromptTitleLong.ifBlank { promptTitle }
        // Main Manage screen: the report icon, the "Manage report" screen
        // title and the green report-name in GenerationPhase all open the
        // View hub (same target as the bottom-bar 👁).
        var showModelNamesInReportRows by rememberSaveable(currentReportId) { mutableStateOf(false) }
        // 🗂️ pick-another-report on the Manage hub → the unfiltered picker,
        // returning to the hub for the chosen report. Provided only around
        // the TitleBar so the auto-captured bottom-bar icon appears here.
        val managePick = com.ai.ui.shared.LocalNavigateToManagePicker.current
        androidx.compose.runtime.CompositionLocalProvider(
            // 🗂️ pick-report is a Manage-only extra; null on the Get-info /
            // second-results layers so it doesn't surface there.
            com.ai.ui.shared.LocalManagePickReport provides
                (if (manageLayer) ({ managePick(com.ai.ui.navigation.ManagePickKind.MANAGE.arg) }) else null)
        ) {
        TitleBar(
            // Help / icon-legend follow the on-screen layer (the hub publishes
            // the bar for all three).
            helpTopic = when (activeReportLayer) {
                2 -> "report_get_info"
                3 -> "report_second_results"
                else -> "report_run"
            },
            // While the Translations layer is open it owns the bottom bar (just
            // 🆕). The hub stays composed underneath and recomposes on every
            // live update, so without this gate its SideEffect re-publishes the
            // full Manage action set and clobbers the Translations bar.
            publishBottomBar = !showTranslationsList,
            title = "Report - manage",
            // Tapping the title / orange report title cycles to the next of
            // the three report screens (Manage → Get-info → second results).
            // The report icon (below) keeps going to the View hub.
            onTitleClick = cycleReportScreens,
            forceTitleClick = true,
            subject = promptTitleForBar,
            reportIcon = if (iconGenEnabled) reportIcon?.takeIf { it.isNotEmpty() } ?: com.ai.data.MetadataIconsHolder.current.reportIcon else null,
            // On the Manage report screen the report icon opens the main
            // View hub ("View a report") — same target as the green
            // report-name line and the bottom-bar 👁.
            onReportIconClick = onOpenViewReport,
            // ℹ️ → the standalone "Report information" screen (real route).
            // Read from a CompositionLocal rather than a threaded arg —
            // ReportsScreen is at the JVM 64 KB method ceiling.
            // View / Edit / Delete / Regenerate / Copy / Pin / Export + the
            // kept extras (Chat / Info / Trace / row-labels) are Manage-only.
            onInfo = if (manageLayer) currentReportId?.let { rid -> { navigateToReportInfo(rid) } } else null,
            // 🆕 → the "New Report" start hub (New report / previous report /
            // example prompt) — Manage layer only.
            onNewReport = if (manageLayer) navigateToNewReport else null,
            onBackClick = onDismiss,
            onReload = if (manageLayer && currentReportId != null && isComplete) onRequestRegenerate else null,
            onTrace = if (manageLayer && currentReportId != null) generationHandlers.onTrace else null,
            onDelete = if (manageLayer && currentReportId != null) generationHandlers.onDelete else null,
            onOpenView = if (manageLayer && currentReportId != null) onOpenViewReport else null,
            onChat = if (manageLayer && uiState.genericPromptText.isNotBlank()) {
                { onChatWithReportPrompt(uiState.genericPromptText) }
            } else null,
            onShare = if (manageLayer && currentReportId != null && isComplete) generationHandlers.onRequestExport else null,
            onCopyReport = if (manageLayer && currentReportId != null) {
                { showCopyConfirm = true }
            } else null,
            onPin = if (manageLayer && currentReportId != null) {
                { generationHandlers.onTogglePin(); pinTick++ }
            } else null,
            isPinned = isPinned,
            onToggleModelRowLabels = if (manageLayer && currentReportId != null) {
                { showModelNamesInReportRows = !showModelNamesInReportRows }
            } else null,
            modelRowLabelsShowModelNames = showModelNamesInReportRows,
            onFanOut = if (secondLayer && currentReportId != null) {
                {
                    st.showCreateOverview.value = false
                    showTournamentOverview = false
                    val existingFanOut = fanOutSummaries.firstOrNull()
                    if (existingFanOut != null) {
                        generationHandlers.onViewSecondaryName(
                            existingFanOut.metaPromptName,
                            existingFanOut.kind
                        )
                    } else {
                        generationHandlers.onOpenFanOutPicker()
                    }
                }
            } else null,
            fanOutIcon = com.ai.ui.shared.LocalMetadataIcons.current.fanOutRow
                .takeIf { it.isNotBlank() }
                ?: com.ai.data.MetadataDefaults.FAN_OUT,
            onTournament = if (secondLayer && currentReportId != null) {
                {
                    st.showCreateOverview.value = false
                    showTournamentOverview = true
                }
            } else null,
            tournamentIcon = com.ai.ui.shared.LocalMetadataIcons.current.tournament
                .takeIf { it.isNotBlank() }
                ?: com.ai.data.MetadataDefaults.TOURNAMENT,
            // 🌐 Translate — always open the Translations list (the Original
            // row + any existing translations); its 🆕 starts the create flow
            // (language→model picker → its own run screen). Shown even with no
            // translations yet, so the page is always reachable.
            onTranslate = if (secondLayer && currentReportId != null) {
                {
                    st.showCreateOverview.value = false
                    showTournamentOverview = false
                    showTranslationsList = true
                }
            } else null,
            translateIcon = com.ai.ui.shared.LocalMetadataIcons.current.translationRow
                .takeIf { it.isNotBlank() }
                ?: com.ai.data.MetadataDefaults.TRANSLATE,
            // 🏆 Rerank — single-shot: if one exists, jump straight to its
            // detail; otherwise start it (the picker → run then surfaces the
            // "Report - second results" screen, where the new row appears).
            onRerank = if (secondLayer && currentReportId != null) {
                {
                    val existing = secondaryRuns.firstOrNull { it.kind == com.ai.data.SecondaryKind.RERANK }
                    if (existing != null) {
                        st.openMetaResultId.value = existing.id
                    } else {
                        generationHandlers.onOpenRerankPicker()
                    }
                }
            } else null,
            rerankIcon = com.ai.ui.shared.LocalMetadataIcons.current.rerank
                .takeIf { it.isNotBlank() }
                ?: com.ai.data.MetadataDefaults.RERANK,
            // 🚦 Moderation — same single-shot behaviour as rerank.
            onModeration = if (secondLayer && currentReportId != null) {
                {
                    val existing = secondaryRuns.firstOrNull { it.kind == com.ai.data.SecondaryKind.MODERATION }
                    if (existing != null) {
                        st.openMetaResultId.value = existing.id
                    } else {
                        generationHandlers.onOpenModerationPicker()
                    }
                }
            } else null,
            moderationIcon = com.ai.ui.shared.LocalMetadataIcons.current.moderate
                .takeIf { it.isNotBlank() }
                ?: com.ai.data.MetadataDefaults.MODERATE,
            // ✏️ opens the full-screen "Edit report" overview (Manage only).
            onEdit = if (manageLayer) { { st.showEditReportOverview.value = true } } else null,
            // 🔗 the full-screen "Meta" launcher (Meta + Compare with meta) —
            // a secondary launcher, so it lives on the second-results layer.
            // Glyph = the user's Meta default icon (Settings → Default icons).
            onAdd = if (secondLayer) { { st.showCreateOverview.value = true } } else null,
            addIcon = com.ai.ui.shared.LocalMetadataIcons.current.meta,
            addFirst = true,
            // 📒 open the all-notes list (Manage only), greyed when the report
            // has no notes yet — still clickable so the User-notes screen (which
            // hosts ✍️ Add note) stays reachable. ✍️ itself moved there.
            onListNotes = if (manageLayer && currentReportId != null) { { showNotesList = true } } else null,
            listNotesActive = reportNotes.isNotEmpty(),
            // 1️⃣ 2️⃣ 3️⃣ switcher — leads the bar on all three report screens.
            screenNav = currentReportId?.let {
                com.ai.ui.shared.ReportScreenNav(activeReportLayer, goManageScreen, goGetInfoScreen, goSecondScreen)
            }
        )
        }

        // Statistics line — api calls / total API time / running cost —
        // directly under the title bar's orange line; tap → costs screen.
        if (currentReportId != null) {
            ReportStatsLine(
                reportId = currentReportId,
                costDollars = totalCostForBar,
                refreshKey = totalCostForBar,
                onClick = generationHandlers.onViewCosts
            )
        }

        if (currentReportId != null) {
            UserNotesSection(
                reportId = currentReportId,
                notes = reportNotes,
                onEdit = { noteEdit = NoteEdit.Edit(it.id, it.text) }
            )
        }

        if (showRegenerateConfirm && currentReportId != null) {
            val rid = currentReportId
            // On the Get-info layer the 🔄 regenerates only this page's
            // metadata jobs (icon / title / language / per-model), not
            // the whole report.
            if (st.showGetInfo.value) {
                com.ai.ui.shared.ReloadConfirmationDialog(
                    target = "",
                    title = "Regenerate report info?",
                    message = "Re-run the icon, language, title and per-model icon / title jobs shown here. Each new call's cost is ADDED on top of the report's existing cost; the model responses and secondary results are left untouched.",
                    confirmLabel = "Regenerate info",
                    onConfirm = {
                        onDismissRegenerateConfirm()
                        onRegenerateInfo(rid)
                    },
                    onDismiss = onDismissRegenerateConfirm
                )
            } else {
                // The report's actual agent count — NOT models.size (the
                // staging list, empty on a normal open → "all 0 models").
                val agentCount = uiState.genericReportsSelectedAgents.size
                com.ai.ui.shared.ReloadConfirmationDialog(
                    target = "",
                    title = "Regenerate report?",
                    message = "Re-fire all $agentCount model${if (agentCount == 1) "" else "s"} on this report, then rerun existing Meta, Fan out, Fan in, Moderation, Rerank, and Translate rows. New API cost is added to the report's existing lifetime cost.",
                    confirmLabel = "Regenerate",
                    onConfirm = {
                        onDismissRegenerateConfirm()
                        onRegenerate(rid)
                    },
                    onDismiss = onDismissRegenerateConfirm
                )
            }
        }

        GenerationPhase(
            uiState = uiState,
            isComplete = isComplete,
            reportsProgress = reportsProgress,
            reportsTotal = reportsTotal,
            reportsAgentResults = reportsAgentResults,
            currentReportId = currentReportId,
            handlers = generationHandlers,
            onOpenViewReport = onOpenViewReport,
            onTotalCostChange = { totalCostForBar = it },
            editSystemPromptTrigger = editSystemPromptTrigger,
            secondaryCounts = secondaryCounts,
            costsFromDeletedItems = costsFromDeletedItems,
            secondaryRuns = secondaryRuns,
            translateRows = translateRows,
            secondaryTotals = secondaryTotals,
            translationRuns = translationRuns,
            translationRunSummaries = translationRunSummaries,
            fanOutSummaries = fanOutSummaries,
            metaPrompts = aiSettings.internalPrompts.filter { it.category.equals("meta", ignoreCase = true) },
            fanOutPrompts = aiSettings.internalPrompts.filter { it.category == "fan_out" },
            loaded = loaded,
            reportIcon = reportIcon,
            reportIconError = reportIconError,
            reportIconCost = reportIconCost,
            reportIconModel = reportIconModel,
            languageIconCost = languageIconCost,
            languageDetectCost = languageDetectCost,
            languageName = languageName,
            agentIconRows = agentIconRows,
            agentModelTitles = agentModelTitles,
            showModelNamesInReportRows = showModelNamesInReportRows,
            agentRecordsByAgentId = agentRecordsByAgentId,
            infoEnabled = infoEnabled,
            infoState = infoState,
            infoMetaTotal = infoMetaTotal,
            secondEnabled = secondEnabled,
            secondState = secondState,
            secondTotal = secondTotal,
            hasPrevReport = hasPrevReport,
            hasNextReport = hasNextReport,
            // Pause the hub's background effects while any full-screen layer
            // (Get-info / Edit report / Edit icons / Edit titles) is on top —
            // the hub stays composed underneath.
            paused = st.showGetInfo.value || (showSecondResults?.value == true) || st.showEditReportOverview.value ||
                st.showEditIconsList.value || st.showEditTitlesList.value ||
                st.showCreateOverview.value
        )
    } // close inner Column
        // Body-level pill. TopCenter + 24.dp top padding lines this
        // pill up with the title-bar's internal pill (which sits at
        // 16.dp Column padding + 8.dp Box padding = 24.dp from the
        // screen top), so swipes anywhere on the screen produce the
        // same visual feedback at the same location.
        val bodyStatus = bodySwipeStatus.value
        if (bodyStatus != null) {
            Text(
                text = bodyStatus,
                color = AppColors.TextPrimary,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(com.ai.ui.shared.AppColors.SurfaceDark.copy(alpha = 0.95f))
                    .border(1.dp, com.ai.ui.shared.AppColors.InfoAccent.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
        // "Report - Get info" drawn as a visual layer ON TOP of this
        // still-composed hub. Because the hub's TitleBar + GenerationPhase
        // stay composed underneath, the global bottom bar remains the
        // hub's (published per-layer, with the live ✏️/🆕 menus and confirm
        // dialogs) — Get info's own header passes publishBottomBar=false so
        // it doesn't clobber it. Its opaque background covers the hub body;
        // Back peels just this layer.
        if (st.showGetInfo.value && currentReportId != null) {
            // Provide the report-context locals the screen's TitleBar
            // reads: the dynamic report icon (top-left), and the
            // "current report" target so tapping the icon / title peels
            // this layer back to the Manage hub.
            androidx.compose.runtime.CompositionLocalProvider(
                com.ai.ui.shared.LocalReportIcon provides (reportIcon?.takeIf { it.isNotBlank() } ?: com.ai.data.MetadataIconsHolder.current.reportIcon),
                com.ai.ui.shared.LocalReportTitle provides uiState.genericPromptTitle,
                com.ai.ui.shared.LocalNavigateToCurrentReport provides { st.showGetInfo.value = false }
            ) {
                ReportGetInfoScreen(
                    reportId = currentReportId,
                    settings = aiSettings,
                    iconRefreshTick = uiState.iconRefreshTick,
                    iconGenEnabled = iconGenEnabled,
                    reportLanguageOn = uiState.generalSettings.reportLanguageOn(),
                    titleModeAi = uiState.generalSettings.reportTitleAiOn(),
                    perModelIcon = uiState.generalSettings.perModelIconOn(),
                    perModelTitle = uiState.generalSettings.perModelTitleOn(),
                    runningInfoJobs = runningInfoJobs,
                    // Stats line + the report / second cross-link rows —
                    // same live data the hub's own stats line shows.
                    reportTitle = uiState.genericPromptTitleLong.ifBlank { uiState.genericPromptTitle },
                    costDollars = totalCostForBar,
                    onViewCosts = generationHandlers.onViewCosts,
                    reportCost = mainResponseTotal,
                    secondState = secondState,
                    secondTotal = secondTotal,
                    onGoManage = goManageScreen,
                    onGoSecond = goSecondScreen,
                    onBack = { st.showGetInfo.value = false },
                    onCycleNext = cycleReportScreens,
                    onOpenViewHub = onOpenViewReport,
                    onOpenIconDetail = { st.showIconDetail.value = true },
                    onOpenLanguageDetect = {
                        st.showIconDetail.value = true
                        st.targetLanguageDetect.value = true
                    },
                    onOpenLanguageDetail = {
                        st.showIconDetail.value = true
                        st.targetLanguageIcon.value = true
                    },
                    onEditTitle = { st.showEditShortTitle.value = true },
                    onEditLongTitle = { st.showEditLongTitle.value = true },
                    onOpenAgentIconDetail = { agentId -> st.agentIconDetailFor.value = agentId },
                    onEditModelTitle = { agentId -> st.editModelTitleFor.value = agentId },
                    onRestartErrors = { onRestartInfoErrors(currentReportId) }
                )
            }
        }

        // "Report - second results" — the secondary-result analogue of Get-info,
        // drawn as a layer over the still-composed hub (same publishBottomBar
        // pattern). Tapping a row sets the same open-states / handlers the hub
        // rows used, so their detail screens / overlays open on top; Back peels.
        if (showSecondResults?.value == true && currentReportId != null) {
            androidx.compose.runtime.CompositionLocalProvider(
                com.ai.ui.shared.LocalReportIcon provides (reportIcon?.takeIf { it.isNotBlank() } ?: com.ai.data.MetadataIconsHolder.current.reportIcon),
                com.ai.ui.shared.LocalReportTitle provides uiState.genericPromptTitle,
                com.ai.ui.shared.LocalNavigateToCurrentReport provides { showSecondResults.value = false }
            ) {
                ReportSecondResultsScreen(
                    reportId = currentReportId,
                    uiState = uiState,
                    aiSettings = aiSettings,
                    secondaryRuns = secondaryRuns,
                    fanOutSummaries = fanOutSummaries,
                    translationRuns = translationRuns,
                    translationRunSummaries = translationRunSummaries,
                    languageName = languageName,
                    // Stats line + the report / info cross-link rows —
                    // same live data the hub's own stats line shows.
                    reportTitle = uiState.genericPromptTitleLong.ifBlank { uiState.genericPromptTitle },
                    costDollars = totalCostForBar,
                    onViewCosts = generationHandlers.onViewCosts,
                    reportCost = mainResponseTotal,
                    infoEnabled = infoEnabled,
                    infoState = infoState,
                    infoMetaTotal = infoMetaTotal,
                    onGoManage = goManageScreen,
                    onGoInfo = goGetInfoScreen,
                    // The 🔤 model-names toggle is a Manage-row local (out of
                    // scope here); the screen always shows prompt titles.
                    showModelNamesInReportRows = false,
                    onOpenSecondaryRun = generationHandlers.onOpenSecondaryRun,
                    onMissingPromptIcon = generationHandlers.onMissingPromptIcon,
                    onOpenInternalPromptIconDetail = generationHandlers.onOpenInternalPromptIconDetail,
                    onOpenInternalPromptIconDetailForRow = generationHandlers.onOpenInternalPromptIconDetailForRow,
                    onViewSecondaryName = generationHandlers.onViewSecondaryName,
                    onViewFanMeta = generationHandlers.onViewFanMeta,
                    onOpenTranslationRun = generationHandlers.onOpenTranslationRun,
                    onMissingTranslationIcon = generationHandlers.onMissingTranslationIcon,
                    onOpenTranslationIconDetail = generationHandlers.onOpenTranslationIconDetail,
                    onBack = { showSecondResults.value = false },
                    onCycleNext = cycleReportScreens,
                    onOpenViewHub = onOpenViewReport
                )
            }
        }

        // "Edit report" overview + its two child list screens — same
        // layer-on-top pattern as Get-info. Painted in order so a child list
        // (icons / titles) draws over the overview; each Back clears only its
        // own flag, so the stack unwinds one level per press (child → overview
        // → hub). The pencils inside each set the existing st.* edit/detail
        // flags, whose early-return overlays (in ReportsScreen, before this
        // hub) render over these layers.
        if (st.showEditReportOverview.value && currentReportId != null) {
            androidx.compose.runtime.CompositionLocalProvider(
                com.ai.ui.shared.LocalReportIcon provides (reportIcon?.takeIf { it.isNotBlank() } ?: com.ai.data.MetadataIconsHolder.current.reportIcon),
                com.ai.ui.shared.LocalReportTitle provides uiState.genericPromptTitle,
                com.ai.ui.shared.LocalNavigateToCurrentReport provides { st.showEditReportOverview.value = false }
            ) {
                ReportEditOverviewScreen(
                    reportId = currentReportId,
                    uiState = uiState,
                    st = st,
                    editSystemPromptTrigger = editSystemPromptTrigger,
                    onEditModels = {
                        st.showEditReportOverview.value = false
                        generationHandlers.onEditModelsInline()
                    },
                    onEditWorkers = { showWorkerConfig = true },
                    onBack = { st.showEditReportOverview.value = false }
                )
            }
        }
        if (st.showEditIconsList.value && currentReportId != null) {
            androidx.compose.runtime.CompositionLocalProvider(
                com.ai.ui.shared.LocalReportIcon provides (reportIcon?.takeIf { it.isNotBlank() } ?: com.ai.data.MetadataIconsHolder.current.reportIcon),
                com.ai.ui.shared.LocalReportTitle provides uiState.genericPromptTitle,
                com.ai.ui.shared.LocalNavigateToCurrentReport provides { st.showEditIconsList.value = false }
            ) {
                ReportEditIconsScreen(
                    reportId = currentReportId,
                    iconRefreshTick = uiState.iconRefreshTick,
                    st = st,
                    onBack = { st.showEditIconsList.value = false }
                )
            }
        }
        if (st.showEditTitlesList.value && currentReportId != null) {
            androidx.compose.runtime.CompositionLocalProvider(
                com.ai.ui.shared.LocalReportIcon provides (reportIcon?.takeIf { it.isNotBlank() } ?: com.ai.data.MetadataIconsHolder.current.reportIcon),
                com.ai.ui.shared.LocalReportTitle provides uiState.genericPromptTitle,
                com.ai.ui.shared.LocalNavigateToCurrentReport provides { st.showEditTitlesList.value = false }
            ) {
                ReportEditTitlesScreen(
                    reportId = currentReportId,
                    uiState = uiState,
                    st = st,
                    onBack = { st.showEditTitlesList.value = false }
                )
            }
        }
        // "Create" launcher — the 🆕 sibling of the Edit overview. Each row's
        // callback closes this layer then fires the same flow the old pop-up
        // did (so Back from the opened picker returns to the hub).
        if (st.showCreateOverview.value && currentReportId != null) {
            androidx.compose.runtime.CompositionLocalProvider(
                com.ai.ui.shared.LocalReportIcon provides (reportIcon?.takeIf { it.isNotBlank() } ?: com.ai.data.MetadataIconsHolder.current.reportIcon),
                com.ai.ui.shared.LocalReportTitle provides uiState.genericPromptTitle,
                com.ai.ui.shared.LocalNavigateToCurrentReport provides { st.showCreateOverview.value = false }
            ) {
                ReportCreateOverviewScreen(
                    metaEnabled = aiSettings.internalPrompts.any { it.category.equals("meta", ignoreCase = true) },
                    compareEnabled = compareEnabled,
                    onMeta = {
                        st.showCreateOverview.value = false
                        generationHandlers.onOpenMetaPicker()
                    },
                    onCompare = {
                        st.showCreateOverview.value = false
                        compareStep = 1
                    },
                    onBack = { st.showCreateOverview.value = false }
                )
            }
        }
        // Tournament launcher — opened by the Manage bottom-bar tournament
        // icon. The actual runs still use the same confirm dialogs below.
        if (showTournamentOverview && currentReportId != null) {
            androidx.compose.runtime.CompositionLocalProvider(
                com.ai.ui.shared.LocalReportIcon provides (reportIcon?.takeIf { it.isNotBlank() } ?: com.ai.data.MetadataIconsHolder.current.reportIcon),
                com.ai.ui.shared.LocalReportTitle provides uiState.genericPromptTitle,
                com.ai.ui.shared.LocalNavigateToCurrentReport provides { showTournamentOverview = false }
            ) {
                ReportTournamentOverviewScreen(
                    // Tournament needs ≥2 responses; multiple judges allowed (not single-shot).
                    tournamentEnabled = tournamentResponseCount >= 2,
                    // Judge-the-judges needs ≥2 responses to form a head-to-head pair.
                    judgeJudgesEnabled = tournamentResponseCount >= 2,
                    onTournament = {
                        showTournamentOverview = false
                        confirmTournament = true
                    },
                    onJudgeJudges = {
                        showTournamentOverview = false
                        confirmJudgeJudges = true
                    },
                    onBack = { showTournamentOverview = false }
                )
            }
        }

        // Translations list — opened by the 🌐 icon when the report already has
        // translations. Drill into a run (opens its detail via openTranslationRunId,
        // a layer above this hub) or 🆕 a new one (the create flow).
        if (showTranslationsList && currentReportId != null) {
            androidx.compose.runtime.CompositionLocalProvider(
                com.ai.ui.shared.LocalReportIcon provides (reportIcon?.takeIf { it.isNotBlank() } ?: com.ai.data.MetadataIconsHolder.current.reportIcon),
                com.ai.ui.shared.LocalReportTitle provides uiState.genericPromptTitle,
                com.ai.ui.shared.LocalNavigateToCurrentReport provides { showTranslationsList = false }
            ) {
                ReportTranslationsScreen(
                    reportTitle = uiState.genericPromptTitleLong.ifBlank { uiState.genericPromptTitle },
                    reportIcon = reportIcon?.takeIf { it.isNotBlank() } ?: com.ai.data.MetadataIconsHolder.current.reportIcon,
                    originalLanguage = languageName,
                    originalLanguageIcon = languageIcon?.takeIf { it.isNotBlank() }
                        ?: com.ai.data.MetadataIconsHolder.current.languageIcon,
                    summaries = translationRunSummaries,
                    // translationRuns is already filtered to this report (Nav.kt);
                    // show the still-running ones with a green progress bar.
                    activeRuns = translationRuns.filter { !it.isFinished && !it.cancelled },
                    onOpenRun = { st.openTranslationRunId.value = it },
                    onRankTranslators = onRankMedal,
                    onOpenOriginal = { showTranslationsList = false },
                    onNewTranslation = { generationHandlers.onTranslate() },
                    onBack = { showTranslationsList = false }
                )
            }
        }

        // 🏅 Rank-the-translators launch — shared confirm dialog (with the call
        // count); on Rank, build-stage + run + open the ranking overlay. Honors
        // the Worker-batches / *SELECT worker-source precedence, like Tournament / Judges.
        com.ai.ui.report.manage.RankTranslatorsConfirmHost(currentReportId, pendingRank, translatorRankEngine) { req ->
            currentReportId?.let { rid ->
                val key = java.util.UUID.randomUUID().toString()
                onArmBuildStage(
                    key, "Building translator ranking",
                    { transRankOpenState?.value = com.ai.data.transRankRunKey(rid, req.runId) },
                    { translatorRankEngine?.deleteRun(context, com.ai.data.transRankRunKey(rid, req.runId)) }
                )
                translatorRankEngine?.startRun(context, rid, req.runId, req.lang, req.native, key, req.overrideWorkers)
            }
        }

        // Tournament launch — a single confirm dialog showing the N(N-1)
        // worker-call count. Judging runs on the worker engine.
        if (confirmTournament && currentReportId != null) {
            val matchCount = tournamentResponseCount * (tournamentResponseCount - 1)
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmTournament = false },
                title = { androidx.compose.material3.Text("Run tournament?") },
                text = {
                    androidx.compose.material3.Text(
                        "This runs $matchCount head-to-head judgments " +
                            "($tournamentResponseCount answers, each pair judged both ways), " +
                            "judged by the worker engine."
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        currentReportId.let { rid ->
                            // Build stage: block behind "Preparing…" while the
                            // match grid is created, then open the L1 once done.
                            val key = java.util.UUID.randomUUID().toString()
                            val arm = { ws: List<com.ai.model.Worker>? ->
                                onArmBuildStage(key, "Building tournament", { tournamentOpenState?.value = rid }, { onDeleteTournamentRun(rid) })
                                onRunTournament(rid, key, ws)
                            }
                            launchWithWorkerPlan(
                                st.runtimeWorkerPick, context, st.screenScope, rid,
                                aiSettings.workerPromptByName("tournament"),
                                "Tournament — pick workers"
                            ) { picked -> arm(picked) }
                        }
                        confirmTournament = false
                    }) { androidx.compose.material3.Text("Run") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmTournament = false }) {
                        androidx.compose.material3.Text("Cancel")
                    }
                }
            )
        }

        // Judge-the-judges launch — every judge model judges the same 25
        // random head-to-heads, then we score their agreement.
        if (confirmJudgeJudges && currentReportId != null) {
            val pairCount = tournamentResponseCount * (tournamentResponseCount - 1) / 2
            val matches = pairCount.coerceAtMost(25)
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmJudgeJudges = false },
                title = { androidx.compose.material3.Text("Judge the judges?") },
                text = {
                    androidx.compose.material3.Text(
                        "This gives the same $matches random head-to-head${if (matches == 1) "" else "s"} to " +
                            "every judge model (the worker models), then scores how the judges agree with one another."
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        currentReportId.let { rid ->
                            val key = java.util.UUID.randomUUID().toString()
                            val arm = { ws: List<com.ai.model.Worker>? ->
                                onArmBuildStage(key, "Building judge-the-judges", { judgeEvalOpenState?.value = rid }, { onDeleteJudgeRun(rid) })
                                onRunJudgeJudges(rid, key, ws)
                            }
                            launchWithWorkerPlan(
                                st.runtimeWorkerPick, context, st.screenScope, rid,
                                aiSettings.workerPromptByName("tournament"),
                                "Judge the judges — pick workers"
                            ) { picked -> arm(picked) }
                        }
                        confirmJudgeJudges = false
                    }) { androidx.compose.material3.Text("Run") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmJudgeJudges = false }) {
                        androidx.compose.material3.Text("Cancel")
                    }
                }
            )
        }
    } // close outer Box
}
