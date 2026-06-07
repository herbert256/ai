package com.ai.ui.report.manage

import com.ai.data.AppService
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GenerationPhaseSummariesTest {
    @Test
    fun fanOutSummariesAggregateVisibleStatusAndCostBuckets() {
        val summary = buildFanOutSummaries(
            listOf(
                fanOutRow(
                    id = "done",
                    content = "answer",
                    timestamp = 100L,
                    inputCost = 0.10,
                    outputCost = 0.20,
                    icon = "I",
                    iconInputCost = 0.01,
                    iconOutputCost = 0.02,
                    title = "Pair title",
                    titleInputCost = 0.03,
                    titleOutputCost = 0.04
                ),
                fanOutRow(
                    id = "pending",
                    content = null,
                    timestamp = 110L
                ),
                fanOutRow(
                    id = "empty-success",
                    content = "",
                    timestamp = 120L,
                    durationMs = 42L
                ),
                fanOutRow(
                    id = "pair-error",
                    content = null,
                    timestamp = 130L,
                    errorMessage = "failed",
                    inputCost = 0.30,
                    outputCost = 0.40
                ),
                fanOutRow(
                    id = "metadata-pending",
                    content = "answer without metadata",
                    timestamp = 140L,
                    inputCost = 0.50,
                    outputCost = 0.60
                ),
                fanOutRow(
                    id = "metadata-error",
                    content = "answer with metadata errors",
                    timestamp = 150L,
                    iconErrorMessage = "icon failed",
                    iconInputCost = 0.05,
                    iconOutputCost = 0.06,
                    titleErrorMessage = "title failed",
                    titleInputCost = 0.07,
                    titleOutputCost = 0.08
                )
            )
        ).single()

        assertThat(summary.metaPromptName).isEqualTo("Compare answers")
        assertThat(summary.pairCount).isEqualTo(6)
        assertThat(summary.pendingCount).isEqualTo(1)
        assertThat(summary.errorCount).isEqualTo(1)
        assertThat(summary.iconCount).isEqualTo(2)
        assertThat(summary.iconPendingCount).isEqualTo(1)
        assertThat(summary.iconErrorCount).isEqualTo(1)
        assertThat(summary.iconCost).isWithin(0.000001).of(0.14)
        assertThat(summary.titleCount).isEqualTo(2)
        assertThat(summary.titlePendingCount).isEqualTo(1)
        assertThat(summary.titleErrorCount).isEqualTo(1)
        assertThat(summary.titleCost).isWithin(0.000001).of(0.22)
        assertThat(summary.totalCost).isWithin(0.000001).of(2.10)
        assertThat(summary.timestamp).isEqualTo(150L)
    }

    @Test
    fun fanOutSummariesUseLegacyPromptIdFallbackSkipBlankKeysAndSortNewestFirst() {
        val summaries = buildFanOutSummaries(
            listOf(
                fanOutRow(id = "named", metaPromptName = "Named", timestamp = 10L),
                fanOutRow(id = "legacy", metaPromptName = "", metaPromptId = "legacy-prompt", timestamp = 30L),
                fanOutRow(id = "ignored", metaPromptName = "", metaPromptId = null, timestamp = 50L)
            )
        )

        assertThat(summaries.map { it.metaPromptName })
            .containsExactly("legacy-prompt", "Named")
            .inOrder()
        assertThat(summaries.map { it.pairCount }).containsExactly(1, 1).inOrder()
    }

    @Test
    fun translationRunSummariesGroupByRunOrLegacyLanguageAndSortNewestFirst() {
        val summaries = buildTranslationRunSummaries(
            listOf(
                translateRow(
                    id = "run-1-a",
                    runId = "run-1",
                    language = "French",
                    native = "Francais",
                    model = "translator-model",
                    timestamp = 100L,
                    inputCost = 0.10,
                    outputCost = 0.20
                ),
                translateRow(
                    id = "run-1-b",
                    runId = "run-1",
                    language = "French",
                    native = "Francais",
                    model = "translator-model",
                    timestamp = 120L,
                    errorMessage = "failed",
                    inputCost = 0.30,
                    outputCost = 0.40
                ),
                translateRow(
                    id = "legacy-dutch",
                    runId = null,
                    language = "Dutch",
                    native = "Nederlands",
                    model = "",
                    timestamp = 200L,
                    inputCost = 0.50,
                    outputCost = null
                )
            )
        )

        assertThat(summaries.map { it.runId }).containsExactly("lang:Dutch", "run-1").inOrder()

        val legacy = summaries[0]
        assertThat(legacy.targetLanguage).isEqualTo("Dutch")
        assertThat(legacy.targetLanguageNative).isEqualTo("Nederlands")
        assertThat(legacy.model).isNull()
        assertThat(legacy.callCount).isEqualTo(1)
        assertThat(legacy.errorCount).isEqualTo(0)
        assertThat(legacy.totalCost).isWithin(0.000001).of(0.50)
        assertThat(legacy.timestamp).isEqualTo(200L)

        val run = summaries[1]
        assertThat(run.targetLanguage).isEqualTo("French")
        assertThat(run.targetLanguageNative).isEqualTo("Francais")
        assertThat(run.model).isEqualTo("translator-model")
        assertThat(run.callCount).isEqualTo(2)
        assertThat(run.errorCount).isEqualTo(1)
        assertThat(run.totalCost).isWithin(0.000001).of(1.00)
        assertThat(run.timestamp).isEqualTo(120L)
    }

    private fun fanOutRow(
        id: String,
        metaPromptName: String? = "Compare answers",
        metaPromptId: String? = "prompt-1",
        content: String? = "answer",
        errorMessage: String? = null,
        timestamp: Long,
        durationMs: Long? = null,
        inputCost: Double? = null,
        outputCost: Double? = null,
        icon: String? = null,
        iconErrorMessage: String? = null,
        iconInputCost: Double = 0.0,
        iconOutputCost: Double = 0.0,
        title: String? = null,
        titleErrorMessage: String? = null,
        titleInputCost: Double = 0.0,
        titleOutputCost: Double = 0.0
    ) = SecondaryResult(
        id = id,
        reportId = "report-1",
        kind = SecondaryKind.META,
        providerId = AppService.LOCAL.id,
        model = "answerer-model",
        agentName = "Answerer",
        timestamp = timestamp,
        content = content,
        errorMessage = errorMessage,
        inputCost = inputCost,
        outputCost = outputCost,
        durationMs = durationMs,
        metaPromptId = metaPromptId,
        metaPromptName = metaPromptName,
        fanOutSourceAgentId = "source-agent",
        icon = icon,
        iconErrorMessage = iconErrorMessage,
        iconInputCost = iconInputCost,
        iconOutputCost = iconOutputCost,
        title = title,
        titleErrorMessage = titleErrorMessage,
        titleInputCost = titleInputCost,
        titleOutputCost = titleOutputCost
    )

    private fun translateRow(
        id: String,
        runId: String?,
        language: String,
        native: String,
        model: String,
        timestamp: Long,
        errorMessage: String? = null,
        inputCost: Double? = null,
        outputCost: Double? = null
    ) = SecondaryResult(
        id = id,
        reportId = "report-1",
        kind = SecondaryKind.TRANSLATE,
        providerId = AppService.LOCAL.id,
        model = model,
        agentName = "Translator",
        timestamp = timestamp,
        content = "translated",
        errorMessage = errorMessage,
        inputCost = inputCost,
        outputCost = outputCost,
        targetLanguage = language,
        targetLanguageNative = native,
        translationRunId = runId
    )
}
