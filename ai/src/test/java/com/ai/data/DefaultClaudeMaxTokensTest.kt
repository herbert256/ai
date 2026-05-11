package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DefaultClaudeMaxTokensTest {
    private val anthropic = AppService(
        id = "Anthropic",
        baseUrl = "https://api.anthropic.com/",
        adminUrl = "",
        defaultModel = "claude-sonnet-4-6",
        apiFormat = ApiFormat.ANTHROPIC,
        maxTokensDefaults = listOf(
            MaxTokensRule(ModelPattern(contains = "opus-4"), 32_000),
            MaxTokensRule(ModelPattern(contains = "sonnet-4"), 8_192),
            MaxTokensRule(ModelPattern(contains = "haiku-4"), 8_192),
            MaxTokensRule(ModelPattern(contains = "claude-3-5"), 8_192),
            MaxTokensRule(ModelPattern(contains = "claude-3.5"), 8_192),
        )
    )

    @Test fun opus4_gets_32k() {
        assertThat(defaultClaudeMaxTokens(anthropic, "claude-opus-4-20250514")).isEqualTo(32_000)
        assertThat(defaultClaudeMaxTokens(anthropic, "Claude-Opus-4")).isEqualTo(32_000)
    }

    @Test fun sonnet4_gets_8k() {
        assertThat(defaultClaudeMaxTokens(anthropic, "claude-sonnet-4-20250514")).isEqualTo(8_192)
    }

    @Test fun haiku4_gets_8k() {
        assertThat(defaultClaudeMaxTokens(anthropic, "claude-haiku-4")).isEqualTo(8_192)
    }

    @Test fun claude_3_5_variants_get_8k() {
        assertThat(defaultClaudeMaxTokens(anthropic, "claude-3-5-sonnet-20241022")).isEqualTo(8_192)
        assertThat(defaultClaudeMaxTokens(anthropic, "claude-3.5-haiku")).isEqualTo(8_192)
    }

    @Test fun older_models_fall_back_to_4k() {
        assertThat(defaultClaudeMaxTokens(anthropic, "claude-3-opus-20240229")).isEqualTo(4_096)
        assertThat(defaultClaudeMaxTokens(anthropic, "claude-3-haiku-20240307")).isEqualTo(4_096)
        assertThat(defaultClaudeMaxTokens(anthropic, "claude-2.1")).isEqualTo(4_096)
        assertThat(defaultClaudeMaxTokens(anthropic, "unknown-model")).isEqualTo(4_096)
    }

    @Test fun provider_without_rules_falls_back_to_4k() {
        val noRules = anthropic.copy(maxTokensDefaults = emptyList())
        assertThat(defaultClaudeMaxTokens(noRules, "claude-opus-4")).isEqualTo(4_096)
    }
}
