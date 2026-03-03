package com.ai.ui

import com.ai.data.AnalysisResponse

// General app settings
data class GeneralSettings(
    val userName: String = "user",
    val developerMode: Boolean = true,
    val huggingFaceApiKey: String = "",
    val openRouterApiKey: String = "",
    val fullScreenMode: Boolean = false,
    val defaultEmail: String = "",
    val popupModelSelection: Boolean = true
)

// Prompt history entry
data class PromptHistoryEntry(
    val timestamp: Long,
    val title: String,
    val prompt: String
)

// AI UI State - main state for the AI app
data class UiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    // General settings
    val generalSettings: GeneralSettings = GeneralSettings(),
    // AI settings
    val aiSettings: Settings = Settings(),
    // Model loading state (model data lives in aiSettings.getModels(service))
    val loadingModelsFor: Set<com.ai.data.AppService> = emptySet(),
    // Generic AI Reports (main feature)
    val showGenericAgentSelection: Boolean = false,
    val showGenericReportsDialog: Boolean = false,
    val genericPromptTitle: String = "",
    val genericPromptText: String = "",
    val genericReportsProgress: Int = 0,
    val genericReportsTotal: Int = 0,
    val genericReportsSelectedAgents: Set<String> = emptySet(),
    val genericReportsAgentResults: Map<String, AnalysisResponse> = emptyMap(),
    val currentReportId: String? = null,  // ID of the current AI report being generated
    // Report advanced parameters (override agent parameters for a specific report)
    val reportAdvancedParameters: AgentParameters? = null,
    // External intent parameters
    val externalSystemPrompt: String? = null,  // System prompt from intent
    // External intent instructions (from -- end prompt -- marker or instructions parameter)
    val externalCloseHtml: String? = null,
    val externalReportType: String? = null,  // "Classic" or "Table"
    val externalEmail: String? = null,
    val externalNextAction: String? = null,  // "View", "Share", "Browser", "Email"
    val externalReturn: Boolean = false,  // finish() after <next> action completes
    val externalEdit: Boolean = false,  // show New Report screen for prompt editing
    val externalSelect: Boolean = false,  // show selection screen even with API selection tags
    val externalOpenHtml: String? = null,  // <open> content stored separately for <edit> mode
    // External intent API selections (from <agent>, <flock>, <swarm>, <model> tags)
    val externalAgentNames: List<String> = emptyList(),
    val externalFlockNames: List<String> = emptyList(),
    val externalSwarmNames: List<String> = emptyList(),
    val externalModelSpecs: List<String> = emptyList(),  // "provider/model" format
    // Chat
    val chatParameters: ChatParameters = ChatParameters(),
    // Dual chat
    val dualChatConfig: DualChatConfig? = null
)
