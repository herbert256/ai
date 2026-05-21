package com.ai.compose

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.ui.hub.ReportsHubScreen
import com.ai.viewmodel.AppViewModel
import com.ai.viewmodel.ReportViewModel
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for the rewritten Reports hub. The screen now shows
 * a title bar, three top buttons (New / Search / All), and four
 * list cards (problems / running / pinned / latest). These tests
 * verify the layout + the New button + the back arrow callback.
 */
@RunWith(AndroidJUnit4::class)
class ReportsHubScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun newReportViewModel(): ReportViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return ReportViewModel(AppViewModel(app))
    }

    @Test fun renders_title_and_top_buttons_and_list_cards() {
        val rvm = newReportViewModel()
        rule.setContent {
            MaterialTheme {
                ReportsHubScreen(
                    onNavigateBack = {}, onNavigateHome = {},
                    onOpenReportManage = {},
                    onOpenReportView = {},
                    onNavigateToNewAiReport = {},
                    onNavigateToSearchAiReports = {},
                    onNavigateToAllReports = {},
                    reportViewModel = rvm
                )
            }
        }
        // Title bar
        rule.onNodeWithText("AI Reports").assertIsDisplayed()
        // Three top buttons
        rule.onNodeWithText("New").assertIsDisplayed()
        rule.onNodeWithText("Search").assertIsDisplayed()
        rule.onNodeWithText("All").assertIsDisplayed()
        // Four list-card labels
        rule.onNodeWithText("AI Reports with problems").assertIsDisplayed()
        rule.onNodeWithText("Running AI reports").assertIsDisplayed()
        rule.onNodeWithText("Pinned AI Reports").assertIsDisplayed()
        rule.onNodeWithText("Latest AI Reports").assertIsDisplayed()
    }

    @Test fun new_button_click_invokes_onNavigateToNewAiReport() {
        val newClicks = mutableIntStateOf(0)
        val rvm = newReportViewModel()
        rule.setContent {
            MaterialTheme {
                ReportsHubScreen(
                    onNavigateBack = {}, onNavigateHome = {},
                    onOpenReportManage = {},
                    onOpenReportView = {},
                    onNavigateToNewAiReport = { newClicks.intValue++ },
                    onNavigateToSearchAiReports = {},
                    onNavigateToAllReports = {},
                    reportViewModel = rvm
                )
            }
        }
        rule.onNodeWithText("New").performClick()
        assertThat(newClicks.intValue).isEqualTo(1)
    }

}
