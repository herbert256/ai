package com.ai.ui

import com.ai.data.AiAnalysisResponse

// General app settings
data class GeneralSettings(
    val userName: String = "user",
    val developerMode: Boolean = false,
    val trackApiCalls: Boolean = false,
    val huggingFaceApiKey: String = "",
    val openRouterApiKey: String = "",
    val fullScreenMode: Boolean = false,
    val defaultEmail: String = ""
)

// Prompt history entry
data class PromptHistoryEntry(
    val timestamp: Long,
    val title: String,
    val prompt: String
)

// AI UI State - main state for the AI app
data class AiUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    // General settings
    val generalSettings: GeneralSettings = GeneralSettings(),
    // AI settings
    val aiSettings: AiSettings = AiSettings(),
    // Model loading state (model data lives in aiSettings.getModels(service))
    val loadingModelsFor: Set<com.ai.data.AiService> = emptySet(),
    // Generic AI Reports (main feature)
    val showGenericAiAgentSelection: Boolean = false,
    val showGenericAiReportsDialog: Boolean = false,
    val genericAiPromptTitle: String = "",
    val genericAiPromptText: String = "",
    val genericAiReportsProgress: Int = 0,
    val genericAiReportsTotal: Int = 0,
    val genericAiReportsSelectedAgents: Set<String> = emptySet(),
    val genericAiReportsAgentResults: Map<String, AiAnalysisResponse> = emptyMap(),
    val currentReportId: String? = null,  // ID of the current AI report being generated
    // Report advanced parameters (override agent parameters for a specific report)
    val reportAdvancedParameters: AiAgentParameters? = null,
    // External intent instructions (from -- end prompt -- marker)
    val externalCloseHtml: String? = null,
    val externalReportType: String? = null,  // "Classic" or "Table"
    val externalEmail: String? = null,
    // Chat
    val chatParameters: ChatParameters = ChatParameters()
)
