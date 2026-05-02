package com.ai.ui.report

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.data.ApiTrace
import com.ai.data.ApiTracer
import com.ai.data.ReportStorage
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
class JsonTraceZipInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun reset() {
        TestProvider.register(context)
        ReportStorage.init(context); ApiTracer.init(context)
        ReportStorage.deleteAllReports(context)
        ApiTracer.clearTraces()
        ApiTracer.isTracingEnabled = true
    }
    @After fun cleanup() { TestProvider.unregister() }

    companion object {
        @ClassRule @JvmField val stateGuard = PersistentStateGuard()
    }

    private fun planTrace(reportId: String, category: String, hostname: String = TestProvider.HOST) =
        ApiTrace(
            timestamp = System.currentTimeMillis(),
            hostname = hostname, reportId = reportId,
            category = category, model = TestProvider.MODEL,
            request = TraceRequest("https://$hostname/v1/chat", "POST",
                mapOf("Authorization" to "Bearer leak"),
                """{"messages":[{"role":"user","content":"x"}]}"""),
            response = TraceResponse(200, mapOf("Content-Type" to "application/json"),
                """{"choices":[{"message":{"content":"y"}}]}""")
        )

    private fun zipNames(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                out += e.name
                zis.closeEntry()
            }
        }
        return out
    }

    @Test fun buildJsonTraceZipBytes_groups_traces_by_category_directory() {
        val report = ReportStorage.createReport(context, "t", "p", emptyList())
        ApiTracer.saveTrace(planTrace(report.id, "Report"))
        ApiTracer.saveTrace(planTrace(report.id, "Report compare"))

        val bytes = buildJsonTraceZipBytes(context, ReportStorage.getReport(context, report.id)!!)
        assertThat(bytes).isNotNull()
        val names = zipNames(bytes!!)
        assertThat(names.any { it.startsWith("Report/") }).isTrue()
        assertThat(names.any { it.startsWith("Report compare/") }).isTrue()
    }

    @Test fun buildJsonTraceZipBytes_returns_null_when_no_traces() {
        val report = ReportStorage.createReport(context, "t", "p", emptyList())
        val bytes = buildJsonTraceZipBytes(context, ReportStorage.getReport(context, report.id)!!)
        assertThat(bytes).isNull()
    }

    @Test fun buildJsonTraceZipBytes_buckets_uncategorised_under_Other() {
        val report = ReportStorage.createReport(context, "t", "p", emptyList())
        ApiTracer.saveTrace(planTrace(report.id, category = "").copy(category = null))

        val bytes = buildJsonTraceZipBytes(context, ReportStorage.getReport(context, report.id)!!)
        assertThat(bytes).isNotNull()
        assertThat(zipNames(bytes!!).any { it.startsWith("Other/") }).isTrue()
    }

    @Test fun buildJsonTraceZipBytes_includes_source_traces_under_source_prefix_for_translated_reports() {
        val src = ReportStorage.createReport(context, "src", "p", emptyList())
        val translated = ReportStorage.createReport(
            context, "[NL] src", "p", emptyList(), sourceReportId = src.id
        )
        ApiTracer.saveTrace(planTrace(translated.id, "Translation"))
        ApiTracer.saveTrace(planTrace(src.id, "Report"))

        val bytes = buildJsonTraceZipBytes(context, ReportStorage.getReport(context, translated.id)!!)
        assertThat(bytes).isNotNull()
        val names = zipNames(bytes!!)
        // Own (translation) bucket at the top.
        assertThat(names.any { it.startsWith("Translation/") }).isTrue()
        // Source-side under source/.
        assertThat(names.any { it.startsWith("source/Report/") }).isTrue()
    }
}
