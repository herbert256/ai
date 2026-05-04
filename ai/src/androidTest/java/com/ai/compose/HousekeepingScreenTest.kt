package com.ai.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
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
        rule.onNodeWithText("Backup & Restore").assertIsDisplayed()
        rule.onNodeWithText("Export & Import").assertIsDisplayed()
        rule.onNodeWithText("Trim by age").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Usage statistics").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Reset").performScrollTo().assertIsDisplayed()
    }

    @Test fun clear_all_runtime_data_dialog_states_usage_stats_are_kept() {
        rule.setContent { MaterialTheme { HousekeepingScreen(onBackToHome = {}) } }

        // Cards are collapsed by default — expand "Reset" first so
        // the Clear-all-runtime-data button enters composition.
        rule.onNodeWithText("Reset").performScrollTo().performClick()
        // Bottom of the screen — scroll the button into view first.
        rule.onAllNodesWithText("Clear all runtime data").onFirst().performScrollTo().performClick()

        rule.onNodeWithText("Clear all runtime data?").assertIsDisplayed()
        // The wording explicitly carves out configuration (providers /
        // agents / API keys etc.) and usage statistics from the wipe —
        // guard those two promises against accidental drift via
        // substring matches so the test isn't tied to exact phrasing.
        rule.onNode(hasText("and usage statistics are kept", substring = true))
            .assertIsDisplayed()
        rule.onNode(
            hasText(
                "Configuration (providers, agents, flocks, swarms, prompts, parameters, API keys)",
                substring = true
            )
        ).assertIsDisplayed()
    }
}
