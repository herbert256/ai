package com.ai.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.lang.reflect.Modifier
import java.lang.reflect.Type

// ============================================================================
// Gson factory
// ============================================================================

/**
 * Gson constructs Kotlin objects via UnsafeAllocator, bypassing the primary
 * constructor and its default values. A JSON document missing a non-null
 * Kotlin field therefore leaves that field at the JVM zero value (null),
 * silently violating the non-null contract and NPE-ing later code that
 * trusts the declared type (Bug 1).
 *
 * This factory wraps the reflective delegate adapter and, after reading,
 * coerces any still-null **collection** field to its empty default
 * (List/Set/Collection → empty, Map → empty) so iteration of a missing
 * non-null collection (e.g. `Report.agents`) can't NPE. A best-effort
 * safety net so a partial / hand-edited JSON loads instead of crashing.
 *
 * IMPORTANT: it does NOT coerce String. Java reflection can't distinguish
 * a Kotlin non-null `String` from a nullable `String?`, and a great many
 * model fields use `String? = null` as a meaningful sentinel — e.g. the
 * `*ErrorMessage` fields (null = "no error"), `icon` / `languageName` /
 * `titlePromptUsed` (null = "not generated yet"). Coercing those to ""
 * silently turns "absent" into a present-but-empty value and breaks every
 * `!= null` check (it once made the whole Get-info screen show a red ✗ on
 * every row). Non-null String fields that are genuinely missing are rare
 * and handled with field-specific defaults at their load site instead
 * (e.g. InternalPrompt.parameters in SettingsPreferences).
 */
private class NullSafeFieldAdapterFactory : TypeAdapterFactory {
    override fun <T : Any?> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        val raw = type.rawType
        // Only post-process plain data/model holders in our own packages.
        if (!raw.name.startsWith("com.ai.")) return null
        if (raw.isEnum || raw.isInterface || raw.isArray || raw.isPrimitive) return null

        val delegate = gson.getDelegateAdapter(this, type)
        val coercibleFields = raw.declaredFields.filter { f ->
            !Modifier.isStatic(f.modifiers) && !Modifier.isTransient(f.modifiers) &&
                // Collections only — NOT String (see class doc: String? sentinels).
                (List::class.java.isAssignableFrom(f.type) ||
                    Set::class.java.isAssignableFrom(f.type) ||
                    Map::class.java.isAssignableFrom(f.type) ||
                    Collection::class.java.isAssignableFrom(f.type))
        }.onEach { it.isAccessible = true }
        if (coercibleFields.isEmpty()) return delegate

        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T?) = delegate.write(out, value)
            override fun read(reader: JsonReader): T? {
                val value = delegate.read(reader) ?: return null
                for (f in coercibleFields) {
                    if (f.get(value) == null) {
                        val empty: Any = when {
                            Set::class.java.isAssignableFrom(f.type) -> emptySet<Any>()
                            Map::class.java.isAssignableFrom(f.type) -> emptyMap<Any, Any>()
                            else -> emptyList<Any>()
                        }
                        runCatching { f.set(value, empty) }
                    }
                }
                return value
            }
        }
    }
}

private fun baseGsonBuilder(): GsonBuilder = GsonBuilder()
    .registerTypeAdapter(AppService::class.java, AppServiceAdapter())
    .registerTypeAdapterFactory(NullSafeFieldAdapterFactory())

private val aiGson: Gson by lazy { baseGsonBuilder().create() }
private val aiGsonPretty: Gson by lazy { baseGsonBuilder().setPrettyPrinting().create() }
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

/** A provider's per-model `pricing` field shape varies wildly across
 *  OpenAI-compatible catalogs — Together uses an `{input,output}` object,
 *  others use `{prompt,completion}` strings, and some (Atlas Cloud) emit a
 *  JSON *array* for certain models. A rigid `TogetherPricing` mapping throws
 *  on the array shape and takes the WHOLE model-list parse down with it.
 *  This tolerates anything: parse `{input,output}` when present, else null. */
class LenientModelPricingDeserializer : JsonDeserializer<TogetherPricing?> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): TogetherPricing? {
        if (json == null || !json.isJsonObject) return null
        return try {
            val o = json.asJsonObject
            fun num(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() }
            TogetherPricing(input = num("input"), output = num("output"))
        } catch (_: Exception) { null }
    }
}

/** Mistral uses boolean capability fields; gateways such as Glama use
 *  capability-name arrays. Optional metadata must not reject an entire
 *  otherwise valid model catalog when providers use a different shape. */
class LenientModelCapabilitiesDeserializer : JsonDeserializer<MistralCapabilities?> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): MistralCapabilities? {
        if (json == null || json.isJsonNull) return null
        if (json.isJsonObject) {
            return runCatching { context?.deserialize<MistralCapabilities>(json, MistralCapabilities::class.java) }.getOrNull()
        }
        if (!json.isJsonArray) return null
        val names = json.asJsonArray.mapNotNull {
            it.takeIf { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString }?.asString
        }.toSet()
        // Unlisted flags remain unknown so other capability sources can
        // still fill them in; only map names with an established meaning.
        fun declared(vararg values: String): Boolean? = true.takeIf { values.any { it in names } }
        return MistralCapabilities(
            completion_chat = declared("completion_chat"),
            completion_fim = declared("completion_fim"),
            function_calling = declared("function_calling", "native_tool_use"),
            fine_tuning = declared("fine_tuning", "tuning"),
            vision = declared("vision", "input:image"),
            classification = declared("classification"),
            reasoning = declared("reasoning"),
            moderation = declared("moderation"),
            ocr = declared("ocr"),
            audio_transcription = declared("audio_transcription"),
            audio_speech = declared("audio_speech")
        )
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
    /** Hidden chain-of-thought, as emitted by reasoning models on most
     *  OpenAI-compatible providers (SiliconFlow, Moonshot, Z.AI,
     *  DeepInfra, …). Falls into the parser's content-fallback chain
     *  when [content] is empty (reasoner exhausted max_tokens on
     *  thinking). */
    val reasoning_content: String? = null,
    /** OpenRouter's parallel field — exact same role as
     *  [reasoning_content], different name. Some providers ship one,
     *  some the other, a few ship both. The parser falls back to
     *  whichever is non-empty. */
    val reasoning: String? = null
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
    val reasoning_effort: String? = null,
    /** Ask the server to emit a trailing usage-only chunk on streamed
     *  responses (OpenAI + most compatible providers). Lets the
     *  streaming-report path recover exact token counts for cost. */
    val stream_options: StreamOptions? = null
)

data class StreamOptions(val include_usage: Boolean)

data class OpenAiResponseFormat(val type: String = "text")

data class OpenAiChoice(val message: OpenAiMessage, val index: Int, val finish_reason: String? = null)

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
data class ClaudeMessage(val role: String, val content: Any?)

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
    val cachedContentTokenCount: Int? = null,
    // Gemini 2.5 / 3.x thinking models report hidden reasoning here.
    // Billed at the output rate, but distinct from candidatesTokenCount
    // (which is *visible* output). When the budget cap is small the
    // probe can burn it all here and produce no visible text.
    val thoughtsTokenCount: Int? = null
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
 *    - Novita ships `context_size` and `max_output_tokens`.
 *    - Fireworks ships `supports_chat` / `supports_image_input` /
 *      `context_length`.
 *  Letting them all coexist lets one OpenAiModel data class absorb
 *  whatever extra metadata a provider includes without forking parsers. */
data class OpenAiModel(
    val id: String?,
    val owned_by: String? = null,
    @JsonAdapter(LenientModelCapabilitiesDeserializer::class)
    val capabilities: MistralCapabilities? = null,
    val max_context_length: Int? = null,
    val context_length: Int? = null,
    val context_window: Int? = null,
    val context_size: Int? = null,
    val max_output_tokens: Int? = null,
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
    val supported_parameters: List<String>? = null,
    /** Mistral exposes friendly version-aliases on every model entry
     *  — `mistral-large-2407` carries `aliases: ["mistral-large-latest"]`,
     *  for instance — so a user who searches "latest" can still hit
     *  the dated id. The picker's search filter unions the aliases
     *  with the model id. */
    val aliases: List<String>? = null,
    /** Together AI exposes a per-model pricing block on its
     *  /v1/models response (USD per 1M tokens). Used to seed the
     *  TOGETHER pricing tier so Together-hosted runs don't have to
     *  fall through to LiteLLM / models.dev / Helicone for prices
     *  the provider already published. Other providers ship null —
     *  or, like Atlas Cloud, ship a different/array shape, which the
     *  lenient deserializer tolerates so it can't break the list parse. */
    @JsonAdapter(LenientModelPricingDeserializer::class)
    val pricing: TogetherPricing? = null,
    /** Together AI's per-model chat-template config block — carries
     *  recommended stop tokens. Mirrored to ModelCapabilities.
     *  defaultStopSequences so a fresh Parameters preset can seed
     *  the field. */
    val config: TogetherConfig? = null,
    /** Mistral's per-model recommended temperature. Other providers
     *  don't ship this. Fed into ModelCapabilities.defaultTemperature
     *  for the same Parameters-preset seeding. */
    val default_model_temperature: Float? = null,
    /** Mistral's deprecation date (ISO-8601) when present. The
     *  picker can flag deprecated entries with a small ⚠ badge so
     *  the user knows to migrate. Null = active. */
    val deprecation: String? = null,
    /** Mistral's recommended replacement when an entry has been
     *  deprecated. Pairs with [deprecation] — picker badge can read
     *  "deprecated → use $deprecation_replacement_model". */
    val deprecation_replacement_model: String? = null
)

/** Together AI's per-model pricing block. Values are USD per 1M
 *  tokens; the dispatcher divides by 1_000_000 to land the
 *  per-token price [com.ai.data.PricingCache.ModelPricing] expects.
 *  Multi-modal fields (image / video / transcribe) are out of scope
 *  for the chat tier — captured here for completeness so a future
 *  per-modality cost feature can read them without re-fetching. */
/** Together AI's per-model `pricing` block. Only the chat token
 *  fields (`input` / `output`, USD per 1M tokens) are consumed — by
 *  fetchModelsOpenAi to seed the TOGETHER pricing tier. The block
 *  also carries `image`, `video`, `transcribe` keys whose values are
 *  *objects* on image/video/audio entries (`{example_price, ...}` or
 *  `{price_per_minute}`), not plain numbers, so declaring them here
 *  as `Double?` would make Gson throw on every refresh and zero out
 *  the whole catalog. We deliberately leave them undeclared so Gson
 *  skips them. */
data class TogetherPricing(
    val input: Double? = null,
    val output: Double? = null
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

/** Together AI's per-model `config` block carries the chat-template
 *  + bos / eos / stop tokens. Surfaced via ModelCapabilities so a
 *  Parameters preset can pre-fill stop sequences from the provider's
 *  recommendation rather than leaving the field blank. */
data class TogetherConfig(
    val stop: List<String>? = null,
    val bos_token: String? = null,
    val eos_token: String? = null,
    val chat_template: String? = null
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

data class GeminiBatchEmbedRequest(val requests: List<GeminiEmbedContentRequest>)
data class GeminiEmbedContentRequest(val model: String, val content: GeminiContent)
data class GeminiBatchEmbedResponse(val embeddings: List<GeminiEmbedding>?)
data class GeminiEmbedding(val values: List<Double>?)

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

// `usage` is non-null only on the trailing chunk emitted when the request
// set stream_options.include_usage=true (choices is empty on that chunk).
// Used by the streaming-report path to keep token usage / cost exact.
data class OpenAiStreamChunk(val id: String?, val choices: List<StreamChoice>?, val created: Long?, val usage: OpenAiUsage? = null)
data class StreamChoice(val index: Int?, val delta: StreamDelta?, val finish_reason: String?)
data class StreamDelta(val role: String? = null, val content: String? = null, val reasoning_content: String? = null, val reasoning: String? = null)

data class ClaudeStreamEvent(
    val type: String,
    val index: Int? = null,
    val delta: ClaudeStreamDelta? = null,
    val content_block: ClaudeStreamContentBlock? = null,
    // Anthropic splits usage across two events: `message_start` carries the
    // input (+ cache) counts under message.usage; `message_delta` carries the
    // running output count under usage. The streaming-report collector merges
    // both into the final TokenUsage.
    val message: ClaudeStreamMessage? = null,
    val usage: ClaudeUsage? = null
)
data class ClaudeStreamMessage(val usage: ClaudeUsage? = null)
data class ClaudeStreamDelta(val type: String? = null, val text: String? = null, val stop_reason: String? = null)
data class ClaudeStreamContentBlock(val type: String? = null, val text: String? = null)

// `usageMetadata` rides along on Gemini stream chunks (cumulative; the final
// chunk holds the complete counts).
data class GeminiStreamChunk(val candidates: List<GeminiStreamCandidate>?, val usageMetadata: GeminiUsageMetadata? = null)
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
    val supported_parameters: List<String>? = null,
    /** Training-data cutoff date the model creator declares (ISO
     *  YYYY-MM-DD). Useful on Model Info — users routinely ask
     *  "does this model know about <recent thing>". Null when the
     *  upstream catalog doesn't carry it. */
    val knowledge_cutoff: String? = null,
    /** ISO-8601 expiration (deprecation / removal) date. Pickers
     *  flag entries with a ⚠ badge so the user knows to migrate
     *  before the upstream pulls the model. Null = active. */
    val expiration_date: String? = null,
    /** OpenRouter's per-model default sampling — the values the
     *  upstream applies when a parameter is omitted. Used to seed
     *  fresh Parameters presets so a user creating one doesn't
     *  start from blank fields. */
    val default_parameters: OpenRouterDefaultParameters? = null
)

data class OpenRouterDefaultParameters(
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val repetition_penalty: Float? = null
)

data class OpenRouterPricing(
    val prompt: String? = null,
    val completion: String? = null,
    val image: String? = null,
    val request: String? = null,
    /** Per-query cost when the OpenAI-Responses-style web-search
     *  tool is invoked on top of the chat call. OpenRouter
     *  publishes this on every model that supports the tool;
     *  surfaces in the per-call cost when the user has the 🌐
     *  toggle on. Strings here mirror the prompt/completion shape
     *  (USD per token / per call). */
    val web_search: String? = null,
    /** Discounted rate for cached-prompt re-reads. OpenAI bills
     *  cached input at ~50% of normal; OpenRouter exposes the
     *  exact figure per model. Surfaces in TierBreakdown so the
     *  Costs page can show the cache rate alongside prompt /
     *  completion. */
    val input_cache_read: String? = null
)
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

/** OpenAI-compatible usage shapes differ by provider. Most providers report
 *  prompt_tokens as a cached-inclusive total, so we subtract the cached count
 *  to get the fresh bucket. Providers such as xAI flatten cached_tokens while
 *  keeping prompt_tokens as fresh input; their provider definition sets
 *  promptTokensIncludeCachedTokens=false so fresh input passes through. */
fun OpenAiUsage.toTokenUsage(provider: AppService? = null): TokenUsage {
    val total = prompt_tokens ?: input_tokens ?: 0
    val cached = prompt_tokens_details?.cached_tokens
        ?: prompt_cache_hit_tokens
        ?: cached_tokens
        ?: 0
    val fresh = prompt_cache_miss_tokens
        ?: if (provider?.promptTokensIncludeCachedTokens == false) total else (total - cached).coerceAtLeast(0)
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
        cachedInputTokens = cached,
        reasoningTokens = thoughtsTokenCount ?: 0
    )
}

// ============================================================================
// Cost extraction (legacy — used internally by the toTokenUsage helpers above
// so the apiCost field on TokenUsage stays populated when the provider
// returned an explicit cost)
// ============================================================================

private const val XAI_COST_TICKS_DIVISOR = 10_000_000_000.0

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
    if (provider?.id == "xAI") {
        usage.cost_in_usd_ticks?.let { return it / XAI_COST_TICKS_DIVISOR }
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

// ============================================================================
// Replicate predictions API (ApiFormat.REPLICATE)
// ============================================================================

/** Body of `POST /v1/models/{owner}/{name}/predictions`. The per-model input
 *  schema varies, but the meta-llama / mistral instruct models take a flat
 *  `prompt` (+ optional `system_prompt`) and the usual sampling knobs. */
data class ReplicatePredictionRequest(val input: ReplicateInput)

data class ReplicateInput(
    val prompt: String,
    val system_prompt: String? = null,
    val max_tokens: Int? = null,
    val temperature: Double? = null,
    val top_p: Double? = null
)

/** A Replicate prediction. With `Prefer: wait` the POST returns the completed
 *  object: `status` = succeeded/failed/processing, `output` = the result, and
 *  `metrics` carries token counts. For LLMs `output` is an array of token
 *  strings to join; some models return a single string instead — hence the
 *  raw [com.google.gson.JsonElement]. */
data class ReplicatePredictionResponse(
    val id: String? = null,
    val status: String? = null,
    val output: com.google.gson.JsonElement? = null,
    val error: String? = null,
    val metrics: ReplicateMetrics? = null
)

data class ReplicateMetrics(
    val input_token_count: Int? = null,
    val output_token_count: Int? = null
)

/** Join the prediction `output` (array of token strings, or a single string)
 *  into the generated text, or null when empty/absent. */
fun ReplicatePredictionResponse.outputText(): String? {
    val o = output ?: return null
    val text = when {
        o.isJsonArray -> o.asJsonArray.mapNotNull { e -> if (e.isJsonPrimitive) e.asString else null }.joinToString("")
        o.isJsonPrimitive -> o.asString
        else -> null
    }
    return text?.takeIf { it.isNotEmpty() }
}

fun ReplicateMetrics.toTokenUsage(): TokenUsage =
    TokenUsage(inputTokens = input_token_count ?: 0, outputTokens = output_token_count ?: 0)
