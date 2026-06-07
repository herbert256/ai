package com.ai.viewmodel

import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrokenWorkPolicyTest {
    @Test
    fun active_compare_run_is_not_reported_as_broken() {
        val row = row(
            id = "compare-1",
            kind = SecondaryKind.COMPARE,
            timestamp = 0,
            content = null,
            compareRunId = REPORT_ID
        )

        val active = BrokenWorkPolicy.detectBatches(
            REPORT_ID,
            "Report",
            10,
            listOf(row),
            BrokenWorkLiveState(nowMs = OLD_ENOUGH, activeCompareRunKeys = setOf(REPORT_ID))
        )
        val inactive = BrokenWorkPolicy.detectBatches(
            REPORT_ID,
            "Report",
            10,
            listOf(row),
            BrokenWorkLiveState(nowMs = OLD_ENOUGH)
        )

        assertThat(active).isEmpty()
        assertThat(inactive.single().kind).isEqualTo(BatchFamilyKind.COMPARE)
        assertThat(inactive.single().unfinishedCount).isEqualTo(1)
    }

    @Test
    fun young_blank_placeholders_are_not_reported_until_stale() {
        val row = fanOutRow(id = "fan-1", timestamp = OLD_ENOUGH - 1_000, content = null)

        val young = BrokenWorkPolicy.detectBatches(
            REPORT_ID,
            "Report",
            10,
            listOf(row),
            BrokenWorkLiveState(nowMs = OLD_ENOUGH)
        )
        val stale = BrokenWorkPolicy.detectBatches(
            REPORT_ID,
            "Report",
            10,
            listOf(row.copy(timestamp = 0)),
            BrokenWorkLiveState(nowMs = OLD_ENOUGH)
        )

        assertThat(young).isEmpty()
        assertThat(stale.single().kind).isEqualTo(BatchFamilyKind.FAN_OUT)
        assertThat(stale.single().unfinishedCount).isEqualTo(1)
    }

    @Test
    fun fan_meta_without_start_marker_is_not_broken_work() {
        val row = fanOutRow(id = "fan-1", timestamp = 0, content = "answer")

        val batches = BrokenWorkPolicy.detectBatches(
            REPORT_ID,
            "Report",
            10,
            listOf(row),
            BrokenWorkLiveState(nowMs = OLD_ENOUGH)
        )

        assertThat(batches.map { it.kind }).doesNotContain(BatchFamilyKind.FAN_META)
    }

    @Test
    fun fan_meta_start_marker_surfaces_unfinished_rows() {
        val row = fanOutRow(
            id = "fan-1",
            timestamp = 0,
            content = "answer",
            titleRunId = "fan-meta-run",
            iconRunId = "fan-meta-run"
        )

        val batches = BrokenWorkPolicy.detectBatches(
            REPORT_ID,
            "Report",
            10,
            listOf(row),
            BrokenWorkLiveState(nowMs = OLD_ENOUGH)
        )

        val fanMeta = batches.single { it.kind == BatchFamilyKind.FAN_META }
        assertThat(fanMeta.unfinishedCount).isEqualTo(1)
        assertThat(fanMeta.errorCount).isEqualTo(0)
    }

    @Test
    fun fan_meta_start_marker_is_row_scoped_not_batch_scoped() {
        val marked = fanOutRow(
            id = "marked",
            timestamp = 0,
            content = "answer",
            titleRunId = "fan-meta-run"
        )
        val unmarked = fanOutRow(id = "unmarked", timestamp = 0, content = "answer")

        val batches = BrokenWorkPolicy.detectBatches(
            REPORT_ID,
            "Report",
            10,
            listOf(marked, unmarked),
            BrokenWorkLiveState(nowMs = OLD_ENOUGH)
        )
        val batch = batches.single { it.kind == BatchFamilyKind.FAN_META }

        assertThat(batch.unfinishedCount).isEqualTo(1)
        assertThat(
            BrokenWorkPolicy.matchingRows(
                listOf(marked, unmarked),
                batch,
                BrokenItemMode.UNFINISHED,
                BrokenWorkLiveState(nowMs = OLD_ENOUGH)
            ).map { it.id }
        ).containsExactly("marked")
    }

    @Test
    fun matching_rows_uses_the_same_fan_meta_marker_policy() {
        val unmarked = fanOutRow(id = "unmarked", timestamp = 0, content = "answer")
        val marked = fanOutRow(
            id = "marked",
            timestamp = 0,
            content = "answer",
            titleRunId = "fan-meta-run"
        )
        val batch = BrokenBatch(
            reportId = REPORT_ID,
            reportTitle = "Report",
            kind = BatchFamilyKind.FAN_META,
            key = "$REPORT_ID|$PROMPT_ID",
            batchName = "Fan Meta",
            unfinishedCount = 1,
            errorCount = 0,
            timestamp = 10
        )

        assertThat(
            BrokenWorkPolicy.matchingRows(
                listOf(unmarked),
                batch,
                BrokenItemMode.UNFINISHED,
                BrokenWorkLiveState(nowMs = OLD_ENOUGH)
            )
        ).isEmpty()
        assertThat(
            BrokenWorkPolicy.matchingRows(
                listOf(marked),
                batch,
                BrokenItemMode.UNFINISHED,
                BrokenWorkLiveState(nowMs = OLD_ENOUGH)
            ).map { it.id }
        ).containsExactly("marked")
    }

    @Test
    fun errored_compare_cell_is_reported_as_an_error_not_unfinished() {
        val row = row(
            id = "c", kind = SecondaryKind.COMPARE, timestamp = 0, content = null,
            errorMessage = "boom", durationMs = 0, compareRunId = REPORT_ID
        )
        val b = BrokenWorkPolicy.detectBatches(
            REPORT_ID, "Report", 10, listOf(row), BrokenWorkLiveState(nowMs = OLD_ENOUGH)
        ).single { it.kind == BatchFamilyKind.COMPARE }
        assertThat(b.errorCount).isEqualTo(1)
        assertThat(b.unfinishedCount).isEqualTo(0)
    }

    @Test
    fun in_flight_row_is_not_reported() {
        val row = row(id = "c", kind = SecondaryKind.COMPARE, timestamp = 0, content = null, compareRunId = REPORT_ID)
        val batches = BrokenWorkPolicy.detectBatches(
            REPORT_ID, "Report", 10, listOf(row),
            BrokenWorkLiveState(nowMs = OLD_ENOUGH, inFlightRowIds = setOf("c"))
        )
        assertThat(batches).isEmpty()
    }

    @Test
    fun tournament_match_error_is_reported() {
        val row = row(
            id = "t", kind = SecondaryKind.TOURNAMENT, timestamp = 0, content = null,
            errorMessage = "lost", durationMs = 0, tournamentRole = "MATCH"
        )
        val b = BrokenWorkPolicy.detectBatches(
            REPORT_ID, "Report", 10, listOf(row), BrokenWorkLiveState(nowMs = OLD_ENOUGH)
        ).single()
        assertThat(b.kind).isEqualTo(BatchFamilyKind.TOURNAMENT)
        assertThat(b.errorCount).isEqualTo(1)
    }

    @Test
    fun translation_run_unfinished_then_suppressed_when_active() {
        val row = row(
            id = "tr", kind = SecondaryKind.TRANSLATE, timestamp = 0, content = null,
            translationRunId = "run-x", targetLanguage = "French"
        )
        val reported = BrokenWorkPolicy.detectBatches(
            REPORT_ID, "Report", 10, listOf(row), BrokenWorkLiveState(nowMs = OLD_ENOUGH)
        ).single()
        assertThat(reported.kind).isEqualTo(BatchFamilyKind.TRANSLATION)
        assertThat(reported.unfinishedCount).isEqualTo(1)

        val active = BrokenWorkPolicy.detectBatches(
            REPORT_ID, "Report", 10, listOf(row),
            BrokenWorkLiveState(nowMs = OLD_ENOUGH, activeTranslationRunIds = setOf("run-x"))
        )
        assertThat(active).isEmpty()
    }

    @Test
    fun standalone_rerank_error_falls_into_the_other_family() {
        val row = row(
            id = "rr", kind = SecondaryKind.RERANK, timestamp = 0, content = null,
            errorMessage = "nope", durationMs = 0
        )
        val b = BrokenWorkPolicy.detectBatches(
            REPORT_ID, "Report", 10, listOf(row), BrokenWorkLiveState(nowMs = OLD_ENOUGH)
        ).single()
        assertThat(b.kind).isEqualTo(BatchFamilyKind.OTHER)
        assertThat(b.errorCount).isEqualTo(1)
    }

    private fun fanOutRow(
        id: String,
        timestamp: Long,
        content: String?,
        titleRunId: String? = null,
        iconRunId: String? = null
    ) = row(
        id = id,
        kind = SecondaryKind.META,
        timestamp = timestamp,
        content = content,
        metaPromptId = PROMPT_ID,
        metaPromptName = "Meta",
        fanOutSourceAgentId = "source",
        titleRunId = titleRunId,
        iconRunId = iconRunId
    )

    private fun row(
        id: String,
        kind: SecondaryKind,
        timestamp: Long,
        content: String?,
        errorMessage: String? = null,
        durationMs: Long? = null,
        metaPromptId: String? = null,
        metaPromptName: String? = null,
        fanOutSourceAgentId: String? = null,
        titleRunId: String? = null,
        iconRunId: String? = null,
        compareRunId: String? = null,
        tournamentRole: String? = null,
        translationRunId: String? = null,
        targetLanguage: String? = null
    ) = SecondaryResult(
        id = id,
        reportId = REPORT_ID,
        kind = kind,
        providerId = "provider",
        model = "model",
        agentName = "agent",
        timestamp = timestamp,
        content = content,
        errorMessage = errorMessage,
        durationMs = durationMs,
        metaPromptId = metaPromptId,
        metaPromptName = metaPromptName,
        fanOutSourceAgentId = fanOutSourceAgentId,
        titleRunId = titleRunId,
        iconRunId = iconRunId,
        compareRunId = compareRunId,
        tournamentRole = tournamentRole,
        translationRunId = translationRunId,
        targetLanguage = targetLanguage
    )

    private companion object {
        const val REPORT_ID = "report-1"
        const val PROMPT_ID = "prompt-1"
        const val OLD_ENOUGH = BrokenWorkPolicy.DEFAULT_STALE_PLACEHOLDER_MS + 1_000L
    }
}
