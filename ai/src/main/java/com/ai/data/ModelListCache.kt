package com.ai.data

import android.content.Context
import java.io.File

/**
 * On-disk cache for the latest /models response per provider. Each
 * successful fetch writes the raw response body to
 * <filesDir>/model_lists/<providerId>.json — preserving the wire
 * format the provider returned, with the file's mtime acting as the
 * fetch timestamp.
 *
 * The cache is intentionally minimal: it stores the raw JSON, no
 * parsing, no extraction. Future code paths (pricing inference,
 * capability detection, debug inspection) read from here rather than
 * re-hitting the provider, and a shared single source of truth keeps
 * those paths consistent. SharedPreferences also keeps a copy under
 * `${prefsKey}_models_response_raw` for the in-memory snapshot path
 * that drives the model picker — the file cache is the authoritative
 * disk record for cross-session use.
 */
object ModelListCache {
    private const val DIR = "model_lists"
    private const val EXT = "json"

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).also { if (!it.exists()) it.mkdirs() }

    private fun fileFor(context: Context, providerId: String): File =
        File(dir(context), "$providerId.$EXT")

    /** Persist [rawResponse] for [providerId]. No-op when the body is
     *  null or blank — fetches that produced no body shouldn't
     *  clobber a previous successful snapshot. The disk write happens
     *  on the calling thread; callers are already on Dispatchers.IO
     *  via the fetch path. */
    fun save(context: Context, providerId: String, rawResponse: String?) {
        if (rawResponse.isNullOrBlank()) return
        try {
            fileFor(context, providerId).writeText(rawResponse)
        } catch (e: Exception) {
            android.util.Log.w("ModelListCache", "save($providerId) failed: ${e.message}")
        }
    }

    /** Convenience overload accepting an [AppService]. */
    fun save(context: Context, service: AppService, rawResponse: String?) =
        save(context, service.id, rawResponse)

    /** Read the cached raw body for [providerId] or null when no
     *  fetch has been recorded. Returned text is the provider's
     *  original response — JSON for every wired provider today. */
    fun read(context: Context, providerId: String): String? = try {
        val f = fileFor(context, providerId)
        if (f.exists()) f.readText() else null
    } catch (e: Exception) {
        android.util.Log.w("ModelListCache", "read($providerId) failed: ${e.message}")
        null
    }

    fun read(context: Context, service: AppService): String? = read(context, service.id)

    /** Last-modified timestamp (epoch ms) for [providerId]'s cached
     *  body, or null when the file doesn't exist. */
    fun fetchedAt(context: Context, providerId: String): Long? {
        val f = fileFor(context, providerId)
        return if (f.exists()) f.lastModified() else null
    }

    fun fetchedAt(context: Context, service: AppService): Long? = fetchedAt(context, service.id)

    /** Drop the cached body for [providerId]. Safe to call when no
     *  cache file exists. */
    fun delete(context: Context, providerId: String) {
        try { fileFor(context, providerId).delete() } catch (_: Exception) {}
    }

    /** Wipe every cached body — used by the housekeeping "clear all"
     *  flow. */
    fun clearAll(context: Context) {
        try { dir(context).listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
    }
}
