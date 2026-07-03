package com.ai.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Tiny persisted cache of the LAST generated alternative-icon candidates
 * per target (F68). The live fan-out candidates used to exist only in
 * in-memory StateFlows cleared on back-out — re-picking a previously-seen
 * glyph after committing one meant re-running (and re-paying for) the
 * whole fan-out. One JSON map in `<filesDir>/icon_candidates.json`:
 * scope key → [(providerId, model, emoji)]. Overwritten per fan-out; no
 * costs are stored (a re-pick from here is free by definition).
 */
object IconCandidateStore {
    private const val FILE_NAME = "icon_candidates.json"
    private val gson = Gson()
    private val lock = Any()

    data class Entry(val providerId: String, val model: String, val emoji: String)

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun loadAll(context: Context): MutableMap<String, List<Entry>> = synchronized(lock) {
        val f = file(context)
        if (!f.exists()) return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, List<Entry>>>() {}.type
            gson.fromJson<MutableMap<String, List<Entry>>>(f.readText(), type) ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    /** Replace the stored candidate list for [key]. No-op on empty. */
    fun save(context: Context, key: String, entries: List<Entry>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            val all = loadAll(context)
            all[key] = entries
            // Soft cap so the file can't grow unboundedly across many
            // reports — oldest insertion order is dropped first.
            while (all.size > 200) all.remove(all.keys.first())
            file(context).writeText(gson.toJson(all))
        }
    }

    fun load(context: Context, key: String): List<Entry> = loadAll(context)[key].orEmpty()
}
