package com.ai.data

import android.content.Context
import com.ai.model.SystemPrompt
import java.util.UUID

/**
 * Reads `assets/prompts/system/` — one JSON file per system prompt —
 * and merges any bundled System prompt that's missing from the user's
 * set. Bundled files are pure (title, text) pairs; they map to
 * [com.ai.model.SystemPrompt] as (name = title, prompt = text).
 *
 * Existing entries (matched case-insensitively by name) are left
 * strictly alone — no field overwrites, even if the bundled defaults
 * drift. Missing entries are added with a fresh UUID so re-runs are
 * idempotent. Mirrors [ExamplePromptSeed].
 */
object SystemPromptSeed {

    /** Root assets folder — one `{title, text}` file per system prompt. */
    private const val DIR = "prompts/system"

    /** DTO mirroring one file under `assets/prompts/system/`. */
    private data class Entry(
        val title: String = "",
        val text: String = ""
    )

    /** Read every JSON file under `prompts/system/` and return each as a
     *  [SystemPrompt] with a fresh UUID, sorted by filename. Empty list
     *  on read or parse failure; a single bad file is skipped. */
    fun loadFromAssets(context: Context): List<SystemPrompt> {
        return try {
            val gson = createAppGson()
            val files = context.assets.list(DIR) ?: return emptyList()
            files.filter { it.endsWith(".json") }.sorted().mapNotNull { file ->
                try {
                    val json = context.assets.open("$DIR/$file").bufferedReader().use { it.readText() }
                    val e = gson.fromJson(json, Entry::class.java) ?: return@mapNotNull null
                    if (e.title.isBlank()) null
                    else SystemPrompt(id = UUID.randomUUID().toString(), name = e.title.trim(), prompt = e.text)
                } catch (ex: Exception) {
                    AppLog.w("SystemPromptSeed", "Skipped system-prompt file $file: ${ex.message}")
                    null
                }
            }
        } catch (e: Exception) {
            AppLog.w("SystemPromptSeed", "Failed to load $DIR/: ${e.message}")
            emptyList()
        }
    }

    /** Append every bundled prompt whose name (case-insensitive) is not
     *  yet present in [existing]. Existing rows are returned unchanged. */
    fun ensureAllPresent(
        existing: List<SystemPrompt>,
        bundled: List<SystemPrompt>
    ): List<SystemPrompt> {
        if (bundled.isEmpty()) return existing
        val known = existing.map { it.name.lowercase() }.toSet()
        val toAdd = bundled.filter { it.name.lowercase() !in known }
        return if (toAdd.isEmpty()) existing else existing + toAdd
    }
}
