package com.ai.ui.search

import com.ai.data.ApiFormat
import com.ai.data.AppService
import com.ai.data.ModelType
import com.ai.data.ProviderRegistry
import com.ai.model.ProviderConfig
import com.ai.model.Settings
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SemanticSearchChoicesTest {
    @Test fun supportedEmbeddingChoices_keeps_only_openai_compatible_embedding_models() {
        val openAiCompatible = AppService(
            id = "UNIT_SEARCH_OPENAI",
            displayName = "Unit Search OpenAI",
            baseUrl = "https://openai-compatible.example.com/",
            adminUrl = "",
            defaultModel = "chat"
        )
        val google = AppService(
            id = "UNIT_SEARCH_GOOGLE",
            displayName = "Unit Search Google",
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

        assertThat(supportedEmbeddingChoices(settings))
            .containsExactly(openAiCompatible to "text-embedding-3-small")
    }
}
