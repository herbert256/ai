package com.ai.ui.helpers

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** [formatRerankScore] + [parseRerankRows] — the rerank flow's score format and
 *  structured-output parser. */
class RerankTableTest {
    private val saved = Locale.getDefault()
    // formatRerankScore's fractional branch uses "%.3f".format (locale default);
    // pin US so the decimal separator is deterministic on any dev machine.
    @Before fun pin() = Locale.setDefault(Locale.US)
    @After fun restore() = Locale.setDefault(saved)

    @Test fun whole_numbers_have_no_decimal_point() {
        assertThat(formatRerankScore(5.0)).isEqualTo("5")
        assertThat(formatRerankScore(0.0)).isEqualTo("0")
        assertThat(formatRerankScore(100.0)).isEqualTo("100")
    }

    @Test fun fractional_scores_trim_trailing_zeros() {
        assertThat(formatRerankScore(0.875)).isEqualTo("0.875")
        assertThat(formatRerankScore(0.870)).isEqualTo("0.87")
        assertThat(formatRerankScore(0.5)).isEqualTo("0.5")
    }

    @Test fun parses_and_sorts_rows_by_rank_then_id() {
        val rows = parseRerankRows(
            "[{\"id\":2,\"rank\":1,\"score\":0.9,\"reason\":\"r\"},{\"id\":1,\"rank\":2,\"score\":0.5}]"
        )!!
        assertThat(rows.map { it.id }).containsExactly(2, 1).inOrder()
        assertThat(rows[0].score).isWithin(1e-9).of(0.9)
    }

    @Test fun empty_array_is_empty_list_not_null() {
        assertThat(parseRerankRows("[]")).isEmpty()
    }

    @Test fun non_array_returns_null() {
        assertThat(parseRerankRows("{\"id\":1}")).isNull()
    }

    @Test fun garbage_returns_null() {
        assertThat(parseRerankRows("not json")).isNull()
    }

    @Test fun code_fence_is_tolerated() {
        assertThat(parseRerankRows("```json\n[{\"id\":1,\"rank\":1}]\n```")).hasSize(1)
    }

    @Test fun rows_missing_id_are_dropped() {
        assertThat(parseRerankRows("[{\"rank\":1}]")).isNull()
    }

    @Test fun fractional_score_is_preserved() {
        assertThat(parseRerankRows("[{\"id\":1,\"score\":0.87}]")!!.single().score)
            .isWithin(1e-9).of(0.87)
    }

    @Test fun string_encoded_score_is_tolerated() {
        assertThat(parseRerankRows("[{\"id\":1,\"score\":\"0.5\"}]")!!.single().score)
            .isWithin(1e-9).of(0.5)
    }
}
