package com.ai.compose

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.ui.hub.ReportsHubScreen
import com.ai.viewmodel.AppViewModel
import com.ai.viewmodel.ReportViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for the rewritten Reports hub. The screen shows a "Reports" title
 * bar and a "Latest AI Reports" list card (a "Pinned AI Reports" card appears
 * only when there are pinned reports). New / Search / All are no longer top
 * buttons on this screen — they live as emoji icons in the shared
 * [com.ai.ui.shared.BottomIconBar], which is bottom-anchored and needs the
 * app-level scaffold context to lay out, so it is not interactable from an
 * isolated screen test; its wiring is exercised where that bar is hosted. This
 * test therefore asserts the hub's own synchronously-rendered content.
 */
@RunWith(AndroidJUnit4::class)
class ReportsHubScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun newReportViewModel(): ReportViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return ReportViewModel(AppViewModel(app))
    }

    @Test fun renders_title_and_latest_card() {
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
        rule.onNodeWithText("Reports").assertIsDisplayed()
        rule.onNodeWithText("Latest AI Reports").assertIsDisplayed()
    }
}
