package com.ai.data

import android.content.Context
import com.ai.model.MetaPrompt
import com.ai.ui.settings.SettingsPreferences
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * One-shot seeder for [Settings.metaPrompts] from `assets/prompts.json`.
 * Mirrors [ProviderRegistry.loadFromAssets] in spirit: read the bundled
 * JSON once, write the user-managed list, set a SharedPreferences flag,
 * never run again. Subsequent launches treat the list as user data —
 * deleting an entry stays deleted.
 *
 * The JSON schema is shared with the (future) Internal-prompt seed; we
 * only consume entries with `category == "meta"`. The remaining
 * categories are ignored here.
 */
object MetaPromptSeed {

    /** DTO matching one row in `assets/prompts.json`. */
    private data class Entry(
        val name: String = "",
        val type: String = "chat",
        val reference: Boolean = false,
        val category: String = "",
        val text: String = ""
    )

    /** Read prompts.json and return the meta-category entries as
     *  [MetaPrompt]s with fresh UUIDs. Empty list on any read or parse
     *  failure — failures are logged but never propagate so a malformed
     *  asset can't block app start. */
    fun loadSeedFromAssets(context: Context): List<MetaPrompt> {
        return try {
            val json = context.assets.open("prompts.json").bufferedReader().use { it.readText() }
            val gson = createAppGson()
            val type = object : TypeToken<List<Entry>>() {}.type
            val entries: List<Entry> = gson.fromJson(json, type) ?: emptyList()
            entries
                .filter { it.category.equals("meta", ignoreCase = true) }
                .map {
                    MetaPrompt(
                        id = UUID.randomUUID().toString(),
                        name = it.name,
                        type = it.type.ifBlank { "chat" },
                        reference = it.reference,
                        text = it.text
                    )
                }
        } catch (e: Exception) {
            android.util.Log.w("MetaPromptSeed", "Failed to load prompts.json: ${e.message}")
            emptyList()
        }
    }

    /** True when the seeder has already run on this install (flag held
     *  in `eval_prefs.xml`). */
    fun isSeeded(context: Context): Boolean {
        val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(SettingsPreferences.KEY_AI_META_PROMPTS_SEEDED, false)
    }

    /** Stamp the seed-completed flag — writes synchronously so a crash
     *  immediately after seeding doesn't double-seed on next start. */
    fun markSeeded(context: Context) {
        val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(SettingsPreferences.KEY_AI_META_PROMPTS_SEEDED, true).apply()
    }
}
