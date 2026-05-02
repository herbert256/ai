package com.ai.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.ui.report.ContentWithThinkSections
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for [ContentWithThinkSections] — the renderer that
 * splits a model response into plain content + collapsible
 * <think>...</think> reasoning blocks. Used by every per-model
 * result viewer; a regression here would break report display.
 */
@RunWith(AndroidJUnit4::class)
class ContentDisplayTest {
    @get:Rule val rule = createComposeRule()

    @Test fun renders_plain_text_when_no_think_section() {
        rule.setContent {
            MaterialTheme {
                ContentWithThinkSections(analysis = "The capital of France is Paris.")
            }
        }
        rule.onNodeWithText("The capital of France is Paris.").assertIsDisplayed()
    }

    @Test fun think_section_starts_collapsed_with_Think_button_and_post_text_visible() {
        rule.setContent {
            MaterialTheme {
                ContentWithThinkSections(
                    analysis = "<think>let me reason about this</think>The answer is 42."
                )
            }
        }
        rule.onNodeWithText("Think").assertIsDisplayed()
        rule.onNodeWithText("The answer is 42.").assertIsDisplayed()
        // Inner reasoning is hidden until the toggle is clicked.
        rule.onNodeWithText("let me reason about this").assertDoesNotExist()
    }

    @Test fun think_section_expands_on_click_and_button_label_flips() {
        rule.setContent {
            MaterialTheme {
                ContentWithThinkSections(
                    analysis = "<think>step one\nstep two</think>Final answer."
                )
            }
        }
        rule.onNodeWithText("Think").performClick()
        rule.onNodeWithText("Hide Think").assertIsDisplayed()
        rule.onNodeWithText("step one\nstep two").assertIsDisplayed()
        rule.onNodeWithText("Final answer.").assertIsDisplayed()
    }
}
