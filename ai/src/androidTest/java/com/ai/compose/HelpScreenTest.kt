package com.ai.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.ui.admin.HelpScreen
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for the in-app Help screen. Renders a long static
 * scrolling list of HelpSection cards; we verify the title bar is
 * up, a representative subset of section headings render, and
 * back navigation fires.
 */
@RunWith(AndroidJUnit4::class)
class HelpScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun renders_title_and_a_handful_of_help_sections() {
        rule.setContent {
            MaterialTheme {
                HelpScreen(onBack = {}, onNavigateHome = {})
            }
        }
        rule.onNodeWithText("Help").assertIsDisplayed()
        rule.onNodeWithText("Welcome").assertIsDisplayed()
        rule.onNodeWithText("Getting Started").assertIsDisplayed()
        rule.onNodeWithText("AI Reports").assertIsDisplayed()
    }

    @Test fun back_button_invokes_onBack() {
        val backClicks = mutableIntStateOf(0)
        rule.setContent {
            MaterialTheme {
                HelpScreen(onBack = { backClicks.intValue++ }, onNavigateHome = {})
            }
        }
        rule.onNodeWithText("< Back").performClick()
        assertThat(backClicks.intValue).isEqualTo(1)
    }

    @Test fun ai_button_invokes_onNavigateHome() {
        val homeClicks = mutableIntStateOf(0)
        rule.setContent {
            MaterialTheme {
                HelpScreen(onBack = {}, onNavigateHome = { homeClicks.intValue++ })
            }
        }
        rule.onNodeWithText("AI").performClick()
        assertThat(homeClicks.intValue).isEqualTo(1)
    }
}
