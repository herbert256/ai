package com.ai.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.util.PersistentStateGuard
import com.ai.util.TestProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatHistoryManagerInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    // Register a synthetic provider so the AppServiceAdapter can
    // round-trip ChatSession.provider through gson by id lookup.
    private val unitProvider get() = TestProvider.service

    @Before fun reset() {
        TestProvider.register(context)
        ChatHistoryManager.init(context)
        ChatHistoryManager.deleteAllSessions()
    }
    @After fun cleanup() {
        ChatHistoryManager.deleteAllSessions()
        TestProvider.unregister()
    }

    companion object {
        @ClassRule @JvmField val stateGuard = PersistentStateGuard()
    }

    private fun session(id: String, messages: Int = 2, updatedAt: Long = System.currentTimeMillis()) =
        ChatSession(
            id = id,
            provider = unitProvider,
            model = "m",
            messages = (1..messages).map { ChatMessage("user", "msg-$it") },
            createdAt = updatedAt,
            updatedAt = updatedAt
        )

    @Test fun saveSession_then_loadSession_round_trips_messages_and_provider() {
        val s = session("a")
        assertThat(ChatHistoryManager.saveSession(s)).isTrue()

        val loaded = ChatHistoryManager.loadSession("a")
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.id).isEqualTo("a")
        assertThat(loaded.provider.id).isEqualTo(TestProvider.ID)
        assertThat(loaded.messages.map { it.content }).containsExactly("msg-1", "msg-2")
    }

    @Test fun getAllSessions_returns_newest_first_by_updatedAt() {
        ChatHistoryManager.saveSession(session("oldest", updatedAt = 1_000))
        ChatHistoryManager.saveSession(session("middle", updatedAt = 2_000))
        ChatHistoryManager.saveSession(session("newest", updatedAt = 3_000))
        val ids = ChatHistoryManager.getAllSessions().map { it.id }
        assertThat(ids).containsExactly("newest", "middle", "oldest").inOrder()
    }

    @Test fun deleteSession_removes_only_one_and_invalidates_cache() {
        ChatHistoryManager.saveSession(session("a"))
        ChatHistoryManager.saveSession(session("b"))
        // Prime the cached list
        assertThat(ChatHistoryManager.getAllSessions()).hasSize(2)

        assertThat(ChatHistoryManager.deleteSession("a")).isTrue()
        val remaining = ChatHistoryManager.getAllSessions().map { it.id }
        assertThat(remaining).containsExactly("b")
    }

    @Test fun deleteSession_returns_false_for_unknown_id() {
        ChatHistoryManager.saveSession(session("a"))
        assertThat(ChatHistoryManager.deleteSession("nope")).isFalse()
    }

    @Test fun deleteAllSessions_returns_count_and_empties() {
        ChatHistoryManager.saveSession(session("a"))
        ChatHistoryManager.saveSession(session("b"))
        ChatHistoryManager.saveSession(session("c"))
        val n = ChatHistoryManager.deleteAllSessions()
        assertThat(n).isEqualTo(3)
        assertThat(ChatHistoryManager.getSessionCount()).isEqualTo(0)
    }

    @Test fun getSessionCount_matches_number_of_files() {
        assertThat(ChatHistoryManager.getSessionCount()).isEqualTo(0)
        ChatHistoryManager.saveSession(session("a"))
        ChatHistoryManager.saveSession(session("b"))
        assertThat(ChatHistoryManager.getSessionCount()).isEqualTo(2)
    }

    @Test fun historyVersion_bumps_on_save_and_delete() {
        val before = ChatHistoryManager.historyVersion.value
        Thread.sleep(2)
        ChatHistoryManager.saveSession(session("v1"))
        val afterSave = ChatHistoryManager.historyVersion.value
        assertThat(afterSave).isGreaterThan(before)

        Thread.sleep(2)
        ChatHistoryManager.deleteSession("v1")
        val afterDelete = ChatHistoryManager.historyVersion.value
        assertThat(afterDelete).isGreaterThan(afterSave)
    }
}
