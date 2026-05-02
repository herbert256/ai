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
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class ZippedHtmlBuildInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun reset() {
        TestProvider.register(context)
        ReportStorage.init(context); SecondaryResultStorage.init(context); ApiTracer.init(context)
        ReportStorage.deleteAllReports(context)
        ApiTracer.clearTraces()
        ApiTracer.isTracingEnabled = true
    }
    @After fun cleanup() { TestProvider.unregister() }

    companion object {
        @ClassRule @JvmField val stateGuard = PersistentStateGuard()
    }

    private fun zipEntries(bytes: ByteArray): Set<String> {
        val out = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                out += e.name
                zis.closeEntry()
            }
        }
        return out
    }

    private fun zipEntryText(bytes: ByteArray, name: String): String? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.name == name) return zis.readBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
            }
        }
        return null
    }

    @Test fun buildZippedHtmlBytes_lays_out_expected_directories_for_a_simple_report() {
        val report = ReportStorage.createReport(
            context, title = "Probe", prompt = "What?",
            agents = listOf(ReportAgent("a-1", "OpenAI / gpt-test", TestProvider.ID, TestProvider.MODEL,
                reportStatus = ReportStatus.SUCCESS, responseBody = "answer body",
                tokenUsage = TokenUsage(10, 20), cost = 0.05))
        )
        SecondaryResultStorage.save(
            context,
            SecondaryResultStorage.create(
                context, report.id, SecondaryKind.SUMMARIZE, TestProvider.ID, TestProvider.MODEL, "OpenAI / gpt-test"
            ).copy(content = "the summary")
        )

        val bytes = buildZippedHtmlBytes(context, ReportStorage.getReport(context, report.id)!!)
        val entries = zipEntries(bytes)

        assertThat(entries).contains("style.css")
        assertThat(entries).contains("index.html")
        assertThat(entries).contains("Reports/index.html")
        assertThat(entries).contains("Summaries/index.html")
        assertThat(entries).contains("Prompt/index.html")
        assertThat(entries).contains("Costs/index.html")
        // No JSON section because no traces.
        assertThat(entries.none { it.startsWith("JSON/") }).isTrue()
    }

    @Test fun buildZippedHtmlBytes_emits_Source_tree_for_translated_report() {
        val src = ReportStorage.createReport(
            context, title = "Source", prompt = "p",
            agents = listOf(ReportAgent("a-1", "OpenAI / gpt-test", TestProvider.ID, TestProvider.MODEL,
                reportStatus = ReportStatus.SUCCESS, responseBody = "src body"))
        )
        val translated = ReportStorage.createReport(
            context, title = "[NL] Source", prompt = "p",
            agents = listOf(ReportAgent("a-1", "OpenAI / gpt-test", TestProvider.ID, TestProvider.MODEL,
                reportStatus = ReportStatus.SUCCESS, responseBody = "translated body")),
            sourceReportId = src.id
        )
        // Plant a source-side trace so JSON/source/ shows up too.
        ApiTracer.saveTrace(
            ApiTrace(timestamp = 1L, hostname = TestProvider.HOST,
                reportId = src.id, category = "Report", model = TestProvider.MODEL,
                request = TraceRequest("${TestProvider.BASE_URL}v1/chat", "POST",
                    emptyMap(), """{"messages":[{"role":"user","content":"x"}]}"""),
                response = TraceResponse(200, emptyMap(), """{"choices":[{"message":{"content":"y"}}]}"""))
        )

        val bytes = buildZippedHtmlBytes(context, ReportStorage.getReport(context, translated.id)!!)
        val entries = zipEntries(bytes)

        assertThat(entries).contains("Source/index.html")
        assertThat(entries).contains("Source/Reports/index.html")
        // Source's own traces now live alongside its other HTML
        // sections under /Source/JSON/ rather than /JSON/source/. The
        // main translated side has no own traces in this scenario
        // (the planted trace was for src.id), so its /JSON/ tree is
        // legitimately absent.
        assertThat(entries).contains("Source/JSON/index.html")
        assertThat(entries.any { it.startsWith("Source/JSON/Report/") }).isTrue()
    }

    @Test fun root_index_lists_all_present_sections_and_links_to_Source() {
        val src = ReportStorage.createReport(context, "src", "p", emptyList())
        val translated = ReportStorage.createReport(
            context, "translated", "p", emptyList(), sourceReportId = src.id
        )
        val bytes = buildZippedHtmlBytes(context, ReportStorage.getReport(context, translated.id)!!)
        val rootIndex = zipEntryText(bytes, "index.html") ?: error("missing root index.html")
        assertThat(rootIndex).contains("Source/index.html")
    }

    @Test fun report_page_carries_a_bug_link_when_a_trace_matches() {
        val report = ReportStorage.createReport(
            context, "T", "p",
            agents = listOf(ReportAgent("a", "OpenAI / gpt-test", TestProvider.ID, TestProvider.MODEL,
                reportStatus = ReportStatus.SUCCESS, responseBody = "body"))
        )
        ApiTracer.saveTrace(
            ApiTrace(timestamp = 1L, hostname = TestProvider.HOST,
                reportId = report.id, category = "Report", model = TestProvider.MODEL,
                request = TraceRequest("${TestProvider.BASE_URL}v1/chat", "POST",
                    emptyMap(), "{}"),
                response = TraceResponse(200, emptyMap(), """{"choices":[{"message":{"content":"x"}}]}"""))
        )
        val bytes = buildZippedHtmlBytes(context, ReportStorage.getReport(context, report.id)!!)

        val entries = zipEntries(bytes).filter { it.startsWith("Reports/") && it.endsWith(".html") && it != "Reports/index.html" }
        assertThat(entries).hasSize(1)
        val agentPage = zipEntryText(bytes, entries.single()) ?: error("agent page missing")
        // 🐞 emoji and a link into the trace's directory inside JSON/.
        assertThat(agentPage).contains("🐞")
        assertThat(agentPage).contains("../JSON/Report/")
    }
}
