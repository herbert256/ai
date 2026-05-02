package com.ai.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cached model pricing with five-tier lookup: API > LITELLM > OVERRIDE > OPENROUTER > DEFAULT.
 * Exception: For the OpenRouter provider itself, OPENROUTER pricing is checked first.
 *
 * LITELLM sits ahead of OVERRIDE so the curated BerriAI/litellm prices win over
 * stale manual entries by default — users now only need to keep an override for
 * models LiteLLM doesn't track, not for routine price refreshes.
 */
object PricingCache {
    private const val PREFS_NAME = "pricing_cache"
    private const val KEY_OPENROUTER_PRICING = "openrouter_pricing"
    private const val KEY_OPENROUTER_TIMESTAMP = "openrouter_timestamp"
    private const val KEY_LITELLM_PRICING = "litellm_pricing"
    private const val KEY_LITELLM_META = "litellm_meta"
    private const val KEY_LITELLM_TIMESTAMP = "litellm_timestamp"
    private const val KEY_MODELS_DEV_PRICING = "models_dev_pricing"
    private const val KEY_MODELS_DEV_META = "models_dev_meta"
    private const val KEY_MODELS_DEV_TIMESTAMP = "models_dev_timestamp"
    // Helicone — pricing aggregator (helicone.ai/api/llm-costs). Pricing only.
    private const val KEY_HELICONE_PRICING = "helicone_pricing"
    private const val KEY_HELICONE_PATTERNS = "helicone_patterns"
    private const val KEY_HELICONE_TIMESTAMP = "helicone_timestamp"
    // llm-prices.com — Simon Willison's curated per-vendor pricing tables.
    private const val KEY_LLMPRICES_PRICING = "llmprices_pricing"
    private const val KEY_LLMPRICES_TIMESTAMP = "llmprices_timestamp"
    // Artificial Analysis — pricing + intelligence/speed scores. Needs API key.
    // _v2 keys: the original parser used AA's UUID `id` field as the
    // composite key, so model-name lookups never matched. Bumping the key
    // names invalidates that bad data on existing installs — next refresh
    // writes the slug-keyed format and reads pick up correctly.
    private const val KEY_AA_PRICING = "aa_pricing_v2"
    private const val KEY_AA_META = "aa_meta_v2"
    private const val KEY_AA_TIMESTAMP = "aa_timestamp_v2"
    private const val KEY_MANUAL_PRICING = "manual_pricing"
    private const val CACHE_DURATION_MS = 7L * 24 * 60 * 60 * 1000

    private val gson = createAppGson()
    private val lock = Any()
    private val mapModelPricingType: Type = object : TypeToken<Map<String, ModelPricing>>() {}.type
    private val mutableMapModelPricingType: Type = object : TypeToken<MutableMap<String, ModelPricing>>() {}.type
    private val mapStringMapType: Type = object : TypeToken<Map<String, Map<String, Any>>>() {}.type
    private val listSupportedParamsType: Type = object : TypeToken<List<ModelSupportedParametersEntry>>() {}.type

    @Volatile private var manualPricing: MutableMap<String, ModelPricing>? = null
    @Volatile private var openRouterPricing: Map<String, ModelPricing>? = null
    @Volatile private var litellmPricing: Map<String, ModelPricing>? = null
    /** Capability sidecar to litellmPricing — populated alongside it from the
     *  same parse pass. Lets vision/web-search/mode lookups consult LiteLLM
     *  without re-loading the 1.2 MB raw JSON. */
    @Volatile private var litellmMeta: Map<String, LiteLLMMeta>? = null
    /** models.dev pricing — fallback tier consulted when LiteLLM has no
     *  matching entry. Keyed `<provider>/<modelId>` to mirror LiteLLM. */
    @Volatile private var modelsDevPricing: Map<String, ModelPricing>? = null
    @Volatile private var modelsDevMeta: Map<String, ModelsDevMeta>? = null
    /** Helicone exact-match entries (operator="equals"), keyed `<provider>/<modelId>`
     *  using the provider name from Helicone lowercased. */
    @Volatile private var heliconePricing: Map<String, ModelPricing>? = null
    /** Helicone non-exact entries (operator="startsWith" / "includes") sorted
     *  by descending pattern length so the longest-matching prefix wins. */
    @Volatile private var heliconePatterns: List<HeliconePattern>? = null
    /** llm-prices.com per-vendor curated pricing — pulled from simonw/llm-prices
     *  on GitHub. Composite key `<vendor>/<modelId>`. */
    @Volatile private var llmPricesPricing: Map<String, ModelPricing>? = null
    /** Artificial Analysis pricing — ships alongside intelligence_index and
     *  speed scores. Composite key `<host>/<modelId>` (lowercased). */
    @Volatile private var aaPricing: Map<String, ModelPricing>? = null
    @Volatile private var aaMeta: Map<String, ArtificialAnalysisMeta>? = null
    @Volatile private var openRouterTimestamp: Long = 0
    @Volatile private var litellmTimestamp: Long = 0
    @Volatile private var modelsDevTimestamp: Long = 0
    @Volatile private var heliconeTimestamp: Long = 0
    @Volatile private var llmPricesTimestamp: Long = 0
    @Volatile private var aaTimestamp: Long = 0
    @Volatile private var preloadCompleted = false

    // Per-(provider, model) memoization for the LiteLLM / models.dev meta
    // lookups. findLiteLLMMeta and findModelsDevMeta otherwise do two full
    // ~1k-entry scans per call (findBestPrefixedMatch + findLatestAliasKey),
    // which dominated render time on screens that show many model rows
    // (each row asks for vision + web-search badges, so 4 scans per row).
    // Cleared whenever the underlying catalog map is reassigned. The
    // sentinel encodes "looked up, found nothing" — without it a missing
    // entry would re-scan on every call.
    private val litellmMetaLookupCache = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val modelsDevMetaLookupCache = java.util.concurrent.ConcurrentHashMap<String, Any>()
    // Same memoization for the per-row pricing lookup. The catalog
    // contains ~1k entries and getPricing is called from every row of
    // every cost table / model picker; without this, scrolling a long
    // list runs findBestPrefixedMatch O(rows × catalogSize) times.
    private val litellmPricingLookupCache = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val MISSING_META: Any = Any()

    data class ModelPricing(
        val modelId: String,
        val promptPrice: Double,
        val completionPrice: Double,
        val source: String = "unknown",
        // Cache-aware pricing — null means "no cache rate, charge full input
        // for cached tokens". Default factor applied when LiteLLM doesn't
        // surface the explicit key (most providers settle around 0.1×–0.5×
        // input for cache reads; we leave nulls and the cost helper falls
        // back to promptPrice rather than guess).
        val cachedReadPrice: Double? = null,
        val cachedWritePrice: Double? = null,
        // Above-200k context tier (Gemini 2.5/3 Pro, legacy Anthropic Sonnet 4
        // before the 4.6 GA, DashScope Qwen-Long). Charged per-call when the
        // current request crosses the threshold.
        val promptPriceAbove200k: Double? = null,
        val completionPriceAbove200k: Double? = null,
        val cachedReadPriceAbove200k: Double? = null,
        val cachedWritePriceAbove200k: Double? = null,
        // Per-query pricing for rerank-mode models (Cohere rerank-v3.5 /
        // rerank-v4.0-fast etc.). Cohere bills $2/1000 searches → 0.002
        // per search-unit. Token-based pricing fields are 0 for these
        // models because they don't bill per token at all. Read from
        // LiteLLM's `input_cost_per_query` field.
        val perQueryPrice: Double = 0.0
    )

    fun needsOpenRouterRefresh(context: Context): Boolean {
        ensureLoaded(context)
        if (openRouterPricing.isNullOrEmpty()) return true
        return System.currentTimeMillis() - openRouterTimestamp > CACHE_DURATION_MS
    }

    fun getOpenRouterCacheAge(context: Context): String {
        ensureLoaded(context)
        if (openRouterTimestamp == 0L) return "never fetched"
        val ageMs = System.currentTimeMillis() - openRouterTimestamp
        val days = ageMs / (24 * 60 * 60 * 1000)
        val hours = (ageMs % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000)
        return when { days > 0 -> "${days}d ${hours}h ago"; hours > 0 -> "${hours}h ago"; else -> "just now" }
    }

    fun saveOpenRouterPricing(context: Context, pricing: Map<String, ModelPricing>) = synchronized(lock) {
        openRouterPricing = pricing
        openRouterTimestamp = System.currentTimeMillis()
        getPrefs(context).edit {
            putString(KEY_OPENROUTER_PRICING, gson.toJson(pricing))
            putLong(KEY_OPENROUTER_TIMESTAMP, openRouterTimestamp)
        }
    }

    // Manual pricing overrides
    fun setManualPricing(context: Context, provider: AppService, model: String, promptPrice: Double, completionPrice: Double) = synchronized(lock) {
        ensureLoaded(context)
        val key = "${provider.id}:$model"
        val map = manualPricing ?: mutableMapOf<String, ModelPricing>().also { manualPricing = it }
        map[key] = ModelPricing(model, promptPrice, completionPrice, "OVERRIDE")
        saveManualPricing(context)
    }

    fun removeManualPricing(context: Context, provider: AppService, model: String) = synchronized(lock) {
        ensureLoaded(context)
        manualPricing?.remove("${provider.id}:$model")
        saveManualPricing(context)
    }

    /** Drop manual cost overrides that are dormant or redundant. An entry
     *  is removed when any of these holds:
     *   1. LiteLLM has a price for the model — override sits behind LiteLLM
     *      in the lookup, so the manual entry is never read.
     *   2. OpenRouter has a price — for OpenRouter-the-provider OPENROUTER
     *      is consulted first; for other providers the user is opting to
     *      trust OpenRouter pricing over the manual entry.
     *   3. The override prices equal the DEFAULT_PRICING fallback.
     *   4. The override prices equal what getPricingWithoutOverride would
     *      have returned anyway.
     *  Returns the number of entries removed. */
    fun cleanupRedundantManualOverrides(context: Context): Int = synchronized(lock) {
        ensureLoaded(context)
        val entries = manualPricing?.toMap() ?: return 0
        var removed = 0
        for ((key, override) in entries) {
            val parts = key.split(":", limit = 2)
            val providerId = parts.getOrNull(0) ?: continue
            val modelId = parts.getOrNull(1) ?: continue
            val service = AppService.findById(providerId) ?: continue
            val breakdown = getTierBreakdown(context, service, modelId)
            val matchesDefault = pricesEqual(override, breakdown.default)
            val withoutOverride = getPricingWithoutOverride(context, service, modelId)
            val matchesWithoutOverride = pricesEqual(override, withoutOverride)
            val shouldRemove = breakdown.litellm != null ||
                breakdown.modelsDev != null ||
                breakdown.helicone != null ||
                breakdown.llmPrices != null ||
                breakdown.artificialAnalysis != null ||
                breakdown.openrouter != null ||
                matchesDefault ||
                matchesWithoutOverride
            if (shouldRemove) {
                manualPricing?.remove(key)
                removed++
            }
        }
        if (removed > 0) saveManualPricing(context)
        removed
    }

    private fun pricesEqual(a: ModelPricing, b: ModelPricing): Boolean =
        kotlin.math.abs(a.promptPrice - b.promptPrice) < 1e-12 &&
            kotlin.math.abs(a.completionPrice - b.completionPrice) < 1e-12

    fun getManualPricing(context: Context, provider: AppService, model: String): ModelPricing? {
        ensureLoaded(context); return manualPricing?.get("${provider.id}:$model")
    }

    fun getAllManualPricing(context: Context): Map<String, ModelPricing> { ensureLoaded(context); return manualPricing?.toMap() ?: emptyMap() }

    fun setAllManualPricing(context: Context, pricing: Map<String, ModelPricing>) {
        manualPricing = pricing.toMutableMap(); saveManualPricing(context)
    }

    private fun saveManualPricing(context: Context) { getPrefs(context).edit { putString(KEY_MANUAL_PRICING, gson.toJson(manualPricing)) } }

    private val DEFAULT_PRICING = ModelPricing("default", 25.00e-6, 75.00e-6, "DEFAULT")

    /**
     * Compute the cost of a call. Trusts usage.apiCost when populated by the
     * provider (OpenRouter, Perplexity); otherwise applies cache-aware,
     * tier-aware token math.
     *
     *   • Cached input tokens charged at the cache-read rate (or full input
     *     rate if no cache rate is known).
     *   • Anthropic cache-creation tokens charged at the cache-write rate.
     *   • Above-200k tier prices applied per-call when the request crosses
     *     the threshold (Gemini Pro tiers, legacy Anthropic Sonnet 4).
     */
    fun computeCost(usage: TokenUsage, pricing: ModelPricing): Double {
        usage.apiCost?.let { return it }
        val totalInput = usage.inputTokens + usage.cachedInputTokens + usage.cacheCreationTokens
        val highTier = totalInput > 200_000 && pricing.promptPriceAbove200k != null
        val pIn = if (highTier) pricing.promptPriceAbove200k!! else pricing.promptPrice
        val pOut = if (highTier) (pricing.completionPriceAbove200k ?: pricing.completionPrice) else pricing.completionPrice
        val pCacheR = if (highTier) (pricing.cachedReadPriceAbove200k ?: pricing.cachedReadPrice ?: pIn)
                     else (pricing.cachedReadPrice ?: pIn)
        val pCacheW = if (highTier) (pricing.cachedWritePriceAbove200k ?: pricing.cachedWritePrice ?: pIn)
                     else (pricing.cachedWritePrice ?: pIn)
        return usage.inputTokens * pIn +
            usage.cachedInputTokens * pCacheR +
            usage.cacheCreationTokens * pCacheW +
            usage.outputTokens * pOut
    }

    /**
     * Warm the in-memory caches in the background. Safe to call repeatedly; only runs once.
     * Compose code that calls [getPricing] synchronously won't have to block on a 1.2MB
     * asset parse on first use.
     */
    fun preloadAsync(context: Context, scope: kotlinx.coroutines.CoroutineScope) {
        if (preloadCompleted) return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            ensureLoaded(context)
            preloadCompleted = true
        }
    }

    /** Synchronous load — caller MUST be off the main thread. Used by
     *  bootstrap migrations that need LiteLLM / models.dev populated
     *  before running [Settings.recomputeAllCapabilities]. */
    fun ensureLoadedBlocking(context: Context) {
        ensureLoaded(context)
        preloadCompleted = true
    }

    /**
     * Get pricing for a model using five-tier lookup.
     *
     * If preload hasn't finished (caches still null) and this was called from the main thread,
     * avoid the synchronous 1.2MB parse by returning DEFAULT_PRICING — the UI will refresh
     * once the preload completes and recomposition reads fresh values.
     */
    fun getPricing(context: Context, provider: AppService, model: String): ModelPricing {
        if (!preloadCompleted && isMainThread()) return DEFAULT_PRICING
        ensureLoaded(context)
        val isOpenRouter = provider.id == "OPENROUTER"
        if (isOpenRouter) findOpenRouterPricing(provider, model)?.let { return it }
        // Curated bulk sources first, then user OVERRIDE, then OPENROUTER
        // fallback, then Helicone as last resort before DEFAULT. Order:
        // LITELLM → MODELSDEV → LLMPRICES → AA → OVERRIDE → OPENROUTER →
        // HELICONE. Helicone is intentionally placed last (even after the
        // OpenRouter fallback) because its catalog has known data-quality
        // issues — wrong claude-opus-4-5 rate, "includes"-operator rules
        // that hijack whole families, dated-snapshot price drift. We
        // still consult it before giving up to DEFAULT so the data has
        // some chance of helping when no other source has the model.
        findLiteLLMPricing(provider, model)?.let { return it }
        findModelsDevPricing(provider, model)?.let { return it }
        findLLMPricesPricing(provider, model)?.let { return it }
        findArtificialAnalysisPricing(provider, model)?.let { return it }
        manualPricing?.get("${provider.id}:$model")?.let { return it }
        if (!isOpenRouter) findOpenRouterPricing(provider, model)?.let { return it }
        findHeliconePricing(provider, model)?.let { return it }
        return DEFAULT_PRICING
    }

    private fun isMainThread(): Boolean =
        android.os.Looper.myLooper() == android.os.Looper.getMainLooper()

    fun getPricingWithoutOverride(context: Context, provider: AppService, model: String): ModelPricing {
        ensureLoaded(context)
        findOpenRouterPricing(provider, model)?.let { return it }
        findLiteLLMPricing(provider, model)?.let { return it }
        findModelsDevPricing(provider, model)?.let { return it }
        findLLMPricesPricing(provider, model)?.let { return it }
        findArtificialAnalysisPricing(provider, model)?.let { return it }
        findHeliconePricing(provider, model)?.let { return it }
        return DEFAULT_PRICING
    }

    /** Context-free, in-memory-only variant of [getPricing] used by
     *  [com.ai.model.Settings.recomputeCapabilities] to fill the
     *  per-provider modelPricing snapshot. Caller must have triggered
     *  [ensureLoadedBlocking] / [preloadAsync] first; this method itself
     *  never touches SharedPreferences or the bundled asset and never
     *  blocks. Returns DEFAULT_PRICING when catalogs aren't loaded. */
    fun lookupPricing(provider: AppService, model: String): ModelPricing {
        val isOpenRouter = provider.id == "OPENROUTER"
        if (isOpenRouter) findOpenRouterPricing(provider, model)?.let { return it }
        findLiteLLMPricing(provider, model)?.let { return it }
        findModelsDevPricing(provider, model)?.let { return it }
        findLLMPricesPricing(provider, model)?.let { return it }
        findArtificialAnalysisPricing(provider, model)?.let { return it }
        manualPricing?.get("${provider.id}:$model")?.let { return it }
        if (!isOpenRouter) findOpenRouterPricing(provider, model)?.let { return it }
        findHeliconePricing(provider, model)?.let { return it }
        return DEFAULT_PRICING
    }

    /** OpenRouter and Anthropic disagree on punctuation — Anthropic ships
     *  "claude-opus-4-6" while OpenRouter catalogs "anthropic/claude-opus-4.6".
     *  Normalize both sides to lowercase-dash for matching. */
    private fun normalizeModelId(s: String): String = s.replace('.', '-').lowercase()

    /** Resolve a `-latest` rolling alias to the catalog's most recent dated
     *  snapshot. Strips the `-latest` suffix, finds every key whose
     *  remainder after the stripped base is a date-like token (digits and
     *  dashes only, must contain at least one digit), and picks the
     *  lexically max — works for YYYYMMDD ("claude-3-5-sonnet-20241022"),
     *  YYYY-MM-DD ("gpt-4o-2024-11-20"), and YYMM ("mistral-large-2411").
     *
     *  Candidates are bucketed by prefix so the provider's own catalog
     *  prefix wins over arbitrary other prefixes (azure/, bedrock/,
     *  vertex_ai/, etc.). Priority: (0) bare key → (1) declared
     *  litellmPrefix → (2) provider.id.lowercase() → (3) any other prefix.
     *
     *  LiteLLM doesn't catalog `-latest` aliases, so this fallback fires
     *  whenever the user has a rolling-alias model id configured. Returns
     *  null when the input doesn't end with `-latest` or no dated sibling
     *  is found in [keys]. */
    private fun findLatestAliasKey(
        keys: Set<String>, model: String,
        declaredPrefix: String?, idLowercase: String
    ): String? {
        if (!model.endsWith("-latest", ignoreCase = true)) return null
        val base = normalizeModelId(model.dropLast("-latest".length))
        if (base.isEmpty()) return null
        val declaredBase = declaredPrefix?.takeIf { it.isNotBlank() }?.let { "${normalizeModelId(it)}/$base" }
        val idBase = "${normalizeModelId(idLowercase)}/$base"
        val buckets = arrayOfNulls<Pair<String, String>>(4)
        for (key in keys) {
            val nk = normalizeModelId(key)
            var priority = -1
            var suffix = ""
            if (nk.startsWith("$base-")) {
                priority = 0; suffix = nk.substring(base.length + 1)
            } else if (declaredBase != null && nk.startsWith("$declaredBase-")) {
                priority = 1; suffix = nk.substring(declaredBase.length + 1)
            } else if (nk.startsWith("$idBase-")) {
                priority = 2; suffix = nk.substring(idBase.length + 1)
            } else if (nk.contains("/")) {
                val tail = nk.substringAfterLast('/')
                if (tail.startsWith("$base-")) {
                    priority = 3; suffix = tail.substring(base.length + 1)
                }
            }
            if (priority < 0) continue
            if (suffix.isEmpty()) continue
            if (suffix.any { !it.isDigit() && it != '-' }) continue
            if (suffix.none { it.isDigit() }) continue
            val cur = buckets[priority]
            if (cur == null || suffix > cur.second) buckets[priority] = key to suffix
        }
        return buckets.firstOrNull { it != null }?.first
    }

    private fun findOpenRouterPricing(provider: AppService, model: String): ModelPricing? {
        val pricing = openRouterPricing ?: return null
        // Exact-key fast path.
        pricing[model]?.let { return it }
        provider.openRouterName?.let { prefix -> pricing["$prefix/$model"]?.let { return it } }
        // Bucketed normalized scan — prefer the provider's own prefix when
        // multiple prefixes carry the same model id (avoids picking up
        // azure/bedrock/vertex variants for a native-API entry).
        return findBestPrefixedMatch(pricing, provider, model)
            ?: findLatestAliasKey(pricing.keys, model, provider.openRouterName, provider.id.lowercase())?.let { pricing[it] }
    }

    /** Look up the LiteLLM capability sidecar for (provider, model) using
     *  the same dash/dot normalization the pricing lookup uses. Returns
     *  null when LiteLLM hasn't loaded yet OR the model isn't cataloged.
     *  Memoized per (provider, model); the cache is cleared when the
     *  underlying catalog reloads. */
    private fun findLiteLLMMeta(provider: AppService, model: String): LiteLLMMeta? {
        val meta = litellmMeta ?: return null
        val cacheKey = "${provider.id}|$model"
        val cached = litellmMetaLookupCache[cacheKey]
        if (cached != null) {
            @Suppress("UNCHECKED_CAST")
            return if (cached === MISSING_META) null else cached as LiteLLMMeta
        }
        val resolved = meta[model]
            ?: provider.litellmPrefix?.let { prefix -> meta["$prefix/$model"] }
            ?: findBestPrefixedMatch(meta, provider, model, useLitellmPrefix = true)
            ?: findLatestAliasKey(meta.keys, model, provider.litellmPrefix, provider.id.lowercase())?.let { meta[it] }
        litellmMetaLookupCache[cacheKey] = resolved ?: MISSING_META
        return resolved
    }

    /** True/false from LiteLLM's supports_vision flag, or null when
     *  LiteLLM has no entry for this (provider, model) or doesn't carry
     *  the flag. Callers use this as a first authoritative test before
     *  falling back to the naming heuristic. */
    fun liteLLMSupportsVision(provider: AppService, model: String): Boolean? =
        findLiteLLMMeta(provider, model)?.supportsVision

    fun liteLLMSupportsWebSearch(provider: AppService, model: String): Boolean? =
        findLiteLLMMeta(provider, model)?.supportsWebSearch

    /** Paths from LiteLLM's supported_endpoints (e.g. "/v1/responses",
     *  "/v1/chat/completions"). Caller combines with provider.baseUrl
     *  to produce a full URL. Empty / null when no entry. */
    fun liteLLMSupportedEndpoints(provider: AppService, model: String): List<String>? =
        findLiteLLMMeta(provider, model)?.supportedEndpoints

    fun liteLLMSupportsSystemMessages(provider: AppService, model: String): Boolean? =
        findLiteLLMMeta(provider, model)?.supportsSystemMessages

    fun liteLLMSupportsResponseSchema(provider: AppService, model: String): Boolean? =
        findLiteLLMMeta(provider, model)?.supportsResponseSchema

    fun liteLLMSupportsReasoning(provider: AppService, model: String): Boolean? =
        findLiteLLMMeta(provider, model)?.supportsReasoning

    fun liteLLMSupportsNativeStreaming(provider: AppService, model: String): Boolean? =
        findLiteLLMMeta(provider, model)?.supportsNativeStreaming

    fun liteLLMToolUseOverhead(provider: AppService, model: String): Int? =
        findLiteLLMMeta(provider, model)?.toolUseSystemPromptTokens

    /** ModelType constant derived from LiteLLM's `mode` field, or null
     *  when no mapping applies. "chat" → CHAT, "embedding" → EMBEDDING,
     *  etc. CHAT is rarely useful (it's the default heuristic anyway) so
     *  callers may want to skip it; we still return it for transparency. */
    fun liteLLMModelType(provider: AppService, model: String): String? {
        val mode = findLiteLLMMeta(provider, model)?.mode?.lowercase() ?: return null
        return when (mode) {
            "chat", "completion" -> ModelType.CHAT
            "responses" -> ModelType.RESPONSES
            "embedding" -> ModelType.EMBEDDING
            "image_generation", "image_generations" -> ModelType.IMAGE
            "audio_transcription" -> ModelType.STT
            "audio_speech" -> ModelType.TTS
            "moderation", "moderations" -> ModelType.MODERATION
            "rerank" -> ModelType.RERANK
            else -> null
        }
    }

    private fun findLiteLLMPricing(provider: AppService, model: String): ModelPricing? {
        val pricing = litellmPricing ?: return null
        val cacheKey = "${provider.id}|$model"
        val cached = litellmPricingLookupCache[cacheKey]
        if (cached != null) {
            return if (cached === MISSING_META) null else cached as ModelPricing
        }
        // Exact-key fast path, then prefix variants, then prefix-aware scan.
        val resolved = pricing[model]
            ?: provider.litellmPrefix?.let { prefix -> pricing["$prefix/$model"] }
            ?: findBestPrefixedMatch(pricing, provider, model, useLitellmPrefix = true)
            ?: findLatestAliasKey(pricing.keys, model, provider.litellmPrefix, provider.id.lowercase())?.let { pricing[it] }
        litellmPricingLookupCache[cacheKey] = resolved ?: MISSING_META
        return resolved
    }

    /** Bucket-rank a [target]-matching scan over [map] and return the
     *  highest-priority value, where buckets are: (0) bare key, (1)
     *  declared prefix, (2) provider.id.lowercase()/, (3) any other
     *  prefix. This prevents picking azure/bedrock/vertex variants when
     *  the provider's own catalog row exists for the same model. */
    private fun <V> findBestPrefixedMatch(
        map: Map<String, V>, provider: AppService, model: String,
        useLitellmPrefix: Boolean = false
    ): V? {
        val target = normalizeModelId(model)
        val declaredPrefix = if (useLitellmPrefix) provider.litellmPrefix else provider.openRouterName
        val targetDeclared = declaredPrefix?.let { "${normalizeModelId(it)}/$target" }
        val targetId = "${provider.id.lowercase()}/$target"
        @Suppress("UNCHECKED_CAST")
        val buckets = arrayOfNulls<Any>(4) as Array<V?>
        for ((key, value) in map) {
            val k = normalizeModelId(key)
            val priority = when {
                k == target -> 0
                targetDeclared != null && k == targetDeclared -> 1
                k == targetId -> 2
                k.endsWith("/$target") -> 3
                else -> -1
            }
            if (priority < 0) continue
            if (priority == 0) return value // bare match is unique
            if (buckets[priority] == null) buckets[priority] = value
        }
        return buckets.firstOrNull { it != null }
    }

    fun getAllPricing(context: Context): Map<String, ModelPricing> {
        ensureLoaded(context)
        val merged = mutableMapOf<String, ModelPricing>()
        litellmPricing?.let { merged.putAll(it) }
        openRouterPricing?.let { merged.putAll(it) }
        manualPricing?.let { merged.putAll(it) }
        return merged
    }

    fun getPricingStats(context: Context): String {
        ensureLoaded(context)
        val sources = mutableListOf<String>()
        manualPricing?.let { if (it.isNotEmpty()) sources.add("Manual (${it.size})") }
        openRouterPricing?.let { if (it.isNotEmpty()) sources.add("OpenRouter (${it.size})") }
        litellmPricing?.let { if (it.isNotEmpty()) sources.add("LiteLLM (${it.size})") }
        return sources.joinToString(" + ")
    }

    /** Per-tier pricing snapshot for a single (provider, model) — mirrors the
     *  exact key-resolution logic getPricing() uses but reports each tier
     *  independently so callers can show or export the layered view.
     *  [default] is always populated; the others may be null. */
    data class TierBreakdown(
        val litellm: ModelPricing?,
        val modelsDev: ModelPricing?,
        val helicone: ModelPricing?,
        val llmPrices: ModelPricing?,
        val artificialAnalysis: ModelPricing?,
        val override: ModelPricing?,
        val openrouter: ModelPricing?,
        val default: ModelPricing
    )

    fun getTierBreakdown(context: Context, provider: AppService, model: String): TierBreakdown {
        ensureLoaded(context)
        val litellm = findLiteLLMPricing(provider, model)
        val modelsDev = findModelsDevPricing(provider, model)
        val helicone = findHeliconePricing(provider, model)
        val llmPrices = findLLMPricesPricing(provider, model)
        val aa = findArtificialAnalysisPricing(provider, model)
        val override = manualPricing?.get("${provider.id}:$model")
        val openrouter = findOpenRouterPricing(provider, model)
        return TierBreakdown(litellm, modelsDev, helicone, llmPrices, aa, override, openrouter, DEFAULT_PRICING)
    }

    /** True when two or more catalog tiers have pricing for this
     *  (provider, model) and they disagree on either the prompt or
     *  completion rate beyond a 1% relative tolerance. The user override
     *  and the static DEFAULT fallback are intentionally excluded —
     *  override is user-curated and "winning" the lookup, default is
     *  the fallback no real source disagrees with. Used by the AI
     *  Models filter to surface entries where the catalog ecosystem
     *  hasn't settled on a single number. */
    fun pricesConflict(context: Context, provider: AppService, model: String): Boolean {
        val br = getTierBreakdown(context, provider, model)
        val tiers = listOfNotNull(br.litellm, br.modelsDev, br.helicone, br.llmPrices, br.artificialAnalysis, br.openrouter)
        if (tiers.size < 2) return false
        fun close(a: Double, b: Double): Boolean {
            if (a == b) return true
            val mag = maxOf(kotlin.math.abs(a), kotlin.math.abs(b))
            if (mag == 0.0) return false
            return kotlin.math.abs(a - b) / mag <= 0.01
        }
        val prompt = tiers.first().promptPrice
        val completion = tiers.first().completionPrice
        return tiers.any { !close(it.promptPrice, prompt) || !close(it.completionPrice, completion) }
    }

    fun getOpenRouterPricing(context: Context): Map<String, ModelPricing> { ensureLoaded(context); return openRouterPricing?.toMap() ?: emptyMap() }
    fun getLiteLLMPricing(context: Context): Map<String, ModelPricing> { ensureLoaded(context); return litellmPricing?.toMap() ?: emptyMap() }

    /** Pretty-printed synthetic LiteLLM JSON entry for (provider, model),
     *  or null when the model isn't in the cache. Built from the parsed
     *  fields persisted in pricing_cache prefs — there is no bundled
     *  asset to fall back on, so a fresh install returns null until the
     *  user runs Refresh → LiteLLM. The shape mirrors the relevant
     *  subset of the upstream model_prices_and_context_window.json
     *  entry so the Model Info "LiteLLM" source button keeps showing
     *  the same fields. */
    fun getLiteLLMRawEntry(context: Context, provider: AppService, model: String): String? {
        ensureLoaded(context)
        val pricing = findLiteLLMPricing(provider, model) ?: return null
        val meta = findLiteLLMMeta(provider, model)
        val obj = com.google.gson.JsonObject().apply {
            addProperty("input_cost_per_token", pricing.promptPrice)
            addProperty("output_cost_per_token", pricing.completionPrice)
            meta?.mode?.let { addProperty("mode", it) }
            meta?.supportsVision?.let { addProperty("supports_vision", it) }
            meta?.supportsWebSearch?.let { addProperty("supports_web_search", it) }
            meta?.supportsSystemMessages?.let { addProperty("supports_system_messages", it) }
            meta?.supportsResponseSchema?.let { addProperty("supports_response_schema", it) }
            meta?.supportsReasoning?.let { addProperty("supports_reasoning", it) }
            meta?.supportsNativeStreaming?.let { addProperty("supports_native_streaming", it) }
            meta?.toolUseSystemPromptTokens?.let { addProperty("tool_use_system_prompt_tokens", it) }
            meta?.supportedEndpoints?.let { eps ->
                add("supported_endpoints", com.google.gson.JsonArray().apply {
                    eps.forEach { add(it) }
                })
            }
        }
        return createAppGson(prettyPrint = true).toJson(obj)
    }

    /**
     * Pull the latest model_prices_and_context_window.json from BerriAI/litellm's
     * GitHub main branch and replace the LITELLM tier with it. The bundled asset
     * is a snapshot taken at app build time; calling this from the Refresh screen
     * is how a user picks up new model entries between releases.
     *
     * Returns the number of priced entries that ended up in the cache, or null
     * if the network call failed.
     */
    suspend fun fetchLiteLLMPricingOnline(context: Context): Int? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val url = java.net.URL("https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json")
            val json = url.openStream().bufferedReader().use { it.readText() }
            val (pricing, meta) = parseLiteLLMJson(json)
            if (pricing.isEmpty()) return@withContext null
            synchronized(lock) {
                litellmPricing = pricing
                litellmMeta = meta
                litellmMetaLookupCache.clear()
                litellmPricingLookupCache.clear()
                litellmTimestamp = System.currentTimeMillis()
                getPrefs(context).edit {
                    putString(KEY_LITELLM_PRICING, gson.toJson(pricing))
                    putString(KEY_LITELLM_META, gson.toJson(meta))
                    putLong(KEY_LITELLM_TIMESTAMP, litellmTimestamp)
                }
            }
            pricing.size
        } catch (e: Exception) {
            android.util.Log.e("PricingCache", "Online LITELLM refresh failed: ${e.message}", e)
            null
        }
    }

    /**
     * Pull https://models.dev/api.json — a community-curated catalog with
     * per-model pricing, capability flags, and context-window limits.
     * Returns the number of priced entries, or null on network/parse
     * failure. Values are cached in pricing_cache prefs (round-trips
     * through BackupManager via the existing PREFS_TO_BACKUP entry).
     */
    suspend fun fetchModelsDevOnline(context: Context): Int? = withContext(kotlinx.coroutines.Dispatchers.IO) {
      withTraceCategory("Pricing fetch") {
        try {
            // Route through the shared OkHttp client (TracingInterceptor +
            // proper timeouts) instead of java.net.URL.openStream — so a
            // silent failure shows up in the in-app Trace screen rather
            // than vanishing into Log.e nobody reads.
            val json = ApiFactory.fetchUrlAsString("https://models.dev/api.json")
            if (json.isNullOrBlank()) {
                android.util.Log.w("PricingCache", "models.dev refresh: empty / failed response")
                return@withContext null
            }
            val (pricing, meta) = parseModelsDevJson(json)
            android.util.Log.i("PricingCache", "models.dev parse: ${pricing.size} priced, ${meta.size} meta entries (raw ${json.length} bytes)")
            if (pricing.isEmpty() && meta.isEmpty()) return@withContext null
            synchronized(lock) {
                modelsDevPricing = pricing
                modelsDevMeta = meta
                modelsDevMetaLookupCache.clear()
                modelsDevTimestamp = System.currentTimeMillis()
                getPrefs(context).edit {
                    putString(KEY_MODELS_DEV_PRICING, gson.toJson(pricing))
                    putString(KEY_MODELS_DEV_META, gson.toJson(meta))
                    putLong(KEY_MODELS_DEV_TIMESTAMP, modelsDevTimestamp)
                }
            }
            pricing.size
        } catch (e: Exception) {
            android.util.Log.e("PricingCache", "models.dev refresh failed: ${e.message}", e)
            null
        }
      }
    }

    /** Walk models.dev's two-level shape (provider → models → model entry)
     *  and produce two maps keyed `<provider>/<modelId>` (mirrors LiteLLM
     *  so `endsWith("/$target")` lookups still work). Cost numbers in
     *  models.dev are already \$/M-token — we divide by 1M to land in our
     *  per-token unit. */
    private fun parseModelsDevJson(json: String): Pair<Map<String, ModelPricing>, Map<String, ModelsDevMeta>> {
        @Suppress("DEPRECATION")
        val root: JsonObject = JsonParser().parse(json).asJsonObject
        val pricing = mutableMapOf<String, ModelPricing>()
        val meta = mutableMapOf<String, ModelsDevMeta>()
        for (provKey in root.keySet()) {
            val provEl = root.get(provKey) ?: continue
            if (!provEl.isJsonObject) continue
            val provObj = provEl.asJsonObject
            val modelsEl = provObj.get("models") ?: continue
            if (!modelsEl.isJsonObject) continue
            val modelsObj = modelsEl.asJsonObject
            for (modelKey in modelsObj.keySet()) {
                val modelEl = modelsObj.get(modelKey) ?: continue
                if (!modelEl.isJsonObject) continue
                val m = modelEl.asJsonObject
                val composite = "$provKey/$modelKey"
                // Pricing — cost.input / cost.output in $/M tokens, divide
                // to get per-token. Skip when both are missing (some
                // entries are open-weights or free-tier metadata only).
                val costEl = m.get("cost")
                if (costEl != null && costEl.isJsonObject) {
                    val cost = costEl.asJsonObject
                    val ic = cost.get("input")?.takeIf { it.isJsonPrimitive }?.asDouble
                    val oc = cost.get("output")?.takeIf { it.isJsonPrimitive }?.asDouble
                    if (ic != null && oc != null) {
                        val cr = cost.get("cache_read")?.takeIf { it.isJsonPrimitive }?.asDouble
                        val cw = cost.get("cache_write")?.takeIf { it.isJsonPrimitive }?.asDouble
                        pricing[composite] = ModelPricing(
                            modelId = modelKey,
                            promptPrice = ic / 1_000_000.0,
                            completionPrice = oc / 1_000_000.0,
                            source = "MODELSDEV",
                            cachedReadPrice = cr?.div(1_000_000.0),
                            cachedWritePrice = cw?.div(1_000_000.0)
                        )
                    }
                }
                // Capability sidecar.
                val attachment = m.get("attachment")?.takeIf { it.isJsonPrimitive }?.asBoolean
                val toolCall = m.get("tool_call")?.takeIf { it.isJsonPrimitive }?.asBoolean
                val reasoning = m.get("reasoning")?.takeIf { it.isJsonPrimitive }?.asBoolean
                val modalitiesIn = m.get("modalities")?.takeIf { it.isJsonObject }
                    ?.asJsonObject?.get("input")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
                val visionFromModalities = modalitiesIn?.any { it.equals("image", ignoreCase = true) }
                val supportsVision = attachment ?: visionFromModalities
                val limit = m.get("limit")?.takeIf { it.isJsonObject }?.asJsonObject
                val ctx = limit?.get("context")?.takeIf { it.isJsonPrimitive }?.asInt
                val out = limit?.get("output")?.takeIf { it.isJsonPrimitive }?.asInt
                if (supportsVision != null || toolCall != null || reasoning != null || ctx != null || out != null) {
                    meta[composite] = ModelsDevMeta(supportsVision, toolCall, reasoning, ctx, out)
                }
            }
        }
        return pricing to meta
    }

    private fun findModelsDevPricing(provider: AppService, model: String): ModelPricing? {
        val pricing = modelsDevPricing ?: return null
        pricing[model]?.let { return it }
        return findBestPrefixedMatch(pricing, provider, model, useLitellmPrefix = true)
            ?: findLatestAliasKey(pricing.keys, model, provider.litellmPrefix, provider.id.lowercase())?.let { pricing[it] }
    }

    private fun findModelsDevMeta(provider: AppService, model: String): ModelsDevMeta? {
        val meta = modelsDevMeta ?: return null
        val cacheKey = "${provider.id}|$model"
        val cached = modelsDevMetaLookupCache[cacheKey]
        if (cached != null) {
            @Suppress("UNCHECKED_CAST")
            return if (cached === MISSING_META) null else cached as ModelsDevMeta
        }
        val resolved = meta[model]
            ?: findBestPrefixedMatch(meta, provider, model, useLitellmPrefix = true)
            ?: findLatestAliasKey(meta.keys, model, provider.litellmPrefix, provider.id.lowercase())?.let { meta[it] }
        modelsDevMetaLookupCache[cacheKey] = resolved ?: MISSING_META
        return resolved
    }

    fun modelsDevSupportsVision(provider: AppService, model: String): Boolean? =
        findModelsDevMeta(provider, model)?.supportsVision

    fun modelsDevSupportsToolCall(provider: AppService, model: String): Boolean? =
        findModelsDevMeta(provider, model)?.supportsToolCall

    fun modelsDevSupportsReasoning(provider: AppService, model: String): Boolean? =
        findModelsDevMeta(provider, model)?.supportsReasoning

    fun modelsDevMaxInputTokens(provider: AppService, model: String): Int? =
        findModelsDevMeta(provider, model)?.maxInputTokens

    /** Pretty-printed models.dev JSON entry for the (provider, model)
     *  pair, or null when unknown — drives the Models.dev raw-data
     *  button on Model Info, mirroring [getLiteLLMRawEntry]. */
    fun getModelsDevRawEntry(context: Context, provider: AppService, model: String): String? {
        ensureLoaded(context)
        val composite = findModelsDevPricingKey(provider, model)
            ?: findModelsDevMetaKey(provider, model)
            ?: return null
        val pricing = modelsDevPricing?.get(composite)
        val meta = modelsDevMeta?.get(composite)
        if (pricing == null && meta == null) return null
        val pretty = createAppGson(prettyPrint = true)
        val combined = mapOf(
            "key" to composite,
            "pricing" to pricing,
            "meta" to meta
        )
        return pretty.toJson(combined)
    }

    private fun findModelsDevPricingKey(provider: AppService, model: String): String? {
        val pricing = modelsDevPricing ?: return null
        if (pricing.containsKey(model)) return model
        val target = normalizeModelId(model)
        val targetDeclared = provider.litellmPrefix?.let { "${normalizeModelId(it)}/$target" }
        val targetId = "${provider.id.lowercase()}/$target"
        val buckets = arrayOfNulls<String>(4)
        for (key in pricing.keys) {
            val k = normalizeModelId(key)
            when {
                k == target -> return key
                targetDeclared != null && k == targetDeclared -> if (buckets[1] == null) buckets[1] = key
                k == targetId -> if (buckets[2] == null) buckets[2] = key
                k.endsWith("/$target") -> if (buckets[3] == null) buckets[3] = key
            }
        }
        return buckets[1] ?: buckets[2] ?: buckets[3]
            ?: findLatestAliasKey(pricing.keys, model, provider.litellmPrefix, provider.id.lowercase())
    }

    private fun findModelsDevMetaKey(provider: AppService, model: String): String? {
        val meta = modelsDevMeta ?: return null
        if (meta.containsKey(model)) return model
        val target = normalizeModelId(model)
        val targetDeclared = provider.litellmPrefix?.let { "${normalizeModelId(it)}/$target" }
        val targetId = "${provider.id.lowercase()}/$target"
        val buckets = arrayOfNulls<String>(4)
        for (key in meta.keys) {
            val k = normalizeModelId(key)
            when {
                k == target -> return key
                targetDeclared != null && k == targetDeclared -> if (buckets[1] == null) buckets[1] = key
                k == targetId -> if (buckets[2] == null) buckets[2] = key
                k.endsWith("/$target") -> if (buckets[3] == null) buckets[3] = key
            }
        }
        return buckets[1] ?: buckets[2] ?: buckets[3]
            ?: findLatestAliasKey(meta.keys, model, provider.litellmPrefix, provider.id.lowercase())
    }

    // ============================================================================
    // Helicone — pricing aggregator (helicone.ai/api/llm-costs)
    // ============================================================================

    /** Parse Helicone's /api/llm-costs response. Each entry has provider /
     *  model / operator (equals / startsWith / includes) / input_cost_per_1m /
     *  output_cost_per_1m. The exact-match map covers ~95% of entries; the
     *  pattern list handles the rest at lookup time. */
    private fun parseHeliconeJson(json: String): Pair<Map<String, ModelPricing>, List<HeliconePattern>> {
        @Suppress("DEPRECATION")
        val root = JsonParser().parse(json).asJsonObject
        val data = root.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyMap<String, ModelPricing>() to emptyList()
        val exact = mutableMapOf<String, ModelPricing>()
        val patterns = mutableListOf<HeliconePattern>()
        for (el in data) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val provider = obj.get("provider")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val modelStr = obj.get("model")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val op = obj.get("operator")?.takeIf { it.isJsonPrimitive }?.asString ?: "equals"
            val ic = obj.get("input_cost_per_1m")?.takeIf { it.isJsonPrimitive }?.asDouble ?: continue
            val oc = obj.get("output_cost_per_1m")?.takeIf { it.isJsonPrimitive }?.asDouble ?: continue
            val cr = obj.get("prompt_cache_read_per_1m")?.takeIf { it.isJsonPrimitive }?.asDouble
            val cw = obj.get("prompt_cache_write_per_1m")?.takeIf { it.isJsonPrimitive }?.asDouble
            val pricing = ModelPricing(
                modelId = modelStr,
                promptPrice = ic / 1_000_000.0,
                completionPrice = oc / 1_000_000.0,
                source = "HELICONE",
                cachedReadPrice = cr?.div(1_000_000.0),
                cachedWritePrice = cw?.div(1_000_000.0)
            )
            val composite = "${provider.lowercase()}/$modelStr"
            if (op == "equals") exact[composite] = pricing
            else patterns.add(HeliconePattern(provider.lowercase(), modelStr, op, pricing))
        }
        // Longest-pattern-first means a more specific rule (e.g. "claude-opus-4-5")
        // wins over a generic one (e.g. "claude") when both could match.
        patterns.sortByDescending { it.pattern.length }
        return exact to patterns
    }

    suspend fun fetchHeliconeOnline(context: Context): Int? = withContext(kotlinx.coroutines.Dispatchers.IO) {
      withTraceCategory("Pricing fetch") {
        try {
            val json = ApiFactory.fetchUrlAsString("https://www.helicone.ai/api/llm-costs")
            if (json.isNullOrBlank()) {
                android.util.Log.w("PricingCache", "Helicone refresh: empty / failed response")
                return@withContext null
            }
            val (exact, patterns) = parseHeliconeJson(json)
            android.util.Log.i("PricingCache", "Helicone parse: ${exact.size} exact, ${patterns.size} patterns")
            if (exact.isEmpty() && patterns.isEmpty()) return@withContext null
            synchronized(lock) {
                heliconePricing = exact
                heliconePatterns = patterns
                heliconeTimestamp = System.currentTimeMillis()
                getPrefs(context).edit {
                    putString(KEY_HELICONE_PRICING, gson.toJson(exact))
                    putString(KEY_HELICONE_PATTERNS, gson.toJson(patterns))
                    putLong(KEY_HELICONE_TIMESTAMP, heliconeTimestamp)
                }
            }
            exact.size + patterns.size
        } catch (e: Exception) {
            android.util.Log.e("PricingCache", "Helicone refresh failed: ${e.message}", e)
            null
        }
      }
    }

    private fun findHeliconePricing(provider: AppService, model: String): ModelPricing? {
        val exact = heliconePricing ?: return null
        exact[model]?.let { return it }
        findBestPrefixedMatch(exact, provider, model, useLitellmPrefix = true)?.let { return it }
        // Pattern fallback — Helicone's startsWith/includes rules. We bias
        // toward our provider's own prefix to avoid an Azure/Bedrock pattern
        // hijacking a native call.
        val patterns = heliconePatterns ?: return null
        val target = normalizeModelId(model)
        val ourPrefixes = listOfNotNull(provider.id.lowercase(), provider.litellmPrefix?.lowercase()).toSet()
        for (p in patterns) {
            val pat = normalizeModelId(p.pattern)
            val matches = when (p.operator) {
                "startsWith" -> target.startsWith(pat)
                "includes" -> target.contains(pat)
                else -> target == pat
            }
            if (!matches) continue
            // Prefer matches from the same provider family as the request.
            if (p.provider in ourPrefixes) return p.pricing
        }
        // No same-provider pattern hit — fall back to the first cross-provider
        // pattern as a last resort (e.g. when the user routes a model id
        // through a generic provider).
        for (p in patterns) {
            val pat = normalizeModelId(p.pattern)
            val matches = when (p.operator) {
                "startsWith" -> target.startsWith(pat)
                "includes" -> target.contains(pat)
                else -> target == pat
            }
            if (matches) return p.pricing
        }
        return null
    }

    fun getHeliconeRawEntry(context: Context, provider: AppService, model: String): String? {
        ensureLoaded(context)
        val exact = heliconePricing ?: return null
        val pricing = findHeliconePricing(provider, model) ?: return null
        // Reverse-lookup the composite key (or pattern provider/pattern) so
        // the user can see *why* this entry matched their request.
        val key = exact.entries.firstOrNull { it.value === pricing }?.key
            ?: heliconePatterns?.firstOrNull { it.pricing === pricing }?.let { "${it.provider}/${it.pattern} (${it.operator})" }
        val pretty = createAppGson(prettyPrint = true)
        return pretty.toJson(mapOf("key" to (key ?: "?"), "pricing" to pricing))
    }

    // ============================================================================
    // llm-prices.com — Simon Willison's per-vendor curated tables
    // ============================================================================

    /** Vendors hosted under simonw/llm-prices's data/ folder. Stable list —
     *  add to it if upstream adds new vendor JSON files. */
    private val llmPricesVendors = listOf(
        "amazon", "anthropic", "deepseek", "google", "minimax",
        "mistral", "moonshot-ai", "openai", "qwen", "xai"
    )

    private fun parseLLMPricesVendorJson(vendor: String, json: String): Map<String, ModelPricing> {
        @Suppress("DEPRECATION")
        val root = JsonParser().parse(json).asJsonObject
        val models = root.get("models")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyMap()
        val out = mutableMapOf<String, ModelPricing>()
        for (el in models) {
            if (!el.isJsonObject) continue
            val m = el.asJsonObject
            val id = m.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            // price_history is reverse-chronological — first non-archived row
            // is the current price (to_date == null marks the active entry).
            val history = m.get("price_history")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            val current = history.firstOrNull { e ->
                e.isJsonObject && (e.asJsonObject.get("to_date")?.isJsonNull ?: true)
            }?.asJsonObject ?: history.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val ic = current.get("input")?.takeIf { it.isJsonPrimitive }?.asDouble ?: continue
            val oc = current.get("output")?.takeIf { it.isJsonPrimitive }?.asDouble ?: continue
            val cached = current.get("input_cached")?.takeIf { it.isJsonPrimitive }?.asDouble
            out["$vendor/$id"] = ModelPricing(
                modelId = id,
                promptPrice = ic / 1_000_000.0,
                completionPrice = oc / 1_000_000.0,
                source = "LLMPRICES",
                cachedReadPrice = cached?.div(1_000_000.0)
            )
        }
        return out
    }

    suspend fun fetchLLMPricesOnline(context: Context): Int? = withContext(kotlinx.coroutines.Dispatchers.IO) {
      withTraceCategory("Pricing fetch") {
        try {
            val combined = mutableMapOf<String, ModelPricing>()
            for (vendor in llmPricesVendors) {
                val url = "https://raw.githubusercontent.com/simonw/llm-prices/main/data/$vendor.json"
                val json = ApiFactory.fetchUrlAsString(url) ?: continue
                combined.putAll(parseLLMPricesVendorJson(vendor, json))
            }
            android.util.Log.i("PricingCache", "llm-prices parse: ${combined.size} entries from ${llmPricesVendors.size} vendors")
            if (combined.isEmpty()) return@withContext null
            synchronized(lock) {
                llmPricesPricing = combined
                llmPricesTimestamp = System.currentTimeMillis()
                getPrefs(context).edit {
                    putString(KEY_LLMPRICES_PRICING, gson.toJson(combined))
                    putLong(KEY_LLMPRICES_TIMESTAMP, llmPricesTimestamp)
                }
            }
            combined.size
        } catch (e: Exception) {
            android.util.Log.e("PricingCache", "llm-prices refresh failed: ${e.message}", e)
            null
        }
      }
    }

    private fun findLLMPricesPricing(provider: AppService, model: String): ModelPricing? {
        val pricing = llmPricesPricing ?: return null
        pricing[model]?.let { return it }
        return findBestPrefixedMatch(pricing, provider, model, useLitellmPrefix = true)
            ?: findLatestAliasKey(pricing.keys, model, provider.litellmPrefix, provider.id.lowercase())?.let { pricing[it] }
    }

    fun getLLMPricesRawEntry(context: Context, provider: AppService, model: String): String? {
        ensureLoaded(context)
        val pricing = llmPricesPricing ?: return null
        val resolved = findLLMPricesPricing(provider, model) ?: return null
        val key = pricing.entries.firstOrNull { it.value === resolved }?.key
        val pretty = createAppGson(prettyPrint = true)
        return pretty.toJson(mapOf("key" to (key ?: "?"), "pricing" to resolved))
    }

    // ============================================================================
    // Artificial Analysis — pricing + intelligence/speed scores
    // Endpoint: https://artificialanalysis.ai/api/v2/data/llms/models
    // Auth: x-api-key header (free tier, requires sign-up)
    // ============================================================================

    /** Parse AA's /api/v2/data/llms/models response.
     *
     *  Real shape (verified against a captured trace):
     *  ```
     *  { "data": [ {
     *      "id": "<uuid>",                   // not useful — internal AA id
     *      "name": "Claude Opus 4.6",
     *      "slug": "claude-opus-4-6",       // matchable model id
     *      "model_creator": { "slug": "anthropic", "name": "Anthropic" },
     *      "evaluations": { "artificial_analysis_intelligence_index": 56.3, ... },
     *      "pricing": { "price_1m_input_tokens": 5.0, "price_1m_output_tokens": 25.0 },
     *      "median_output_tokens_per_second": 168.5,
     *      "median_time_to_first_token_seconds": 0.55
     *  }, ... ] }
     *  ```
     *
     *  Composite key: `<creator_slug>/<slug>` so it lines up with how the
     *  rest of the chain stores LiteLLM / models.dev entries (the existing
     *  prefix-bucket lookup then handles dots-vs-dashes via normalizeModelId). */
    private fun parseArtificialAnalysisJson(json: String): Pair<Map<String, ModelPricing>, Map<String, ArtificialAnalysisMeta>> {
        @Suppress("DEPRECATION")
        val rootEl = JsonParser().parse(json)
        val arr = when {
            rootEl.isJsonArray -> rootEl.asJsonArray
            rootEl.isJsonObject -> rootEl.asJsonObject.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyMap<String, ModelPricing>() to emptyMap()
            else -> return emptyMap<String, ModelPricing>() to emptyMap()
        }
        val pricing = mutableMapOf<String, ModelPricing>()
        val meta = mutableMapOf<String, ArtificialAnalysisMeta>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val m = el.asJsonObject
            val slug = m.get("slug")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val creatorObj = m.get("model_creator")?.takeIf { it.isJsonObject }?.asJsonObject
            val creatorSlug = creatorObj?.get("slug")?.takeIf { it.isJsonPrimitive }?.asString
            val creatorName = creatorObj?.get("name")?.takeIf { it.isJsonPrimitive }?.asString
            val composite = if (!creatorSlug.isNullOrBlank()) "${creatorSlug.lowercase()}/$slug" else slug

            val priceObj = m.get("pricing")?.takeIf { it.isJsonObject }?.asJsonObject
            val ic = priceObj?.get("price_1m_input_tokens")?.takeIf { it.isJsonPrimitive }?.asDouble
            val oc = priceObj?.get("price_1m_output_tokens")?.takeIf { it.isJsonPrimitive }?.asDouble
            if (ic != null && oc != null) {
                pricing[composite] = ModelPricing(
                    modelId = slug,
                    promptPrice = ic / 1_000_000.0,
                    completionPrice = oc / 1_000_000.0,
                    source = "ARTIFICIALANALYSIS"
                )
            }

            val evals = m.get("evaluations")?.takeIf { it.isJsonObject }?.asJsonObject
            val intelligenceIndex = evals?.get("artificial_analysis_intelligence_index")?.takeIf { it.isJsonPrimitive }?.asDouble
            val outputSpeed = m.get("median_output_tokens_per_second")?.takeIf { it.isJsonPrimitive }?.asDouble
            val firstChunk = m.get("median_time_to_first_token_seconds")?.takeIf { it.isJsonPrimitive }?.asDouble
            if (intelligenceIndex != null || outputSpeed != null || firstChunk != null || creatorName != null) {
                meta[composite] = ArtificialAnalysisMeta(intelligenceIndex, outputSpeed, firstChunk, creatorName)
            }
        }
        return pricing to meta
    }

    suspend fun fetchArtificialAnalysisOnline(context: Context, apiKey: String): Int? = withContext(kotlinx.coroutines.Dispatchers.IO) {
      withTraceCategory("Pricing fetch") {
        if (apiKey.isBlank()) {
            android.util.Log.w("PricingCache", "Artificial Analysis refresh skipped: missing API key")
            return@withContext null
        }
        try {
            val json = ApiFactory.fetchUrlAsString(
                "https://artificialanalysis.ai/api/v2/data/llms/models",
                headers = mapOf("x-api-key" to apiKey)
            )
            if (json.isNullOrBlank()) {
                android.util.Log.w("PricingCache", "Artificial Analysis refresh: empty / failed response")
                return@withContext null
            }
            val (pricing, meta) = parseArtificialAnalysisJson(json)
            android.util.Log.i("PricingCache", "Artificial Analysis parse: ${pricing.size} priced, ${meta.size} meta entries")
            if (pricing.isEmpty() && meta.isEmpty()) return@withContext null
            synchronized(lock) {
                aaPricing = pricing
                aaMeta = meta
                aaTimestamp = System.currentTimeMillis()
                getPrefs(context).edit {
                    putString(KEY_AA_PRICING, gson.toJson(pricing))
                    putString(KEY_AA_META, gson.toJson(meta))
                    putLong(KEY_AA_TIMESTAMP, aaTimestamp)
                }
            }
            pricing.size + meta.size
        } catch (e: Exception) {
            android.util.Log.e("PricingCache", "Artificial Analysis refresh failed: ${e.message}", e)
            null
        }
      }
    }

    private fun findArtificialAnalysisPricing(provider: AppService, model: String): ModelPricing? {
        val pricing = aaPricing ?: return null
        pricing[model]?.let { return it }
        return findBestPrefixedMatch(pricing, provider, model, useLitellmPrefix = true)
            ?: findLatestAliasKey(pricing.keys, model, provider.litellmPrefix, provider.id.lowercase())?.let { pricing[it] }
    }

    private fun findArtificialAnalysisMeta(provider: AppService, model: String): ArtificialAnalysisMeta? {
        val meta = aaMeta ?: return null
        meta[model]?.let { return it }
        return findBestPrefixedMatch(meta, provider, model, useLitellmPrefix = true)
            ?: findLatestAliasKey(meta.keys, model, provider.litellmPrefix, provider.id.lowercase())?.let { meta[it] }
    }

    fun getArtificialAnalysisRawEntry(context: Context, provider: AppService, model: String): String? {
        ensureLoaded(context)
        val pricing = findArtificialAnalysisPricing(provider, model)
        val meta = findArtificialAnalysisMeta(provider, model)
        if (pricing == null && meta == null) return null
        val pretty = createAppGson(prettyPrint = true)
        return pretty.toJson(mapOf(
            "pricing" to pricing,
            "meta" to meta
        ))
    }

    suspend fun fetchOpenRouterPricing(apiKey: String): Map<String, ModelPricing> {
        if (apiKey.isBlank()) return emptyMap()
        return withTraceCategory("Pricing fetch") {
            try {
                val api = ApiFactory.createOpenRouterModelsApi("https://openrouter.ai/api/")
                val response = api.listModelsDetailed("Bearer $apiKey")
                if (response.isSuccessful) {
                    response.body()?.data?.mapNotNull { model ->
                        val pp = model.pricing?.prompt?.toDoubleOrNull()
                        val cp = model.pricing?.completion?.toDoubleOrNull()
                        if (pp != null && cp != null) model.id to ModelPricing(model.id, pp, cp, "OPENROUTER") else null
                    }?.toMap() ?: emptyMap()
                } else emptyMap()
            } catch (_: Exception) { emptyMap() }
        }
    }

    // Private helpers
    private fun getPrefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun ensureLoaded(context: Context) = synchronized(lock) {
        if (manualPricing == null) loadManualPricing(context)
        if (openRouterPricing == null) loadFromPrefs(context)
        // LiteLLM: no bundled asset (network only). The user populates the
        // tier with Refresh → LiteLLM; subsequent app starts read from
        // pricing_cache prefs. A fresh install with no refresh yet leaves
        // litellmPricing/Meta as empty maps — the layered lookup just
        // falls through to the next tier.
        if (litellmPricing == null) {
            val prefs = getPrefs(context)
            prefs.getString(KEY_LITELLM_PRICING, null)?.let { json ->
                try { litellmPricing = gson.fromJson(json, mapModelPricingType); litellmTimestamp = prefs.getLong(KEY_LITELLM_TIMESTAMP, 0) }
                catch (_: Exception) {}
            }
            if (litellmPricing == null) litellmPricing = emptyMap()
            litellmPricingLookupCache.clear()
        }
        if (litellmMeta == null) {
            val prefs = getPrefs(context)
            prefs.getString(KEY_LITELLM_META, null)?.let { json ->
                try {
                    val type = object : TypeToken<Map<String, LiteLLMMeta>>() {}.type
                    litellmMeta = gson.fromJson(json, type)
                } catch (_: Exception) {}
            }
            if (litellmMeta == null) litellmMeta = emptyMap()
            litellmMetaLookupCache.clear()
        }
        // models.dev: no bundled asset (network only). Both maps live in
        // prefs, repopulate from there if present. The user's first
        // refresh populates them; subsequent app restarts read from prefs.
        if (modelsDevPricing == null || modelsDevMeta == null) {
            val prefs = getPrefs(context)
            modelsDevTimestamp = prefs.getLong(KEY_MODELS_DEV_TIMESTAMP, 0)
            prefs.getString(KEY_MODELS_DEV_PRICING, null)?.let { json ->
                try { modelsDevPricing = gson.fromJson(json, mapModelPricingType) } catch (_: Exception) {}
            }
            prefs.getString(KEY_MODELS_DEV_META, null)?.let { json ->
                try {
                    val type = object : TypeToken<Map<String, ModelsDevMeta>>() {}.type
                    modelsDevMeta = gson.fromJson(json, type)
                } catch (_: Exception) {}
            }
            modelsDevMetaLookupCache.clear()
        }
        // Helicone — exact map plus pattern list. Both are network-only
        // (no bundled asset); empty until the user runs the refresh.
        if (heliconePricing == null || heliconePatterns == null) {
            val prefs = getPrefs(context)
            heliconeTimestamp = prefs.getLong(KEY_HELICONE_TIMESTAMP, 0)
            prefs.getString(KEY_HELICONE_PRICING, null)?.let { json ->
                try { heliconePricing = gson.fromJson(json, mapModelPricingType) } catch (_: Exception) {}
            }
            prefs.getString(KEY_HELICONE_PATTERNS, null)?.let { json ->
                try {
                    val type = object : TypeToken<List<HeliconePattern>>() {}.type
                    heliconePatterns = gson.fromJson(json, type)
                } catch (_: Exception) {}
            }
        }
        // llm-prices.com — single combined map.
        if (llmPricesPricing == null) {
            val prefs = getPrefs(context)
            llmPricesTimestamp = prefs.getLong(KEY_LLMPRICES_TIMESTAMP, 0)
            prefs.getString(KEY_LLMPRICES_PRICING, null)?.let { json ->
                try { llmPricesPricing = gson.fromJson(json, mapModelPricingType) } catch (_: Exception) {}
            }
        }
        // Artificial Analysis — pricing + sidecar.
        if (aaPricing == null || aaMeta == null) {
            val prefs = getPrefs(context)
            aaTimestamp = prefs.getLong(KEY_AA_TIMESTAMP, 0)
            prefs.getString(KEY_AA_PRICING, null)?.let { json ->
                try { aaPricing = gson.fromJson(json, mapModelPricingType) } catch (_: Exception) {}
            }
            prefs.getString(KEY_AA_META, null)?.let { json ->
                try {
                    val type = object : TypeToken<Map<String, ArtificialAnalysisMeta>>() {}.type
                    aaMeta = gson.fromJson(json, type)
                } catch (_: Exception) {}
            }
        }
    }

    private fun loadFromPrefs(context: Context) {
        val prefs = getPrefs(context)
        val json = prefs.getString(KEY_OPENROUTER_PRICING, null)
        openRouterTimestamp = prefs.getLong(KEY_OPENROUTER_TIMESTAMP, 0)
        openRouterPricing = if (json != null) {
            try { gson.fromJson(json, mapModelPricingType) } catch (_: Exception) { emptyMap() }
        } else emptyMap()
    }

    private fun loadManualPricing(context: Context) {
        val json = getPrefs(context).getString(KEY_MANUAL_PRICING, null)
        manualPricing = if (json != null) {
            try { gson.fromJson(json, mutableMapModelPricingType) } catch (_: Exception) { mutableMapOf() }
        } else mutableMapOf()
    }

    /** Helicone non-exact entry — keeps the original provider/model strings
     *  alongside the operator so we can match arbitrary requests against
     *  prefix or substring rules at lookup time. Sorted longest-first so
     *  more specific patterns win. */
    data class HeliconePattern(
        val provider: String,
        val pattern: String,
        val operator: String,
        val pricing: ModelPricing
    )

    /** Artificial Analysis sidecar — surfaces the unique data points AA
     *  exposes that nothing else in the chain has: the composite quality
     *  index and median output speed. Other fields kept for the raw view. */
    data class ArtificialAnalysisMeta(
        val intelligenceIndex: Double? = null,
        val outputSpeed: Double? = null,
        val firstChunkSeconds: Double? = null,
        val modelCreator: String? = null
    )

    /** Capability sidecar derived from models.dev's per-model JSON. The
     *  fields we lift mirror what LiteLLMMeta carries so capability
     *  fallbacks chain cleanly when LiteLLM has no entry. */
    data class ModelsDevMeta(
        val supportsVision: Boolean? = null,
        val supportsToolCall: Boolean? = null,
        val supportsReasoning: Boolean? = null,
        val maxInputTokens: Int? = null,
        val maxOutputTokens: Int? = null
    )

    data class LiteLLMMeta(
        val mode: String? = null,
        val supportsVision: Boolean? = null,
        val supportsWebSearch: Boolean? = null,
        /** Paths the model is callable on, as listed in the upstream JSON's
         *  `supported_endpoints` field — e.g. ["/v1/chat/completions",
         *  "/v1/responses"]. Combined with the provider's baseUrl by
         *  callers when offering endpoint suggestions. */
        val supportedEndpoints: List<String>? = null,
        val supportsSystemMessages: Boolean? = null,
        val supportsResponseSchema: Boolean? = null,
        val supportsReasoning: Boolean? = null,
        val supportsNativeStreaming: Boolean? = null,
        /** Provider-side overhead (in tokens) added to the request when
         *  the web-search tool is in play, taken from LiteLLM's
         *  `tool_use_system_prompt_tokens` field. Useful for making the
         *  client-side cost estimate line up with what the provider
         *  actually charges when 🌐 is on. */
        val toolUseSystemPromptTokens: Int? = null
    )

    /** Walk the litellm pricing JSON via the tree model so duplicate keys
     *  inside a single model entry (last-wins) don't blow up the parse the
     *  way fromJson(Map<String, Map<String, Any>>) does. Returns the
     *  parsed pricing map alongside a sidecar capability map (mode +
     *  supports_vision / supports_web_search) so vision / web-search /
     *  type lookups can use LiteLLM as a first-test source. */
    private fun parseLiteLLMJson(json: String): Pair<Map<String, ModelPricing>, Map<String, LiteLLMMeta>> {
        @Suppress("DEPRECATION")
        val root: JsonObject = JsonParser().parse(json).asJsonObject
        val pricing = mutableMapOf<String, ModelPricing>()
        val meta = mutableMapOf<String, LiteLLMMeta>()
        for (modelId in root.keySet()) {
            val infoEl: JsonElement = root.get(modelId) ?: continue
            if (!infoEl.isJsonObject) continue
            val infoObj: JsonObject = infoEl.asJsonObject
            val flat = mutableMapOf<String, Any>()
            for (k in infoObj.keySet()) {
                val v: JsonElement = infoObj.get(k) ?: continue
                if (v.isJsonNull || !v.isJsonPrimitive) continue
                val p = v.asJsonPrimitive
                flat[k] = when {
                    p.isNumber -> p.asDouble
                    p.isBoolean -> p.asBoolean
                    else -> p.asString
                }
            }
            liteLLMEntry(modelId, flat)?.let { pricing[it.first] = it.second }
            // Capability sidecar — populated even for entries with no
            // input/output cost (some embedding / tts models have flag
            // info but free-of-charge pricing). supported_endpoints is a
            // JSON array, so it's pulled from the raw JsonObject rather
            // than the flat primitive map.
            val mode = flat["mode"] as? String
            val sv = flat["supports_vision"] as? Boolean
            val sw = flat["supports_web_search"] as? Boolean
            val sm = flat["supports_system_messages"] as? Boolean
            val sr = flat["supports_response_schema"] as? Boolean
            val sre = (flat["supports_reasoning"] as? Boolean) ?: (flat["supports_max_reasoning_effort"] as? Boolean)
            val sns = flat["supports_native_streaming"] as? Boolean
            val tu = (flat["tool_use_system_prompt_tokens"] as? Number)?.toInt()
            val seArr = infoObj.get("supported_endpoints")
            val se = if (seArr != null && seArr.isJsonArray) {
                seArr.asJsonArray.mapNotNull { el ->
                    if (el.isJsonPrimitive) el.asString else null
                }.takeIf { it.isNotEmpty() }
            } else null
            if (mode != null || sv != null || sw != null || se != null || sm != null || sr != null || sre != null || sns != null || tu != null) {
                meta[modelId] = LiteLLMMeta(mode, sv, sw, se, sm, sr, sre, sns, tu)
            }
        }
        return pricing to meta
    }

    /** Map a single litellm JSON entry to a ModelPricing, or null when it
     *  has no usable cost field at all. Token-based fields drive
     *  prompt/completion price for chat/embedding/etc.; rerank-mode
     *  models bill per query and are accepted even when both per-token
     *  fields are absent or zero. */
    private fun liteLLMEntry(modelId: String, info: Map<String, Any>): Pair<String, ModelPricing>? {
        fun n(key: String) = (info[key] as? Number)?.toDouble()
        val ic = n("input_cost_per_token")
        val oc = n("output_cost_per_token")
        val perQuery = n("input_cost_per_query") ?: 0.0
        // Drop entries that have no cost signal whatsoever — keeps the
        // cache from filling up with rows that would render as $0/$0
        // and crowd out the layered lookup. Rerank entries with a real
        // per-query price still pass even when per-token is missing.
        if (ic == null && oc == null && perQuery == 0.0) return null
        return modelId to ModelPricing(
            modelId = modelId,
            promptPrice = ic ?: 0.0,
            completionPrice = oc ?: 0.0,
            source = "LITELLM",
            cachedReadPrice = n("cache_read_input_token_cost"),
            cachedWritePrice = n("cache_creation_input_token_cost"),
            promptPriceAbove200k = n("input_cost_per_token_above_200k_tokens")
                ?: n("input_cost_per_token_above_128k_tokens"),
            completionPriceAbove200k = n("output_cost_per_token_above_200k_tokens")
                ?: n("output_cost_per_token_above_128k_tokens"),
            cachedReadPriceAbove200k = n("cache_read_input_token_cost_above_200k_tokens")
                ?: n("cache_read_input_token_cost_above_128k_tokens"),
            cachedWritePriceAbove200k = n("cache_creation_input_token_cost_above_200k_tokens")
                ?: n("cache_creation_input_token_cost_above_128k_tokens"),
            perQueryPrice = perQuery
        )
    }

    // Model specifications from OpenRouter
    data class ModelPricingEntry(val provider: String, val model: String, val pricing: OpenRouterPricing?)
    data class ModelSupportedParametersEntry(val provider: String, val model: String, val supported_parameters: List<String>?)

    suspend fun fetchAndSaveModelSpecifications(context: Context, apiKey: String): Pair<Int, Int>? {
        if (apiKey.isBlank()) return null
        return withTraceCategory("OpenRouter model specs") {
            try {
                val api = ApiFactory.createOpenRouterModelsApi("https://openrouter.ai/api/")
                val response = api.listModelsDetailed("Bearer $apiKey")
                if (!response.isSuccessful) return@withTraceCategory null
                val models = response.body()?.data ?: emptyList()
                val pricingEntries = mutableListOf<ModelPricingEntry>()
                val parametersEntries = mutableListOf<ModelSupportedParametersEntry>()
                for (model in models) {
                    val parts = model.id.split("/", limit = 2)
                    if (parts.size != 2) continue
                    val aiService = AppService.entries.find { it.openRouterName == parts[0] } ?: continue
                    if (model.pricing != null) pricingEntries.add(ModelPricingEntry(aiService.id, parts[1], model.pricing))
                    if (model.supported_parameters != null) parametersEntries.add(ModelSupportedParametersEntry(aiService.id, parts[1], model.supported_parameters))
                }
                java.io.File(context.filesDir, "model_pricing.json").writeText(gson.toJson(pricingEntries))
                java.io.File(context.filesDir, "model_supported_parameters.json").writeText(gson.toJson(parametersEntries))
                clearSupportedParametersCache()
                Pair(pricingEntries.size, parametersEntries.size)
            } catch (e: Exception) { android.util.Log.e("PricingCache", "Failed: ${e.message}"); null }
        }
    }

    @Volatile private var supportedParametersCache: Map<String, List<String>>? = null

    fun getSupportedParameters(context: Context, provider: AppService, model: String): List<String>? {
        if (supportedParametersCache == null) loadSupportedParametersCache(context)
        return supportedParametersCache?.get("${provider.id}:$model")
    }

    private fun loadSupportedParametersCache(context: Context) {
        val file = java.io.File(context.filesDir, "model_supported_parameters.json")
        if (!file.exists()) { supportedParametersCache = emptyMap(); return }
        try {
            val entries: List<ModelSupportedParametersEntry> = gson.fromJson(file.readText(), listSupportedParamsType)
            supportedParametersCache = entries.filter { it.supported_parameters != null }
                .associate { "${it.provider}:${it.model}" to (it.supported_parameters ?: emptyList()) }
        } catch (_: Exception) { supportedParametersCache = emptyMap() }
    }

    fun clearSupportedParametersCache() { supportedParametersCache = null }
}
