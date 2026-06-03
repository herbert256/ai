package com.ai.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.ui.admin.HousekeepingScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for the Housekeeping screen. Each row is a NavCard that
 * drills into its own full screen — the per-card destructive-action
 * dialogs and their wording moved to the deeper screens
 * (ResetScreen, BackupRestoreScreen, etc.) and have their own tests.
 */
@RunWith(AndroidJUnit4::class)
class HousekeepingScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun renders_title_and_all_navcards() {
        // Default params → hasActiveProvider = hasTrimmable = true, so
        // every card shows. "Application log" is no longer here (it moved
        // to the Monitor hub).
        rule.setContent { MaterialTheme { HousekeepingScreen(onBackToHome = {}) } }

        rule.onNodeWithText("Housekeeping").assertIsDisplayed()
        rule.onNodeWithText("Backup & Restore").assertIsDisplayed()
        rule.onNodeWithText("Export & Import").assertIsDisplayed()
        // The cards live in a LazyColumn — scroll the list to each lower
        // card. performScrollToNode is the lazy-list-correct API; a plain
        // performScrollTo on the item doesn't reliably reach one below the
        // fold (which is why "Reset", the last card, was flaky).
        val list = rule.onNode(hasScrollAction())
        for (label in listOf("Trim by age", "Update from cloud", "Costs", "Test", "Refresh", "Reset")) {
            list.performScrollToNode(hasText(label))
            rule.onNodeWithText(label).assertIsDisplayed()
        }
    }
}
