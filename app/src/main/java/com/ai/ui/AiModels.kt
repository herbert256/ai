package com.ai.ui

import com.ai.data.AiAnalysisResponse

// General app settings
data class GeneralSettings(
    val userName: String = "user",
    val developerMode: Boolean = false,
    val trackApiCalls: Boolean = false,
    val huggingFaceApiKey: String = "",
    val openRouterApiKey: String = "",
    val fullScreenMode: Boolean = false
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
    // Model availability for each provider
    val availableChatGptModels: List<String> = emptyList(),
    val isLoadingChatGptModels: Boolean = false,
    val availableGeminiModels: List<String> = emptyList(),
    val isLoadingGeminiModels: Boolean = false,
    val availableGrokModels: List<String> = emptyList(),
    val isLoadingGrokModels: Boolean = false,
    val availableGroqModels: List<String> = emptyList(),
    val isLoadingGroqModels: Boolean = false,
    val availableDeepSeekModels: List<String> = emptyList(),
    val isLoadingDeepSeekModels: Boolean = false,
    val availableMistralModels: List<String> = emptyList(),
    val isLoadingMistralModels: Boolean = false,
    val availablePerplexityModels: List<String> = emptyList(),
    val isLoadingPerplexityModels: Boolean = false,
    val availableTogetherModels: List<String> = emptyList(),
    val isLoadingTogetherModels: Boolean = false,
    val availableOpenRouterModels: List<String> = emptyList(),
    val isLoadingOpenRouterModels: Boolean = false,
    val availableDummyModels: List<String> = emptyList(),
    val isLoadingDummyModels: Boolean = false,
    val availableClaudeModels: List<String> = emptyList(),
    val isLoadingClaudeModels: Boolean = false,
    val availableSiliconFlowModels: List<String> = emptyList(),
    val isLoadingSiliconFlowModels: Boolean = false,
    val availableZaiModels: List<String> = emptyList(),
    val isLoadingZaiModels: Boolean = false,
    val availableMoonshotModels: List<String> = emptyList(),
    val isLoadingMoonshotModels: Boolean = false,
    val availableCohereModels: List<String> = emptyList(),
    val isLoadingCohereModels: Boolean = false,
    val availableAi21Models: List<String> = emptyList(),
    val isLoadingAi21Models: Boolean = false,
    val availableDashScopeModels: List<String> = emptyList(),
    val isLoadingDashScopeModels: Boolean = false,
    val availableFireworksModels: List<String> = emptyList(),
    val isLoadingFireworksModels: Boolean = false,
    val availableCerebrasModels: List<String> = emptyList(),
    val isLoadingCerebrasModels: Boolean = false,
    val availableSambaNovaModels: List<String> = emptyList(),
    val isLoadingSambaNovaModels: Boolean = false,
    val availableBaichuanModels: List<String> = emptyList(),
    val isLoadingBaichuanModels: Boolean = false,
    val availableStepFunModels: List<String> = emptyList(),
    val isLoadingStepFunModels: Boolean = false,
    val availableMiniMaxModels: List<String> = emptyList(),
    val isLoadingMiniMaxModels: Boolean = false,
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
    // Chat
    val chatParameters: ChatParameters = ChatParameters()
)
