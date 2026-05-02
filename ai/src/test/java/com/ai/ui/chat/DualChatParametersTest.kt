package com.ai.ui.chat

import com.ai.model.Parameters
import com.ai.model.Settings
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DualChatParametersTest {
    @Test fun resolveParamsIds_preserves_webSearchTool_and_reasoningEffort() {
        val settings = Settings(
            providers = emptyMap(),
            parameters = listOf(
                Parameters(
                    id = "reasoning-web",
                    name = "Reasoning Web",
                    webSearchTool = true,
                    reasoningEffort = "high"
                )
            )
        )

        val params = resolveParamsIds(settings, listOf("reasoning-web"))

        assertThat(params.webSearchTool).isTrue()
        assertThat(params.reasoningEffort).isEqualTo("high")
    }
}
