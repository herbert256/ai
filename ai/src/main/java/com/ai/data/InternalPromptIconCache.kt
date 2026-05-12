package com.ai.data

import android.content.Context
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Per-(name, title) emoji cache for [com.ai.model.InternalPrompt]s.
 *
 * Backs the "Use internal prompts icons" feature
 * ([com.ai.viewmodel.GeneralSettings.useInternalPromptsIcons]): each
 * `InternalPrompt` displayed on the report result page gets a leading
 * emoji generated once via the bundled `internal/prompt_icon` prompt
 * and cached here so re-rendering the same row is free.
 *
 * Storage layout: one JSON map under
 * `<filesDir>/internal_prompt_icons.json`. The map's keys are
 * `name + SEP + title` where SEP is the ASCII Unit Separator
 * (U+001F) — a non-printable control character that real prompt names
 * and titles never contain, so the join is unambiguous.
 *
 * Backup/restore: the file lives under `<filesDir>` and is **not** in
 * [BackupManager.FILES_DIR_BACKUP_EXCLUDES], so the standard recursive
 * `addDirectoryRecursive` path on backup captures it and the standard
 * restore path puts it back.
 *
 * Cleared by:
 *  - Housekeeping → Reset → **Clear all configuration**
 *  - Housekeeping → Reset → **Reset application**
 * Not touched by **Clear all runtime data** — this cache is
 * configuration-level (matches the prompts themselves).
 */
object InternalPromptIconCache {
    private const val FILE_NAME = "internal_prompt_icons.json"
    private const val SEP = ""  // ASCII Unit Separator
    private val mapType = object : TypeToken<Map<String, String>>() {}.type

    private val lock = ReentrantLock()
    @Volatile private var cacheFile: File? = null
    private val map = mutableMapOf<String, String>()

    /** Keys whose generation call is currently in flight. Guards
     *  against a second renderer firing a duplicate call for the same
     *  prompt before the first response settles. */
    private val inFlightKeys = mutableSetOf<String>()

    fun init(context: Context) = lock.withLock {
        val file = File(context.filesDir, FILE_NAME).also { cacheFile = it }
        if (!file.exists()) return@withLock
        try {
            val parsed: Map<String, String>? =
                createAppGson().fromJson(file.readText(), mapType)
            map.clear()
            parsed?.forEach { (k, v) -> if (v.isNotBlank()) map[k] = v }
            AppLog.d("InternalPromptIcon", "loaded ${map.size} cached icons")
        } catch (e: Exception) {
            AppLog.w("InternalPromptIcon", "load failed: ${e.message}")
            map.clear()
        }
    }

    fun get(name: String, title: String): String? = lock.withLock {
        map[keyOf(name, title)]
    }

    fun put(name: String, title: String, emoji: String) {
        if (emoji.isBlank()) return
        val key = keyOf(name, title)
        lock.withLock {
            map[key] = emoji
            saveLocked()
        }
        AppLog.d("InternalPromptIcon", "cached emoji for name='$name' title='$title' -> $emoji")
    }

    /** True if the call for ([name], [title]) is already running.
     *  Atomically marks the key as in-flight when it isn't yet — call
     *  sites do `if (!markInFlight(...)) return` to dedupe. */
    fun markInFlight(name: String, title: String): Boolean = lock.withLock {
        val key = keyOf(name, title)
        if (key in inFlightKeys) false else { inFlightKeys += key; true }
    }

    fun clearInFlight(name: String, title: String) = lock.withLock {
        inFlightKeys.remove(keyOf(name, title))
    }

    fun clearAll(context: Context): Int = lock.withLock {
        val n = map.size
        map.clear()
        inFlightKeys.clear()
        val file = cacheFile ?: File(context.filesDir, FILE_NAME).also { cacheFile = it }
        try { if (file.exists()) file.delete() } catch (_: Exception) {}
        AppLog.i("InternalPromptIcon", "clearAll dropped $n cached icons")
        n
    }

    private fun keyOf(name: String, title: String): String = name + SEP + title

    private fun saveLocked() {
        val file = cacheFile ?: return
        try {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            file.writeTextAtomic(createAppGson().toJson(map))
        } catch (e: Exception) {
            AppLog.w("InternalPromptIcon", "save failed: ${e.message}")
        }
    }
}
