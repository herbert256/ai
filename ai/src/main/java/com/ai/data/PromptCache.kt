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
        // Use a length-prefix scheme rather than a delimiter: agentId
        // and prompt may both contain `|`, so naively concatenating
        // with `|` collides (e.g., agentId="a", prompt="|b" hashes the
        // same bytes as agentId="a|", prompt="b"). Prepending the
        // agentId byte length removes the ambiguity.
        val md = MessageDigest.getInstance("SHA-256")
        val agentBytes = agentId.toByteArray(Charsets.UTF_8)
        md.update(agentBytes.size.toString().toByteArray(Charsets.UTF_8))
        md.update(":".toByteArray(Charsets.UTF_8))
        md.update(agentBytes)
        md.update(prompt.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Cached response paired with the wall-clock timestamp at which
     *  it was written. Returned by [getRaw] so callers that want a
     *  custom TTL (e.g. the View Model-Info screen, which uses a
     *  1-week refresh window) can age-check themselves without
     *  triggering the destructive 48 h TTL in [get]. */
    data class TimestampedResponse(val response: String, val timestamp: Long)

    /** Non-destructive read — returns whatever's on disk regardless
     *  of age. Distinct from [get] (which honours the 48 h TTL and
     *  deletes stale entries). Callers handle the age check
     *  themselves. */
    fun getRaw(key: String): TimestampedResponse? = lock.withLock {
        val file = File(cacheDir ?: return@withLock null, "$key.json")
        if (!file.exists()) return@withLock null
        try {
            @Suppress("DEPRECATION")
            val obj = JsonParser().parse(file.readText()).asJsonObject
            val response = obj.get("response")?.asString ?: return@withLock null
            val ts = obj.get("timestamp")?.asLong ?: 0L
            TimestampedResponse(response, ts)
        } catch (_: Exception) { null }
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
