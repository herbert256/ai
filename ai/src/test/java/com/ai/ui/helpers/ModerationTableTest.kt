package com.ai.ui.helpers

import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [parseModerationRows] + [anyModerationFlagged] — moderation JSON parsing and
 *  the View-tile flagged roll-up. */
class ModerationTableTest {
    private fun sr(content: String?) = SecondaryResult(
        id = "i", reportId = "r", kind = SecondaryKind.MODERATION,
        providerId = "p", model = "m", agentName = "a", timestamp = 0, content = content
    )

    @Test fun parses_rows_with_fired_categories() {
        val rows = parseModerationRows(
            "[{\"id\":1,\"flagged\":true,\"categories\":{\"hate\":true,\"spam\":false}," +
                "\"scores\":{\"hate\":0.9,\"spam\":0.1}}]"
        )!!
        assertThat(rows).hasSize(1)
        assertThat(rows[0].id).isEqualTo(1)
        assertThat(rows[0].flagged).isTrue()
        assertThat(rows[0].firedCategories).containsExactly("hate")
    }

    @Test fun unflagged_row_has_no_fired_categories() {
        val row = parseModerationRows(
            "[{\"id\":1,\"flagged\":false,\"categories\":{\"hate\":false},\"scores\":{\"hate\":0.01}}]"
        )!!.single()
        assertThat(row.flagged).isFalse()
        assertThat(row.firedCategories).isEmpty()
    }

    @Test fun empty_array_is_empty_list() {
        assertThat(parseModerationRows("[]")).isEmpty()
    }

    @Test fun non_array_returns_null() {
        assertThat(parseModerationRows("{}")).isNull()
    }

    @Test fun code_fence_tolerated() {
        assertThat(parseModerationRows("```json\n[{\"id\":1,\"flagged\":false}]\n```")).hasSize(1)
    }

    @Test fun anyFlagged_true_when_a_moderation_row_fires() {
        val rows = listOf(
            sr("[{\"id\":1,\"flagged\":false,\"categories\":{\"x\":false}}]"),
            sr("[{\"id\":2,\"flagged\":true,\"categories\":{\"x\":true}}]")
        )
        assertThat(anyModerationFlagged(rows)).isTrue()
    }

    @Test fun anyFlagged_false_when_nothing_fires() {
        val rows = listOf(sr("[{\"id\":1,\"flagged\":false,\"categories\":{\"x\":false}}]"))
        assertThat(anyModerationFlagged(rows)).isFalse()
    }

    @Test fun anyFlagged_ignores_non_moderation_kinds() {
        val notModeration = SecondaryResult(
            id = "i", reportId = "r", kind = SecondaryKind.RERANK,
            providerId = "p", model = "m", agentName = "a", timestamp = 0,
            content = "[{\"id\":1,\"flagged\":true,\"categories\":{\"x\":true}}]"
        )
        assertThat(anyModerationFlagged(listOf(notModeration))).isFalse()
    }
}
