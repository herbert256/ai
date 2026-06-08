package com.ai.ui.search

import com.ai.data.ApiFormat
import com.ai.data.AppService
import com.ai.data.ModelType
import com.ai.data.ProviderRegistry
import com.ai.model.ProviderConfig
import com.ai.model.Settings
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class SemanticSearchChoicesTest {
    // ProviderRegistry is a process-global singleton; remove the test providers
    // we register so they don't leak into later tests (e.g. GsonNullSafetyTest,
    // which reads the registry-derived Settings.providers default).
    @After fun removeTestProviders() {
        ProviderRegistry.remove("UNIT_SEARCH_OPENAI")
        ProviderRegistry.remove("UNIT_SEARCH_GOOGLE")
    }

    @Test fun supportedEmbeddingChoices_keeps_openai_compatible_and_google_embedding_models() {
        val openAiCompatible = AppService(
            id = "UNIT_SEARCH_OPENAI",
            baseUrl = "https://openai-compatible.example.com/",
            adminUrl = "",
            defaultModel = "chat"
        )
        val google = AppService(
            id = "UNIT_SEARCH_GOOGLE",
            baseUrl = "https://google.example.com/",
            adminUrl = "",
            defaultModel = "gemini",
            apiFormat = ApiFormat.GOOGLE
        )
        ProviderRegistry.ensureProviders(listOf(openAiCompatible, google))

        val settings = Settings(
            providers = mapOf(
                openAiCompatible to ProviderConfig(
                    apiKey = "key",
                    models = listOf("text-embedding-3-small", "chat-model"),
                    modelTypes = mapOf(
                        "text-embedding-3-small" to ModelType.EMBEDDING,
                        "chat-model" to ModelType.CHAT
                    )
                ),
                google to ProviderConfig(
                    apiKey = "key",
                    models = listOf("gemini-embedding-001"),
                    modelTypes = mapOf("gemini-embedding-001" to ModelType.EMBEDDING)
                )
            ),
            providerStates = mapOf(
                openAiCompatible.id to "ok",
                google.id to "ok"
            )
        )

        // Both OpenAI-compatible AND Google embedding models are supported; the
        // CHAT-typed model is excluded. (Google embedding support was added to
        // supportedEmbeddingChoices; the chat model stays filtered out.)
        assertThat(supportedEmbeddingChoices(settings))
            .containsExactly(
                openAiCompatible to "text-embedding-3-small",
                google to "gemini-embedding-001",
            )
    }
}
