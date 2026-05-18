package com.ai.data

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class SecondaryKind { RERANK, META, MODERATION, TRANSLATE }

/**
 * A meta-result that operates on a parent Report's per-agent outputs:
 * a Rerank ranks them, a chat-type Meta prompt narrates / compares /
 * summarises / etc. them. Each is the work of a single chosen model,
 * persisted independently so a report can accumulate many of either
 * kind over time. The user-given Meta prompt name carried on the row
 * (metaPromptName) is what the UI / exports bucket by — the kind is
 * just the routing label for which API path handles the call.
 */
data class SecondaryResult(
    val id: String,
    val reportId: String,
    val kind: SecondaryKind,
    val providerId: String,
    val model: String,
    val agentName: String,
    val timestamp: Long,
    val content: String?,
    val errorMessage: String? = null,
    val tokenUsage: TokenUsage? = null,
    val inputCost: Double? = null,
    val outputCost: Double? = null,
    val durationMs: Long? = null,
    /** When kind == TRANSLATE: identifier of the item this translation
     *  operated on. "prompt" for the report prompt, agent.agentId for
     *  an agent response, secondary.id for a META row. Null on non-
     *  translate rows. */
    val translateSourceTargetId: String? = null,
    /** Companion to [translateSourceTargetId]. One of "PROMPT", "AGENT",
     *  "META". Null on non-translate rows. */
    val translateSourceKind: String? = null,
    /** Target language for a TRANSLATE row, English name (e.g. "Dutch",
     *  "German"). Used to group translated content into per-language
     *  views in the report viewer / Complete HTML / Zipped HTML. Null
     *  on non-translate rows. */
    val targetLanguage: String? = null,
    /** Companion to [targetLanguage] — native rendering for the picker
     *  ("Nederlands", "Deutsch"). Null on non-translate rows. */
    val targetLanguageNative: String? = null,
    /** UUID shared by every TRANSLATE row produced by a single Translate
     *  invocation, so the result page can render one aggregate "run"
     *  row per click and let the user drill into the individual API
     *  calls. Null on non-translate rows and on legacy rows saved
     *  before this field existed (those fall back to grouping-by-
     *  language for the same effect). */
    val translationRunId: String? = null,
    /** Legacy field — set by the old "translation creates a copy" flow
     *  on Summary / Compare secondaries that were duplicated onto a
     *  translated report. New translations don't fork the report so
     *  this stays null on every TRANSLATE row written by the current
     *  code path; preserved on disk so old reports still load. */
    val translatedFromSecondaryId: String? = null,
    /** Id of the [com.ai.model.MetaPrompt] entry that produced this
     *  row. Null on TRANSLATE rows and on legacy rows written before
     *  the Meta-prompt CRUD existed; UI falls back to the legacy
     *  `kind` label in that case. */
    val metaPromptId: String? = null,
    /** Display name of the Meta prompt that produced this row, copied
     *  from the [com.ai.model.MetaPrompt] at run time so the View
     *  card can group results by user-given name even after the user
     *  renames or deletes the Meta prompt. Null on TRANSLATE / legacy
     *  rows. */
    val metaPromptName: String? = null,
    /** For fan-out Meta runs: agentId of the report-model whose
     *  response was substituted into the prompt's `@RESPONSE@` slot.
     *  This row's own (providerId, model) is the answerer that
     *  produced this content. Together they form the (answerer,
     *  source) pair the fan out drill-in keys on. Null on every
     *  non-fan out row. */
    val fanOutSourceAgentId: String? = null,
    /** For fan_in-type Meta runs: id of the [com.ai.model.InternalPrompt]
     *  that produced this combined-report row. Lets the fan out detail
     *  screen distinguish the single combined output from the per-pair
     *  response rows even though both share `metaPromptName`. Null on
     *  every non-fan_in row. */
    val fanInOf: String? = null,
    /** Encoded [SecondaryScope] used when this row was originally
     *  produced — see [SecondaryScope.encode]. The cascade-on-prompt-
     *  change path reads this so a previously-narrowed run (Top-N /
     *  Manual selection) is re-run at the same scope instead of
     *  silently widening to AllReports. Null on legacy rows written
     *  before this field existed; cascade defaults to AllReports
     *  there, matching prior behaviour. */
    val secondaryScope: String? = null,
    /** Set when this row is a model-scoped fan-in (categories
     *  initiator / requester / model). Identifies which (provider,
     *  model) pair the L2 page should surface this row under so the
     *  per-model drill-in can filter to just its own. Null on every
     *  other row including the legacy "total" fan_in (which combines
     *  the whole report and is shown on L1's combinedRows section). */
    val scopeProviderId: String? = null,
    val scopeModel: String? = null,
    /** Per-fan-out-pair emoji produced by the
     *  [com.ai.viewmodel.ReportViewModel.runFanOutIconChain] 3-tier
     *  chain (chat continuation → one-shot fan_out_icon →
     *  fixed-agent fan_out_icon_3th). Null until the chain finishes
     *  (or skipped — feature gated by
     *  [com.ai.viewmodel.GeneralSettings.fanOutIconGenEnabled]).
     *  Empty / non-fan-out rows leave this null forever. */
    val icon: String? = null,
    /** Which tier of the fan-out icon chain produced [icon]. 1 =
     *  chat continuation, 2 = one-shot fan_out_icon, 3 = fixed-
     *  agent fan_out_icon_3th. Null when no tier succeeded and the
     *  icon is the 📝 fallback, or when [icon] is null. */
    val iconWinningTier: Int? = null,
    val iconErrorMessage: String? = null,
    val iconInputTokens: Int = 0,
    val iconOutputTokens: Int = 0,
    val iconInputCost: Double = 0.0,
    val iconOutputCost: Double = 0.0,
    /** Bundled prompt name that produced the currently-displayed
     *  [icon] on this fan-out pair row. "fan_out" / "fan_out_2" /
     *  "fan_out_3" per the winning tier; "fan_out_alt" after a
     *  Find-alt pick (future). Null on legacy rows — the future
     *  Icon lookup screen for fan-out pairs falls back to deriving
     *  the name from [iconWinningTier]. */
    val iconPromptUsed: String? = null,
    /** UUID shared by every secondary result produced by a single
     *  user-launched batch (fan-out, translation, ...). Lets the
     *  run's L1 screen deep-link the 🐞 trace icon to a trace list
     *  filtered to exactly this batch's API calls. Null on legacy
     *  rows written before this field existed. */
    val runId: String? = null,
    /** UUID of the most recent fan-icons sweep that touched this
     *  row. Stamped at icon commit time so the fan-icons L1 screen
     *  can deep-link 🐞 to that sweep's traces independently from
     *  the row's own [runId] (which records the fan-out that
     *  created the row). Null when no icon sweep has run, or on
     *  legacy rows. */
    val iconRunId: String? = null,
    /** Trace filename captured for the specific API call that
     *  produced this row, when available. Currently populated only
     *  for TRANSLATE rows (the View → Prompt screen wires a 🐞 from
     *  this so a selected translation jumps straight into its trace).
     *  Null on every other kind and on legacy translate rows written
     *  before this field existed. */
    val traceFile: String? = null
)

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
            target.writeTextAtomic(gson.toJson(result))
            listCache[result.reportId]?.remove(target.name)
        }
        return true
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
     *  errorMessage, durationMs, tokenUsage and costs so the
     *  resume-stale path picks it up and re-dispatches. Leaves
     *  the row's prompt / model / scope / metaPromptName fields
     *  intact so the dispatcher can reconstruct the call.
     *  Used by [com.ai.viewmodel.RegenerateBatchEngine] when a
     *  phase starts so every row in that phase shows ⏳ before
     *  the dispatcher fires. No-op when the row is gone. */
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
                tokenUsage = null,
                inputCost = null,
                outputCost = null,
                // Bump timestamp so the dispatcher sees a fresh row.
                timestamp = System.currentTimeMillis()
            )
            target.writeTextAtomic(gson.toJson(updated))
            listCache[reportId]?.remove(target.name)
        }
    }

    /** Add prior cost + token counts back onto a row's inputCost /
     *  outputCost / tokenUsage after a successful Regenerate-batch
     *  re-dispatch overwrote them. Used for META / FAN_OUT / FAN_IN
     *  / TRANSLATIONS phases. */
    fun accumulateRowCost(
        context: Context, reportId: String, resultId: String,
        addInputTokens: Int, addOutputTokens: Int,
        addInputCost: Double, addOutputCost: Double
    ) {
        init(context)
        lock.withLock {
            val dir = rootDir?.let { File(it, reportId) } ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = try { gson.fromJson(target.readText(), SecondaryResult::class.java) }
                catch (_: Exception) { return }
            val updated = current.copy(
                inputCost = (current.inputCost ?: 0.0) + addInputCost,
                outputCost = (current.outputCost ?: 0.0) + addOutputCost,
                tokenUsage = TokenUsage(
                    inputTokens = (current.tokenUsage?.inputTokens ?: 0) + addInputTokens,
                    outputTokens = (current.tokenUsage?.outputTokens ?: 0) + addOutputTokens
                )
            )
            target.writeTextAtomic(gson.toJson(updated))
            listCache[reportId]?.remove(target.name)
        }
    }

    /** Add prior fan-out-icon cost + tokens back onto a fan-out
     *  pair row's iconInputCost / iconOutputCost / iconInputTokens
     *  / iconOutputTokens after a successful Regenerate-batch
     *  FAN_ICONS-phase re-dispatch. */
    fun accumulateRowFanIconCost(
        context: Context, reportId: String, resultId: String,
        addInputTokens: Int, addOutputTokens: Int,
        addInputCost: Double, addOutputCost: Double
    ) {
        init(context)
        lock.withLock {
            val dir = rootDir?.let { File(it, reportId) } ?: return
            val target = File(dir, "$resultId.json")
            if (!target.exists()) return
            val current = try { gson.fromJson(target.readText(), SecondaryResult::class.java) }
                catch (_: Exception) { return }
            val updated = current.copy(
                iconInputTokens = current.iconInputTokens + addInputTokens,
                iconOutputTokens = current.iconOutputTokens + addOutputTokens,
                iconInputCost = current.iconInputCost + addInputCost,
                iconOutputCost = current.iconOutputCost + addOutputCost
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

/** Restriction on which of the parent report's per-agent results are
 *  fed into a chat-type Meta run. Rerank itself always uses
 *  [AllReports] — it ranks the full set by definition. */
sealed class SecondaryScope {
    object AllReports : SecondaryScope()
    data class TopRanked(val count: Int, val rerankResultId: String) : SecondaryScope()
    /** Explicit list of agent ids the user picked from the existing
     *  report. When non-empty the meta run only sees those rows, the
     *  same way [TopRanked] only sees the rerank's top-N. */
    data class Manual(val agentIds: Set<String>) : SecondaryScope()

    /** Persistable string form so the cascade-on-prompt-change path can
     *  re-run a meta with the same scope it originally ran with,
     *  instead of silently widening to AllReports and re-billing the
     *  full agent set. Format:
     *    "ALL"
     *    "TOP:<rerankResultId>:<count>"
     *    "MANUAL:<agentId1>,<agentId2>,..." */
    fun encode(): String = when (this) {
        is AllReports -> "ALL"
        is TopRanked -> "TOP:$rerankResultId:$count"
        is Manual -> "MANUAL:" + agentIds.joinToString(",")
    }

    companion object {
        /** Inverse of [encode]. Returns [AllReports] on null, blank, or
         *  malformed input — defensive fallback so a corrupt or legacy
         *  row never blocks a cascade. */
        fun decodeOrAllReports(s: String?): SecondaryScope {
            if (s.isNullOrBlank()) return AllReports
            return runCatching {
                when {
                    s == "ALL" -> AllReports
                    s.startsWith("TOP:") -> {
                        val rest = s.removePrefix("TOP:")
                        val lastColon = rest.lastIndexOf(':')
                        if (lastColon <= 0) AllReports
                        else {
                            val rerankId = rest.substring(0, lastColon)
                            val count = rest.substring(lastColon + 1).toIntOrNull() ?: return AllReports
                            TopRanked(count, rerankId)
                        }
                    }
                    s.startsWith("MANUAL:") -> {
                        val rest = s.removePrefix("MANUAL:")
                        val ids = rest.split(",").filter { it.isNotBlank() }.toSet()
                        if (ids.isEmpty()) AllReports else Manual(ids)
                    }
                    else -> AllReports
                }
            }.getOrDefault(AllReports)
        }
    }
}

/** Language filter for chat-type META multi-language fan-out. Defaults
 *  to AllPresent so existing call sites keep their behaviour; the scope
 *  picker offers Selected for "only these languages". TRANSLATE /
 *  RERANK / MODERATION ignore this. */
sealed class SecondaryLanguageScope {
    object AllPresent : SecondaryLanguageScope()
    /** [languages] holds English-name keys ("Dutch") plus the empty
     *  string for the original (untranslated) source. */
    data class Selected(val languages: Set<String>) : SecondaryLanguageScope()
}

/** Result of an API-level rerank call (Cohere v2/rerank or compatible).
 *  Outcome shape mirrors what the chat-model rerank flow already
 *  produces — content is the structured JSON the rest of the system
 *  reads (HTML export, Top-Ranked scope, etc.). */
data class RerankApiResult(
    val content: String?,           // JSON in the same shape as the chat prompt asks for
    val errorMessage: String?,
    val httpStatusCode: Int? = null,
    val billedSearchUnits: Int? = null,
    val durationMs: Long
)

/** Call the provider's dedicated rerank endpoint with the parent
 *  report's prompt as the query and the per-agent response bodies as
 *  documents, then convert the response into the same
 *  `[{id, rank, score, reason}, ...]` JSON the chat-model rerank flow
 *  uses — so the rest of the system (HTML export, Top-Ranked scope)
 *  doesn't need a second code path.
 *
 *  Routes any provider that declares [AppService.nativeRerankUrl];
 *  others fall through with an explanatory error. Documents are
 *  passed in success-ordered position, so the provider's `index`
 *  (0-based) maps directly to the bracketed [N] id (1-based) the
 *  rest of the app expects. */
suspend fun callRerankApi(
    provider: AppService, apiKey: String, model: String,
    query: String, documents: List<String>
): RerankApiResult {
    val start = System.currentTimeMillis()
    val url = provider.nativeRerankUrl
    return if (url != null) {
        callNativeRerank(url, apiKey, model, query, documents, start)
    } else {
        RerankApiResult(
            content = null,
            errorMessage = "Rerank API not wired for provider ${provider.id}. Pick a chat model instead, or open an issue to add ${provider.id} rerank support.",
            durationMs = System.currentTimeMillis() - start
        )
    }
}

/** Outcome of a single moderation call. [content] is structured JSON of
 *  the form `[{id: N, flagged: bool, categories: {…}, scores: {…}}, …]`
 *  — one object per input. The renderer parses this in the detail
 *  screen and tabulates flags. [tokenUsage] mirrors the chat token
 *  trio so the caller can attribute cost the same way other meta runs
 *  do — Mistral's /v1/moderations response includes prompt_tokens /
 *  completion_tokens / total_tokens just like the chat endpoints. */
data class ModerationApiResult(
    val content: String?,
    val errorMessage: String?,
    val httpStatusCode: Int? = null,
    val durationMs: Long,
    val tokenUsage: TokenUsage? = null
)

/** Per-input moderation classification. Used by both the chat-input
 *  validator and the Meta moderation flow; the latter aggregates many
 *  of these into a [ModerationApiResult.content] JSON array. */
data class ModerationInputResult(
    val flagged: Boolean,
    val categories: Map<String, Boolean>,
    val scores: Map<String, Double>
) {
    /** Categories that fired (`true` in `categories`). Convenience for
     *  the dialog / row label so callers don't repeat the filter. */
    val firedCategories: List<String> get() = categories.filterValues { it }.keys.toList()
}

/** Call the provider's moderation endpoint on each entry in [inputs].
 *  Routes any provider that declares [AppService.nativeModerationUrl];
 *  others fall through with an explanatory error so the user learns
 *  to pick a moderation-capable model. The result list is
 *  index-aligned with [inputs]. */
suspend fun callModerationApi(
    provider: AppService, apiKey: String, model: String,
    inputs: List<String>
): Pair<List<ModerationInputResult>?, ModerationApiResult> {
    val start = System.currentTimeMillis()
    val url = provider.nativeModerationUrl
    return if (url != null) {
        callNativeModeration(url, apiKey, model, inputs, start)
    } else {
        val err = ModerationApiResult(
            content = null,
            errorMessage = "Moderation API not wired for provider ${provider.id}. Pick a Mistral moderation model instead, or open an issue to add ${provider.id} moderation support.",
            durationMs = System.currentTimeMillis() - start
        )
        null to err
    }
}

private suspend fun callNativeModeration(
    url: String, apiKey: String, model: String, inputs: List<String>, start: Long
): Pair<List<ModerationInputResult>?, ModerationApiResult> {
    return try {
        val api = ApiFactory.createMistralModerationApi()
        val response = api.moderate(url, "Bearer $apiKey", MistralModerationRequest(model, inputs))
        val duration = System.currentTimeMillis() - start
        if (!response.isSuccessful) {
            val errBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
            return null to ModerationApiResult(
                content = null,
                errorMessage = "${response.code()} ${response.message()}: ${errBody ?: ""}",
                httpStatusCode = response.code(),
                durationMs = duration
            )
        }
        val body = response.body() ?: return null to ModerationApiResult(
            content = null, errorMessage = "Empty moderation response", httpStatusCode = response.code(), durationMs = duration
        )
        if (body.results.isNullOrEmpty()) {
            return null to ModerationApiResult(
                content = null,
                errorMessage = body.message ?: "Moderation returned no results",
                httpStatusCode = response.code(), durationMs = duration
            )
        }
        val results = body.results.map { r ->
            val cats = r.categories.orEmpty()
            ModerationInputResult(
                flagged = cats.any { it.value },
                categories = cats,
                scores = r.category_scores.orEmpty()
            )
        }
        val json = moderationResultsToJson(results)
        // Mistral returns token usage on a successful moderation call —
        // lift it into TokenUsage so cost attribution matches the
        // chat-driven meta runs. Falls through to null when the field
        // is missing (older API versions or local proxies).
        val tu = body.usage?.let { u ->
            val inT = u.prompt_tokens ?: 0
            val outT = u.completion_tokens ?: 0
            if (inT == 0 && outT == 0) null
            else TokenUsage(inputTokens = inT, outputTokens = outT)
        }
        results to ModerationApiResult(
            content = json,
            errorMessage = null,
            httpStatusCode = response.code(),
            durationMs = duration,
            tokenUsage = tu
        )
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null to ModerationApiResult(
            content = null,
            errorMessage = "Moderation call failed: ${e.message ?: e.javaClass.simpleName}",
            durationMs = System.currentTimeMillis() - start
        )
    }
}

/** Encode the moderation outcomes as JSON the detail screen parses
 *  back. id = 1-based index of the input (so the bracketed [N] tags
 *  the rest of the pipeline already uses align). */
private fun moderationResultsToJson(results: List<ModerationInputResult>): String {
    val arr = com.google.gson.JsonArray()
    results.forEachIndexed { idx, r ->
        val obj = com.google.gson.JsonObject().apply {
            addProperty("id", idx + 1)
            addProperty("flagged", r.flagged)
            val catsObj = com.google.gson.JsonObject()
            r.categories.forEach { (k, v) -> catsObj.addProperty(k, v) }
            add("categories", catsObj)
            val scoresObj = com.google.gson.JsonObject()
            r.scores.forEach { (k, v) -> scoresObj.addProperty(k, v) }
            add("scores", scoresObj)
        }
        arr.add(obj)
    }
    return createAppGson(prettyPrint = true).toJson(arr)
}

private suspend fun callNativeRerank(
    url: String, apiKey: String, model: String, query: String, documents: List<String>, start: Long
): RerankApiResult {
    return try {
        val api = ApiFactory.createCohereRerankApi()
        val request = CohereRerankRequest(model, query, documents, top_n = documents.size)
        val response = api.rerank(url, "Bearer $apiKey", request)
        val duration = System.currentTimeMillis() - start
        if (!response.isSuccessful) {
            val errBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
            return RerankApiResult(
                content = null,
                errorMessage = "${response.code()} ${response.message()}: ${errBody ?: ""}",
                httpStatusCode = response.code(),
                durationMs = duration
            )
        }
        val body = response.body() ?: return RerankApiResult(
            content = null, errorMessage = "Empty rerank response", httpStatusCode = response.code(), durationMs = duration
        )
        if (body.results.isNullOrEmpty()) {
            return RerankApiResult(
                content = null,
                errorMessage = body.message ?: "Rerank returned no results",
                httpStatusCode = response.code(), durationMs = duration
            )
        }
        val json = rerankResultsToJson(body.results)
        RerankApiResult(
            content = json,
            errorMessage = null,
            httpStatusCode = response.code(),
            billedSearchUnits = body.meta?.billed_units?.search_units,
            durationMs = duration
        )
    } catch (e: kotlinx.coroutines.CancellationException) {
        // Don't swallow cancellation — let structured concurrency
        // unwind without persisting a fake "failed" placeholder.
        // (Same shape as the moderation / chat paths.)
        throw e
    } catch (e: Exception) {
        RerankApiResult(
            content = null,
            errorMessage = "Rerank call failed: ${e.message ?: e.javaClass.simpleName}",
            durationMs = System.currentTimeMillis() - start
        )
    }
}

/** Convert a list of (index, relevance_score) into the structured JSON
 *  the rest of the secondary-result pipeline already parses. Higher
 *  relevance gets a lower rank number (rank 1 = most relevant). Score
 *  is rescaled to 0-100 for parity with the chat-model rerank prompt. */
private fun rerankResultsToJson(results: List<CohereRerankResult>): String {
    val sorted = results.sortedByDescending { it.relevance_score }
    val arr = com.google.gson.JsonArray()
    sorted.forEachIndexed { rank, r ->
        val obj = com.google.gson.JsonObject().apply {
            addProperty("id", r.index + 1)
            addProperty("rank", rank + 1)
            addProperty("score", Math.round(r.relevance_score * 100).toInt().coerceIn(0, 100))
            addProperty("reason", "Relevance score: %.4f".format(r.relevance_score))
        }
        arr.add(obj)
    }
    return createAppGson(prettyPrint = true).toJson(arr)
}

/** Parse the structured JSON output of a rerank result and return the
 *  original [N] ids of the top [count] entries (sorted by `rank`). The
 *  prompt asks for `[{"id":N,"rank":R, ...}, ...]`; ``` fences are
 *  tolerated. Returns null if the payload doesn't deserialize cleanly. */
fun extractTopRankedIds(rerankContent: String?, count: Int): List<Int>? {
    if (rerankContent.isNullOrBlank() || count <= 0) return null
    val cleaned = rerankContent.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val arr = try {
        com.google.gson.JsonParser.parseString(cleaned).takeIf { it.isJsonArray }?.asJsonArray
    } catch (_: Exception) { return null } ?: return null
    if (arr.size() == 0) return null

    data class Entry(val id: Int, val rank: Int?)
    val entries = arr.mapNotNull { el ->
        if (!el.isJsonObject) return@mapNotNull null
        val obj = el.asJsonObject
        val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
        val rank = obj.get("rank")?.takeIf { it.isJsonPrimitive }?.asInt
        Entry(id, rank)
    }
    if (entries.isEmpty()) return null
    // Prefer ranked entries when we have any. Without this guard,
    // a payload missing the rank field on every row used to sort
    // every Entry as MAX_VALUE — turning sortedBy into a no-op
    // that returned the model's response order, NOT the rank
    // order. If at least one rank is present, surface only ranked
    // entries (the model intended those to win); otherwise keep
    // the model's emit order.
    val ranked = entries.filter { it.rank != null }
    return if (ranked.isNotEmpty()) {
        ranked.sortedBy { it.rank!! }.take(count).map { it.id }
    } else {
        entries.take(count).map { it.id }
    }
}
