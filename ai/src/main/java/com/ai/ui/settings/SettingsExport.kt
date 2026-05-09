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
 * Import AI configuration from a file URI. Accepts versions 11-24.
 */
fun importAiConfigFromFile(context: Context, uri: Uri, currentSettings: Settings): ConfigImportResult? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: run {
            Toast.makeText(context, "Could not open file", Toast.LENGTH_SHORT).show(); return null
        }
        val json = BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
        if (json.isBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return null }
        val export = gson.fromJson(json, ConfigExport::class.java)
        if (export.version !in 11..24) {
            Toast.makeText(context, "Unsupported version: ${export.version}. Expected 11-24.", Toast.LENGTH_LONG).show(); return null
        }
        processImportedConfig(context, export, currentSettings)
    } catch (e: JsonSyntaxException) {
        Toast.makeText(context, "Invalid AI configuration format", Toast.LENGTH_SHORT).show(); null
    } catch (e: Exception) {
        Toast.makeText(context, "Error importing: ${e.message}", Toast.LENGTH_SHORT).show(); null
    }
}

/**
 * Import AI configuration from clipboard JSON text. Accepts versions 11-24.
 */
fun importAiConfigFromClipboard(context: Context, json: String, currentSettings: Settings): ConfigImportResult? {
    return try {
        if (json.isBlank()) { Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show(); return null }
        val export = gson.fromJson(json, ConfigExport::class.java)
        if (export.version !in 11..24) {
            Toast.makeText(context, "Unsupported version: ${export.version}. Expected 11-24.", Toast.LENGTH_LONG).show(); return null
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
            apiKey = "", defaultModel = config.model,
            // adminUrl now flows out via the catalog section below
            // (`displayName, baseUrl, adminUrl, …`). modelListUrl is
            // dropped from the schema entirely — it had no consumer in
            // dispatch and is now removed from the data model.
            adminUrl = null, modelListUrl = null,
            parametersIds = config.parametersIds.ifEmpty { null },
            modelTypes = config.modelTypes.takeIf { it.isNotEmpty() },
            visionModels = config.visionModels.toList().ifEmpty { null },
            webSearchModels = config.webSearchModels.toList().ifEmpty { null },
            displayName = service.id, baseUrl = service.baseUrl,
            apiFormat = service.apiFormat.name,
            typePaths = service.typePaths.takeIf { it.isNotEmpty() },
            modelsPath = service.modelsPath, openRouterName = service.openRouterName,
            catalogAdminUrl = service.adminUrl
        )
    }

    val export = ConfigExport(
        version = 24, providers = providerExports,
        agents = settings.agents.map { AgentExport(it.id, it.name, it.provider.id, it.model, "", it.paramsIds.ifEmpty { null }, it.endpointId, it.systemPromptId) },
        flocks = settings.flocks.ifEmpty { null }?.map { FlockExport(it.id, it.name, it.agentIds, it.paramsIds.ifEmpty { null }, it.systemPromptId) },
        swarms = settings.swarms.ifEmpty { null }?.map { SwarmExport(it.id, it.name, it.members.map { m -> SwarmMemberExport(m.provider.id, m.model) }, it.paramsIds.ifEmpty { null }, it.systemPromptId) },
        parameters = settings.parameters.ifEmpty { null }?.map { ParametersExport(it.id, it.name, it.temperature, it.maxTokens, it.topP, it.topK, it.frequencyPenalty, it.presencePenalty, it.systemPrompt, it.stopSequences, it.seed, it.responseFormatJson, it.searchEnabled, it.returnCitations, it.searchRecency, it.webSearchTool, it.reasoningEffort) },
        systemPrompts = settings.systemPrompts.ifEmpty { null }?.map { SystemPromptExport(it.id, it.name, it.prompt) },
        internalPrompts = settings.internalPrompts.ifEmpty { null }?.map {
            InternalPromptExport(it.id, it.name, it.reference, it.category, it.agent, it.text, it.title)
        },
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
        defaultTypePaths = generalSettings.defaultTypePaths.ifEmpty { null }
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
    // Pre-unification (v11-v23) exports carry SCREAMING_SNAKE provider
    // ids ("OPENAI") plus a separate displayName field on each
    // ProviderConfigExport. The unification refactor collapsed id +
    // displayName + prefsKey into the single id (= space-stripped
    // displayName). Rewrite every old id reference in the export to
    // the new id BEFORE the rest of the importer runs, so downstream
    // findById lookups, providerStates / endpoints / modelTypeOverrides
    // map keys, and persisted agent.provider / swarm.member.provider
    // strings all resolve correctly. The case-insensitive findById is
    // a separate safety net; this rewrite is the proper migration.
    val migrated = mapOldIdsToNew(export)

    // Register provider definitions. Updating an existing definition from
    // an import bundle is intentionally NOT done — the only field that
    // ever drove an update was endpointRules, which is now ignored.
    // New providers (no existing match by id) are still added.
    migrated.providerDefinitions?.let { defs ->
        val newProviders = mutableListOf<AppService>()
        for (def in defs) {
            if (ProviderRegistry.findById(def.id) == null) {
                try { newProviders.add(def.toAppService()) } catch (e: Exception) { android.util.Log.w("SettingsExport", "Skipped provider ${def.id}: ${e.message}") }
            }
        }
        if (newProviders.isNotEmpty()) ProviderRegistry.ensureProviders(newProviders)
    }

    val agents = migrated.agents.mapNotNull { e -> AppService.findById(e.provider)?.let { Agent(e.id, e.name, it, e.model, e.apiKey, e.endpointId, e.parametersIds ?: emptyList(), e.systemPromptId) }.also { if (it == null) android.util.Log.w("SettingsExport", "Skipped agent ${e.name}: unknown provider ${e.provider}") } }
    val flocks = migrated.flocks?.map { Flock(it.id, it.name, it.agentIds, it.parametersIds ?: emptyList(), it.systemPromptId) } ?: emptyList()
    val swarms = migrated.swarms?.mapNotNull { e -> try { Swarm(e.id, e.name, e.members.mapNotNull { m -> AppService.findById(m.provider)?.let { SwarmMember(it, m.model) }.also { if (it == null) android.util.Log.w("SettingsExport", "Skipped swarm member ${m.provider}/${m.model}: unknown provider") } }, e.parametersIds ?: emptyList(), e.systemPromptId) } catch (ex: Exception) { android.util.Log.w("SettingsExport", "Skipped swarm ${e.name}: ${ex.message}"); null } } ?: emptyList()
    val parameters = migrated.parameters?.map { Parameters(it.id, it.name, it.temperature, it.maxTokens, it.topP, it.topK, it.frequencyPenalty, it.presencePenalty, it.systemPrompt, it.stopSequences, it.seed, it.responseFormatJson, it.searchEnabled, it.returnCitations, it.searchRecency, it.webSearchTool, it.reasoningEffort) } ?: emptyList()
    val systemPrompts = migrated.systemPrompts?.map { SystemPrompt(it.id, it.name, it.prompt) } ?: emptyList()
    // Pull internalPrompts; fall back to the legacy v22 metaPrompts
    // field when an older bundle is being imported. v22 metaPrompts
    // rows had no category / agent — Gson reflection bypass leaves
    // those properties as runtime null. Default category to "meta"
    // (the legacy data was always meta-eligible) and agent to
    // "*select" so the imported rows carry valid values.
    val internalPromptSource = migrated.internalPrompts ?: migrated.metaPrompts
    val mappedInternalPrompts: List<InternalPrompt>? = internalPromptSource?.map { e ->
        @Suppress("USELESS_CAST")
        val cat = (e.category as String?)?.takeIf { it.isNotBlank() } ?: "meta"
        @Suppress("USELESS_CAST")
        val ag = (e.agent as String?)?.takeIf { it.isNotBlank() } ?: "*select"
        InternalPrompt(e.id, e.name, e.reference, cat, ag, e.text, e.title)
    }
    // Fold legacy v11-v22 introPrompt / modelInfoPrompt / translatePrompt
    // GeneralSettings fields into matching InternalPrompt entries by
    // name so older bundles don't lose the user's overrides. Only
    // applied when the bundle has the legacy field set AND no entry
    // by that name already exists in the imported set.
    val internalPrompts = run {
        val base = (mappedInternalPrompts ?: currentSettings.internalPrompts).toMutableList()
        fun upsertLegacy(name: String, text: String?) {
            if (text.isNullOrBlank()) return
            if (base.none { it.name.equals(name, ignoreCase = true) }) {
                base += InternalPrompt(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name, reference = false,
                    category = "internal", agent = "*select", text = text
                )
            }
        }
        upsertLegacy("Intro", migrated.introPrompt)
        upsertLegacy("Model info", migrated.modelInfoPrompt)
        upsertLegacy("Translate", migrated.translatePrompt)
        base.toList()
    }

    var settings = currentSettings.copy(
        agents = agents, flocks = flocks, swarms = swarms,
        parameters = parameters, systemPrompts = systemPrompts, internalPrompts = internalPrompts,
        providerStates = migrated.providerStates ?: currentSettings.providerStates,
        modelTypeOverrides = migrated.modelTypeOverrides ?: currentSettings.modelTypeOverrides
    )

    for ((providerKey, p) in migrated.providers) {
        val service = AppService.findById(providerKey) ?: continue
        val cur = settings.getProvider(service)
        val importedConfig = cur.copy(
            modelSource = try { ModelSource.valueOf(p.modelSource) } catch (_: Exception) { defaultProviderConfig(service).modelSource },
            models = p.models, apiKey = p.apiKey, model = p.defaultModel ?: cur.model,
            modelTypes = p.modelTypes ?: cur.modelTypes,
            visionModels = p.visionModels?.toSet() ?: cur.visionModels,
            webSearchModels = p.webSearchModels?.toSet() ?: cur.webSearchModels,
            parametersIds = p.parametersIds ?: cur.parametersIds
        )
        settings = settings.withProvider(service, importedConfig)
        // Migrate legacy override fields into the catalog. Pre-v24
        // exports stored the user's per-config Admin URL override on
        // ProviderConfig.adminUrl; that field is gone from the data
        // model. Apply the value to the catalog (ProviderRegistry)
        // instead, but only if it actually differs from what we already
        // ship — same semantic the runtime read-path used to honour.
        // v24+ exports carry the catalog admin URL in catalogAdminUrl;
        // honour that if present.
        val catalogAdmin = p.catalogAdminUrl ?: p.adminUrl
        if (!catalogAdmin.isNullOrBlank() && catalogAdmin != service.adminUrl) {
            val updated = com.ai.data.ProviderDefinition.fromAppService(service)
                .copy(adminUrl = catalogAdmin)
                .toAppService()
            com.ai.data.ProviderRegistry.update(updated)
        }
        if (!p.modelListUrl.isNullOrBlank()) {
            android.util.Log.w("SettingsExport",
                "Dropping legacy modelListUrl import for ${service.id}: ${p.modelListUrl} " +
                    "(no equivalent in unified schema; edit modelsPath in the provider catalog if a custom endpoint is needed)")
        }
    }

    migrated.manualPricing?.let { pricingList ->
        val map = pricingList.associate { entry ->
            val modelId = entry.key.substringAfter(':', entry.key)
            entry.key to PricingCache.ModelPricing(modelId, entry.promptPrice, entry.completionPrice, "manual")
        }
        PricingCache.setAllManualPricing(context, map)
    }

    var settingsWithEndpoints = settings
    migrated.providerEndpoints?.forEach { pe ->
        AppService.findById(pe.provider)?.let { provider ->
            settingsWithEndpoints = settingsWithEndpoints.withEndpoints(provider, pe.endpoints.map { Endpoint(it.id, it.name, it.url, it.isDefault) })
        }
    }

    if (!silent) {
        val apiKeys = migrated.providers.values.count { it.apiKey.isNotBlank() }
        val pricing = migrated.manualPricing?.size ?: 0
        val endpoints = migrated.providerEndpoints?.sumOf { it.endpoints.size } ?: 0
        val msg = "Imported ${agents.size} agents, $apiKeys API keys" +
            (if (pricing > 0) ", $pricing price overrides" else "") +
            (if (endpoints > 0) ", $endpoints endpoints" else "")
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    return ConfigImportResult(settingsWithEndpoints, migrated.huggingFaceApiKey, migrated.openRouterApiKey, migrated.artificialAnalysisApiKey, migrated.defaultTypePaths)
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

/** Migrate provider id references in a [ConfigExport] from the
 *  pre-unification SCREAMING_SNAKE form ("OPENAI") to the unified
 *  mixed-case form ("OpenAI"). Pre-unification exports populate
 *  `displayName` on each [ProviderConfigExport]; the new id is that
 *  displayName with spaces stripped (matching the convention in
 *  assets/providers.json after the refactor). Returns a [ConfigExport]
 *  with every persisted id reference rewritten:
 *    - export.providers map keys
 *    - export.agents[*].provider
 *    - export.swarms[*].members[*].provider
 *    - export.providerEndpoints[*].provider
 *    - export.providerStates map keys
 *    - export.modelTypeOverrides[*].providerId
 *    - export.providerDefinitions[*].id
 *    - export.manualPricing[*].key (when shaped "providerId:model")
 *  Acts as a no-op on already-unified (v24+) bundles where displayName
 *  isn't present on the provider exports. */
internal fun mapOldIdsToNew(export: ConfigExport): ConfigExport {
    // Build oldId → newId from the providers map. Skip entries already
    // in the unified shape (no displayName, or displayName already
    // matches the key with no spaces).
    val map: Map<String, String> = export.providers.entries.mapNotNull { (key, p) ->
        val dn = p.displayName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val newId = dn.replace(" ", "")
        if (newId == key) null else key to newId
    }.toMap()
    if (map.isEmpty()) return export   // already unified

    fun mapId(s: String?): String? = s?.let { map[it] ?: it }
    val newProviders = export.providers.mapKeys { (k, _) -> map[k] ?: k }
    val newAgents = export.agents.map { a -> a.copy(provider = mapId(a.provider) ?: a.provider) }
    val newSwarms = export.swarms?.map { s ->
        s.copy(members = s.members.map { m -> m.copy(provider = mapId(m.provider) ?: m.provider) })
    }
    val newEndpoints = export.providerEndpoints?.map { pe ->
        pe.copy(provider = mapId(pe.provider) ?: pe.provider)
    }
    val newStates = export.providerStates?.mapKeys { (k, _) -> map[k] ?: k }
    val newOverrides = export.modelTypeOverrides?.map { o ->
        o.copy(providerId = mapId(o.providerId) ?: o.providerId)
    }
    val newDefinitions = export.providerDefinitions?.map { def ->
        val newId = map[def.id] ?: def.id
        if (newId == def.id) def else def.copy(id = newId)
    }
    val newManualPricing = export.manualPricing?.map { mp ->
        val colon = mp.key.indexOf(':')
        if (colon <= 0) mp
        else {
            val pid = mp.key.substring(0, colon)
            val rest = mp.key.substring(colon + 1)
            val newPid = map[pid] ?: pid
            if (newPid == pid) mp else mp.copy(key = "$newPid:$rest")
        }
    }
    return export.copy(
        providers = newProviders,
        agents = newAgents,
        swarms = newSwarms,
        providerEndpoints = newEndpoints,
        providerStates = newStates,
        modelTypeOverrides = newOverrides,
        providerDefinitions = newDefinitions,
        manualPricing = newManualPricing
    )
}
