package com.ai.data

import android.content.Context
import androidx.core.content.edit

/**
 * Per-model cache for the lazy CloudPrice detail lookup on Model Info —
 * the direct sibling of [HuggingFaceCache].
 *
 * CloudPrice's bulk `/models` list feeds the capability tier, but Model Info
 * also does a live per-model call to `/api/v1/models/{id}` (richer / fresher,
 * resolves aliases the bulk keying misses). A 404 is common — many
 * provider/model pairs aren't in CloudPrice's catalog — so this cache:
 *
 *   • Holds the raw detail JSON for [TTL_MS] (7 days) keyed by
 *     `${providerId}::${modelId}`.
 *   • Stores `null` for misses so a model with no CloudPrice entry doesn't
 *     re-hit the API (and re-record a 404 trace) on every screen open.
 *   • Persists to SharedPreferences "cloudprice_model_cache" (one JSON-blob
 *     key) so a process restart doesn't re-fetch.
 *   • Round-trips via [BackupManager] (its prefs file is in PREFS_TO_BACKUP).
 */
object CloudPriceModelCache {
    private const val PREFS_NAME = "cloudprice_model_cache"
    private const val KEY_ENTRIES = "entries_json"
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    private val gson = createAppGson()
    // Serialise put / clear so two parallel Model Info opens for different
    // (provider, model) pairs can't race a load-modify-save cycle.
    private val lock = Any()

    /** One stored result. [json] is null when the previous lookup found
     *  nothing (404 / error) — that null is meaningful and short-circuits
     *  another network call until [ts] expires. */
    data class Entry(val ts: Long, val json: String?)

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
     *  [Entry.json] may itself be null — that's a cached miss, distinct from
     *  "no entry at all". */
    fun get(context: Context, providerId: String, modelId: String): Entry? {
        val entry = load(context)[key(providerId, modelId)] ?: return null
        if (System.currentTimeMillis() - entry.ts > TTL_MS) return null
        return entry
    }

    fun put(context: Context, providerId: String, modelId: String, json: String?) {
        synchronized(lock) {
            val entries = load(context)
            entries[key(providerId, modelId)] = Entry(System.currentTimeMillis(), json)
            save(context, entries)
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { remove(KEY_ENTRIES) }
        }
    }
}
