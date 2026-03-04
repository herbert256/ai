package com.ai.ui.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import com.ai.data.AppService
import com.ai.data.createAppGson
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
        val listPromptType: Type = object : TypeToken<List<Prompt>>() {}.type
        val mapEndpointsType: Type = object : TypeToken<Map<String, List<Endpoint>>>() {}.type
        val mapStringStringType: Type = object : TypeToken<Map<String, String>>() {}.type
        val listPromptHistoryType: Type = object : TypeToken<List<PromptHistoryEntry>>() {}.type
        val listUsageStatsType: Type = object : TypeToken<List<UsageStats>>() {}.type
    }

    // ===== General Settings =====

    fun loadGeneralSettings(): GeneralSettings {
        return GeneralSettings(
            userName = prefs.getString(KEY_USER_NAME, "user") ?: "user",
            developerMode = prefs.getBoolean(KEY_DEVELOPER_MODE, true),
            huggingFaceApiKey = prefs.getString(KEY_HUGGINGFACE_API_KEY, "") ?: "",
            openRouterApiKey = prefs.getString(KEY_OPENROUTER_API_KEY, "") ?: "",
            defaultEmail = prefs.getString(KEY_DEFAULT_EMAIL, "") ?: "",
        )
    }

    fun saveGeneralSettings(settings: GeneralSettings) {
        prefs.edit {
            putString(KEY_USER_NAME, settings.userName.ifBlank { "user" })
            putBoolean(KEY_DEVELOPER_MODE, settings.developerMode)
            putString(KEY_HUGGINGFACE_API_KEY, settings.huggingFaceApiKey)
            putString(KEY_OPENROUTER_API_KEY, settings.openRouterApiKey)
            putString(KEY_DEFAULT_EMAIL, settings.defaultEmail)
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
            prompts = loadList(KEY_AI_PROMPTS, TypeTokens.listPromptType),
            endpoints = loadEndpoints(),
            providerStates = loadMap(KEY_PROVIDER_STATES)
        )
    }

    private fun loadSettings(): Settings {
        val providers = AppService.entries.associateWith { service ->
            val key = service.prefsKey
            val defaults = defaultProviderConfig(service)
            val modelSource = prefs.getString("${key}_model_source", null)?.let {
                try { ModelSource.valueOf(it) } catch (_: Exception) { null }
            } ?: defaults.modelSource
            val models = if (defaults.models.isNotEmpty())
                loadJsonList("${key}_manual_models") ?: defaults.models
            else
                loadJsonList("${key}_manual_models") ?: emptyList()

            ProviderConfig(
                apiKey = prefs.getString("${key}_api_key", "") ?: "",
                model = prefs.getString("${key}_model", service.defaultModel) ?: service.defaultModel,
                modelSource = modelSource, models = models,
                adminUrl = prefs.getString("${key}_admin_url", service.adminUrl) ?: service.adminUrl,
                modelListUrl = prefs.getString("${key}_model_list_url", "") ?: "",
                parametersIds = loadJsonList("${key}_parameters_id") ?: emptyList()
            )
        }
        return Settings(providers = providers)
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
                putString("${key}_admin_url", config.adminUrl)
                putString("${key}_model_list_url", config.modelListUrl)
                putString("${key}_parameters_id", if (config.parametersIds.isEmpty()) null else gson.toJson(config.parametersIds))
            }
            putString(KEY_AI_AGENTS, gson.toJson(settings.agents))
            putString(KEY_AI_FLOCKS, gson.toJson(settings.flocks))
            putString(KEY_AI_SWARMS, gson.toJson(settings.swarms))
            putString(KEY_AI_PARAMETERS, gson.toJson(settings.parameters))
            putString(KEY_AI_SYSTEM_PROMPTS, gson.toJson(settings.systemPrompts))
            putString(KEY_AI_PROMPTS, gson.toJson(settings.prompts))
            putString(KEY_AI_ENDPOINTS, gson.toJson(settings.endpoints.mapKeys { it.key.id }))
            putString(KEY_PROVIDER_STATES, gson.toJson(settings.providerStates))
        }
    }

    fun saveModelsForProvider(service: AppService, models: List<String>) {
        prefs.edit { putString("${service.prefsKey}_manual_models", gson.toJson(models)) }
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
        try { file.writeText(gson.toJson(entries)) } catch (_: Exception) {}
    }

    fun clearPromptHistory() {
        filesDir?.let { File(it, FILE_PROMPT_HISTORY) }?.let { if (it.exists()) it.delete() }
    }

    fun clearLastReportPrompt() { prefs.edit { remove(KEY_LAST_AI_REPORT_TITLE); remove(KEY_LAST_AI_REPORT_PROMPT) } }

    // ===== Usage Statistics =====

    fun loadUsageStats(): Map<String, UsageStats> {
        val file = filesDir?.let { File(it, FILE_USAGE_STATS) } ?: return emptyMap()
        if (!file.exists()) return emptyMap()
        return try {
            val list: List<UsageStats>? = gson.fromJson(file.readText(), TypeTokens.listUsageStatsType)
            list?.associateBy { it.key } ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    fun saveUsageStats(stats: Map<String, UsageStats>) {
        val file = filesDir?.let { File(it, FILE_USAGE_STATS) } ?: return
        try { file.writeText(gson.toJson(stats.values.toList())) } catch (_: Exception) {}
    }

    fun updateUsageStats(provider: AppService, model: String, inputTokens: Int, outputTokens: Int, totalTokens: Int = inputTokens + outputTokens) {
        synchronized(usageStatsLock) {
            val stats = loadUsageStats().toMutableMap()
            val key = "${provider.id}::$model"
            val existing = stats[key] ?: UsageStats(provider, model)
            stats[key] = existing.copy(callCount = existing.callCount + 1, inputTokens = existing.inputTokens + inputTokens, outputTokens = existing.outputTokens + outputTokens)
            saveUsageStats(stats)
        }
    }

    suspend fun updateUsageStatsAsync(provider: AppService, model: String, inputTokens: Int, outputTokens: Int, totalTokens: Int = inputTokens + outputTokens) =
        withContext(Dispatchers.IO) { updateUsageStats(provider, model, inputTokens, outputTokens, totalTokens) }

    fun clearUsageStats() {
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
        const val PREFS_NAME = "eval_prefs"

        private const val KEY_USER_NAME = "user_name"
        private const val KEY_DEVELOPER_MODE = "developer_mode"
        private const val KEY_HUGGINGFACE_API_KEY = "huggingface_api_key"
        private const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
        private const val KEY_DEFAULT_EMAIL = "default_email"
        private const val KEY_AI_AGENTS = "ai_agents"
        private const val KEY_AI_FLOCKS = "ai_flocks"
        private const val KEY_AI_SWARMS = "ai_swarms"
        private const val KEY_AI_PARAMETERS = "ai_parameters"
        private const val KEY_AI_SYSTEM_PROMPTS = "ai_system_prompts"
        private const val KEY_AI_PROMPTS = "ai_prompts"
        private const val KEY_AI_ENDPOINTS = "ai_endpoints"
        private const val KEY_PROVIDER_STATES = "provider_states"

        const val MAX_PROMPT_HISTORY = 100
        const val KEY_LAST_AI_REPORT_TITLE = "last_ai_report_title"
        const val KEY_LAST_AI_REPORT_PROMPT = "last_ai_report_prompt"
        private const val FILE_USAGE_STATS = "usage-stats.json"
        private const val FILE_PROMPT_HISTORY = "prompt-history.json"
        private const val KEY_MODEL_LIST_TIMESTAMP_PREFIX = "model_list_timestamp_"
        private const val MODEL_LISTS_CACHE_DURATION_MS = 24 * 60 * 60 * 1000L
    }
}
