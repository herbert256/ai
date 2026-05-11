package com.ai.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.concurrent.withLock

/**
 * Singleton managing chat session storage in JSON files under chat-history directory.
 */
object ChatHistoryManager {
    private const val HISTORY_DIR = "chat-history"
    private var historyDir: File? = null
    private val gson = createAppGson()
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val _historyVersion = MutableStateFlow(0L)
    val historyVersion: StateFlow<Long> = _historyVersion.asStateFlow()
    @Volatile private var cachedSessions: List<ChatSession>? = null

    fun init(context: Context) = lock.withLock {
        if (historyDir != null) return
        historyDir = File(context.filesDir, HISTORY_DIR).also { if (!it.exists()) it.mkdirs() }
    }

    fun saveSession(session: ChatSession): Boolean {
        val dir = historyDir ?: run { AppLog.w("ChatHistoryManager", "Not initialized"); return false }
        return lock.withLock {
            if (!dir.exists()) dir.mkdirs()
            try {
                // writeTextAtomic returns false on disk-full / permission /
                // other I/O failure; previously we ignored that and
                // returned `true` regardless, so the caller had no way to
                // detect a save that didn't actually land. Forward the
                // boolean so the chat session UI can warn / retry instead
                // of pretending the message persisted.
                val ok = File(dir, "${session.id}.json").writeTextAtomic(gson.toJson(session))
                if (ok) {
                    cachedSessions = null
                    notifyHistoryChanged()
                }
                ok
            } catch (e: Exception) {
                AppLog.e("ChatHistoryManager", "Failed to save: ${e.message}"); false
            }
        }
    }

    fun loadSession(sessionId: String): ChatSession? {
        val dir = historyDir ?: run { AppLog.w("ChatHistoryManager", "Not initialized"); return null }
        return lock.withLock {
            val file = File(dir, "$sessionId.json")
            if (!file.exists()) return null
            // Stream the file straight into Gson via reader() instead of
            // file.readText() + fromJson(String) — avoids holding the
            // whole JSON document as a String alongside Gson's parse
            // buffer, which matters for image-heavy sessions.
            try { file.bufferedReader().use { gson.fromJson(it, ChatSession::class.java) } }
            catch (e: Exception) { AppLog.e("ChatHistoryManager", "Failed to load: ${e.message}"); null }
        }
    }

    fun getAllSessions(): List<ChatSession> {
        cachedSessions?.let { return it }
        val dir = historyDir ?: return emptyList()
        if (!dir.exists()) return emptyList()
        return lock.withLock {
            cachedSessions?.let { return it }
            val sessions = dir.listFiles { f -> f.extension == "json" }?.mapNotNull { file ->
                try { file.bufferedReader().use { gson.fromJson(it, ChatSession::class.java) } }
                catch (e: Exception) { AppLog.e("ChatHistoryManager", "Failed to parse: ${e.message}"); null }
            }?.sortedByDescending { it.updatedAt } ?: emptyList()
            cachedSessions = sessions
            sessions
        }
    }

    fun deleteSession(sessionId: String): Boolean {
        val dir = historyDir ?: return false
        return try {
            // Hold the lock across the disk delete + cache invalidation
            // so a concurrent getAllSessions can't observe the cached
            // list AFTER the file is gone but BEFORE cachedSessions is
            // null'd. Notification fires outside the lock so observers
            // don't deadlock if their callback re-enters this object.
            val deleted = lock.withLock {
                val ok = File(dir, "$sessionId.json").delete()
                if (ok) cachedSessions = null
                ok
            }
            if (deleted) notifyHistoryChanged()
            deleted
        } catch (_: Exception) { false }
    }

    /** Toggle the pinned flag on [sessionId]. Pinned sessions surface
     *  as their own section on the AI Chat hub. Doesn't bump
     *  updatedAt — pinning is metadata, not activity. */
    fun setSessionPinned(sessionId: String, pinned: Boolean): Boolean {
        val current = loadSession(sessionId) ?: return false
        return saveSession(current.copy(pinned = pinned))
    }

    fun deleteAllSessions(): Int {
        val dir = historyDir ?: return 0
        if (!dir.exists()) return 0
        return lock.withLock {
            var count = 0
            dir.listFiles { f -> f.extension == "json" }?.forEach { if (it.delete()) count++ }
            cachedSessions = null
            if (count > 0) notifyHistoryChanged()
            count
        }
    }

    fun getSessionCount(): Int {
        val dir = historyDir ?: return 0
        if (!dir.exists()) return 0
        return dir.listFiles { f -> f.extension == "json" }?.size ?: 0
    }

    suspend fun getAllSessionsAsync() = withContext(Dispatchers.IO) { getAllSessions() }
    suspend fun getSessionCountAsync() = withContext(Dispatchers.IO) { getSessionCount() }

    private fun notifyHistoryChanged() { _historyVersion.value = System.currentTimeMillis() }
}
