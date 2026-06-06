package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FanOutHttpStatsTest {
    @Test fun groups_final_http_statuses_by_responder_model() {
        val stats = computeFanOutHttpStatusStats(
            listOf(
                pair("OpenAI", "gpt-a", 200),
                pair("OpenAI", "gpt-a", 429),
                pair("OpenAI", "gpt-a", 529),
                pair("OpenAI", "gpt-a", 404),
                pair("OpenAI", "gpt-a", 503),
                pair("OpenAI", "gpt-a", 201),
                pair("OpenAI", "gpt-a", null),
                pair("Anthropic", "claude-b", 200),
                pair("Anthropic", "claude-b", 500)
            )
        )

        assertThat(stats.totalPairs).isEqualTo(9)
        assertThat(stats.modelCount).isEqualTo(2)
        assertThat(stats.noHttpCount).isEqualTo(1)

        val openAi = stats.rows.first { it.modelKey == "OpenAI|gpt-a" }.counts
        assertThat(openAi.ok200).isEqualTo(1)
        assertThat(openAi.rate429).isEqualTo(1)
        assertThat(openAi.overloaded529).isEqualTo(1)
        assertThat(openAi.client4xx).isEqualTo(1)
        assertThat(openAi.server5xx).isEqualTo(1)
        assertThat(openAi.other).isEqualTo(1)
        assertThat(openAi.noHttp).isEqualTo(1)

        val anthropic = stats.rows.first { it.modelKey == "Anthropic|claude-b" }.counts
        assertThat(anthropic.ok200).isEqualTo(1)
        assertThat(anthropic.server5xx).isEqualTo(1)
    }

    @Test fun special_buckets_are_not_counted_in_broad_families() {
        val counts = computeFanOutHttpStatusStats(
            listOf(pair("P", "m", 429), pair("P", "m", 529))
        ).rows.single().counts

        assertThat(counts.rate429).isEqualTo(1)
        assertThat(counts.overloaded529).isEqualTo(1)
        assertThat(counts.client4xx).isEqualTo(0)
        assertThat(counts.server5xx).isEqualTo(0)
    }

    private fun pair(providerId: String, model: String, httpStatusCode: Int?) = PairState(
        id = java.util.UUID.randomUUID().toString(),
        answererAgentId = "$providerId-$model-answerer",
        sourceAgentId = "source",
        providerId = providerId,
        model = model,
        status = PairStatus.DONE,
        httpStatusCode = httpStatusCode
    )
}
