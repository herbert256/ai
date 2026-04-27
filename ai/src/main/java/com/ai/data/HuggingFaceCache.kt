package com.ai.data

import android.content.Context
import androidx.core.content.edit

/**
 * Cache for HuggingFace model-info lookups, including error state.
 *
 * HuggingFace rate-limits unauthenticated lookups aggressively and caches
 * misses are common — many provider/model pairs simply don't have an HF
 * mirror. Re-querying every Model Info open burns the user's API
 * allowance and slows the screen. This cache:
 *
 *   • Holds the result for [TTL_MS] (7 days) keyed by `${providerId}::${modelId}`.
 *   • Stores `null` for misses so we don't retry failed lookups for a week.
 *   • Persists to SharedPreferences "huggingface_cache" (one JSON-blob key)
 *     so a process restart doesn't re-fetch.
 *   • Round-trips via BackupManager because the prefs file is included in
 *     [BackupManager.PREFS_TO_BACKUP].
 *
 * Restoring an older cache entry whose TTL has expired naturally falls
 * through to a fresh fetch.
 */
object HuggingFaceCache {
    private const val PREFS_NAME = "huggingface_cache"
    private const val KEY_ENTRIES = "entries_json"
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    private val gson = createAppGson()

    /** One stored result. [info] is null when the previous lookup found
     *  nothing (404 / error / no API key) — that null is meaningful and
     *  short-circuits another network call until [ts] expires. */
    data class Entry(val ts: Long, val info: HuggingFaceModelInfo?)

    private fun key(providerId: String, modelId: String) = "$providerId::$modelId"

    private fun load(context: Context): MutableMap<String, Entry> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null) ?: return mutableMapOf()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<MutableMap<String, Entry>>() {}.type
            gson.fromJson<MutableMap<String, Entry>>(json, type) ?: mutableMapOf()
        } catch (_: Exception) { mutableMapOf() }
    }

    private fun save(context: Context, entries: Map<String, Entry>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_ENTRIES, gson.toJson(entries)) }
    }

    /** Returns the cached entry when one is present AND fresh. The entry's
     *  [Entry.info] may itself be null — that's a cached miss, distinct
     *  from "no entry at all". */
    fun get(context: Context, providerId: String, modelId: String): Entry? {
        val entry = load(context)[key(providerId, modelId)] ?: return null
        if (System.currentTimeMillis() - entry.ts > TTL_MS) return null
        return entry
    }

    fun put(context: Context, providerId: String, modelId: String, info: HuggingFaceModelInfo?) {
        val entries = load(context)
        entries[key(providerId, modelId)] = Entry(System.currentTimeMillis(), info)
        save(context, entries)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { remove(KEY_ENTRIES) }
    }
}
