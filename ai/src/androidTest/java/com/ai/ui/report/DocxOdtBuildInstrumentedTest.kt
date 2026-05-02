package com.ai.ui.report

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.data.ReportAgent
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.util.PersistentStateGuard
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class DocxOdtBuildInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun reset() {
        ReportStorage.init(context)
        ReportStorage.deleteAllReports(context)
    }

    companion object {
        @ClassRule @JvmField val stateGuard = PersistentStateGuard()
    }

    private fun zipEntryNames(bytes: ByteArray): List<String> {
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

    private fun zipEntryFirstByte(bytes: ByteArray, name: String): Byte? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.name == name) {
                    val b = zis.read()
                    return if (b < 0) null else b.toByte()
                }
                zis.closeEntry()
            }
        }
        return null
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

    private fun simpleReport(): com.ai.data.Report = ReportStorage.createReport(
        context, title = "Probe", prompt = "Why?",
        agents = listOf(
            ReportAgent("a-1", "Provider / m1", "p", "m1",
                reportStatus = ReportStatus.SUCCESS, responseBody = "answer 1"),
            ReportAgent("a-2", "Provider / m2", "p", "m2",
                reportStatus = ReportStatus.SUCCESS, responseBody = "answer 2")
        )
    )

    // ===== DOCX =====

    @Test fun buildDocxBytes_returns_a_valid_office_open_xml_zip() {
        val bytes = buildDocxBytes(context, simpleReport(), short = false)
        val names = zipEntryNames(bytes)
        // Required parts of an Office Open XML wordprocessing document.
        assertThat(names).contains("[Content_Types].xml")
        assertThat(names).contains("_rels/.rels")
        assertThat(names).contains("word/document.xml")
        assertThat(names).contains("word/styles.xml")
        assertThat(names).contains("word/numbering.xml")
        assertThat(names).contains("word/_rels/document.xml.rels")
    }

    @Test fun buildDocxBytes_short_omits_TOC_field() {
        val short = buildDocxBytes(context, simpleReport(), short = true)
        val complete = buildDocxBytes(context, simpleReport(), short = false)
        val shortDoc = zipEntryText(short, "word/document.xml") ?: error("missing")
        val completeDoc = zipEntryText(complete, "word/document.xml") ?: error("missing")
        // Complete embeds a TOC field; short does not.
        assertThat(completeDoc).contains("TOC")
        assertThat(shortDoc).doesNotContain("TOC")
    }

    // ===== ODT =====

    @Test fun buildOdtBytes_first_entry_is_uncompressed_mimetype_per_spec() {
        val bytes = buildOdtBytes(context, simpleReport(), short = false)
        val names = zipEntryNames(bytes)
        // mimetype must be the first entry per the OpenDocument spec.
        assertThat(names.first()).isEqualTo("mimetype")
        assertThat(names).contains("META-INF/manifest.xml")
        assertThat(names).contains("content.xml")
    }

    @Test fun buildOdtBytes_mimetype_payload_matches_application_vnd_oasis_opendocument_text() {
        val bytes = buildOdtBytes(context, simpleReport(), short = false)
        val mimetype = zipEntryText(bytes, "mimetype")
        assertThat(mimetype).isEqualTo("application/vnd.oasis.opendocument.text")
    }

    @Test fun buildOdtBytes_short_omits_TOC_element() {
        val short = buildOdtBytes(context, simpleReport(), short = true)
        val complete = buildOdtBytes(context, simpleReport(), short = false)
        val shortContent = zipEntryText(short, "content.xml") ?: error("missing")
        val completeContent = zipEntryText(complete, "content.xml") ?: error("missing")
        assertThat(completeContent).contains("table-of-content")
        assertThat(shortContent).doesNotContain("table-of-content")
    }

    @Test fun buildOdtBytes_includes_each_agent_response_in_content_xml() {
        val bytes = buildOdtBytes(context, simpleReport(), short = false)
        val content = zipEntryText(bytes, "content.xml") ?: error("missing")
        assertThat(content).contains("answer 1")
        assertThat(content).contains("answer 2")
        // Per-agent heading uses providerDisplay / model — for unregistered
        // provider id "p" the displayName falls back to "p".
        assertThat(content).contains("m1")
        assertThat(content).contains("m2")
    }
}
