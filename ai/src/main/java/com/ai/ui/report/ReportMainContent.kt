package com.ai.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.data.AgentParameters
import com.ai.data.AnalysisResponse
import com.ai.data.ReportType
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.model.ReportModel
import com.ai.ui.shared.LocalNavigateToCurrentReport
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.ReportViewModel
import com.ai.viewmodel.UiState

@Composable
internal fun ReportMainContent(
    uiState: UiState,
    isGenerating: Boolean,
    isComplete: Boolean,
    reportsProgress: Int,
    reportsTotal: Int,
    reportsAgentResults: Map<String, AnalysisResponse>,
    currentReportId: String?,
    iconGenEnabled: Boolean,
    showRegenerateConfirm: Boolean,
    models: List<ReportModel>,
    selectedParametersIds: List<String>,
    advancedParameters: AgentParameters?,
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
    languageName: String?,
    agentIconRows: Map<String, AgentIconRow>,
    hasPrevReport: Boolean,
    hasNextReport: Boolean,
    onDismiss: () -> Unit,
    onOpenViewReport: () -> Unit,
    onRequestRegenerate: () -> Unit,
    onDismissRegenerateConfirm: () -> Unit,
    onRegenerate: (String) -> Unit,
    onChatWithReportPrompt: (String) -> Unit,
    onAddFlock: () -> Unit,
    onAddAgent: () -> Unit,
    onAddSwarm: () -> Unit,
    onAddModel: () -> Unit,
    onAddAllModels: () -> Unit,
    onAddFromReport: () -> Unit,
    onRemoveModel: (Int) -> Unit,
    onClearAllModels: () -> Unit,
    onAdvancedParams: () -> Unit,
    onParametersChange: (List<String>) -> Unit,
    onGenerate: (ReportType) -> Unit,
    onUpdateModelList: () -> Unit,
    onAttachKnowledgeBases: (List<String>) -> Unit,
    onSystemPromptChange: (String?) -> Unit
) {
    val aiSettings = uiState.aiSettings
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        val promptTitle = uiState.genericPromptTitle
        CompositionLocalProvider(
            LocalNavigateToCurrentReport provides (
                if (isGenerating && currentReportId != null) onOpenViewReport else null
            )
        ) {
            TitleBar(
                helpTopic = "report_result_generation",
                title = "Report - manage",
                onTitleClick = if (isGenerating && currentReportId != null) onOpenViewReport else null,
                subject = if (isGenerating) promptTitle else null,
                reportIcon = if (iconGenEnabled && isGenerating) {
                    reportIcon?.takeIf { it.isNotEmpty() } ?: "📝"
                } else null,
                onBackClick = onDismiss,
                onReload = if (isGenerating && currentReportId != null && isComplete) onRequestRegenerate else null,
                onTrace = if (isGenerating && currentReportId != null) generationHandlers.onTrace else null,
                onDelete = if (isGenerating && currentReportId != null) generationHandlers.onDelete else null,
                onInfo = if (isGenerating && currentReportId != null) onOpenViewReport else null,
                onChat = if (isGenerating && uiState.genericPromptText.isNotBlank()) {
                    { onChatWithReportPrompt(uiState.genericPromptText) }
                } else null,
                onShare = if (isGenerating && currentReportId != null && isComplete) generationHandlers.onRequestExport else null
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

        // Generation-phase green subject row moved into GenerationPhase
        // so it can carry the running total cost on the right.

        if (!isGenerating) {
            SelectionPhase(
                models = models,
                aiSettings = aiSettings,
                selectedParametersIds = selectedParametersIds,
                advancedParameters = advancedParameters,
                editModeReportId = uiState.editModeReportId,
                onAddFlock = onAddFlock,
                onAddAgent = onAddAgent,
                onAddSwarm = onAddSwarm,
                onAddModel = onAddModel,
                onAddAllModels = onAddAllModels,
                onAddFromReport = onAddFromReport,
                onRemoveModel = onRemoveModel,
                onClearAll = onClearAllModels,
                onAdvancedParams = onAdvancedParams,
                onParametersChange = onParametersChange,
                onGenerate = onGenerate,
                onUpdateModelList = onUpdateModelList,
                attachedKnowledgeBaseIds = uiState.attachedKnowledgeBaseIds,
                onAttachKnowledgeBases = onAttachKnowledgeBases,
                selectedSystemPromptId = uiState.reportSystemPromptId,
                onSystemPromptChange = onSystemPromptChange
            )
        } else {
            GenerationPhase(
                uiState = uiState,
                isComplete = isComplete,
                reportsProgress = reportsProgress,
                reportsTotal = reportsTotal,
                reportsAgentResults = reportsAgentResults,
                currentReportId = currentReportId,
                handlers = generationHandlers,
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
                languageName = languageName,
                agentIconRows = agentIconRows,
                hasPrevReport = hasPrevReport,
                hasNextReport = hasNextReport
            )
        }
    }
}
