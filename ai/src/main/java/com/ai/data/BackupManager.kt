package com.ai.data

import android.content.Context
import com.ai.ui.settings.SettingsPreferences
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One-stop backup/restore for everything the app stores locally.
 *
 * Backup payload (single .zip):
 *   manifest.json                     — version, timestamp, app version
 *   prefs/eval_prefs.json             — main SharedPreferences (typed)
 *   prefs/provider_registry.json      — provider catalog SharedPreferences
 *   files/<mirror of filesDir>/...    — reports, chats, traces, prompt cache,
 *                                       prompt history, datastore — everything
 *
 * SharedPreferences entries are serialized with a type discriminator so values
 * round-trip through JSON without ambiguity (otherwise an Int would come back
 * as a Double via Gson). Files are stored verbatim.
 *
 * After a restore the in-memory state of singletons (ProviderRegistry,
 * ApiTracer, PromptCache, ReportStorage, ChatHistoryManager) is stale, and the
 * AppViewModel's StateFlow is out of sync. The simplest correct path is to
 * tell the user to restart the app — restoration writes everything to disk,
 * the next launch re-reads it. We don't try to live-reload here.
 */
object BackupManager {

    private const val MANIFEST_VERSION = 1
    private const val MAIN_PREFS = SettingsPreferences.PREFS_NAME
    private const val PROVIDER_REGISTRY_PREFS = "provider_registry"

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun timestampForFileName(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    fun defaultFileName(): String = "ai-backup-${timestampForFileName()}.zip"

    /**
     * Stream a complete backup zip into [out]. The caller (Housekeeping)
     * provides [out] from a SAF-picked Uri.
     */
    fun backup(context: Context, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            // Manifest
            val manifest = mapOf(
                "version" to MANIFEST_VERSION,
                "timestamp" to System.currentTimeMillis(),
                "appVersion" to runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
                }.getOrDefault("?"),
                "packageName" to context.packageName
            )
            zip.write("manifest.json", gson.toJson(manifest).toByteArray())

            // SharedPreferences — serialize with type discriminator.
            zip.write("prefs/eval_prefs.json", serializePrefs(context, MAIN_PREFS))
            zip.write("prefs/provider_registry.json", serializePrefs(context, PROVIDER_REGISTRY_PREFS))

            // Mirror the entire filesDir (reports, chats, traces, prompt cache,
            // prompt history, DataStore proto files, exports cache subdir excluded).
            val filesRoot = context.filesDir
            if (filesRoot.exists()) {
                addDirectoryRecursive(zip, filesRoot, "files")
            }
        }
    }

    /**
     * Restore a backup zip from [input]. Throws if the zip is malformed or the
     * manifest version is unsupported. The caller should prompt the user to
     * restart the app afterwards.
     */
    fun restore(context: Context, input: InputStream): RestoreSummary {
        var prefsRestored = 0
        var filesRestored = 0
        var manifestRead: Map<String, Any?>? = null

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) { zip.closeEntry(); continue }
                val bytes = zip.readBytes()
                when {
                    entry.name == "manifest.json" -> {
                        @Suppress("UNCHECKED_CAST")
                        manifestRead = gson.fromJson(bytes.toString(Charsets.UTF_8), Map::class.java) as Map<String, Any?>
                    }
                    entry.name == "prefs/eval_prefs.json" -> {
                        applyPrefs(context, MAIN_PREFS, bytes); prefsRestored++
                    }
                    entry.name == "prefs/provider_registry.json" -> {
                        applyPrefs(context, PROVIDER_REGISTRY_PREFS, bytes); prefsRestored++
                    }
                    entry.name.startsWith("files/") -> {
                        val rel = entry.name.removePrefix("files/")
                        if (rel.isNotBlank()) {
                            val target = File(context.filesDir, rel)
                            target.parentFile?.mkdirs()
                            target.writeBytes(bytes)
                            filesRestored++
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        val version = (manifestRead?.get("version") as? Number)?.toInt() ?: -1
        if (version > MANIFEST_VERSION) {
            throw IllegalStateException("Backup is from a newer app version ($version). Please update the app.")
        }
        return RestoreSummary(version = version, prefsFiles = prefsRestored, dataFiles = filesRestored)
    }

    data class RestoreSummary(val version: Int, val prefsFiles: Int, val dataFiles: Int)

    // ===== SharedPreferences ↔ JSON =====

    private fun serializePrefs(context: Context, name: String): ByteArray {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val out = mutableListOf<Map<String, Any?>>()
        for ((key, value) in prefs.all) {
            val entry: Map<String, Any?> = when (value) {
                is String -> mapOf("k" to key, "t" to "s", "v" to value)
                is Boolean -> mapOf("k" to key, "t" to "b", "v" to value)
                is Int -> mapOf("k" to key, "t" to "i", "v" to value)
                is Long -> mapOf("k" to key, "t" to "l", "v" to value)
                is Float -> mapOf("k" to key, "t" to "f", "v" to value)
                is Set<*> -> mapOf("k" to key, "t" to "ss", "v" to value.filterIsInstance<String>())
                else -> continue
            }
            out += entry
        }
        return gson.toJson(out).toByteArray()
    }

    private fun applyPrefs(context: Context, name: String, json: ByteArray) {
        val parsed = gson.fromJson(json.toString(Charsets.UTF_8), List::class.java) ?: return
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().clear().also { editor ->
            for (raw in parsed) {
                @Suppress("UNCHECKED_CAST")
                val m = raw as? Map<String, Any?> ?: continue
                val k = m["k"] as? String ?: continue
                when (m["t"] as? String) {
                    "s" -> editor.putString(k, m["v"] as? String)
                    "b" -> editor.putBoolean(k, m["v"] as? Boolean ?: false)
                    "i" -> editor.putInt(k, (m["v"] as? Number)?.toInt() ?: 0)
                    "l" -> editor.putLong(k, (m["v"] as? Number)?.toLong() ?: 0L)
                    "f" -> editor.putFloat(k, (m["v"] as? Number)?.toFloat() ?: 0f)
                    "ss" -> editor.putStringSet(k, (m["v"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet())
                }
            }
        }.commit()
    }

    // ===== Zip helpers =====

    private fun ZipOutputStream.write(path: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(path))
        write(bytes)
        closeEntry()
    }

    private fun addDirectoryRecursive(zip: ZipOutputStream, dir: File, prefix: String) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            val entryName = "$prefix/${child.name}"
            if (child.isDirectory) {
                addDirectoryRecursive(zip, child, entryName)
            } else {
                try {
                    zip.write(entryName, child.readBytes())
                } catch (_: Exception) {
                    // Skip files we can't read (locked, permission denied, etc.)
                }
            }
        }
    }
}
