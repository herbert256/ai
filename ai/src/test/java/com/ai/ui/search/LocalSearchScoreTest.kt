package com.ai.ui.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [localSearchScore] / [localSearchTokenRegex] — token scoring for local
 *  report search. Audit chat#55: non-ASCII tokens must match. */
class LocalSearchScoreTest {

    @Test fun ascii_token_respects_word_boundaries() {
        // "in" matches the standalone word, NOT inside "rain" / "spain".
        assertThat(localSearchScore("rain in spain", listOf("in"))).isEqualTo(1)
    }

    @Test fun ascii_token_counts_each_occurrence() {
        assertThat(localSearchScore("in in in", listOf("in"))).isEqualTo(3)
    }

    @Test fun accented_token_matches_via_substring() {
        // The bug: with ASCII \b this scored 0.
        assertThat(localSearchScore("a lovely café latte", listOf("café"))).isEqualTo(1)
        assertThat(localSearchScore("schöne grüße", listOf("schöne"))).isEqualTo(1)
    }

    @Test fun cjk_token_matches() {
        assertThat(localSearchScore("文档很好用", listOf("文档"))).isEqualTo(1)
    }

    @Test fun cyrillic_token_matches() {
        assertThat(localSearchScore("это хороший отчёт", listOf("отчёт"))).isEqualTo(1)
    }

    @Test fun multiple_tokens_sum() {
        assertThat(localSearchScore("hello world hello", listOf("hello", "world"))).isEqualTo(3)
    }

    @Test fun no_match_scores_zero() {
        assertThat(localSearchScore("nothing relevant here", listOf("absent"))).isEqualTo(0)
    }

    @Test fun ascii_is_boundary_bound_nonascii_is_substring() {
        // ASCII token: boundary — "cat" must NOT match inside "category".
        assertThat(localSearchScore("category", listOf("cat"))).isEqualTo(0)
        // non-ASCII token: substring — matches inside a longer run.
        assertThat(localSearchScore("naïveté", listOf("naïve"))).isEqualTo(1)
    }
}
