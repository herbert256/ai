package com.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.data.AiService
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Data class for provider settings in JSON export/import.
 * Version 11: Added defaultModel, adminUrl, modelListUrl.
 */
data class ProviderConfigExport(
    val modelSource: String,  // "API" or "MANUAL"
    val manualModels: List<String>,
    val apiKey: String = "",   // API key for the provider
    val defaultModel: String? = null,  // Version 11+
    val adminUrl: String? = null,       // Version 11+
    val modelListUrl: String? = null    // Version 11+
)

/**
 * Data class for agent parameters in JSON export/import (version 5+).
 */
data class AgentParametersExport(
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val systemPrompt: String? = null,
    val stopSequences: List<String>? = null,
    val seed: Int? = null,
    val responseFormatJson: Boolean = false,
    val searchEnabled: Boolean = false,
    val returnCitations: Boolean = true,
    val searchRecency: String? = null
)

/**
 * Data class for agent in JSON export/import.
 * Version 4: Basic agent info only.
 * Version 5: Added parameters field.
 */
data class AgentExport(
    val id: String,
    val name: String,
    val provider: String,  // Provider enum name (OPENAI, ANTHROPIC, GOOGLE, XAI, etc.)
    val model: String,
    val apiKey: String,
    // Parameters (version 5+)
    val parameters: AgentParametersExport? = null,
    // Endpoint ID (version 9+) - references a custom endpoint for this provider
    val endpointId: String? = null,
    // Legacy fields from version 3 (ignored on import, included for compatibility)
    val gamePromptId: String? = null,
    val serverPlayerPromptId: String? = null,
    val otherPlayerPromptId: String? = null
)

/**
 * Data class for flock in JSON export/import (version 6+).
 * Version 14+: Added paramsId.
 */
data class FlockExport(
    val id: String,
    val name: String,
    val agentIds: List<String>,
    val paramsId: String? = null  // Version 14+
)

/**
 * Data class for swarm member in JSON export/import (version 13+).
 */
data class SwarmMemberExport(
    val provider: String,  // Provider enum name
    val model: String
)

/**
 * Data class for swarm in JSON export/import (version 13+).
 * Version 14+: Added paramsId.
 */
data class SwarmExport(
    val id: String,
    val name: String,
    val members: List<SwarmMemberExport>,
    val paramsId: String? = null  // Version 14+
)

// ============================================================================
// Legacy data classes for backward compatibility with pre-rename exports
// Before version 14, the names were swapped:
// - Old "swarms" had agentIds (groups of agents) → now called "flocks"
// - Old "flocks" had members (provider/model pairs) → now called "swarms"
// ============================================================================

/**
 * Legacy swarm format (pre-version 14) - had agentIds, now called "flock".
 */
data class LegacySwarmExport(
    val id: String,
    val name: String,
    val agentIds: List<String>,
    val paramsId: String? = null
)

/**
 * Legacy flock member format (pre-version 14).
 */
data class LegacyFlockMemberExport(
    val provider: String,
    val model: String
)

/**
 * Legacy flock format (pre-version 14) - had members, now called "swarm".
 */
data class LegacyFlockExport(
    val id: String,
    val name: String,
    val members: List<LegacyFlockMemberExport>,
    val paramsId: String? = null
)

/**
 * Legacy config export class for detecting pre-rename format.
 */
data class LegacyAiConfigExport(
    val version: Int = 0,
    val providers: Map<String, ProviderConfigExport>? = null,
    val agents: List<AgentExport>? = null,
    // Legacy fields - before the Swarms/Flocks rename
    val swarms: List<LegacySwarmExport>? = null,  // Old swarms had agentIds
    val flocks: List<LegacyFlockExport>? = null,  // Old flocks had members
    val params: List<ParamsExport>? = null,
    val huggingFaceApiKey: String? = null,
    val aiPrompts: List<PromptExport>? = null,
    val manualPricing: List<ManualPricingExport>? = null,
    val providerEndpoints: List<ProviderEndpointsExport>? = null,
    val openRouterApiKey: String? = null
)

/**
 * Data class for prompt in JSON export/import (version 8+).
 */
data class PromptExport(
    val id: String,
    val name: String,
    val agentId: String,
    val promptText: String
)

/**
 * Data class for params (parameter presets) in JSON export/import (version 14+).
 */
data class ParamsExport(
    val id: String,
    val name: String,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val systemPrompt: String? = null,
    val stopSequences: List<String>? = null,
    val seed: Int? = null,
    val responseFormatJson: Boolean = false,
    val searchEnabled: Boolean = false,
    val returnCitations: Boolean = false,
    val searchRecency: String? = null
)

/**
 * Data class for manual pricing override in JSON export/import (version 9+).
 * Key format: "PROVIDER:model" (e.g., "OPENAI:gpt-4o")
 */
data class ManualPricingExport(
    val key: String,           // "PROVIDER:model"
    val promptPrice: Double,   // Per token price
    val completionPrice: Double // Per token price
)

/**
 * Data class for endpoint in JSON export/import (version 10+).
 */
data class EndpointExport(
    val id: String,
    val name: String,
    val url: String,
    val isDefault: Boolean = false
)

/**
 * Data class for endpoints grouped by provider (version 10+).
 */
data class ProviderEndpointsExport(
    val provider: String,  // Provider enum name
    val endpoints: List<EndpointExport>
)

/**
 * Data class for the complete AI configuration export.
 * Version 11: Complete provider settings including defaultModel, adminUrl, modelListUrl,
 *             endpoints, agents with parameters, flocks, AI prompts, manual pricing.
 * Version 14: Added params (reusable parameter presets).
 */
data class AiConfigExport(
    val version: Int = 14,
    val providers: Map<String, ProviderConfigExport>,
    val agents: List<AgentExport>,
    val flocks: List<FlockExport>? = null,  // Version 6+
    val swarms: List<SwarmExport>? = null,  // Version 13+
    val params: List<ParamsExport>? = null,  // Version 14+
    val huggingFaceApiKey: String? = null,  // Version 7+
    val aiPrompts: List<PromptExport>? = null,  // Version 8+
    val manualPricing: List<ManualPricingExport>? = null,  // Version 9+
    val providerEndpoints: List<ProviderEndpointsExport>? = null,  // Version 10+
    val openRouterApiKey: String? = null,  // Version 12+
    // Legacy field from version 3 (ignored on import)
    val prompts: List<Any>? = null
)

/**
 * Data class for API key export entry.
 */
data class ApiKeyEntry(
    val service: String,
    val apiKey: String
)

/**
 * Result of importing AI configuration.
 */
data class AiConfigImportResult(
    val aiSettings: AiSettings,
    val huggingFaceApiKey: String? = null,
    val openRouterApiKey: String? = null
)

/**
 * Export AI configuration to a file and share via Android share sheet.
 * Exports providers (model config), agents, flocks, huggingFaceApiKey, and openRouterApiKey.
 */
fun exportAiConfigToFile(context: Context, aiSettings: AiSettings, huggingFaceApiKey: String = "", openRouterApiKey: String = "") {
    // Build providers map (model source, manual models, API key, default model, admin URL, model list URL per provider)
    val providers = mapOf(
        "OPENAI" to ProviderConfigExport(aiSettings.chatGptModelSource.name, aiSettings.chatGptManualModels, aiSettings.chatGptApiKey, aiSettings.chatGptModel, aiSettings.chatGptAdminUrl, aiSettings.chatGptModelListUrl.ifBlank { null }),
        "ANTHROPIC" to ProviderConfigExport(aiSettings.claudeModelSource.name, aiSettings.claudeManualModels, aiSettings.claudeApiKey, aiSettings.claudeModel, aiSettings.claudeAdminUrl, aiSettings.claudeModelListUrl.ifBlank { null }),
        "GOOGLE" to ProviderConfigExport(aiSettings.geminiModelSource.name, aiSettings.geminiManualModels, aiSettings.geminiApiKey, aiSettings.geminiModel, aiSettings.geminiAdminUrl, aiSettings.geminiModelListUrl.ifBlank { null }),
        "XAI" to ProviderConfigExport(aiSettings.grokModelSource.name, aiSettings.grokManualModels, aiSettings.grokApiKey, aiSettings.grokModel, aiSettings.grokAdminUrl, aiSettings.grokModelListUrl.ifBlank { null }),
        "GROQ" to ProviderConfigExport(aiSettings.groqModelSource.name, aiSettings.groqManualModels, aiSettings.groqApiKey, aiSettings.groqModel, aiSettings.groqAdminUrl, aiSettings.groqModelListUrl.ifBlank { null }),
        "DEEPSEEK" to ProviderConfigExport(aiSettings.deepSeekModelSource.name, aiSettings.deepSeekManualModels, aiSettings.deepSeekApiKey, aiSettings.deepSeekModel, aiSettings.deepSeekAdminUrl, aiSettings.deepSeekModelListUrl.ifBlank { null }),
        "MISTRAL" to ProviderConfigExport(aiSettings.mistralModelSource.name, aiSettings.mistralManualModels, aiSettings.mistralApiKey, aiSettings.mistralModel, aiSettings.mistralAdminUrl, aiSettings.mistralModelListUrl.ifBlank { null }),
        "PERPLEXITY" to ProviderConfigExport(aiSettings.perplexityModelSource.name, aiSettings.perplexityManualModels, aiSettings.perplexityApiKey, aiSettings.perplexityModel, aiSettings.perplexityAdminUrl, aiSettings.perplexityModelListUrl.ifBlank { null }),
        "TOGETHER" to ProviderConfigExport(aiSettings.togetherModelSource.name, aiSettings.togetherManualModels, aiSettings.togetherApiKey, aiSettings.togetherModel, aiSettings.togetherAdminUrl, aiSettings.togetherModelListUrl.ifBlank { null }),
        "OPENROUTER" to ProviderConfigExport(aiSettings.openRouterModelSource.name, aiSettings.openRouterManualModels, aiSettings.openRouterApiKey, aiSettings.openRouterModel, aiSettings.openRouterAdminUrl, aiSettings.openRouterModelListUrl.ifBlank { null }),
        "SILICONFLOW" to ProviderConfigExport(aiSettings.siliconFlowModelSource.name, aiSettings.siliconFlowManualModels, aiSettings.siliconFlowApiKey, aiSettings.siliconFlowModel, aiSettings.siliconFlowAdminUrl, aiSettings.siliconFlowModelListUrl.ifBlank { null }),
        "ZAI" to ProviderConfigExport(aiSettings.zaiModelSource.name, aiSettings.zaiManualModels, aiSettings.zaiApiKey, aiSettings.zaiModel, aiSettings.zaiAdminUrl, aiSettings.zaiModelListUrl.ifBlank { null }),
        "MOONSHOT" to ProviderConfigExport(aiSettings.moonshotModelSource.name, aiSettings.moonshotManualModels, aiSettings.moonshotApiKey, aiSettings.moonshotModel, aiSettings.moonshotAdminUrl, aiSettings.moonshotModelListUrl.ifBlank { null }),
        "DUMMY" to ProviderConfigExport(aiSettings.dummyModelSource.name, aiSettings.dummyManualModels, aiSettings.dummyApiKey, aiSettings.dummyModel, aiSettings.dummyAdminUrl, aiSettings.dummyModelListUrl.ifBlank { null })
    )

    // Convert agents with parameters
    val agents = aiSettings.agents.map { agent ->
        AgentExport(
            id = agent.id,
            name = agent.name,
            provider = agent.provider.name,
            model = agent.model,
            apiKey = agent.apiKey,
            parameters = AgentParametersExport(
                temperature = agent.parameters.temperature,
                maxTokens = agent.parameters.maxTokens,
                topP = agent.parameters.topP,
                topK = agent.parameters.topK,
                frequencyPenalty = agent.parameters.frequencyPenalty,
                presencePenalty = agent.parameters.presencePenalty,
                systemPrompt = agent.parameters.systemPrompt,
                stopSequences = agent.parameters.stopSequences,
                seed = agent.parameters.seed,
                responseFormatJson = agent.parameters.responseFormatJson,
                searchEnabled = agent.parameters.searchEnabled,
                returnCitations = agent.parameters.returnCitations,
                searchRecency = agent.parameters.searchRecency
            ),
            endpointId = agent.endpointId
        )
    }

    // Convert flocks
    val flocks = aiSettings.flocks.map { flock ->
        FlockExport(
            id = flock.id,
            name = flock.name,
            agentIds = flock.agentIds,
            paramsId = flock.paramsId
        )
    }

    // Convert swarms
    val swarms = aiSettings.swarms.map { swarm ->
        SwarmExport(
            id = swarm.id,
            name = swarm.name,
            members = swarm.members.map { member ->
                SwarmMemberExport(
                    provider = member.provider.name,
                    model = member.model
                )
            },
            paramsId = swarm.paramsId
        )
    }

    // Convert prompts
    val aiPrompts = aiSettings.prompts.map { prompt ->
        PromptExport(
            id = prompt.id,
            name = prompt.name,
            agentId = prompt.agentId,
            promptText = prompt.promptText
        )
    }

    // Convert params (parameter presets)
    val params = aiSettings.params.map { param ->
        ParamsExport(
            id = param.id,
            name = param.name,
            temperature = param.temperature,
            maxTokens = param.maxTokens,
            topP = param.topP,
            topK = param.topK,
            frequencyPenalty = param.frequencyPenalty,
            presencePenalty = param.presencePenalty,
            systemPrompt = param.systemPrompt,
            stopSequences = param.stopSequences,
            seed = param.seed,
            responseFormatJson = param.responseFormatJson,
            searchEnabled = param.searchEnabled,
            returnCitations = param.returnCitations,
            searchRecency = param.searchRecency
        )
    }

    // Convert manual pricing overrides
    val manualPricingMap = com.ai.data.PricingCache.getAllManualPricing(context)
    val manualPricing = manualPricingMap.map { (key, pricing) ->
        ManualPricingExport(
            key = key,
            promptPrice = pricing.promptPrice,
            completionPrice = pricing.completionPrice
        )
    }

    // Convert endpoints
    val providerEndpoints = aiSettings.endpoints.mapNotNull { (provider, endpoints) ->
        if (endpoints.isNotEmpty()) {
            ProviderEndpointsExport(
                provider = provider.name,
                endpoints = endpoints.map { endpoint ->
                    EndpointExport(
                        id = endpoint.id,
                        name = endpoint.name,
                        url = endpoint.url,
                        isDefault = endpoint.isDefault
                    )
                }
            )
        } else null
    }

    val export = AiConfigExport(
        providers = providers,
        agents = agents,
        flocks = flocks,
        swarms = swarms.ifEmpty { null },
        params = params.ifEmpty { null },
        huggingFaceApiKey = huggingFaceApiKey.ifBlank { null },
        aiPrompts = aiPrompts.ifEmpty { null },
        manualPricing = manualPricing.ifEmpty { null },
        providerEndpoints = providerEndpoints.ifEmpty { null },
        openRouterApiKey = openRouterApiKey.ifBlank { null }
    )

    val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
    val json = gson.toJson(export)

    try {
        // Create file in cache/ai_analysis directory (must match FileProvider paths)
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val fileName = "ai_config_$timestamp.json"
        val cacheDir = java.io.File(context.cacheDir, "ai_analysis")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val file = java.io.File(cacheDir, fileName)
        file.writeText(json)

        // Get URI via FileProvider
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        // Create share intent
        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(android.content.Intent.createChooser(shareIntent, "Export AI Configuration"))
    } catch (e: Exception) {
        Toast.makeText(context, "Error exporting: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Export API keys only to clipboard as JSON array.
 */
fun exportApiKeysToClipboard(context: Context, aiSettings: AiSettings) {
    val keys = mutableListOf<ApiKeyEntry>()

    if (aiSettings.chatGptApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("OpenAI", aiSettings.chatGptApiKey))
    }
    if (aiSettings.claudeApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("Anthropic", aiSettings.claudeApiKey))
    }
    if (aiSettings.geminiApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("Google", aiSettings.geminiApiKey))
    }
    if (aiSettings.grokApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("xAI", aiSettings.grokApiKey))
    }
    if (aiSettings.groqApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("Groq", aiSettings.groqApiKey))
    }
    if (aiSettings.deepSeekApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("DeepSeek", aiSettings.deepSeekApiKey))
    }
    if (aiSettings.mistralApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("Mistral", aiSettings.mistralApiKey))
    }
    if (aiSettings.perplexityApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("Perplexity", aiSettings.perplexityApiKey))
    }
    if (aiSettings.togetherApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("Together", aiSettings.togetherApiKey))
    }
    if (aiSettings.openRouterApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("OpenRouter", aiSettings.openRouterApiKey))
    }
    if (aiSettings.siliconFlowApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("SiliconFlow", aiSettings.siliconFlowApiKey))
    }
    if (aiSettings.zaiApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("Z.AI", aiSettings.zaiApiKey))
    }
    if (aiSettings.moonshotApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("Moonshot", aiSettings.moonshotApiKey))
    }

    val gson = Gson()
    val json = gson.toJson(keys)

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("API Keys", json)
    clipboard.setPrimaryClip(clip)

    Toast.makeText(context, "${keys.size} API keys copied to clipboard", Toast.LENGTH_SHORT).show()
}

/**
 * Helper function to process imported AI configuration.
 * Contains the common logic shared between clipboard and file import.
 */
private fun processImportedConfig(
    context: Context,
    export: AiConfigExport,
    currentSettings: AiSettings
): AiConfigImportResult {
    // Import agents with parameters
    val agents = export.agents.mapNotNull { agentExport ->
        val provider = try {
            AiService.valueOf(agentExport.provider)
        } catch (e: IllegalArgumentException) {
            null  // Skip agents with unknown providers
        }
        provider?.let {
            AiAgent(
                id = agentExport.id,
                name = agentExport.name,
                provider = it,
                model = agentExport.model,
                apiKey = agentExport.apiKey,
                endpointId = agentExport.endpointId,
                parameters = agentExport.parameters?.let { p ->
                    AiAgentParameters(
                        temperature = p.temperature,
                        maxTokens = p.maxTokens,
                        topP = p.topP,
                        topK = p.topK,
                        frequencyPenalty = p.frequencyPenalty,
                        presencePenalty = p.presencePenalty,
                        systemPrompt = p.systemPrompt,
                        stopSequences = p.stopSequences,
                        seed = p.seed,
                        responseFormatJson = p.responseFormatJson,
                        searchEnabled = p.searchEnabled,
                        returnCitations = p.returnCitations,
                        searchRecency = p.searchRecency
                    )
                } ?: AiAgentParameters()
            )
        }
    }

    // Import flocks
    val flocks = export.flocks?.map { flockExport ->
        AiFlock(
            id = flockExport.id,
            name = flockExport.name,
            agentIds = flockExport.agentIds,
            paramsId = flockExport.paramsId
        )
    } ?: emptyList()

    // Import swarms
    val swarms = export.swarms?.mapNotNull { swarmExport ->
        try {
            AiSwarm(
                id = swarmExport.id,
                name = swarmExport.name,
                members = swarmExport.members.mapNotNull { memberExport ->
                    try {
                        AiSwarmMember(
                            provider = AiService.valueOf(memberExport.provider),
                            model = memberExport.model
                        )
                    } catch (e: Exception) {
                        null  // Skip invalid provider
                    }
                },
                paramsId = swarmExport.paramsId
            )
        } catch (e: Exception) {
            null
        }
    } ?: emptyList()

    // Import AI prompts
    val aiPrompts = export.aiPrompts?.map { promptExport ->
        AiPrompt(
            id = promptExport.id,
            name = promptExport.name,
            agentId = promptExport.agentId,
            promptText = promptExport.promptText
        )
    } ?: emptyList()

    // Import params (parameter presets)
    val params = export.params?.map { paramsExport ->
        AiParams(
            id = paramsExport.id,
            name = paramsExport.name,
            temperature = paramsExport.temperature,
            maxTokens = paramsExport.maxTokens,
            topP = paramsExport.topP,
            topK = paramsExport.topK,
            frequencyPenalty = paramsExport.frequencyPenalty,
            presencePenalty = paramsExport.presencePenalty,
            systemPrompt = paramsExport.systemPrompt,
            stopSequences = paramsExport.stopSequences,
            seed = paramsExport.seed,
            responseFormatJson = paramsExport.responseFormatJson,
            searchEnabled = paramsExport.searchEnabled,
            returnCitations = paramsExport.returnCitations,
            searchRecency = paramsExport.searchRecency
        )
    } ?: emptyList()

    // Import provider settings
    var settings = currentSettings.copy(
        agents = agents,
        flocks = flocks,
        swarms = swarms,
        params = params,
        prompts = aiPrompts
    )

    // Helper to import a single provider's settings
    fun importProvider(
        providerKey: String,
        update: (ProviderConfigExport) -> AiSettings
    ) {
        export.providers[providerKey]?.let { p ->
            settings = update(p)
        }
    }

    // Update all provider settings
    importProvider("OPENAI") { p ->
        settings.copy(
            chatGptModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
            chatGptManualModels = p.manualModels,
            chatGptApiKey = p.apiKey,
            chatGptModel = p.defaultModel ?: settings.chatGptModel,
            chatGptAdminUrl = p.adminUrl ?: settings.chatGptAdminUrl,
            chatGptModelListUrl = p.modelListUrl ?: ""
        )
    }
    importProvider("ANTHROPIC") { p ->
        settings.copy(
            claudeModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.MANUAL },
            claudeManualModels = p.manualModels,
            claudeApiKey = p.apiKey,
            claudeModel = p.defaultModel ?: settings.claudeModel,
            claudeAdminUrl = p.adminUrl ?: settings.claudeAdminUrl,
            claudeModelListUrl = p.modelListUrl ?: ""
        )
    }
    importProvider("GOOGLE") { p ->
        settings.copy(
            geminiModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
            geminiManualModels = p.manualModels,
            geminiApiKey = p.apiKey,
            geminiModel = p.defaultModel ?: settings.geminiModel,
            geminiAdminUrl = p.adminUrl ?: settings.geminiAdminUrl,
            geminiModelListUrl = p.modelListUrl ?: ""
        )
    }
    importProvider("XAI") { p ->
        settings.copy(
            grokModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
            grokManualModels = p.manualModels,
            grokApiKey = p.apiKey,
            grokModel = p.defaultModel ?: settings.grokModel,
            grokAdminUrl = p.adminUrl ?: settings.grokAdminUrl,
            grokModelListUrl = p.modelListUrl ?: ""
        )
    }
    importProvider("GROQ") { p ->
        settings.copy(
            groqModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
            groqManualModels = p.manualModels,
            groqApiKey = p.apiKey,
            groqModel = p.defaultModel ?: settings.groqModel,
            groqAdminUrl = p.adminUrl ?: settings.groqAdminUrl,
            groqModelListUrl = p.modelListUrl ?: ""
        )
    }
    importProvider("DEEPSEEK") { p ->
        settings.copy(
            deepSeekModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
            deepSeekManualModels = p.manualModels,
            deepSeekApiKey = p.apiKey,
            deepSeekModel = p.defaultModel ?: settings.deepSeekModel,
            deepSeekAdminUrl = p.adminUrl ?: settings.deepSeekAdminUrl,
            deepSeekModelListUrl = p.modelListUrl ?: ""
        )
    }
    importProvider("MISTRAL") { p ->
        settings.copy(
            mistralModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
            mistralManualModels = p.manualModels,
            mistralApiKey = p.apiKey,
            mistralModel = p.defaultModel ?: settings.mistralModel,
            mistralAdminUrl = p.adminUrl ?: settings.mistralAdminUrl,
            mistralModelListUrl = p.modelListUrl ?: ""
        )
    }
    importProvider("PERPLEXITY") { p ->
        settings.copy(
            perplexityModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.MANUAL },
            perplexityManualModels = p.manualModels,
            perplexityApiKey = p.apiKey,
            perplexityModel = p.defaultModel ?: settings.perplexityModel,
            perplexityAdminUrl = p.adminUrl ?: settings.perplexityAdminUrl,
            perplexityModelListUrl = p.modelListUrl ?: ""
        )
    }
    importProvider("TOGETHER") { p ->
        settings.copy(
            togetherModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
            togetherManualModels = p.manualModels,
            togetherApiKey = p.apiKey,
            togetherModel = p.defaultModel ?: settings.togetherModel,
            togetherAdminUrl = p.adminUrl ?: settings.togetherAdminUrl,
            togetherModelListUrl = p.modelListUrl ?: ""
        )
    }
    importProvider("OPENROUTER") { p ->
        settings.copy(
            openRouterModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
            openRouterManualModels = p.manualModels,
            openRouterApiKey = p.apiKey,
            openRouterModel = p.defaultModel ?: settings.openRouterModel,
            openRouterAdminUrl = p.adminUrl ?: settings.openRouterAdminUrl,
            openRouterModelListUrl = p.modelListUrl ?: ""
        )
    }
    importProvider("SILICONFLOW") { p ->
        settings.copy(
            siliconFlowModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.MANUAL },
            siliconFlowManualModels = p.manualModels,
            siliconFlowApiKey = p.apiKey,
            siliconFlowModel = p.defaultModel ?: settings.siliconFlowModel,
            siliconFlowAdminUrl = p.adminUrl ?: settings.siliconFlowAdminUrl,
            siliconFlowModelListUrl = p.modelListUrl ?: ""
        )
    }
    importProvider("ZAI") { p ->
        settings.copy(
            zaiModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.MANUAL },
            zaiManualModels = p.manualModels,
            zaiApiKey = p.apiKey,
            zaiModel = p.defaultModel ?: settings.zaiModel,
            zaiAdminUrl = p.adminUrl ?: settings.zaiAdminUrl,
            zaiModelListUrl = p.modelListUrl ?: ""
        )
    }
    importProvider("MOONSHOT") { p ->
        settings.copy(
            moonshotModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.MANUAL },
            moonshotManualModels = p.manualModels,
            moonshotApiKey = p.apiKey,
            moonshotModel = p.defaultModel ?: settings.moonshotModel,
            moonshotAdminUrl = p.adminUrl ?: settings.moonshotAdminUrl,
            moonshotModelListUrl = p.modelListUrl ?: ""
        )
    }
    importProvider("DUMMY") { p ->
        settings.copy(
            dummyModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.MANUAL },
            dummyManualModels = p.manualModels,
            dummyApiKey = p.apiKey,
            dummyModel = p.defaultModel ?: settings.dummyModel,
            dummyAdminUrl = p.adminUrl ?: settings.dummyAdminUrl,
            dummyModelListUrl = p.modelListUrl ?: ""
        )
    }

    // Import manual pricing overrides
    export.manualPricing?.let { pricingList ->
        val pricingMap = pricingList.associate { mp ->
            mp.key to com.ai.data.PricingCache.ModelPricing(
                modelId = mp.key.substringAfter(":"),
                promptPrice = mp.promptPrice,
                completionPrice = mp.completionPrice,
                source = "manual"
            )
        }
        com.ai.data.PricingCache.setAllManualPricing(context, pricingMap)
    }

    // Import endpoints
    var settingsWithEndpoints = settings
    export.providerEndpoints?.forEach { providerEndpoints ->
        val provider = try {
            AiService.valueOf(providerEndpoints.provider)
        } catch (e: IllegalArgumentException) {
            null  // Skip unknown providers
        }
        provider?.let {
            val endpoints = providerEndpoints.endpoints.map { ep ->
                AiEndpoint(
                    id = ep.id,
                    name = ep.name,
                    url = ep.url,
                    isDefault = ep.isDefault
                )
            }
            settingsWithEndpoints = settingsWithEndpoints.withEndpoints(provider, endpoints)
        }
    }

    // Show summary toast
    val importedApiKeys = export.providers.values.count { it.apiKey.isNotBlank() }
    val importedPricing = export.manualPricing?.size ?: 0
    val importedEndpoints = export.providerEndpoints?.sumOf { it.endpoints.size } ?: 0
    val pricingMsg = if (importedPricing > 0) ", $importedPricing price overrides" else ""
    val endpointsMsg = if (importedEndpoints > 0) ", $importedEndpoints endpoints" else ""
    Toast.makeText(context, "Imported ${agents.size} agents, $importedApiKeys API keys$pricingMsg$endpointsMsg", Toast.LENGTH_SHORT).show()

    return AiConfigImportResult(settingsWithEndpoints, export.huggingFaceApiKey, export.openRouterApiKey)
}

/**
 * Detect if the JSON uses the old format (pre-rename where swarms had agentIds).
 * Returns true if old format is detected.
 */
private fun isOldFormat(json: String): Boolean {
    val gson = Gson()
    try {
        val jsonElement = gson.fromJson(json, com.google.gson.JsonElement::class.java)
        if (!jsonElement.isJsonObject) return false
        val obj = jsonElement.asJsonObject

        // Check if "swarms" array has entries with "agentIds" (old format)
        val swarmsArray = obj.getAsJsonArray("swarms")
        if (swarmsArray != null && swarmsArray.size() > 0) {
            val firstSwarm = swarmsArray.get(0).asJsonObject
            if (firstSwarm.has("agentIds")) {
                return true  // Old format: swarms had agentIds
            }
        }

        // Check if "flocks" array has entries with "members" (old format)
        val flocksArray = obj.getAsJsonArray("flocks")
        if (flocksArray != null && flocksArray.size() > 0) {
            val firstFlock = flocksArray.get(0).asJsonObject
            if (firstFlock.has("members")) {
                return true  // Old format: flocks had members
            }
        }
    } catch (e: Exception) {
        // Ignore parsing errors, assume new format
    }
    return false
}

/**
 * Convert legacy export to new format by swapping swarms and flocks.
 */
private fun convertLegacyExport(legacyExport: LegacyAiConfigExport): AiConfigExport {
    // Old swarms (with agentIds) become new flocks
    val newFlocks = legacyExport.swarms?.map { oldSwarm ->
        FlockExport(
            id = oldSwarm.id,
            name = oldSwarm.name,
            agentIds = oldSwarm.agentIds,
            paramsId = oldSwarm.paramsId
        )
    }

    // Old flocks (with members) become new swarms
    val newSwarms = legacyExport.flocks?.map { oldFlock ->
        SwarmExport(
            id = oldFlock.id,
            name = oldFlock.name,
            members = oldFlock.members.map { member ->
                SwarmMemberExport(
                    provider = member.provider,
                    model = member.model
                )
            },
            paramsId = oldFlock.paramsId
        )
    }

    return AiConfigExport(
        version = 14,  // Upgrade to current version
        providers = legacyExport.providers ?: emptyMap(),
        agents = legacyExport.agents ?: emptyList(),
        flocks = newFlocks,
        swarms = newSwarms,
        params = legacyExport.params,
        huggingFaceApiKey = legacyExport.huggingFaceApiKey,
        aiPrompts = legacyExport.aiPrompts,
        manualPricing = legacyExport.manualPricing,
        providerEndpoints = legacyExport.providerEndpoints,
        openRouterApiKey = legacyExport.openRouterApiKey
    )
}

/**
 * Import AI configuration from clipboard JSON.
 * Supports versions 11-14, with backward compatibility for pre-rename exports.
 */
fun importAiConfigFromClipboard(context: Context, currentSettings: AiSettings): AiConfigImportResult? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipData = clipboard.primaryClip

    if (clipData == null || clipData.itemCount == 0) {
        Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        return null
    }

    val json = clipData.getItemAt(0).text?.toString()
    if (json.isNullOrBlank()) {
        Toast.makeText(context, "No text in clipboard", Toast.LENGTH_SHORT).show()
        return null
    }

    return try {
        val gson = Gson()

        // Check if this is old format (pre-rename)
        val export = if (isOldFormat(json)) {
            val legacyExport = gson.fromJson(json, LegacyAiConfigExport::class.java)
            convertLegacyExport(legacyExport)
        } else {
            gson.fromJson(json, AiConfigExport::class.java)
        }

        if (export.version !in 11..14) {
            Toast.makeText(context, "Unsupported configuration version: ${export.version}. Expected version 11-14.", Toast.LENGTH_LONG).show()
            return null
        }

        processImportedConfig(context, export, currentSettings)
    } catch (e: JsonSyntaxException) {
        Toast.makeText(context, "Invalid AI configuration format", Toast.LENGTH_SHORT).show()
        null
    } catch (e: Exception) {
        Toast.makeText(context, "Error importing configuration: ${e.message}", Toast.LENGTH_SHORT).show()
        null
    }
}

/**
 * Import AI configuration from a file URI.
 * Supports versions 11-14, with backward compatibility for pre-rename exports.
 */
fun importAiConfigFromFile(context: Context, uri: Uri, currentSettings: AiSettings): AiConfigImportResult? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri)
        if (inputStream == null) {
            Toast.makeText(context, "Could not open file", Toast.LENGTH_SHORT).show()
            return null
        }

        val json = BufferedReader(InputStreamReader(inputStream)).use { reader ->
            reader.readText()
        }

        if (json.isBlank()) {
            Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show()
            return null
        }

        val gson = Gson()

        // Check if this is old format (pre-rename)
        val export = if (isOldFormat(json)) {
            val legacyExport = gson.fromJson(json, LegacyAiConfigExport::class.java)
            convertLegacyExport(legacyExport)
        } else {
            gson.fromJson(json, AiConfigExport::class.java)
        }

        if (export.version !in 11..14) {
            Toast.makeText(context, "Unsupported configuration version: ${export.version}. Expected version 11-14.", Toast.LENGTH_LONG).show()
            return null
        }

        processImportedConfig(context, export, currentSettings)
    } catch (e: JsonSyntaxException) {
        Toast.makeText(context, "Invalid AI configuration format", Toast.LENGTH_SHORT).show()
        null
    } catch (e: Exception) {
        Toast.makeText(context, "Error importing configuration: ${e.message}", Toast.LENGTH_SHORT).show()
        null
    }
}

/**
 * Dialog for importing AI configuration from clipboard.
 * @deprecated Use file picker with importAiConfigFromFile instead.
 */
@Composable
fun ImportAiConfigDialog(
    currentSettings: AiSettings,
    onImport: (AiSettings) -> Unit,
    onImportHuggingFaceApiKey: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Import AI Configuration",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "This will import AI configuration from the clipboard.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = "The clipboard should contain a JSON configuration exported from this app (version 11 or 12).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                Text(
                    text = "Warning: This will replace your agents, API keys, and provider settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF9800)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val result = importAiConfigFromClipboard(context, currentSettings)
                    if (result != null) {
                        onImport(result.aiSettings)
                        result.huggingFaceApiKey?.let { onImportHuggingFaceApiKey(it) }
                    }
                }
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
