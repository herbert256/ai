package com.ai.data

import android.content.Context
import com.ai.model.Swarm
import com.google.gson.JsonParser
import java.util.UUID

/**
 * Reads `assets/workers/swarms.json` and merges any bundled Swarm
 * that's missing from the user's set. The asset is
 * `{ "swarms": [ { id, name, members:[{provider,model}], paramsIds } ] }`;
 * member provider strings resolve to [com.ai.data.AppService] through
 * the same [createAppGson] adapter the Import/Export flow uses.
 *
 * Existing entries (matched case-insensitively by name) are left
 * strictly alone. Missing entries are added with a fresh UUID so
 * re-runs are idempotent. Mirrors [SystemPromptSeed] / [FlockSeed].
 */
object SwarmSeed {

    /** Read workers/swarms.json and return every row as a [Swarm] with
     *  a fresh UUID. Empty list on read / parse failure; a single bad
     *  row (e.g. an unknown provider) is skipped, not fatal. */
    fun loadFromAssets(context: Context): List<Swarm> {
        return try {
            val json = context.assets.open("workers/swarms.json").bufferedReader().use { it.readText() }
            val gson = createAppGson()
            val arr = JsonParser.parseString(json).asJsonObject.getAsJsonArray("swarms") ?: return emptyList()
            arr.mapNotNull { el ->
                try {
                    val s = gson.fromJson(el, Swarm::class.java) ?: return@mapNotNull null
                    if (s.name.isBlank()) null else s.copy(id = UUID.randomUUID().toString())
                } catch (e: Exception) {
                    AppLog.w("SwarmSeed", "Skipped swarm entry: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            AppLog.w("SwarmSeed", "Failed to load workers/swarms.json: ${e.message}")
            emptyList()
        }
    }

    /** Append every bundled swarm whose name (case-insensitive) is not
     *  yet present in [existing]. Existing rows are returned unchanged. */
    fun ensureAllPresent(existing: List<Swarm>, bundled: List<Swarm>): List<Swarm> {
        if (bundled.isEmpty()) return existing
        val known = existing.map { it.name.lowercase() }.toSet()
        val toAdd = bundled.filter { it.name.lowercase() !in known }
        return if (toAdd.isEmpty()) existing else existing + toAdd
    }
}
