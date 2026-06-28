package com.ai.data

/**
 * Canonical list of the external "info providers" the app reads model
 * pricing / capabilities / token-limits from. Lives in the data layer so
 * [PricingCache] can gate by [id]; the AI Setup → Info providers screen
 * iterates [entries] to render the enable/disable checkboxes.
 *
 * [id] is the stable string persisted in `Settings.disabledInfoProviders`
 * (NEVER rename — it would silently re-enable a user's disabled provider).
 * [helpTopic] points at the existing `info_provider_*` HelpContent entry.
 */
enum class InfoProvider(
    val id: String,
    val displayName: String,
    val helpTopic: String,
    val tagline: String
) {
    LITELLM("litellm", "LiteLLM", "info_provider_litellm", "BerriAI's model_prices JSON"),
    MODELS_DEV("modelsdev", "models.dev", "info_provider_models_dev", "Community catalog (LiteLLM fallback)"),
    LLM_PRICES("llmprices", "llm-prices.com", "info_provider_llm_prices", "Simon Willison's curated 10-vendor table"),
    ARTIFICIAL_ANALYSIS("aa", "Artificial Analysis", "info_provider_artificial_analysis", "Independent benchmarker (key required)"),
    LLM_STATS("llmstats", "llm-stats", "info_provider_llm_stats", "Pricing + benchmark scores (key required)"),
    OPENROUTER("openrouter", "OpenRouter", "info_provider_openrouter", "Aggregator catalog + per-model specs"),
    REQUESTY("requesty", "Requesty", "info_provider_requesty", "Cross-provider router catalog (keyless)"),
    GENAI_PRICES("genaiprices", "genai-prices", "info_provider_genai_prices", "Pydantic's curated price catalog (keyless)"),
    TRUEFOUNDRY("truefoundry", "TrueFoundry", "info_provider_truefoundry", "Community model registry (keyless)"),
    CLOUDPRICE("cloudprice", "CloudPrice", "info_provider_cloudprice", "Capabilities + context catalog (keyless)"),
    HELICONE("helicone", "Helicone", "info_provider_helicone", "Pricing-only side product"),
    HUGGINGFACE("huggingface", "HuggingFace", "info_provider_huggingface", "Model cards · context · license");

    companion object {
        /** All canonical ids — handy for "is this id still known" checks. */
        val ALL_IDS: Set<String> = entries.map { it.id }.toSet()
    }
}
