package com.ai.data

import android.content.Context
import com.ai.model.SystemPrompt
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Reads `assets/system-prompts.json` and merges any bundled System
 * prompt that's missing from the user's set. Bundled rows are pure
 * (title, text) pairs; they map to [com.ai.model.SystemPrompt] as
 * (name = title, prompt = text).
 *
 * Existing entries (matched case-insensitively by name) are left
 * strictly alone — no field overwrites, even if the bundled defaults
 * drift. Missing entries are added with a fresh UUID so re-runs are
 * idempotent. Mirrors [ExamplePromptSeed].
 */
object SystemPromptSeed {

    /** DTO mirroring one row in `assets/system-prompts.json`. */
    private data class Entry(
        val title: String = "",
        val text: String = ""
    )

    /** Read system-prompts.json and return every row as a [SystemPrompt]
     *  with a fresh UUID. Empty list on read or parse failure. */
    fun loadFromAssets(context: Context): List<SystemPrompt> {
        return try {
            val json = context.assets.open("system-prompts.json").bufferedReader().use { it.readText() }
            val gson = createAppGson()
            val type = object : TypeToken<List<Entry>>() {}.type
            val entries: List<Entry> = gson.fromJson(json, type) ?: emptyList()
            entries.mapNotNull {
                if (it.title.isBlank()) null
                else SystemPrompt(id = UUID.randomUUID().toString(), name = it.title.trim(), prompt = it.text)
            }
        } catch (e: Exception) {
            AppLog.w("SystemPromptSeed", "Failed to load system-prompts.json: ${e.message}")
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
