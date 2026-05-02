package com.ai.ui.report

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.data.ApiTrace
import com.ai.data.ApiTracer
import com.ai.data.ReportAgent
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResultStorage
import com.ai.data.TokenUsage
import com.ai.data.TraceRequest
import com.ai.data.TraceResponse
import com.ai.util.PersistentStateGuard
import com.ai.util.TestProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end coverage for [buildHtmlReportData] — the assembler used
 * by every Medium-equivalent export. Plants a fully-shaped report,
 * one secondary, one own trace and one source trace, then asserts
 * the resulting [HtmlReportData] mirrors what the renderers expect.
 */
@RunWith(AndroidJUnit4::class)
class BuildHtmlReportDataInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun reset() {
        TestProvider.register(context)
        ReportStorage.init(context)
        SecondaryResultStorage.init(context)
        ApiTracer.init(context)
        ReportStorage.deleteAllReports(context)
        ApiTracer.clearTraces()
        ApiTracer.isTracingEnabled = true
    }
    @After fun cleanup() { TestProvider.unregister() }

    companion object {
        @ClassRule @JvmField val stateGuard = PersistentStateGuard()
    }

    private fun trace(reportId: String, category: String) = ApiTrace(
        timestamp = System.currentTimeMillis(),
        hostname = TestProvider.HOST,
        reportId = reportId,
        category = category,
        model = TestProvider.MODEL,
        request = TraceRequest("${TestProvider.BASE_URL}v1/chat", "POST",
            mapOf("Authorization" to "Bearer secret-key"),
            """{"messages":[{"role":"user","content":"hi"}]}"""),
        response = TraceResponse(200, mapOf("Content-Type" to "application/json"),
            """{"choices":[{"message":{"content":"hi back"}}]}""")
    )

    @Test fun assembles_agents_secondary_and_traces_with_own_plus_source_origins() {
        val source = ReportStorage.createReport(
            context, title = "src", prompt = "p",
            agents = listOf(ReportAgent("a-1", "Agent 1", TestProvider.ID, TestProvider.MODEL,
                reportStatus = ReportStatus.SUCCESS, responseBody = "src body",
                tokenUsage = TokenUsage(10, 20)))
        )
        val translated = ReportStorage.createReport(
            context, title = "[NL] src", prompt = "p",
            agents = listOf(ReportAgent("a-1", "Agent 1", TestProvider.ID, TestProvider.MODEL,
                reportStatus = ReportStatus.SUCCESS, responseBody = "vertaling",
                tokenUsage = TokenUsage(10, 20))),
            sourceReportId = source.id
        )
        // One secondary on the translated copy.
        val sumPlaceholder = SecondaryResultStorage.create(
            context, translated.id, SecondaryKind.SUMMARIZE, TestProvider.ID, TestProvider.MODEL, "OpenAI / gpt-test"
        )
        SecondaryResultStorage.save(context, sumPlaceholder.copy(content = "vertaalde samenvatting"))

        // One trace tagged with the new (translated) report's id and one
        // tagged with the original source's id — buildHtmlReportData
        // should pull both into data.traces with the right origin.
        ApiTracer.saveTrace(trace(reportId = translated.id, category = "Translation"))
        ApiTracer.saveTrace(trace(reportId = source.id, category = "Report"))

        val data = buildHtmlReportData(context, ReportStorage.getReport(context, translated.id)!!)

        // Title + prompt round-tripped.
        assertThat(data.title).isEqualTo("[NL] src")
        assertThat(data.prompt).isEqualTo("p")
        // One agent — present, with cost info computed.
        assertThat(data.agents).hasSize(1)
        assertThat(data.agents[0].agentId).isEqualTo("a-1")
        assertThat(data.agents[0].responseText).isEqualTo("vertaling")
        // Secondary present.
        assertThat(data.secondary).hasSize(1)
        assertThat(data.secondary[0].kind).isEqualTo(SecondaryKind.SUMMARIZE)
        assertThat(data.secondary[0].content).isEqualTo("vertaalde samenvatting")
        // Two traces with distinct origins.
        assertThat(data.traces.map { it.origin }).containsExactly("this", "source")
    }

    @Test fun a_non_translated_report_has_no_source_traces() {
        val report = ReportStorage.createReport(
            context, title = "plain", prompt = "p",
            agents = listOf(ReportAgent("a", "A", "p", "m", reportStatus = ReportStatus.SUCCESS, responseBody = "x"))
        )
        ApiTracer.saveTrace(trace(reportId = report.id, category = "Report"))

        val data = buildHtmlReportData(context, ReportStorage.getReport(context, report.id)!!)

        assertThat(data.traces.map { it.origin }).containsExactly("this")
    }

    @Test fun trace_bodies_in_data_are_redacted() {
        val report = ReportStorage.createReport(
            context, title = "t", prompt = "p", agents = emptyList()
        )
        ApiTracer.saveTrace(
            ApiTrace(
                timestamp = 1L, hostname = "api.example.com", reportId = report.id,
                category = "Report",
                request = TraceRequest("https://api.example.com/v1/chat", "POST",
                    mapOf("Authorization" to "Bearer leaked-token"),
                    """{"messages":[{"role":"user","content":"x"}],"api_key":"raw-key"}"""),
                response = TraceResponse(200, mapOf("Set-Cookie" to "session=raw-session"),
                    """{"choices":[{"message":{"content":"hi"}}],"token":"raw-token"}""")
            )
        )

        val data = buildHtmlReportData(context, ReportStorage.getReport(context, report.id)!!)
        val tr = data.traces.single()
        // Sensitive headers replaced.
        assertThat(tr.requestHeaders).doesNotContain("leaked-token")
        assertThat(tr.responseHeaders).doesNotContain("raw-session")
        // Sensitive JSON keys replaced in both bodies.
        assertThat(tr.requestBody).doesNotContain("raw-key")
        assertThat(tr.responseBody).doesNotContain("raw-token")
    }
}
