package com.eval.ui

import com.eval.data.AiService

/**
 * Default prompt template for AI chess game analysis.
 */
const val DEFAULT_GAME_PROMPT = """You are an expert chess analyst. Analyze the following chess position given in FEN notation.

FEN: @FEN@

Please provide:
1. A brief assessment of the position (who is better and why)
2. Key strategic themes and plans for the side to play

No need to use an chess engine to look for tactical opportunities, Stockfish is already doing that for me.

Keep your analysis concise but insightful, suitable for a chess player looking to understand the position better."""

// Keep old constant name for backwards compatibility
const val DEFAULT_AI_PROMPT = DEFAULT_GAME_PROMPT

/**
 * Default prompt template for lichess.org & chess.com player analysis.
 */
const val DEFAULT_SERVER_PLAYER_PROMPT = """What do you know about user @PLAYER@ on chess server @SERVER@ ?. What is the real name of this player? What is good and the bad about this player? Is there any gossip on the internet?"""

/**
 * Default prompt template for other player analysis.
 */
const val DEFAULT_OTHER_PLAYER_PROMPT = """You are a professional chess journalist. Write a profile of the chess player @PLAYER@ (1000 words) for a serious publication.

Rules: Do not invent facts, quotes, games, ratings, titles, events, or personal details. If info is missing or uncertain, say so and label it 'unverified' or 'unknown.' If web access exists, verify key facts via reputable sources (e.g., FIDE, national federation, major chess media) and list sources at the end.

Must cover (with subheadings):

Career timeline + key results + rating/title context (only if verified)

Playing style: openings, strengths/weaknesses, psychology—grounded in evidence

2–3 signature games (human explanation; minimal notation; no engine-dump)

Rivalries/peers and place in today's chess landscape

Off-the-board work (coaching/streaming/writing/sponsors/controversies—verified only)

Current form (last 12 months) and realistic outlook

End with a tight conclusion"""

/**
 * Default prompt names for migration and initialization.
 */
const val DEFAULT_GAME_PROMPT_NAME = "Game Analysis"
const val DEFAULT_SERVER_PLAYER_PROMPT_NAME = "Server Player"
const val DEFAULT_OTHER_PLAYER_PROMPT_NAME = "Other Player"

/**
 * Enum for model source - API (fetched from provider) or Manual (user-maintained list)
 */
enum class ModelSource {
    API,
    MANUAL
}

/**
 * Available Claude models (hardcoded as Anthropic doesn't provide a list models API).
 */
val CLAUDE_MODELS = listOf(
    "claude-sonnet-4-20250514",
    "claude-opus-4-20250514",
    "claude-3-7-sonnet-20250219",
    "claude-3-5-sonnet-20241022",
    "claude-3-5-haiku-20241022",
    "claude-3-opus-20240229",
    "claude-3-sonnet-20240229",
    "claude-3-haiku-20240307"
)

/**
 * Available Perplexity models (hardcoded).
 */
val PERPLEXITY_MODELS = listOf(
    "sonar",
    "sonar-pro",
    "sonar-reasoning-pro",
    "sonar-deep-research"
)

/**
 * AI Prompt - user-created prompt template for AI analysis.
 */
data class AiPrompt(
    val id: String,    // UUID
    val name: String,  // User-defined name
    val text: String   // Prompt template with @FEN@, @PLAYER@, @SERVER@, @DATE@ placeholders
)

/**
 * AI Agent - user-created configuration combining provider, model, API key, and prompts.
 */
data class AiAgent(
    val id: String,                    // UUID
    val name: String,                  // User-defined name
    val provider: AiService,           // Reference to provider enum
    val model: String,                 // Model name
    val apiKey: String,                // API key for this agent
    val gamePromptId: String,          // Reference to AiPrompt by ID
    val serverPlayerPromptId: String,  // Reference to AiPrompt by ID
    val otherPlayerPromptId: String    // Reference to AiPrompt by ID
)

/**
 * AI Settings data class for storing API keys for various AI services.
 */
data class AiSettings(
    val chatGptApiKey: String = "",
    val chatGptModel: String = "gpt-4o-mini",
    val chatGptPrompt: String = DEFAULT_GAME_PROMPT,
    val chatGptServerPlayerPrompt: String = DEFAULT_SERVER_PLAYER_PROMPT,
    val chatGptOtherPlayerPrompt: String = DEFAULT_OTHER_PLAYER_PROMPT,
    val chatGptModelSource: ModelSource = ModelSource.API,
    val chatGptManualModels: List<String> = emptyList(),
    val claudeApiKey: String = "",
    val claudeModel: String = "claude-sonnet-4-20250514",
    val claudePrompt: String = DEFAULT_GAME_PROMPT,
    val claudeServerPlayerPrompt: String = DEFAULT_SERVER_PLAYER_PROMPT,
    val claudeOtherPlayerPrompt: String = DEFAULT_OTHER_PLAYER_PROMPT,
    val claudeModelSource: ModelSource = ModelSource.MANUAL,
    val claudeManualModels: List<String> = CLAUDE_MODELS,
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-2.0-flash",
    val geminiPrompt: String = DEFAULT_GAME_PROMPT,
    val geminiServerPlayerPrompt: String = DEFAULT_SERVER_PLAYER_PROMPT,
    val geminiOtherPlayerPrompt: String = DEFAULT_OTHER_PLAYER_PROMPT,
    val geminiModelSource: ModelSource = ModelSource.API,
    val geminiManualModels: List<String> = emptyList(),
    val grokApiKey: String = "",
    val grokModel: String = "grok-3-mini",
    val grokPrompt: String = DEFAULT_GAME_PROMPT,
    val grokServerPlayerPrompt: String = DEFAULT_SERVER_PLAYER_PROMPT,
    val grokOtherPlayerPrompt: String = DEFAULT_OTHER_PLAYER_PROMPT,
    val grokModelSource: ModelSource = ModelSource.API,
    val grokManualModels: List<String> = emptyList(),
    val groqApiKey: String = "",
    val groqModel: String = "llama-3.3-70b-versatile",
    val groqPrompt: String = DEFAULT_GAME_PROMPT,
    val groqServerPlayerPrompt: String = DEFAULT_SERVER_PLAYER_PROMPT,
    val groqOtherPlayerPrompt: String = DEFAULT_OTHER_PLAYER_PROMPT,
    val groqModelSource: ModelSource = ModelSource.API,
    val groqManualModels: List<String> = emptyList(),
    val deepSeekApiKey: String = "",
    val deepSeekModel: String = "deepseek-chat",
    val deepSeekPrompt: String = DEFAULT_GAME_PROMPT,
    val deepSeekServerPlayerPrompt: String = DEFAULT_SERVER_PLAYER_PROMPT,
    val deepSeekOtherPlayerPrompt: String = DEFAULT_OTHER_PLAYER_PROMPT,
    val deepSeekModelSource: ModelSource = ModelSource.API,
    val deepSeekManualModels: List<String> = emptyList(),
    val mistralApiKey: String = "",
    val mistralModel: String = "mistral-small-latest",
    val mistralPrompt: String = DEFAULT_GAME_PROMPT,
    val mistralServerPlayerPrompt: String = DEFAULT_SERVER_PLAYER_PROMPT,
    val mistralOtherPlayerPrompt: String = DEFAULT_OTHER_PLAYER_PROMPT,
    val mistralModelSource: ModelSource = ModelSource.API,
    val mistralManualModels: List<String> = emptyList(),
    val perplexityApiKey: String = "",
    val perplexityModel: String = "sonar",
    val perplexityPrompt: String = DEFAULT_GAME_PROMPT,
    val perplexityServerPlayerPrompt: String = DEFAULT_SERVER_PLAYER_PROMPT,
    val perplexityOtherPlayerPrompt: String = DEFAULT_OTHER_PLAYER_PROMPT,
    val perplexityModelSource: ModelSource = ModelSource.MANUAL,
    val perplexityManualModels: List<String> = PERPLEXITY_MODELS,
    val togetherApiKey: String = "",
    val togetherModel: String = "meta-llama/Llama-3.3-70B-Instruct-Turbo",
    val togetherPrompt: String = DEFAULT_GAME_PROMPT,
    val togetherServerPlayerPrompt: String = DEFAULT_SERVER_PLAYER_PROMPT,
    val togetherOtherPlayerPrompt: String = DEFAULT_OTHER_PLAYER_PROMPT,
    val togetherModelSource: ModelSource = ModelSource.API,
    val togetherManualModels: List<String> = emptyList(),
    val openRouterApiKey: String = "",
    val openRouterModel: String = "anthropic/claude-3.5-sonnet",
    val openRouterPrompt: String = DEFAULT_GAME_PROMPT,
    val openRouterServerPlayerPrompt: String = DEFAULT_SERVER_PLAYER_PROMPT,
    val openRouterOtherPlayerPrompt: String = DEFAULT_OTHER_PLAYER_PROMPT,
    val openRouterModelSource: ModelSource = ModelSource.API,
    val openRouterManualModels: List<String> = emptyList(),
    val dummyApiKey: String = "",
    val dummyModel: String = "dummy-model",
    val dummyManualModels: List<String> = listOf("dummy-model"),
    // New three-tier architecture
    val prompts: List<AiPrompt> = emptyList(),
    val agents: List<AiAgent> = emptyList()
) {
    fun getApiKey(service: AiService): String {
        return when (service) {
            AiService.CHATGPT -> chatGptApiKey
            AiService.CLAUDE -> claudeApiKey
            AiService.GEMINI -> geminiApiKey
            AiService.GROK -> grokApiKey
            AiService.GROQ -> groqApiKey
            AiService.DEEPSEEK -> deepSeekApiKey
            AiService.MISTRAL -> mistralApiKey
            AiService.PERPLEXITY -> perplexityApiKey
            AiService.TOGETHER -> togetherApiKey
            AiService.OPENROUTER -> openRouterApiKey
            AiService.DUMMY -> dummyApiKey
        }
    }

    fun getModel(service: AiService): String {
        return when (service) {
            AiService.CHATGPT -> chatGptModel
            AiService.CLAUDE -> claudeModel
            AiService.GEMINI -> geminiModel
            AiService.GROK -> grokModel
            AiService.GROQ -> groqModel
            AiService.DEEPSEEK -> deepSeekModel
            AiService.MISTRAL -> mistralModel
            AiService.PERPLEXITY -> perplexityModel
            AiService.TOGETHER -> togetherModel
            AiService.OPENROUTER -> openRouterModel
            AiService.DUMMY -> dummyModel
        }
    }

    fun getPrompt(service: AiService): String = getGamePrompt(service)

    fun getGamePrompt(service: AiService): String {
        return when (service) {
            AiService.CHATGPT -> chatGptPrompt
            AiService.CLAUDE -> claudePrompt
            AiService.GEMINI -> geminiPrompt
            AiService.GROK -> grokPrompt
            AiService.GROQ -> groqPrompt
            AiService.DEEPSEEK -> deepSeekPrompt
            AiService.MISTRAL -> mistralPrompt
            AiService.PERPLEXITY -> perplexityPrompt
            AiService.TOGETHER -> togetherPrompt
            AiService.OPENROUTER -> openRouterPrompt
            AiService.DUMMY -> DEFAULT_GAME_PROMPT
        }
    }

    fun getServerPlayerPrompt(service: AiService): String {
        return when (service) {
            AiService.CHATGPT -> chatGptServerPlayerPrompt
            AiService.CLAUDE -> claudeServerPlayerPrompt
            AiService.GEMINI -> geminiServerPlayerPrompt
            AiService.GROK -> grokServerPlayerPrompt
            AiService.GROQ -> groqServerPlayerPrompt
            AiService.DEEPSEEK -> deepSeekServerPlayerPrompt
            AiService.MISTRAL -> mistralServerPlayerPrompt
            AiService.PERPLEXITY -> perplexityServerPlayerPrompt
            AiService.TOGETHER -> togetherServerPlayerPrompt
            AiService.OPENROUTER -> openRouterServerPlayerPrompt
            AiService.DUMMY -> DEFAULT_SERVER_PLAYER_PROMPT
        }
    }

    fun getOtherPlayerPrompt(service: AiService): String {
        return when (service) {
            AiService.CHATGPT -> chatGptOtherPlayerPrompt
            AiService.CLAUDE -> claudeOtherPlayerPrompt
            AiService.GEMINI -> geminiOtherPlayerPrompt
            AiService.GROK -> grokOtherPlayerPrompt
            AiService.GROQ -> groqOtherPlayerPrompt
            AiService.DEEPSEEK -> deepSeekOtherPlayerPrompt
            AiService.MISTRAL -> mistralOtherPlayerPrompt
            AiService.PERPLEXITY -> perplexityOtherPlayerPrompt
            AiService.TOGETHER -> togetherOtherPlayerPrompt
            AiService.OPENROUTER -> openRouterOtherPlayerPrompt
            AiService.DUMMY -> DEFAULT_OTHER_PLAYER_PROMPT
        }
    }

    fun withModel(service: AiService, model: String): AiSettings {
        return when (service) {
            AiService.CHATGPT -> copy(chatGptModel = model)
            AiService.CLAUDE -> copy(claudeModel = model)
            AiService.GEMINI -> copy(geminiModel = model)
            AiService.GROK -> copy(grokModel = model)
            AiService.GROQ -> copy(groqModel = model)
            AiService.DEEPSEEK -> copy(deepSeekModel = model)
            AiService.MISTRAL -> copy(mistralModel = model)
            AiService.PERPLEXITY -> copy(perplexityModel = model)
            AiService.TOGETHER -> copy(togetherModel = model)
            AiService.OPENROUTER -> copy(openRouterModel = model)
            AiService.DUMMY -> this
        }
    }

    fun getModelSource(service: AiService): ModelSource {
        return when (service) {
            AiService.CHATGPT -> chatGptModelSource
            AiService.CLAUDE -> claudeModelSource
            AiService.GEMINI -> geminiModelSource
            AiService.GROK -> grokModelSource
            AiService.GROQ -> groqModelSource
            AiService.DEEPSEEK -> deepSeekModelSource
            AiService.MISTRAL -> mistralModelSource
            AiService.PERPLEXITY -> perplexityModelSource
            AiService.TOGETHER -> togetherModelSource
            AiService.OPENROUTER -> openRouterModelSource
            AiService.DUMMY -> ModelSource.MANUAL
        }
    }

    fun getManualModels(service: AiService): List<String> {
        return when (service) {
            AiService.CHATGPT -> chatGptManualModels
            AiService.CLAUDE -> claudeManualModels
            AiService.GEMINI -> geminiManualModels
            AiService.GROK -> grokManualModels
            AiService.GROQ -> groqManualModels
            AiService.DEEPSEEK -> deepSeekManualModels
            AiService.MISTRAL -> mistralManualModels
            AiService.PERPLEXITY -> perplexityManualModels
            AiService.TOGETHER -> togetherManualModels
            AiService.OPENROUTER -> openRouterManualModels
            AiService.DUMMY -> emptyList()
        }
    }

    fun hasAnyApiKey(): Boolean {
        return chatGptApiKey.isNotBlank() ||
                claudeApiKey.isNotBlank() ||
                geminiApiKey.isNotBlank() ||
                grokApiKey.isNotBlank() ||
                deepSeekApiKey.isNotBlank() ||
                mistralApiKey.isNotBlank() ||
                perplexityApiKey.isNotBlank() ||
                togetherApiKey.isNotBlank() ||
                openRouterApiKey.isNotBlank()
    }

    fun getConfiguredServices(): List<AiService> {
        return AiService.entries.filter { getApiKey(it).isNotBlank() }
    }

    // Helper methods for prompts
    fun getPromptById(id: String): AiPrompt? = prompts.find { it.id == id }

    fun getPromptByName(name: String): AiPrompt? = prompts.find { it.name == name }

    // Helper methods for agents
    fun getAgentById(id: String): AiAgent? = agents.find { it.id == id }

    fun getConfiguredAgents(): List<AiAgent> = agents.filter { it.apiKey.isNotBlank() }

    /**
     * Get the prompt text for an agent's game analysis.
     * Falls back to default if prompt not found.
     */
    fun getAgentGamePrompt(agent: AiAgent): String {
        return getPromptById(agent.gamePromptId)?.text ?: DEFAULT_GAME_PROMPT
    }

    /**
     * Get the prompt text for an agent's server player analysis.
     * Falls back to default if prompt not found.
     */
    fun getAgentServerPlayerPrompt(agent: AiAgent): String {
        return getPromptById(agent.serverPlayerPromptId)?.text ?: DEFAULT_SERVER_PLAYER_PROMPT
    }

    /**
     * Get the prompt text for an agent's other player analysis.
     * Falls back to default if prompt not found.
     */
    fun getAgentOtherPlayerPrompt(agent: AiAgent): String {
        return getPromptById(agent.otherPlayerPromptId)?.text ?: DEFAULT_OTHER_PLAYER_PROMPT
    }
}
