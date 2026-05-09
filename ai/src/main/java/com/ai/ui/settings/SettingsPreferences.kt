package com.ai.ui.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import com.ai.data.AppService
import com.ai.data.createAppGson
import com.ai.data.writeTextAtomic
import com.ai.model.*
import com.ai.viewmodel.GeneralSettings
import com.ai.viewmodel.ModelNameLayout
import com.ai.viewmodel.PromptHistoryEntry
import com.google.gson.reflect.TypeToken
import java.io.File
import java.lang.reflect.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages all settings persistence via SharedPreferences and file-based storage.
 */
class SettingsPreferences(private val prefs: SharedPreferences, private val filesDir: File? = null) {

    private val gson = createAppGson()

    private object TypeTokens {
        val listStringType: Type = object : TypeToken<List<String>>() {}.type
        val listAgentType: Type = object : TypeToken<List<Agent>>() {}.type
        val listFlockType: Type = object : TypeToken<List<Flock>>() {}.type
        val listSwarmType: Type = object : TypeToken<List<Swarm>>() {}.type
        val listParametersType: Type = object : TypeToken<List<Parameters>>() {}.type
        val listSystemPromptType: Type = object : TypeToken<List<SystemPrompt>>() {}.type
        val listInternalPromptType: Type = object : TypeToken<List<InternalPrompt>>() {}.type
        val listModelTypeOverrideType: Type = object : TypeToken<List<ModelTypeOverride>>() {}.type
        val mapEndpointsType: Type = object : TypeToken<Map<String, List<Endpoint>>>() {}.type
        val mapStringStringType: Type = object : TypeToken<Map<String, String>>() {}.type
        val listPromptHistoryType: Type = object : TypeToken<List<PromptHistoryEntry>>() {}.type
        val listUsageStatsType: Type = object : TypeToken<List<UsageStats>>() {}.type
    }

    // ===== General Settings =====

    fun loadGeneralSettings(): GeneralSettings {
        val typePathsJson = prefs.getString(KEY_DEFAULT_TYPE_PATHS, null)
        val defaultTypePaths: Map<String, String> = typePathsJson?.let {
            try {
                @Suppress("UNCHECKED_CAST")
                (gson.fromJson(it, Map::class.java) as? Map<String, String>) ?: emptyMap()
            } catch (_: Exception) { emptyMap() }
        } ?: emptyMap()
        val layoutName = prefs.getString(KEY_MODEL_NAME_LAYOUT, null)
        val modelNameLayout = layoutName?.let {
            try { ModelNameLayout.valueOf(it) } catch (_: Exception) { null }
        } ?: ModelNameLayout.MODEL_ONLY
        return GeneralSettings(
            userName = prefs.getString(KEY_USER_NAME, "user") ?: "user",
            huggingFaceApiKey = prefs.getString(KEY_HUGGINGFACE_API_KEY, "") ?: "",
            openRouterApiKey = prefs.getString(KEY_OPENROUTER_API_KEY, "") ?: "",
            artificialAnalysisApiKey = prefs.getString(KEY_AA_API_KEY, "") ?: "",
            defaultEmail = prefs.getString(KEY_DEFAULT_EMAIL, "") ?: "",
            defaultTypePaths = defaultTypePaths,
            tracingEnabled = prefs.getBoolean(KEY_TRACING_ENABLED, true),
            modelNameLayout = modelNameLayout,
            showBackButton = prefs.getBoolean(KEY_SHOW_BACK_BUTTON, true)
        )
    }

    fun saveGeneralSettings(settings: GeneralSettings) {
        prefs.edit {
            putString(KEY_USER_NAME, settings.userName.ifBlank { "user" })
            putString(KEY_HUGGINGFACE_API_KEY, settings.huggingFaceApiKey)
            putString(KEY_OPENROUTER_API_KEY, settings.openRouterApiKey)
            putString(KEY_AA_API_KEY, settings.artificialAnalysisApiKey)
            putString(KEY_DEFAULT_EMAIL, settings.defaultEmail)
            putString(KEY_DEFAULT_TYPE_PATHS, gson.toJson(settings.defaultTypePaths))
            putBoolean(KEY_TRACING_ENABLED, settings.tracingEnabled)
            putString(KEY_MODEL_NAME_LAYOUT, settings.modelNameLayout.name)
            putBoolean(KEY_SHOW_BACK_BUTTON, settings.showBackButton)
        }
    }

    // ===== AI Settings =====

    fun loadSettingsWithMigration(): Settings {
        val base = loadSettings()
        return base.copy(
            agents = loadList(KEY_AI_AGENTS, TypeTokens.listAgentType) { (it as? List<Agent>)?.filter { a -> AppService.findById(a.provider.id) != null } ?: emptyList() },
            flocks = loadList(KEY_AI_FLOCKS, TypeTokens.listFlockType),
            swarms = loadList(KEY_AI_SWARMS, TypeTokens.listSwarmType),
            parameters = loadList(KEY_AI_PARAMETERS, TypeTokens.listParametersType),
            systemPrompts = loadList(KEY_AI_SYSTEM_PROMPTS, TypeTokens.listSystemPromptType),
            // Gson reflection bypasses Kotlin defaults for fields that
            // didn't exist when the JSON was written, so rows persisted
            // by the previous build (without category / agent) come back
            // with those properties as runtime null. Patch them up.
            internalPrompts = loadList<InternalPrompt>(KEY_AI_INTERNAL_PROMPTS, TypeTokens.listInternalPromptType).map { ip ->
                @Suppress("USELESS_CAST")
                val cat = (ip.category as String?) ?: "meta"
                @Suppress("USELESS_CAST")
                val ag = (ip.agent as String?) ?: "*select"
                @Suppress("USELESS_CAST")
                val ttl = (ip.title as String?) ?: ""
                if (cat == ip.category && ag == ip.agent && ttl == ip.title) ip
                else ip.copy(category = cat, agent = ag, title = ttl)
            },
            endpoints = loadEndpoints(),
            providerStates = loadMap(KEY_PROVIDER_STATES),
            modelTypeOverrides = loadList(KEY_AI_MODEL_TYPE_OVERRIDES, TypeTokens.listModelTypeOverrideType)
        )
    }

    private fun loadSettings(): Settings {
        val providers = AppService.entries.associateWith { service ->
            val key = service.id
            val defaults = defaultProviderConfig(service)
            val modelSource = prefs.getString("${key}_model_source", null)?.let {
                try { ModelSource.valueOf(it) } catch (_: Exception) { null }
            } ?: defaults.modelSource
            // Migration shim: dedupe on read so any prefs written before
            // Settings.withModels learned to .distinct() come up clean
            // without a manual refresh. Cheap (small lists).
            val models = (
                if (defaults.models.isNotEmpty())
                    loadJsonList("${key}_manual_models") ?: defaults.models
                else
                    loadJsonList("${key}_manual_models") ?: emptyList()
            ).distinct()
            val storedTypes: Map<String, String> = prefs.getString("${key}_model_types", null)?.let {
                try {
                    @Suppress("UNCHECKED_CAST")
                    gson.fromJson(it, Map::class.java) as? Map<String, String>
                } catch (_: Exception) { null }
            } ?: emptyMap()
            // Backfill heuristic types for any models loaded from old prefs that pre-date
            // the types column, so the UI / dispatcher always has *some* classification.
            val types = models.associateWith { id -> storedTypes[id] ?: com.ai.data.ModelType.infer(id) }

            // visionModels (user override + auto-flagged on fetch) and
            // webSearchModels are JSON-encoded string lists. Falsy/missing
            // values fall through to empty sets — older prefs files migrate
            // transparently.
            val visionModels = loadJsonStringSet("${key}_vision_models")
            val webSearchModels = loadJsonStringSet("${key}_web_search_models")
            val reasoningModels = loadJsonStringSet("${key}_reasoning_models")
            val visionCapableComputed = loadJsonStringSet("${key}_vision_capable_computed")
            val webSearchCapableComputed = loadJsonStringSet("${key}_web_search_capable_computed")
            val reasoningCapableComputed = loadJsonStringSet("${key}_reasoning_capable_computed")
            val modelPricing: Map<String, com.ai.data.PricingCache.ModelPricing> = prefs.getString("${key}_model_pricing", null)?.let {
                try {
                    val mapType = object : com.google.gson.reflect.TypeToken<Map<String, com.ai.data.PricingCache.ModelPricing>>() {}.type
                    gson.fromJson(it, mapType) ?: emptyMap()
                } catch (_: Exception) { null }
            } ?: emptyMap()
            val modelCapabilities: Map<String, com.ai.data.ModelCapabilities> = prefs.getString("${key}_model_capabilities", null)?.let {
                try {
                    val mapType = object : com.google.gson.reflect.TypeToken<Map<String, com.ai.data.ModelCapabilities>>() {}.type
                    gson.fromJson(it, mapType) ?: emptyMap()
                } catch (_: Exception) { null }
            } ?: emptyMap()
            val modelListRawJson = prefs.getString("${key}_models_response_raw", null)

            ProviderConfig(
                apiKey = prefs.getString("${key}_api_key", "") ?: "",
                model = prefs.getString("${key}_model", service.defaultModel) ?: service.defaultModel,
                modelSource = modelSource, models = models, modelTypes = types,
                visionModels = visionModels, webSearchModels = webSearchModels,
                reasoningModels = reasoningModels,
                visionCapableComputed = visionCapableComputed,
                webSearchCapableComputed = webSearchCapableComputed,
                reasoningCapableComputed = reasoningCapableComputed,
                modelPricing = modelPricing,
                modelCapabilities = modelCapabilities,
                modelListRawJson = modelListRawJson,
                parametersIds = loadJsonList("${key}_parameters_id") ?: emptyList()
            )
        }
        return Settings(providers = providers)
    }

    private fun loadJsonStringSet(key: String): Set<String> {
        val json = prefs.getString(key, null) ?: return emptySet()
        return try {
            @Suppress("UNCHECKED_CAST")
            (gson.fromJson(json, List::class.java) as? List<String>)?.toSet() ?: emptySet()
        } catch (_: Exception) { emptySet() }
    }

    fun saveSettings(settings: Settings) {
        prefs.edit {
            for (service in AppService.entries) {
                val key = service.id
                val config = settings.providers[service] ?: defaultProviderConfig(service)
                putString("${key}_api_key", config.apiKey)
                putString("${key}_model", config.model)
                putString("${key}_model_source", config.modelSource.name)
                putString("${key}_manual_models", gson.toJson(config.models))
                putString("${key}_model_types", gson.toJson(config.modelTypes))
                // User-curated vision / web-search overrides + the per-fetch
                // capability sidecar. Without these the in-memory state was
                // dropping on every app restart, and the backup zip never
                // saw it either.
                putString("${key}_vision_models", if (config.visionModels.isEmpty()) null else gson.toJson(config.visionModels.toList()))
                putString("${key}_web_search_models", if (config.webSearchModels.isEmpty()) null else gson.toJson(config.webSearchModels.toList()))
                putString("${key}_reasoning_models", if (config.reasoningModels.isEmpty()) null else gson.toJson(config.reasoningModels.toList()))
                // Pre-computed result of the layered isVisionCapable /
                // isWebSearchCapable / isReasoningCapable lookup — stored
                // so list-render code can short-circuit through a Set
                // membership check instead of re-running ~1k-entry
                // catalog scans on every row.
                putString("${key}_vision_capable_computed", if (config.visionCapableComputed.isEmpty()) null else gson.toJson(config.visionCapableComputed.toList()))
                putString("${key}_web_search_capable_computed", if (config.webSearchCapableComputed.isEmpty()) null else gson.toJson(config.webSearchCapableComputed.toList()))
                putString("${key}_reasoning_capable_computed", if (config.reasoningCapableComputed.isEmpty()) null else gson.toJson(config.reasoningCapableComputed.toList()))
                putString("${key}_model_pricing", if (config.modelPricing.isEmpty()) null else gson.toJson(config.modelPricing))
                putString("${key}_model_capabilities", if (config.modelCapabilities.isEmpty()) null else gson.toJson(config.modelCapabilities))
                // Raw /models response — kept verbatim so a later parser
                // revision can pull out new fields without forcing a refetch.
                putString("${key}_models_response_raw", config.modelListRawJson)
                putString("${key}_parameters_id", if (config.parametersIds.isEmpty()) null else gson.toJson(config.parametersIds))
            }
            putString(KEY_AI_AGENTS, gson.toJson(settings.agents))
            putString(KEY_AI_FLOCKS, gson.toJson(settings.flocks))
            putString(KEY_AI_SWARMS, gson.toJson(settings.swarms))
            putString(KEY_AI_PARAMETERS, gson.toJson(settings.parameters))
            putString(KEY_AI_SYSTEM_PROMPTS, gson.toJson(settings.systemPrompts))
            putString(KEY_AI_INTERNAL_PROMPTS, gson.toJson(settings.internalPrompts))
            putString(KEY_AI_ENDPOINTS, gson.toJson(settings.endpoints.mapKeys { it.key.id }))
            putString(KEY_PROVIDER_STATES, gson.toJson(settings.providerStates))
            putString(KEY_AI_MODEL_TYPE_OVERRIDES, gson.toJson(settings.modelTypeOverrides))
        }
    }

    /** One-shot migration of legacy provider storage into the unified
     *  shape. Two earlier refactors converged here:
     *
     *  1. Admin URL / model-list URL override layer: pre-refactor builds
     *     stored a user-edited Admin URL under "${prefsKey}_admin_url"
     *     and a custom model-list URL under "${prefsKey}_model_list_url"
     *     in this prefs file. Both layered on top of the bundled-asset
     *     values at read time. The Admin URL value is migrated into the
     *     catalog (`AppService.adminUrl` via `ProviderRegistry.update`);
     *     the model-list URL is dropped (it had no dispatch consumer).
     *
     *  2. Provider id unification: pre-refactor providers.json carried
     *     three name-like fields per entry — `id` (SCREAMING_SNAKE),
     *     `displayName` (human label), `prefsKey` (`ai_<lowercase>`).
     *     Code referenced all three, surfaced confusion, and the
     *     per-provider settings screen even exposed prefsKey to the
     *     user. The unified shape is one field — `id` — whose value
     *     equals what `displayName` used to hold (e.g. `"OpenAI"`,
     *     `"Anthropic"`). This migration:
     *       - reads the legacy provider_registry JSON to learn
     *         every (oldId, oldPrefsKey, displayName) triple
     *       - computes newId = displayName with spaces stripped
     *       - renames every "${oldPrefsKey}_<suffix>" eval_prefs key
     *         to "${newId}_<suffix>" (covering all 15 active suffixes)
     *       - rewrites providerStates / endpoints / modelTypeOverrides
     *         / agents JSON in eval_prefs so old id strings resolve
     *       - rewrites the `manual_pricing` map keys in pricing_cache
     *         prefs ("oldId:model" → "newId:model")
     *       - renames model_lists/<oldId>.json files
     *       - rewrites the provider_registry JSON with only the new id
     *         (drops displayName + prefsKey)
     *
     *  Other persisted artifacts (chat sessions, reports, traces,
     *  secondary results, usage-stats.json) carry the AppService string
     *  id via [com.ai.data.AppServiceAdapter]. Their migration is
     *  covered by making `AppService.findById` case-insensitive — old
     *  uppercase ids transparently resolve to the new mixed-case
     *  AppService and the next save rewrites the file.
     *
     *  Returns the count of legacy provider entries migrated (0 when
     *  nothing legacy is present — the typical path on a fresh install
     *  or any restart past the first). Idempotent + gated on a marker
     *  pref so subsequent invocations are no-ops.
     *
     *  Callers: [com.ai.data.BackupManager.restore] invokes after the
     *  prefs commit; [com.ai.viewmodel.AppViewModel.bootstrap] invokes
     *  once at startup to cover in-place upgrades. */
    fun migrateLegacyProviderIds(context: android.content.Context): Int {
        // Independent sweeper: drop orphan `*_model_kinds` keys from
        // a previous capability-cache shape. Runs at most once even
        // if the main migration is already done.
        if (!prefs.getBoolean(KEY_MODEL_KINDS_SWEEP_DONE, false)) {
            val orphans = prefs.all.keys.filter { it.endsWith("_model_kinds") }
            if (orphans.isNotEmpty()) {
                prefs.edit {
                    orphans.forEach { remove(it) }
                    putBoolean(KEY_MODEL_KINDS_SWEEP_DONE, true)
                }
                android.util.Log.i("SettingsPreferences",
                    "migrateLegacyProviderIds: swept ${orphans.size} orphan _model_kinds key(s)")
            } else {
                prefs.edit { putBoolean(KEY_MODEL_KINDS_SWEEP_DONE, true) }
            }
        }

        if (prefs.getBoolean(KEY_PROVIDER_ID_UNIFICATION_MIGRATED, false)) return 0

        val regPrefs = context.getSharedPreferences(PROVIDER_REGISTRY_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val regJson = regPrefs.getString(KEY_REGISTRY_PROVIDERS_JSON, null) ?: run {
            // No persisted registry yet — fresh install. Mark migrated and bail.
            prefs.edit { putBoolean(KEY_PROVIDER_ID_UNIFICATION_MIGRATED, true) }
            return 0
        }

        // Parse the OLD registry shape. A legacy entry carries `id` (e.g.
        // "OPENAI"), `displayName` ("OpenAI"), `prefsKey` ("ai_openai").
        // Detect legacy by presence of either displayName or prefsKey.
        data class Legacy(val oldId: String, val oldPrefsKey: String, val newId: String, val adminUrlOverride: String?)
        val legacy = try {
            val arr = com.google.gson.JsonParser.parseString(regJson).asJsonArray
            arr.mapNotNull { el ->
                val o = el.asJsonObject
                val oid = o["id"]?.asString ?: return@mapNotNull null
                val dn = o["displayName"]?.asString
                val pk = o["prefsKey"]?.asString
                if (dn == null && pk == null) return@mapNotNull null   // already in new shape
                val newId = (dn ?: oid).replace(" ", "")
                val oldPrefsKey = pk ?: oid.lowercase()
                val adminOverride = prefs.getString("${oldPrefsKey}_admin_url", null)?.takeIf { it.isNotBlank() }
                Legacy(oldId = oid, oldPrefsKey = oldPrefsKey, newId = newId, adminUrlOverride = adminOverride)
            }
        } catch (e: Exception) {
            android.util.Log.w("SettingsPreferences",
                "migrateLegacyProviderIds: failed to parse provider_registry JSON: ${e.message}")
            emptyList()
        }
        if (legacy.isEmpty()) {
            prefs.edit { putBoolean(KEY_PROVIDER_ID_UNIFICATION_MIGRATED, true) }
            return 0
        }

        val oldIdToNewId: Map<String, String> = legacy.associate { it.oldId to it.newId }

        // -- eval_prefs side --
        prefs.edit {
            for (entry in legacy) {
                for (suffix in PER_PROVIDER_SUFFIXES) {
                    val oldK = "${entry.oldPrefsKey}$suffix"
                    val v = prefs.getString(oldK, null)
                    if (v != null) {
                        putString("${entry.newId}$suffix", v)
                        remove(oldK)
                    }
                }
                // Drop now-defunct keys regardless of value: legacy
                // override URLs and the orphan `_model_kinds` blob from
                // an earlier capability cache shape.
                remove("${entry.oldPrefsKey}_admin_url")
                remove("${entry.oldPrefsKey}_model_list_url")
                remove("${entry.oldPrefsKey}_model_kinds")
                // Also clear any same-named keys under the new prefix that
                // a partial earlier migration might have planted.
                remove("${entry.newId}_admin_url")
                remove("${entry.newId}_model_list_url")
                remove("${entry.newId}_model_kinds")
            }
            // providerStates: Map<String,String> keyed by id
            prefs.getString(KEY_PROVIDER_STATES, null)?.let { json ->
                runCatching {
                    val states: Map<String, String> = gson.fromJson(json, TypeTokens.mapStringStringType) ?: emptyMap()
                    val rewritten = states.mapKeys { (k, _) -> oldIdToNewId[k] ?: k }
                    putString(KEY_PROVIDER_STATES, gson.toJson(rewritten))
                }
            }
            // endpoints: Map<String, List<Endpoint>> keyed by id (string)
            prefs.getString(KEY_AI_ENDPOINTS, null)?.let { json ->
                runCatching {
                    val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
                    val rewritten = com.google.gson.JsonObject()
                    for (e in obj.entrySet()) {
                        val newKey = oldIdToNewId[e.key] ?: e.key
                        rewritten.add(newKey, e.value)
                    }
                    putString(KEY_AI_ENDPOINTS, rewritten.toString())
                }
            }
            // modelTypeOverrides: List<{providerId, modelId, ...}>
            prefs.getString(KEY_AI_MODEL_TYPE_OVERRIDES, null)?.let { json ->
                runCatching {
                    val arr = com.google.gson.JsonParser.parseString(json).asJsonArray
                    arr.forEach { el ->
                        val o = el.asJsonObject
                        o["providerId"]?.asString?.let { pid ->
                            oldIdToNewId[pid]?.let { o.addProperty("providerId", it) }
                        }
                    }
                    putString(KEY_AI_MODEL_TYPE_OVERRIDES, arr.toString())
                }
            }
            // agents: List<{... "provider": "OPENAI", ...}> — the AppService
            // adapter writes the id string. Rewrite to new id.
            prefs.getString(KEY_AI_AGENTS, null)?.let { json ->
                runCatching {
                    val arr = com.google.gson.JsonParser.parseString(json).asJsonArray
                    arr.forEach { el ->
                        val o = el.asJsonObject
                        o["provider"]?.asString?.let { pid ->
                            oldIdToNewId[pid]?.let { o.addProperty("provider", it) }
                        }
                    }
                    putString(KEY_AI_AGENTS, arr.toString())
                }
            }
            putBoolean(KEY_PROVIDER_ID_UNIFICATION_MIGRATED, true)
        }

        // -- provider_registry: rewrite with the new shape (id only) and
        // fold any captured admin-URL override into the catalog adminUrl.
        regPrefs.edit {
            runCatching {
                val arr = com.google.gson.JsonParser.parseString(regJson).asJsonArray
                arr.forEach { el ->
                    val o = el.asJsonObject
                    val oid = o["id"]?.asString ?: return@forEach
                    val newId = oldIdToNewId[oid] ?: oid
                    o.addProperty("id", newId)
                    o.remove("displayName")
                    o.remove("prefsKey")
                    val override = legacy.firstOrNull { it.oldId == oid }?.adminUrlOverride
                    if (!override.isNullOrBlank()) o.addProperty("adminUrl", override)
                }
                putString(KEY_REGISTRY_PROVIDERS_JSON, arr.toString())
                putBoolean(KEY_REGISTRY_INITIALIZED, true)
            }
        }

        // -- pricing_cache: manual_pricing map keyed by "id:model" --
        val pricingPrefs = context.getSharedPreferences(PRICING_CACHE_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        pricingPrefs.getString(KEY_PRICING_MANUAL, null)?.let { json ->
            runCatching {
                val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
                val rewritten = com.google.gson.JsonObject()
                for (e in obj.entrySet()) {
                    val k = e.key
                    val colon = k.indexOf(':')
                    val newKey = if (colon > 0) {
                        val pid = k.substring(0, colon)
                        val rest = k.substring(colon + 1)
                        val newPid = oldIdToNewId[pid] ?: pid
                        "$newPid:$rest"
                    } else k
                    rewritten.add(newKey, e.value)
                }
                pricingPrefs.edit { putString(KEY_PRICING_MANUAL, rewritten.toString()) }
            }
        }

        // -- model_lists/<oldId>.json → <newId>.json --
        if (filesDir != null) {
            val mlDir = File(filesDir, "model_lists")
            if (mlDir.isDirectory) {
                for (entry in legacy) {
                    if (entry.oldId == entry.newId) continue
                    val oldFile = File(mlDir, "${entry.oldId}.json")
                    val newFile = File(mlDir, "${entry.newId}.json")
                    if (oldFile.exists() && !newFile.exists()) {
                        runCatching { oldFile.renameTo(newFile) }
                    } else if (oldFile.exists()) {
                        runCatching { oldFile.delete() }   // both exist — keep the new one
                    }
                }
            }
        }

        android.util.Log.i("SettingsPreferences",
            "migrateLegacyProviderIds: migrated ${legacy.size} provider entry/entries to unified id shape")
        return legacy.size
    }

    fun saveModelsForProvider(
        service: AppService, models: List<String>, types: Map<String, String> = emptyMap(),
        visionModels: Set<String>? = null,
        modelCapabilities: Map<String, com.ai.data.ModelCapabilities>? = null,
        modelListRawJson: String? = null
    ) {
        prefs.edit {
            putString("${service.id}_manual_models", gson.toJson(models))
            putString("${service.id}_model_types", gson.toJson(types))
            if (visionModels != null) {
                putString("${service.id}_vision_models", if (visionModels.isEmpty()) null else gson.toJson(visionModels.toList()))
            }
            if (modelCapabilities != null) {
                putString("${service.id}_model_capabilities", if (modelCapabilities.isEmpty()) null else gson.toJson(modelCapabilities))
            }
            if (modelListRawJson != null) {
                putString("${service.id}_models_response_raw", modelListRawJson)
            }
        }
    }

    // ===== Prompt History =====

    fun loadPromptHistory(): List<PromptHistoryEntry> {
        val file = filesDir?.let { File(it, FILE_PROMPT_HISTORY) } ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try { gson.fromJson(file.readText(), TypeTokens.listPromptHistoryType) ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    fun savePromptToHistory(title: String, prompt: String) {
        val history = loadPromptHistory().toMutableList()
        history.indexOfFirst { it.title == title && it.prompt == prompt }.let { if (it >= 0) history.removeAt(it) }
        history.add(0, PromptHistoryEntry(System.currentTimeMillis(), title, prompt))
        savePromptHistoryList(history.take(MAX_PROMPT_HISTORY))
    }

    fun savePromptHistoryList(entries: List<PromptHistoryEntry>) {
        val file = filesDir?.let { File(it, FILE_PROMPT_HISTORY) } ?: return
        file.writeTextAtomic(gson.toJson(entries))
    }

    fun clearPromptHistory() {
        filesDir?.let { File(it, FILE_PROMPT_HISTORY) }?.let { if (it.exists()) it.delete() }
    }

    fun clearLastReportPrompt() { prefs.edit { remove(KEY_LAST_AI_REPORT_TITLE); remove(KEY_LAST_AI_REPORT_PROMPT) } }

    // ===== Usage Statistics =====

    fun loadUsageStats(): Map<String, UsageStats> = HashMap(ensureUsageStatsCache())

    private fun ensureUsageStatsCache(): java.util.concurrent.ConcurrentHashMap<String, UsageStats> {
        usageStatsCache?.let { return it }
        return synchronized(usageStatsLock) {
            usageStatsCache?.let { return@synchronized it }
            val file = filesDir?.let { File(it, FILE_USAGE_STATS) }
            val cache = java.util.concurrent.ConcurrentHashMap<String, UsageStats>()
            if (file == null || !file.exists()) {
                usageStatsCache = cache
                return@synchronized cache
            }
            // Parse entries individually so a single unresolvable provider id (e.g. a custom
            // provider that's been deleted) doesn't drop the whole list. Only commit the cache
            // when the JSON shape itself parsed — otherwise leave usageStatsCache null so the
            // next read retries (covers the race where ProviderRegistry is still initialising).
            val arr = try { gson.fromJson(file.readText(), com.google.gson.JsonArray::class.java) } catch (_: Exception) { null }
            if (arr == null) return@synchronized cache
            arr.forEach { el ->
                try {
                    val raw = gson.fromJson(el, UsageStats::class.java)
                    // Gson bypasses Kotlin's default-value constructors via
                    // Unsafe, so rows written before `kind` was added have
                    // a runtime-null `kind` even though the data class
                    // declares `String = "report"`. Backfill to keep the
                    // non-null contract — downstream code (the kind pill
                    // on UsageModelRow) assumes a non-null String and
                    // would NPE on the missing rows when the provider is
                    // expanded.
                    @Suppress("USELESS_CAST")
                    val stat = if ((raw.kind as String?) == null) raw.copy(kind = "report") else raw
                    cache[stat.key] = stat
                } catch (_: Exception) { /* skip rows that reference an unknown provider id */ }
            }
            // If the file had rows but every single one failed to deserialise — most likely
            // ProviderRegistry hasn't initialised yet so AppServiceAdapter throws on every
            // provider id — refuse to commit the empty cache and let the next call retry.
            if (cache.isEmpty() && arr.size() > 0) return@synchronized cache
            usageStatsCache = cache
            cache
        }
    }

    fun saveUsageStats(stats: Map<String, UsageStats>) {
        val file = filesDir?.let { File(it, FILE_USAGE_STATS) } ?: return
        file.writeTextAtomic(gson.toJson(stats.values.toList()))
    }

    /**
     * updateUsageStats used to hold a lock, re-read the whole JSON file, mutate, and re-write
     * on every API call. Under concurrent report generation that serialized every worker and
     * allocated a new Map-of-all-stats per token-usage event. Now we keep stats in an in-memory
     * ConcurrentHashMap and debounce disk writes to once per USAGE_STATS_FLUSH_MS window.
     */
    fun updateUsageStats(provider: AppService, model: String, inputTokens: Int, outputTokens: Int, totalTokens: Int = inputTokens + outputTokens, kind: String = "report", searchUnits: Int = 0) {
        val stats = ensureUsageStatsCache()
        val key = "${provider.id}::$model::$kind"
        stats.compute(key) { _, existing ->
            val base = existing ?: UsageStats(provider, model, kind = kind)
            base.copy(
                callCount = base.callCount + 1,
                inputTokens = base.inputTokens + inputTokens,
                outputTokens = base.outputTokens + outputTokens,
                searchUnits = base.searchUnits + searchUnits
            )
        }
        scheduleUsageStatsFlush()
    }

    private fun scheduleUsageStatsFlush() {
        val now = System.currentTimeMillis()
        val last = lastUsageStatsFlush
        if (now - last < USAGE_STATS_FLUSH_MS) return
        synchronized(usageStatsLock) {
            if (System.currentTimeMillis() - lastUsageStatsFlush < USAGE_STATS_FLUSH_MS) return
            lastUsageStatsFlush = System.currentTimeMillis()
            val snapshot = usageStatsCache?.let { HashMap(it) } ?: return
            saveUsageStats(snapshot)
        }
    }

    fun flushUsageStats() {
        synchronized(usageStatsLock) {
            val snapshot = usageStatsCache?.let { HashMap(it) } ?: return
            lastUsageStatsFlush = System.currentTimeMillis()
            saveUsageStats(snapshot)
        }
    }

    suspend fun updateUsageStatsAsync(provider: AppService, model: String, inputTokens: Int, outputTokens: Int, totalTokens: Int = inputTokens + outputTokens, kind: String = "report", searchUnits: Int = 0) =
        withContext(Dispatchers.IO) { updateUsageStats(provider, model, inputTokens, outputTokens, totalTokens, kind, searchUnits) }

    fun clearUsageStats() {
        usageStatsCache?.clear()
        // Reset the flush timestamp so the next updateUsageStats
        // doesn't skip the disk flush against a 2-second debounce
        // window inherited from a recent pre-clear write — that
        // window made the post-clear cache hold writes invisible
        // on disk for the rest of the debounce period.
        lastUsageStatsFlush = 0L
        filesDir?.let { File(it, FILE_USAGE_STATS) }?.let { if (it.exists()) it.delete() }
    }

    // ===== Model Lists Cache =====

    fun isModelListCacheValid(provider: AppService): Boolean {
        val ts = prefs.getLong(KEY_MODEL_LIST_TIMESTAMP_PREFIX + provider.id, 0L)
        return System.currentTimeMillis() - ts < MODEL_LISTS_CACHE_DURATION_MS
    }

    fun updateModelListTimestamps(providers: List<AppService>) {
        val now = System.currentTimeMillis()
        prefs.edit { providers.forEach { putLong(KEY_MODEL_LIST_TIMESTAMP_PREFIX + it.id, now) } }
    }

    // ===== Private helpers =====

    private fun loadJsonList(key: String): List<String>? {
        val json = prefs.getString(key, null) ?: return null
        return try { gson.fromJson(json, TypeTokens.listStringType) } catch (_: Exception) { null }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> loadList(key: String, type: Type, transform: ((Any?) -> List<T>)? = null): List<T> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val raw = gson.fromJson<Any>(json, type)
            if (transform != null) transform(raw) else (raw as? List<T>) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun loadMap(key: String): Map<String, String> {
        val json = prefs.getString(key, null) ?: return emptyMap()
        return try { gson.fromJson(json, TypeTokens.mapStringStringType) ?: emptyMap() } catch (_: Exception) { emptyMap() }
    }

    private fun loadEndpoints(): Map<AppService, List<Endpoint>> {
        val json = prefs.getString(KEY_AI_ENDPOINTS, null) ?: return emptyMap()
        return try {
            val rawMap: Map<String, List<Endpoint>>? = gson.fromJson(json, TypeTokens.mapEndpointsType)
            rawMap?.mapKeys { AppService.findById(it.key) }?.entries?.mapNotNull { (k, v) -> k?.let { it to v } }?.toMap() ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    companion object {
        private val usageStatsLock = Any()
        @Volatile private var usageStatsCache: java.util.concurrent.ConcurrentHashMap<String, UsageStats>? = null
        @Volatile private var lastUsageStatsFlush: Long = 0L
        private const val USAGE_STATS_FLUSH_MS = 2_000L
        const val PREFS_NAME = "eval_prefs"
        // Sibling SharedPreferences files the id-unification migration
        // touches alongside the main eval_prefs (see migrateLegacyProviderIds).
        private const val PROVIDER_REGISTRY_PREFS_NAME = "provider_registry"
        private const val PRICING_CACHE_PREFS_NAME = "pricing_cache"
        private const val KEY_REGISTRY_PROVIDERS_JSON = "providers_json"
        private const val KEY_REGISTRY_INITIALIZED = "initialized"
        private const val KEY_PRICING_MANUAL = "manual_pricing"
        /** Marker pref. Set true once [migrateLegacyProviderIds]
         *  successfully completes — subsequent invocations bail out. */
        private const val KEY_PROVIDER_ID_UNIFICATION_MIGRATED = "ai_provider_id_unification_migrated"
        /** Sweeper marker — runs once after the v2 cleanup landed
         *  to drop orphan `*_model_kinds` keys from a previous
         *  capability-cache shape. Independent of the main marker
         *  so installs already through the first migration still
         *  get the cleanup pass. */
        private const val KEY_MODEL_KINDS_SWEEP_DONE = "ai_model_kinds_sweep_done"
        /** Suffixes used by per-provider keys in eval_prefs. The id
         *  migration walks these when renaming "${oldPrefsKey}_<suffix>"
         *  to "${newId}_<suffix>". Keep in sync with loadSettings /
         *  saveSettings / saveModelsForProvider. */
        private val PER_PROVIDER_SUFFIXES = listOf(
            "_api_key", "_model", "_model_source",
            "_manual_models", "_model_types",
            "_vision_models", "_web_search_models", "_reasoning_models",
            "_vision_capable_computed", "_web_search_capable_computed", "_reasoning_capable_computed",
            "_model_pricing", "_model_capabilities", "_models_response_raw",
            "_parameters_id"
        )

        private const val KEY_USER_NAME = "user_name"
        private const val KEY_HUGGINGFACE_API_KEY = "huggingface_api_key"
        private const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
        private const val KEY_AA_API_KEY = "artificial_analysis_api_key"
        private const val KEY_DEFAULT_EMAIL = "default_email"
        private const val KEY_DEFAULT_TYPE_PATHS = "default_type_paths"
        private const val KEY_TRACING_ENABLED = "tracing_enabled"
        private const val KEY_MODEL_NAME_LAYOUT = "model_name_layout"
        private const val KEY_SHOW_BACK_BUTTON = "show_back_button"
        private const val KEY_AI_AGENTS = "ai_agents"
        private const val KEY_AI_FLOCKS = "ai_flocks"
        private const val KEY_AI_SWARMS = "ai_swarms"
        private const val KEY_AI_PARAMETERS = "ai_parameters"
        private const val KEY_AI_SYSTEM_PROMPTS = "ai_system_prompts"
        // Persisted under the legacy "ai_meta_prompts" key so users
        // who already have seeded entries from the previous build don't
        // lose them across the rename to InternalPrompt.
        private const val KEY_AI_INTERNAL_PROMPTS = "ai_meta_prompts"
        private const val KEY_AI_ENDPOINTS = "ai_endpoints"
        private const val KEY_PROVIDER_STATES = "provider_states"
        private const val KEY_AI_MODEL_TYPE_OVERRIDES = "ai_model_type_overrides"

        const val MAX_PROMPT_HISTORY = 100
        const val KEY_LAST_AI_REPORT_TITLE = "last_ai_report_title"
        const val KEY_LAST_AI_REPORT_PROMPT = "last_ai_report_prompt"
        private const val FILE_USAGE_STATS = "usage-stats.json"
        private const val FILE_PROMPT_HISTORY = "prompt-history.json"
        private const val KEY_MODEL_LIST_TIMESTAMP_PREFIX = "model_list_timestamp_"
        private const val MODEL_LISTS_CACHE_DURATION_MS = 24 * 60 * 60 * 1000L
    }
}
