package com.ai.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.ui.admin.HousekeepingScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for the Housekeeping screen — the destructive-actions
 * hub. Verifies the title bar, the section labels, and that the
 * "Clear all runtime data" confirmation dialog opens with the
 * expected wording (which now states usage statistics are kept).
 */
@RunWith(AndroidJUnit4::class)
class HousekeepingScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun renders_title_and_section_headers() {
        rule.setContent { MaterialTheme { HousekeepingScreen(onBackToHome = {}) } }

        rule.onNodeWithText("Housekeeping").assertIsDisplayed()
        rule.onNodeWithText("Configuration").assertIsDisplayed()
        rule.onNodeWithText("Backup & Restore").assertIsDisplayed()
        rule.onNodeWithText("Trim by age").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Usage statistics").performScrollTo().assertIsDisplayed()
    }

    @Test fun clear_all_runtime_data_dialog_states_usage_stats_are_kept() {
        rule.setContent { MaterialTheme { HousekeepingScreen(onBackToHome = {}) } }

        // Bottom of the screen — scroll the button into view first.
        rule.onAllNodesWithText("Clear all runtime data").onFirst().performScrollTo().performClick()

        rule.onNodeWithText("Clear all runtime data?").assertIsDisplayed()
        // Per the recent change, the wording explicitly excludes usage
        // statistics from the wipe — guard that promise here.
        rule.onNodeWithText(
            "This permanently deletes all reports, chat history, API traces, and the prompt history. " +
                "Configuration (providers, agents, flocks, swarms, prompts, parameters, API keys) " +
                "and usage statistics are kept."
        ).assertIsDisplayed()
    }
}
