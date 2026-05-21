package com.ai.ui.report.manage
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.data.AgentParameters
import com.ai.data.ReportType
import com.ai.model.ReportModel
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.UiState

/** Pre-Generate page in the report flow. Empty model list to start;
 *  +Agent / +Flock / +Swarm / +Model / +Report fill it, Params lets
 *  you tweak the per-call parameter set, Generate fires the dispatch.
 *
 *  Distinct from [ReportRunScreen] which is the post-Generate manage
 *  page. The split exists because the two screens have no overlapping
 *  controls — the user is on one when picking what to fire and on
 *  the other when interpreting / acting on the results — and sharing
 *  a title + help topic was confusing in practice. */
@Composable
internal fun ReportSelectModelsScreen(
    uiState: UiState,
    models: List<ReportModel>,
    selectedParametersIds: List<String>,
    advancedParameters: AgentParameters?,
    onDismiss: () -> Unit,
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
        TitleBar(
            helpTopic = "report_select_models",
            title = "Report - select models",
            onBackClick = onDismiss
        )
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
            experimentalFeatures = uiState.generalSettings.experimentalFeaturesEnabled,
            selectedSystemPromptId = uiState.reportSystemPromptId,
            onSystemPromptChange = onSystemPromptChange
        )
    }
}
