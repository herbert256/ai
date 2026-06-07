package com.ai.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ParametersMergeTest {
    @Test fun mergeParameters_uses_later_returnCitations_value_instead_of_or() {
        val settings = Settings(
            providers = emptyMap(),
            parameters = listOf(
                Parameters(id = "citations-on", name = "Citations on", returnCitations = true),
                Parameters(id = "citations-off", name = "Citations off", returnCitations = false)
            )
        )

        assertThat(settings.mergeParameters(listOf("citations-on", "citations-off"))!!.returnCitations)
            .isFalse()
    }

    @Test fun empty_or_unmatched_ids_return_null() {
        val s = Settings(parameters = listOf(Parameters(id = "p1", name = "P1", temperature = 0.5f)))
        assertThat(s.mergeParameters(emptyList())).isNull()
        assertThat(s.mergeParameters(listOf("ghost"))).isNull()
    }

    @Test fun single_preset_passes_its_value_through() {
        val s = Settings(parameters = listOf(Parameters(id = "p1", name = "P1", temperature = 0.7f)))
        assertThat(s.mergeParameters(listOf("p1"))!!.temperature).isEqualTo(0.7f)
    }

    @Test fun later_preset_non_null_value_wins() {
        val s = Settings(
            parameters = listOf(
                Parameters(id = "a", name = "A", temperature = 0.5f),
                Parameters(id = "b", name = "B", temperature = 0.9f)
            )
        )
        assertThat(s.mergeParameters(listOf("a", "b"))!!.temperature).isEqualTo(0.9f)
    }

    @Test fun earlier_preset_fills_a_gap_left_by_a_later_null() {
        val s = Settings(
            parameters = listOf(
                Parameters(id = "a", name = "A", temperature = 0.5f),
                Parameters(id = "b", name = "B")   // temperature null
            )
        )
        assertThat(s.mergeParameters(listOf("a", "b"))!!.temperature).isEqualTo(0.5f)
    }

    @Test fun search_enabled_is_or_combined_across_the_chain() {
        val s = Settings(
            parameters = listOf(
                Parameters(id = "a", name = "A", searchEnabled = true),
                Parameters(id = "b", name = "B", searchEnabled = false)
            )
        )
        assertThat(s.mergeParameters(listOf("a", "b"))!!.searchEnabled).isTrue()
    }
}
