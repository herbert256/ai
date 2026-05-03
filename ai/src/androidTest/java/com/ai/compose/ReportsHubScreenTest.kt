package com.ai.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.ui.hub.ReportsHubScreen
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for the Reports hub. Verifies the title bar and the
 * four entry-point cards render, and that the always-enabled "New
 * AI Report" card invokes its callback.
 */
@RunWith(AndroidJUnit4::class)
class ReportsHubScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun renders_title_and_all_four_hub_cards() {
        rule.setContent {
            MaterialTheme {
                ReportsHubScreen(
                    onNavigateBack = {}, onNavigateHome = {},
                    onNavigateToNewReport = {},
                    onNavigateToPromptHistory = {},
                    onNavigateToHistory = {},
                    onNavigateToSearch = {},
                    onNavigateToLocalSearch = {},
                    onNavigateToQuickLocalSearch = {}
                )
            }
        }
        rule.onNodeWithText("AI Reports").assertIsDisplayed()
        rule.onNodeWithText("New AI Report").assertIsDisplayed()
        rule.onNodeWithText("Start with a previous prompt").assertIsDisplayed()
        rule.onNodeWithText("View previous reports").assertIsDisplayed()
        rule.onNodeWithText("Remote semantic search").assertIsDisplayed()
    }

    @Test fun new_report_card_click_invokes_callback() {
        val newReport = mutableIntStateOf(0)
        rule.setContent {
            MaterialTheme {
                ReportsHubScreen(
                    onNavigateBack = {}, onNavigateHome = {},
                    onNavigateToNewReport = { newReport.intValue++ },
                    onNavigateToPromptHistory = {},
                    onNavigateToHistory = {},
                    onNavigateToSearch = {},
                    onNavigateToLocalSearch = {},
                    onNavigateToQuickLocalSearch = {}
                )
            }
        }
        rule.onNodeWithText("New AI Report").performClick()
        assertThat(newReport.intValue).isEqualTo(1)
    }

    @Test fun back_button_invokes_onNavigateBack() {
        val back = mutableIntStateOf(0)
        rule.setContent {
            MaterialTheme {
                ReportsHubScreen(
                    onNavigateBack = { back.intValue++ },
                    onNavigateHome = {},
                    onNavigateToNewReport = {},
                    onNavigateToPromptHistory = {},
                    onNavigateToHistory = {},
                    onNavigateToSearch = {},
                    onNavigateToLocalSearch = {},
                    onNavigateToQuickLocalSearch = {}
                )
            }
        }
        rule.onNodeWithText("< Back").performClick()
        assertThat(back.intValue).isEqualTo(1)
    }
}
