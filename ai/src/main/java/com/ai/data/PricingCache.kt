package com.ai.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cached model pricing with layered lookup:
 * provider self-report (OpenRouter/Together for their own calls) > manual
 * OVERRIDE > curated bulk tiers (LiteLLM, models.dev, llm-prices,
 * Artificial Analysis, llm-stats) > OpenRouter cross-provider fallback >
 * Requesty cross-provider router > Helicone > DEFAULT.
 *
 * Manual overrides intentionally beat curated tiers so a user can correct a
 * stale catalog price without waiting for the upstream source to refresh.
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
    // Requesty — cross-provider router catalog (router.requesty.ai/v1/models).
    // Pricing (already per-token) + capability sidecar. No API key.
    private const val KEY_REQUESTY_PRICING = "requesty_pricing"
    private const val KEY_REQUESTY_META = "requesty_meta"
    private const val KEY_REQUESTY_TIMESTAMP = "requesty_timestamp"
    // llm-stats.com — curated catalog with per-provider pricing + benchmark
    // scores (api.llm-stats.com/stats/v1/models). Needs an API key + Stats
    // API onboarding. Paginated via next_cursor.
    private const val KEY_LLMSTATS_PRICING = "llmstats_pricing"
    private const val KEY_LLMSTATS_META = "llmstats_meta"
    private const val KEY_LLMSTATS_TIMESTAMP = "llmstats_timestamp"
    private const val KEY_MANUAL_PRICING = "manual_pricing"
    /** Together AI native pricing — extracted from each model entry's
     *  `pricing.{input, output, cached_input}` block during a Together
     *  /v1/models refresh. Per-token prices keyed by raw model id. */
    private const val KEY_TOGETHER_PRICING = "together_pricing"
    private const val KEY_TOGETHER_TIMESTAMP = "together_timestamp"
    private const val CACHE_DURATION_MS = 7L * 24 * 60 * 60 * 1000

    private val gson = createAppGson()
    private val lock = Any()
    private val mapModelPricingType: Type = object : TypeToken<Map<String, ModelPricing>>() {}.type
    private val mutableMapModelPricingType: Type = object : TypeToken<MutableMap<String, ModelPricing>>() {}.type
    private val mapStringMapType: Type = object : TypeToken<Map<String, Map<String, Any>>>() {}.type
    private val listSupportedParamsType: Type = object : TypeToken<List<ModelSupportedParametersEntry>>() {}.type

    @Volatile private var manualPricing: MutableMap<String, ModelPricing>? = null
    private val _manualPricingVersion = MutableStateFlow(0)
    val manualPricingVersion: StateFlow<Int> = _manualPricingVersion.asStateFlow()
    @Volatile private var openRouterPricing: Map<String, ModelPricing>? = null
    @Volatile private var togetherPricing: Map<String, ModelPricing>? = null
    @Volatile private var togetherTimestamp: Long = 0
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
    /** Requesty router pricing — cross-provider aggregator, keyed
     *  `<vendor>/<modelId>` (OpenRouter-style ids). Prices are already
     *  per-token in the upstream JSON, so no $/M conversion. */
    @Volatile private var requestyPricing: Map<String, ModelPricing>? = null
    @Volatile private var requestyMeta: Map<String, RequestyMeta>? = null
    @Volatile private var requestyTimestamp: Long = 0
    /** llm-stats.com pricing — curated catalog keyed `<org>/<modelId>`.
     *  Per-provider $/M prices collapsed to a single representative
     *  (first-party when present, else cheapest). Sidecar carries the
     *  benchmark `top_scores` + modalities. */
    @Volatile private var llmStatsPricing: Map<String, ModelPricing>? = null
    @Volatile private var llmStatsMeta: Map<String, LlmStatsMeta>? = null
    @Volatile private var llmStatsTimestamp: Long = 0
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
        saveBlob(context, KEY_OPENROUTER_PRICING, gson.toJson(pricing))
        getPrefs(context).edit { putLong(KEY_OPENROUTER_TIMESTAMP, openRouterTimestamp) }
    }

    /** Persist Together AI native pricing — populated as a side
     *  effect of fetchModelsOpenAiCompat when the provider is
     *  Together. Keyed by raw model id (no provider prefix; this map
     *  is only consulted when the caller's provider is Together). */
    fun saveTogetherPricing(context: Context, pricing: Map<String, ModelPricing>) = synchronized(lock) {
        togetherPricing = pricing
        togetherTimestamp = System.currentTimeMillis()
        saveBlob(context, KEY_TOGETHER_PRICING, gson.toJson(pricing))
        getPrefs(context).edit { putLong(KEY_TOGETHER_TIMESTAMP, togetherTimestamp) }
    }

    private fun findTogetherPricing(provider: AppService, model: String): ModelPricing? {
        if (!provider.pricingFromModelList) return null
        return togetherPricing?.get(model)
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
                breakdown.llmStats != null ||
                breakdown.openrouter != null ||
                breakdown.requesty != null ||
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

    private fun saveManualPricing(context: Context) {
        getPrefs(context).edit { putString(KEY_MANUAL_PRICING, gson.toJson(manualPricing)) }
        _manualPricingVersion.value = _manualPricingVersion.value + 1
    }

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
        val (inCost, outCost) = computeInOutCost(usage, pricing)
        return inCost + outCost
    }

    /** Tier-aware split of input vs output spend. Used by code paths
     *  that need to persist the two halves separately (translation,
     *  meta, secondary). The previous simple multiplication
     *  (inputTokens * promptPrice) ignored the above-200k tier and
     *  the cached-read / cache-creation rates — for long contexts
     *  the persisted split diverged from the canonical computeCost. */
    fun computeInOutCost(usage: TokenUsage, pricing: ModelPricing): Pair<Double, Double> {
        val totalInput = usage.inputTokens + usage.cachedInputTokens + usage.cacheCreationTokens
        val highTier = totalInput > 200_000 && pricing.promptPriceAbove200k != null
        val pIn = if (highTier) pricing.promptPriceAbove200k else pricing.promptPrice
        val pOut = if (highTier) (pricing.completionPriceAbove200k ?: pricing.completionPrice) else pricing.completionPrice
        val pCacheR = if (highTier) (pricing.cachedReadPriceAbove200k ?: pricing.cachedReadPrice ?: pIn)
                     else (pricing.cachedReadPrice ?: pIn)
        val pCacheW = if (highTier) (pricing.cachedWritePriceAbove200k ?: pricing.cachedWritePrice ?: pIn)
                     else (pricing.cachedWritePrice ?: pIn)
        val inCost = usage.inputTokens * pIn +
            usage.cachedInputTokens * pCacheR +
            usage.cacheCreationTokens * pCacheW
        val outCost = usage.outputTokens * pOut
        // apiCost shortcut: if the API ships a total, split it pro-rata
        // by the simple-rate baseline so callers that need the two
        // halves still get a consistent split.
        usage.apiCost?.let { total ->
            val baseIn = usage.inputTokens * pricing.promptPrice +
                usage.cachedInputTokens * (pricing.cachedReadPrice ?: pricing.promptPrice) +
                usage.cacheCreationTokens * (pricing.cachedWritePrice ?: pricing.promptPrice)
            val baseOut = usage.outputTokens * pricing.completionPrice
            val baseTotal = baseIn + baseOut
            return if (baseTotal > 0.0) {
                val ratioIn = baseIn / baseTotal
                (total * ratioIn) to (total * (1 - ratioIn))
            } else {
                // Baseline rates are all zero (free / DEFAULT pricing) but
                // the API still shipped a total. Split by token ratio rather
                // than attributing 100% to output (Bug 38).
                val totalTokens = (usage.inputTokens + usage.cachedInputTokens +
                    usage.cacheCreationTokens + usage.outputTokens).toDouble()
                if (totalTokens > 0.0) {
                    val ratioIn = (usage.inputTokens + usage.cachedInputTokens +
                        usage.cacheCreationTokens).toDouble() / totalTokens
                    (total * ratioIn) to (total * (1 - ratioIn))
                } else (0.0 to total)
            }
        }
        return inCost to outCost
    }

    /**
     * Warm the in-memory caches in the background. Safe to call repeatedly; only runs once.
     * Compose code that calls [getPricing] synchronously won't have to block on a 1.2MB
     * asset parse on first use.
     */
    fun preloadAsync(context: Context, scope: kotlinx.coroutines.CoroutineScope) {
        if (preloadCompleted) return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            AppLog.d("PricingCache", "preload start")
            ensureLoaded(context)
            preloadCompleted = true
            AppLog.d(
                "PricingCache",
                "preload done in ${System.currentTimeMillis() - t0}ms" +
                    " (litellm=${litellmPricing?.size ?: 0}, modelsDev=${modelsDevPricing?.size ?: 0}," +
                    " llmPrices=${llmPricesPricing?.size ?: 0}, aa=${aaPricing?.size ?: 0}," +
                    " llmStats=${llmStatsPricing?.size ?: 0}, openrouter=${openRouterPricing?.size ?: 0}," +
                    " requesty=${requestyPricing?.size ?: 0}, helicone=${heliconePricing?.size ?: 0}," +
                    " manual=${manualPricing?.size ?: 0})"
            )
        }
    }

    /** Synchronous load — caller MUST be off the main thread. Used by
     *  bootstrap migrations that need LiteLLM / models.dev populated
     *  before running [Settings.recomputeAllCapabilities]. If a
     *  caller mistakenly invokes this on the main thread, ensureLoaded
     *  short-circuits without actually loading; flipping
     *  preloadCompleted=true regardless would silently disable the
     *  cold-start safety net that returns DEFAULT_PRICING during the
     *  preload window. Detect the misuse, log loudly, and leave the
     *  flag alone so subsequent main-thread getPricing calls keep
     *  returning DEFAULT until a real off-thread load completes. */
    fun ensureLoadedBlocking(context: Context) {
        if (isMainThread()) {
            AppLog.e(
                "PricingCache",
                "ensureLoadedBlocking invoked on the main thread — refusing to mark preload complete. " +
                    "Move the call to Dispatchers.IO."
            )
            return
        }
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
        findPricingMatch(provider, model, includeOverride = true)?.let {
            return tracePricing(provider, model, it.tier, it.pricing)
        }
        AppLog.d("PricingCache", "miss ${provider.id}/$model → DEFAULT")
        return DEFAULT_PRICING
    }

    fun isPreloadCompleted(): Boolean = preloadCompleted

    private fun tracePricing(provider: AppService, model: String, tier: String, p: ModelPricing): ModelPricing {
        AppLog.d("PricingCache", "match ${provider.id}/$model → $tier in=${p.promptPrice * 1_000_000} out=${p.completionPrice * 1_000_000}")
        return p
    }

    private fun isMainThread(): Boolean =
        android.os.Looper.myLooper() == android.os.Looper.getMainLooper()

    fun getPricingWithoutOverride(context: Context, provider: AppService, model: String): ModelPricing {
        ensureLoaded(context)
        return findPricingMatch(provider, model, includeOverride = false)?.pricing ?: DEFAULT_PRICING
    }

    /** Context-free, in-memory-only variant of [getPricing] used by
     *  [com.ai.model.Settings.recomputeCapabilities] to fill the
     *  per-provider modelPricing snapshot. Caller must have triggered
     *  [ensureLoadedBlocking] / [preloadAsync] first; this method itself
     *  never touches SharedPreferences or the bundled asset and never
     *  blocks. Returns DEFAULT_PRICING when catalogs aren't loaded. */
    fun lookupPricing(provider: AppService, model: String): ModelPricing {
        return findPricingMatch(provider, model, includeOverride = true)?.pricing ?: DEFAULT_PRICING
    }

    private data class PricingMatch(val tier: String, val pricing: ModelPricing)

    /** True when info provider [p] is enabled in the live settings. Reads
     *  [com.ai.model.SettingsHolder] (the same static mirror defaultMaxTokens
     *  uses) and defaults to ENABLED when settings aren't loaded yet, so a
     *  cold-start lookup never silently drops a tier. The per-provider
     *  finders return null when their tier is disabled, which gates pricing,
     *  capabilities, token-limits, the tier breakdown and the raw-entry
     *  getters in one place. */
    internal fun isInfoProviderEnabled(p: InfoProvider): Boolean =
        com.ai.model.SettingsHolder.current?.let { p.id !in it.disabledInfoProviders } ?: true

    private fun findPricingMatch(
        provider: AppService,
        model: String,
        includeOverride: Boolean
    ): PricingMatch? {
        val isOpenRouter = provider.crossProviderModelList
        val isTogether = provider.pricingFromModelList
        // OpenRouter SELF-report (caller IS OpenRouter) is the provider
        // pricing its own call, not a third-party catalog — intentionally
        // NOT gated by the OpenRouter info-provider toggle.
        if (isOpenRouter) findOpenRouterPricing(provider, model)?.let { return PricingMatch("OPENROUTER-SELF", it) }
        if (isTogether) findTogetherPricing(provider, model)?.let { return PricingMatch("TOGETHER-SELF", it) }
        if (includeOverride) manualPricing?.get("${provider.id}:$model")?.let { return PricingMatch("OVERRIDE", it) }
        findLiteLLMPricing(provider, model)?.let { return PricingMatch("LITELLM", it) }
        findModelsDevPricing(provider, model)?.let { return PricingMatch("MODELSDEV", it) }
        findLLMPricesPricing(provider, model)?.let { return PricingMatch("LLMPRICES", it) }
        findArtificialAnalysisPricing(provider, model)?.let { return PricingMatch("AA", it) }
        findLlmStatsPricing(provider, model)?.let { return PricingMatch("LLMSTATS", it) }
        // Cross-provider OpenRouter fallback IS gated by the toggle.
        if (!isOpenRouter && isInfoProviderEnabled(InfoProvider.OPENROUTER))
            findOpenRouterPricing(provider, model)?.let { return PricingMatch("OPENROUTER", it) }
        findRequestyPricing(provider, model)?.let { return PricingMatch("REQUESTY", it) }
        findHeliconePricing(provider, model)?.let { return PricingMatch("HELICONE", it) }
        return null
    }

    /** OpenRouter and Anthropic disagree on punctuation — Anthropic ships
     *  "claude-opus-4-6" while OpenRouter catalogs "anthropic/claude-opus-4.6".
     *  Normalize both sides to lowercase-dash for matching. */
    private fun normalizeModelId(s: String): String = s.replace('.', '-').lowercase()

    /** Resolve a `-latest` rolling alias to the catalog's most recent dated
     *  snapshot. Strips the `-latest` suffix, finds every key whose
     *  remainder after the stripped base is a supported date-like token,
     *  and picks the chronologically max. Supported forms are YYYYMMDD,
     *  YYYY-MM-DD, YYYYMM, YYYY-MM, and YYMM.
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
        val buckets = arrayOfNulls<Pair<String, Int>>(4)
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
            val suffixScore = latestAliasDateScore(suffix) ?: continue
            val cur = buckets[priority]
            if (cur == null || suffixScore > cur.second) buckets[priority] = key to suffixScore
        }
        return buckets.firstOrNull { it != null }?.first
    }

    private fun latestAliasDateScore(suffix: String): Int? {
        if (suffix.isBlank()) return null
        if (suffix.any { !it.isDigit() && it != '-' }) return null
        val compact = suffix.replace("-", "")
        if (compact.any { !it.isDigit() }) return null
        val (year, month, day) = when {
            suffix.contains('-') -> {
                val parts = suffix.split('-')
                when {
                    parts.size == 3 && parts[0].length == 4 && parts[1].length == 2 && parts[2].length == 2 ->
                        Triple(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
                    parts.size == 2 && parts[0].length == 4 && parts[1].length == 2 ->
                        Triple(parts[0].toInt(), parts[1].toInt(), 0)
                    else -> return null
                }
            }
            compact.length == 8 -> Triple(compact.take(4).toInt(), compact.substring(4, 6).toInt(), compact.takeLast(2).toInt())
            compact.length == 6 && compact.take(4).toIntOrNull()?.let { it in 2000..2199 } == true ->
                Triple(compact.take(4).toInt(), compact.substring(4, 6).toInt(), 0)
            compact.length == 4 -> Triple(2000 + compact.take(2).toInt(), compact.takeLast(2).toInt(), 0)
            else -> return null
        }
        if (year !in 2000..2199 || month !in 1..12 || day !in 0..31) return null
        return year * 10_000 + month * 100 + day
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
        if (!isInfoProviderEnabled(InfoProvider.LITELLM)) return null
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
        if (!isInfoProviderEnabled(InfoProvider.LITELLM)) return null
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

    /** Quantization / packaging tokens a host appends to a model id that
     *  the price catalogs don't carry (the quant variant is billed at the
     *  base model's rate). Stripped only for the last-resort bare match. */
    private val QUANT_SUFFIXES = setOf(
        "fp8", "fp16", "bf16", "fp4", "int8", "int4", "awq", "gptq", "gguf", "mlx", "w8a8", "w4a16"
    )

    /** Reduce an already-normalized id to a bare model name: drop the
     *  owner / routing prefix (`zai-org/`, `doubleword/`, `cloudflare/@cf/zai-org/`),
     *  an `@region` deployment tag (Requesty's `azure/gpt-4.1-mini@eastus2`),
     *  a `:` routing tag (`...:flex`, `...:free`), an optional [selfPrefix]
     *  — the provider stamping its own name as a dash-prefix
     *  (Parasail's `parasail-gpt-oss-120b`) — and a trailing quantization
     *  token (`-fp8`). Used ONLY for the lowest-priority match bucket, so
     *  a price for the same model under a different host / region / quant
     *  beats the 25/75 DEFAULT. */
    private fun bareModelKey(normalized: String, selfPrefix: String? = null): String {
        var t = normalized.substringAfterLast('/').substringBefore(':').substringBefore('@')
        if (selfPrefix != null && t.length > selfPrefix.length && t.startsWith(selfPrefix)) t = t.removePrefix(selfPrefix)
        val dash = t.lastIndexOf('-')
        if (dash > 0 && t.substring(dash + 1) in QUANT_SUFFIXES) t = t.substring(0, dash)
        return t
    }

    /** Bucket-rank a [target]-matching scan over [map] and return the
     *  highest-priority value, where buckets are: (0) bare key, (1)
     *  declared prefix, (2) provider.id.lowercase()/, (3) any other
     *  prefix, (4) bare model name. (0–3) prevent picking
     *  azure/bedrock/vertex variants when the provider's own catalog row
     *  exists. (4) is the last resort: when the queried id carries an
     *  owner/routing prefix, an `@region` / `:` tag, a `<self>-` prefix,
     *  or a quant suffix the catalogs don't use (`zhipu/glm-5.2`,
     *  `doubleword/glm-5.2`, `zai-org/GLM-5.2-FP8`, `…:flex`,
     *  `azure/gpt-4.1-mini@eastus2`, `parasail-gpt-oss-120b`), it still
     *  matches the same model's price under a different host instead of
     *  falling through to DEFAULT. Only enabled when something was
     *  actually stripped, so plain ids are unaffected. */
    private fun <V> findBestPrefixedMatch(
        map: Map<String, V>, provider: AppService, model: String,
        useLitellmPrefix: Boolean = false
    ): V? {
        val target = normalizeModelId(model)
        val declaredPrefix = if (useLitellmPrefix) provider.litellmPrefix else provider.openRouterName
        val targetDeclared = declaredPrefix?.let { "${normalizeModelId(it)}/$target" }
        val targetId = "${provider.id.lowercase()}/$target"
        // A provider stamping its own name as a dash-prefix (Parasail's
        // `parasail-…`) is stripped too — only the provider's OWN id, so it
        // can't over-strip an unrelated model name.
        val selfPrefix = "${provider.id.lowercase()}-"
        val bareTarget = bareModelKey(target, selfPrefix)
        // Bare matching is a deliberately loose fallback — only arm it when
        // the id actually had a prefix/suffix to strip and the remainder is
        // distinctive enough to not collide on a trivial token.
        val useBare = bareTarget != target && bareTarget.length > 2
        @Suppress("UNCHECKED_CAST")
        val buckets = arrayOfNulls<Any>(5) as Array<V?>
        for ((key, value) in map) {
            val k = normalizeModelId(key)
            val priority = when {
                k == target -> 0
                targetDeclared != null && k == targetDeclared -> 1
                k == targetId -> 2
                k.endsWith("/$target") -> 3
                useBare && bareModelKey(k, selfPrefix) == bareTarget -> 4
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

    /** One external pricing catalog's loaded state — entry count + last fetch
     *  ([fetchedAt] = 0 means never retrieved). */
    data class CatalogStat(val name: String, val entries: Int, val fetchedAt: Long)

    /** Entry count + retrieval timestamp for each of the eight external pricing
     *  info-providers, in lookup-precedence order. Drives the Monitor hub's
     *  "Pricing cache" table. Cheap — map sizes + volatile longs. */
    fun catalogStats(context: Context): List<CatalogStat> {
        ensureLoaded(context)
        return listOf(
            CatalogStat("LiteLLM", litellmPricing?.size ?: 0, litellmTimestamp),
            CatalogStat("models.dev", modelsDevPricing?.size ?: 0, modelsDevTimestamp),
            CatalogStat("llm-prices", llmPricesPricing?.size ?: 0, llmPricesTimestamp),
            CatalogStat("Artificial Analysis", aaPricing?.size ?: 0, aaTimestamp),
            CatalogStat("llm-stats", llmStatsPricing?.size ?: 0, llmStatsTimestamp),
            CatalogStat("OpenRouter", openRouterPricing?.size ?: 0, openRouterTimestamp),
            CatalogStat("Requesty", requestyPricing?.size ?: 0, requestyTimestamp),
            CatalogStat("Helicone", heliconePricing?.size ?: 0, heliconeTimestamp),
        )
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
        val llmStats: ModelPricing?,
        val override: ModelPricing?,
        val openrouter: ModelPricing?,
        val requesty: ModelPricing?,
        val together: ModelPricing?,
        val default: ModelPricing
    )

    fun getTierBreakdown(context: Context, provider: AppService, model: String): TierBreakdown {
        ensureLoaded(context)
        val litellm = findLiteLLMPricing(provider, model)
        val modelsDev = findModelsDevPricing(provider, model)
        val helicone = findHeliconePricing(provider, model)
        val llmPrices = findLLMPricesPricing(provider, model)
        val aa = findArtificialAnalysisPricing(provider, model)
        val llmStats = findLlmStatsPricing(provider, model)
        val override = manualPricing?.get("${provider.id}:$model")
        // Gate the OpenRouter cross-provider tier the same way findPricingMatch
        // does — but keep showing it when the caller IS OpenRouter (self-report).
        val openrouter = if (provider.crossProviderModelList || isInfoProviderEnabled(InfoProvider.OPENROUTER))
            findOpenRouterPricing(provider, model) else null
        val requesty = findRequestyPricing(provider, model)
        val together = findTogetherPricing(provider, model)
        return TierBreakdown(litellm, modelsDev, helicone, llmPrices, aa, llmStats, override, openrouter, requesty, together, DEFAULT_PRICING)
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
        val tiers = listOfNotNull(br.litellm, br.modelsDev, br.helicone, br.llmPrices, br.artificialAnalysis, br.llmStats, br.openrouter, br.requesty)
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

    /** Snapshot of a single catalog tier's previously-fetched state.
     *  [entryCount] is the number of priced/meta entries in the
     *  cached blob; [timestamp] is when the cache was last refreshed
     *  (epoch ms). Returned only when there is actual previous data —
     *  callers can rely on a non-null result meaning "we have
     *  something usable on disk if the next fetch fails". */
    data class PreviousCacheInfo(val entryCount: Int, val timestamp: Long) {
        /** "2d ago" / "5h ago" / "12min ago" / "just now". "never"
         *  if the timestamp is 0 (data on disk from a backup restore
         *  that didn't preserve the timestamp). */
        fun ageString(now: Long = System.currentTimeMillis()): String {
            if (timestamp == 0L) return "never"
            val ageMs = (now - timestamp).coerceAtLeast(0)
            val mins = ageMs / 60_000
            val hours = mins / 60
            val days = hours / 24
            return when {
                days >= 1 -> "${days}d ago"
                hours >= 1 -> "${hours}h ago"
                mins >= 1 -> "${mins}min ago"
                else -> "just now"
            }
        }
    }

    /** Look up the cached state of one catalog tier. [source] is the
     *  same key the Refresh-all step list uses: "openrouter",
     *  "litellm", "modelsdev", "helicone", "llmprices", "aa". Returns
     *  null when there is no previous cache (or the source key is
     *  unknown) — the Refresh UI surfaces this as "no previous to
     *  keep" vs "kept previous N entries from Xago". */
    fun previousCacheInfo(context: Context, source: String): PreviousCacheInfo? {
        ensureLoaded(context)
        val (cache, ts) = when (source.lowercase()) {
            "openrouter" -> openRouterPricing to openRouterTimestamp
            "litellm" -> litellmPricing to litellmTimestamp
            "modelsdev" -> modelsDevPricing to modelsDevTimestamp
            "helicone" -> heliconePricing to heliconeTimestamp
            "llmprices" -> llmPricesPricing to llmPricesTimestamp
            "aa" -> aaPricing to aaTimestamp
            "requesty" -> requestyPricing to requestyTimestamp
            "llmstats" -> llmStatsPricing to llmStatsTimestamp
            else -> return null
        }
        val map = cache ?: return null
        if (map.isEmpty()) return null
        return PreviousCacheInfo(map.size, ts)
    }

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
    suspend fun fetchLiteLLMPricingOnline(context: Context): Int? = withTraceCategory("pricing/LiteLLM") {
      withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            // Route through the shared OkHttp client (TracingInterceptor) so
            // the retrieve shows up in the in-app Trace screen — same as the
            // other pricing sources, and what the Costs tiers 🐞 links to.
            val json = ApiFactory.fetchUrlAsString("https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json")
            if (json.isNullOrBlank()) return@withContext null
            val (pricing, meta) = parseLiteLLMJson(json)
            if (pricing.isEmpty()) return@withContext null
            synchronized(lock) {
                litellmPricing = pricing
                litellmMeta = meta
                litellmMetaLookupCache.clear()
                litellmPricingLookupCache.clear()
                litellmTimestamp = System.currentTimeMillis()
                saveBlob(context, KEY_LITELLM_PRICING, gson.toJson(pricing))
                saveBlob(context, KEY_LITELLM_META, gson.toJson(meta))
                getPrefs(context).edit { putLong(KEY_LITELLM_TIMESTAMP, litellmTimestamp) }
            }
            pricing.size
        } catch (e: Exception) {
            AppLog.e("PricingCache", "Online LITELLM refresh failed: ${e.message}", e)
            null
        }
      }
    }

    /**
     * Pull https://models.dev/api.json — a community-curated catalog with
     * per-model pricing, capability flags, and context-window limits.
     * Returns the number of priced entries, or null on network/parse
     * failure. Values are cached in pricing_cache prefs (round-trips
     * through BackupManager via the existing PREFS_TO_BACKUP entry).
     */
    suspend fun fetchModelsDevOnline(context: Context): Int? = withTraceCategory("pricing/models.dev") {
      withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            // Route through the shared OkHttp client (TracingInterceptor +
            // proper timeouts) instead of java.net.URL.openStream — so a
            // silent failure shows up in the in-app Trace screen rather
            // than vanishing into Log.e nobody reads.
            val json = ApiFactory.fetchUrlAsString("https://models.dev/api.json")
            if (json.isNullOrBlank()) {
                AppLog.w("PricingCache", "models.dev refresh: empty / failed response")
                return@withContext null
            }
            val (pricing, meta) = parseModelsDevJson(json)
            AppLog.i("PricingCache", "models.dev parse: ${pricing.size} priced, ${meta.size} meta entries (raw ${json.length} bytes)")
            if (pricing.isEmpty() && meta.isEmpty()) return@withContext null
            synchronized(lock) {
                modelsDevPricing = pricing
                modelsDevMeta = meta
                modelsDevMetaLookupCache.clear()
                modelsDevTimestamp = System.currentTimeMillis()
                saveBlob(context, KEY_MODELS_DEV_PRICING, gson.toJson(pricing))
                saveBlob(context, KEY_MODELS_DEV_META, gson.toJson(meta))
                getPrefs(context).edit { putLong(KEY_MODELS_DEV_TIMESTAMP, modelsDevTimestamp) }
            }
            pricing.size
        } catch (e: Exception) {
            AppLog.e("PricingCache", "models.dev refresh failed: ${e.message}", e)
            null
        }
      }
    }

    /** Walk models.dev's two-level shape (provider → models → model entry)
     *  and produce two maps keyed `<provider>/<modelId>` (mirrors LiteLLM
     *  so `endsWith("/$target")` lookups still work). Cost numbers in
     *  models.dev are already \$/M-token — we divide by 1M to land in our
     *  per-token unit. */

    private fun findModelsDevPricing(provider: AppService, model: String): ModelPricing? {
        if (!isInfoProviderEnabled(InfoProvider.MODELS_DEV)) return null
        val pricing = modelsDevPricing ?: return null
        pricing[model]?.let { return it }
        return findBestPrefixedMatch(pricing, provider, model, useLitellmPrefix = true)
            ?: findLatestAliasKey(pricing.keys, model, provider.litellmPrefix, provider.id.lowercase())?.let { pricing[it] }
    }

    private fun findModelsDevMeta(provider: AppService, model: String): ModelsDevMeta? {
        if (!isInfoProviderEnabled(InfoProvider.MODELS_DEV)) return null
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

    fun modelsDevMaxOutputTokens(provider: AppService, model: String): Int? =
        findModelsDevMeta(provider, model)?.maxOutputTokens

    /** Same-provider-ONLY models.dev meta lookup. Matches the model under
     *  THIS provider's own catalog key — exact id, declared litellmPrefix,
     *  or `<provider.id>/<id>` — and deliberately skips the loose
     *  cross-provider `endsWith` / bare-name buckets that [findModelsDevMeta]
     *  falls through to. [defaultMaxTokens] uses the token-limit variants so
     *  a reseller's context window (e.g. `submodel/…/DeepSeek-V3-0324`'s
     *  75 000) can't masquerade as the caller's real limit. Not memoized —
     *  called once per outbound API call, not per UI row. */
    private fun findModelsDevMetaOwn(provider: AppService, model: String): ModelsDevMeta? {
        if (!isInfoProviderEnabled(InfoProvider.MODELS_DEV)) return null
        val meta = modelsDevMeta ?: return null
        meta[model]?.let { return it }
        val target = normalizeModelId(model)
        val declared = provider.litellmPrefix?.takeIf { it.isNotBlank() }?.let { "${normalizeModelId(it)}/$target" }
        val byId = "${provider.id.lowercase()}/$target"
        for ((k, v) in meta) {
            val nk = normalizeModelId(k)
            if (nk == target || (declared != null && nk == declared) || nk == byId) return v
        }
        return null
    }

    fun modelsDevMaxInputTokensOwn(provider: AppService, model: String): Int? =
        findModelsDevMetaOwn(provider, model)?.maxInputTokens

    fun modelsDevMaxOutputTokensOwn(provider: AppService, model: String): Int? =
        findModelsDevMetaOwn(provider, model)?.maxOutputTokens

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

    suspend fun fetchHeliconeOnline(context: Context): Int? = withTraceCategory("pricing/Helicone") {
      withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val json = ApiFactory.fetchUrlAsString("https://www.helicone.ai/api/llm-costs")
            if (json.isNullOrBlank()) {
                AppLog.w("PricingCache", "Helicone refresh: empty / failed response")
                return@withContext null
            }
            val (exact, patterns) = parseHeliconeJson(json)
            AppLog.i("PricingCache", "Helicone parse: ${exact.size} exact, ${patterns.size} patterns")
            if (exact.isEmpty() && patterns.isEmpty()) return@withContext null
            synchronized(lock) {
                heliconePricing = exact
                heliconePatterns = patterns
                heliconeTimestamp = System.currentTimeMillis()
                saveBlob(context, KEY_HELICONE_PRICING, gson.toJson(exact))
                saveBlob(context, KEY_HELICONE_PATTERNS, gson.toJson(patterns))
                getPrefs(context).edit { putLong(KEY_HELICONE_TIMESTAMP, heliconeTimestamp) }
            }
            exact.size + patterns.size
        } catch (e: Exception) {
            AppLog.e("PricingCache", "Helicone refresh failed: ${e.message}", e)
            null
        }
      }
    }

    private fun findHeliconePricing(provider: AppService, model: String): ModelPricing? {
        if (!isInfoProviderEnabled(InfoProvider.HELICONE)) return null
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
        // No same-provider pattern hit — fall back to the first fan out-provider
        // pattern as a last resort (e.g. when the user routes a model id
        // through a generic provider). Require at least 4 chars on the
        // pattern so a short string like "claude" can't hijack pricing
        // from an unrelated provider's catalog.
        for (p in patterns) {
            val pat = normalizeModelId(p.pattern)
            if (pat.length < 4) continue
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


    suspend fun fetchLLMPricesOnline(context: Context): Int? = withTraceCategory("pricing/llm-prices") {
      withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val combined = mutableMapOf<String, ModelPricing>()
            for (vendor in llmPricesVendors) {
                val url = "https://raw.githubusercontent.com/simonw/llm-prices/main/data/$vendor.json"
                val json = ApiFactory.fetchUrlAsString(url) ?: continue
                combined.putAll(parseLLMPricesVendorJson(vendor, json))
            }
            AppLog.i("PricingCache", "llm-prices parse: ${combined.size} entries from ${llmPricesVendors.size} vendors")
            if (combined.isEmpty()) return@withContext null
            synchronized(lock) {
                llmPricesPricing = combined
                llmPricesTimestamp = System.currentTimeMillis()
                saveBlob(context, KEY_LLMPRICES_PRICING, gson.toJson(combined))
                getPrefs(context).edit { putLong(KEY_LLMPRICES_TIMESTAMP, llmPricesTimestamp) }
            }
            combined.size
        } catch (e: Exception) {
            AppLog.e("PricingCache", "llm-prices refresh failed: ${e.message}", e)
            null
        }
      }
    }

    private fun findLLMPricesPricing(provider: AppService, model: String): ModelPricing? {
        if (!isInfoProviderEnabled(InfoProvider.LLM_PRICES)) return null
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

    suspend fun fetchArtificialAnalysisOnline(context: Context, apiKey: String): Int? = withTraceCategory("pricing/Artificial Analysis") {
      withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (apiKey.isBlank()) {
            AppLog.w("PricingCache", "Artificial Analysis refresh skipped: missing API key")
            return@withContext null
        }
        try {
            val json = ApiFactory.fetchUrlAsString(
                "https://artificialanalysis.ai/api/v2/data/llms/models",
                headers = mapOf("x-api-key" to apiKey)
            )
            if (json.isNullOrBlank()) {
                AppLog.w("PricingCache", "Artificial Analysis refresh: empty / failed response")
                return@withContext null
            }
            val (pricing, meta) = parseArtificialAnalysisJson(json)
            AppLog.i("PricingCache", "Artificial Analysis parse: ${pricing.size} priced, ${meta.size} meta entries")
            if (pricing.isEmpty() && meta.isEmpty()) return@withContext null
            synchronized(lock) {
                aaPricing = pricing
                aaMeta = meta
                aaTimestamp = System.currentTimeMillis()
                saveBlob(context, KEY_AA_PRICING, gson.toJson(pricing))
                saveBlob(context, KEY_AA_META, gson.toJson(meta))
                getPrefs(context).edit { putLong(KEY_AA_TIMESTAMP, aaTimestamp) }
            }
            pricing.size + meta.size
        } catch (e: Exception) {
            AppLog.e("PricingCache", "Artificial Analysis refresh failed: ${e.message}", e)
            null
        }
      }
    }

    private fun findArtificialAnalysisPricing(provider: AppService, model: String): ModelPricing? {
        if (!isInfoProviderEnabled(InfoProvider.ARTIFICIAL_ANALYSIS)) return null
        val pricing = aaPricing ?: return null
        pricing[model]?.let { return it }
        return findBestPrefixedMatch(pricing, provider, model, useLitellmPrefix = true)
            ?: findLatestAliasKey(pricing.keys, model, provider.litellmPrefix, provider.id.lowercase())?.let { pricing[it] }
    }

    private fun findArtificialAnalysisMeta(provider: AppService, model: String): ArtificialAnalysisMeta? {
        if (!isInfoProviderEnabled(InfoProvider.ARTIFICIAL_ANALYSIS)) return null
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

    // ============================================================================
    // Requesty — cross-provider router catalog (router.requesty.ai/v1/models)
    // Auth: none (public models). Pricing already per-token + capability flags.
    // ============================================================================

    /** Pull Requesty's `/v1/models` catalog and replace the REQUESTY tier.
     *  Keyless (the public catalog returns every approved/public model).
     *  Returns the number of priced entries, or null on network/parse
     *  failure. */
    suspend fun fetchRequestyOnline(context: Context): Int? = withTraceCategory("pricing/Requesty") {
      withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val json = ApiFactory.fetchUrlAsString("https://router.requesty.ai/v1/models")
            if (json.isNullOrBlank()) {
                AppLog.w("PricingCache", "Requesty refresh: empty / failed response")
                return@withContext null
            }
            val (pricing, meta) = parseRequestyJson(json)
            AppLog.i("PricingCache", "Requesty parse: ${pricing.size} priced, ${meta.size} meta entries")
            if (pricing.isEmpty() && meta.isEmpty()) return@withContext null
            synchronized(lock) {
                requestyPricing = pricing
                requestyMeta = meta
                requestyTimestamp = System.currentTimeMillis()
                saveBlob(context, KEY_REQUESTY_PRICING, gson.toJson(pricing))
                saveBlob(context, KEY_REQUESTY_META, gson.toJson(meta))
                getPrefs(context).edit { putLong(KEY_REQUESTY_TIMESTAMP, requestyTimestamp) }
            }
            pricing.size
        } catch (e: Exception) {
            AppLog.e("PricingCache", "Requesty refresh failed: ${e.message}", e)
            null
        }
      }
    }

    /** Requesty ids are `<vendor>/<modelId>` (OpenRouter-style), so we
     *  resolve them exactly like findOpenRouterPricing — exact key, then
     *  the provider's openRouterName prefix, then the bucketed scan. */
    private fun findRequestyPricing(provider: AppService, model: String): ModelPricing? {
        if (!isInfoProviderEnabled(InfoProvider.REQUESTY)) return null
        val pricing = requestyPricing ?: return null
        pricing[model]?.let { return it }
        provider.openRouterName?.let { prefix -> pricing["$prefix/$model"]?.let { return it } }
        return findBestPrefixedMatch(pricing, provider, model)
            ?: findLatestAliasKey(pricing.keys, model, provider.openRouterName, provider.id.lowercase())?.let { pricing[it] }
    }

    private fun findRequestyMeta(provider: AppService, model: String): RequestyMeta? {
        if (!isInfoProviderEnabled(InfoProvider.REQUESTY)) return null
        val meta = requestyMeta ?: return null
        meta[model]?.let { return it }
        provider.openRouterName?.let { prefix -> meta["$prefix/$model"]?.let { return it } }
        return findBestPrefixedMatch(meta, provider, model)
            ?: findLatestAliasKey(meta.keys, model, provider.openRouterName, provider.id.lowercase())?.let { meta[it] }
    }

    fun requestySupportsVision(provider: AppService, model: String): Boolean? =
        findRequestyMeta(provider, model)?.supportsVision

    fun requestySupportsReasoning(provider: AppService, model: String): Boolean? =
        findRequestyMeta(provider, model)?.supportsReasoning

    fun requestySupportsWebSearch(provider: AppService, model: String): Boolean? =
        findRequestyMeta(provider, model)?.supportsWebSearch

    fun getRequestyRawEntry(context: Context, provider: AppService, model: String): String? {
        ensureLoaded(context)
        val pricing = findRequestyPricing(provider, model)
        val meta = findRequestyMeta(provider, model)
        if (pricing == null && meta == null) return null
        val pretty = createAppGson(prettyPrint = true)
        return pretty.toJson(mapOf("pricing" to pricing, "meta" to meta))
    }

    // ============================================================================
    // llm-stats.com — curated catalog: per-provider pricing + benchmark scores
    // Endpoint: https://api.llm-stats.com/stats/v1/models  (paginated, cursor)
    // Auth: Authorization: Bearer <key> (+ Stats API onboarding required)
    // ============================================================================

    /** Pull the llm-stats catalog and replace the LLMSTATS tier. The
     *  endpoint is paginated (`next_cursor`), so this walks every page
     *  (capped at 20) accumulating priced + meta entries. Returns the
     *  number of priced entries, or null on auth / network / parse
     *  failure (a blank key or the 403 `stats_api_access_denied` that
     *  precedes Stats-API onboarding both yield null). */
    suspend fun fetchLlmStatsOnline(context: Context, apiKey: String): Int? = withTraceCategory("pricing/llm-stats") {
      withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (apiKey.isBlank()) {
            AppLog.w("PricingCache", "llm-stats refresh skipped: missing API key")
            return@withContext null
        }
        try {
            val pricing = mutableMapOf<String, ModelPricing>()
            val meta = mutableMapOf<String, LlmStatsMeta>()
            var cursor: String? = null
            var pages = 0
            do {
                val url = buildString {
                    append("https://api.llm-stats.com/stats/v1/models?limit=200")
                    cursor?.let { append("&cursor="); append(java.net.URLEncoder.encode(it, "UTF-8")) }
                }
                val json = ApiFactory.fetchUrlAsString(url, headers = mapOf("Authorization" to "Bearer $apiKey"))
                if (json.isNullOrBlank()) break
                val (p, m, next) = parseLlmStatsJson(json)
                pricing.putAll(p); meta.putAll(m)
                cursor = next?.takeIf { it.isNotBlank() }
                pages++
            } while (cursor != null && pages < 20)
            AppLog.i("PricingCache", "llm-stats parse: ${pricing.size} priced, ${meta.size} meta entries ($pages pages)")
            if (pricing.isEmpty() && meta.isEmpty()) return@withContext null
            synchronized(lock) {
                llmStatsPricing = pricing
                llmStatsMeta = meta
                llmStatsTimestamp = System.currentTimeMillis()
                saveBlob(context, KEY_LLMSTATS_PRICING, gson.toJson(pricing))
                saveBlob(context, KEY_LLMSTATS_META, gson.toJson(meta))
                getPrefs(context).edit { putLong(KEY_LLMSTATS_TIMESTAMP, llmStatsTimestamp) }
            }
            pricing.size
        } catch (e: Exception) {
            AppLog.e("PricingCache", "llm-stats refresh failed: ${e.message}", e)
            null
        }
      }
    }

    /** llm-stats keys are `<org>/<modelId>` (creator-slug style, like AA),
     *  so the lookup mirrors findArtificialAnalysisPricing. */
    private fun findLlmStatsPricing(provider: AppService, model: String): ModelPricing? {
        if (!isInfoProviderEnabled(InfoProvider.LLM_STATS)) return null
        val pricing = llmStatsPricing ?: return null
        pricing[model]?.let { return it }
        return findBestPrefixedMatch(pricing, provider, model, useLitellmPrefix = true)
            ?: findLatestAliasKey(pricing.keys, model, provider.litellmPrefix, provider.id.lowercase())?.let { pricing[it] }
    }

    private fun findLlmStatsMeta(provider: AppService, model: String): LlmStatsMeta? {
        if (!isInfoProviderEnabled(InfoProvider.LLM_STATS)) return null
        val meta = llmStatsMeta ?: return null
        meta[model]?.let { return it }
        return findBestPrefixedMatch(meta, provider, model, useLitellmPrefix = true)
            ?: findLatestAliasKey(meta.keys, model, provider.litellmPrefix, provider.id.lowercase())?.let { meta[it] }
    }

    fun llmStatsSupportsVision(provider: AppService, model: String): Boolean? =
        findLlmStatsMeta(provider, model)?.supportsVision

    fun getLlmStatsRawEntry(context: Context, provider: AppService, model: String): String? {
        ensureLoaded(context)
        val pricing = findLlmStatsPricing(provider, model)
        val meta = findLlmStatsMeta(provider, model)
        if (pricing == null && meta == null) return null
        val pretty = createAppGson(prettyPrint = true)
        return pretty.toJson(mapOf("pricing" to pricing, "meta" to meta))
    }

    suspend fun fetchOpenRouterPricing(apiKey: String): Map<String, ModelPricing> {
        if (apiKey.isBlank()) return emptyMap()
        return withTraceCategory("pricing/OpenRouter") {
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

    /** filesDir/pricing/<key>.json — the new home for tier blobs. SharedPreferences was
     *  the wrong store for ~1 MB JSON each: it loads the entire prefs map into RAM at
     *  process start and keeps it there forever. Each tier now lives in its own file
     *  and is read on demand. Timestamps stay in prefs (small longs, hot-read). */
    private fun blobFile(context: Context, name: String): java.io.File {
        val dir = java.io.File(context.filesDir, "pricing").also { it.mkdirs() }
        return java.io.File(dir, "$name.json")
    }

    /** Read a tier blob. Look-up order:
     *   1. `filesDir/pricing/<key>.json` — the post-Refresh on-disk copy.
     *   2. Bundled `assets/info-providers/<key>.json` — shipped snapshot
     *      so a fresh install has working pricing/capabilities tiers
     *      without forcing the user through Housekeeping → Refresh on
     *      first run. Not written through to filesDir: timestamps stay
     *      unset (the UI still surfaces "never refreshed"), and the
     *      next Refresh overwrites the in-memory state and persists to
     *      filesDir as usual. */
    private fun loadBlob(context: Context, prefsKey: String): String? {
        val f = blobFile(context, prefsKey)
        if (f.exists()) return runCatching { f.readText() }.getOrNull()
        return loadBundledInfoProviderBlob(context, prefsKey)
    }

    /** Read a bundled tier blob from `assets/info-providers/<key>.json`.
     *  Used by [loadBlob] when the post-Refresh file doesn't exist —
     *  gives a fresh install pre-populated pricing / capability tiers
     *  until the user runs Refresh. */
    private fun loadBundledInfoProviderBlob(context: Context, prefsKey: String): String? = try {
        context.assets.open("info-providers/$prefsKey.json")
            .bufferedReader().use { it.readText() }
    } catch (_: java.io.IOException) {
        null  // Asset absent — tier wasn't shipped, fall through to DEFAULT_PRICING.
    }

    /** Atomically write a tier blob. */
    private fun saveBlob(context: Context, prefsKey: String, json: String) {
        blobFile(context, prefsKey).writeTextAtomic(json)
    }

    /** Lazy first-call population for every cache tier. Cold-call cost is dominated
     *  by the LiteLLM bundled-asset parse (~1.2 MB JSON → ~3k entries) plus six
     *  smaller SharedPreferences blobs. Once preload has completed (via [preloadAsync]
     *  at app start, or [ensureLoadedBlocking] from a non-main caller), this is a
     *  no-op past the per-tier null checks below.
     *
     *  Main-thread guard: if a UI caller hits this before the preload has finished,
     *  short-circuit instead of blocking on the synchronized lock + reflective parse.
     *  Affected lookups will fall through to DEFAULT_PRICING / null capability flags
     *  for the duration of the cold window — Compose will recompose with real values
     *  once the preload completes and the next state-driven recompose reads them.
     *  This makes every public getter (getPricing, pricesConflict, getTierBreakdown,
     *  getPricingStats, getOpenRouterCacheAge, the manual-override CRUD, the raw-entry
     *  inspectors) safe to call from a Composable without an explicit guard at every
     *  site. */
    private fun ensureLoaded(context: Context) {
        if (!preloadCompleted && isMainThread()) return
        synchronized(lock) {
            ensureLoadedLocked(context)
        }
    }

    private fun ensureLoadedLocked(context: Context) {
        if (manualPricing == null) loadManualPricing(context)
        if (openRouterPricing == null) loadFromPrefs(context)
        // LiteLLM: no bundled asset (network only). The user populates the
        // tier with Refresh → LiteLLM; subsequent app starts read from
        // pricing_cache prefs. A fresh install with no refresh yet leaves
        // litellmPricing/Meta as empty maps — the layered lookup just
        // falls through to the next tier.
        if (litellmPricing == null) {
            val prefs = getPrefs(context)
            loadBlob(context, KEY_LITELLM_PRICING)?.let { json ->
                try { litellmPricing = gson.fromJson(json, mapModelPricingType); litellmTimestamp = prefs.getLong(KEY_LITELLM_TIMESTAMP, 0) }
                catch (_: Exception) {}
            }
            if (litellmPricing == null) litellmPricing = emptyMap()
            litellmPricingLookupCache.clear()
        }
        if (litellmMeta == null) {
            loadBlob(context, KEY_LITELLM_META)?.let { json ->
                try {
                    val type = object : TypeToken<Map<String, LiteLLMMeta>>() {}.type
                    litellmMeta = gson.fromJson(json, type)
                } catch (_: Exception) {}
            }
            if (litellmMeta == null) litellmMeta = emptyMap()
            litellmMetaLookupCache.clear()
        }
        // models.dev: no bundled asset (network only). Both maps live in
        // filesDir/pricing/, repopulate from there if present. The user's
        // first refresh populates them; subsequent app restarts read from
        // disk.
        if (modelsDevPricing == null || modelsDevMeta == null) {
            modelsDevTimestamp = getPrefs(context).getLong(KEY_MODELS_DEV_TIMESTAMP, 0)
            loadBlob(context, KEY_MODELS_DEV_PRICING)?.let { json ->
                try { modelsDevPricing = gson.fromJson(json, mapModelPricingType) } catch (_: Exception) {}
            }
            loadBlob(context, KEY_MODELS_DEV_META)?.let { json ->
                try {
                    val type = object : TypeToken<Map<String, ModelsDevMeta>>() {}.type
                    modelsDevMeta = gson.fromJson(json, type)
                } catch (_: Exception) {}
            }
            // Memoize a missing tier as "loaded-empty" (Bug 36) so the
            // `== null` guard short-circuits next time — otherwise every
            // getPricing (per cost-table / picker row) re-ran loadBlob,
            // which on a never-refreshed install means a File.exists() +
            // failing assets.open() per call while scrolling a list.
            if (modelsDevPricing == null) modelsDevPricing = emptyMap()
            if (modelsDevMeta == null) modelsDevMeta = emptyMap()
            modelsDevMetaLookupCache.clear()
        }
        // Helicone — exact map plus pattern list. Both are network-only
        // (no bundled asset); empty until the user runs the refresh.
        if (heliconePricing == null || heliconePatterns == null) {
            heliconeTimestamp = getPrefs(context).getLong(KEY_HELICONE_TIMESTAMP, 0)
            loadBlob(context, KEY_HELICONE_PRICING)?.let { json ->
                try { heliconePricing = gson.fromJson(json, mapModelPricingType) } catch (_: Exception) {}
            }
            loadBlob(context, KEY_HELICONE_PATTERNS)?.let { json ->
                try {
                    val type = object : TypeToken<List<HeliconePattern>>() {}.type
                    heliconePatterns = gson.fromJson(json, type)
                } catch (_: Exception) {}
            }
            if (heliconePricing == null) heliconePricing = emptyMap()
            if (heliconePatterns == null) heliconePatterns = emptyList()
        }
        // llm-prices.com — single combined map.
        if (llmPricesPricing == null) {
            llmPricesTimestamp = getPrefs(context).getLong(KEY_LLMPRICES_TIMESTAMP, 0)
            loadBlob(context, KEY_LLMPRICES_PRICING)?.let { json ->
                try { llmPricesPricing = gson.fromJson(json, mapModelPricingType) } catch (_: Exception) {}
            }
            if (llmPricesPricing == null) llmPricesPricing = emptyMap()
        }
        // Artificial Analysis — pricing + sidecar.
        if (aaPricing == null || aaMeta == null) {
            aaTimestamp = getPrefs(context).getLong(KEY_AA_TIMESTAMP, 0)
            loadBlob(context, KEY_AA_PRICING)?.let { json ->
                try { aaPricing = gson.fromJson(json, mapModelPricingType) } catch (_: Exception) {}
            }
            loadBlob(context, KEY_AA_META)?.let { json ->
                try {
                    val type = object : TypeToken<Map<String, ArtificialAnalysisMeta>>() {}.type
                    aaMeta = gson.fromJson(json, type)
                } catch (_: Exception) {}
            }
            if (aaPricing == null) aaPricing = emptyMap()
            if (aaMeta == null) aaMeta = emptyMap()
        }
        // Requesty — pricing + capability sidecar. Bundled asset ships a
        // snapshot, so a fresh install has Requesty pricing before the
        // first refresh (same as the other curated tiers).
        if (requestyPricing == null || requestyMeta == null) {
            requestyTimestamp = getPrefs(context).getLong(KEY_REQUESTY_TIMESTAMP, 0)
            loadBlob(context, KEY_REQUESTY_PRICING)?.let { json ->
                try { requestyPricing = gson.fromJson(json, mapModelPricingType) } catch (_: Exception) {}
            }
            loadBlob(context, KEY_REQUESTY_META)?.let { json ->
                try {
                    val type = object : TypeToken<Map<String, RequestyMeta>>() {}.type
                    requestyMeta = gson.fromJson(json, type)
                } catch (_: Exception) {}
            }
            if (requestyPricing == null) requestyPricing = emptyMap()
            if (requestyMeta == null) requestyMeta = emptyMap()
        }
        // llm-stats — pricing + benchmark/modality sidecar. Bundled asset
        // ships a snapshot so the tier works on a fresh install before the
        // user adds their key.
        if (llmStatsPricing == null || llmStatsMeta == null) {
            llmStatsTimestamp = getPrefs(context).getLong(KEY_LLMSTATS_TIMESTAMP, 0)
            loadBlob(context, KEY_LLMSTATS_PRICING)?.let { json ->
                try { llmStatsPricing = gson.fromJson(json, mapModelPricingType) } catch (_: Exception) {}
            }
            loadBlob(context, KEY_LLMSTATS_META)?.let { json ->
                try {
                    val type = object : TypeToken<Map<String, LlmStatsMeta>>() {}.type
                    llmStatsMeta = gson.fromJson(json, type)
                } catch (_: Exception) {}
            }
            if (llmStatsPricing == null) llmStatsPricing = emptyMap()
            if (llmStatsMeta == null) llmStatsMeta = emptyMap()
        }
        // Once we've finished loading every tier, mark the cache
        // primed so the main-thread guard in ensureLoaded() stops
        // short-circuiting future getPricing calls. Previously only
        // ensureLoadedBlocking flipped this flag — non-main IO callers
        // (the synthetic preload from AppViewModel.bootstrap, ad-hoc
        // suspend callers from coroutines) finished the load but never
        // marked it complete, so main-thread getPricing kept returning
        // DEFAULT_PRICING long after every blob was already in memory.
        preloadCompleted = true
    }

    private fun loadFromPrefs(context: Context) {
        val prefs = getPrefs(context)
        val json = loadBlob(context, KEY_OPENROUTER_PRICING)
        openRouterTimestamp = prefs.getLong(KEY_OPENROUTER_TIMESTAMP, 0)
        openRouterPricing = if (json != null) {
            try { gson.fromJson(json, mapModelPricingType) } catch (_: Exception) { emptyMap() }
        } else emptyMap()
        val tj = loadBlob(context, KEY_TOGETHER_PRICING)
        togetherTimestamp = prefs.getLong(KEY_TOGETHER_TIMESTAMP, 0)
        togetherPricing = if (tj != null) {
            try { gson.fromJson(tj, mapModelPricingType) } catch (_: Exception) { emptyMap() }
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

    /** Sidecar for the llm-stats tier — surfaces the data points that
     *  distinguish it: the per-benchmark `top_scores` map (note: mixed
     *  scales upstream, so stored raw and never collapsed to one index)
     *  and the `modalities` list. [supportsVision] is derived from
     *  modalities containing "image" and feeds the capability chain. */
    data class LlmStatsMeta(
        val supportsVision: Boolean? = null,
        val modalities: List<String>? = null,
        val organization: String? = null,
        val topScores: Map<String, Double>? = null
    )

    /** Capability sidecar derived from Requesty's /v1/models flags.
     *  vision / reasoning / web-search feed the layered capability chain
     *  (after models.dev); computer-use / tool-calling / token limits are
     *  kept for the raw Model-Info view. */
    data class RequestyMeta(
        val supportsVision: Boolean? = null,
        val supportsReasoning: Boolean? = null,
        val supportsComputerUse: Boolean? = null,
        val supportsToolCalling: Boolean? = null,
        val supportsWebSearch: Boolean? = null,
        val maxInputTokens: Int? = null,
        val maxOutputTokens: Int? = null
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

    // Model specifications from OpenRouter
    data class ModelPricingEntry(val provider: String, val model: String, val pricing: OpenRouterPricing?)
    data class ModelSupportedParametersEntry(val provider: String, val model: String, val supported_parameters: List<String>?)

    suspend fun fetchAndSaveModelSpecifications(context: Context, apiKey: String): Pair<Int, Int>? {
        if (apiKey.isBlank()) return null
        return withTraceCategory("OpenRouter model specs") {
            try {
                val api = ApiFactory.createOpenRouterModelsApi("https://openrouter.ai/api/")
                val response = api.listModelsDetailed("Bearer $apiKey")
                if (!response.isSuccessful) {
                    null
                } else {
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
                    // Atomic — process death mid-write would otherwise
                    // truncate the cache; the supported-parameters loader
                    // catches the parse exception and falls back to an
                    // empty map, so every report would send every parameter
                    // regardless of model support until the next refresh.
                    // model_pricing.json is intentionally NOT written — nothing
                    // reads it back (pricing resolves through the layered
                    // getPricing tiers). pricingEntries is kept only to report
                    // the priced-model count in the return value.
                    java.io.File(context.filesDir, "model_supported_parameters.json").writeTextAtomic(gson.toJson(parametersEntries))
                    clearSupportedParametersCache()
                    Pair(pricingEntries.size, parametersEntries.size)
                }
            } catch (e: Exception) { AppLog.e("PricingCache", "Failed: ${e.message}"); null }
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

    /** Every cached OpenRouter supported-parameters entry, for the
     *  Caches → Supported params screen. */
    fun listSupportedParameters(context: Context): List<ModelSupportedParametersEntry> {
        val file = java.io.File(context.filesDir, "model_supported_parameters.json")
        if (!file.exists()) return emptyList()
        return try { gson.fromJson(file.readText(), listSupportedParamsType) ?: emptyList() }
        catch (_: Exception) { emptyList() }
    }

    /** Drop one (provider, model) supported-parameters entry from the
     *  on-disk catalog + the in-memory cache. */
    fun deleteSupportedParameter(context: Context, provider: String, model: String) {
        val file = java.io.File(context.filesDir, "model_supported_parameters.json")
        if (!file.exists()) return
        try {
            val all: List<ModelSupportedParametersEntry> =
                gson.fromJson(file.readText(), listSupportedParamsType) ?: return
            val filtered = all.filterNot { it.provider == provider && it.model == model }
            file.writeTextAtomic(gson.toJson(filtered))
            clearSupportedParametersCache()
        } catch (_: Exception) {}
    }

    /** Drop ONE Info-provider pricing tier (by its [CatalogStat.name]) —
     *  blob file(s), timestamp, and in-memory state — leaving the other
     *  five tiers intact. Per-source sibling of [clearInfoProviderTiers],
     *  wired to the Caches → Pricing tiers screen's 🗑. */
    fun deleteTier(context: Context, source: String) = synchronized(lock) {
        val blobKeys: List<String> = when (source) {
            "LiteLLM" -> listOf(KEY_LITELLM_PRICING, KEY_LITELLM_META)
            "models.dev" -> listOf(KEY_MODELS_DEV_PRICING, KEY_MODELS_DEV_META)
            "llm-prices" -> listOf(KEY_LLMPRICES_PRICING)
            "Artificial Analysis" -> listOf(KEY_AA_PRICING, KEY_AA_META)
            "OpenRouter" -> listOf(KEY_OPENROUTER_PRICING)
            "Requesty" -> listOf(KEY_REQUESTY_PRICING, KEY_REQUESTY_META)
            "llm-stats" -> listOf(KEY_LLMSTATS_PRICING, KEY_LLMSTATS_META)
            "Helicone" -> listOf(KEY_HELICONE_PRICING, KEY_HELICONE_PATTERNS)
            else -> emptyList()
        }
        if (blobKeys.isEmpty()) return@synchronized
        val tsKey = when (source) {
            "LiteLLM" -> KEY_LITELLM_TIMESTAMP
            "models.dev" -> KEY_MODELS_DEV_TIMESTAMP
            "llm-prices" -> KEY_LLMPRICES_TIMESTAMP
            "Artificial Analysis" -> KEY_AA_TIMESTAMP
            "OpenRouter" -> KEY_OPENROUTER_TIMESTAMP
            "Requesty" -> KEY_REQUESTY_TIMESTAMP
            "llm-stats" -> KEY_LLMSTATS_TIMESTAMP
            "Helicone" -> KEY_HELICONE_TIMESTAMP
            else -> null
        }
        blobKeys.forEach { try { blobFile(context, it).delete() } catch (_: Exception) {} }
        getPrefs(context).edit { blobKeys.forEach { remove(it) }; tsKey?.let { remove(it) } }
        when (source) {
            "LiteLLM" -> { litellmPricing = null; litellmMeta = null; litellmTimestamp = 0
                litellmMetaLookupCache.clear(); litellmPricingLookupCache.clear() }
            "models.dev" -> { modelsDevPricing = null; modelsDevMeta = null; modelsDevTimestamp = 0
                modelsDevMetaLookupCache.clear() }
            "llm-prices" -> { llmPricesPricing = null; llmPricesTimestamp = 0 }
            "Artificial Analysis" -> { aaPricing = null; aaMeta = null; aaTimestamp = 0 }
            "OpenRouter" -> { openRouterPricing = null; openRouterTimestamp = 0 }
            "Requesty" -> { requestyPricing = null; requestyMeta = null; requestyTimestamp = 0 }
            "llm-stats" -> { llmStatsPricing = null; llmStatsMeta = null; llmStatsTimestamp = 0 }
            "Helicone" -> { heliconePricing = null; heliconePatterns = null; heliconeTimestamp = 0 }
        }
    }

    /** Wipe the six Info-provider catalog tiers (OpenRouter, LiteLLM,
     *  models.dev, Helicone, llm-prices, Artificial Analysis) plus the
     *  OpenRouter model-specs cache. Manual cost overrides and the
     *  Together-native pricing (harvested from Together's /v1/models
     *  response) are preserved — neither comes from an Info provider. */
    fun clearInfoProviderTiers(context: Context) = synchronized(lock) {
        val tierBlobs = listOf(
            KEY_OPENROUTER_PRICING, KEY_LITELLM_PRICING, KEY_LITELLM_META,
            KEY_MODELS_DEV_PRICING, KEY_MODELS_DEV_META,
            KEY_HELICONE_PRICING, KEY_HELICONE_PATTERNS,
            KEY_LLMPRICES_PRICING, KEY_AA_PRICING, KEY_AA_META,
            KEY_REQUESTY_PRICING, KEY_REQUESTY_META,
            KEY_LLMSTATS_PRICING, KEY_LLMSTATS_META
        )
        tierBlobs.forEach { key ->
            try { blobFile(context, key).delete() } catch (_: Exception) {}
        }
        // OpenRouter model-specs files. model_pricing.json is a legacy file
        // (no longer written; nothing ever read it) — deleted here to clean it
        // off older installs. model_supported_parameters.json is still live.
        try { java.io.File(context.filesDir, "model_pricing.json").delete() } catch (_: Exception) {}
        try { java.io.File(context.filesDir, "model_supported_parameters.json").delete() } catch (_: Exception) {}

        getPrefs(context).edit {
            tierBlobs.forEach { remove(it) }
            remove(KEY_OPENROUTER_TIMESTAMP)
            remove(KEY_LITELLM_TIMESTAMP)
            remove(KEY_MODELS_DEV_TIMESTAMP)
            remove(KEY_HELICONE_TIMESTAMP)
            remove(KEY_LLMPRICES_TIMESTAMP)
            remove(KEY_AA_TIMESTAMP)
            remove(KEY_REQUESTY_TIMESTAMP)
            remove(KEY_LLMSTATS_TIMESTAMP)
        }
        openRouterPricing = null; openRouterTimestamp = 0
        litellmPricing = null; litellmMeta = null; litellmTimestamp = 0
        modelsDevPricing = null; modelsDevMeta = null; modelsDevTimestamp = 0
        heliconePricing = null; heliconePatterns = null; heliconeTimestamp = 0
        llmPricesPricing = null; llmPricesTimestamp = 0
        aaPricing = null; aaMeta = null; aaTimestamp = 0
        requestyPricing = null; requestyMeta = null; requestyTimestamp = 0
        llmStatsPricing = null; llmStatsMeta = null; llmStatsTimestamp = 0
        litellmMetaLookupCache.clear()
        modelsDevMetaLookupCache.clear()
        litellmPricingLookupCache.clear()
        supportedParametersCache = null
        // preloadCompleted intentionally kept true — manual + together
        // tiers are still loaded; only the six Info-provider tiers were
        // dropped, and they'll lazily repopulate on the next refresh.
    }

    /** Wipe every cached pricing tier and manual override — used by
     *  the housekeeping "clear all runtime data" flow. Drops the
     *  pricing prefs file, the tier blobs under filesDir/pricing/,
     *  and every in-memory cache so the next [ensureLoaded] starts
     *  from a clean slate. */
    fun clearAll(context: Context) = synchronized(lock) {
        // Disk: remove every tier blob plus the supported-parameters
        // catalog file maintained alongside it.
        try {
            java.io.File(context.filesDir, "pricing").deleteRecursively()
        } catch (_: Exception) {}
        try {
            java.io.File(context.filesDir, "model_pricing.json").delete()
        } catch (_: Exception) {}
        try {
            java.io.File(context.filesDir, "model_supported_parameters.json").delete()
        } catch (_: Exception) {}
        // Prefs: wipe the whole pricing_cache file.
        getPrefs(context).edit { clear() }
        // In-memory: drop every loaded tier + lookup memo so the next
        // ensureLoaded call repopulates from the now-empty stores.
        manualPricing = null
        _manualPricingVersion.value = _manualPricingVersion.value + 1
        openRouterPricing = null
        togetherPricing = null
        togetherTimestamp = 0
        litellmPricing = null
        litellmMeta = null
        modelsDevPricing = null
        modelsDevMeta = null
        heliconePricing = null
        heliconePatterns = null
        llmPricesPricing = null
        aaPricing = null
        aaMeta = null
        requestyPricing = null
        requestyMeta = null
        llmStatsPricing = null
        llmStatsMeta = null
        openRouterTimestamp = 0
        litellmTimestamp = 0
        modelsDevTimestamp = 0
        heliconeTimestamp = 0
        llmPricesTimestamp = 0
        aaTimestamp = 0
        requestyTimestamp = 0
        llmStatsTimestamp = 0
        preloadCompleted = false
        litellmMetaLookupCache.clear()
        modelsDevMetaLookupCache.clear()
        litellmPricingLookupCache.clear()
        supportedParametersCache = null
    }
}
