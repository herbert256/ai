package com.ai.ui.report.manage.view

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.data.AppService
import com.ai.model.ProviderConfig
import com.ai.model.Settings
import com.ai.viewmodel.ModelSwitchResult
import com.ai.viewmodel.ModelSwitchSelection
import com.ai.viewmodel.ModelSwitchState
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReportChangeResultScreensInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun responseChangeActionsScreenRendersActionsAndInvokesEnabledAction() {
        val clicked = mutableStateOf("")

        rule.setContent {
            MaterialTheme {
                ResponseChangeActionsScreen(
                    title = "Change result",
                    subject = "Provider / model",
                    actions = listOf(
                        ResponseChangeAction(
                            icon = "A",
                            title = "Continue in chat",
                            description = "Start from this response",
                            onClick = { clicked.value = "chat" }
                        ),
                        ResponseChangeAction(
                            icon = "B",
                            title = "Web search replay",
                            description = "Unavailable for this model",
                            enabled = false,
                            onClick = { clicked.value = "web" }
                        )
                    ),
                    onBack = {}
                )
            }
        }

        rule.onNodeWithText("Change result").assertIsDisplayed()
        rule.onNodeWithText("Provider / model").assertIsDisplayed()
        rule.onNodeWithText("Continue in chat").assertIsDisplayed()
        rule.onNodeWithText("Start from this response").assertIsDisplayed()
        rule.onNodeWithText("Web search replay").assertIsDisplayed()
        rule.onNodeWithText("Unavailable for this model").assertIsDisplayed()

        rule.onNodeWithText("Continue in chat").performClick()

        assertThat(clicked.value).isEqualTo("chat")
    }

    @Test
    fun modelSwitchPickScreenShowsAgentAndProviderEntryPoints() {
        rule.setContent {
            MaterialTheme {
                SecondaryModelSwitchPickScreen(
                    aiSettings = Settings(
                        providers = mapOf(AppService.LOCAL to ProviderConfig(apiKey = "local-key"))
                    ),
                    rowParamsIds = listOf("p1", "p2"),
                    rowSystemPromptId = "system-1",
                    onPicked = {},
                    onBack = {},
                    onNavigateHome = {}
                )
            }
        }

        rule.onNodeWithText("Switch model / agent").assertIsDisplayed()
        rule.onNodeWithText("Re-run this result against another model").assertIsDisplayed()
        rule.onNodeWithText("Choose an agent").assertIsDisplayed()
        rule.onNodeWithText("Choose a provider & model").assertIsDisplayed()
    }

    @Test
    fun modelSwitchPreviewSuccessShowsCandidateAndUseDiscardActions() {
        val used = mutableIntStateOf(0)
        val discarded = mutableIntStateOf(0)
        val traced = mutableStateOf("")

        rule.setContent {
            MaterialTheme {
                SecondaryModelSwitchPreviewScreen(
                    state = modelSwitchState(
                        ModelSwitchResult.Success(
                            content = "candidate answer body",
                            tokenUsage = null,
                            inputCost = 0.01,
                            outputCost = 0.02,
                            durationMs = 1_500L,
                            traceFile = "trace-success.json"
                        )
                    ),
                    onUse = { used.intValue++ },
                    onDiscard = { discarded.intValue++ },
                    onTrace = { traced.value = it },
                    onBack = {},
                    body = { Text(it) }
                )
            }
        }

        rule.onNodeWithText("Switch model / agent").assertIsDisplayed()
        rule.onNodeWithText("Local / switch-model").assertIsDisplayed()
        rule.onNodeWithText("candidate answer body").assertIsDisplayed()
        rule.onNodeWithText("Discard").assertIsDisplayed()
        rule.onNodeWithText("Use").assertIsDisplayed()

        rule.onNodeWithText("Use").performClick()
        rule.onNodeWithText("🐞").performClick()

        assertThat(used.intValue).isEqualTo(1)
        assertThat(discarded.intValue).isEqualTo(0)
        assertThat(traced.value).isEqualTo("trace-success.json")
    }

    @Test
    fun modelSwitchPreviewErrorShowsTraceAndDiscardActions() {
        val discarded = mutableIntStateOf(0)
        val traced = mutableStateOf("")

        rule.setContent {
            MaterialTheme {
                SecondaryModelSwitchPreviewScreen(
                    state = modelSwitchState(
                        ModelSwitchResult.Error(
                            message = "provider failed",
                            httpStatusCode = 500,
                            durationMs = 750L,
                            traceFile = "trace-error.json"
                        )
                    ),
                    onUse = {},
                    onDiscard = { discarded.intValue++ },
                    onTrace = { traced.value = it },
                    onBack = {},
                    body = { Text(it) }
                )
            }
        }

        rule.onNodeWithText("Error").assertIsDisplayed()
        rule.onNodeWithText("provider failed").assertIsDisplayed()
        rule.onNodeWithText("🐞 Trace").assertIsDisplayed()
        rule.onNodeWithText("Discard").assertIsDisplayed()

        rule.onNodeWithText("🐞 Trace").performClick()
        rule.onNodeWithText("Discard").performClick()

        assertThat(traced.value).isEqualTo("trace-error.json")
        assertThat(discarded.intValue).isEqualTo(1)
    }

    @Test
    fun modelSwitchPreviewRunningShowsProgressText() {
        rule.setContent {
            MaterialTheme {
                SecondaryModelSwitchPreviewScreen(
                    state = modelSwitchState(ModelSwitchResult.Running, isRunning = true),
                    onUse = {},
                    onDiscard = {},
                    onTrace = {},
                    onBack = {},
                    body = { Text(it) }
                )
            }
        }

        rule.onNodeWithText("Running Local / switch-model", substring = true).assertIsDisplayed()
    }

    private fun modelSwitchState(
        result: ModelSwitchResult,
        isRunning: Boolean = false
    ) = ModelSwitchState(
        reportId = "report-1",
        resultId = "secondary-1",
        selection = ModelSwitchSelection(
            provider = AppService.LOCAL,
            model = "switch-model",
            paramsIds = emptyList(),
            systemPromptId = null,
            label = "Local / switch-model"
        ),
        result = result,
        isRunning = isRunning
    )
}
