package com.ai.ui.settings

import com.ai.data.AppLog

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.ChatHistoryManager
import com.ai.data.ChatSession
import com.ai.data.PricingCache
import com.ai.data.ProviderRegistry
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.data.createAppGson
import com.ai.model.*
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.ControlledCollapsibleCard
import com.ai.ui.shared.RestartAppBanner
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.restartApp
import com.ai.ui.shared.csvField
import com.ai.ui.shared.exportTimestamp
import com.ai.ui.shared.parseCsvRow
import com.ai.ui.shared.shareExportText
import com.ai.viewmodel.GeneralSettings
import com.ai.viewmodel.ModelNameLayout
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

// Drop-in shape for assets/prompts.json — no id field (the seed
// loader assigns fresh UUIDs on read). Used by both the standalone
// prompts.json export and the All-bundle's "prompts" section.
private fun promptEntry(p: InternalPrompt): Map<String, Any> = linkedMapOf(
    "name" to p.name,
    "title" to p.title,
    "reference" to p.reference,
    "category" to p.category,
    "agent" to p.agent,
    "scope" to p.scope,
    "text" to p.text
)

/** Apply Workers sections (agents / flocks / swarms) from a parsed
 *  bundle root to [working]. Each section upserts by id — existing
 *  entries with a matching id get replaced, missing ones append.
 *  Per-entry deserialisation is wrapped so a single bad row (e.g.
 *  references a deleted provider, which makes AppServiceAdapter
 *  throw) doesn't take down the rest. Returns the new Settings plus
 *  per-section counts for the toast. */
private data class WorkerImportResult(
    val settings: Settings,
    val agents: Int,
    val flocks: Int,
    val swarms: Int
) {
    fun isEmpty() = agents == 0 && flocks == 0 && swarms == 0
}

private fun applyWorkers(root: JsonObject, working: Settings): WorkerImportResult {
    val gson = createAppGson()
    fun <T> readList(name: String, type: Class<T>): List<T> {
        val arr = root.getAsJsonArray(name) ?: return emptyList()
        val out = mutableListOf<T>()
        arr.forEach { el ->
            try { out.add(gson.fromJson(el, type)) }
            catch (e: Exception) { AppLog.w("ImportExport", "Skipped $name entry: ${e.message}") }
        }
        return out
    }
    val incomingAgents = readList("agents", Agent::class.java)
    val incomingFlocks = readList("flocks", Flock::class.java)
    val incomingSwarms = readList("swarms", Swarm::class.java)

    fun <T, ID> upsert(existing: List<T>, incoming: List<T>, idOf: (T) -> ID): List<T> {
        val incomingIds = incoming.map(idOf).toSet()
        return existing.filterNot { idOf(it) in incomingIds } + incoming
    }
    val mergedAgents = upsert(working.agents, incomingAgents) { it.id }
    val mergedFlocks = upsert(working.flocks, incomingFlocks) { it.id }
    val mergedSwarms = upsert(working.swarms, incomingSwarms) { it.id }
    val updated = working.copy(agents = mergedAgents, flocks = mergedFlocks, swarms = mergedSwarms)
    return WorkerImportResult(updated, incomingAgents.size, incomingFlocks.size, incomingSwarms.size)
}

/** Runtime-data export bundle: every report + the per-report
 *  secondary-result rows (rerank / summary / compare / moderate /
 *  translate). Reports already serialise round-trip via the same
 *  [createAppGson] used by [ReportStorage.saveReport], so the bundle
 *  is just a top-level array of those JSON objects plus a sibling map
 *  keyed by reportId. */
private fun buildReportsRuntimeBundle(context: Context): JsonObject {
    val gson = createAppGson()
    val reports = ReportStorage.getAllReports(context)
    val reportsArr = gson.toJsonTree(reports).asJsonArray
    val secondaries = JsonObject().apply {
        for (r in reports) {
            val rows = SecondaryResultStorage.listForReport(context, r.id)
            if (rows.isNotEmpty()) add(r.id, gson.toJsonTree(rows))
        }
    }
    return JsonObject().apply {
        add("reports", reportsArr)
        add("secondaries", secondaries)
    }
}

/** Runtime-data export bundle: every chat session. Same JSON shape
 *  [ChatHistoryManager.saveSession] writes per file, just bundled in
 *  a top-level array. */
private fun buildChatsRuntimeBundle(): JsonObject {
    val gson = createAppGson()
    val sessions = ChatHistoryManager.getAllSessions()
    return JsonObject().apply {
        add("chats", gson.toJsonTree(sessions))
    }
}

/** Combined runtime bundle — reports + secondaries + chats. The
 *  importer reads each section independently so old single-section
 *  exports stay readable. */
private fun buildAllRuntimeBundle(context: Context): JsonObject {
    val gson = createAppGson()
    val reports = ReportStorage.getAllReports(context)
    val sessions = ChatHistoryManager.getAllSessions()
    val secondaries = JsonObject().apply {
        for (r in reports) {
            val rows = SecondaryResultStorage.listForReport(context, r.id)
            if (rows.isNotEmpty()) add(r.id, gson.toJsonTree(rows))
        }
    }
    return JsonObject().apply {
        add("reports", gson.toJsonTree(reports))
        add("secondaries", secondaries)
        add("chats", gson.toJsonTree(sessions))
    }
}

/** Additive merge: every incoming report whose id is not already on
 *  disk is persisted; secondaries that travel with it land in
 *  filesDir/secondary/<reportId>/. Existing reports are NEVER
 *  overwritten — the user explicitly asked for merge, not replace.
 *  Returns (addedReports, skippedReports, addedSecondaries). */
private data class ImportReportsResult(val added: Int, val skipped: Int, val secondaries: Int)

private fun applyRuntimeReports(context: Context, root: JsonObject): ImportReportsResult {
    val gson = createAppGson()
    val existingIds = ReportStorage.getAllReports(context).map { it.id }.toSet()
    var added = 0
    var skipped = 0
    var secondariesAdded = 0
    val reportsArr = root.getAsJsonArray("reports") ?: return ImportReportsResult(0, 0, 0)
    val secondariesObj = root.getAsJsonObject("secondaries")
    reportsArr.forEach { el ->
        val report = try { gson.fromJson(el, Report::class.java) } catch (e: Exception) {
            AppLog.w("ImportExport", "Skipped runtime report entry: ${e.message}")
            return@forEach
        }
        if (report.id.isBlank()) { skipped++; return@forEach }
        if (report.id in existingIds) { skipped++; return@forEach }
        ReportStorage.persistReport(context, report)
        added++
        // Per-report secondaries — same additive logic. The parent
        // report just landed (its id wasn't in existingIds), so any
        // secondary id is by construction new on this device.
        val rows = secondariesObj?.getAsJsonArray(report.id) ?: return@forEach
        rows.forEach { se ->
            val sr = try { gson.fromJson(se, SecondaryResult::class.java) } catch (e: Exception) {
                AppLog.w("ImportExport", "Skipped secondary row: ${e.message}")
                return@forEach
            }
            if (sr.id.isBlank() || sr.reportId.isBlank()) return@forEach
            SecondaryResultStorage.save(context, sr)
            secondariesAdded++
        }
    }
    return ImportReportsResult(added, skipped, secondariesAdded)
}

/** Additive merge for chat sessions — same id-based logic. */
private data class ImportChatsResult(val added: Int, val skipped: Int)

private fun applyRuntimeChats(root: JsonObject): ImportChatsResult {
    val gson = createAppGson()
    val existingIds = ChatHistoryManager.getAllSessions().map { it.id }.toSet()
    var added = 0
    var skipped = 0
    val arr = root.getAsJsonArray("chats") ?: return ImportChatsResult(0, 0)
    arr.forEach { el ->
        val session = try { gson.fromJson(el, ChatSession::class.java) } catch (e: Exception) {
            AppLog.w("ImportExport", "Skipped chat session entry: ${e.message}")
            return@forEach
        }
        if (session.id.isBlank()) { skipped++; return@forEach }
        if (session.id in existingIds) { skipped++; return@forEach }
        ChatHistoryManager.saveSession(session)
        added++
    }
    return ImportChatsResult(added, skipped)
}

/** JSON tree of every Agent / Flock / Swarm. Same shape used by both
 *  the standalone Workers export and the All-bundle (inlined at the
 *  bundle root, not nested). */
private fun buildWorkersTree(settings: Settings): JsonObject {
    val gson = createAppGson()
    return JsonObject().apply {
        add("agents", gson.toJsonTree(settings.agents))
        add("flocks", gson.toJsonTree(settings.flocks))
        add("swarms", gson.toJsonTree(settings.swarms))
    }
}

/** JSON tree of [GeneralSettings] *minus* the three info-provider API
 *  keys (huggingFaceApiKey / openRouterApiKey / artificialAnalysisApiKey).
 *  Those three round-trip through the API Keys export so we don't
 *  double-emit them. */
private fun buildGeneralSettingsTree(g: GeneralSettings): JsonObject = JsonObject().apply {
    addProperty("userName", g.userName)
    addProperty("defaultEmail", g.defaultEmail)
    add("defaultTypePaths", JsonObject().apply {
        g.defaultTypePaths.forEach { (k, v) -> addProperty(k, v) }
    })
    addProperty("tracingEnabled", g.tracingEnabled)
    addProperty("modelNameLayout", g.modelNameLayout.name)
    addProperty("subjectToTitleBarMode", g.subjectToTitleBarMode.name)
    addProperty("iconGenEnabled", g.iconGenEnabled)
    addProperty("showKnowledgeCard", g.showKnowledgeCard)
}

/** Apply each present field of a Settings export onto [current],
 *  leaving fields the file omitted untouched. The three info-provider
 *  API keys are deliberately not carried by this shape — they live in
 *  the API Keys export. */
private fun applyGeneralSettings(obj: JsonObject, current: GeneralSettings): GeneralSettings {
    fun str(name: String) = obj.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    fun bool(name: String) = obj.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
    val typePaths: Map<String, String>? = obj.getAsJsonObject("defaultTypePaths")?.let { o ->
        val m = LinkedHashMap<String, String>()
        o.entrySet().forEach { (k, v) -> if (v.isJsonPrimitive) m[k] = v.asString }
        m
    }
    val layout = str("modelNameLayout")?.let {
        runCatching { ModelNameLayout.valueOf(it) }.getOrNull()
    }
    val subjectMode = str("subjectToTitleBarMode")?.let {
        runCatching { com.ai.viewmodel.SubjectToTitleBarMode.valueOf(it) }.getOrNull()
    }
    return current.copy(
        userName = str("userName") ?: current.userName,
        defaultEmail = str("defaultEmail") ?: current.defaultEmail,
        defaultTypePaths = typePaths ?: current.defaultTypePaths,
        tracingEnabled = bool("tracingEnabled") ?: current.tracingEnabled,
        modelNameLayout = layout ?: current.modelNameLayout,
        subjectToTitleBarMode = subjectMode ?: current.subjectToTitleBarMode,
        iconGenEnabled = bool("iconGenEnabled") ?: current.iconGenEnabled,
        showKnowledgeCard = bool("showKnowledgeCard") ?: current.showKnowledgeCard
    )
}

/** Per-provider model catalog keyed by AppService.id. Carries the
 *  user-curated model list plus everything the user might have tuned
 *  for those models: type assignments, vision/web-search/reasoning
 *  overrides, provider self-report pricing, and the parsed capability
 *  sidecar. The pre-computed *_capable_computed sets and the raw
 *  /models JSON blob are deliberately omitted — both regenerate from
 *  a Refresh on the receiving install. Sorted by provider id so
 *  successive exports diff cleanly. */
private fun buildModelListsTree(s: Settings): JsonObject {
    val gson = createAppGson()
    return JsonObject().apply {
        s.providers.entries.sortedBy { it.key.id }.forEach { (svc, cfg) ->
            val obj = JsonObject().apply {
                add("models", gson.toJsonTree(cfg.models))
                if (cfg.modelTypes.isNotEmpty()) add("modelTypes", gson.toJsonTree(cfg.modelTypes))
                if (cfg.visionModels.isNotEmpty()) add("visionModels", gson.toJsonTree(cfg.visionModels.toList()))
                if (cfg.webSearchModels.isNotEmpty()) add("webSearchModels", gson.toJsonTree(cfg.webSearchModels.toList()))
                if (cfg.reasoningModels.isNotEmpty()) add("reasoningModels", gson.toJsonTree(cfg.reasoningModels.toList()))
                if (cfg.modelPricing.isNotEmpty()) add("modelPricing", gson.toJsonTree(cfg.modelPricing))
                if (cfg.modelCapabilities.isNotEmpty()) add("modelCapabilities", gson.toJsonTree(cfg.modelCapabilities))
                if (cfg.parametersIds.isNotEmpty()) add("parametersIds", gson.toJsonTree(cfg.parametersIds))
            }
            add(svc.id, obj)
        }
    }
}

/** Replace each known provider's catalog with the file's. Unknown
 *  provider ids are skipped. The capability/pricing sidecar fields
 *  are optional — importing a bare `[modelId, …]` array still works
 *  and just refreshes the model list. Returns (updated, count). */
private fun applyModelLists(obj: JsonObject, working: Settings): Pair<Settings, Int> {
    val gson = createAppGson()
    var s = working
    var n = 0
    obj.entrySet().forEach { (key, value) ->
        val service = AppService.findById(key) ?: run {
            AppLog.w("ImportExport", "Skipped model list for unknown provider $key")
            return@forEach
        }
        // Tolerate both the legacy `[modelId, …]` shape and the new
        // per-provider object so older exports still import.
        if (value is JsonArray) {
            val list = value.mapNotNull {
                if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString else null
            }
            s = s.withModels(service, list)
            n++
            return@forEach
        }
        val po = value as? JsonObject ?: return@forEach
        val models: List<String> = po.getAsJsonArray("models")?.mapNotNull {
            if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString else null
        } ?: emptyList()
        val modelTypes: Map<String, String> = po.getAsJsonObject("modelTypes")?.entrySet()
            ?.mapNotNull { (k, v) -> if (v.isJsonPrimitive) k to v.asString else null }
            ?.toMap()
            .orEmpty()
        fun strSet(name: String): Set<String> = po.getAsJsonArray(name)?.mapNotNull {
            if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString else null
        }?.toSet().orEmpty()
        val pricing: Map<String, com.ai.data.PricingCache.ModelPricing> =
            po.getAsJsonObject("modelPricing")?.let {
                try {
                    val t = object : com.google.gson.reflect.TypeToken<Map<String, com.ai.data.PricingCache.ModelPricing>>() {}.type
                    gson.fromJson<Map<String, com.ai.data.PricingCache.ModelPricing>>(it, t)
                } catch (_: Exception) { null }
            }.orEmpty()
        val caps: Map<String, com.ai.data.ModelCapabilities> =
            po.getAsJsonObject("modelCapabilities")?.let {
                try {
                    val t = object : com.google.gson.reflect.TypeToken<Map<String, com.ai.data.ModelCapabilities>>() {}.type
                    gson.fromJson<Map<String, com.ai.data.ModelCapabilities>>(it, t)
                } catch (_: Exception) { null }
            }.orEmpty()
        val parametersIds: List<String> = po.getAsJsonArray("parametersIds")?.mapNotNull {
            if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString else null
        } ?: emptyList()
        val current = s.getProvider(service)
        val updated = current.copy(
            models = models,
            modelTypes = modelTypes.ifEmpty { models.associateWith { id -> com.ai.data.ModelType.infer(id) } },
            visionModels = strSet("visionModels"),
            webSearchModels = strSet("webSearchModels"),
            reasoningModels = strSet("reasoningModels"),
            modelPricing = pricing,
            modelCapabilities = caps,
            parametersIds = parametersIds
        )
        s = s.withProvider(service, updated)
        n++
    }
    return s to n
}

/** Per-provider user-defined endpoints, keyed by AppService.id. The
 *  built-in endpoints (sourced from assets/providers.json) are not in
 *  Settings.endpoints — only the user's custom additions / overrides
 *  ride along here. */
private fun buildEndpointsTree(s: Settings): JsonObject {
    val gson = createAppGson()
    return JsonObject().apply {
        s.endpoints.entries.sortedBy { it.key.id }.forEach { (svc, list) ->
            if (list.isNotEmpty()) add(svc.id, gson.toJsonTree(list))
        }
    }
}

private fun applyEndpoints(obj: JsonObject, working: Settings): Pair<Settings, Int> {
    val gson = createAppGson()
    var s = working
    var n = 0
    obj.entrySet().forEach { (key, value) ->
        val arr = value as? JsonArray ?: return@forEach
        val service = AppService.findById(key) ?: run {
            AppLog.w("ImportExport", "Skipped endpoints for unknown provider $key")
            return@forEach
        }
        val list = mutableListOf<Endpoint>()
        arr.forEach { el ->
            try { list.add(gson.fromJson(el, Endpoint::class.java)) }
            catch (e: Exception) { AppLog.w("ImportExport", "Skipped endpoint entry: ${e.message}") }
        }
        if (list.isNotEmpty()) {
            s = s.withEndpoints(service, list)
            n++
        }
    }
    return s to n
}

private fun buildParametersTree(s: Settings): JsonArray =
    createAppGson().toJsonTree(s.parameters).asJsonArray

/** Upsert by id. Bad rows are logged and skipped so a single corrupt
 *  entry doesn't take down the rest. */
private fun applyParameters(arr: JsonArray, working: Settings): Pair<Settings, Int> {
    val gson = createAppGson()
    val incoming = mutableListOf<Parameters>()
    arr.forEach { el ->
        try { incoming.add(gson.fromJson(el, Parameters::class.java)) }
        catch (e: Exception) { AppLog.w("ImportExport", "Skipped parameters entry: ${e.message}") }
    }
    val incomingIds = incoming.map { it.id }.toSet()
    val merged = working.parameters.filterNot { it.id in incomingIds } + incoming
    return working.copy(parameters = merged) to incoming.size
}

private fun buildModelTypeOverridesTree(s: Settings): JsonArray =
    createAppGson().toJsonTree(s.modelTypeOverrides).asJsonArray

private fun applyModelTypeOverrides(arr: JsonArray, working: Settings): Pair<Settings, Int> {
    val gson = createAppGson()
    val incoming = mutableListOf<ModelTypeOverride>()
    arr.forEach { el ->
        try { incoming.add(gson.fromJson(el, ModelTypeOverride::class.java)) }
        catch (e: Exception) { AppLog.w("ImportExport", "Skipped model type override entry: ${e.message}") }
    }
    val incomingIds = incoming.map { it.id }.toSet()
    val merged = working.modelTypeOverrides.filterNot { it.id in incomingIds } + incoming
    return working.copy(modelTypeOverrides = merged) to incoming.size
}

// Model cooldowns live in the ModelCooldownStore singleton, not in
// Settings — export reads the singleton's snapshot, import merges
// straight back into it (no onSave).
private fun buildModelCooldownsTree(): JsonObject =
    createAppGson().toJsonTree(com.ai.data.ModelCooldownStore.cooldowns.value).asJsonObject

private fun applyModelCooldowns(obj: JsonObject): Int {
    val type = object : com.google.gson.reflect.TypeToken<Map<String, Long>>() {}.type
    val incoming: Map<String, Long> = try {
        createAppGson().fromJson(obj, type) ?: emptyMap()
    } catch (e: Exception) {
        AppLog.w("ImportExport", "Skipped model cooldowns blob: ${e.message}"); emptyMap()
    }
    com.ai.data.ModelCooldownStore.importMerge(incoming)
    return incoming.size
}

private fun buildSystemPromptsTree(s: Settings): JsonArray =
    createAppGson().toJsonTree(s.systemPrompts).asJsonArray

private fun applySystemPrompts(arr: JsonArray, working: Settings): Pair<Settings, Int> {
    val gson = createAppGson()
    val incoming = mutableListOf<SystemPrompt>()
    arr.forEach { el ->
        try { incoming.add(gson.fromJson(el, SystemPrompt::class.java)) }
        catch (e: Exception) { AppLog.w("ImportExport", "Skipped system prompt entry: ${e.message}") }
    }
    val incomingIds = incoming.map { it.id }.toSet()
    val merged = working.systemPrompts.filterNot { it.id in incomingIds } + incoming
    return working.copy(systemPrompts = merged) to incoming.size
}

private fun buildBlockedModelsTree(s: Settings): JsonArray =
    createAppGson().toJsonTree(s.blockedModels).asJsonArray

/** Upsert by `(providerId, model)` key. Bad rows are logged and skipped. */
private fun applyBlockedModels(arr: JsonArray, working: Settings): Pair<Settings, Int> {
    val gson = createAppGson()
    val incoming = mutableListOf<com.ai.model.BlockedModel>()
    arr.forEach { el ->
        try { incoming.add(gson.fromJson(el, com.ai.model.BlockedModel::class.java)) }
        catch (e: Exception) { AppLog.w("ImportExport", "Skipped blocked model entry: ${e.message}") }
    }
    val incomingKeys = incoming.map { it.key }.toSet()
    val merged = working.blockedModels.filterNot { it.key in incomingKeys } + incoming
    return working.copy(blockedModels = merged) to incoming.size
}

private fun buildTestExcludedModelsTree(s: Settings): JsonArray =
    createAppGson().toJsonTree(s.testExcludedModels).asJsonArray

/** Upsert by `(providerId, model)` key. Bad rows are logged and skipped. */
private fun applyTestExcludedModels(arr: JsonArray, working: Settings): Pair<Settings, Int> {
    val gson = createAppGson()
    val incoming = mutableListOf<com.ai.model.TestExcludedModel>()
    arr.forEach { el ->
        try { incoming.add(gson.fromJson(el, com.ai.model.TestExcludedModel::class.java)) }
        catch (e: Exception) { AppLog.w("ImportExport", "Skipped test-excluded model entry: ${e.message}") }
    }
    val incomingKeys = incoming.map { it.key }.toSet()
    val merged = working.testExcludedModels.filterNot { it.key in incomingKeys } + incoming
    return working.copy(testExcludedModels = merged) to incoming.size
}

private fun buildInaccessibleModelsTree(s: Settings): JsonArray =
    createAppGson().toJsonTree(s.inaccessibleModels).asJsonArray

/** Upsert by `(providerId, model)` key. Bad rows are logged and skipped. */
private fun applyInaccessibleModels(arr: JsonArray, working: Settings): Pair<Settings, Int> {
    val gson = createAppGson()
    val incoming = mutableListOf<com.ai.model.InaccessibleModel>()
    arr.forEach { el ->
        try { incoming.add(gson.fromJson(el, com.ai.model.InaccessibleModel::class.java)) }
        catch (e: Exception) { AppLog.w("ImportExport", "Skipped inaccessible model entry: ${e.message}") }
    }
    val incomingKeys = incoming.map { it.key }.toSet()
    val merged = working.inaccessibleModels.filterNot { it.key in incomingKeys } + incoming
    return working.copy(inaccessibleModels = merged) to incoming.size
}

/** Build the All-bundle JsonObject. API keys are intentionally omitted
 *  — they ship via the dedicated API Keys export. The Import-card All
 *  button still tolerates an `apiKeys` section (older bundles), since
 *  every section is optional on read. */
private fun buildAllBundle(
    aiSettings: Settings,
    generalSettings: GeneralSettings,
    context: Context
): JsonObject {
    val gson = createAppGson(prettyPrint = true)
    val bundle = JsonObject()
    val costsArr = JsonArray()
    val manual = PricingCache.getAllManualPricing(context)
    manual.forEach { (key, pricing) ->
        val parts = key.split(":", limit = 2)
        val obj = JsonObject().apply {
            addProperty("provider", parts[0])
            addProperty("model", parts.getOrElse(1) { "" })
            addProperty("inputPerMillion", pricing.promptPrice * 1_000_000)
            addProperty("outputPerMillion", pricing.completionPrice * 1_000_000)
        }
        costsArr.add(obj)
    }
    bundle.add("costs", costsArr)
    val providers = ProviderRegistry.getCustomProviders()
    bundle.add("providers", gson.toJsonTree(providers))
    val prompts = aiSettings.internalPrompts.map { promptEntry(it) }
    bundle.add("prompts", gson.toJsonTree(prompts))
    val examples = aiSettings.examplePrompts.map { linkedMapOf("title" to it.title, "text" to it.text) }
    bundle.add("examples", gson.toJsonTree(examples))
    val workers = buildWorkersTree(aiSettings)
    workers.entrySet().forEach { (k, v) -> bundle.add(k, v) }
    bundle.add("settings", buildGeneralSettingsTree(generalSettings))
    bundle.add("modelLists", buildModelListsTree(aiSettings))
    bundle.add("endpoints", buildEndpointsTree(aiSettings))
    bundle.add("parameters", buildParametersTree(aiSettings))
    bundle.add("systemPrompts", buildSystemPromptsTree(aiSettings))
    bundle.add("modelTypeOverrides", buildModelTypeOverridesTree(aiSettings))
    bundle.add("modelCooldowns", buildModelCooldownsTree())
    bundle.add("blockedModels", buildBlockedModelsTree(aiSettings))
    bundle.add("testExcludedModels", buildTestExcludedModelsTree(aiSettings))
    bundle.add("inaccessibleModels", buildInaccessibleModelsTree(aiSettings))
    return bundle
}

@Composable
fun ImportExportScreen(
    aiSettings: Settings,
    generalSettings: GeneralSettings,
    huggingFaceApiKey: String,
    openRouterApiKey: String,
    artificialAnalysisApiKey: String,
    onSave: (Settings) -> Unit,
    onSaveGeneral: (GeneralSettings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    /** True when no provider is active yet (first-run / dead-key
     *  state). Hides the Export card and renames the title — there's
     *  nothing meaningful to export before the user has at least
     *  one working API key, but Import from another install is
     *  exactly the use case. */
    importOnly: Boolean = false,
    /** Wired by SettingsScreenNav to [AppViewModel.startRefreshAll].
     *  Fired by the "Refresh all" button in the post-API-keys-import
     *  action group — kicks the full catalog + workers pipeline. */
    onStartRefreshAll: () -> Unit = {},
    /** Wired by SettingsScreenNav to [AppViewModel.startRefreshWorkers].
     *  Fired by the "Refresh providers, model lists & default agents"
     *  button — runs the per-provider clean-slate + worker phase
     *  without paying for every external catalog round-trip. */
    onStartRefreshWorkers: () -> Unit = {},
    /** Navigates the host to the Refresh sub-screen so the user
     *  lands on the progress overlay that's about to open. */
    onNavigateToRefresh: () -> Unit = {}
) {
    BackHandler { onBack() }
    val context = LocalContext.current

    var importType by remember { mutableStateOf("keys") }
    // Set once an "Import all" finishes successfully — the in-memory
    // singletons (Settings StateFlow, ProviderRegistry, PromptCache,
    // PricingCache caches) are out of sync with the freshly-imported
    // disk state, so a forced restart brings everything back fresh.
    var restartMessage by remember { mutableStateOf<String?>(null) }
    // Set after a successful "API keys" import — surfaces a three-
    // button action banner at the top of the page (Refresh all /
    // Refresh providers, model lists & default agents / Restart
    // application). The keys are already on disk by this point; the
    // banner just lets the user pick what should happen next. No
    // modal — they can scroll, navigate, or import more without
    // dismissing it first.
    var keysImportedActions by remember { mutableStateOf(false) }

    // Rendered as a banner inside the page Column below — see the
    // `restartMessage?.let` call right under the TitleBar.

    // Action banner is rendered inline at the top of the page below —
    // see the `keysImportedActions` block right under the TitleBar.

    fun readFromUri(uri: Uri): String? {
        return context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }

    // Each export builds its content here and hands it to
    // `shareExportText`, which stages a temp file under
    // `cacheDir/exports/` and fires `Intent.ACTION_SEND` so the user
    // picks any installed destination — Email, Drive, Files, Slack,
    // the system file picker, etc. Replaces the older SAF
    // `CreateDocument` launcher pattern, which could only write to a
    // SAF-picked filesystem location.

    fun exportKeys() {
        val json = buildApiKeysJson(aiSettings, huggingFaceApiKey, openRouterApiKey, artificialAnalysisApiKey)
        // Count is just the populated key count — re-derive from the
        // payload to keep the toast in step with the helper's logic.
        val count = JsonParser.parseString(json).asJsonObject.size()
        shareExportText(context, "ai_keys-${exportTimestamp()}.json", "application/json", "Share API keys", json)
        Toast.makeText(context, "$count API keys ready to share", Toast.LENGTH_SHORT).show()
    }

    fun exportProvidersJson() {
        // Dump the current registry as `{ "providers": [<ProviderDefinition>] }`
        // — same shape the on-demand "Import new providers from
        // assets/providers.json" button consumes. ProviderDefinition
        // doesn't carry an apiKey field, so this export is intrinsically
        // key-free; the per-provider configs (with keys) live in a
        // different prefs file and are not touched here.
        val defs = ProviderRegistry.getCustomProviders()
        val payload = mapOf("providers" to defs)
        shareExportText(context, "providers.json", "application/json", "Share providers",
            createAppGson(prettyPrint = true).toJson(payload))
        Toast.makeText(context, "Providers ready to share (${defs.size} entries)", Toast.LENGTH_SHORT).show()
    }

    fun exportPromptsJson() {
        // Drop-in shape for assets/prompts.json — top-level array of
        // {name, title, reference, category, agent, text} objects, no
        // ids (the seed loader assigns fresh UUIDs on read).
        val payload = aiSettings.internalPrompts.map { promptEntry(it) }
        shareExportText(context, "prompts.json", "application/json", "Share prompts",
            createAppGson(prettyPrint = true).toJson(payload))
        Toast.makeText(context, "Internal prompts ready to share (${payload.size} entries)", Toast.LENGTH_SHORT).show()
    }

    fun exportExamplePrompts() {
        // Drop-in shape for assets/examples.json — top-level array of
        // {title, text} objects, no ids (the seed loader assigns fresh
        // UUIDs on read).
        val payload = aiSettings.examplePrompts.map { linkedMapOf("title" to it.title, "text" to it.text) }
        shareExportText(context, "examples.json", "application/json", "Share examples",
            createAppGson(prettyPrint = true).toJson(payload))
        Toast.makeText(context, "Example prompts ready to share (${payload.size} entries)", Toast.LENGTH_SHORT).show()
    }

    fun exportWorkers() {
        // { agents: [...], flocks: [...], swarms: [...] } — same shape
        // the All bundle inlines and the Workers import reads back.
        val tree = buildWorkersTree(aiSettings)
        shareExportText(context, "ai_workers-${exportTimestamp()}.json", "application/json", "Share workers",
            createAppGson(prettyPrint = true).toJson(tree))
        Toast.makeText(
            context,
            "Workers ready to share (${aiSettings.agents.size} agents, ${aiSettings.flocks.size} flocks, ${aiSettings.swarms.size} swarms)",
            Toast.LENGTH_SHORT
        ).show()
    }

    // Per-section exports share the same JSON shape as the combined
    // Workers export — a single-key object that applyWorkers reads back
    // with the missing two keys treated as empty arrays. That keeps the
    // importer single-implementation: any of the three files just calls
    // applyWorkers and only the present section upserts.
    fun exportAgents() {
        val gson = createAppGson()
        val tree = JsonObject().apply { add("agents", gson.toJsonTree(aiSettings.agents)) }
        shareExportText(context, "ai_agents-${exportTimestamp()}.json", "application/json", "Share agents",
            createAppGson(prettyPrint = true).toJson(tree))
        Toast.makeText(context, "Agents ready to share (${aiSettings.agents.size})", Toast.LENGTH_SHORT).show()
    }

    fun exportFlocks() {
        val gson = createAppGson()
        val tree = JsonObject().apply { add("flocks", gson.toJsonTree(aiSettings.flocks)) }
        shareExportText(context, "ai_flocks-${exportTimestamp()}.json", "application/json", "Share flocks",
            createAppGson(prettyPrint = true).toJson(tree))
        Toast.makeText(context, "Flocks ready to share (${aiSettings.flocks.size})", Toast.LENGTH_SHORT).show()
    }

    fun exportSwarms() {
        val gson = createAppGson()
        val tree = JsonObject().apply { add("swarms", gson.toJsonTree(aiSettings.swarms)) }
        shareExportText(context, "ai_swarms-${exportTimestamp()}.json", "application/json", "Share swarms",
            createAppGson(prettyPrint = true).toJson(tree))
        Toast.makeText(context, "Swarms ready to share (${aiSettings.swarms.size})", Toast.LENGTH_SHORT).show()
    }

    fun exportSettings() {
        // GeneralSettings minus the three info-provider API keys (those
        // already round-trip through the API Keys export).
        val tree = buildGeneralSettingsTree(generalSettings)
        shareExportText(context, "ai_settings-${exportTimestamp()}.json", "application/json", "Share settings",
            createAppGson(prettyPrint = true).toJson(tree))
        Toast.makeText(context, "Settings ready to share", Toast.LENGTH_SHORT).show()
    }

    fun exportModelLists() {
        val tree = buildModelListsTree(aiSettings)
        shareExportText(context, "ai_model_lists-${exportTimestamp()}.json", "application/json", "Share model lists",
            createAppGson(prettyPrint = true).toJson(tree))
        val nonEmpty = aiSettings.providers.values.count { it.models.isNotEmpty() }
        val totalModels = aiSettings.providers.values.sumOf { it.models.size }
        Toast.makeText(context, "Model lists ready to share ($nonEmpty providers, $totalModels models)", Toast.LENGTH_SHORT).show()
    }

    fun exportEndpoints() {
        val tree = buildEndpointsTree(aiSettings)
        shareExportText(context, "ai_endpoints-${exportTimestamp()}.json", "application/json", "Share endpoints",
            createAppGson(prettyPrint = true).toJson(tree))
        val providers = tree.size()
        val total = aiSettings.endpoints.values.sumOf { it.size }
        Toast.makeText(context, "Endpoints ready to share ($providers providers, $total endpoints)", Toast.LENGTH_SHORT).show()
    }

    fun exportParameters() {
        val tree = buildParametersTree(aiSettings)
        shareExportText(context, "ai_parameters-${exportTimestamp()}.json", "application/json", "Share parameters",
            createAppGson(prettyPrint = true).toJson(tree))
        Toast.makeText(context, "Parameters ready to share (${aiSettings.parameters.size} entries)", Toast.LENGTH_SHORT).show()
    }

    fun exportSystemPrompts() {
        val tree = buildSystemPromptsTree(aiSettings)
        shareExportText(context, "ai_system_prompts-${exportTimestamp()}.json", "application/json", "Share system prompts",
            createAppGson(prettyPrint = true).toJson(tree))
        Toast.makeText(context, "System prompts ready to share (${aiSettings.systemPrompts.size} entries)", Toast.LENGTH_SHORT).show()
    }

    fun exportModelTypeOverrides() {
        val tree = buildModelTypeOverridesTree(aiSettings)
        shareExportText(context, "ai_model_overrides-${exportTimestamp()}.json", "application/json", "Share model overrides",
            createAppGson(prettyPrint = true).toJson(tree))
        Toast.makeText(context, "Model overrides ready to share (${aiSettings.modelTypeOverrides.size} entries)", Toast.LENGTH_SHORT).show()
    }

    fun exportModelCooldowns() {
        val tree = buildModelCooldownsTree()
        shareExportText(context, "ai_model_cooldowns-${exportTimestamp()}.json", "application/json", "Share model cooldowns",
            createAppGson(prettyPrint = true).toJson(tree))
        Toast.makeText(context, "Model cooldowns ready to share (${com.ai.data.ModelCooldownStore.cooldowns.value.size} entries)", Toast.LENGTH_SHORT).show()
    }

    fun exportBlockedModels() {
        val tree = buildBlockedModelsTree(aiSettings)
        shareExportText(context, "ai_blocked_models-${exportTimestamp()}.json", "application/json", "Share blocked models",
            createAppGson(prettyPrint = true).toJson(tree))
        Toast.makeText(context, "Blocked models ready to share (${aiSettings.blockedModels.size} entries)", Toast.LENGTH_SHORT).show()
    }

    fun exportTestExcludedModels() {
        val tree = buildTestExcludedModelsTree(aiSettings)
        shareExportText(context, "ai_test_excluded_models-${exportTimestamp()}.json", "application/json", "Share test-excluded models",
            createAppGson(prettyPrint = true).toJson(tree))
        Toast.makeText(context, "Test-excluded models ready to share (${aiSettings.testExcludedModels.size} entries)", Toast.LENGTH_SHORT).show()
    }

    fun exportInaccessibleModels() {
        val tree = buildInaccessibleModelsTree(aiSettings)
        shareExportText(context, "ai_inaccessible_models-${exportTimestamp()}.json", "application/json", "Share inaccessible models",
            createAppGson(prettyPrint = true).toJson(tree))
        Toast.makeText(context, "Inaccessible models ready to share (${aiSettings.inaccessibleModels.size} entries)", Toast.LENGTH_SHORT).show()
    }

    // "All" bundle: single JSON file carrying every section the
    // individual buttons would have written, except API keys (those
    // ship via the dedicated API Keys export). Structure:
    //   { "costs": [...], "providers": [...],
    //     "prompts": [...], "examples": [...],
    //     "agents": [...], "flocks": [...], "swarms": [...],
    //     "settings": {…}, "modelLists": {…},
    //     "parameters": [...], "systemPrompts": [...] }
    // Each section is the same payload the matching individual export
    // produces, so the importer can hand each one to its existing
    // handler without a separate schema.
    fun exportAll() {
        val bundle = buildAllBundle(aiSettings, generalSettings, context)
        shareExportText(context, "ai_bundle-${exportTimestamp()}.json", "application/json", "Share bundle",
            createAppGson(prettyPrint = true).toJson(bundle))
        val costs = bundle.getAsJsonArray("costs")?.size() ?: 0
        val providers = bundle.getAsJsonArray("providers")?.size() ?: 0
        val prompts = bundle.getAsJsonArray("prompts")?.size() ?: 0
        val examples = bundle.getAsJsonArray("examples")?.size() ?: 0
        val params = bundle.getAsJsonArray("parameters")?.size() ?: 0
        val sysPrompts = bundle.getAsJsonArray("systemPrompts")?.size() ?: 0
        val modelLists = bundle.getAsJsonObject("modelLists")?.size() ?: 0
        val blocked = bundle.getAsJsonArray("blockedModels")?.size() ?: 0
        val excluded = bundle.getAsJsonArray("testExcludedModels")?.size() ?: 0
        val inaccessible = bundle.getAsJsonArray("inaccessibleModels")?.size() ?: 0
        Toast.makeText(
            context,
            "Bundle ready to share ($costs costs, $providers providers, " +
                "$prompts prompts, $examples examples, " +
                "${aiSettings.agents.size} agents, ${aiSettings.flocks.size} flocks, ${aiSettings.swarms.size} swarms, " +
                "$modelLists model lists, $params parameters, $sysPrompts system prompts, $blocked blocked models, $excluded test-excluded models, $inaccessible inaccessible models)",
            Toast.LENGTH_LONG
        ).show()
    }

    fun exportRuntimeReports() {
        val bundle = buildReportsRuntimeBundle(context)
        shareExportText(context, "ai_reports-${exportTimestamp()}.json", "application/json", "Share reports",
            createAppGson(prettyPrint = true).toJson(bundle))
        val reports = bundle.getAsJsonArray("reports")?.size() ?: 0
        val secondaries = bundle.getAsJsonObject("secondaries")?.entrySet()?.sumOf {
            (it.value as? JsonArray)?.size() ?: 0
        } ?: 0
        Toast.makeText(context, "Reports ready to share ($reports reports, $secondaries meta-results)", Toast.LENGTH_SHORT).show()
    }

    fun exportRuntimeChats() {
        val bundle = buildChatsRuntimeBundle()
        shareExportText(context, "ai_chats-${exportTimestamp()}.json", "application/json", "Share chats",
            createAppGson(prettyPrint = true).toJson(bundle))
        val chats = bundle.getAsJsonArray("chats")?.size() ?: 0
        Toast.makeText(context, "Chats ready to share ($chats sessions)", Toast.LENGTH_SHORT).show()
    }

    fun exportRuntimeAll() {
        val bundle = buildAllRuntimeBundle(context)
        shareExportText(context, "ai_runtime-${exportTimestamp()}.json", "application/json", "Share runtime data",
            createAppGson(prettyPrint = true).toJson(bundle))
        val reports = bundle.getAsJsonArray("reports")?.size() ?: 0
        val chats = bundle.getAsJsonArray("chats")?.size() ?: 0
        val secondaries = bundle.getAsJsonObject("secondaries")?.entrySet()?.sumOf {
            (it.value as? JsonArray)?.size() ?: 0
        } ?: 0
        Toast.makeText(context, "Runtime data ready to share ($reports reports, $secondaries meta-results, $chats chats)", Toast.LENGTH_SHORT).show()
    }

    fun exportCosts() {
        val manual = PricingCache.getAllManualPricing(context)
        val lines = mutableListOf("provider,model,input_per_million,output_per_million")
        manual.forEach { (key, pricing) ->
            val parts = key.split(":", limit = 2)
            lines.add(listOf(
                csvField(parts[0]),
                csvField(parts.getOrElse(1) { "" }),
                "${pricing.promptPrice * 1_000_000}",
                "${pricing.completionPrice * 1_000_000}"
            ).joinToString(","))
        }
        shareExportText(context, "ai_costs-${exportTimestamp()}.csv", "text/csv", "Share costs",
            lines.joinToString("\n"))
        Toast.makeText(context, "${manual.size} cost entries ready to share", Toast.LENGTH_SHORT).show()
    }

    val importFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        when (importType) {
            "keys" -> {
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                try {
                    val result = applyApiKeysJson(json, aiSettings)
                    if (result == null) {
                        Toast.makeText(context, "Expected a JSON object like {\"OPENAI\": \"sk-...\"}", Toast.LENGTH_LONG).show()
                        return@rememberLauncherForActivityResult
                    }
                    // Batch all three external-service key updates into
                    // one onSaveGeneral call. Three sequential per-field
                    // setters each launch a Dispatchers.IO save coroutine
                    // with their own GeneralSettings snapshot, and on a
                    // multi-threaded IO dispatcher those writes race —
                    // last write wins, so two of the three keys ended up
                    // dropped on a fresh-install import.
                    val updatedGs = generalSettings.copy(
                        huggingFaceApiKey = result.huggingFaceApiKey ?: generalSettings.huggingFaceApiKey,
                        openRouterApiKey = result.openRouterApiKey ?: generalSettings.openRouterApiKey,
                        artificialAnalysisApiKey = result.artificialAnalysisApiKey ?: generalSettings.artificialAnalysisApiKey
                    )
                    if (updatedGs != generalSettings) onSaveGeneral(updatedGs)
                    onSave(result.settings)
                    val msg = "${result.imported} API keys imported" + if (result.skipped > 0) ", ${result.skipped} skipped" else ""
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    // Only prompt for the next step when at least one
                    // key actually landed — a "0 imported, N skipped"
                    // bundle has nothing to act on, no point asking.
                    if (result.imported > 0) keysImportedActions = true
                } catch (e: ConfigBundleMistakenForKeysException) {
                    Toast.makeText(context, "This looks like a full config bundle, not an API keys file.", Toast.LENGTH_LONG).show()
                } catch (e: JsonSyntaxException) {
                    AppLog.e("ImportExport", "API keys import parse error", e)
                    Toast.makeText(context, "Not valid JSON", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    AppLog.e("ImportExport", "API keys import error", e)
                    Toast.makeText(context, "Import failed (${e.javaClass.simpleName}); see logcat", Toast.LENGTH_LONG).show()
                }
            }
            "costs" -> {
                val csv = readFromUri(uri)
                if (csv.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                var imported = 0; var skipped = 0
                csv.lines().drop(1).filter { it.isNotBlank() }.forEach { line ->
                    val parts = parseCsvRow(line)
                    if (parts.size >= 4) {
                        val provider = AppService.findById(parts[0].trim())
                        val model = parts[1].trim()
                        val inp = parts[2].trim().toDoubleOrNull()?.div(1_000_000)
                        val outp = parts[3].trim().toDoubleOrNull()?.div(1_000_000)
                        if (provider != null && model.isNotBlank() && inp != null && outp != null) {
                            PricingCache.setManualPricing(context, provider, model, inp, outp); imported++
                        } else skipped++
                    } else skipped++
                }
                Toast.makeText(context, "Imported $imported costs" + (if (skipped > 0) ", skipped $skipped" else ""), Toast.LENGTH_SHORT).show()
            }
            "providers" -> {
                // Catalog-only update. Per-provider API keys live in
                // Settings (different prefs file) and are not touched.
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                val n = ProviderRegistry.upsertFromJson(json)
                if (n < 0) Toast.makeText(context, "Could not parse providers.json", Toast.LENGTH_LONG).show()
                else Toast.makeText(context, "Updated $n provider${if (n == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
            }
            "prompts" -> {
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                val pair = com.ai.data.InternalPromptSeed.upsertFromJson(json, aiSettings.internalPrompts)
                if (pair == null) {
                    Toast.makeText(context, "Could not parse prompts.json", Toast.LENGTH_LONG).show()
                } else {
                    val (updated, n) = pair
                    onSave(aiSettings.copy(internalPrompts = updated))
                    Toast.makeText(context, "Updated $n internal prompt${if (n == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
                }
            }
            "examples" -> {
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                val pair = com.ai.data.ExamplePromptSeed.upsertFromJson(json, aiSettings.examplePrompts)
                if (pair == null) {
                    Toast.makeText(context, "Could not parse examples.json", Toast.LENGTH_LONG).show()
                } else {
                    val (updated, n) = pair
                    onSave(aiSettings.copy(examplePrompts = updated))
                    Toast.makeText(context, "Updated $n example prompt${if (n == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
                }
            }
            "workers" -> {
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                val root = try { JsonParser.parseString(json) as? JsonObject } catch (_: Exception) { null }
                if (root == null) {
                    Toast.makeText(context, "Workers file is not a JSON object", Toast.LENGTH_LONG).show()
                    return@rememberLauncherForActivityResult
                }
                val res = applyWorkers(root, aiSettings)
                if (res.isEmpty()) {
                    Toast.makeText(context, "No agents / flocks / swarms found in file", Toast.LENGTH_LONG).show()
                } else {
                    onSave(res.settings)
                    Toast.makeText(
                        context,
                        "Imported ${res.agents} agents, ${res.flocks} flocks, ${res.swarms} swarms",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            "settings" -> {
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                val obj = try { JsonParser.parseString(json) as? JsonObject } catch (_: Exception) { null }
                if (obj == null) {
                    Toast.makeText(context, "Settings file is not a JSON object", Toast.LENGTH_LONG).show()
                    return@rememberLauncherForActivityResult
                }
                onSaveGeneral(applyGeneralSettings(obj, generalSettings))
                Toast.makeText(context, "Settings imported", Toast.LENGTH_SHORT).show()
            }
            "modelLists" -> {
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                val obj = try { JsonParser.parseString(json) as? JsonObject } catch (_: Exception) { null }
                if (obj == null) {
                    Toast.makeText(context, "Model lists file is not a JSON object", Toast.LENGTH_LONG).show()
                    return@rememberLauncherForActivityResult
                }
                val (updated, n) = applyModelLists(obj, aiSettings)
                if (n == 0) {
                    Toast.makeText(context, "No model lists matched a known provider", Toast.LENGTH_LONG).show()
                } else {
                    onSave(updated)
                    Toast.makeText(context, "Updated model lists for $n provider${if (n == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
                }
            }
            "endpoints" -> {
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                val obj = try { JsonParser.parseString(json) as? JsonObject } catch (_: Exception) { null }
                if (obj == null) {
                    Toast.makeText(context, "Endpoints file is not a JSON object", Toast.LENGTH_LONG).show()
                    return@rememberLauncherForActivityResult
                }
                val (updated, n) = applyEndpoints(obj, aiSettings)
                if (n == 0) {
                    Toast.makeText(context, "No endpoints matched a known provider", Toast.LENGTH_LONG).show()
                } else {
                    onSave(updated)
                    Toast.makeText(context, "Updated endpoints for $n provider${if (n == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
                }
            }
            "parameters" -> {
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                val arr = try { JsonParser.parseString(json) as? JsonArray } catch (_: Exception) { null }
                if (arr == null) {
                    Toast.makeText(context, "Parameters file is not a JSON array", Toast.LENGTH_LONG).show()
                    return@rememberLauncherForActivityResult
                }
                val (updated, n) = applyParameters(arr, aiSettings)
                if (n == 0) {
                    Toast.makeText(context, "No parameter presets found in file", Toast.LENGTH_LONG).show()
                } else {
                    onSave(updated)
                    Toast.makeText(context, "Imported $n parameter preset${if (n == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
                }
            }
            "systemPrompts" -> {
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                val arr = try { JsonParser.parseString(json) as? JsonArray } catch (_: Exception) { null }
                if (arr == null) {
                    Toast.makeText(context, "System prompts file is not a JSON array", Toast.LENGTH_LONG).show()
                    return@rememberLauncherForActivityResult
                }
                val (updated, n) = applySystemPrompts(arr, aiSettings)
                if (n == 0) {
                    Toast.makeText(context, "No system prompts found in file", Toast.LENGTH_LONG).show()
                } else {
                    onSave(updated)
                    Toast.makeText(context, "Imported $n system prompt${if (n == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
                }
            }
            "modelTypeOverrides" -> {
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                val arr = try { JsonParser.parseString(json) as? JsonArray } catch (_: Exception) { null }
                if (arr == null) {
                    Toast.makeText(context, "Model overrides file is not a JSON array", Toast.LENGTH_LONG).show()
                    return@rememberLauncherForActivityResult
                }
                val (updated, n) = applyModelTypeOverrides(arr, aiSettings)
                if (n == 0) {
                    Toast.makeText(context, "No model overrides found in file", Toast.LENGTH_LONG).show()
                } else {
                    onSave(updated)
                    Toast.makeText(context, "Imported $n model override${if (n == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
                }
            }
            "modelCooldowns" -> {
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                val obj = try { JsonParser.parseString(json) as? JsonObject } catch (_: Exception) { null }
                if (obj == null) {
                    Toast.makeText(context, "Model cooldowns file is not a JSON object", Toast.LENGTH_LONG).show()
                    return@rememberLauncherForActivityResult
                }
                val n = applyModelCooldowns(obj)
                Toast.makeText(context, "Imported $n model cooldown${if (n == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
            }
            "blockedModels" -> {
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                val arr = try { JsonParser.parseString(json) as? JsonArray } catch (_: Exception) { null }
                if (arr == null) {
                    Toast.makeText(context, "Blocked models file is not a JSON array", Toast.LENGTH_LONG).show()
                    return@rememberLauncherForActivityResult
                }
                val (updated, n) = applyBlockedModels(arr, aiSettings)
                if (n == 0) {
                    Toast.makeText(context, "No blocked models found in file", Toast.LENGTH_LONG).show()
                } else {
                    onSave(updated)
                    Toast.makeText(context, "Imported $n blocked model${if (n == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
                }
            }
            "testExcludedModels" -> {
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                val arr = try { JsonParser.parseString(json) as? JsonArray } catch (_: Exception) { null }
                if (arr == null) {
                    Toast.makeText(context, "Test-excluded models file is not a JSON array", Toast.LENGTH_LONG).show()
                    return@rememberLauncherForActivityResult
                }
                val (updated, n) = applyTestExcludedModels(arr, aiSettings)
                if (n == 0) {
                    Toast.makeText(context, "No test-excluded models found in file", Toast.LENGTH_LONG).show()
                } else {
                    onSave(updated)
                    Toast.makeText(context, "Imported $n test-excluded model${if (n == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
                }
            }
            "inaccessibleModels" -> {
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                val arr = try { JsonParser.parseString(json) as? JsonArray } catch (_: Exception) { null }
                if (arr == null) {
                    Toast.makeText(context, "Inaccessible models file is not a JSON array", Toast.LENGTH_LONG).show()
                    return@rememberLauncherForActivityResult
                }
                val (updated, n) = applyInaccessibleModels(arr, aiSettings)
                if (n == 0) {
                    Toast.makeText(context, "No inaccessible models found in file", Toast.LENGTH_LONG).show()
                } else {
                    onSave(updated)
                    Toast.makeText(context, "Imported $n inaccessible model${if (n == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
                }
            }
            "runtimeReports" -> {
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                val root = try { JsonParser.parseString(json) as? JsonObject } catch (_: Exception) { null }
                if (root == null) {
                    Toast.makeText(context, "Reports file is not a JSON object", Toast.LENGTH_LONG).show()
                    return@rememberLauncherForActivityResult
                }
                val res = applyRuntimeReports(context, root)
                val msg = buildString {
                    append("Added ${res.added} report")
                    if (res.added != 1) append("s")
                    if (res.secondaries > 0) append(" + ${res.secondaries} meta-results")
                    if (res.skipped > 0) append(" (${res.skipped} skipped, already present)")
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
            "runtimeChats" -> {
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                val root = try { JsonParser.parseString(json) as? JsonObject } catch (_: Exception) { null }
                if (root == null) {
                    Toast.makeText(context, "Chats file is not a JSON object", Toast.LENGTH_LONG).show()
                    return@rememberLauncherForActivityResult
                }
                val res = applyRuntimeChats(root)
                val msg = "Added ${res.added} chat session${if (res.added == 1) "" else "s"}" +
                    if (res.skipped > 0) " (${res.skipped} skipped, already present)" else ""
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
            "runtimeAll" -> {
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                val root = try { JsonParser.parseString(json) as? JsonObject } catch (_: Exception) { null }
                if (root == null) {
                    Toast.makeText(context, "Runtime file is not a JSON object", Toast.LENGTH_LONG).show()
                    return@rememberLauncherForActivityResult
                }
                val rRes = if (root.has("reports")) applyRuntimeReports(context, root) else ImportReportsResult(0, 0, 0)
                val cRes = if (root.has("chats")) applyRuntimeChats(root) else ImportChatsResult(0, 0)
                if (rRes.added == 0 && cRes.added == 0 && rRes.skipped == 0 && cRes.skipped == 0) {
                    Toast.makeText(context, "No runtime data found in file", Toast.LENGTH_LONG).show()
                } else {
                    val parts = mutableListOf<String>()
                    if (rRes.added > 0) parts += "${rRes.added} reports"
                    if (rRes.secondaries > 0) parts += "${rRes.secondaries} meta-results"
                    if (cRes.added > 0) parts += "${cRes.added} chats"
                    val skipped = rRes.skipped + cRes.skipped
                    val msg = "Added " + (if (parts.isEmpty()) "nothing new" else parts.joinToString(", ")) +
                        if (skipped > 0) " ($skipped skipped, already present)" else ""
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
            "all" -> {
                // All-bundle: { apiKeys, costs, providers, prompts }.
                // Each section is optional; missing or malformed sections
                // are skipped, present ones are dispatched through the
                // same logic the standalone importers use. Settings
                // updates are folded together (keys + prompts) so the
                // single onSave at the end carries both.
                val json = readFromUri(uri)
                if (json.isNullOrBlank()) { Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
                val root = try { JsonParser.parseString(json) as? JsonObject } catch (_: Exception) { null }
                if (root == null) {
                    Toast.makeText(context, "Bundle is not a JSON object", Toast.LENGTH_LONG).show()
                    return@rememberLauncherForActivityResult
                }
                val parts = mutableListOf<String>()
                var working = aiSettings
                // Fold every GeneralSettings mutation (apiKeys block +
                // settings block) into a single onSaveGeneral call at
                // the end so the three external-service writes don't
                // race on Dispatchers.IO.
                var workingGs = generalSettings

                root.getAsJsonObject("apiKeys")?.let { keysObj ->
                    try {
                        val res = applyApiKeysJson(keysObj.toString(), working)
                        if (res != null) {
                            workingGs = workingGs.copy(
                                huggingFaceApiKey = res.huggingFaceApiKey ?: workingGs.huggingFaceApiKey,
                                openRouterApiKey = res.openRouterApiKey ?: workingGs.openRouterApiKey,
                                artificialAnalysisApiKey = res.artificialAnalysisApiKey ?: workingGs.artificialAnalysisApiKey
                            )
                            working = res.settings
                            parts.add("${res.imported} keys")
                        }
                    } catch (e: Exception) {
                        AppLog.w("ImportExport", "Bundle apiKeys section failed: ${e.message}")
                    }
                }

                root.getAsJsonArray("costs")?.let { arr ->
                    var imported = 0
                    arr.forEach { el ->
                        val o = (el as? JsonObject) ?: return@forEach
                        val provId = o.get("provider")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                        val model = o.get("model")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                        val inp = o.get("inputPerMillion")?.takeIf { it.isJsonPrimitive }?.asDouble?.div(1_000_000) ?: return@forEach
                        val outp = o.get("outputPerMillion")?.takeIf { it.isJsonPrimitive }?.asDouble?.div(1_000_000) ?: return@forEach
                        val provider = AppService.findById(provId) ?: return@forEach
                        if (model.isNotBlank()) {
                            PricingCache.setManualPricing(context, provider, model, inp, outp); imported++
                        }
                    }
                    if (imported > 0) parts.add("$imported costs")
                }

                root.getAsJsonArray("providers")?.let { arr ->
                    val wrapped = JsonObject().apply { add("providers", arr) }
                    val n = ProviderRegistry.upsertFromJson(wrapped.toString())
                    if (n > 0) parts.add("$n providers")
                }

                root.getAsJsonArray("prompts")?.let { arr ->
                    val pair = com.ai.data.InternalPromptSeed.upsertFromJson(arr.toString(), working.internalPrompts)
                    if (pair != null) {
                        working = working.copy(internalPrompts = pair.first)
                        if (pair.second > 0) parts.add("${pair.second} prompts")
                    }
                }

                root.getAsJsonArray("examples")?.let { arr ->
                    val pair = com.ai.data.ExamplePromptSeed.upsertFromJson(arr.toString(), working.examplePrompts)
                    if (pair != null) {
                        working = working.copy(examplePrompts = pair.first)
                        if (pair.second > 0) parts.add("${pair.second} examples")
                    }
                }

                run {
                    val w = applyWorkers(root, working)
                    if (!w.isEmpty()) {
                        working = w.settings
                        if (w.agents > 0) parts.add("${w.agents} agents")
                        if (w.flocks > 0) parts.add("${w.flocks} flocks")
                        if (w.swarms > 0) parts.add("${w.swarms} swarms")
                    }
                }

                root.getAsJsonObject("settings")?.let { obj ->
                    workingGs = applyGeneralSettings(obj, workingGs)
                    parts.add("settings")
                }

                root.getAsJsonObject("modelLists")?.let { obj ->
                    val (updated, n) = applyModelLists(obj, working)
                    if (n > 0) { working = updated; parts.add("$n model lists") }
                }

                root.getAsJsonObject("endpoints")?.let { obj ->
                    val (updated, n) = applyEndpoints(obj, working)
                    if (n > 0) { working = updated; parts.add("$n endpoints") }
                }

                root.getAsJsonArray("parameters")?.let { arr ->
                    val (updated, n) = applyParameters(arr, working)
                    if (n > 0) { working = updated; parts.add("$n parameters") }
                }

                root.getAsJsonArray("systemPrompts")?.let { arr ->
                    val (updated, n) = applySystemPrompts(arr, working)
                    if (n > 0) { working = updated; parts.add("$n system prompts") }
                }

                root.getAsJsonArray("modelTypeOverrides")?.let { arr ->
                    val (updated, n) = applyModelTypeOverrides(arr, working)
                    if (n > 0) { working = updated; parts.add("$n model overrides") }
                }

                root.getAsJsonObject("modelCooldowns")?.let { obj ->
                    val n = applyModelCooldowns(obj)
                    if (n > 0) parts.add("$n model cooldowns")
                }

                root.getAsJsonArray("blockedModels")?.let { arr ->
                    val (updated, n) = applyBlockedModels(arr, working)
                    if (n > 0) { working = updated; parts.add("$n blocked models") }
                }

                root.getAsJsonArray("testExcludedModels")?.let { arr ->
                    val (updated, n) = applyTestExcludedModels(arr, working)
                    if (n > 0) { working = updated; parts.add("$n test-excluded models") }
                }

                root.getAsJsonArray("inaccessibleModels")?.let { arr ->
                    val (updated, n) = applyInaccessibleModels(arr, working)
                    if (n > 0) { working = updated; parts.add("$n inaccessible models") }
                }

                if (workingGs != generalSettings) onSaveGeneral(workingGs)
                if (working !== aiSettings) onSave(working)
                if (parts.isEmpty()) {
                    Toast.makeText(context, "Bundle had no recognised sections", Toast.LENGTH_LONG).show()
                } else {
                    restartMessage = "Imported: " + parts.joinToString(", ")
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(
            helpTopic = "import_export",
            title = if (importOnly) "Import" else "Export / Import",
            onBackClick = onBack
        )
        Spacer(modifier = Modifier.height(12.dp))
        restartMessage?.let { msg ->
            RestartAppBanner(message = msg, onConfirm = { restartApp(context) })
        }
        if (keysImportedActions) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "API keys imported — pick what should happen next:",
                    fontSize = 12.sp, color = AppColors.TextTertiary
                )
                Button(
                    onClick = {
                        keysImportedActions = false
                        onStartRefreshAll()
                        onNavigateToRefresh()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
                ) { Text("Refresh all", maxLines = 1, softWrap = false) }
                Button(
                    onClick = {
                        keysImportedActions = false
                        onStartRefreshWorkers()
                        onNavigateToRefresh()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
                ) { Text("Refresh providers, model lists & default agents", maxLines = 1, softWrap = false) }
                Button(
                    onClick = { restartApp(context) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
                ) { Text("Restart application", maxLines = 1, softWrap = false) }
            }
        }

        // Accordion state for the three cards on this screen: at most
        // one is expanded at a time. Tapping the open card's header
        // collapses it; tapping a closed one collapses any sibling and
        // opens this one. Cards keep their identity through the
        // navigation hops via rememberSaveable so a back-pop returns
        // to the same open card.
        var openCard by rememberSaveable { mutableStateOf<String?>(null) }
        fun toggle(id: String) { openCard = if (openCard == id) null else id }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // API keys live in their own card so the Export "All" button
            // never carries them — sharing a bundle by accident shouldn't
            // leak credentials. Importing keys from another install is
            // the first-run use case, so the Import button stays usable
            // even when the Export card below is hidden.
            ControlledCollapsibleCard(
                title = "API keys",
                expanded = openCard == "keys",
                onToggle = { toggle("keys") }
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!importOnly) {
                        OutlinedButton(onClick = { exportKeys() },
                            modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("Export", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    }
                    OutlinedButton(onClick = {
                        importType = "keys"; importFileLauncher.launch(arrayOf("application/json", "text/*"))
                    }, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("Import", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                }
            }

            // Configuration: every catalog / prompt / agent section
            // collapsed into a single card. One row per section with
            // Export + Import buttons; importOnly mode hides the Export
            // column so the first-run shape is just a list of "Import X"
            // buttons.
            ControlledCollapsibleCard(
                title = "Configuration",
                expanded = openCard == "config",
                onToggle = { toggle("config") }
            ) {
                ImportExportRow("providers.json", importOnly,
                    onExport = { exportProvidersJson() },
                    onImport = { importType = "providers"; importFileLauncher.launch(arrayOf("application/json", "text/*")) })
                ImportExportRow("prompts.json", importOnly,
                    onExport = { exportPromptsJson() },
                    onImport = { importType = "prompts"; importFileLauncher.launch(arrayOf("application/json", "text/*")) })
                ImportExportRow("examples.json", importOnly,
                    onExport = { exportExamplePrompts() },
                    onImport = { importType = "examples"; importFileLauncher.launch(arrayOf("application/json", "text/*")) })
                // Agents / Flocks / Swarms split into three rows. The
                // export side writes a single-key {agents|flocks|swarms}
                // JSON object; the import side dispatches to "workers"
                // for all three since applyWorkers reads the keys
                // independently and skips any that are absent.
                ImportExportRow("Agents", importOnly,
                    onExport = { exportAgents() },
                    onImport = { importType = "workers"; importFileLauncher.launch(arrayOf("application/json", "text/*")) })
                ImportExportRow("Flocks", importOnly,
                    onExport = { exportFlocks() },
                    onImport = { importType = "workers"; importFileLauncher.launch(arrayOf("application/json", "text/*")) })
                ImportExportRow("Swarms", importOnly,
                    onExport = { exportSwarms() },
                    onImport = { importType = "workers"; importFileLauncher.launch(arrayOf("application/json", "text/*")) })
                ImportExportRow("Settings", importOnly,
                    onExport = { exportSettings() },
                    onImport = { importType = "settings"; importFileLauncher.launch(arrayOf("application/json", "text/*")) })
                ImportExportRow("Model lists", importOnly,
                    onExport = { exportModelLists() },
                    onImport = { importType = "modelLists"; importFileLauncher.launch(arrayOf("application/json", "text/*")) })
                ImportExportRow("Parameters", importOnly,
                    onExport = { exportParameters() },
                    onImport = { importType = "parameters"; importFileLauncher.launch(arrayOf("application/json", "text/*")) })
                ImportExportRow("System prompts", importOnly,
                    onExport = { exportSystemPrompts() },
                    onImport = { importType = "systemPrompts"; importFileLauncher.launch(arrayOf("application/json", "text/*")) })
                ImportExportRow("Endpoints", importOnly,
                    onExport = { exportEndpoints() },
                    onImport = { importType = "endpoints"; importFileLauncher.launch(arrayOf("application/json", "text/*")) })
                ImportExportRow("Model overrides", importOnly,
                    onExport = { exportModelTypeOverrides() },
                    onImport = { importType = "modelTypeOverrides"; importFileLauncher.launch(arrayOf("application/json", "text/*")) })
                ImportExportRow("Model cooldowns", importOnly,
                    onExport = { exportModelCooldowns() },
                    onImport = { importType = "modelCooldowns"; importFileLauncher.launch(arrayOf("application/json", "text/*")) })
                ImportExportRow("Blocked models", importOnly,
                    onExport = { exportBlockedModels() },
                    onImport = { importType = "blockedModels"; importFileLauncher.launch(arrayOf("application/json", "text/*")) })
                ImportExportRow("Test-excluded models", importOnly,
                    onExport = { exportTestExcludedModels() },
                    onImport = { importType = "testExcludedModels"; importFileLauncher.launch(arrayOf("application/json", "text/*")) })
                ImportExportRow("Inaccessible models", importOnly,
                    onExport = { exportInaccessibleModels() },
                    onImport = { importType = "inaccessibleModels"; importFileLauncher.launch(arrayOf("application/json", "text/*")) })
                ImportExportRow("Costs Overrides", importOnly,
                    onExport = { exportCosts() },
                    onImport = { importType = "costs"; importFileLauncher.launch(arrayOf("text/*", "text/csv", "application/octet-stream")) })
                // "All" bundles every section above; Export omits API
                // keys (their dedicated card handles that), the Import
                // still tolerates an apiKeys section for older bundles.
                ImportExportRow("All", importOnly,
                    onExport = { exportAll() },
                    onImport = { importType = "all"; importFileLauncher.launch(arrayOf("application/json", "text/*")) })
            }

            // Runtime data = reports + chat sessions. Different from
            // the configuration card above: this carries user activity,
            // not catalog / prompt / agent definitions. Imports here
            // merge additively (by id) — existing rows with the same id
            // are kept, only new ids land. Safer than replace for an
            // activity log the user accumulated on the source phone.
            ControlledCollapsibleCard(
                title = "Runtime data",
                expanded = openCard == "runtime",
                onToggle = { toggle("runtime") }
            ) {
                ImportExportRow("Reports", importOnly,
                    onExport = { exportRuntimeReports() },
                    onImport = { importType = "runtimeReports"; importFileLauncher.launch(arrayOf("application/json", "text/*")) })
                ImportExportRow("Chat", importOnly,
                    onExport = { exportRuntimeChats() },
                    onImport = { importType = "runtimeChats"; importFileLauncher.launch(arrayOf("application/json", "text/*")) })
                ImportExportRow("All", importOnly,
                    onExport = { exportRuntimeAll() },
                    onImport = { importType = "runtimeAll"; importFileLauncher.launch(arrayOf("application/json", "text/*")) })
            }

        }
    }
}

/** One row inside the Configuration / Runtime data cards: section
 *  label on the left, Export and Import buttons on the right. The
 *  Export column drops out in [importOnly] mode (first-run Restore /
 *  Import variant of the screen) so the button strip narrows to a
 *  single Import column. */
@Composable
private fun ImportExportRow(
    label: String,
    importOnly: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = Color.White, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (!importOnly) {
            OutlinedButton(onClick = onExport,
                modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) {
                Text("Export", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
        }
        OutlinedButton(onClick = onImport,
            modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) {
            Text("Import", fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
    }
}
