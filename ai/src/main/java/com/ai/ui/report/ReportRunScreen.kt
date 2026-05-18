package com.ai.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.data.AnalysisResponse
import com.ai.data.ReportStorage
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.model.ReportModel
import com.ai.ui.shared.LocalNavigateToCurrentReport
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.ReportViewModel
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
    generationHandlers: GenerationPhaseHandlers,
    secondaryCounts: SecondaryResultStorage.Counts,
    costsFromDeletedItems: Double,
    secondaryRuns: List<SecondaryResult>,
    translateRows: List<SecondaryResult>,
    secondaryTotals: SecondaryTotals,
    translationRuns: List<ReportViewModel.TranslationRunState>,
    translationRunSummaries: List<TranslationRunSummary>,
    fanOutSummaries: List<FanOutRunSummary>,
    reportIcon: String?,
    reportIconError: String?,
    reportIconCost: Double,
    reportIconModel: String?,
    languageIconCost: Double,
    languageDetectCost: Double,
    languageName: String?,
    agentIconRows: Map<String, AgentIconRow>,
    hasPrevReport: Boolean,
    hasNextReport: Boolean,
    onDismiss: () -> Unit,
    onOpenViewReport: () -> Unit,
    onRequestRegenerate: () -> Unit,
    onDismissRegenerateConfirm: () -> Unit,
    onRegenerate: (String) -> Unit,
    onChatWithReportPrompt: (String) -> Unit
) {
    val aiSettings = uiState.aiSettings
    val context = LocalContext.current
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
    // Per-report system-prompt picker — owns its visibility state +
    // renders the SystemPromptSelectorDialog. Returns the trigger
    // lambda that Edit Row 2's "System prompt" button fires. Lives
    // here (not in ReportScreen) so its bytecode stays out of the
    // 64 KB-ceiling-hugging ReportsScreen. The select callback is
    // pulled from LocalSystemPromptChange so we don't need to thread
    // it through the call site as another arg.
    val systemPromptChange = com.ai.ui.shared.LocalSystemPromptChange.current
    val editSystemPromptTrigger = rememberEditSystemPromptDialog(
        aiSettings = aiSettings,
        selectedId = uiState.reportSystemPromptId,
        onSelect = systemPromptChange
    )
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        val promptTitle = uiState.genericPromptTitle
        CompositionLocalProvider(
            LocalNavigateToCurrentReport provides (
                if (currentReportId != null) onOpenViewReport else null
            )
        ) {
            TitleBar(
                helpTopic = "report_run",
                title = "Manage report",
                onTitleClick = if (currentReportId != null) onOpenViewReport else null,
                subject = promptTitle,
                reportIcon = if (iconGenEnabled) reportIcon?.takeIf { it.isNotEmpty() } ?: "📝" else null,
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
                isPinned = isPinned
            )
        }

        if (showRegenerateConfirm && currentReportId != null) {
            val rid = currentReportId
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

        GenerationPhase(
            uiState = uiState,
            isComplete = isComplete,
            reportsProgress = reportsProgress,
            reportsTotal = reportsTotal,
            reportsAgentResults = reportsAgentResults,
            currentReportId = currentReportId,
            handlers = generationHandlers,
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
            reportIcon = reportIcon,
            reportIconError = reportIconError,
            reportIconCost = reportIconCost,
            reportIconModel = reportIconModel,
            languageIconCost = languageIconCost,
            languageDetectCost = languageDetectCost,
            languageName = languageName,
            agentIconRows = agentIconRows,
            hasPrevReport = hasPrevReport,
            hasNextReport = hasNextReport
        )
    }
}
