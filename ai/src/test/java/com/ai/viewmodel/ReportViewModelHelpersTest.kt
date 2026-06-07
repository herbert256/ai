package com.ai.viewmodel

import com.ai.data.AppService
import com.ai.data.Report
import com.ai.data.ReportAgent
import com.ai.data.ReportStatus
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.model.Agent
import com.ai.model.ProviderConfig
import com.ai.model.Settings
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReportViewModelHelpersTest {
    @Test
    fun reportToModelsRestoresAgentRowsAndFallsBackForOrphans() {
        val settings = Settings(
            providers = mapOf(AppService.LOCAL to ProviderConfig(apiKey = "local-key")),
            agents = listOf(
                Agent(
                    id = "agent-1",
                    name = "Local Agent",
                    provider = AppService.LOCAL,
                    model = "configured-agent-model",
                    apiKey = "",
                    paramsIds = listOf("agent-params")
                )
            )
        )
        val report = report(
            agents = mutableListOf(
                agent("agent-1", model = "persisted-agent-model"),
                agent("swarm:Local:swarm-model", model = "swarm-model"),
                agent("deleted-agent", model = "orphan-model"),
                agent("unknown-provider", provider = "Missing", model = "skip-me")
            )
        )

        val models = reportToModels(report, settings)

        assertThat(models.map { it.model })
            .containsExactly("configured-agent-model", "swarm-model", "orphan-model")
            .inOrder()
        assertThat(models[0].sourceType).isEqualTo("agent")
        assertThat(models[0].sourceName).isEqualTo("Local Agent")
        assertThat(models[0].agentId).isEqualTo("agent-1")
        assertThat(models[0].paramsIds).containsExactly("agent-params")
        assertThat(models[1].sourceType).isEqualTo("model")
        assertThat(models[2].sourceType).isEqualTo("model")
    }

    @Test
    fun buildLanguageInputsUsesTranslationsAndPreservesOriginalNumberingForSubset() {
        val report = report(
            prompt = "Original prompt",
            agents = mutableListOf(
                agent("a1", body = "Alpha original"),
                agent("a2", body = "Beta original"),
                agent("a3", status = ReportStatus.ERROR, body = "Gamma error"),
                agent("a4", body = "")
            )
        )
        val secondaries = listOf(
            translation("PROMPT", "prompt", "Dutch", "Nederlandse prompt"),
            translation("AGENT", "a1", "Dutch", "Alpha vertaald")
        )

        val (prompt, resultsBlock) = buildLanguageInputs(
            report = report,
            secondaries = secondaries,
            language = "Dutch",
            includeIds = setOf(2)
        )

        assertThat(prompt).isEqualTo("Nederlandse prompt")
        assertThat(resultsBlock).isEqualTo("[2]\nBeta original")
    }

    @Test
    fun buildLanguageInputsFallsBackPerAgentWhenTranslationRowsArePartial() {
        val report = report(
            agents = mutableListOf(
                agent("a1", body = " Alpha original "),
                agent("a2", body = " Beta original ")
            )
        )
        val secondaries = listOf(
            translation("AGENT", "a1", "Dutch", " Alpha vertaald ")
        )

        val (_, resultsBlock) = buildLanguageInputs(
            report = report,
            secondaries = secondaries,
            language = "Dutch",
            includeIds = null
        )

        assertThat(resultsBlock).isEqualTo("[1]\n Alpha vertaald \n\n[2]\nBeta original")
    }

    @Test
    fun lookupLanguageTranslationsBuildsTrimmedContextWithOriginalFallbacks() {
        val report = report(
            title = "Original title",
            prompt = "Original prompt",
            agents = mutableListOf(
                agent("a1", body = " Alpha original "),
                agent("a2", body = " Beta original "),
                agent("a3", status = ReportStatus.ERROR, body = "Gamma error")
            )
        )
        val secondaries = listOf(
            translation("PROMPT", "prompt", "Dutch", "Nederlandse prompt", native = "Nederlands"),
            translation("TITLE", "title", "Dutch", "Nederlandse titel"),
            translation("AGENT", "a1", "Dutch", " Alpha vertaald ")
        )

        val context = lookupLanguageTranslations(report, secondaries, "Dutch")

        assertThat(context).isNotNull()
        assertThat(context!!.prompt).isEqualTo("Nederlandse prompt")
        assertThat(context.title).isEqualTo("Nederlandse titel")
        assertThat(context.native).isEqualTo("Nederlands")
        assertThat(context.bodiesByAgentId)
            .containsExactly("a1", "Alpha vertaald", "a2", "Beta original")
    }

    @Test
    fun lookupLanguageTranslationsReturnsNullForOriginalLanguage() {
        val report = report()

        assertThat(lookupLanguageTranslations(report, emptyList(), null)).isNull()
        assertThat(lookupLanguageTranslations(report, emptyList(), "")).isNull()
    }

    private fun report(
        title: String = "Report title",
        prompt: String = "Report prompt",
        agents: MutableList<ReportAgent> = mutableListOf(agent("a1"))
    ) = Report(
        id = "report-1",
        timestamp = 123L,
        title = title,
        prompt = prompt,
        agents = agents
    )

    private fun agent(
        id: String,
        provider: String = AppService.LOCAL.id,
        model: String = "local-model",
        status: ReportStatus = ReportStatus.SUCCESS,
        body: String? = "Response body"
    ) = ReportAgent(
        agentId = id,
        agentName = id,
        provider = provider,
        model = model,
        reportStatus = status,
        responseBody = body
    )

    private fun translation(
        sourceKind: String,
        sourceTargetId: String,
        language: String,
        content: String,
        native: String? = null
    ) = SecondaryResult(
        id = "$sourceKind-$sourceTargetId-$language",
        reportId = "report-1",
        kind = SecondaryKind.TRANSLATE,
        providerId = AppService.LOCAL.id,
        model = "translator",
        agentName = "Translator",
        timestamp = 456L,
        content = content,
        translateSourceKind = sourceKind,
        translateSourceTargetId = sourceTargetId,
        targetLanguage = language,
        targetLanguageNative = native
    )
}
