package com.ai.viewmodel

import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [BatchResume] caps how many times the 30s background sweep re-dispatches an
 * incomplete secondary row before giving up — without it, a row that can never
 * complete (provider removed, etc.) would be re-run and re-billed forever
 * (audit T03). `attempts` is a process-global map, so each test uses unique row
 * ids to stay isolated.
 */
class BatchResumeTest {

    private var seq = 0
    private fun row(id: String) = SecondaryResult(
        id = id,
        reportId = "r",
        kind = SecondaryKind.META,
        providerId = "p",
        model = "m",
        agentName = "a",
        timestamp = 0L,
        content = null,
    )

    @Test fun fresh_row_is_returned_for_retry_and_not_terminalized() {
        val terminated = mutableListOf<String>()
        val r = row("brt-fresh")

        val retry = BatchResume.capForRetry(listOf(r)) { terminated += it.id }

        assertThat(retry.map { it.id }).containsExactly("brt-fresh")
        assertThat(terminated).isEmpty()
    }

    @Test fun row_survives_MAX_ATTEMPTS_retries_then_is_terminalized() {
        val terminated = mutableListOf<String>()
        val r = row("brt-max")
        val term: (SecondaryResult) -> Unit = { terminated += it.id }

        // MAX_ATTEMPTS (3) re-dispatches all return the row for retry.
        repeat(BatchResume.MAX_ATTEMPTS) {
            assertThat(BatchResume.capForRetry(listOf(r), term).map { it.id })
                .containsExactly("brt-max")
        }
        // The next sweep finds it at the cap → terminalize, drop from retry set.
        val afterCap = BatchResume.capForRetry(listOf(r), term)

        assertThat(afterCap).isEmpty()
        assertThat(terminated).containsExactly("brt-max")
    }

    @Test fun resetAttempts_restores_a_fresh_retry_budget() {
        val terminated = mutableListOf<String>()
        val r = row("brt-reset")
        val term: (SecondaryResult) -> Unit = { terminated += it.id }

        repeat(BatchResume.MAX_ATTEMPTS) { BatchResume.capForRetry(listOf(r), term) }
        BatchResume.resetAttempts(listOf("brt-reset"))

        // Fresh budget: a row that would have hit the cap is retried again.
        val retry = BatchResume.capForRetry(listOf(r), term)
        assertThat(retry.map { it.id }).containsExactly("brt-reset")
        assertThat(terminated).isEmpty()
    }

    @Test fun finalizeLeftover_terminalizes_every_row() {
        val terminated = mutableListOf<String>()
        val a = row("brt-fin-a")
        val b = row("brt-fin-b")

        BatchResume.finalizeLeftover(listOf(a, b)) { terminated += it.id }

        assertThat(terminated).containsExactly("brt-fin-a", "brt-fin-b")
    }

    @Test fun capForRetry_partitions_mixed_rows_by_attempt_count() {
        val terminated = mutableListOf<String>()
        val maxed = row("brt-mix-maxed")
        val fresh = row("brt-mix-fresh")
        val term: (SecondaryResult) -> Unit = { terminated += it.id }

        // Drive only `maxed` to the cap.
        repeat(BatchResume.MAX_ATTEMPTS) { BatchResume.capForRetry(listOf(maxed), term) }

        val retry = BatchResume.capForRetry(listOf(maxed, fresh), term)

        assertThat(retry.map { it.id }).containsExactly("brt-mix-fresh")
        assertThat(terminated).containsExactly("brt-mix-maxed")
    }
}
