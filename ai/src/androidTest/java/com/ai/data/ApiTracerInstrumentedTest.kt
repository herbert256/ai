package com.ai.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.util.PersistentStateGuard
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented coverage for [ApiTracer]. The unit tests exercise
 * the in-memory tag pair via [withTracerTags]; this suite exercises
 * the on-disk side: saveTrace + getTraceFiles + readTraceFile +
 * report-id filtering + getTraceCount + clearTraces +
 * deleteTracesOlderThan.
 *
 * The tracer is a singleton, so each test wipes the trace dir
 * before running and at the end.
 */
@RunWith(AndroidJUnit4::class)
class ApiTracerInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun reset() {
        ApiTracer.init(context)
        ApiTracer.clearTraces()
        ApiTracer.isTracingEnabled = true
        ApiTracer.currentReportId = null
        ApiTracer.currentCategory = null
    }
    @After fun cleanup() {
        ApiTracer.clearTraces()
        ApiTracer.currentReportId = null
        ApiTracer.currentCategory = null
        // Leave isTracingEnabled in whatever state the rest of the app expects.
    }

    companion object {
        @ClassRule @JvmField val stateGuard = PersistentStateGuard()
    }

    private fun trace(
        host: String = "api.example.com",
        ts: Long = System.currentTimeMillis(),
        reportId: String? = null,
        category: String? = null,
        model: String? = "m"
    ) = ApiTrace(
        timestamp = ts,
        hostname = host,
        reportId = reportId,
        model = model,
        category = category,
        request = TraceRequest(
            url = "https://$host/v1/chat",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = """{"prompt":"x"}"""
        ),
        response = TraceResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = """{"ok":true}"""
        )
    )

    @Test fun saveTrace_then_getTraceFiles_lists_with_summary_fields() {
        ApiTracer.saveTrace(trace(host = "h.example.com", reportId = "r1", category = "Report"))
        val files = ApiTracer.getTraceFiles()
        assertThat(files).hasSize(1)
        val info = files[0]
        assertThat(info.hostname).isEqualTo("h.example.com")
        assertThat(info.reportId).isEqualTo("r1")
        assertThat(info.category).isEqualTo("Report")
        assertThat(info.statusCode).isEqualTo(200)
        assertThat(info.model).isEqualTo("m")
    }

    @Test fun saveTrace_is_a_no_op_when_tracing_disabled() {
        ApiTracer.isTracingEnabled = false
        try {
            ApiTracer.saveTrace(trace())
            assertThat(ApiTracer.getTraceFiles()).isEmpty()
        } finally {
            ApiTracer.isTracingEnabled = true
        }
    }

    @Test fun getTraceFiles_sorts_newest_first() {
        ApiTracer.saveTrace(trace(host = "older.example.com", ts = 1_000))
        ApiTracer.saveTrace(trace(host = "newer.example.com", ts = 2_000))
        val files = ApiTracer.getTraceFiles().map { it.hostname }
        assertThat(files).containsExactly("newer.example.com", "older.example.com").inOrder()
    }

    @Test fun getTraceFilesForReport_filters_by_reportId() {
        ApiTracer.saveTrace(trace(reportId = "report-a"))
        ApiTracer.saveTrace(trace(reportId = "report-b"))
        ApiTracer.saveTrace(trace(reportId = null))

        val a = ApiTracer.getTraceFilesForReport("report-a")
        val b = ApiTracer.getTraceFilesForReport("report-b")
        assertThat(a).hasSize(1)
        assertThat(b).hasSize(1)
        assertThat(a[0].reportId).isEqualTo("report-a")
        assertThat(b[0].reportId).isEqualTo("report-b")
    }

    @Test fun readTraceFile_returns_the_full_ApiTrace() {
        ApiTracer.saveTrace(trace(reportId = "r", category = "Report"))
        val file = ApiTracer.getTraceFiles().first().filename
        val full = ApiTracer.readTraceFile(file)
        assertThat(full).isNotNull()
        assertThat(full!!.reportId).isEqualTo("r")
        assertThat(full.category).isEqualTo("Report")
        assertThat(full.request.body).contains("prompt")
        assertThat(full.response.statusCode).isEqualTo(200)
    }

    @Test fun readTraceFileRaw_returns_the_on_disk_bytes_unchanged() {
        ApiTracer.saveTrace(trace(reportId = "r"))
        val file = ApiTracer.getTraceFiles().first().filename
        val raw = ApiTracer.readTraceFileRaw(file)
        assertThat(raw).isNotNull()
        // Raw text contains both reportId and the response body.
        // The response body field is JSON-encoded as a string, so the
        // inner JSON quotes are escaped as `\"` — assert without
        // requiring the surrounding quote chars.
        assertThat(raw!!).contains("\"reportId\"")
        assertThat(raw).contains("\"r\"")
        assertThat(raw).contains("ok")
    }

    @Test fun getTraceCount_and_hasAnyTraceFile_reflect_storage_state() {
        assertThat(ApiTracer.getTraceCount()).isEqualTo(0)
        assertThat(ApiTracer.hasAnyTraceFile()).isFalse()
        ApiTracer.saveTrace(trace())
        assertThat(ApiTracer.getTraceCount()).isEqualTo(1)
        assertThat(ApiTracer.hasAnyTraceFile()).isTrue()
    }

    @Test fun clearTraces_drops_every_trace_file() {
        ApiTracer.saveTrace(trace(host = "a"))
        ApiTracer.saveTrace(trace(host = "b"))
        ApiTracer.clearTraces()
        assertThat(ApiTracer.getTraceFiles()).isEmpty()
        assertThat(ApiTracer.getTraceCount()).isEqualTo(0)
    }

    @Test fun deleteTracesOlderThan_removes_only_old_entries_and_returns_count() {
        ApiTracer.saveTrace(trace(host = "old", ts = 1_000))
        ApiTracer.saveTrace(trace(host = "newer-1", ts = 5_000))
        ApiTracer.saveTrace(trace(host = "newer-2", ts = 9_000))

        val deleted = ApiTracer.deleteTracesOlderThan(cutoffTimestamp = 4_000)
        assertThat(deleted).isEqualTo(1)
        val survivors = ApiTracer.getTraceFiles().map { it.hostname }
        assertThat(survivors).containsExactly("newer-1", "newer-2")
    }

    @Test fun prettyPrintJson_round_trips_valid_json_and_falls_through_on_invalid() {
        val pretty = ApiTracer.prettyPrintJson("""{"a":1,"b":[2,3]}""")
        assertThat(pretty).contains("\"a\"")
        assertThat(pretty).contains("\"b\"")
        // Multi-line indentation is the only requirement we can rely on.
        assertThat(pretty.lines().size).isGreaterThan(1)

        val passthrough = ApiTracer.prettyPrintJson("not json at all")
        assertThat(passthrough).isEqualTo("not json at all")
    }
}
