package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SecondaryResultHelpersTest {
    @Test fun resolveSecondaryPrompt_replaces_static_placeholders() {
        val prompt = resolveSecondaryPrompt(
            template = "Title=@TITLE@ Count=@COUNT@ Q=@QUESTION@ R=@RESULTS@",
            question = "Why?",
            results = "[1] answer",
            count = 3,
            title = "Report title"
        )

        assertThat(prompt).contains("Title=Report title")
        assertThat(prompt).contains("Count=3")
        assertThat(prompt).contains("Q=Why?")
        assertThat(prompt).contains("R=[1] answer")
        assertThat(prompt).doesNotContain("@TITLE@")
    }

    @Test fun buildResultsBlock_includes_successes_only_and_preserves_original_ids() {
        val report = Report(
            id = "r1",
            timestamp = 0,
            title = "title",
            prompt = "prompt",
            agents = mutableListOf(
                ReportAgent("a1", "A", "UNIT", "m1", reportStatus = ReportStatus.SUCCESS, responseBody = "first"),
                ReportAgent("a2", "B", "UNIT", "m2", reportStatus = ReportStatus.ERROR, responseBody = "failed"),
                ReportAgent("a3", "C", "UNIT", "m3", reportStatus = ReportStatus.SUCCESS, responseBody = "third"),
                ReportAgent("a4", "D", "UNIT", "m4", reportStatus = ReportStatus.SUCCESS, responseBody = "fourth")
            )
        )

        val all = buildResultsBlock(report)
        val subset = buildResultsBlock(report, includeIds = setOf(1, 3))

        // @RESULTS@ headers are short — just the bracketed [N] id.
        // Provider / model identifiers reach the user via the legend
        // appended to the secondary result, not via the prompt block.
        assertThat(all).contains("[1]\nfirst")
        assertThat(all).contains("[2]\nthird")
        assertThat(all).contains("[3]\nfourth")
        assertThat(all).doesNotContain("failed")
        assertThat(all).doesNotContain("provider=UNIT")
        assertThat(subset).contains("[1]\nfirst")
        assertThat(subset).contains("[3]\nfourth")
        assertThat(subset).doesNotContain("[2]")
    }

    @Test fun extractTopRankedIds_accepts_plain_json_and_fenced_json() {
        val json = """[{"id":4,"rank":2},{"id":2,"rank":1},{"id":7,"rank":3}]"""
        val fenced = "```json\n$json\n```"

        assertThat(extractTopRankedIds(json, 2)).containsExactly(2, 4).inOrder()
        assertThat(extractTopRankedIds(fenced, 3)).containsExactly(2, 4, 7).inOrder()
    }

    @Test fun extractTopRankedIds_returns_null_for_invalid_inputs() {
        assertThat(extractTopRankedIds(null, 2)).isNull()
        assertThat(extractTopRankedIds("not json", 2)).isNull()
        assertThat(extractTopRankedIds("[]", 2)).isNull()
        assertThat(extractTopRankedIds("""[{"rank":1}]""", 2)).isNull()
    }
}
