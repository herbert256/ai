package com.ai.ui.report

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReportMarkdownExportTest {
    @Test fun convertMarkdownToHtmlForExport_handles_headings_lists_code_and_escaping() {
        val html = convertMarkdownToHtmlForExport(
            """
            # Title & Things

            - **bold**
            - `code`

            ```kotlin
            val x = "<unsafe>"
            ```
            """.trimIndent()
        )

        assertThat(html).contains("<h2>Title &amp; Things</h2>")
        assertThat(html).contains("<li><strong>bold</strong></li>")
        assertThat(html).contains("<li><code>code</code></li>")
        assertThat(html).contains("&lt;unsafe&gt;")
    }

    @Test fun processThinkSections_keeps_thinking_collapsed_and_renders_surrounding_markdown() {
        val html = processThinkSections("Before **answer**\n<think>private <plan></think>\nAfter", "agent'1")

        assertThat(html).contains("<strong>answer</strong>")
        assertThat(html).contains("think-btn-agent1-0")
        assertThat(html).contains("private &lt;plan&gt;")
        assertThat(html).contains("After")
    }
}
