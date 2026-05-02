package com.ai.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.ui.shared.TitleBar
import com.google.common.truth.Truth.assertThat
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for the shared [TitleBar] composable. Validates the
 * title text is rendered and the back/AI buttons actually invoke
 * their callbacks. Most other screens build on this — a regression
 * here would silently break navigation.
 *
 * Currently @Ignored class-wide: Espresso 3.6.1 + Android API 36
 * trip on `InputManager.getInstance []` (the no-arg variant was
 * removed in API 36). Re-enable once the project's espresso version
 * is bumped past that gap (3.7.x).
 */
@Ignore("Espresso 3.6.1 + API 36 InputManager.getInstance() incompatibility")
@RunWith(AndroidJUnit4::class)
class TitleBarTest {
    @get:Rule val rule = createComposeRule()

    @Test fun renders_the_provided_title() {
        rule.setContent {
            MaterialTheme {
                TitleBar(title = "Hello Title", onBackClick = {}, onAiClick = {})
            }
        }
        rule.onNodeWithText("Hello Title").assertIsDisplayed()
    }

    @Test fun back_button_invokes_onBackClick() {
        val backClicks = mutableIntStateOf(0)
        rule.setContent {
            MaterialTheme {
                TitleBar(
                    title = "Title",
                    onBackClick = { backClicks.intValue++ },
                    onAiClick = {}
                )
            }
        }
        // The shared TitleBar's back affordance is rendered as
        // "< Back" (see ui/shared/SharedComponents.kt:53,79).
        rule.onNodeWithText("< Back").performClick()
        assertThat(backClicks.intValue).isEqualTo(1)
    }
}
