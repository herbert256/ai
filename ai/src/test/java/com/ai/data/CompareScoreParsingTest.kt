package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Covers [parseSimilarityScore] — the meta_compare worker-reply parser whose
 *  null result is load-bearing (the engine treats it as a logical miss). */
class CompareScoreParsingTest {

    @Test fun null_or_blank_returns_null() {
        assertThat(parseSimilarityScore(null)).isNull()
        assertThat(parseSimilarityScore("")).isNull()
        assertThat(parseSimilarityScore("   \n   ")).isNull()
    }

    @Test fun prose_with_no_number_returns_null() {
        assertThat(parseSimilarityScore("These two answers are quite different.")).isNull()
    }

    @Test fun labeled_percentage_line() {
        val s = parseSimilarityScore("percentage: 40")!!
        assertThat(s.percent).isEqualTo(40)
        assertThat(s.reason).isNull()
    }

    @Test fun labeled_percentage_with_reason() {
        val s = parseSimilarityScore("percentage: 72\nreason: fairly close")!!
        assertThat(s.percent).isEqualTo(72)
        assertThat(s.reason).isEqualTo("fairly close")
    }

    @Test fun reason_line_can_precede_percentage() {
        val s = parseSimilarityScore("reason: because reasons\npercentage: 50")!!
        assertThat(s.percent).isEqualTo(50)
        assertThat(s.reason).isEqualTo("because reasons")
    }

    @Test fun labels_are_case_insensitive() {
        assertThat(parseSimilarityScore("PERCENTAGE: 60")!!.percent).isEqualTo(60)
        assertThat(parseSimilarityScore("Percent: 12")!!.percent).isEqualTo(12)
    }

    @Test fun decimal_value_is_truncated() {
        assertThat(parseSimilarityScore("percentage: 33.7")!!.percent).isEqualTo(33)
    }

    @Test fun value_is_clamped_to_0_100() {
        assertThat(parseSimilarityScore("percentage: 150")!!.percent).isEqualTo(100)
        assertThat(parseSimilarityScore("percentage: 0")!!.percent).isEqualTo(0)
    }

    @Test fun percent_suffix_is_tolerated() {
        assertThat(parseSimilarityScore("percentage: 47%")!!.percent).isEqualTo(47)
    }

    @Test fun strict_json_object_form() {
        val s = parseSimilarityScore("{\"percentage\": 88, \"reason\": \"near\"}")!!
        assertThat(s.percent).isEqualTo(88)
        assertThat(s.reason).isEqualTo("near")
    }

    @Test fun json_score_key_in_code_fence() {
        val s = parseSimilarityScore("```json\n{\"score\": 25}\n```")!!
        assertThat(s.percent).isEqualTo(25)
    }

    @Test fun plain_code_fence_without_json_tag() {
        assertThat(parseSimilarityScore("```\npercentage: 19\n```")!!.percent).isEqualTo(19)
    }

    @Test fun first_number_anywhere_as_last_resort() {
        assertThat(parseSimilarityScore("I'd say about 85 out of 100")!!.percent).isEqualTo(85)
    }
}
