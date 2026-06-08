package com.ai.ui.report.manage

import com.ai.data.AppService
import com.ai.data.Report
import com.ai.data.ReportAgent
import com.ai.data.ReportStatus
import com.ai.model.Agent
import com.ai.model.InternalPrompt
import com.ai.model.Settings
import com.ai.model.Worker
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GetInfoJobsTest {
    @Test
    fun completedLegacyReportOmitsNeverRunReportLevelJobs() {
        val report = report(completedAt = 2_000L)

        val jobs = buildInfoJobs(
            report = report,
            settings = workerSettings(),
            iconGenEnabled = true,
            reportLanguageOn = true,
            titleModeAi = true,
            perModelIcon = false,
            perModelTitle = false
        )

        assertThat(jobs).isEmpty()
        assertThat(aggregateInfoState(jobs)).isEqualTo(InfoJobState.DONE)
    }

    @Test
    fun incompleteReportLevelJobsRemainPendingWhileReportIsOpen() {
        val report = report(completedAt = null)

        val jobs = buildInfoJobs(
            report = report,
            settings = workerSettings(),
            iconGenEnabled = true,
            reportLanguageOn = true,
            titleModeAi = true,
            perModelIcon = false,
            perModelTitle = false
        )

        assertThat(jobs.map { it.type }).containsExactly(
            "language",
            "language-icon",
            "report-short",
            "report-long",
            "report-icon"
        ).inOrder()
        assertThat(jobs.map { it.state }).containsExactly(
            InfoJobState.CLOCK,
            InfoJobState.CLOCK,
            InfoJobState.CLOCK,
            InfoJobState.CLOCK,
            InfoJobState.CLOCK
        ).inOrder()
        assertThat(jobs.all { it.pending }).isTrue()
        assertThat(aggregateInfoState(jobs)).isEqualTo(InfoJobState.RUNNING)
    }

    @Test
    fun runningKeysMarkEligibleReportLevelRowsRunning() {
        val report = report(completedAt = null)

        val jobs = buildInfoJobs(
            report = report,
            settings = workerSettings(),
            iconGenEnabled = true,
            reportLanguageOn = true,
            titleModeAi = true,
            perModelIcon = false,
            perModelTitle = false,
            running = setOf("${report.id}|language", "${report.id}|title", "${report.id}|icon")
        )
        val byType = jobs.associateBy { it.type }

        assertThat(byType["language"]?.state).isEqualTo(InfoJobState.RUNNING)
        assertThat(byType["language-icon"]?.state).isEqualTo(InfoJobState.CLOCK)
        assertThat(byType["report-short"]?.state).isEqualTo(InfoJobState.RUNNING)
        assertThat(byType["report-long"]?.state).isEqualTo(InfoJobState.RUNNING)
        assertThat(byType["report-icon"]?.state).isEqualTo(InfoJobState.RUNNING)
    }

    @Test
    fun failedAgentLeavesPerModelMetadataClockTerminal() {
        val report = report(
            completedAt = 2_000L,
            agents = mutableListOf(agent("failed", ReportStatus.ERROR))
        )

        val jobs = buildInfoJobs(
            report = report,
            settings = workerSettings(),
            iconGenEnabled = false,
            reportLanguageOn = false,
            titleModeAi = false,
            perModelIcon = true,
            perModelTitle = true
        )

        assertThat(jobs.map { it.type }).containsExactly("model-title", "model-icon").inOrder()
        assertThat(jobs.map { it.state }).containsExactly(InfoJobState.CLOCK, InfoJobState.CLOCK).inOrder()
        assertThat(jobs.any { it.pending }).isFalse()
        assertThat(aggregateInfoState(jobs)).isEqualTo(InfoJobState.DONE)
    }

    @Test
    fun pendingAgentClockStillKeepsAggregateRunning() {
        val report = report(
            completedAt = null,
            agents = mutableListOf(agent("queued", ReportStatus.PENDING))
        )

        val jobs = buildInfoJobs(
            report = report,
            settings = workerSettings(),
            iconGenEnabled = false,
            reportLanguageOn = false,
            titleModeAi = false,
            perModelIcon = true,
            perModelTitle = true
        )

        assertThat(jobs.map { it.state }).containsExactly(InfoJobState.CLOCK, InfoJobState.CLOCK).inOrder()
        assertThat(jobs.all { it.pending }).isTrue()
        assertThat(aggregateInfoState(jobs)).isEqualTo(InfoJobState.RUNNING)
    }

    @Test
    fun successfulModelTitleAttemptWithoutTitleIsTerminalEmpty() {
        val report = report(
            completedAt = 2_000L,
            agents = mutableListOf(
                agent("success", ReportStatus.SUCCESS).apply {
                    modelTitleInputTokens = 10
                    modelTitleInputCost = 0.01
                    modelTitleOutputCost = 0.02
                }
            )
        )

        val jobs = buildInfoJobs(
            report = report,
            settings = workerSettings(),
            iconGenEnabled = false,
            reportLanguageOn = false,
            titleModeAi = false,
            perModelIcon = false,
            perModelTitle = true
        )

        assertThat(jobs).hasSize(1)
        assertThat(jobs.single().type).isEqualTo("model-title")
        // A concluded title call that yielded no title and no error is terminal
        // "but not a success" → EMPTY (GetInfo.kt titleStateFor), not DONE.
        assertThat(jobs.single().state).isEqualTo(InfoJobState.EMPTY)
        assertThat(jobs.single().pending).isFalse()
        assertThat(jobs.single().cost).isWithin(0.000001).of(0.03)
    }

    @Test
    fun modelIconWaitsForSuccessfulTitleWhenTitlesAreEnabled() {
        val report = report(
            completedAt = null,
            agents = mutableListOf(agent("success", ReportStatus.SUCCESS))
        )

        val jobs = buildInfoJobs(
            report = report,
            settings = workerSettings(),
            iconGenEnabled = false,
            reportLanguageOn = false,
            titleModeAi = false,
            perModelIcon = true,
            perModelTitle = true
        )
        val byType = jobs.associateBy { it.type }

        assertThat(byType["model-title"]?.state).isEqualTo(InfoJobState.RUNNING)
        assertThat(byType["model-title"]?.pending).isTrue()
        assertThat(byType["model-icon"]?.state).isEqualTo(InfoJobState.CLOCK)
        assertThat(byType["model-icon"]?.pending).isFalse()
        assertThat(aggregateInfoState(jobs)).isEqualTo(InfoJobState.RUNNING)
    }

    @Test
    fun aggregateInfoStatePrioritizesFailureOverPending() {
        val jobs = listOf(
            InfoJob("queued", "Queued", InfoJobState.CLOCK, cost = 0.0, pending = true),
            InfoJob("failed", "Failed", InfoJobState.FAILED, cost = 0.0, pending = false)
        )

        assertThat(aggregateInfoState(jobs)).isEqualTo(InfoJobState.FAILED)
    }

    private fun workerSettings(): Settings {
        val workerAgent = Agent(
            id = "worker",
            name = "Worker",
            provider = AppService.LOCAL,
            model = "local-worker",
            apiKey = ""
        )
        val worker = Worker(agent = workerAgent.name)
        return Settings(
            providers = emptyMap(),
            agents = listOf(workerAgent),
            internalPrompts = listOf(
                InternalPrompt("language", "language", category = "workers", workers = listOf(worker)),
                InternalPrompt("language-icon", "language-icon", category = "workers", workers = listOf(worker)),
                InternalPrompt("report-title-short", "report-title-short", category = "workers", workers = listOf(worker)),
                InternalPrompt("report-title-long", "report-title-long", category = "workers", workers = listOf(worker)),
                InternalPrompt("report-icon", "report-icon", category = "workers", workers = listOf(worker))
            )
        )
    }

    private fun report(
        completedAt: Long?,
        agents: MutableList<ReportAgent> = mutableListOf()
    ): Report = Report(
        id = "report-1",
        timestamp = 1_000L,
        title = "Report",
        prompt = "Prompt",
        agents = agents,
        completedAt = completedAt
    )

    private fun agent(id: String, status: ReportStatus): ReportAgent = ReportAgent(
        agentId = id,
        agentName = id,
        provider = "Provider",
        model = "provider-model",
        reportStatus = status
    )
}
