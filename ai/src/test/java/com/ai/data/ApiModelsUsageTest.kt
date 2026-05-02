package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ApiModelsUsageTest {
    @Test fun openAiUsage_subtracts_cached_prompt_tokens_and_extracts_cost() {
        val usage = OpenAiUsage(
            prompt_tokens = 100,
            completion_tokens = 25,
            total_tokens = 125,
            prompt_tokens_details = OpenAiPromptTokensDetails(cached_tokens = 40),
            cost = 0.0123
        ).toTokenUsage()

        assertThat(usage.inputTokens).isEqualTo(60)
        assertThat(usage.cachedInputTokens).isEqualTo(40)
        assertThat(usage.outputTokens).isEqualTo(25)
        assertThat(usage.apiCost).isEqualTo(0.0123)
    }

    @Test fun openAiUsage_uses_deepseek_cache_hit_tokens_when_present() {
        val usage = OpenAiUsage(
            prompt_tokens = 100,
            completion_tokens = 10,
            total_tokens = 110,
            prompt_cache_hit_tokens = 95
        ).toTokenUsage()

        assertThat(usage.inputTokens).isEqualTo(5)
        assertThat(usage.cachedInputTokens).isEqualTo(95)
    }

    @Test fun claudeUsage_preserves_cache_creation_and_read_buckets() {
        val usage = ClaudeUsage(
            input_tokens = 50,
            output_tokens = 20,
            cache_read_input_tokens = 11,
            cache_creation_input_tokens = 7,
            cost_in_usd_ticks = 123_000_000
        ).toTokenUsage()

        assertThat(usage.inputTokens).isEqualTo(50)
        assertThat(usage.outputTokens).isEqualTo(20)
        assertThat(usage.cachedInputTokens).isEqualTo(11)
        assertThat(usage.cacheCreationTokens).isEqualTo(7)
        assertThat(usage.apiCost).isEqualTo(0.0123)
    }

    @Test fun geminiUsage_subtracts_cached_content_tokens() {
        val usage = GeminiUsageMetadata(
            promptTokenCount = 80,
            candidatesTokenCount = 30,
            totalTokenCount = 110,
            cachedContentTokenCount = 35,
            cost_usd = UsageCost(total_cost = 0.045)
        ).toTokenUsage()

        assertThat(usage.inputTokens).isEqualTo(45)
        assertThat(usage.cachedInputTokens).isEqualTo(35)
        assertThat(usage.outputTokens).isEqualTo(30)
        assertThat(usage.apiCost).isEqualTo(0.045)
    }

    @Test fun costDeserializer_accepts_primitive_and_object_shapes() {
        val primitive = createAppGson().fromJson(
            """{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3,"cost":0.5}""",
            OpenAiUsage::class.java
        )
        val objectCost = createAppGson().fromJson(
            """{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3,"cost":{"total_cost":0.75}}""",
            OpenAiUsage::class.java
        )

        assertThat(primitive.cost).isEqualTo(0.5)
        assertThat(objectCost.cost).isEqualTo(0.75)
    }
}
