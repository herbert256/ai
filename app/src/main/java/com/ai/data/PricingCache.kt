package com.ai.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Cached model pricing data for cost calculations.
 * Uses a four-tier lookup: Manual overrides -> OpenRouter API (cached weekly) -> LiteLLM JSON -> Hardcoded fallback.
 */
object PricingCache {

    private const val PREFS_NAME = "pricing_cache"
    private const val KEY_OPENROUTER_PRICING = "openrouter_pricing"
    private const val KEY_OPENROUTER_TIMESTAMP = "openrouter_timestamp"
    private const val KEY_LITELLM_PRICING = "litellm_pricing"
    private const val KEY_LITELLM_TIMESTAMP = "litellm_timestamp"
    private const val KEY_MANUAL_PRICING = "manual_pricing"

    private const val CACHE_DURATION_MS = 7L * 24 * 60 * 60 * 1000  // 7 days in milliseconds

    private val gson = Gson()

    // In-memory cache for quick access
    private var manualPricing: MutableMap<String, ModelPricing>? = null
    private var openRouterPricing: Map<String, ModelPricing>? = null
    private var litellmPricing: Map<String, ModelPricing>? = null
    private var openRouterTimestamp: Long = 0
    private var litellmTimestamp: Long = 0

    /**
     * Model pricing information.
     */
    data class ModelPricing(
        val modelId: String,
        val promptPrice: Double,     // Price per token
        val completionPrice: Double, // Price per token
        val source: String = "unknown"
    )

    /**
     * Check if OpenRouter cache needs refresh (older than 7 days or empty).
     */
    fun needsOpenRouterRefresh(context: Context): Boolean {
        ensureLoaded(context)
        if (openRouterPricing.isNullOrEmpty()) return true
        return System.currentTimeMillis() - openRouterTimestamp > CACHE_DURATION_MS
    }

    /**
     * Get the age of the OpenRouter cache in human-readable format.
     */
    fun getOpenRouterCacheAge(context: Context): String {
        ensureLoaded(context)
        if (openRouterTimestamp == 0L) return "never fetched"

        val ageMs = System.currentTimeMillis() - openRouterTimestamp
        val days = ageMs / (24 * 60 * 60 * 1000)
        val hours = (ageMs % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000)

        return when {
            days > 0 -> "${days}d ${hours}h ago"
            hours > 0 -> "${hours}h ago"
            else -> "just now"
        }
    }

    /**
     * Save OpenRouter pricing data to cache.
     */
    fun saveOpenRouterPricing(context: Context, pricing: Map<String, ModelPricing>) {
        openRouterPricing = pricing
        openRouterTimestamp = System.currentTimeMillis()

        val prefs = getPrefs(context)
        prefs.edit()
            .putString(KEY_OPENROUTER_PRICING, gson.toJson(pricing))
            .putLong(KEY_OPENROUTER_TIMESTAMP, openRouterTimestamp)
            .apply()

        android.util.Log.d("PricingCache", "Saved ${pricing.size} OpenRouter prices")
    }

    // ============================================================================
    // Manual pricing overrides
    // ============================================================================

    /**
     * Set manual pricing override for a provider/model combination.
     * Prices are per token (not per million).
     */
    fun setManualPricing(context: Context, provider: AiService, model: String, promptPrice: Double, completionPrice: Double) {
        ensureLoaded(context)
        val key = "${provider.name}:$model"
        val pricing = ModelPricing(model, promptPrice, completionPrice, "manual")

        if (manualPricing == null) {
            manualPricing = mutableMapOf()
        }
        manualPricing!![key] = pricing
        saveManualPricing(context)
        android.util.Log.d("PricingCache", "Set manual pricing for $key")
    }

    /**
     * Remove manual pricing override for a provider/model combination.
     */
    fun removeManualPricing(context: Context, provider: AiService, model: String) {
        ensureLoaded(context)
        val key = "${provider.name}:$model"
        manualPricing?.remove(key)
        saveManualPricing(context)
        android.util.Log.d("PricingCache", "Removed manual pricing for $key")
    }

    /**
     * Get manual pricing for a provider/model combination.
     */
    fun getManualPricing(context: Context, provider: AiService, model: String): ModelPricing? {
        ensureLoaded(context)
        val key = "${provider.name}:$model"
        return manualPricing?.get(key)
    }

    /**
     * Get all manual pricing overrides.
     */
    fun getAllManualPricing(context: Context): Map<String, ModelPricing> {
        ensureLoaded(context)
        return manualPricing?.toMap() ?: emptyMap()
    }

    /**
     * Set all manual pricing overrides (for import).
     */
    fun setAllManualPricing(context: Context, pricing: Map<String, ModelPricing>) {
        manualPricing = pricing.toMutableMap()
        saveManualPricing(context)
        android.util.Log.d("PricingCache", "Imported ${pricing.size} manual prices")
    }

    private fun saveManualPricing(context: Context) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putString(KEY_MANUAL_PRICING, gson.toJson(manualPricing))
            .apply()
    }

    /**
     * Get pricing for a specific model, using four-tier lookup.
     * Returns null if no pricing found.
     */
    fun getPricing(context: Context, provider: AiService, model: String): ModelPricing? {
        ensureLoaded(context)

        // Build the key for manual pricing lookup
        val manualKey = "${provider.name}:$model"

        // 0. Try manual overrides first (highest priority)
        manualPricing?.get(manualKey)?.let { return it }

        // 1. Try OpenRouter (best source for most models)
        openRouterPricing?.let { pricing ->
            // Try exact match first
            pricing[model]?.let { return it }

            // Try with provider prefix (e.g., "anthropic/claude-3-opus")
            val providerPrefix = getOpenRouterPrefix(provider)
            if (providerPrefix != null) {
                pricing["$providerPrefix/$model"]?.let { return it }
            }

            // Try partial match
            pricing.entries.find { it.key.endsWith("/$model") || it.key == model }?.let { return it.value }
        }

        // 2. Try LiteLLM
        litellmPricing?.let { pricing ->
            pricing[model]?.let { return it }

            // Try with provider prefix
            val litellmPrefix = getLiteLLMPrefix(provider)
            if (litellmPrefix != null) {
                pricing["$litellmPrefix/$model"]?.let { return it }
            }
        }

        // 3. Try hardcoded fallback
        FALLBACK_PRICING[model]?.let { return it }

        return null
    }

    /**
     * Get all cached pricing (merged from all sources).
     * Manual takes priority, then OpenRouter, then LiteLLM, then fallback.
     */
    fun getAllPricing(context: Context): Map<String, ModelPricing> {
        ensureLoaded(context)

        val merged = mutableMapOf<String, ModelPricing>()

        // Add fallback first (lowest priority)
        merged.putAll(FALLBACK_PRICING)

        // Add LiteLLM (medium priority)
        litellmPricing?.let { merged.putAll(it) }

        // Add OpenRouter (high priority)
        openRouterPricing?.let { merged.putAll(it) }

        // Add manual last (highest priority)
        manualPricing?.let { merged.putAll(it) }

        return merged
    }

    /**
     * Get pricing source statistics.
     */
    fun getPricingStats(context: Context): String {
        ensureLoaded(context)
        val sources = mutableListOf<String>()
        manualPricing?.let { if (it.isNotEmpty()) sources.add("Manual (${it.size})") }
        openRouterPricing?.let { if (it.isNotEmpty()) sources.add("OpenRouter (${it.size})") }
        litellmPricing?.let { if (it.isNotEmpty()) sources.add("LiteLLM (${it.size})") }
        sources.add("Fallback (${FALLBACK_PRICING.size})")
        return sources.joinToString(" + ")
    }

    /**
     * Force refresh of LiteLLM pricing from assets.
     */
    fun refreshLiteLLMPricing(context: Context) {
        litellmPricing = parseLiteLLMPricing(context)
        litellmTimestamp = System.currentTimeMillis()

        val prefs = getPrefs(context)
        prefs.edit()
            .putString(KEY_LITELLM_PRICING, gson.toJson(litellmPricing))
            .putLong(KEY_LITELLM_TIMESTAMP, litellmTimestamp)
            .apply()

        android.util.Log.d("PricingCache", "Loaded ${litellmPricing?.size ?: 0} LiteLLM prices")
    }

    /**
     * Fetch OpenRouter pricing from API and save to cache.
     * Returns true if successful.
     */
    suspend fun fetchOpenRouterPricing(apiKey: String): Map<String, ModelPricing> {
        if (apiKey.isBlank()) return emptyMap()

        return try {
            val api = AiApiFactory.createOpenRouterModelsApi()
            val response = api.listModelsDetailed("Bearer $apiKey")
            if (response.isSuccessful) {
                val models = response.body()?.data ?: emptyList()
                models.mapNotNull { model ->
                    val promptPrice = model.pricing?.prompt?.toDoubleOrNull()
                    val completionPrice = model.pricing?.completion?.toDoubleOrNull()
                    if (promptPrice != null && completionPrice != null) {
                        model.id to ModelPricing(model.id, promptPrice, completionPrice, "openrouter")
                    } else null
                }.toMap()
            } else {
                android.util.Log.w("PricingCache", "OpenRouter API error: ${response.code()}")
                emptyMap()
            }
        } catch (e: Exception) {
            android.util.Log.w("PricingCache", "OpenRouter fetch error: ${e.message}")
            emptyMap()
        }
    }

    // ============================================================================
    // Private helpers
    // ============================================================================

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun ensureLoaded(context: Context) {
        if (manualPricing == null) {
            loadManualPricing(context)
        }
        if (openRouterPricing == null) {
            loadFromPrefs(context)
        }
        if (litellmPricing == null) {
            // First try loading from prefs cache
            val prefs = getPrefs(context)
            val litellmJson = prefs.getString(KEY_LITELLM_PRICING, null)
            if (litellmJson != null) {
                try {
                    val type = object : TypeToken<Map<String, ModelPricing>>() {}.type
                    litellmPricing = gson.fromJson(litellmJson, type)
                    litellmTimestamp = prefs.getLong(KEY_LITELLM_TIMESTAMP, 0)
                } catch (e: Exception) {
                    android.util.Log.w("PricingCache", "Failed to load LiteLLM cache: ${e.message}")
                }
            }
            // If still null, parse from assets
            if (litellmPricing == null) {
                refreshLiteLLMPricing(context)
            }
        }
    }

    private fun loadFromPrefs(context: Context) {
        val prefs = getPrefs(context)

        // Load OpenRouter cache
        val openRouterJson = prefs.getString(KEY_OPENROUTER_PRICING, null)
        openRouterTimestamp = prefs.getLong(KEY_OPENROUTER_TIMESTAMP, 0)

        if (openRouterJson != null) {
            try {
                val type = object : TypeToken<Map<String, ModelPricing>>() {}.type
                openRouterPricing = gson.fromJson(openRouterJson, type)
                android.util.Log.d("PricingCache", "Loaded ${openRouterPricing?.size ?: 0} cached OpenRouter prices")
            } catch (e: Exception) {
                android.util.Log.w("PricingCache", "Failed to load OpenRouter cache: ${e.message}")
                openRouterPricing = emptyMap()
            }
        } else {
            openRouterPricing = emptyMap()
        }
    }

    private fun loadManualPricing(context: Context) {
        val prefs = getPrefs(context)
        val manualJson = prefs.getString(KEY_MANUAL_PRICING, null)
        if (manualJson != null) {
            try {
                val type = object : TypeToken<MutableMap<String, ModelPricing>>() {}.type
                manualPricing = gson.fromJson(manualJson, type)
                android.util.Log.d("PricingCache", "Loaded ${manualPricing?.size ?: 0} manual prices")
            } catch (e: Exception) {
                android.util.Log.w("PricingCache", "Failed to load manual pricing: ${e.message}")
                manualPricing = mutableMapOf()
            }
        } else {
            manualPricing = mutableMapOf()
        }
    }

    private fun parseLiteLLMPricing(context: Context): Map<String, ModelPricing> {
        return try {
            val json = context.assets.open("model_prices_and_context_window.json").bufferedReader().use { it.readText() }
            val type = object : TypeToken<Map<String, Map<String, Any>>>() {}.type
            val data: Map<String, Map<String, Any>> = gson.fromJson(json, type)

            data.mapNotNull { (modelId, info) ->
                val inputCost = (info["input_cost_per_token"] as? Number)?.toDouble()
                val outputCost = (info["output_cost_per_token"] as? Number)?.toDouble()
                if (inputCost != null && outputCost != null) {
                    modelId to ModelPricing(modelId, inputCost, outputCost, "litellm")
                } else null
            }.toMap()
        } catch (e: Exception) {
            android.util.Log.w("PricingCache", "Failed to parse LiteLLM pricing: ${e.message}")
            emptyMap()
        }
    }

    private fun getOpenRouterPrefix(provider: AiService): String? {
        return when (provider) {
            AiService.OPENAI -> "openai"
            AiService.ANTHROPIC -> "anthropic"
            AiService.GOOGLE -> "google"
            AiService.XAI -> "x-ai"
            AiService.MISTRAL -> "mistralai"
            AiService.DEEPSEEK -> "deepseek"
            AiService.PERPLEXITY -> "perplexity"
            else -> null
        }
    }

    private fun getLiteLLMPrefix(provider: AiService): String? {
        return when (provider) {
            AiService.GOOGLE -> "gemini"
            AiService.XAI -> "xai"
            AiService.GROQ -> "groq"
            AiService.DEEPSEEK -> "deepseek"
            AiService.TOGETHER -> "together_ai"
            else -> null
        }
    }

    /**
     * Hardcoded fallback pricing for common models (per token, not per million).
     * Prices as of January 2025.
     */
    private val FALLBACK_PRICING = mapOf(
        // DeepSeek
        "deepseek-chat" to ModelPricing("deepseek-chat", 0.14e-6, 0.28e-6, "fallback"),
        "deepseek-coder" to ModelPricing("deepseek-coder", 0.14e-6, 0.28e-6, "fallback"),
        "deepseek-reasoner" to ModelPricing("deepseek-reasoner", 0.55e-6, 2.19e-6, "fallback"),
        // Groq
        "llama-3.3-70b-versatile" to ModelPricing("llama-3.3-70b-versatile", 0.59e-6, 0.79e-6, "fallback"),
        "llama-3.1-8b-instant" to ModelPricing("llama-3.1-8b-instant", 0.05e-6, 0.08e-6, "fallback"),
        "llama3-70b-8192" to ModelPricing("llama3-70b-8192", 0.59e-6, 0.79e-6, "fallback"),
        "llama3-8b-8192" to ModelPricing("llama3-8b-8192", 0.05e-6, 0.08e-6, "fallback"),
        "mixtral-8x7b-32768" to ModelPricing("mixtral-8x7b-32768", 0.24e-6, 0.24e-6, "fallback"),
        "gemma2-9b-it" to ModelPricing("gemma2-9b-it", 0.20e-6, 0.20e-6, "fallback"),
        // xAI Grok
        "grok-3" to ModelPricing("grok-3", 3.0e-6, 15.0e-6, "fallback"),
        "grok-3-mini" to ModelPricing("grok-3-mini", 0.30e-6, 0.50e-6, "fallback"),
        "grok-2" to ModelPricing("grok-2", 2.0e-6, 10.0e-6, "fallback"),
        "grok-beta" to ModelPricing("grok-beta", 5.0e-6, 15.0e-6, "fallback"),
        // Mistral
        "mistral-small-latest" to ModelPricing("mistral-small-latest", 0.1e-6, 0.3e-6, "fallback"),
        "mistral-medium-latest" to ModelPricing("mistral-medium-latest", 0.4e-6, 2.0e-6, "fallback"),
        "mistral-large-latest" to ModelPricing("mistral-large-latest", 2.0e-6, 6.0e-6, "fallback"),
        "open-mistral-7b" to ModelPricing("open-mistral-7b", 0.25e-6, 0.25e-6, "fallback"),
        "open-mixtral-8x7b" to ModelPricing("open-mixtral-8x7b", 0.7e-6, 0.7e-6, "fallback"),
        "open-mixtral-8x22b" to ModelPricing("open-mixtral-8x22b", 2.0e-6, 6.0e-6, "fallback"),
        "codestral-latest" to ModelPricing("codestral-latest", 0.3e-6, 0.9e-6, "fallback"),
        // Perplexity
        "sonar" to ModelPricing("sonar", 1.0e-6, 1.0e-6, "fallback"),
        "sonar-pro" to ModelPricing("sonar-pro", 3.0e-6, 15.0e-6, "fallback"),
        "sonar-reasoning" to ModelPricing("sonar-reasoning", 1.0e-6, 5.0e-6, "fallback"),
        // SiliconFlow
        "Qwen/Qwen2.5-7B-Instruct" to ModelPricing("Qwen/Qwen2.5-7B-Instruct", 0.35e-6, 0.35e-6, "fallback"),
        "Qwen/Qwen2.5-72B-Instruct" to ModelPricing("Qwen/Qwen2.5-72B-Instruct", 1.26e-6, 1.26e-6, "fallback"),
        "deepseek-ai/DeepSeek-V3" to ModelPricing("deepseek-ai/DeepSeek-V3", 0.5e-6, 2.0e-6, "fallback"),
        "deepseek-ai/DeepSeek-R1" to ModelPricing("deepseek-ai/DeepSeek-R1", 0.55e-6, 2.19e-6, "fallback"),
        // Z.AI / ZhipuAI GLM
        "glm-4" to ModelPricing("glm-4", 1.4e-6, 1.4e-6, "fallback"),
        "glm-4-plus" to ModelPricing("glm-4-plus", 0.7e-6, 0.7e-6, "fallback"),
        "glm-4-flash" to ModelPricing("glm-4-flash", 0.007e-6, 0.007e-6, "fallback"),
        "glm-4-long" to ModelPricing("glm-4-long", 0.14e-6, 0.14e-6, "fallback"),
        "glm-4.5-flash" to ModelPricing("glm-4.5-flash", 0.007e-6, 0.007e-6, "fallback"),
        "glm-4.7-flash" to ModelPricing("glm-4.7-flash", 0.007e-6, 0.007e-6, "fallback")
    )
}
