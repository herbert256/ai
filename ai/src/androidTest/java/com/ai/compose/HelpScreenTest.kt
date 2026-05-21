package com.ai.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
        // "Help" appears multiple times — the TitleBar title and a
        // few rows in the legend / footer — so target the first match.
        rule.onAllNodesWithText("Help")[0].assertIsDisplayed()
        // Sections currently rendered by CompactOverview in
        // HelpScreen.kt: "Welcome" near the top, "Per-screen help"
        // below it, and "Copyright" pinned near the foot.
        rule.onNodeWithText("Welcome").assertIsDisplayed()
        rule.onNodeWithText("Per-screen help").assertIsDisplayed()
        rule.onNodeWithText("Copyright").performScrollTo().assertIsDisplayed()
    }

    @Test fun home_button_invokes_navigate_home() {
        // HelpScreen's own onNavigateHome param is vestigial — the
        // AI brand glyph in TitleBar routes through LocalNavigateHome,
        // which the harness wires. It's an Image with
        // contentDescription = "Home" (was a 🏠 emoji on an older UI).
        val homeClicks = mutableIntStateOf(0)
        rule.setContent {
            WithBottomBar(onNavigateHome = { homeClicks.intValue++ }) {
                HelpScreen(onBack = {}, onNavigateHome = {})
            }
        }
        rule.onNodeWithContentDescription("Home").performClick()
        assertThat(homeClicks.intValue).isEqualTo(1)
    }
}
