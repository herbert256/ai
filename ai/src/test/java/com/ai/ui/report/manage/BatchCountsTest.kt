package com.ai.ui.report.manage

import com.ai.data.BatchItemStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BatchCountsTest {
    @Test
    fun fixedModelParksShortBenchedPendingRunningAndErrorItems() {
        val items = listOf(
            TestItem("pending", BatchItemStatus.PENDING, benched = true),
            TestItem("running", BatchItemStatus.RUNNING, benched = true),
            TestItem("error", BatchItemStatus.ERROR, benched = true),
            TestItem("done", BatchItemStatus.DONE, benched = true),
        )

        val summary = deriveBatchSummary(
            items = items,
            idOf = { it.id },
            statusOf = { it.status },
            throttledIds = emptySet(),
            family = BatchFamily.FIXED_MODEL,
            benchedOf = { it.benched },
        )

        assertThat(summary.showBenchColumn).isTrue()
        assertThat(summary.displayError).isEqualTo(0)
        assertThat(summary.activeOutstanding).isTrue()
        assertThat(summary.counts.done).isEqualTo(1)
        assertThat(summary.counts.bench).isEqualTo(3)
        assertThat(summary.counts.error).isEqualTo(0)
        assertThat(summary.counts.running).isEqualTo(0)
        assertThat(summary.counts.queued).isEqualTo(0)
    }

    @Test
    fun workerPoolNeverProducesBenchEvenWhenCooldownPredicateWouldMatch() {
        val items = listOf(
            TestItem("pending", BatchItemStatus.PENDING, benched = true),
            TestItem("running", BatchItemStatus.RUNNING, benched = true),
            TestItem("error", BatchItemStatus.ERROR, benched = true),
            TestItem("done", BatchItemStatus.DONE, benched = true),
        )

        val summary = deriveBatchSummary(
            items = items,
            idOf = { it.id },
            statusOf = { it.status },
            throttledIds = emptySet(),
            family = BatchFamily.WORKER_POOL,
            benchedOf = { it.benched },
        )

        assertThat(summary.showBenchColumn).isFalse()
        assertThat(summary.counts.bench).isEqualTo(0)
        assertThat(summary.displayError).isEqualTo(1)
        assertThat(summary.counts.error).isEqualTo(1)
        assertThat(summary.counts.running).isEqualTo(1)
        assertThat(summary.counts.queued).isEqualTo(1)
        assertThat(summary.activeOutstanding).isTrue()
    }

    @Test
    fun activeOutstandingIncludesWaitAndFixedModelBenchOnly() {
        val fixed = deriveBatchSummary(
            items = listOf(
                TestItem("wait", BatchItemStatus.PENDING),
                TestItem("bench", BatchItemStatus.ERROR, benched = true),
            ),
            idOf = { it.id },
            statusOf = { it.status },
            throttledIds = setOf("wait"),
            family = BatchFamily.FIXED_MODEL,
            benchedOf = { it.benched },
        )
        val worker = deriveBatchSummary(
            items = listOf(
                TestItem("wait", BatchItemStatus.PENDING),
                TestItem("bench", BatchItemStatus.ERROR, benched = true),
            ),
            idOf = { it.id },
            statusOf = { it.status },
            throttledIds = setOf("wait"),
            family = BatchFamily.WORKER_POOL,
            benchedOf = { it.benched },
        )

        assertThat(fixed.counts.wait).isEqualTo(1)
        assertThat(fixed.counts.queued).isEqualTo(0)
        assertThat(fixed.counts.bench).isEqualTo(1)
        assertThat(fixed.activeOutstanding).isTrue()
        assertThat(worker.counts.wait).isEqualTo(1)
        assertThat(worker.counts.queued).isEqualTo(0)
        assertThat(worker.counts.bench).isEqualTo(0)
        assertThat(worker.counts.error).isEqualTo(1)
        assertThat(worker.activeOutstanding).isTrue()
    }

    @Test
    fun doneWinsOverBenchAndWait() {
        val summary = deriveBatchSummary(
            items = listOf(TestItem("done", BatchItemStatus.DONE, benched = true)),
            idOf = { it.id },
            statusOf = { it.status },
            throttledIds = setOf("done"),
            family = BatchFamily.FIXED_MODEL,
            benchedOf = { it.benched },
        )

        assertThat(summary.counts.done).isEqualTo(1)
        assertThat(summary.counts.bench).isEqualTo(0)
        assertThat(summary.counts.wait).isEqualTo(0)
        assertThat(summary.activeOutstanding).isFalse()
    }

    private data class TestItem(
        val id: String,
        val status: BatchItemStatus,
        val benched: Boolean = false,
    )
}
