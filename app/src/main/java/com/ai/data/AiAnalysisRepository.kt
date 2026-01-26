package com.ai.data

import com.ai.ui.AiAgentParameters
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.ResponseBody
import java.io.BufferedReader
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Token usage statistics from AI API response.
 */
data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int
) {
    val totalTokens: Int get() = inputTokens + outputTokens
}

/**
 * Response from AI analysis containing either the analysis text or an error message.
 */
data class AiAnalysisResponse(
    val service: AiService,
    val analysis: String?,
    val error: String?,
    val tokenUsage: TokenUsage? = null,
    val agentName: String? = null,  // Name of the agent that generated this response (for three-tier architecture)
    val promptUsed: String? = null,  // The actual prompt sent to the AI (with @FEN@ etc. replaced)
    val citations: List<String>? = null,  // Citations/sources returned by AI (e.g., Perplexity)
    val searchResults: List<SearchResult>? = null,  // Search results returned by AI (e.g., Grok, Perplexity)
    val relatedQuestions: List<String>? = null,  // Related questions returned by AI (e.g., Perplexity)
    val rawUsageJson: String? = null,  // Raw usage/usageMetadata JSON for developer mode
    val httpHeaders: String? = null,  // HTTP response headers for developer mode
    val httpStatusCode: Int? = null  // HTTP status code (captured even on parse errors)
) {
    val isSuccess: Boolean get() = analysis != null && error == null

    // Display name: use agent name if available, otherwise service name
    val displayName: String get() = agentName ?: service.displayName
}

/**
 * Repository for making AI analysis requests to various AI services.
 */
class AiAnalysisRepository {
    companion object {
        private const val RETRY_DELAY_MS = 500L
        private const val TEST_PROMPT = "Reply with exactly: OK"
    }

    private val openAiApi = AiApiFactory.createOpenAiApi()
    private val claudeApi = AiApiFactory.createClaudeApi()
    private val geminiApi = AiApiFactory.createGeminiApi()
    private val grokApi = AiApiFactory.createGrokApi()
    private val groqApi = AiApiFactory.createGroqApi()
    private val deepSeekApi = AiApiFactory.createDeepSeekApi()
    private val mistralApi = AiApiFactory.createMistralApi()
    private val perplexityApi = AiApiFactory.createPerplexityApi()
    private val togetherApi = AiApiFactory.createTogetherApi()
    private val openRouterApi = AiApiFactory.createOpenRouterApi()
    private val siliconFlowApi = AiApiFactory.createSiliconFlowApi()
    private val zaiApi = AiApiFactory.createZaiApi()
    private val dummyApi = AiApiFactory.createDummyApi()

    // Streaming API instances
    private val openAiStreamApi = AiApiFactory.createOpenAiStreamApi()
    private val claudeStreamApi = AiApiFactory.createClaudeStreamApi()
    private val geminiStreamApi = AiApiFactory.createGeminiStreamApi()
    private val grokStreamApi = AiApiFactory.createGrokStreamApi()
    private val groqStreamApi = AiApiFactory.createGroqStreamApi()
    private val deepSeekStreamApi = AiApiFactory.createDeepSeekStreamApi()
    private val mistralStreamApi = AiApiFactory.createMistralStreamApi()
    private val perplexityStreamApi = AiApiFactory.createPerplexityStreamApi()
    private val togetherStreamApi = AiApiFactory.createTogetherStreamApi()
    private val openRouterStreamApi = AiApiFactory.createOpenRouterStreamApi()
    private val siliconFlowStreamApi = AiApiFactory.createSiliconFlowStreamApi()
    private val zaiStreamApi = AiApiFactory.createZaiStreamApi()
    private val dummyStreamApi = AiApiFactory.createDummyStreamApi()

    // Gson instance for pretty printing usage JSON
    private val gson = GsonBuilder().setPrettyPrinting().create()

    // Helper to convert usage object to pretty JSON
    private fun formatUsageJson(usage: Any?): String? {
        return usage?.let { gson.toJson(it) }
    }

    // Helper to format HTTP headers as a string
    private fun formatHeaders(headers: Headers): String {
        return headers.toMultimap().entries.joinToString("\n") { (name, values) ->
            "$name: ${values.joinToString(", ")}"
        }
    }

    /**
     * Formats the current date as "Saturday, January 24th".
     */
    private fun formatCurrentDate(): String {
        val today = LocalDate.now()
        val dayOfWeek = today.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH))
        val month = today.format(DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH))
        val dayOfMonth = today.dayOfMonth
        val ordinalSuffix = when {
            dayOfMonth in 11..13 -> "th"
            dayOfMonth % 10 == 1 -> "st"
            dayOfMonth % 10 == 2 -> "nd"
            dayOfMonth % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$dayOfWeek, $month $dayOfMonth$ordinalSuffix"
    }

    /**
     * Builds the chess analysis prompt by replacing @FEN@ and @DATE@ placeholders.
     */
    private fun buildChessPrompt(promptTemplate: String, fen: String): String {
        return promptTemplate
            .replace("@FEN@", fen)
            .replace("@DATE@", formatCurrentDate())
    }

    /**
     * Builds the player analysis prompt by replacing @PLAYER@, @SERVER@, and @DATE@ placeholders.
     */
    private fun buildPlayerPrompt(promptTemplate: String, playerName: String, server: String?): String {
        var result = promptTemplate
            .replace("@PLAYER@", playerName)
            .replace("@DATE@", formatCurrentDate())
        if (server != null) {
            result = result.replace("@SERVER@", server)
        }
        return result
    }

    /**
     * Analyzes a chess player using the specified AI service.
     *
     * @param service The AI service to use
     * @param playerName The name of the player to analyze
     * @param server The chess server (e.g., "lichess.org", "chess.com") or null for other players
     * @param apiKey The API key for the service
     * @param prompt The custom prompt template (use @PLAYER@ and @SERVER@ as placeholders)
     * @param chatGptModel The ChatGPT model to use
     * @param claudeModel The Claude model to use
     * @param geminiModel The Gemini model to use
     * @param grokModel The Grok model to use
     * @param deepSeekModel The DeepSeek model to use
     * @param mistralModel The Mistral model to use
     * @return AiAnalysisResponse containing either the analysis or an error
     */
    suspend fun analyzePlayer(
        service: AiService,
        playerName: String,
        server: String?,
        apiKey: String,
        prompt: String,
        chatGptModel: String = AiService.OPENAI.defaultModel,
        claudeModel: String = AiService.ANTHROPIC.defaultModel,
        geminiModel: String = AiService.GOOGLE.defaultModel,
        grokModel: String = AiService.XAI.defaultModel,
        groqModel: String = AiService.GROQ.defaultModel,
        deepSeekModel: String = AiService.DEEPSEEK.defaultModel,
        mistralModel: String = AiService.MISTRAL.defaultModel,
        perplexityModel: String = AiService.PERPLEXITY.defaultModel,
        togetherModel: String = AiService.TOGETHER.defaultModel,
        openRouterModel: String = AiService.OPENROUTER.defaultModel,
        siliconFlowModel: String = AiService.SILICONFLOW.defaultModel,
        zaiModel: String = AiService.ZAI.defaultModel
    ): AiAnalysisResponse = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext AiAnalysisResponse(
                service = service,
                analysis = null,
                error = "API key not configured for ${service.displayName}"
            )
        }

        val finalPrompt = buildPlayerPrompt(prompt, playerName, server)

        suspend fun makeApiCall(): AiAnalysisResponse {
            return when (service) {
                AiService.OPENAI -> analyzeWithChatGpt(apiKey, finalPrompt, chatGptModel)
                AiService.ANTHROPIC -> analyzeWithClaude(apiKey, finalPrompt, claudeModel)
                AiService.GOOGLE -> analyzeWithGemini(apiKey, finalPrompt, geminiModel)
                AiService.XAI -> analyzeWithGrok(apiKey, finalPrompt, grokModel)
                AiService.GROQ -> analyzeWithGroq(apiKey, finalPrompt, groqModel)
                AiService.DEEPSEEK -> analyzeWithDeepSeek(apiKey, finalPrompt, deepSeekModel)
                AiService.MISTRAL -> analyzeWithMistral(apiKey, finalPrompt, mistralModel)
                AiService.PERPLEXITY -> analyzeWithPerplexity(apiKey, finalPrompt, perplexityModel)
                AiService.TOGETHER -> analyzeWithTogether(apiKey, finalPrompt, togetherModel)
                AiService.OPENROUTER -> analyzeWithOpenRouter(apiKey, finalPrompt, openRouterModel)
                AiService.SILICONFLOW -> analyzeWithSiliconFlow(apiKey, finalPrompt, siliconFlowModel)
                AiService.ZAI -> analyzeWithZai(apiKey, finalPrompt, zaiModel)
                AiService.DUMMY -> analyzeWithDummy("dummy", finalPrompt, "abc")
            }
        }

        // First attempt
        try {
            val result = makeApiCall()
            if (result.isSuccess) {
                return@withContext result
            }
            // API returned an error response, retry after delay
            android.util.Log.w("AiAnalysis", "${service.displayName} player analysis first attempt failed: ${result.error}, retrying...")
            delay(RETRY_DELAY_MS)
            return@withContext makeApiCall()
        } catch (e: Exception) {
            // Network/exception error, retry after delay
            android.util.Log.w("AiAnalysis", "${service.displayName} player analysis first attempt exception: ${e.message}, retrying...")
            try {
                delay(RETRY_DELAY_MS)
                return@withContext makeApiCall()
            } catch (e2: Exception) {
                return@withContext AiAnalysisResponse(
                    service = service,
                    analysis = null,
                    error = "Failed after retry: ${e2.message}"
                )
            }
        }
    }

    /**
     * Analyzes a chess position using the specified AI service.
     *
     * @param service The AI service to use
     * @param fen The FEN string representing the chess position
     * @param apiKey The API key for the service
     * @param prompt The custom prompt template (use @FEN@ as placeholder)
     * @param chatGptModel The ChatGPT model to use
     * @param claudeModel The Claude model to use
     * @param geminiModel The Gemini model to use
     * @param grokModel The Grok model to use
     * @param deepSeekModel The DeepSeek model to use
     * @return AiAnalysisResponse containing either the analysis or an error
     */
    suspend fun analyzePosition(
        service: AiService,
        fen: String,
        apiKey: String,
        prompt: String,
        chatGptModel: String = AiService.OPENAI.defaultModel,
        claudeModel: String = AiService.ANTHROPIC.defaultModel,
        geminiModel: String = AiService.GOOGLE.defaultModel,
        grokModel: String = AiService.XAI.defaultModel,
        groqModel: String = AiService.GROQ.defaultModel,
        deepSeekModel: String = AiService.DEEPSEEK.defaultModel,
        mistralModel: String = AiService.MISTRAL.defaultModel,
        perplexityModel: String = AiService.PERPLEXITY.defaultModel,
        togetherModel: String = AiService.TOGETHER.defaultModel,
        openRouterModel: String = AiService.OPENROUTER.defaultModel,
        siliconFlowModel: String = AiService.SILICONFLOW.defaultModel,
        zaiModel: String = AiService.ZAI.defaultModel
    ): AiAnalysisResponse = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext AiAnalysisResponse(
                service = service,
                analysis = null,
                error = "API key not configured for ${service.displayName}"
            )
        }

        val finalPrompt = buildChessPrompt(prompt, fen)

        suspend fun makeApiCall(): AiAnalysisResponse {
            return when (service) {
                AiService.OPENAI -> analyzeWithChatGpt(apiKey, finalPrompt, chatGptModel)
                AiService.ANTHROPIC -> analyzeWithClaude(apiKey, finalPrompt, claudeModel)
                AiService.GOOGLE -> analyzeWithGemini(apiKey, finalPrompt, geminiModel)
                AiService.XAI -> analyzeWithGrok(apiKey, finalPrompt, grokModel)
                AiService.GROQ -> analyzeWithGroq(apiKey, finalPrompt, groqModel)
                AiService.DEEPSEEK -> analyzeWithDeepSeek(apiKey, finalPrompt, deepSeekModel)
                AiService.MISTRAL -> analyzeWithMistral(apiKey, finalPrompt, mistralModel)
                AiService.PERPLEXITY -> analyzeWithPerplexity(apiKey, finalPrompt, perplexityModel)
                AiService.TOGETHER -> analyzeWithTogether(apiKey, finalPrompt, togetherModel)
                AiService.OPENROUTER -> analyzeWithOpenRouter(apiKey, finalPrompt, openRouterModel)
                AiService.SILICONFLOW -> analyzeWithSiliconFlow(apiKey, finalPrompt, siliconFlowModel)
                AiService.ZAI -> analyzeWithZai(apiKey, finalPrompt, zaiModel)
                AiService.DUMMY -> analyzeWithDummy("dummy", finalPrompt, "abc")
            }
        }

        // First attempt
        try {
            val result = makeApiCall()
            if (result.isSuccess) {
                return@withContext result
            }
            // API returned an error response, retry after delay
            android.util.Log.w("AiAnalysis", "${service.displayName} first attempt failed: ${result.error}, retrying...")
            delay(RETRY_DELAY_MS)
            return@withContext makeApiCall()
        } catch (e: Exception) {
            // Network/exception error, retry after delay
            android.util.Log.w("AiAnalysis", "${service.displayName} first attempt exception: ${e.message}, retrying...")
            try {
                delay(RETRY_DELAY_MS)
                return@withContext makeApiCall()
            } catch (e2: Exception) {
                return@withContext AiAnalysisResponse(
                    service = service,
                    analysis = null,
                    error = "Network error after retry: ${e2.message ?: "Unknown error"}"
                )
            }
        }
    }

    /**
     * Analyze a chess position using an AI Agent configuration.
     * Uses the agent's provider, model, and API key.
     */
    suspend fun analyzePositionWithAgent(
        agent: com.ai.ui.AiAgent,
        fen: String,
        prompt: String
    ): AiAnalysisResponse = withContext(Dispatchers.IO) {
        if (agent.apiKey.isBlank()) {
            return@withContext AiAnalysisResponse(
                service = agent.provider,
                analysis = null,
                error = "API key not configured for agent ${agent.name}",
                agentName = agent.name
            )
        }

        val finalPrompt = buildChessPrompt(prompt, fen)

        suspend fun makeApiCall(): AiAnalysisResponse {
            val params = agent.parameters
            val result = when (agent.provider) {
                AiService.OPENAI -> analyzeWithChatGpt(agent.apiKey, finalPrompt, agent.model, params)
                AiService.ANTHROPIC -> analyzeWithClaude(agent.apiKey, finalPrompt, agent.model, params)
                AiService.GOOGLE -> analyzeWithGemini(agent.apiKey, finalPrompt, agent.model, params)
                AiService.XAI -> analyzeWithGrok(agent.apiKey, finalPrompt, agent.model, params)
                AiService.GROQ -> analyzeWithGroq(agent.apiKey, finalPrompt, agent.model, params)
                AiService.DEEPSEEK -> analyzeWithDeepSeek(agent.apiKey, finalPrompt, agent.model, params)
                AiService.MISTRAL -> analyzeWithMistral(agent.apiKey, finalPrompt, agent.model, params)
                AiService.PERPLEXITY -> analyzeWithPerplexity(agent.apiKey, finalPrompt, agent.model, params)
                AiService.TOGETHER -> analyzeWithTogether(agent.apiKey, finalPrompt, agent.model, params)
                AiService.OPENROUTER -> analyzeWithOpenRouter(agent.apiKey, finalPrompt, agent.model, params)
                AiService.SILICONFLOW -> analyzeWithSiliconFlow(agent.apiKey, finalPrompt, agent.model, params)
                AiService.ZAI -> analyzeWithZai(agent.apiKey, finalPrompt, agent.model, params)
                AiService.DUMMY -> analyzeWithDummy(agent.apiKey, finalPrompt, agent.model, params)
            }
            // Add agent name and prompt used to result
            return result.copy(agentName = agent.name, promptUsed = finalPrompt)
        }

        // First attempt
        try {
            val result = makeApiCall()
            if (result.isSuccess) {
                return@withContext result
            }
            android.util.Log.w("AiAnalysis", "Agent ${agent.name} first attempt failed: ${result.error}, retrying...")
            delay(RETRY_DELAY_MS)
            return@withContext makeApiCall()
        } catch (e: Exception) {
            android.util.Log.w("AiAnalysis", "Agent ${agent.name} first attempt exception: ${e.message}, retrying...")
            try {
                delay(RETRY_DELAY_MS)
                return@withContext makeApiCall()
            } catch (e2: Exception) {
                return@withContext AiAnalysisResponse(
                    service = agent.provider,
                    analysis = null,
                    error = "Network error after retry: ${e2.message ?: "Unknown error"}",
                    agentName = agent.name
                )
            }
        }
    }

    /**
     * Analyze a player using an AI Agent configuration (no FEN, just prompt).
     */
    suspend fun analyzePlayerWithAgent(
        agent: com.ai.ui.AiAgent,
        prompt: String
    ): AiAnalysisResponse = withContext(Dispatchers.IO) {
        if (agent.apiKey.isBlank()) {
            return@withContext AiAnalysisResponse(
                service = agent.provider,
                analysis = null,
                error = "API key not configured for agent ${agent.name}",
                agentName = agent.name
            )
        }

        // Replace @DATE@ placeholder
        val finalPrompt = prompt.replace("@DATE@", formatCurrentDate())

        suspend fun makeApiCall(): AiAnalysisResponse {
            val params = agent.parameters
            val result = when (agent.provider) {
                AiService.OPENAI -> analyzeWithChatGpt(agent.apiKey, finalPrompt, agent.model, params)
                AiService.ANTHROPIC -> analyzeWithClaude(agent.apiKey, finalPrompt, agent.model, params)
                AiService.GOOGLE -> analyzeWithGemini(agent.apiKey, finalPrompt, agent.model, params)
                AiService.XAI -> analyzeWithGrok(agent.apiKey, finalPrompt, agent.model, params)
                AiService.GROQ -> analyzeWithGroq(agent.apiKey, finalPrompt, agent.model, params)
                AiService.DEEPSEEK -> analyzeWithDeepSeek(agent.apiKey, finalPrompt, agent.model, params)
                AiService.MISTRAL -> analyzeWithMistral(agent.apiKey, finalPrompt, agent.model, params)
                AiService.PERPLEXITY -> analyzeWithPerplexity(agent.apiKey, finalPrompt, agent.model, params)
                AiService.TOGETHER -> analyzeWithTogether(agent.apiKey, finalPrompt, agent.model, params)
                AiService.OPENROUTER -> analyzeWithOpenRouter(agent.apiKey, finalPrompt, agent.model, params)
                AiService.SILICONFLOW -> analyzeWithSiliconFlow(agent.apiKey, finalPrompt, agent.model, params)
                AiService.ZAI -> analyzeWithZai(agent.apiKey, finalPrompt, agent.model, params)
                AiService.DUMMY -> analyzeWithDummy(agent.apiKey, finalPrompt, agent.model, params)
            }
            return result.copy(agentName = agent.name, promptUsed = finalPrompt)
        }

        try {
            val result = makeApiCall()
            if (result.isSuccess) {
                return@withContext result
            }
            android.util.Log.w("AiAnalysis", "Agent ${agent.name} player analysis failed: ${result.error}, retrying...")
            delay(RETRY_DELAY_MS)
            return@withContext makeApiCall()
        } catch (e: Exception) {
            android.util.Log.w("AiAnalysis", "Agent ${agent.name} player analysis exception: ${e.message}, retrying...")
            try {
                delay(RETRY_DELAY_MS)
                return@withContext makeApiCall()
            } catch (e2: Exception) {
                return@withContext AiAnalysisResponse(
                    service = agent.provider,
                    analysis = null,
                    error = "Network error after retry: ${e2.message ?: "Unknown error"}",
                    agentName = agent.name
                )
            }
        }
    }

    /**
     * Check if a model requires the Responses API (GPT-5.x and newer).
     * Chat Completions API is used for older models (gpt-4o, gpt-4, gpt-3.5, etc.)
     */
    private fun usesResponsesApi(model: String): Boolean {
        val lowerModel = model.lowercase()
        return lowerModel.startsWith("gpt-5") ||
               lowerModel.startsWith("o3") ||
               lowerModel.startsWith("o4")
    }

    private suspend fun analyzeWithChatGpt(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
        return if (usesResponsesApi(model)) {
            analyzeWithChatGptResponsesApi(apiKey, prompt, model, params)
        } else {
            analyzeWithChatGptChatCompletions(apiKey, prompt, model, params)
        }
    }

    /**
     * Use the Chat Completions API for older models (gpt-4o, gpt-4, gpt-3.5, etc.)
     */
    private suspend fun analyzeWithChatGptChatCompletions(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
        // Build messages with optional system prompt
        val messages = buildList {
            params?.systemPrompt?.let { systemPrompt ->
                if (systemPrompt.isNotBlank()) {
                    add(OpenAiMessage(role = "system", content = systemPrompt))
                }
            }
            add(OpenAiMessage(role = "user", content = prompt))
        }

        val request = OpenAiRequest(
            model = model,
            messages = messages,
            max_tokens = params?.maxTokens,
            temperature = params?.temperature,
            top_p = params?.topP,
            frequency_penalty = params?.frequencyPenalty,
            presence_penalty = params?.presencePenalty,
            stop = params?.stopSequences?.takeIf { it.isNotEmpty() },
            seed = params?.seed,
            response_format = if (params?.responseFormatJson == true) OpenAiResponseFormat(type = "json_object") else null
        )
        val response = openAiApi.createChatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )

        val headers = formatHeaders(response.headers())
        val statusCode = response.code()
        return if (response.isSuccessful) {
            val body = response.body()
            // Try multiple parsing strategies for content extraction
            val content = body?.choices?.let { choices ->
                // Strategy 1: Get content from first choice's message
                choices.firstOrNull()?.message?.content
                // Strategy 2: Get reasoning_content from first choice (DeepSeek)
                    ?: choices.firstOrNull()?.message?.reasoning_content
                // Strategy 3: Try any choice with non-null content
                    ?: choices.firstNotNullOfOrNull { it.message?.content }
                // Strategy 4: Try any choice with reasoning_content
                    ?: choices.firstNotNullOfOrNull { it.message?.reasoning_content }
            }
            val rawUsageJson = formatUsageJson(body?.usage)
            val usage = body?.usage?.let {
                TokenUsage(
                    inputTokens = it.prompt_tokens ?: it.input_tokens ?: 0,
                    outputTokens = it.completion_tokens ?: it.output_tokens ?: 0
                )
            }
            if (content != null) {
                AiAnalysisResponse(AiService.OPENAI, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
            } else {
                val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
                AiAnalysisResponse(AiService.OPENAI, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
            }
        } else {
            AiAnalysisResponse(AiService.OPENAI, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
        }
    }

    /**
     * Use the Responses API for newer models (gpt-5.x, o3, o4, etc.)
     */
    private suspend fun analyzeWithChatGptResponsesApi(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
        val request = OpenAiResponsesRequest(
            model = model,
            input = prompt,
            instructions = params?.systemPrompt?.takeIf { it.isNotBlank() }
        )
        val response = openAiApi.createResponse(
            authorization = "Bearer $apiKey",
            request = request
        )

        val headers = formatHeaders(response.headers())
        val statusCode = response.code()
        return if (response.isSuccessful) {
            val body = response.body()
            // Try multiple parsing strategies for content extraction
            val content = body?.output?.let { outputs ->
                // Strategy 1: Look for output_text type in first output's content
                outputs.firstOrNull()?.content?.firstOrNull { it.type == "output_text" }?.text
                // Strategy 2: Look for text type in first output's content
                    ?: outputs.firstOrNull()?.content?.firstOrNull { it.type == "text" }?.text
                // Strategy 3: Get first non-null text from first output's content
                    ?: outputs.firstOrNull()?.content?.firstNotNullOfOrNull { it.text }
                // Strategy 4: Look for message type output with content
                    ?: outputs.firstOrNull { it.type == "message" }?.content?.firstNotNullOfOrNull { it.text }
                // Strategy 5: Try any output's content
                    ?: outputs.flatMap { it.content ?: emptyList() }.firstNotNullOfOrNull { it.text }
            }
            val rawUsageJson = formatUsageJson(body?.usage)
            // Responses API uses input_tokens/output_tokens (not prompt_tokens/completion_tokens)
            val usage = body?.usage?.let {
                TokenUsage(
                    inputTokens = it.input_tokens ?: it.prompt_tokens ?: 0,
                    outputTokens = it.output_tokens ?: it.completion_tokens ?: 0
                )
            }
            if (content != null) {
                AiAnalysisResponse(AiService.OPENAI, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
            } else {
                val errorMsg = body?.error?.message ?: "No response content (output: ${body?.output})"
                AiAnalysisResponse(AiService.OPENAI, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
            }
        } else {
            AiAnalysisResponse(AiService.OPENAI, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
        }
    }

    private suspend fun analyzeWithClaude(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
        val request = ClaudeRequest(
            model = model,
            messages = listOf(ClaudeMessage(role = "user", content = prompt)),
            max_tokens = params?.maxTokens ?: 1024,
            temperature = params?.temperature,
            top_p = params?.topP,
            top_k = params?.topK,
            system = params?.systemPrompt?.takeIf { it.isNotBlank() },
            stop_sequences = params?.stopSequences?.takeIf { it.isNotEmpty() }
        )
        val response = claudeApi.createMessage(apiKey = apiKey, request = request)

        val headers = formatHeaders(response.headers())
        val statusCode = response.code()
        return if (response.isSuccessful) {
            val body = response.body()
            // Try multiple parsing strategies for content extraction
            val content = body?.content?.let { contentBlocks ->
                // Strategy 1: Get text from first "text" type block
                contentBlocks.firstOrNull { it.type == "text" }?.text
                // Strategy 2: Get any non-null text from content blocks
                    ?: contentBlocks.firstNotNullOfOrNull { it.text }
            }
            val rawUsageJson = formatUsageJson(body?.usage)
            val usage = body?.usage?.let {
                TokenUsage(
                    inputTokens = it.input_tokens ?: 0,
                    outputTokens = it.output_tokens ?: 0
                )
            }
            if (content != null) {
                AiAnalysisResponse(AiService.ANTHROPIC, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
            } else {
                val errorMsg = body?.error?.message ?: "No response content (blocks: ${body?.content?.size ?: 0})"
                AiAnalysisResponse(AiService.ANTHROPIC, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
            }
        } else {
            AiAnalysisResponse(AiService.ANTHROPIC, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
        }
    }

    private suspend fun analyzeWithGemini(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
        // Build generation config if any parameters are set
        val generationConfig = if (params != null && (params.temperature != null || params.topP != null ||
            params.topK != null || params.maxTokens != null || !params.stopSequences.isNullOrEmpty())) {
            GeminiGenerationConfig(
                temperature = params.temperature,
                topP = params.topP,
                topK = params.topK,
                maxOutputTokens = params.maxTokens,
                stopSequences = params.stopSequences?.takeIf { it.isNotEmpty() }
            )
        } else null

        // System instruction for Gemini
        val systemInstruction = params?.systemPrompt?.takeIf { it.isNotBlank() }?.let {
            GeminiContent(parts = listOf(GeminiPart(text = it)))
        }

        val request = GeminiRequest(
            contents = listOf(
                GeminiContent(parts = listOf(GeminiPart(text = prompt)))
            ),
            generationConfig = generationConfig,
            systemInstruction = systemInstruction
        )

        val response = geminiApi.generateContent(model = model, apiKey = apiKey, request = request)

        android.util.Log.d("GeminiAPI", "Response code: ${response.code()}, message: ${response.message()}")

        val headers = formatHeaders(response.headers())
        val statusCode = response.code()
        return if (response.isSuccessful) {
            val body = response.body()
            // Try multiple parsing strategies for content extraction
            val content = body?.candidates?.let { candidates ->
                // Strategy 1: Get text from first candidate's first part
                candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
                // Strategy 2: Get any non-null text from first candidate's parts
                    ?: candidates.firstOrNull()?.content?.parts?.firstNotNullOfOrNull { it.text }
                // Strategy 3: Try any candidate with non-null content
                    ?: candidates.flatMap { it.content?.parts ?: emptyList() }.firstNotNullOfOrNull { it.text }
            }
            val rawUsageJson = formatUsageJson(body?.usageMetadata)
            val usage = body?.usageMetadata?.let {
                TokenUsage(
                    inputTokens = it.promptTokenCount ?: 0,
                    outputTokens = it.candidatesTokenCount ?: 0
                )
            }
            if (content != null) {
                AiAnalysisResponse(AiService.GOOGLE, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
            } else {
                val errorMsg = body?.error?.message ?: "No response content (candidates: ${body?.candidates?.size ?: 0})"
                AiAnalysisResponse(AiService.GOOGLE, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
            }
        } else {
            val errorBody = response.errorBody()?.string()
            android.util.Log.e("GeminiAPI", "Error body: $errorBody")
            AiAnalysisResponse(AiService.GOOGLE, null, "API error: ${response.code()} ${response.message()} - $errorBody", httpHeaders = headers, httpStatusCode = statusCode)
        }
    }

    private suspend fun analyzeWithGrok(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
        // Build messages with optional system prompt
        val messages = buildList {
            params?.systemPrompt?.let { systemPrompt ->
                if (systemPrompt.isNotBlank()) {
                    add(OpenAiMessage(role = "system", content = systemPrompt))
                }
            }
            add(OpenAiMessage(role = "user", content = prompt))
        }

        val request = GrokRequest(
            model = model,
            messages = messages,
            max_tokens = params?.maxTokens,
            temperature = params?.temperature,
            top_p = params?.topP,
            frequency_penalty = params?.frequencyPenalty,
            presence_penalty = params?.presencePenalty,
            stop = params?.stopSequences?.takeIf { it.isNotEmpty() },
            search = if (params?.searchEnabled == true) true else null
        )
        val response = grokApi.createChatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )

        val headers = formatHeaders(response.headers())
        val statusCode = response.code()
        return if (response.isSuccessful) {
            val body = response.body()
            // Try multiple parsing strategies for content extraction
            val content = body?.choices?.let { choices ->
                choices.firstOrNull()?.message?.content
                    ?: choices.firstOrNull()?.message?.reasoning_content
                    ?: choices.firstNotNullOfOrNull { it.message?.content }
            }
            val searchResults = body?.search_results  // Grok may return search results
            val rawUsageJson = formatUsageJson(body?.usage)
            val usage = body?.usage?.let {
                TokenUsage(
                    inputTokens = it.prompt_tokens ?: 0,
                    outputTokens = it.completion_tokens ?: 0
                )
            }
            if (content != null) {
                AiAnalysisResponse(AiService.XAI, content, null, usage, searchResults = searchResults, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
            } else {
                val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
                AiAnalysisResponse(AiService.XAI, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
            }
        } else {
            AiAnalysisResponse(AiService.XAI, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
        }
    }

    private suspend fun analyzeWithGroq(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
        // Build messages with optional system prompt
        val messages = buildList {
            params?.systemPrompt?.let { systemPrompt ->
                if (systemPrompt.isNotBlank()) {
                    add(OpenAiMessage(role = "system", content = systemPrompt))
                }
            }
            add(OpenAiMessage(role = "user", content = prompt))
        }

        val request = GroqRequest(
            model = model,
            messages = messages,
            max_tokens = params?.maxTokens,
            temperature = params?.temperature,
            top_p = params?.topP,
            frequency_penalty = params?.frequencyPenalty,
            presence_penalty = params?.presencePenalty,
            stop = params?.stopSequences?.takeIf { it.isNotEmpty() },
            seed = params?.seed
        )
        val response = groqApi.createChatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )

        val headers = formatHeaders(response.headers())
        val statusCode = response.code()
        return if (response.isSuccessful) {
            val body = response.body()
            // Try multiple parsing strategies for content extraction
            val content = body?.choices?.let { choices ->
                choices.firstOrNull()?.message?.content
                    ?: choices.firstOrNull()?.message?.reasoning_content
                    ?: choices.firstNotNullOfOrNull { it.message?.content }
            }
            val rawUsageJson = formatUsageJson(body?.usage)
            val usage = body?.usage?.let {
                TokenUsage(
                    inputTokens = it.prompt_tokens ?: 0,
                    outputTokens = it.completion_tokens ?: 0
                )
            }
            if (content != null) {
                AiAnalysisResponse(AiService.GROQ, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
            } else {
                val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
                AiAnalysisResponse(AiService.GROQ, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
            }
        } else {
            AiAnalysisResponse(AiService.GROQ, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
        }
    }

    private suspend fun analyzeWithDeepSeek(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
        // Build messages with optional system prompt
        val messages = buildList {
            params?.systemPrompt?.let { systemPrompt ->
                if (systemPrompt.isNotBlank()) {
                    add(OpenAiMessage(role = "system", content = systemPrompt))
                }
            }
            add(OpenAiMessage(role = "user", content = prompt))
        }

        val request = DeepSeekRequest(
            model = model,
            messages = messages,
            max_tokens = params?.maxTokens,
            temperature = params?.temperature,
            top_p = params?.topP,
            frequency_penalty = params?.frequencyPenalty,
            presence_penalty = params?.presencePenalty,
            stop = params?.stopSequences?.takeIf { it.isNotEmpty() }
        )
        val response = deepSeekApi.createChatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )

        val headers = formatHeaders(response.headers())
        val statusCode = response.code()
        return if (response.isSuccessful) {
            val body = response.body()
            // Try multiple parsing strategies for content extraction
            // DeepSeek reasoning models may return content in reasoning_content field
            val content = body?.choices?.let { choices ->
                val message = choices.firstOrNull()?.message
                message?.content
                    ?: message?.reasoning_content
                    ?: choices.firstNotNullOfOrNull { it.message?.content }
                    ?: choices.firstNotNullOfOrNull { it.message?.reasoning_content }
            }
            val searchResults = body?.search_results
            val rawUsageJson = formatUsageJson(body?.usage)
            val usage = body?.usage?.let {
                TokenUsage(
                    inputTokens = it.prompt_tokens ?: 0,
                    outputTokens = it.completion_tokens ?: 0
                )
            }
            if (!content.isNullOrBlank()) {
                AiAnalysisResponse(AiService.DEEPSEEK, content, null, usage, searchResults = searchResults, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
            } else {
                val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
                AiAnalysisResponse(AiService.DEEPSEEK, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
            }
        } else {
            AiAnalysisResponse(AiService.DEEPSEEK, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
        }
    }

    private suspend fun analyzeWithMistral(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
        // Build messages with optional system prompt
        val messages = buildList {
            params?.systemPrompt?.let { systemPrompt ->
                if (systemPrompt.isNotBlank()) {
                    add(OpenAiMessage(role = "system", content = systemPrompt))
                }
            }
            add(OpenAiMessage(role = "user", content = prompt))
        }

        val request = MistralRequest(
            model = model,
            messages = messages,
            max_tokens = params?.maxTokens,
            temperature = params?.temperature,
            top_p = params?.topP,
            stop = params?.stopSequences?.takeIf { it.isNotEmpty() },
            random_seed = params?.seed
        )
        val response = mistralApi.createChatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )

        val headers = formatHeaders(response.headers())
        val statusCode = response.code()
        return if (response.isSuccessful) {
            val body = response.body()
            // Try multiple parsing strategies for content extraction
            val content = body?.choices?.let { choices ->
                choices.firstOrNull()?.message?.content
                    ?: choices.firstOrNull()?.message?.reasoning_content
                    ?: choices.firstNotNullOfOrNull { it.message?.content }
            }
            val searchResults = body?.search_results
            val rawUsageJson = formatUsageJson(body?.usage)
            val usage = body?.usage?.let {
                TokenUsage(
                    inputTokens = it.prompt_tokens ?: 0,
                    outputTokens = it.completion_tokens ?: 0
                )
            }
            if (content != null) {
                AiAnalysisResponse(AiService.MISTRAL, content, null, usage, searchResults = searchResults, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
            } else {
                val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
                AiAnalysisResponse(AiService.MISTRAL, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
            }
        } else {
            AiAnalysisResponse(AiService.MISTRAL, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
        }
    }

    private suspend fun analyzeWithPerplexity(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
        // Build messages with optional system prompt
        val messages = buildList {
            params?.systemPrompt?.let { systemPrompt ->
                if (systemPrompt.isNotBlank()) {
                    add(OpenAiMessage(role = "system", content = systemPrompt))
                }
            }
            add(OpenAiMessage(role = "user", content = prompt))
        }

        val request = PerplexityRequest(
            model = model,
            messages = messages,
            max_tokens = params?.maxTokens,
            temperature = params?.temperature,
            top_p = params?.topP,
            frequency_penalty = params?.frequencyPenalty,
            presence_penalty = params?.presencePenalty,
            return_citations = params?.returnCitations,
            search_recency_filter = params?.searchRecency
        )
        val response = perplexityApi.createChatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )

        val headers = formatHeaders(response.headers())
        val statusCode = response.code()
        return if (response.isSuccessful) {
            val body = response.body()
            // Try multiple parsing strategies for content extraction
            val content = body?.choices?.let { choices ->
                choices.firstOrNull()?.message?.content
                    ?: choices.firstOrNull()?.message?.reasoning_content
                    ?: choices.firstNotNullOfOrNull { it.message?.content }
            }
            val citations = body?.citations  // Extract citations from Perplexity response
            val searchResults = body?.search_results  // Extract search results
            val relatedQuestions = body?.related_questions  // Extract related questions
            val rawUsageJson = formatUsageJson(body?.usage)
            val usage = body?.usage?.let {
                TokenUsage(
                    inputTokens = it.prompt_tokens ?: 0,
                    outputTokens = it.completion_tokens ?: 0
                )
            }
            if (content != null) {
                AiAnalysisResponse(AiService.PERPLEXITY, content, null, usage, citations = citations, searchResults = searchResults, relatedQuestions = relatedQuestions, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
            } else {
                val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
                AiAnalysisResponse(AiService.PERPLEXITY, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
            }
        } else {
            AiAnalysisResponse(AiService.PERPLEXITY, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
        }
    }

    private suspend fun analyzeWithTogether(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
        // Build messages with optional system prompt
        val messages = buildList {
            params?.systemPrompt?.let { systemPrompt ->
                if (systemPrompt.isNotBlank()) {
                    add(OpenAiMessage(role = "system", content = systemPrompt))
                }
            }
            add(OpenAiMessage(role = "user", content = prompt))
        }

        val request = TogetherRequest(
            model = model,
            messages = messages,
            max_tokens = params?.maxTokens,
            temperature = params?.temperature,
            top_p = params?.topP,
            top_k = params?.topK,
            frequency_penalty = params?.frequencyPenalty,
            presence_penalty = params?.presencePenalty,
            stop = params?.stopSequences?.takeIf { it.isNotEmpty() }
        )
        val response = togetherApi.createChatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )

        val headers = formatHeaders(response.headers())
        val statusCode = response.code()
        return if (response.isSuccessful) {
            val body = response.body()
            // Try multiple parsing strategies for content extraction
            val content = body?.choices?.let { choices ->
                choices.firstOrNull()?.message?.content
                    ?: choices.firstOrNull()?.message?.reasoning_content
                    ?: choices.firstNotNullOfOrNull { it.message?.content }
            }
            val searchResults = body?.search_results
            val rawUsageJson = formatUsageJson(body?.usage)
            val usage = body?.usage?.let {
                TokenUsage(
                    inputTokens = it.prompt_tokens ?: 0,
                    outputTokens = it.completion_tokens ?: 0
                )
            }
            if (content != null) {
                AiAnalysisResponse(AiService.TOGETHER, content, null, usage, searchResults = searchResults, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
            } else {
                val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
                AiAnalysisResponse(AiService.TOGETHER, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
            }
        } else {
            AiAnalysisResponse(AiService.TOGETHER, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
        }
    }

    private suspend fun analyzeWithOpenRouter(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
        // Build messages with optional system prompt
        val messages = buildList {
            params?.systemPrompt?.let { systemPrompt ->
                if (systemPrompt.isNotBlank()) {
                    add(OpenAiMessage(role = "system", content = systemPrompt))
                }
            }
            add(OpenAiMessage(role = "user", content = prompt))
        }

        val request = OpenRouterRequest(
            model = model,
            messages = messages,
            max_tokens = params?.maxTokens,
            temperature = params?.temperature,
            top_p = params?.topP,
            top_k = params?.topK,
            frequency_penalty = params?.frequencyPenalty,
            presence_penalty = params?.presencePenalty,
            stop = params?.stopSequences?.takeIf { it.isNotEmpty() },
            seed = params?.seed
        )
        val response = openRouterApi.createChatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )

        val headers = formatHeaders(response.headers())
        val statusCode = response.code()
        return if (response.isSuccessful) {
            val body = response.body()
            // Try multiple parsing strategies for content extraction
            val content = body?.choices?.let { choices ->
                choices.firstOrNull()?.message?.content
                    ?: choices.firstOrNull()?.message?.reasoning_content
                    ?: choices.firstNotNullOfOrNull { it.message?.content }
            }
            val searchResults = body?.search_results
            val rawUsageJson = formatUsageJson(body?.usage)
            val usage = body?.usage?.let {
                TokenUsage(
                    inputTokens = it.prompt_tokens ?: 0,
                    outputTokens = it.completion_tokens ?: 0
                )
            }
            if (content != null) {
                AiAnalysisResponse(AiService.OPENROUTER, content, null, usage, searchResults = searchResults, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
            } else {
                val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
                AiAnalysisResponse(AiService.OPENROUTER, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
            }
        } else {
            AiAnalysisResponse(AiService.OPENROUTER, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
        }
    }

    private suspend fun analyzeWithSiliconFlow(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
        // Build messages with optional system prompt
        val messages = buildList {
            params?.systemPrompt?.let { systemPrompt ->
                if (systemPrompt.isNotBlank()) {
                    add(OpenAiMessage(role = "system", content = systemPrompt))
                }
            }
            add(OpenAiMessage(role = "user", content = prompt))
        }

        val request = SiliconFlowRequest(
            model = model,
            messages = messages,
            max_tokens = params?.maxTokens,
            temperature = params?.temperature,
            top_p = params?.topP,
            top_k = params?.topK,
            frequency_penalty = params?.frequencyPenalty,
            presence_penalty = params?.presencePenalty,
            stop = params?.stopSequences?.takeIf { it.isNotEmpty() }
        )
        val response = siliconFlowApi.createChatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )

        val headers = formatHeaders(response.headers())
        val statusCode = response.code()
        return if (response.isSuccessful) {
            val body = response.body()
            // Try multiple parsing strategies for content extraction
            val content = body?.choices?.let { choices ->
                choices.firstOrNull()?.message?.content
                    ?: choices.firstOrNull()?.message?.reasoning_content
                    ?: choices.firstNotNullOfOrNull { it.message?.content }
            }
            val rawUsageJson = formatUsageJson(body?.usage)
            val usage = body?.usage?.let {
                TokenUsage(
                    inputTokens = it.prompt_tokens ?: 0,
                    outputTokens = it.completion_tokens ?: 0
                )
            }
            if (content != null) {
                AiAnalysisResponse(AiService.SILICONFLOW, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
            } else {
                val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
                AiAnalysisResponse(AiService.SILICONFLOW, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
            }
        } else {
            AiAnalysisResponse(AiService.SILICONFLOW, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
        }
    }

    private suspend fun analyzeWithZai(apiKey: String, prompt: String, model: String, params: AiAgentParameters? = null): AiAnalysisResponse {
        val api = AiApiFactory.createZaiApi()

        // Build messages with optional system prompt
        val messages = buildList {
            params?.systemPrompt?.let { systemPrompt ->
                if (systemPrompt.isNotBlank()) {
                    add(OpenAiMessage(role = "system", content = systemPrompt))
                }
            }
            add(OpenAiMessage(role = "user", content = prompt))
        }

        val request = OpenAiRequest(
            model = model,
            messages = messages,
            max_tokens = params?.maxTokens,
            temperature = params?.temperature,
            top_p = params?.topP,
            frequency_penalty = params?.frequencyPenalty,
            presence_penalty = params?.presencePenalty,
            stop = params?.stopSequences?.takeIf { it.isNotEmpty() }
        )

        val response = api.createChatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )

        val headers = formatHeaders(response.headers())
        val statusCode = response.code()
        return if (response.isSuccessful) {
            val body = response.body()
            // Try multiple parsing strategies for content extraction
            val content = body?.choices?.let { choices ->
                choices.firstOrNull()?.message?.content
                    ?: choices.firstOrNull()?.message?.reasoning_content
                    ?: choices.firstNotNullOfOrNull { it.message?.content }
            }
            val rawUsageJson = formatUsageJson(body?.usage)
            val usage = body?.usage?.let {
                TokenUsage(
                    inputTokens = it.prompt_tokens ?: 0,
                    outputTokens = it.completion_tokens ?: 0
                )
            }
            if (content != null) {
                AiAnalysisResponse(AiService.ZAI, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
            } else {
                val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
                AiAnalysisResponse(AiService.ZAI, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
            }
        } else {
            AiAnalysisResponse(AiService.ZAI, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
        }
    }

    private suspend fun analyzeWithDummy(
        apiKey: String,
        prompt: String,
        model: String = "abc",
        parameters: AiAgentParameters? = null
    ): AiAnalysisResponse {
        // Ensure Dummy server is running
        DummyApiServer.start()
        val api = AiApiFactory.createDummyApi()

        val systemPrompt = parameters?.systemPrompt
        val messages = if (systemPrompt != null) {
            listOf(
                OpenAiMessage("system", systemPrompt),
                OpenAiMessage("user", prompt)
            )
        } else {
            listOf(OpenAiMessage("user", prompt))
        }

        val request = OpenAiRequest(
            model = model,
            messages = messages,
            max_tokens = parameters?.maxTokens,
            temperature = parameters?.temperature,
            top_p = parameters?.topP
        )

        return try {
            val response = api.createChatCompletion("Bearer $apiKey", request)
            val statusCode = response.code()

            if (response.isSuccessful) {
                val body = response.body()
                // Try multiple parsing strategies for content extraction
                val content = body?.choices?.let { choices ->
                    choices.firstOrNull()?.message?.content
                        ?: choices.firstOrNull()?.message?.reasoning_content
                        ?: choices.firstNotNullOfOrNull { it.message?.content }
                }
                val usage = body?.usage?.let {
                    TokenUsage(
                        inputTokens = it.prompt_tokens ?: 0,
                        outputTokens = it.completion_tokens ?: 0
                    )
                }

                val rawUsage = body?.usage?.let {
                    GsonBuilder().setPrettyPrinting().create().toJson(it)
                }
                val headers = formatHeaders(response.headers())

                if (content != null) {
                    AiAnalysisResponse(
                        service = AiService.DUMMY,
                        analysis = content,
                        error = null,
                        tokenUsage = usage,
                        rawUsageJson = rawUsage,
                        httpHeaders = headers,
                        httpStatusCode = statusCode
                    )
                } else {
                    val errorMsg = body?.error?.message ?: "No response content (choices: ${body?.choices?.size ?: 0})"
                    AiAnalysisResponse(AiService.DUMMY, null, errorMsg, httpHeaders = headers, httpStatusCode = statusCode)
                }
            } else {
                val errorBody = response.errorBody()?.string() ?: "Unknown error"
                AiAnalysisResponse(AiService.DUMMY, null, "Dummy API error: $errorBody", httpStatusCode = statusCode)
            }
        } catch (e: Exception) {
            AiAnalysisResponse(AiService.DUMMY, null, "Dummy API error: ${e.message}")
        }
    }

    /**
     * Fetch available Dummy models.
     */
    suspend fun fetchDummyModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        // Ensure Dummy server is running
        DummyApiServer.start()
        try {
            val api = AiApiFactory.createDummyApi()
            val response = api.listModels("Bearer $apiKey")

            if (response.isSuccessful) {
                response.body()?.data?.mapNotNull { it.id } ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("DummyAPI", "Failed to fetch models: ${e.message}")
            emptyList()
        }
    }

    /**
     * Test if a model is accessible with the given API key.
     * Makes a minimal API call to verify the configuration works.
     * @return null if successful, error message if failed
     */
    suspend fun testModel(
        service: AiService,
        apiKey: String,
        model: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val response = when (service) {
                AiService.OPENAI -> analyzeWithChatGpt(apiKey, TEST_PROMPT, model)
                AiService.ANTHROPIC -> analyzeWithClaude(apiKey, TEST_PROMPT, model)
                AiService.GOOGLE -> analyzeWithGemini(apiKey, TEST_PROMPT, model)
                AiService.XAI -> analyzeWithGrok(apiKey, TEST_PROMPT, model)
                AiService.GROQ -> analyzeWithGroq(apiKey, TEST_PROMPT, model)
                AiService.DEEPSEEK -> analyzeWithDeepSeek(apiKey, TEST_PROMPT, model)
                AiService.MISTRAL -> analyzeWithMistral(apiKey, TEST_PROMPT, model)
                AiService.PERPLEXITY -> analyzeWithPerplexity(apiKey, TEST_PROMPT, model)
                AiService.TOGETHER -> analyzeWithTogether(apiKey, TEST_PROMPT, model)
                AiService.OPENROUTER -> analyzeWithOpenRouter(apiKey, TEST_PROMPT, model)
                AiService.SILICONFLOW -> analyzeWithSiliconFlow(apiKey, TEST_PROMPT, model)
                AiService.ZAI -> analyzeWithZai(apiKey, TEST_PROMPT, model)
                AiService.DUMMY -> analyzeWithDummy(apiKey, TEST_PROMPT, model)
            }

            if (response.isSuccess) {
                null // Success
            } else {
                response.error ?: "Unknown error"
            }
        } catch (e: Exception) {
            e.message ?: "Connection error"
        }
    }

    /**
     * Fetch available Gemini models that support generateContent.
     */
    suspend fun fetchGeminiModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val response = geminiApi.listModels(apiKey)
            if (response.isSuccessful) {
                val models = response.body()?.models ?: emptyList()
                // Filter models that support generateContent and return their names without "models/" prefix
                models
                    .filter { model ->
                        model.supportedGenerationMethods?.contains("generateContent") == true
                    }
                    .mapNotNull { model ->
                        model.name?.removePrefix("models/")
                    }
                    .sorted()
            } else {
                android.util.Log.e("GeminiAPI", "Failed to fetch models: ${response.code()}")
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("GeminiAPI", "Error fetching models: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetch available Grok models.
     */
    suspend fun fetchGrokModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val response = grokApi.listModels("Bearer $apiKey")
            if (response.isSuccessful) {
                val models = response.body()?.data ?: emptyList()
                models
                    .mapNotNull { it.id }
                    .filter { it.startsWith("grok") }  // Only include grok models
                    .sorted()
            } else {
                android.util.Log.e("GrokAPI", "Failed to fetch models: ${response.code()}")
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("GrokAPI", "Error fetching models: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetch available Groq models.
     */
    suspend fun fetchGroqModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val response = groqApi.listModels("Bearer $apiKey")
            if (response.isSuccessful) {
                val models = response.body()?.data ?: emptyList()
                models
                    .mapNotNull { it.id }
                    .sorted()
            } else {
                android.util.Log.e("GroqAPI", "Failed to fetch models: ${response.code()}")
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("GroqAPI", "Error fetching models: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetch available ChatGPT/OpenAI models.
     */
    suspend fun fetchChatGptModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val response = openAiApi.listModels("Bearer $apiKey")
            if (response.isSuccessful) {
                val models = response.body()?.data ?: emptyList()
                models
                    .mapNotNull { it.id }
                    .filter { it.startsWith("gpt") }  // Only include GPT models
                    .sorted()
            } else {
                android.util.Log.e("ChatGptAPI", "Failed to fetch models: ${response.code()}")
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatGptAPI", "Error fetching models: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetch available DeepSeek models.
     */
    suspend fun fetchDeepSeekModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val response = deepSeekApi.listModels("Bearer $apiKey")
            if (response.isSuccessful) {
                val models = response.body()?.data ?: emptyList()
                models
                    .mapNotNull { it.id }
                    .filter { it.startsWith("deepseek") }  // Only include DeepSeek models
                    .sorted()
            } else {
                android.util.Log.e("DeepSeekAPI", "Failed to fetch models: ${response.code()}")
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("DeepSeekAPI", "Error fetching models: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetch available Mistral models.
     */
    suspend fun fetchMistralModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val response = mistralApi.listModels("Bearer $apiKey")
            if (response.isSuccessful) {
                val models = response.body()?.data ?: emptyList()
                models
                    .mapNotNull { it.id }
                    .filter { it.startsWith("mistral") || it.startsWith("open-mistral") || it.startsWith("codestral") || it.startsWith("pixtral") }
                    .sorted()
            } else {
                android.util.Log.e("MistralAPI", "Failed to fetch models: ${response.code()}")
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("MistralAPI", "Error fetching models: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetch available Perplexity models.
     */
    suspend fun fetchPerplexityModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val response = perplexityApi.listModels("Bearer $apiKey")
            if (response.isSuccessful) {
                val models = response.body()?.data ?: emptyList()
                models
                    .mapNotNull { it.id }
                    .filter { it.contains("sonar") || it.contains("llama") }
                    .sorted()
            } else {
                android.util.Log.e("PerplexityAPI", "Failed to fetch models: ${response.code()}")
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("PerplexityAPI", "Error fetching models: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetch available Together AI models.
     * Note: Together API returns a raw array, not {"data": [...]}
     */
    suspend fun fetchTogetherModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val response = togetherApi.listModels("Bearer $apiKey")
            if (response.isSuccessful) {
                val models = response.body() ?: emptyList()
                models
                    .mapNotNull { it.id }
                    .filter { it.contains("chat") || it.contains("instruct", ignoreCase = true) || it.contains("llama", ignoreCase = true) }
                    .sorted()
            } else {
                android.util.Log.e("TogetherAPI", "Failed to fetch models: ${response.code()}")
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("TogetherAPI", "Error fetching models: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetch available OpenRouter models.
     */
    suspend fun fetchOpenRouterModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val response = openRouterApi.listModels("Bearer $apiKey")
            if (response.isSuccessful) {
                val models = response.body()?.data ?: emptyList()
                models
                    .mapNotNull { it.id }
                    .sorted()
            } else {
                android.util.Log.e("OpenRouterAPI", "Failed to fetch models: ${response.code()}")
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("OpenRouterAPI", "Error fetching models: ${e.message}")
            emptyList()
        }
    }

    /**
     * Send a chat message with conversation history.
     * Returns the assistant's response message.
     */
    suspend fun sendChatMessage(
        service: AiService,
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): String = withContext(Dispatchers.IO) {
        when (service) {
            AiService.OPENAI -> sendChatMessageChatGpt(apiKey, model, messages, params)
            AiService.ANTHROPIC -> sendChatMessageClaude(apiKey, model, messages, params)
            AiService.GOOGLE -> sendChatMessageGemini(apiKey, model, messages, params)
            AiService.XAI -> sendChatMessageGrok(apiKey, model, messages, params)
            AiService.GROQ -> sendChatMessageGroq(apiKey, model, messages, params)
            AiService.DEEPSEEK -> sendChatMessageDeepSeek(apiKey, model, messages, params)
            AiService.MISTRAL -> sendChatMessageMistral(apiKey, model, messages, params)
            AiService.PERPLEXITY -> sendChatMessagePerplexity(apiKey, model, messages, params)
            AiService.TOGETHER -> sendChatMessageTogether(apiKey, model, messages, params)
            AiService.OPENROUTER -> sendChatMessageOpenRouter(apiKey, model, messages, params)
            AiService.SILICONFLOW -> sendChatMessageSiliconFlow(apiKey, model, messages, params)
            AiService.ZAI -> sendChatMessageZai(apiKey, model, messages, params)
            AiService.DUMMY -> sendChatMessageDummy(apiKey, model, messages, params)
        }
    }

    private fun convertToOpenAiMessages(messages: List<com.ai.ui.ChatMessage>): List<OpenAiMessage> {
        return messages.map { msg -> OpenAiMessage(role = msg.role, content = msg.content) }
    }

    private suspend fun sendChatMessageChatGpt(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): String {
        val openAiMessages = convertToOpenAiMessages(messages)
        val request = OpenAiRequest(
            model = model,
            messages = openAiMessages,
            max_tokens = params.maxTokens,
            temperature = params.temperature,
            top_p = params.topP,
            frequency_penalty = params.frequencyPenalty,
            presence_penalty = params.presencePenalty
        )
        val response = openAiApi.createChatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )
        if (response.isSuccessful) {
            val content = response.body()?.choices?.firstOrNull()?.message?.content
            return content ?: throw Exception("No response content")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private suspend fun sendChatMessageGrok(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): String {
        val openAiMessages = convertToOpenAiMessages(messages)
        val request = GrokRequest(
            model = model,
            messages = openAiMessages,
            max_tokens = params.maxTokens,
            temperature = params.temperature,
            top_p = params.topP,
            frequency_penalty = params.frequencyPenalty,
            presence_penalty = params.presencePenalty
        )
        val response = grokApi.createChatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )
        if (response.isSuccessful) {
            val content = response.body()?.choices?.firstOrNull()?.message?.content
            return content ?: throw Exception("No response content")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private suspend fun sendChatMessageGroq(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): String {
        val openAiMessages = convertToOpenAiMessages(messages)
        val request = GroqRequest(
            model = model,
            messages = openAiMessages,
            max_tokens = params.maxTokens,
            temperature = params.temperature,
            top_p = params.topP,
            frequency_penalty = params.frequencyPenalty,
            presence_penalty = params.presencePenalty
        )
        val response = groqApi.createChatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )
        if (response.isSuccessful) {
            val content = response.body()?.choices?.firstOrNull()?.message?.content
            return content ?: throw Exception("No response content")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private suspend fun sendChatMessageDeepSeek(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): String {
        val openAiMessages = convertToOpenAiMessages(messages)
        val request = DeepSeekRequest(
            model = model,
            messages = openAiMessages,
            max_tokens = params.maxTokens,
            temperature = params.temperature,
            top_p = params.topP,
            frequency_penalty = params.frequencyPenalty,
            presence_penalty = params.presencePenalty
        )
        val response = deepSeekApi.createChatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )
        if (response.isSuccessful) {
            val content = response.body()?.choices?.firstOrNull()?.message?.content
            return content ?: throw Exception("No response content")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private suspend fun sendChatMessageMistral(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): String {
        val openAiMessages = convertToOpenAiMessages(messages)
        val request = MistralRequest(
            model = model,
            messages = openAiMessages,
            max_tokens = params.maxTokens,
            temperature = params.temperature,
            top_p = params.topP
        )
        val response = mistralApi.createChatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )
        if (response.isSuccessful) {
            val content = response.body()?.choices?.firstOrNull()?.message?.content
            return content ?: throw Exception("No response content")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private suspend fun sendChatMessagePerplexity(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): String {
        val openAiMessages = convertToOpenAiMessages(messages)
        val request = PerplexityRequest(
            model = model,
            messages = openAiMessages,
            max_tokens = params.maxTokens,
            temperature = params.temperature,
            top_p = params.topP,
            frequency_penalty = params.frequencyPenalty,
            presence_penalty = params.presencePenalty
        )
        val response = perplexityApi.createChatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )
        if (response.isSuccessful) {
            val content = response.body()?.choices?.firstOrNull()?.message?.content
            return content ?: throw Exception("No response content")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private suspend fun sendChatMessageTogether(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): String {
        val openAiMessages = convertToOpenAiMessages(messages)
        val request = TogetherRequest(
            model = model,
            messages = openAiMessages,
            max_tokens = params.maxTokens,
            temperature = params.temperature,
            top_p = params.topP,
            top_k = params.topK,
            frequency_penalty = params.frequencyPenalty,
            presence_penalty = params.presencePenalty
        )
        val response = togetherApi.createChatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )
        if (response.isSuccessful) {
            val content = response.body()?.choices?.firstOrNull()?.message?.content
            return content ?: throw Exception("No response content")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private suspend fun sendChatMessageOpenRouter(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): String {
        val openAiMessages = convertToOpenAiMessages(messages)
        val request = OpenRouterRequest(
            model = model,
            messages = openAiMessages,
            max_tokens = params.maxTokens,
            temperature = params.temperature,
            top_p = params.topP,
            top_k = params.topK,
            frequency_penalty = params.frequencyPenalty,
            presence_penalty = params.presencePenalty
        )
        val response = openRouterApi.createChatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )
        if (response.isSuccessful) {
            val content = response.body()?.choices?.firstOrNull()?.message?.content
            return content ?: throw Exception("No response content")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private suspend fun sendChatMessageSiliconFlow(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): String {
        val openAiMessages = convertToOpenAiMessages(messages)
        val request = SiliconFlowRequest(
            model = model,
            messages = openAiMessages,
            max_tokens = params.maxTokens,
            temperature = params.temperature,
            top_p = params.topP,
            top_k = params.topK,
            frequency_penalty = params.frequencyPenalty,
            presence_penalty = params.presencePenalty
        )
        val response = siliconFlowApi.createChatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )
        if (response.isSuccessful) {
            val content = response.body()?.choices?.firstOrNull()?.message?.content
            return content ?: throw Exception("No response content")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private suspend fun sendChatMessageZai(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): String {
        val openAiMessages = convertToOpenAiMessages(messages)
        val request = OpenAiRequest(
            model = model,
            messages = openAiMessages,
            max_tokens = params.maxTokens,
            temperature = params.temperature,
            top_p = params.topP,
            frequency_penalty = params.frequencyPenalty,
            presence_penalty = params.presencePenalty
        )
        val response = zaiApi.createChatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )
        if (response.isSuccessful) {
            val content = response.body()?.choices?.firstOrNull()?.message?.content
            return content ?: throw Exception("No response content")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private suspend fun sendChatMessageDummy(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): String {
        val openAiMessages = convertToOpenAiMessages(messages)
        val request = OpenAiRequest(
            model = model,
            messages = openAiMessages,
            max_tokens = params.maxTokens,
            temperature = params.temperature,
            top_p = params.topP,
            frequency_penalty = params.frequencyPenalty,
            presence_penalty = params.presencePenalty
        )
        val response = dummyApi.createChatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )
        if (response.isSuccessful) {
            val content = response.body()?.choices?.firstOrNull()?.message?.content
            return content ?: throw Exception("No response content")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private suspend fun sendChatMessageClaude(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): String {
        // Filter out system message (passed separately) and convert to Claude format
        val claudeMessages = messages
            .filter { it.role != "system" }
            .map { msg -> ClaudeMessage(role = msg.role, content = msg.content) }
        val systemPrompt = messages.find { it.role == "system" }?.content

        val request = ClaudeRequest(
            model = model,
            messages = claudeMessages,
            max_tokens = params.maxTokens ?: 4096,
            temperature = params.temperature,
            top_p = params.topP,
            top_k = params.topK,
            system = systemPrompt
        )
        val response = claudeApi.createMessage(apiKey = apiKey, request = request)
        if (response.isSuccessful) {
            val content = response.body()?.content?.firstOrNull { it.type == "text" }?.text
            return content ?: throw Exception("No response content")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private suspend fun sendChatMessageGemini(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): String {
        // Convert messages to Gemini format (alternating user/model roles)
        val contents = messages
            .filter { it.role != "system" }
            .map { msg ->
                GeminiContent(
                    parts = listOf(GeminiPart(text = msg.content)),
                    role = if (msg.role == "user") "user" else "model"
                )
            }
        val systemInstruction = messages.find { it.role == "system" }?.let {
            GeminiContent(parts = listOf(GeminiPart(text = it.content)))
        }

        val request = GeminiRequest(
            contents = contents,
            generationConfig = GeminiGenerationConfig(
                temperature = params.temperature,
                maxOutputTokens = params.maxTokens,
                topP = params.topP,
                topK = params.topK
            ),
            systemInstruction = systemInstruction
        )
        val response = geminiApi.generateContent(model, apiKey, request)
        if (response.isSuccessful) {
            val content = response.body()?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            return content ?: throw Exception("No response content")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    // ========== Streaming Chat Methods ==========

    /**
     * Send a chat message with streaming response.
     * Returns a Flow that emits content chunks as they arrive.
     */
    fun sendChatMessageStream(
        service: AiService,
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): Flow<String> = flow {
        when (service) {
            AiService.OPENAI -> streamChatOpenAi(apiKey, model, messages, params).collect { emit(it) }
            AiService.ANTHROPIC -> streamChatClaude(apiKey, model, messages, params).collect { emit(it) }
            AiService.GOOGLE -> streamChatGemini(apiKey, model, messages, params).collect { emit(it) }
            AiService.XAI -> streamChatGrok(apiKey, model, messages, params).collect { emit(it) }
            AiService.GROQ -> streamChatGroq(apiKey, model, messages, params).collect { emit(it) }
            AiService.DEEPSEEK -> streamChatDeepSeek(apiKey, model, messages, params).collect { emit(it) }
            AiService.MISTRAL -> streamChatMistral(apiKey, model, messages, params).collect { emit(it) }
            AiService.PERPLEXITY -> streamChatPerplexity(apiKey, model, messages, params).collect { emit(it) }
            AiService.TOGETHER -> streamChatTogether(apiKey, model, messages, params).collect { emit(it) }
            AiService.OPENROUTER -> streamChatOpenRouter(apiKey, model, messages, params).collect { emit(it) }
            AiService.SILICONFLOW -> streamChatSiliconFlow(apiKey, model, messages, params).collect { emit(it) }
            AiService.ZAI -> streamChatZai(apiKey, model, messages, params).collect { emit(it) }
            AiService.DUMMY -> streamChatDummy(apiKey, model, messages, params).collect { emit(it) }
        }
    }

    /**
     * Parse SSE (Server-Sent Events) stream for OpenAI-compatible format.
     * Format: data: {"choices":[{"delta":{"content":"..."}}]}
     */
    private fun parseOpenAiSseStream(responseBody: ResponseBody): Flow<String> = flow {
        val reader = responseBody.charStream().buffered()
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue

                // Skip empty lines and comments
                if (currentLine.isBlank() || currentLine.startsWith(":")) continue

                // Parse SSE data lines
                if (currentLine.startsWith("data: ")) {
                    val data = currentLine.removePrefix("data: ").trim()

                    // Check for stream termination
                    if (data == "[DONE]") break

                    try {
                        val chunk = gson.fromJson(data, OpenAiStreamChunk::class.java)
                        val delta = chunk?.choices?.firstOrNull()?.delta

                        // Emit content or reasoning_content (for DeepSeek)
                        delta?.content?.let { if (it.isNotEmpty()) emit(it) }
                        delta?.reasoning_content?.let { if (it.isNotEmpty()) emit(it) }
                    } catch (e: Exception) {
                        android.util.Log.w("StreamParse", "Failed to parse chunk: $data")
                    }
                }
            }
        } finally {
            reader.close()
            responseBody.close()
        }
    }

    /**
     * Parse SSE stream for Anthropic Claude format.
     * Events: content_block_delta with delta.text
     */
    private fun parseClaudeSseStream(responseBody: ResponseBody): Flow<String> = flow {
        val reader = responseBody.charStream().buffered()
        try {
            var line: String?
            var eventType: String? = null

            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue

                // Skip empty lines
                if (currentLine.isBlank()) {
                    eventType = null
                    continue
                }

                // Parse event type
                if (currentLine.startsWith("event: ")) {
                    eventType = currentLine.removePrefix("event: ").trim()
                    continue
                }

                // Parse data lines
                if (currentLine.startsWith("data: ")) {
                    val data = currentLine.removePrefix("data: ").trim()

                    // Only process content_block_delta events
                    if (eventType == "content_block_delta") {
                        try {
                            val event = gson.fromJson(data, ClaudeStreamEvent::class.java)
                            event.delta?.text?.let { if (it.isNotEmpty()) emit(it) }
                        } catch (e: Exception) {
                            android.util.Log.w("StreamParse", "Failed to parse Claude chunk: $data")
                        }
                    }

                    // Check for message_stop
                    if (eventType == "message_stop") break
                }
            }
        } finally {
            reader.close()
            responseBody.close()
        }
    }

    /**
     * Parse SSE stream for Google Gemini format.
     * Format: data: {"candidates":[{"content":{"parts":[{"text":"..."}]}}]}
     */
    private fun parseGeminiSseStream(responseBody: ResponseBody): Flow<String> = flow {
        val reader = responseBody.charStream().buffered()
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue

                // Skip empty lines
                if (currentLine.isBlank()) continue

                // Parse data lines
                if (currentLine.startsWith("data: ")) {
                    val data = currentLine.removePrefix("data: ").trim()

                    try {
                        val chunk = gson.fromJson(data, GeminiStreamChunk::class.java)
                        val text = chunk?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                        text?.let { if (it.isNotEmpty()) emit(it) }
                    } catch (e: Exception) {
                        android.util.Log.w("StreamParse", "Failed to parse Gemini chunk: $data")
                    }
                }
            }
        } finally {
            reader.close()
            responseBody.close()
        }
    }

    // ========== Provider-Specific Streaming Methods ==========

    private fun streamChatOpenAi(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): Flow<String> = flow {
        val openAiMessages = convertToOpenAiMessages(messages)
        val request = OpenAiStreamRequest(
            model = model,
            messages = openAiMessages,
            stream = true,
            max_tokens = params.maxTokens,
            temperature = params.temperature,
            top_p = params.topP,
            frequency_penalty = params.frequencyPenalty,
            presence_penalty = params.presencePenalty
        )

        val response = withContext(Dispatchers.IO) {
            openAiStreamApi.createChatCompletionStream("Bearer $apiKey", request)
        }

        if (response.isSuccessful) {
            response.body()?.let { body ->
                parseOpenAiSseStream(body).collect { emit(it) }
            } ?: throw Exception("Empty response body")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private fun streamChatClaude(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): Flow<String> = flow {
        val claudeMessages = messages
            .filter { it.role != "system" }
            .map { msg -> ClaudeMessage(role = msg.role, content = msg.content) }
        val systemPrompt = messages.find { it.role == "system" }?.content

        val request = ClaudeStreamRequest(
            model = model,
            messages = claudeMessages,
            stream = true,
            max_tokens = params.maxTokens ?: 4096,
            temperature = params.temperature,
            top_p = params.topP,
            top_k = params.topK,
            system = systemPrompt
        )

        val response = withContext(Dispatchers.IO) {
            claudeStreamApi.createMessageStream(apiKey, request = request)
        }

        if (response.isSuccessful) {
            response.body()?.let { body ->
                parseClaudeSseStream(body).collect { emit(it) }
            } ?: throw Exception("Empty response body")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private fun streamChatGemini(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): Flow<String> = flow {
        val contents = messages
            .filter { it.role != "system" }
            .map { msg ->
                GeminiContent(
                    parts = listOf(GeminiPart(text = msg.content)),
                    role = if (msg.role == "user") "user" else "model"
                )
            }
        val systemInstruction = messages.find { it.role == "system" }?.let {
            GeminiContent(parts = listOf(GeminiPart(text = it.content)))
        }

        val request = GeminiRequest(
            contents = contents,
            generationConfig = GeminiGenerationConfig(
                temperature = params.temperature,
                maxOutputTokens = params.maxTokens,
                topP = params.topP,
                topK = params.topK
            ),
            systemInstruction = systemInstruction
        )

        val response = withContext(Dispatchers.IO) {
            geminiStreamApi.streamGenerateContent(model, apiKey, request = request)
        }

        if (response.isSuccessful) {
            response.body()?.let { body ->
                parseGeminiSseStream(body).collect { emit(it) }
            } ?: throw Exception("Empty response body")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private fun streamChatGrok(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): Flow<String> = flow {
        val openAiMessages = convertToOpenAiMessages(messages)
        val request = OpenAiStreamRequest(
            model = model,
            messages = openAiMessages,
            stream = true,
            max_tokens = params.maxTokens,
            temperature = params.temperature,
            top_p = params.topP,
            frequency_penalty = params.frequencyPenalty,
            presence_penalty = params.presencePenalty
        )

        val response = withContext(Dispatchers.IO) {
            grokStreamApi.createChatCompletionStream("Bearer $apiKey", request)
        }

        if (response.isSuccessful) {
            response.body()?.let { body ->
                parseOpenAiSseStream(body).collect { emit(it) }
            } ?: throw Exception("Empty response body")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private fun streamChatGroq(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): Flow<String> = flow {
        val openAiMessages = convertToOpenAiMessages(messages)
        val request = OpenAiStreamRequest(
            model = model,
            messages = openAiMessages,
            stream = true,
            max_tokens = params.maxTokens,
            temperature = params.temperature,
            top_p = params.topP,
            frequency_penalty = params.frequencyPenalty,
            presence_penalty = params.presencePenalty
        )

        val response = withContext(Dispatchers.IO) {
            groqStreamApi.createChatCompletionStream("Bearer $apiKey", request)
        }

        if (response.isSuccessful) {
            response.body()?.let { body ->
                parseOpenAiSseStream(body).collect { emit(it) }
            } ?: throw Exception("Empty response body")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private fun streamChatDeepSeek(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): Flow<String> = flow {
        val openAiMessages = convertToOpenAiMessages(messages)
        val request = OpenAiStreamRequest(
            model = model,
            messages = openAiMessages,
            stream = true,
            max_tokens = params.maxTokens,
            temperature = params.temperature,
            top_p = params.topP,
            frequency_penalty = params.frequencyPenalty,
            presence_penalty = params.presencePenalty
        )

        val response = withContext(Dispatchers.IO) {
            deepSeekStreamApi.createChatCompletionStream("Bearer $apiKey", request)
        }

        if (response.isSuccessful) {
            response.body()?.let { body ->
                parseOpenAiSseStream(body).collect { emit(it) }
            } ?: throw Exception("Empty response body")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private fun streamChatMistral(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): Flow<String> = flow {
        val openAiMessages = convertToOpenAiMessages(messages)
        val request = OpenAiStreamRequest(
            model = model,
            messages = openAiMessages,
            stream = true,
            max_tokens = params.maxTokens,
            temperature = params.temperature,
            top_p = params.topP
        )

        val response = withContext(Dispatchers.IO) {
            mistralStreamApi.createChatCompletionStream("Bearer $apiKey", request)
        }

        if (response.isSuccessful) {
            response.body()?.let { body ->
                parseOpenAiSseStream(body).collect { emit(it) }
            } ?: throw Exception("Empty response body")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private fun streamChatPerplexity(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): Flow<String> = flow {
        val openAiMessages = convertToOpenAiMessages(messages)
        val request = OpenAiStreamRequest(
            model = model,
            messages = openAiMessages,
            stream = true,
            max_tokens = params.maxTokens,
            temperature = params.temperature,
            top_p = params.topP,
            frequency_penalty = params.frequencyPenalty,
            presence_penalty = params.presencePenalty
        )

        val response = withContext(Dispatchers.IO) {
            perplexityStreamApi.createChatCompletionStream("Bearer $apiKey", request)
        }

        if (response.isSuccessful) {
            response.body()?.let { body ->
                parseOpenAiSseStream(body).collect { emit(it) }
            } ?: throw Exception("Empty response body")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private fun streamChatTogether(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): Flow<String> = flow {
        val openAiMessages = convertToOpenAiMessages(messages)
        val request = OpenAiStreamRequest(
            model = model,
            messages = openAiMessages,
            stream = true,
            max_tokens = params.maxTokens,
            temperature = params.temperature,
            top_p = params.topP,
            frequency_penalty = params.frequencyPenalty,
            presence_penalty = params.presencePenalty
        )

        val response = withContext(Dispatchers.IO) {
            togetherStreamApi.createChatCompletionStream("Bearer $apiKey", request)
        }

        if (response.isSuccessful) {
            response.body()?.let { body ->
                parseOpenAiSseStream(body).collect { emit(it) }
            } ?: throw Exception("Empty response body")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private fun streamChatOpenRouter(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): Flow<String> = flow {
        val openAiMessages = convertToOpenAiMessages(messages)
        val request = OpenAiStreamRequest(
            model = model,
            messages = openAiMessages,
            stream = true,
            max_tokens = params.maxTokens,
            temperature = params.temperature,
            top_p = params.topP,
            frequency_penalty = params.frequencyPenalty,
            presence_penalty = params.presencePenalty
        )

        val response = withContext(Dispatchers.IO) {
            openRouterStreamApi.createChatCompletionStream("Bearer $apiKey", request)
        }

        if (response.isSuccessful) {
            response.body()?.let { body ->
                parseOpenAiSseStream(body).collect { emit(it) }
            } ?: throw Exception("Empty response body")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private fun streamChatSiliconFlow(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): Flow<String> = flow {
        val openAiMessages = convertToOpenAiMessages(messages)
        val request = OpenAiStreamRequest(
            model = model,
            messages = openAiMessages,
            stream = true,
            max_tokens = params.maxTokens,
            temperature = params.temperature,
            top_p = params.topP,
            frequency_penalty = params.frequencyPenalty,
            presence_penalty = params.presencePenalty
        )

        val response = withContext(Dispatchers.IO) {
            siliconFlowStreamApi.createChatCompletionStream("Bearer $apiKey", request)
        }

        if (response.isSuccessful) {
            response.body()?.let { body ->
                parseOpenAiSseStream(body).collect { emit(it) }
            } ?: throw Exception("Empty response body")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private fun streamChatZai(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): Flow<String> = flow {
        val openAiMessages = convertToOpenAiMessages(messages)
        val request = OpenAiStreamRequest(
            model = model,
            messages = openAiMessages,
            stream = true,
            max_tokens = params.maxTokens,
            temperature = params.temperature,
            top_p = params.topP,
            frequency_penalty = params.frequencyPenalty,
            presence_penalty = params.presencePenalty
        )

        val response = withContext(Dispatchers.IO) {
            zaiStreamApi.createChatCompletionStream("Bearer $apiKey", request)
        }

        if (response.isSuccessful) {
            response.body()?.let { body ->
                parseOpenAiSseStream(body).collect { emit(it) }
            } ?: throw Exception("Empty response body")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

    private fun streamChatDummy(
        apiKey: String,
        model: String,
        messages: List<com.ai.ui.ChatMessage>,
        params: com.ai.ui.ChatParameters
    ): Flow<String> = flow {
        // Ensure Dummy server is running
        DummyApiServer.start()

        val openAiMessages = convertToOpenAiMessages(messages)
        val request = OpenAiStreamRequest(
            model = model,
            messages = openAiMessages,
            stream = true,
            max_tokens = params.maxTokens,
            temperature = params.temperature,
            top_p = params.topP,
            frequency_penalty = params.frequencyPenalty,
            presence_penalty = params.presencePenalty
        )

        val response = withContext(Dispatchers.IO) {
            dummyStreamApi.createChatCompletionStream("Bearer $apiKey", request)
        }

        if (response.isSuccessful) {
            response.body()?.let { body ->
                parseOpenAiSseStream(body).collect { emit(it) }
            } ?: throw Exception("Empty response body")
        } else {
            throw Exception("API error: ${response.code()} ${response.message()}")
        }
    }

}
