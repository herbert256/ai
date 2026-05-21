package com.ai.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.ui.shared.TitleBar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for the shared [TitleBar] composable. Validates the title
 * text is rendered. Most other screens build on this — a regression
 * here would silently break navigation. (Back navigation is the
 * Android system gesture via BackHandler, not a visible affordance.)
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
}
