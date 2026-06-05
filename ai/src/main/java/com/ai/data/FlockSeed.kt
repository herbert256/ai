package com.ai.data

import android.content.Context
import com.ai.model.Agent
import com.ai.model.Flock
import com.google.gson.JsonParser
import java.util.UUID

/**
 * Reads `assets/workers/flocks/` — one JSON file per flock — and merges
 * any bundled Flock that's missing from the user's set. A flock's
 * members are stored by agent NAME (`agentNames`) so the bundled file is
 * portable; they're resolved to the install-local agent ids here against
 * the current agent set (a legacy `agentIds` array is still accepted).
 * Each file is a single `{ id, name, agentNames, paramsIds }` object (the
 * filename is cosmetic; the in-file `name` is authoritative). Mirrors how
 * the Import/Export flow re-links imported flocks.
 *
 * Existing entries (matched case-insensitively by name) are left
 * strictly alone. Missing entries are added with a fresh UUID so
 * re-runs are idempotent. Mirrors [SwarmSeed] / [SystemPromptSeed].
 */
object FlockSeed {

    /** Root assets folder — one `<name>.json` per flock. */
    private const val DIR = "workers/flocks"

    /** Read every JSON file under `workers/flocks/`, resolving each
     *  flock's member agents by name against [agents], sorted by filename
     *  for a deterministic merge. Empty list on read / parse failure; a
     *  single bad file is skipped. */
    fun loadFromAssets(context: Context, agents: List<Agent>): List<Flock> {
        return try {
            val idByName = agents.associate { it.name.lowercase() to it.id }
            val files = context.assets.list(DIR) ?: return emptyList()
            files.filter { it.endsWith(".json") }.sorted().mapNotNull { file ->
                try {
                    val json = context.assets.open("$DIR/$file").bufferedReader().use { it.readText() }
                    val o = JsonParser.parseString(json).asJsonObject
                    val name = o.get("name")?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    // Filter JsonNull defensively — a hand-edited asset with a
                    // trailing comma yields a null array element that Gson's
                    // lenient parser tolerates; .asString on it would throw.
                    val agentIds = when {
                        o.has("agentNames") -> o.getAsJsonArray("agentNames")
                            .filterNot { it.isJsonNull }.mapNotNull { idByName[it.asString.lowercase()] }
                        o.has("agentIds") -> o.getAsJsonArray("agentIds")
                            .filterNot { it.isJsonNull }.map { it.asString }
                        else -> emptyList()
                    }
                    val paramsIds = o.getAsJsonArray("paramsIds")?.map { it.asString } ?: emptyList()
                    val systemPromptId = o.get("systemPromptId")?.asString
                    Flock(id = UUID.randomUUID().toString(), name = name.trim(), agentIds = agentIds,
                        paramsIds = paramsIds, systemPromptId = systemPromptId)
                } catch (e: Exception) {
                    AppLog.w("FlockSeed", "Skipped flock file $file: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            AppLog.w("FlockSeed", "Failed to load $DIR/: ${e.message}")
            emptyList()
        }
    }

    /** Append every bundled flock whose name (case-insensitive) is not
     *  yet present in [existing]. Existing rows are returned unchanged. */
    fun ensureAllPresent(existing: List<Flock>, bundled: List<Flock>): List<Flock> {
        if (bundled.isEmpty()) return existing
        val known = existing.map { it.name.lowercase() }.toSet()
        val toAdd = bundled.filter { it.name.lowercase() !in known }
        return if (toAdd.isEmpty()) existing else existing + toAdd
    }
}
