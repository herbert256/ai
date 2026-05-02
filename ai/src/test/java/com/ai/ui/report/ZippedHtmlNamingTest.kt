package com.ai.ui.report

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Coverage for the small filesystem-safe naming helpers used by the
 * Zipped HTML export to lay out per-item HTML files inside the
 * generated zip — and the trace-directory naming used by the JSON
 * tree. They're plain string functions but the export's link-up
 * relies on their output being stable, so a regression would break
 * breadcrumb / 🐞 navigation.
 */
class ZippedHtmlNamingTest {

    @Test fun safeName_replaces_special_characters_with_underscore() {
        assertThat(safeName("Acme Co. / GPT-4o")).isEqualTo("Acme_Co._GPT-4o")
        assertThat(safeName("a:b?c=d")).isEqualTo("a_b_c_d")
    }

    @Test fun safeName_trims_leading_and_trailing_underscores() {
        assertThat(safeName("  spaced  ")).isEqualTo("spaced")
        assertThat(safeName("/leading/trailing/")).isEqualTo("leading_trailing")
    }

    @Test fun safeName_falls_back_to_single_underscore_when_input_is_all_separators() {
        assertThat(safeName("///   ")).isEqualTo("_")
    }

    @Test fun safeName_truncates_overlong_input_to_80_chars() {
        val long = "abc".repeat(100)
        val safe = safeName(long)
        assertThat(safe.length).isAtMost(80)
        assertThat(safe).startsWith("abc")
    }

    @Test fun itemFilename_zero_pads_index_to_two_digits_and_appends_html() {
        assertThat(itemFilename(0, "OpenAI gpt-4o"))
            .isEqualTo("01_OpenAI_gpt-4o.html")
        assertThat(itemFilename(8, "X"))
            .isEqualTo("09_X.html")
        assertThat(itemFilename(99, "Y"))
            .isEqualTo("100_Y.html")
    }

    @Test fun traceDirName_combines_method_host_status_with_safe_separators() {
        val trace = HtmlTraceData(
            id = "t0",
            origin = "this",
            timestamp = "2026-05-02 09:00:00",
            method = "POST",
            hostname = "api.openai.com",
            url = "https://api.openai.com/v1/chat/completions",
            statusCode = 200,
            model = "gpt-5",
            category = "Report",
            requestHeaders = "",
            requestBody = "",
            responseHeaders = "",
            responseBody = ""
        )
        assertThat(traceDirName(0, trace))
            .isEqualTo("01_POST_api.openai.com_200")
    }

    @Test fun traceDirName_handles_unusual_status_and_long_host_within_limit() {
        val trace = HtmlTraceData(
            id = "tx",
            origin = "this",
            timestamp = "ts",
            method = "GET",
            hostname = "really-long-host-name-that-keeps-going-and-going.example.com",
            url = "https://example.com/?q=long",
            statusCode = 500,
            model = null,
            category = null,
            requestHeaders = "",
            requestBody = "",
            responseHeaders = "",
            responseBody = ""
        )
        val dir = traceDirName(7, trace)
        assertThat(dir).startsWith("08_GET_really-long-host-name")
        assertThat(dir.endsWith("500")).isTrue()
    }

    // ===== languageKey =====

    @Test fun languageKey_lowercases_and_strips_non_alphanumerics() {
        assertThat(languageKey("Dutch")).isEqualTo("dutch")
        assertThat(languageKey("Mandarin Chinese")).isEqualTo("mandarinchinese")
        assertThat(languageKey("Persian (Farsi)")).isEqualTo("persianfarsi")
    }

    @Test fun languageKey_falls_back_to_x_when_input_has_no_alnum() {
        assertThat(languageKey("---")).isEqualTo("x")
        assertThat(languageKey("")).isEqualTo("x")
    }
}
