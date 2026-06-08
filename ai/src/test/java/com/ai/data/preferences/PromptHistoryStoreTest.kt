package com.ai.data.preferences

import com.ai.viewmodel.PromptHistoryEntry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Locks down the PromptHistoryStore extracted from SettingsPreferences (audit
 * D01): newest-first ordering, duplicate-to-front, the MAX cap, clear, and
 * cross-instance persistence (the on-disk file is the source of truth).
 */
class PromptHistoryStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = PromptHistoryStore(tmp.root)

    @Test fun add_prepends_newest_first_and_persists_across_instances() {
        val s = store()
        s.add("t1", "p1")
        s.add("t2", "p2")

        assertThat(s.load().map { it.title }).containsExactly("t2", "t1").inOrder()
        // A fresh instance reads the same backing file.
        assertThat(PromptHistoryStore(tmp.root).load().map { it.title })
            .containsExactly("t2", "t1").inOrder()
    }

    @Test fun exact_duplicate_moves_to_front() {
        val s = store()
        s.add("t1", "p1")
        s.add("t2", "p2")
        s.add("t1", "p1")

        assertThat(s.load().map { it.title }).containsExactly("t1", "t2").inOrder()
    }

    @Test fun caps_at_MAX_keeping_newest() {
        val s = store()
        repeat(PromptHistoryStore.MAX + 10) { s.add("t$it", "p$it") }

        assertThat(s.load()).hasSize(PromptHistoryStore.MAX)
        assertThat(s.load().first().title).isEqualTo("t${PromptHistoryStore.MAX + 9}")
    }

    @Test fun clear_wipes_and_returns_prior_count() {
        val s = store()
        s.add("a", "1")
        s.add("b", "2")

        assertThat(s.clear()).isEqualTo(2)
        assertThat(s.load()).isEmpty()
    }

    @Test fun saveList_replaces_the_whole_list() {
        val s = store()
        s.saveList((1..3).map { PromptHistoryEntry(it.toLong(), "t$it", "p$it") })

        assertThat(s.load().map { it.title }).containsExactly("t1", "t2", "t3").inOrder()
    }
}
