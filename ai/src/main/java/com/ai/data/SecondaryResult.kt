package com.ai.data

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Persists [SecondaryResult]s as <filesDir>/secondary/<reportId>/<resultId>.json.
 * Mirrors [ReportStorage]'s init/lock pattern. The per-report subdirectory keeps
 * deletion of the parent report (and its meta-results) a single rmdir.
 */
object SecondaryResultStorage {
    private const val SECONDARY_DIR = "secondary"
    private val gson = createAppGson()
    private val lock = ReentrantLock()
    @Volatile private var rootDir: File? = null

    /** Per-report cache of parsed [SecondaryResult] rows plus lookup indexes.
     *  The first read of a report scans its flat secondary directory; in-app
     *  writes then update the affected row in memory, so later kind-specific
     *  reads can return from `kind -> filenames` without rescanning every row
     *  from unrelated batch families. On process start the cache is empty. */
    private data class CachedEntry(
        val mtime: Long,
        val length: Long,
        val parsed: SecondaryResult
    )
    private class ReportCache(initialCapacity: Int = 16) {
        val byFilename = HashMap<String, CachedEntry>(initialCapacity)
        val filenameById = HashMap<String, String>(initialCapacity)
        val filenamesByKind = HashMap<SecondaryKind, LinkedHashSet<String>>()
        var loaded: Boolean = false
        var dirMtime: Long = 0L
        var bytes: Long = 0L

        fun put(filename: String, entry: CachedEntry) {
            remove(filename)
            byFilename[filename] = entry
            bytes += entry.length
            filenameById[entry.parsed.id] = filename
            filenamesByKind.getOrPut(entry.parsed.kind) { LinkedHashSet() }.add(filename)
        }

        fun remove(filename: String) {
            val old = byFilename.remove(filename) ?: return
            bytes -= old.length
            filenameById.remove(old.parsed.id)
            filenamesByKind[old.parsed.kind]?.let { names ->
                names.remove(filename)
                if (names.isEmpty()) filenamesByKind.remove(old.parsed.kind)
            }
        }
    }
    private val reportCaches = object : LinkedHashMap<String, ReportCache>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReportCache>?) = size > 3
    }

    fun init(context: Context) {
        if (rootDir == null) lock.withLock {
            if (rootDir == null) {
                val dir = File(context.filesDir, SECONDARY_DIR)
                if (!dir.exists()) dir.mkdirs()
                rootDir = dir
            }
        }
    }

    private fun reportDir(reportId: String): File? {
        val root = rootDir ?: return null
        // Defence in depth: the import path persists secondaries
        // keyed by the embedded reportId. A crafted id ("../prefs/x")
        // would otherwise mkdirs outside the secondary root. Reject
        // flat-id violations and canonical containment escapes alike.
        if (reportId.isBlank() || reportId == "." || reportId == ".."
                || reportId.contains('/') || reportId.contains('\\')) {
            AppLog.e("SecondaryResultStorage", "Refusing to resolve report dir for suspect id $reportId")
            return null
        }
        val dir = File(root, reportId)
        if (!dir.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            AppLog.e("SecondaryResultStorage", "Refusing to resolve report dir that escapes root: $reportId")
            return null
        }
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Read-side validated resolver (Bug 32): the list/get/bump/count
     *  paths previously resolved `File(rootDir, reportId)` directly,
     *  bypassing the traversal guard that only [reportDir] (the write
     *  path) applied. Mirrors [reportDir]'s flat-id + canonical
     *  containment checks but does NOT mkdirs — a read of a non-existent
     *  report shouldn't create an empty directory. Returns null on an
     *  unsafe id or when the root isn't initialised. */
    private fun resolveReportDirForRead(reportId: String): File? {
        val root = rootDir ?: return null
        if (reportId.isBlank() || reportId == "." || reportId == ".."
                || reportId.contains('/') || reportId.contains('\\')) {
            AppLog.e("SecondaryResultStorage", "Refusing to resolve report dir for suspect id $reportId")
            return null
        }
        val dir = File(root, reportId)
        if (!dir.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            AppLog.e("SecondaryResultStorage", "Refusing to resolve report dir that escapes root: $reportId")
            return null
        }
        return dir
    }

    private fun cacheForReport(reportId: String, capacity: Int = 16): ReportCache =
        reportCaches.getOrPut(reportId) { ReportCache(capacity) }

    private fun rememberCachedResult(reportId: String, file: File, result: SecondaryResult) {
        val cache = cacheForReport(reportId)
        cache.put(file.name, CachedEntry(file.lastModified(), file.length(), result))
        if (cache.loaded) cache.dirMtime = file.parentFile?.lastModified() ?: cache.dirMtime
        if (cache.bytes > 8L * 1024 * 1024) reportCaches.remove(reportId)
    }

    private fun forgetCachedResult(reportId: String, filename: String) {
        val cache = reportCaches[reportId] ?: return
        cache.remove(filename)
    }

    private fun refreshReportCacheFromDisk(dir: File, cache: ReportCache) {
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        val seenFilenames = HashSet<String>(files.size)
        for (file in files) {
            val name = file.name
            seenFilenames.add(name)
            val mtime = file.lastModified()
            val length = file.length()
            val cached = cache.byFilename[name]
            if (cached != null && cached.mtime == mtime && cached.length == length) {
                continue
            }
            val parsed = try {
                gson.fromJson(file.readText(), SecondaryResult::class.java)
            } catch (_: Exception) {
                null
            }
            if (parsed != null) {
                cache.put(name, CachedEntry(mtime, length, parsed))
            } else {
                cache.remove(name)
            }
        }
        cache.byFilename.keys.filter { it !in seenFilenames }.toList().forEach(cache::remove)
        cache.loaded = true
        cache.dirMtime = dir.lastModified()
    }

    private fun rowsFromCache(cache: ReportCache, kind: SecondaryKind?): List<SecondaryResult> {
        val rows = if (kind == null) {
            cache.byFilename.values.map { it.parsed }
        } else {
            cache.filenamesByKind[kind].orEmpty().mapNotNull { cache.byFilename[it]?.parsed }
        }
        return rows.sortedWith(compareBy({ it.timestamp }, { it.id }))
    }

    private fun captureSources(context: Context, row: SecondaryResult): SecondaryResult {
        if (row.sourceSnapshotId != null) return row
        val existing = get(context,row.reportId,row.id)
        existing?.sourceSnapshotId?.let { return row.copy(sourceSnapshotId=it) }
        if (existing != null && !existing.content.isNullOrBlank()) return row // legacy revision is unknown
        val manifestId=ReportEvidenceStore.sourceId(row)
        if (manifestId != null) return row.copy(sourceSnapshotId=manifestId)
        val report=ReportStorage.getReport(context,row.reportId) ?: return row
        val target=row.compareToResultId ?: row.translateSourceTargetId?.takeIf { row.translateSourceKind=="META" }
        val extra=target?.let { id -> get(context,row.reportId,id)?.content?.let { mapOf(id to it) } }.orEmpty() +
            listForReport(context,row.reportId).filter {
                !it.content.isNullOrBlank() && ((row.targetLanguage != null && it.kind==SecondaryKind.TRANSLATE && it.targetLanguage==row.targetLanguage) ||
                    (row.fanInOf != null && it.fanOutSourceAgentId != null))
            }.associate { it.id to it.content!! }
        return row.copy(sourceSnapshotId=ReportEvidenceStore.capture(report,extra))
    }

    fun save(context: Context, result: SecondaryResult): SecondaryResult {
        init(context)
        val result = captureSources(context, result)
        if (!ReportStorage.reportFileExists(context, result.reportId)) {
            AppLog.w("SecondaryResultStorage", "Skipping save for deleted report ${result.reportId}")
            throw kotlinx.coroutines.CancellationException("Report/result is no longer available")
        }
        // Defence in depth: every caller today uses UUIDs, but a future
        // regression that constructs an id with a slash or `..` would
        // otherwise write outside the per-report directory. Reject ids
        // that don't canonicalise to a child of the report dir before
        // touching the filesystem.
        if (result.id.isBlank() || result.id.contains('/') || result.id.contains('\\')
                || result.id == "." || result.id == "..") {
            AppLog.e("SecondaryResultStorage", "Refusing to save result with suspect id ${result.id}")
            throw kotlinx.coroutines.CancellationException("Report/result is no longer available")
        }
        var saved = false
        lock.withLock {
            if (!ReportStorage.reportFileExists(context, result.reportId)) {
                AppLog.w("SecondaryResultStorage", "Skipping save for deleted report ${result.reportId}")
                throw kotlinx.coroutines.CancellationException("Report/result is no longer available")
            }
            val dir = reportDir(result.reportId) ?: throw kotlinx.coroutines.CancellationException("Report/result is no longer available")
            val target = File(dir, "${result.id}.json")
            if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                AppLog.e("SecondaryResultStorage", "Refusing to save result that escapes report dir: ${result.id}")
                throw kotlinx.coroutines.CancellationException("Report/result is no longer available")
            }
            val existedBeforeSave = target.exists()
            ReportSaveRecovery.write(target, gson.toJson(result), result.reportId,
                retryLocked = { action -> lock.withLock { action() } },
                stillValid = { (!existedBeforeSave || target.exists()) && ReportStorage.reportFileExists(context, result.reportId) },
                onSaved = { forgetCachedResult(result.reportId, target.name); SecondaryDataVersion.bump(result.reportId, result.kind) })
            if (!ReportStorage.reportFileExists(context, result.reportId)) {
                target.delete()
                if (dir.listFiles()?.isEmpty() == true) dir.delete()
                AppLog.w("SecondaryResultStorage", "Removed late save for deleted report ${result.reportId}")
                forgetCachedResult(result.reportId, target.name)
                throw kotlinx.coroutines.CancellationException("Report/result is no longer available")
            }
            rememberCachedResult(result.reportId, target, result)
            saved = true
        }
        if (saved) SecondaryDataVersion.bump(result.reportId, result.kind)
        return result
    }

    /** Persist many rows under the same storage lock and bump
     *  [SecondaryDataVersion] once. Used by large batch build phases
     *  that create hundreds of placeholders up front.
     *
     *  [onProgress] (rows written so far) fires every few writes plus
     *  once at the end — the disk writes are the slow part of a batch
     *  build, so the "Preparing N / M…" popup counts THEM rather than
     *  the instant in-memory row construction. Keep the callback cheap
     *  (a StateFlow bump): it runs while the storage lock is held. */
    fun saveAll(
        context: Context,
        results: List<SecondaryResult>,
        onProgress: ((Int) -> Unit)? = null
    ): List<SecondaryResult> {
        if (results.isEmpty()) return emptyList()
        ReportWorkLimits.checkSize((results.size - 1).coerceAtLeast(0))
        init(context)
        val safeResults = results.filter { result ->
            result.id.isNotBlank() && !result.id.contains('/') && !result.id.contains('\\') &&
                result.id != "." && result.id != ".."
        }
        val currentSnapshots = mutableMapOf<String, String?>()
        val capturedResults = safeResults.map { row ->
            val existing = get(context,row.reportId,row.id)
            val sourceId = ReportEvidenceStore.sourceId(row) ?: existing?.let(ReportEvidenceStore::sourceId)
            when {
                sourceId != null -> row.copy(sourceSnapshotId=sourceId)
                // Updating a legacy result must not invent its original inputs.
                existing != null && (!existing.content.isNullOrBlank() || existing.durationMs != null || existing.errorMessage != null) -> row
                else -> row.copy(sourceSnapshotId=currentSnapshots.getOrPut(row.reportId) {
                    ReportStorage.getReport(context,row.reportId)?.let { report ->
                        ReportEvidenceStore.capture(report,listForReport(context,row.reportId)
                            .filter { !it.content.isNullOrBlank() }.associate { it.id to it.content!! })
                    }
                })
            }
        }
        if (capturedResults.isEmpty()) return emptyList()
        val reportIds = safeResults.map { it.reportId }.distinct()
        val existingReports = reportIds.filter { ReportStorage.reportFileExists(context, it) }.toSet()
        if (existingReports.isEmpty()) return emptyList()
        var savedAny = false
        val saved = mutableListOf<SecondaryResult>()
        lock.withLock {
            val liveReports = existingReports.filter { ReportStorage.reportFileExists(context, it) }.toSet()
            for (result in capturedResults) {
                if (result.reportId !in liveReports) continue
                val dir = reportDir(result.reportId) ?: continue
                val target = File(dir, "${result.id}.json")
                if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                    AppLog.e("SecondaryResultStorage", "Refusing to save result that escapes report dir: ${result.id}")
                    continue
                }
                if (!target.writeTextAtomic(gson.toJson(result))) {
                    // Already-written siblings remain visible if this batch stops.
                    if (savedAny) SecondaryDataVersion.bumpMany(saved.map { it.reportId to it.kind })
                    val existed = target.exists()
                    ReportSaveRecovery.write(target, gson.toJson(result), result.reportId,
                        retryLocked = { action -> lock.withLock { action() } },
                        stillValid = { (!existed || target.exists()) && ReportStorage.reportFileExists(context,result.reportId) },
                        onSaved = { forgetCachedResult(result.reportId,target.name); SecondaryDataVersion.bump(result.reportId,result.kind) })
                }
                rememberCachedResult(result.reportId, target, result)
                saved += result
                savedAny = true
                if (onProgress != null && saved.size % 5 == 0) onProgress(saved.size)
            }
        }
        onProgress?.invoke(saved.size)
        if (savedAny) SecondaryDataVersion.bumpMany(saved.map { it.reportId to it.kind })
        return saved
    }

    /** Construct a placeholder row and persist it. [extras] runs on the
     *  freshly-constructed [SecondaryResult] before it hits disk, so
     *  callers that need to seed `metaPromptName` / `fanOutSourceAgentId` /
     *  etc. up-front can do so atomically — no `.copy().save()` follow-up,
     *  no window where the row exists on disk in a half-baked shape that
     *  a concurrent `listForReport` could pick up and route as a plain
     *  Meta row. Default no-op preserves the old call shape. */
    fun create(
        context: Context, reportId: String, kind: SecondaryKind,
        providerId: String, model: String, agentName: String,
        extras: (SecondaryResult) -> SecondaryResult = { it }
    ): SecondaryResult {
        val r = SecondaryResult(
            id = UUID.randomUUID().toString(),
            reportId = reportId, kind = kind,
            providerId = providerId, model = model, agentName = agentName,
            timestamp = System.currentTimeMillis(), content = null
        )
        return save(context, extras(r))
    }

    fun listForReport(context: Context, reportId: String, kind: SecondaryKind? = null): List<SecondaryResult> {
        init(context)
        return lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return@withLock emptyList()
            if (!dir.exists()) return@withLock emptyList()
            val cache = cacheForReport(reportId)
            val dirMtime = dir.lastModified()
            if (!cache.loaded || cache.dirMtime != dirMtime) {
                refreshReportCacheFromDisk(dir, cache)
            }
            rowsFromCache(cache, kind).also {
                if (cache.bytes > 8L * 1024 * 1024) reportCaches.remove(reportId)
            }
        }
    }

    /** Reject a result id that could escape the report dir (separators, `.`,
     *  `..`, blank). Result ids are internal UUIDs today; this hardens the
     *  direct read/exists/delete helpers for any future caller that builds the
     *  id from a route arg or import — the write path already validates. */
    private fun isSafeResultId(resultId: String): Boolean =
        resultId.isNotBlank() && !resultId.contains('/') && !resultId.contains('\\') &&
            resultId != "." && resultId != ".."

    fun get(context: Context, reportId: String, resultId: String): SecondaryResult? {
        init(context)
        if (!isSafeResultId(resultId)) return null
        return lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return@withLock null
            val cache = cacheForReport(reportId)
            val indexedName = cache.filenameById[resultId]
            var name = indexedName ?: "$resultId.json"
            var file = File(dir, name)
            if (!file.exists() && indexedName != null) {
                cache.remove(indexedName)
                name = "$resultId.json"
                file = File(dir, name)
            }
            if (!file.exists()) return@withLock null
            // Reuse the same fingerprint-validated parse cache listForReport
            // populates, so repeated get() calls on heavy screens don't reparse
            // unchanged JSON. Same in-process invalidation + mtime/length
            // validation as listForReport.
            // See audit data bug 14.
            val mtime = file.lastModified()
            val length = file.length()
            val cached = cache.byFilename[name]
            if (cached != null && cached.mtime == mtime && cached.length == length) {
                return@withLock cached.parsed
            }
            val parsed = try { gson.fromJson(file.readText(), SecondaryResult::class.java) } catch (_: Exception) { null }
            if (parsed != null) {
                cache.put(name, CachedEntry(mtime, length, parsed))
            } else {
                cache.remove(name)
            }
            parsed
        }
    }

    /** Lock-held read path for row updates. It reuses the parsed row when
     *  our cache fingerprint still matches the file, avoiding the
     *  read+parse half of read-modify-write for hot batch rows that this
     *  process just created or updated. */
    private fun readCachedOrDisk(reportId: String, file: File): SecondaryResult? {
        val name = file.name
        val mtime = file.lastModified()
        val length = file.length()
        val cache = cacheForReport(reportId)
        val cached = cache.byFilename[name]
        if (cached != null && cached.mtime == mtime && cached.length == length) {
            return cached.parsed
        }
        val parsed = try {
            gson.fromJson(file.readText(), SecondaryResult::class.java)
        } catch (_: Exception) {
            null
        }
        if (parsed != null) {
            cache.put(name, CachedEntry(mtime, length, parsed))
        } else {
            cache.remove(name)
        }
        return parsed
    }

    /** Compound read-modify-write under one lock acquisition. Use this
     *  instead of get()+save() for field-scoped edits so concurrent cost/icon
     *  bumps cannot be overwritten by a stale full-row snapshot. */
    private fun updateResult(
        context: Context,
        reportId: String,
        resultId: String,
        mutator: (SecondaryResult) -> SecondaryResult
    ): SecondaryResult? {
        init(context)
        if (!ReportStorage.reportFileExists(context, reportId)) return null
        if (!isSafeResultId(resultId)) {
            AppLog.e("SecondaryResultStorage", "Refusing to update result with suspect id $resultId")
            return null
        }
        val updated = lock.withLock {
            if (!ReportStorage.reportFileExists(context, reportId)) return@withLock null
            val dir = resolveReportDirForRead(reportId) ?: return@withLock null
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return@withLock null
            if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                AppLog.e("SecondaryResultStorage", "Refusing to update result that escapes report dir: $resultId")
                return@withLock null
            }
            val current = readCachedOrDisk(reportId, target) ?: return@withLock null
            val next = mutator(current)
            if (!target.writeTextAtomic(gson.toJson(next))) {
                AppLog.e("SecondaryResultStorage", "Failed to update result $resultId")
                return@withLock null
            }
            rememberCachedResult(reportId, target, next)
            next
        } ?: return null
        SecondaryDataVersion.bump(updated.reportId, updated.kind)
        return updated
    }

    /** Trace-only patch, including cancelled/timed-out attempts. Never
     *  replaces concurrent title, icon, answer, or accumulated cost updates. */
    fun updateTraceFile(context: Context, reportId: String, resultId: String, traceFile: String?): Boolean =
        updateResult(context, reportId, resultId) { it.copy(traceFile = traceFile) } != null

    /** Replace a fan-out pair's in-report refine-chat conversation
     *  ([SecondaryResult.chatMessages]). Returns false when the row is
     *  gone. Leaves [content] untouched — see [updateContent] for Apply. */
    fun updateChatMessages(context: Context, reportId: String, resultId: String, messages: List<ChatMessage>): Boolean {
        return updateResult(context, reportId, resultId) { existing ->
            existing.copy(chatMessages = messages)
        } != null
    }

    /** Overwrite a fan-out pair's [SecondaryResult.content] with a chosen
     *  replacement (a fan-out pair or a plain meta row). Leaves
     *  cost/tokens and metadata untouched. */
    fun updateContent(
        context: Context,
        reportId: String,
        resultId: String,
        content: String,
        changeSource: String? = null,
        changeValue: String? = null
    ): Boolean {
        val updated = updateResult(context, reportId, resultId) { existing ->
            existing.copy(
                content = content,
                responseChangeSource = changeSource?.takeIf { it.isNotBlank() },
                responseChangeValue = changeValue?.takeIf { it.isNotBlank() }
            )
        } ?: return false
        AuditLog.append(reportId, buildString {
            append("Selected a new response for a result")
            changeSource?.takeIf { it.isNotBlank() }?.let { src ->
                append(" from $src")
                changeValue?.takeIf { it.isNotBlank() }?.let { append(" with value $it") }
            }
        })
        return updated.id == resultId
    }

    /** Re-point a secondary row at a NEW provider/model (and optionally new
     *  param presets / system prompt) and overwrite its result fields with a
     *  freshly-run candidate. Unlike [updateContent], this also rewrites the
     *  row's identity (providerId / model / agentName) and its cost / token /
     *  duration / trace, so the row reflects the new model. Powers the
     *  "Switch model / agent" Change-result action. Returns the saved row, or
     *  null when the row is gone. */
    fun updateModelSelection(
        context: Context,
        reportId: String,
        resultId: String,
        providerId: String,
        model: String,
        agentName: String,
        content: String?,
        tokenUsage: TokenUsage?,
        inputCost: Double?,
        outputCost: Double?,
        durationMs: Long?,
        traceFile: String?,
        parameterPresetIds: List<String>?,
        systemPromptId: String?,
        changeValue: String?
    ): SecondaryResult? {
        val updated = updateResult(context, reportId, resultId) { existing ->
            existing.copy(
                providerId = providerId,
                model = model,
                agentName = agentName,
                content = content,
                tokenUsage = tokenUsage,
                inputCost = inputCost,
                outputCost = outputCost,
                durationMs = durationMs,
                traceFile = traceFile,
                // The switched-in model produced this result, so any error from
                // the replaced run is gone — otherwise the detail screen's error
                // branch would keep hiding the new content (e.g. switching a
                // failed OpenAI moderation to a working Mistral one).
                errorMessage = null,
                httpStatusCode = null,
                secondaryParameterPresetIds = parameterPresetIds,
                secondarySystemPromptId = systemPromptId,
                responseChangeSource = RESPONSE_CHANGE_SOURCE_MODEL_SWITCH,
                responseChangeValue = changeValue?.takeIf { it.isNotBlank() }
            )
        } ?: return null
        AuditLog.append(reportId, "Switched a result to $providerId / $model")
        return updated
    }

    /** True when a row for [resultId] exists on disk under [reportId].
     *  Used by long-running fan-out / meta coroutines to drop their
     *  final save when the user deleted the placeholder mid-flight.
     *  Cheap on-disk check inside the same lock save / delete use. */
    fun exists(context: Context, reportId: String, resultId: String): Boolean {
        init(context)
        if (!isSafeResultId(resultId)) return false
        return lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return@withLock false
            File(dir, "$resultId.json").exists()
        }
    }

    /** Conditional save — writes only if a row for [result.id] is still
     *  on disk under [result.reportId]. Returns true on a successful
     *  write, false when the row was deleted while the caller was
     *  preparing the update (the user tapped a delete button while
     *  a fan-out / meta HTTP call was in flight). The exists-check and
     *  the write share the same lock so a concurrent
     *  [delete] / [deleteAllForReport] can't race in between. */
    /** [mergeCosts] = false writes the row's cost fields as-is instead of
     *  adding the on-disk row's prior spend. Callers that bank the prior
     *  spend into [ReportStorage.bumpCostsFromDeletedItems] before wiping
     *  a row MUST pass false — the merge would silently keep the banked
     *  spend on the row and count it twice. */
    fun saveIfStillPresent(context: Context, result: SecondaryResult, mergeCosts: Boolean = true,
                           replaceExecutionConfig: Boolean = false): Boolean {
        init(context)
        if (!ReportStorage.reportFileExists(context, result.reportId)) return false
        if (result.id.isBlank() || result.id.contains('/') || result.id.contains('\\')
                || result.id == "." || result.id == "..") {
            AppLog.e("SecondaryResultStorage", "Refusing to save result with suspect id ${result.id}")
            return false
        }
        lock.withLock {
            if (!ReportStorage.reportFileExists(context, result.reportId)) return false
            val dir = resolveReportDirForRead(result.reportId) ?: return false
            val target = File(dir, "${result.id}.json")
            if (!target.exists()) {
                // Row was deleted while the caller was running. Skip
                // the write so the user-deleted placeholder doesn't
                // resurrect from the just-completed HTTP call.
                AppLog.d("SecondaryResultStorage",
                    "saveIfStillPresent: row ${result.id} no longer on disk, skipping save")
                return false
            }
            if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                AppLog.e("SecondaryResultStorage", "Refusing to save result that escapes report dir: ${result.id}")
                return false
            }
            // Cost accumulation: re-read the on-disk row and add
            // its prior cost / token counts to the incoming result.
            // For fresh runs the prior is null/0 → additive ≡
            // overwrite (no behaviour change). For a Regenerate
            // batch re-dispatch the prior is the previous run's
            // expenditure, so the saved row reflects (prior + new).
            // Caller-controlled overwrites that don't want
            // accumulation should write via [save] instead.
            val current = readCachedOrDisk(result.reportId, target)
            val incoming = result.copy(sourceSnapshotId = current?.sourceSnapshotId ?: result.sourceSnapshotId,
                executionConfig = if (replaceExecutionConfig) result.executionConfig else current?.executionConfig ?: result.executionConfig,
                translationSourceText = current?.translationSourceText ?: result.translationSourceText)
            val toWrite = if (mergeCosts) mergeCostFromCurrent(current, incoming) else incoming
            ReportSaveRecovery.write(target, gson.toJson(toWrite), result.reportId,
                retryLocked = { action -> lock.withLock { action() } },
                stillValid = { target.exists() && ReportStorage.reportFileExists(context, result.reportId) },
                onSaved = { forgetCachedResult(result.reportId, target.name); SecondaryDataVersion.bump(result.reportId, result.kind) })
            rememberCachedResult(result.reportId, target, toWrite)
        }
        SecondaryDataVersion.bump(result.reportId, result.kind)
        return true
    }

    /** Adds prior cost counters from [existing] onto [incoming]. The result's
     *  other fields win. Returns [incoming] unchanged when no cached/disk row
     *  was available or it has no prior cost. */
    private fun mergeCostFromCurrent(existing: SecondaryResult?, incoming: SecondaryResult): SecondaryResult {
        if (existing == null) return incoming
        val priorIn = existing.inputCost ?: 0.0
        val priorOut = existing.outputCost ?: 0.0
        val priorUsage = existing.tokenUsage
        if (priorIn == 0.0 && priorOut == 0.0 &&
            (priorUsage == null || (priorUsage.totalTokens == 0 && priorUsage.apiCost == null))) {
            return incoming
        }
        val newIn = incoming.inputCost ?: 0.0
        val newOut = incoming.outputCost ?: 0.0
        val newUsage = incoming.tokenUsage
        return incoming.copy(
            inputCost = priorIn + newIn,
            outputCost = priorOut + newOut,
            tokenUsage = mergeTokenUsage(priorUsage, newUsage)
        )
    }

    private fun mergeTokenUsage(prior: TokenUsage?, incoming: TokenUsage?): TokenUsage? {
        if (prior == null && incoming == null) return null
        val apiCost = if (prior?.apiCost != null || incoming?.apiCost != null) {
            (prior?.apiCost ?: 0.0) + (incoming?.apiCost ?: 0.0)
        } else {
            null
        }
        return TokenUsage(
            inputTokens = (prior?.inputTokens ?: 0) + (incoming?.inputTokens ?: 0),
            outputTokens = (prior?.outputTokens ?: 0) + (incoming?.outputTokens ?: 0),
            apiCost = apiCost,
            cachedInputTokens = (prior?.cachedInputTokens ?: 0) + (incoming?.cachedInputTokens ?: 0),
            cacheCreationTokens = (prior?.cacheCreationTokens ?: 0) + (incoming?.cacheCreationTokens ?: 0),
            reasoningTokens = (prior?.reasoningTokens ?: 0) + (incoming?.reasoningTokens ?: 0),
            estimated = prior?.estimated == true || incoming?.estimated == true
        )
    }

    /** Atomically bumps the row's main [SecondaryResult.inputCost] /
     *  [SecondaryResult.outputCost] / token counters by the supplied
     *  amounts. Used by the Find-alternative-icons fan-out for
     *  meta-prompt + translation icons so the alt-call spend lands
     *  on the SR's own cost cell on the Report-Manage cost table
     *  (the per-row attribution requested for these flows; the
     *  cost-table subtracts the attributed alt portion when building
     *  the per-call rows below it to avoid double-counting). No-op
     *  when the row is gone (user deleted it mid-fan-out). */
    fun bumpResultInputOutputCost(
        context: Context, reportId: String, resultId: String,
        inputTokens: Int, outputTokens: Int,
        inputCost: Double, outputCost: Double
    ) {
        init(context)
        lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = readCachedOrDisk(reportId, target) ?: return
            // tokenUsage carries the row's main input/output tokens
            // for display; we update both that and the (inputCost,
            // outputCost) USD fields the cost table reads from.
            // totalTokens is a derived property — set inputTokens +
            // outputTokens here and let it recompute.
            val curTu = current.tokenUsage ?: TokenUsage(0, 0)
            val newTu = curTu.copy(
                inputTokens = curTu.inputTokens + inputTokens,
                outputTokens = curTu.outputTokens + outputTokens
            )
            val updated = current.copy(
                tokenUsage = newTu,
                inputCost = (current.inputCost ?: 0.0) + inputCost,
                outputCost = (current.outputCost ?: 0.0) + outputCost
            )
            if (!target.writeTextAtomic(gson.toJson(updated))) {
                AppLog.e("SecondaryResultStorage", "Failed to write result $resultId")
                return
            }
            rememberCachedResult(reportId, target, updated)
        }
    }

    /** Atomically bumps the per-pair icon-chain cost counters on the
     *  [resultId] row under [reportId]. No-op when the row is gone
     *  (user deleted the pair mid-chain) so a late-arriving tier
     *  doesn't resurrect a deleted placeholder. Mirrors
     *  [ReportStorage.bumpReportAgentIconCost] — same atomic-write
     *  shape, same per-tier accumulation. */
    fun bumpFanOutIconCost(
        context: Context, reportId: String, resultId: String,
        inputTokens: Int, outputTokens: Int,
        inputCost: Double, outputCost: Double
    ) {
        init(context)
        lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = readCachedOrDisk(reportId, target) ?: return
            val updated = current.copy(
                iconInputTokens = current.iconInputTokens + inputTokens,
                iconOutputTokens = current.iconOutputTokens + outputTokens,
                iconInputCost = current.iconInputCost + inputCost,
                iconOutputCost = current.iconOutputCost + outputCost
            )
            if (!target.writeTextAtomic(gson.toJson(updated))) {
                AppLog.e("SecondaryResultStorage", "Failed to write result $resultId")
                return
            }
            rememberCachedResult(reportId, target, updated)
        }
    }

    /** Final commit step of the fan-out icon chain — stamps [icon] +
     *  [winningTier] on the row, leaving the cost / token counters
     *  bumped by earlier [bumpFanOutIconCost] calls intact. No-op
     *  when the row was deleted while the chain ran. */
    fun setFanOutIconAndTier(
        context: Context, reportId: String, resultId: String,
        icon: String, winningTier: Int?, iconRunId: String? = null,
        /** Bundled prompt that produced [icon] — "fan_out_1" for
         *  tier 1, "fan_out_2" for tier 2, "fan_out_3" for tier 3
         *  (or "fan_out_alt" after a future Find-alt pick).
         *  Surfaces on the Icon lookup screen. */
        promptUsed: String? = null
    ) {
        init(context)
        lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = readCachedOrDisk(reportId, target) ?: return
            val updated = current.copy(
                icon = icon,
                iconWinningTier = winningTier,
                iconErrorMessage = null,
                iconRunId = iconRunId ?: current.iconRunId,
                iconPromptUsed = promptUsed ?: current.iconPromptUsed
            )
            if (!target.writeTextAtomic(gson.toJson(updated))) {
                AppLog.e("SecondaryResultStorage", "Failed to write result $resultId")
                return
            }
            rememberCachedResult(reportId, target, updated)
        }
    }

    /** Stamp an icon-chain failure on the pair row. Called when
     *  every tier of the Fan Meta chain has failed. Mirrors
     *  [setFanOutIconAndTier] but writes [iconErrorMessage] only.
     *  Restart / retry paths clear it by writing a successful
     *  icon back. */
    fun setFanOutIconError(
        context: Context, reportId: String, resultId: String,
        errorMessage: String,
        iconRunId: String? = null,
        promptUsed: String? = null
    ) {
        init(context)
        lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = readCachedOrDisk(reportId, target) ?: return
            val updated = current.copy(
                iconErrorMessage = errorMessage,
                iconRunId = iconRunId ?: current.iconRunId,
                iconPromptUsed = promptUsed ?: current.iconPromptUsed
            )
            if (!target.writeTextAtomic(gson.toJson(updated))) {
                AppLog.e("SecondaryResultStorage", "Failed to write result $resultId")
                return
            }
            rememberCachedResult(reportId, target, updated)
        }
    }

    /** Persist evidence that a Fan Meta batch has started for this row before
     *  the worker call returns. If the app dies before the first completion,
     *  Broken-work can still distinguish "Fan Meta was interrupted" from
     *  "Fan Meta was never requested". */
    fun markFanOutFanMetaStarted(
        context: Context,
        reportId: String,
        resultId: String,
        fanMetaRunId: String,
        promptUsed: String = "fan-meta"
    ) {
        init(context)
        lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = readCachedOrDisk(reportId, target) ?: return
            val updated = current.copy(
                titleRunId = current.titleRunId ?: fanMetaRunId,
                iconRunId = current.iconRunId ?: fanMetaRunId,
                titlePromptUsed = current.titlePromptUsed ?: promptUsed,
                iconPromptUsed = current.iconPromptUsed ?: promptUsed,
                timestamp = System.currentTimeMillis()
            )
            if (!target.writeTextAtomic(gson.toJson(updated))) {
                AppLog.e("SecondaryResultStorage", "Failed to write result $resultId")
                return
            }
            rememberCachedResult(reportId, target, updated)
        }
        SecondaryDataVersion.bump(reportId, SecondaryKind.META)
    }

    /** Set just the [icon] field on any SecondaryResult row by
     *  (reportId, resultId) — minimal mirror of [setFanOutIconAndTier]
     *  with no tier / cost / promptUsed plumbing. Used by the per-row
     *  Meta-tile alt-icon pick flow (View screen) so two tiles that
     *  share a metaPromptName can carry distinct icons; the per-row
     *  override takes precedence over the shared
     *  [InternalPromptIconCache] entry. No-op when the row is gone. */
    fun setRowIcon(
        context: Context, reportId: String, resultId: String,
        icon: String?
    ) {
        init(context)
        lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = readCachedOrDisk(reportId, target) ?: return
            val updated = current.copy(icon = icon)
            if (!target.writeTextAtomic(gson.toJson(updated))) {
                AppLog.e("SecondaryResultStorage", "Failed to write result $resultId")
                return
            }
            rememberCachedResult(reportId, target, updated)
        }
    }

    /** Regenerate-batch variant of [clearFanOutIconState] — clears
     *  the icon + iconErrorMessage + winning tier so the row
     *  re-reads as "icon-less" (FAN_META phase will re-fire the
     *  chain) BUT preserves icon* cost / token counters. The
     *  dispatcher's additive cost write adds the new chain's
     *  expenditure onto the prior. */
    fun clearFanOutIconStateKeepingCost(
        context: Context, reportId: String, resultId: String
    ) {
        init(context)
        lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = readCachedOrDisk(reportId, target) ?: return
            val updated = current.copy(
                icon = null,
                iconWinningTier = null,
                iconErrorMessage = null,
                // Clear iconRunId too (like clearFanOutIconState): the
                // report-open + 30 s resume detectors treat a non-null
                // iconRunId as "a Fan Meta batch was started here", which is
                // what made a deleted/regenerated Fan Meta reappear.
                iconRunId = null
            )
            if (!target.writeTextAtomic(gson.toJson(updated))) {
                AppLog.e("SecondaryResultStorage", "Failed to write result $resultId")
                return
            }
            rememberCachedResult(reportId, target, updated)
        }
    }

    /** Drop any prior icon / error state from the pair row so a
     *  re-launched Fan Meta batch starts clean for these pairs.
     *  Leaves the row's main response untouched. */
    fun clearFanOutIconState(
        context: Context, reportId: String, resultId: String
    ) {
        init(context)
        lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = readCachedOrDisk(reportId, target) ?: return
            val updated = current.copy(
                icon = null,
                iconWinningTier = null,
                iconErrorMessage = null,
                // iconRunId too: the report-open + 30 s resume detectors treat a
                // non-null iconRunId as "a Fan Meta batch was started here" and
                // relaunch it. Leaving it set is what made a deleted Fan Meta
                // reappear (as a regenerated icon row) on every Manage open —
                // the same trap the title clear already guards against.
                iconRunId = null,
                iconInputTokens = 0,
                iconOutputTokens = 0,
                iconInputCost = 0.0,
                iconOutputCost = 0.0
            )
            if (!target.writeTextAtomic(gson.toJson(updated))) {
                AppLog.e("SecondaryResultStorage", "Failed to write result $resultId")
                return
            }
            rememberCachedResult(reportId, target, updated)
        }
    }

    // ---- Per-pair TITLE state (single-tier Fan Meta batch) ----
    // Parallel to the icon setters above; no "winning tier" since the
    // title batch runs a single chat-continuation call per pair.

    /** Add to the per-pair title cost / token counters. No-op when the
     *  row was deleted mid-call. Mirrors [bumpFanOutIconCost]. */
    fun bumpFanOutTitleCost(
        context: Context, reportId: String, resultId: String,
        inputTokens: Int, outputTokens: Int,
        inputCost: Double, outputCost: Double,
        model: String? = null
    ) {
        init(context)
        lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = readCachedOrDisk(reportId, target) ?: return
            val updated = current.copy(
                titleInputTokens = current.titleInputTokens + inputTokens,
                titleOutputTokens = current.titleOutputTokens + outputTokens,
                titleInputCost = current.titleInputCost + inputCost,
                titleOutputCost = current.titleOutputCost + outputCost,
                titleModel = model ?: current.titleModel
            )
            if (!target.writeTextAtomic(gson.toJson(updated))) {
                AppLog.e("SecondaryResultStorage", "Failed to write result $resultId")
                return
            }
            rememberCachedResult(reportId, target, updated)
        }
    }

    /** Stamp the generated [title] on the row (clears any prior error),
     *  leaving cost counters bumped by [bumpFanOutTitleCost] intact.
     *  No-op when the row was deleted while the batch ran. */
    fun setFanOutTitle(
        context: Context, reportId: String, resultId: String,
        title: String, titleRunId: String? = null, promptUsed: String? = null,
        durationMs: Long? = null,
        model: String? = null
    ) {
        init(context)
        lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = readCachedOrDisk(reportId, target) ?: return
            val updated = current.copy(
                title = title,
                titleErrorMessage = null,
                titleRunId = titleRunId ?: current.titleRunId,
                titlePromptUsed = promptUsed ?: current.titlePromptUsed,
                titleDurationMs = durationMs ?: current.titleDurationMs,
                titleModel = model ?: current.titleModel
            )
            if (!target.writeTextAtomic(gson.toJson(updated))) {
                AppLog.e("SecondaryResultStorage", "Failed to write result $resultId")
                return
            }
            rememberCachedResult(reportId, target, updated)
        }
    }

    /** Stamp a title-batch failure on the pair row. Mirrors
     *  [setFanOutIconError]; retry clears it by writing a title back. */
    fun setFanOutTitleError(
        context: Context, reportId: String, resultId: String,
        errorMessage: String,
        titleRunId: String? = null,
        promptUsed: String? = null,
        model: String? = null
    ) {
        init(context)
        lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = readCachedOrDisk(reportId, target) ?: return
            val updated = current.copy(
                titleErrorMessage = errorMessage,
                titleRunId = titleRunId ?: current.titleRunId,
                titlePromptUsed = promptUsed ?: current.titlePromptUsed,
                titleModel = model ?: current.titleModel
            )
            if (!target.writeTextAtomic(gson.toJson(updated))) {
                AppLog.e("SecondaryResultStorage", "Failed to write result $resultId")
                return
            }
            rememberCachedResult(reportId, target, updated)
        }
    }

    internal fun repairFanMetaRow(context: Context, baseline: SecondaryResult, attempts: List<FanMetaAttempt>) {
        init(context)
        lock.withLock {
            val dir = resolveReportDirForRead(baseline.reportId) ?: return
            val target = File(dir, "${baseline.id}.json")
            val row = readCachedOrDisk(baseline.reportId, target) ?: return
            // Do not alter metadata replaced while the historical traces were read.
            if (row.titleRunId != baseline.titleRunId || row.title != baseline.title || row.icon != baseline.icon) return
            val added = attempts.filter { a -> row.fanMetaAttempts.orEmpty().none { it.id == a.id || it.traceFile == a.traceFile } }
            val rejected = added.filterNot { it.accepted }
            val updated = row.copy(
                title = row.title?.let { FanMetaFormat.cleanTitle(it).takeIf(String::isNotBlank) ?: it },
                fanMetaAttempts = row.fanMetaAttempts.orEmpty() + added,
                // The historical winner was already charged to title fields.
                titleInputCost = row.titleInputCost + rejected.sumOf { it.inputCost },
                titleOutputCost = row.titleOutputCost + rejected.sumOf { it.outputCost },
                titleInputTokens = row.titleInputTokens + rejected.sumOf { it.inputTokens },
                titleOutputTokens = row.titleOutputTokens + rejected.sumOf { it.outputTokens }
            )
            if (updated == row) return
            ReportSaveRecovery.write(target, gson.toJson(updated), baseline.reportId,
                retryLocked = { action -> lock.withLock { action() } }, stillValid = { target.exists() },
                onSaved = { forgetCachedResult(baseline.reportId, target.name); SecondaryDataVersion.bump(baseline.reportId, SecondaryKind.META) })
            rememberCachedResult(baseline.reportId, target, updated)
        }
    }

    /** Save each attempt before fallback or cancellation can discard its trace/cost. */
    fun recordFanMetaAttempt(context: Context, reportId: String, resultId: String, attempt: FanMetaAttempt) {
        init(context)
        lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return
            val target = File(dir, "$resultId.json")
            val current = readCachedOrDisk(reportId, target) ?: return
            if (current.fanMetaAttempts.orEmpty().any { it.id == attempt.id }) return
            val updated = current.copy(
                fanMetaAttempts = current.fanMetaAttempts.orEmpty() + attempt,
                titleInputTokens = current.titleInputTokens + attempt.inputTokens,
                titleOutputTokens = current.titleOutputTokens + attempt.outputTokens,
                titleInputCost = current.titleInputCost + attempt.inputCost,
                titleOutputCost = current.titleOutputCost + attempt.outputCost
            )
            ReportSaveRecovery.write(target, gson.toJson(updated), reportId,
                retryLocked = { action -> lock.withLock { action() } },
                stillValid = { target.exists() },
                onSaved = { forgetCachedResult(reportId, target.name); SecondaryDataVersion.bump(reportId, SecondaryKind.META) })
            rememberCachedResult(reportId, target, updated)
        }
    }

    /** Commit one Fan Meta worker result in a single row rewrite. The
     *  legacy path wrote cost, title, and icon/error as separate atomic
     *  writes per pair; large Fan Meta batches spent most of their time
     *  fsyncing those transitions. */
    fun recordFanMetaResult(
        context: Context,
        reportId: String,
        resultId: String,
        title: String?,
        icon: String?,
        inputTokens: Int,
        outputTokens: Int,
        inputCost: Double,
        outputCost: Double,
        titleRunId: String,
        iconRunId: String,
        promptUsed: String,
        durationMs: Long,
        titleModel: String? = null,
        titleErrorMessage: String? = null,
        iconErrorMessage: String? = null
    ) {
        init(context)
        lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = readCachedOrDisk(reportId, target) ?: return
            val updated = current.copy(
                title = title?.takeIf { it.isNotBlank() },
                titleErrorMessage = titleErrorMessage,
                titleRunId = titleRunId,
                titlePromptUsed = promptUsed,
                titleDurationMs = durationMs,
                titleModel = titleModel ?: current.titleModel,
                titleInputTokens = current.titleInputTokens + inputTokens,
                titleOutputTokens = current.titleOutputTokens + outputTokens,
                titleInputCost = current.titleInputCost + inputCost,
                titleOutputCost = current.titleOutputCost + outputCost,
                icon = icon?.takeIf { it.isNotBlank() },
                iconWinningTier = null,
                iconErrorMessage = iconErrorMessage,
                iconRunId = iconRunId,
                iconPromptUsed = promptUsed
            )
            if (!target.writeTextAtomic(gson.toJson(updated))) {
                AppLog.e("SecondaryResultStorage", "Failed to write result $resultId")
                return
            }
            rememberCachedResult(reportId, target, updated)
        }
        SecondaryDataVersion.bump(reportId, SecondaryKind.META)
    }

    /** Mark many fan-out rows as having a Fan Meta pass in progress.
     *  Writes still happen per row for crash visibility, but the lock
     *  acquisition and data-version notification are batched.
     *  [onProgress] (rows written so far) mirrors [saveAll]'s — the
     *  build popup counts the writes, the slow part. */
    fun markFanOutFanMetaStartedBatch(
        context: Context,
        reportId: String,
        resultIds: Collection<String>,
        fanMetaRunId: String,
        promptUsed: String = "fan-meta",
        onProgress: ((Int) -> Unit)? = null
    ) {
        if (resultIds.isEmpty()) return
        init(context)
        var changed = false
        var written = 0
        lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return
            for (resultId in resultIds) {
                if (!isSafeResultId(resultId)) continue
                val target = File(dir, "$resultId.json")
                if (!target.exists()) continue
                val current = readCachedOrDisk(reportId, target) ?: continue
                val updated = current.copy(
                    titleRunId = fanMetaRunId,
                    iconRunId = fanMetaRunId,
                    titlePromptUsed = current.titlePromptUsed ?: promptUsed,
                    iconPromptUsed = current.iconPromptUsed ?: promptUsed,
                    timestamp = System.currentTimeMillis()
                )
                if (!target.writeTextAtomic(gson.toJson(updated))) {
                    AppLog.e("SecondaryResultStorage", "Failed to write result $resultId")
                    continue
                }
                rememberCachedResult(reportId, target, updated)
                changed = true
                written++
                if (onProgress != null && written % 5 == 0) onProgress(written)
            }
        }
        onProgress?.invoke(written)
        if (changed) SecondaryDataVersion.bump(reportId, SecondaryKind.META)
    }

    /** Regenerate variant — clears [title] + [titleErrorMessage] but
     *  preserves the title cost / token counters. Mirrors
     *  [clearFanOutIconStateKeepingCost]. */
    fun clearFanOutTitleStateKeepingCost(
        context: Context, reportId: String, resultId: String
    ) {
        init(context)
        lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = readCachedOrDisk(reportId, target) ?: return
            // Clear titleRunId too (like clearFanOutTitleState): a non-null
            // titleRunId is read by the report-open + 30 s resume detectors as
            // "a titles batch was started here", resurrecting a deleted/
            // regenerated Fan Meta title.
            val updated = current.copy(title = null, titleErrorMessage = null, titleRunId = null)
            if (!target.writeTextAtomic(gson.toJson(updated))) {
                AppLog.e("SecondaryResultStorage", "Failed to write result $resultId")
                return
            }
            rememberCachedResult(reportId, target, updated)
        }
    }

    /** Drop any prior title / error / cost so a re-launched Fan Meta
     *  batch starts clean. Mirrors [clearFanOutIconState]. */
    fun clearFanOutTitleState(
        context: Context, reportId: String, resultId: String
    ) {
        init(context)
        lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = readCachedOrDisk(reportId, target) ?: return
            val updated = current.copy(
                title = null,
                titleErrorMessage = null,
                // titleRunId too: the report-open + 30s resume detectors
                // treat a non-null titleRunId as "a titles batch was
                // started here" and relaunch it. Leaving it set is what
                // made a deleted Fan Meta run reappear on every Manage
                // open, even after the cancel-join race was closed.
                titleRunId = null,
                titleInputTokens = 0,
                titleOutputTokens = 0,
                titleInputCost = 0.0,
                titleOutputCost = 0.0,
                titleModel = null,
                fanMetaAttempts = emptyList()
            )
            if (!target.writeTextAtomic(gson.toJson(updated))) {
                AppLog.e("SecondaryResultStorage", "Failed to write result $resultId")
                return
            }
            rememberCachedResult(reportId, target, updated)
        }
    }

    /** Reset a row to "stale placeholder" — clear content,
     *  errorMessage, durationMs so the resume-stale path picks
     *  it up and re-dispatches. Preserves cost / tokenUsage —
     *  the dispatcher's [saveIfStillPresent] adds the new
     *  call's cost onto the prior. Leaves prompt / model /
     *  scope / metaPromptName fields intact so the dispatcher
     *  can reconstruct the call. Used by
     *  [com.ai.viewmodel.RegenerateBatchEngine] when a phase
     *  starts. No-op when the row is gone. */
    fun resetRowToPlaceholder(context: Context, reportId: String, resultId: String) {
        init(context)
        lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = readCachedOrDisk(reportId, target) ?: return
            val updated = current.copy(
                content = null,
                errorMessage = null,
                durationMs = null,
                // Bump timestamp so the dispatcher sees a fresh row.
                timestamp = System.currentTimeMillis()
            )
            if (!target.writeTextAtomic(gson.toJson(updated))) {
                AppLog.e("SecondaryResultStorage", "Failed to write result $resultId")
                return
            }
            rememberCachedResult(reportId, target, updated)
        }
    }

    /** Commit a tournament MATCH worker call: overwrite the row's
     *  provider/model (was the pre-judge sentinel) with the winning worker,
     *  plus the verdict [content], cost and duration — atomically, in one
     *  write. No-op when the row was deleted mid-call. Used by
     *  [com.ai.viewmodel.TournamentEngine]. */
    fun recordTournamentMatch(
        context: Context, reportId: String, resultId: String,
        providerId: String, model: String, content: String,
        inputTokens: Int, outputTokens: Int,
        inputCost: Double, outputCost: Double, durationMs: Long,
        traceFile: String? = null,
        /** Full usage to persist when the caller has it (TransRank passes the
         *  whole [TokenUsage] so cached / cache-creation / reasoning tokens and
         *  the API-reported cost survive). When null, the row keeps the legacy
         *  input/output-only shape built from [inputTokens] / [outputTokens]. */
        tokenUsage: TokenUsage? = null
    ) {
        init(context)
        lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = readCachedOrDisk(reportId, target) ?: return
            // Additive cost like [saveIfStillPresent]: a Regenerate batch
            // resets MATCH rows to placeholders KEEPING their cost on the
            // engine's documented assumption that this write adds the new
            // call's spend onto it — a plain replace silently dropped the
            // whole prior tournament's expenditure from the row + lifetime
            // totals. Rerun paths bank-then-clear first, and fresh rows
            // carry no prior cost, so both stay overwrite-equivalent.
            val updated = mergeCostFromCurrent(current, current.copy(
                providerId = providerId,
                model = model,
                agentName = "$providerId / $model",
                content = content,
                errorMessage = null,
                tokenUsage = tokenUsage ?: TokenUsage(inputTokens = inputTokens, outputTokens = outputTokens),
                inputCost = inputCost,
                outputCost = outputCost,
                durationMs = durationMs,
                traceFile = traceFile ?: current.traceFile
            ))
            if (!target.writeTextAtomic(gson.toJson(updated))) {
                AppLog.e("SecondaryResultStorage", "Failed to write result $resultId")
                return
            }
            rememberCachedResult(reportId, target, updated)
        }
        SecondaryDataVersion.bump(reportId, SecondaryKind.TOURNAMENT)
    }

    /** Commit a "Compare with meta" CELL worker call: overwrite the row's
     *  provider/model (was the pre-judge sentinel) with the winning worker,
     *  plus the percentage [content], cost and duration — atomically. No-op
     *  when the row was deleted mid-call. Generic body identical to
     *  [recordTournamentMatch]; kept separate for call-site clarity. Used by
     *  [com.ai.viewmodel.CompareEngine]. */
    fun recordCompareCell(
        context: Context, reportId: String, resultId: String,
        providerId: String, model: String, content: String,
        inputTokens: Int, outputTokens: Int,
        inputCost: Double, outputCost: Double, durationMs: Long,
        traceFile: String? = null
    ) {
        init(context)
        lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = readCachedOrDisk(reportId, target) ?: return
            // Additive cost — same rationale as [recordTournamentMatch].
            val updated = mergeCostFromCurrent(current, current.copy(
                providerId = providerId,
                model = model,
                agentName = "$providerId / $model",
                content = content,
                errorMessage = null,
                tokenUsage = TokenUsage(inputTokens = inputTokens, outputTokens = outputTokens),
                inputCost = inputCost,
                outputCost = outputCost,
                durationMs = durationMs,
                traceFile = traceFile ?: current.traceFile
            ))
            if (!target.writeTextAtomic(gson.toJson(updated))) {
                AppLog.e("SecondaryResultStorage", "Failed to write result $resultId")
                return
            }
            rememberCachedResult(reportId, target, updated)
        }
        SecondaryDataVersion.bump(reportId, SecondaryKind.COMPARE)
    }

    fun delete(context: Context, reportId: String, resultId: String) {
        init(context)
        if (!isSafeResultId(resultId)) return
        lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return
            val target = File(dir, "$resultId.json")
            target.delete()
            // Drop only this file's cache entry — the remaining files
            // for the report stay parsed and ready for the next read.
            forgetCachedResult(reportId, target.name)
        }
        SecondaryDataVersion.bumpReport(reportId)
    }

    fun deleteAllForReport(context: Context, reportId: String) {
        init(context)
        lock.withLock {
            val dir = resolveReportDirForRead(reportId) ?: return
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
            // Whole report gone — drop the entire per-report bucket.
            reportCaches.remove(reportId)
        }
        SecondaryDataVersion.bumpReport(reportId)
    }

    /** Counts persisted across all kinds for a report. Used by the Report
     *  result screen for the Translate / legacy buckets. The redesigned
     *  Meta card uses [countByMetaName] instead. */
    data class Counts(val rerank: Int, val meta: Int, val moderation: Int, val translate: Int, val tournament: Int = 0, val judges: Int = 0, val compare: Int = 0, val transrank: Int = 0)
    fun countForReport(context: Context, reportId: String): Counts {
        // Delegate to listForReport so we share its fingerprint cache —
        // the previous implementation re-parsed every JSON file on
        // every call even when the per-report list was already in
        // memory and unchanged. Fan out drill-in polls this every
        // 500 ms while batching, so the redundant parses scaled with
        // (file count × poll rate) for no benefit.
        val rows = listForReport(context, reportId)
        var rerank = 0; var meta = 0; var moderation = 0; var translate = 0; var tournament = 0; var judges = 0; var compare = 0; var transrank = 0
        for (r in rows) {
            when (r.kind) {
                SecondaryKind.RERANK -> rerank++
                SecondaryKind.META -> meta++
                SecondaryKind.MODERATION -> moderation++
                SecondaryKind.TRANSLATE -> translate++
                // Count only the aggregate ranking rows — the N(N-1)
                // per-match rows are inspection detail, not standalone
                // results the result-screen buckets care about.
                SecondaryKind.TOURNAMENT -> if (r.tournamentRole == "AGGREGATE") tournament++
                SecondaryKind.JUDGES -> if (r.tournamentRole == "AGGREGATE") judges++
                // Compare cells collapse into the single CompareManageRow
                // drill-in; count them flat for parity with the other kinds.
                SecondaryKind.COMPARE -> compare++
                // Translator-rank: count only the aggregate ranking row (per language).
                SecondaryKind.TRANSRANK -> if (r.tournamentRole == TRANSRANK_ROLE_AGGREGATE) transrank++
            }
        }
        return Counts(rerank, meta, moderation, translate, tournament, judges, compare, transrank)
    }

    /** Group non-translate Meta results on a report by the user-given
     *  Meta prompt name, returning name → count. Legacy rows (written
     *  before metaPromptName existed) fall back to a kind-derived
     *  label so the View card keeps a stable history. */
    fun countByMetaName(context: Context, reportId: String): Map<String, Int> {
        // Build the tally from listForReport so it shares the per-report
        // fingerprint cache (Bug 33) instead of re-parsing every JSON file
        // on each call — fan-out drill-in polls this frequently.
        val tally = LinkedHashMap<String, Int>()
        for (r in listForReport(context, reportId)) {
            // Tournament rows carry their own View tab; keep them out of
            // the Meta-name grouping (they'd otherwise bucket under
            // "tournament" via the shared prompt name).
            if (r.kind == SecondaryKind.TRANSLATE || r.kind == SecondaryKind.TOURNAMENT || r.kind == SecondaryKind.JUDGES || r.kind == SecondaryKind.COMPARE) continue
            val name = r.metaPromptName?.takeIf { it.isNotBlank() } ?: legacyKindDisplayName(r.kind)
            tally[name] = (tally[name] ?: 0) + 1
        }
        return tally
    }
}

/** Display label for a [SecondaryKind]. Only ever shown when a row
 *  doesn't carry a `metaPromptName` — every UI surface prefers the
 *  user-given Meta prompt name. */
fun legacyKindDisplayName(kind: SecondaryKind): String = when (kind) {
    SecondaryKind.RERANK -> "Rerank"
    SecondaryKind.META -> "Meta"
    SecondaryKind.MODERATION -> "Moderation"
    SecondaryKind.TRANSLATE -> "Translate"
    SecondaryKind.TOURNAMENT -> "Tournament"
    SecondaryKind.JUDGES -> "Judge the judges"
    SecondaryKind.COMPARE -> "Compare"
    SecondaryKind.TRANSRANK -> "Rank the translators"
}

/** Display label for a secondary's prompt name. The built-in rerank /
 *  moderation runs are stored under their internal asset prompt names
 *  ("second-rerank" / "second-moderation"); show them as plain
 *  "rerank" / "moderation". User-authored Meta prompt names pass through. */
fun secondaryPromptDisplayName(metaPromptName: String): String = when (metaPromptName) {
    "second-rerank" -> "rerank"
    "second-moderation" -> "moderation"
    "second-tournament" -> "tournament"
    else -> metaPromptName
}

/** Substitutes placeholders in [template] using the values for the
 *  current secondary-result run. `@RESULTS@` arrives pre-formatted
 *  from the caller — we only do plain string replace here. */
fun resolveSecondaryPrompt(
    template: String, question: String, results: String, count: Int,
    title: String? = null
): String {
    val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
    return template
        .replace("@QUESTION@", question)
        .replace("@RESULTS@", results)
        .replace("@COUNT@", count.toString())
        .replace("@DATE@", now)
        .replace("@TITLE@", title ?: "")
}

/** Substitutes placeholders for a fan-in run.
 *  Top-level placeholders (@QUESTION@, @TITLE@, @DATE@, @COUNT@,
 *  @FAN_OUT_COUNT@) are plain string replaces. The iterable block
 *  `\n\n***Report*** @REPORT@@RESPONSES@` is found once in the template
 *  and expanded N times — one per (reportBody, responses) entry —
 *  with @RESPONSE@ inside @RESPONSES@ replaced by each fan-out response
 *  content. */
fun resolveFanInPrompt(
    template: String,
    question: String,
    count: Int,
    fanOutCount: Int,
    perReport: List<Pair<String, List<String>>>,
    title: String? = null
): String {
    val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
    val withTopLevel = template
        .replace("@QUESTION@", question)
        .replace("@TITLE@", title ?: "")
        .replace("@DATE@", now)
        .replace("@COUNT@", count.toString())
        .replace("@FAN_OUT_COUNT@", fanOutCount.toString())
    // Whitespace-tolerant detection of the iterable ***Report*** block.
    // Previously matched a literal "\n\n***Report*** @REPORT@@RESPONSES@",
    // so a user editing the prompt template in Internal Prompts and
    // adjusting whitespace by even a single character broke the
    // expansion silently and the structured Report/Response framing
    // was lost. The regex matches *** Report *** with optional
    // surrounding whitespace and the two placeholders adjacent or
    // separated by whitespace.
    val iterableRegex = Regex("""\s*\*\*\*\s*Report\s*\*\*\*\s*@REPORT@\s*@RESPONSES@\s*""")
    val expansion = buildString {
        perReport.forEach { (reportBody, responses) ->
            append("\n\n***Report*** ").append(reportBody)
            responses.forEach { fc -> append("\n\n***Response*** ").append(fc) }
        }
    }
    val match = iterableRegex.find(withTopLevel)
    val expanded = if (match != null) {
        withTopLevel.substring(0, match.range.first) + expansion + withTopLevel.substring(match.range.last + 1)
    } else {
        withTopLevel
            .replace("@REPORT@", "")
            .replace("@RESPONSES@", "")
    }
    return expanded
        .replace("@REPORT@", "")
        .replace("@RESPONSES@", "")
        .replace("@RESPONSE@", "")
}

/** Build the @RESULTS@ block: per-agent text, prefixed only with the
 *  bracketed `[N]` id (no provider / model identifiers — those reach
 *  the user via the appended Compare legend, not the prompt). The
 *  bracketed N is the stable id rerank models echo back — and the
 *  anchor target HTML export wires up links to.
 *
 *  [includeIds] (1-based) restricts the block to a subset of the success-
 *  ordered agent list while *preserving the original numbering*. The
 *  rest of the system (HTML anchors, secondary-result link rewriting)
 *  keys on the original ids, so passing [4, 1, 7] correctly emits
 *  blocks `[1] [4] [7]` in their original-success order. */
fun buildResultsBlock(report: Report, includeIds: Set<Int>? = null): String {
    val sb = StringBuilder()
    val successful = report.agents.filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
    var emitted = 0
    val total = if (includeIds != null) successful.indices.count { (it + 1) in includeIds } else successful.size
    successful.forEachIndexed { idx, agent ->
        val originalId = idx + 1
        if (includeIds != null && originalId !in includeIds) return@forEachIndexed
        sb.append("[").append(originalId).append("]\n")
        sb.append(agent.responseBody?.trim() ?: "")
        emitted++
        if (emitted != total) sb.append("\n\n")
    }
    return sb.toString()
}

/** The success-ordered agentIds [buildResultsBlock] numbers 1-based —
 *  stamped onto RERANK / MODERATION rows at run time (see
 *  [SecondaryResult.sourceAgentIds]) so their detail screens keep
 *  resolving [N] correctly after the report's agent set changes. */
fun successOrderedAgentIds(report: Report): List<String> =
    report.agents
        .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
        .map { it.agentId }

/** Resolve a TopRanked scope's rerank `[N]` positions to the CURRENT-success
 *  agents they name. Each position is mapped through the rerank row's frozen
 *  [SecondaryResult.sourceAgentIds] snapshot (the success order AT RERANK
 *  time) to an agentId, then to the still-present current-success agent — so
 *  a success-set change since the rerank (an agent removed, a failed→SUCCESS
 *  regenerate inserting into the list) doesn't silently reselect a DIFFERENT
 *  model at the same position. Mirrors how `Manual` scope keys on stable
 *  agentIds. Legacy rerank rows written before the snapshot existed fall back
 *  to the old direct-position interpretation (positions into the current
 *  success set). [successful] must be the current success-nonblank agents in
 *  [buildResultsBlock] order. */
fun resolveTopRankedAgents(rerankRow: SecondaryResult?, count: Int, successful: List<ReportAgent>): List<ReportAgent> {
    val positions = extractTopRankedIds(rerankRow?.content, count) ?: return emptyList()
    val snapshot = rerankRow?.sourceAgentIds
    return if (!snapshot.isNullOrEmpty()) {
        positions.mapNotNull { p -> snapshot.getOrNull(p - 1) }               // agentIds frozen at rerank time
            .mapNotNull { aid -> successful.firstOrNull { it.agentId == aid } } // still-present current agents
    } else {
        positions.mapNotNull { p -> successful.getOrNull(p - 1) }             // legacy: positions into current success
    }
}

/** Resolve the 1-based [N] ids a rerank/moderation row's content
 *  references back to "provider / model" labels. Prefers the row's
 *  run-time [SecondaryResult.sourceAgentIds] snapshot — resolving
 *  through the report's CURRENT success set silently mislabels every
 *  row once an agent removal or a failed→success regenerate shifts
 *  the numbering. Rows written before the snapshot existed fall back
 *  to the current-set map (drift risk, but nothing better exists). */
fun sourceAgentLabels(report: Report, row: SecondaryResult): Map<Int, String> {
    val report = ReportEvidenceStore.historicalReport(report,row)
    val snapshot = row.sourceAgentIds
    if (!snapshot.isNullOrEmpty()) {
        val byId = report.agents.associateBy { it.agentId }
        return snapshot.mapIndexed { idx, id ->
            val agent = byId[id]
            val label = if (agent != null) {
                val provDisplay = AppService.findById(agent.provider)?.id ?: agent.provider
                "$provDisplay / ${agent.model}"
            } else "(removed model)"
            (idx + 1) to label
        }.toMap()
    }
    return report.agents
        .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
        .mapIndexed { idx, agent ->
            val provDisplay = AppService.findById(agent.provider)?.id ?: agent.provider
            (idx + 1) to "$provDisplay / ${agent.model}"
        }.toMap()
}

/** Companion to [sourceAgentLabels]: the same 1-based ids resolved to
 *  the agents' response bodies (the exact text a moderation call ran
 *  on). A removed agent's body is gone — empty string. */
fun sourceAgentResponses(report: Report, row: SecondaryResult): Map<Int, String> {
    val report = ReportEvidenceStore.historicalReport(report,row)
    val snapshot = row.sourceAgentIds
    if (!snapshot.isNullOrEmpty()) {
        val byId = report.agents.associateBy { it.agentId }
        return snapshot.mapIndexed { idx, id ->
            (idx + 1) to (byId[id]?.responseBody ?: "")
        }.toMap()
    }
    return report.agents
        .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
        .mapIndexed { idx, agent -> (idx + 1) to (agent.responseBody ?: "") }
        .toMap()
}

/** Build the reference legend appended to a chat-type Meta-prompt
 *  result when its `reference` flag is true. Mirrors
 *  [buildResultsBlock]'s 1-based id assignment so each `[N]` in the
 *  generated text maps to the matching `[N] = Provider / Model` line
 *  here. Honours [includeIds] the same way the results block does —
 *  restrict the legend to the same subset that fed the prompt. */
fun buildReferenceLegend(report: Report, includeIds: Set<Int>? = null): String {
    val sb = StringBuilder()
    val successful = report.agents.filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
    successful.forEachIndexed { idx, agent ->
        val originalId = idx + 1
        if (includeIds != null && originalId !in includeIds) return@forEachIndexed
        val provDisplay = AppService.findById(agent.provider)?.id ?: agent.provider
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append("[").append(originalId).append("] = ")
            .append(provDisplay).append(" / ").append(agent.model)
    }
    return sb.toString()
}
