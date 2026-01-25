package com.ai.data

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Enum representing the supported AI services for chess position analysis.
 */
enum class AiService(val displayName: String, val baseUrl: String) {
    CHATGPT("ChatGPT", "https://api.openai.com/"),
    CLAUDE("Claude", "https://api.anthropic.com/"),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/"),
    GROK("Grok", "https://api.x.ai/"),
    GROQ("Groq", "https://api.groq.com/openai/"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/"),
    MISTRAL("Mistral", "https://api.mistral.ai/"),
    PERPLEXITY("Perplexity", "https://api.perplexity.ai/"),
    TOGETHER("Together", "https://api.together.xyz/"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/"),
    SILICONFLOW("SiliconFlow", "https://api.siliconflow.com/"),
    DUMMY("Dummy", "http://localhost:54321/")
}

// OpenAI / ChatGPT models
data class OpenAiMessage(
    val role: String,
    val content: String?,
    // DeepSeek reasoning models return reasoning in this field
    val reasoning_content: String? = null
)

data class OpenAiRequest(
    val model: String = "gpt-4o-mini",
    val messages: List<OpenAiMessage>,
    val max_tokens: Int? = 1024,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val stop: List<String>? = null,
    val seed: Int? = null,
    val response_format: OpenAiResponseFormat? = null
)

data class OpenAiResponseFormat(
    val type: String = "text"  // "text" or "json_object"
)

data class OpenAiChoice(
    val message: OpenAiMessage,
    val index: Int
)

data class OpenAiUsage(
    val prompt_tokens: Int?,
    val completion_tokens: Int?,
    val total_tokens: Int?
)

// Search result from AI services that perform web searches
data class SearchResult(
    val name: String?,      // Title of the search result
    val url: String?,       // URL of the result
    val snippet: String?    // Text snippet/description
)

data class OpenAiResponse(
    val id: String?,
    val choices: List<OpenAiChoice>?,
    val usage: OpenAiUsage?,
    val error: OpenAiError?,
    val citations: List<String>? = null,  // Perplexity returns citations as URLs
    val search_results: List<SearchResult>? = null,  // Some services return search results
    val related_questions: List<String>? = null  // Perplexity returns follow-up questions
)

data class OpenAiError(
    val message: String?,
    val type: String?
)

// OpenAI Responses API models (for GPT-5.x and newer models)
data class OpenAiResponsesRequest(
    val model: String,
    val input: String,
    val instructions: String? = null
)

data class OpenAiResponsesOutputContent(
    val type: String?,
    val text: String?,
    val annotations: List<Any>? = null
)

data class OpenAiResponsesOutputMessage(
    val type: String?,
    val id: String?,
    val status: String?,
    val role: String?,
    val content: List<OpenAiResponsesOutputContent>?
)

data class OpenAiResponsesApiResponse(
    val id: String?,
    val status: String?,
    val error: OpenAiResponsesError?,
    val output: List<OpenAiResponsesOutputMessage>?,
    val usage: OpenAiUsage?
)

data class OpenAiResponsesError(
    val message: String?,
    val type: String?,
    val code: String?
)

// Anthropic / Claude models
data class ClaudeMessage(
    val role: String,
    val content: String
)

data class ClaudeRequest(
    val model: String = "claude-sonnet-4-20250514",
    val max_tokens: Int? = 1024,
    val messages: List<ClaudeMessage>,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val system: String? = null,
    val stop_sequences: List<String>? = null
)

data class ClaudeContentBlock(
    val type: String,
    val text: String?
)

data class ClaudeUsage(
    val input_tokens: Int?,
    val output_tokens: Int?
)

data class ClaudeResponse(
    val id: String?,
    val content: List<ClaudeContentBlock>?,
    val usage: ClaudeUsage?,
    val error: ClaudeError?
)

data class ClaudeError(
    val type: String?,
    val message: String?
)

// Google / Gemini models
data class GeminiPart(
    val text: String
)

data class GeminiContent(
    val parts: List<GeminiPart>
)

data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null,
    val systemInstruction: GeminiContent? = null
)

data class GeminiGenerationConfig(
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val maxOutputTokens: Int? = null,
    val stopSequences: List<String>? = null
)

data class GeminiCandidate(
    val content: GeminiContent?
)

data class GeminiUsageMetadata(
    val promptTokenCount: Int?,
    val candidatesTokenCount: Int?,
    val totalTokenCount: Int?
)

data class GeminiResponse(
    val candidates: List<GeminiCandidate>?,
    val usageMetadata: GeminiUsageMetadata?,
    val error: GeminiError?
)

data class GeminiError(
    val code: Int?,
    val message: String?,
    val status: String?
)

// xAI / Grok models (uses OpenAI-compatible format)
data class GrokRequest(
    val model: String = "grok-3-mini",
    val messages: List<OpenAiMessage>,
    val max_tokens: Int? = 1024,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val stop: List<String>? = null,
    val search: Boolean? = null  // Enable web search
)

// DeepSeek models (uses OpenAI-compatible format)
data class DeepSeekRequest(
    val model: String = "deepseek-chat",
    val messages: List<OpenAiMessage>,
    val max_tokens: Int? = 1024,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val stop: List<String>? = null
)

// Mistral models (uses OpenAI-compatible format)
data class MistralRequest(
    val model: String = "mistral-small-latest",
    val messages: List<OpenAiMessage>,
    val max_tokens: Int? = 1024,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val stop: List<String>? = null,
    val random_seed: Int? = null
)

// Perplexity models (uses OpenAI-compatible format)
data class PerplexityRequest(
    val model: String = "sonar",
    val messages: List<OpenAiMessage>,
    val max_tokens: Int? = 1024,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val return_citations: Boolean? = null,
    val search_recency_filter: String? = null  // "day", "week", "month", "year"
)

// Together AI models (uses OpenAI-compatible format)
data class TogetherRequest(
    val model: String = "meta-llama/Llama-3.3-70B-Instruct-Turbo",
    val messages: List<OpenAiMessage>,
    val max_tokens: Int? = 1024,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val stop: List<String>? = null
)

// OpenRouter models (uses OpenAI-compatible format)
data class OpenRouterRequest(
    val model: String = "anthropic/claude-3.5-sonnet",
    val messages: List<OpenAiMessage>,
    val max_tokens: Int? = 1024,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val stop: List<String>? = null,
    val seed: Int? = null
)

// SiliconFlow models (uses OpenAI-compatible format)
data class SiliconFlowRequest(
    val model: String = "Qwen/Qwen2.5-7B-Instruct",
    val messages: List<OpenAiMessage>,
    val max_tokens: Int? = 1024,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val stop: List<String>? = null
)

// Groq models (uses OpenAI-compatible format)
data class GroqRequest(
    val model: String = "llama-3.3-70b-versatile",
    val messages: List<OpenAiMessage>,
    val max_tokens: Int? = 1024,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val stop: List<String>? = null,
    val seed: Int? = null
)

/**
 * Retrofit interface for OpenAI / ChatGPT API.
 * Uses Chat Completions API for older models (gpt-4o, etc.)
 * and Responses API for newer models (gpt-5.x, etc.)
 */
interface OpenAiApi {
    @POST("v1/chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiRequest
    ): Response<OpenAiResponse>

    @POST("v1/responses")
    suspend fun createResponse(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiResponsesRequest
    ): Response<OpenAiResponsesApiResponse>

    @retrofit2.http.GET("v1/models")
    suspend fun listModels(
        @Header("Authorization") authorization: String
    ): Response<OpenAiModelsResponse>
}

/**
 * Retrofit interface for Anthropic / Claude API.
 */
interface ClaudeApi {
    @POST("v1/messages")
    suspend fun createMessage(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        @Body request: ClaudeRequest
    ): Response<ClaudeResponse>
}

// Response for listing Gemini models
data class GeminiModelsResponse(
    val models: List<GeminiModel>?
)

data class GeminiModel(
    val name: String?,
    val displayName: String?,
    val supportedGenerationMethods: List<String>?
)

/**
 * Retrofit interface for Google / Gemini API.
 */
interface GeminiApi {
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): Response<GeminiResponse>

    @retrofit2.http.GET("v1beta/models")
    suspend fun listModels(
        @Query("key") apiKey: String
    ): Response<GeminiModelsResponse>
}

// Response for listing models (OpenAI-compatible format, used by Grok)
data class OpenAiModelsResponse(
    val data: List<OpenAiModel>?
)

data class OpenAiModel(
    val id: String?,
    val owned_by: String?
)

/**
 * Retrofit interface for xAI / Grok API (OpenAI-compatible).
 */
interface GrokApi {
    @POST("v1/chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: GrokRequest
    ): Response<OpenAiResponse>

    @retrofit2.http.GET("v1/models")
    suspend fun listModels(
        @Header("Authorization") authorization: String
    ): Response<OpenAiModelsResponse>
}

/**
 * Retrofit interface for DeepSeek API (OpenAI-compatible).
 */
interface DeepSeekApi {
    @POST("chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: DeepSeekRequest
    ): Response<OpenAiResponse>

    @retrofit2.http.GET("models")
    suspend fun listModels(
        @Header("Authorization") authorization: String
    ): Response<OpenAiModelsResponse>
}

/**
 * Retrofit interface for Mistral API (OpenAI-compatible).
 */
interface MistralApi {
    @POST("v1/chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: MistralRequest
    ): Response<OpenAiResponse>

    @retrofit2.http.GET("v1/models")
    suspend fun listModels(
        @Header("Authorization") authorization: String
    ): Response<OpenAiModelsResponse>
}

/**
 * Retrofit interface for Perplexity API (OpenAI-compatible).
 */
interface PerplexityApi {
    @POST("chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: PerplexityRequest
    ): Response<OpenAiResponse>

    @retrofit2.http.GET("models")
    suspend fun listModels(
        @Header("Authorization") authorization: String
    ): Response<OpenAiModelsResponse>
}

/**
 * Retrofit interface for Together AI API (OpenAI-compatible).
 * Note: Together's /v1/models endpoint returns a raw array, not {"data": [...]}
 */
interface TogetherApi {
    @POST("v1/chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: TogetherRequest
    ): Response<OpenAiResponse>

    @retrofit2.http.GET("v1/models")
    suspend fun listModels(
        @Header("Authorization") authorization: String
    ): Response<List<OpenAiModel>>
}

/**
 * Retrofit interface for OpenRouter API (OpenAI-compatible).
 */
interface OpenRouterApi {
    @POST("v1/chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: OpenRouterRequest
    ): Response<OpenAiResponse>

    @retrofit2.http.GET("v1/models")
    suspend fun listModels(
        @Header("Authorization") authorization: String
    ): Response<OpenAiModelsResponse>
}

/**
 * Retrofit interface for SiliconFlow API (OpenAI-compatible).
 */
interface SiliconFlowApi {
    @POST("v1/chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: SiliconFlowRequest
    ): Response<OpenAiResponse>
}

/**
 * Retrofit interface for Groq API (OpenAI-compatible).
 */
interface GroqApi {
    @POST("v1/chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: GroqRequest
    ): Response<OpenAiResponse>

    @retrofit2.http.GET("v1/models")
    suspend fun listModels(
        @Header("Authorization") authorization: String
    ): Response<OpenAiModelsResponse>
}

/**
 * Factory for creating API instances.
 */
object AiApiFactory {
    private val retrofitCache = ConcurrentHashMap<String, Retrofit>()

    // OkHttpClient with extended timeouts for AI API calls
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(TracingInterceptor())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(420, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun getRetrofit(baseUrl: String): Retrofit {
        return retrofitCache.getOrPut(baseUrl) {
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
    }

    fun createOpenAiApi(): OpenAiApi {
        return getRetrofit(AiService.CHATGPT.baseUrl).create(OpenAiApi::class.java)
    }

    fun createClaudeApi(): ClaudeApi {
        return getRetrofit(AiService.CLAUDE.baseUrl).create(ClaudeApi::class.java)
    }

    fun createGeminiApi(): GeminiApi {
        return getRetrofit(AiService.GEMINI.baseUrl).create(GeminiApi::class.java)
    }

    fun createGrokApi(): GrokApi {
        return getRetrofit(AiService.GROK.baseUrl).create(GrokApi::class.java)
    }

    fun createGroqApi(): GroqApi {
        return getRetrofit(AiService.GROQ.baseUrl).create(GroqApi::class.java)
    }

    fun createDeepSeekApi(): DeepSeekApi {
        return getRetrofit(AiService.DEEPSEEK.baseUrl).create(DeepSeekApi::class.java)
    }

    fun createMistralApi(): MistralApi {
        return getRetrofit(AiService.MISTRAL.baseUrl).create(MistralApi::class.java)
    }

    fun createPerplexityApi(): PerplexityApi {
        return getRetrofit(AiService.PERPLEXITY.baseUrl).create(PerplexityApi::class.java)
    }

    fun createTogetherApi(): TogetherApi {
        return getRetrofit(AiService.TOGETHER.baseUrl).create(TogetherApi::class.java)
    }

    fun createOpenRouterApi(): OpenRouterApi {
        return getRetrofit(AiService.OPENROUTER.baseUrl).create(OpenRouterApi::class.java)
    }

    fun createSiliconFlowApi(): SiliconFlowApi {
        return getRetrofit(AiService.SILICONFLOW.baseUrl).create(SiliconFlowApi::class.java)
    }

    fun createDummyApi(): OpenAiApi {
        // Dummy API uses OpenAI-compatible format
        return getRetrofit(AiService.DUMMY.baseUrl).create(OpenAiApi::class.java)
    }
}
