package com.ai.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Cached model pricing data for cost calculations.
 * Uses six-tier lookup: API > OVERRIDE > OPENROUTER > LITELLM > FALLBACK > DEFAULT.
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
        val pricing = ModelPricing(model, promptPrice, completionPrice, "OVERRIDE")

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

    // Default pricing: $2.50 per 1M input tokens, $5.00 per 1M output tokens
    private val DEFAULT_PRICING = ModelPricing("default", 2.50e-6, 5.00e-6, "DEFAULT")

    /**
     * Get pricing for a specific model, using six-tier lookup.
     * Priority: API (from response) > OVERRIDE > OPENROUTER > LITELLM > FALLBACK > DEFAULT
     * Note: API cost is checked at the call site before calling this function.
     * Always returns pricing (DEFAULT as last resort).
     */
    fun getPricing(context: Context, provider: AiService, model: String): ModelPricing {
        ensureLoaded(context)

        // Build the key for override pricing lookup
        val overrideKey = "${provider.name}:$model"

        // 1. Try user overrides first (highest priority after API cost)
        manualPricing?.get(overrideKey)?.let { return it }

        // 2. Try OpenRouter API (cached weekly, accurate pricing)
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

        // 3. Try LiteLLM (bundled pricing data)
        litellmPricing?.let { pricing ->
            pricing[model]?.let { return it }

            // Try with provider prefix
            val LITELLMPrefix = getLiteLLMPrefix(provider)
            if (LITELLMPrefix != null) {
                pricing["$LITELLMPrefix/$model"]?.let { return it }
            }
        }

        // 4. Try hardcoded FALLBACK
        FALLBACK_PRICING[model]?.let { return it }

        // 5. Return DEFAULT pricing as last resort
        return DEFAULT_PRICING
    }

    /**
     * Get all cached pricing (merged from all sources).
     * Manual takes priority, then OpenRouter, then LiteLLM, then FALLBACK.
     */
    fun getAllPricing(context: Context): Map<String, ModelPricing> {
        ensureLoaded(context)

        val merged = mutableMapOf<String, ModelPricing>()

        // Add FALLBACK first (lowest priority)
        merged.putAll(FALLBACK_PRICING)

        // Add LiteLLM (3rd priority)
        litellmPricing?.let { merged.putAll(it) }

        // Add OpenRouter (2nd priority, overwrites LiteLLM)
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
     * Get all OpenRouter pricing (for export).
     */
    fun getOpenRouterPricing(context: Context): Map<String, ModelPricing> {
        ensureLoaded(context)
        return openRouterPricing?.toMap() ?: emptyMap()
    }

    /**
     * Get all LiteLLM pricing (for export).
     */
    fun getLiteLLMPricing(context: Context): Map<String, ModelPricing> {
        ensureLoaded(context)
        return litellmPricing?.toMap() ?: emptyMap()
    }

    /**
     * Get all fallback pricing (for export).
     */
    fun getFallbackPricing(): Map<String, ModelPricing> {
        return FALLBACK_PRICING.toMap()
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
                        model.id to ModelPricing(model.id, promptPrice, completionPrice, "OPENROUTER")
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
            val LITELLMJson = prefs.getString(KEY_LITELLM_PRICING, null)
            if (LITELLMJson != null) {
                try {
                    val type = object : TypeToken<Map<String, ModelPricing>>() {}.type
                    litellmPricing = gson.fromJson(LITELLMJson, type)
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
                    modelId to ModelPricing(modelId, inputCost, outputCost, "LITELLM")
                } else null
            }.toMap()
        } catch (e: Exception) {
            android.util.Log.w("PricingCache", "Failed to parse LiteLLM pricing: ${e.message}")
            emptyMap()
        }
    }

    private fun getOpenRouterPrefix(provider: AiService): String? {
        return provider.openRouterName
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
     * Hardcoded FALLBACK pricing for common models (per token, not per million).
     * Prices as of January 2025.
     */
    private val FALLBACK_PRICING = mapOf(
        // DeepSeek
        "deepseek-chat" to ModelPricing("deepseek-chat", 0.14e-6, 0.28e-6, "FALLBACK"),
        "deepseek-coder" to ModelPricing("deepseek-coder", 0.14e-6, 0.28e-6, "FALLBACK"),
        "deepseek-reasoner" to ModelPricing("deepseek-reasoner", 0.55e-6, 2.19e-6, "FALLBACK"),
        // Groq
        "llama-3.3-70b-versatile" to ModelPricing("llama-3.3-70b-versatile", 0.59e-6, 0.79e-6, "FALLBACK"),
        "llama-3.1-8b-instant" to ModelPricing("llama-3.1-8b-instant", 0.05e-6, 0.08e-6, "FALLBACK"),
        "llama3-70b-8192" to ModelPricing("llama3-70b-8192", 0.59e-6, 0.79e-6, "FALLBACK"),
        "llama3-8b-8192" to ModelPricing("llama3-8b-8192", 0.05e-6, 0.08e-6, "FALLBACK"),
        "mixtral-8x7b-32768" to ModelPricing("mixtral-8x7b-32768", 0.24e-6, 0.24e-6, "FALLBACK"),
        "gemma2-9b-it" to ModelPricing("gemma2-9b-it", 0.20e-6, 0.20e-6, "FALLBACK"),
        // xAI Grok
        "grok-3" to ModelPricing("grok-3", 3.0e-6, 15.0e-6, "FALLBACK"),
        "grok-3-mini" to ModelPricing("grok-3-mini", 0.30e-6, 0.50e-6, "FALLBACK"),
        "grok-2" to ModelPricing("grok-2", 2.0e-6, 10.0e-6, "FALLBACK"),
        "grok-beta" to ModelPricing("grok-beta", 5.0e-6, 15.0e-6, "FALLBACK"),
        // Mistral
        "mistral-small-latest" to ModelPricing("mistral-small-latest", 0.1e-6, 0.3e-6, "FALLBACK"),
        "mistral-medium-latest" to ModelPricing("mistral-medium-latest", 0.4e-6, 2.0e-6, "FALLBACK"),
        "mistral-large-latest" to ModelPricing("mistral-large-latest", 2.0e-6, 6.0e-6, "FALLBACK"),
        "open-mistral-7b" to ModelPricing("open-mistral-7b", 0.25e-6, 0.25e-6, "FALLBACK"),
        "open-mixtral-8x7b" to ModelPricing("open-mixtral-8x7b", 0.7e-6, 0.7e-6, "FALLBACK"),
        "open-mixtral-8x22b" to ModelPricing("open-mixtral-8x22b", 2.0e-6, 6.0e-6, "FALLBACK"),
        "codestral-latest" to ModelPricing("codestral-latest", 0.3e-6, 0.9e-6, "FALLBACK"),
        // Perplexity
        "sonar" to ModelPricing("sonar", 1.0e-6, 1.0e-6, "FALLBACK"),
        "sonar-pro" to ModelPricing("sonar-pro", 3.0e-6, 15.0e-6, "FALLBACK"),
        "sonar-reasoning" to ModelPricing("sonar-reasoning", 1.0e-6, 5.0e-6, "FALLBACK"),
        // SiliconFlow
        "Qwen/Qwen2.5-7B-Instruct" to ModelPricing("Qwen/Qwen2.5-7B-Instruct", 0.35e-6, 0.35e-6, "FALLBACK"),
        "Qwen/Qwen2.5-72B-Instruct" to ModelPricing("Qwen/Qwen2.5-72B-Instruct", 1.26e-6, 1.26e-6, "FALLBACK"),
        "deepseek-ai/DeepSeek-V3" to ModelPricing("deepseek-ai/DeepSeek-V3", 0.5e-6, 2.0e-6, "FALLBACK"),
        "deepseek-ai/DeepSeek-R1" to ModelPricing("deepseek-ai/DeepSeek-R1", 0.55e-6, 2.19e-6, "FALLBACK"),
        // Z.AI / ZhipuAI GLM
        "glm-4" to ModelPricing("glm-4", 1.4e-6, 1.4e-6, "FALLBACK"),
        "glm-4-plus" to ModelPricing("glm-4-plus", 0.7e-6, 0.7e-6, "FALLBACK"),
        "glm-4-flash" to ModelPricing("glm-4-flash", 0.007e-6, 0.007e-6, "FALLBACK"),
        "glm-4-long" to ModelPricing("glm-4-long", 0.14e-6, 0.14e-6, "FALLBACK"),
        "glm-4.5-flash" to ModelPricing("glm-4.5-flash", 0.007e-6, 0.007e-6, "FALLBACK"),
        "glm-4.7-flash" to ModelPricing("glm-4.7-flash", 0.007e-6, 0.007e-6, "FALLBACK"),
        // Moonshot / Kimi
        "kimi-latest" to ModelPricing("kimi-latest", 0.55e-6, 2.19e-6, "FALLBACK"),
        "moonshot-v1-8k" to ModelPricing("moonshot-v1-8k", 0.55e-6, 2.19e-6, "FALLBACK"),
        "moonshot-v1-32k" to ModelPricing("moonshot-v1-32k", 1.1e-6, 4.38e-6, "FALLBACK"),
        "moonshot-v1-128k" to ModelPricing("moonshot-v1-128k", 4.38e-6, 8.76e-6, "FALLBACK"),
        // Cohere
        "command-a-03-2025" to ModelPricing("command-a-03-2025", 2.5e-6, 10.0e-6, "FALLBACK"),
        "command-r-plus-08-2024" to ModelPricing("command-r-plus-08-2024", 2.5e-6, 10.0e-6, "FALLBACK"),
        "command-r-08-2024" to ModelPricing("command-r-08-2024", 0.15e-6, 0.6e-6, "FALLBACK"),
        "command-r7b-12-2024" to ModelPricing("command-r7b-12-2024", 0.0375e-6, 0.15e-6, "FALLBACK"),
        // AI21
        "jamba-mini" to ModelPricing("jamba-mini", 0.2e-6, 0.4e-6, "FALLBACK"),
        "jamba-large" to ModelPricing("jamba-large", 2.0e-6, 8.0e-6, "FALLBACK"),
        // DashScope (Qwen)
        "qwen-plus" to ModelPricing("qwen-plus", 0.8e-6, 2.0e-6, "FALLBACK"),
        "qwen-max" to ModelPricing("qwen-max", 2.4e-6, 9.6e-6, "FALLBACK"),
        "qwen-turbo" to ModelPricing("qwen-turbo", 0.3e-6, 0.6e-6, "FALLBACK"),
        // Fireworks
        "accounts/fireworks/models/llama-v3p3-70b-instruct" to ModelPricing("accounts/fireworks/models/llama-v3p3-70b-instruct", 0.9e-6, 0.9e-6, "FALLBACK"),
        // Cerebras
        "llama-3.3-70b" to ModelPricing("llama-3.3-70b", 0.85e-6, 1.2e-6, "FALLBACK"),
        // SambaNova
        "Meta-Llama-3.3-70B-Instruct" to ModelPricing("Meta-Llama-3.3-70B-Instruct", 0.6e-6, 1.2e-6, "FALLBACK"),
        // Baichuan
        "Baichuan4-Turbo" to ModelPricing("Baichuan4-Turbo", 0.55e-6, 2.19e-6, "FALLBACK"),
        // StepFun
        "step-2-16k" to ModelPricing("step-2-16k", 1.33e-6, 16.0e-6, "FALLBACK"),
        "step-1-8k" to ModelPricing("step-1-8k", 0.8e-6, 2.0e-6, "FALLBACK"),
        // MiniMax
        "MiniMax-M2.1" to ModelPricing("MiniMax-M2.1", 1.1e-6, 4.4e-6, "FALLBACK"),
        "MiniMax-M1" to ModelPricing("MiniMax-M1", 0.3e-6, 1.1e-6, "FALLBACK")
    )

    // ============================================================================
    // Model Specifications from OpenRouter
    // ============================================================================

    /**
     * Model pricing entry for JSON export.
     */
    data class ModelPricingEntry(
        val provider: String,  // Our provider name (e.g., "OPENAI", "ANTHROPIC")
        val model: String,     // Model name without provider prefix
        val pricing: OpenRouterPricing?
    )

    /**
     * Model supported parameters entry for JSON export.
     */
    data class ModelSupportedParametersEntry(
        val provider: String,  // Our provider name (e.g., "OPENAI", "ANTHROPIC")
        val model: String,     // Model name without provider prefix
        val supported_parameters: List<String>?
    )

    /**
     * Map OpenRouter provider prefix to our AiService.
     * Reverse lookup of AiService.openRouterName.
     */
    private fun getAiServiceFromOpenRouterPrefix(prefix: String): AiService? {
        return AiService.entries.find { it.openRouterName == prefix }
    }

    /**
     * Fetch model specifications from OpenRouter and save to JSON files.
     * Creates two files:
     * - model_pricing.json: Array of {provider, model, pricing}
     * - model_supported_parameters.json: Array of {provider, model, supported_parameters}
     *
     * @return Pair of (pricing count, parameters count) or null on error
     */
    suspend fun fetchAndSaveModelSpecifications(context: Context, apiKey: String): Pair<Int, Int>? {
        if (apiKey.isBlank()) return null

        return try {
            val api = AiApiFactory.createOpenRouterModelsApi()
            val response = api.listModelsDetailed("Bearer $apiKey")

            if (!response.isSuccessful) {
                android.util.Log.w("PricingCache", "OpenRouter API error: ${response.code()}")
                return null
            }

            val models = response.body()?.data ?: emptyList()

            val pricingEntries = mutableListOf<ModelPricingEntry>()
            val parametersEntries = mutableListOf<ModelSupportedParametersEntry>()

            for (model in models) {
                // Parse model ID: "provider/model-name" -> provider prefix and model name
                val parts = model.id.split("/", limit = 2)
                if (parts.size != 2) continue

                val openRouterPrefix = parts[0]
                val modelName = parts[1]

                // Map OpenRouter prefix to our AiService
                val aiService = getAiServiceFromOpenRouterPrefix(openRouterPrefix)
                if (aiService == null) continue  // Skip models from providers we don't support

                val providerName = aiService.name  // Our enum name (e.g., "OPENAI", "ANTHROPIC")

                // Add pricing entry
                if (model.pricing != null) {
                    pricingEntries.add(ModelPricingEntry(
                        provider = providerName,
                        model = modelName,
                        pricing = model.pricing
                    ))
                }

                // Add supported parameters entry
                if (model.supported_parameters != null) {
                    parametersEntries.add(ModelSupportedParametersEntry(
                        provider = providerName,
                        model = modelName,
                        supported_parameters = model.supported_parameters
                    ))
                }
            }

            // Save to files
            val filesDir = context.filesDir

            // Save model_pricing.json
            val pricingFile = java.io.File(filesDir, "model_pricing.json")
            pricingFile.writeText(gson.toJson(pricingEntries))
            android.util.Log.d("PricingCache", "Saved ${pricingEntries.size} pricing entries to ${pricingFile.absolutePath}")

            // Save model_supported_parameters.json
            val parametersFile = java.io.File(filesDir, "model_supported_parameters.json")
            parametersFile.writeText(gson.toJson(parametersEntries))
            android.util.Log.d("PricingCache", "Saved ${parametersEntries.size} parameter entries to ${parametersFile.absolutePath}")

            // Clear cache so it reloads on next lookup
            clearSupportedParametersCache()

            Pair(pricingEntries.size, parametersEntries.size)
        } catch (e: Exception) {
            android.util.Log.e("PricingCache", "Failed to fetch model specifications: ${e.message}")
            null
        }
    }

    // In-memory cache for supported parameters
    private var supportedParametersCache: Map<String, List<String>>? = null

    /**
     * Get supported parameters for a specific provider/model combination.
     * Returns null if no entry exists (meaning all parameters should be used).
     *
     * @param provider Our AiService enum
     * @param model The model name (without provider prefix)
     * @return List of supported parameter names, or null if not found
     */
    fun getSupportedParameters(context: Context, provider: AiService, model: String): List<String>? {
        // Load cache if not loaded
        if (supportedParametersCache == null) {
            loadSupportedParametersCache(context)
        }

        // Lookup by "PROVIDER:model" key
        val key = "${provider.name}:$model"
        return supportedParametersCache?.get(key)
    }

    /**
     * Load the supported parameters cache from JSON file.
     */
    private fun loadSupportedParametersCache(context: Context) {
        val file = java.io.File(context.filesDir, "model_supported_parameters.json")
        if (!file.exists()) {
            android.util.Log.d("PricingCache", "model_supported_parameters.json not found")
            supportedParametersCache = emptyMap()
            return
        }

        try {
            val json = file.readText()
            val type = object : TypeToken<List<ModelSupportedParametersEntry>>() {}.type
            val entries: List<ModelSupportedParametersEntry> = gson.fromJson(json, type)

            // Build lookup map: "PROVIDER:model" -> List<String>
            supportedParametersCache = entries
                .filter { it.supported_parameters != null }
                .associate { "${it.provider}:${it.model}" to it.supported_parameters!! }

            android.util.Log.d("PricingCache", "Loaded ${supportedParametersCache?.size ?: 0} supported parameter entries")
        } catch (e: Exception) {
            android.util.Log.e("PricingCache", "Failed to load supported parameters: ${e.message}")
            supportedParametersCache = emptyMap()
        }
    }

    /**
     * Clear the supported parameters cache (e.g., after fetching new data).
     */
    fun clearSupportedParametersCache() {
        supportedParametersCache = null
    }
}
