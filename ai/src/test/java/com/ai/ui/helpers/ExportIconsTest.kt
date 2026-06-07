package com.ai.ui.helpers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [iconPrefixHtml] / [iconPrefixPlain] — the optional "<icon> " prefixes the
 *  exporters prepend unconditionally. */
class ExportIconsTest {

    @Test fun html_prefix_blank_icon_is_empty() {
        assertThat(iconPrefixHtml(null)).isEqualTo("")
        assertThat(iconPrefixHtml("")).isEqualTo("")
        assertThat(iconPrefixHtml("   ")).isEqualTo("")
    }

    @Test fun html_prefix_appends_trailing_space() {
        assertThat(iconPrefixHtml("🎯")).isEqualTo("🎯 ")
    }

    @Test fun html_prefix_escapes_markup() {
        assertThat(iconPrefixHtml("<")).isEqualTo("&lt; ")
        assertThat(iconPrefixHtml("a&b")).isEqualTo("a&amp;b ")
    }

    @Test fun plain_prefix_blank_icon_is_empty() {
        assertThat(iconPrefixPlain(null)).isEqualTo("")
        assertThat(iconPrefixPlain("")).isEqualTo("")
    }

    @Test fun plain_prefix_does_not_escape() {
        assertThat(iconPrefixPlain("🎯")).isEqualTo("🎯 ")
        assertThat(iconPrefixPlain("<")).isEqualTo("< ")
    }
}
