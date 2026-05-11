package com.ai.data

import android.content.Context
import com.ai.model.ExamplePrompt
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Reads `assets/examples.json` on demand and merges any starter
 * Example Prompt that's missing from the user's set. Example prompts
 * are pure (title, text) pairs with no category / agent semantics —
 * see [com.ai.model.ExamplePrompt] vs [com.ai.model.InternalPrompt].
 *
 * Existing entries (matched case-insensitively by title) are left
 * strictly alone — no field overwrites, even if the bundled defaults
 * drift. Missing entries are added with a fresh UUID so re-runs are
 * idempotent.
 */
object ExamplePromptSeed {

    /** DTO mirroring one row in `assets/examples.json`. */
    private data class Entry(
        val title: String = "",
        val text: String = ""
    )

    /** Read examples.json and return every row as an [ExamplePrompt]
     *  with a fresh UUID. Empty list on read or parse failure. */
    fun loadFromAssets(context: Context): List<ExamplePrompt> {
        return try {
            val json = context.assets.open("examples.json").bufferedReader().use { it.readText() }
            val gson = createAppGson()
            val type = object : TypeToken<List<Entry>>() {}.type
            val entries: List<Entry> = gson.fromJson(json, type) ?: emptyList()
            entries.mapNotNull {
                if (it.title.isBlank()) null
                else ExamplePrompt(id = UUID.randomUUID().toString(), title = it.title, text = it.text)
            }
        } catch (e: Exception) {
            AppLog.w("ExamplePromptSeed", "Failed to load examples.json: ${e.message}")
            emptyList()
        }
    }

    /** Append every bundled prompt whose title (case-insensitive) is
     *  not yet present in [existing]. Existing rows are returned
     *  unchanged. */
    fun ensureAllPresent(
        existing: List<ExamplePrompt>,
        bundled: List<ExamplePrompt>
    ): List<ExamplePrompt> {
        if (bundled.isEmpty()) return existing
        val known = existing.map { it.title.lowercase() }.toSet()
        val toAdd = bundled.filter { it.title.lowercase() !in known }
        return if (toAdd.isEmpty()) existing else existing + toAdd
    }

    /** Upsert by case-insensitive title from a JSON blob shaped like
     *  `assets/examples.json` (a top-level array of {title, text}
     *  objects). Existing rows keep their UUID and only get their
     *  text overwritten; missing titles are appended with a fresh
     *  UUID. Returns the new list and the count of (added + updated)
     *  rows, or null on parse failure. */
    fun upsertFromJson(
        json: String,
        existing: List<ExamplePrompt>
    ): Pair<List<ExamplePrompt>, Int>? {
        return try {
            val gson = createAppGson()
            val type = object : TypeToken<List<Entry>>() {}.type
            val entries: List<Entry> = gson.fromJson(json, type) ?: return null
            val result = existing.toMutableList()
            var changed = 0
            for (e in entries) {
                if (e.title.isBlank()) continue
                val i = result.indexOfFirst { it.title.equals(e.title, ignoreCase = true) }
                if (i >= 0) {
                    result[i] = result[i].copy(title = e.title, text = e.text)
                } else {
                    result.add(ExamplePrompt(id = UUID.randomUUID().toString(), title = e.title, text = e.text))
                }
                changed++
            }
            result to changed
        } catch (e: Exception) {
            AppLog.w("ExamplePromptSeed", "upsertFromJson failed: ${e.message}")
            null
        }
    }
}
