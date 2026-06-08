package com.ai.model

import com.ai.data.ApiFormat
import com.ai.data.AppService
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** AI Setup graph: name lookups, flock/swarm resolution, effective model/key
 *  fallbacks, configured-agent filtering, blocked-model upsert/sync, and the
 *  worker expansion/resolution used by swarms. */
class SettingsAiSetupGraphTest {
    private fun service(id: String) = AppService(
        id = id, baseUrl = "https://$id/", adminUrl = "",
        defaultModel = "def-$id", apiFormat = ApiFormat.OPENAI_COMPATIBLE
    )
    private val pa = service("UNIT_PA")
    private val pb = service("UNIT_PB")

    // ---- case-insensitive name lookups ----

    @Test fun name_lookups_are_case_insensitive_and_null_when_missing() {
        val s = Settings(
            flocks = listOf(Flock("f1", "Cheap", agentIds = emptyList())),
            swarms = listOf(Swarm("s1", "Workers", members = emptyList())),
            internalPrompts = listOf(InternalPrompt("i1", "Equivalent")),
            parameters = listOf(Parameters("p1", "Creative"))
        )
        assertThat(s.getFlockByName("CHEAP")?.id).isEqualTo("f1")
        assertThat(s.getSwarmByName("workers")?.id).isEqualTo("s1")
        assertThat(s.getInternalPromptByName("EQUIVALENT")?.id).isEqualTo("i1")
        assertThat(s.getParametersByName("creative")?.id).isEqualTo("p1")
        assertThat(s.getFlockByName("nope")).isNull()
    }

    // ---- flock agent resolution + dedup ----

    @Test fun flock_agents_resolve_and_skip_missing_ids() {
        val s = Settings(
            agents = listOf(Agent("a1", "A1", pa, "m1", ""), Agent("a2", "A2", pb, "m2", "")),
            flocks = listOf(Flock("f1", "F", agentIds = listOf("a1", "a2", "ghost")))
        )
        assertThat(s.getAgentsForFlock(s.getFlockById("f1")!!).map { it.id })
            .containsExactly("a1", "a2").inOrder()
    }

    @Test fun getAgentsForFlocks_dedups_by_agent_id() {
        val s = Settings(
            agents = listOf(Agent("a1", "A1", pa, "m1", ""), Agent("a2", "A2", pb, "m2", "")),
            flocks = listOf(
                Flock("f1", "F1", agentIds = listOf("a1", "a2")),
                Flock("f2", "F2", agentIds = listOf("a1"))
            )
        )
        assertThat(s.getAgentsForFlocks(setOf("f1", "f2")).map { it.id })
            .containsExactly("a1", "a2")
    }

    // ---- effective model / api key fallback ----

    @Test fun effective_model_falls_back_to_provider_default() {
        val s = Settings()
        assertThat(s.getEffectiveModelForAgent(Agent("a", "A", pa, "custom", ""))).isEqualTo("custom")
        assertThat(s.getEffectiveModelForAgent(Agent("a", "A", pa, "", ""))).isEqualTo("def-UNIT_PA")
    }

    @Test fun effective_api_key_prefers_agent_then_provider() {
        val s = Settings(providers = mapOf(pa to ProviderConfig(apiKey = "provkey")))
        assertThat(s.getEffectiveApiKeyForAgent(Agent("a", "A", pa, "m", "agentkey"))).isEqualTo("agentkey")
        assertThat(s.getEffectiveApiKeyForAgent(Agent("a", "A", pa, "m", ""))).isEqualTo("provkey")
    }

    @Test fun configured_agents_need_an_api_key_somewhere() {
        val s = Settings(
            providers = mapOf(pa to ProviderConfig(apiKey = "k"), pb to ProviderConfig(apiKey = "")),
            agents = listOf(
                Agent("viaProvider", "A", pa, "m", ""),
                Agent("viaOwn", "B", pb, "m", "ownkey"),
                Agent("unconfigured", "C", pb, "m", "")
            )
        )
        assertThat(s.getConfiguredAgents().map { it.id }).containsExactly("viaProvider", "viaOwn")
    }

    // ---- blocked models ----

    @Test fun blocked_model_upsert_remove_and_incoming_wins() {
        var s = Settings().upsertBlockedModel(BlockedModel("UNIT_PA", "m", "first"))
        assertThat(s.isBlocked("UNIT_PA", "m")).isTrue()
        s = s.upsertBlockedModel(BlockedModel("UNIT_PA", "m", "second"))
        assertThat(s.blockedModels).hasSize(1)
        assertThat(s.blockedModels.single().reason).isEqualTo("second")
        s = s.removeBlockedModel("UNIT_PA", "m")
        assertThat(s.isBlocked("UNIT_PA", "m")).isFalse()
    }

    // NOTE: the former `sync_blocked_from_test_run_*` case was removed — its
    // subject `Settings.syncBlockedModelsFromTestRun` was deleted as dead code
    // in 89a07fd1e (the live path is now AppViewModel.applyTestItemIncrement).
    // The merge from codex re-introduced this stale test; dropped here.

    // ---- worker expansion / resolution ----

    private fun activeSettings() = Settings(
        providers = mapOf(pa to ProviderConfig(apiKey = "k")),
        providerStates = mapOf(pa.id to "ok"),
        agents = listOf(Agent("a1", "Alpha", pa, "m1", "")),
        flocks = listOf(Flock("f1", "MyFlock", agentIds = listOf("a1"))),
        swarms = listOf(Swarm("s1", "MySwarm", members = listOf(SwarmMember(pa, "m1"))))
    )

    @Test fun expand_model_worker_is_itself() {
        val w = Worker(agent = "Alpha")
        assertThat(activeSettings().expandWorker(w)).containsExactly(w)
    }

    @Test fun expand_flock_worker_yields_active_member_agents() {
        val ws = activeSettings().expandWorker(Worker(flock = "MyFlock"))
        assertThat(ws.map { it.agent }).containsExactly("Alpha")
    }

    @Test fun expand_swarm_worker_yields_provider_model_workers() {
        val ws = activeSettings().expandWorker(Worker(swarm = "MySwarm"))
        assertThat(ws).hasSize(1)
        assertThat(ws[0].provider).isEqualTo("UNIT_PA")
        assertThat(ws[0].model).isEqualTo("m1")
    }

    @Test fun expand_flock_filters_inactive_providers() {
        val s = activeSettings().copy(providerStates = mapOf(pa.id to "inactive"))
        assertThat(s.expandWorker(Worker(flock = "MyFlock"))).isEmpty()
    }

    @Test fun resolve_named_agent_worker() {
        assertThat(activeSettings().resolveWorker(Worker(agent = "Alpha"))?.id).isEqualTo("a1")
    }

    @Test fun resolve_unresolvable_worker_is_null() {
        assertThat(activeSettings().resolveWorker(Worker(agent = "Ghost"))).isNull()
    }

    @Test fun resolve_pinned_local_worker_is_a_synthetic_agent() {
        val agent = activeSettings().resolveWorker(Worker(agent = "*N/A", provider = "Local", model = "tinyllama"))
        assertThat(agent).isNotNull()
        assertThat(agent!!.model).isEqualTo("tinyllama")
        assertThat(agent.provider).isEqualTo(AppService.LOCAL)
    }
}
