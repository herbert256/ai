package com.ai.ui.helpers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [colorizedJsonHtml] — the JSON syntax highlighter for the zipped-HTML trace
 *  viewer. Returns null for anything that isn't a JSON object/array. */
class ColorizedJsonHtmlTest {

    @Test fun non_json_returns_null() {
        assertThat(colorizedJsonHtml("hello")).isNull()
        assertThat(colorizedJsonHtml("")).isNull()
        assertThat(colorizedJsonHtml("42")).isNull()        // doesn't start with { or [
    }

    @Test fun invalid_json_returns_null() {
        assertThat(colorizedJsonHtml("{not valid")).isNull()
    }

    @Test fun object_values_get_typed_spans() {
        val html = colorizedJsonHtml("{\"a\":\"x\",\"n\":5,\"b\":true,\"z\":null}")!!
        assertThat(html).contains("j-str")
        assertThat(html).contains("j-num")
        assertThat(html).contains("j-bool")
        assertThat(html).contains("j-null")
    }

    @Test fun empty_array_renders() {
        assertThat(colorizedJsonHtml("[]")).contains("[]")
    }

    @Test fun string_value_is_escaped() {
        val html = colorizedJsonHtml("{\"k\":\"a<b\"}")!!
        assertThat(html).contains("&lt;")
    }
}
