package com.ai.ui.report

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.data.ApiTrace
import com.ai.data.ApiTracer
import com.ai.data.ReportAgent
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.TraceRequest
import com.ai.data.TraceResponse
import com.ai.util.PersistentStateGuard
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * End-to-end coverage of the bulk-export builder logic — same as
 * [bulkExportAndShare] minus the share-intent dispatch (which we
 * can't easily exercise from a test without a live activity). We
 * call the builder helpers directly, replicate the master-zip
 * assembly, and assert the layout matches what the bundle is
 * advertised to ship.
 */
@RunWith(AndroidJUnit4::class)
class BulkExportInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun reset() {
        ReportStorage.init(context); ApiTracer.init(context)
        ReportStorage.deleteAllReports(context)
        ApiTracer.clearTraces()
        ApiTracer.isTracingEnabled = true
    }

    companion object {
        @ClassRule @JvmField val stateGuard = PersistentStateGuard()
    }

    @Test fun buildersProduce_8_doc_artifacts_plus_zipped_html_and_optional_traces() {
        val report = ReportStorage.createReport(
            context, title = "Bulk", prompt = "p",
            agents = listOf(ReportAgent("a", "Provider / m", "p", "m",
                reportStatus = ReportStatus.SUCCESS, responseBody = "answer"))
        )

        // Build everything the bulk export does, on Dispatchers.IO to
        // mirror its threading contract.
        val (entries, masterZip) = runBlocking {
            withContext(Dispatchers.IO) {
                val safeTitle = "Bulk"
                val workDir = File(context.cacheDir, "bulk_test_${System.currentTimeMillis()}")
                    .also { if (it.exists()) it.deleteRecursively(); it.mkdirs() }

                File(workDir, "${safeTitle}_short.html").writeText(buildShortHtml(context, report))
                File(workDir, "${safeTitle}_complete.html").writeText(
                    convertReportToHtml(context, report, "test")
                )
                File(workDir, "${safeTitle}_short.docx").writeBytes(buildDocxBytes(context, report, true))
                File(workDir, "${safeTitle}_complete.docx").writeBytes(buildDocxBytes(context, report, false))
                File(workDir, "${safeTitle}_short.odt").writeBytes(buildOdtBytes(context, report, true))
                File(workDir, "${safeTitle}_complete.odt").writeBytes(buildOdtBytes(context, report, false))

                val zippedHtml = buildZippedHtmlBytes(context, report)
                val tracesZip = buildJsonTraceZipBytes(context, report)

                val outZip = File(context.cacheDir, "bulk_test_master_${System.currentTimeMillis()}.zip")
                java.util.zip.ZipOutputStream(outZip.outputStream().buffered()).use { zos ->
                    workDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                        zos.putNextEntry(java.util.zip.ZipEntry("docs/${f.name}"))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                    unpackInto(zos, zippedHtml, "html/")
                    tracesZip?.let { unpackInto(zos, it, "json/") }
                }
                workDir.deleteRecursively()
                val list = ZipFile(outZip).use { zf -> zf.entries().toList().map { it.name } }
                list to outZip
            }
        }

        try {
            // 6 of the 8 doc artifacts are exercised here (HTML + DOCX
            // + ODT in both detail levels). The two PDFs need a
            // WebView hosted by an Activity — they're skipped in this
            // instrumented test.
            assertThat(entries).contains("docs/Bulk_short.html")
            assertThat(entries).contains("docs/Bulk_complete.html")
            assertThat(entries).contains("docs/Bulk_short.docx")
            assertThat(entries).contains("docs/Bulk_complete.docx")
            assertThat(entries).contains("docs/Bulk_short.odt")
            assertThat(entries).contains("docs/Bulk_complete.odt")

            // html/ tree from the zipped-html builder is unpacked,
            // not nested.
            assertThat(entries.any { it.startsWith("html/") && it != "html/" }).isTrue()
            // No traces planted → no json/ tree.
            assertThat(entries.none { it.startsWith("json/") }).isTrue()
        } finally {
            masterZip.delete()
        }
    }
}
