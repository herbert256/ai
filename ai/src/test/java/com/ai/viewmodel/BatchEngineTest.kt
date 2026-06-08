package com.ai.viewmodel

import com.ai.data.BatchItem
import com.ai.data.BatchItemStatus
import com.ai.data.BatchRun
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import org.junit.Test

/**
 * Lifecycle tests for the shared [BatchEngine] base (audit R01 / T02): the
 * run/item job registries, cancel-by-run / cancel-by-item / cancel-all, the
 * resume-scan dedupe, and the unfinished-items query that every concrete engine
 * (Fan Out, Tournament, Judges, Compare) can adopt instead of re-rolling its own
 * maps. Plain CompletableJob()s exercise the registry mechanics synchronously.
 */
class BatchEngineTest {

    private data class Item(
        override val id: String,
        override val key: String,
        override val status: BatchItemStatus,
        override val totalCost: Double = 0.0,
    ) : BatchItem<String>

    private data class Run(
        override val reportId: String,
        override val items: Map<String, Item>,
    ) : BatchRun<String, Item>

    /** Concrete engine exposing the protected lifecycle API for testing. */
    private class TestEngine : BatchEngine<String, String, Item, Run>() {
        override fun copyWithItems(run: Run, items: Map<String, Item>) = run.copy(items = items)
        fun putRun(run: Run) { _runs.value = _runs.value + (run.reportId to run) }
        fun regRun(k: String, j: Job) = registerRunJob(k, j)
        fun regItem(id: String, j: Job) = registerItemJob(id, j)
        fun active(k: String) = isRunActive(k)
        fun cancelR(k: String, ids: Collection<String> = emptyList()) = cancelRun(k, ids)
        fun cancelI(id: String) = cancelItem(id)
        fun cancelEvery() = cancelAll()
        fun begin(k: String) = beginResumeScan(k)
        fun end(k: String) = endResumeScan(k)
        fun unfinished(k: String) = hasUnfinishedItems(k)
    }

    @Test fun runJob_isActive_until_cancelled() {
        val e = TestEngine()
        val job = Job()
        e.regRun("r", job)

        assertThat(e.active("r")).isTrue()
        e.cancelR("r")
        assertThat(job.isCancelled).isTrue()
        assertThat(e.active("r")).isFalse()
    }

    @Test fun registering_second_run_job_supersedes_and_cancels_first() {
        val e = TestEngine()
        val first = Job()
        val second = Job()
        e.regRun("r", first)
        e.regRun("r", second)

        assertThat(first.isCancelled).isTrue()
        assertThat(second.isActive).isTrue()
        assertThat(e.active("r")).isTrue()
    }

    @Test fun completed_run_job_self_removes_from_registry() {
        val e = TestEngine()
        val job = Job()
        e.regRun("r", job)

        job.complete()

        assertThat(e.active("r")).isFalse()
    }

    @Test fun cancelRun_cancels_run_and_its_item_jobs() {
        val e = TestEngine()
        val run = Job(); val i1 = Job(); val i2 = Job()
        e.regRun("r", run)
        e.regItem("i1", i1)
        e.regItem("i2", i2)

        e.cancelR("r", listOf("i1", "i2"))

        assertThat(run.isCancelled).isTrue()
        assertThat(i1.isCancelled).isTrue()
        assertThat(i2.isCancelled).isTrue()
    }

    @Test fun cancelItem_cancels_only_that_item() {
        val e = TestEngine()
        val i1 = Job(); val i2 = Job()
        e.regItem("i1", i1)
        e.regItem("i2", i2)

        e.cancelI("i1")

        assertThat(i1.isCancelled).isTrue()
        assertThat(i2.isActive).isTrue()
    }

    @Test fun cancelAll_cancels_runs_and_items_and_clears_resume_scans() {
        val e = TestEngine()
        val run = Job(); val item = Job()
        e.regRun("r", run)
        e.regItem("i", item)
        e.begin("r")

        e.cancelEvery()

        assertThat(run.isCancelled).isTrue()
        assertThat(item.isCancelled).isTrue()
        assertThat(e.active("r")).isFalse()
        // Resume slot was cleared, so it can be claimed afresh.
        assertThat(e.begin("r")).isTrue()
    }

    @Test fun resumeScan_dedupes_until_released() {
        val e = TestEngine()

        assertThat(e.begin("r")).isTrue()   // first claim wins
        assertThat(e.begin("r")).isFalse()  // concurrent scan blocked
        e.end("r")
        assertThat(e.begin("r")).isTrue()    // released → claimable again
    }

    @Test fun hasUnfinishedItems_reflects_item_statuses() {
        val e = TestEngine()
        e.putRun(Run("pending", mapOf("a" to Item("a", "a", BatchItemStatus.PENDING))))
        e.putRun(Run("running", mapOf("a" to Item("a", "a", BatchItemStatus.RUNNING))))
        e.putRun(Run("done", mapOf("a" to Item("a", "a", BatchItemStatus.DONE))))

        assertThat(e.unfinished("pending")).isTrue()
        assertThat(e.unfinished("running")).isTrue()
        assertThat(e.unfinished("done")).isFalse()
        assertThat(e.unfinished("no-such-run")).isFalse()
    }
}
