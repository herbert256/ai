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
                context, report.id, SecondaryKind.META, TestProvider.ID, TestProvider.MODEL, "OpenAI / gpt-test"
            ).copy(content = "the summary", metaPromptName = "Summary")
        )

        val bytes = buildZippedHtmlBytes(context, ReportStorage.getReport(context, report.id)!!)
        val entries = zipEntries(bytes)

        // Without translations the zip still contains exactly one
        // language directory ("original/"); the user-facing root index
        // sits at the top alongside style.css. Meta sections are now
        // bucketed by user-given prompt name — a row with
        // metaPromptName="Summary" lives under original/Summary/.
        assertThat(entries).contains("style.css")
        assertThat(entries).contains("index.html")
        assertThat(entries).contains("original/index.html")
        assertThat(entries).contains("original/Reports/index.html")
        assertThat(entries).contains("original/Summary/index.html")
        assertThat(entries).contains("original/Prompt/index.html")
        assertThat(entries).contains("original/Costs/index.html")
        // No JSON section because no traces.
        assertThat(entries.none { it.startsWith("original/JSON/") }).isTrue()
        // No translated language directories without TRANSLATE rows.
        assertThat(entries.none { it.startsWith("dutch/") || it.startsWith("german/") }).isTrue()
    }

    @Test fun buildZippedHtmlBytes_emits_per_language_directories_when_translations_exist() {
        val report = ReportStorage.createReport(
            context, title = "Source", prompt = "What is the capital of France?",
            agents = listOf(ReportAgent("a-1", "OpenAI / gpt-test", TestProvider.ID, TestProvider.MODEL,
                reportStatus = ReportStatus.SUCCESS, responseBody = "Paris."))
        )
        // Translate the prompt + the agent body into Dutch via TRANSLATE
        // SecondaryResults stamped with targetLanguage = "Dutch". This
        // is what the translation runner now persists on the source
        // report — no separate translated report is created.
        SecondaryResultStorage.save(context, SecondaryResultStorage.create(
            context, report.id, SecondaryKind.TRANSLATE, TestProvider.ID, TestProvider.MODEL, "Translate: prompt"
        ).copy(
            content = "Wat is de hoofdstad van Frankrijk?",
            translateSourceKind = "PROMPT", translateSourceTargetId = "prompt",
            targetLanguage = "Dutch", targetLanguageNative = "Nederlands"
        ))
        SecondaryResultStorage.save(context, SecondaryResultStorage.create(
            context, report.id, SecondaryKind.TRANSLATE, TestProvider.ID, TestProvider.MODEL, "Translate: a-1"
        ).copy(
            content = "Parijs.",
            translateSourceKind = "AGENT", translateSourceTargetId = "a-1",
            targetLanguage = "Dutch", targetLanguageNative = "Nederlands"
        ))

        val bytes = buildZippedHtmlBytes(context, ReportStorage.getReport(context, report.id)!!)
        val entries = zipEntries(bytes)

        // Original tree as before.
        assertThat(entries).contains("original/index.html")
        assertThat(entries).contains("original/Reports/index.html")
        assertThat(entries).contains("original/Prompt/index.html")
        // Dutch tree mirrors Original's narrative sections.
        assertThat(entries).contains("dutch/index.html")
        assertThat(entries).contains("dutch/Reports/index.html")
        assertThat(entries).contains("dutch/Prompt/index.html")
        // Costs / JSON / Reranks / Moderations are Original-only since
        // they aggregate across languages or aren't translated.
        assertThat(entries.none { it.startsWith("dutch/Costs/") }).isTrue()
        assertThat(entries.none { it.startsWith("dutch/JSON/") }).isTrue()

        // Dutch agent page carries the translated body, not the source.
        val dutchAgent = entries.single { it.startsWith("dutch/Reports/") && it.endsWith(".html") && it != "dutch/Reports/index.html" }
        val dutchPage = zipEntryText(bytes, dutchAgent) ?: error("dutch agent page missing")
        assertThat(dutchPage).contains("Parijs.")
        assertThat(dutchPage).doesNotContain("Paris.")
        // Dutch prompt page carries the translated prompt.
        val dutchPrompt = zipEntryText(bytes, "dutch/Prompt/index.html") ?: error("dutch prompt missing")
        assertThat(dutchPrompt).contains("Wat is de hoofdstad")
    }

    @Test fun root_index_lists_each_language_when_translations_exist() {
        val report = ReportStorage.createReport(context, "T", "p", emptyList())
        SecondaryResultStorage.save(context, SecondaryResultStorage.create(
            context, report.id, SecondaryKind.TRANSLATE, TestProvider.ID, TestProvider.MODEL, "Translate: prompt"
        ).copy(
            content = "translated",
            translateSourceKind = "PROMPT", translateSourceTargetId = "prompt",
            targetLanguage = "German", targetLanguageNative = "Deutsch"
        ))

        val bytes = buildZippedHtmlBytes(context, ReportStorage.getReport(context, report.id)!!)
        val rootIndex = zipEntryText(bytes, "index.html") ?: error("missing root index.html")
        assertThat(rootIndex).contains("original/index.html")
        assertThat(rootIndex).contains("german/index.html")
        assertThat(rootIndex).contains("German")
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

        val entries = zipEntries(bytes).filter { it.startsWith("original/Reports/") && it.endsWith(".html") && it != "original/Reports/index.html" }
        assertThat(entries).hasSize(1)
        val agentPage = zipEntryText(bytes, entries.single()) ?: error("agent page missing")
        // 🐞 emoji and a link into the trace's directory inside the
        // language-scoped JSON/ tree (../../original/JSON/...).
        assertThat(agentPage).contains("🐞")
        assertThat(agentPage).contains("original/JSON/Report/")
    }
}
