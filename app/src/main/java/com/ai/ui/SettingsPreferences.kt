package com.ai.ui

import android.content.SharedPreferences
import com.ai.data.AiService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Helper class for managing all settings persistence via SharedPreferences.
 */
class SettingsPreferences(private val prefs: SharedPreferences) {

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
            fullScreenMode = prefs.getBoolean(KEY_FULL_SCREEN_MODE, false)
        )
    }

    fun saveGeneralSettings(settings: GeneralSettings) {
        prefs.edit()
            .putString(KEY_USER_NAME, settings.userName.ifBlank { "user" })
            .putBoolean(KEY_DEVELOPER_MODE, settings.developerMode)
            .putBoolean(KEY_TRACK_API_CALLS, settings.trackApiCalls)
            .putString(KEY_HUGGINGFACE_API_KEY, settings.huggingFaceApiKey)
            .putBoolean(KEY_FULL_SCREEN_MODE, settings.fullScreenMode)
            .apply()
    }

    // ============================================================================
    // AI Settings
    // ============================================================================

    /**
     * Load AI settings with agents, swarms, and prompts.
     * Also runs migrations for settings changes.
     */
    fun loadAiSettingsWithMigration(): AiSettings {
        // Migration: Update Claude, SiliconFlow, Z.AI to use API model source
        val migrationVersion = prefs.getInt("settings_migration_version", 0)
        if (migrationVersion < 1) {
            prefs.edit()
                .putString(KEY_AI_ANTHROPIC_MODEL_SOURCE, ModelSource.API.name)
                .putString(KEY_AI_SILICONFLOW_MODEL_SOURCE, ModelSource.API.name)
                .putString(KEY_AI_ZAI_MODEL_SOURCE, ModelSource.API.name)
                .putInt("settings_migration_version", 1)
                .apply()
            android.util.Log.d("SettingsPreferences", "Migrated Claude, SiliconFlow, Z.AI to API model source")
        }

        val baseSettings = loadAiSettings()
        val agents = loadAgents()
        val swarms = loadSwarms()
        val prompts = loadPrompts()
        val endpoints = loadEndpoints()
        return baseSettings.copy(agents = agents, swarms = swarms, prompts = prompts, endpoints = endpoints)
    }

    fun loadAiSettings(): AiSettings {
        return AiSettings(
            chatGptApiKey = prefs.getString(KEY_AI_OPENAI_API_KEY, "") ?: "",
            chatGptModel = prefs.getString(KEY_AI_OPENAI_MODEL, AiService.OPENAI.defaultModel) ?: AiService.OPENAI.defaultModel,
            chatGptModelSource = loadModelSource(KEY_AI_OPENAI_MODEL_SOURCE, ModelSource.API),
            chatGptManualModels = loadManualModels(KEY_AI_OPENAI_MANUAL_MODELS),
            chatGptAdminUrl = prefs.getString(KEY_AI_OPENAI_ADMIN_URL, AiService.OPENAI.adminUrl) ?: AiService.OPENAI.adminUrl,
            chatGptModelListUrl = prefs.getString(KEY_AI_OPENAI_MODEL_LIST_URL, "") ?: "",
            claudeApiKey = prefs.getString(KEY_AI_ANTHROPIC_API_KEY, "") ?: "",
            claudeModel = prefs.getString(KEY_AI_ANTHROPIC_MODEL, AiService.ANTHROPIC.defaultModel) ?: AiService.ANTHROPIC.defaultModel,
            claudeModelSource = loadModelSource(KEY_AI_ANTHROPIC_MODEL_SOURCE, ModelSource.API),
            claudeManualModels = loadManualModelsWithDefault(KEY_AI_ANTHROPIC_MANUAL_MODELS, CLAUDE_MODELS),
            claudeAdminUrl = prefs.getString(KEY_AI_ANTHROPIC_ADMIN_URL, AiService.ANTHROPIC.adminUrl) ?: AiService.ANTHROPIC.adminUrl,
            claudeModelListUrl = prefs.getString(KEY_AI_ANTHROPIC_MODEL_LIST_URL, "") ?: "",
            geminiApiKey = prefs.getString(KEY_AI_GOOGLE_API_KEY, "") ?: "",
            geminiModel = prefs.getString(KEY_AI_GOOGLE_MODEL, AiService.GOOGLE.defaultModel) ?: AiService.GOOGLE.defaultModel,
            geminiModelSource = loadModelSource(KEY_AI_GOOGLE_MODEL_SOURCE, ModelSource.API),
            geminiManualModels = loadManualModels(KEY_AI_GOOGLE_MANUAL_MODELS),
            geminiAdminUrl = prefs.getString(KEY_AI_GOOGLE_ADMIN_URL, AiService.GOOGLE.adminUrl) ?: AiService.GOOGLE.adminUrl,
            geminiModelListUrl = prefs.getString(KEY_AI_GOOGLE_MODEL_LIST_URL, "") ?: "",
            grokApiKey = prefs.getString(KEY_AI_XAI_API_KEY, "") ?: "",
            grokModel = prefs.getString(KEY_AI_XAI_MODEL, AiService.XAI.defaultModel) ?: AiService.XAI.defaultModel,
            grokModelSource = loadModelSource(KEY_AI_XAI_MODEL_SOURCE, ModelSource.API),
            grokManualModels = loadManualModels(KEY_AI_XAI_MANUAL_MODELS),
            grokAdminUrl = prefs.getString(KEY_AI_XAI_ADMIN_URL, AiService.XAI.adminUrl) ?: AiService.XAI.adminUrl,
            grokModelListUrl = prefs.getString(KEY_AI_XAI_MODEL_LIST_URL, "") ?: "",
            groqApiKey = prefs.getString(KEY_AI_GROQ_API_KEY, "") ?: "",
            groqModel = prefs.getString(KEY_AI_GROQ_MODEL, AiService.GROQ.defaultModel) ?: AiService.GROQ.defaultModel,
            groqModelSource = loadModelSource(KEY_AI_GROQ_MODEL_SOURCE, ModelSource.API),
            groqManualModels = loadManualModels(KEY_AI_GROQ_MANUAL_MODELS),
            groqAdminUrl = prefs.getString(KEY_AI_GROQ_ADMIN_URL, AiService.GROQ.adminUrl) ?: AiService.GROQ.adminUrl,
            groqModelListUrl = prefs.getString(KEY_AI_GROQ_MODEL_LIST_URL, "") ?: "",
            deepSeekApiKey = prefs.getString(KEY_AI_DEEPSEEK_API_KEY, "") ?: "",
            deepSeekModel = prefs.getString(KEY_AI_DEEPSEEK_MODEL, AiService.DEEPSEEK.defaultModel) ?: AiService.DEEPSEEK.defaultModel,
            deepSeekModelSource = loadModelSource(KEY_AI_DEEPSEEK_MODEL_SOURCE, ModelSource.API),
            deepSeekManualModels = loadManualModels(KEY_AI_DEEPSEEK_MANUAL_MODELS),
            deepSeekAdminUrl = prefs.getString(KEY_AI_DEEPSEEK_ADMIN_URL, AiService.DEEPSEEK.adminUrl) ?: AiService.DEEPSEEK.adminUrl,
            deepSeekModelListUrl = prefs.getString(KEY_AI_DEEPSEEK_MODEL_LIST_URL, "") ?: "",
            mistralApiKey = prefs.getString(KEY_AI_MISTRAL_API_KEY, "") ?: "",
            mistralModel = prefs.getString(KEY_AI_MISTRAL_MODEL, AiService.MISTRAL.defaultModel) ?: AiService.MISTRAL.defaultModel,
            mistralModelSource = loadModelSource(KEY_AI_MISTRAL_MODEL_SOURCE, ModelSource.API),
            mistralManualModels = loadManualModels(KEY_AI_MISTRAL_MANUAL_MODELS),
            mistralAdminUrl = prefs.getString(KEY_AI_MISTRAL_ADMIN_URL, AiService.MISTRAL.adminUrl) ?: AiService.MISTRAL.adminUrl,
            mistralModelListUrl = prefs.getString(KEY_AI_MISTRAL_MODEL_LIST_URL, "") ?: "",
            perplexityApiKey = prefs.getString(KEY_AI_PERPLEXITY_API_KEY, "") ?: "",
            perplexityModel = prefs.getString(KEY_AI_PERPLEXITY_MODEL, AiService.PERPLEXITY.defaultModel) ?: AiService.PERPLEXITY.defaultModel,
            perplexityModelSource = loadModelSource(KEY_AI_PERPLEXITY_MODEL_SOURCE, ModelSource.MANUAL),
            perplexityManualModels = loadManualModelsWithDefault(KEY_AI_PERPLEXITY_MANUAL_MODELS, PERPLEXITY_MODELS),
            perplexityAdminUrl = prefs.getString(KEY_AI_PERPLEXITY_ADMIN_URL, AiService.PERPLEXITY.adminUrl) ?: AiService.PERPLEXITY.adminUrl,
            perplexityModelListUrl = prefs.getString(KEY_AI_PERPLEXITY_MODEL_LIST_URL, "") ?: "",
            togetherApiKey = prefs.getString(KEY_AI_TOGETHER_API_KEY, "") ?: "",
            togetherModel = prefs.getString(KEY_AI_TOGETHER_MODEL, AiService.TOGETHER.defaultModel) ?: AiService.TOGETHER.defaultModel,
            togetherModelSource = loadModelSource(KEY_AI_TOGETHER_MODEL_SOURCE, ModelSource.API),
            togetherManualModels = loadManualModels(KEY_AI_TOGETHER_MANUAL_MODELS),
            togetherAdminUrl = prefs.getString(KEY_AI_TOGETHER_ADMIN_URL, AiService.TOGETHER.adminUrl) ?: AiService.TOGETHER.adminUrl,
            togetherModelListUrl = prefs.getString(KEY_AI_TOGETHER_MODEL_LIST_URL, "") ?: "",
            openRouterApiKey = prefs.getString(KEY_AI_OPENROUTER_API_KEY, "") ?: "",
            openRouterModel = prefs.getString(KEY_AI_OPENROUTER_MODEL, AiService.OPENROUTER.defaultModel) ?: AiService.OPENROUTER.defaultModel,
            openRouterModelSource = loadModelSource(KEY_AI_OPENROUTER_MODEL_SOURCE, ModelSource.API),
            openRouterManualModels = loadManualModels(KEY_AI_OPENROUTER_MANUAL_MODELS),
            openRouterAdminUrl = prefs.getString(KEY_AI_OPENROUTER_ADMIN_URL, AiService.OPENROUTER.adminUrl) ?: AiService.OPENROUTER.adminUrl,
            openRouterModelListUrl = prefs.getString(KEY_AI_OPENROUTER_MODEL_LIST_URL, "") ?: "",
            siliconFlowApiKey = prefs.getString(KEY_AI_SILICONFLOW_API_KEY, "") ?: "",
            siliconFlowModel = prefs.getString(KEY_AI_SILICONFLOW_MODEL, AiService.SILICONFLOW.defaultModel) ?: AiService.SILICONFLOW.defaultModel,
            siliconFlowModelSource = loadModelSource(KEY_AI_SILICONFLOW_MODEL_SOURCE, ModelSource.API),
            siliconFlowManualModels = loadManualModelsWithDefault(KEY_AI_SILICONFLOW_MANUAL_MODELS, SILICONFLOW_MODELS),
            siliconFlowAdminUrl = prefs.getString(KEY_AI_SILICONFLOW_ADMIN_URL, AiService.SILICONFLOW.adminUrl) ?: AiService.SILICONFLOW.adminUrl,
            siliconFlowModelListUrl = prefs.getString(KEY_AI_SILICONFLOW_MODEL_LIST_URL, "") ?: "",
            zaiApiKey = prefs.getString(KEY_AI_ZAI_API_KEY, "") ?: "",
            zaiModel = prefs.getString(KEY_AI_ZAI_MODEL, AiService.ZAI.defaultModel) ?: AiService.ZAI.defaultModel,
            zaiModelSource = loadModelSource(KEY_AI_ZAI_MODEL_SOURCE, ModelSource.API),
            zaiManualModels = loadManualModelsWithDefault(KEY_AI_ZAI_MANUAL_MODELS, ZAI_MODELS),
            zaiAdminUrl = prefs.getString(KEY_AI_ZAI_ADMIN_URL, AiService.ZAI.adminUrl) ?: AiService.ZAI.adminUrl,
            zaiModelListUrl = prefs.getString(KEY_AI_ZAI_MODEL_LIST_URL, "") ?: "",
            dummyApiKey = prefs.getString(KEY_AI_DUMMY_API_KEY, "") ?: "",
            dummyModel = prefs.getString(KEY_AI_DUMMY_MODEL, AiService.DUMMY.defaultModel) ?: AiService.DUMMY.defaultModel,
            dummyModelSource = loadModelSource(KEY_AI_DUMMY_MODEL_SOURCE, ModelSource.API),
            dummyManualModels = loadManualModelsWithDefault(KEY_AI_DUMMY_MANUAL_MODELS, listOf(AiService.DUMMY.defaultModel)),
            dummyAdminUrl = prefs.getString(KEY_AI_DUMMY_ADMIN_URL, AiService.DUMMY.adminUrl) ?: AiService.DUMMY.adminUrl,
            dummyModelListUrl = prefs.getString(KEY_AI_DUMMY_MODEL_LIST_URL, "") ?: ""
        )
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

    fun saveAiSettings(settings: AiSettings) {
        prefs.edit()
            .putString(KEY_AI_OPENAI_API_KEY, settings.chatGptApiKey)
            .putString(KEY_AI_OPENAI_MODEL, settings.chatGptModel)
            .putString(KEY_AI_OPENAI_MODEL_SOURCE, settings.chatGptModelSource.name)
            .putString(KEY_AI_OPENAI_MANUAL_MODELS, gson.toJson(settings.chatGptManualModels))
            .putString(KEY_AI_OPENAI_ADMIN_URL, settings.chatGptAdminUrl)
            .putString(KEY_AI_OPENAI_MODEL_LIST_URL, settings.chatGptModelListUrl)
            .putString(KEY_AI_ANTHROPIC_API_KEY, settings.claudeApiKey)
            .putString(KEY_AI_ANTHROPIC_MODEL, settings.claudeModel)
            .putString(KEY_AI_ANTHROPIC_MODEL_SOURCE, settings.claudeModelSource.name)
            .putString(KEY_AI_ANTHROPIC_MANUAL_MODELS, gson.toJson(settings.claudeManualModels))
            .putString(KEY_AI_ANTHROPIC_ADMIN_URL, settings.claudeAdminUrl)
            .putString(KEY_AI_ANTHROPIC_MODEL_LIST_URL, settings.claudeModelListUrl)
            .putString(KEY_AI_GOOGLE_API_KEY, settings.geminiApiKey)
            .putString(KEY_AI_GOOGLE_MODEL, settings.geminiModel)
            .putString(KEY_AI_GOOGLE_MODEL_SOURCE, settings.geminiModelSource.name)
            .putString(KEY_AI_GOOGLE_MANUAL_MODELS, gson.toJson(settings.geminiManualModels))
            .putString(KEY_AI_GOOGLE_ADMIN_URL, settings.geminiAdminUrl)
            .putString(KEY_AI_GOOGLE_MODEL_LIST_URL, settings.geminiModelListUrl)
            .putString(KEY_AI_XAI_API_KEY, settings.grokApiKey)
            .putString(KEY_AI_XAI_MODEL, settings.grokModel)
            .putString(KEY_AI_XAI_MODEL_SOURCE, settings.grokModelSource.name)
            .putString(KEY_AI_XAI_MANUAL_MODELS, gson.toJson(settings.grokManualModels))
            .putString(KEY_AI_XAI_ADMIN_URL, settings.grokAdminUrl)
            .putString(KEY_AI_XAI_MODEL_LIST_URL, settings.grokModelListUrl)
            .putString(KEY_AI_GROQ_API_KEY, settings.groqApiKey)
            .putString(KEY_AI_GROQ_MODEL, settings.groqModel)
            .putString(KEY_AI_GROQ_MODEL_SOURCE, settings.groqModelSource.name)
            .putString(KEY_AI_GROQ_MANUAL_MODELS, gson.toJson(settings.groqManualModels))
            .putString(KEY_AI_GROQ_ADMIN_URL, settings.groqAdminUrl)
            .putString(KEY_AI_GROQ_MODEL_LIST_URL, settings.groqModelListUrl)
            .putString(KEY_AI_DEEPSEEK_API_KEY, settings.deepSeekApiKey)
            .putString(KEY_AI_DEEPSEEK_MODEL, settings.deepSeekModel)
            .putString(KEY_AI_DEEPSEEK_MODEL_SOURCE, settings.deepSeekModelSource.name)
            .putString(KEY_AI_DEEPSEEK_MANUAL_MODELS, gson.toJson(settings.deepSeekManualModels))
            .putString(KEY_AI_DEEPSEEK_ADMIN_URL, settings.deepSeekAdminUrl)
            .putString(KEY_AI_DEEPSEEK_MODEL_LIST_URL, settings.deepSeekModelListUrl)
            .putString(KEY_AI_MISTRAL_API_KEY, settings.mistralApiKey)
            .putString(KEY_AI_MISTRAL_MODEL, settings.mistralModel)
            .putString(KEY_AI_MISTRAL_MODEL_SOURCE, settings.mistralModelSource.name)
            .putString(KEY_AI_MISTRAL_MANUAL_MODELS, gson.toJson(settings.mistralManualModels))
            .putString(KEY_AI_MISTRAL_ADMIN_URL, settings.mistralAdminUrl)
            .putString(KEY_AI_MISTRAL_MODEL_LIST_URL, settings.mistralModelListUrl)
            .putString(KEY_AI_PERPLEXITY_API_KEY, settings.perplexityApiKey)
            .putString(KEY_AI_PERPLEXITY_MODEL, settings.perplexityModel)
            .putString(KEY_AI_PERPLEXITY_MODEL_SOURCE, settings.perplexityModelSource.name)
            .putString(KEY_AI_PERPLEXITY_MANUAL_MODELS, gson.toJson(settings.perplexityManualModels))
            .putString(KEY_AI_PERPLEXITY_ADMIN_URL, settings.perplexityAdminUrl)
            .putString(KEY_AI_PERPLEXITY_MODEL_LIST_URL, settings.perplexityModelListUrl)
            .putString(KEY_AI_TOGETHER_API_KEY, settings.togetherApiKey)
            .putString(KEY_AI_TOGETHER_MODEL, settings.togetherModel)
            .putString(KEY_AI_TOGETHER_MODEL_SOURCE, settings.togetherModelSource.name)
            .putString(KEY_AI_TOGETHER_MANUAL_MODELS, gson.toJson(settings.togetherManualModels))
            .putString(KEY_AI_TOGETHER_ADMIN_URL, settings.togetherAdminUrl)
            .putString(KEY_AI_TOGETHER_MODEL_LIST_URL, settings.togetherModelListUrl)
            .putString(KEY_AI_OPENROUTER_API_KEY, settings.openRouterApiKey)
            .putString(KEY_AI_OPENROUTER_MODEL, settings.openRouterModel)
            .putString(KEY_AI_OPENROUTER_MODEL_SOURCE, settings.openRouterModelSource.name)
            .putString(KEY_AI_OPENROUTER_MANUAL_MODELS, gson.toJson(settings.openRouterManualModels))
            .putString(KEY_AI_OPENROUTER_ADMIN_URL, settings.openRouterAdminUrl)
            .putString(KEY_AI_OPENROUTER_MODEL_LIST_URL, settings.openRouterModelListUrl)
            .putString(KEY_AI_SILICONFLOW_API_KEY, settings.siliconFlowApiKey)
            .putString(KEY_AI_SILICONFLOW_MODEL, settings.siliconFlowModel)
            .putString(KEY_AI_SILICONFLOW_MODEL_SOURCE, settings.siliconFlowModelSource.name)
            .putString(KEY_AI_SILICONFLOW_MANUAL_MODELS, gson.toJson(settings.siliconFlowManualModels))
            .putString(KEY_AI_SILICONFLOW_ADMIN_URL, settings.siliconFlowAdminUrl)
            .putString(KEY_AI_SILICONFLOW_MODEL_LIST_URL, settings.siliconFlowModelListUrl)
            .putString(KEY_AI_ZAI_API_KEY, settings.zaiApiKey)
            .putString(KEY_AI_ZAI_MODEL, settings.zaiModel)
            .putString(KEY_AI_ZAI_MODEL_SOURCE, settings.zaiModelSource.name)
            .putString(KEY_AI_ZAI_MANUAL_MODELS, gson.toJson(settings.zaiManualModels))
            .putString(KEY_AI_ZAI_ADMIN_URL, settings.zaiAdminUrl)
            .putString(KEY_AI_ZAI_MODEL_LIST_URL, settings.zaiModelListUrl)
            .putString(KEY_AI_DUMMY_API_KEY, settings.dummyApiKey)
            .putString(KEY_AI_DUMMY_MODEL, settings.dummyModel)
            .putString(KEY_AI_DUMMY_MODEL_SOURCE, settings.dummyModelSource.name)
            .putString(KEY_AI_DUMMY_MANUAL_MODELS, gson.toJson(settings.dummyManualModels))
            .putString(KEY_AI_DUMMY_ADMIN_URL, settings.dummyAdminUrl)
            .putString(KEY_AI_DUMMY_MODEL_LIST_URL, settings.dummyModelListUrl)
            // Save agents
            .putString(KEY_AI_AGENTS, gson.toJson(settings.agents))
            // Save swarms
            .putString(KEY_AI_SWARMS, gson.toJson(settings.swarms))
            // Save prompts
            .putString(KEY_AI_PROMPTS, gson.toJson(settings.prompts))
            // Save endpoints (convert to Map<String, List<AiEndpoint>> for storage)
            .putString(KEY_AI_ENDPOINTS, gson.toJson(settings.endpoints.mapKeys { it.key.name }))
            .apply()
    }

    // ============================================================================
    // AI Agents
    // ============================================================================

    private fun loadAgents(): List<AiAgent> {
        val json = prefs.getString(KEY_AI_AGENTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<AiAgent>>() {}.type
            val agents: List<AiAgent>? = gson.fromJson(json, type)
            // Ensure parameters is never null (migration from older versions without parameters)
            // Gson bypasses Kotlin default values, so we need to handle null explicitly
            agents?.map { agent ->
                @Suppress("SENSELESS_COMPARISON")
                if (agent.parameters == null) {
                    // Recreate agent with default parameters (can't use copy() when parameters is null)
                    AiAgent(
                        id = agent.id,
                        name = agent.name,
                        provider = agent.provider,
                        model = agent.model,
                        apiKey = agent.apiKey,
                        parameters = AiAgentParameters()
                    )
                } else {
                    agent
                }
            } ?: emptyList()
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
            val type = object : TypeToken<List<AiSwarm>>() {}.type
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

    private fun saveEndpoints(endpoints: Map<AiService, List<AiEndpoint>>) {
        // Convert to Map<String, List<AiEndpoint>> for storage
        val rawMap = endpoints.mapKeys { it.key.name }
        prefs.edit().putString(KEY_AI_ENDPOINTS, gson.toJson(rawMap)).apply()
    }

    // ============================================================================
    // Prompt History (for New AI Report)
    // ============================================================================

    fun loadPromptHistory(): List<PromptHistoryEntry> {
        val json = prefs.getString(KEY_PROMPT_HISTORY, null) ?: return emptyList()
        return try {
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
        val json = gson.toJson(trimmed)
        prefs.edit().putString(KEY_PROMPT_HISTORY, json).apply()
    }

    fun clearPromptHistory() {
        prefs.edit().remove(KEY_PROMPT_HISTORY).apply()
    }

    companion object {
        const val PREFS_NAME = "eval_prefs"

        // General settings
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_DEVELOPER_MODE = "developer_mode"
        private const val KEY_TRACK_API_CALLS = "track_api_calls"
        private const val KEY_HUGGINGFACE_API_KEY = "huggingface_api_key"
        private const val KEY_FULL_SCREEN_MODE = "full_screen_mode"

        // AI Analysis settings - API keys and models
        private const val KEY_AI_OPENAI_API_KEY = "ai_openai_api_key"
        private const val KEY_AI_OPENAI_MODEL = "ai_openai_model"
        private const val KEY_AI_ANTHROPIC_API_KEY = "ai_anthropic_api_key"
        private const val KEY_AI_ANTHROPIC_MODEL = "ai_anthropic_model"
        private const val KEY_AI_GOOGLE_API_KEY = "ai_google_api_key"
        private const val KEY_AI_GOOGLE_MODEL = "ai_google_model"
        private const val KEY_AI_XAI_API_KEY = "ai_xai_api_key"
        private const val KEY_AI_XAI_MODEL = "ai_xai_model"
        private const val KEY_AI_GROQ_API_KEY = "ai_groq_api_key"
        private const val KEY_AI_GROQ_MODEL = "ai_groq_model"
        private const val KEY_AI_DEEPSEEK_API_KEY = "ai_deepseek_api_key"
        private const val KEY_AI_DEEPSEEK_MODEL = "ai_deepseek_model"
        private const val KEY_AI_MISTRAL_API_KEY = "ai_mistral_api_key"
        private const val KEY_AI_MISTRAL_MODEL = "ai_mistral_model"
        private const val KEY_AI_PERPLEXITY_API_KEY = "ai_perplexity_api_key"
        private const val KEY_AI_PERPLEXITY_MODEL = "ai_perplexity_model"
        private const val KEY_AI_TOGETHER_API_KEY = "ai_together_api_key"
        private const val KEY_AI_TOGETHER_MODEL = "ai_together_model"
        private const val KEY_AI_OPENROUTER_API_KEY = "ai_openrouter_api_key"
        private const val KEY_AI_OPENROUTER_MODEL = "ai_openrouter_model"
        private const val KEY_AI_SILICONFLOW_API_KEY = "ai_siliconflow_api_key"
        private const val KEY_AI_SILICONFLOW_MODEL = "ai_siliconflow_model"
        private const val KEY_AI_ZAI_API_KEY = "ai_zai_api_key"
        private const val KEY_AI_ZAI_MODEL = "ai_zai_model"
        private const val KEY_AI_DUMMY_API_KEY = "ai_dummy_api_key"
        private const val KEY_AI_DUMMY_MODEL = "ai_dummy_model"
        private const val KEY_AI_DUMMY_MANUAL_MODELS = "ai_dummy_manual_models"

        // AI model source (API or MANUAL)
        private const val KEY_AI_OPENAI_MODEL_SOURCE = "ai_openai_model_source"
        private const val KEY_AI_ANTHROPIC_MODEL_SOURCE = "ai_anthropic_model_source"
        private const val KEY_AI_GOOGLE_MODEL_SOURCE = "ai_google_model_source"
        private const val KEY_AI_XAI_MODEL_SOURCE = "ai_xai_model_source"
        private const val KEY_AI_GROQ_MODEL_SOURCE = "ai_groq_model_source"
        private const val KEY_AI_DEEPSEEK_MODEL_SOURCE = "ai_deepseek_model_source"
        private const val KEY_AI_MISTRAL_MODEL_SOURCE = "ai_mistral_model_source"
        private const val KEY_AI_PERPLEXITY_MODEL_SOURCE = "ai_perplexity_model_source"
        private const val KEY_AI_TOGETHER_MODEL_SOURCE = "ai_together_model_source"
        private const val KEY_AI_OPENROUTER_MODEL_SOURCE = "ai_openrouter_model_source"
        private const val KEY_AI_SILICONFLOW_MODEL_SOURCE = "ai_siliconflow_model_source"
        private const val KEY_AI_ZAI_MODEL_SOURCE = "ai_zai_model_source"
        private const val KEY_AI_DUMMY_MODEL_SOURCE = "ai_dummy_model_source"

        // AI manual models lists
        private const val KEY_AI_OPENAI_MANUAL_MODELS = "ai_openai_manual_models"
        private const val KEY_AI_ANTHROPIC_MANUAL_MODELS = "ai_anthropic_manual_models"
        private const val KEY_AI_GOOGLE_MANUAL_MODELS = "ai_google_manual_models"
        private const val KEY_AI_XAI_MANUAL_MODELS = "ai_xai_manual_models"
        private const val KEY_AI_GROQ_MANUAL_MODELS = "ai_groq_manual_models"
        private const val KEY_AI_DEEPSEEK_MANUAL_MODELS = "ai_deepseek_manual_models"
        private const val KEY_AI_MISTRAL_MANUAL_MODELS = "ai_mistral_manual_models"
        private const val KEY_AI_PERPLEXITY_MANUAL_MODELS = "ai_perplexity_manual_models"
        private const val KEY_AI_TOGETHER_MANUAL_MODELS = "ai_together_manual_models"
        private const val KEY_AI_OPENROUTER_MANUAL_MODELS = "ai_openrouter_manual_models"
        private const val KEY_AI_SILICONFLOW_MANUAL_MODELS = "ai_siliconflow_manual_models"
        private const val KEY_AI_ZAI_MANUAL_MODELS = "ai_zai_manual_models"

        // AI admin URLs
        private const val KEY_AI_OPENAI_ADMIN_URL = "ai_openai_admin_url"
        private const val KEY_AI_ANTHROPIC_ADMIN_URL = "ai_anthropic_admin_url"
        private const val KEY_AI_GOOGLE_ADMIN_URL = "ai_google_admin_url"
        private const val KEY_AI_XAI_ADMIN_URL = "ai_xai_admin_url"
        private const val KEY_AI_GROQ_ADMIN_URL = "ai_groq_admin_url"
        private const val KEY_AI_DEEPSEEK_ADMIN_URL = "ai_deepseek_admin_url"
        private const val KEY_AI_MISTRAL_ADMIN_URL = "ai_mistral_admin_url"
        private const val KEY_AI_PERPLEXITY_ADMIN_URL = "ai_perplexity_admin_url"
        private const val KEY_AI_TOGETHER_ADMIN_URL = "ai_together_admin_url"
        private const val KEY_AI_OPENROUTER_ADMIN_URL = "ai_openrouter_admin_url"
        private const val KEY_AI_SILICONFLOW_ADMIN_URL = "ai_siliconflow_admin_url"
        private const val KEY_AI_ZAI_ADMIN_URL = "ai_zai_admin_url"
        private const val KEY_AI_DUMMY_ADMIN_URL = "ai_dummy_admin_url"

        // AI model list URLs
        private const val KEY_AI_OPENAI_MODEL_LIST_URL = "ai_openai_model_list_url"
        private const val KEY_AI_ANTHROPIC_MODEL_LIST_URL = "ai_anthropic_model_list_url"
        private const val KEY_AI_GOOGLE_MODEL_LIST_URL = "ai_google_model_list_url"
        private const val KEY_AI_XAI_MODEL_LIST_URL = "ai_xai_model_list_url"
        private const val KEY_AI_GROQ_MODEL_LIST_URL = "ai_groq_model_list_url"
        private const val KEY_AI_DEEPSEEK_MODEL_LIST_URL = "ai_deepseek_model_list_url"
        private const val KEY_AI_MISTRAL_MODEL_LIST_URL = "ai_mistral_model_list_url"
        private const val KEY_AI_PERPLEXITY_MODEL_LIST_URL = "ai_perplexity_model_list_url"
        private const val KEY_AI_TOGETHER_MODEL_LIST_URL = "ai_together_model_list_url"
        private const val KEY_AI_OPENROUTER_MODEL_LIST_URL = "ai_openrouter_model_list_url"
        private const val KEY_AI_SILICONFLOW_MODEL_LIST_URL = "ai_siliconflow_model_list_url"
        private const val KEY_AI_ZAI_MODEL_LIST_URL = "ai_zai_model_list_url"
        private const val KEY_AI_DUMMY_MODEL_LIST_URL = "ai_dummy_model_list_url"

        // AI agents
        private const val KEY_AI_AGENTS = "ai_agents"

        // AI swarms
        private const val KEY_AI_SWARMS = "ai_swarms"

        // AI prompts (internal app prompts)
        private const val KEY_AI_PROMPTS = "ai_prompts"

        // AI endpoints per provider
        private const val KEY_AI_ENDPOINTS = "ai_endpoints"

        // Prompt history for New AI Report
        private const val KEY_PROMPT_HISTORY = "prompt_history"
        const val MAX_PROMPT_HISTORY = 100

        // Last AI report title and prompt (for New AI Report screen)
        const val KEY_LAST_AI_REPORT_TITLE = "last_ai_report_title"
        const val KEY_LAST_AI_REPORT_PROMPT = "last_ai_report_prompt"

        // Selected swarm IDs for report generation
        private const val KEY_SELECTED_SWARM_IDS = "selected_swarm_ids"

        // AI usage statistics
        private const val KEY_AI_USAGE_STATS = "ai_usage_stats"

        // API-fetched model lists (cached from API calls)
        private const val KEY_API_MODELS_OPENAI = "api_models_openai"
        private const val KEY_API_MODELS_GOOGLE = "api_models_google"
        private const val KEY_API_MODELS_XAI = "api_models_xai"
        private const val KEY_API_MODELS_GROQ = "api_models_groq"
        private const val KEY_API_MODELS_DEEPSEEK = "api_models_deepseek"
        private const val KEY_API_MODELS_MISTRAL = "api_models_mistral"
        private const val KEY_API_MODELS_TOGETHER = "api_models_together"
        private const val KEY_API_MODELS_OPENROUTER = "api_models_openrouter"
        private const val KEY_API_MODELS_DUMMY = "api_models_dummy"
        private const val KEY_API_MODELS_CLAUDE = "api_models_claude"
        private const val KEY_API_MODELS_SILICONFLOW = "api_models_siliconflow"
        private const val KEY_API_MODELS_ZAI = "api_models_zai"
    }

    // ============================================================================
    // Selected Swarm IDs (for report generation)
    // ============================================================================

    fun loadSelectedSwarmIds(): Set<String> {
        val json = prefs.getString(KEY_SELECTED_SWARM_IDS, null) ?: return emptySet()
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
        prefs.edit().putString(KEY_SELECTED_SWARM_IDS, json).apply()
    }

    // ============================================================================
    // AI Usage Statistics
    // ============================================================================

    fun loadUsageStats(): Map<String, AiUsageStats> {
        val json = prefs.getString(KEY_AI_USAGE_STATS, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<List<AiUsageStats>>() {}.type
            val list: List<AiUsageStats>? = gson.fromJson(json, type)
            list?.associateBy { it.key } ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveUsageStats(stats: Map<String, AiUsageStats>) {
        val json = gson.toJson(stats.values.toList())
        prefs.edit().putString(KEY_AI_USAGE_STATS, json).apply()
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
        prefs.edit().remove(KEY_AI_USAGE_STATS).apply()
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

    fun loadDummyApiModels(): List<String> = loadApiModels(KEY_API_MODELS_DUMMY)
    fun saveDummyApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_DUMMY, models)

    fun loadClaudeApiModels(): List<String> = loadApiModels(KEY_API_MODELS_CLAUDE)
    fun saveClaudeApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_CLAUDE, models)

    fun loadSiliconFlowApiModels(): List<String> = loadApiModels(KEY_API_MODELS_SILICONFLOW)
    fun saveSiliconFlowApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_SILICONFLOW, models)

    fun loadZaiApiModels(): List<String> = loadApiModels(KEY_API_MODELS_ZAI)
    fun saveZaiApiModels(models: List<String>) = saveApiModels(KEY_API_MODELS_ZAI, models)
}
