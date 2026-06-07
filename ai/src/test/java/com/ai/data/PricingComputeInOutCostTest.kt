package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [PricingCache.computeInOutCost] — the in/out cost split used by translation,
 *  meta and secondary persistence (cache tiers, >200k tier, apiCost pro-rata). */
class PricingComputeInOutCostTest {
    private fun pricing(
        prompt: Double, completion: Double,
        cachedRead: Double? = null, cacheWrite: Double? = null,
        promptAbove: Double? = null, completionAbove: Double? = null
    ) = PricingCache.ModelPricing(
        modelId = "m", promptPrice = prompt, completionPrice = completion,
        cachedReadPrice = cachedRead, cachedWritePrice = cacheWrite,
        promptPriceAbove200k = promptAbove, completionPriceAbove200k = completionAbove
    )

    @Test fun simple_input_output() {
        val (inc, out) = PricingCache.computeInOutCost(
            TokenUsage(inputTokens = 100, outputTokens = 50),
            pricing(prompt = 0.01, completion = 0.02)
        )
        assertThat(inc).isWithin(1e-9).of(1.0)
        assertThat(out).isWithin(1e-9).of(1.0)
    }

    @Test fun cached_read_uses_cached_rate() {
        val (inc, out) = PricingCache.computeInOutCost(
            TokenUsage(inputTokens = 0, outputTokens = 0, cachedInputTokens = 100),
            pricing(prompt = 0.01, completion = 0.02, cachedRead = 0.001)
        )
        assertThat(inc).isWithin(1e-9).of(0.1)
        assertThat(out).isWithin(1e-9).of(0.0)
    }

    @Test fun cached_read_falls_back_to_prompt_price_when_no_cache_rate() {
        val (inc, _) = PricingCache.computeInOutCost(
            TokenUsage(inputTokens = 0, outputTokens = 0, cachedInputTokens = 100),
            pricing(prompt = 0.01, completion = 0.02)   // no cachedReadPrice
        )
        assertThat(inc).isWithin(1e-9).of(1.0)          // 100 * 0.01
    }

    @Test fun above_200k_tier_uses_high_rate() {
        val (inc, out) = PricingCache.computeInOutCost(
            TokenUsage(inputTokens = 250_000, outputTokens = 0),
            pricing(prompt = 0.01, completion = 0.02, promptAbove = 0.02)
        )
        assertThat(inc).isWithin(1e-6).of(5_000.0)      // 250k * 0.02 high tier
        assertThat(out).isWithin(1e-9).of(0.0)
    }

    @Test fun under_200k_stays_on_base_rate() {
        val (inc, _) = PricingCache.computeInOutCost(
            TokenUsage(inputTokens = 100_000, outputTokens = 0),
            pricing(prompt = 0.01, completion = 0.02, promptAbove = 0.02)
        )
        assertThat(inc).isWithin(1e-6).of(1_000.0)      // base 0.01
    }

    @Test fun apiCost_is_split_pro_rata_by_baseline() {
        val (inc, out) = PricingCache.computeInOutCost(
            TokenUsage(inputTokens = 100, outputTokens = 100, apiCost = 3.0),
            pricing(prompt = 0.01, completion = 0.01)   // baseline 50/50
        )
        assertThat(inc).isWithin(1e-9).of(1.5)
        assertThat(out).isWithin(1e-9).of(1.5)
    }
}
