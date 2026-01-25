package com.eval.ui

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
            paginationPageSize = prefs.getInt(KEY_PAGINATION_PAGE_SIZE, 25).coerceIn(5, 50),
            developerMode = prefs.getBoolean(KEY_DEVELOPER_MODE, false),
            trackApiCalls = prefs.getBoolean(KEY_TRACK_API_CALLS, false)
        )
    }

    fun saveGeneralSettings(settings: GeneralSettings) {
        prefs.edit()
            .putInt(KEY_PAGINATION_PAGE_SIZE, settings.paginationPageSize.coerceIn(5, 50))
            .putBoolean(KEY_DEVELOPER_MODE, settings.developerMode)
            .putBoolean(KEY_TRACK_API_CALLS, settings.trackApiCalls)
            .apply()
    }

    // ============================================================================
    // AI Settings
    // ============================================================================

    /**
     * Load AI settings with prompts and agents, performing migration if needed.
     */
    fun loadAiSettingsWithMigration(): AiSettings {
        val baseSettings = loadAiSettings()
        val (prompts, agents) = migrateToAgentsIfNeeded(baseSettings)
        return baseSettings.copy(prompts = prompts, agents = agents)
    }

    fun loadAiSettings(): AiSettings {
        return AiSettings(
            chatGptApiKey = prefs.getString(KEY_AI_CHATGPT_API_KEY, "") ?: "",
            chatGptModel = prefs.getString(KEY_AI_CHATGPT_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini",
            chatGptPrompt = prefs.getString(KEY_AI_CHATGPT_PROMPT, DEFAULT_GAME_PROMPT) ?: DEFAULT_GAME_PROMPT,
            chatGptServerPlayerPrompt = prefs.getString(KEY_AI_CHATGPT_SERVER_PLAYER_PROMPT, DEFAULT_SERVER_PLAYER_PROMPT) ?: DEFAULT_SERVER_PLAYER_PROMPT,
            chatGptOtherPlayerPrompt = prefs.getString(KEY_AI_CHATGPT_OTHER_PLAYER_PROMPT, DEFAULT_OTHER_PLAYER_PROMPT) ?: DEFAULT_OTHER_PLAYER_PROMPT,
            chatGptModelSource = loadModelSource(KEY_AI_CHATGPT_MODEL_SOURCE, ModelSource.API),
            chatGptManualModels = loadManualModels(KEY_AI_CHATGPT_MANUAL_MODELS),
            claudeApiKey = prefs.getString(KEY_AI_CLAUDE_API_KEY, "") ?: "",
            claudeModel = prefs.getString(KEY_AI_CLAUDE_MODEL, "claude-sonnet-4-20250514") ?: "claude-sonnet-4-20250514",
            claudePrompt = prefs.getString(KEY_AI_CLAUDE_PROMPT, DEFAULT_GAME_PROMPT) ?: DEFAULT_GAME_PROMPT,
            claudeServerPlayerPrompt = prefs.getString(KEY_AI_CLAUDE_SERVER_PLAYER_PROMPT, DEFAULT_SERVER_PLAYER_PROMPT) ?: DEFAULT_SERVER_PLAYER_PROMPT,
            claudeOtherPlayerPrompt = prefs.getString(KEY_AI_CLAUDE_OTHER_PLAYER_PROMPT, DEFAULT_OTHER_PLAYER_PROMPT) ?: DEFAULT_OTHER_PLAYER_PROMPT,
            claudeModelSource = loadModelSource(KEY_AI_CLAUDE_MODEL_SOURCE, ModelSource.MANUAL),
            claudeManualModels = loadManualModelsWithDefault(KEY_AI_CLAUDE_MANUAL_MODELS, CLAUDE_MODELS),
            geminiApiKey = prefs.getString(KEY_AI_GEMINI_API_KEY, "") ?: "",
            geminiModel = prefs.getString(KEY_AI_GEMINI_MODEL, "gemini-2.0-flash") ?: "gemini-2.0-flash",
            geminiPrompt = prefs.getString(KEY_AI_GEMINI_PROMPT, DEFAULT_GAME_PROMPT) ?: DEFAULT_GAME_PROMPT,
            geminiServerPlayerPrompt = prefs.getString(KEY_AI_GEMINI_SERVER_PLAYER_PROMPT, DEFAULT_SERVER_PLAYER_PROMPT) ?: DEFAULT_SERVER_PLAYER_PROMPT,
            geminiOtherPlayerPrompt = prefs.getString(KEY_AI_GEMINI_OTHER_PLAYER_PROMPT, DEFAULT_OTHER_PLAYER_PROMPT) ?: DEFAULT_OTHER_PLAYER_PROMPT,
            geminiModelSource = loadModelSource(KEY_AI_GEMINI_MODEL_SOURCE, ModelSource.API),
            geminiManualModels = loadManualModels(KEY_AI_GEMINI_MANUAL_MODELS),
            grokApiKey = prefs.getString(KEY_AI_GROK_API_KEY, "") ?: "",
            grokModel = prefs.getString(KEY_AI_GROK_MODEL, "grok-3-mini") ?: "grok-3-mini",
            grokPrompt = prefs.getString(KEY_AI_GROK_PROMPT, DEFAULT_GAME_PROMPT) ?: DEFAULT_GAME_PROMPT,
            grokServerPlayerPrompt = prefs.getString(KEY_AI_GROK_SERVER_PLAYER_PROMPT, DEFAULT_SERVER_PLAYER_PROMPT) ?: DEFAULT_SERVER_PLAYER_PROMPT,
            grokOtherPlayerPrompt = prefs.getString(KEY_AI_GROK_OTHER_PLAYER_PROMPT, DEFAULT_OTHER_PLAYER_PROMPT) ?: DEFAULT_OTHER_PLAYER_PROMPT,
            grokModelSource = loadModelSource(KEY_AI_GROK_MODEL_SOURCE, ModelSource.API),
            grokManualModels = loadManualModels(KEY_AI_GROK_MANUAL_MODELS),
            groqApiKey = prefs.getString(KEY_AI_GROQ_API_KEY, "") ?: "",
            groqModel = prefs.getString(KEY_AI_GROQ_MODEL, "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile",
            groqPrompt = prefs.getString(KEY_AI_GROQ_PROMPT, DEFAULT_GAME_PROMPT) ?: DEFAULT_GAME_PROMPT,
            groqServerPlayerPrompt = prefs.getString(KEY_AI_GROQ_SERVER_PLAYER_PROMPT, DEFAULT_SERVER_PLAYER_PROMPT) ?: DEFAULT_SERVER_PLAYER_PROMPT,
            groqOtherPlayerPrompt = prefs.getString(KEY_AI_GROQ_OTHER_PLAYER_PROMPT, DEFAULT_OTHER_PLAYER_PROMPT) ?: DEFAULT_OTHER_PLAYER_PROMPT,
            groqModelSource = loadModelSource(KEY_AI_GROQ_MODEL_SOURCE, ModelSource.API),
            groqManualModels = loadManualModels(KEY_AI_GROQ_MANUAL_MODELS),
            deepSeekApiKey = prefs.getString(KEY_AI_DEEPSEEK_API_KEY, "") ?: "",
            deepSeekModel = prefs.getString(KEY_AI_DEEPSEEK_MODEL, "deepseek-chat") ?: "deepseek-chat",
            deepSeekPrompt = prefs.getString(KEY_AI_DEEPSEEK_PROMPT, DEFAULT_GAME_PROMPT) ?: DEFAULT_GAME_PROMPT,
            deepSeekServerPlayerPrompt = prefs.getString(KEY_AI_DEEPSEEK_SERVER_PLAYER_PROMPT, DEFAULT_SERVER_PLAYER_PROMPT) ?: DEFAULT_SERVER_PLAYER_PROMPT,
            deepSeekOtherPlayerPrompt = prefs.getString(KEY_AI_DEEPSEEK_OTHER_PLAYER_PROMPT, DEFAULT_OTHER_PLAYER_PROMPT) ?: DEFAULT_OTHER_PLAYER_PROMPT,
            deepSeekModelSource = loadModelSource(KEY_AI_DEEPSEEK_MODEL_SOURCE, ModelSource.API),
            deepSeekManualModels = loadManualModels(KEY_AI_DEEPSEEK_MANUAL_MODELS),
            mistralApiKey = prefs.getString(KEY_AI_MISTRAL_API_KEY, "") ?: "",
            mistralModel = prefs.getString(KEY_AI_MISTRAL_MODEL, "mistral-small-latest") ?: "mistral-small-latest",
            mistralPrompt = prefs.getString(KEY_AI_MISTRAL_PROMPT, DEFAULT_GAME_PROMPT) ?: DEFAULT_GAME_PROMPT,
            mistralServerPlayerPrompt = prefs.getString(KEY_AI_MISTRAL_SERVER_PLAYER_PROMPT, DEFAULT_SERVER_PLAYER_PROMPT) ?: DEFAULT_SERVER_PLAYER_PROMPT,
            mistralOtherPlayerPrompt = prefs.getString(KEY_AI_MISTRAL_OTHER_PLAYER_PROMPT, DEFAULT_OTHER_PLAYER_PROMPT) ?: DEFAULT_OTHER_PLAYER_PROMPT,
            mistralModelSource = loadModelSource(KEY_AI_MISTRAL_MODEL_SOURCE, ModelSource.API),
            mistralManualModels = loadManualModels(KEY_AI_MISTRAL_MANUAL_MODELS),
            perplexityApiKey = prefs.getString(KEY_AI_PERPLEXITY_API_KEY, "") ?: "",
            perplexityModel = prefs.getString(KEY_AI_PERPLEXITY_MODEL, "sonar") ?: "sonar",
            perplexityPrompt = prefs.getString(KEY_AI_PERPLEXITY_PROMPT, DEFAULT_GAME_PROMPT) ?: DEFAULT_GAME_PROMPT,
            perplexityServerPlayerPrompt = prefs.getString(KEY_AI_PERPLEXITY_SERVER_PLAYER_PROMPT, DEFAULT_SERVER_PLAYER_PROMPT) ?: DEFAULT_SERVER_PLAYER_PROMPT,
            perplexityOtherPlayerPrompt = prefs.getString(KEY_AI_PERPLEXITY_OTHER_PLAYER_PROMPT, DEFAULT_OTHER_PLAYER_PROMPT) ?: DEFAULT_OTHER_PLAYER_PROMPT,
            perplexityModelSource = loadModelSource(KEY_AI_PERPLEXITY_MODEL_SOURCE, ModelSource.MANUAL),
            perplexityManualModels = loadManualModelsWithDefault(KEY_AI_PERPLEXITY_MANUAL_MODELS, PERPLEXITY_MODELS),
            togetherApiKey = prefs.getString(KEY_AI_TOGETHER_API_KEY, "") ?: "",
            togetherModel = prefs.getString(KEY_AI_TOGETHER_MODEL, "meta-llama/Llama-3.3-70B-Instruct-Turbo") ?: "meta-llama/Llama-3.3-70B-Instruct-Turbo",
            togetherPrompt = prefs.getString(KEY_AI_TOGETHER_PROMPT, DEFAULT_GAME_PROMPT) ?: DEFAULT_GAME_PROMPT,
            togetherServerPlayerPrompt = prefs.getString(KEY_AI_TOGETHER_SERVER_PLAYER_PROMPT, DEFAULT_SERVER_PLAYER_PROMPT) ?: DEFAULT_SERVER_PLAYER_PROMPT,
            togetherOtherPlayerPrompt = prefs.getString(KEY_AI_TOGETHER_OTHER_PLAYER_PROMPT, DEFAULT_OTHER_PLAYER_PROMPT) ?: DEFAULT_OTHER_PLAYER_PROMPT,
            togetherModelSource = loadModelSource(KEY_AI_TOGETHER_MODEL_SOURCE, ModelSource.API),
            togetherManualModels = loadManualModels(KEY_AI_TOGETHER_MANUAL_MODELS),
            openRouterApiKey = prefs.getString(KEY_AI_OPENROUTER_API_KEY, "") ?: "",
            openRouterModel = prefs.getString(KEY_AI_OPENROUTER_MODEL, "anthropic/claude-3.5-sonnet") ?: "anthropic/claude-3.5-sonnet",
            openRouterPrompt = prefs.getString(KEY_AI_OPENROUTER_PROMPT, DEFAULT_GAME_PROMPT) ?: DEFAULT_GAME_PROMPT,
            openRouterServerPlayerPrompt = prefs.getString(KEY_AI_OPENROUTER_SERVER_PLAYER_PROMPT, DEFAULT_SERVER_PLAYER_PROMPT) ?: DEFAULT_SERVER_PLAYER_PROMPT,
            openRouterOtherPlayerPrompt = prefs.getString(KEY_AI_OPENROUTER_OTHER_PLAYER_PROMPT, DEFAULT_OTHER_PLAYER_PROMPT) ?: DEFAULT_OTHER_PLAYER_PROMPT,
            openRouterModelSource = loadModelSource(KEY_AI_OPENROUTER_MODEL_SOURCE, ModelSource.API),
            openRouterManualModels = loadManualModels(KEY_AI_OPENROUTER_MANUAL_MODELS),
            dummyApiKey = prefs.getString(KEY_AI_DUMMY_API_KEY, "") ?: "",
            dummyModel = prefs.getString(KEY_AI_DUMMY_MODEL, "dummy-model") ?: "dummy-model",
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
            emptyList()
        }
    }

    private fun loadManualModelsWithDefault(key: String, default: List<String>): List<String> {
        val json = prefs.getString(key, null) ?: return default
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: default
        } catch (e: Exception) {
            default
        }
    }

    fun saveAiSettings(settings: AiSettings) {
        prefs.edit()
            .putString(KEY_AI_CHATGPT_API_KEY, settings.chatGptApiKey)
            .putString(KEY_AI_CHATGPT_MODEL, settings.chatGptModel)
            .putString(KEY_AI_CHATGPT_PROMPT, settings.chatGptPrompt)
            .putString(KEY_AI_CHATGPT_SERVER_PLAYER_PROMPT, settings.chatGptServerPlayerPrompt)
            .putString(KEY_AI_CHATGPT_OTHER_PLAYER_PROMPT, settings.chatGptOtherPlayerPrompt)
            .putString(KEY_AI_CHATGPT_MODEL_SOURCE, settings.chatGptModelSource.name)
            .putString(KEY_AI_CHATGPT_MANUAL_MODELS, gson.toJson(settings.chatGptManualModels))
            .putString(KEY_AI_CLAUDE_API_KEY, settings.claudeApiKey)
            .putString(KEY_AI_CLAUDE_MODEL, settings.claudeModel)
            .putString(KEY_AI_CLAUDE_PROMPT, settings.claudePrompt)
            .putString(KEY_AI_CLAUDE_SERVER_PLAYER_PROMPT, settings.claudeServerPlayerPrompt)
            .putString(KEY_AI_CLAUDE_OTHER_PLAYER_PROMPT, settings.claudeOtherPlayerPrompt)
            .putString(KEY_AI_CLAUDE_MODEL_SOURCE, settings.claudeModelSource.name)
            .putString(KEY_AI_CLAUDE_MANUAL_MODELS, gson.toJson(settings.claudeManualModels))
            .putString(KEY_AI_GEMINI_API_KEY, settings.geminiApiKey)
            .putString(KEY_AI_GEMINI_MODEL, settings.geminiModel)
            .putString(KEY_AI_GEMINI_PROMPT, settings.geminiPrompt)
            .putString(KEY_AI_GEMINI_SERVER_PLAYER_PROMPT, settings.geminiServerPlayerPrompt)
            .putString(KEY_AI_GEMINI_OTHER_PLAYER_PROMPT, settings.geminiOtherPlayerPrompt)
            .putString(KEY_AI_GEMINI_MODEL_SOURCE, settings.geminiModelSource.name)
            .putString(KEY_AI_GEMINI_MANUAL_MODELS, gson.toJson(settings.geminiManualModels))
            .putString(KEY_AI_GROK_API_KEY, settings.grokApiKey)
            .putString(KEY_AI_GROK_MODEL, settings.grokModel)
            .putString(KEY_AI_GROK_PROMPT, settings.grokPrompt)
            .putString(KEY_AI_GROK_SERVER_PLAYER_PROMPT, settings.grokServerPlayerPrompt)
            .putString(KEY_AI_GROK_OTHER_PLAYER_PROMPT, settings.grokOtherPlayerPrompt)
            .putString(KEY_AI_GROK_MODEL_SOURCE, settings.grokModelSource.name)
            .putString(KEY_AI_GROK_MANUAL_MODELS, gson.toJson(settings.grokManualModels))
            .putString(KEY_AI_GROQ_API_KEY, settings.groqApiKey)
            .putString(KEY_AI_GROQ_MODEL, settings.groqModel)
            .putString(KEY_AI_GROQ_PROMPT, settings.groqPrompt)
            .putString(KEY_AI_GROQ_SERVER_PLAYER_PROMPT, settings.groqServerPlayerPrompt)
            .putString(KEY_AI_GROQ_OTHER_PLAYER_PROMPT, settings.groqOtherPlayerPrompt)
            .putString(KEY_AI_GROQ_MODEL_SOURCE, settings.groqModelSource.name)
            .putString(KEY_AI_GROQ_MANUAL_MODELS, gson.toJson(settings.groqManualModels))
            .putString(KEY_AI_DEEPSEEK_API_KEY, settings.deepSeekApiKey)
            .putString(KEY_AI_DEEPSEEK_MODEL, settings.deepSeekModel)
            .putString(KEY_AI_DEEPSEEK_PROMPT, settings.deepSeekPrompt)
            .putString(KEY_AI_DEEPSEEK_SERVER_PLAYER_PROMPT, settings.deepSeekServerPlayerPrompt)
            .putString(KEY_AI_DEEPSEEK_OTHER_PLAYER_PROMPT, settings.deepSeekOtherPlayerPrompt)
            .putString(KEY_AI_DEEPSEEK_MODEL_SOURCE, settings.deepSeekModelSource.name)
            .putString(KEY_AI_DEEPSEEK_MANUAL_MODELS, gson.toJson(settings.deepSeekManualModels))
            .putString(KEY_AI_MISTRAL_API_KEY, settings.mistralApiKey)
            .putString(KEY_AI_MISTRAL_MODEL, settings.mistralModel)
            .putString(KEY_AI_MISTRAL_PROMPT, settings.mistralPrompt)
            .putString(KEY_AI_MISTRAL_SERVER_PLAYER_PROMPT, settings.mistralServerPlayerPrompt)
            .putString(KEY_AI_MISTRAL_OTHER_PLAYER_PROMPT, settings.mistralOtherPlayerPrompt)
            .putString(KEY_AI_MISTRAL_MODEL_SOURCE, settings.mistralModelSource.name)
            .putString(KEY_AI_MISTRAL_MANUAL_MODELS, gson.toJson(settings.mistralManualModels))
            .putString(KEY_AI_PERPLEXITY_API_KEY, settings.perplexityApiKey)
            .putString(KEY_AI_PERPLEXITY_MODEL, settings.perplexityModel)
            .putString(KEY_AI_PERPLEXITY_PROMPT, settings.perplexityPrompt)
            .putString(KEY_AI_PERPLEXITY_SERVER_PLAYER_PROMPT, settings.perplexityServerPlayerPrompt)
            .putString(KEY_AI_PERPLEXITY_OTHER_PLAYER_PROMPT, settings.perplexityOtherPlayerPrompt)
            .putString(KEY_AI_PERPLEXITY_MODEL_SOURCE, settings.perplexityModelSource.name)
            .putString(KEY_AI_PERPLEXITY_MANUAL_MODELS, gson.toJson(settings.perplexityManualModels))
            .putString(KEY_AI_TOGETHER_API_KEY, settings.togetherApiKey)
            .putString(KEY_AI_TOGETHER_MODEL, settings.togetherModel)
            .putString(KEY_AI_TOGETHER_PROMPT, settings.togetherPrompt)
            .putString(KEY_AI_TOGETHER_SERVER_PLAYER_PROMPT, settings.togetherServerPlayerPrompt)
            .putString(KEY_AI_TOGETHER_OTHER_PLAYER_PROMPT, settings.togetherOtherPlayerPrompt)
            .putString(KEY_AI_TOGETHER_MODEL_SOURCE, settings.togetherModelSource.name)
            .putString(KEY_AI_TOGETHER_MANUAL_MODELS, gson.toJson(settings.togetherManualModels))
            .putString(KEY_AI_OPENROUTER_API_KEY, settings.openRouterApiKey)
            .putString(KEY_AI_OPENROUTER_MODEL, settings.openRouterModel)
            .putString(KEY_AI_OPENROUTER_PROMPT, settings.openRouterPrompt)
            .putString(KEY_AI_OPENROUTER_SERVER_PLAYER_PROMPT, settings.openRouterServerPlayerPrompt)
            .putString(KEY_AI_OPENROUTER_OTHER_PLAYER_PROMPT, settings.openRouterOtherPlayerPrompt)
            .putString(KEY_AI_OPENROUTER_MODEL_SOURCE, settings.openRouterModelSource.name)
            .putString(KEY_AI_OPENROUTER_MANUAL_MODELS, gson.toJson(settings.openRouterManualModels))
            .putString(KEY_AI_DUMMY_API_KEY, settings.dummyApiKey)
            .putString(KEY_AI_DUMMY_MODEL, settings.dummyModel)
            .putString(KEY_AI_DUMMY_MANUAL_MODELS, gson.toJson(settings.dummyManualModels))
            // Save prompts and agents
            .putString(KEY_AI_PROMPTS, gson.toJson(settings.prompts))
            .putString(KEY_AI_AGENTS, gson.toJson(settings.agents))
            .apply()
    }

    // ============================================================================
    // AI Prompts and Agents (Three-Tier Architecture)
    // ============================================================================

    private fun migrateToAgentsIfNeeded(baseSettings: AiSettings): Pair<List<AiPrompt>, List<AiAgent>> {
        // Check if already migrated
        if (prefs.getBoolean(KEY_AI_MIGRATION_DONE, false)) {
            // Load existing prompts and agents
            val prompts = loadPrompts()
            val agents = loadAgents()
            return Pair(prompts, agents)
        }

        // Migration not done - create default prompts and agents from existing settings
        val prompts = mutableListOf<AiPrompt>()
        val agents = mutableListOf<AiAgent>()

        // Create default prompts
        val gamePrompt = AiPrompt(
            id = java.util.UUID.randomUUID().toString(),
            name = "Default Prompt",
            text = DEFAULT_GAME_PROMPT
        )
        prompts.add(gamePrompt)

        // Mark migration as done
        prefs.edit().putBoolean(KEY_AI_MIGRATION_DONE, true).apply()

        return Pair(prompts, agents)
    }

    private fun loadPrompts(): List<AiPrompt> {
        val json = prefs.getString(KEY_AI_PROMPTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<AiPrompt>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun loadAgents(): List<AiAgent> {
        val json = prefs.getString(KEY_AI_AGENTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<AiAgent>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
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

    companion object {
        const val PREFS_NAME = "eval_prefs"

        // General settings
        private const val KEY_PAGINATION_PAGE_SIZE = "pagination_page_size"
        private const val KEY_DEVELOPER_MODE = "developer_mode"
        private const val KEY_TRACK_API_CALLS = "track_api_calls"

        // AI Analysis settings
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

        // AI prompts - Game prompts
        private const val KEY_AI_CHATGPT_PROMPT = "ai_chatgpt_prompt"
        private const val KEY_AI_CLAUDE_PROMPT = "ai_claude_prompt"
        private const val KEY_AI_GEMINI_PROMPT = "ai_gemini_prompt"
        private const val KEY_AI_GROK_PROMPT = "ai_grok_prompt"
        private const val KEY_AI_GROQ_PROMPT = "ai_groq_prompt"
        private const val KEY_AI_DEEPSEEK_PROMPT = "ai_deepseek_prompt"
        private const val KEY_AI_MISTRAL_API_KEY = "ai_mistral_api_key"
        private const val KEY_AI_MISTRAL_MODEL = "ai_mistral_model"
        private const val KEY_AI_MISTRAL_PROMPT = "ai_mistral_prompt"
        private const val KEY_AI_PERPLEXITY_API_KEY = "ai_perplexity_api_key"
        private const val KEY_AI_PERPLEXITY_MODEL = "ai_perplexity_model"
        private const val KEY_AI_PERPLEXITY_PROMPT = "ai_perplexity_prompt"
        private const val KEY_AI_TOGETHER_API_KEY = "ai_together_api_key"
        private const val KEY_AI_TOGETHER_MODEL = "ai_together_model"
        private const val KEY_AI_TOGETHER_PROMPT = "ai_together_prompt"
        private const val KEY_AI_OPENROUTER_API_KEY = "ai_openrouter_api_key"
        private const val KEY_AI_OPENROUTER_MODEL = "ai_openrouter_model"
        private const val KEY_AI_OPENROUTER_PROMPT = "ai_openrouter_prompt"
        private const val KEY_AI_DUMMY_API_KEY = "ai_dummy_api_key"
        private const val KEY_AI_DUMMY_MODEL = "ai_dummy_model"
        private const val KEY_AI_DUMMY_MANUAL_MODELS = "ai_dummy_manual_models"

        // AI prompts - Server player prompts
        private const val KEY_AI_CHATGPT_SERVER_PLAYER_PROMPT = "ai_chatgpt_server_player_prompt"
        private const val KEY_AI_CLAUDE_SERVER_PLAYER_PROMPT = "ai_claude_server_player_prompt"
        private const val KEY_AI_GEMINI_SERVER_PLAYER_PROMPT = "ai_gemini_server_player_prompt"
        private const val KEY_AI_GROK_SERVER_PLAYER_PROMPT = "ai_grok_server_player_prompt"
        private const val KEY_AI_GROQ_SERVER_PLAYER_PROMPT = "ai_groq_server_player_prompt"
        private const val KEY_AI_DEEPSEEK_SERVER_PLAYER_PROMPT = "ai_deepseek_server_player_prompt"
        private const val KEY_AI_MISTRAL_SERVER_PLAYER_PROMPT = "ai_mistral_server_player_prompt"
        private const val KEY_AI_PERPLEXITY_SERVER_PLAYER_PROMPT = "ai_perplexity_server_player_prompt"
        private const val KEY_AI_TOGETHER_SERVER_PLAYER_PROMPT = "ai_together_server_player_prompt"
        private const val KEY_AI_OPENROUTER_SERVER_PLAYER_PROMPT = "ai_openrouter_server_player_prompt"

        // AI prompts - Other player prompts
        private const val KEY_AI_CHATGPT_OTHER_PLAYER_PROMPT = "ai_chatgpt_other_player_prompt"
        private const val KEY_AI_CLAUDE_OTHER_PLAYER_PROMPT = "ai_claude_other_player_prompt"
        private const val KEY_AI_GEMINI_OTHER_PLAYER_PROMPT = "ai_gemini_other_player_prompt"
        private const val KEY_AI_GROK_OTHER_PLAYER_PROMPT = "ai_grok_other_player_prompt"
        private const val KEY_AI_GROQ_OTHER_PLAYER_PROMPT = "ai_groq_other_player_prompt"
        private const val KEY_AI_DEEPSEEK_OTHER_PLAYER_PROMPT = "ai_deepseek_other_player_prompt"
        private const val KEY_AI_MISTRAL_OTHER_PLAYER_PROMPT = "ai_mistral_other_player_prompt"
        private const val KEY_AI_PERPLEXITY_OTHER_PLAYER_PROMPT = "ai_perplexity_other_player_prompt"
        private const val KEY_AI_TOGETHER_OTHER_PLAYER_PROMPT = "ai_together_other_player_prompt"
        private const val KEY_AI_OPENROUTER_OTHER_PLAYER_PROMPT = "ai_openrouter_other_player_prompt"

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

        // AI prompts and agents (three-tier architecture)
        private const val KEY_AI_PROMPTS = "ai_prompts"
        private const val KEY_AI_AGENTS = "ai_agents"
        private const val KEY_AI_MIGRATION_DONE = "ai_migration_done"

        // Prompt history for New AI Report
        private const val KEY_PROMPT_HISTORY = "prompt_history"
        const val MAX_PROMPT_HISTORY = 100

        // Last AI report title and prompt (for New AI Report screen)
        const val KEY_LAST_AI_REPORT_TITLE = "last_ai_report_title"
        const val KEY_LAST_AI_REPORT_PROMPT = "last_ai_report_prompt"

        // Default prompts
        const val DEFAULT_GAME_PROMPT = "Please analyze this request:"
        const val DEFAULT_SERVER_PLAYER_PROMPT = "Please provide information about this topic:"
        const val DEFAULT_OTHER_PLAYER_PROMPT = "Please provide additional information:"

        // Claude models (manual list)
        val CLAUDE_MODELS = listOf(
            "claude-sonnet-4-20250514",
            "claude-opus-4-20250514",
            "claude-3-5-sonnet-20241022",
            "claude-3-5-haiku-20241022",
            "claude-3-opus-20240229"
        )

        // Perplexity models (manual list)
        val PERPLEXITY_MODELS = listOf(
            "sonar",
            "sonar-pro",
            "sonar-reasoning",
            "sonar-reasoning-pro"
        )
    }
}
