package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [Report.notesFor] — pure filter of a report's user notes by target,
 *  newest-first. */
class ReportNotesTest {
    private fun note(id: String, kind: String, target: String, createdAt: Long) =
        UserNote(id = id, targetKind = kind, targetId = target, text = "t", createdAt = createdAt)

    private fun report(notes: List<UserNote>) = Report(
        id = "r", timestamp = 0, title = "T", prompt = "P",
        agents = mutableListOf(), userNotes = notes.toMutableList()
    )

    @Test fun filters_by_target_kind_and_id() {
        val r = report(
            listOf(
                note("1", "AGENT", "a1", 10),
                note("2", "AGENT", "a2", 20),
                note("3", "REPORT", "a1", 30)
            )
        )
        assertThat(r.notesFor("AGENT", "a1").map { it.id }).containsExactly("1")
    }

    @Test fun returns_newest_first() {
        val r = report(
            listOf(
                note("old", "AGENT", "a1", 10),
                note("new", "AGENT", "a1", 99)
            )
        )
        assertThat(r.notesFor("AGENT", "a1").map { it.id }).containsExactly("new", "old").inOrder()
    }

    @Test fun empty_when_nothing_matches() {
        val r = report(listOf(note("1", "AGENT", "a1", 10)))
        assertThat(r.notesFor("SECONDARY", "x")).isEmpty()
    }

    @Test fun empty_for_a_report_with_no_notes() {
        assertThat(report(emptyList()).notesFor("AGENT", "a1")).isEmpty()
    }
}
