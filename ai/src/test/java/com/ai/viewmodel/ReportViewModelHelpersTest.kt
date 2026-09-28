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
    fun reportToModelsKeepsEachRowsPersistedIdentity() {
        // The report's saved rows are the source of truth (replay fidelity):
        // an agent row keeps the model and name it ran with even when the
        // agent was edited or deleted since; swarm rows become plain models;
        // rows of an unknown provider are dropped.
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
            .containsExactly("persisted-agent-model", "swarm-model", "orphan-model")
            .inOrder()
        assertThat(models[0].sourceType).isEqualTo("agent")
        assertThat(models[0].sourceName).isEqualTo("agent-1")
        assertThat(models[0].agentId).isEqualTo("agent-1")
        assertThat(models[1].sourceType).isEqualTo("model")
        assertThat(models[2].sourceType).isEqualTo("agent")
        assertThat(models[2].agentId).isEqualTo("deleted-agent")
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
            translation("PROMPT", "prompt", "Dutch", "Nederlandse prompt", sourceText = "Original prompt"),
            translation("AGENT", "a1", "Dutch", "Alpha vertaald", sourceText = "Alpha original")
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
            translation("AGENT", "a1", "Dutch", " Alpha vertaald ", sourceText = " Alpha original ")
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
            translation("PROMPT", "prompt", "Dutch", "Nederlandse prompt", native = "Nederlands", sourceText = "Original prompt"),
            translation("TITLE", "title", "Dutch", "Nederlandse titel", sourceText = "Original title"),
            translation("AGENT", "a1", "Dutch", " Alpha vertaald ", sourceText = " Alpha original ")
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
    fun lookupLanguageTranslationsSkipsTranslationsOfOutdatedSources() {
        val report = report(
            prompt = "Edited prompt",
            agents = mutableListOf(
                agent("a1", body = "Alpha regenerated"),
                agent("a2", body = "Beta original"),
                agent("a3", body = "Gamma original")
            )
        )
        val secondaries = listOf(
            // Translated before the prompt edit / a1's regenerate — stale.
            translation("PROMPT", "prompt", "Dutch", "Oude prompt", sourceText = "Original prompt"),
            translation("AGENT", "a1", "Dutch", "Alpha oud", sourceText = "Alpha original"),
            // Unknown source text — can't be vouched for.
            translation("AGENT", "a2", "Dutch", "Beta vertaald"),
            // Translated from the current French translation — one hop, current.
            translation("AGENT", "a3", "French", "Gamma traduit", sourceText = "Gamma original"),
            translation("AGENT", "a3", "Dutch", "Gamma vertaald", sourceText = "Gamma traduit")
        )

        val context = lookupLanguageTranslations(report, secondaries, "Dutch")

        assertThat(context!!.prompt).isEqualTo("Edited prompt")
        assertThat(context.bodiesByAgentId)
            .containsExactly("a1", "Alpha regenerated", "a2", "Beta original", "a3", "Gamma vertaald")
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
        native: String? = null,
        sourceText: String? = null
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
        targetLanguageNative = native,
        translationSourceText = sourceText
    )
}
