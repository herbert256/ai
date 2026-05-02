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
}
