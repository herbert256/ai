package com.ai.data.preferences

import com.ai.data.createAppGson
import com.ai.data.writeTextAtomic
import com.ai.viewmodel.PromptHistoryEntry
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Owns the report prompt-history list — newest-first, de-duplicated, capped at
 * [MAX], stored as JSON under `<filesDir>/<FILE>`.
 *
 * First step of the per-domain settings-persistence split (audit D01): this was
 * a cluster of methods + cache/lock/constants inside the 1,100-line
 * SettingsPreferences. The file name and cap are unchanged so existing history
 * loads as-is, and SettingsPreferences keeps thin delegating methods, so every
 * caller is untouched. A focused, testable home for one durable concern.
 */
class PromptHistoryStore(private val filesDir: File?) {
    private val gson = createAppGson()
    private val lock = Any()
    @Volatile private var cache: List<PromptHistoryEntry>? = null
    private val listType = object : TypeToken<List<PromptHistoryEntry>>() {}.type

    fun load(): List<PromptHistoryEntry> {
        cache?.let { return it }
        return synchronized(lock) {
            cache?.let { return@synchronized it }
            readFromDisk().also { cache = it }
        }
    }

    private fun readFromDisk(): List<PromptHistoryEntry> {
        val file = filesDir?.let { File(it, FILE) } ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try { gson.fromJson(file.readText(), listType) ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    /** Prepend a (title, prompt); an exact duplicate is moved to the front. */
    fun add(title: String, prompt: String) {
        synchronized(lock) {
            val history = (cache ?: readFromDisk().also { cache = it }).toMutableList()
            history.indexOfFirst { it.title == title && it.prompt == prompt }.let { if (it >= 0) history.removeAt(it) }
            history.add(0, PromptHistoryEntry(System.currentTimeMillis(), title, prompt))
            saveListLocked(history.take(MAX))
        }
    }

    fun saveList(entries: List<PromptHistoryEntry>) {
        synchronized(lock) { saveListLocked(entries) }
    }

    private fun saveListLocked(entries: List<PromptHistoryEntry>) {
        val file = filesDir?.let { File(it, FILE) } ?: return
        val snapshot = entries.take(MAX)
        file.writeTextAtomic(gson.toJson(snapshot))
        cache = snapshot
    }

    /** Wipe the file and return how many entries it held before the wipe. */
    fun clear(): Int = synchronized(lock) {
        val n = (cache ?: readFromDisk().also { cache = it }).size
        filesDir?.let { File(it, FILE) }?.let { if (it.exists()) it.delete() }
        cache = emptyList()
        n
    }

    companion object {
        const val FILE = "prompt-history.json"
        const val MAX = 100
    }
}
