package com.ai.ui.helpers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [ExportLanguage] file-tag / matching-key selectors and [makeStaticForPdf]. */
class ExportLanguageTest {

    @Test fun file_tag_per_selector() {
        assertThat(ExportLanguage.All.fileTag()).isEqualTo("")
        assertThat(ExportLanguage.Original.fileTag()).isEqualTo("_original")
        assertThat(ExportLanguage.Single("French").fileTag()).isEqualTo("_french")
        assertThat(ExportLanguage.Single("English (US)").fileTag()).isEqualTo("_englishus")
    }

    @Test fun matching_key_per_selector() {
        assertThat(ExportLanguage.All.matchingKey()).isNull()
        assertThat(ExportLanguage.Original.matchingKey()).isEqualTo("original")
        assertThat(ExportLanguage.Single("French").matchingKey()).isEqualTo("french")
    }

    @Test fun static_pdf_injects_override_before_head_close() {
        val out = makeStaticForPdf("<html><head><title>T</title></head><body>BODY</body></html>")
        assertThat(out).contains("BODY")
        assertThat(out).contains("view-picker")     // override style is present
        assertThat(out).contains("display: none")
        assertThat(out.indexOf("view-picker")).isLessThan(out.indexOf("</head>"))
    }

    @Test fun static_pdf_appends_when_no_head_tag() {
        val out = makeStaticForPdf("plain content")
        assertThat(out).startsWith("plain content")
        assertThat(out).contains("view-picker")
    }
}
