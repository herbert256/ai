package com.ai.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.data.ApiTracer
import com.ai.data.MetadataDefaults
import com.ai.ui.hub.FirstLaunchScreen
import com.ai.ui.shared.HomeIconBar
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeBarModeScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun homeIconBar_invokes_all_actions() {
        val clicked = mutableStateOf("")
        val oldTracing = ApiTracer.isTracingEnabled
        val oldLadybugs = ApiTracer.showLadybugIcons
        ApiTracer.isTracingEnabled = true
        ApiTracer.showLadybugIcons = true
        try {
            rule.setContent {
                MaterialTheme {
                    HomeIconBar(
                        icons = null,
                        onReports = { clicked.value = "reports" },
                        onChat = { clicked.value = "chat" },
                        onMonitor = { clicked.value = "monitor" },
                        onSetup = { clicked.value = "setup" },
                        onHousekeeping = { clicked.value = "housekeeping" },
                        onSettings = { clicked.value = "settings" },
                        onTraceFallback = { clicked.value = "trace" },
                        onHelpFallback = { clicked.value = "help" },
                        onAbout = { clicked.value = "about" },
                        onAppLog = { clicked.value = "applog" }
                    )
                }
            }

            rule.onNodeWithText(MetadataDefaults.REPORT_ICON).performClick()
            assertThat(clicked.value).isEqualTo("reports")
            rule.onNodeWithText(MetadataDefaults.CHAT).performClick()
            assertThat(clicked.value).isEqualTo("chat")
            rule.onNodeWithText(MetadataDefaults.LIVE_DASHBOARD).performClick()
            assertThat(clicked.value).isEqualTo("monitor")
            rule.onNodeWithText(MetadataDefaults.AGENT).performClick()
            assertThat(clicked.value).isEqualTo("setup")
            rule.onNodeWithText(MetadataDefaults.HOUSEKEEPING).performClick()
            assertThat(clicked.value).isEqualTo("housekeeping")
            rule.onNodeWithText(MetadataDefaults.SETTINGS).performClick()
            assertThat(clicked.value).isEqualTo("settings")
            rule.onNodeWithText(MetadataDefaults.TRACES).performClick()
            assertThat(clicked.value).isEqualTo("trace")
            rule.onNodeWithText(MetadataDefaults.HELP).performClick()
            assertThat(clicked.value).isEqualTo("help")
            rule.onNodeWithContentDescription("About").performClick()
            assertThat(clicked.value).isEqualTo("about")
        } finally {
            ApiTracer.isTracingEnabled = oldTracing
            ApiTracer.showLadybugIcons = oldLadybugs
        }
    }

    @Test fun firstLaunchScreen_shows_required_cards() {
        rule.setContent {
            MaterialTheme {
                FirstLaunchScreen(
                    onImportApiKeys = {},
                    onAiSetup = {},
                    onExampleReports = {},
                    onHousekeeping = {},
                    onSettings = {},
                    onMainHelp = {},
                    onAbout = {}
                )
            }
        }

        rule.onNodeWithText("First launch").assertIsDisplayed()
        rule.onNodeWithText("Import API keys").assertIsDisplayed()
        rule.onNodeWithText("AI Setup").assertIsDisplayed()
        rule.onNodeWithText("Example reports").assertIsDisplayed()
        rule.onNodeWithText("Housekeeping").assertIsDisplayed()
        rule.onNodeWithText("Settings").assertIsDisplayed()
        rule.onNodeWithText("Help").assertIsDisplayed()
        rule.onNodeWithText("About").assertIsDisplayed()
    }
}
