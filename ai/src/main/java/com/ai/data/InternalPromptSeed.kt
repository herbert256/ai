package com.ai.data

import android.content.Context
import com.ai.model.InternalPrompt
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Reads `assets/prompts.json` on every app startup and ensures every
 * row by `name` is present in [com.ai.model.Settings.internalPrompts].
 * Existing entries are left strictly alone — no field overwrites, even
 * if the bundled defaults drift. Missing entries are added with a
 * fresh UUID so re-runs are idempotent.
 *
 * The bundled JSON is also the only source of the "default" template
 * text — the previous in-code constants (DEFAULT_RERANK / etc.) are
 * gone. Edit prompts.json to change a default for fresh installs.
 */
object InternalPromptSeed {

    /** DTO mirroring one row in `assets/prompts.json`. */
    private data class Entry(
        val name: String = "",
        val title: String = "",
        val type: String = "chat",
        val reference: Boolean = false,
        val category: String = "internal",
        val agent: String = "*select",
        val text: String = ""
    )

    /** Read prompts.json and return every row as an [InternalPrompt]
     *  with a fresh UUID. Empty list on read or parse failure. */
    fun loadFromAssets(context: Context): List<InternalPrompt> {
        return try {
            val json = context.assets.open("prompts.json").bufferedReader().use { it.readText() }
            val gson = createAppGson()
            val type = object : TypeToken<List<Entry>>() {}.type
            val entries: List<Entry> = gson.fromJson(json, type) ?: emptyList()
            entries.map {
                InternalPrompt(
                    id = UUID.randomUUID().toString(),
                    name = it.name,
                    type = it.type.ifBlank { "chat" },
                    reference = it.reference,
                    category = it.category.ifBlank { "internal" },
                    agent = it.agent.ifBlank { "*select" },
                    text = it.text,
                    title = it.title
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("InternalPromptSeed", "Failed to load prompts.json: ${e.message}")
            emptyList()
        }
    }

    /** Append every bundled prompt whose name (case-insensitive) is
     *  not yet present in [existing]. Existing rows are returned
     *  unchanged. */
    fun ensureAllPresent(
        existing: List<InternalPrompt>,
        bundled: List<InternalPrompt>
    ): List<InternalPrompt> {
        if (bundled.isEmpty()) return existing
        val knownNames = existing.map { it.name.lowercase() }.toSet()
        val toAdd = bundled.filter { it.name.lowercase() !in knownNames }
        return if (toAdd.isEmpty()) existing else existing + toAdd
    }

    /** Upsert by case-insensitive name from a JSON blob shaped like
     *  `assets/prompts.json` (a top-level array of Entry-shaped
     *  objects). Existing rows keep their UUID and only get their
     *  non-name fields overwritten; missing names are appended with a
     *  fresh UUID. Returns the new list and the count of (added +
     *  updated) rows, or null on parse failure. */
    fun upsertFromJson(
        json: String,
        existing: List<InternalPrompt>
    ): Pair<List<InternalPrompt>, Int>? {
        return try {
            val gson = createAppGson()
            val type = object : TypeToken<List<Entry>>() {}.type
            val entries: List<Entry> = gson.fromJson(json, type) ?: return null
            val result = existing.toMutableList()
            var changed = 0
            for (e in entries) {
                if (e.name.isBlank()) continue
                val i = result.indexOfFirst { it.name.equals(e.name, ignoreCase = true) }
                if (i >= 0) {
                    result[i] = result[i].copy(
                        type = e.type.ifBlank { "chat" },
                        reference = e.reference,
                        category = e.category.ifBlank { "internal" },
                        agent = e.agent.ifBlank { "*select" },
                        text = e.text,
                        title = e.title
                    )
                } else {
                    result.add(
                        InternalPrompt(
                            id = UUID.randomUUID().toString(),
                            name = e.name,
                            type = e.type.ifBlank { "chat" },
                            reference = e.reference,
                            category = e.category.ifBlank { "internal" },
                            agent = e.agent.ifBlank { "*select" },
                            text = e.text,
                            title = e.title
                        )
                    )
                }
                changed++
            }
            result to changed
        } catch (e: Exception) {
            android.util.Log.w("InternalPromptSeed", "upsertFromJson failed: ${e.message}")
            null
        }
    }
}
