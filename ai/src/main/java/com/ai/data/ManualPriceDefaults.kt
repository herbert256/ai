package com.ai.data

/** User-requested manual prices, in USD per million tokens. Seed only an
 * absent store: existing edits, deletions and deliberately empty maps win.
 * Rates checked 2026-09-13 against:
 * https://console.groq.com/docs/model/openai/gpt-oss-20b
 * https://console.groq.com/docs/model/openai/gpt-oss-120b
 * https://openai.com/index/introducing-gpt-5-4-mini-and-nano/
 */
internal object ManualPriceDefaults {
    fun create(): MutableMap<String, PricingCache.ModelPricing> = linkedMapOf(
        entry("Groq", "openai/gpt-oss-20b", 0.075, 0.30),
        entry("Groq", "openai/gpt-oss-120b", 0.15, 0.60),
        entry("OpenAI", "gpt-5.4-mini", 0.75, 4.50)
    )

    private fun entry(provider: String, model: String, input: Double, output: Double) =
        "$provider:$model" to PricingCache.ModelPricing(
            model, input / 1_000_000, output / 1_000_000, "OVERRIDE"
        )
}

/** Shared by the form and both CSV import paths. Zero is a valid free rate. */
internal fun parseManualPricePerMillion(raw: String): Double? =
    raw.trim().replace(',', '.').toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?.div(1_000_000)
