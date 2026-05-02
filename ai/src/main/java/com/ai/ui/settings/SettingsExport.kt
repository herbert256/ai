package com.ai.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import com.ai.data.AppService
import com.ai.data.PricingCache
import com.ai.data.ProviderRegistry
import com.ai.data.createAppGson
import com.ai.model.*
import com.google.gson.JsonSyntaxException
import java.io.BufferedReader
import java.io.InputStreamReader

private val gson = createAppGson()

// ===== Import Functions =====

/**
 * Import AI configuration from a bundled asset file (e.g., setup.json on first run).
 * Returns ConfigImportResult or null on error. Does not show toasts.
 */
fun importAiConfigFromAsset(context: Context, assetFileName: String, currentSettings: Settings): ConfigImportResult? {
    return try {
        val json = context.assets.open(assetFileName).bufferedReader().use { it.readText() }
        if (json.isBlank()) return null
        val export = gson.fromJson(json, ConfigExport::class.java)
        if (export.version !in 11..21) return null
        processImportedConfig(context, export, currentSettings, silent = true)
    } catch (e: Exception) {
        android.util.Log.e("SettingsExport", "Error importing config from asset $assetFileName: ${e.message}")
        null
    }
}

/**
 * Import AI configuration from a file URI. Accepts versions 11-21.
 */
fun importAiConfigFromFile(context: Context, uri: Uri, currentSettings: Settings): ConfigImportResult? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: run {
            Toast.makeText(context, "Could not open file", Toast.LENGTH_SHORT).show(); return null
        }
        val json = BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
        if (json.isBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return null }
        val export = gson.fromJson(json, ConfigExport::class.java)
        if (export.version !in 11..21) {
            Toast.makeText(context, "Unsupported version: ${export.version}. Expected 11-21.", Toast.LENGTH_LONG).show(); return null
        }
        processImportedConfig(context, export, currentSettings)
    } catch (e: JsonSyntaxException) {
        Toast.makeText(context, "Invalid AI configuration format", Toast.LENGTH_SHORT).show(); null
    } catch (e: Exception) {
        Toast.makeText(context, "Error importing: ${e.message}", Toast.LENGTH_SHORT).show(); null
    }
}

/**
 * Import AI configuration from clipboard JSON text. Accepts versions 11-21.
 */
fun importAiConfigFromClipboard(context: Context, json: String, currentSettings: Settings): ConfigImportResult? {
    return try {
        if (json.isBlank()) { Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show(); return null }
        val export = gson.fromJson(json, ConfigExport::class.java)
        if (export.version !in 11..21) {
            Toast.makeText(context, "Unsupported version: ${export.version}. Expected 11-21.", Toast.LENGTH_LONG).show(); return null
        }
        processImportedConfig(context, export, currentSettings)
    } catch (e: JsonSyntaxException) {
        Toast.makeText(context, "Invalid AI configuration format", Toast.LENGTH_SHORT).show(); null
    } catch (e: Exception) {
        Toast.makeText(context, "Error importing: ${e.message}", Toast.LENGTH_SHORT).show(); null
    }
}

// ===== Export Functions =====

/**
 * Export current AI configuration to JSON string (v21 format).
 */
fun exportAiConfig(context: Context, settings: Settings, generalSettings: com.ai.viewmodel.GeneralSettings): String {
    // API keys are intentionally stripped from the config export — use the dedicated
    // "API Keys" export to share keys, so an accidentally shared config can't leak them.
    val providerExports = mutableMapOf<String, ProviderConfigExport>()
    for (service in AppService.entries) {
        val config = settings.getProvider(service)
        providerExports[service.id] = ProviderConfigExport(
            modelSource = config.modelSource.name, models = config.models,
            apiKey = "", defaultModel = config.model, adminUrl = config.adminUrl,
            modelListUrl = config.modelListUrl, parametersIds = config.parametersIds.ifEmpty { null },
            modelTypes = config.modelTypes.takeIf { it.isNotEmpty() },
            visionModels = config.visionModels.toList().ifEmpty { null },
            webSearchModels = config.webSearchModels.toList().ifEmpty { null },
            displayName = service.displayName, baseUrl = service.baseUrl,
            apiFormat = service.apiFormat.name,
            typePaths = service.typePaths.takeIf { it.isNotEmpty() },
            modelsPath = service.modelsPath, openRouterName = service.openRouterName
        )
    }

    val export = ConfigExport(
        version = 21, providers = providerExports,
        agents = settings.agents.map { AgentExport(it.id, it.name, it.provider.id, it.model, "", it.paramsIds.ifEmpty { null }, it.endpointId, it.systemPromptId) },
        flocks = settings.flocks.ifEmpty { null }?.map { FlockExport(it.id, it.name, it.agentIds, it.paramsIds.ifEmpty { null }, it.systemPromptId) },
        swarms = settings.swarms.ifEmpty { null }?.map { SwarmExport(it.id, it.name, it.members.map { m -> SwarmMemberExport(m.provider.id, m.model) }, it.paramsIds.ifEmpty { null }, it.systemPromptId) },
        parameters = settings.parameters.ifEmpty { null }?.map { ParametersExport(it.id, it.name, it.temperature, it.maxTokens, it.topP, it.topK, it.frequencyPenalty, it.presencePenalty, it.systemPrompt, it.stopSequences, it.seed, it.responseFormatJson, it.searchEnabled, it.returnCitations, it.searchRecency, it.webSearchTool, it.reasoningEffort) },
        systemPrompts = settings.systemPrompts.ifEmpty { null }?.map { SystemPromptExport(it.id, it.name, it.prompt) },
        huggingFaceApiKey = null,
        // aiPrompts dropped — Internal Prompts are gone; ConfigExport
        // keeps the field as deserialization-only for back-compat.
        aiPrompts = null,
        manualPricing = PricingCache.getAllManualPricing(context).map { (key, pricing) ->
            ManualPricingExport(key, pricing.promptPrice, pricing.completionPrice)
        }.ifEmpty { null },
        providerEndpoints = settings.endpoints.entries.map { (provider, eps) -> ProviderEndpointsExport(provider.id, eps.map { EndpointExport(it.id, it.name, it.url, it.isDefault) }) }.ifEmpty { null },
        openRouterApiKey = null,
        providerDefinitions = ProviderRegistry.getCustomProviders().ifEmpty { null },
        providerStates = settings.providerStates.ifEmpty { null },
        modelTypeOverrides = settings.modelTypeOverrides.ifEmpty { null },
        defaultTypePaths = generalSettings.defaultTypePaths.ifEmpty { null },
        rerankPrompt = generalSettings.rerankPrompt.ifBlank { null },
        summarizePrompt = generalSettings.summarizePrompt.ifBlank { null },
        comparePrompt = generalSettings.comparePrompt.ifBlank { null },
        introPrompt = generalSettings.introPrompt.ifBlank { null },
        modelInfoPrompt = generalSettings.modelInfoPrompt.ifBlank { null },
        translatePrompt = generalSettings.translatePrompt.ifBlank { null }
    )

    return createAppGson(prettyPrint = true).toJson(export)
}

fun exportAiConfigToClipboard(context: Context, settings: Settings, generalSettings: com.ai.viewmodel.GeneralSettings) {
    val json = exportAiConfig(context, settings, generalSettings)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("AI Configuration", json))
    Toast.makeText(context, "Configuration copied to clipboard", Toast.LENGTH_SHORT).show()
}

// ===== Internal =====

internal fun processImportedConfig(context: Context, export: ConfigExport, currentSettings: Settings, silent: Boolean = false): ConfigImportResult {
    // Register provider definitions. Updating an existing definition from
    // an import bundle is intentionally NOT done — the only field that
    // ever drove an update was endpointRules, which is now ignored.
    // New providers (no existing match by id) are still added.
    export.providerDefinitions?.let { defs ->
        val newProviders = mutableListOf<AppService>()
        for (def in defs) {
            if (ProviderRegistry.findById(def.id) == null) {
                try { newProviders.add(def.toAppService()) } catch (e: Exception) { android.util.Log.w("SettingsExport", "Skipped provider ${def.id}: ${e.message}") }
            }
        }
        if (newProviders.isNotEmpty()) ProviderRegistry.ensureProviders(newProviders)
    }

    val agents = export.agents.mapNotNull { e -> AppService.findById(e.provider)?.let { Agent(e.id, e.name, it, e.model, e.apiKey, e.endpointId, e.parametersIds ?: emptyList(), e.systemPromptId) }.also { if (it == null) android.util.Log.w("SettingsExport", "Skipped agent ${e.name}: unknown provider ${e.provider}") } }
    val flocks = export.flocks?.map { Flock(it.id, it.name, it.agentIds, it.parametersIds ?: emptyList(), it.systemPromptId) } ?: emptyList()
    val swarms = export.swarms?.mapNotNull { e -> try { Swarm(e.id, e.name, e.members.mapNotNull { m -> AppService.findById(m.provider)?.let { SwarmMember(it, m.model) }.also { if (it == null) android.util.Log.w("SettingsExport", "Skipped swarm member ${m.provider}/${m.model}: unknown provider") } }, e.parametersIds ?: emptyList(), e.systemPromptId) } catch (ex: Exception) { android.util.Log.w("SettingsExport", "Skipped swarm ${e.name}: ${ex.message}"); null } } ?: emptyList()
    val parameters = export.parameters?.map { Parameters(it.id, it.name, it.temperature, it.maxTokens, it.topP, it.topK, it.frequencyPenalty, it.presencePenalty, it.systemPrompt, it.stopSequences, it.seed, it.responseFormatJson, it.searchEnabled, it.returnCitations, it.searchRecency, it.webSearchTool, it.reasoningEffort) } ?: emptyList()
    val systemPrompts = export.systemPrompts?.map { SystemPrompt(it.id, it.name, it.prompt) } ?: emptyList()
    // export.aiPrompts is intentionally ignored — Internal Prompts feature
    // is gone. Old export bundles still parse via the nullable field on
    // ConfigExport.

    var settings = currentSettings.copy(
        agents = agents, flocks = flocks, swarms = swarms,
        parameters = parameters, systemPrompts = systemPrompts,
        providerStates = export.providerStates ?: currentSettings.providerStates,
        modelTypeOverrides = export.modelTypeOverrides ?: currentSettings.modelTypeOverrides
    )

    for ((providerKey, p) in export.providers) {
        val service = AppService.findById(providerKey) ?: continue
        val cur = settings.getProvider(service)
        val importedConfig = cur.copy(
            modelSource = try { ModelSource.valueOf(p.modelSource) } catch (_: Exception) { defaultProviderConfig(service).modelSource },
            models = p.models, apiKey = p.apiKey, model = p.defaultModel ?: cur.model,
            modelTypes = p.modelTypes ?: cur.modelTypes,
            visionModels = p.visionModels?.toSet() ?: cur.visionModels,
            webSearchModels = p.webSearchModels?.toSet() ?: cur.webSearchModels,
            adminUrl = p.adminUrl ?: cur.adminUrl, modelListUrl = p.modelListUrl ?: "",
            parametersIds = p.parametersIds ?: cur.parametersIds
        )
        settings = settings.withProvider(service, importedConfig)
    }

    export.manualPricing?.let { pricingList ->
        val map = pricingList.associate { entry ->
            val modelId = entry.key.substringAfter(':', entry.key)
            entry.key to PricingCache.ModelPricing(modelId, entry.promptPrice, entry.completionPrice, "manual")
        }
        PricingCache.setAllManualPricing(context, map)
    }

    var settingsWithEndpoints = settings
    export.providerEndpoints?.forEach { pe ->
        AppService.findById(pe.provider)?.let { provider ->
            settingsWithEndpoints = settingsWithEndpoints.withEndpoints(provider, pe.endpoints.map { Endpoint(it.id, it.name, it.url, it.isDefault) })
        }
    }

    if (!silent) {
        val apiKeys = export.providers.values.count { it.apiKey.isNotBlank() }
        val pricing = export.manualPricing?.size ?: 0
        val endpoints = export.providerEndpoints?.sumOf { it.endpoints.size } ?: 0
        val msg = "Imported ${agents.size} agents, $apiKeys API keys" +
            (if (pricing > 0) ", $pricing price overrides" else "") +
            (if (endpoints > 0) ", $endpoints endpoints" else "")
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    return ConfigImportResult(settingsWithEndpoints, export.huggingFaceApiKey, export.openRouterApiKey, export.artificialAnalysisApiKey, export.defaultTypePaths, export.rerankPrompt, export.summarizePrompt, export.comparePrompt, export.introPrompt, export.modelInfoPrompt, export.translatePrompt)
}

/**
 * Start clean: delete all data and refresh.
 */
fun performStartClean(context: Context, onProgress: ((String) -> Unit)? = null) {
    val sp = SettingsPreferences(
        context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE), context.filesDir
    )
    val cutoff = System.currentTimeMillis()
    onProgress?.invoke("Deleting chats...")
    com.ai.data.ChatHistoryManager.getAllSessions().forEach { if (it.updatedAt < cutoff) com.ai.data.ChatHistoryManager.deleteSession(it.id) }
    onProgress?.invoke("Deleting reports...")
    com.ai.data.ReportStorage.getAllReports(context).forEach { if (it.timestamp < cutoff) com.ai.data.ReportStorage.deleteReport(context, it.id) }
    onProgress?.invoke("Clearing prompts...")
    sp.clearPromptHistory(); sp.clearLastReportPrompt()
    onProgress?.invoke("Clearing statistics...")
    sp.clearUsageStats()
    onProgress?.invoke("Deleting API traces...")
    com.ai.data.ApiTracer.deleteTracesOlderThan(cutoff)
}
