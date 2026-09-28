package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReasoningEffortLevelsTest {
    private val mistral = AppService(id = "Mistral", baseUrl = "https://api.mistral.ai/", adminUrl = "", defaultModel = "mistral-small-latest")

    @Test fun mistralMediumAndSmallOfferOnlyNoneAndHigh() {
        assertThat(knownReasoningEffortLevels(mistral, "mistral-medium-latest")).containsExactly("none", "high")
        assertThat(knownReasoningEffortLevels(mistral, "mistral-small-latest")).containsExactly("none", "high")
        assertThat(knownReasoningEffortLevels(mistral, "magistral-medium-latest")).isNull()
    }

    @Test fun nearestLevelPrefersTheCheaperOneOnATie() {
        val noneHigh = listOf("none", "high")
        assertThat(nearestReasoningEffort("low", noneHigh)).isEqualTo("none")
        assertThat(nearestReasoningEffort("medium", noneHigh)).isEqualTo("high")
        assertThat(nearestReasoningEffort("high", noneHigh)).isEqualTo("high")
        assertThat(nearestReasoningEffort("xhigh", listOf("low", "medium", "high", "max"))).isEqualTo("high")
        assertThat(nearestReasoningEffort("bogus", noneHigh)).isNull()
    }
}
