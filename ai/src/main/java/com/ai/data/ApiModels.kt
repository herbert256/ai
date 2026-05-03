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

/**
 * OpenAI-compatible chat message. [content] is `Any?` because the API accepts
 * either a String (text-only) or a List of typed parts (text + image_url) for
 * vision requests. Response messages always come back with content as String.
 */
data class OpenAiMessage(
    val role: String,
    val content: Any?,
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
    val search: Boolean? = null,
    val tools: List<Any>? = null,
    /** Reasoning-effort hint for chat-completions models that honour it
     *  (OpenRouter routing reasoning models, DeepSeek-R, Together's
     *  reasoning routes, Groq reasoning, Mistral magistral, …). One of
     *  "low" / "medium" / "high"; null = unset. The Responses API has
     *  its own [OpenAiResponsesRequest.reasoning] block — separate field. */
    val reasoning_effort: String? = null
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

// Embeddings (OpenAI-compatible — providers all use the same shape).
data class OpenAiEmbeddingRequest(val model: String, val input: List<String>, val encoding_format: String = "float")
data class OpenAiEmbeddingResponse(
    val data: List<OpenAiEmbeddingItem>?,
    val usage: OpenAiUsage? = null,
    val error: OpenAiError? = null
)
data class OpenAiEmbeddingItem(val embedding: List<Double>?, val index: Int? = null)

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
    val stream: Boolean? = null,
    val tools: List<Any>? = null,
    /** Reasoning hint — `{"effort": "low"|"medium"|"high"}` — supported on
     *  gpt-5/o-series. Stripped at dispatch when LiteLLM says the model
     *  isn't reasoning-capable. */
    val reasoning: Map<String, Any>? = null
)

data class OpenAiResponsesInputMessage(val role: String, val content: String)

data class OpenAiResponsesOutputContent(
    val type: String?,
    val text: String?,
    val annotations: List<OpenAiResponsesAnnotation>? = null
)

/** A url_citation annotation emitted by the Responses API when the
 *  web_search_preview tool runs. start_index/end_index point into the
 *  surrounding text block. */
data class OpenAiResponsesAnnotation(
    val type: String? = null,
    val url: String? = null,
    val title: String? = null,
    val start_index: Int? = null,
    val end_index: Int? = null
)

data class OpenAiResponsesOutputMessage(
    val type: String?,
    val id: String?,
    val status: String?,
    val role: String?,
    val content: List<OpenAiResponsesOutputContent>?,
    /** For `web_search_call` items the action has a `query` field — useful
     *  to surface what the tool searched for. */
    val action: OpenAiResponsesAction? = null
)

data class OpenAiResponsesAction(
    val type: String? = null,
    val query: String? = null
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

/**
 * Anthropic message. [content] accepts either a String (text-only) or a List
 * of content blocks (text + image source) for vision requests.
 */
data class ClaudeMessage(val role: String, val content: Any)

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
    val search: Boolean? = null,
    val tools: List<Any>? = null,
    /** Anthropic extended-thinking block: `{type: "enabled",
     *  budget_tokens: N}` for Claude 3.7 / 4.x (pre-4.7), or
     *  `{type: "adaptive"}` for Claude Opus 4.7+ (which carries effort
     *  on the [output_config] field instead). Only attached when the
     *  chosen model supports it; mapped from the unified low/medium/
     *  high effort levels by [com.ai.data.anthropicThinkingField]. */
    val thinking: Map<String, Any>? = null,
    /** Top-level effort companion to [thinking] for Claude Opus 4.7+:
     *  `{effort: "low|medium|high"}`. Older Claude builds ignore this
     *  field — the budget rides on the thinking block. */
    val output_config: Map<String, Any>? = null
)

data class ClaudeContentBlock(
    val type: String,
    val text: String? = null,
    /** Attached to text blocks when web_search citations point back to a
     *  previously-emitted web_search_tool_result. */
    val citations: List<ClaudeCitation>? = null,
    /** Present on web_search_tool_result blocks. Each item is a
     *  `web_search_result` with `url` + `title` (+ `page_age`). */
    val content: List<ClaudeWebSearchResultItem>? = null,
    val tool_use_id: String? = null
)

data class ClaudeCitation(
    val type: String? = null,
    val url: String? = null,
    val title: String? = null,
    val cited_text: String? = null
)

data class ClaudeWebSearchResultItem(
    val type: String? = null,
    val url: String? = null,
    val title: String? = null,
    val page_age: String? = null
)

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

data class GeminiPart(
    val text: String? = null,
    @SerializedName("inline_data") val inlineData: GeminiInlineData? = null
)

data class GeminiInlineData(
    @SerializedName("mime_type") val mimeType: String,
    val data: String
)

data class GeminiContent(
    val parts: List<GeminiPart>,
    val role: String? = null
)

data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null,
    val systemInstruction: GeminiContent? = null,
    val tools: List<Any>? = null
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
    val search: Boolean? = null,
    /** Gemini 2.5 thinking config: `{thinkingBudget: N, includeThoughts:
     *  bool}`. Only attached when the model supports thinking; mapped
     *  from the unified effort levels by
     *  [com.ai.data.geminiThinkingConfigField]. */
    val thinkingConfig: Map<String, Any>? = null
)

data class GeminiCandidate(
    val content: GeminiContent?,
    val groundingMetadata: GeminiGroundingMetadata? = null
)

/** Populated by Gemini when the google_search tool runs. groundingChunks
 *  is the list of cited URLs; webSearchQueries is what the model searched. */
data class GeminiGroundingMetadata(
    val groundingChunks: List<GeminiGroundingChunk>? = null,
    val webSearchQueries: List<String>? = null
)

data class GeminiGroundingChunk(val web: GeminiGroundingWeb? = null)

data class GeminiGroundingWeb(val uri: String? = null, val title: String? = null)

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
/** OpenAI-compatible model entry. Fields beyond `id`/`owned_by` are
 *  provider-specific extensions Gson silently ignores when absent:
 *    - Mistral exposes a rich `capabilities` block + max_context_length.
 *    - Together AI ships `context_length` and `type` (chat / image / etc).
 *    - Groq ships `context_window`.
 *    - Fireworks ships `supports_chat` / `supports_image_input` /
 *      `context_length`.
 *  Letting them all coexist lets one OpenAiModel data class absorb
 *  whatever extra metadata a provider includes without forking parsers. */
data class OpenAiModel(
    val id: String?,
    val owned_by: String? = null,
    val capabilities: MistralCapabilities? = null,
    val max_context_length: Int? = null,
    val context_length: Int? = null,
    val context_window: Int? = null,
    val supports_chat: Boolean? = null,
    val supports_image_input: Boolean? = null,
    /** Moonshot's `/v1/models` declares vision under
     *  `supports_image_in` (no trailing `put`). Different field, same
     *  meaning — the dispatcher falls back to it when
     *  `supports_image_input` is null so we don't silently miss every
     *  Moonshot vision-capable model. */
    val supports_image_in: Boolean? = null,
    /** Groq's /v1/models flags entries the provider has temporarily
     *  disabled with `active=false`. Kept-but-disabled is meaningful
     *  on Groq because their fleet rotates models in and out by
     *  utilisation; an `active=false` model returns 401 on any chat
     *  call. The dispatcher drops these so the picker doesn't
     *  promise something the provider won't serve. Other providers
     *  don't ship the field — null falls through to "active". */
    val active: Boolean? = null,
    /** xAI and some other OpenAI-compat providers expose this array of
     *  parameter names the model honors (e.g. ["reasoning",
     *  "include_reasoning", "max_tokens", …]). Used to detect thinking-
     *  capable models without forcing a bespoke parser. */
    val supported_parameters: List<String>? = null
)

data class MistralCapabilities(
    val completion_chat: Boolean? = null,
    val completion_fim: Boolean? = null,
    val function_calling: Boolean? = null,
    val fine_tuning: Boolean? = null,
    val vision: Boolean? = null,
    val classification: Boolean? = null,
    /** Mistral's per-model thinking flag (true on `magistral-*`). */
    val reasoning: Boolean? = null,
    /** Per-modality flags Mistral exposes per entry — used to
     *  auto-tag the model's `type` instead of guessing from the id.
     *  See `MistralCapabilities.inferType()` and the ModelType
     *  dispatch in ApiDispatch.fetchModelsOpenAiCompat. */
    val moderation: Boolean? = null,
    val ocr: Boolean? = null,
    val audio_transcription: Boolean? = null,
    val audio_speech: Boolean? = null
)

data class CohereModelsResponse(val models: List<CohereModelInfo>?)
data class CohereModelInfo(
    val name: String?,
    val endpoints: List<String>?,
    val context_length: Int? = null,
    val supports_vision: Boolean? = null,
    val finetuned: Boolean? = null
)

/** Cohere v2 rerank request. The compatibility shim does not expose
 *  /rerank — calls always go through the native api.cohere.com host. */
data class CohereRerankRequest(
    val model: String,
    val query: String,
    val documents: List<String>,
    val top_n: Int? = null,
    val return_documents: Boolean = false
)

data class CohereRerankResult(val index: Int, val relevance_score: Double)
data class CohereRerankBilledUnits(val search_units: Int? = null)
data class CohereRerankMeta(val billed_units: CohereRerankBilledUnits? = null)
data class CohereRerankResponse(
    val id: String? = null,
    val results: List<CohereRerankResult>? = null,
    val meta: CohereRerankMeta? = null,
    /** Error envelope shape — populated only on 4xx/5xx with a JSON body. */
    val message: String? = null
)

/** Mistral /v1/moderations request. Accepts an array of text inputs;
 *  one [MistralModerationResult] is returned per input. */
data class MistralModerationRequest(
    val model: String,
    val input: List<String>
)

/** Per-input result from /v1/moderations. `categories` is a map of
 *  category name → boolean (true means the category fired);
 *  `category_scores` is the same keys with 0.0–1.0 floats. We keep the
 *  shape generic (Map<String, *>) so the renderer doesn't need to be
 *  updated when Mistral adds a new category. */
data class MistralModerationResult(
    val categories: Map<String, Boolean>? = null,
    val category_scores: Map<String, Double>? = null
)

/** Token-usage block Mistral returns on a successful moderation
 *  call. Same prompt/completion/total trio the chat endpoints use,
 *  so it can be lifted straight into [TokenUsage] for cost
 *  computation. */
data class MistralModerationUsage(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null,
    val total_tokens: Int? = null
)

data class MistralModerationResponse(
    val id: String? = null,
    val model: String? = null,
    val results: List<MistralModerationResult>? = null,
    val usage: MistralModerationUsage? = null,
    /** Populated only on error envelopes — Mistral returns `message` on
     *  4xx/5xx, just like the rerank shape. */
    val message: String? = null
)

data class ClaudeModelsResponse(val data: List<ClaudeModelInfo>?)
data class ClaudeModelInfo(
    val id: String?,
    val display_name: String? = null,
    val type: String? = null,
    /** Token-limit fields exposed by Anthropic on every model entry.
     *  Replaces our previous heuristic (LiteLLM / models.dev) for
     *  Claude — provider self-report is authoritative when present. */
    val max_input_tokens: Int? = null,
    val max_tokens: Int? = null,
    /** Anthropic's per-model capability bundle. Carries the thinking
     *  flag for Claude 3.7 / 4.x extended thinking, plus image_input
     *  and pdf_input for vision-capable / PDF-ingest entries. */
    val capabilities: ClaudeModelCapabilities? = null
)
data class ClaudeModelCapabilities(
    val thinking: ClaudeModelThinking? = null,
    /** Authoritative vision flag — replaces the naming heuristic for
     *  Claude. Present on every model that accepts image content
     *  blocks (Claude 3+). */
    val image_input: ClaudeModelSupportFlag? = null,
    /** Native PDF ingestion (Claude 3.5+) — the model accepts a
     *  document content block with raw PDF bytes, no OCR needed.
     *  Surfaced as ModelCapabilities.supportsPdfInput. */
    val pdf_input: ClaudeModelSupportFlag? = null,
    /** Hard guarantee for response_format=json_schema. */
    val structured_outputs: ClaudeModelSupportFlag? = null,
    /** Per-effort-level support — Claude 3.7+ exposes which of
     *  low/medium/high/max it accepts on the reasoning_effort param.
     *  See ClaudeModelEffort. */
    val effort: ClaudeModelEffort? = null
)
data class ClaudeModelThinking(
    val supported: Boolean? = null
    // The `types` field used to be declared here as List<String>?
    // for forward-compat. Anthropic changed its shape to an object
    // (was an array of "enabled"/"adaptive" strings, now nested
    // metadata) and Gson started throwing JsonSyntaxException on
    // every Claude 3.7 / 4.x entry, killing the whole list parse
    // and blanking the model picker. We don't actually consume the
    // field; the rawResponse snapshot preserves whatever shape
    // Anthropic ships for a future parser revision to pull out, so
    // dropping the typed declaration here lets Gson silently skip
    // it regardless of shape.
)
/** Generic { "supported": bool } shape Anthropic uses for several
 *  binary capability flags (image_input, pdf_input,
 *  structured_outputs, batch, …). */
data class ClaudeModelSupportFlag(val supported: Boolean? = null)
/** Per-effort-level reasoning support. Each nested entry is the
 *  same { supported: bool } shape so non-thinking models cleanly
 *  parse with `supported = false` everywhere. */
data class ClaudeModelEffort(
    val supported: Boolean? = null,
    val low: ClaudeModelSupportFlag? = null,
    val medium: ClaudeModelSupportFlag? = null,
    val high: ClaudeModelSupportFlag? = null,
    val max: ClaudeModelSupportFlag? = null
)

data class GeminiModelsResponse(val models: List<GeminiModel>?)
data class GeminiModel(
    val name: String?,
    val displayName: String?,
    val supportedGenerationMethods: List<String>?,
    val inputTokenLimit: Int? = null,
    val outputTokenLimit: Int? = null,
    /** Top-level boolean Gemini sets on 2.5-family entries. Matches
     *  the field name in the v1beta /models response. */
    val thinking: Boolean? = null
)

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
data class OpenRouterArchitecture(
    val modality: String? = null,
    val tokenizer: String? = null,
    val instruct_type: String? = null,
    val input_modalities: List<String>? = null
)
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
