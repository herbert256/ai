package com.ai.ui.report.manage.view

import com.ai.data.AppService
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContentDisplayHelpersTest {
    @Test
    fun buildLangTabsIncludesOriginalAndDistinctTranslationLanguagesInOrder() {
        val tabs = buildLangTabs(
            listOf(
                translate("Dutch", "Nederlands"),
                translate("German", "Deutsch"),
                translate("Dutch", "Nederlands duplicate"),
                translate("", "Ignored"),
                translate(null, "Ignored")
            )
        )

        assertThat(tabs).containsExactly(
            LangTab(LangTab.ORIGINAL_KEY, "Original", null),
            LangTab("dutch", "Dutch", "Nederlands"),
            LangTab("german", "German", "Deutsch")
        ).inOrder()
    }

    @Test
    fun buildLangTabsCanOmitOriginalAndFoldOriginalAlias() {
        val tabs = buildLangTabs(
            translates = listOf(
                translate("English", "English"),
                translate("Spanish", "Espanol")
            ),
            includeOriginal = false,
            originalAlias = "English"
        )

        assertThat(tabs).containsExactly(
            LangTab("spanish", "Spanish", "Espanol")
        )
    }

    @Test
    fun buildLangTabsNormalizesKeysAndDropsNativeNameWhenItMatchesDisplayName() {
        val tabs = buildLangTabs(
            listOf(
                translate("Portuguese (Brazil)", "Portugues do Brasil"),
                translate("English", "English")
            ),
            includeOriginal = false
        )

        assertThat(tabs).containsExactly(
            LangTab("portuguesebrazil", "Portuguese (Brazil)", "Portugues do Brasil"),
            LangTab("english", "English", null)
        ).inOrder()
    }

    @Test
    fun extractTagContentReturnsTrimmedFirstMatchAcrossLines() {
        val body = """
            intro
            <conclusion>
              First line
              second line
            </conclusion>
            <conclusion>ignored</conclusion>
        """.trimIndent()

        assertThat(extractTagContent(body, "conclusion")).isEqualTo("First line\n  second line")
    }

    @Test
    fun extractTagContentReturnsNullForMissingOrEmptyTags() {
        assertThat(extractTagContent("plain text", "conclusion")).isNull()
        assertThat(extractTagContent("<conclusion>   </conclusion>", "conclusion")).isNull()
    }

    private fun translate(language: String?, native: String?) = SecondaryResult(
        id = "row-${language ?: "none"}-${native ?: "none"}",
        reportId = "report-1",
        kind = SecondaryKind.TRANSLATE,
        providerId = AppService.LOCAL.id,
        model = "translator",
        agentName = "Translator",
        timestamp = 123L,
        content = "translated",
        targetLanguage = language,
        targetLanguageNative = native
    )
}
