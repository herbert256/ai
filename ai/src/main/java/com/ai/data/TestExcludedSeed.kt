package com.ai.data

import android.content.Context
import com.ai.model.TestExcludedModel
import com.google.gson.reflect.TypeToken

/**
 * Reads `assets/excluded.json` on app start and merges any
 * (providerId, model) pair that's missing from the user's
 * [com.ai.model.Settings.testExcludedModels]. Lets APK upgrades ship
 * a curated list of "don't probe these in Test all models" entries
 * (models whose probes cost at least 5 cents, known-flaky models, etc.)
 * without forcing the user to re-add them after a clean install.
 *
 * Existing entries (matched by the `provider:model` key) are left
 * strictly alone — never touched, never reordered.
 */
object TestExcludedSeed {

    private data class Entry(
        val providerId: String = "",
        val model: String = ""
    )

    fun loadFromAssets(context: Context): List<TestExcludedModel> {
        return try {
            val json = context.assets.open("excluded.json").bufferedReader().use { it.readText() }
            val gson = createAppGson()
            val type = object : TypeToken<List<Entry>>() {}.type
            val entries: List<Entry> = gson.fromJson(json, type) ?: emptyList()
            entries.mapNotNull {
                if (it.providerId.isBlank() || it.model.isBlank()) null
                else TestExcludedModel(it.providerId, it.model)
            }
        } catch (e: Exception) {
            AppLog.w("TestExcludedSeed", "Failed to load excluded.json: ${e.message}")
            emptyList()
        }
    }

    /** Prefs StringSet of every bundled `provider:model` key already
     *  offered on an earlier launch. Cleared with the configuration. */
    const val OFFERED_PREFS_KEY = "bundled_test_excluded_offered"

    /** Append every bundled entry whose `provider:model` key is neither
     *  in [existing] nor in [alreadyOffered]. An entry offered before and
     *  since removed (a passing retry, a user delete) stays removed — only
     *  keys new to this APK's asset are added. Existing rows are untouched. */
    fun ensureAllPresent(
        existing: List<TestExcludedModel>,
        bundled: List<TestExcludedModel>,
        alreadyOffered: Set<String>
    ): List<TestExcludedModel> {
        if (bundled.isEmpty()) return existing
        val known = existing.map { it.key }.toSet()
        val toAdd = bundled.filter { it.key !in known && it.key !in alreadyOffered }
        return if (toAdd.isEmpty()) existing else existing + toAdd
    }
}
