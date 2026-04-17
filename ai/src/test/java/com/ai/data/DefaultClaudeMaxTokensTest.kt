package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DefaultClaudeMaxTokensTest {
    @Test fun opus4_gets_32k() {
        assertThat(defaultClaudeMaxTokens("claude-opus-4-20250514")).isEqualTo(32_000)
        assertThat(defaultClaudeMaxTokens("Claude-Opus-4")).isEqualTo(32_000)
    }

    @Test fun sonnet4_gets_8k() {
        assertThat(defaultClaudeMaxTokens("claude-sonnet-4-20250514")).isEqualTo(8_192)
    }

    @Test fun haiku4_gets_8k() {
        assertThat(defaultClaudeMaxTokens("claude-haiku-4")).isEqualTo(8_192)
    }

    @Test fun claude_3_5_variants_get_8k() {
        assertThat(defaultClaudeMaxTokens("claude-3-5-sonnet-20241022")).isEqualTo(8_192)
        assertThat(defaultClaudeMaxTokens("claude-3.5-haiku")).isEqualTo(8_192)
    }

    @Test fun older_models_fall_back_to_4k() {
        assertThat(defaultClaudeMaxTokens("claude-3-opus-20240229")).isEqualTo(4_096)
        assertThat(defaultClaudeMaxTokens("claude-3-haiku-20240307")).isEqualTo(4_096)
        assertThat(defaultClaudeMaxTokens("claude-2.1")).isEqualTo(4_096)
        assertThat(defaultClaudeMaxTokens("unknown-model")).isEqualTo(4_096)
    }
}
