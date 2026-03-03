package com.ai.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

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
    @Volatile private var openRouterTimestamp: Long = 0
    @Volatile private var litellmTimestamp: Long = 0

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
     * Get pricing for a model using six-tier lookup.
     */
    fun getPricing(context: Context, provider: AppService, model: String): ModelPricing {
        ensureLoaded(context)
        val isOpenRouter = provider.id == "OPENROUTER"
        if (isOpenRouter) findOpenRouterPricing(provider, model)?.let { return it }
        manualPricing?.get("${provider.id}:$model")?.let { return it }
        if (!isOpenRouter) findOpenRouterPricing(provider, model)?.let { return it }
        litellmPricing?.let { p ->
            p[model]?.let { return it }
            provider.litellmPrefix?.let { prefix -> p["$prefix/$model"]?.let { return it } }
        }
        FALLBACK_PRICING[model]?.let { return it }
        return DEFAULT_PRICING
    }

    fun getPricingWithoutOverride(context: Context, provider: AppService, model: String): ModelPricing {
        ensureLoaded(context)
        findOpenRouterPricing(provider, model)?.let { return it }
        litellmPricing?.let { p ->
            p[model]?.let { return it }
            provider.litellmPrefix?.let { prefix -> p["$prefix/$model"]?.let { return it } }
        }
        FALLBACK_PRICING[model]?.let { return it }
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
        merged.putAll(FALLBACK_PRICING)
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
        sources.add("Fallback (${FALLBACK_PRICING.size})")
        return sources.joinToString(" + ")
    }

    fun getOpenRouterPricing(context: Context): Map<String, ModelPricing> { ensureLoaded(context); return openRouterPricing?.toMap() ?: emptyMap() }
    fun getLiteLLMPricing(context: Context): Map<String, ModelPricing> { ensureLoaded(context); return litellmPricing?.toMap() ?: emptyMap() }
    fun getFallbackPricing(): Map<String, ModelPricing> = FALLBACK_PRICING.toMap()

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

    // Hardcoded FALLBACK pricing for common models (per token)
    private val FALLBACK_PRICING = mapOf(
        "deepseek-chat" to ModelPricing("deepseek-chat", 0.14e-6, 0.28e-6, "FALLBACK"),
        "deepseek-coder" to ModelPricing("deepseek-coder", 0.14e-6, 0.28e-6, "FALLBACK"),
        "deepseek-reasoner" to ModelPricing("deepseek-reasoner", 0.55e-6, 2.19e-6, "FALLBACK"),
        "llama-3.3-70b-versatile" to ModelPricing("llama-3.3-70b-versatile", 0.59e-6, 0.79e-6, "FALLBACK"),
        "llama-3.1-8b-instant" to ModelPricing("llama-3.1-8b-instant", 0.05e-6, 0.08e-6, "FALLBACK"),
        "llama3-70b-8192" to ModelPricing("llama3-70b-8192", 0.59e-6, 0.79e-6, "FALLBACK"),
        "llama3-8b-8192" to ModelPricing("llama3-8b-8192", 0.05e-6, 0.08e-6, "FALLBACK"),
        "mixtral-8x7b-32768" to ModelPricing("mixtral-8x7b-32768", 0.24e-6, 0.24e-6, "FALLBACK"),
        "gemma2-9b-it" to ModelPricing("gemma2-9b-it", 0.20e-6, 0.20e-6, "FALLBACK"),
        "grok-3" to ModelPricing("grok-3", 3.0e-6, 15.0e-6, "FALLBACK"),
        "grok-3-mini" to ModelPricing("grok-3-mini", 0.30e-6, 0.50e-6, "FALLBACK"),
        "grok-2" to ModelPricing("grok-2", 2.0e-6, 10.0e-6, "FALLBACK"),
        "grok-beta" to ModelPricing("grok-beta", 5.0e-6, 15.0e-6, "FALLBACK"),
        "mistral-small-latest" to ModelPricing("mistral-small-latest", 0.1e-6, 0.3e-6, "FALLBACK"),
        "mistral-medium-latest" to ModelPricing("mistral-medium-latest", 0.4e-6, 2.0e-6, "FALLBACK"),
        "mistral-large-latest" to ModelPricing("mistral-large-latest", 2.0e-6, 6.0e-6, "FALLBACK"),
        "open-mistral-7b" to ModelPricing("open-mistral-7b", 0.25e-6, 0.25e-6, "FALLBACK"),
        "open-mixtral-8x7b" to ModelPricing("open-mixtral-8x7b", 0.7e-6, 0.7e-6, "FALLBACK"),
        "open-mixtral-8x22b" to ModelPricing("open-mixtral-8x22b", 2.0e-6, 6.0e-6, "FALLBACK"),
        "codestral-latest" to ModelPricing("codestral-latest", 0.3e-6, 0.9e-6, "FALLBACK"),
        "sonar" to ModelPricing("sonar", 1.0e-6, 1.0e-6, "FALLBACK"),
        "sonar-pro" to ModelPricing("sonar-pro", 3.0e-6, 15.0e-6, "FALLBACK"),
        "sonar-reasoning" to ModelPricing("sonar-reasoning", 1.0e-6, 5.0e-6, "FALLBACK"),
        "Qwen/Qwen2.5-7B-Instruct" to ModelPricing("Qwen/Qwen2.5-7B-Instruct", 0.35e-6, 0.35e-6, "FALLBACK"),
        "Qwen/Qwen2.5-72B-Instruct" to ModelPricing("Qwen/Qwen2.5-72B-Instruct", 1.26e-6, 1.26e-6, "FALLBACK"),
        "deepseek-ai/DeepSeek-V3" to ModelPricing("deepseek-ai/DeepSeek-V3", 0.5e-6, 2.0e-6, "FALLBACK"),
        "deepseek-ai/DeepSeek-R1" to ModelPricing("deepseek-ai/DeepSeek-R1", 0.55e-6, 2.19e-6, "FALLBACK"),
        "glm-4" to ModelPricing("glm-4", 1.4e-6, 1.4e-6, "FALLBACK"),
        "glm-4-plus" to ModelPricing("glm-4-plus", 0.7e-6, 0.7e-6, "FALLBACK"),
        "glm-4-flash" to ModelPricing("glm-4-flash", 0.007e-6, 0.007e-6, "FALLBACK"),
        "glm-4-long" to ModelPricing("glm-4-long", 0.14e-6, 0.14e-6, "FALLBACK"),
        "glm-4.5-flash" to ModelPricing("glm-4.5-flash", 0.007e-6, 0.007e-6, "FALLBACK"),
        "glm-4.7-flash" to ModelPricing("glm-4.7-flash", 0.007e-6, 0.007e-6, "FALLBACK"),
        "kimi-latest" to ModelPricing("kimi-latest", 0.55e-6, 2.19e-6, "FALLBACK"),
        "moonshot-v1-8k" to ModelPricing("moonshot-v1-8k", 0.55e-6, 2.19e-6, "FALLBACK"),
        "moonshot-v1-32k" to ModelPricing("moonshot-v1-32k", 1.1e-6, 4.38e-6, "FALLBACK"),
        "moonshot-v1-128k" to ModelPricing("moonshot-v1-128k", 4.38e-6, 8.76e-6, "FALLBACK"),
        "command-a-03-2025" to ModelPricing("command-a-03-2025", 2.5e-6, 10.0e-6, "FALLBACK"),
        "command-r-plus-08-2024" to ModelPricing("command-r-plus-08-2024", 2.5e-6, 10.0e-6, "FALLBACK"),
        "command-r-08-2024" to ModelPricing("command-r-08-2024", 0.15e-6, 0.6e-6, "FALLBACK"),
        "command-r7b-12-2024" to ModelPricing("command-r7b-12-2024", 0.0375e-6, 0.15e-6, "FALLBACK"),
        "jamba-mini" to ModelPricing("jamba-mini", 0.2e-6, 0.4e-6, "FALLBACK"),
        "jamba-large" to ModelPricing("jamba-large", 2.0e-6, 8.0e-6, "FALLBACK"),
        "qwen-plus" to ModelPricing("qwen-plus", 0.8e-6, 2.0e-6, "FALLBACK"),
        "qwen-max" to ModelPricing("qwen-max", 2.4e-6, 9.6e-6, "FALLBACK"),
        "qwen-turbo" to ModelPricing("qwen-turbo", 0.3e-6, 0.6e-6, "FALLBACK"),
        "accounts/fireworks/models/llama-v3p3-70b-instruct" to ModelPricing("accounts/fireworks/models/llama-v3p3-70b-instruct", 0.9e-6, 0.9e-6, "FALLBACK"),
        "llama-3.3-70b" to ModelPricing("llama-3.3-70b", 0.85e-6, 1.2e-6, "FALLBACK"),
        "Meta-Llama-3.3-70B-Instruct" to ModelPricing("Meta-Llama-3.3-70B-Instruct", 0.6e-6, 1.2e-6, "FALLBACK"),
        "Baichuan4-Turbo" to ModelPricing("Baichuan4-Turbo", 0.55e-6, 2.19e-6, "FALLBACK"),
        "step-2-16k" to ModelPricing("step-2-16k", 1.33e-6, 16.0e-6, "FALLBACK"),
        "step-1-8k" to ModelPricing("step-1-8k", 0.8e-6, 2.0e-6, "FALLBACK"),
        "MiniMax-M2.1" to ModelPricing("MiniMax-M2.1", 1.1e-6, 4.4e-6, "FALLBACK"),
        "MiniMax-M1" to ModelPricing("MiniMax-M1", 0.3e-6, 1.1e-6, "FALLBACK"),
        "nvidia/llama-3.1-nemotron-70b-instruct" to ModelPricing("nvidia/llama-3.1-nemotron-70b-instruct", 0.9e-6, 0.9e-6, "FALLBACK"),
        "meta/meta-llama-3-70b-instruct" to ModelPricing("meta/meta-llama-3-70b-instruct", 0.65e-6, 2.75e-6, "FALLBACK"),
        "meta-llama/Llama-3.1-70B-Instruct" to ModelPricing("meta-llama/Llama-3.1-70B-Instruct", 0.9e-6, 0.9e-6, "FALLBACK"),
        "hermes-3-llama-3.1-405b-fp8" to ModelPricing("hermes-3-llama-3.1-405b-fp8", 0.8e-6, 0.8e-6, "FALLBACK"),
        "llama3-1-70b" to ModelPricing("llama3-1-70b", 0.9e-6, 0.9e-6, "FALLBACK"),
        "yi-lightning" to ModelPricing("yi-lightning", 0.99e-6, 0.99e-6, "FALLBACK"),
        "doubao-pro-32k" to ModelPricing("doubao-pro-32k", 0.56e-6, 2.24e-6, "FALLBACK"),
        "reka-flash" to ModelPricing("reka-flash", 0.8e-6, 2.0e-6, "FALLBACK"),
        "palmyra-x-004" to ModelPricing("palmyra-x-004", 5.0e-6, 15.0e-6, "FALLBACK")
    )

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
