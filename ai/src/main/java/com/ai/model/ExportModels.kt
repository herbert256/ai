package com.ai.model

import com.ai.data.ProviderDefinition

/**
 * v21 export/import data classes. Import accepts v11-v21.
 */

data class ProviderConfigExport(
    val modelSource: String,
    val models: List<String>,
    val apiKey: String = "",
    val defaultModel: String? = null,
    val adminUrl: String? = null,
    val modelListUrl: String? = null,
    val parametersIds: List<String>? = null,
    val modelTypes: Map<String, String>? = null,
    val visionModels: List<String>? = null,
    val webSearchModels: List<String>? = null,
    val displayName: String? = null,
    val baseUrl: String? = null,
    val apiFormat: String? = null,
    val chatPath: String? = null,
    val typePaths: Map<String, String>? = null,
    val modelsPath: String? = null,
    val openRouterName: String? = null,
    /** Deprecated — kept on the import shape so old export bundles still
     *  parse, but ignored at dispatch time. ModelType.infer drives
     *  Responses-vs-Chat routing now. */
    @Suppress("unused")
    val endpointRules: List<Map<String, String>>? = null
)

data class AgentExport(
    val id: String, val name: String, val provider: String, val model: String,
    val apiKey: String, val parametersIds: List<String>? = null,
    val endpointId: String? = null, val systemPromptId: String? = null
)

data class FlockExport(
    val id: String, val name: String, val agentIds: List<String>,
    val parametersIds: List<String>? = null, val systemPromptId: String? = null
)

data class SwarmMemberExport(val provider: String, val model: String)

data class SwarmExport(
    val id: String, val name: String, val members: List<SwarmMemberExport>,
    val parametersIds: List<String>? = null, val systemPromptId: String? = null
)

data class PromptExport(val id: String, val name: String, val agentId: String, val promptText: String)

data class ParametersExport(
    val id: String, val name: String,
    val temperature: Float? = null, val maxTokens: Int? = null,
    val topP: Float? = null, val topK: Int? = null,
    val frequencyPenalty: Float? = null, val presencePenalty: Float? = null,
    val systemPrompt: String? = null, val stopSequences: List<String>? = null,
    val seed: Int? = null, val responseFormatJson: Boolean = false,
    val searchEnabled: Boolean = false, val returnCitations: Boolean = true,
    val searchRecency: String? = null,
    val webSearchTool: Boolean = false,
    val reasoningEffort: String? = null
)

data class SystemPromptExport(val id: String, val name: String, val prompt: String)

data class ManualPricingExport(val key: String, val promptPrice: Double, val completionPrice: Double)

data class EndpointExport(val id: String, val name: String, val url: String, val isDefault: Boolean = false)

data class ProviderEndpointsExport(val provider: String, val endpoints: List<EndpointExport>)

data class ConfigExport(
    val version: Int = 21,
    val providers: Map<String, ProviderConfigExport>,
    val agents: List<AgentExport>,
    val flocks: List<FlockExport>? = null,
    val swarms: List<SwarmExport>? = null,
    val parameters: List<ParametersExport>? = null,
    val systemPrompts: List<SystemPromptExport>? = null,
    val huggingFaceApiKey: String? = null,
    val aiPrompts: List<PromptExport>? = null,
    val manualPricing: List<ManualPricingExport>? = null,
    val providerEndpoints: List<ProviderEndpointsExport>? = null,
    val openRouterApiKey: String? = null,
    val artificialAnalysisApiKey: String? = null,
    val providerDefinitions: List<ProviderDefinition>? = null,
    val providerStates: Map<String, String>? = null,
    val modelTypeOverrides: List<ModelTypeOverride>? = null,
    val defaultTypePaths: Map<String, String>? = null,
    /** User-managed Meta prompts (Rerank / Summarize / Compare /
     *  Moderation and any custom variants). Null when the user has
     *  none, in which case the importer leaves the existing list
     *  untouched. */
    val metaPrompts: List<MetaPromptExport>? = null,
    /** Self-introduction template (was an Internal Prompt named "intro";
     *  Internal Prompts have been removed). Null when unset. */
    val introPrompt: String? = null,
    /** Model-info template (was an Internal Prompt named "model_info";
     *  Internal Prompts have been removed). Null when unset. */
    val modelInfoPrompt: String? = null,
    /** Translation prompt — used by the Translate button on the
     *  Report result screen. Null when unset (runtime falls back to
     *  SecondaryPrompts.DEFAULT_TRANSLATE). */
    val translatePrompt: String? = null
)

data class MetaPromptExport(
    val id: String,
    val name: String,
    val type: String,
    val reference: Boolean = false,
    val text: String = ""
)

data class ApiKeyEntry(val service: String, val apiKey: String)

data class ApiKeysExport(
    val type: String = "api_keys",
    val keys: List<ApiKeyEntry>,
    val huggingFaceApiKey: String? = null,
    val openRouterApiKey: String? = null,
    val artificialAnalysisApiKey: String? = null
)

data class ConfigImportResult(
    val aiSettings: Settings,
    val huggingFaceApiKey: String? = null,
    val openRouterApiKey: String? = null,
    val artificialAnalysisApiKey: String? = null,
    val defaultTypePaths: Map<String, String>? = null,
    val introPrompt: String? = null,
    val modelInfoPrompt: String? = null,
    /** Translation template — used by the Report result screen's
     *  Translate button. Null when unset (runtime falls back to
     *  SecondaryPrompts.DEFAULT_TRANSLATE). */
    val translatePrompt: String? = null
)
