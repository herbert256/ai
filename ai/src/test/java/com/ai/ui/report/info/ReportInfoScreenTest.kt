package com.ai.ui.report.info

import com.ai.data.AppService
import com.ai.data.IconCallRecord
import com.ai.data.Report
import com.ai.data.ReportAgent
import com.ai.data.ReportStatus
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReportInfoScreenTest {
    @Test
    fun totalApiDurationSumsReportAgentIconAndSecondaryDurations() {
        val report = Report(
            id = "report-1",
            timestamp = 123L,
            title = "Report title",
            prompt = "Report prompt",
            agents = mutableListOf(
                agent("a1").apply {
                    durationMs = 100L
                    modelTitleDurationMs = 200L
                },
                agent("a2").apply {
                    modelTitleDurationMs = 300L
                }
            )
        ).apply {
            iconCalls = mutableListOf(
                iconCall(durationMs = 400L),
                iconCall(durationMs = null)
            )
            iconDurationMs = 500L
            languageDurationMs = 600L
            languageIconDurationMs = 700L
            titleDurationMs = 800L
            titleLongDurationMs = 900L
        }
        val secondaries = listOf(
            secondary("s1", durationMs = 1_000L, titleDurationMs = 1_100L),
            secondary("s2", durationMs = null, titleDurationMs = 1_200L)
        )

        assertThat(totalApiDurationMs(report, secondaries)).isEqualTo(7_800L)
    }

    @Test
    fun totalApiDurationTreatsMissingDurationsAsZero() {
        val report = Report(
            id = "report-1",
            timestamp = 123L,
            title = "Report title",
            prompt = "Report prompt",
            agents = mutableListOf(agent("a1"))
        )

        assertThat(totalApiDurationMs(report, listOf(secondary("s1")))).isEqualTo(0L)
    }

    @Test
    fun formatDurationCoversZeroMillisSecondsMinutesAndHours() {
        assertThat(formatDuration(0L)).isEqualTo("\u2014")
        assertThat(formatDuration(-1L)).isEqualTo("\u2014")
        assertThat(formatDuration(999L)).isEqualTo("999 ms")
        assertThat(formatDuration(1_000L)).isEqualTo("1s")
        assertThat(formatDuration(65_000L)).isEqualTo("1m 5s")
        assertThat(formatDuration(3_661_000L)).isEqualTo("1h 1m 1s")
    }

    private fun agent(id: String) = ReportAgent(
        agentId = id,
        agentName = id,
        provider = AppService.LOCAL.id,
        model = "local-model",
        reportStatus = ReportStatus.SUCCESS,
        responseBody = "response"
    )

    private fun iconCall(durationMs: Long?) = IconCallRecord(
        agentId = "a1",
        tier = 1,
        provider = AppService.LOCAL.id,
        model = "local-model",
        pricingTier = "DEFAULT",
        inputTokens = 1,
        outputTokens = 2,
        inputCost = 0.01,
        outputCost = 0.02,
        durationMs = durationMs,
        success = durationMs != null
    )

    private fun secondary(
        id: String,
        durationMs: Long? = null,
        titleDurationMs: Long? = null
    ) = SecondaryResult(
        id = id,
        reportId = "report-1",
        kind = SecondaryKind.META,
        providerId = AppService.LOCAL.id,
        model = "local-model",
        agentName = "Meta",
        timestamp = 456L,
        content = "content",
        durationMs = durationMs,
        titleDurationMs = titleDurationMs
    )
}
