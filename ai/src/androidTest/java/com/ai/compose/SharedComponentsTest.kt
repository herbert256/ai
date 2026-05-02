package com.ai.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.ui.shared.CollapsibleCard
import com.ai.ui.shared.DeleteConfirmationDialog
import com.ai.ui.shared.HubCard
import com.ai.ui.shared.SettingsListItemCard
import com.ai.ui.shared.VisionBadge
import com.ai.ui.shared.WebSearchBadge
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for the public composables in shared/SharedComponents.
 * These widgets are reused across nearly every screen, so a regression
 * here would cascade — these tests catch import / signature breakage
 * after Compose BOM bumps and similar toolchain changes.
 */
@RunWith(AndroidJUnit4::class)
class SharedComponentsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun visionBadge_shows_eye_emoji_when_capable() {
        rule.setContent { MaterialTheme { VisionBadge(isVisionCapable = true) } }
        rule.onNodeWithText("👁").assertIsDisplayed()
    }

    @Test fun visionBadge_renders_nothing_when_not_capable() {
        rule.setContent { MaterialTheme { VisionBadge(isVisionCapable = false) } }
        rule.onNodeWithText("👁").assertDoesNotExist()
    }

    @Test fun webSearchBadge_shows_globe_emoji_when_capable() {
        rule.setContent { MaterialTheme { WebSearchBadge(isWebSearchCapable = true) } }
        rule.onNodeWithText("🌐").assertIsDisplayed()
    }

    @Test fun webSearchBadge_renders_nothing_when_not_capable() {
        rule.setContent { MaterialTheme { WebSearchBadge(isWebSearchCapable = false) } }
        rule.onNodeWithText("🌐").assertDoesNotExist()
    }

    @Test fun deleteConfirmationDialog_shows_entity_type_and_name() {
        rule.setContent {
            MaterialTheme {
                DeleteConfirmationDialog(
                    entityType = "Agent", entityName = "GPT-4 helper",
                    onConfirm = {}, onDismiss = {}
                )
            }
        }
        rule.onNodeWithText("Delete Agent").assertIsDisplayed()
        rule.onNodeWithText("Are you sure you want to delete \"GPT-4 helper\"?").assertIsDisplayed()
        rule.onNodeWithText("Delete").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test fun deleteConfirmationDialog_confirm_and_cancel_invoke_separate_callbacks() {
        val confirmed = mutableIntStateOf(0)
        val dismissed = mutableIntStateOf(0)
        rule.setContent {
            MaterialTheme {
                DeleteConfirmationDialog(
                    entityType = "Flock", entityName = "Coding crew",
                    onConfirm = { confirmed.intValue++ },
                    onDismiss = { dismissed.intValue++ }
                )
            }
        }
        rule.onNodeWithText("Delete").performClick()
        assertThat(confirmed.intValue).isEqualTo(1)
        assertThat(dismissed.intValue).isEqualTo(0)

        rule.onNodeWithText("Cancel").performClick()
        assertThat(dismissed.intValue).isEqualTo(1)
    }

    @Test fun settingsListItemCard_renders_all_three_lines_when_extra_supplied() {
        rule.setContent {
            MaterialTheme {
                SettingsListItemCard(
                    title = "My agent",
                    subtitle = "OpenAI / gpt-4o",
                    extraLine = "temp 0.7",
                    onClick = {}, onDelete = {}
                )
            }
        }
        rule.onNodeWithText("My agent").assertIsDisplayed()
        rule.onNodeWithText("OpenAI / gpt-4o").assertIsDisplayed()
        rule.onNodeWithText("temp 0.7").assertIsDisplayed()
    }

    @Test fun settingsListItemCard_click_and_delete_fire_distinct_callbacks() {
        val clicked = mutableIntStateOf(0)
        val deleted = mutableIntStateOf(0)
        rule.setContent {
            MaterialTheme {
                SettingsListItemCard(
                    title = "Row title", subtitle = "row sub",
                    onClick = { clicked.intValue++ },
                    onDelete = { deleted.intValue++ }
                )
            }
        }
        // Card body click — touch the title text.
        rule.onNodeWithText("Row title").performClick()
        assertThat(clicked.intValue).isEqualTo(1)

        // Delete affordance is rendered as the literal "X" label.
        rule.onNodeWithText("X").performClick()
        assertThat(deleted.intValue).isEqualTo(1)
        assertThat(clicked.intValue).isEqualTo(1)
    }

    @Test fun collapsibleCard_collapsed_by_default_shows_summary_and_hides_content() {
        rule.setContent {
            MaterialTheme {
                Column {
                    CollapsibleCard(title = "Section", summary = "3 items") {
                        Text("inner content body")
                    }
                }
            }
        }
        rule.onNodeWithText("Section").assertIsDisplayed()
        rule.onNodeWithText("3 items").assertIsDisplayed()
        rule.onNodeWithText("inner content body").assertDoesNotExist()
    }

    @Test fun collapsibleCard_click_expands_to_show_content_and_hide_summary() {
        rule.setContent {
            MaterialTheme {
                Column {
                    CollapsibleCard(title = "Section", summary = "3 items") {
                        Text("inner content body")
                    }
                }
            }
        }
        rule.onNodeWithText("Section").performClick()
        rule.onNodeWithText("inner content body").assertIsDisplayed()
        // Summary disappears once expanded (per the production behavior).
        rule.onNodeWithText("3 items").assertDoesNotExist()
    }

    @Test fun hubCard_shows_title_and_description_and_fires_onClick() {
        val clicked = mutableIntStateOf(0)
        rule.setContent {
            MaterialTheme {
                HubCard(
                    title = "AI Reports", description = "Generate parallel reports",
                    onClick = { clicked.intValue++ }
                )
            }
        }
        rule.onNodeWithText("AI Reports").assertIsDisplayed()
        rule.onNodeWithText("Generate parallel reports").assertIsDisplayed()
        rule.onNodeWithText("AI Reports").performClick()
        assertThat(clicked.intValue).isEqualTo(1)
    }
}
