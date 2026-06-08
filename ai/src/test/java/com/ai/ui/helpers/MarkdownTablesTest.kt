package com.ai.ui.helpers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** GFM-table detection + export rendering used by the in-app renderer and the
 *  HTML/PDF exporters. */
class MarkdownTablesTest {

    @Test fun strip_inline_markdown_removes_bold_italic_code() {
        assertThat(stripInlineMarkdown("**bold**")).isEqualTo("bold")
        assertThat(stripInlineMarkdown("*italic*")).isEqualTo("italic")
        assertThat(stripInlineMarkdown("`code`")).isEqualTo("code")
        assertThat(stripInlineMarkdown("**a** and *b* and `c`")).isEqualTo("a and b and c")
    }

    @Test fun strip_inline_markdown_leaves_plain_text() {
        assertThat(stripInlineMarkdown("nothing to strip")).isEqualTo("nothing to strip")
    }

    @Test fun no_table_returns_empty_list() {
        val (_, tables) = parseGfmTables("just a paragraph\nand another line")
        assertThat(tables).isEmpty()
    }

    @Test fun parses_a_simple_table_and_emits_a_placeholder() {
        val md = "| A | B |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |"
        val (text, tables) = parseGfmTables(md)
        assertThat(tables).hasSize(1)
        assertThat(tables[0].headers).containsExactly("A", "B").inOrder()
        assertThat(tables[0].rows[0]).containsExactly("1", "2").inOrder()
        assertThat(tables[0].rows[1]).containsExactly("3", "4").inOrder()
        assertThat(text).contains("MDTBL0")
    }

    @Test fun parses_column_alignments() {
        val md = "| L | C | R |\n|:---|:---:|---:|\n| a | b | c |"
        val t = parseGfmTables(md).second.single()
        assertThat(t.alignments).containsExactly(TableAlign.LEFT, TableAlign.CENTER, TableAlign.RIGHT).inOrder()
    }

    @Test fun header_and_separator_without_body_is_not_a_table() {
        val md = "| A | B |\n| --- | --- |"
        assertThat(parseGfmTables(md).second).isEmpty()
    }

    @Test fun ragged_rows_are_padded_and_truncated_to_header_width() {
        // A line needs >1 cell to count as a table row (so prose lines with a
        // stray pipe aren't swallowed), so the short row here is 2 cells. Short
        // rows pad with empty cells to header width; over-long rows truncate.
        val md = "| A | B | C |\n|---|---|---|\n| 1 | 2 |\n| 1 | 2 | 3 | 4 |"
        val t = parseGfmTables(md).second.single()
        assertThat(t.rows[0]).containsExactly("1", "2", "").inOrder()
        assertThat(t.rows[1]).containsExactly("1", "2", "3").inOrder()
    }

    @Test fun export_html_escapes_and_renders_inline_markup() {
        val t = MarkdownTable(
            headers = listOf("H<x>"),
            rows = listOf(listOf("**b**"), listOf("`c`")),
            alignments = listOf(TableAlign.CENTER)
        )
        val html = buildExportTableHtml(t)
        assertThat(html).contains("class='md-table'")
        assertThat(html).contains("H&lt;x&gt;")
        assertThat(html).contains("text-align:center")
        assertThat(html).contains("<strong>b</strong>")
        assertThat(html).contains("<code>c</code>")
    }
}
