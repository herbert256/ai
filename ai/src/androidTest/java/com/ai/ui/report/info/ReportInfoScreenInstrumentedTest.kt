package com.ai.ui.report.info

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.data.ReportApiCallCost
import com.ai.data.ReportAgent
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResultStorage
import com.ai.data.TokenUsage
import com.ai.util.PersistentStateGuard
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReportInfoScreenInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    companion object {
        @ClassRule @JvmField val stateGuard = PersistentStateGuard()
    }

    @Before
    fun resetStorage() {
        ReportStorage.init(context)
        SecondaryResultStorage.init(context)
        ReportStorage.deleteAllReports(context)
    }

    @After
    fun cleanup() {
        ReportStorage.deleteAllReports(context)
    }

    @Test
    fun reportInfoScreenRendersPersistedReportTotalsAndComposition() {
        val report = ReportStorage.createReport(
            context = context,
            title = "Instrumented report info",
            prompt = "Summarize the result",
            agents = listOf(
                ReportAgent(
                    agentId = "agent-1",
                    agentName = "Agent One",
                    provider = "UNIT",
                    model = "model-one",
                    reportStatus = ReportStatus.PENDING
                )
            )
        )
        ReportStorage.markAgentSuccess(
            context = context,
            reportId = report.id,
            agentId = "agent-1",
            httpStatus = 200,
            responseHeaders = null,
            responseBody = "answer body",
            tokenUsage = TokenUsage(inputTokens = 10, outputTokens = 20),
            cost = 0.03,
            inputCost = 0.01,
            outputCost = 0.02,
            durationMs = 1_500L,
            traceFile = "agent-trace.json"
        )
        assertThat(
            ReportStorage.appendApiCallCost(
                filesDir = context.filesDir,
                reportId = report.id,
                record = ReportApiCallCost(
                    id = "cost-1",
                    type = "report/prompt",
                    provider = "UNIT",
                    model = "model-one",
                    pricingTier = "TEST",
                    inputTokens = 10,
                    outputTokens = 20,
                    inputCost = 0.01,
                    outputCost = 0.02,
                    durationMs = 1_500L,
                    traceFile = "agent-trace.json"
                )
            )
        ).isNotNull()
        SecondaryResultStorage.create(
            context = context,
            reportId = report.id,
            kind = SecondaryKind.META,
            providerId = "UNIT",
            model = "meta-model",
            agentName = "Meta"
        ) { it.copy(content = "meta body", durationMs = 500L) }

        rule.setContent {
            MaterialTheme {
                ReportInfoScreen(
                    reportId = report.id,
                    onBack = {},
                    onOpenTrace = {},
                    onOpenModelInfo = { _, _ -> }
                )
            }
        }

        rule.onNodeWithText("Report information").assertIsDisplayed()
        rule.onNodeWithText("Identity").assertIsDisplayed()

        rule.onNodeWithText("Totals").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("API calls").assertIsDisplayed()
        rule.onNodeWithText("Total API time").assertIsDisplayed()
        rule.onNodeWithText("2s").assertIsDisplayed()
        rule.onNodeWithText("Tokens").assertIsDisplayed()
        rule.onNodeWithText("10 in · 20 out").assertIsDisplayed()

        rule.onNodeWithText("Composition").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Model reports").assertIsDisplayed()
        rule.onNodeWithText("Secondary results").assertIsDisplayed()
        rule.onNodeWithText("1 meta").assertIsDisplayed()
    }

    @Test
    fun reportInfoScreenShowsNotFoundForMissingReport() {
        rule.setContent {
            MaterialTheme {
                ReportInfoScreen(reportId = "missing-report", onBack = {})
            }
        }

        rule.onNodeWithText("Report information").assertIsDisplayed()
        rule.onNodeWithText("Report not found.").assertIsDisplayed()
    }
}
