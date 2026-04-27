package com.ai.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type

// ============================================================================
// Gson factory
// ============================================================================

private val aiGson: Gson by lazy {
    GsonBuilder().registerTypeAdapter(AppService::class.java, AppServiceAdapter()).create()
}
private val aiGsonPretty: Gson by lazy {
    GsonBuilder().registerTypeAdapter(AppService::class.java, AppServiceAdapter()).setPrettyPrinting().create()
}
fun createAppGson(prettyPrint: Boolean = false): Gson = if (prettyPrint) aiGsonPretty else aiGson

// ============================================================================
// Flexible cost deserializer (OpenRouter returns Double, Perplexity returns object)
// ============================================================================

class FlexibleCostDeserializer : JsonDeserializer<Double?> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): Double? {
        if (json == null || json.isJsonNull) return null
        return try {
            when {
                json.isJsonPrimitive && json.asJsonPrimitive.isNumber -> json.asDouble
                json.isJsonObject -> json.asJsonObject.get("total_cost")?.asDouble
                else -> null
            }
        } catch (_: Exception) { null }
    }
}

// ============================================================================
// OpenAI models — single request class with optional stream field
// ============================================================================

data class OpenAiMessage(
    val role: String,
    val content: String?,
    val reasoning_content: String? = null
)

data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val stream: Boolean? = null,
    val max_tokens: Int? = null,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val stop: List<String>? = null,
    val seed: Int? = null,
    val random_seed: Int? = null,
    val response_format: OpenAiResponseFormat? = null,
    val return_citations: Boolean? = null,
    val search_recency_filter: String? = null,
    val search: Boolean? = null
)

data class OpenAiResponseFormat(val type: String = "text")

data class OpenAiChoice(val message: OpenAiMessage, val index: Int)

data class UsageCost(
    val total_cost: Double? = null,
    val input_tokens_cost: Double? = null,
    val output_tokens_cost: Double? = null,
    val request_cost: Double? = null
)

data class OpenAiPromptTokensDetails(val cached_tokens: Int? = null)

data class OpenAiUsage(
    val prompt_tokens: Int?,
    val completion_tokens: Int?,
    val total_tokens: Int?,
    val input_tokens: Int? = null,
    val output_tokens: Int? = null,
    // FlexibleCostDeserializer handles both shapes: OpenRouter / xAI emit a
    // primitive Double here; Perplexity emits a nested object with total_cost.
    @JsonAdapter(FlexibleCostDeserializer::class)
    val cost: Double? = null,
    val cost_in_usd_ticks: Long? = null,
    val cost_usd: UsageCost? = null,
    val prompt_tokens_details: OpenAiPromptTokensDetails? = null,
    val prompt_cache_hit_tokens: Int? = null,   // DeepSeek
    val prompt_cache_miss_tokens: Int? = null,  // DeepSeek
    val cached_tokens: Int? = null              // some xAI / others flatten this
)

data class SearchResult(val name: String?, val url: String?, val snippet: String?)

data class OpenAiResponse(
    val id: String?,
    val choices: List<OpenAiChoice>?,
    val usage: OpenAiUsage?,
    val error: OpenAiError?,
    val citations: List<String>? = null,
    val search_results: List<SearchResult>? = null,
    val related_questions: List<String>? = null
)

data class OpenAiError(val message: String?, val type: String?)

// OpenAI Responses API models (GPT-5.x, o3, o4)
data class OpenAiResponsesRequest(
    val model: String,
    val input: Any,
    val instructions: String? = null,
    val stream: Boolean? = null
)

data class OpenAiResponsesInputMessage(val role: String, val content: String)

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

data class OpenAiResponsesError(val message: String?, val type: String?, val code: String?)

// ============================================================================
// Anthropic models — single request class with optional stream field
// ============================================================================

data class ClaudeMessage(val role: String, val content: String)

data class ClaudeRequest(
    val model: String,
    val messages: List<ClaudeMessage>,
    val stream: Boolean? = null,
    val max_tokens: Int? = null,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val system: String? = null,
    val stop_sequences: List<String>? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val seed: Int? = null,
    val search: Boolean? = null
)

data class ClaudeContentBlock(val type: String, val text: String?)

data class ClaudeUsage(
    val input_tokens: Int?,
    val output_tokens: Int?,
    val cost: Double? = null,
    val cost_in_usd_ticks: Long? = null,
    val cost_usd: UsageCost? = null,
    // Anthropic's input_tokens excludes cached / cache-creation; we read these
    // separately and bill at distinct rates.
    val cache_creation_input_tokens: Int? = null,
    val cache_read_input_tokens: Int? = null
)

data class ClaudeResponse(
    val id: String?,
    val content: List<ClaudeContentBlock>?,
    val usage: ClaudeUsage?,
    val error: ClaudeError?
)

data class ClaudeError(val type: String?, val message: String?)

// ============================================================================
// Google Gemini models
// ============================================================================

data class GeminiPart(val text: String)

data class GeminiContent(
    val parts: List<GeminiPart>,
    val role: String? = null
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
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val seed: Int? = null,
    val search: Boolean? = null
)

data class GeminiCandidate(val content: GeminiContent?)

data class GeminiUsageMetadata(
    val promptTokenCount: Int?,
    val candidatesTokenCount: Int?,
    val totalTokenCount: Int?,
    val cost: Double? = null,
    val cost_in_usd_ticks: Long? = null,
    val cost_usd: UsageCost? = null,
    // Subset of promptTokenCount that came from the cached-content store.
    val cachedContentTokenCount: Int? = null
)

data class GeminiResponse(
    val candidates: List<GeminiCandidate>?,
    val usageMetadata: GeminiUsageMetadata?,
    val error: GeminiError?
)

data class GeminiError(val code: Int?, val message: String?, val status: String?)

// ============================================================================
// Model listing response types
// ============================================================================

data class OpenAiModelsResponse(val data: List<OpenAiModel>?)
data class OpenAiModel(val id: String?, val owned_by: String?)

data class CohereModelsResponse(val models: List<CohereModelInfo>?)
data class CohereModelInfo(val name: String?, val endpoints: List<String>?)

data class ClaudeModelsResponse(val data: List<ClaudeModelInfo>?)
data class ClaudeModelInfo(val id: String?, val display_name: String?, val type: String?)

data class GeminiModelsResponse(val models: List<GeminiModel>?)
data class GeminiModel(val name: String?, val displayName: String?, val supportedGenerationMethods: List<String>?)

// ============================================================================
// Streaming chunk types
// ============================================================================

data class OpenAiStreamChunk(val id: String?, val choices: List<StreamChoice>?, val created: Long?)
data class StreamChoice(val index: Int?, val delta: StreamDelta?, val finish_reason: String?)
data class StreamDelta(val role: String? = null, val content: String? = null, val reasoning_content: String? = null)

data class ClaudeStreamEvent(
    val type: String,
    val index: Int? = null,
    val delta: ClaudeStreamDelta? = null,
    val content_block: ClaudeStreamContentBlock? = null
)
data class ClaudeStreamDelta(val type: String? = null, val text: String? = null, val stop_reason: String? = null)
data class ClaudeStreamContentBlock(val type: String? = null, val text: String? = null)

data class GeminiStreamChunk(val candidates: List<GeminiStreamCandidate>?)
data class GeminiStreamCandidate(val content: GeminiContent?, val finishReason: String?)

// ============================================================================
// OpenRouter / HuggingFace model info types
// ============================================================================

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

data class OpenRouterPricing(val prompt: String? = null, val completion: String? = null, val image: String? = null, val request: String? = null)
data class OpenRouterTopProvider(val context_length: Int? = null, val max_completion_tokens: Int? = null, val is_moderated: Boolean? = null)
data class OpenRouterArchitecture(val modality: String? = null, val tokenizer: String? = null, val instruct_type: String? = null)
data class OpenRouterLimits(val prompt_tokens: Int? = null, val completion_tokens: Int? = null)
data class OpenRouterModelsDetailedResponse(val data: List<OpenRouterModelInfo>)

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

data class HuggingFaceSibling(val rfilename: String? = null)

// ============================================================================
// Token-usage extraction — normalize each provider's response shape into the
// unified TokenUsage(inputTokens, cachedInputTokens, cacheCreationTokens,
// outputTokens) where inputTokens is the *uncached* portion only.
// ============================================================================

/** OpenAI / DeepSeek / xAI / Gemini-compat: prompt_tokens is the total and
 *  includes cached tokens. We subtract the cached count to get the fresh
 *  bucket. DeepSeek uses prompt_cache_hit_tokens / prompt_cache_miss_tokens;
 *  OpenAI uses prompt_tokens_details.cached_tokens. */
fun OpenAiUsage.toTokenUsage(provider: AppService? = null): TokenUsage {
    val total = prompt_tokens ?: input_tokens ?: 0
    val cached = prompt_tokens_details?.cached_tokens
        ?: cached_tokens
        ?: prompt_cache_hit_tokens
        ?: 0
    val fresh = (total - cached).coerceAtLeast(0)
    return TokenUsage(
        inputTokens = fresh,
        outputTokens = completion_tokens ?: output_tokens ?: 0,
        apiCost = extractApiCost(this, provider),
        cachedInputTokens = cached
    )
}

/** Anthropic: input_tokens already excludes both cache buckets — pass through. */
fun ClaudeUsage.toTokenUsage(): TokenUsage = TokenUsage(
    inputTokens = input_tokens ?: 0,
    outputTokens = output_tokens ?: 0,
    apiCost = extractApiCost(this),
    cachedInputTokens = cache_read_input_tokens ?: 0,
    cacheCreationTokens = cache_creation_input_tokens ?: 0
)

/** Gemini: cachedContentTokenCount is a subset of promptTokenCount. */
fun GeminiUsageMetadata.toTokenUsage(): TokenUsage {
    val total = promptTokenCount ?: 0
    val cached = cachedContentTokenCount ?: 0
    val fresh = (total - cached).coerceAtLeast(0)
    return TokenUsage(
        inputTokens = fresh,
        outputTokens = candidatesTokenCount ?: 0,
        apiCost = extractApiCost(this),
        cachedInputTokens = cached
    )
}

// ============================================================================
// Cost extraction (legacy — used internally by the toTokenUsage helpers above
// so the apiCost field on TokenUsage stays populated when the provider
// returned an explicit cost)
// ============================================================================

fun extractApiCost(usage: OpenAiUsage?, provider: AppService? = null): Double? {
    if (usage == null) return null
    // Trust usage.cost whenever the response actually populated it. OpenRouter
    // returns a primitive Double; Perplexity returns a nested object whose
    // total_cost field is decoded by FlexibleCostDeserializer. Both reach us
    // here as a Double. Other providers leave it null.
    usage.cost?.let { return it }
    usage.cost_usd?.total_cost?.let { return it }
    provider?.costTicksDivisor?.let { divisor ->
        usage.cost_in_usd_ticks?.let { return it / divisor }
    }
    if (provider?.costTicksDivisor == null) {
        usage.cost_in_usd_ticks?.let { return it / 10_000_000_000.0 }
    }
    return null
}

fun extractApiCost(usage: ClaudeUsage?): Double? {
    if (usage == null) return null
    usage.cost?.let { return it }
    usage.cost_in_usd_ticks?.let { return it / 10_000_000_000.0 }
    usage.cost_usd?.total_cost?.let { return it }
    return null
}

fun extractApiCost(usage: GeminiUsageMetadata?): Double? {
    if (usage == null) return null
    usage.cost?.let { return it }
    usage.cost_in_usd_ticks?.let { return it / 10_000_000_000.0 }
    usage.cost_usd?.total_cost?.let { return it }
    return null
}
