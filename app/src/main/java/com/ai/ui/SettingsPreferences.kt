package com.ai.ui

import android.content.SharedPreferences
import com.ai.data.AiService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Helper class for managing all settings persistence via SharedPreferences.
 * Transactional data (usage stats, prompt history) is stored as files in filesDir.
 */
class SettingsPreferences(private val prefs: SharedPreferences, private val filesDir: File? = null) {

    private val gson = Gson()

    // ============================================================================
    // General Settings
    // ============================================================================

    fun loadGeneralSettings(): GeneralSettings {
        return GeneralSettings(
            userName = prefs.getString(KEY_USER_NAME, "user") ?: "user",
            developerMode = prefs.getBoolean(KEY_DEVELOPER_MODE, false),
            trackApiCalls = prefs.getBoolean(KEY_TRACK_API_CALLS, false),
            huggingFaceApiKey = prefs.getString(KEY_HUGGINGFACE_API_KEY, "") ?: "",
            openRouterApiKey = prefs.getString(KEY_OPENROUTER_API_KEY, "") ?: "",
            fullScreenMode = prefs.getBoolean(KEY_FULL_SCREEN_MODE, false)
        )
    }

    fun saveGeneralSettings(settings: GeneralSettings) {
        prefs.edit()
            .putString(KEY_USER_NAME, settings.userName.ifBlank { "user" })
            .putBoolean(KEY_DEVELOPER_MODE, settings.developerMode)
            .putBoolean(KEY_TRACK_API_CALLS, settings.trackApiCalls)
            .putString(KEY_HUGGINGFACE_API_KEY, settings.huggingFaceApiKey)
            .putString(KEY_OPENROUTER_API_KEY, settings.openRouterApiKey)
            .putBoolean(KEY_FULL_SCREEN_MODE, settings.fullScreenMode)
            .apply()
    }

    // ============================================================================
    // AI Settings
    // ============================================================================

    /**
     * Load AI settings with agents, flocks, and prompts.
     * Also runs migrations for settings changes.
     */
    fun loadAiSettingsWithMigration(): AiSettings {
        // Migration: Update Claude, SiliconFlow, Z.AI to use API model source
        val migrationVersion = prefs.getInt("settings_migration_version", 0)
        if (migrationVersion < 1) {
            prefs.edit()
                .putString("${AiService.ANTHROPIC.prefsKey}_model_source", ModelSource.API.name)
                .putString("${AiService.SILICONFLOW.prefsKey}_model_source", ModelSource.API.name)
                .putString("${AiService.ZAI.prefsKey}_model_source", ModelSource.API.name)
                .putInt("settings_migration_version", 1)
                .apply()
            android.util.Log.d("SettingsPreferences", "Migrated Claude, SiliconFlow, Z.AI to API model source")
        }

        val baseSettings = loadAiSettings()
        val agents = loadAgents()
        val flocks = loadFlocks()
        val swarms = loadSwarms()
        val parameters = loadParameters()
        val prompts = loadPrompts()
        val endpoints = loadEndpoints()
        val providerStates = loadProviderStates()
        return baseSettings.copy(agents = agents, flocks = flocks, swarms = swarms, parameters = parameters, prompts = prompts, endpoints = endpoints, providerStates = providerStates)
    }

    fun loadAiSettings(): AiSettings {
        val providers = AiService.entries.associateWith { service ->
            val key = service.prefsKey
            val defaults = defaultProviderConfig(service)
            ProviderConfig(
                apiKey = prefs.getString("${key}_api_key", "") ?: "",
                model = prefs.getString("${key}_model", service.defaultModel) ?: service.defaultModel,
                modelSource = loadModelSource("${key}_model_source", defaults.modelSource),
                manualModels = if (defaults.manualModels.isNotEmpty())
                    loadManualModelsWithDefault("${key}_manual_models", defaults.manualModels)
                else
                    loadManualModels("${key}_manual_models"),
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
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.w("SettingsPreferences", "Failed to load manual models for $key: ${e.message}")
            emptyList()
        }
    }

    private fun loadManualModelsWithDefault(key: String, default: List<String>): List<String> {
        val json = prefs.getString(key, null) ?: return default
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: default
        } catch (e: Exception) {
            android.util.Log.w("SettingsPreferences", "Failed to load manual models with default for $key: ${e.message}")
            default
        }
    }

    /**
     * Load parameter IDs list from SharedPreferences.
     * Handles backward compatibility: if the stored value is a plain string (old format),
     * converts it to a single-element list.
     */
    private fun loadParametersIds(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        // Try to parse as JSON list first (new format)
        if (raw.startsWith("[")) {
            return try {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson<List<String>>(raw, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
        // Old format: single ID string - convert to single-element list
        return listOf(raw)
    }

    /**
     * Save parameter IDs list to SharedPreferences as JSON.
     * Returns null if the list is empty (to clear the preference).
     */
    private fun saveParametersIds(ids: List<String>): String? {
        if (ids.isEmpty()) return null
        return gson.toJson(ids)
    }

    /**
     * Migrate old "paramsId" (String?) JSON field to new "paramsIds" (List<String>) format.
     * If the JSON object has "paramsId" but not "paramsIds", converts the single string
     * to a single-element JSON array under "paramsIds".
     */
    private fun migrateParamsIdToParamsIds(obj: com.google.gson.JsonObject) {
        if (obj.has("paramsId") && !obj.has("paramsIds")) {
            val oldValue = obj.get("paramsId")
            if (oldValue != null && !oldValue.isJsonNull) {
                val array = com.google.gson.JsonArray()
                array.add(oldValue.asString)
                obj.add("paramsIds", array)
            }
            obj.remove("paramsId")
        }
    }

    fun saveAiSettings(settings: AiSettings) {
        val editor = prefs.edit()
        // Save per-provider config
        for (service in AiService.entries) {
            val key = service.prefsKey
            val config = settings.providers[service] ?: defaultProviderConfig(service)
            editor.putString("${key}_api_key", config.apiKey)
            editor.putString("${key}_model", config.model)
            editor.putString("${key}_model_source", config.modelSource.name)
            editor.putString("${key}_manual_models", gson.toJson(config.manualModels))
            editor.putString("${key}_admin_url", config.adminUrl)
            editor.putString("${key}_model_list_url", config.modelListUrl)
            editor.putString("${key}_parameters_id", saveParametersIds(config.parametersIds))
        }
        // Save collections
        editor.putString(KEY_AI_AGENTS, gson.toJson(settings.agents))
            .putString(KEY_AI_SWARMS, gson.toJson(settings.flocks))
            .putString(KEY_AI_FLOCKS, gson.toJson(settings.swarms))
            .putString(KEY_AI_PARAMETERS, gson.toJson(settings.parameters))
            .putString(KEY_AI_PROMPTS, gson.toJson(settings.prompts))
            .putString(KEY_AI_ENDPOINTS, gson.toJson(settings.endpoints.mapKeys { it.key.name }))
            .putString(KEY_PROVIDER_STATES, gson.toJson(settings.providerStates))
            .apply()
    }

    // ============================================================================
    // AI Agents
    // ============================================================================

    private fun loadAgents(): List<AiAgent> {
        val json = prefs.getString(KEY_AI_AGENTS, null) ?: return emptyList()
        return try {
            // Parse as JsonArray to handle migration from old format with inline parameters
            val jsonArray = com.google.gson.JsonParser().parse(json).asJsonArray
            jsonArray.mapNotNull { element ->
                val obj = element.asJsonObject
                // Skip agents with null/unknown provider
                val providerName = obj.get("provider")?.asString ?: return@mapNotNull null
                try { AiService.valueOf(providerName) } catch (e: Exception) { return@mapNotNull null }
                // Migrate old "paramsId" (String?) to new "paramsIds" (List<String>) format if needed
                migrateParamsIdToParamsIds(obj)
                // Ensure paramsIds exists (Gson may deserialize as null)
                if (!obj.has("paramsIds") || obj.get("paramsIds").isJsonNull) {
                    obj.add("paramsIds", com.google.gson.JsonArray())
                }
                // Remove old "parameters" field if present (no longer in data class)
                obj.remove("parameters")
                gson.fromJson<AiAgent>(obj, AiAgent::class.java)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ============================================================================
    // AI Flocks
    // ============================================================================

    private fun loadFlocks(): List<AiFlock> {
        val json = prefs.getString(KEY_AI_SWARMS, null) ?: return emptyList()
        return try {
            // Migrate old "paramsId" (String?) to new "paramsIds" (List<String>) format
            val jsonArray = com.google.gson.JsonParser().parse(json).asJsonArray
            jsonArray.map { element ->
                val obj = element.asJsonObject
                migrateParamsIdToParamsIds(obj)
                gson.fromJson<AiFlock>(obj, AiFlock::class.java)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ============================================================================
    // AI Swarms
    // ============================================================================

    private fun loadSwarms(): List<AiSwarm> {
        val json = prefs.getString(KEY_AI_FLOCKS, null) ?: return emptyList()
        return try {
            // Migrate old "paramsId" (String?) to new "paramsIds" (List<String>) format
            val jsonArray = com.google.gson.JsonParser().parse(json).asJsonArray
            jsonArray.map { element ->
                val obj = element.asJsonObject
                migrateParamsIdToParamsIds(obj)
                gson.fromJson<AiSwarm>(obj, AiSwarm::class.java)
            }
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
            val type = object : TypeToken<List<AiParameters>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
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
            val type = object : TypeToken<List<AiPrompt>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
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
            val type = object : TypeToken<Map<String, List<AiEndpoint>>>() {}.type
            val rawMap: Map<String, List<AiEndpoint>>? = gson.fromJson(json, type)
            rawMap?.mapKeys { entry ->
                try {
                    AiService.valueOf(entry.key)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }?.filterKeys { it != null }?.mapKeys { it.key!! } ?: emptyMap()
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
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            android.util.Log.w("SettingsPreferences", "Failed to load provider states: ${e.message}")
            emptyMap()
        }
    }

    private fun saveEndpoints(endpoints: Map<AiService, List<AiEndpoint>>) {
        // Convert to Map<String, List<AiEndpoint>> for storage
        val rawMap = endpoints.mapKeys { it.key.name }
        prefs.edit().putString(KEY_AI_ENDPOINTS, gson.toJson(rawMap)).apply()
    }

    // ============================================================================
    // Prompt History (for New AI Report)
    // ============================================================================

    fun loadPromptHistory(): List<PromptHistoryEntry> {
        migratePromptHistoryFromPrefs()
        val file = promptHistoryFile() ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText()
            val type = object : TypeToken<List<PromptHistoryEntry>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
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
        // Also clear old prefs if still present
        prefs.edit().remove(KEY_PROMPT_HISTORY).apply()
    }

    private fun promptHistoryFile(): File? {
        return filesDir?.let { File(it, FILE_PROMPT_HISTORY) }
    }

    private fun migratePromptHistoryFromPrefs() {
        val file = promptHistoryFile() ?: return
        if (file.exists()) return
        val json = prefs.getString(KEY_PROMPT_HISTORY, null) ?: return
        try {
            file.writeText(json)
            prefs.edit().remove(KEY_PROMPT_HISTORY).apply()
            android.util.Log.d("SettingsPreferences", "Migrated prompt history to file")
        } catch (e: Exception) {
            android.util.Log.e("SettingsPreferences", "Failed to migrate prompt history: ${e.message}")
        }
    }

    fun clearLastAiReportPrompt() {
        prefs.edit()
            .remove(KEY_LAST_AI_REPORT_TITLE)
            .remove(KEY_LAST_AI_REPORT_PROMPT)
            .apply()
    }

    fun clearSelectedReportIds() {
        prefs.edit()
            .remove(KEY_SELECTED_SWARM_IDS)
            .remove(KEY_SELECTED_FLOCK_IDS)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "eval_prefs"

        // General settings
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_DEVELOPER_MODE = "developer_mode"
        private const val KEY_TRACK_API_CALLS = "track_api_calls"
        private const val KEY_HUGGINGFACE_API_KEY = "huggingface_api_key"
        private const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
        private const val KEY_FULL_SCREEN_MODE = "full_screen_mode"

        // Per-provider settings keys are now computed from AiService.prefsKey
        // e.g., "${service.prefsKey}_api_key", "${service.prefsKey}_model", etc.
        // AI agents
        private const val KEY_AI_AGENTS = "ai_agents"

        // AI flocks
        private const val KEY_AI_SWARMS = "ai_flocks"

        // AI swarms
        private const val KEY_AI_FLOCKS = "ai_swarms"

        // AI parameters (reusable parameter presets)
        private const val KEY_AI_PARAMETERS = "ai_parameters"

        // AI prompts (internal app prompts)
        private const val KEY_AI_PROMPTS = "ai_prompts"

        // AI endpoints per provider
        private const val KEY_AI_ENDPOINTS = "ai_endpoints"

        // Provider states (ok/error/not-used)
        private const val KEY_PROVIDER_STATES = "provider_states"

        // Prompt history for New AI Report
        private const val KEY_PROMPT_HISTORY = "prompt_history"
        const val MAX_PROMPT_HISTORY = 100

        // Last AI report title and prompt (for New AI Report screen)
        const val KEY_LAST_AI_REPORT_TITLE = "last_ai_report_title"
        const val KEY_LAST_AI_REPORT_PROMPT = "last_ai_report_prompt"

        // Selected flock IDs for report generation
        private const val KEY_SELECTED_SWARM_IDS = "selected_flockIds"

        // Selected swarm IDs for report generation
        private const val KEY_SELECTED_FLOCK_IDS = "selected_swarm_ids"

        // AI usage statistics (legacy SharedPreferences key, used for migration)
        private const val KEY_AI_USAGE_STATS = "ai_usage_stats"

        // File-based storage filenames
        private const val FILE_USAGE_STATS = "usage-stats.json"
        private const val FILE_PROMPT_HISTORY = "prompt-history.json"

        // API-fetched model lists (cached from API calls)
        private const val KEY_API_MODELS_OPENAI = "api_models_openai"
        private const val KEY_API_MODELS_GOOGLE = "api_models_google"
        private const val KEY_API_MODELS_XAI = "api_models_xai"
        private const val KEY_API_MODELS_GROQ = "api_models_groq"
        private const val KEY_API_MODELS_DEEPSEEK = "api_models_deepseek"
        private const val KEY_API_MODELS_MISTRAL = "api_models_mistral"
        private const val KEY_API_MODELS_TOGETHER = "api_models_together"
        private const val KEY_API_MODELS_OPENROUTER = "api_models_openrouter"
        private const val KEY_API_MODELS_CLAUDE = "api_models_claude"
        private const val KEY_API_MODELS_SILICONFLOW = "api_models_siliconflow"
        private const val KEY_API_MODELS_ZAI = "api_models_zai"
        private const val KEY_API_MODELS_MOONSHOT = "api_models_moonshot"
        private const val KEY_API_MODELS_COHERE = "api_models_cohere"
        private const val KEY_API_MODELS_AI21 = "api_models_ai21"
        private const val KEY_API_MODELS_DASHSCOPE = "api_models_dashscope"
        private const val KEY_API_MODELS_FIREWORKS = "api_models_fireworks"
        private const val KEY_API_MODELS_CEREBRAS = "api_models_cerebras"
        private const val KEY_API_MODELS_SAMBANOVA = "api_models_sambanova"
        private const val KEY_API_MODELS_BAICHUAN = "api_models_baichuan"
        private const val KEY_API_MODELS_STEPFUN = "api_models_stepfun"
        private const val KEY_API_MODELS_MINIMAX = "api_models_minimax"
        private const val KEY_API_MODELS_NVIDIA = "api_models_nvidia"
        private const val KEY_API_MODELS_REPLICATE = "api_models_replicate"
        private const val KEY_API_MODELS_HUGGINGFACE_INFERENCE = "api_models_huggingface_inference"
        private const val KEY_API_MODELS_LAMBDA = "api_models_lambda"
        private const val KEY_API_MODELS_LEPTON = "api_models_lepton"
        private const val KEY_API_MODELS_YI = "api_models_yi"
        private const val KEY_API_MODELS_DOUBAO = "api_models_doubao"
        private const val KEY_API_MODELS_REKA = "api_models_reka"
        private const val KEY_API_MODELS_WRITER = "api_models_writer"

        // Model lists cache timestamps (24-hour cache per provider)
        private const val KEY_MODEL_LIST_TIMESTAMP_PREFIX = "model_list_timestamp_"
        private const val MODEL_LISTS_CACHE_DURATION_MS = 24 * 60 * 60 * 1000L  // 24 hours
    }

    // ============================================================================
    // Selected Flock IDs (for report generation)
    // ============================================================================

    fun loadSelectedFlockIds(): Set<String> {
        val json = prefs.getString(KEY_SELECTED_SWARM_IDS, null) ?: return emptySet()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            val list: List<String>? = gson.fromJson(json, type)
            list?.toSet() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun saveSelectedFlockIds(flockIds: Set<String>) {
        val json = gson.toJson(flockIds.toList())
        prefs.edit().putString(KEY_SELECTED_SWARM_IDS, json).apply()
    }

    // ============================================================================
    // Selected Swarm IDs (for report generation)
    // ============================================================================

    fun loadSelectedSwarmIds(): Set<String> {
        val json = prefs.getString(KEY_SELECTED_FLOCK_IDS, null) ?: return emptySet()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            val list: List<String>? = gson.fromJson(json, type)
            list?.toSet() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun saveSelectedSwarmIds(swarmIds: Set<String>) {
        val json = gson.toJson(swarmIds.toList())
        prefs.edit().putString(KEY_SELECTED_FLOCK_IDS, json).apply()
    }

    // ============================================================================
    // AI Usage Statistics
    // ============================================================================

    fun loadUsageStats(): Map<String, AiUsageStats> {
        migrateUsageStatsFromPrefs()
        val file = usageStatsFile() ?: return emptyMap()
        if (!file.exists()) return emptyMap()
        return try {
            val json = file.readText()
            val type = object : TypeToken<List<AiUsageStats>>() {}.type
            val list: List<AiUsageStats>? = gson.fromJson(json, type)
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
        totalTokens: Int
    ) {
        val stats = loadUsageStats().toMutableMap()
        val key = "${provider.name}::$model"
        val existing = stats[key] ?: AiUsageStats(provider, model)
        stats[key] = existing.copy(
            callCount = existing.callCount + 1,
            inputTokens = existing.inputTokens + inputTokens,
            outputTokens = existing.outputTokens + outputTokens,
            totalTokens = existing.totalTokens + totalTokens
        )
        saveUsageStats(stats)
    }

    fun clearUsageStats() {
        val file = usageStatsFile()
        if (file != null && file.exists()) file.delete()
        // Also clear old prefs if still present
        prefs.edit().remove(KEY_AI_USAGE_STATS).apply()
    }

    private fun usageStatsFile(): File? {
        return filesDir?.let { File(it, FILE_USAGE_STATS) }
    }

    private fun migrateUsageStatsFromPrefs() {
        val file = usageStatsFile() ?: return
        if (file.exists()) return
        val json = prefs.getString(KEY_AI_USAGE_STATS, null) ?: return
        try {
            file.writeText(json)
            prefs.edit().remove(KEY_AI_USAGE_STATS).apply()
            android.util.Log.d("SettingsPreferences", "Migrated usage stats to file")
        } catch (e: Exception) {
            android.util.Log.e("SettingsPreferences", "Failed to migrate usage stats: ${e.message}")
        }
    }

    // ============================================================================
    // API-Fetched Model Lists (Cached)
    // ============================================================================

    private fun loadApiModels(key: String): List<String> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.w("SettingsPreferences", "Failed to load API models for $key: ${e.message}")
            emptyList()
        }
    }

    private fun saveApiModels(key: String, models: List<String>) {
        if (models.isNotEmpty()) {
            prefs.edit().putString(key, gson.toJson(models)).apply()
        }
    }

    fun loadChatGptApiModels(): List<String> = loadApiModels(KEY_API_MODELS_OPENAI)
    fun saveChatGptApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_OPENAI, models)

    fun loadGeminiApiModels(): List<String> = loadApiModels(KEY_API_MODELS_GOOGLE)
    fun saveGeminiApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_GOOGLE, models)

    fun loadGrokApiModels(): List<String> = loadApiModels(KEY_API_MODELS_XAI)
    fun saveGrokApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_XAI, models)

    fun loadGroqApiModels(): List<String> = loadApiModels(KEY_API_MODELS_GROQ)
    fun saveGroqApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_GROQ, models)

    fun loadDeepSeekApiModels(): List<String> = loadApiModels(KEY_API_MODELS_DEEPSEEK)
    fun saveDeepSeekApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_DEEPSEEK, models)

    fun loadMistralApiModels(): List<String> = loadApiModels(KEY_API_MODELS_MISTRAL)
    fun saveMistralApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_MISTRAL, models)

    fun loadTogetherApiModels(): List<String> = loadApiModels(KEY_API_MODELS_TOGETHER)
    fun saveTogetherApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_TOGETHER, models)

    fun loadOpenRouterApiModels(): List<String> = loadApiModels(KEY_API_MODELS_OPENROUTER)
    fun saveOpenRouterApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_OPENROUTER, models)

    fun loadClaudeApiModels(): List<String> = loadApiModels(KEY_API_MODELS_CLAUDE)
    fun saveClaudeApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_CLAUDE, models)

    fun loadSiliconFlowApiModels(): List<String> = loadApiModels(KEY_API_MODELS_SILICONFLOW)
    fun saveSiliconFlowApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_SILICONFLOW, models)

    fun loadZaiApiModels(): List<String> = loadApiModels(KEY_API_MODELS_ZAI)
    fun saveZaiApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_ZAI, models)

    fun loadMoonshotApiModels(): List<String> = loadApiModels(KEY_API_MODELS_MOONSHOT)
    fun saveMoonshotApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_MOONSHOT, models)

    fun loadCohereApiModels(): List<String> = loadApiModels(KEY_API_MODELS_COHERE)
    fun saveCohereApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_COHERE, models)
    fun loadAi21ApiModels(): List<String> = loadApiModels(KEY_API_MODELS_AI21)
    fun saveAi21ApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_AI21, models)
    fun loadDashScopeApiModels(): List<String> = loadApiModels(KEY_API_MODELS_DASHSCOPE)
    fun saveDashScopeApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_DASHSCOPE, models)
    fun loadFireworksApiModels(): List<String> = loadApiModels(KEY_API_MODELS_FIREWORKS)
    fun saveFireworksApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_FIREWORKS, models)
    fun loadCerebrasApiModels(): List<String> = loadApiModels(KEY_API_MODELS_CEREBRAS)
    fun saveCerebrasApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_CEREBRAS, models)
    fun loadSambaNovaApiModels(): List<String> = loadApiModels(KEY_API_MODELS_SAMBANOVA)
    fun saveSambaNovaApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_SAMBANOVA, models)
    fun loadBaichuanApiModels(): List<String> = loadApiModels(KEY_API_MODELS_BAICHUAN)
    fun saveBaichuanApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_BAICHUAN, models)
    fun loadStepFunApiModels(): List<String> = loadApiModels(KEY_API_MODELS_STEPFUN)
    fun saveStepFunApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_STEPFUN, models)
    fun loadMiniMaxApiModels(): List<String> = loadApiModels(KEY_API_MODELS_MINIMAX)
    fun saveMiniMaxApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_MINIMAX, models)
    fun loadNvidiaApiModels(): List<String> = loadApiModels(KEY_API_MODELS_NVIDIA)
    fun saveNvidiaApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_NVIDIA, models)
    fun loadReplicateApiModels(): List<String> = loadApiModels(KEY_API_MODELS_REPLICATE)
    fun saveReplicateApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_REPLICATE, models)
    fun loadHuggingFaceInferenceApiModels(): List<String> = loadApiModels(KEY_API_MODELS_HUGGINGFACE_INFERENCE)
    fun saveHuggingFaceInferenceApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_HUGGINGFACE_INFERENCE, models)
    fun loadLambdaApiModels(): List<String> = loadApiModels(KEY_API_MODELS_LAMBDA)
    fun saveLambdaApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_LAMBDA, models)
    fun loadLeptonApiModels(): List<String> = loadApiModels(KEY_API_MODELS_LEPTON)
    fun saveLeptonApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_LEPTON, models)
    fun loadYiApiModels(): List<String> = loadApiModels(KEY_API_MODELS_YI)
    fun saveYiApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_YI, models)
    fun loadDoubaoApiModels(): List<String> = loadApiModels(KEY_API_MODELS_DOUBAO)
    fun saveDoubaoApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_DOUBAO, models)
    fun loadRekaApiModels(): List<String> = loadApiModels(KEY_API_MODELS_REKA)
    fun saveRekaApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_REKA, models)
    fun loadWriterApiModels(): List<String> = loadApiModels(KEY_API_MODELS_WRITER)
    fun saveWriterApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_WRITER, models)

    // ============================================================================
    // Model Lists Cache (24-hour validity per provider)
    // ============================================================================

    fun isModelListCacheValid(provider: com.ai.data.AiService): Boolean {
        val key = KEY_MODEL_LIST_TIMESTAMP_PREFIX + provider.name
        val timestamp = prefs.getLong(key, 0L)
        return System.currentTimeMillis() - timestamp < MODEL_LISTS_CACHE_DURATION_MS
    }

    fun updateModelListTimestamp(provider: com.ai.data.AiService) {
        val key = KEY_MODEL_LIST_TIMESTAMP_PREFIX + provider.name
        prefs.edit().putLong(key, System.currentTimeMillis()).apply()
    }

    fun clearModelListsCache() {
        val editor = prefs.edit()
        com.ai.data.AiService.entries.forEach { provider ->
            editor.remove(KEY_MODEL_LIST_TIMESTAMP_PREFIX + provider.name)
        }
        editor.apply()
        android.util.Log.d("SettingsPreferences", "All model list caches cleared")
    }
}
