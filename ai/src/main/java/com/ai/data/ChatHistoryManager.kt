package com.ai.data

import android.content.Context
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    @Volatile private var cachedHeaders: List<ChatSessionHeader>? = null

    fun init(context: Context) {
        lock.withLock {
            if (historyDir != null) return
            historyDir = File(context.filesDir, HISTORY_DIR).also { if (!it.exists()) it.mkdirs() }
        }
    }

    fun saveSession(session: ChatSession): Boolean {
        val dir = historyDir ?: run { AppLog.w("ChatHistory", "Not initialized"); return false }
        // Defence in depth: imports carry an externally-supplied id.
        // Internal callers all use UUIDs, but a crafted runtime-import
        // payload with `id = "../reports/foo"` would otherwise land
        // outside chat-history/. Reject and canonical-check.
        if (!isSafeFlatId(session.id)) {
            AppLog.e("ChatHistory", "Refusing to save session with suspect id ${session.id}")
            return false
        }
        var notifyChanged = false
        val saved = lock.withLock {
            if (!dir.exists()) dir.mkdirs()
            try {
                val target = File(dir, "${session.id}.json")
                if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                    AppLog.e("ChatHistory", "Refusing to save session that escapes historyDir: ${session.id}")
                    return@withLock false
                }
                // writeTextAtomic returns false on disk-full / permission /
                // other I/O failure; previously we ignored that and
                // returned `true` regardless, so the caller had no way to
                // detect a save that didn't actually land. Forward the
                // boolean so the chat session UI can warn / retry instead
                // of pretending the message persisted.
                val json = gson.toJson(session)
                val ok = target.writeTextAtomic(json)
                if (ok) {
                    AppLog.d("ChatHistory", "save ${session.id} msgs=${session.messages.size} bytes=${json.length}")
                    cachedSessions = null
                    cachedHeaders = null
                    notifyChanged = true
                }
                ok
            } catch (e: Exception) {
                AppLog.e("ChatHistory", "Failed to save: ${e.message}"); false
            }
        }
        if (notifyChanged) notifyHistoryChanged()
        return saved
    }

    fun loadSession(sessionId: String): ChatSession? {
        val dir = historyDir ?: run { AppLog.w("ChatHistory", "Not initialized"); return null }
        if (!isSafeSessionFile(dir, sessionId)) {
            AppLog.e("ChatHistory", "Refusing to load session with unsafe id: $sessionId")
            return null
        }
        return lock.withLock {
            val file = File(dir, "$sessionId.json")
            if (!file.exists()) return null
            // Stream the file straight into Gson via reader() instead of
            // file.readText() + fromJson(String) — avoids holding the
            // whole JSON document as a String alongside Gson's parse
            // buffer, which matters for image-heavy sessions.
            try {
                file.bufferedReader().use { gson.fromJson(it, ChatSession::class.java) }
                    ?.also { AppLog.d("ChatHistory", "load ${it.id} msgs=${it.messages.size}") }
            } catch (e: Exception) { AppLog.e("ChatHistory", "Failed to load: ${e.message}"); null }
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
                catch (e: Exception) { AppLog.e("ChatHistory", "Failed to parse: ${e.message}"); null }
            }?.sortedByDescending { it.updatedAt } ?: emptyList()
            cachedSessions = sessions
            sessions
        }
    }

    fun getSessionHeaders(): List<ChatSessionHeader> {
        cachedHeaders?.let { return it }
        val dir = historyDir ?: return emptyList()
        if (!dir.exists()) return emptyList()
        return lock.withLock {
            cachedHeaders?.let { return it }
            val headers = dir.listFiles { f -> f.extension == "json" }?.mapNotNull(::readSessionHeader)
                ?.sortedByDescending { it.updatedAt }
                ?: emptyList()
            cachedHeaders = headers
            headers
        }
    }

    fun deleteSession(sessionId: String): Boolean {
        val dir = historyDir ?: return false
        if (!isSafeSessionFile(dir, sessionId)) {
            AppLog.e("ChatHistory", "Refusing to delete session with unsafe id: $sessionId")
            return false
        }
        return try {
            // Hold the lock across the disk delete + cache invalidation
            // so a concurrent getAllSessions can't observe the cached
            // list AFTER the file is gone but BEFORE cachedSessions is
            // null'd. Notification fires outside the lock so observers
            // don't deadlock if their callback re-enters this object.
            val deleted = lock.withLock {
                val ok = File(dir, "$sessionId.json").delete()
                if (ok) {
                    cachedSessions = null
                    cachedHeaders = null
                }
                ok
            }
            if (deleted) {
                AppLog.d("ChatHistory", "delete $sessionId")
                notifyHistoryChanged()
            }
            deleted
        } catch (_: Exception) { false }
    }

    /** Toggle the pinned flag on [sessionId]. Pinned sessions surface
     *  as their own section on the AI Chat hub. Doesn't bump
     *  updatedAt — pinning is metadata, not activity. */
    fun setSessionPinned(sessionId: String, pinned: Boolean): Boolean {
        val dir = historyDir ?: run { AppLog.w("ChatHistory", "Not initialized"); return false }
        if (!isSafeSessionFile(dir, sessionId)) {
            AppLog.e("ChatHistory", "Refusing to pin session with unsafe id: $sessionId")
            return false
        }
        return lock.withLock {
            val file = File(dir, "$sessionId.json")
            if (!file.exists()) return@withLock false
            try {
                val current = file.bufferedReader().use { gson.fromJson(it, ChatSession::class.java) }
                    ?: return@withLock false
                val json = gson.toJson(current.copy(pinned = pinned))
                val ok = file.writeTextAtomic(json)
                if (ok) {
                    cachedSessions = null
                    cachedHeaders = null
                    notifyHistoryChanged()
                }
                ok
            } catch (e: Exception) {
                AppLog.e("ChatHistory", "Failed to pin session: ${e.message}")
                false
            }
        }
    }

    fun deleteAllSessions(): Int {
        val dir = historyDir ?: return 0
        if (!dir.exists()) return 0
        return lock.withLock {
            var count = 0
            dir.listFiles { f -> f.extension == "json" }?.forEach { if (it.delete()) count++ }
            cachedSessions = null
            cachedHeaders = null
            if (count > 0) notifyHistoryChanged()
            count
        }
    }

    private fun isSafeFlatId(id: String): Boolean =
        id.isNotBlank() && id != "." && id != ".." &&
            !id.contains('/') && !id.contains('\\')

    /** Defence-in-depth for the read/delete paths (Bug 25): the same
     *  flat-id + canonical-containment guard saveSession applies, so a
     *  deep-link / nav-arg / import-driven id of `../reports/foo` can't
     *  read or delete a file outside historyDir. */
    private fun isSafeSessionFile(dir: File, sessionId: String): Boolean {
        if (!isSafeFlatId(sessionId)) return false
        return try {
            File(dir, "$sessionId.json").canonicalPath
                .startsWith(dir.canonicalPath + File.separator)
        } catch (_: Exception) { false }
    }

    fun getSessionCount(): Int {
        val dir = historyDir ?: return 0
        if (!dir.exists()) return 0
        return dir.listFiles { f -> f.extension == "json" }?.size ?: 0
    }

    suspend fun getAllSessionsAsync() = withContext(Dispatchers.IO) { getAllSessions() }
    suspend fun getSessionHeadersAsync() = withContext(Dispatchers.IO) { getSessionHeaders() }
    suspend fun getSessionCountAsync() = withContext(Dispatchers.IO) { getSessionCount() }

    // Monotonic counter, not wall-clock (Bug 26): two mutations in the same
    // millisecond would set _historyVersion to an equal value and StateFlow
    // drops equal emissions, so a collector keyed on it could miss a refresh.
    private fun notifyHistoryChanged() { _historyVersion.update { it + 1 } }

    private fun readSessionHeader(file: File): ChatSessionHeader? = try {
        file.bufferedReader().use { reader ->
            JsonReader(reader).use { json ->
                var id = file.nameWithoutExtension
                var title = ""
                var pinned = false
                var updatedAt = file.lastModified()
                var preview: String? = null
                var lastVisibleRole: String? = null
                json.beginObject()
                while (json.hasNext()) {
                    when (json.nextName()) {
                        "id" -> id = json.nextStringOrNull() ?: id
                        "title" -> title = json.nextStringOrNull().orEmpty()
                        "pinned" -> pinned = json.nextBooleanOrDefault(false)
                        "updatedAt" -> updatedAt = json.nextLongOrDefault(updatedAt)
                        "messages" -> {
                            json.beginArray()
                            while (json.hasNext()) {
                                val message = json.readMessageHeader()
                                val role = message.first
                                val content = message.second
                                if (role != null && role != "system") lastVisibleRole = role
                                if (role == "user" && preview == null) preview = content.orEmpty().take(50)
                            }
                            json.endArray()
                        }
                        else -> json.skipValue()
                    }
                }
                json.endObject()
                ChatSessionHeader(
                    id = id,
                    title = title,
                    preview = preview ?: "Empty chat",
                    pinned = pinned,
                    updatedAt = updatedAt,
                    lastVisibleRole = lastVisibleRole
                )
            }
        }
    } catch (e: Exception) {
        AppLog.e("ChatHistory", "Failed to parse header: ${e.message}")
        null
    }

    private fun JsonReader.readMessageHeader(): Pair<String?, String?> {
        var role: String? = null
        var content: String? = null
        beginObject()
        while (hasNext()) {
            when (nextName()) {
                "role" -> role = nextStringOrNull()
                "content" -> content = nextStringOrNull()
                else -> skipValue()
            }
        }
        endObject()
        return role to content
    }

    private fun JsonReader.nextStringOrNull(): String? =
        if (peek() == JsonToken.NULL) {
            nextNull()
            null
        } else {
            nextString()
        }

    private fun JsonReader.nextBooleanOrDefault(default: Boolean): Boolean =
        if (peek() == JsonToken.NULL) {
            nextNull()
            default
        } else {
            nextBoolean()
        }

    private fun JsonReader.nextLongOrDefault(default: Long): Long =
        if (peek() == JsonToken.NULL) {
            nextNull()
            default
        } else {
            nextLong()
        }
}
