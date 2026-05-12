package com.ai.data

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

/**
 * Data class representing a supported AI service provider.
 * Identity is based on `id` only — equality, hashing, and serialization all use `id`.
 */
class AppService(
    /** Stable identifier AND human-readable label. Pre-unification
     *  builds carried three name-like fields (id / displayName /
     *  prefsKey); these collapsed into one in the id-unification
     *  refactor. UI shows `id` directly; SharedPreferences key
     *  prefixes use `id` directly (e.g. `"OpenAI_api_key"`). Bundled
     *  values come from assets/providers.json; user-added providers
     *  set their own. */
    val id: String,
    val baseUrl: String,
    val adminUrl: String,
    val defaultModel: String,
    val openRouterName: String? = null,
    val apiFormat: ApiFormat = ApiFormat.OPENAI_COMPATIBLE,
    /** Per-type API paths the provider exposes ("chat" → "v1/chat/completions",
     *  "embedding" → "v1/embeddings", etc.). `chatPath` and `responsesPath` are
     *  computed views over this map for back-compat. */
    val typePaths: Map<String, String> = emptyMap(),
    val modelsPath: String? = "v1/models",
    val seedFieldName: String = "seed",
    val supportsCitations: Boolean = false,
    val supportsSearchRecency: Boolean = false,
    val extractApiCost: Boolean = false,
    val costTicksDivisor: Double? = null,
    val modelListFormat: String = "object",
    val modelFilter: String? = null,
    val litellmPrefix: String? = null,
    val hardcodedModels: List<String>? = null,
    val defaultModelSource: String? = null,
    /** Alternate hostnames the provider's API uses besides its
     *  baseUrl host. Empty list = no aux hosts. */
    val auxHosts: List<String> = emptyList(),
    /** Full URL the rerank dispatcher POSTs to. Null → no native rerank API. */
    val nativeRerankUrl: String? = null,
    /** Full URL the moderation dispatcher POSTs to. Null → no native moderation API. */
    val nativeModerationUrl: String? = null,
    /** Full URL of a Cohere-shaped `/v1/models` capability listing. */
    val nativeCapabilityUrl: String? = null,
    /** Provider's `/v1/models` response carries authoritative pricing — harvest into PricingCache. */
    val pricingFromModelList: Boolean = false,
    /** Provider's `/v1/models` response drives pricing + type fan-out across other providers. */
    val crossProviderModelList: Boolean = false,
    /** Union persisted hardcodedModels with API list when fetcher refreshes. */
    val mergeHardcodedModels: Boolean = false,
    /** Ignore the provider's `reasoning: true` signal from /models. */
    val externalReasoningSignalUntrusted: Boolean = false,
    /** Patterns routing a model to the OpenAI Responses API. */
    val responsesApiPatterns: List<ModelPattern> = emptyList(),
    /** Patterns gating the 🧠 reasoning badge + thinking dispatch. */
    val reasoningModelPatterns: List<ModelPattern> = emptyList(),
    /** Patterns gating the `reasoning_effort` request param. Null = use [reasoningModelPatterns]. */
    val reasoningEffortAcceptPatterns: List<ModelPattern>? = null,
    /** Patterns gating the 🌐 web-search tool descriptor. */
    val webSearchModelPatterns: List<ModelPattern> = emptyList(),
    /** Patterns opting in to Anthropic's adaptive-thinking shape. */
    val adaptiveThinkingPatterns: List<ModelPattern> = emptyList(),
    /** Per-family default max_tokens (Anthropic). First match wins. */
    val maxTokensDefaults: List<MaxTokensRule> = emptyList(),
    /** Built-in endpoints the user can pick between. */
    val builtInEndpoints: List<Endpoint> = emptyList(),
    /** Per-provider override for
     *  [GeneralSettings.maxCallsPerProviderPerMinute]. Null →
     *  inherit the global default. Read by
     *  [com.ai.data.ProviderThrottle.acquire] when this provider's
     *  hostname is matched. */
    val maxCallsPerProviderPerMinute: Int? = null,
    /** Per-provider override for
     *  [GeneralSettings.maxConcurrentCallsPerProvider]. Null →
     *  inherit the global default. */
    val maxConcurrentCallsPerProvider: Int? = null,
    /** Per-provider override for the 429-retry cap. Null → inherit
     *  [GeneralSettings.maxRetriesOn429]. Read by
     *  [com.ai.data.RateLimitRetryInterceptor] when a 429 lands on
     *  this provider's host. */
    val maxRetriesOn429: Int? = null,
    /** Per-provider override for the wait between 429 retries (in
     *  milliseconds). Null → inherit
     *  [GeneralSettings.retryBackoffMs429]. */
    val retryBackoffMs429: Long? = null,
    /** Per-provider override for the 529 (server overloaded) retry cap.
     *  Null → inherit [GeneralSettings.maxRetriesOn529]. */
    val maxRetriesOn529: Int? = null,
    /** Per-provider override for the wait between 529 retries (in
     *  milliseconds). Null → inherit
     *  [GeneralSettings.retryBackoffMs529]. */
    val retryBackoffMs529: Long? = null
) {
    val modelFilterRegex: Regex? by lazy { modelFilter?.toRegex(RegexOption.IGNORE_CASE) }

    /** Path used for the /chat/completions style endpoint. */
    val chatPath: String get() = pathFor(ModelType.CHAT) ?: ModelType.DEFAULT_PATHS[ModelType.CHAT]!!

    /** Path used for OpenAI-style Responses API. Null when neither the provider
     *  nor the user-supplied defaults declare one. */
    val responsesPath: String? get() = typePaths[ModelType.RESPONSES] ?: ModelType.userDefaults[ModelType.RESPONSES]

    /** Resolve a path for any model type. Per-provider override → user-supplied
     *  global default (from AI Setup → Model Types) → hardcoded ModelType.DEFAULT_PATHS. */
    fun pathFor(type: String): String? =
        typePaths[type] ?: ModelType.userDefaults[type] ?: ModelType.DEFAULT_PATHS[type]

    override fun equals(other: Any?): Boolean = other is AppService && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = id

    /** Manual copy — AppService is intentionally NOT a data class (its
     *  equals/hashCode are id-only), so the compiler doesn't generate
     *  one. The previous workaround in [com.ai.model.Settings.withModel]
     *  /[withModelSource] was a hand-written full-field constructor
     *  call, which silently dropped the per-provider throttle
     *  overrides every time a new field landed without those two
     *  copies being updated. Funnel every field update through here
     *  so the next added field can't quietly disappear. */
    fun copy(
        id: String = this.id,
        baseUrl: String = this.baseUrl,
        adminUrl: String = this.adminUrl,
        defaultModel: String = this.defaultModel,
        openRouterName: String? = this.openRouterName,
        apiFormat: ApiFormat = this.apiFormat,
        typePaths: Map<String, String> = this.typePaths,
        modelsPath: String? = this.modelsPath,
        seedFieldName: String = this.seedFieldName,
        supportsCitations: Boolean = this.supportsCitations,
        supportsSearchRecency: Boolean = this.supportsSearchRecency,
        extractApiCost: Boolean = this.extractApiCost,
        costTicksDivisor: Double? = this.costTicksDivisor,
        modelListFormat: String = this.modelListFormat,
        modelFilter: String? = this.modelFilter,
        litellmPrefix: String? = this.litellmPrefix,
        hardcodedModels: List<String>? = this.hardcodedModels,
        defaultModelSource: String? = this.defaultModelSource,
        auxHosts: List<String> = this.auxHosts,
        nativeRerankUrl: String? = this.nativeRerankUrl,
        nativeModerationUrl: String? = this.nativeModerationUrl,
        nativeCapabilityUrl: String? = this.nativeCapabilityUrl,
        pricingFromModelList: Boolean = this.pricingFromModelList,
        crossProviderModelList: Boolean = this.crossProviderModelList,
        mergeHardcodedModels: Boolean = this.mergeHardcodedModels,
        externalReasoningSignalUntrusted: Boolean = this.externalReasoningSignalUntrusted,
        responsesApiPatterns: List<ModelPattern> = this.responsesApiPatterns,
        reasoningModelPatterns: List<ModelPattern> = this.reasoningModelPatterns,
        reasoningEffortAcceptPatterns: List<ModelPattern>? = this.reasoningEffortAcceptPatterns,
        webSearchModelPatterns: List<ModelPattern> = this.webSearchModelPatterns,
        adaptiveThinkingPatterns: List<ModelPattern> = this.adaptiveThinkingPatterns,
        maxTokensDefaults: List<MaxTokensRule> = this.maxTokensDefaults,
        builtInEndpoints: List<Endpoint> = this.builtInEndpoints,
        maxCallsPerProviderPerMinute: Int? = this.maxCallsPerProviderPerMinute,
        maxConcurrentCallsPerProvider: Int? = this.maxConcurrentCallsPerProvider,
        maxRetriesOn429: Int? = this.maxRetriesOn429,
        retryBackoffMs429: Long? = this.retryBackoffMs429,
        maxRetriesOn529: Int? = this.maxRetriesOn529,
        retryBackoffMs529: Long? = this.retryBackoffMs529
    ): AppService = AppService(
        id = id, baseUrl = baseUrl, adminUrl = adminUrl,
        defaultModel = defaultModel,
        openRouterName = openRouterName, apiFormat = apiFormat,
        typePaths = typePaths, modelsPath = modelsPath,
        seedFieldName = seedFieldName,
        supportsCitations = supportsCitations,
        supportsSearchRecency = supportsSearchRecency,
        extractApiCost = extractApiCost,
        costTicksDivisor = costTicksDivisor,
        modelListFormat = modelListFormat, modelFilter = modelFilter,
        litellmPrefix = litellmPrefix, hardcodedModels = hardcodedModels,
        defaultModelSource = defaultModelSource,
        auxHosts = auxHosts,
        nativeRerankUrl = nativeRerankUrl,
        nativeModerationUrl = nativeModerationUrl,
        nativeCapabilityUrl = nativeCapabilityUrl,
        pricingFromModelList = pricingFromModelList,
        crossProviderModelList = crossProviderModelList,
        mergeHardcodedModels = mergeHardcodedModels,
        externalReasoningSignalUntrusted = externalReasoningSignalUntrusted,
        responsesApiPatterns = responsesApiPatterns,
        reasoningModelPatterns = reasoningModelPatterns,
        reasoningEffortAcceptPatterns = reasoningEffortAcceptPatterns,
        webSearchModelPatterns = webSearchModelPatterns,
        adaptiveThinkingPatterns = adaptiveThinkingPatterns,
        maxTokensDefaults = maxTokensDefaults,
        builtInEndpoints = builtInEndpoints,
        maxCallsPerProviderPerMinute = maxCallsPerProviderPerMinute,
        maxConcurrentCallsPerProvider = maxConcurrentCallsPerProvider,
        maxRetriesOn429 = maxRetriesOn429,
        retryBackoffMs429 = retryBackoffMs429,
        maxRetriesOn529 = maxRetriesOn529,
        retryBackoffMs529 = retryBackoffMs529
    )

    companion object {
        /** Sentinel AppService used to route chat / report flows
         *  through the on-device MediaPipe LLM Inference runtime
         *  ([com.ai.data.local.LocalLlm]). Not registered in
         *  [ProviderRegistry] so it doesn't show up in provider
         *  lists / pickers / settings; surfaces by id "Local"
         *  through [findById] so persisted ChatSessions whose
         *  provider was Local can be reloaded after restart. */
        val LOCAL = AppService(
            id = "Local",
            baseUrl = "local://",
            adminUrl = "",
            defaultModel = ""
        )
        val entries: List<AppService> get() = ProviderRegistry.getAll()
        fun findById(id: String): AppService? = if (id == LOCAL.id) LOCAL else ProviderRegistry.findById(id)
        fun valueOf(id: String): AppService = findById(id)
            ?: throw IllegalArgumentException("Unknown AppService: $id")
    }
}

/**
 * Gson serializer/deserializer for AppService — serializes as string ID.
 */
class AppServiceAdapter : JsonDeserializer<AppService>, JsonSerializer<AppService> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): AppService {
        val id = json?.asString ?: throw JsonParseException("Null AppService")
        // Route through AppService.findById so the synthetic LOCAL
        // sentinel resolves — a chat session whose provider is Local
        // would otherwise fail to deserialize and silently disappear
        // from history (ProviderRegistry doesn't know about LOCAL).
        return AppService.findById(id) ?: throw JsonParseException("Unknown AppService: $id")
    }
    override fun serialize(src: AppService?, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement {
        return JsonPrimitive(src?.id ?: "")
    }
}
