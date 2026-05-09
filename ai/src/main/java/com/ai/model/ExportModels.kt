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
    /** Legacy override layer (pre-v24). The runtime ProviderConfig
     *  used to carry an Admin URL override that shadowed the catalog
     *  value; v24+ moves all Admin URL editing into the catalog. The
     *  field stays here as a nullable input so old export bundles
     *  still parse — the importer migrates a non-null value into the
     *  catalog via [com.ai.data.ProviderRegistry.update]. */
    val adminUrl: String? = null,
    /** Legacy override layer (pre-v24). modelListUrl had no consumer
     *  in dispatch and is dropped from the data model in v24. The
     *  importer logs and discards a non-null value. */
    val modelListUrl: String? = null,
    val parametersIds: List<String>? = null,
    val modelTypes: Map<String, String>? = null,
    val visionModels: List<String>? = null,
    val webSearchModels: List<String>? = null,
    val displayName: String? = null,
    val baseUrl: String? = null,
    /** Catalog Admin URL — v24+ exports carry the unified value here.
     *  Pre-v24 exports populate [adminUrl] (the override field) but
     *  not this one; the importer falls back accordingly. */
    val catalogAdminUrl: String? = null,
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
    val version: Int = 23,
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
    /** User-managed Internal prompts (covers Meta + Internal categories
     *  in one list). Null when the importer should leave the user's
     *  existing list untouched. */
    val internalPrompts: List<InternalPromptExport>? = null,
    /** Legacy GeneralSettings prompt fields, kept here only so v11–v22
     *  bundles still deserialise. The importer ignores these for
     *  writing — those templates are now [InternalPrompt] entries
     *  managed via the unified Internal Prompts CRUD. */
    val introPrompt: String? = null,
    val modelInfoPrompt: String? = null,
    val translatePrompt: String? = null,
    /** Legacy v22 field — kept on the wire so older bundles still
     *  parse. Importer treats `metaPrompts` as a legacy alias of
     *  [internalPrompts] when the new field is absent. */
    val metaPrompts: List<InternalPromptExport>? = null
)

data class InternalPromptExport(
    val id: String,
    val name: String,
    val reference: Boolean = false,
    val category: String = "internal",
    val agent: String = "*select",
    val text: String = "",
    val title: String = ""
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
    val defaultTypePaths: Map<String, String>? = null
)
