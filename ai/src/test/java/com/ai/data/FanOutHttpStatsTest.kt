package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Fan Out HTTP stats are now an in-memory, per-run tally ([RunHttpStats]) that
 * counts EVERY response received (each retry attempt included), not the final
 * per-pair status. RunHttpStats is a process-wide singleton with no reset, so
 * each test uses a distinct runId to stay independent.
 */
class FanOutHttpStatsTest {
    @Test fun groups_http_responses_by_responder_model() {
        val runId = "test-run-group"
        listOf(200, 429, 529, 404, 503, 201).forEach { RunHttpStats.record(runId, "OpenAI", "gpt-a", it) }
        RunHttpStats.record(runId, "OpenAI", "gpt-a", 0)        // non-positive → skipped (not an HTTP response)
        RunHttpStats.record(runId, null, "gpt-a", 200)          // missing provider → skipped
        RunHttpStats.record(runId, "Anthropic", "claude-b", 200)
        RunHttpStats.record(runId, "Anthropic", "claude-b", 500)

        val stats = RunHttpStats.statsForRun(runId)
        assertThat(stats.totalResponses).isEqualTo(8)  // 6 valid OpenAI + 2 Anthropic
        assertThat(stats.modelCount).isEqualTo(2)

        val openAi = stats.rows.first { it.modelKey == "OpenAI|gpt-a" }.counts
        assertThat(openAi.ok200).isEqualTo(1)
        assertThat(openAi.rate429).isEqualTo(1)
        assertThat(openAi.overloaded529).isEqualTo(1)
        assertThat(openAi.client4xx).isEqualTo(1)
        assertThat(openAi.server5xx).isEqualTo(1)
        assertThat(openAi.other).isEqualTo(1)          // 201
        assertThat(openAi.total).isEqualTo(6)

        val anthropic = stats.rows.first { it.modelKey == "Anthropic|claude-b" }.counts
        assertThat(anthropic.ok200).isEqualTo(1)
        assertThat(anthropic.server5xx).isEqualTo(1)
    }

    @Test fun special_buckets_are_not_counted_in_broad_families() {
        val runId = "test-run-special"
        RunHttpStats.record(runId, "P", "m", 429)
        RunHttpStats.record(runId, "P", "m", 529)
        val counts = RunHttpStats.statsForRun(runId).rows.single().counts
        assertThat(counts.rate429).isEqualTo(1)
        assertThat(counts.overloaded529).isEqualTo(1)
        assertThat(counts.client4xx).isEqualTo(0)
        assertThat(counts.server5xx).isEqualTo(0)
    }

    @Test fun unknown_run_is_empty() {
        val stats = RunHttpStats.statsForRun("never-recorded")
        assertThat(stats.totalResponses).isEqualTo(0)
        assertThat(stats.modelCount).isEqualTo(0)
        assertThat(stats.rows).isEmpty()
        assertThat(RunHttpStats.hasRun("never-recorded")).isFalse()
    }
}
