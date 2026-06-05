package com.ai.data

import android.content.Context
import com.ai.model.Swarm
import java.util.UUID

/**
 * Reads `assets/workers/swarms/` — one JSON file per swarm — and merges
 * any bundled Swarm that's missing from the user's set. Each file is a
 * single `{ id, name, members:[{provider,model}], paramsIds }` object
 * (the filename is cosmetic; the in-file `name` is authoritative);
 * member provider strings resolve to [com.ai.data.AppService] through
 * the same [createAppGson] adapter the Import/Export flow uses.
 *
 * Existing entries (matched case-insensitively by name) are left
 * strictly alone. Missing entries are added with a fresh UUID so
 * re-runs are idempotent. Mirrors [SystemPromptSeed] / [FlockSeed].
 */
object SwarmSeed {

    /** Root assets folder — one `<name>.json` per swarm. */
    private const val DIR = "workers/swarms"

    /** Read every JSON file under `workers/swarms/` and return each as a
     *  [Swarm] with a fresh UUID, sorted by filename for a deterministic
     *  merge.
     *  Empty list on read / parse failure; a single bad file (e.g. an
     *  unknown provider) is skipped, not fatal. */
    fun loadFromAssets(context: Context): List<Swarm> {
        return try {
            val gson = createAppGson()
            val files = context.assets.list(DIR) ?: return emptyList()
            files.filter { it.endsWith(".json") }.sorted().mapNotNull { file ->
                try {
                    val json = context.assets.open("$DIR/$file").bufferedReader().use { it.readText() }
                    val s = gson.fromJson(json, Swarm::class.java) ?: return@mapNotNull null
                    if (s.name.isBlank()) null else s.copy(id = UUID.randomUUID().toString())
                } catch (e: Exception) {
                    AppLog.w("SwarmSeed", "Skipped swarm file $file: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            AppLog.w("SwarmSeed", "Failed to load $DIR/: ${e.message}")
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
