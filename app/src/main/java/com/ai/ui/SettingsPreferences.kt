package com.ai.ui

import android.content.SharedPreferences
import androidx.core.content.edit
import com.ai.data.AiService
import com.ai.data.createAiGson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.lang.reflect.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Helper class for managing all settings persistence via SharedPreferences.
 * Transactional data (usage stats, prompt history) is stored as files in filesDir.
 */
class SettingsPreferences(private val prefs: SharedPreferences, private val filesDir: File? = null) {

    private val gson = createAiGson()

    // Cached TypeToken types to avoid repeated anonymous class allocations
    private object TypeTokens {
        val listStringType: Type = object : TypeToken<List<String>>() {}.type
        val listAgentType: Type = object : TypeToken<List<AiAgent>>() {}.type
        val listFlockType: Type = object : TypeToken<List<AiFlock>>() {}.type
        val listSwarmType: Type = object : TypeToken<List<AiSwarm>>() {}.type
        val listParametersType: Type = object : TypeToken<List<AiParameters>>() {}.type
        val listSystemPromptType: Type = object : TypeToken<List<AiSystemPrompt>>() {}.type
        val listPromptType: Type = object : TypeToken<List<AiPrompt>>() {}.type
        val mapEndpointsType: Type = object : TypeToken<Map<String, List<AiEndpoint>>>() {}.type
        val mapStringStringType: Type = object : TypeToken<Map<String, String>>() {}.type
        val listPromptHistoryType: Type = object : TypeToken<List<PromptHistoryEntry>>() {}.type
        val listUsageStatsType: Type = object : TypeToken<List<AiUsageStats>>() {}.type
    }

    // ============================================================================
    // General Settings
    // ============================================================================

    fun loadGeneralSettings(): GeneralSettings {
        return GeneralSettings(
            userName = prefs.getString(KEY_USER_NAME, "user") ?: "user",
            developerMode = true,
            huggingFaceApiKey = prefs.getString(KEY_HUGGINGFACE_API_KEY, "") ?: "",
            openRouterApiKey = prefs.getString(KEY_OPENROUTER_API_KEY, "") ?: "",
            fullScreenMode = prefs.getBoolean(KEY_FULL_SCREEN_MODE, false),
            defaultEmail = prefs.getString(KEY_DEFAULT_EMAIL, "") ?: "",
            popupModelSelection = prefs.getBoolean(KEY_POPUP_MODEL_SELECTION, true)
        )
    }

    fun saveGeneralSettings(settings: GeneralSettings) {
        prefs.edit {
            putString(KEY_USER_NAME, settings.userName.ifBlank { "user" })
            putString(KEY_HUGGINGFACE_API_KEY, settings.huggingFaceApiKey)
            putString(KEY_OPENROUTER_API_KEY, settings.openRouterApiKey)
            putBoolean(KEY_FULL_SCREEN_MODE, settings.fullScreenMode)
            putString(KEY_DEFAULT_EMAIL, settings.defaultEmail)
            putBoolean(KEY_POPUP_MODEL_SELECTION, settings.popupModelSelection)
        }
    }

    // ============================================================================
    // AI Settings
    // ============================================================================

    /**
     * Load AI settings with agents, flocks, and prompts.
     * Also runs migrations for settings changes.
     */
    fun loadAiSettingsWithMigration(): AiSettings {
        val baseSettings = loadAiSettings()
        val agents = loadAgents()
        val flocks = loadFlocks()
        val swarms = loadSwarms()
        val parameters = loadParameters()
        val systemPrompts = loadSystemPrompts()
        val prompts = loadPrompts()
        val endpoints = loadEndpoints()
        val providerStates = loadProviderStates()
        return baseSettings.copy(agents = agents, flocks = flocks, swarms = swarms, parameters = parameters, systemPrompts = systemPrompts, prompts = prompts, endpoints = endpoints, providerStates = providerStates)
    }

    fun loadAiSettings(): AiSettings {
        val providers = AiService.entries.associateWith { service ->
            val key = service.prefsKey
            val defaults = defaultProviderConfig(service)
            val modelSource = loadModelSource("${key}_model_source", defaults.modelSource)
            val models = if (defaults.models.isNotEmpty())
                loadManualModelsWithDefault("${key}_manual_models", defaults.models)
            else
                loadManualModels("${key}_manual_models")
            ProviderConfig(
                apiKey = prefs.getString("${key}_api_key", "") ?: "",
                model = prefs.getString("${key}_model", service.defaultModel) ?: service.defaultModel,
                modelSource = modelSource,
                models = models,
                adminUrl = prefs.getString("${key}_admin_url", service.adminUrl) ?: service.adminUrl,
                modelListUrl = prefs.getString("${key}_model_list_url", "") ?: "",
                parametersIds = loadParametersIds("${key}_parameters_id")
            )
        }
        return AiSettings(providers = providers)
    }

    private fun loadModelSource(key: String, default: ModelSource): ModelSource {
        val sourceName = prefs.getString(key, null) ?: return default
        return try {
            ModelSource.valueOf(sourceName)
        } catch (e: IllegalArgumentException) {
            default
        }
    }

    private fun loadManualModels(key: String): List<String> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            gson.fromJson(json, TypeTokens.listStringType) ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.w("SettingsPreferences", "Failed to load manual models for $key: ${e.message}")
            emptyList()
        }
    }

    private fun loadManualModelsWithDefault(key: String, default: List<String>): List<String> {
        val json = prefs.getString(key, null) ?: return default
        return try {
            gson.fromJson(json, TypeTokens.listStringType) ?: default
        } catch (e: Exception) {
            android.util.Log.w("SettingsPreferences", "Failed to load manual models with default for $key: ${e.message}")
            default
        }
    }

    private fun loadParametersIds(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            gson.fromJson<List<String>>(raw, TypeTokens.listStringType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Save parameter IDs list to SharedPreferences as JSON.
     * Returns null if the list is empty (to clear the preference).
     */
    private fun saveParametersIds(ids: List<String>): String? {
        if (ids.isEmpty()) return null
        return gson.toJson(ids)
    }

    fun saveAiSettings(settings: AiSettings) {
        prefs.edit {
            for (service in AiService.entries) {
                val key = service.prefsKey
                val config = settings.providers[service] ?: defaultProviderConfig(service)
                putString("${key}_api_key", config.apiKey)
                putString("${key}_model", config.model)
                putString("${key}_model_source", config.modelSource.name)
                putString("${key}_manual_models", gson.toJson(config.models))
                putString("${key}_admin_url", config.adminUrl)
                putString("${key}_model_list_url", config.modelListUrl)
                putString("${key}_parameters_id", saveParametersIds(config.parametersIds))
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

    /**
     * Save models for a single provider (used during API fetch to avoid saving entire AiSettings).
     */
    fun saveModelsForProvider(service: AiService, models: List<String>) {
        val key = service.prefsKey
        prefs.edit {
            putString("${key}_manual_models", gson.toJson(models))
        }
    }

    // ============================================================================
    // AI Agents
    // ============================================================================

    private fun loadAgents(): List<AiAgent> {
        val json = prefs.getString(KEY_AI_AGENTS, null) ?: return emptyList()
        return try {
            val list: List<AiAgent>? = gson.fromJson(json, TypeTokens.listAgentType)
            // Filter out agents with unknown providers; coalesce Gson null defaults
            list?.filter { AiService.findById(it.provider.id) != null } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ============================================================================
    // AI Flocks
    // ============================================================================

    private fun loadFlocks(): List<AiFlock> {
        val json = prefs.getString(KEY_AI_FLOCKS, null) ?: return emptyList()
        return try {
            val list: List<AiFlock>? = gson.fromJson(json, TypeTokens.listFlockType)
            list ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ============================================================================
    // AI Swarms
    // ============================================================================

    private fun loadSwarms(): List<AiSwarm> {
        val json = prefs.getString(KEY_AI_SWARMS, null) ?: return emptyList()
        return try {
            val list: List<AiSwarm>? = gson.fromJson(json, TypeTokens.listSwarmType)
            list ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ============================================================================
    // AI Parameters
    // ============================================================================

    private fun loadParameters(): List<AiParameters> {
        val json = prefs.getString(KEY_AI_PARAMETERS, null) ?: return emptyList()
        return try {
            val list: List<AiParameters>? = gson.fromJson(json, TypeTokens.listParametersType)
            list?.map { params ->
                params.copy(
                    stopSequences = params.stopSequences
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ============================================================================
    // AI System Prompts
    // ============================================================================

    private fun loadSystemPrompts(): List<AiSystemPrompt> {
        val json = prefs.getString(KEY_AI_SYSTEM_PROMPTS, null) ?: return emptyList()
        return try {
            gson.fromJson(json, TypeTokens.listSystemPromptType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ============================================================================
    // AI Prompts
    // ============================================================================

    private fun loadPrompts(): List<AiPrompt> {
        val json = prefs.getString(KEY_AI_PROMPTS, null) ?: return emptyList()
        return try {
            gson.fromJson(json, TypeTokens.listPromptType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ============================================================================
    // AI Endpoints
    // ============================================================================

    private fun loadEndpoints(): Map<AiService, List<AiEndpoint>> {
        val json = prefs.getString(KEY_AI_ENDPOINTS, null) ?: return emptyMap()
        return try {
            // Stored as Map<String, List<AiEndpoint>> where key is provider name
            val rawMap: Map<String, List<AiEndpoint>>? = gson.fromJson(json, TypeTokens.mapEndpointsType)
            rawMap?.mapKeys { entry -> AiService.findById(entry.key) }
                ?.entries?.mapNotNull { (k, v) -> k?.let { it to v } }?.toMap() ?: emptyMap()
        } catch (e: Exception) {
            android.util.Log.w("SettingsPreferences", "Failed to load endpoints: ${e.message}")
            emptyMap()
        }
    }

    // ============================================================================
    // Provider States
    // ============================================================================

    private fun loadProviderStates(): Map<String, String> {
        val json = prefs.getString(KEY_PROVIDER_STATES, null) ?: return emptyMap()
        return try {
            gson.fromJson(json, TypeTokens.mapStringStringType) ?: emptyMap()
        } catch (e: Exception) {
            android.util.Log.w("SettingsPreferences", "Failed to load provider states: ${e.message}")
            emptyMap()
        }
    }

    private fun saveEndpoints(endpoints: Map<AiService, List<AiEndpoint>>) {
        // Convert to Map<String, List<AiEndpoint>> for storage
        val rawMap = endpoints.mapKeys { it.key.id }
        prefs.edit { putString(KEY_AI_ENDPOINTS, gson.toJson(rawMap)) }
    }

    // ============================================================================
    // Prompt History (for New AI Report)
    // ============================================================================

    fun loadPromptHistory(): List<PromptHistoryEntry> {
        val file = promptHistoryFile() ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText()
            gson.fromJson(json, TypeTokens.listPromptHistoryType) ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.w("SettingsPreferences", "Failed to load prompt history: ${e.message}")
            emptyList()
        }
    }

    fun savePromptToHistory(title: String, prompt: String) {
        val history = loadPromptHistory().toMutableList()

        // Check if there's already an entry with the same title and prompt
        val existingIndex = history.indexOfFirst { it.title == title && it.prompt == prompt }

        if (existingIndex >= 0) {
            // Remove existing entry (will be re-added at the beginning with new timestamp)
            history.removeAt(existingIndex)
        }

        // Add at the beginning with current timestamp
        history.add(0, PromptHistoryEntry(
            timestamp = System.currentTimeMillis(),
            title = title,
            prompt = prompt
        ))
        // Keep only the last MAX_PROMPT_HISTORY entries
        val trimmed = history.take(MAX_PROMPT_HISTORY)
        savePromptHistoryList(trimmed)
    }

    fun savePromptHistoryList(entries: List<PromptHistoryEntry>) {
        val file = promptHistoryFile() ?: return
        try {
            file.writeText(gson.toJson(entries))
        } catch (e: Exception) {
            android.util.Log.e("SettingsPreferences", "Failed to save prompt history: ${e.message}")
        }
    }

    fun clearPromptHistory() {
        val file = promptHistoryFile()
        if (file != null && file.exists()) file.delete()
    }

    private fun promptHistoryFile(): File? {
        return filesDir?.let { File(it, FILE_PROMPT_HISTORY) }
    }

    fun clearLastAiReportPrompt() {
        prefs.edit {
            remove(KEY_LAST_AI_REPORT_TITLE)
            remove(KEY_LAST_AI_REPORT_PROMPT)
        }
    }

    fun clearSelectedReportIds() {
        prefs.edit {
            remove(KEY_SELECTED_SWARM_IDS)
            remove(KEY_SELECTED_FLOCK_IDS)
        }
    }

    companion object {
        /** File-level lock for usage stats to prevent lost updates across instances. */
        private val usageStatsLock = Any()

        const val PREFS_NAME = "eval_prefs"

        // General settings
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_DEVELOPER_MODE = "developer_mode"
        private const val KEY_TRACK_API_CALLS = "track_api_calls"
        private const val KEY_HUGGINGFACE_API_KEY = "huggingface_api_key"
        private const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
        private const val KEY_FULL_SCREEN_MODE = "full_screen_mode"
        private const val KEY_DEFAULT_EMAIL = "default_email"
        private const val KEY_POPUP_MODEL_SELECTION = "popup_model_selection"

        // Per-provider settings keys are now computed from AiService.prefsKey
        // e.g., "${service.prefsKey}_api_key", "${service.prefsKey}_model", etc.
        // AI agents
        private const val KEY_AI_AGENTS = "ai_agents"

        // AI flocks
        private const val KEY_AI_FLOCKS = "ai_flocks_v2"

        // AI swarms
        private const val KEY_AI_SWARMS = "ai_swarms_v2"

        // AI parameters (reusable parameter presets)
        private const val KEY_AI_PARAMETERS = "ai_parameters"

        // AI system prompts (reusable system prompts for agents/flocks/swarms)
        private const val KEY_AI_SYSTEM_PROMPTS = "ai_system_prompts"

        // AI prompts (internal app prompts)
        private const val KEY_AI_PROMPTS = "ai_prompts"

        // AI endpoints per provider
        private const val KEY_AI_ENDPOINTS = "ai_endpoints"

        // Provider states (ok/error/not-used)
        private const val KEY_PROVIDER_STATES = "provider_states"

        const val MAX_PROMPT_HISTORY = 100

        // Last AI report title and prompt (for New AI Report screen)
        const val KEY_LAST_AI_REPORT_TITLE = "last_ai_report_title"
        const val KEY_LAST_AI_REPORT_PROMPT = "last_ai_report_prompt"

        // Selected flock IDs for report generation
        private const val KEY_SELECTED_FLOCK_IDS = "selected_flock_ids_v2"

        // Selected swarm IDs for report generation
        private const val KEY_SELECTED_SWARM_IDS = "selected_swarm_ids_v2"

        // File-based storage filenames
        private const val FILE_USAGE_STATS = "usage-stats.json"
        private const val FILE_PROMPT_HISTORY = "prompt-history.json"

        // Model lists cache timestamps (24-hour cache per provider)
        private const val KEY_MODEL_LIST_TIMESTAMP_PREFIX = "model_list_timestamp_"
        private const val MODEL_LISTS_CACHE_DURATION_MS = 24 * 60 * 60 * 1000L  // 24 hours
    }

    // ============================================================================
    // Selected Flock IDs (for report generation)
    // ============================================================================

    fun loadSelectedFlockIds(): Set<String> {
        val json = prefs.getString(KEY_SELECTED_FLOCK_IDS, null) ?: return emptySet()
        return try {
            val list: List<String>? = gson.fromJson(json, TypeTokens.listStringType)
            list?.toSet() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun saveSelectedFlockIds(flockIds: Set<String>) {
        val json = gson.toJson(flockIds.toList())
        prefs.edit { putString(KEY_SELECTED_FLOCK_IDS, json) }
    }

    // ============================================================================
    // Selected Swarm IDs (for report generation)
    // ============================================================================

    fun loadSelectedSwarmIds(): Set<String> {
        val json = prefs.getString(KEY_SELECTED_SWARM_IDS, null) ?: return emptySet()
        return try {
            val list: List<String>? = gson.fromJson(json, TypeTokens.listStringType)
            list?.toSet() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun saveSelectedSwarmIds(swarmIds: Set<String>) {
        val json = gson.toJson(swarmIds.toList())
        prefs.edit { putString(KEY_SELECTED_SWARM_IDS, json) }
    }

    // ============================================================================
    // AI Usage Statistics
    // ============================================================================

    fun loadUsageStats(): Map<String, AiUsageStats> {
        val file = usageStatsFile() ?: return emptyMap()
        if (!file.exists()) return emptyMap()
        return try {
            val json = file.readText()
            val list: List<AiUsageStats>? = gson.fromJson(json, TypeTokens.listUsageStatsType)
            list?.associateBy { it.key } ?: emptyMap()
        } catch (e: Exception) {
            android.util.Log.e("SettingsPreferences", "Failed to load usage stats: ${e.message}")
            emptyMap()
        }
    }

    fun saveUsageStats(stats: Map<String, AiUsageStats>) {
        val file = usageStatsFile() ?: return
        try {
            file.writeText(gson.toJson(stats.values.toList()))
        } catch (e: Exception) {
            android.util.Log.e("SettingsPreferences", "Failed to save usage stats: ${e.message}")
        }
    }

    fun updateUsageStats(
        provider: com.ai.data.AiService,
        model: String,
        inputTokens: Int,
        outputTokens: Int,
        totalTokens: Int = inputTokens + outputTokens
    ) {
        synchronized(usageStatsLock) {
            val stats = loadUsageStats().toMutableMap()
            val key = "${provider.id}::$model"
            val existing = stats[key] ?: AiUsageStats(provider, model)
            stats[key] = existing.copy(
                callCount = existing.callCount + 1,
                inputTokens = existing.inputTokens + inputTokens,
                outputTokens = existing.outputTokens + outputTokens
            )
            saveUsageStats(stats)
        }
    }

    suspend fun updateUsageStatsAsync(
        provider: com.ai.data.AiService,
        model: String,
        inputTokens: Int,
        outputTokens: Int,
        totalTokens: Int = inputTokens + outputTokens
    ) = withContext(Dispatchers.IO) {
        updateUsageStats(provider, model, inputTokens, outputTokens, totalTokens)
    }

    fun clearUsageStats() {
        val file = usageStatsFile()
        if (file != null && file.exists()) file.delete()
    }

    private fun usageStatsFile(): File? {
        return filesDir?.let { File(it, FILE_USAGE_STATS) }
    }

    // ============================================================================
    // Model Lists Cache (24-hour validity per provider)
    // ============================================================================

    fun isModelListCacheValid(provider: com.ai.data.AiService): Boolean {
        val key = KEY_MODEL_LIST_TIMESTAMP_PREFIX + provider.id
        val timestamp = prefs.getLong(key, 0L)
        return System.currentTimeMillis() - timestamp < MODEL_LISTS_CACHE_DURATION_MS
    }

    fun updateModelListTimestamp(provider: com.ai.data.AiService) {
        val key = KEY_MODEL_LIST_TIMESTAMP_PREFIX + provider.id
        prefs.edit { putLong(key, System.currentTimeMillis()) }
    }

    fun clearModelListsCache() {
        prefs.edit {
            com.ai.data.AiService.entries.forEach { provider ->
                remove(KEY_MODEL_LIST_TIMESTAMP_PREFIX + provider.id)
            }
        }
        android.util.Log.d("SettingsPreferences", "All model list caches cleared")
    }
}
