package com.ai.ui.helpers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure export helpers in ReportExport.kt: [languageKey], [processThinkSections]
 *  and [convertMarkdownToHtmlForExport]. */
class ReportExportHelpersTest {

    // ---- languageKey ----

    @Test fun language_key_lowercases_and_strips_non_alphanumerics() {
        assertThat(languageKey("French")).isEqualTo("french")
        assertThat(languageKey("English (US)")).isEqualTo("englishus")
        assertThat(languageKey("GPT-4 Turbo")).isEqualTo("gpt4turbo")
    }

    @Test fun language_key_falls_back_to_x_when_blank() {
        assertThat(languageKey("")).isEqualTo("x")
        assertThat(languageKey("   ")).isEqualTo("x")
        assertThat(languageKey("中文")).isEqualTo("x")   // no ascii alphanumerics
    }

    // ---- processThinkSections ----

    @Test fun no_think_block_is_just_markdown_conversion() {
        assertThat(processThinkSections("hello", "a"))
            .isEqualTo(convertMarkdownToHtmlForExport("hello"))
    }

    @Test fun think_block_becomes_a_toggle_button_and_content() {
        val out = processThinkSections("Before<think>my reasoning</think>After", "agent1")
        assertThat(out).contains("think-btn-agent1-0")
        assertThat(out).contains("Think</button>")
        assertThat(out).contains("class=\"think-content\"")
        assertThat(out).contains("my reasoning")
        assertThat(out).contains("Before")
        assertThat(out).contains("After")
    }

    @Test fun think_content_is_html_escaped() {
        val out = processThinkSections("<think>x < y</think>", "a")
        assertThat(out).contains("x &lt; y")
    }

    // ---- convertMarkdownToHtmlForExport ----

    @Test fun export_headings_shift_down_one_level() {
        assertThat(convertMarkdownToHtmlForExport("# Title")).contains("<h2>Title</h2>")
        assertThat(convertMarkdownToHtmlForExport("## Sub")).contains("<h3>Sub</h3>")
        assertThat(convertMarkdownToHtmlForExport("### Small")).contains("<h4>Small</h4>")
    }

    @Test fun export_inline_code_and_bold() {
        assertThat(convertMarkdownToHtmlForExport("`x`")).contains("<code>x</code>")
        assertThat(convertMarkdownToHtmlForExport("**b**")).contains("<strong>b</strong>")
    }

    @Test fun export_renders_gfm_tables_as_html() {
        val html = convertMarkdownToHtmlForExport("| A | B |\n|---|---|\n| 1 | 2 |")
        assertThat(html).contains("<table class='md-table'>")
        assertThat(html).contains("<td>1</td>")
        assertThat(html).contains("<td>2</td>")
    }

    @Test fun export_escapes_special_characters() {
        val html = convertMarkdownToHtmlForExport("a < b > c")
        assertThat(html).contains("&lt;")
        assertThat(html).contains("&gt;")
    }

    @Test fun literal_table_placeholder_out_of_range_is_left_intact() {
        // No real tables in the text, so MDTBL999 must not crash / index past
        // the (empty) table list — it survives as literal text.
        assertThat(convertMarkdownToHtmlForExport("MDTBL999")).contains("MDTBL999")
    }
}
