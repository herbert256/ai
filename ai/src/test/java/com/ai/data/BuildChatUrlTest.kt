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

    // ---- alternate-path stripping (the cross-endpoint 404 bug) ----

    @Test fun strips_chat_completions_tail_when_caller_wants_responses() {
        // Production trace: /v1/chat/completions/v1/responses → 404 "Invalid URL".
        // The user configured the OpenAI provider with the chat endpoint as base
        // URL; gpt-5-* models route to v1/responses, which must replace, not append.
        assertThat(
            buildChatUrl(
                "https://api.openai.com/v1/chat/completions",
                "v1/responses",
                listOf("v1/chat/completions", "v1/responses", "v1/embeddings")
            )
        ).isEqualTo("https://api.openai.com/v1/responses")
    }

    @Test fun strips_responses_tail_when_caller_wants_chat_completions() {
        assertThat(
            buildChatUrl(
                "https://api.openai.com/v1/responses",
                "v1/chat/completions",
                listOf("v1/chat/completions", "v1/responses", "v1/embeddings")
            )
        ).isEqualTo("https://api.openai.com/v1/chat/completions")
    }

    @Test fun strips_chat_completions_tail_when_caller_wants_embeddings() {
        assertThat(
            buildChatUrl(
                "https://api.openai.com/v1/chat/completions",
                "v1/embeddings",
                listOf("v1/chat/completions", "v1/responses", "v1/embeddings")
            )
        ).isEqualTo("https://api.openai.com/v1/embeddings")
    }

    @Test fun does_not_strip_when_target_is_alternate_set() {
        // alternatePaths includes the target path; the existing "already ends with"
        // branch should fire first and leave the URL untouched.
        assertThat(
            buildChatUrl(
                "https://api.openai.com/v1/responses",
                "v1/responses",
                listOf("v1/chat/completions", "v1/responses")
            )
        ).isEqualTo("https://api.openai.com/v1/responses")
    }

    @Test fun preserves_proxy_prefix_when_stripping_endpoint_tail() {
        // User's base URL has a custom proxy prefix; we should preserve it and
        // only strip the recognised endpoint suffix.
        assertThat(
            buildChatUrl(
                "https://my-proxy.example/openai/v1/chat/completions",
                "v1/responses",
                listOf("v1/chat/completions", "v1/responses")
            )
        ).isEqualTo("https://my-proxy.example/openai/v1/responses")
    }

    @Test fun ignores_alternates_for_bare_base_url() {
        // No tail to strip — alternatePaths must not change behavior.
        assertThat(
            buildChatUrl(
                "https://api.example.com/",
                "v1/chat/completions",
                listOf("v1/responses", "v1/embeddings")
            )
        ).isEqualTo("https://api.example.com/v1/chat/completions")
    }
}
