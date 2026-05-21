package com.ai.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.ui.report.view.TranslationCompareScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for the original/translation split overlay. Verifies
 * the title is rendered, both pane labels appear, and both content
 * blocks are visible.
 */
@RunWith(AndroidJUnit4::class)
class TranslationCompareScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun renders_title_and_both_pane_contents() {
        rule.setContent {
            MaterialTheme {
                TranslationCompareScreen(
                    title = "Translation info",
                    originalLabel = "Original",
                    originalContent = "Hello world!",
                    translatedLabel = "Translation",
                    translatedContent = "Hallo wereld!",
                    onBack = {},
                    onNavigateHome = {}
                )
            }
        }
        rule.onNodeWithText("Translation info").assertIsDisplayed()
        rule.onNodeWithText("Original").assertIsDisplayed()
        rule.onNodeWithText("Translation").assertIsDisplayed()
        rule.onNodeWithText("Hello world!").assertIsDisplayed()
        rule.onNodeWithText("Hallo wereld!").assertIsDisplayed()
    }

    @Test fun shows_no_content_placeholder_when_a_pane_is_blank() {
        rule.setContent {
            MaterialTheme {
                TranslationCompareScreen(
                    title = "Translation info",
                    originalLabel = "Original", originalContent = "",
                    translatedLabel = "Translation", translatedContent = "Hallo!",
                    onBack = {}, onNavigateHome = {}
                )
            }
        }
        rule.onNodeWithText("(no content)").assertIsDisplayed()
        rule.onNodeWithText("Hallo!").assertIsDisplayed()
    }
}
