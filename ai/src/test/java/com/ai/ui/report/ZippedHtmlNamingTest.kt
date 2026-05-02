package com.ai.ui.report

import com.ai.data.SecondaryKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Coverage for the small filesystem-safe naming helpers used by the
 * Zipped HTML export to lay out per-item HTML files inside the
 * generated zip — and the trace-directory naming used by the JSON
 * tree. They're plain string functions but the export's link-up
 * relies on their output being stable, so a regression would
 * break breadcrumb / 🐞 / Source/Result links.
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

    // ===== resolveTranslationLinks =====

    private fun agent(idx: Int): HtmlAgentData = HtmlAgentData(
        agentId = "agent-$idx",
        agentName = "agent $idx",
        provider = null,
        providerDisplay = "ProvX",
        model = "model-$idx",
        responseText = null,
        errorMessage = null,
        citations = null,
        searchResults = null,
        relatedQuestions = null,
        rawUsageJson = null,
        responseHeaders = null
    )

    private fun secondary(id: String, kind: SecondaryKind, fromId: String? = null): HtmlSecondaryData =
        HtmlSecondaryData(
            id = id,
            kind = kind,
            providerDisplay = "ProvX",
            model = "model",
            agentName = "name",
            timestamp = "2026-05-02 09:00:00",
            content = "",
            errorMessage = null,
            translatedFromSecondaryId = fromId
        )

    private fun data(agents: List<HtmlAgentData> = emptyList(), secondary: List<HtmlSecondaryData> = emptyList()): HtmlReportData =
        HtmlReportData(
            title = "T",
            prompt = "",
            timestamp = "ts",
            rapportText = null,
            closeText = null,
            agents = agents,
            secondary = secondary,
            traces = emptyList()
        )

    @Test fun resolveTranslationLinks_PROMPT_with_source_yields_both_links() {
        val translate = HtmlSecondaryData(
            id = "t1", kind = SecondaryKind.TRANSLATE,
            providerDisplay = "Anthropic", model = "claude-haiku-4-5",
            agentName = "Translate: prompt", timestamp = "ts",
            content = "", errorMessage = null,
            translateSourceKind = "PROMPT",
            translateSourceTargetId = "prompt"
        )
        val current = data()
        val source = data()
        val (src, res) = resolveTranslationLinks(translate, current, source)
        assertThat(src).isEqualTo("../Source/Prompt/index.html")
        assertThat(res).isEqualTo("../Prompt/index.html")
    }

    @Test fun resolveTranslationLinks_PROMPT_without_source_returns_null_source() {
        val translate = HtmlSecondaryData(
            id = "t1", kind = SecondaryKind.TRANSLATE,
            providerDisplay = "p", model = "m", agentName = "Translate: prompt",
            timestamp = "ts", content = "", errorMessage = null,
            translateSourceKind = "PROMPT", translateSourceTargetId = "prompt"
        )
        val (src, res) = resolveTranslationLinks(translate, data(), sourceData = null)
        assertThat(src).isNull()
        assertThat(res).isEqualTo("../Prompt/index.html")
    }

    @Test fun resolveTranslationLinks_AGENT_links_by_preserved_agentId() {
        val src = data(agents = listOf(agent(1), agent(2), agent(3)))
        val current = data(agents = listOf(agent(1), agent(2), agent(3)))
        val translate = HtmlSecondaryData(
            id = "t", kind = SecondaryKind.TRANSLATE,
            providerDisplay = "p", model = "m", agentName = "Translate: ProvX / model-2",
            timestamp = "ts", content = "", errorMessage = null,
            translateSourceKind = "AGENT", translateSourceTargetId = "agent-2"
        )
        val (sourceLink, resultLink) = resolveTranslationLinks(translate, current, src)
        assertThat(sourceLink).isEqualTo("../Source/Reports/02_ProvX_model-2.html")
        assertThat(resultLink).isEqualTo("../Reports/02_ProvX_model-2.html")
    }

    @Test fun resolveTranslationLinks_AGENT_returns_null_when_target_missing() {
        val src = data(agents = listOf(agent(1)))
        val current = data(agents = listOf(agent(1)))
        val translate = HtmlSecondaryData(
            id = "t", kind = SecondaryKind.TRANSLATE,
            providerDisplay = "p", model = "m", agentName = "Translate: ?",
            timestamp = "ts", content = "", errorMessage = null,
            translateSourceKind = "AGENT", translateSourceTargetId = "agent-99"
        )
        val (s, r) = resolveTranslationLinks(translate, current, src)
        assertThat(s).isNull()
        assertThat(r).isNull()
    }

    @Test fun resolveTranslationLinks_SUMMARY_uses_translatedFromSecondaryId_for_result() {
        val srcSummary = secondary("src-1", SecondaryKind.SUMMARIZE)
        val translatedSummary = secondary("new-1", SecondaryKind.SUMMARIZE, fromId = "src-1")
        val source = data(secondary = listOf(srcSummary))
        val current = data(secondary = listOf(translatedSummary))
        val translate = HtmlSecondaryData(
            id = "t", kind = SecondaryKind.TRANSLATE,
            providerDisplay = "p", model = "m",
            agentName = "Translate: Summary 1: ProvX / model",
            timestamp = "ts", content = "", errorMessage = null,
            translateSourceKind = "SUMMARY", translateSourceTargetId = "src-1"
        )
        val (s, r) = resolveTranslationLinks(translate, current, source)
        assertThat(s).isEqualTo("../Source/Summaries/01_ProvX_model.html")
        assertThat(r).isEqualTo("../Summaries/01_ProvX_model.html")
    }

    @Test fun resolveTranslationLinks_unknown_kind_yields_pair_of_nulls() {
        val translate = HtmlSecondaryData(
            id = "t", kind = SecondaryKind.TRANSLATE,
            providerDisplay = "p", model = "m", agentName = "?",
            timestamp = "ts", content = "", errorMessage = null,
            translateSourceKind = "UNRECOGNISED", translateSourceTargetId = "x"
        )
        val (s, r) = resolveTranslationLinks(translate, data(), data())
        assertThat(s).isNull()
        assertThat(r).isNull()
    }

    @Test fun resolveTranslationLinks_no_source_kind_returns_null_pair() {
        val translate = HtmlSecondaryData(
            id = "t", kind = SecondaryKind.TRANSLATE,
            providerDisplay = "p", model = "m", agentName = "Translate: prompt",
            timestamp = "ts", content = "", errorMessage = null,
            translateSourceKind = null, translateSourceTargetId = "prompt"
        )
        val (s, r) = resolveTranslationLinks(translate, data(), data())
        assertThat(s).isNull()
        assertThat(r).isNull()
    }
}
