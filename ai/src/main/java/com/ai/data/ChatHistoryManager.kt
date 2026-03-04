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
        val dir = historyDir ?: return false
        return lock.withLock {
            if (!dir.exists()) dir.mkdirs()
            try {
                File(dir, "${session.id}.json").writeText(gson.toJson(session))
                cachedSessions = null
                notifyHistoryChanged(); true
            } catch (e: Exception) {
                android.util.Log.e("ChatHistoryManager", "Failed to save: ${e.message}"); false
            }
        }
    }

    fun loadSession(sessionId: String): ChatSession? {
        val dir = historyDir ?: return null
        return lock.withLock {
            val file = File(dir, "$sessionId.json")
            if (!file.exists()) return null
            try { gson.fromJson(file.readText(), ChatSession::class.java) }
            catch (e: Exception) { android.util.Log.e("ChatHistoryManager", "Failed to load: ${e.message}"); null }
        }
    }

    fun getAllSessions(): List<ChatSession> {
        cachedSessions?.let { return it }
        val dir = historyDir ?: return emptyList()
        if (!dir.exists()) return emptyList()
        return lock.withLock {
            cachedSessions?.let { return it }
            val sessions = dir.listFiles { f -> f.extension == "json" }?.mapNotNull { file ->
                try { gson.fromJson(file.readText(), ChatSession::class.java) }
                catch (e: Exception) { android.util.Log.e("ChatHistoryManager", "Failed to parse: ${e.message}"); null }
            }?.sortedByDescending { it.updatedAt } ?: emptyList()
            cachedSessions = sessions
            sessions
        }
    }

    fun deleteSession(sessionId: String): Boolean {
        val dir = historyDir ?: return false
        return try {
            File(dir, "$sessionId.json").delete().also {
                if (it) { lock.withLock { cachedSessions = null }; notifyHistoryChanged() }
            }
        } catch (_: Exception) { false }
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
