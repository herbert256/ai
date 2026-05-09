package com.ai.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
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

    @Test fun renders_title_and_all_eight_navcards() {
        rule.setContent { MaterialTheme { HousekeepingScreen(onBackToHome = {}) } }

        rule.onNodeWithText("Housekeeping").assertIsDisplayed()
        rule.onNodeWithText("Backup & Restore").assertIsDisplayed()
        rule.onNodeWithText("Export & Import").assertIsDisplayed()
        rule.onNodeWithText("Refresh").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Trim by age").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Usage statistics").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Manual cost overrides").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Internal prompts").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Reset").performScrollTo().assertIsDisplayed()
    }
}
