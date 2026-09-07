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
 * Existing entries (matched case-insensitively by name) retain custom
 * selections; unchanged older bundled workers pools track the current seed.
 * Missing entries are added with a fresh UUID so
 * re-runs are idempotent. Mirrors [SystemPromptSeed] / [FlockSeed].
 */
object SwarmSeed {

    /** Root assets folder — one `<name>.json` per swarm. */
    private const val DIR = "workers/swarms"

    // Explicit historical membership: deriving this from the new seed loses
    // the match whenever that seed adds/removes providers or changes models.
    private val previousWorkers = mapOf(
        "Mistral" to "mistral-medium-latest",
        "OpenAI" to "gpt-4o-mini",
        "Groq" to "openai/gpt-oss-20b",
        "Cerebras" to "gpt-oss-120b",
        "DeepSeek" to "deepseek-v4-flash",
        "Google" to "gemini-3.5-flash",
        "Anthropic" to "claude-haiku-4-5-20251001",
        "xAI" to "grok-4.20-0309-non-reasoning",
        "Cohere" to "command-r-08-2024",
        "DeepInfra" to "google/gemma-3-12b-it",
        "Together" to "openai/gpt-oss-20b",
        "SiliconFlow" to "Qwen/Qwen3-14B"
    )
    private val originalWorkers = previousWorkers + mapOf(
        "Groq" to "llama-3.3-70b-versatile",
        "Together" to "Qwen/Qwen3-235B-A22B-Instruct-2507-tput"
    )

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

    /** Append missing swarms and migrate exact historical workers membership.
     *  Preserve IDs, custom membership, parameters and system prompts. */
    fun ensureAllPresent(existing: List<Swarm>, bundled: List<Swarm>): List<Swarm> {
        if (bundled.isEmpty()) return existing
        val known = existing.map { it.name.lowercase() }.toSet()
        val toAdd = bundled.filter { it.name.lowercase() !in known }
        val repaired = existing.map { swarm ->
            // Only migrate the unchanged bundled pool, identified by all of
            // its original members. Custom pools and edited selections remain
            // intact; UUIDs and references to this swarm are preserved.
            val replacement = bundled.firstOrNull { it.name.equals("workers", true) }
            if (!swarm.name.equals("workers", true) || replacement == null) return@map swarm
            val membership = swarm.members.map { it.provider.id to it.model }.toSet()
            val isUnchanged = swarm.members.size == previousWorkers.size &&
                (membership == previousWorkers.toList().toSet() || membership == originalWorkers.toList().toSet())
            if (isUnchanged) swarm.copy(members = replacement.members) else swarm
        }
        return if (toAdd.isEmpty() && repaired == existing) existing else repaired + toAdd
    }
}
