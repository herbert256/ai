package com.ai.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.ui.shared.TitleBar
import com.ai.util.WithBottomBar
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for the shared [TitleBar] composable. Validates the
 * title text is rendered and the back button actually invokes its
 * callback. Most other screens build on this — a regression here
 * would silently break navigation.
 */
@RunWith(AndroidJUnit4::class)
class TitleBarTest {
    @get:Rule val rule = createComposeRule()

    @Test fun renders_the_provided_title() {
        rule.setContent {
            MaterialTheme {
                TitleBar(title = "Hello Title", onBackClick = {})
            }
        }
        rule.onNodeWithText("Hello Title").assertIsDisplayed()
    }

    @Test fun back_button_invokes_onBackClick() {
        val backClicks = mutableIntStateOf(0)
        rule.setContent {
            WithBottomBar {
                TitleBar(
                    title = "Title",
                    onBackClick = { backClicks.intValue++ }
                )
            }
        }
        // TitleBar publishes onBack into LocalBottomIconState; the
        // global BottomIconBar paints it as the "←" arrow.
        rule.onNodeWithText("←").performClick()
        assertThat(backClicks.intValue).isEqualTo(1)
    }
}
