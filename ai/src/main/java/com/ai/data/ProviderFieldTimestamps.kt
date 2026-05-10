package com.ai.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Per-provider, per-field "user-touched-at" timestamps.
 *
 * Field name == AppService property name (e.g. "baseUrl",
 * "modelFilter"). Timestamps get set by [ProviderRegistry.update] when
 * the new value differs from the existing one — the user just edited
 * that field via the Settings UI. Asset-driven paths
 * ([ProviderRegistry.importFromAsset], [ProviderRegistry.upsertFromJson],
 * [ProviderRegistry.syncFromAsset]) don't bump.
 *
 * The startup sync uses these to decide which fields to refresh from
 * `assets/providers.json`:
 *   - timestamp == null → field was never user-touched, refresh
 *   - timestamp != null → user edited this field, leave alone
 *
 * Persisted in its own SharedPreferences entry as JSON
 * (`{ "OpenAI": { "baseUrl": 1715… }, … }`) so the AppService
 * serialization shape stays untouched.
 */
object ProviderFieldTimestamps {
    private const val PREFS_NAME = "provider_field_timestamps"
    private const val KEY_JSON = "ts"
    private val ts = mutableMapOf<String, MutableMap<String, Long>>()
    private var prefs: SharedPreferences? = null
    private val mapType = object : TypeToken<Map<String, Map<String, Long>>>() {}.type
    private val lock = Any()

    fun init(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs?.getString(KEY_JSON, null) ?: return
            try {
                val parsed: Map<String, Map<String, Long>>? = Gson().fromJson(json, mapType)
                ts.clear()
                parsed?.forEach { (id, m) -> ts[id] = m.toMutableMap() }
            } catch (e: Exception) {
                android.util.Log.w("ProviderFieldTimestamps", "load failed: ${e.message}")
            }
        }
    }

    /** Timestamp recorded when the user last edited [field] for
     *  [providerId], or null if untouched. */
    fun get(providerId: String, field: String): Long? = synchronized(lock) {
        ts[providerId]?.get(field)
    }

    /** Set [fields] for [providerId] to [now]. */
    fun bump(providerId: String, fields: Collection<String>, now: Long = System.currentTimeMillis()) {
        if (fields.isEmpty()) return
        synchronized(lock) {
            val pmap = ts.getOrPut(providerId) { mutableMapOf() }
            fields.forEach { pmap[it] = now }
            saveLocked()
        }
    }

    /** Drop every recorded timestamp — Restart from asset. */
    fun clearAll() = synchronized(lock) {
        if (ts.isEmpty()) return
        ts.clear()
        saveLocked()
    }

    /** Drop timestamps for one provider — used when removing it. */
    fun clear(providerId: String) = synchronized(lock) {
        if (ts.remove(providerId) != null) saveLocked()
    }

    private fun saveLocked() {
        prefs?.edit { putString(KEY_JSON, Gson().toJson(ts)) }
    }
}
