package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProviderDefinitionTest {
    @Test fun defaults_fill_in_for_minimal_openai_compatible_provider() {
        val def = ProviderDefinition(
            id = "Mini", baseUrl = "https://example.com/",
            defaultModel = "mini-1"
        )
        val service = def.toAppService()
        assertThat(service.apiFormat).isEqualTo(ApiFormat.OPENAI_COMPATIBLE)
        assertThat(service.chatPath).isEqualTo("v1/chat/completions")
        assertThat(service.modelsPath).isEqualTo("v1/models")
        assertThat(service.modelListFormat).isEqualTo("object")
        assertThat(service.seedFieldName).isEqualTo("seed")
    }

    @Test fun explicit_fields_override_defaults() {
        val def = ProviderDefinition(
            id = "Custom", baseUrl = "https://x.com/",
            defaultModel = "c", apiFormat = "ANTHROPIC", chatPath = "v1/messages"
        )
        val service = def.toAppService()
        assertThat(service.apiFormat).isEqualTo(ApiFormat.ANTHROPIC)
        assertThat(service.chatPath).isEqualTo("v1/messages")
    }

    @Test fun invalid_api_format_falls_back_to_openai_compatible() {
        val def = ProviderDefinition(
            id = "X", baseUrl = "https://x/", defaultModel = "m",
            apiFormat = "NOT_A_REAL_FORMAT"
        )
        assertThat(def.toAppService().apiFormat).isEqualTo(ApiFormat.OPENAI_COMPATIBLE)
    }
}
