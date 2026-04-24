package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BuildChatUrlTest {
    @Test fun appends_chat_path_to_bare_base_url_with_trailing_slash() {
        assertThat(buildChatUrl("https://api.example.com/", "v1/chat/completions"))
            .isEqualTo("https://api.example.com/v1/chat/completions")
    }

    @Test fun appends_chat_path_to_bare_base_url_without_trailing_slash() {
        assertThat(buildChatUrl("https://api.example.com", "v1/chat/completions"))
            .isEqualTo("https://api.example.com/v1/chat/completions")
    }

    @Test fun does_not_duplicate_when_url_already_ends_with_chat_path() {
        // The 404 bug: reports passed the full endpoint URL and analyzeOpenAi appended chatPath again.
        assertThat(buildChatUrl("https://api.cohere.ai/compatibility/v1/chat/completions", "v1/chat/completions"))
            .isEqualTo("https://api.cohere.ai/compatibility/v1/chat/completions")
    }

    @Test fun handles_short_chat_paths() {
        assertThat(buildChatUrl("https://api.perplexity.ai/", "chat/completions"))
            .isEqualTo("https://api.perplexity.ai/chat/completions")
        assertThat(buildChatUrl("https://api.perplexity.ai/chat/completions", "chat/completions"))
            .isEqualTo("https://api.perplexity.ai/chat/completions")
    }

    @Test fun tolerates_deep_paths_in_base_url() {
        assertThat(buildChatUrl("https://api.z.ai/api/paas/v4/", "chat/completions"))
            .isEqualTo("https://api.z.ai/api/paas/v4/chat/completions")
        assertThat(buildChatUrl("https://api.z.ai/api/paas/v4/chat/completions", "chat/completions"))
            .isEqualTo("https://api.z.ai/api/paas/v4/chat/completions")
    }

    @Test fun strips_trailing_slash_on_already_complete_url() {
        assertThat(buildChatUrl("https://api.example.com/v1/chat/completions/", "v1/chat/completions"))
            .isEqualTo("https://api.example.com/v1/chat/completions")
    }

    @Test fun returns_url_unchanged_when_chat_path_empty() {
        assertThat(buildChatUrl("https://api.example.com/foo", "")).isEqualTo("https://api.example.com/foo")
    }
}
