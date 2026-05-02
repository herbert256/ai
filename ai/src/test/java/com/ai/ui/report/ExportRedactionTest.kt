package com.ai.ui.report

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

class ExportRedactionTest {
    @Test fun redactUrl_removes_sensitive_query_values_only() {
        val redacted = redactUrl(
            "https://api.example.com/v1/models?key=gemini-secret&normal=keep&api_key=openai-secret&token=session-secret"
        )
        val parsed = redacted.toHttpUrl()

        assertThat(parsed.queryParameter("key")).isEqualTo(REDACTED)
        assertThat(parsed.queryParameter("api_key")).isEqualTo(REDACTED)
        assertThat(parsed.queryParameter("token")).isEqualTo(REDACTED)
        assertThat(parsed.queryParameter("normal")).isEqualTo("keep")
        assertThat(redacted).doesNotContain("gemini-secret")
        assertThat(redacted).doesNotContain("openai-secret")
        assertThat(redacted).doesNotContain("session-secret")
    }

    @Test fun redactHeaderMap_redacts_sensitive_headers_case_insensitively() {
        val redacted = redactHeaderMap(
            mapOf(
                "Authorization" to "Bearer raw-token",
                "X-API-Key" to "raw-key",
                "Content-Type" to "application/json"
            )
        )

        assertThat(redacted["Authorization"]).isEqualTo(REDACTED)
        assertThat(redacted["X-API-Key"]).isEqualTo(REDACTED)
        assertThat(redacted["Content-Type"]).isEqualTo("application/json")
    }

    @Test fun redactJsonString_redacts_nested_sensitive_keys() {
        val redacted = redactJsonString(
            """{"api_key":"raw-key","nested":{"token":"raw-token","safe":"keep"},"items":[{"secret":"raw-secret"}]}"""
        )

        @Suppress("DEPRECATION")
        val obj = JsonParser().parse(redacted).asJsonObject
        assertThat(obj["api_key"].asString).isEqualTo(REDACTED)
        assertThat(obj["nested"].asJsonObject["token"].asString).isEqualTo(REDACTED)
        assertThat(obj["nested"].asJsonObject["safe"].asString).isEqualTo("keep")
        assertThat(obj["items"].asJsonArray[0].asJsonObject["secret"].asString).isEqualTo(REDACTED)
        assertThat(redacted).doesNotContain("raw-key")
        assertThat(redacted).doesNotContain("raw-token")
        assertThat(redacted).doesNotContain("raw-secret")
    }
}
