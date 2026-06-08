package com.ai.ui.report.view

import com.ai.ui.shared.formatCentsValue
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure AnswerMatrix helpers (made `internal` for testability): cost/duration
 *  formatting and the response-text signal extractors. formatCentsValue uses an
 *  explicit Locale.US, so these are locale-independent. */
class AnswerMatrixHelpersTest {

    // ---- formatCentsValue ----

    @Test fun cents_uses_fixed_four_decimals_by_default() {
        // formatCentsValue is a plain fixed-decimals primitive (default 4). The
        // zero/negative → "-" rule lives at the call sites (e.g. AnswerMatrix's
        // `if (cost > 0.0) formatCentsValue(..) else "-"`), not in this helper.
        assertThat(formatCentsValue(0.0)).isEqualTo("0.0000 ¢")
        assertThat(formatCentsValue(12.5)).isEqualTo("12.5000 ¢")
    }

    @Test fun cents_decimals_param_controls_precision() {
        assertThat(formatCentsValue(12.5, decimals = 2)).isEqualTo("12.50 ¢")
        assertThat(formatCentsValue(2.5, decimals = 3)).isEqualTo("2.500 ¢")
        assertThat(formatCentsValue(0.5, decimals = 4)).isEqualTo("0.5000 ¢")
    }

    // ---- formatDuration ----

    @Test fun duration_null_or_nonpositive_is_dash() {
        assertThat(formatDuration(null)).isEqualTo("-")
        assertThat(formatDuration(0L)).isEqualTo("-")
        assertThat(formatDuration(-5L)).isEqualTo("-")
    }

    @Test fun duration_millis_seconds_minutes_hours() {
        assertThat(formatDuration(500L)).isEqualTo("500 ms")
        assertThat(formatDuration(999L)).isEqualTo("999 ms")
        assertThat(formatDuration(1_500L)).isEqualTo("1s")
        assertThat(formatDuration(65_000L)).isEqualTo("1m 5s")
        assertThat(formatDuration(3_661_000L)).isEqualTo("1h 1m")
    }

    // ---- cleanResponseText ----

    @Test fun clean_strips_think_and_tags_and_collapses_whitespace() {
        assertThat(cleanResponseText("Hello <think>secret</think> world")).isEqualTo("Hello world")
        assertThat(cleanResponseText("<conclusion>done</conclusion>")).isEqualTo("done")
        assertThat(cleanResponseText("a\n\n   b")).isEqualTo("a b")
        assertThat(cleanResponseText("  trim me  ")).isEqualTo("trim me")
    }

    // ---- splitSentences ----

    @Test fun split_separates_on_terminal_punctuation() {
        assertThat(splitSentences("First. Second! Third?"))
            .containsExactly("First.", "Second!", "Third?").inOrder()
    }

    @Test fun split_strips_leading_bullets_and_drops_blanks() {
        assertThat(splitSentences("- bullet one. * bullet two."))
            .containsExactly("bullet one.", "bullet two.").inOrder()
    }

    @Test fun split_unpunctuated_is_one_sentence() {
        assertThat(splitSentences("just one line")).containsExactly("just one line")
    }

    // ---- firstUsefulSentence ----

    @Test fun first_useful_sentence_skips_think_blocks() {
        assertThat(firstUsefulSentence("<think>noise</think> Real sentence. More."))
            .isEqualTo("Real sentence.")
    }

    @Test fun first_useful_sentence_null_when_empty() {
        assertThat(firstUsefulSentence("")).isNull()
        assertThat(firstUsefulSentence("   ")).isNull()
    }

    // ---- compactText ----

    @Test fun compact_collapses_whitespace() {
        assertThat(compactText("a  b   c", 100)).isEqualTo("a b c")
    }

    @Test fun compact_returns_short_text_unchanged() {
        assertThat(compactText("short", 100)).isEqualTo("short")
    }

    @Test fun compact_truncates_with_ellipsis() {
        assertThat(compactText("0123456789", 8)).isEqualTo("01234...")
    }
}
