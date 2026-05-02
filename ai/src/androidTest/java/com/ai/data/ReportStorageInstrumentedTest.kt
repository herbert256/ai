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
 * Round-trip coverage for [ReportStorage] using a real Android
 * Context. The storage is a singleton tied to filesDir, so each
 * test wipes the report directory before running to keep state
 * between cases isolated.
 */
@RunWith(AndroidJUnit4::class)
class ReportStorageInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun cleanReports() {
        ReportStorage.init(context)
        ReportStorage.deleteAllReports(context)
    }
    @After fun cleanup() { ReportStorage.deleteAllReports(context) }

    companion object {
        @ClassRule @JvmField val stateGuard = PersistentStateGuard()
    }

    private fun agent(id: String, status: ReportStatus = ReportStatus.PENDING) = ReportAgent(
        agentId = id,
        agentName = "agent-$id",
        provider = "UNIT",
        model = "model-$id",
        reportStatus = status
    )

    @Test fun createReport_persists_and_returns_with_assigned_id() {
        val report = ReportStorage.createReport(
            context = context,
            title = "Round-trip",
            prompt = "p",
            agents = listOf(agent("a1"), agent("a2"))
        )
        assertThat(report.id).isNotEmpty()
        assertThat(report.title).isEqualTo("Round-trip")

        val reloaded = ReportStorage.getReport(context, report.id)
        assertThat(reloaded).isNotNull()
        assertThat(reloaded!!.title).isEqualTo("Round-trip")
        assertThat(reloaded.agents.map { it.agentId }).containsExactly("a1", "a2").inOrder()
    }

    @Test fun createReport_with_explicitId_uses_that_id() {
        val explicit = "fixed-uuid-1"
        val report = ReportStorage.createReport(
            context = context, title = "x", prompt = "y",
            agents = listOf(agent("a")), explicitId = explicit
        )
        assertThat(report.id).isEqualTo(explicit)
        assertThat(ReportStorage.getReport(context, explicit)).isNotNull()
    }

    @Test fun createReport_records_sourceReportId_on_translated_copies() {
        val src = ReportStorage.createReport(
            context = context, title = "src", prompt = "p", agents = listOf(agent("a"))
        )
        val copy = ReportStorage.createReport(
            context = context, title = "[NL] src", prompt = "p",
            agents = listOf(agent("a")), sourceReportId = src.id
        )
        assertThat(ReportStorage.getReport(context, copy.id)?.sourceReportId).isEqualTo(src.id)
        assertThat(ReportStorage.getReport(context, src.id)?.sourceReportId).isNull()
    }

    @Test fun markAgentSuccess_writes_usage_and_completes_when_all_done() {
        val report = ReportStorage.createReport(
            context = context, title = "t", prompt = "p",
            agents = listOf(agent("a"))
        )
        ReportStorage.markAgentSuccess(
            context, report.id, "a", httpStatus = 200,
            responseHeaders = "h", responseBody = "body",
            tokenUsage = TokenUsage(10, 20), cost = 0.0001,
            durationMs = 250
        )
        val reloaded = ReportStorage.getReport(context, report.id)!!
        assertThat(reloaded.agents[0].reportStatus).isEqualTo(ReportStatus.SUCCESS)
        assertThat(reloaded.agents[0].responseBody).isEqualTo("body")
        assertThat(reloaded.agents[0].tokenUsage?.totalTokens).isEqualTo(30)  // 10 + 20 input/output
        assertThat(reloaded.agents[0].cost).isEqualTo(0.0001)
        assertThat(reloaded.agents[0].durationMs).isEqualTo(250)
        // All agents done → completedAt populated.
        assertThat(reloaded.completedAt).isNotNull()
        // totalCost recomputed from agents.
        assertThat(reloaded.totalCost).isEqualTo(0.0001)
    }

    @Test fun markAgentSuccess_clears_prior_error_on_re_run() {
        val report = ReportStorage.createReport(
            context = context, title = "t", prompt = "p",
            agents = listOf(agent("a"))
        )
        ReportStorage.markAgentError(context, report.id, "a", httpStatus = 500, errorMessage = "boom")
        ReportStorage.markAgentSuccess(
            context, report.id, "a", httpStatus = 200,
            responseHeaders = null, responseBody = "ok",
            tokenUsage = null, cost = null
        )
        val agent = ReportStorage.getReport(context, report.id)!!.agents[0]
        assertThat(agent.errorMessage).isNull()
        assertThat(agent.responseBody).isEqualTo("ok")
    }

    @Test fun updateReportPromptAndTitle_overwrites_those_fields_only() {
        val report = ReportStorage.createReport(
            context = context, title = "old", prompt = "old prompt",
            agents = listOf(agent("a"))
        )
        val ok = ReportStorage.updateReportPromptAndTitle(context, report.id, "new", "new prompt")
        assertThat(ok).isTrue()
        val reloaded = ReportStorage.getReport(context, report.id)!!
        assertThat(reloaded.title).isEqualTo("new")
        assertThat(reloaded.prompt).isEqualTo("new prompt")
        assertThat(reloaded.agents.map { it.agentId }).containsExactly("a")
    }

    @Test fun removeAgent_drops_row_and_recomputes_total_cost() {
        val report = ReportStorage.createReport(
            context = context, title = "t", prompt = "p",
            agents = listOf(agent("a"), agent("b"))
        )
        ReportStorage.markAgentSuccess(context, report.id, "a", 200, null, "x", null, 0.05)
        ReportStorage.markAgentSuccess(context, report.id, "b", 200, null, "y", null, 0.10)
        assertThat(ReportStorage.getReport(context, report.id)!!.totalCost).isWithin(1e-9).of(0.15)

        val removed = ReportStorage.removeAgent(context, report.id, "a")
        assertThat(removed).isTrue()
        val reloaded = ReportStorage.getReport(context, report.id)!!
        assertThat(reloaded.agents.map { it.agentId }).containsExactly("b")
        assertThat(reloaded.totalCost).isWithin(1e-9).of(0.10)
    }

    @Test fun removeAgent_returns_false_for_unknown_id() {
        val report = ReportStorage.createReport(
            context = context, title = "t", prompt = "p", agents = listOf(agent("a"))
        )
        assertThat(ReportStorage.removeAgent(context, report.id, "nope")).isFalse()
    }

    @Test fun deleteReport_removes_file_and_secondary_dir() {
        val report = ReportStorage.createReport(
            context = context, title = "t", prompt = "p", agents = emptyList()
        )
        // Plant a secondary so deleteReport's cascade is exercised.
        SecondaryResultStorage.init(context)
        SecondaryResultStorage.create(
            context = context, reportId = report.id,
            kind = SecondaryKind.SUMMARIZE,
            providerId = "UNIT", model = "m",
            agentName = "n"
        )
        assertThat(SecondaryResultStorage.listForReport(context, report.id)).hasSize(1)

        ReportStorage.deleteReport(context, report.id)

        assertThat(ReportStorage.getReport(context, report.id)).isNull()
        assertThat(SecondaryResultStorage.listForReport(context, report.id)).isEmpty()
    }

    @Test fun getAllReports_returns_newest_first_by_timestamp() {
        val a = ReportStorage.createReport(context, "first", "p", emptyList())
        Thread.sleep(5)
        val b = ReportStorage.createReport(context, "second", "p", emptyList())
        Thread.sleep(5)
        val c = ReportStorage.createReport(context, "third", "p", emptyList())

        val ids = ReportStorage.getAllReports(context).map { it.id }
        assertThat(ids).containsExactly(c.id, b.id, a.id).inOrder()
    }
}
