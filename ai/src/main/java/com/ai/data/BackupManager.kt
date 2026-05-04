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
 *   prefs/<name>.json                 — every entry of [PREFS_TO_BACKUP]
 *                                       (typed, see serializePrefs)
 *   files/<mirror of filesDir>/...    — reports, chats, traces, KBs,
 *                                       prompt cache, prompt history,
 *                                       pricing tier blobs, datastore,
 *                                       embeddings cache, model lists.
 *                                       Excludes [FILES_DIR_BACKUP_EXCLUDES]
 *                                       (local_llms / local_models — see
 *                                       comment on that constant).
 *
 * Things deliberately NOT in the backup:
 *   - Anything under cacheDir (exports, shared traces, restore temp) —
 *     regeneratable and non-portable.
 *   - filesDir/local_llms — multi-GB user-supplied .task model bundles.
 *   - filesDir/local_models — hundreds-of-MB MediaPipe TextEmbedder
 *     .tflite files. Both are user-supplied via SAF / direct download
 *     and re-importing them is independent of settings restore.
 *   - WebViewChromiumPrefs and any prefs not in PREFS_TO_BACKUP.
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
    /** Cached pricing tables (OpenRouter + LiteLLM downloads + manual overrides).
     *  Including these in the backup means a restore preserves the user's
     *  freshly-fetched pricing snapshot and any manual overrides without forcing
     *  a re-download. The bulk of pricing data now lives as files under
     *  filesDir/pricing/ (picked up by the files/ mirror); this prefs file
     *  carries timestamps + the manual-override map. */
    private const val PRICING_CACHE_PREFS = "pricing_cache"
    /** Last-used Dual Chat configuration (the two picked models, their params,
     *  system prompts, subject, and two prompts). User-meaningful state worth
     *  preserving across a restore. */
    private const val DUAL_CHAT_PREFS = "dual_chat_prefs"
    /** Cached HuggingFace model-info lookups (positive + negative, 7-day TTL).
     *  Including these in the backup means a restore preserves the cache so
     *  we don't re-hit HuggingFace for models that were already known to be
     *  absent / present. */
    private const val HUGGINGFACE_CACHE_PREFS = "huggingface_cache"

    /** Every SharedPreferences file we round-trip through backup/restore.
     *  WebViewChromiumPrefs (cookies, web-process state) is intentionally
     *  excluded — it doesn't make sense to restore on a different device. */
    private val PREFS_TO_BACKUP = listOf(
        MAIN_PREFS, PROVIDER_REGISTRY_PREFS, PRICING_CACHE_PREFS, DUAL_CHAT_PREFS, HUGGINGFACE_CACHE_PREFS
    )

    /** Top-level filesDir subdirs we never copy into a backup zip and never
     *  delete during a restore wipe. Both hold user-supplied on-device model
     *  bundles — Gemma / Phi / Llama .task files (hundreds of MB to several
     *  GB each) under local_llms/, MediaPipe TextEmbedder .tflite files
     *  (~50–500 MB each) under local_models/. They're sourced via SAF or
     *  direct hand-off from Kaggle / HuggingFace, so:
     *    - Excluding from backup keeps zip sizes sane and avoids shipping
     *      potentially gigabytes of redistributable-but-user-installed
     *      model weights.
     *    - Preserving across the restore wipe means a user who has local
     *      models installed on the target device doesn't lose them when
     *      restoring an unrelated settings/data backup. */
    internal val FILES_DIR_BACKUP_EXCLUDES = setOf("local_llms", "local_models")

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

            // SharedPreferences — serialize each tracked file with type discriminator.
            for (name in PREFS_TO_BACKUP) {
                zip.write("prefs/$name.json", serializePrefs(context, name))
            }

            // Mirror the entire filesDir, minus FILES_DIR_BACKUP_EXCLUDES at the
            // top level. Exports live under cacheDir/exports/ and are excluded
            // simply because cacheDir isn't part of this mirror.
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
        val tempZip = File.createTempFile("ai-restore-", ".zip", context.cacheDir)
        return try {
            tempZip.outputStream().use { out -> input.copyTo(out) }
            val version = readManifestVersion(tempZip)
            if (version > MANIFEST_VERSION) {
                throw IllegalStateException("Backup is from a newer app version ($version). Please update the app.")
            }
            // Validate-then-write. Walk the zip once into memory,
            // catching any read/truncation failure BEFORE we touch
            // filesDir. The previous flow cleared filesDir as soon as
            // the manifest version checked out — any subsequent
            // failure (zip corruption, partial entry, IOException
            // mid-stream) wiped the user's reports / chats / KBs and
            // left a half-empty install with no way back. Now: bytes
            // first, destroy second.
            val staged = readAllEntriesValidated(context, tempZip)
            clearFilesDirForRestore(context.filesDir)
            applyStagedEntries(context, staged, version)
        } finally {
            tempZip.delete()
        }
    }

    /** Walk every entry in the zip, decompress + readBytes each one
     *  into memory, and return the staged map. Any IOException /
     *  truncation throws here, BEFORE the destructive
     *  clearFilesDirForRestore step in [restore]. Memory cost: full
     *  uncompressed payload — acceptable since backups are typically
     *  10–50 MB and the device already had to load that much during
     *  the SAF copy into the temp file. */
    private fun readAllEntriesValidated(context: Context, zipFile: File): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(zipFile.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) { zip.closeEntry(); continue }
                val name = entry.name
                // Skip entry names we wouldn't act on anyway so a
                // weird/extra path in the zip doesn't allocate bytes
                // for nothing.
                val keep = name == "manifest.json"
                    || (name.startsWith("prefs/") && name.endsWith(".json"))
                    || name.startsWith("files/")
                if (!keep) { zip.closeEntry(); continue }
                // For files/ entries, validate the resolved path lives
                // inside filesDir before staging — defence in depth
                // against `files/../shared_prefs/...` style entries.
                if (name.startsWith("files/")) {
                    val rel = name.removePrefix("files/")
                    if (rel.isBlank()) { zip.closeEntry(); continue }
                    val target = File(context.filesDir, rel)
                    val canonicalTarget = target.canonicalPath
                    val canonicalRoot = context.filesDir.canonicalPath + File.separator
                    if (!canonicalTarget.startsWith(canonicalRoot)) {
                        android.util.Log.w("BackupManager",
                            "Skipping zip entry that escapes filesDir: $name")
                        zip.closeEntry(); continue
                    }
                }
                out[name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return out
    }

    /** Second pass — apply the in-memory staged entries to disk. By
     *  now we've passed the wipe and the staged bytes are already
     *  proven to read cleanly. */
    private fun applyStagedEntries(context: Context, staged: Map<String, ByteArray>, version: Int): RestoreSummary {
        var prefsRestored = 0
        var filesRestored = 0
        for ((name, bytes) in staged) {
            when {
                name == "manifest.json" -> Unit
                // Generic: any prefs/<name>.json restores into the SharedPreferences
                // file called <name>. Old backups (pre-pricing_cache addition) and
                // future additions both round-trip without further code changes.
                name.startsWith("prefs/") && name.endsWith(".json") -> {
                    val prefsName = name.removePrefix("prefs/").removeSuffix(".json")
                    if (prefsName.isNotBlank()) {
                        applyPrefs(context, prefsName, bytes); prefsRestored++
                    }
                }
                name.startsWith("files/") -> {
                    val rel = name.removePrefix("files/")
                    if (rel.isNotBlank()) {
                        val target = File(context.filesDir, rel)
                        target.parentFile?.mkdirs()
                        target.writeBytes(bytes)
                        filesRestored++
                    }
                }
            }
        }
        // The backup carries the user's provider_registry prefs in
        // full, so the registry rebuilds straight from disk on next
        // launch — nothing else to do here. The on-demand
        // ProviderRegistry.importFromAsset is the only path that adds
        // newly bundled providers now.
        return RestoreSummary(
            version = version,
            prefsFiles = prefsRestored,
            dataFiles = filesRestored
        )
    }

    private fun readManifestVersion(zipFile: File): Int {
        ZipInputStream(zipFile.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name == "manifest.json") {
                    val bytes = zip.readBytes()
                    @Suppress("UNCHECKED_CAST")
                    val manifest = gson.fromJson(bytes.toString(Charsets.UTF_8), Map::class.java) as Map<String, Any?>
                    zip.closeEntry()
                    return (manifest["version"] as? Number)?.toInt() ?: -1
                }
                zip.closeEntry()
            }
        }
        return -1
    }

    internal fun clearFilesDirForRestore(filesDir: File) {
        if (!filesDir.exists()) {
            filesDir.mkdirs()
            return
        }
        // Wipe everything except the local-model dirs — see FILES_DIR_BACKUP_EXCLUDES.
        // The backup never contained those, so deleting them here would silently
        // destroy gigabytes of unrelated user data on the target device.
        filesDir.listFiles()?.forEach {
            if (it.name !in FILES_DIR_BACKUP_EXCLUDES) it.deleteRecursively()
        }
    }

    data class RestoreSummary(
        val version: Int,
        val prefsFiles: Int,
        val dataFiles: Int
    )

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
            // Top-level filesDir excludes — local model bundles, see
            // FILES_DIR_BACKUP_EXCLUDES. Only applied at depth 0 (prefix == "files")
            // so a deeper directory that happens to share the name still gets backed up.
            if (prefix == "files" && child.name in FILES_DIR_BACKUP_EXCLUDES) continue
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
