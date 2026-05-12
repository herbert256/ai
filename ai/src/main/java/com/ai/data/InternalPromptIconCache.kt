package com.ai.data

import android.content.Context
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Per-(name, title) icon cache for [com.ai.model.InternalPrompt]s.
 *
 * Backs the "Use internal prompts icons" feature
 * ([com.ai.viewmodel.GeneralSettings.useInternalPromptsIcons]): each
 * `InternalPrompt` displayed on a successful secondary-result row on
 * the report result page gets a one-emoji glyph generated via the
 * bundled `internal/prompt_icon` prompt and cached here so re-rendering
 * the same row across reports is free.
 *
 * The cached record carries enough provenance to power the
 * full-screen Meta-icon detail screen (cost, resolved prompt, raw
 * API response, which model produced the emoji) AND to accumulate
 * cost across the initial generation plus every alternative-icons
 * candidate the user runs against the same prompt — same pattern
 * as the per-agent 3-tier icon chain.
 *
 * Storage layout: one JSON map under
 * `<filesDir>/internal_prompt_icons.json`. The map's keys are
 * `name + SEP + title` where SEP is the ASCII Unit Separator
 * (U+001F) — a non-printable control character that real prompt names
 * and titles never contain, so the join is unambiguous.
 *
 * Backup/restore: the file lives under `<filesDir>` and is **not** in
 * [BackupManager.FILES_DIR_BACKUP_EXCLUDES], so the standard recursive
 * `addDirectoryRecursive` path on backup captures it and the standard
 * restore path puts it back.
 *
 * Cleared by:
 *  - Housekeeping → Reset → **Clear all configuration**
 *  - Housekeeping → Reset → **Reset application**
 * Not touched by **Clear all runtime data** — this cache is
 * configuration-level (matches the prompts themselves).
 */
object InternalPromptIconCache {
    private const val FILE_NAME = "internal_prompt_icons.json"
    private const val SEP = ""  // ASCII Unit Separator

    /** Persisted per-(name, title) record. Fields:
     *  - [emoji] is the currently displayed glyph.
     *  - [providerId] + [model] identify which `(provider, model)`
     *    pair produced the displayed emoji (initial generation or the
     *    candidate the user picked from the alt-icons flow).
     *  - [promptText] is the resolved prompt text sent to that
     *    model — `internal/prompt_icon` with `@NAME@` and `@TITLE@`
     *    substituted.
     *  - [responseText] is the raw response from that model — what
     *    the Response card on the detail screen renders.
     *  - [inputTokens] / [outputTokens] / [inputCost] / [outputCost]
     *    accumulate across **every** API call for this (name, title):
     *    initial generation plus every alternative-icons candidate.
     *    Mirrors how `ReportAgent.iconInputCost / iconOutputCost`
     *    accumulate across the per-agent 3-tier chain.
     *  - [timestamp] is the most recent update — useful for sorting /
     *    debugging only. */
    data class CacheEntry(
        val emoji: String,
        val providerId: String,
        val model: String,
        val promptText: String,
        val responseText: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val inputCost: Double,
        val outputCost: Double,
        val timestamp: Long
    )

    private val mapType = object : TypeToken<Map<String, CacheEntry>>() {}.type

    private val lock = ReentrantLock()
    @Volatile private var cacheFile: File? = null
    private val map = mutableMapOf<String, CacheEntry>()

    /** Keys whose generation call is currently in flight. Guards
     *  against a second renderer firing a duplicate call for the same
     *  prompt before the first response settles. */
    private val inFlightKeys = mutableSetOf<String>()

    fun init(context: Context) = lock.withLock {
        val file = File(context.filesDir, FILE_NAME).also { cacheFile = it }
        if (!file.exists()) return@withLock
        try {
            val parsed: Map<String, CacheEntry>? =
                createAppGson().fromJson(file.readText(), mapType)
            map.clear()
            // Gson tolerates a missing key from JSON by null-filling
            // the field — bail on entries whose emoji didn't survive
            // the load. The earlier `Map<String, String>` file
            // format parses as null entries here (every field
            // missing); we just drop them per the no-backwards-compat
            // rule — the affected emojis will regenerate on next
            // render.
            parsed?.forEach { (k, v) ->
                @Suppress("SENSELESS_COMPARISON")
                if (v != null && v.emoji.isNotBlank()) map[k] = v
            }
            AppLog.d("InternalPromptIcon", "loaded ${map.size} cached icons")
        } catch (e: Exception) {
            AppLog.w("InternalPromptIcon", "load failed: ${e.message}")
            map.clear()
        }
    }

    fun getEntry(name: String, title: String): CacheEntry? = lock.withLock {
        map[keyOf(name, title)]
    }

    /** Convenience — returns the cached emoji or null. */
    fun get(name: String, title: String): String? = getEntry(name, title)?.emoji

    /** First successful generation for ([name], [title]). Creates a
     *  fresh entry; this call's tokens + cost are the entry's
     *  starting totals. Overwrites an existing entry — the previous
     *  emoji + provenance go away. */
    fun recordInitial(
        name: String, title: String,
        emoji: String, providerId: String, model: String,
        promptText: String, responseText: String,
        inputTokens: Int, outputTokens: Int,
        inputCost: Double, outputCost: Double
    ) {
        if (emoji.isBlank()) return
        val key = keyOf(name, title)
        lock.withLock {
            map[key] = CacheEntry(
                emoji = emoji,
                providerId = providerId, model = model,
                promptText = promptText, responseText = responseText,
                inputTokens = inputTokens, outputTokens = outputTokens,
                inputCost = inputCost, outputCost = outputCost,
                timestamp = System.currentTimeMillis()
            )
            saveLocked()
        }
        AppLog.d(
            "InternalPromptIcon",
            "recordInitial name='$name' -> $emoji via $providerId/$model" +
                " (in=$inputTokens out=$outputTokens cost=${inputCost + outputCost})"
        )
    }

    /** Increment-only cost bump for alternative-icons candidate
     *  calls. The displayed emoji + prompt + response stay untouched
     *  — those are owned by [recordInitial] / [pickAlternative].
     *  Creates an empty-emoji shell entry if the user opens the
     *  alt-icons flow before the initial generation lands. */
    fun bumpCost(
        name: String, title: String,
        inputTokens: Int, outputTokens: Int,
        inputCost: Double, outputCost: Double
    ) {
        if (inputTokens <= 0 && outputTokens <= 0 && inputCost == 0.0 && outputCost == 0.0) return
        val key = keyOf(name, title)
        lock.withLock {
            val existing = map[key]
            map[key] = if (existing == null) {
                CacheEntry(
                    emoji = "", providerId = "", model = "",
                    promptText = "", responseText = "",
                    inputTokens = inputTokens, outputTokens = outputTokens,
                    inputCost = inputCost, outputCost = outputCost,
                    timestamp = System.currentTimeMillis()
                )
            } else {
                existing.copy(
                    inputTokens = existing.inputTokens + inputTokens,
                    outputTokens = existing.outputTokens + outputTokens,
                    inputCost = existing.inputCost + inputCost,
                    outputCost = existing.outputCost + outputCost,
                    timestamp = System.currentTimeMillis()
                )
            }
            saveLocked()
        }
    }

    /** User picked [emoji] from the alt-icons screen for ([name],
     *  [title]). Overwrites the displayed glyph + provenance.
     *  Costs are NOT touched — bumps already happened in
     *  [bumpCost] for the candidate call. */
    fun pickAlternative(
        name: String, title: String,
        emoji: String, providerId: String, model: String,
        promptText: String, responseText: String
    ) {
        if (emoji.isBlank()) return
        val key = keyOf(name, title)
        lock.withLock {
            val existing = map[key]
            map[key] = if (existing == null) {
                CacheEntry(
                    emoji = emoji,
                    providerId = providerId, model = model,
                    promptText = promptText, responseText = responseText,
                    inputTokens = 0, outputTokens = 0,
                    inputCost = 0.0, outputCost = 0.0,
                    timestamp = System.currentTimeMillis()
                )
            } else {
                existing.copy(
                    emoji = emoji,
                    providerId = providerId, model = model,
                    promptText = promptText, responseText = responseText,
                    timestamp = System.currentTimeMillis()
                )
            }
            saveLocked()
        }
        AppLog.d(
            "InternalPromptIcon",
            "pickAlternative name='$name' -> $emoji via $providerId/$model"
        )
    }

    /** True if the call for ([name], [title]) is already running.
     *  Atomically marks the key as in-flight when it isn't yet — call
     *  sites do `if (!markInFlight(...)) return` to dedupe. */
    fun markInFlight(name: String, title: String): Boolean = lock.withLock {
        val key = keyOf(name, title)
        if (key in inFlightKeys) false else { inFlightKeys += key; true }
    }

    fun clearInFlight(name: String, title: String) = lock.withLock {
        inFlightKeys.remove(keyOf(name, title))
    }

    fun clearAll(context: Context): Int = lock.withLock {
        val n = map.size
        map.clear()
        inFlightKeys.clear()
        val file = cacheFile ?: File(context.filesDir, FILE_NAME).also { cacheFile = it }
        try { if (file.exists()) file.delete() } catch (_: Exception) {}
        AppLog.i("InternalPromptIcon", "clearAll dropped $n cached icons")
        n
    }

    private fun keyOf(name: String, title: String): String = name + SEP + title

    private fun saveLocked() {
        val file = cacheFile ?: return
        try {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            file.writeTextAtomic(createAppGson().toJson(map))
        } catch (e: Exception) {
            AppLog.w("InternalPromptIcon", "save failed: ${e.message}")
        }
    }
}
