package com.ai.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.model.Settings
import com.ai.ui.chat.ChatsHubScreen
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for the Chats hub. Verifies the entry cards render
 * with the empty default Settings (no agents → "New Chat with
 * Agent" disabled but still rendered) and that always-enabled
 * "New Chat - Configure On The Fly" fires its callback.
 */
@RunWith(AndroidJUnit4::class)
class ChatsHubScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun renders_title_and_all_chat_entry_cards() {
        rule.setContent {
            MaterialTheme {
                ChatsHubScreen(
                    aiSettings = Settings(),
                    onNavigateBack = {}, onNavigateHome = {},
                    onNavigateToAgentSelect = {},
                    onNavigateToNewChat = {},
                    onNavigateToChatHistory = {},
                    onNavigateToChatSearch = {},
                    onNavigateToDualChat = {}
                )
            }
        }
        rule.onNodeWithText("AI Chat").assertIsDisplayed()
        rule.onNodeWithText("New Chat with Agent").assertIsDisplayed()
        // The "Configure on the fly" card uses an en-dash separator in
        // the production string — assert the human-recognisable head.
        rule.onNodeWithText("Continue Existing Chat").assertIsDisplayed()
        rule.onNodeWithText("Search Chats").assertIsDisplayed()
        rule.onNodeWithText("Dual AI Chat").assertIsDisplayed()
    }

    @Test fun new_chat_configure_on_the_fly_invokes_callback() {
        val newChat = mutableIntStateOf(0)
        rule.setContent {
            MaterialTheme {
                ChatsHubScreen(
                    aiSettings = Settings(),
                    onNavigateBack = {}, onNavigateHome = {},
                    onNavigateToAgentSelect = {},
                    onNavigateToNewChat = { newChat.intValue++ },
                    onNavigateToChatHistory = {},
                    onNavigateToChatSearch = {},
                    onNavigateToDualChat = {}
                )
            }
        }
        // The card label is "New Chat – Configure On The Fly" with an en-dash.
        rule.onNodeWithText("New Chat – Configure On The Fly").performClick()
        assertThat(newChat.intValue).isEqualTo(1)
    }

    @Test fun dual_ai_chat_card_invokes_callback() {
        val dual = mutableIntStateOf(0)
        rule.setContent {
            MaterialTheme {
                ChatsHubScreen(
                    aiSettings = Settings(),
                    onNavigateBack = {}, onNavigateHome = {},
                    onNavigateToAgentSelect = {},
                    onNavigateToNewChat = {},
                    onNavigateToChatHistory = {},
                    onNavigateToChatSearch = {},
                    onNavigateToDualChat = { dual.intValue++ }
                )
            }
        }
        rule.onNodeWithText("Dual AI Chat").performClick()
        assertThat(dual.intValue).isEqualTo(1)
    }
}
