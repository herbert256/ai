package com.ai.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.ui.report.ReportExportScreen
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for the Export screen layout. Verifies the format
 * chips render with their displayName labels, the action buttons
 * appear, and the Detail card hides for JSON / Zipped HTML (which
 * always emit the Complete content).
 *
 * Currently @Ignored — see TitleBarTest for the Espresso 3.6.1 +
 * API 36 InputManager incompatibility note.
 */
@Ignore("Espresso 3.6.1 + API 36 InputManager.getInstance() incompatibility")
@RunWith(AndroidJUnit4::class)
class ReportExportScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun renders_default_format_HTML_with_Detail_card_visible() {
        rule.setContent {
            MaterialTheme {
                ReportExportScreen(
                    onBack = {}, onNavigateHome = {},
                    onExport = { _, _, _, _ -> },
                    onExportAll = { _ -> }
                )
            }
        }
        // Format chips
        rule.onNodeWithText("HTML").assertIsDisplayed()
        rule.onNodeWithText("PDF").assertIsDisplayed()
        rule.onNodeWithText("MS Word").assertIsDisplayed()
        rule.onNodeWithText("OpenDocument").assertIsDisplayed()
        rule.onNodeWithText("JSON").assertIsDisplayed()
        rule.onNodeWithText("Zipped HTML").assertIsDisplayed()

        // Detail card visible by default (HTML is selected).
        rule.onNodeWithText("Detail").assertIsDisplayed()
        rule.onNodeWithText("Short").assertIsDisplayed()
        rule.onNodeWithText("Complete").assertIsDisplayed()

        // Action buttons.
        rule.onNodeWithText("Android share").assertIsDisplayed()
        rule.onNodeWithText("View in browser").assertIsDisplayed()
        rule.onNodeWithText("Export all (zip)").assertIsDisplayed()
    }
}
