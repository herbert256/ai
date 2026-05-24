package com.ai.data

import android.content.Context
import com.ai.model.DefaultMetaItem
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Reads `assets/meta.json` and merges any bundled "default meta item"
 * that's missing from the user's set. Each bundled row is a
 * (metaName, agentName, providerName, modelName) tuple; it maps to
 * [com.ai.model.DefaultMetaItem] with a fresh UUID.
 *
 * Existing entries (matched by [DefaultMetaItem.seedKey] — the
 * case-insensitive tuple) are left strictly alone. Missing entries are
 * appended, so re-runs are idempotent. Mirrors [SystemPromptSeed] /
 * [InaccessibleSeed].
 */
object DefaultMetaItemSeed {

    /** DTO mirroring one row in `assets/meta.json`. */
    private data class Entry(
        val metaName: String = "",
        val agentName: String = "",
        val providerName: String = "",
        val modelName: String = ""
    )

    /** Read meta.json and return every row as a [DefaultMetaItem] with a
     *  fresh UUID. Rows with a blank metaName are dropped. Empty list on
     *  read or parse failure. */
    fun loadFromAssets(context: Context): List<DefaultMetaItem> {
        return try {
            val json = context.assets.open("meta.json").bufferedReader().use { it.readText() }
            val gson = createAppGson()
            val type = object : TypeToken<List<Entry>>() {}.type
            val entries: List<Entry> = gson.fromJson(json, type) ?: emptyList()
            entries.mapNotNull {
                if (it.metaName.isBlank()) null
                else DefaultMetaItem(
                    id = UUID.randomUUID().toString(),
                    metaName = it.metaName.trim(),
                    agentName = it.agentName.trim(),
                    providerName = it.providerName.trim(),
                    modelName = it.modelName.trim()
                )
            }
        } catch (e: Exception) {
            AppLog.w("DefaultMetaItemSeed", "Failed to load meta.json: ${e.message}")
            emptyList()
        }
    }

    /** Append every bundled item whose [DefaultMetaItem.seedKey] is not
     *  yet present in [existing]. Existing rows are returned unchanged. */
    fun ensureAllPresent(
        existing: List<DefaultMetaItem>,
        bundled: List<DefaultMetaItem>
    ): List<DefaultMetaItem> {
        if (bundled.isEmpty()) return existing
        val known = existing.map { it.seedKey }.toSet()
        val toAdd = bundled.filter { it.seedKey !in known }
        return if (toAdd.isEmpty()) existing else existing + toAdd
    }
}
