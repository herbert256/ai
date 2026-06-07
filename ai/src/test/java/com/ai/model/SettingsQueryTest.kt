package com.ai.model

import com.ai.data.ApiFormat
import com.ai.data.AppService
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure query helpers on [Settings]: API-key presence, provider activeness,
 *  and swarm-member flattening. */
class SettingsQueryTest {
    private fun service(id: String) = AppService(
        id = id, baseUrl = "https://$id.example.com/", adminUrl = "",
        defaultModel = "default", apiFormat = ApiFormat.OPENAI_COMPATIBLE
    )
    private val a = service("UNIT_Q_A")
    private val b = service("UNIT_Q_B")

    @Test fun hasAnyApiKey_false_when_all_blank() {
        val s = Settings(providers = mapOf(a to ProviderConfig(apiKey = ""), b to ProviderConfig(apiKey = "")))
        assertThat(s.hasAnyApiKey()).isFalse()
    }

    @Test fun hasAnyApiKey_true_when_any_set() {
        val s = Settings(providers = mapOf(a to ProviderConfig(apiKey = ""), b to ProviderConfig(apiKey = "secret")))
        assertThat(s.hasAnyApiKey()).isTrue()
    }

    @Test fun hasAnyApiKey_false_for_empty_providers() {
        assertThat(Settings().hasAnyApiKey()).isFalse()
    }

    @Test fun isProviderActive_requires_key_and_ok_state() {
        val active = Settings(
            providers = mapOf(a to ProviderConfig(apiKey = "k")),
            providerStates = mapOf(a.id to "ok")
        )
        assertThat(active.isProviderActive(a)).isTrue()
        assertThat(active.withProviderState(a, "inactive").isProviderActive(a)).isFalse()
    }

    @Test fun isProviderActive_false_without_key() {
        val noKey = Settings(
            providers = mapOf(a to ProviderConfig(apiKey = "")),
            providerStates = mapOf(a.id to "ok")
        )
        assertThat(noKey.isProviderActive(a)).isFalse()
    }

    @Test fun getMembersForSwarms_flattens_and_dedups_by_provider_model() {
        val s = Settings(
            swarms = listOf(
                Swarm("s1", "S1", members = listOf(SwarmMember(a, "m1"), SwarmMember(b, "m2"))),
                Swarm("s2", "S2", members = listOf(SwarmMember(a, "m1"), SwarmMember(b, "m3")))
            )
        )
        val ids = s.getMembersForSwarms(setOf("s1", "s2")).map { "${it.provider.id}:${it.model}" }
        assertThat(ids).containsExactly("UNIT_Q_A:m1", "UNIT_Q_B:m2", "UNIT_Q_B:m3")
    }

    @Test fun getMembersForSwarms_unknown_swarm_is_empty() {
        assertThat(Settings().getMembersForSwarms(setOf("nope"))).isEmpty()
    }

    @Test fun getMembersForSwarms_single_swarm() {
        val s = Settings(swarms = listOf(Swarm("s1", "S1", members = listOf(SwarmMember(a, "m1")))))
        assertThat(s.getMembersForSwarms(setOf("s1"))).hasSize(1)
    }
}
