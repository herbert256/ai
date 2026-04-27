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
    private const val KEY_LITELLM_TIMESTAMP = "litellm_timestamp"
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
    @Volatile private var openRouterTimestamp: Long = 0
    @Volatile private var litellmTimestamp: Long = 0
    @Volatile private var preloadCompleted = false

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
        val cachedWritePriceAbove200k: Double? = null
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
        // LITELLM ahead of OVERRIDE so refreshed bulk pricing wins over stale
        // manual entries; users only need overrides for models LiteLLM lacks.
        findLiteLLMPricing(provider, model)?.let { return it }
        manualPricing?.get("${provider.id}:$model")?.let { return it }
        if (!isOpenRouter) findOpenRouterPricing(provider, model)?.let { return it }
        return DEFAULT_PRICING
    }

    private fun isMainThread(): Boolean =
        android.os.Looper.myLooper() == android.os.Looper.getMainLooper()

    fun getPricingWithoutOverride(context: Context, provider: AppService, model: String): ModelPricing {
        ensureLoaded(context)
        findOpenRouterPricing(provider, model)?.let { return it }
        findLiteLLMPricing(provider, model)?.let { return it }
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
     *  null when LiteLLM hasn't loaded yet OR the model isn't cataloged. */
    private fun findLiteLLMMeta(provider: AppService, model: String): LiteLLMMeta? {
        val meta = litellmMeta ?: return null
        meta[model]?.let { return it }
        provider.litellmPrefix?.let { prefix -> meta["$prefix/$model"]?.let { return it } }
        return findBestPrefixedMatch(meta, provider, model, useLitellmPrefix = true)
            ?: findLatestAliasKey(meta.keys, model, provider.litellmPrefix, provider.id.lowercase())?.let { meta[it] }
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
        // Exact-key fast path.
        pricing[model]?.let { return it }
        provider.litellmPrefix?.let { prefix -> pricing["$prefix/$model"]?.let { return it } }
        return findBestPrefixedMatch(pricing, provider, model, useLitellmPrefix = true)
            ?: findLatestAliasKey(pricing.keys, model, provider.litellmPrefix, provider.id.lowercase())?.let { pricing[it] }
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
        val override: ModelPricing?,
        val openrouter: ModelPricing?,
        val default: ModelPricing
    )

    fun getTierBreakdown(context: Context, provider: AppService, model: String): TierBreakdown {
        ensureLoaded(context)
        val litellm = findLiteLLMPricing(provider, model)
        val override = manualPricing?.get("${provider.id}:$model")
        val openrouter = findOpenRouterPricing(provider, model)
        return TierBreakdown(litellm, override, openrouter, DEFAULT_PRICING)
    }

    fun getOpenRouterPricing(context: Context): Map<String, ModelPricing> { ensureLoaded(context); return openRouterPricing?.toMap() ?: emptyMap() }
    fun getLiteLLMPricing(context: Context): Map<String, ModelPricing> { ensureLoaded(context); return litellmPricing?.toMap() ?: emptyMap() }

    /** Pretty-printed LiteLLM JSON entry for (provider, model), or null
     *  when the model isn't cataloged. Loads the bundled asset on demand
     *  so the 1.2MB blob doesn't sit in memory just for the rare raw-view
     *  case. Same dash/dot normalization as findLiteLLMPricing. */
    fun getLiteLLMRawEntry(context: Context, provider: AppService, model: String): String? {
        val pretty = createAppGson(prettyPrint = true)
        val json = try {
            context.assets.open("model_prices_and_context_window.json").bufferedReader().use { it.readText() }
        } catch (_: Exception) { return null }
        val root = try {
            @Suppress("DEPRECATION")
            JsonParser().parse(json).asJsonObject
        } catch (_: Exception) { return null }
        val target = normalizeModelId(model)
        val targetDeclared = provider.litellmPrefix?.let { "${normalizeModelId(it)}/$target" }
        val targetId = "${provider.id.lowercase()}/$target"
        val keysList = root.keySet()
        // Same prioritization the lookup uses: bare → declared prefix →
        // id-lowercase prefix → any other prefix. Picks the provider's own
        // catalog row over azure/bedrock/vertex variants.
        val buckets = arrayOfNulls<String>(4)
        var bareMatch: String? = null
        for (key in keysList) {
            val k = normalizeModelId(key)
            when {
                k == target -> { bareMatch = key; break }
                targetDeclared != null && k == targetDeclared -> if (buckets[1] == null) buckets[1] = key
                k == targetId -> if (buckets[2] == null) buckets[2] = key
                k.endsWith("/$target") -> if (buckets[3] == null) buckets[3] = key
            }
        }
        val match = bareMatch
            ?: buckets[1] ?: buckets[2] ?: buckets[3]
            ?: findLatestAliasKey(keysList, model, provider.litellmPrefix, provider.id.lowercase())
            ?: return null
        return pretty.toJson(root.get(match))
    }

    fun refreshLiteLLMPricing(context: Context) {
        val (pricing, meta) = parseLiteLLMPricing(context)
        litellmPricing = pricing
        litellmMeta = meta
        litellmTimestamp = System.currentTimeMillis()
        getPrefs(context).edit {
            putString(KEY_LITELLM_PRICING, gson.toJson(pricing))
            putLong(KEY_LITELLM_TIMESTAMP, litellmTimestamp)
        }
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
                litellmTimestamp = System.currentTimeMillis()
                getPrefs(context).edit {
                    putString(KEY_LITELLM_PRICING, gson.toJson(pricing))
                    putLong(KEY_LITELLM_TIMESTAMP, litellmTimestamp)
                }
            }
            pricing.size
        } catch (e: Exception) {
            android.util.Log.e("PricingCache", "Online LITELLM refresh failed: ${e.message}", e)
            null
        }
    }

    suspend fun fetchOpenRouterPricing(apiKey: String): Map<String, ModelPricing> {
        if (apiKey.isBlank()) return emptyMap()
        return try {
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

    // Private helpers
    private fun getPrefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun ensureLoaded(context: Context) = synchronized(lock) {
        if (manualPricing == null) loadManualPricing(context)
        if (openRouterPricing == null) loadFromPrefs(context)
        if (litellmPricing == null) {
            val prefs = getPrefs(context)
            val json = prefs.getString(KEY_LITELLM_PRICING, null)
            if (json != null) {
                try { litellmPricing = gson.fromJson(json, mapModelPricingType); litellmTimestamp = prefs.getLong(KEY_LITELLM_TIMESTAMP, 0) }
                catch (_: Exception) {}
            }
            if (litellmPricing == null) refreshLiteLLMPricing(context)
        }
        // Pricing comes from prefs (parsed ModelPricing) but the capability
        // sidecar isn't persisted — repopulate it from the bundled asset on
        // cold start so vision/web-search/mode lookups have data even when
        // pricing is loaded from prefs.
        if (litellmMeta == null) {
            litellmMeta = parseLiteLLMPricing(context).second
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

    private fun parseLiteLLMPricing(context: Context): Pair<Map<String, ModelPricing>, Map<String, LiteLLMMeta>> {
        return try {
            val json = context.assets.open("model_prices_and_context_window.json").bufferedReader().use { it.readText() }
            parseLiteLLMJson(json)
        } catch (_: Exception) { emptyMap<String, ModelPricing>() to emptyMap() }
    }

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

    /** Map a single litellm JSON entry to a ModelPricing, or null if it lacks
     *  the base input/output token costs. Reads the cache and 200k-tier keys
     *  alongside; missing keys leave nulls (call site falls back to plain
     *  prompt/completion rates). */
    private fun liteLLMEntry(modelId: String, info: Map<String, Any>): Pair<String, ModelPricing>? {
        val ic = (info["input_cost_per_token"] as? Number)?.toDouble() ?: return null
        val oc = (info["output_cost_per_token"] as? Number)?.toDouble() ?: return null
        fun n(key: String) = (info[key] as? Number)?.toDouble()
        return modelId to ModelPricing(
            modelId = modelId,
            promptPrice = ic,
            completionPrice = oc,
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
                ?: n("cache_creation_input_token_cost_above_128k_tokens")
        )
    }

    // Model specifications from OpenRouter
    data class ModelPricingEntry(val provider: String, val model: String, val pricing: OpenRouterPricing?)
    data class ModelSupportedParametersEntry(val provider: String, val model: String, val supported_parameters: List<String>?)

    suspend fun fetchAndSaveModelSpecifications(context: Context, apiKey: String): Pair<Int, Int>? {
        if (apiKey.isBlank()) return null
        return try {
            val api = ApiFactory.createOpenRouterModelsApi("https://openrouter.ai/api/")
            val response = api.listModelsDetailed("Bearer $apiKey")
            if (!response.isSuccessful) return null
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
