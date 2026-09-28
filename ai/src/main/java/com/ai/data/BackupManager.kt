package com.ai.data

import android.content.Context
import com.ai.data.preferences.SettingsPreferences
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
 *   - filesDir/local_llms — multi-GB user-supplied .task model bundles.
 *   - filesDir/local_models — hundreds-of-MB MediaPipe TextEmbedder
 *     .tflite files. Both are user-supplied via SAF / direct download
 *     and re-importing them is independent of settings restore.
 *   - cacheDir, entirely. It only ever holds transient hand-offs: share
 *     exports (including earlier ai-backup-*.zip files, which made every
 *     backup contain all previous ones and double in size), shared
 *     traces, camera captures, APK update downloads, import/export
 *     staging, the in-flight restore zip and the reset flow's plaintext
 *     key temp. None of it is user data a restored install needs.
 *   - WebViewChromiumPrefs and any prefs not in PREFS_TO_BACKUP.
 *
 * SharedPreferences entries are serialized with a type discriminator so values
 * round-trip through JSON without ambiguity (otherwise an Int would come back
 * as a Double via Gson). Files are stored verbatim.
 *
 * After a restore the in-memory state of singletons (ProviderRegistry,
 * ApiTracer, PromptCache, ReportStorage, ChatHistoryManager) is stale, and the
 * AppViewModel's StateFlow is out of sync. HousekeepingScreen handles this by
 * killing the process and relaunching the activity once restore returns —
 * the next launch reads everything from disk fresh. We don't try to
 * live-reload here.
 */

/** Thrown when a restore fails AFTER the destructive filesDir/cacheDir wipe
 *  has begun. Lets the UI avoid telling the user their data is intact when it
 *  has in fact been destroyed/half-restored. Pre-wipe failures (bad manifest,
 *  validation, cap, zero data files) propagate as their original exception. */
class RestoreAfterWipeException(cause: Throwable) : Exception(
    "Restore failed after the data wipe: ${cause.message}", cause)

object BackupManager {

    private const val MANIFEST_VERSION = 1

    /** Per-entry uncompressed cap during restore. Embeddings-heavy
     *  knowledge-base files are the realistic worst case; 256 MB is
     *  well above a fully populated KB but refuses a zip-bomb entry
     *  before it fills the disk. File entries stream to disk, never
     *  into the heap. */
    private const val MAX_RESTORE_ENTRY_BYTES: Long = 256L * 1024L * 1024L

    /** Cap for a prefs/<name>.json entry — the only entries held in
     *  memory (they're parsed during validation). Real ones are well
     *  under a few MB even with inlined provider catalogs. */
    private const val MAX_RESTORE_PREFS_BYTES: Long = 64L * 1024L * 1024L

    /** Total uncompressed cap across all kept entries. Backups are
     *  typically 10–50 MB; 1 GB is generous enough for users with a
     *  large RAG corpus and tight enough to refuse a zip bomb. */
    private const val MAX_RESTORE_TOTAL_BYTES: Long = 1024L * 1024L * 1024L

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
    /** Per-model CloudPrice detail lookups (sibling of the HuggingFace cache) —
     *  preserved across a restore so a restored device keeps its negative /
     *  positive per-model results instead of re-hitting CloudPrice. */
    private const val CLOUDPRICE_MODEL_CACHE_PREFS = "cloudprice_model_cache"
    /** Rate-limited (provider, model) cooldowns benched by a >1h 429.
     *  Worth preserving across a restore — a restored device should
     *  keep skipping a model that's still inside its quota window. */
    private const val MODEL_COOLDOWNS_PREFS = "model_cooldowns"
    /** View screen's reorderable tile order — single string key
     *  `tile_order` holding a comma-separated list of tile ids. The
     *  user explicitly arranged the grid (e.g. "Costs first"), so the
     *  order should survive backup/restore. */
    private const val VIEW_SCREEN_PREFS = "view_screen_prefs"

    /** Every SharedPreferences file we round-trip through backup/restore.
     *  WebViewChromiumPrefs (cookies, web-process state) is intentionally
     *  excluded — it doesn't make sense to restore on a different device. */
    private val PREFS_TO_BACKUP = listOf(
        MAIN_PREFS, PROVIDER_REGISTRY_PREFS, PRICING_CACHE_PREFS, DUAL_CHAT_PREFS, HUGGINGFACE_CACHE_PREFS,
        CLOUDPRICE_MODEL_CACHE_PREFS, MODEL_COOLDOWNS_PREFS, VIEW_SCREEN_PREFS
    )

    /** Top-level filesDir subdirs we never copy into a backup zip and never
     *  delete during a restore wipe. They hold user-supplied on-device model
     *  bundles — Gemma / Phi / Llama .task files (hundreds of MB to several
     *  GB each) under local_llms/, MediaPipe TextEmbedder .tflite files
     *  (~50–500 MB each) under local_models/ — plus the MediaPipe LLM
     *  inference native runtime (.so, ~26 MB) under native/. All three are
     *  user-installed via the AI Setup → Local LLMs / Local LiteRT
     *  screens, so:
     *    - Excluding from backup keeps zip sizes sane and avoids shipping
     *      potentially gigabytes of redistributable-but-user-installed
     *      model weights, plus a binary that's tied to the device ABI.
     *    - Preserving across the restore wipe means a user who has local
     *      models or the runtime installed on the target device doesn't
     *      lose them when restoring an unrelated settings/data backup. */
    internal val FILES_DIR_BACKUP_EXCLUDES = setOf("local_llms", "local_models", "native", "applog")

    /** Top-level filesDir subdirs holding content-addressed blobs that the
     *  report / secondary JSON (reports/, secondary/) point at. Blobs are
     *  written before their parent and kept until the report is deleted,
     *  so copying these dirs AFTER every parent keeps a backup taken while
     *  a run is saving consistent: every blob a copied parent references
     *  already exists when its dir is walked. The reverse order let a
     *  parent saved mid-backup reference a blob the zip never got
     *  ("Saved report content is missing" / "Saved source unavailable"
     *  after restore). */
    private val FILES_DIR_BLOB_DIRS = setOf("report_content", "report_evidence")

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private const val BACKUP_FILE_PREFIX = "ai-backup-"

    fun timestampForFileName(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    fun defaultFileName(): String = "$BACKUP_FILE_PREFIX${timestampForFileName()}.zip"

    /** Delete earlier backup zips (and orphaned `.part` stagings) that the
     *  share flow left in `cacheDir/exports/`. A backup is superseded by
     *  the next one and each can be hundreds of MB, so the Backup button
     *  calls this before staging a new zip. Only files with the backup
     *  prefix are touched — other exports stay for their share targets. */
    fun deleteStaleBackupExports(context: Context) {
        File(context.cacheDir, "exports").listFiles()
            ?.filter { it.isFile && it.name.startsWith(BACKUP_FILE_PREFIX) }
            ?.forEach { if (!it.delete()) AppLog.w("Backup", "Could not delete stale backup export ${it.name}") }
    }

    /**
     * Stream a complete backup zip into [out]. The caller (Housekeeping)
     * provides [out] from a SAF-picked Uri.
     */
    fun backup(context: Context, out: OutputStream): BackupSummary {
        AppLog.i("Backup", "→ backup start")
        val t0 = System.currentTimeMillis()
        var filesWritten = 0
        var filesSkipped = 0
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
            AppLog.d("Backup", "manifest written")

            // SharedPreferences — serialize each tracked file with type discriminator.
            for (name in PREFS_TO_BACKUP) {
                zip.write("prefs/$name.json", serializePrefs(context, name))
            }
            AppLog.d("Backup", "prefs section written (${PREFS_TO_BACKUP.size} files)")

            // Mirror the entire filesDir, minus FILES_DIR_BACKUP_EXCLUDES at
            // the top level (local model bundles).
            val filesRoot = context.filesDir
            if (filesRoot.exists()) {
                val summary = addDirectoryRecursive(zip, filesRoot, "files")
                filesWritten = summary.written
                filesSkipped += summary.skipped
                AppLog.d("Backup", "filesDir mirrored — $filesWritten entries, skipped=${summary.skipped}")
            }
            // cacheDir is deliberately not mirrored — see the class doc.
            // It holds this very backup's share staging and every earlier
            // backup zip, so mirroring it grew each backup by all the
            // previous ones.
        }
        if (filesSkipped > 0) {
            AppLog.w("Backup", "Backup skipped $filesSkipped unreadable file(s); see earlier warnings for paths")
        }
        AppLog.i("Backup", "← backup done in ${System.currentTimeMillis() - t0}ms (filesDir=$filesWritten skipped=$filesSkipped)")
        return BackupSummary(filesDirEntries = filesWritten, skippedFiles = filesSkipped)
    }

    /**
     * Restore a backup zip from [input]. Throws if the zip is malformed or the
     * manifest version is unsupported. The caller should prompt the user to
     * restart the app afterwards.
     */
    fun restore(context: Context, input: InputStream): RestoreSummary {
        AppLog.i("Backup", "→ restore start")
        val t0 = System.currentTimeMillis()
        val tempZip = File.createTempFile("ai-restore-", ".zip", context.cacheDir)
        return try {
            tempZip.outputStream().use { out -> input.copyTo(out) }
            val version = readManifestVersion(tempZip)
            // Sentinel -1 = manifest.json not present (or its
            // "version" field unreadable). Reject — without that
            // positive proof of "this is an AI-app backup" we'd
            // otherwise wipe filesDir and write whatever the random
            // zip happened to contain.
            if (version < 1) {
                throw IllegalStateException("Backup is missing a recognizable manifest.json — refusing to restore.")
            }
            if (version > MANIFEST_VERSION) {
                throw IllegalStateException("Backup is from a newer app version ($version). Please update the app.")
            }
            // Validate-then-write. Read every entry of the local temp zip
            // once (path checks, size caps, CRC check, every prefs file
            // parsed), catching any read/truncation/parse failure BEFORE we
            // touch prefs or filesDir. File bytes are not kept: the apply
            // pass below streams them again from the same, already verified
            // temp zip, so a large backup never has to fit in the heap.
            val validated = validateBackup(context, tempZip)
            AppLog.d("Backup", "manifest version=$version, validated ${validated.prefs.size} prefs + ${validated.fileEntries.size} files (${validated.totalBytes} bytes)")
            // Sanity floor before the destructive wipe: a structurally-valid
            // backup with ZERO files/ entries (the historical "0 files" /
            // symlink-skip regression) would otherwise wipe the device's
            // reports / chats / KBs and write nothing back. Refuse here, before
            // any prefs apply or wipe, so the current data stays untouched.
            if (validated.fileEntries.isEmpty()) {
                throw IllegalStateException("Backup contains no data files — refusing to restore; your current data is untouched.")
            }
            // Everything from here is destructive — the prefs apply clears
            // each prefs file before writing it — so any failure means data
            // is gone / half-restored: re-throw as RestoreAfterWipeException
            // and let the UI drop the "data left unchanged" reassurance.
            // Validation + the zero-files floor above already caught the
            // recoverable cases (including a malformed prefs file) before
            // this point.
            try {
                // Commit prefs first — SharedPreferences.commit() is
                // synchronous and atomic per file, so once this returns
                // every restored prefs file is durable on disk. A process
                // kill between this step and the file-writing step below
                // leaves prefs valid + filesDir empty (re-restorable),
                // whereas wiping filesDir BEFORE committing prefs would
                // leave an inconsistent half-restored state pointing at
                // nothing.
                val prefsRestored = applyPrefsOnly(context, validated)
                AppLog.d("Backup", "prefs applied: $prefsRestored file(s)")
                clearFilesDirForRestore(context.filesDir)
                AppLog.d("Backup", "filesDir wiped (except excludes)")
                // Wipe cacheDir too (nothing in it is restored — it only
                // holds transient exports / staging from the old state),
                // but preserve the temp zip we're restoring from: the file
                // pass below still streams its entries.
                clearCacheDirForRestore(context.cacheDir, preserve = setOf(tempZip.name))
                AppLog.d("Backup", "cacheDir wiped (preserving ${tempZip.name})")
                val filesRestored = applyFilesOnly(context, tempZip, validated)
                AppLog.d("Backup", "files applied: $filesRestored entries")
                AppLog.i("Backup", "← restore done in ${System.currentTimeMillis() - t0}ms (prefs=$prefsRestored files=$filesRestored)")
                RestoreSummary(version = version, prefsFiles = prefsRestored, dataFiles = filesRestored)
            } catch (e: Throwable) {
                AppLog.e("Backup", "Restore failed AFTER wipe: ${e.message}")
                throw RestoreAfterWipeException(e)
            }
        } finally {
            tempZip.delete()
        }
    }

    /** What [validateBackup] found: every prefs file already parsed (so a
     *  malformed one aborts before anything is changed) and the files/
     *  entry names that passed the path + size checks. File bytes are NOT
     *  held in memory — [applyFilesOnly] streams them from the same local
     *  temp zip, which the validation pass has fully read and CRC-checked
     *  (ZipInputStream verifies each entry's CRC and size at its end). */
    private class ValidatedBackup(
        val prefs: Map<String, List<Map<String, Any?>>>,
        val fileEntries: Set<String>,
        val totalBytes: Long
    )

    /** Walk every entry in the zip and decompress it once without keeping
     *  file bytes. Any IOException / truncation / CRC mismatch / malformed
     *  prefs file throws here, BEFORE the destructive steps in [restore].
     *  Only the (small) prefs files are held in memory, as parsed rows.
     *
     *  A maliciously-crafted or just unusually-large backup is refused by
     *  a per-entry and total uncompressed cap — generous enough for real
     *  backups (an embeddings-heavy KB can be tens of MB), tight enough to
     *  refuse a zip bomb. Cap is enforced during the read by counting
     *  bytes against a cumulative budget. */
    private fun validateBackup(context: Context, zipFile: File): ValidatedBackup {
        val prefs = LinkedHashMap<String, List<Map<String, Any?>>>()
        val fileEntries = LinkedHashSet<String>()
        var totalBytes = 0L
        ZipInputStream(zipFile.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) { zip.closeEntry(); continue }
                val name = entry.name
                // Skip entry names we wouldn't act on anyway so a
                // weird/extra path in the zip doesn't allocate bytes
                // for nothing.
                // cache/ entries (written by older builds, which mirrored
                // cacheDir) are ignored: cacheDir only ever held transient
                // exports — including every earlier backup zip.
                val keep = name == "manifest.json"
                    || (name.startsWith("prefs/") && name.endsWith(".json")
                        && name.removePrefix("prefs/").removeSuffix(".json") in PREFS_TO_BACKUP)
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
                        AppLog.w("Backup",
                            "Skipping zip entry that escapes filesDir: $name")
                        zip.closeEntry(); continue
                    }
                }
                val remaining = MAX_RESTORE_TOTAL_BYTES - totalBytes
                if (name.startsWith("prefs/")) {
                    // The allowlist was applied by `keep` above: a crafted
                    // name with a path separator would make
                    // getSharedPreferences throw mid-apply, and an arbitrary
                    // one would create a junk shared_prefs file.
                    val buf = java.io.ByteArrayOutputStream()
                    totalBytes += copyCapped(zip, MAX_RESTORE_PREFS_BYTES, remaining, name, buf)
                    val prefsName = name.removePrefix("prefs/").removeSuffix(".json")
                    parsePrefs(prefsName, buf.toByteArray())?.let { prefs[prefsName] = it }
                } else {
                    // manifest.json / files/: read to the end (size caps +
                    // CRC check) and discard.
                    totalBytes += copyCapped(zip, MAX_RESTORE_ENTRY_BYTES, remaining, name, null)
                    if (name.startsWith("files/")) fileEntries += name
                }
                zip.closeEntry()
            }
        }
        return ValidatedBackup(prefs, fileEntries, totalBytes)
    }

    /** Copy the current entry of [zip] into [sink] (null = discard),
     *  allowing up to [perEntryCap] bytes (or [remainingTotal], whichever
     *  is lower). Throws if the entry exceeds either cap, so the
     *  destructive restore steps never run against an oversized payload.
     *  Returns the entry's uncompressed size. */
    private fun copyCapped(zip: ZipInputStream, perEntryCap: Long, remainingTotal: Long, name: String,
                           sink: OutputStream?): Long {
        val cap = minOf(perEntryCap, remainingTotal)
        if (cap <= 0L) throw IllegalStateException(
            "Backup exceeds total cap (${MAX_RESTORE_TOTAL_BYTES / (1024L * 1024L)} MB) at entry $name")
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = zip.read(chunk)
            if (n < 0) break
            total += n
            if (total > cap) {
                throw IllegalStateException(
                    "Backup entry $name exceeds cap " +
                        "(${if (cap == perEntryCap) "per-entry" else "remaining total"} = $cap bytes)")
            }
            sink?.write(chunk, 0, n)
        }
        return total
    }

    /** Parse a prefs/<name>.json entry into its {k, t, v} rows. Runs in
     *  the validation pass so a malformed later file aborts the restore
     *  BEFORE an earlier prefs file (e.g. eval_prefs) has been cleared —
     *  parsing only at apply time left the user with half-replaced
     *  settings while being told nothing changed. Null = empty entry
     *  (nothing to apply; that prefs file is left as it is). */
    @Suppress("UNCHECKED_CAST")
    private fun parsePrefs(name: String, json: ByteArray): List<Map<String, Any?>>? {
        val parsed: List<*> = (try {
            gson.fromJson(json.toString(Charsets.UTF_8), List::class.java)
        } catch (e: com.google.gson.JsonParseException) {
            throw IllegalStateException("Backup settings file $name is malformed: ${e.message}", e)
        }) ?: return null
        return parsed.mapNotNull { it as? Map<String, Any?> }
    }

    /** Stream the current entry of [zip] to [target] and fsync the file
     *  descriptor before returning so a process kill (HousekeepingScreen
     *  kills the process right after restore() returns to force a fresh
     *  launch) can't surface partial / empty / pre-write content. SAF
     *  OutputStream close doesn't fsync, hence the explicit
     *  FileDescriptor.sync(). Returns the bytes written. */
    private fun writeEntryFsync(zip: ZipInputStream, target: File, name: String, remainingTotal: Long): Long =
        java.io.FileOutputStream(target).use { fos ->
            val written = copyCapped(zip, MAX_RESTORE_ENTRY_BYTES, remainingTotal, name, fos)
            fos.flush()
            try { fos.fd.sync() } catch (_: java.io.IOException) { /* best effort */ }
            written
        }

    /** First apply pass — commit every validated prefs file into its
     *  SharedPreferences file. Each commit() call is synchronous +
     *  atomic per file. Splitting prefs out of the file pass lets the
     *  restore() caller commit prefs BEFORE wiping filesDir, so a
     *  crash mid-restore can't leave us with empty filesDir + stale
     *  prefs pointing at nothing. */
    private fun applyPrefsOnly(context: Context, validated: ValidatedBackup): Int {
        for ((prefsName, rows) in validated.prefs) applyPrefs(context, prefsName, rows)
        return validated.prefs.size
    }

    /** Second apply pass — stream every validated files/ entry from
     *  [zipFile] to disk. Caller should already have wiped filesDir. */
    private fun applyFilesOnly(context: Context, zipFile: File, validated: ValidatedBackup): Int {
        var filesRestored = 0
        var totalBytes = 0L
        ZipInputStream(zipFile.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (!entry.isDirectory && name in validated.fileEntries) {
                    val target = File(context.filesDir, name.removePrefix("files/"))
                    target.parentFile?.mkdirs()
                    totalBytes += writeEntryFsync(zip, target, name, MAX_RESTORE_TOTAL_BYTES - totalBytes)
                    filesRestored++
                }
                zip.closeEntry()
            }
        }
        // The backup carries the user's provider_registry prefs in
        // full, so the registry rebuilds straight from disk on next
        // launch — nothing else to do here. The on-demand
        // ProviderRegistry.importFromAsset is the only path that adds
        // newly bundled providers now.
        return filesRestored
    }

    private fun readManifestVersion(zipFile: File): Int {
        ZipInputStream(zipFile.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                try {
                    if (!entry.isDirectory && entry.name == "manifest.json") {
                        val bytes = zip.readBytes()
                        @Suppress("UNCHECKED_CAST")
                        val manifest = gson.fromJson(bytes.toString(Charsets.UTF_8), Map::class.java) as Map<String, Any?>
                        return when (val version = manifest["version"]) {
                            is Number -> version.toInt()
                            is String -> version.trim().toIntOrNull() ?: -1
                            else -> -1
                        }
                    }
                } finally {
                    // Always closeEntry — ZipInputStream.nextEntry behavior
                    // on a partially-read entry is implementation-defined,
                    // and the previous code only closed inside the matched
                    // branch. Fully-skipping a non-manifest entry could
                    // mis-align the stream and make the manifest scan
                    // return -1 even though the manifest was present.
                    zip.closeEntry()
                }
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

    /** Wipe cacheDir's transient exports / staging on restore. [preserve]
     *  protects the in-flight restore temp zip — its name is generated
     *  by File.createTempFile, so the caller passes it in. */
    internal fun clearCacheDirForRestore(cacheDir: File, preserve: Set<String>) {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
            return
        }
        cacheDir.listFiles()?.forEach {
            if (it.name !in preserve) it.deleteRecursively()
        }
    }

    data class RestoreSummary(
        val version: Int,
        val prefsFiles: Int,
        val dataFiles: Int
    )

    data class BackupSummary(
        val filesDirEntries: Int,
        val skippedFiles: Int
    )

    // ===== SharedPreferences ↔ JSON =====

    private fun serializePrefs(context: Context, name: String): ByteArray {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val out = mutableListOf<Map<String, Any?>>()
        val values = if (name == MAIN_PREFS)
            com.ai.data.preferences.CatalogPreferences(prefs, context.filesDir).snapshotForBackup()
        else prefs.all
        for ((key, value) in values) {
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

    /** Replace prefs file [name] with [rows] (already parsed by
     *  [parsePrefs] during validation). */
    private fun applyPrefs(context: Context, name: String, rows: List<Map<String, Any?>>) {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val committed = prefs.edit().clear().also { editor ->
            for (m in rows) {
                val k = m["k"] as? String ?: continue
                when (val tag = m["t"] as? String) {
                    // putString(k, null) REMOVES the key rather than storing
                    // it (Bug 53). Skip a null/non-string value so a restore
                    // doesn't silently drop a key that was present (even if
                    // null) in the backup.
                    "s" -> (m["v"] as? String)?.let { editor.putString(k, it) }
                    "b" -> editor.putBoolean(k, m["v"] as? Boolean ?: false)
                    "i" -> editor.putInt(k, (m["v"] as? Number)?.toInt() ?: 0)
                    "l" -> editor.putLong(k, (m["v"] as? Number)?.toLong() ?: 0L)
                    "f" -> editor.putFloat(k, (m["v"] as? Number)?.toFloat() ?: 0f)
                    "ss" -> editor.putStringSet(k, (m["v"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet())
                    else -> AppLog.w("Backup",
                        "applyPrefs($name): unknown type tag '$tag' for key '$k' — entry skipped")
                }
            }
        }.commit()
        if (!committed) throw java.io.IOException("Could not save restored settings file $name")
    }

    // ===== Zip helpers =====

    private fun ZipOutputStream.write(path: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(path))
        write(bytes)
        closeEntry()
    }

    private data class DirectoryBackupSummary(val written: Int = 0, val skipped: Int = 0)

    private fun addDirectoryRecursive(zip: ZipOutputStream, dir: File, prefix: String): DirectoryBackupSummary {
        var written = 0
        var skipped = 0
        val children = dir.listFiles()
        if (children == null) {
            AppLog.w("Backup", "Skipping unreadable directory during backup: ${dir.absolutePath}")
            return DirectoryBackupSummary(skipped = 1)
        }
        // Resolve [dir]'s canonical path ONCE per recursion level so the
        // symlink check below can compare apples-to-apples. The previous
        // implementation compared `child.canonicalPath != child.absolutePath`
        // which always fired on Android: `/data/user/0` is a symlink to
        // `/data/data`, so every child of filesDir / cacheDir reports a
        // canonical path that differs from its absolute path — even when
        // it's a perfectly real file. Result: addDirectoryRecursive
        // skipped EVERY child, and backup zips ended up with only the
        // manifest + prefs entries (the "0 files" the user saw at
        // restore). The fix is to compare each child's canonical path
        // against its parent's canonical path — a real child resolves
        // under the parent, a symlink escaping outside doesn't.
        val parentCanonical = try { dir.canonicalPath } catch (_: java.io.IOException) { dir.absolutePath }
        // Blob dirs last at the top level — see FILES_DIR_BLOB_DIRS. Stable
        // sort, so every other entry keeps listFiles order.
        val ordered = if (prefix == "files") children.sortedBy { it.name in FILES_DIR_BLOB_DIRS } else children.asList()
        for (child in ordered) {
            // Top-level filesDir excludes — local model bundles, see
            // FILES_DIR_BACKUP_EXCLUDES. Only applied at depth 0 (prefix == "files")
            // so a deeper directory that happens to share the name still gets backed up.
            if (prefix == "files" && child.name in FILES_DIR_BACKUP_EXCLUDES) continue
            // Don't follow symlinks — a symlink in filesDir pointing
            // outside (e.g. into /sdcard/) would silently slurp
            // unrelated user data into the backup zip. A real child
            // canonicalises under its parent; a symlink escaping the
            // tree resolves somewhere else entirely.
            try {
                val childCanonical = child.canonicalPath
                if (!childCanonical.startsWith(parentCanonical + File.separator)) {
                    AppLog.w("Backup", "Skipping symlink that escapes ${dir.absolutePath}: ${child.absolutePath} → $childCanonical")
                    continue
                }
            } catch (_: java.io.IOException) {
                // canonicalPath can throw on a dangling symlink — also skip.
                AppLog.w("Backup", "Skipping path that cannot be resolved during backup: ${child.absolutePath}")
                skipped++
                continue
            }
            val entryName = "$prefix/${child.name}"
            if (child.isDirectory) {
                val summary = addDirectoryRecursive(zip, child, entryName)
                written += summary.written
                skipped += summary.skipped
            } else {
                try {
                    zip.write(entryName, child.readBytes())
                    written++
                } catch (e: Exception) {
                    skipped++
                    AppLog.w("Backup", "Skipping unreadable file during backup: ${child.absolutePath}", e)
                }
            }
        }
        return DirectoryBackupSummary(written = written, skipped = skipped)
    }
}
