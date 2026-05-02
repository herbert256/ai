package com.ai.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.ui.report.TranslationCompareScreen
import com.google.common.truth.Truth.assertThat
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for the original/translation split overlay. Verifies
 * the title is rendered, both pane labels appear, both content
 * blocks are visible, and the back-button callback fires.
 *
 * Currently @Ignored — see TitleBarTest for the Espresso 3.6.1 +
 * API 36 InputManager incompatibility note.
 */
@Ignore("Espresso 3.6.1 + API 36 InputManager.getInstance() incompatibility")
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

    @Test fun back_button_invokes_callback() {
        val backClicks = mutableIntStateOf(0)
        rule.setContent {
            MaterialTheme {
                TranslationCompareScreen(
                    title = "Translation info",
                    originalLabel = "O", originalContent = "x",
                    translatedLabel = "T", translatedContent = "y",
                    onBack = { backClicks.intValue++ },
                    onNavigateHome = {}
                )
            }
        }
        rule.onNodeWithText("< Back").performClick()
        assertThat(backClicks.intValue).isEqualTo(1)
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
