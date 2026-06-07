package com.ai.ui.report.manage

import com.ai.data.AppService
import com.ai.model.ProviderConfig
import com.ai.model.ReportModel
import com.ai.model.Settings
import com.ai.model.Swarm
import com.ai.model.SwarmMember
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SavedReportModelSelectionTest {
    @Test
    fun encodeBareModelUsesProviderModelSelectionString() {
        val encoded = encodeSavedReportModelSelection(
            ReportModel(
                provider = AppService.LOCAL,
                model = "local-model",
                type = "model",
                sourceType = "model",
                sourceName = ""
            )
        )

        assertThat(encoded).isEqualTo("swarm:Local:local-model")
    }

    @Test
    fun encodeSwarmSelectionUsesStableSourceIdWhenAvailable() {
        val encoded = encodeSavedReportModelSelection(
            ReportModel(
                provider = AppService.LOCAL,
                model = "member-model",
                type = "model",
                sourceType = "swarm",
                sourceName = "Saved Swarm",
                sourceId = "swarm-123"
            )
        )

        assertThat(encoded).isEqualTo("swarm-id:swarm-123")
    }

    @Test
    fun encodeSwarmWithoutSourceIdFallsBackToProviderModel() {
        val encoded = encodeSavedReportModelSelection(
            ReportModel(
                provider = AppService.LOCAL,
                model = "member-model",
                type = "model",
                sourceType = "swarm",
                sourceName = "Legacy Swarm"
            )
        )

        assertThat(encoded).isEqualTo("swarm:Local:member-model")
    }

    @Test
    fun decodeProviderModelSelectionPreservesModelIdsContainingColon() {
        val decoded = decodeSavedReportModelSelection(
            "swarm:Local:repo/model:variant",
            Settings(providers = mapOf(AppService.LOCAL to ProviderConfig(apiKey = "local-key")))
        )

        assertThat(decoded).hasSize(1)
        val model = decoded.single()
        assertThat(model.provider).isEqualTo(AppService.LOCAL)
        assertThat(model.model).isEqualTo("repo/model:variant")
        assertThat(model.sourceType).isEqualTo("model")
        assertThat(model.sourceName).isEmpty()
    }

    @Test
    fun decodeSwarmIdExpandsOnlyActiveMembersAndPreservesSwarmMetadata() {
        val active = AppService(
            id = "ReportTestActive",
            baseUrl = "https://active.example.com/",
            adminUrl = "",
            defaultModel = "active-default"
        )
        val inactive = AppService(
            id = "ReportTestInactive",
            baseUrl = "https://inactive.example.com/",
            adminUrl = "",
            defaultModel = "inactive-default"
        )
        val settings = Settings(
            providers = mapOf(
                active to ProviderConfig(apiKey = "active-key"),
                inactive to ProviderConfig(apiKey = "inactive-key")
            ),
            providerStates = mapOf(active.id to "ok", inactive.id to "inactive"),
            swarms = listOf(
                Swarm(
                    id = "swarm-1",
                    name = "Report Swarm",
                    members = listOf(
                        SwarmMember(active, "active-model"),
                        SwarmMember(inactive, "inactive-model")
                    ),
                    paramsIds = listOf("p-fast", "p-json")
                )
            )
        )

        val decoded = decodeSavedReportModelSelection("swarm-id:swarm-1", settings)

        assertThat(decoded.map { it.model }).containsExactly("active-model")
        val model = decoded.single()
        assertThat(model.provider).isEqualTo(active)
        assertThat(model.sourceType).isEqualTo("swarm")
        assertThat(model.sourceName).isEqualTo("Report Swarm")
        assertThat(model.sourceId).isEqualTo("swarm-1")
        assertThat(model.paramsIds).containsExactly("p-fast", "p-json").inOrder()
    }

    @Test
    fun decodeUnknownProviderOrMissingSwarmReturnsEmpty() {
        val settings = Settings(providers = mapOf(AppService.LOCAL to ProviderConfig(apiKey = "local-key")))

        assertThat(decodeSavedReportModelSelection("swarm:Missing:model", settings)).isEmpty()
        assertThat(decodeSavedReportModelSelection("swarm-id:no-such-swarm", settings)).isEmpty()
        assertThat(decodeSavedReportModelSelection("agent:legacy-agent", settings)).isEmpty()
        assertThat(decodeSavedReportModelSelection("swarm:", settings)).isEmpty()
    }
}
