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
import com.ai.data.createAiGson
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Data class for provider settings in JSON export/import.
 * Version 17: Renamed manualModels → models (unified model list).
 * Version 18: Added parametersIds, displayName, baseUrl, apiFormat, chatPath, modelsPath, openRouterName.
 */
data class ProviderConfigExport(
    val modelSource: String,  // "API" or "MANUAL"
    val models: List<String>,
    val apiKey: String = "",
    val defaultModel: String? = null,
    val adminUrl: String? = null,
    val modelListUrl: String? = null,
    // New in v18:
    val parametersIds: List<String>? = null,   // Bug fix: was missing from export
    val displayName: String? = null,
    val baseUrl: String? = null,
    val apiFormat: String? = null,             // "OPENAI_COMPATIBLE", "ANTHROPIC", "GOOGLE"
    val chatPath: String? = null,
    val modelsPath: String? = null,
    val openRouterName: String? = null
)

data class AgentExport(
    val id: String,
    val name: String,
    val provider: String,
    val model: String,
    val apiKey: String,
    val parametersIds: List<String>? = null,
    val endpointId: String? = null
)

data class FlockExport(
    val id: String,
    val name: String,
    val agentIds: List<String>,
    val parametersIds: List<String>? = null
)

/**
 * Data class for swarm member in JSON export/import (version 13+).
 */
data class SwarmMemberExport(
    val provider: String,  // Provider enum name
    val model: String
)

data class SwarmExport(
    val id: String,
    val name: String,
    val members: List<SwarmMemberExport>,
    val parametersIds: List<String>? = null
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

data class AiConfigExport(
    val version: Int = 20,
    val providers: Map<String, ProviderConfigExport>,
    val agents: List<AgentExport>,
    val flocks: List<FlockExport>? = null,
    val swarms: List<SwarmExport>? = null,
    val parameters: List<ParametersExport>? = null,
    val huggingFaceApiKey: String? = null,
    val aiPrompts: List<PromptExport>? = null,
    val manualPricing: List<ManualPricingExport>? = null,
    val providerEndpoints: List<ProviderEndpointsExport>? = null,
    val openRouterApiKey: String? = null,
    val providerDefinitions: List<com.ai.data.ProviderDefinition>? = null
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
    // Build providers map
    val providers = AiService.entries.associate { service ->
        val config = aiSettings.getProvider(service)
        service.id to ProviderConfigExport(
            modelSource = config.modelSource.name,
            models = config.models,
            apiKey = config.apiKey,
            defaultModel = config.model,
            adminUrl = config.adminUrl,
            modelListUrl = config.modelListUrl.ifBlank { null },
            parametersIds = config.parametersIds.ifEmpty { null },
            displayName = service.displayName,
            baseUrl = service.baseUrl,
            apiFormat = service.apiFormat.name,
            chatPath = service.chatPath,
            modelsPath = service.modelsPath,
            openRouterName = service.openRouterName
        )
    }

    // Convert agents with parameter preset IDs
    val agents = aiSettings.agents.map { agent ->
        AgentExport(
            id = agent.id,
            name = agent.name,
            provider = agent.provider.id,
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
                    provider = member.provider.id,
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
                provider = provider.id,
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

    // Export full provider definitions for v20
    val providerDefinitions = AiService.entries.map { com.ai.data.ProviderDefinition.fromAiService(it) }

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
        openRouterApiKey = openRouterApiKey.ifBlank { null },
        providerDefinitions = providerDefinitions
    )

    val gson = createAiGson(prettyPrint = true)
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

    val gson = createAiGson()
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
        if (apiKey.isNotBlank()) ApiKeyEntry(service.id, apiKey) else null
    }

    val export = ApiKeysExport(
        keys = keys,
        huggingFaceApiKey = huggingFaceApiKey.ifBlank { null },
        openRouterApiKey = openRouterApiKey.ifBlank { null }
    )

    val gson = createAiGson(prettyPrint = true)
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

        val gson = createAiGson()
        val export = gson.fromJson(json, ApiKeysExport::class.java)

        if (export.type != "api_keys" || export.keys == null) {
            Toast.makeText(context, "Not an API keys file", Toast.LENGTH_SHORT).show()
            return null
        }

        var settings = currentSettings
        var importedCount = 0

        for (entry in export.keys) {
            if (entry.apiKey.isBlank()) continue
            val service = AiService.findById(entry.service)
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
    // Register provider definitions from import (creates missing providers in registry)
    export.providerDefinitions?.let { defs ->
        val newProviders = defs.mapNotNull { def ->
            if (com.ai.data.ProviderRegistry.findById(def.id) == null) {
                try { def.toAiService() } catch (e: Exception) { null }
            } else null
        }
        if (newProviders.isNotEmpty()) {
            com.ai.data.ProviderRegistry.ensureProviders(newProviders)
        }
    }

    // Import agents
    val agents = export.agents.mapNotNull { agentExport ->
        val provider = AiService.findById(agentExport.provider)
        provider?.let {
            AiAgent(
                id = agentExport.id,
                name = agentExport.name,
                provider = it,
                model = agentExport.model,
                apiKey = agentExport.apiKey,
                endpointId = agentExport.endpointId,
                paramsIds = agentExport.parametersIds ?: emptyList()
            )
        }
    }

    // Import flocks
    val flocks = export.flocks?.map { flockExport ->
        AiFlock(
            id = flockExport.id,
            name = flockExport.name,
            agentIds = flockExport.agentIds,
            paramsIds = flockExport.parametersIds ?: emptyList()
        )
    } ?: emptyList()

    // Import swarms
    val swarms = export.swarms?.mapNotNull { swarmExport ->
        try {
            AiSwarm(
                id = swarmExport.id,
                name = swarmExport.name,
                members = swarmExport.members.mapNotNull { memberExport ->
                    val provider = AiService.findById(memberExport.provider) ?: return@mapNotNull null
                    AiSwarmMember(provider = provider, model = memberExport.model)
                },
                paramsIds = swarmExport.parametersIds ?: emptyList()
            )
        } catch (e: Exception) { null }
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

    // Import parameter presets
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
    } ?: emptyList())

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
        val service = AiService.findById(providerKey) ?: continue
        val currentConfig = settings.getProvider(service)
        val defaultModelSource = defaultProviderConfig(service).modelSource
        val importedConfig = currentConfig.copy(
            modelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { defaultModelSource },
            models = p.models,
            apiKey = p.apiKey,
            model = p.defaultModel ?: currentConfig.model,
            adminUrl = p.adminUrl ?: currentConfig.adminUrl,
            modelListUrl = p.modelListUrl ?: "",
            parametersIds = p.parametersIds ?: currentConfig.parametersIds
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
            AiService.findById(providerEndpoints.provider)
        } catch (e: Exception) {
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
 * Import AI configuration from a file URI. Accepts version 20.
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

        val gson = createAiGson()
        val export = gson.fromJson(json, AiConfigExport::class.java)

        if (export.version != 20) {
            Toast.makeText(context, "Unsupported configuration version: ${export.version}. Expected version 20.", Toast.LENGTH_LONG).show()
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
