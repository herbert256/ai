package com.ai.ui.helpers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [redactJsonString] + [redactHeaders] — the secret-scrubbing paths not already
 *  covered by ExportRedactionTest (which tests redactUrl / redactHeaderMap). */
class PdfRedactionExtraTest {

    @Test fun blank_json_returns_null() {
        assertThat(redactJsonString(null)).isNull()
        assertThat(redactJsonString("")).isNull()
        assertThat(redactJsonString("   ")).isNull()
    }

    @Test fun redacts_sensitive_top_level_keys_keeps_the_rest() {
        val out = redactJsonString("{\"api_key\":\"TOPSECRET\",\"model\":\"gpt\"}")!!
        assertThat(out).contains(REDACTED)
        assertThat(out).doesNotContain("TOPSECRET")
        assertThat(out).contains("gpt")
    }

    @Test fun redacts_nested_and_arrayed_secrets() {
        val out = redactJsonString("{\"a\":{\"token\":\"NESTED\"},\"b\":[{\"password\":\"INARR\"}]}")!!
        assertThat(out).doesNotContain("NESTED")
        assertThat(out).doesNotContain("INARR")
        assertThat(out).contains(REDACTED)
    }

    @Test fun invalid_json_is_returned_unchanged() {
        assertThat(redactJsonString("not json")).isEqualTo("not json")
    }

    @Test fun headers_none_when_empty() {
        assertThat(redactHeaders(null)).isEqualTo("(none)")
        assertThat(redactHeaders(emptyMap())).isEqualTo("(none)")
    }

    @Test fun headers_joined_and_sensitive_ones_redacted() {
        val out = redactHeaders(mapOf("Authorization" to "Bearer x", "Accept" to "application/json"))
        assertThat(out).contains("Authorization: $REDACTED")
        assertThat(out).contains("Accept: application/json")
    }
}
