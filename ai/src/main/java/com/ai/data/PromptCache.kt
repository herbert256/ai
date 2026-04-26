package com.ai.data

import android.content.Context
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Disk cache for AI-prompt responses keyed by (prompt text, agent identity).
 * Used by Internal-Prompt invocations (e.g. the AI Introduction on Model Info) to avoid
 * paying for the same call repeatedly. Entries expire after 48h.
 */
object PromptCache {
    private const val DIR = "prompt_cache"
    private const val TTL_MS = 48L * 60 * 60 * 1000
    private val lock = ReentrantLock()
    private var cacheDir: File? = null

    fun init(context: Context) = lock.withLock {
        cacheDir = File(context.filesDir, DIR).also { if (!it.exists()) it.mkdirs() }
    }

    /** Stable SHA-256 hash of (prompt + agentId). Different agent ⇒ different key, different
     *  resolved prompt (e.g. with @MODEL@ replaced) ⇒ different key. */
    fun keyFor(prompt: String, agentId: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest("$agentId|$prompt".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun get(key: String): String? = lock.withLock {
        val file = File(cacheDir ?: return@withLock null, "$key.json")
        if (!file.exists()) return@withLock null
        try {
            @Suppress("DEPRECATION")
            val obj = JsonParser().parse(file.readText()).asJsonObject
            val ts = obj.get("timestamp")?.asLong ?: 0L
            if (System.currentTimeMillis() - ts > TTL_MS) {
                file.delete()
                return@withLock null
            }
            obj.get("response")?.asString
        } catch (_: Exception) {
            try { file.delete() } catch (_: Exception) {}
            null
        }
    }

    fun put(key: String, response: String) = lock.withLock {
        val dir = cacheDir ?: return@withLock
        if (!dir.exists()) dir.mkdirs()
        val payload = createAppGson().toJson(mapOf(
            "timestamp" to System.currentTimeMillis(),
            "response" to response
        ))
        try { File(dir, "$key.json").writeTextAtomic(payload) }
        catch (_: Exception) {}
    }

    fun clearAll(): Int = lock.withLock {
        val dir = cacheDir ?: return@withLock 0
        var n = 0
        dir.listFiles { f -> f.extension == "json" }?.forEach { if (it.delete()) n++ }
        n
    }
}
