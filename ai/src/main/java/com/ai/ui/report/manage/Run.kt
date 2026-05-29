package com.ai.ui.report.manage
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AnalysisResponse
import com.ai.data.ReportAgent
import com.ai.data.ReportStorage
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.model.ReportModel
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.ReportViewModel
import com.ai.viewmodel.TranslationRunState
import com.ai.viewmodel.UiState
import kotlinx.coroutines.Dispatchers
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
    agentIconRows: Map<String, AgentIconRow>,
    agentModelTitles: Map<String, AgentModelTitle> = emptyMap(),
    agentRecordsByAgentId: Map<String, ReportAgent> = emptyMap(),
    infoEnabled: Boolean = false,
    infoState: InfoJobState = InfoJobState.DONE,
    infoMetaTotal: Double = 0.0,
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
    onChatWithReportPrompt: (String) -> Unit
) {
    val aiSettings = uiState.aiSettings
    val context = LocalContext.current
    val navigateToReportInfo = com.ai.ui.shared.LocalNavigateToReportInfo.current
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
    // 👯 duplicate-report tap shows a yes/no first so an accidental
    // hit on the bottom bar doesn't silently spawn a "(Copy)" report.
    var showCopyConfirm by remember { mutableStateOf(false) }
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
                ) { androidx.compose.material3.Text("Duplicate", color = com.ai.ui.shared.AppColors.Blue) }
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
    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
        // Running total cost, reported up from GenerationPhase, shown in
        // the bottom icon bar (top row, right, above ❓).
        var totalCostForBar by remember { mutableStateOf(0.0) }
        // 🗂️ pick-another-report on the Manage hub → the unfiltered picker,
        // returning to the hub for the chosen report. Provided only around
        // the TitleBar so the auto-captured bottom-bar icon appears here.
        val managePick = com.ai.ui.shared.LocalNavigateToManagePicker.current
        androidx.compose.runtime.CompositionLocalProvider(
            com.ai.ui.shared.LocalManagePickReport provides
                { managePick(com.ai.ui.navigation.ManagePickKind.MANAGE.arg) }
        ) {
        TitleBar(
            helpTopic = "report_run",
            title = "Manage a report",
            costText = totalCostForBar.takeIf { it > 0.0 }?.let { com.ai.ui.shared.formatCents(it, 2) },
            onCostClick = generationHandlers.onViewCosts,
            // Tapping the "Manage report" screen title opens the main
            // View hub ("View a report") — same target as the report
            // icon, the green report-name and the bottom-bar 👁.
            onTitleClick = onOpenViewReport,
            subject = promptTitleForBar,
            reportIcon = if (iconGenEnabled) reportIcon?.takeIf { it.isNotEmpty() } ?: "📝" else null,
            // On the Manage report screen the report icon opens the main
            // View hub ("View a report") — same target as the green
            // report-name line and the bottom-bar 👁.
            onReportIconClick = onOpenViewReport,
            // ℹ️ → the standalone "Report information" screen (real route).
            // Read from a CompositionLocal rather than a threaded arg —
            // ReportsScreen is at the JVM 64 KB method ceiling.
            onInfo = currentReportId?.let { rid -> { navigateToReportInfo(rid) } },
            onBackClick = onDismiss,
            onReload = if (currentReportId != null && isComplete) onRequestRegenerate else null,
            onTrace = if (currentReportId != null) generationHandlers.onTrace else null,
            onDelete = if (currentReportId != null) generationHandlers.onDelete else null,
            onOpenView = if (currentReportId != null) onOpenViewReport else null,
            onChat = if (uiState.genericPromptText.isNotBlank()) {
                { onChatWithReportPrompt(uiState.genericPromptText) }
            } else null,
            onShare = if (currentReportId != null && isComplete) generationHandlers.onRequestExport else null,
            onCopyReport = if (currentReportId != null) {
                { showCopyConfirm = true }
            } else null,
            onPin = if (currentReportId != null) {
                { generationHandlers.onTogglePin(); pinTick++ }
            } else null,
            isPinned = isPinned,
            // ✏️ opens the full-screen "Edit report" overview (layer on top
            // of this hub) instead of the old 3-button pop-up.
            onEdit = { st.showEditReportOverview.value = true },
            // 🌡️ parameters / 🎭 system prompt — pulled out of the Edit
            // pop-up onto their own bottom-bar icons.
            onParameters = if (currentReportId != null) { { st.showEditParameters.value = true } } else null,
            onSystemPrompt = if (currentReportId != null) editSystemPromptTrigger else null,
            // 🆕 opens the full-screen "Create" launcher (layer on top of this
            // hub) instead of the old pop-up.
            onAdd = { st.showCreateOverview.value = true },
            addFirst = true
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
                val agentCount = models.size
                com.ai.ui.shared.ReloadConfirmationDialog(
                    target = "",
                    title = "Regenerate every agent?",
                    message = "Re-fire the API call for all $agentCount model${if (agentCount == 1) "" else "s"} on this report. The existing responses, costs, and traces are replaced. Secondary results (Meta, Fan out, Translate) are kept.",
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
            agentRecordsByAgentId = agentRecordsByAgentId,
            infoEnabled = infoEnabled,
            infoState = infoState,
            infoMetaTotal = infoMetaTotal,
            hasPrevReport = hasPrevReport,
            hasNextReport = hasNextReport,
            // Pause the hub's background effects while any full-screen layer
            // (Get-info / Edit report / Edit icons / Edit titles) is on top —
            // the hub stays composed underneath.
            paused = st.showGetInfo.value || st.showEditReportOverview.value ||
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
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(com.ai.ui.shared.AppColors.SurfaceDark.copy(alpha = 0.95f))
                    .border(1.dp, com.ai.ui.shared.AppColors.Blue.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
        // "Report - Get info" drawn as a visual layer ON TOP of this
        // still-composed hub. Because the hub's TitleBar + GenerationPhase
        // stay composed underneath, the global bottom bar remains the
        // hub's (cost above ❓ + every action icon, with the live ✏️/🆕
        // menus and confirm dialogs) — Get info's own header passes
        // publishBottomBar=false so it doesn't clobber it. Its opaque
        // background covers the hub body; Back peels just this layer.
        if (st.showGetInfo.value && currentReportId != null) {
            // Provide the report-context locals the screen's TitleBar
            // reads: the dynamic report icon (top-left), and the
            // "current report" target so tapping the icon / title peels
            // this layer back to the Manage hub.
            androidx.compose.runtime.CompositionLocalProvider(
                com.ai.ui.shared.LocalReportIcon provides (reportIcon?.takeIf { it.isNotBlank() } ?: "📝"),
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
                    onBack = { st.showGetInfo.value = false },
                    onOpenIconDetail = { st.showIconDetail.value = true },
                    onOpenLanguageDetail = {
                        st.showIconDetail.value = true
                        st.targetLanguageIcon.value = true
                    },
                    onEditTitle = { st.showEditTitle.value = true },
                    onOpenAgentIconDetail = { agentId -> st.agentIconDetailFor.value = agentId },
                    onEditModelTitle = { agentId -> st.editModelTitleFor.value = agentId },
                    onRestartErrors = { onRestartInfoErrors(currentReportId) }
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
                com.ai.ui.shared.LocalReportIcon provides (reportIcon?.takeIf { it.isNotBlank() } ?: "📝"),
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
                    onBack = { st.showEditReportOverview.value = false }
                )
            }
        }
        if (st.showEditIconsList.value && currentReportId != null) {
            androidx.compose.runtime.CompositionLocalProvider(
                com.ai.ui.shared.LocalReportIcon provides (reportIcon?.takeIf { it.isNotBlank() } ?: "📝"),
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
                com.ai.ui.shared.LocalReportIcon provides (reportIcon?.takeIf { it.isNotBlank() } ?: "📝"),
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
                com.ai.ui.shared.LocalReportIcon provides (reportIcon?.takeIf { it.isNotBlank() } ?: "📝"),
                com.ai.ui.shared.LocalReportTitle provides uiState.genericPromptTitle,
                com.ai.ui.shared.LocalNavigateToCurrentReport provides { st.showCreateOverview.value = false }
            ) {
                ReportCreateOverviewScreen(
                    metaEnabled = aiSettings.internalPrompts.any { it.category.equals("meta", ignoreCase = true) },
                    rerankEnabled = secondaryCounts.rerank == 0,
                    moderationEnabled = secondaryCounts.moderation == 0,
                    fanOutEnabled = aiSettings.internalPrompts.any { it.category == "fan_out" },
                    onMeta = {
                        st.showCreateOverview.value = false
                        generationHandlers.onOpenMetaPicker()
                    },
                    onRerank = {
                        st.showCreateOverview.value = false
                        android.widget.Toast.makeText(context, "Loading rerank models…", android.widget.Toast.LENGTH_SHORT).show()
                        generationHandlers.onOpenRerankPicker()
                    },
                    onModeration = {
                        st.showCreateOverview.value = false
                        android.widget.Toast.makeText(context, "Loading moderation models…", android.widget.Toast.LENGTH_SHORT).show()
                        generationHandlers.onOpenModerationPicker()
                    },
                    onFanOut = {
                        st.showCreateOverview.value = false
                        generationHandlers.onOpenFanOutPicker()
                    },
                    onTranslate = {
                        st.showCreateOverview.value = false
                        generationHandlers.onTranslate()
                    },
                    onBack = { st.showCreateOverview.value = false }
                )
            }
        }
    } // close outer Box
}
