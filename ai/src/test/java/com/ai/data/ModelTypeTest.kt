package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelTypeTest {
    @Test fun infer_routes_reasoning_families_to_responses_api() {
        assertThat(ModelType.infer("gpt-5-mini")).isEqualTo(ModelType.RESPONSES)
        assertThat(ModelType.infer("o3-pro")).isEqualTo(ModelType.RESPONSES)
        assertThat(ModelType.infer("o4-mini")).isEqualTo(ModelType.RESPONSES)
    }

    @Test fun infer_detects_non_chat_model_families() {
        assertThat(ModelType.infer("text-embedding-3-small")).isEqualTo(ModelType.EMBEDDING)
        assertThat(ModelType.infer("rerank-v3.5")).isEqualTo(ModelType.RERANK)
        assertThat(ModelType.infer("omni-moderation-latest")).isEqualTo(ModelType.MODERATION)
        assertThat(ModelType.infer("whisper-large-v3")).isEqualTo(ModelType.STT)
        assertThat(ModelType.infer("dall-e-3")).isEqualTo(ModelType.IMAGE)
        assertThat(ModelType.infer("ordinary-chat-model")).isEqualTo(ModelType.CHAT)
    }

    @Test fun provider_metadata_maps_to_model_types() {
        assertThat(ModelType.fromOpenRouterModality("text->image")).isEqualTo(ModelType.IMAGE)
        assertThat(ModelType.fromOpenRouterModality("text->audio")).isEqualTo(ModelType.TTS)
        assertThat(ModelType.fromCohereEndpoints(listOf("chat", "embed"))).isEqualTo(ModelType.EMBEDDING)
        assertThat(ModelType.fromCohereEndpoints(listOf("rerank", "chat"))).isEqualTo(ModelType.RERANK)
        assertThat(ModelType.fromGeminiMethods(listOf("embedContent", "generateContent"))).isEqualTo(ModelType.EMBEDDING)
    }

    @Test fun vision_heuristic_is_conservative_but_covers_known_families() {
        assertThat(ModelType.inferVision("gpt-4o-mini")).isTrue()
        assertThat(ModelType.inferVision("claude-3-5-sonnet")).isTrue()
        assertThat(ModelType.inferVision("llama-3.2-90b-vision")).isTrue()
        assertThat(ModelType.inferVision("llama-3.1-70b-instruct")).isFalse()
    }

    @Test fun web_search_heuristic_is_provider_specific() {
        // inferWebSearch now consults provider.webSearchModelPatterns
        // exclusively — the family-name heuristic moved to provider
        // config (assets/providers.json). Build test services with the
        // same patterns the bundled config declares so the contract
        // ("anthropic/claude-3.7 has web search, openai/gpt-4o-mini
        // doesn't") is still covered.
        val anthropic = service("ANTHROPIC_UNIT", ApiFormat.ANTHROPIC, webSearchModelPatterns = listOf(
            ModelPattern(contains = "claude-3-7"),
            ModelPattern(contains = "sonnet-4")
        ))
        val google = service("GOOGLE_UNIT", ApiFormat.GOOGLE, webSearchModelPatterns = listOf(
            ModelPattern(contains = "gemini-2")
        ))
        val openAiCompatible = service("OPENAI_COMPAT_UNIT", ApiFormat.OPENAI_COMPATIBLE, webSearchModelPatterns = listOf(
            ModelPattern(prefix = "gpt-5")
        ))

        assertThat(ModelType.inferWebSearch(anthropic, "claude-3-7-sonnet")).isTrue()
        assertThat(ModelType.inferWebSearch(google, "gemini-2.5-pro")).isTrue()
        assertThat(ModelType.inferWebSearch(openAiCompatible, "gpt-5-mini")).isTrue()
        assertThat(ModelType.inferWebSearch(openAiCompatible, "gpt-4o-mini")).isFalse()
    }

    private fun service(
        id: String,
        format: ApiFormat,
        webSearchModelPatterns: List<ModelPattern> = emptyList()
    ) = AppService(
        id = id,
        baseUrl = "https://$id.example.com/",
        adminUrl = "",
        defaultModel = "model",
        apiFormat = format,
        webSearchModelPatterns = webSearchModelPatterns
    )
}
