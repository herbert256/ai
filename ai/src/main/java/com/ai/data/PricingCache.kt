package com.ai.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import kotlinx.coroutines.launch

/**
 * Cached model pricing with six-tier lookup: API > OVERRIDE > OPENROUTER > LITELLM > FALLBACK > DEFAULT.
 * Exception: For OpenRouter provider, OPENROUTER source is checked before OVERRIDE.
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
    @Volatile private var fallbackPricing: Map<String, ModelPricing>? = null
    @Volatile private var openRouterTimestamp: Long = 0
    @Volatile private var litellmTimestamp: Long = 0
    @Volatile private var preloadCompleted = false

    data class ModelPricing(val modelId: String, val promptPrice: Double, val completionPrice: Double, val source: String = "unknown")

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
     * Get pricing for a model using six-tier lookup.
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
        manualPricing?.get("${provider.id}:$model")?.let { return it }
        if (!isOpenRouter) findOpenRouterPricing(provider, model)?.let { return it }
        litellmPricing?.let { p ->
            p[model]?.let { return it }
            provider.litellmPrefix?.let { prefix -> p["$prefix/$model"]?.let { return it } }
        }
        fallbackPricing?.get(model)?.let { return it }
        return DEFAULT_PRICING
    }

    private fun isMainThread(): Boolean =
        android.os.Looper.myLooper() == android.os.Looper.getMainLooper()

    fun getPricingWithoutOverride(context: Context, provider: AppService, model: String): ModelPricing {
        ensureLoaded(context)
        findOpenRouterPricing(provider, model)?.let { return it }
        litellmPricing?.let { p ->
            p[model]?.let { return it }
            provider.litellmPrefix?.let { prefix -> p["$prefix/$model"]?.let { return it } }
        }
        fallbackPricing?.get(model)?.let { return it }
        return DEFAULT_PRICING
    }

    private fun findOpenRouterPricing(provider: AppService, model: String): ModelPricing? {
        openRouterPricing?.let { pricing ->
            pricing[model]?.let { return it }
            provider.openRouterName?.let { prefix -> pricing["$prefix/$model"]?.let { return it } }
            pricing.entries.find { it.key.endsWith("/$model") || it.key == model }?.let { return it.value }
        }
        return null
    }

    fun getAllPricing(context: Context): Map<String, ModelPricing> {
        ensureLoaded(context)
        val merged = mutableMapOf<String, ModelPricing>()
        fallbackPricing?.let { merged.putAll(it) }
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
        fallbackPricing?.let { sources.add("Fallback (${it.size})") }
        return sources.joinToString(" + ")
    }

    fun getOpenRouterPricing(context: Context): Map<String, ModelPricing> { ensureLoaded(context); return openRouterPricing?.toMap() ?: emptyMap() }
    fun getLiteLLMPricing(context: Context): Map<String, ModelPricing> { ensureLoaded(context); return litellmPricing?.toMap() ?: emptyMap() }
    fun getFallbackPricing(context: Context): Map<String, ModelPricing> { ensureLoaded(context); return fallbackPricing?.toMap() ?: emptyMap() }

    fun refreshLiteLLMPricing(context: Context) {
        litellmPricing = parseLiteLLMPricing(context)
        litellmTimestamp = System.currentTimeMillis()
        getPrefs(context).edit {
            putString(KEY_LITELLM_PRICING, gson.toJson(litellmPricing))
            putLong(KEY_LITELLM_TIMESTAMP, litellmTimestamp)
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
        if (fallbackPricing == null) fallbackPricing = loadFallbackPricing(context)
        if (litellmPricing == null) {
            val prefs = getPrefs(context)
            val json = prefs.getString(KEY_LITELLM_PRICING, null)
            if (json != null) {
                try { litellmPricing = gson.fromJson(json, mapModelPricingType); litellmTimestamp = prefs.getLong(KEY_LITELLM_TIMESTAMP, 0) }
                catch (_: Exception) {}
            }
            if (litellmPricing == null) refreshLiteLLMPricing(context)
        }
    }

    private data class FallbackPricingEntry(val input: Double, val output: Double)
    private val mapFallbackEntryType: Type = object : TypeToken<Map<String, FallbackPricingEntry>>() {}.type

    private fun loadFallbackPricing(context: Context): Map<String, ModelPricing> {
        return try {
            val json = context.assets.open("fallback_pricing.json").bufferedReader().use { it.readText() }
            val raw: Map<String, FallbackPricingEntry> = gson.fromJson(json, mapFallbackEntryType)
            raw.mapValues { (id, e) -> ModelPricing(id, e.input, e.output, "FALLBACK") }
        } catch (e: Exception) {
            android.util.Log.e("PricingCache", "Failed to load fallback_pricing.json: ${e.message}")
            emptyMap()
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

    private fun parseLiteLLMPricing(context: Context): Map<String, ModelPricing> {
        return try {
            val json = context.assets.open("model_prices_and_context_window.json").bufferedReader().use { it.readText() }
            val data: Map<String, Map<String, Any>> = gson.fromJson(json, mapStringMapType)
            data.mapNotNull { (modelId, info) ->
                val ic = (info["input_cost_per_token"] as? Number)?.toDouble()
                val oc = (info["output_cost_per_token"] as? Number)?.toDouble()
                if (ic != null && oc != null) modelId to ModelPricing(modelId, ic, oc, "LITELLM") else null
            }.toMap()
        } catch (_: Exception) { emptyMap() }
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
