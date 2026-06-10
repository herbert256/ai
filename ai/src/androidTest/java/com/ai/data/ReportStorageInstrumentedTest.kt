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
            agents = listOf(agent("a")), config = CreateReportConfig(explicitId = explicit)
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
            agents = listOf(agent("a")), config = CreateReportConfig(sourceReportId = src.id)
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
        // Per-agent cost is persisted on the agent row (asserted above), but the
        // report's totalCost is owned by the append-only cost ledger
        // (`apiCallCosts`), which markAgentSuccess does not write — a fresh report
        // is ledger-current with an empty ledger, so totalCost stays 0 here. The
        // ledger path is covered by the cost-ledger tests below.
        assertThat(reloaded.totalCost).isEqualTo(0.0)
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

    @Test fun removeAgent_drops_row_and_preserves_removed_cost() {
        val report = ReportStorage.createReport(
            context = context, title = "t", prompt = "p",
            agents = listOf(agent("a"), agent("b"))
        )
        ReportStorage.markAgentSuccess(context, report.id, "a", 200, null, "x", null, 0.05)
        ReportStorage.markAgentSuccess(context, report.id, "b", 200, null, "y", null, 0.10)
        // Per-agent cost lands on each agent row.
        assertThat(ReportStorage.getReport(context, report.id)!!.agents.mapNotNull { it.cost })
            .containsExactly(0.05, 0.10)

        val removed = ReportStorage.removeAgent(context, report.id, "a")
        assertThat(removed).isTrue()
        val reloaded = ReportStorage.getReport(context, report.id)!!
        assertThat(reloaded.agents.map { it.agentId }).containsExactly("b")
        // The removed row's per-agent spend (0.05) is rolled into
        // costsFromDeletedItems so the report's lifetime total stays stable
        // after the row is dropped.
        assertThat(reloaded.costsFromDeletedItems).isWithin(1e-9).of(0.05)
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
            kind = SecondaryKind.META,
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

    // ---- cost ledger + load-failure tolerance (audit T06) ----

    private fun costRecord(id: String) = ReportApiCallCost(
        id = id, type = "report", provider = "UNIT", model = "m", pricingTier = "t",
        inputTokens = 10, outputTokens = 20, inputCost = 0.001, outputCost = 0.002
    )

    @Test fun appendApiCallCost_is_idempotent_for_a_duplicate_record_id() {
        val report = ReportStorage.createReport(context, "ledger", "p", listOf(agent("a")))
        val rec = costRecord("rec-1")

        val first = ReportStorage.appendApiCallCost(context.filesDir, report.id, rec)
        // Same record id again → dedup: returns null and does NOT append a 2nd row
        // (a non-null return would double-count callCount/tokens/cost on replay).
        val second = ReportStorage.appendApiCallCost(context.filesDir, report.id, rec)

        assertThat(first).isNotNull()
        assertThat(second).isNull()
        assertThat(ReportStorage.getReport(context, report.id)!!.apiCallCosts.map { it.id })
            .containsExactly("rec-1")
    }

    @Test fun corrupted_report_json_is_skipped_and_reported_not_crashing() {
        val good = ReportStorage.createReport(context, "good", "p", listOf(agent("a")))
        // Drop a garbage file into the reports dir alongside the valid one.
        java.io.File(java.io.File(context.filesDir, "reports"), "broken.json")
            .writeText("{ this is not valid json")

        val all = ReportStorage.getAllReports(context)

        // The valid report still loads; the corrupt file doesn't crash the list call.
        assertThat(all.map { it.id }).contains(good.id)
        assertThat(ReportStorage.getLastLoadFailures(context).map { it.filename })
            .contains("broken.json")
    }

    // ---- additive vs idempotent cost mutations (audit T06) ----

    @Test fun markAgentSuccess_same_trace_is_idempotent_on_cost_and_tokens() {
        val report = ReportStorage.createReport(context, "t", "p", listOf(agent("a")))
        ReportStorage.markAgentSuccess(context, report.id, "a", 200, null, "body",
            TokenUsage(10, 20), 0.05, traceFile = "trace-1.json")
        // A retry/fallback re-reporting the SAME attempt (same traceFile) must not
        // double-count its cost or tokens.
        ReportStorage.markAgentSuccess(context, report.id, "a", 200, null, "body",
            TokenUsage(10, 20), 0.05, traceFile = "trace-1.json")
        val ag = ReportStorage.getReport(context, report.id)!!.agents.first { it.agentId == "a" }
        assertThat(ag.cost!!).isWithin(1e-9).of(0.05)
        assertThat(ag.tokenUsage!!.inputTokens).isEqualTo(10)
        assertThat(ag.tokenUsage!!.outputTokens).isEqualTo(20)
    }

    @Test fun markAgentSuccess_distinct_traces_accumulate_cost_and_tokens() {
        val report = ReportStorage.createReport(context, "t", "p", listOf(agent("a")))
        ReportStorage.markAgentSuccess(context, report.id, "a", 200, null, "body1",
            TokenUsage(10, 20), 0.05, traceFile = "trace-1.json")
        // A genuine re-dispatch (Regenerate-batch retry → success, different trace)
        // is additive: the row shows prior + new spend.
        ReportStorage.markAgentSuccess(context, report.id, "a", 200, null, "body2",
            TokenUsage(3, 7), 0.05, traceFile = "trace-2.json")
        val ag = ReportStorage.getReport(context, report.id)!!.agents.first { it.agentId == "a" }
        assertThat(ag.cost!!).isWithin(1e-9).of(0.10)
        assertThat(ag.tokenUsage!!.inputTokens).isEqualTo(13)
        assertThat(ag.tokenUsage!!.outputTokens).isEqualTo(27)
    }

    @Test fun markAgentSuccess_records_inOut_cost_when_aggregate_cost_is_null() {
        // Metadata-style cost write: no aggregate `cost`, but split in/out costs
        // still land on the agent row (Bug 28 — a cost bump with cost=null must
        // not be silently dropped).
        val report = ReportStorage.createReport(context, "t", "p", listOf(agent("a")))
        ReportStorage.markAgentSuccess(context, report.id, "a", 200, null, "body",
            TokenUsage(1, 1), null, inputCost = 0.01, outputCost = 0.02, traceFile = "t.json")
        val ag = ReportStorage.getReport(context, report.id)!!.agents.first { it.agentId == "a" }
        assertThat(ag.inputCost!!).isWithin(1e-9).of(0.01)
        assertThat(ag.outputCost!!).isWithin(1e-9).of(0.02)
    }

    @Test fun markAgentError_after_success_preserves_recorded_cost() {
        val report = ReportStorage.createReport(context, "t", "p", listOf(agent("a")))
        ReportStorage.markAgentSuccess(context, report.id, "a", 200, null, "body",
            TokenUsage(10, 20), 0.05, traceFile = "trace-1.json")
        // A later failed attempt passes cost=null and must not zero or alter the
        // spend already booked for the successful call.
        ReportStorage.markAgentError(context, report.id, "a", httpStatus = 500, errorMessage = "boom")
        val ag = ReportStorage.getReport(context, report.id)!!.agents.first { it.agentId == "a" }
        assertThat(ag.cost!!).isWithin(1e-9).of(0.05)
    }

    @Test fun appendApiCallCost_accumulates_distinct_records_in_ledger() {
        val report = ReportStorage.createReport(context, "ledger", "p", listOf(agent("a")))
        ReportStorage.appendApiCallCost(context.filesDir, report.id, costRecord("rec-1"))
        ReportStorage.appendApiCallCost(context.filesDir, report.id, costRecord("rec-2"))
        val reloaded = ReportStorage.getReport(context, report.id)!!
        assertThat(reloaded.apiCallCosts.map { it.id }).containsExactly("rec-1", "rec-2")
        // The ledger is the cost source of truth for current reports, so totalCost
        // is the sum of every row's in + out cost.
        assertThat(reloaded.totalCost).isWithin(1e-9).of((0.001 + 0.002) * 2)
    }

    @Test fun reconcileApiCallCostLedger_returns_null_for_current_report() {
        // Reports created now are already ledger-current, so reconciliation is a
        // no-op: it returns null, which is what stops SettingsPreferences from
        // applying a spurious usage-stat delta for an unchanged report.
        val report = ReportStorage.createReport(context, "current", "p", listOf(agent("a")))
        assertThat(ReportStorage.reconcileApiCallCostLedger(context, report.id)).isNull()
    }
}
