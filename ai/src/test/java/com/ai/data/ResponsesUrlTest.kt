package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResponsesUrlTest {
    @Test fun responsesUrlFor_uses_custom_base_url_and_provider_path() {
        val service = AppService(
            id = "UNIT_RESPONSES",
            displayName = "Unit Responses",
            baseUrl = "https://provider.example.com/",
            adminUrl = "",
            defaultModel = "gpt-5",
            typePaths = mapOf(ModelType.RESPONSES to "custom/responses")
        )

        assertThat(responsesUrlFor(service, "https://proxy.example.com/root/"))
            .isEqualTo("https://proxy.example.com/root/custom/responses")
    }

    @Test fun responsesUrlFor_does_not_duplicate_complete_endpoint_url() {
        val service = AppService(
            id = "UNIT_RESPONSES_COMPLETE",
            displayName = "Unit Responses Complete",
            baseUrl = "https://provider.example.com/",
            adminUrl = "",
            defaultModel = "gpt-5",
            typePaths = mapOf(ModelType.RESPONSES to "v1/responses")
        )

        assertThat(responsesUrlFor(service, "https://proxy.example.com/v1/responses"))
            .isEqualTo("https://proxy.example.com/v1/responses")
    }

    @Test fun responsesUrlFor_falls_back_to_default_responses_path() {
        val service = AppService(
            id = "UNIT_RESPONSES_DEFAULT",
            displayName = "Unit Responses Default",
            baseUrl = "https://provider.example.com/",
            adminUrl = "",
            defaultModel = "gpt-5"
        )

        assertThat(responsesUrlFor(service, "https://proxy.example.com/"))
            .isEqualTo("https://proxy.example.com/v1/responses")
    }
}
