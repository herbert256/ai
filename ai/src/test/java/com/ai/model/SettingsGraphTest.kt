package com.ai.model

import com.ai.data.ApiFormat
import com.ai.data.AppService
import com.ai.data.ModelType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsGraphTest {
    private val serviceA = service("UNIT_SETTINGS_A")
    private val serviceB = service("UNIT_SETTINGS_B")

    @Test fun provider_state_depends_on_api_key_and_explicit_inactive_state() {
        val noKey = Settings(
            providers = mapOf(serviceA to ProviderConfig(apiKey = "")),
            providerStates = mapOf(serviceA.id to "ok")
        )
        val active = noKey.withApiKey(serviceA, "key")
        val inactive = active.withProviderState(serviceA, "inactive")

        assertThat(noKey.getProviderState(serviceA)).isEqualTo("not-used")
        assertThat(active.getProviderState(serviceA)).isEqualTo("ok")
        assertThat(inactive.getProviderState(serviceA)).isEqualTo("inactive")
    }

    @Test fun withModels_deduplicates_models_and_stores_type_metadata() {
        val settings = Settings(providers = mapOf(serviceA to ProviderConfig()))
            .withModels(serviceA, listOf("m1", "m1", "embed"), mapOf("embed" to ModelType.EMBEDDING))

        assertThat(settings.getModels(serviceA)).containsExactly("m1", "embed").inOrder()
        assertThat(settings.getProvider(serviceA).modelTypes["embed"]).isEqualTo(ModelType.EMBEDDING)
    }

    @Test fun removeProvider_cascades_agents_flocks_swarms_endpoints_and_state() {
        val removedAgent = Agent("a1", "A1", serviceA, "m", "")
        val keptAgent = Agent("a2", "A2", serviceB, "m", "")
        val settings = Settings(
            providers = mapOf(serviceA to ProviderConfig(apiKey = "a"), serviceB to ProviderConfig(apiKey = "b")),
            agents = listOf(removedAgent, keptAgent),
            flocks = listOf(Flock("f1", "F", agentIds = listOf("a1", "a2"))),
            swarms = listOf(Swarm("s1", "S", members = listOf(SwarmMember(serviceA, "m1"), SwarmMember(serviceB, "m2")))),
            endpoints = mapOf(serviceA to listOf(Endpoint("custom", "Custom", "https://x", true))),
            providerStates = mapOf(serviceA.id to "ok", serviceB.id to "ok")
        )

        val updated = settings.removeProvider(serviceA)

        assertThat(updated.providers.keys).containsExactly(serviceB)
        assertThat(updated.agents).containsExactly(keptAgent)
        assertThat(updated.flocks.single().agentIds).containsExactly("a2")
        assertThat(updated.swarms.single().members).containsExactly(SwarmMember(serviceB, "m2"))
        assertThat(updated.endpoints).doesNotContainKey(serviceA)
        assertThat(updated.providerStates).doesNotContainKey(serviceA.id)
    }

    @Test fun removeSystemPrompt_and_parameters_clear_references() {
        val settings = Settings(
            providers = mapOf(serviceA to ProviderConfig(parametersIds = listOf("p1", "p2"))),
            agents = listOf(Agent("a1", "A", serviceA, "m", "", paramsIds = listOf("p1"), systemPromptId = "sp1")),
            flocks = listOf(Flock("f1", "F", agentIds = listOf("a1"), paramsIds = listOf("p1"), systemPromptId = "sp1")),
            swarms = listOf(Swarm("s1", "S", members = listOf(SwarmMember(serviceA, "m")), paramsIds = listOf("p1"), systemPromptId = "sp1")),
            parameters = listOf(Parameters("p1", "P1"), Parameters("p2", "P2")),
            systemPrompts = listOf(SystemPrompt("sp1", "SP", "prompt"))
        )

        val updated = settings.removeSystemPrompt("sp1").removeParameters("p1")

        assertThat(updated.systemPrompts).isEmpty()
        assertThat(updated.parameters.map { it.id }).containsExactly("p2")
        assertThat(updated.agents.single().systemPromptId).isNull()
        assertThat(updated.agents.single().paramsIds).isEmpty()
        assertThat(updated.flocks.single().systemPromptId).isNull()
        assertThat(updated.flocks.single().paramsIds).isEmpty()
        assertThat(updated.swarms.single().systemPromptId).isNull()
        assertThat(updated.swarms.single().paramsIds).isEmpty()
        assertThat(updated.getProvider(serviceA).parametersIds).containsExactly("p2")
    }

    @Test fun expansion_helpers_filter_inactive_providers_and_deduplicate_models() {
        val agentA = Agent("a1", "A", serviceA, "m1", "")
        val agentB = Agent("a2", "B", serviceB, "m1", "")
        val settings = Settings(
            providers = mapOf(
                serviceA to ProviderConfig(apiKey = "key-a", model = "fallback-a"),
                serviceB to ProviderConfig(apiKey = "key-b", model = "fallback-b")
            ),
            agents = listOf(agentA, agentB),
            flocks = listOf(Flock("f1", "F", agentIds = listOf("a1", "a2"))),
            swarms = listOf(Swarm("s1", "S", members = listOf(SwarmMember(serviceA, "m1"), SwarmMember(serviceB, "m2")))),
            providerStates = mapOf(serviceA.id to "ok", serviceB.id to "inactive")
        )

        assertThat(expandAgentToModel(agentA, settings)?.model).isEqualTo("m1")
        assertThat(expandAgentToModel(agentB, settings)).isNull()
        assertThat(expandFlockToModels(settings.flocks.single(), settings).map { it.agentId }).containsExactly("a1")
        assertThat(expandSwarmToModels(settings.swarms.single(), settings).map { it.model }).containsExactly("m1")

        val duplicated = listOf(
            ReportModel(serviceA, "m1", "agent", "agent", "A"),
            ReportModel(serviceA, "m1", "model", "model", ""),
            ReportModel(serviceB, "m1", "model", "model", "")
        )
        assertThat(deduplicateModels(duplicated).map { it.deduplicationKey })
            .containsExactly("${serviceA.id}:m1", "${serviceB.id}:m1")
            .inOrder()
    }

    @Test fun endpoint_helpers_prefer_agent_endpoint_then_default_endpoint() {
        val custom = Endpoint("custom", "Custom", "https://custom.example.com/v1/chat/completions", isDefault = false)
        val default = Endpoint("default", "Default", "https://default.example.com/v1/chat/completions", isDefault = true)
        val settings = Settings(
            providers = mapOf(serviceA to ProviderConfig(apiKey = "key")),
            endpoints = mapOf(serviceA to listOf(custom, default))
        )

        assertThat(settings.getEffectiveEndpointUrl(serviceA)).isEqualTo(default.url)
        assertThat(settings.getEffectiveEndpointUrlForAgent(Agent("a", "A", serviceA, "m", "", endpointId = "custom")))
            .isEqualTo(custom.url)
    }

    private fun service(id: String) = AppService(
        id = id,
        displayName = id,
        baseUrl = "https://$id.example.com/",
        adminUrl = "",
        defaultModel = "default",
        apiFormat = ApiFormat.OPENAI_COMPATIBLE
    )
}
