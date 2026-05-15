package com.ai.data

import android.content.Context
import com.ai.model.InaccessibleModel
import com.google.gson.reflect.TypeToken

/**
 * Reads `assets/inaccessible.json` on app start and merges any
 * (providerId, model) pair that's missing from the user's
 * [com.ai.model.Settings.inaccessibleModels]. Mirrors
 * [TestExcludedSeed] but for the Inaccessible bucket: lets APK
 * upgrades ship a curated list of "not callable on this account"
 * entries so a clean install doesn't have to rediscover them by
 * burning a sweep slot on every tier-gated catalog model.
 *
 * Existing entries (matched by `provider:model` key) are left
 * strictly alone — reason field on the user's side stays whatever
 * they (or the runtime detection) set it to.
 */
object InaccessibleSeed {

    private data class Entry(
        val providerId: String = "",
        val model: String = "",
        val reason: String = ""
    )

    fun loadFromAssets(context: Context): List<InaccessibleModel> {
        return try {
            val json = context.assets.open("inaccessible.json").bufferedReader().use { it.readText() }
            val gson = createAppGson()
            val type = object : TypeToken<List<Entry>>() {}.type
            val entries: List<Entry> = gson.fromJson(json, type) ?: emptyList()
            entries.mapNotNull {
                if (it.providerId.isBlank() || it.model.isBlank()) null
                else InaccessibleModel(
                    it.providerId, it.model,
                    it.reason.ifBlank { "Unable to access non-serverless (bundled)" }
                )
            }
        } catch (e: Exception) {
            AppLog.w("InaccessibleSeed", "Failed to load inaccessible.json: ${e.message}")
            emptyList()
        }
    }

    /** Append every bundled entry whose `provider:model` key is not
     *  yet in [existing]. Existing rows are untouched. */
    fun ensureAllPresent(
        existing: List<InaccessibleModel>,
        bundled: List<InaccessibleModel>
    ): List<InaccessibleModel> {
        if (bundled.isEmpty()) return existing
        val known = existing.map { it.key }.toSet()
        val toAdd = bundled.filter { it.key !in known }
        return if (toAdd.isEmpty()) existing else existing + toAdd
    }
}
