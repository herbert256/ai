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

    /** Per-file cache of parsed [SecondaryResult] rows. Keyed by
     *  reportId → filename → (mtime, length, parsed). Each save /
     *  delete invalidates only the affected filename's entry, so the
     *  next [listForReport] only re-parses the changed file instead
     *  of re-parsing the entire report directory. For a 56-pair
     *  fan-out at steady state, this turns ~56 redundant parses per
     *  pair completion into 1.
     *
     *  Coarse-mtime collisions (two saves to the same file landing
     *  in the same filesystem second with identical content length)
     *  are handled by the cache invalidation that fires on the save
     *  itself — the entry is removed before the next listForReport
     *  reads it, so the mtime+length match check is only relevant
     *  to OTHER files in the same directory that didn't change. */
    private data class CachedEntry(val mtime: Long, val length: Long, val parsed: SecondaryResult)
    @Volatile private var listCache: HashMap<String, HashMap<String, CachedEntry>> = HashMap()

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

    fun save(context: Context, result: SecondaryResult): SecondaryResult {
        init(context)
        // Defence in depth: every caller today uses UUIDs, but a future
        // regression that constructs an id with a slash or `..` would
        // otherwise write outside the per-report directory. Reject ids
        // that don't canonicalise to a child of the report dir before
        // touching the filesystem.
        if (result.id.isBlank() || result.id.contains('/') || result.id.contains('\\')
                || result.id == "." || result.id == "..") {
            AppLog.e("SecondaryResultStorage", "Refusing to save result with suspect id ${result.id}")
            return result
        }
        lock.withLock {
            val dir = reportDir(result.reportId) ?: return result
            val target = File(dir, "${result.id}.json")
            if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                AppLog.e("SecondaryResultStorage", "Refusing to save result that escapes report dir: ${result.id}")
                return result
            }
            target.writeTextAtomic(gson.toJson(result))
            // Invalidate only this file's cache entry — other files in
            // the report directory stay cached, so the next
            // listForReport only re-parses what changed. Coarse-mtime
            // collisions (same-second overwrite, same content length)
            // can't bite here because the entry is removed BEFORE the
            // next listForReport re-reads it.
            listCache[result.reportId]?.remove(target.name)
        }
        return result
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
            val dir = rootDir?.let { File(it, reportId) } ?: return@withLock emptyList()
            if (!dir.exists()) return@withLock emptyList()
            val files = dir.listFiles { f -> f.extension == "json" } ?: return@withLock emptyList()
            val cacheForReport = listCache.getOrPut(reportId) { HashMap(files.size.coerceAtLeast(16)) }
            val rows = ArrayList<SecondaryResult>(files.size)
            val seenFilenames = HashSet<String>(files.size)
            for (file in files) {
                val name = file.name
                seenFilenames.add(name)
                val mtime = file.lastModified()
                val length = file.length()
                val cached = cacheForReport[name]
                if (cached != null && cached.mtime == mtime && cached.length == length) {
                    rows.add(cached.parsed)
                    continue
                }
                val parsed = try {
                    gson.fromJson(file.readText(), SecondaryResult::class.java)
                } catch (_: Exception) {
                    null
                }
                if (parsed != null) {
                    cacheForReport[name] = CachedEntry(mtime, length, parsed)
                    rows.add(parsed)
                } else {
                    cacheForReport.remove(name)
                }
            }
            // Drop cache entries for files that have been deleted
            // since the last call — keeps the cache bounded to the
            // current on-disk set.
            cacheForReport.keys.retainAll(seenFilenames)
            // Tiebreaker on collision — burst-saved rows can share a
            // millisecond timestamp; sort-by-timestamp alone produced
            // an unstable order across listFiles calls. Adding the id
            // as a secondary key gives deterministic ordering.
            rows.sortWith(compareBy({ it.timestamp }, { it.id }))
            if (kind != null) rows.filter { it.kind == kind } else rows
        }
    }

    fun get(context: Context, reportId: String, resultId: String): SecondaryResult? {
        init(context)
        return lock.withLock {
            val dir = rootDir?.let { File(it, reportId) } ?: return@withLock null
            val file = File(dir, "$resultId.json")
            if (!file.exists()) return@withLock null
            try { gson.fromJson(file.readText(), SecondaryResult::class.java) } catch (_: Exception) { null }
        }
    }

    /** True when a row for [resultId] exists on disk under [reportId].
     *  Used by long-running fan-out / meta coroutines to drop their
     *  final save when the user deleted the placeholder mid-flight.
     *  Cheap on-disk check inside the same lock save / delete use. */
    fun exists(context: Context, reportId: String, resultId: String): Boolean {
        init(context)
        return lock.withLock {
            val dir = rootDir?.let { File(it, reportId) } ?: return@withLock false
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
    fun saveIfStillPresent(context: Context, result: SecondaryResult): Boolean {
        init(context)
        if (result.id.isBlank() || result.id.contains('/') || result.id.contains('\\')
                || result.id == "." || result.id == "..") {
            AppLog.e("SecondaryResultStorage", "Refusing to save result with suspect id ${result.id}")
            return false
        }
        lock.withLock {
            val dir = rootDir?.let { File(it, result.reportId) } ?: return false
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
            val toWrite = mergeCostFromDisk(target, result)
            target.writeTextAtomic(gson.toJson(toWrite))
            listCache[result.reportId]?.remove(target.name)
        }
        return true
    }

    /** Re-reads the existing on-disk row and adds its prior cost
     *  counters onto [incoming]. The result's other fields win.
     *  Returns [incoming] unchanged when the disk row can't be
     *  read or has no prior cost. */
    private fun mergeCostFromDisk(target: File, incoming: SecondaryResult): SecondaryResult {
        val existing = try { gson.fromJson(target.readText(), SecondaryResult::class.java) }
            catch (_: Exception) { return incoming }
        val priorIn = existing.inputCost ?: 0.0
        val priorOut = existing.outputCost ?: 0.0
        val priorInTokens = existing.tokenUsage?.inputTokens ?: 0
        val priorOutTokens = existing.tokenUsage?.outputTokens ?: 0
        if (priorIn == 0.0 && priorOut == 0.0 && priorInTokens == 0 && priorOutTokens == 0) {
            return incoming
        }
        val newIn = incoming.inputCost ?: 0.0
        val newOut = incoming.outputCost ?: 0.0
        val newInTokens = incoming.tokenUsage?.inputTokens ?: 0
        val newOutTokens = incoming.tokenUsage?.outputTokens ?: 0
        return incoming.copy(
            inputCost = priorIn + newIn,
            outputCost = priorOut + newOut,
            tokenUsage = TokenUsage(
                inputTokens = priorInTokens + newInTokens,
                outputTokens = priorOutTokens + newOutTokens
            )
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
            val dir = rootDir?.let { File(it, reportId) } ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = try { gson.fromJson(target.readText(), SecondaryResult::class.java) }
                catch (_: Exception) { return }
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
            target.writeTextAtomic(gson.toJson(updated))
            listCache[reportId]?.remove(target.name)
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
            val dir = rootDir?.let { File(it, reportId) } ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = try { gson.fromJson(target.readText(), SecondaryResult::class.java) }
                catch (_: Exception) { return }
            val updated = current.copy(
                iconInputTokens = current.iconInputTokens + inputTokens,
                iconOutputTokens = current.iconOutputTokens + outputTokens,
                iconInputCost = current.iconInputCost + inputCost,
                iconOutputCost = current.iconOutputCost + outputCost
            )
            target.writeTextAtomic(gson.toJson(updated))
            listCache[reportId]?.remove(target.name)
        }
    }

    /** Final commit step of the fan-out icon chain — stamps [icon] +
     *  [winningTier] on the row, leaving the cost / token counters
     *  bumped by earlier [bumpFanOutIconCost] calls intact. No-op
     *  when the row was deleted while the chain ran. */
    fun setFanOutIconAndTier(
        context: Context, reportId: String, resultId: String,
        icon: String, winningTier: Int?, iconRunId: String? = null,
        /** Bundled prompt that produced [icon] — "fan_out_2" for
         *  tier 1, "fan_out" for tier 2, "fan_out_3" for tier 3
         *  (or "fan_out_alt" after a future Find-alt pick).
         *  Surfaces on the Icon lookup screen. */
        promptUsed: String? = null
    ) {
        init(context)
        lock.withLock {
            val dir = rootDir?.let { File(it, reportId) } ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = try { gson.fromJson(target.readText(), SecondaryResult::class.java) }
                catch (_: Exception) { return }
            val updated = current.copy(
                icon = icon,
                iconWinningTier = winningTier,
                iconErrorMessage = null,
                iconRunId = iconRunId ?: current.iconRunId,
                iconPromptUsed = promptUsed ?: current.iconPromptUsed
            )
            target.writeTextAtomic(gson.toJson(updated))
            listCache[reportId]?.remove(target.name)
        }
    }

    /** Stamp an icon-chain failure on the pair row. Called when
     *  every tier of the fan-icons chain has failed. Mirrors
     *  [setFanOutIconAndTier] but writes [iconErrorMessage] only.
     *  Restart / retry paths clear it by writing a successful
     *  icon back. */
    fun setFanOutIconError(
        context: Context, reportId: String, resultId: String,
        errorMessage: String
    ) {
        init(context)
        lock.withLock {
            val dir = rootDir?.let { File(it, reportId) } ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = try { gson.fromJson(target.readText(), SecondaryResult::class.java) }
                catch (_: Exception) { return }
            val updated = current.copy(iconErrorMessage = errorMessage)
            target.writeTextAtomic(gson.toJson(updated))
            listCache[reportId]?.remove(target.name)
        }
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
            val dir = rootDir?.let { File(it, reportId) } ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = try { gson.fromJson(target.readText(), SecondaryResult::class.java) }
                catch (_: Exception) { return }
            val updated = current.copy(icon = icon)
            target.writeTextAtomic(gson.toJson(updated))
            listCache[reportId]?.remove(target.name)
        }
    }

    /** Regenerate-batch variant of [clearFanOutIconState] — clears
     *  the icon + iconErrorMessage + winning tier so the row
     *  re-reads as "icon-less" (FAN_ICONS phase will re-fire the
     *  chain) BUT preserves icon* cost / token counters. The
     *  dispatcher's additive cost write adds the new chain's
     *  expenditure onto the prior. */
    fun clearFanOutIconStateKeepingCost(
        context: Context, reportId: String, resultId: String
    ) {
        init(context)
        lock.withLock {
            val dir = rootDir?.let { File(it, reportId) } ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = try { gson.fromJson(target.readText(), SecondaryResult::class.java) }
                catch (_: Exception) { return }
            val updated = current.copy(
                icon = null,
                iconWinningTier = null,
                iconErrorMessage = null
            )
            target.writeTextAtomic(gson.toJson(updated))
            listCache[reportId]?.remove(target.name)
        }
    }

    /** Drop any prior icon / error state from the pair row so a
     *  re-launched fan-icons batch starts clean for these pairs.
     *  Leaves the row's main response untouched. */
    fun clearFanOutIconState(
        context: Context, reportId: String, resultId: String
    ) {
        init(context)
        lock.withLock {
            val dir = rootDir?.let { File(it, reportId) } ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = try { gson.fromJson(target.readText(), SecondaryResult::class.java) }
                catch (_: Exception) { return }
            val updated = current.copy(
                icon = null,
                iconWinningTier = null,
                iconErrorMessage = null,
                iconInputTokens = 0,
                iconOutputTokens = 0,
                iconInputCost = 0.0,
                iconOutputCost = 0.0
            )
            target.writeTextAtomic(gson.toJson(updated))
            listCache[reportId]?.remove(target.name)
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
            val dir = rootDir?.let { File(it, reportId) } ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = try { gson.fromJson(target.readText(), SecondaryResult::class.java) }
                catch (_: Exception) { return }
            val updated = current.copy(
                content = null,
                errorMessage = null,
                durationMs = null,
                // Bump timestamp so the dispatcher sees a fresh row.
                timestamp = System.currentTimeMillis()
            )
            target.writeTextAtomic(gson.toJson(updated))
            listCache[reportId]?.remove(target.name)
        }
    }

    fun delete(context: Context, reportId: String, resultId: String) {
        init(context)
        lock.withLock {
            val dir = rootDir?.let { File(it, reportId) } ?: return
            val target = File(dir, "$resultId.json")
            target.delete()
            // Drop only this file's cache entry — the remaining files
            // for the report stay parsed and ready for the next read.
            listCache[reportId]?.remove(target.name)
        }
    }

    fun deleteAllForReport(context: Context, reportId: String) {
        init(context)
        lock.withLock {
            val dir = rootDir?.let { File(it, reportId) } ?: return
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
            // Whole report gone — drop the entire per-report bucket.
            listCache.remove(reportId)
        }
    }

    /** Counts persisted across all kinds for a report. Used by the Report
     *  result screen for the Translate / legacy buckets. The redesigned
     *  Meta card uses [countByMetaName] instead. */
    data class Counts(val rerank: Int, val meta: Int, val moderation: Int, val translate: Int)
    fun countForReport(context: Context, reportId: String): Counts {
        // Delegate to listForReport so we share its fingerprint cache —
        // the previous implementation re-parsed every JSON file on
        // every call even when the per-report list was already in
        // memory and unchanged. Fan out drill-in polls this every
        // 500 ms while batching, so the redundant parses scaled with
        // (file count × poll rate) for no benefit.
        val rows = listForReport(context, reportId)
        var rerank = 0; var meta = 0; var moderation = 0; var translate = 0
        for (r in rows) {
            when (r.kind) {
                SecondaryKind.RERANK -> rerank++
                SecondaryKind.META -> meta++
                SecondaryKind.MODERATION -> moderation++
                SecondaryKind.TRANSLATE -> translate++
            }
        }
        return Counts(rerank, meta, moderation, translate)
    }

    /** Group non-translate Meta results on a report by the user-given
     *  Meta prompt name, returning name → count. Legacy rows (written
     *  before metaPromptName existed) fall back to a kind-derived
     *  label so the View card keeps a stable history. */
    fun countByMetaName(context: Context, reportId: String): Map<String, Int> {
        init(context)
        return lock.withLock {
            val dir = rootDir?.let { File(it, reportId) } ?: return@withLock emptyMap()
            if (!dir.exists()) return@withLock emptyMap()
            val tally = LinkedHashMap<String, Int>()
            dir.listFiles { f -> f.extension == "json" }?.forEach { file ->
                try {
                    val r = gson.fromJson(file.readText(), SecondaryResult::class.java)
                    if (r.kind == SecondaryKind.TRANSLATE) return@forEach
                    val name = r.metaPromptName?.takeIf { it.isNotBlank() } ?: legacyKindDisplayName(r.kind)
                    tally[name] = (tally[name] ?: 0) + 1
                } catch (_: Exception) {}
            }
            tally
        }
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

/** Resolve the prompt template for a model-scoped fan-in run
 *  (categories initiator / requester / model). Distinct from
 *  [resolveFanInPrompt] — the model-scoped resolver pre-builds the
 *  iterable blocks in code (not via regex expansion of an
 *  `***Report*** @REPORT@@RESPONSES@` template fragment) because
 *  the data shape is per-pair list rather than per-source iterable.
 *
 *  Variables (all plain string substitutions):
 *  - `@QUESTION@` — original report prompt
 *  - `@TITLE@` — report title (or empty)
 *  - `@DATE@` — current date/time, `yyyy-MM-dd HH:mm`
 *  - `@COUNT@` — `max(responders.size, responderPairs.size)`
 *  - `@INITIATOR@` — active model's own report response. Used by
 *    initiator / model. Empty for requester where the active model
 *    is the answerer, not the source.
 *  - `@RESPONDERS@` — block of fan-out responses where the active
 *    model is the source (other models responded TO active's report).
 *    One `***Response*** {body}` line per responder, separated by
 *    blank lines. Same `***Response***` prefix the legacy fan-in
 *    iterable uses for parallel rendering. Used by initiator / model.
 *  - `@RESPONDER_PAIRS@` — iterable list of pairs where the active
 *    model is the answerer. Each pair renders as
 *    `***Report*** {other's report body}\n\n***Response*** {active's
 *    fan-out response}`. Pairs separated by blank lines. Used by
 *    requester / model. */
fun resolveModelFanInPrompt(
    template: String,
    question: String,
    title: String?,
    initiatorBody: String,
    responders: List<String>,
    responderPairs: List<Pair<String, String>>
): String {
    val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
    val respondersBlock = responders.joinToString("\n\n") { "***Response*** $it" }
    val pairsBlock = responderPairs.joinToString("\n\n") { (rep, resp) ->
        "***Report*** $rep\n\n***Response*** $resp"
    }
    return template
        .replace("@QUESTION@", question)
        .replace("@TITLE@", title ?: "")
        .replace("@DATE@", now)
        .replace("@COUNT@", maxOf(responders.size, responderPairs.size).toString())
        .replace("@INITIATOR@", initiatorBody)
        .replace("@RESPONDERS@", respondersBlock)
        .replace("@RESPONDER_PAIRS@", pairsBlock)
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

