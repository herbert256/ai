package com.ai.data

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Enum representing the supported AI services for chess position analysis.
 */
enum class AiService(
    val displayName: String,
    val baseUrl: String,
    val adminUrl: String,
    val defaultModel: String,
    val openRouterName: String? = null  // Provider name as used in OpenRouter model IDs (e.g., "anthropic" for "anthropic/claude-3-opus")
) {
    OPENAI("OpenAI", "https://api.openai.com/", "https://platform.openai.com/settings/organization/api-keys", "gpt-4o-mini", "openai"),
    ANTHROPIC("Anthropic", "https://api.anthropic.com/", "https://console.anthropic.com/settings/keys", "claude-sonnet-4-20250514", "anthropic"),
    GOOGLE("Google", "https://generativelanguage.googleapis.com/", "https://aistudio.google.com/app/apikey", "gemini-2.0-flash", "google"),
    XAI("xAI", "https://api.x.ai/", "https://console.x.ai/", "grok-3-mini", "x-ai"),
    GROQ("Groq", "https://api.groq.com/openai/", "https://console.groq.com/keys", "llama-3.3-70b-versatile"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/", "https://platform.deepseek.com/api_keys", "deepseek-chat", "deepseek"),
    MISTRAL("Mistral", "https://api.mistral.ai/", "https://console.mistral.ai/api-keys/", "mistral-small-latest", "mistralai"),
    PERPLEXITY("Perplexity", "https://api.perplexity.ai/", "https://www.perplexity.ai/settings/api", "sonar", "perplexity"),
    TOGETHER("Together", "https://api.together.xyz/", "https://api.together.xyz/settings/api-keys", "meta-llama/Llama-3.3-70B-Instruct-Turbo"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/", "https://openrouter.ai/keys", "anthropic/claude-3.5-sonnet"),
    SILICONFLOW("SiliconFlow", "https://api.siliconflow.com/", "https://cloud.siliconflow.cn/account/ak", "Qwen/Qwen2.5-7B-Instruct"),
    ZAI("Z.AI", "https://api.z.ai/api/paas/v4/", "https://open.bigmodel.cn/usercenter/apikeys", "glm-4.7-flash", "z-ai"),
    DUMMY("Dummy", "http://localhost:54321/", "", "dummy-model")
}

// OpenAI models
data class OpenAiMessage(
    val role: String,
    val content: String?,
    // DeepSeek reasoning models return reasoning in this field
    val reasoning_content: String? = null
)

data class OpenAiRequest(
    val model: String = AiService.OPENAI.defaultModel,
    val messages: List<OpenAiMessage>,
    val max_tokens: Int? = 1024,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val stop: List<String>? = null,
    val seed: Int? = null,
    val response_format: OpenAiResponseFormat? = null,
    val search: Boolean? = null  // Web search (may be ignored by provider)
)

data class OpenAiResponseFormat(
    val type: String = "text"  // "text" or "json_object"
)

data class OpenAiChoice(
    val message: OpenAiMessage,
    val index: Int
)

// Cost object structure (used by Perplexity)
data class UsageCost(
    val total_cost: Double? = null
)

data class OpenAiUsage(
    // Chat Completions API uses prompt_tokens/completion_tokens
    val prompt_tokens: Int?,
    val completion_tokens: Int?,
    val total_tokens: Int?,
    // Responses API uses input_tokens/output_tokens
    val input_tokens: Int? = null,
    val output_tokens: Int? = null,
    // Cost variations from different providers
    val cost: Double? = null,              // Standard: direct cost in USD
    val cost_in_usd_ticks: Long? = null,   // xAI: cost in millionths of a dollar
    val cost_usd: UsageCost? = null        // Perplexity: cost object with total_cost
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

// Anthropic models
data class ClaudeMessage(
    val role: String,
    val content: String
)

data class ClaudeRequest(
    val model: String = AiService.ANTHROPIC.defaultModel,
    val max_tokens: Int? = 1024,
    val messages: List<ClaudeMessage>,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val system: String? = null,
    val stop_sequences: List<String>? = null,
    // Additional parameters (may be ignored by API)
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val seed: Int? = null,
    val search: Boolean? = null  // Web search (may be ignored by provider)
)

data class ClaudeContentBlock(
    val type: String,
    val text: String?
)

data class ClaudeUsage(
    val input_tokens: Int?,
    val output_tokens: Int?,
    // Cost variations (in case API provides them)
    val cost: Double? = null,
    val cost_in_usd_ticks: Long? = null,
    val cost_usd: UsageCost? = null
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

// Google Gemini models
data class GeminiPart(
    val text: String
)

data class GeminiContent(
    val parts: List<GeminiPart>,
    val role: String? = null  // "user" or "model" for multi-turn chat
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
    val stopSequences: List<String>? = null,
    // Additional parameters (may be ignored by API)
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val seed: Int? = null,
    val search: Boolean? = null  // Web search (may be ignored by provider)
)

data class GeminiCandidate(
    val content: GeminiContent?
)

data class GeminiUsageMetadata(
    val promptTokenCount: Int?,
    val candidatesTokenCount: Int?,
    val totalTokenCount: Int?,
    // Cost variations (in case API provides them)
    val cost: Double? = null,
    val cost_in_usd_ticks: Long? = null,
    val cost_usd: UsageCost? = null
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

// xAI Grok models (uses OpenAI-compatible format)
data class GrokRequest(
    val model: String = AiService.XAI.defaultModel,
    val messages: List<OpenAiMessage>,
    val max_tokens: Int? = 1024,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val stop: List<String>? = null,
    val seed: Int? = null,
    val search: Boolean? = null  // Enable web search
)

// DeepSeek models (uses OpenAI-compatible format)
data class DeepSeekRequest(
    val model: String = AiService.DEEPSEEK.defaultModel,
    val messages: List<OpenAiMessage>,
    val max_tokens: Int? = 1024,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val stop: List<String>? = null,
    val seed: Int? = null,
    val search: Boolean? = null  // Web search (may be ignored by provider)
)

// Mistral models (uses OpenAI-compatible format)
data class MistralRequest(
    val model: String = AiService.MISTRAL.defaultModel,
    val messages: List<OpenAiMessage>,
    val max_tokens: Int? = 1024,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val stop: List<String>? = null,
    val random_seed: Int? = null,
    val search: Boolean? = null  // Web search (may be ignored by provider)
)

// Perplexity models (uses OpenAI-compatible format)
data class PerplexityRequest(
    val model: String = AiService.PERPLEXITY.defaultModel,
    val messages: List<OpenAiMessage>,
    val max_tokens: Int? = 1024,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val stop: List<String>? = null,
    val seed: Int? = null,
    val return_citations: Boolean? = null,
    val search_recency_filter: String? = null,  // "day", "week", "month", "year"
    val search: Boolean? = null  // Web search (may be ignored by provider)
)

// Together AI models (uses OpenAI-compatible format)
data class TogetherRequest(
    val model: String = AiService.TOGETHER.defaultModel,
    val messages: List<OpenAiMessage>,
    val max_tokens: Int? = 1024,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val stop: List<String>? = null,
    val seed: Int? = null,
    val search: Boolean? = null  // Web search (may be ignored by provider)
)

// OpenRouter models (uses OpenAI-compatible format)
data class OpenRouterRequest(
    val model: String = AiService.OPENROUTER.defaultModel,
    val messages: List<OpenAiMessage>,
    val max_tokens: Int? = 1024,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val stop: List<String>? = null,
    val seed: Int? = null,
    val search: Boolean? = null  // Web search (may be ignored by provider)
)

// SiliconFlow models (uses OpenAI-compatible format)
data class SiliconFlowRequest(
    val model: String = AiService.SILICONFLOW.defaultModel,
    val messages: List<OpenAiMessage>,
    val max_tokens: Int? = 1024,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val stop: List<String>? = null,
    val seed: Int? = null,
    val search: Boolean? = null  // Web search (may be ignored by provider)
)

// Groq models (uses OpenAI-compatible format)
data class GroqRequest(
    val model: String = AiService.GROQ.defaultModel,
    val messages: List<OpenAiMessage>,
    val max_tokens: Int? = 1024,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val stop: List<String>? = null,
    val seed: Int? = null,
    val search: Boolean? = null  // Web search (may be ignored by provider)
)

/**
 * Retrofit interface for OpenAI API.
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
 * Retrofit interface for Anthropic API.
 */
interface ClaudeApi {
    @POST("v1/messages")
    suspend fun createMessage(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        @Body request: ClaudeRequest
    ): Response<ClaudeResponse>

    @GET("v1/models")
    suspend fun listModels(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01"
    ): Response<ClaudeModelsResponse>
}

// Response for listing Claude/Anthropic models
data class ClaudeModelsResponse(
    val data: List<ClaudeModelInfo>?
)

data class ClaudeModelInfo(
    val id: String?,
    val display_name: String?,
    val type: String?
)

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
 * Retrofit interface for Google Gemini API.
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
 * Retrofit interface for xAI Grok API (OpenAI-compatible).
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

    @GET("v1/models")
    suspend fun listModels(
        @Header("Authorization") authorization: String
    ): Response<SiliconFlowModelsResponse>
}

// Response for listing SiliconFlow models
data class SiliconFlowModelsResponse(
    val data: List<SiliconFlowModelInfo>?
)

data class SiliconFlowModelInfo(
    val id: String?,
    val `object`: String?
)

/**
 * Retrofit interface for Z.AI API (OpenAI-compatible format, different endpoint).
 * Z.AI uses https://api.z.ai/api/paas/v4/chat/completions
 */
interface ZaiApi {
    @POST("chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiRequest
    ): Response<OpenAiResponse>

    @GET("model")
    suspend fun listModels(
        @Header("Authorization") authorization: String
    ): Response<ZaiModelsResponse>
}

// Response for listing Z.AI models
data class ZaiModelsResponse(
    val data: List<ZaiModelInfo>?
)

data class ZaiModelInfo(
    val id: String?,
    val `object`: String?
)

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
        return getRetrofit(AiService.OPENAI.baseUrl).create(OpenAiApi::class.java)
    }

    fun createClaudeApi(): ClaudeApi {
        return getRetrofit(AiService.ANTHROPIC.baseUrl).create(ClaudeApi::class.java)
    }

    fun createGeminiApi(): GeminiApi {
        return getRetrofit(AiService.GOOGLE.baseUrl).create(GeminiApi::class.java)
    }

    fun createGrokApi(): GrokApi {
        return getRetrofit(AiService.XAI.baseUrl).create(GrokApi::class.java)
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

    fun createZaiApi(): ZaiApi {
        // Z.AI uses OpenAI-compatible format with endpoint chat/completions
        return getRetrofit(AiService.ZAI.baseUrl).create(ZaiApi::class.java)
    }

    fun createDummyApi(): OpenAiApi {
        // Dummy API uses OpenAI-compatible format
        return getRetrofit(AiService.DUMMY.baseUrl).create(OpenAiApi::class.java)
    }

    fun createHuggingFaceApi(): HuggingFaceApi {
        return getRetrofit("https://huggingface.co/api/").create(HuggingFaceApi::class.java)
    }

    fun createOpenRouterModelsApi(): OpenRouterModelsApi {
        return getRetrofit(AiService.OPENROUTER.baseUrl).create(OpenRouterModelsApi::class.java)
    }

    // ========== Streaming API Factories ==========

    fun createOpenAiStreamApi(): OpenAiStreamApi {
        return getRetrofit(AiService.OPENAI.baseUrl).create(OpenAiStreamApi::class.java)
    }

    fun createClaudeStreamApi(): ClaudeStreamApi {
        return getRetrofit(AiService.ANTHROPIC.baseUrl).create(ClaudeStreamApi::class.java)
    }

    fun createGeminiStreamApi(): GeminiStreamApi {
        return getRetrofit(AiService.GOOGLE.baseUrl).create(GeminiStreamApi::class.java)
    }

    fun createGrokStreamApi(): GrokStreamApi {
        return getRetrofit(AiService.XAI.baseUrl).create(GrokStreamApi::class.java)
    }

    fun createGroqStreamApi(): GroqStreamApi {
        return getRetrofit(AiService.GROQ.baseUrl).create(GroqStreamApi::class.java)
    }

    fun createDeepSeekStreamApi(): DeepSeekStreamApi {
        return getRetrofit(AiService.DEEPSEEK.baseUrl).create(DeepSeekStreamApi::class.java)
    }

    fun createMistralStreamApi(): MistralStreamApi {
        return getRetrofit(AiService.MISTRAL.baseUrl).create(MistralStreamApi::class.java)
    }

    fun createPerplexityStreamApi(): PerplexityStreamApi {
        return getRetrofit(AiService.PERPLEXITY.baseUrl).create(PerplexityStreamApi::class.java)
    }

    fun createTogetherStreamApi(): TogetherStreamApi {
        return getRetrofit(AiService.TOGETHER.baseUrl).create(TogetherStreamApi::class.java)
    }

    fun createOpenRouterStreamApi(): OpenRouterStreamApi {
        return getRetrofit(AiService.OPENROUTER.baseUrl).create(OpenRouterStreamApi::class.java)
    }

    fun createSiliconFlowStreamApi(): SiliconFlowStreamApi {
        return getRetrofit(AiService.SILICONFLOW.baseUrl).create(SiliconFlowStreamApi::class.java)
    }

    fun createZaiStreamApi(): ZaiStreamApi {
        return getRetrofit(AiService.ZAI.baseUrl).create(ZaiStreamApi::class.java)
    }

    fun createDummyStreamApi(): DummyStreamApi {
        return getRetrofit(AiService.DUMMY.baseUrl).create(DummyStreamApi::class.java)
    }
}

// ============================================================================
// Model Info APIs - for fetching detailed model information
// ============================================================================

/**
 * OpenRouter detailed model info response.
 */
data class OpenRouterModelInfo(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val context_length: Int? = null,
    val pricing: OpenRouterPricing? = null,
    val top_provider: OpenRouterTopProvider? = null,
    val architecture: OpenRouterArchitecture? = null,
    val per_request_limits: OpenRouterLimits? = null,
    val supported_parameters: List<String>? = null
)

data class OpenRouterPricing(
    val prompt: String? = null,  // Cost per token (as string)
    val completion: String? = null,
    val image: String? = null,
    val request: String? = null
)

data class OpenRouterTopProvider(
    val context_length: Int? = null,
    val max_completion_tokens: Int? = null,
    val is_moderated: Boolean? = null
)

data class OpenRouterArchitecture(
    val modality: String? = null,  // "text->text", "text+image->text", etc.
    val tokenizer: String? = null,
    val instruct_type: String? = null
)

data class OpenRouterLimits(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null
)

data class OpenRouterModelsDetailedResponse(
    val data: List<OpenRouterModelInfo>
)

/**
 * Hugging Face model info response.
 */
data class HuggingFaceModelInfo(
    val id: String? = null,
    val modelId: String? = null,
    val author: String? = null,
    val sha: String? = null,
    val downloads: Long? = null,
    val likes: Int? = null,
    val tags: List<String>? = null,
    val pipeline_tag: String? = null,
    val library_name: String? = null,
    val createdAt: String? = null,
    val lastModified: String? = null,
    val private: Boolean? = null,
    val gated: Boolean? = null,
    val disabled: Boolean? = null,
    val cardData: HuggingFaceCardData? = null,
    val siblings: List<HuggingFaceSibling>? = null,
    val config: Map<String, Any>? = null
)

data class HuggingFaceCardData(
    val license: String? = null,
    val language: List<String>? = null,
    val datasets: List<String>? = null,
    val base_model: String? = null,
    val model_type: String? = null,
    val pipeline_tag: String? = null,
    val tags: List<String>? = null
)

data class HuggingFaceSibling(
    val rfilename: String? = null
)

/**
 * Retrofit interface for OpenRouter Models API with detailed info.
 */
interface OpenRouterModelsApi {
    @retrofit2.http.GET("v1/models")
    suspend fun listModelsDetailed(
        @Header("Authorization") authorization: String
    ): Response<OpenRouterModelsDetailedResponse>
}

/**
 * Retrofit interface for Hugging Face API.
 * Requires Bearer token authentication.
 */
interface HuggingFaceApi {
    @retrofit2.http.GET("models/{modelId}")
    suspend fun getModelInfo(
        @Path("modelId", encoded = true) modelId: String,
        @Header("Authorization") authorization: String
    ): Response<HuggingFaceModelInfo>
}

// ============================================================================
// Streaming API Models - for SSE (Server-Sent Events) streaming responses
// ============================================================================

/**
 * OpenAI streaming chunk response (used by most providers).
 * Format: data: {"id":"...","choices":[{"delta":{"content":"..."}}]}
 */
data class OpenAiStreamChunk(
    val id: String?,
    val choices: List<StreamChoice>?,
    val created: Long?
)

data class StreamChoice(
    val index: Int?,
    val delta: StreamDelta?,
    val finish_reason: String?
)

data class StreamDelta(
    val role: String? = null,
    val content: String? = null,
    val reasoning_content: String? = null  // DeepSeek reasoning models
)

/**
 * Anthropic streaming event format.
 * Events: message_start, content_block_start, content_block_delta, content_block_stop, message_delta, message_stop
 */
data class ClaudeStreamEvent(
    val type: String,
    val index: Int? = null,
    val delta: ClaudeStreamDelta? = null,
    val content_block: ClaudeStreamContentBlock? = null
)

data class ClaudeStreamDelta(
    val type: String? = null,
    val text: String? = null,
    val stop_reason: String? = null
)

data class ClaudeStreamContentBlock(
    val type: String? = null,
    val text: String? = null
)

/**
 * Google Gemini streaming response.
 * Returns array of candidates with partial content.
 */
data class GeminiStreamChunk(
    val candidates: List<GeminiStreamCandidate>?
)

data class GeminiStreamCandidate(
    val content: GeminiContent?,
    val finishReason: String?
)

// ============================================================================
// Streaming Request Models - with stream: true parameter
// ============================================================================

/**
 * OpenAI streaming request (adds stream: true).
 */
data class OpenAiStreamRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val stream: Boolean = true,
    val max_tokens: Int? = null,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val stop: List<String>? = null,
    val seed: Int? = null
)

/**
 * Claude streaming request (adds stream: true).
 */
data class ClaudeStreamRequest(
    val model: String,
    val messages: List<ClaudeMessage>,
    val stream: Boolean = true,
    val max_tokens: Int = 4096,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val system: String? = null,
    val stop_sequences: List<String>? = null
)

/**
 * Gemini doesn't use a separate stream request - uses different endpoint.
 */

// ============================================================================
// Streaming API Interfaces - using ResponseBody for raw stream access
// ============================================================================

/**
 * Streaming API interface for OpenAI-compatible providers.
 */
interface OpenAiStreamApi {
    @retrofit2.http.Streaming
    @POST("v1/chat/completions")
    suspend fun createChatCompletionStream(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiStreamRequest
    ): Response<okhttp3.ResponseBody>
}

/**
 * Streaming API interface for Anthropic.
 */
interface ClaudeStreamApi {
    @retrofit2.http.Streaming
    @POST("v1/messages")
    suspend fun createMessageStream(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        @Body request: ClaudeStreamRequest
    ): Response<okhttp3.ResponseBody>
}

/**
 * Streaming API interface for Google Gemini.
 */
interface GeminiStreamApi {
    @retrofit2.http.Streaming
    @POST("v1beta/models/{model}:streamGenerateContent")
    suspend fun streamGenerateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Query("alt") alt: String = "sse",
        @Body request: GeminiRequest
    ): Response<okhttp3.ResponseBody>
}

/**
 * Streaming API interface for xAI Grok.
 */
interface GrokStreamApi {
    @retrofit2.http.Streaming
    @POST("v1/chat/completions")
    suspend fun createChatCompletionStream(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiStreamRequest
    ): Response<okhttp3.ResponseBody>
}

/**
 * Streaming API interface for Groq.
 */
interface GroqStreamApi {
    @retrofit2.http.Streaming
    @POST("v1/chat/completions")
    suspend fun createChatCompletionStream(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiStreamRequest
    ): Response<okhttp3.ResponseBody>
}

/**
 * Streaming API interface for DeepSeek.
 */
interface DeepSeekStreamApi {
    @retrofit2.http.Streaming
    @POST("chat/completions")
    suspend fun createChatCompletionStream(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiStreamRequest
    ): Response<okhttp3.ResponseBody>
}

/**
 * Streaming API interface for Mistral.
 */
interface MistralStreamApi {
    @retrofit2.http.Streaming
    @POST("v1/chat/completions")
    suspend fun createChatCompletionStream(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiStreamRequest
    ): Response<okhttp3.ResponseBody>
}

/**
 * Streaming API interface for Perplexity.
 */
interface PerplexityStreamApi {
    @retrofit2.http.Streaming
    @POST("chat/completions")
    suspend fun createChatCompletionStream(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiStreamRequest
    ): Response<okhttp3.ResponseBody>
}

/**
 * Streaming API interface for Together AI.
 */
interface TogetherStreamApi {
    @retrofit2.http.Streaming
    @POST("v1/chat/completions")
    suspend fun createChatCompletionStream(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiStreamRequest
    ): Response<okhttp3.ResponseBody>
}

/**
 * Streaming API interface for OpenRouter.
 */
interface OpenRouterStreamApi {
    @retrofit2.http.Streaming
    @POST("v1/chat/completions")
    suspend fun createChatCompletionStream(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiStreamRequest
    ): Response<okhttp3.ResponseBody>
}

/**
 * Streaming API interface for SiliconFlow.
 */
interface SiliconFlowStreamApi {
    @retrofit2.http.Streaming
    @POST("v1/chat/completions")
    suspend fun createChatCompletionStream(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiStreamRequest
    ): Response<okhttp3.ResponseBody>
}

/**
 * Streaming API interface for Z.AI.
 */
interface ZaiStreamApi {
    @retrofit2.http.Streaming
    @POST("chat/completions")
    suspend fun createChatCompletionStream(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiStreamRequest
    ): Response<okhttp3.ResponseBody>
}

/**
 * Streaming API interface for Dummy (uses OpenAI format).
 */
interface DummyStreamApi {
    @retrofit2.http.Streaming
    @POST("v1/chat/completions")
    suspend fun createChatCompletionStream(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiStreamRequest
    ): Response<okhttp3.ResponseBody>
}
