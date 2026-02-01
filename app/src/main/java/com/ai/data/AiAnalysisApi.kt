package com.ai.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.annotations.JsonAdapter
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
import retrofit2.http.Url
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Custom deserializer for cost field that can be either a Double (OpenRouter)
 * or an object with total_cost (Perplexity).
 */
class FlexibleCostDeserializer : JsonDeserializer<Double?> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): Double? {
        if (json == null || json.isJsonNull) return null
        return try {
            when {
                json.isJsonPrimitive && json.asJsonPrimitive.isNumber -> json.asDouble
                json.isJsonObject -> json.asJsonObject.get("total_cost")?.asDouble
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * API format used by a provider.
 */
enum class ApiFormat {
    OPENAI_COMPATIBLE,  // 28 providers using OpenAI-compatible chat/completions
    ANTHROPIC,          // Anthropic Messages API
    GOOGLE              // Google Gemini GenerativeAI API
}

/**
 * Data class representing a supported AI service provider.
 * Identity is based on `id` only — equality, hashing, and serialization all use `id`.
 */
class AiService(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val adminUrl: String,
    val defaultModel: String,
    val openRouterName: String? = null,
    val apiFormat: ApiFormat = ApiFormat.OPENAI_COMPATIBLE,
    val chatPath: String = "v1/chat/completions",
    val modelsPath: String? = "v1/models",
    val prefsKey: String = "",
    // Provider-specific quirks
    val seedFieldName: String = "seed",
    val supportsCitations: Boolean = false,
    val supportsSearchRecency: Boolean = false,
    val extractApiCost: Boolean = false,
    val costTicksDivisor: Double? = null,
    val modelListFormat: String = "object",
    val modelFilter: String? = null,
    val litellmPrefix: String? = null,
    val apiModelsLegacyKey: String? = null,
    val hardcodedModels: List<String>? = null,
    val defaultModelSource: String? = null
) {
    override fun equals(other: Any?): Boolean = other is AiService && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = id

    companion object {
        /** All registered providers — delegates to ProviderRegistry. */
        val entries: List<AiService> get() = ProviderRegistry.getAll()

        /** Find a provider by its ID string, or null if not found. */
        fun findById(id: String): AiService? = ProviderRegistry.findById(id)

        /** Find a provider by its ID string, throwing if not found (like enum valueOf). */
        fun valueOf(id: String): AiService = findById(id)
            ?: throw IllegalArgumentException("Unknown AiService: $id")
    }
}

/**
 * Gson serializer/deserializer for AiService.
 * Serializes as the string ID, deserializes by looking up in companion object.
 */
class AiServiceAdapter : JsonDeserializer<AiService>, JsonSerializer<AiService> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): AiService {
        val id = json?.asString ?: throw JsonParseException("Null AiService")
        return ProviderRegistry.findById(id) ?: throw JsonParseException("Unknown AiService: $id")
    }
    override fun serialize(src: AiService?, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement {
        return JsonPrimitive(src?.id ?: "")
    }
}

/**
 * Create a Gson instance with AiService type adapter registered.
 * Use this instead of Gson() when serializing/deserializing objects that contain AiService.
 */
fun createAiGson(prettyPrint: Boolean = false): Gson = GsonBuilder()
    .registerTypeAdapter(AiService::class.java, AiServiceAdapter())
    .apply { if (prettyPrint) setPrettyPrinting() }
    .create()

// OpenAI models
data class OpenAiMessage(
    val role: String,
    val content: String?,
    // DeepSeek reasoning models return reasoning in this field
    val reasoning_content: String? = null
)

data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val max_tokens: Int? = null,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val stop: List<String>? = null,
    val seed: Int? = null,
    val random_seed: Int? = null,  // Mistral uses random_seed instead of seed
    val response_format: OpenAiResponseFormat? = null,
    val return_citations: Boolean? = null,  // Perplexity
    val search_recency_filter: String? = null,  // Perplexity: "day", "week", "month", "year"
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
    // cost can be a Double (OpenRouter) or object with total_cost (Perplexity) - use custom deserializer
    @JsonAdapter(FlexibleCostDeserializer::class)
    val cost: Double? = null,
    val cost_in_usd_ticks: Long? = null,   // xAI: cost in billionths of a dollar (despite the name)
    val cost_usd: UsageCost? = null        // Alternative Perplexity cost field
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

// OpenAI Responses API streaming request
data class OpenAiResponsesStreamRequest(
    val model: String,
    val input: List<OpenAiResponsesInputMessage>,
    val instructions: String? = null,
    val stream: Boolean = true
)

data class OpenAiResponsesInputMessage(
    val role: String,
    val content: String
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
    val model: String,
    val max_tokens: Int? = null,
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
 * Unified Retrofit interface for all OpenAI-compatible providers.
 * Uses @Url to support different chat and models paths per provider.
 */
interface OpenAiCompatibleApi {
    @POST
    suspend fun createChatCompletion(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: OpenAiRequest
    ): Response<OpenAiResponse>

    @GET
    suspend fun listModels(
        @Url url: String,
        @Header("Authorization") authorization: String
    ): Response<OpenAiModelsResponse>

    @GET
    suspend fun listModelsArray(
        @Url url: String,
        @Header("Authorization") authorization: String
    ): Response<List<OpenAiModel>>
}

/**
 * Unified streaming interface for all OpenAI-compatible providers.
 */
interface OpenAiCompatibleStreamApi {
    @retrofit2.http.Streaming
    @POST
    suspend fun createChatCompletionStream(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: OpenAiStreamRequest
    ): Response<okhttp3.ResponseBody>
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
        // Retrofit requires base URL to end with /
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return retrofitCache.getOrPut(normalizedUrl) {
            Retrofit.Builder()
                .baseUrl(normalizedUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
    }

    /**
     * Create a unified OpenAI-compatible API for any provider.
     * Uses @Url for dynamic paths, so the caller must provide full path.
     */
    fun createOpenAiCompatibleApi(baseUrl: String): OpenAiCompatibleApi {
        return getRetrofit(baseUrl).create(OpenAiCompatibleApi::class.java)
    }

    /**
     * Create a unified OpenAI-compatible streaming API for any provider.
     */
    fun createOpenAiCompatibleStreamApi(baseUrl: String): OpenAiCompatibleStreamApi {
        return getRetrofit(baseUrl).create(OpenAiCompatibleStreamApi::class.java)
    }

    fun createOpenAiApiWithBaseUrl(baseUrl: String): OpenAiApi {
        return getRetrofit(baseUrl).create(OpenAiApi::class.java)
    }

    /**
     * Create OpenAI streaming API with a custom base URL.
     */
    fun createOpenAiStreamApiWithBaseUrl(baseUrl: String): OpenAiStreamApi {
        return getRetrofit(baseUrl).create(OpenAiStreamApi::class.java)
    }

    /**
     * Create Claude API with a custom base URL.
     */
    fun createClaudeApiWithBaseUrl(baseUrl: String): ClaudeApi {
        return getRetrofit(baseUrl).create(ClaudeApi::class.java)
    }

    /**
     * Create Claude streaming API with a custom base URL.
     */
    fun createClaudeStreamApiWithBaseUrl(baseUrl: String): ClaudeStreamApi {
        return getRetrofit(baseUrl).create(ClaudeStreamApi::class.java)
    }

    /**
     * Create Gemini API with a custom base URL.
     */
    fun createGeminiApiWithBaseUrl(baseUrl: String): GeminiApi {
        return getRetrofit(baseUrl).create(GeminiApi::class.java)
    }

    /**
     * Create Gemini streaming API with a custom base URL.
     */
    fun createGeminiStreamApiWithBaseUrl(baseUrl: String): GeminiStreamApi {
        return getRetrofit(baseUrl).create(GeminiStreamApi::class.java)
    }

    fun createHuggingFaceApi(): HuggingFaceApi {
        return getRetrofit("https://huggingface.co/api/").create(HuggingFaceApi::class.java)
    }

    fun createOpenRouterModelsApi(baseUrl: String): OpenRouterModelsApi {
        return getRetrofit(baseUrl).create(OpenRouterModelsApi::class.java)
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

    @retrofit2.http.Streaming
    @POST("v1/responses")
    suspend fun createResponseStream(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiResponsesStreamRequest
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

