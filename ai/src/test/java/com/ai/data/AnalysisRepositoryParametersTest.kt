package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnalysisRepositoryParametersTest {
    @Test fun mergeParameters_allows_report_override_to_disable_citations() {
        val merged = AnalysisRepository().mergeParameters(
            agentParams = AgentParameters(returnCitations = true, searchEnabled = true),
            overrideParams = AgentParameters(returnCitations = false)
        )

        assertThat(merged.returnCitations).isFalse()
        assertThat(merged.searchEnabled).isTrue()
    }
}
