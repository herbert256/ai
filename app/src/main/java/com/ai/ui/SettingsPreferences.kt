package com.ai.ui

import android.content.SharedPreferences
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
            paginationPageSize = prefs.getInt(KEY_PAGINATION_PAGE_SIZE, DEFAULT_PAGINATION_PAGE_SIZE)
                .coerceIn(MIN_PAGINATION_PAGE_SIZE, MAX_PAGINATION_PAGE_SIZE),
            developerMode = prefs.getBoolean(KEY_DEVELOPER_MODE, false),
            trackApiCalls = prefs.getBoolean(KEY_TRACK_API_CALLS, false)
        )
    }

    fun saveGeneralSettings(settings: GeneralSettings) {
        prefs.edit()
            .putInt(KEY_PAGINATION_PAGE_SIZE, settings.paginationPageSize
                .coerceIn(MIN_PAGINATION_PAGE_SIZE, MAX_PAGINATION_PAGE_SIZE))
            .putBoolean(KEY_DEVELOPER_MODE, settings.developerMode)
            .putBoolean(KEY_TRACK_API_CALLS, settings.trackApiCalls)
            .apply()
    }

    // ============================================================================
    // AI Settings
    // ============================================================================

    /**
     * Load AI settings with agents.
     */
    fun loadAiSettingsWithMigration(): AiSettings {
        val baseSettings = loadAiSettings()
        val agents = loadAgents()
        return baseSettings.copy(agents = agents)
    }

    fun loadAiSettings(): AiSettings {
        return AiSettings(
            chatGptApiKey = prefs.getString(KEY_AI_CHATGPT_API_KEY, "") ?: "",
            chatGptModel = prefs.getString(KEY_AI_CHATGPT_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini",
            chatGptModelSource = loadModelSource(KEY_AI_CHATGPT_MODEL_SOURCE, ModelSource.API),
            chatGptManualModels = loadManualModels(KEY_AI_CHATGPT_MANUAL_MODELS),
            claudeApiKey = prefs.getString(KEY_AI_CLAUDE_API_KEY, "") ?: "",
            claudeModel = prefs.getString(KEY_AI_CLAUDE_MODEL, "claude-sonnet-4-20250514") ?: "claude-sonnet-4-20250514",
            claudeModelSource = loadModelSource(KEY_AI_CLAUDE_MODEL_SOURCE, ModelSource.MANUAL),
            claudeManualModels = loadManualModelsWithDefault(KEY_AI_CLAUDE_MANUAL_MODELS, CLAUDE_MODELS),
            geminiApiKey = prefs.getString(KEY_AI_GEMINI_API_KEY, "") ?: "",
            geminiModel = prefs.getString(KEY_AI_GEMINI_MODEL, "gemini-2.0-flash") ?: "gemini-2.0-flash",
            geminiModelSource = loadModelSource(KEY_AI_GEMINI_MODEL_SOURCE, ModelSource.API),
            geminiManualModels = loadManualModels(KEY_AI_GEMINI_MANUAL_MODELS),
            grokApiKey = prefs.getString(KEY_AI_GROK_API_KEY, "") ?: "",
            grokModel = prefs.getString(KEY_AI_GROK_MODEL, "grok-3-mini") ?: "grok-3-mini",
            grokModelSource = loadModelSource(KEY_AI_GROK_MODEL_SOURCE, ModelSource.API),
            grokManualModels = loadManualModels(KEY_AI_GROK_MANUAL_MODELS),
            groqApiKey = prefs.getString(KEY_AI_GROQ_API_KEY, "") ?: "",
            groqModel = prefs.getString(KEY_AI_GROQ_MODEL, "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile",
            groqModelSource = loadModelSource(KEY_AI_GROQ_MODEL_SOURCE, ModelSource.API),
            groqManualModels = loadManualModels(KEY_AI_GROQ_MANUAL_MODELS),
            deepSeekApiKey = prefs.getString(KEY_AI_DEEPSEEK_API_KEY, "") ?: "",
            deepSeekModel = prefs.getString(KEY_AI_DEEPSEEK_MODEL, "deepseek-chat") ?: "deepseek-chat",
            deepSeekModelSource = loadModelSource(KEY_AI_DEEPSEEK_MODEL_SOURCE, ModelSource.API),
            deepSeekManualModels = loadManualModels(KEY_AI_DEEPSEEK_MANUAL_MODELS),
            mistralApiKey = prefs.getString(KEY_AI_MISTRAL_API_KEY, "") ?: "",
            mistralModel = prefs.getString(KEY_AI_MISTRAL_MODEL, "mistral-small-latest") ?: "mistral-small-latest",
            mistralModelSource = loadModelSource(KEY_AI_MISTRAL_MODEL_SOURCE, ModelSource.API),
            mistralManualModels = loadManualModels(KEY_AI_MISTRAL_MANUAL_MODELS),
            perplexityApiKey = prefs.getString(KEY_AI_PERPLEXITY_API_KEY, "") ?: "",
            perplexityModel = prefs.getString(KEY_AI_PERPLEXITY_MODEL, "sonar") ?: "sonar",
            perplexityModelSource = loadModelSource(KEY_AI_PERPLEXITY_MODEL_SOURCE, ModelSource.MANUAL),
            perplexityManualModels = loadManualModelsWithDefault(KEY_AI_PERPLEXITY_MANUAL_MODELS, PERPLEXITY_MODELS),
            togetherApiKey = prefs.getString(KEY_AI_TOGETHER_API_KEY, "") ?: "",
            togetherModel = prefs.getString(KEY_AI_TOGETHER_MODEL, "meta-llama/Llama-3.3-70B-Instruct-Turbo") ?: "meta-llama/Llama-3.3-70B-Instruct-Turbo",
            togetherModelSource = loadModelSource(KEY_AI_TOGETHER_MODEL_SOURCE, ModelSource.API),
            togetherManualModels = loadManualModels(KEY_AI_TOGETHER_MANUAL_MODELS),
            openRouterApiKey = prefs.getString(KEY_AI_OPENROUTER_API_KEY, "") ?: "",
            openRouterModel = prefs.getString(KEY_AI_OPENROUTER_MODEL, "anthropic/claude-3.5-sonnet") ?: "anthropic/claude-3.5-sonnet",
            openRouterModelSource = loadModelSource(KEY_AI_OPENROUTER_MODEL_SOURCE, ModelSource.API),
            openRouterManualModels = loadManualModels(KEY_AI_OPENROUTER_MANUAL_MODELS),
            siliconFlowApiKey = prefs.getString(KEY_AI_SILICONFLOW_API_KEY, "") ?: "",
            siliconFlowModel = prefs.getString(KEY_AI_SILICONFLOW_MODEL, "Qwen/Qwen2.5-7B-Instruct") ?: "Qwen/Qwen2.5-7B-Instruct",
            siliconFlowModelSource = loadModelSource(KEY_AI_SILICONFLOW_MODEL_SOURCE, ModelSource.MANUAL),
            siliconFlowManualModels = loadManualModelsWithDefault(KEY_AI_SILICONFLOW_MANUAL_MODELS, SILICONFLOW_MODELS),
            zaiApiKey = prefs.getString(KEY_AI_ZAI_API_KEY, "") ?: "",
            zaiModel = prefs.getString(KEY_AI_ZAI_MODEL, "glm-4.7-flash") ?: "glm-4.7-flash",
            zaiModelSource = loadModelSource(KEY_AI_ZAI_MODEL_SOURCE, ModelSource.MANUAL),
            zaiManualModels = loadManualModelsWithDefault(KEY_AI_ZAI_MANUAL_MODELS, ZAI_MODELS),
            dummyApiKey = prefs.getString(KEY_AI_DUMMY_API_KEY, "") ?: "",
            dummyModel = prefs.getString(KEY_AI_DUMMY_MODEL, "abc") ?: "abc",
            dummyModelSource = loadModelSource(KEY_AI_DUMMY_MODEL_SOURCE, ModelSource.API),
            dummyManualModels = loadManualModelsWithDefault(KEY_AI_DUMMY_MANUAL_MODELS, listOf("dummy-model"))
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
            .putString(KEY_AI_CHATGPT_API_KEY, settings.chatGptApiKey)
            .putString(KEY_AI_CHATGPT_MODEL, settings.chatGptModel)
            .putString(KEY_AI_CHATGPT_MODEL_SOURCE, settings.chatGptModelSource.name)
            .putString(KEY_AI_CHATGPT_MANUAL_MODELS, gson.toJson(settings.chatGptManualModels))
            .putString(KEY_AI_CLAUDE_API_KEY, settings.claudeApiKey)
            .putString(KEY_AI_CLAUDE_MODEL, settings.claudeModel)
            .putString(KEY_AI_CLAUDE_MODEL_SOURCE, settings.claudeModelSource.name)
            .putString(KEY_AI_CLAUDE_MANUAL_MODELS, gson.toJson(settings.claudeManualModels))
            .putString(KEY_AI_GEMINI_API_KEY, settings.geminiApiKey)
            .putString(KEY_AI_GEMINI_MODEL, settings.geminiModel)
            .putString(KEY_AI_GEMINI_MODEL_SOURCE, settings.geminiModelSource.name)
            .putString(KEY_AI_GEMINI_MANUAL_MODELS, gson.toJson(settings.geminiManualModels))
            .putString(KEY_AI_GROK_API_KEY, settings.grokApiKey)
            .putString(KEY_AI_GROK_MODEL, settings.grokModel)
            .putString(KEY_AI_GROK_MODEL_SOURCE, settings.grokModelSource.name)
            .putString(KEY_AI_GROK_MANUAL_MODELS, gson.toJson(settings.grokManualModels))
            .putString(KEY_AI_GROQ_API_KEY, settings.groqApiKey)
            .putString(KEY_AI_GROQ_MODEL, settings.groqModel)
            .putString(KEY_AI_GROQ_MODEL_SOURCE, settings.groqModelSource.name)
            .putString(KEY_AI_GROQ_MANUAL_MODELS, gson.toJson(settings.groqManualModels))
            .putString(KEY_AI_DEEPSEEK_API_KEY, settings.deepSeekApiKey)
            .putString(KEY_AI_DEEPSEEK_MODEL, settings.deepSeekModel)
            .putString(KEY_AI_DEEPSEEK_MODEL_SOURCE, settings.deepSeekModelSource.name)
            .putString(KEY_AI_DEEPSEEK_MANUAL_MODELS, gson.toJson(settings.deepSeekManualModels))
            .putString(KEY_AI_MISTRAL_API_KEY, settings.mistralApiKey)
            .putString(KEY_AI_MISTRAL_MODEL, settings.mistralModel)
            .putString(KEY_AI_MISTRAL_MODEL_SOURCE, settings.mistralModelSource.name)
            .putString(KEY_AI_MISTRAL_MANUAL_MODELS, gson.toJson(settings.mistralManualModels))
            .putString(KEY_AI_PERPLEXITY_API_KEY, settings.perplexityApiKey)
            .putString(KEY_AI_PERPLEXITY_MODEL, settings.perplexityModel)
            .putString(KEY_AI_PERPLEXITY_MODEL_SOURCE, settings.perplexityModelSource.name)
            .putString(KEY_AI_PERPLEXITY_MANUAL_MODELS, gson.toJson(settings.perplexityManualModels))
            .putString(KEY_AI_TOGETHER_API_KEY, settings.togetherApiKey)
            .putString(KEY_AI_TOGETHER_MODEL, settings.togetherModel)
            .putString(KEY_AI_TOGETHER_MODEL_SOURCE, settings.togetherModelSource.name)
            .putString(KEY_AI_TOGETHER_MANUAL_MODELS, gson.toJson(settings.togetherManualModels))
            .putString(KEY_AI_OPENROUTER_API_KEY, settings.openRouterApiKey)
            .putString(KEY_AI_OPENROUTER_MODEL, settings.openRouterModel)
            .putString(KEY_AI_OPENROUTER_MODEL_SOURCE, settings.openRouterModelSource.name)
            .putString(KEY_AI_OPENROUTER_MANUAL_MODELS, gson.toJson(settings.openRouterManualModels))
            .putString(KEY_AI_SILICONFLOW_API_KEY, settings.siliconFlowApiKey)
            .putString(KEY_AI_SILICONFLOW_MODEL, settings.siliconFlowModel)
            .putString(KEY_AI_SILICONFLOW_MODEL_SOURCE, settings.siliconFlowModelSource.name)
            .putString(KEY_AI_SILICONFLOW_MANUAL_MODELS, gson.toJson(settings.siliconFlowManualModels))
            .putString(KEY_AI_ZAI_API_KEY, settings.zaiApiKey)
            .putString(KEY_AI_ZAI_MODEL, settings.zaiModel)
            .putString(KEY_AI_ZAI_MODEL_SOURCE, settings.zaiModelSource.name)
            .putString(KEY_AI_ZAI_MANUAL_MODELS, gson.toJson(settings.zaiManualModels))
            .putString(KEY_AI_DUMMY_API_KEY, settings.dummyApiKey)
            .putString(KEY_AI_DUMMY_MODEL, settings.dummyModel)
            .putString(KEY_AI_DUMMY_MODEL_SOURCE, settings.dummyModelSource.name)
            .putString(KEY_AI_DUMMY_MANUAL_MODELS, gson.toJson(settings.dummyManualModels))
            // Save agents
            .putString(KEY_AI_AGENTS, gson.toJson(settings.agents))
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

        // Pagination limits
        const val MIN_PAGINATION_PAGE_SIZE = 5
        const val MAX_PAGINATION_PAGE_SIZE = 50
        const val DEFAULT_PAGINATION_PAGE_SIZE = 25

        // General settings
        private const val KEY_PAGINATION_PAGE_SIZE = "pagination_page_size"
        private const val KEY_DEVELOPER_MODE = "developer_mode"
        private const val KEY_TRACK_API_CALLS = "track_api_calls"

        // AI Analysis settings - API keys and models
        private const val KEY_AI_CHATGPT_API_KEY = "ai_chatgpt_api_key"
        private const val KEY_AI_CHATGPT_MODEL = "ai_chatgpt_model"
        private const val KEY_AI_CLAUDE_API_KEY = "ai_claude_api_key"
        private const val KEY_AI_CLAUDE_MODEL = "ai_claude_model"
        private const val KEY_AI_GEMINI_API_KEY = "ai_gemini_api_key"
        private const val KEY_AI_GEMINI_MODEL = "ai_gemini_model"
        private const val KEY_AI_GROK_API_KEY = "ai_grok_api_key"
        private const val KEY_AI_GROK_MODEL = "ai_grok_model"
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
        private const val KEY_AI_CHATGPT_MODEL_SOURCE = "ai_chatgpt_model_source"
        private const val KEY_AI_CLAUDE_MODEL_SOURCE = "ai_claude_model_source"
        private const val KEY_AI_GEMINI_MODEL_SOURCE = "ai_gemini_model_source"
        private const val KEY_AI_GROK_MODEL_SOURCE = "ai_grok_model_source"
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
        private const val KEY_AI_CHATGPT_MANUAL_MODELS = "ai_chatgpt_manual_models"
        private const val KEY_AI_CLAUDE_MANUAL_MODELS = "ai_claude_manual_models"
        private const val KEY_AI_GEMINI_MANUAL_MODELS = "ai_gemini_manual_models"
        private const val KEY_AI_GROK_MANUAL_MODELS = "ai_grok_manual_models"
        private const val KEY_AI_GROQ_MANUAL_MODELS = "ai_groq_manual_models"
        private const val KEY_AI_DEEPSEEK_MANUAL_MODELS = "ai_deepseek_manual_models"
        private const val KEY_AI_MISTRAL_MANUAL_MODELS = "ai_mistral_manual_models"
        private const val KEY_AI_PERPLEXITY_MANUAL_MODELS = "ai_perplexity_manual_models"
        private const val KEY_AI_TOGETHER_MANUAL_MODELS = "ai_together_manual_models"
        private const val KEY_AI_OPENROUTER_MANUAL_MODELS = "ai_openrouter_manual_models"
        private const val KEY_AI_SILICONFLOW_MANUAL_MODELS = "ai_siliconflow_manual_models"
        private const val KEY_AI_ZAI_MANUAL_MODELS = "ai_zai_manual_models"

        // AI agents
        private const val KEY_AI_AGENTS = "ai_agents"

        // Prompt history for New AI Report
        private const val KEY_PROMPT_HISTORY = "prompt_history"
        const val MAX_PROMPT_HISTORY = 100

        // Last AI report title and prompt (for New AI Report screen)
        const val KEY_LAST_AI_REPORT_TITLE = "last_ai_report_title"
        const val KEY_LAST_AI_REPORT_PROMPT = "last_ai_report_prompt"
    }
}
