package com.ai.viewmodel

import com.ai.data.AgentParameters
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for the pre-flight report plan (audit R10 / T05): a given selection +
 * settings must produce the exact planned call counts, provider breakdown,
 * capability flags, skip list, and cost ceiling — the contract a preview UI and
 * the launch path both rely on.
 */
class ReportExecutionPlanTest {

    private fun model(p: String, m: String, price: Double? = null, local: Boolean = false) =
        PlannedModel(providerId = p, model = m, outputPricePerToken = price, isLocal = local)

    @Test fun primary_count_and_per_provider_breakdown() {
        val plan = buildReportExecutionPlan(
            selected = listOf(model("openai", "gpt-4o"), model("openai", "gpt-4o-mini"), model("anthropic", "claude")),
        )
        assertThat(plan.primaryCallCount).isEqualTo(3)
        assertThat(plan.perProviderCounts).containsExactly("openai", 2, "anthropic", 1)
        assertThat(plan.providers).containsExactly("anthropic", "openai").inOrder()
        assertThat(plan.metadataCallCount).isEqualTo(0)
        assertThat(plan.secondaryCallCount).isEqualTo(0)
        assertThat(plan.totalCallCount).isEqualTo(3)
    }

    @Test fun metadata_calls_count_report_level_plus_per_model() {
        val plan = buildReportExecutionPlan(
            selected = listOf(model("p", "a"), model("p", "b")),
            metadataEnabled = true,
            reportTitleAiMode = true,    // +1
            reportIconEnabled = true,    // +1
            reportLanguageEnabled = true, // +1
            perModelTitleEnabled = true, // +2 (one per model)
            perModelIconEnabled = true,  // +2
        )
        assertThat(plan.metadataCallCount).isEqualTo(3 + 2 + 2)
        assertThat(plan.totalCallCount).isEqualTo(2 + 7)
    }

    @Test fun metadata_master_switch_forces_all_sub_flags_off() {
        val plan = buildReportExecutionPlan(
            selected = listOf(model("p", "a")),
            metadataEnabled = false,
            reportTitleAiMode = true,
            reportIconEnabled = true,
            perModelTitleEnabled = true,
        )
        assertThat(plan.metadataCallCount).isEqualTo(0)
    }

    @Test fun auto_secondary_needs_at_least_two_answers() {
        val one = buildReportExecutionPlan(listOf(model("p", "a")), autoRerankAndModeration = true)
        val two = buildReportExecutionPlan(listOf(model("p", "a"), model("p", "b")), autoRerankAndModeration = true)
        assertThat(one.secondaryCallCount).isEqualTo(0)
        assertThat(two.secondaryCallCount).isEqualTo(2)
    }

    @Test fun capability_flags_reflect_params() {
        val plan = buildReportExecutionPlan(
            selected = listOf(model("p", "a")),
            params = AgentParameters(webSearchTool = true, reasoningEffort = "high"),
        )
        assertThat(plan.webSearchEnabled).isTrue()
        assertThat(plan.reasoningRequested).isTrue()

        val plain = buildReportExecutionPlan(listOf(model("p", "a")), params = AgentParameters())
        assertThat(plain.webSearchEnabled).isFalse()
        assertThat(plain.reasoningRequested).isFalse()
    }

    @Test fun skipped_models_are_carried_with_reasons() {
        val plan = buildReportExecutionPlan(
            selected = listOf(model("p", "ok")),
            skipped = listOf(SkippedModel("p", "blocked-one", "blocked"), SkippedModel("p", "nokey", "no API key")),
        )
        assertThat(plan.primaryCallCount).isEqualTo(1)
        assertThat(plan.skipped.map { it.model }).containsExactly("blocked-one", "nokey")
        assertThat(plan.summaryLines()).contains("2 models skipped")
    }

    @Test fun local_models_are_flagged() {
        val plan = buildReportExecutionPlan(listOf(model("Local", "gemma", local = true), model("openai", "gpt-4o")))
        assertThat(plan.includesLocalModels).isTrue()
    }

    @Test fun cost_ceiling_sums_priced_models_times_token_budget() {
        val plan = buildReportExecutionPlan(
            selected = listOf(
                model("p", "a", price = 0.000_01),
                model("p", "b", price = 0.000_02),
                model("p", "unpriced", price = null),
            ),
            params = AgentParameters(maxTokens = 1_000),
        )
        // (0.00001 + 0.00002) * 1000 = 0.03; the unpriced model contributes nothing.
        assertThat(plan.estimatedOutputCostCeilingUsd).isWithin(1e-9).of(0.03)
    }

    @Test fun cost_ceiling_is_null_without_prices_or_token_budget() {
        val noPrice = buildReportExecutionPlan(listOf(model("p", "a")), params = AgentParameters(maxTokens = 100))
        val noBudget = buildReportExecutionPlan(listOf(model("p", "a", price = 0.001)), params = AgentParameters(maxTokens = null))
        assertThat(noPrice.estimatedOutputCostCeilingUsd).isNull()
        assertThat(noBudget.estimatedOutputCostCeilingUsd).isNull()
    }

    @Test fun summary_lines_describe_the_run() {
        val plan = buildReportExecutionPlan(
            selected = listOf(model("openai", "gpt-4o"), model("anthropic", "claude")),
            params = AgentParameters(reasoningEffort = "high"),
            metadataEnabled = true,
            reportIconEnabled = true,
        )
        val lines = plan.summaryLines()
        assertThat(lines).contains("2 primary calls across 2 providers")
        assertThat(lines).contains("reasoning requested")
        assertThat(lines.last()).contains("total API call")
    }
}
