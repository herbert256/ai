package com.ai.ui.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import com.ai.data.AppService
import com.ai.data.createAppGson
import com.ai.data.writeTextAtomic
import com.ai.model.*
import com.ai.viewmodel.GeneralSettings
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
        return GeneralSettings(
            userName = prefs.getString(KEY_USER_NAME, "user") ?: "user",
            huggingFaceApiKey = prefs.getString(KEY_HUGGINGFACE_API_KEY, "") ?: "",
            openRouterApiKey = prefs.getString(KEY_OPENROUTER_API_KEY, "") ?: "",
            artificialAnalysisApiKey = prefs.getString(KEY_AA_API_KEY, "") ?: "",
            defaultEmail = prefs.getString(KEY_DEFAULT_EMAIL, "") ?: "",
            defaultTypePaths = defaultTypePaths
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
            // with those properties as runtime null. Patch them up to
            // "meta" / "*select" — the legacy data was always
            // meta-eligible, never bound to a specific agent.
            internalPrompts = loadList<InternalPrompt>(KEY_AI_INTERNAL_PROMPTS, TypeTokens.listInternalPromptType).map { ip ->
                @Suppress("USELESS_CAST")
                val cat = (ip.category as String?) ?: "meta"
                @Suppress("USELESS_CAST")
                val ag = (ip.agent as String?) ?: "*select"
                if (cat == ip.category && ag == ip.agent) ip else ip.copy(category = cat, agent = ag)
            },
            endpoints = loadEndpoints(),
            providerStates = loadMap(KEY_PROVIDER_STATES),
            modelTypeOverrides = loadList(KEY_AI_MODEL_TYPE_OVERRIDES, TypeTokens.listModelTypeOverrideType)
        )
    }

    private fun loadSettings(): Settings {
        val providers = AppService.entries.associateWith { service ->
            val key = service.prefsKey
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
                adminUrl = prefs.getString("${key}_admin_url", service.adminUrl) ?: service.adminUrl,
                modelListUrl = prefs.getString("${key}_model_list_url", "") ?: "",
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
                val key = service.prefsKey
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
                putString("${key}_admin_url", config.adminUrl)
                putString("${key}_model_list_url", config.modelListUrl)
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

    fun saveModelsForProvider(
        service: AppService, models: List<String>, types: Map<String, String> = emptyMap(),
        visionModels: Set<String>? = null,
        modelCapabilities: Map<String, com.ai.data.ModelCapabilities>? = null,
        modelListRawJson: String? = null
    ) {
        prefs.edit {
            putString("${service.prefsKey}_manual_models", gson.toJson(models))
            putString("${service.prefsKey}_model_types", gson.toJson(types))
            if (visionModels != null) {
                putString("${service.prefsKey}_vision_models", if (visionModels.isEmpty()) null else gson.toJson(visionModels.toList()))
            }
            if (modelCapabilities != null) {
                putString("${service.prefsKey}_model_capabilities", if (modelCapabilities.isEmpty()) null else gson.toJson(modelCapabilities))
            }
            if (modelListRawJson != null) {
                putString("${service.prefsKey}_models_response_raw", modelListRawJson)
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

        private const val KEY_USER_NAME = "user_name"
        private const val KEY_HUGGINGFACE_API_KEY = "huggingface_api_key"
        private const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
        private const val KEY_AA_API_KEY = "artificial_analysis_api_key"
        private const val KEY_DEFAULT_EMAIL = "default_email"
        private const val KEY_DEFAULT_TYPE_PATHS = "default_type_paths"
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
