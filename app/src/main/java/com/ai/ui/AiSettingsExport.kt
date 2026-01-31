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
    // Parameters (version 5-15, legacy inline parameters)
    val parameters: AgentParametersExport? = null,
    // Parameter preset IDs (version 16+) - references to AiParameters IDs
    val parametersIds: List<String>? = null,
    // Endpoint ID (version 9+) - references a custom endpoint for this provider
    val endpointId: String? = null,
    // Legacy fields from version 3 (ignored on import, included for compatibility)
    val gamePromptId: String? = null,
    val serverPlayerPromptId: String? = null,
    val otherPlayerPromptId: String? = null
)

/**
 * Data class for flock in JSON export/import (version 6+).
 * Version 14+: Added parametersId.
 */
data class FlockExport(
    val id: String,
    val name: String,
    val agentIds: List<String>,
    val parametersId: String? = null,  // Version 14 (legacy, single ID)
    val parametersIds: List<String>? = null  // Version 15+ (multi-select)
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
 * Version 14+: Added parametersId.
 */
data class SwarmExport(
    val id: String,
    val name: String,
    val members: List<SwarmMemberExport>,
    val parametersId: String? = null,  // Version 14 (legacy, single ID)
    val parametersIds: List<String>? = null  // Version 15+ (multi-select)
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
    val parametersId: String? = null
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
    val parametersId: String? = null
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
    val parameters: List<ParametersExport>? = null,
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
data class ParametersExport(
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
    val version: Int = 16,
    val providers: Map<String, ProviderConfigExport>,
    val agents: List<AgentExport>,
    val flocks: List<FlockExport>? = null,  // Version 6+
    val swarms: List<SwarmExport>? = null,  // Version 13+
    val parameters: List<ParametersExport>? = null,  // Version 14+
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
 * Data class for API keys-only export.
 */
data class ApiKeysExport(
    val type: String = "api_keys",
    val keys: List<ApiKeyEntry>,
    val huggingFaceApiKey: String? = null,
    val openRouterApiKey: String? = null
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
    val providers = AiService.entries.associate { service ->
        val config = aiSettings.getProvider(service)
        service.name to ProviderConfigExport(
            config.modelSource.name,
            config.manualModels,
            config.apiKey,
            config.model,
            config.adminUrl,
            config.modelListUrl.ifBlank { null }
        )
    }

    // Convert agents with parameter preset IDs
    val agents = aiSettings.agents.map { agent ->
        AgentExport(
            id = agent.id,
            name = agent.name,
            provider = agent.provider.name,
            model = agent.model,
            apiKey = agent.apiKey,
            parametersIds = agent.paramsIds.ifEmpty { null },
            endpointId = agent.endpointId
        )
    }

    // Convert flocks
    val flocks = aiSettings.flocks.map { flock ->
        FlockExport(
            id = flock.id,
            name = flock.name,
            agentIds = flock.agentIds,
            parametersIds = flock.paramsIds.ifEmpty { null }
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
            parametersIds = swarm.paramsIds.ifEmpty { null }
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
    val parameters = aiSettings.parameters.map { param ->
        ParametersExport(
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
        parameters = parameters.ifEmpty { null },
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
    val keys = AiService.entries.mapNotNull { service ->
        val apiKey = aiSettings.getApiKey(service)
        if (apiKey.isNotBlank()) ApiKeyEntry(service.displayName, apiKey) else null
    }

    val gson = Gson()
    val json = gson.toJson(keys)

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("API Keys", json)
    clipboard.setPrimaryClip(clip)

    Toast.makeText(context, "${keys.size} API keys copied to clipboard", Toast.LENGTH_SHORT).show()
}

/**
 * Export API keys only to a JSON file and share via Android share sheet.
 * Includes all provider API keys plus HuggingFace and OpenRouter general API keys.
 */
fun exportApiKeysToFile(context: Context, aiSettings: AiSettings, huggingFaceApiKey: String = "", openRouterApiKey: String = "") {
    val keys = AiService.entries.mapNotNull { service ->
        val apiKey = aiSettings.getApiKey(service)
        if (apiKey.isNotBlank()) ApiKeyEntry(service.name, apiKey) else null
    }

    val export = ApiKeysExport(
        keys = keys,
        huggingFaceApiKey = huggingFaceApiKey.ifBlank { null },
        openRouterApiKey = openRouterApiKey.ifBlank { null }
    )

    val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
    val json = gson.toJson(export)

    try {
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val fileName = "api_keys_$timestamp.json"
        val cacheDir = java.io.File(context.cacheDir, "ai_analysis")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val file = java.io.File(cacheDir, fileName)
        file.writeText(json)

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(android.content.Intent.createChooser(shareIntent, "Export API Keys"))
    } catch (e: Exception) {
        Toast.makeText(context, "Error exporting API keys: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Import API keys from a JSON file.
 * Returns a triple of (updated AiSettings, huggingFaceApiKey, openRouterApiKey) or null on failure.
 */
fun importApiKeysFromFile(context: Context, uri: Uri, currentSettings: AiSettings): Triple<AiSettings, String?, String?>? {
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
        val export = gson.fromJson(json, ApiKeysExport::class.java)

        if (export.type != "api_keys" || export.keys == null) {
            Toast.makeText(context, "Not an API keys file", Toast.LENGTH_SHORT).show()
            return null
        }

        var settings = currentSettings
        var importedCount = 0

        for (entry in export.keys) {
            if (entry.apiKey.isBlank()) continue
            val service = try {
                AiService.valueOf(entry.service)
            } catch (e: IllegalArgumentException) {
                // Try matching by display name for backward compatibility
                AiService.entries.firstOrNull { it.displayName == entry.service }
            }
            if (service != null) {
                settings = settings.withApiKey(service, entry.apiKey)
                importedCount++
            }
        }

        Toast.makeText(context, "Imported $importedCount API keys", Toast.LENGTH_SHORT).show()

        Triple(settings, export.huggingFaceApiKey, export.openRouterApiKey)
    } catch (e: com.google.gson.JsonSyntaxException) {
        Toast.makeText(context, "Invalid API keys file format", Toast.LENGTH_SHORT).show()
        null
    } catch (e: Exception) {
        Toast.makeText(context, "Error importing API keys: ${e.message}", Toast.LENGTH_SHORT).show()
        null
    }
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
    // Track any auto-created parameter presets from legacy inline agent parameters
    val autoCreatedPresets = mutableListOf<AiParameters>()

    // Import agents with parameter preset IDs
    val agents = export.agents.mapNotNull { agentExport ->
        val provider = try {
            AiService.valueOf(agentExport.provider)
        } catch (e: IllegalArgumentException) {
            null  // Skip agents with unknown providers
        }
        provider?.let {
            // Determine paramsIds: use new format if present, otherwise migrate old inline parameters
            val paramsIds = if (agentExport.parametersIds != null) {
                agentExport.parametersIds
            } else if (agentExport.parameters != null) {
                // Old format: auto-create a parameter preset from inline values
                val p = agentExport.parameters
                val hasAnyParam = p.temperature != null || p.maxTokens != null || p.topP != null ||
                    p.topK != null || p.frequencyPenalty != null || p.presencePenalty != null ||
                    p.systemPrompt != null || p.stopSequences != null || p.seed != null ||
                    p.responseFormatJson || p.searchEnabled || p.returnCitations || p.searchRecency != null
                if (hasAnyParam) {
                    val presetId = java.util.UUID.randomUUID().toString()
                    val preset = AiParameters(
                        id = presetId,
                        name = "Imported: ${agentExport.name}",
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
                    autoCreatedPresets.add(preset)
                    listOf(presetId)
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }

            AiAgent(
                id = agentExport.id,
                name = agentExport.name,
                provider = it,
                model = agentExport.model,
                apiKey = agentExport.apiKey,
                endpointId = agentExport.endpointId,
                paramsIds = paramsIds
            )
        }
    }

    // Import flocks
    val flocks = export.flocks?.map { flockExport ->
        AiFlock(
            id = flockExport.id,
            name = flockExport.name,
            agentIds = flockExport.agentIds,
            paramsIds = flockExport.parametersIds
                ?: flockExport.parametersId?.let { listOf(it) }
                ?: emptyList()
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
                paramsIds = swarmExport.parametersIds
                    ?: swarmExport.parametersId?.let { listOf(it) }
                    ?: emptyList()
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

    // Import params (parameter presets) + any auto-created presets from legacy inline agent parameters
    val parameters = (export.parameters?.map { parametersExport ->
        AiParameters(
            id = parametersExport.id,
            name = parametersExport.name,
            temperature = parametersExport.temperature,
            maxTokens = parametersExport.maxTokens,
            topP = parametersExport.topP,
            topK = parametersExport.topK,
            frequencyPenalty = parametersExport.frequencyPenalty,
            presencePenalty = parametersExport.presencePenalty,
            systemPrompt = parametersExport.systemPrompt,
            stopSequences = parametersExport.stopSequences,
            seed = parametersExport.seed,
            responseFormatJson = parametersExport.responseFormatJson,
            searchEnabled = parametersExport.searchEnabled,
            returnCitations = parametersExport.returnCitations,
            searchRecency = parametersExport.searchRecency
        )
    } ?: emptyList()) + autoCreatedPresets

    // Import provider settings
    var settings = currentSettings.copy(
        agents = agents,
        flocks = flocks,
        swarms = swarms,
        parameters = parameters,
        prompts = aiPrompts
    )

    // Update all provider settings from export
    for ((providerKey, p) in export.providers) {
        val service = try { AiService.valueOf(providerKey) } catch (e: IllegalArgumentException) { continue }
        val currentConfig = settings.getProvider(service)
        val defaultModelSource = defaultProviderConfig(service).modelSource
        val importedConfig = currentConfig.copy(
            modelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { defaultModelSource },
            manualModels = p.manualModels,
            apiKey = p.apiKey,
            model = p.defaultModel ?: currentConfig.model,
            adminUrl = p.adminUrl ?: currentConfig.adminUrl,
            modelListUrl = p.modelListUrl ?: ""
        )
        settings = settings.withProvider(service, importedConfig)
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
            parametersIds = oldSwarm.parametersId?.let { listOf(it) }
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
            parametersIds = oldFlock.parametersId?.let { listOf(it) }
        )
    }

    return AiConfigExport(
        version = 14,  // Upgrade to current version
        providers = legacyExport.providers ?: emptyMap(),
        agents = legacyExport.agents ?: emptyList(),
        flocks = newFlocks,
        swarms = newSwarms,
        parameters = legacyExport.parameters,
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

        if (export.version !in 11..16) {
            Toast.makeText(context, "Unsupported configuration version: ${export.version}. Expected version 11-16.", Toast.LENGTH_LONG).show()
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

        if (export.version !in 11..16) {
            Toast.makeText(context, "Unsupported configuration version: ${export.version}. Expected version 11-16.", Toast.LENGTH_LONG).show()
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
                    text = "The clipboard should contain a JSON configuration exported from this app (version 11-16).",
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

/**
 * Perform a "Start clean" operation:
 * 1. Delete all agents, flocks, swarms, prompts, parameters
 * 2. Delete all chats
 * 3. Delete all reports
 * 4. Delete all API traces
 * 5. Clear prompt history, last report prompt, selected report IDs
 * 6. Clear legacy HTML history
 * 7. Refresh model lists
 * 8. Refresh OpenRouter data (pricing + specs)
 * 9. Refresh provider state
 * 10. Generate default agents
 * 11. Clear statistics (last, since other steps generate stats)
 */
fun performStartClean(
    context: Context,
    onProgress: ((String) -> Unit)? = null
) {
    val settingsPrefs = SettingsPreferences(
        context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE),
        context.filesDir
    )

    onProgress?.invoke("Deleting chats...")
    val cutoffTime = System.currentTimeMillis()
    com.ai.data.ChatHistoryManager.getAllSessions().forEach { session ->
        if (session.updatedAt < cutoffTime) {
            com.ai.data.ChatHistoryManager.deleteSession(session.id)
        }
    }

    onProgress?.invoke("Deleting reports...")
    com.ai.data.AiReportStorage.getAllReports(context).forEach { report ->
        if (report.timestamp < cutoffTime) {
            com.ai.data.AiReportStorage.deleteReport(context, report.id)
        }
    }

    onProgress?.invoke("Deleting API traces...")
    com.ai.data.ApiTracer.deleteTracesOlderThan(cutoffTime)

    onProgress?.invoke("Clearing prompts...")
    settingsPrefs.clearPromptHistory()
    settingsPrefs.clearLastAiReportPrompt()
    settingsPrefs.clearSelectedReportIds()

    onProgress?.invoke("Clearing statistics...")
    settingsPrefs.clearUsageStats()
}
