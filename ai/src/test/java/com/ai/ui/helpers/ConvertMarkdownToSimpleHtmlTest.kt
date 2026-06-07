package com.ai.ui.helpers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [convertMarkdownToSimpleHtml] — the lightweight markdown→HTML used by the
 *  think-section / export preview paths. */
class ConvertMarkdownToSimpleHtmlTest {

    @Test fun headings() {
        assertThat(convertMarkdownToSimpleHtml("# Title")).contains("<h1>Title</h1>")
        assertThat(convertMarkdownToSimpleHtml("## Sub")).contains("<h2>Sub</h2>")
        assertThat(convertMarkdownToSimpleHtml("### Small")).contains("<h3>Small</h3>")
    }

    @Test fun bold_and_italic() {
        assertThat(convertMarkdownToSimpleHtml("**bold**")).contains("<strong>bold</strong>")
        assertThat(convertMarkdownToSimpleHtml("*em*")).contains("<em>em</em>")
    }

    @Test fun bullets_become_a_list() {
        val html = convertMarkdownToSimpleHtml("- one\n- two")
        assertThat(html).contains("<ul>")
        assertThat(html).contains("<li>one</li>")
        assertThat(html).contains("<li>two</li>")
    }

    @Test fun numbered_items_become_list_items() {
        assertThat(convertMarkdownToSimpleHtml("1. first")).contains("<li>first</li>")
    }

    @Test fun special_characters_are_escaped() {
        val html = convertMarkdownToSimpleHtml("x < y & z")
        assertThat(html).contains("&lt;")
        assertThat(html).contains("&amp;")
    }

    @Test fun double_newline_splits_paragraphs() {
        assertThat(convertMarkdownToSimpleHtml("p1\n\np2")).contains("</p><p>")
    }

    @Test fun single_newline_becomes_break() {
        assertThat(convertMarkdownToSimpleHtml("a\nb")).contains("<br>")
    }

    @Test fun blank_input_returns_empty() {
        assertThat(convertMarkdownToSimpleHtml("")).isEqualTo("")
    }

    @Test fun plain_paragraph_is_wrapped() {
        assertThat(convertMarkdownToSimpleHtml("hello")).isEqualTo("<p>hello</p>")
    }
}
