package com.ai.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.ui.admin.HelpScreen
import com.ai.util.WithBottomBar
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for the in-app Help screen. Renders a long static
 * scrolling list of HelpSection cards; we verify the title bar is
 * up, a representative subset of section headings render, and the
 * back / Home affordances (painted by the global BottomIconBar)
 * fire their callbacks.
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
        // "Help" appears twice — the TitleBar title and the "❓ Help"
        // row in the icon-legend card — so target the first match.
        rule.onAllNodesWithText("Help")[0].assertIsDisplayed()
        rule.onNodeWithText("Welcome").assertIsDisplayed()
        rule.onNodeWithText("Getting started").assertIsDisplayed()
        // "Privacy" sits well below the fold (after the full
        // cloud-provider directory) — scroll it into view first.
        rule.onNodeWithText("Privacy").performScrollTo().assertIsDisplayed()
    }

    @Test fun back_button_invokes_onBack() {
        val backClicks = mutableIntStateOf(0)
        rule.setContent {
            WithBottomBar {
                HelpScreen(onBack = { backClicks.intValue++ }, onNavigateHome = {})
            }
        }
        rule.onNodeWithText("←").performClick()
        assertThat(backClicks.intValue).isEqualTo(1)
    }

    @Test fun home_button_invokes_navigate_home() {
        // HelpScreen's own onNavigateHome param is vestigial — the
        // 🏠 button (in BottomIconBar) routes through LocalNavigateHome,
        // which the harness wires.
        val homeClicks = mutableIntStateOf(0)
        rule.setContent {
            WithBottomBar(onNavigateHome = { homeClicks.intValue++ }) {
                HelpScreen(onBack = {}, onNavigateHome = {})
            }
        }
        // "🏠" also appears as a passive row in HelpScreen's icon-legend
        // table — the clickable one is the BottomIconBar's Home button.
        rule.onAllNodesWithText("🏠").filterToOne(hasClickAction()).performClick()
        assertThat(homeClicks.intValue).isEqualTo(1)
    }
}
