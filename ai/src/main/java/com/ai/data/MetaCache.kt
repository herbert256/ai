package com.ai.data

import android.content.Context
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Short-lived cache for AI-derived meta information about a piece of
 * text, so identical inputs don't re-pay for the same LLM call. Three
 * call sites use it today:
 *  - a report title for a prompt text  (category "report-title-*")
 *  - a fitting emoji for a language    (category "language-icon")
 * (an icon for a title is already cached persistently by
 *  [InternalPromptIconCache], which is keyed by (name, title)).
 *
 * Entries expire after [EXPIRY_MS] (7 days) — meta results are cheap to
 * regenerate and model quality drifts, so a stale entry self-heals on
 * the next miss. The cache key is the MD5 of `category␟input` or
 * `category␟variant␟input`: MD5 keeps
 * arbitrarily long inputs (a whole prompt body) to a fixed-size,
 * filesystem-safe key, and the category prefix keeps the title and icon
 * namespaces from colliding on the same input.
 *
 * Storage: one JSON map under `<filesDir>/meta_cache.json`. Cleared by
 * the same Reset paths as [InternalPromptIconCache].
 */
object MetaCache {
    private const val FILE_NAME = "meta_cache.json"
    private const val SEP = ''  // ASCII Unit Separator
    /** 7-day expiry. */
    const val EXPIRY_MS = 7L * 24 * 60 * 60 * 1000

    data class Entry(val value: String, val timestamp: Long)

    private val mapType = object : TypeToken<Map<String, Entry>>() {}.type
    private val lock = ReentrantLock()
    @Volatile private var cacheFile: File? = null
    private val map = mutableMapOf<String, Entry>()

    fun init(context: Context) = lock.withLock {
        val file = File(context.filesDir, FILE_NAME).also { cacheFile = it }
        if (!file.exists()) return@withLock
        try {
            val parsed: Map<String, Entry>? = createAppGson().fromJson(file.readText(), mapType)
            map.clear()
            val now = System.currentTimeMillis()
            parsed?.forEach { (k, v) ->
                @Suppress("SENSELESS_COMPARISON")
                if (v != null && v.value.isNotBlank() && now - v.timestamp < EXPIRY_MS) map[k] = v
            }
            AppLog.d("MetaCache", "loaded ${map.size} live entries")
        } catch (e: Exception) {
            AppLog.w("MetaCache", "load failed: ${e.message}")
            map.clear()
        }
    }

    /** Cached value for ([category], [input]) when present and younger
     *  than [EXPIRY_MS]; null otherwise (and an expired hit is evicted). */
    fun get(category: String, input: String, variant: String = ""): String? = lock.withLock {
        val key = keyOf(category, input, variant)
        val e = map[key] ?: return@withLock null
        if (System.currentTimeMillis() - e.timestamp >= EXPIRY_MS) {
            map.remove(key); saveLocked(); return@withLock null
        }
        e.value
    }

    fun put(category: String, input: String, value: String, variant: String = "") {
        if (value.isBlank()) return
        lock.withLock {
            map[keyOf(category, input, variant)] = Entry(value, System.currentTimeMillis())
            saveLocked()
        }
    }

    /** Drop one cached ([category], [input]) entry so the next request
     *  re-pays for a fresh LLM call. Used by the per-item "reload" on the
     *  Report - Get info detail screens (titles + language icon are cached,
     *  so a reload must evict the entry to actually regenerate). No-op when
     *  the entry isn't present. */
    fun remove(category: String, input: String, variant: String = "") = lock.withLock {
        if (map.remove(keyOf(category, input, variant)) != null) saveLocked()
    }

    /** One entry as shown on the Caches → Meta screen. The input text isn't
     *  stored (only its MD5 [key]), so the cached [value] (a report title /
     *  language-icon emoji) is what identifies a row. */
    data class MetaListEntry(val key: String, val value: String, val timestamp: Long, val stale: Boolean)

    /** Every cached entry, newest first. Non-destructive (keeps stale ones
     *  so the Caches screen can show + delete them). */
    fun list(): List<MetaListEntry> = lock.withLock {
        val now = System.currentTimeMillis()
        map.entries.map { (k, e) -> MetaListEntry(k, e.value, e.timestamp, now - e.timestamp >= EXPIRY_MS) }
            .sortedByDescending { it.timestamp }
    }

    /** Delete one entry by its on-disk hashed [key] (from [list]). */
    fun removeByKey(key: String) = lock.withLock {
        if (map.remove(key) != null) saveLocked()
    }

    fun clearAll(context: Context): Int = lock.withLock {
        val n = map.size
        map.clear()
        val file = cacheFile ?: File(context.filesDir, FILE_NAME).also { cacheFile = it }
        try { if (file.exists()) file.delete() } catch (_: Exception) {}
        AppLog.i("MetaCache", "clearAll dropped $n entries")
        n
    }

    private fun keyOf(category: String, input: String, variant: String): String {
        val raw = if (variant.isBlank()) "$category$SEP$input" else "$category$SEP$variant$SEP$input"
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun saveLocked() {
        val file = cacheFile ?: return
        try {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            file.writeTextAtomic(createAppGson().toJson(map))
        } catch (e: Exception) {
            AppLog.w("MetaCache", "save failed: ${e.message}")
        }
    }
}
