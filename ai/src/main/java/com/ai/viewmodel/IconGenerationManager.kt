package com.ai.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.data.*
import com.ai.model.*
import com.ai.ui.helpers.translationRunGroupingId
import com.ai.ui.shared.shortModelName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Icon-generation orchestration extracted from [ReportViewModel]:
 *  the report/title/language icon kick-offs, every per-scope icon
 *  fan-out (internal-prompt / pair / translation / agent / language),
 *  the 3-tier emoji chain, and the fan-out-pair icon batch. The icon
 *  job maps + their register/key helpers stay on [rvm] (shared with
 *  report generation + cancellation) and are reached via rvm.* ;
 *  [appViewModel] supplies settings / storage / scope. */
class IconGenerationManager(
    private val appViewModel: AppViewModel,
    private val rvm: ReportViewModel
) {
    /** Background helper that runs the bundled `internal/icon` prompt
     *  against its pinned agent and writes the resolved emoji onto the
     *  Report. Best-effort: silently no-ops when the prompt is missing,
     *  the pinned agent has been deleted / renamed, or the agent isn't
     *  resolvable via [Settings.agents] by name. The call is launched
     *  on viewModelScope so it runs in parallel with per-agent dispatch
     *  and survives the user navigating away from the result screen.
     *  Failures are persisted to [Report.iconErrorMessage] so the
     *  result-page row can render ❌. */
    internal fun kickOffIconGeneration(
        context: Context,
        reportId: String,
        promptText: String,
        aiSettings: Settings
    ) {
        // Master switch — when the user disabled per-report icon-gen
        // in Settings, skip the LLM call entirely. Existing on-disk
        // icon values stay intact.
        if (!appViewModel.uiState.value.generalSettings.reportIconOn()) return
        // Worker-based: the icon is derived from the report's long title
        // (@TITLE_LONG@) and runs through the random-pick / 429-fallback
        // worker chain. Bail if the prompt or every worker is unresolvable.
        val iconPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "workers" && it.name == "report-icon"
        } ?: return
        if (iconPrompt.workers.none { aiSettings.resolveWorker(it) != null }) return
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            withTracerTags(reportId = reportId, category = "workers/report-icon") {
                val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
                appViewModel.updateRunningInfoJobs { it + "$reportId|icon" }
                // Feed the long title (fall back to short title, then the
                // prompt). Read fresh from storage so a standalone icon
                // regen still picks up the previously-stored long title.
                val report = ReportStorage.getReport(context, reportId)
                val titleLong = report?.titleLong?.takeIf { it.isNotBlank() }
                    ?: report?.title?.takeIf { it.isNotBlank() }
                    ?: promptText
                val resolved = iconPrompt.text.replace("@TITLE_LONG@", titleLong)
                val started = System.currentTimeMillis()
                val outcome = withTraceFilenameSink(traceSink) {
                    // A worker reply with no parseable emoji is a logical miss —
                    // fall through to the next worker instead of accepting an
                    // empty 200 and storing the 📝 fallback.
                    rvm.workerRunner.run(iconPrompt, resolved, aiSettings, context) {
                        extractFirstEmoji(it.analysis) != null
                    }
                }
                val durationMs = System.currentTimeMillis() - started
                when (outcome) {
                    is WorkerOutcome.Success -> {
                        // Always end with exactly one emoji glyph (first emoji,
                        // strip prose, 📝 fallback on an empty 200).
                        val emoji = extractFirstEmoji(outcome.response.analysis) ?: "📝"
                        val winAgent = aiSettings.resolveWorker(outcome.worker)?.let {
                            it.copy(model = aiSettings.getEffectiveModelForAgent(it))
                        }
                        val tu = outcome.response.tokenUsage
                        val inT = tu?.inputTokens ?: 0
                        val outT = tu?.outputTokens ?: 0
                        val pricing = winAgent?.let { PricingCache.getPricing(context, it.provider, it.model) }
                        val inC = inT * (pricing?.promptPrice ?: 0.0)
                        val outC = outT * (pricing?.completionPrice ?: 0.0)
                        ReportStorage.updateReportIcon(
                            context, reportId, emoji,
                            inputTokens = inT, outputTokens = outT,
                            inputCost = inC, outputCost = outC,
                            traceFile = traceSink.get(),
                            promptUsed = "main",
                            durationMs = durationMs
                        )
                    }
                    else -> ReportStorage.updateReportIconError(
                        context, reportId,
                        if (outcome is WorkerOutcome.AllRateLimited) "icon-gen: all workers rate-limited"
                        else "icon-gen: no worker produced an icon"
                    )
                }
                appViewModel.updateRunningInfoJobs { it - "$reportId|icon" }
                appViewModel.updateUiState {
                    it.copy(iconRefreshTick = it.iconRefreshTick + 1)
                }
            }
        }
    }

    /** Background helper that runs the bundled `internal/report_title`
     *  prompt against its pinned agent and writes the resolved title
     *  onto the Report. Only fires when the user is in
     *  [com.ai.viewmodel.ReportTitleMode.AI] (default). Mirrors
     *  [kickOffIconGeneration] — best-effort, off the main thread,
     *  failures persisted via [ReportStorage.updateReportTitleError]
     *  so the Manage `title` row can flip to ❌.
     *
     *  On success the generated title is hard-capped to 30 chars and
     *  also pushed into [com.ai.model.UiState.genericPromptTitle] when
     *  the user is currently on this report, so the green title row
     *  at the top of Manage report updates without a refresh. */
    /** Defensive cleanup of one line of an LLM two-line title reply.
     *  Strips leading bullets / numbering ("1.", "2)", "- "), label
     *  prefixes ("Title:", "Short:", "Long:" — any case), surrounding
     *  quotes and **markdown bold**, iterating so stacked prefixes like
     *  "1. Short: X" reduce to "X". Some models echo the prompt's two-line
     *  format as labels/numbers even when told to return only the titles. */
    private fun cleanTitleLine(raw: String): String {
        val leading = Regex(
            "^\\s*(?:[-*•]\\s*|\\d+[.)]\\s*|(?:short|long|title)\\s*[:.\\-]\\s*)",
            RegexOption.IGNORE_CASE
        )
        var s = raw.trim()
        var prev: String
        do {
            prev = s
            s = s.replace(leading, "").trim()
            if (s.length >= 4 && s.startsWith("**") && s.endsWith("**")) s = s.substring(2, s.length - 2).trim()
            s = s.removeSurrounding("\"").trim().removeSurrounding("'").trim()
        } while (s != prev)
        return s
    }

    /** One title-gen call's outcome — the cleaned title plus the cost /
     *  trace / model metadata, so the caller can sum two calls' costs and
     *  persist them onto the report's single set of title* fields. */
    private data class TitleGenResult(
        val title: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val inputCost: Double,
        val outputCost: Double,
        val durationMs: Long,
        val traceFile: String?,
        val model: String?,
    )

    /** Run ONE report-title worker prompt and return its single cleaned
     *  title (capped to [cap] chars) + cost/trace metadata, or null when
     *  the prompt is unusable or no worker produced a title. Each call
     *  carries its own trace sink and its own [traceCategory]
     *  ("workers/report-title-short" / "-long") so each title editor's 🐞
     *  scan finds the right call. */
    private suspend fun runTitlePrompt(
        context: Context,
        reportId: String,
        prompt: InternalPrompt?,
        promptText: String,
        aiSettings: Settings,
        cap: Int,
        traceCategory: String,
    ): TitleGenResult? {
        if (prompt == null || prompt.workers.none { aiSettings.resolveWorker(it) != null }) return null
        val resolved = prompt.text.replace("@PROMPT@", promptText)
        val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val started = System.currentTimeMillis()
        val outcome = withTracerTags(reportId = reportId, category = traceCategory) {
            withTraceFilenameSink(traceSink) {
                // A reply with no non-blank title line is a logical miss —
                // try the next worker instead of settling for a default.
                rvm.workerRunner.run(prompt, resolved, aiSettings, context) { resp ->
                    (resp.analysis ?: "").lineSequence().map { cleanTitleLine(it) }.any { it.isNotBlank() }
                }
            }
        }
        val durationMs = System.currentTimeMillis() - started
        if (outcome !is WorkerOutcome.Success) return null
        // These prompts each return a single title line. Clean defensively
        // (models sometimes add quotes / a "Title: " prefix) and take the
        // first non-blank line, capped to the prompt's char budget.
        val title = (outcome.response.analysis ?: "")
            .lineSequence().map { cleanTitleLine(it) }.firstOrNull { it.isNotBlank() }
            .orEmpty().take(cap)
        if (title.isBlank()) return null
        val winAgent = aiSettings.resolveWorker(outcome.worker)?.let {
            it.copy(model = aiSettings.getEffectiveModelForAgent(it))
        }
        val tu = outcome.response.tokenUsage
        val inT = tu?.inputTokens ?: 0
        val outT = tu?.outputTokens ?: 0
        val pricing = winAgent?.let { PricingCache.getPricing(context, it.provider, it.model) }
        return TitleGenResult(
            title = title,
            inputTokens = inT, outputTokens = outT,
            inputCost = inT * (pricing?.promptPrice ?: 0.0),
            outputCost = outT * (pricing?.completionPrice ?: 0.0),
            durationMs = durationMs,
            traceFile = traceSink.get(),
            model = winAgent?.let { "${it.provider.id}/${it.model}" },
        )
    }

    internal fun kickOffReportTitleGeneration(
        context: Context,
        reportId: String,
        promptText: String,
        aiSettings: Settings,
        /** When true, the report icon is generated right after the title
         *  attempt — so workers/report-icon sees the freshly-stored long
         *  title via @TITLE_LONG@. Fresh-report / regenerate-all sites set
         *  this; a title-only restart leaves a good icon alone. */
        thenIcon: Boolean = false
    ) {
        // Master switch — MANUAL mode = user typed a title themselves;
        // never run the LLM call.
        if (!appViewModel.uiState.value.generalSettings.reportTitleAiOn()) return
        // Two worker prompts → two calls: a ≤25-char short title (list
        // cards) and a ≤50-char long title (top-bar orange line). Each is a
        // random-pick / 429-fallback chain over the same 'workers' swarm.
        val shortPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "workers" && it.name == "report-title-short"
        }
        val longPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "workers" && it.name == "report-title-long"
        }
        val shortUsable = shortPrompt?.workers?.any { aiSettings.resolveWorker(it) != null } == true
        val longUsable = longPrompt?.workers?.any { aiSettings.resolveWorker(it) != null } == true
        if (!shortUsable && !longUsable) return
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            appViewModel.updateRunningInfoJobs { it + "$reportId|title" }
            // Run both calls concurrently.
            val (short, long) = coroutineScope {
                val s = async { runTitlePrompt(context, reportId, shortPrompt, promptText, aiSettings, cap = 25, traceCategory = "workers/report-title-short") }
                val l = async { runTitlePrompt(context, reportId, longPrompt, promptText, aiSettings, cap = 50, traceCategory = "workers/report-title-long") }
                s.await() to l.await()
            }
            if (short == null && long == null) {
                ReportStorage.updateReportTitleError(context, reportId, "title-gen: no worker produced a title")
            } else {
                // Short drives list cards; long the orange line (barTitle =
                // long ?: short). If only the long call succeeded, derive the
                // short from it so the report is never left title-less.
                val shortTitle = short?.title ?: long?.title?.take(25) ?: "AI Report"
                val longTitle = long?.title
                // Sum both calls into the single set of title* cost/token
                // fields (updateReportTitleFromAi adds them in).
                ReportStorage.updateReportTitleFromAi(
                    context, reportId, shortTitle,
                    titleLong = longTitle?.takeIf { it.isNotBlank() },
                    durationMs = (short?.durationMs ?: 0L) + (long?.durationMs ?: 0L),
                    inputTokens = (short?.inputTokens ?: 0) + (long?.inputTokens ?: 0),
                    outputTokens = (short?.outputTokens ?: 0) + (long?.outputTokens ?: 0),
                    inputCost = (short?.inputCost ?: 0.0) + (long?.inputCost ?: 0.0),
                    outputCost = (short?.outputCost ?: 0.0) + (long?.outputCost ?: 0.0),
                    traceFile = short?.traceFile ?: long?.traceFile,
                    model = short?.model ?: long?.model,
                    promptUsed = "report_title"
                )
                // Keep the in-memory UiState in sync so the title row on
                // Manage report updates the moment the calls return.
                appViewModel.updateUiState { st ->
                    if (st.currentReportId == reportId) {
                        st.copy(genericPromptTitle = shortTitle, genericPromptTitleLong = longTitle.orEmpty())
                    } else st
                }
            }
            appViewModel.updateRunningInfoJobs { it - "$reportId|title" }
            appViewModel.updateUiState {
                it.copy(iconRefreshTick = it.iconRefreshTick + 1)
            }
            // Icon is derived from the title's long form — run it after
            // the title attempt so @TITLE_LONG@ reflects the new title.
            if (thenIcon) kickOffIconGeneration(context, reportId, promptText, aiSettings)
        }
    }

    /** Per-agent variant of [kickOffReportTitleGeneration]: runs the
     *  bundled `internal/model_title` prompt against this agent's own
     *  response (via the prompt's pinned Anthropic agent) and writes the
     *  resolved ≤4-word title onto the [ReportAgent]. Fired from
     *  ReportViewModel after a model response succeeds, gated by the
     *  "Generate per model titles" toggle. Best-effort, off the main
     *  thread; failures persist via [ReportStorage.updateReportAgentModelTitleError]. */
    /**
     * Per-model enrichment orchestrator. Decides the per-agent icon/title
     * flow from the two toggles:
     *  - both on  → title first, then derive the icon from the title
     *               (`report_title_icon`); fall back to the response-based
     *               3-tier chain if no usable title.
     *  - icon only → the response-based 3-tier chain (unchanged).
     *  - title only → just the title.
     */
    fun runPerModelEnrichment(
        context: Context, reportId: String, ra: ReportAgent,
        reportPrompt: String, aiSettings: Settings,
        iconOn: Boolean, titleOn: Boolean
    ) {
        when {
            // Title row on → generate + store the model title; chain the icon
            // from it when the icon is on too.
            titleOn -> runModelTitleForAgent(context, reportId, ra, aiSettings, reportPrompt, thenIconFromTitle = iconOn, storeTitle = true)
            // Icon-only → still derive the icon from a title (workers/model-icons
            // needs @TITLE@), but generate that title transiently — never store
            // or surface it, since the per-model title row is off.
            iconOn -> runModelTitleForAgent(context, reportId, ra, aiSettings, reportPrompt, thenIconFromTitle = true, storeTitle = false)
        }
    }

    internal fun runModelTitleForAgent(
        context: Context,
        reportId: String,
        ra: ReportAgent,
        aiSettings: Settings,
        reportPrompt: String = "",
        /** When true, after the title resolves, build the per-agent icon
         *  FROM the title (workers/model-icons); fall back to the response-
         *  based 3-tier chain when no usable title is produced. */
        thenIconFromTitle: Boolean = false,
        /** When false the resolved title is used ONLY to feed the icon
         *  (@TITLE@) and is never written to [ReportAgent.modelTitle] — the
         *  icon-only config, where the per-model title row is hidden. */
        storeTitle: Boolean = true
    ) {
        // Worker-based: random-pick / 429-fallback over workers/model-titles.
        val titlePrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "workers" && it.name == "model-titles"
        } ?: return
        if (titlePrompt.workers.none { aiSettings.resolveWorker(it) != null }) return
        val agentResponse = ra.responseBody.orEmpty()
        if (agentResponse.isBlank()) return
        val resolved = titlePrompt.text.replace("@RESPONSE@", agentResponse)
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            var generatedTitle: String? = null
            withTracerTags(reportId = reportId, category = "workers/model-titles") {
                val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
                val started = System.currentTimeMillis()
                val outcome = withTraceFilenameSink(traceSink) {
                    // No non-blank title line → logical miss → next worker.
                    rvm.workerRunner.run(titlePrompt, resolved, aiSettings, context) { resp ->
                        (resp.analysis ?: "").lineSequence().map { cleanTitleLine(it) }.any { it.isNotBlank() }
                    }
                }
                val durationMs = System.currentTimeMillis() - started
                when (outcome) {
                    is WorkerOutcome.Success -> {
                        val generated = (outcome.response.analysis ?: "")
                            .lineSequence().map { cleanTitleLine(it) }
                            .firstOrNull { it.isNotBlank() }.orEmpty().take(325)
                        if (generated.isBlank()) {
                            if (storeTitle) ReportStorage.updateReportAgentModelTitleError(
                                context, reportId, ra.agentId, "empty title"
                            )
                        } else {
                            generatedTitle = generated
                            if (storeTitle) {
                                val winAgent = aiSettings.resolveWorker(outcome.worker)?.let {
                                    it.copy(model = aiSettings.getEffectiveModelForAgent(it))
                                }
                                val tu = outcome.response.tokenUsage
                                val inT = tu?.inputTokens ?: 0
                                val outT = tu?.outputTokens ?: 0
                                val pricing = winAgent?.let { PricingCache.getPricing(context, it.provider, it.model) }
                                val inC = inT * (pricing?.promptPrice ?: 0.0)
                                val outC = outT * (pricing?.completionPrice ?: 0.0)
                                ReportStorage.updateReportAgentModelTitle(
                                    context, reportId, ra.agentId, generated,
                                    model = winAgent?.let { "${it.provider.id}/${it.model}" },
                                    inputTokens = inT, outputTokens = outT,
                                    inputCost = inC, outputCost = outC,
                                    traceFile = traceSink.get(),
                                    promptUsed = "model_title",
                                    durationMs = durationMs
                                )
                                if ((inT > 0 || outT > 0) && winAgent != null) {
                                    appViewModel.settingsPrefs.updateUsageStatsAsync(
                                        winAgent.provider, winAgent.model, inT, outT, kind = "title"
                                    )
                                }
                            }
                        }
                    }
                    else -> if (storeTitle) ReportStorage.updateReportAgentModelTitleError(
                        context, reportId, ra.agentId,
                        if (outcome is WorkerOutcome.AllRateLimited) "model-title: all workers rate-limited"
                        else "model-title: no worker produced a title"
                    )
                }
                appViewModel.updateUiState {
                    it.copy(iconRefreshTick = it.iconRefreshTick + 1)
                }
            }
            // Chain the icon: derive it from the title via the worker
            // engine (workers/model-icons). No usable title — or no worker
            // produced an emoji — leaves the agent icon-less; there is no
            // longer a response-based fallback chain.
            if (thenIconFromTitle) {
                generatedTitle?.let { generateIconFromTitle(context, reportId, ra, it, aiSettings) }
            }
        }
    }

    /** Build a per-agent icon FROM its model-title via the bundled
     *  `internal/report_title_icon` prompt (fixed Anthropic agent). Stores
     *  the emoji + cost on the agent's icon fields exactly like the 3-tier
     *  chain. Returns true on a committed emoji, false on any failure (the
     *  caller then falls back to the response-based chain). */
    private suspend fun generateIconFromTitle(
        context: Context, reportId: String, ra: ReportAgent,
        title: String, aiSettings: Settings
    ): Boolean {
        // Worker-based: random-pick / 429-fallback over workers/model-icons.
        val prompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "workers" && it.name == "model-icons"
        } ?: return false
        if (prompt.workers.none { aiSettings.resolveWorker(it) != null }) return false
        // Reset this agent's icon fields + iconCalls so a re-fire
        // (regenerate) replaces rather than accumulates — matches the
        // 3-tier chain's clearReportAgentIconState at its own start.
        ReportStorage.clearReportAgentIconState(context, reportId, ra.agentId)
        return withTracerTags(reportId = reportId, category = "workers/model-icons") {
            val started = System.currentTimeMillis()
            val resolved = prompt.text.replace("@TITLE@", title)
            // Capture the trace filename of the winning icon call so the
            // Model-response screen's 🐞 next to the big icon can deep-link
            // to the exact call that decided this icon (the worker runs on
            // its own model, so a category+agent-model lookup can't find it).
            val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
            // No parseable emoji is a logical miss — advance to the next worker
            // rather than accepting a 200 that leaves the agent icon-less.
            val outcome = withTraceFilenameSink(traceSink) {
                rvm.workerRunner.run(prompt, resolved, aiSettings, context) {
                    extractFirstEmoji(it.analysis) != null
                }
            }
            val durationMs = System.currentTimeMillis() - started
            if (outcome is WorkerOutcome.Success) {
                val winAgent = aiSettings.resolveWorker(outcome.worker)?.let {
                    it.copy(model = aiSettings.getEffectiveModelForAgent(it))
                }
                val tu = outcome.response.tokenUsage
                val inT = tu?.inputTokens ?: 0
                val outT = tu?.outputTokens ?: 0
                val emoji = extractFirstEmoji(outcome.response.analysis)
                if (winAgent != null) {
                    recordTierCall(
                        context, reportId, ra.agentId, tier = 2,
                        provider = winAgent.provider, model = winAgent.model,
                        inT = inT, outT = outT, durationMs = durationMs,
                        success = emoji != null,
                        type = "workers/model-icons"
                    )
                }
                if (emoji != null) {
                    ReportStorage.setReportAgentIconAndTier(
                        context, reportId, ra.agentId, emoji,
                        winningTier = null, promptUsed = "report_title_icon",
                        traceFile = traceSink.get()
                    )
                    appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
                    true
                } else false
            } else false
        }
    }

    /** Two-call language flow. First call (bundled `internal/language`
     *  prompt) detects the report prompt's source language; on
     *  success, schedules a second call (bundled `icons/language`
     *  prompt) that picks a fitting emoji for that detected language. The two calls surface as separate
     *  rows in the cost table — type `"language"` for detection,
     *  `"language-icon"` for the emoji. Same gate / agent-resolution
     *  / recompose-tick pattern as [kickOffIconGeneration]. */
    internal fun kickOffLanguageGeneration(
        context: Context,
        reportId: String,
        promptText: String,
        aiSettings: Settings
    ) {
        if (!appViewModel.uiState.value.generalSettings.reportLanguageOn()) return
        // Worker-based: a single workers/report-language call returns BOTH the
        // language name and a fitting emoji (the prompt asks for a
        // "language:" / "icon:" two-line reply), via the random-pick /
        // 429-fallback engine — no chained second call.
        val languagePrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "workers" && it.name == "report-language"
        } ?: return
        if (languagePrompt.workers.none { aiSettings.resolveWorker(it) != null }) return
        val resolved = languagePrompt.text.replace("@PROMPT@", promptText)
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            withTracerTags(reportId = reportId, category = "workers/report-language") {
                val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
                appViewModel.updateRunningInfoJobs { it + "$reportId|language" }
                val started = System.currentTimeMillis()
                val outcome = withTraceFilenameSink(traceSink) {
                    // No parseable "language:" line is a logical miss → next worker.
                    rvm.workerRunner.run(languagePrompt, resolved, aiSettings, context) {
                        parseLanguageDetectionResponse(it.analysis) != null
                    }
                }
                val durationMs = System.currentTimeMillis() - started
                when (outcome) {
                    is WorkerOutcome.Success -> {
                        val analysis = outcome.response.analysis
                        val name = parseLanguageDetectionResponse(analysis)
                        if (name.isNullOrBlank()) {
                            ReportStorage.updateReportLanguageError(context, reportId, "unparseable response")
                        } else {
                            // Emoji from the `icon:` line; fall back to scanning the whole reply.
                            val iconLine = analysis?.lineSequence()?.firstOrNull { it.trim().startsWith("icon", ignoreCase = true) }
                            val emoji = extractFirstEmoji(iconLine ?: analysis.orEmpty()) ?: "🌐"
                            val winAgent = aiSettings.resolveWorker(outcome.worker)?.let {
                                it.copy(model = aiSettings.getEffectiveModelForAgent(it))
                            }
                            val tu = outcome.response.tokenUsage
                            val inT = tu?.inputTokens ?: 0
                            val outT = tu?.outputTokens ?: 0
                            val pricing = winAgent?.let { PricingCache.getPricing(context, it.provider, it.model) }
                            val inC = inT * (pricing?.promptPrice ?: 0.0)
                            val outC = outT * (pricing?.completionPrice ?: 0.0)
                            // One call → attribute its cost AND duration to
                            // the detection row; the icon row stays 0 for
                            // both. ReportInfoScreen's total-API-time sums
                            // languageDurationMs + languageIconDurationMs, so
                            // writing the same duration to both would count
                            // this one call's time twice (the cost was
                            // already handled this way — mirror it).
                            ReportStorage.updateReportLanguageDetect(
                                context, reportId,
                                name = name,
                                inputTokens = inT, outputTokens = outT,
                                inputCost = inC, outputCost = outC,
                                traceFile = traceSink.get(),
                                rawResponse = analysis,
                                durationMs = durationMs
                            )
                            ReportStorage.updateReportLanguageIcon(
                                context, reportId,
                                icon = emoji,
                                model = winAgent?.let { "${it.provider.id}/${it.model}" },
                                inputTokens = 0, outputTokens = 0,
                                inputCost = 0.0, outputCost = 0.0,
                                traceFile = traceSink.get(),
                                rawResponse = analysis,
                                promptUsed = "language",
                                durationMs = 0L
                            )
                        }
                    }
                    else -> ReportStorage.updateReportLanguageError(
                        context, reportId,
                        if (outcome is WorkerOutcome.AllRateLimited) "language: all workers rate-limited"
                        else "language: no worker produced a result"
                    )
                }
                appViewModel.updateUiState {
                    it.copy(iconRefreshTick = it.iconRefreshTick + 1)
                }
                appViewModel.updateRunningInfoJobs { it - "$reportId|language" }
            }
        }
    }

    /** Pull the `language: …` line out of the detection model's
     *  reply. Tolerant of leading/trailing whitespace and
     *  case-variant field names. Returns null when no parseable
     *  language line was found. */
    private fun parseLanguageDetectionResponse(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return Regex("(?im)^\\s*language\\s*[:=]\\s*(.+?)\\s*$")
            .find(raw)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    /** Background helper that runs the bundled `workers/second-meta` prompt
     *  (random-pick / 429-fallback worker engine) and caches a one-emoji
     *  result for [prompt] in [InternalPromptIconCache]. Idempotent: bails
     *  when the master switch is off, when the cache already has a value, or
     *  when another call for the same `(name, title)` is already in flight.
     *  Lives on AppViewModel.viewModelScope so it survives the user
     *  navigating away from whatever screen kicked it off. */
    fun kickOffInternalPromptIcon(
        context: Context,
        prompt: InternalPrompt,
        aiSettings: Settings
    ) {
        if (!appViewModel.uiState.value.generalSettings.metaIconsOn()) return
        if (prompt.name.isBlank()) return
        if (InternalPromptIconCache.get(prompt.name, prompt.title) != null) return
        // Atomically claim the slot; if another caller is already
        // working on the same (name, title) key, bail.
        if (!InternalPromptIconCache.markInFlight(prompt.name, prompt.title)) return

        val iconPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "workers" && it.name.equals("second-meta", ignoreCase = true)
        }
        if (iconPrompt == null || iconPrompt.workers.none { aiSettings.resolveWorker(it) != null }) {
            AppLog.w("InternalPromptIcon", "workers/second-meta not configured — skipping")
            InternalPromptIconCache.clearInFlight(prompt.name, prompt.title)
            return
        }
        val resolved = iconPrompt.text
            .replace("@NAME@", prompt.name)
            .replace("@TITLE@", prompt.title)

        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            withTracerTags(category = "workers/second-meta") {
              try {
                val outcome = rvm.workerRunner.run(iconPrompt, resolved, aiSettings, context) {
                    extractFirstEmoji(it.analysis) != null
                }
                if (outcome is WorkerOutcome.Success) {
                    val emoji = extractFirstEmoji(outcome.response.analysis) ?: "📝"
                    val winAgent = aiSettings.resolveWorker(outcome.worker)?.let {
                        it.copy(model = aiSettings.getEffectiveModelForAgent(it))
                    }
                    val tu = outcome.response.tokenUsage
                    val inT = tu?.inputTokens ?: 0
                    val outT = tu?.outputTokens ?: 0
                    val pricing = winAgent?.let { PricingCache.getPricing(context, it.provider, it.model) }
                    val inC = inT * (pricing?.promptPrice ?: 0.0)
                    val outC = outT * (pricing?.completionPrice ?: 0.0)
                    InternalPromptIconCache.recordInitial(
                        name = prompt.name, title = prompt.title,
                        emoji = emoji,
                        providerId = winAgent?.provider?.id ?: "", model = winAgent?.model ?: "",
                        promptText = resolved,
                        responseText = outcome.response.analysis.orEmpty(),
                        inputTokens = inT, outputTokens = outT,
                        inputCost = inC, outputCost = outC,
                        promptName = "second-meta"
                    )
                    if ((inT > 0 || outT > 0) && winAgent != null) {
                        appViewModel.settingsPrefs.updateUsageStatsAsync(
                            winAgent.provider, winAgent.model, inT, outT, kind = "icon"
                        )
                    }
                    appViewModel.updateUiState {
                        it.copy(iconRefreshTick = it.iconRefreshTick + 1)
                    }
                } else {
                    AppLog.w(
                        "InternalPromptIcon",
                        "no worker produced an icon for name='${prompt.name}'"
                    )
                }
              } finally {
                // finally, not trailing: a throw / cancellation must still
                // release the in-flight key or this (name,title) icon never
                // regenerates again until app restart.
                InternalPromptIconCache.clearInFlight(prompt.name, prompt.title)
              }
            }
        }
    }

    /** Tracks the active fan-out job per `(name + U+001F + title)` key so
     *  [restartInternalPromptIconFanOut] can cancel-and-join an
     *  in-flight batch without leaking coroutines. Same pattern as
     *  [rvm.agentIconFanOutJobs] for per-agent runs. */
    private val internalPromptIconFanOutJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private fun internalPromptIconKey(prompt: InternalPrompt): String =
        prompt.name + "\u001F" + prompt.title

    /** Fan-out of `icons/meta_alt` across user-picked [models]
     *  for one `InternalPrompt`. Each call:
     *  - Substitutes `@NAME@` + `@TITLE@`.
     *  - Calls `analyzeWithAgent` on (provider, model).
     *  - Bumps cumulative cost on [InternalPromptIconCache] and
     *    posts to UsageStats with kind="icon".
     *  - Captures the per-call (promptText, responseText) so
     *    [pickInternalPromptIcon] can write them onto the cache
     *    entry without an additional round-trip.
     *  - Flips the matching [IconCandidate] to Done / Error.
     *
     *  Mirrors [startAgentIconFanOut] / [startIconFanOut]. */
    fun startInternalPromptIconFanOut(
        context: Context,
        prompt: InternalPrompt,
        models: List<ReportModel>,
        aiSettings: Settings,
        /** Report whose SecondaryResult the alt is launched from.
         *  Used to attribute per-call cost into [Report.iconCalls]
         *  (so the cost-table per-call breakdown shows the alt
         *  rows) AND to bump the SR's own cost so the row on
         *  Report-Manage reflects the alt spend. The SR is the
         *  first row on the report whose metaPromptName / metaPromptId
         *  matches [prompt]. Null skips both — keeps legacy
         *  call-sites compiling. */
        reportId: String? = null,
        paramsIds: List<String> = emptyList(),
        systemPromptId: String? = null
    ) {
        if (prompt.name.isBlank()) return
        val altSecondaryParams = resolveSecondaryParams(
            appViewModel.uiState.value.generalSettings, aiSettings, paramsIds, systemPromptId, prompt
        )
        // Find-alternative-icons runs the self-contained `alt/meta`
        // template — the "give me a different emoji" nudge up front
        // (so the model reads the constraint before the template body)
        // with the base wording merged in below it.
        val altPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "alt" && it.name.equals("meta", ignoreCase = true)
        } ?: run {
            AppLog.w("InternalPromptIconAlt", "alt/meta not configured — skipping fan-out")
            return
        }
        val unique = models.distinctBy { "${it.provider.id}:${it.model}" }
        if (unique.isEmpty()) return
        val resolved = altPrompt.text
            .replace("@NAME@", prompt.name)
            .replace("@TITLE@", prompt.title)
        val key = internalPromptIconKey(prompt)
        // Resolve the SR that owns the per-row attribution for this
        // (report, prompt) pair — the first SR whose metaPromptName /
        // metaPromptId matches. Null when there's no matching row on
        // this report (e.g., the user is finding alt icons for a
        // prompt that hasn't been run on this report yet) or when
        // reportId wasn't supplied.
        val attributedSecondaryId: String? = reportId?.let { rid ->
            SecondaryResultStorage.listForReport(context, rid)
                .firstOrNull { sr ->
                    (sr.metaPromptId != null && sr.metaPromptId == prompt.id) ||
                    (!sr.metaPromptName.isNullOrBlank() && sr.metaPromptName == prompt.name)
                }
                ?.id
        }

        // Pre-populate Running rows so the Alternative icons screen
        // shows ⏳ for every pair the moment it opens.
        appViewModel.updateInternalPromptIconFanOut(key) {
            unique.map { IconCandidate.Running(it.provider, it.model) }
        }

        val outer = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            unique.forEach { item ->
                launch(Dispatchers.IO) {
                    withTracerTags(category = "alt/meta") {
                        val agent = Agent(
                            id = "internal-prompt-icon-alt",
                            name = "internal-prompt-icon-alt",
                            provider = item.provider,
                            model = item.model,
                            apiKey = aiSettings.getApiKey(item.provider)
                        )
                        val baseUrl = aiSettings.getEffectiveEndpointUrl(item.provider)
                        runCatching {
                            val response = appViewModel.repository.analyzeWithAgent(
                                agent, "", resolved, altSecondaryParams,
                                null, context, baseUrl
                            )
                            val tu = response.tokenUsage
                            val pricing = PricingCache.getPricing(context, item.provider, item.model)
                            val inT = tu?.inputTokens ?: 0
                            val outT = tu?.outputTokens ?: 0
                            val inC = inT * pricing.promptPrice
                            val outC = outT * pricing.completionPrice
                            if (inT > 0 || outT > 0) {
                                InternalPromptIconCache.bumpCost(
                                    prompt.name, prompt.title, inT, outT, inC, outC
                                )
                                appViewModel.settingsPrefs.updateUsageStatsAsync(
                                    item.provider, item.model, inT, outT, kind = "icon"
                                )
                                // Per-report attribution: bump the SR's
                                // own cost (so its Report-Manage row
                                // includes the alt spend) AND append
                                // an IconCallRecord (so the cost
                                // table's per-call breakdown shows a
                                // `meta_alt` row).
                                if (reportId != null) {
                                    if (attributedSecondaryId != null) {
                                        SecondaryResultStorage.bumpResultInputOutputCost(
                                            context, reportId, attributedSecondaryId,
                                            inputTokens = inT, outputTokens = outT,
                                            inputCost = inC, outputCost = outC
                                        )
                                    }
                                    ReportStorage.appendIconCall(context, reportId, IconCallRecord(
                                        agentId = "", tier = 0,
                                        provider = item.provider.id, model = item.model,
                                        pricingTier = pricing.source,
                                        inputTokens = inT, outputTokens = outT,
                                        inputCost = inC, outputCost = outC,
                                        success = response.error == null,
                                        type = "alt/meta",
                                        attributedToSecondaryId = attributedSecondaryId
                                    ))
                                }
                            }
                            // Capture promptText + responseText so a
                            // subsequent pickInternalPromptIcon can
                            // write them onto the cache entry.
                            appViewModel.setInternalPromptIconCallTexts(
                                key, item.provider.id, item.model,
                                resolved, response.analysis.orEmpty()
                            )
                            val callCost = inC + outC
                            val emoji = if (response.error == null) {
                                extractFirstEmoji(response.analysis) ?: "📝"
                            } else null
                            appViewModel.updateInternalPromptIconFanOut(key) { list ->
                                list.map { c ->
                                    if (c.provider.id == item.provider.id && c.model == item.model) {
                                        if (emoji != null) {
                                            IconCandidate.Done(item.provider, item.model, emoji, callCost)
                                        } else {
                                            IconCandidate.Error(
                                                item.provider, item.model,
                                                response.error ?: "no emoji extracted",
                                                callCost
                                            )
                                        }
                                    } else c
                                }
                            }
                        }.onFailure { e ->
                            AppLog.w(
                                "InternalPromptIconAlt",
                                "exception for ${item.provider.id}/${item.model}: ${e.message}"
                            )
                            appViewModel.updateInternalPromptIconFanOut(key) { list ->
                                list.map { c ->
                                    if (c.provider.id == item.provider.id && c.model == item.model) {
                                        IconCandidate.Error(
                                            item.provider, item.model,
                                            e.message ?: e.javaClass.simpleName, 0.0
                                        )
                                    } else c
                                }
                            }
                        }
                        appViewModel.updateUiState {
                            it.copy(iconRefreshTick = it.iconRefreshTick + 1)
                        }
                    }
                }
            }
        }
        val previous = internalPromptIconFanOutJobs.put(key, outer)
        previous?.cancel()
        outer.invokeOnCompletion { internalPromptIconFanOutJobs.remove(key, outer) }
    }

    /** Commit a picked candidate to the cache. Writes the picked
     *  emoji + the candidate's (provider, model, promptText,
     *  responseText) so the Meta-icon detail screen renders that
     *  call's provenance. Cost is **not** touched — bumps already
     *  happened in `startInternalPromptIconFanOut` for each
     *  candidate call. */
    fun pickInternalPromptIcon(
        context: Context,
        prompt: InternalPrompt,
        candidate: IconCandidate.Done,
        @Suppress("UNUSED_PARAMETER") aiSettings: Settings
    ) {
        val key = internalPromptIconKey(prompt)
        val captured = appViewModel.getInternalPromptIconCallTexts(
            key, candidate.provider.id, candidate.model
        ) ?: ("" to "")
        InternalPromptIconCache.pickAlternative(
            name = prompt.name, title = prompt.title,
            emoji = candidate.emoji,
            providerId = candidate.provider.id, model = candidate.model,
            promptText = captured.first,
            responseText = captured.second,
            promptName = "meta_alt"
        )
        appViewModel.updateUiState {
            it.copy(iconRefreshTick = it.iconRefreshTick + 1)
        }
    }

    /** Cancel any in-flight fan-out and drop the candidate list.
     *  The user just tapped "Restart" on the Alternative icons
     *  screen — start over from a clean slate. */
    fun restartInternalPromptIconFanOut(prompt: InternalPrompt) {
        val key = internalPromptIconKey(prompt)
        internalPromptIconFanOutJobs.remove(key)?.cancel()
        appViewModel.clearInternalPromptIconFanOut(key)
    }

    /** Commit a picked alt-icon to a single SecondaryResult row
     *  instead of the shared [InternalPromptIconCache] entry. Used
     *  when the user opened the Find-alternative-icons flow from a
     *  per-row Meta tile on the View screen: the per-row override
     *  on disk wins over the cache entry in ViewAiReportScreen's
     *  metaTiles fallback chain, so two tiles sharing a
     *  metaPromptName can carry distinct icons. */
    fun pickMetaRowIcon(
        context: Context,
        reportId: String,
        rowId: String,
        emoji: String
    ) {
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            SecondaryResultStorage.setRowIcon(context, reportId, rowId, emoji)
            appViewModel.updateUiState {
                it.copy(iconRefreshTick = it.iconRefreshTick + 1)
            }
        }
    }

    // ── Per-fan-out-pair icon Find-alt ──────────────────────────
    // Mirrors startAgentIconFanOut for fan-out pairs. Composes the
    // bundled `fan_out_alt` (the nudge) FIRST, then `fan_out_2` (the
    // one-shot template), substitutes @QUESTION@ / @SOURCE_RESPONSE@ /
    // @META_PROMPT@ / @RESPONSE@, fires one call per picked
    // (provider, model), attributes cost to the pair's SR + the
    // report's iconCalls audit log, and commits the picked emoji
    // via setFanOutIconAndTier with promptUsed = "fan_out_alt".
    internal val pairIconFanOutJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private fun pairIconJobKey(reportId: String, pairId: String): String =
        "$reportId|$pairId"

    fun startPairIconFanOut(
        context: Context,
        reportId: String,
        pairId: String,
        models: List<ReportModel>,
        aiSettings: Settings
    ) {
        val altPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "alt" && it.name == "fan_out"
        } ?: run {
            AppLog.w("PairIconAlt", "alt/fan_out prompt not found — skipping (pair=$pairId)")
            return
        }
        val unique = models.distinctBy { "${it.provider.id}:${it.model}" }
        if (unique.isEmpty()) return
        appViewModel.updatePairIconFanOut(pairId) {
            unique.map { IconCandidate.Running(it.provider, it.model) }
        }
        val outer = appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            val pair = SecondaryResultStorage.listForReport(context, reportId)
                .firstOrNull { it.id == pairId } ?: return@launch
            // Fan-out pairs carry a source agent; Rerank / Moderation /
            // non-fan-out Meta rows don't. Keep going either way — the
            // @SOURCE_RESPONSE@ / @META_PROMPT@ tokens just resolve empty
            // for a sourceless row, so this same flow re-finds the icon
            // for ANY SecondaryResult (keyed on its row id).
            val sourceAgentId = pair.fanOutSourceAgentId
            val report = ReportStorage.getReport(context, reportId) ?: return@launch
            val sourceAgent = sourceAgentId?.let { sid -> report.agents.firstOrNull { it.agentId == sid } }
            val metaPrompt = pair.metaPromptId?.let { mid ->
                aiSettings.internalPrompts.firstOrNull { it.id == mid }
            }
            val resolved = altPrompt.text
                .replace("@QUESTION@", report.prompt)
                .replace("@SOURCE_RESPONSE@", sourceAgent?.responseBody.orEmpty())
                .replace("@META_PROMPT@", metaPrompt?.text.orEmpty())
                .replace("@RESPONSE@", pair.content.orEmpty())
            unique.forEach { item ->
                launch {
                    val host = providerHost(item.provider)
                    val releaser = ProviderThrottle.acquire(host)
                    try {
                        withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                            withTracerTags(reportId = reportId, category = "alt/fan_out") {
                                runCatching {
                                    val syntheticAgent = Agent(
                                        id = "pair-icon-alt-${pairId}-${item.provider.id}-${item.model}",
                                        name = item.model,
                                        provider = item.provider,
                                        model = item.model,
                                        apiKey = aiSettings.getApiKey(item.provider)
                                    )
                                    val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(syntheticAgent)
                                    val response = appViewModel.repository.analyzeWithAgent(
                                        syntheticAgent, "", resolved, AgentParameters(),
                                        null, context, baseUrl
                                    )
                                    val tu = response.tokenUsage
                                    val pricing = PricingCache.getPricing(context, item.provider, item.model)
                                    val inT = tu?.inputTokens ?: 0
                                    val outT = tu?.outputTokens ?: 0
                                    val inC = inT * pricing.promptPrice
                                    val outC = outT * pricing.completionPrice
                                    if (inT > 0 || outT > 0) {
                                        // Bump the pair's per-icon cost
                                        // counters so the L2/L3 row total +
                                        // Icon-lookup "Cost" line reflect
                                        // every alt attempt.
                                        SecondaryResultStorage.bumpFanOutIconCost(
                                            context, reportId, pairId,
                                            inputTokens = inT, outputTokens = outT,
                                            inputCost = inC, outputCost = outC
                                        )
                                        appViewModel.settingsPrefs.updateUsageStatsAsync(
                                            item.provider, item.model, inT, outT, kind = "icon"
                                        )
                                        // Per-call audit row labelled
                                        // `icon_fan_out_alt`, attributed to
                                        // the SR so the cost-table per-call
                                        // breakdown shows alt rows on the
                                        // owning pair.
                                        ReportStorage.appendIconCall(context, reportId, IconCallRecord(
                                            agentId = pairId, tier = 0,
                                            provider = item.provider.id, model = item.model,
                                            pricingTier = pricing.source,
                                            inputTokens = inT, outputTokens = outT,
                                            inputCost = inC, outputCost = outC,
                                            success = response.error == null,
                                            type = "alt/fan_out",
                                            attributedToSecondaryId = pairId
                                        ))
                                    }
                                    val totalCost = inC + outC
                                    if (response.error == null) {
                                        val emoji = extractFirstEmoji(response.analysis) ?: "📝"
                                        appViewModel.updatePairIconFanOut(pairId) { list ->
                                            list.map { c ->
                                                if (c.provider.id == item.provider.id && c.model == item.model)
                                                    IconCandidate.Done(item.provider, item.model, emoji, totalCost)
                                                else c
                                            }
                                        }
                                    } else {
                                        appViewModel.updatePairIconFanOut(pairId) { list ->
                                            list.map { c ->
                                                if (c.provider.id == item.provider.id && c.model == item.model)
                                                    IconCandidate.Error(item.provider, item.model, response.error, totalCost)
                                                else c
                                            }
                                        }
                                    }
                                    appViewModel.updateUiState {
                                        it.copy(iconRefreshTick = it.iconRefreshTick + 1)
                                    }
                                }.onFailure { e ->
                                    appViewModel.updatePairIconFanOut(pairId) { list ->
                                        list.map { c ->
                                            if (c.provider.id == item.provider.id && c.model == item.model)
                                                IconCandidate.Error(item.provider, item.model, e.message ?: "icon-gen failed", 0.0)
                                            else c
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        releaser.release()
                    }
                }
            }
        }
        val key = pairIconJobKey(reportId, pairId)
        pairIconFanOutJobs.put(key, outer)?.cancel()
        outer.invokeOnCompletion { pairIconFanOutJobs.remove(key, outer) }
    }

    /** Commit a user-picked alt emoji to the fan-out pair. winningTier
     *  stays null — the alt isn't a tier-N hit; the `fan_out_alt`
     *  promptUsed stamp is the source-of-truth label for the Icon
     *  lookup screen's subject row. */
    fun pickPairIconAlternative(
        context: Context,
        reportId: String,
        pairId: String,
        emoji: String
    ) {
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            SecondaryResultStorage.setFanOutIconAndTier(
                context, reportId, pairId,
                icon = emoji, winningTier = null,
                promptUsed = "fan_out_alt"
            )
            appViewModel.updateUiState {
                it.copy(iconRefreshTick = it.iconRefreshTick + 1)
            }
        }
    }

    fun restartPairIconFanOut(reportId: String, pairId: String) {
        pairIconFanOutJobs.remove(pairIconJobKey(reportId, pairId))?.cancel()
        appViewModel.clearPairIconFanOut(pairId)
    }

    // ── Per-fan-out-pair title Find-alt ─────────────────────────
    // Sibling of [startPairIconFanOut] but for titles. Composes the
    // bundled `alt/model_title` prompt (substitutes @RESPONSE@ with
    // the pair's response), fires one call per picked (provider,
    // model), attributes cost to the pair's SR (via bumpFanOutTitleCost,
    // which also stamps titleModel) + the report's iconCalls audit log,
    // and commits the picked title via setFanOutTitle with
    // promptUsed = "model_title_alt". Candidates land in
    // [AppViewModel.pairTitleFanOutByPair].
    fun startPairTitleFanOut(
        context: Context,
        reportId: String,
        pairId: String,
        models: List<ReportModel>,
        aiSettings: Settings,
        paramsIds: List<String> = emptyList(),
        systemPromptId: String? = null
    ) {
        val altPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "alt" && it.name == "model_title"
        } ?: run {
            AppLog.w("PairTitleAlt", "alt/model_title prompt not found — skipping (pair=$pairId)")
            return
        }
        val unique = models.distinctBy { "${it.provider.id}:${it.model}" }
        if (unique.isEmpty()) return
        appViewModel.updatePairTitleFanOut(pairId) {
            unique.map { TitleCandidate.Running(it.provider, it.model) }
        }
        val outer = appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            val pair = SecondaryResultStorage.listForReport(context, reportId)
                .firstOrNull { it.id == pairId } ?: return@launch
            val resolved = altPrompt.text.replace("@RESPONSE@", pair.content.orEmpty())
            unique.forEach { item ->
                launch {
                    fun place(c: TitleCandidate) = appViewModel.updatePairTitleFanOut(pairId) { list ->
                        list.map { if (it.provider.id == item.provider.id && it.model == item.model) c else it }
                    }
                    val releaser = ProviderThrottle.acquire(providerHost(item.provider))
                    try {
                        withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                            withTracerTags(reportId = reportId, category = "alt/model_title") {
                                runCatching {
                                    val syntheticAgent = Agent(
                                        id = "pair-title-alt-${pairId}-${item.provider.id}-${item.model}",
                                        name = item.model,
                                        provider = item.provider,
                                        model = item.model,
                                        apiKey = aiSettings.getApiKey(item.provider)
                                    )
                                    val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(syntheticAgent)
                                    val params = resolveSecondaryParams(
                                        appViewModel.uiState.value.generalSettings, aiSettings, paramsIds, systemPromptId, altPrompt
                                    )
                                    val response = appViewModel.repository.analyzeWithAgent(
                                        syntheticAgent, "", resolved, params, null, context, baseUrl
                                    )
                                    val tu = response.tokenUsage
                                    val pricing = PricingCache.getPricing(context, item.provider, item.model)
                                    val inT = tu?.inputTokens ?: 0
                                    val outT = tu?.outputTokens ?: 0
                                    val inC = inT * pricing.promptPrice
                                    val outC = outT * pricing.completionPrice
                                    if (inT > 0 || outT > 0) {
                                        SecondaryResultStorage.bumpFanOutTitleCost(
                                            context, reportId, pairId,
                                            inputTokens = inT, outputTokens = outT,
                                            inputCost = inC, outputCost = outC,
                                            model = "${item.provider.id}/${item.model}"
                                        )
                                        appViewModel.settingsPrefs.updateUsageStatsAsync(
                                            item.provider, item.model, inT, outT, kind = "title"
                                        )
                                        ReportStorage.appendIconCall(context, reportId, IconCallRecord(
                                            agentId = pairId, tier = 0,
                                            provider = item.provider.id, model = item.model,
                                            pricingTier = pricing.source,
                                            inputTokens = inT, outputTokens = outT,
                                            inputCost = inC, outputCost = outC,
                                            success = response.error == null,
                                            type = "alt/model_title",
                                            attributedToSecondaryId = pairId
                                        ))
                                    }
                                    val totalCost = inC + outC
                                    val title = cleanTitle(response.analysis)
                                    if (response.error == null && title.isNotEmpty())
                                        place(TitleCandidate.Done(item.provider, item.model, title, totalCost))
                                    else
                                        place(TitleCandidate.Error(item.provider, item.model, response.error ?: "empty response", totalCost))
                                    appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
                                }.onFailure { e ->
                                    place(TitleCandidate.Error(item.provider, item.model, e.message ?: "title-gen failed", 0.0))
                                }
                            }
                        }
                    } finally {
                        releaser.release()
                    }
                }
            }
        }
        rvm.registerIconFanOutJob("pt:$pairId", outer)
    }

    /** Commit a user-picked alt title to the fan-out pair. titleModel
     *  was already stamped by [bumpFanOutTitleCost] during the fan-out,
     *  so the L3 META "Meta model" line reflects the picked worker once
     *  the iconRefreshTick bump triggers the screen's disk re-read. */
    fun pickPairTitleAlternative(
        context: Context,
        reportId: String,
        pairId: String,
        title: String
    ) {
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            SecondaryResultStorage.setFanOutTitle(
                context, reportId, pairId, title,
                promptUsed = "model_title_alt"
            )
            appViewModel.updateUiState {
                it.copy(iconRefreshTick = it.iconRefreshTick + 1)
            }
        }
    }

    fun restartPairTitleFanOut(reportId: String, pairId: String) {
        rvm.iconFanOutJobs.remove("pt:$pairId")?.cancel()
        appViewModel.clearPairTitleFanOut(pairId)
    }

    // ── Translation icons ───────────────────────────────────────
    // Sibling flow to the per-`InternalPrompt` icon flow above.
    // Stores per-language entries in [InternalPromptIconCache]
    // under a synthetic `(name = "translation_icon", title =
    // language)` key, reusing the cache + fan-out maps verbatim.
    // The bundled `internal/translation_icon` prompt substitutes
    // `@LANGUAGE@` with the row's target language name.

    private fun translationIconKey(language: String): String =
        "translation_icon" + "" + language

    /** Background helper that runs the bundled `workers/translation-icon`
     *  prompt (random-pick / 429-fallback worker engine) and caches a
     *  one-emoji result for [language] in [InternalPromptIconCache].
     *  Idempotent (same dedupe rules as [kickOffInternalPromptIcon]).
     *  Bails when the metadata-icons master switch is off. */
    fun kickOffTranslationIcon(
        context: Context,
        language: String,
        aiSettings: Settings
    ) {
        if (!appViewModel.uiState.value.generalSettings.metaIconsOn()) return
        if (language.isBlank()) return
        if (InternalPromptIconCache.get("translation_icon", language) != null) return
        if (!InternalPromptIconCache.markInFlight("translation_icon", language)) return

        val iconPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "workers" && it.name.equals("translation-icon", ignoreCase = true)
        }
        if (iconPrompt == null || iconPrompt.workers.none { aiSettings.resolveWorker(it) != null }) {
            AppLog.w("TranslationIcon", "workers/translation-icon not configured — skipping")
            InternalPromptIconCache.clearInFlight("translation_icon", language)
            return
        }
        val resolved = iconPrompt.text.replace("@LANGUAGE@", language)

        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            withTracerTags(category = "workers/translation-icon") {
              try {
                val outcome = rvm.workerRunner.run(iconPrompt, resolved, aiSettings, context) {
                    extractFirstEmoji(it.analysis) != null
                }
                if (outcome is WorkerOutcome.Success) {
                    val emoji = extractFirstEmoji(outcome.response.analysis) ?: "📝"
                    val winAgent = aiSettings.resolveWorker(outcome.worker)?.let {
                        it.copy(model = aiSettings.getEffectiveModelForAgent(it))
                    }
                    val tu = outcome.response.tokenUsage
                    val inT = tu?.inputTokens ?: 0
                    val outT = tu?.outputTokens ?: 0
                    val pricing = winAgent?.let { PricingCache.getPricing(context, it.provider, it.model) }
                    val inC = inT * (pricing?.promptPrice ?: 0.0)
                    val outC = outT * (pricing?.completionPrice ?: 0.0)
                    InternalPromptIconCache.recordInitial(
                        name = "translation_icon", title = language,
                        emoji = emoji,
                        providerId = winAgent?.provider?.id ?: "", model = winAgent?.model ?: "",
                        promptText = resolved,
                        responseText = outcome.response.analysis.orEmpty(),
                        inputTokens = inT, outputTokens = outT,
                        inputCost = inC, outputCost = outC,
                        promptName = "translation-icon"
                    )
                    if ((inT > 0 || outT > 0) && winAgent != null) {
                        appViewModel.settingsPrefs.updateUsageStatsAsync(
                            winAgent.provider, winAgent.model, inT, outT, kind = "icon"
                        )
                    }
                    appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
                } else {
                    AppLog.w("TranslationIcon", "no worker produced an icon for language='$language'")
                }
              } finally {
                // finally, not trailing: a throw / cancellation must still
                // release the in-flight key or this icon never regenerates.
                InternalPromptIconCache.clearInFlight("translation_icon", language)
              }
            }
        }
    }

    /** Fan-out of `icons/translation_alt` across user-picked
     *  [models] for one [language]. Mirrors
     *  [startInternalPromptIconFanOut] — same dedupe, throttle,
     *  cost-accumulation, and call-text capture rules. */
    fun startTranslationIconFanOut(
        context: Context,
        language: String,
        models: List<ReportModel>,
        aiSettings: Settings,
        /** Report whose first TRANSLATE row for [language] gets the
         *  alt-call cost attributed to it (so the row's cost cell on
         *  Report-Manage reflects the alt spend) AND records each
         *  call in [Report.iconCalls] (so the cost table shows a
         *  per-call `translation_alt` row). Null = legacy call-site
         *  (no per-report attribution). */
        reportId: String? = null
    ) {
        if (language.isBlank()) return
        // Find-alternative-icons runs the self-contained `alt/translation`
        // template (base wording + the "don't pick a flag" nudge merged).
        val altPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "alt" && it.name.equals("translation", ignoreCase = true)
        } ?: run {
            AppLog.w("TranslationIconAlt", "alt/translation not configured — skipping fan-out")
            return
        }
        val unique = models.distinctBy { "${it.provider.id}:${it.model}" }
        if (unique.isEmpty()) return
        val resolved = altPrompt.text.replace("@LANGUAGE@", language)
        val key = translationIconKey(language)
        // Resolve the SecondaryResult that owns the per-row alt-cost
        // attribution for this (report, language) pair — the first
        // TRANSLATE row for that language, as the user picked. Null
        // when no TRANSLATE row exists yet on this report (legacy
        // call paths) or when reportId wasn't supplied.
        val attributedSecondaryId: String? = reportId?.let { rid ->
            SecondaryResultStorage.listForReport(context, rid, SecondaryKind.TRANSLATE)
                .firstOrNull { it.targetLanguage == language }
                ?.id
        }

        appViewModel.updateInternalPromptIconFanOut(key) {
            unique.map { IconCandidate.Running(it.provider, it.model) }
        }

        val outer = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            unique.forEach { item ->
                launch(Dispatchers.IO) {
                    withTracerTags(category = "alt/translation") {
                        val agent = Agent(
                            id = "translation-icon-alt",
                            name = "translation-icon-alt",
                            provider = item.provider,
                            model = item.model,
                            apiKey = aiSettings.getApiKey(item.provider)
                        )
                        val baseUrl = aiSettings.getEffectiveEndpointUrl(item.provider)
                        runCatching {
                            val response = appViewModel.repository.analyzeWithAgent(
                                agent, "", resolved, AgentParameters(),
                                null, context, baseUrl
                            )
                            val tu = response.tokenUsage
                            val pricing = PricingCache.getPricing(context, item.provider, item.model)
                            val inT = tu?.inputTokens ?: 0
                            val outT = tu?.outputTokens ?: 0
                            val inC = inT * pricing.promptPrice
                            val outC = outT * pricing.completionPrice
                            if (inT > 0 || outT > 0) {
                                InternalPromptIconCache.bumpCost(
                                    "translation_icon", language, inT, outT, inC, outC
                                )
                                appViewModel.settingsPrefs.updateUsageStatsAsync(
                                    item.provider, item.model, inT, outT, kind = "icon"
                                )
                                // Per-report attribution: bump the first
                                // TRANSLATE SR for this language so its
                                // Report-Manage row reflects the alt
                                // spend, AND append an IconCallRecord
                                // so the cost-table per-call breakdown
                                // shows a `translation_alt` row.
                                if (reportId != null) {
                                    if (attributedSecondaryId != null) {
                                        SecondaryResultStorage.bumpResultInputOutputCost(
                                            context, reportId, attributedSecondaryId,
                                            inputTokens = inT, outputTokens = outT,
                                            inputCost = inC, outputCost = outC
                                        )
                                    }
                                    ReportStorage.appendIconCall(context, reportId, IconCallRecord(
                                        agentId = "", tier = 0,
                                        provider = item.provider.id, model = item.model,
                                        pricingTier = pricing.source,
                                        inputTokens = inT, outputTokens = outT,
                                        inputCost = inC, outputCost = outC,
                                        success = response.error == null,
                                        type = "alt/translation",
                                        attributedToSecondaryId = attributedSecondaryId
                                    ))
                                }
                            }
                            appViewModel.setInternalPromptIconCallTexts(
                                key, item.provider.id, item.model,
                                resolved, response.analysis.orEmpty()
                            )
                            val callCost = inC + outC
                            val emoji = if (response.error == null) {
                                extractFirstEmoji(response.analysis) ?: "📝"
                            } else null
                            appViewModel.updateInternalPromptIconFanOut(key) { list ->
                                list.map { c ->
                                    if (c.provider.id == item.provider.id && c.model == item.model) {
                                        if (emoji != null) {
                                            IconCandidate.Done(item.provider, item.model, emoji, callCost)
                                        } else {
                                            IconCandidate.Error(
                                                item.provider, item.model,
                                                response.error ?: "no emoji extracted",
                                                callCost
                                            )
                                        }
                                    } else c
                                }
                            }
                        }.onFailure { e ->
                            AppLog.w(
                                "TranslationIconAlt",
                                "exception for ${item.provider.id}/${item.model}: ${e.message}"
                            )
                            appViewModel.updateInternalPromptIconFanOut(key) { list ->
                                list.map { c ->
                                    if (c.provider.id == item.provider.id && c.model == item.model) {
                                        IconCandidate.Error(
                                            item.provider, item.model,
                                            e.message ?: e.javaClass.simpleName, 0.0
                                        )
                                    } else c
                                }
                            }
                        }
                        appViewModel.updateUiState {
                            it.copy(iconRefreshTick = it.iconRefreshTick + 1)
                        }
                    }
                }
            }
        }
        val previous = internalPromptIconFanOutJobs.put(key, outer)
        previous?.cancel()
        outer.invokeOnCompletion { internalPromptIconFanOutJobs.remove(key, outer) }
    }

    /** Commit a picked candidate for [language] to the cache.
     *  Mirrors [pickInternalPromptIcon]. */
    fun pickTranslationIcon(
        context: Context,
        language: String,
        candidate: IconCandidate.Done,
        @Suppress("UNUSED_PARAMETER") aiSettings: Settings
    ) {
        val key = translationIconKey(language)
        val captured = appViewModel.getInternalPromptIconCallTexts(
            key, candidate.provider.id, candidate.model
        ) ?: ("" to "")
        InternalPromptIconCache.pickAlternative(
            name = "translation_icon", title = language,
            emoji = candidate.emoji,
            providerId = candidate.provider.id, model = candidate.model,
            promptText = captured.first,
            responseText = captured.second,
            promptName = "translation_alt"
        )
        appViewModel.updateUiState {
            it.copy(iconRefreshTick = it.iconRefreshTick + 1)
        }
    }

    /** Cancel any in-flight per-language fan-out and drop the
     *  candidate list. */
    fun restartTranslationIconFanOut(language: String) {
        val key = translationIconKey(language)
        internalPromptIconFanOutJobs.remove(key)?.cancel()
        appViewModel.clearInternalPromptIconFanOut(key)
    }

    /** Fan-out of the `internal/icon` prompt across user-picked
     *  [models] for one report. Per call: pre-acquire the per-provider
     *  throttle permit, run the prompt against (provider, model), bump
     *  the Report's icon-cost fields by the call's tokens (regardless
     *  of success — token spend already happened), then flip the
     *  matching [IconCandidate] to [IconCandidate.Done] or
     *  [IconCandidate.Error]. Lives independently of the per-call
     *  coroutines so the user can navigate away mid-flight; the in-
     *  memory [AppViewModel.iconFanOutByReport] map is what
     *  [AlternativeIconsScreen] reads. */
    fun startIconFanOut(
        context: Context,
        reportId: String,
        promptText: String,
        models: List<ReportModel>,
        aiSettings: Settings
    ) {
        // Find-alternative-icons runs the self-contained `alt/main`
        // template (base wording + the "pick something distinct" nudge
        // merged).
        val altPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "alt" && it.name == "main"
        } ?: return
        // Dedupe by "provider:model" so picking the same pair via two
        // different sources (e.g. an agent + a direct +Model) only
        // fires one API call.
        val unique = models.distinctBy { "${it.provider.id}:${it.model}" }
        if (unique.isEmpty()) return
        val resolved = altPrompt.text.replace("@PROMPT@", promptText)
        // Pre-populate Running rows so the Alternative icons screen
        // shows ⏳ for every pair the moment the screen opens, before
        // any throttle permit is acquired.
        appViewModel.updateIconFanOut(reportId) {
            unique.map { IconCandidate.Running(it.provider, it.model) }
        }
        val outer = appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            unique.forEach { item ->
                // One async per model. Throttle pre-acquire matches
                // runFanOutPrompt's pattern so the OkHttp interceptor
                // sees permitPreAcquired=true and skips its own
                // acquire, avoiding double-counting.
                launch {
                    val host = providerHost(item.provider)
                    val releaser = ProviderThrottle.acquire(host)
                    try {
                        withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                            withTracerTags(reportId = reportId, category = "alt/main") {
                                runCatching {
                                    val syntheticAgent = Agent(
                                        id = "icon-alt-${item.provider.id}-${item.model}",
                                        name = item.model,
                                        provider = item.provider,
                                        model = item.model,
                                        apiKey = aiSettings.getApiKey(item.provider)
                                    )
                                    val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(syntheticAgent)
                                    val response = appViewModel.repository.analyzeWithAgent(
                                        syntheticAgent, "", resolved, AgentParameters(),
                                        null, context, baseUrl
                                    )
                                    // Extract just the emoji glyph (every other
                                    // icon path does); take(8) wrote raw UTF-16
                                    // prose / a sliced multi-codepoint emoji to
                                    // report.icon. Null (no emoji) → "" so the
                                    // candidate falls to the Error branch below.
                                    val emoji = extractFirstEmoji(response.analysis).orEmpty()
                                    val tu = response.tokenUsage
                                    val pricing = PricingCache.getPricing(context, item.provider, item.model)
                                    val inT = tu?.inputTokens ?: 0
                                    val outT = tu?.outputTokens ?: 0
                                    val inC = inT * pricing.promptPrice
                                    val outC = outT * pricing.completionPrice
                                    // Cost bump is unconditional — the
                                    // user paid for the call whether or
                                    // not it returned a usable emoji.
                                    if (inT > 0 || outT > 0) {
                                        ReportStorage.bumpReportIconCost(
                                            context, reportId,
                                            inputTokens = inT, outputTokens = outT,
                                            inputCost = inC, outputCost = outC
                                        )
                                        // Per-call audit row for the
                                        // cost table. iconRow above
                                        // subtracts the sum of these
                                        // to avoid double-counting.
                                        ReportStorage.appendIconCall(context, reportId, IconCallRecord(
                                            agentId = "", tier = 0,
                                            provider = item.provider.id, model = item.model,
                                            pricingTier = pricing.source,
                                            inputTokens = inT, outputTokens = outT,
                                            inputCost = inC, outputCost = outC,
                                            success = response.error == null,
                                            type = "alt/main"
                                        ))
                                    }
                                    val totalCost = inC + outC
                                    if (response.error == null && emoji.isNotEmpty()) {
                                        appViewModel.updateIconFanOut(reportId) { list ->
                                            list.map { c ->
                                                if (c.provider.id == item.provider.id && c.model == item.model)
                                                    IconCandidate.Done(item.provider, item.model, emoji, totalCost)
                                                else c
                                            }
                                        }
                                    } else {
                                        appViewModel.updateIconFanOut(reportId) { list ->
                                            list.map { c ->
                                                if (c.provider.id == item.provider.id && c.model == item.model)
                                                    IconCandidate.Error(item.provider, item.model, response.error ?: "empty response", totalCost)
                                                else c
                                            }
                                        }
                                    }
                                }.onFailure { e ->
                                    appViewModel.updateIconFanOut(reportId) { list ->
                                        list.map { c ->
                                            if (c.provider.id == item.provider.id && c.model == item.model)
                                                IconCandidate.Error(item.provider, item.model, e.message ?: "icon-gen failed", 0.0)
                                            else c
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        releaser.release()
                    }
                }
            }
        }
        rvm.registerIconFanOutJob(reportId, outer)
    }

    /** Cancel any in-flight "Find alternative icons" fan-out for
     *  [reportId] and drop every candidate from the in-memory map.
     *  Costs already bumped on the Report by completed pair calls
     *  stay — additive cost bookkeeping is the whole point. Wired
     *  to the Restart button on the Alternative icons screen so the
     *  user can wipe the list and re-open the picker with a fresh
     *  model selection without losing what they've already paid for. */
    fun restartIconFanOut(reportId: String) {
        rvm.iconFanOutJobs.remove(reportId)?.cancel()
        appViewModel.clearIconFanOut(reportId)
    }

    // ---- Find alternative TITLES (mirror of the icon fan-out) ----------
    // Transient: the picked title only fills the editor field, so these
    // bump no report cost; they post to the global Usage ledger and stash
    // candidates in titleFanOutByReport / titleFanOutByAgent.

    private fun cleanTitle(raw: String?): String =
        (raw ?: "").trim()
            .removePrefix("Title:").trim()
            .removeSurrounding("\"").trim()
            .removeSurrounding("'").trim()
            .lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            .take(325)

    fun startReportTitleFanOut(
        context: Context, reportId: String, promptText: String,
        models: List<ReportModel>, aiSettings: Settings, long: Boolean = false,
        paramsIds: List<String> = emptyList(), systemPromptId: String? = null
    ) {
        val altPromptName = if (long) "report_title_long" else "report_title"
        val altPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "alt" && it.name == altPromptName
        } ?: return
        val unique = models.distinctBy { "${it.provider.id}:${it.model}" }
        if (unique.isEmpty()) return
        val resolved = altPrompt.text.replace("@PROMPT@", promptText)
        appViewModel.updateReportTitleFanOut(reportId) { unique.map { TitleCandidate.Running(it.provider, it.model) } }
        val outer = appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            unique.forEach { item ->
                launch { runTitleCandidate(context, reportId, null, item, resolved, "alt/$altPromptName", aiSettings, paramsIds, systemPromptId, altPrompt) }
            }
        }
        rvm.registerIconFanOutJob("rt:$reportId", outer)
    }

    fun startModelTitleFanOut(
        context: Context, reportId: String, agentId: String,
        models: List<ReportModel>, aiSettings: Settings,
        paramsIds: List<String> = emptyList(), systemPromptId: String? = null
    ) {
        val altPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "alt" && it.name == "model_title"
        } ?: return
        val unique = models.distinctBy { "${it.provider.id}:${it.model}" }
        if (unique.isEmpty()) return
        appViewModel.updateAgentTitleFanOut(agentId) { unique.map { TitleCandidate.Running(it.provider, it.model) } }
        val outer = appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            val report = ReportStorage.getReport(context, reportId) ?: return@launch
            val ra = report.agents.firstOrNull { it.agentId == agentId } ?: return@launch
            val resolved = altPrompt.text.replace("@RESPONSE@", ra.responseBody.orEmpty())
            unique.forEach { item ->
                launch { runTitleCandidate(context, reportId, agentId, item, resolved, "alt/model_title", aiSettings, paramsIds, systemPromptId, altPrompt) }
            }
        }
        rvm.registerIconFanOutJob("mt:$agentId", outer)
    }

    /** One title candidate call. [agentId] null = report-title fan-out
     *  (writes titleFanOutByReport[reportId]); non-null = per-model
     *  (writes titleFanOutByAgent[agentId]). */
    private suspend fun runTitleCandidate(
        context: Context, reportId: String, agentId: String?,
        item: ReportModel, resolved: String, category: String, aiSettings: Settings,
        paramsIds: List<String> = emptyList(), systemPromptId: String? = null,
        prompt: InternalPrompt? = null
    ) {
        fun set(mutator: (List<TitleCandidate>) -> List<TitleCandidate>) {
            if (agentId == null) appViewModel.updateReportTitleFanOut(reportId, mutator)
            else appViewModel.updateAgentTitleFanOut(agentId, mutator)
        }
        fun place(c: TitleCandidate) = set { list ->
            list.map { if (it.provider.id == item.provider.id && it.model == item.model) c else it }
        }
        val host = providerHost(item.provider)
        val releaser = ProviderThrottle.acquire(host)
        try {
            withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                withTracerTags(reportId = reportId, category = category) {
                    runCatching {
                        val syntheticAgent = Agent(
                            id = "title-alt-${item.provider.id}-${item.model}",
                            name = item.model, provider = item.provider, model = item.model,
                            apiKey = aiSettings.getApiKey(item.provider)
                        )
                        val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(syntheticAgent)
                        val titleParams = resolveSecondaryParams(
                            appViewModel.uiState.value.generalSettings, aiSettings, paramsIds, systemPromptId, prompt
                        )
                        val response = appViewModel.repository.analyzeWithAgent(
                            syntheticAgent, "", resolved, titleParams, null, context, baseUrl
                        )
                        val tu = response.tokenUsage
                        val pricing = PricingCache.getPricing(context, item.provider, item.model)
                        val inT = tu?.inputTokens ?: 0
                        val outT = tu?.outputTokens ?: 0
                        val inC = inT * pricing.promptPrice
                        val outC = outT * pricing.completionPrice
                        val cost = inC + outC
                        if (inT > 0 || outT > 0) {
                            appViewModel.settingsPrefs.updateUsageStatsAsync(item.provider, item.model, inT, outT, kind = "title")
                            // Per-call audit row so this alternative-title
                            // spend shows in the report cost table + totals
                            // (mirrors the Find-alt icon fan-out, which
                            // records into report.iconCalls). agentId is
                            // left blank so agent/pair icon-clearing never
                            // sweeps it; [category] is the row's type
                            // ("title_report_alt" / "title_model_alt").
                            ReportStorage.appendIconCall(context, reportId, IconCallRecord(
                                agentId = "", tier = 0,
                                provider = item.provider.id, model = item.model,
                                pricingTier = pricing.source,
                                inputTokens = inT, outputTokens = outT,
                                inputCost = inC, outputCost = outC,
                                success = response.error == null,
                                type = category
                            ))
                        }
                        val title = cleanTitle(response.analysis)
                        if (response.error == null && title.isNotEmpty())
                            place(TitleCandidate.Done(item.provider, item.model, title, cost))
                        else
                            place(TitleCandidate.Error(item.provider, item.model, response.error ?: "empty response", cost))
                    }.onFailure { e ->
                        place(TitleCandidate.Error(item.provider, item.model, e.message ?: "title-gen failed", 0.0))
                    }
                }
            }
        } finally {
            releaser.release()
        }
    }

    fun restartReportTitleFanOut(reportId: String) {
        rvm.iconFanOutJobs.remove("rt:$reportId")?.cancel()
        appViewModel.clearReportTitleFanOut(reportId)
    }

    fun restartModelTitleFanOut(agentId: String) {
        rvm.iconFanOutJobs.remove("mt:$agentId")?.cancel()
        appViewModel.clearAgentTitleFanOut(agentId)
    }

    /** Language-icon counterpart of [startIconFanOut]. Runs the
     *  bundled `icons/language` prompt against each picked
     *  (provider, model) and pushes results into
     *  [AppViewModel.languageIconFanOutByReport]. The cost is left
     *  unbumped — v1 doesn't track language-icon cost separately
     *  (the call is a single DeepSeek-tier request worth a fraction
     *  of a cent). */
    fun startLanguageIconFanOut(
        context: Context,
        reportId: String,
        promptText: String,
        models: List<ReportModel>,
        aiSettings: Settings
    ) {
        // Find-alternative-icons composes `language` (the base
        // template — second-call emoji-pick for a detected language)
        // + blank line + `language_alt` (the "don't pick a flag"
        // nudge). The language was detected by the first call in the
        // two-step language flow. promptText is ignored — the
        // @PROMPT@ token doesn't exist on either template here. Kept
        // in the signature for caller compat.
        val altLanguagePrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "alt" && it.name == "language"
        } ?: return
        val report = ReportStorage.getReport(context, reportId) ?: return
        val languageName = report.languageName.orEmpty()
        if (languageName.isBlank()) {
            AppLog.w("LanguageIconAlt", "no detected language on report=$reportId — skipping fan-out")
            return
        }
        @Suppress("UNUSED_VARIABLE") val _unusedPrompt = promptText
        val unique = models.distinctBy { "${it.provider.id}:${it.model}" }
        if (unique.isEmpty()) return
        val resolved = altLanguagePrompt.text.replace("@LANGUAGE@", languageName)
        appViewModel.updateLanguageIconFanOut(reportId) {
            unique.map { IconCandidate.Running(it.provider, it.model) }
        }
        val outer = appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            unique.forEach { item ->
                launch {
                    val host = providerHost(item.provider)
                    val releaser = ProviderThrottle.acquire(host)
                    try {
                        withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                            withTracerTags(reportId = reportId, category = "alt/language") {
                                runCatching {
                                    val syntheticAgent = Agent(
                                        id = "language-icon-alt-${item.provider.id}-${item.model}",
                                        name = item.model,
                                        provider = item.provider,
                                        model = item.model,
                                        apiKey = aiSettings.getApiKey(item.provider)
                                    )
                                    val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(syntheticAgent)
                                    val response = appViewModel.repository.analyzeWithAgent(
                                        syntheticAgent, "", resolved, AgentParameters(),
                                        null, context, baseUrl
                                    )
                                    // The alt template outputs a single
                                    // emoji directly — language name was
                                    // fixed by the detection call, the
                                    // user is re-picking the emoji only.
                                    val emoji = extractFirstEmoji(response.analysis.orEmpty())
                                        ?: response.analysis?.trim().orEmpty().take(8)
                                    val tu = response.tokenUsage
                                    val pricing = PricingCache.getPricing(context, item.provider, item.model)
                                    val inT = tu?.inputTokens ?: 0
                                    val outT = tu?.outputTokens ?: 0
                                    val inC = inT * pricing.promptPrice
                                    val outC = outT * pricing.completionPrice
                                    val totalCost = inC + outC
                                    // Cost bump is unconditional — every call
                                    // the user paid for adds to the language-
                                    // icon cost line, whether or not its
                                    // returned emoji was usable.
                                    if (inT > 0 || outT > 0) {
                                        ReportStorage.bumpReportLanguageIconCost(
                                            context, reportId,
                                            inputTokens = inT, outputTokens = outT,
                                            inputCost = inC, outputCost = outC
                                        )
                                        ReportStorage.appendIconCall(context, reportId, IconCallRecord(
                                            agentId = "", tier = 0,
                                            provider = item.provider.id, model = item.model,
                                            pricingTier = pricing.source,
                                            inputTokens = inT, outputTokens = outT,
                                            inputCost = inC, outputCost = outC,
                                            success = response.error == null,
                                            type = "alt/language"
                                        ))
                                    }
                                    if (response.error == null && emoji.isNotEmpty()) {
                                        appViewModel.updateLanguageIconFanOut(reportId) { list ->
                                            list.map { c ->
                                                if (c.provider.id == item.provider.id && c.model == item.model)
                                                    IconCandidate.Done(item.provider, item.model, emoji, totalCost)
                                                else c
                                            }
                                        }
                                    } else {
                                        appViewModel.updateLanguageIconFanOut(reportId) { list ->
                                            list.map { c ->
                                                if (c.provider.id == item.provider.id && c.model == item.model)
                                                    IconCandidate.Error(item.provider, item.model, response.error ?: "empty response", totalCost)
                                                else c
                                            }
                                        }
                                    }
                                }.onFailure { e ->
                                    appViewModel.updateLanguageIconFanOut(reportId) { list ->
                                        list.map { c ->
                                            if (c.provider.id == item.provider.id && c.model == item.model)
                                                IconCandidate.Error(item.provider, item.model, e.message ?: "language-gen failed", 0.0)
                                            else c
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        releaser.release()
                    }
                }
            }
        }
        rvm.languageIconFanOutJobs.put(reportId, outer)?.cancel()
        outer.invokeOnCompletion { rvm.languageIconFanOutJobs.remove(reportId, outer) }
    }

    fun restartLanguageIconFanOut(reportId: String) {
        rvm.languageIconFanOutJobs.remove(reportId)?.cancel()
        appViewModel.clearLanguageIconFanOut(reportId)
    }

    /** Per-agent counterpart of [startIconFanOut]. Drives the Agent
     *  icon detail screen's "Find alternative icons" button: the user
     *  picks alternative models, and each one is asked to iconify
     *  THIS agent's (provider, model) answer to the report's prompt
     *  via the bundled icons/report template (two
     *  placeholders — @PROMPT@ = report.prompt, @RESPONSE@ = this
     *  agent's responseBody). Candidates land in
     *  [AppViewModel.agentIconFanOutByAgent] keyed by agentId; per-
     *  call cost bumps the agent's icon-cost via
     *  [ReportStorage.bumpReportAgentIconCost]. Re-runs cancel any
     *  prior in-flight job for the same agent. */
    fun startAgentIconFanOut(
        context: Context,
        reportId: String,
        agentId: String,
        models: List<ReportModel>,
        aiSettings: Settings
    ) {
        // Find-alternative-icons runs the self-contained `alt/report`
        // template (the tier-2 base wording with @PROMPT@ + @RESPONSE@
        // slots + the "pick something distinct" nudge merged).
        val altPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "alt" && it.name == "report"
        } ?: run {
            AppLog.w("AgentIconAlt", "alt/report prompt not found — skipping (agent=$agentId)")
            return
        }
        val unique = models.distinctBy { "${it.provider.id}:${it.model}" }
        if (unique.isEmpty()) return
        appViewModel.updateAgentIconFanOut(agentId) {
            unique.map { IconCandidate.Running(it.provider, it.model) }
        }
        val outer = appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            val report = ReportStorage.getReport(context, reportId) ?: return@launch
            val ra = report.agents.firstOrNull { it.agentId == agentId } ?: return@launch
            val reportPrompt = report.prompt
            val agentResponse = ra.responseBody.orEmpty()
            val resolved = altPrompt.text
                .replace("@PROMPT@", reportPrompt)
                .replace("@RESPONSE@", agentResponse)
            unique.forEach { item ->
                launch {
                    val host = providerHost(item.provider)
                    val releaser = ProviderThrottle.acquire(host)
                    try {
                        withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                            withTracerTags(reportId = reportId, category = "alt/report") {
                                runCatching {
                                    val syntheticAgent = Agent(
                                        id = "icon-alt-agent-${agentId}-${item.provider.id}-${item.model}",
                                        name = item.model,
                                        provider = item.provider,
                                        model = item.model,
                                        apiKey = aiSettings.getApiKey(item.provider)
                                    )
                                    val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(syntheticAgent)
                                    val response = appViewModel.repository.analyzeWithAgent(
                                        syntheticAgent, "", resolved, AgentParameters(),
                                        null, context, baseUrl
                                    )
                                    val tu = response.tokenUsage
                                    val pricing = PricingCache.getPricing(context, item.provider, item.model)
                                    val inT = tu?.inputTokens ?: 0
                                    val outT = tu?.outputTokens ?: 0
                                    val inC = inT * pricing.promptPrice
                                    val outC = outT * pricing.completionPrice
                                    // Cost bump is unconditional — every
                                    // call counts on the agent's row, same
                                    // additive rule as the report-level
                                    // alternative-icons flow.
                                    if (inT > 0 || outT > 0) {
                                        ReportStorage.bumpReportAgentIconCost(
                                            context, reportId, agentId,
                                            inputTokens = inT, outputTokens = outT,
                                            inputCost = inC, outputCost = outC
                                        )
                                        // Per-call audit row labelled
                                        // `report_alt`. agentId is set
                                        // so the existing classifier
                                        // would map this to
                                        // "report-icons" — but `type`
                                        // overrides, surfacing the
                                        // call as its own labelled row
                                        // alongside the per-tier chain
                                        // entries for the same agent.
                                        ReportStorage.appendIconCall(context, reportId, IconCallRecord(
                                            agentId = agentId, tier = 0,
                                            provider = item.provider.id, model = item.model,
                                            pricingTier = pricing.source,
                                            inputTokens = inT, outputTokens = outT,
                                            inputCost = inC, outputCost = outC,
                                            success = response.error == null,
                                            type = "alt/report"
                                        ))
                                    }
                                    val totalCost = inC + outC
                                    if (response.error == null) {
                                        val emoji = extractFirstEmoji(response.analysis) ?: "📝"
                                        appViewModel.updateAgentIconFanOut(agentId) { list ->
                                            list.map { c ->
                                                if (c.provider.id == item.provider.id && c.model == item.model)
                                                    IconCandidate.Done(item.provider, item.model, emoji, totalCost)
                                                else c
                                            }
                                        }
                                    } else {
                                        appViewModel.updateAgentIconFanOut(agentId) { list ->
                                            list.map { c ->
                                                if (c.provider.id == item.provider.id && c.model == item.model)
                                                    IconCandidate.Error(item.provider, item.model, response.error, totalCost)
                                                else c
                                            }
                                        }
                                    }
                                    appViewModel.updateUiState {
                                        it.copy(iconRefreshTick = it.iconRefreshTick + 1)
                                    }
                                }.onFailure { e ->
                                    appViewModel.updateAgentIconFanOut(agentId) { list ->
                                        list.map { c ->
                                            if (c.provider.id == item.provider.id && c.model == item.model)
                                                IconCandidate.Error(item.provider, item.model, e.message ?: "icon-gen failed", 0.0)
                                            else c
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        releaser.release()
                    }
                }
            }
        }
        rvm.registerAgentIconFanOutJob(reportId, agentId, outer)
    }

    /** Per-agent counterpart of [pickAlternativeIcon]. Commits the
     *  picked emoji to the matching [ReportAgent] via
     *  [ReportStorage.setReportAgentIconChoice]; cost fields stay as
     *  the per-call bumps left them. */
    fun pickAgentIcon(
        context: Context,
        reportId: String,
        agentId: String,
        emoji: String
    ) {
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            ReportStorage.setReportAgentIconChoice(context, reportId, agentId, emoji, promptUsed = "report_alt")
            appViewModel.updateUiState {
                it.copy(iconRefreshTick = it.iconRefreshTick + 1)
            }
        }
    }

    /** Per-agent counterpart of [restartIconFanOut]. Wired to the
     *  Alternative icons screen's Restart button when the active flow
     *  is per-agent. */
    fun restartAgentIconFanOut(reportId: String, agentId: String) {
        rvm.agentIconFanOutJobs.remove(rvm.agentIconJobKey(reportId, agentId))?.cancel()
        appViewModel.clearAgentIconFanOut(agentId)
    }

    /** Commit a user-picked icon from the "Alternative icons" list:
     *  replace the emoji + record the source model on the Report, and
     *  bump [UiState.iconRefreshTick] so screens re-read. Cost fields
     *  were already bumped per-call by [startIconFanOut]. */
    fun pickAlternativeIcon(
        context: Context,
        reportId: String,
        emoji: String,
        iconModel: String
    ) {
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            ReportStorage.setReportIconChoice(context, reportId, emoji, iconModel, promptUsed = "main_alt")
            appViewModel.updateUiState {
                it.copy(iconRefreshTick = it.iconRefreshTick + 1)
            }
        }
    }

    /** Language-icon counterpart of [pickAlternativeIcon]. Writes
     *  the picked emoji + model attribution to disk; bumps the
     *  recompose tick so the row/detail rerender. */
    fun pickAlternativeLanguageIcon(
        context: Context,
        reportId: String,
        emoji: String,
        iconModel: String
    ) {
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            ReportStorage.setReportLanguageChoice(context, reportId, emoji, iconModel, promptUsed = "language_alt")
            appViewModel.updateUiState {
                it.copy(iconRefreshTick = it.iconRefreshTick + 1)
            }
        }
    }

    /* The response-based 3-tier per-agent report-icon fallback chain
     * (runReportIconsForAgent + runTier1/2/3 + commitChainResult, on
     * icons/report_1/2/3) has been removed. Per-agent model icons are
     * now produced solely by the worker engine via
     * [generateIconFromTitle] (workers/model-icons, derived from the
     * model title). When no title is available or no worker yields an
     * emoji, the agent is simply left icon-less. */

    /** Shared write-side of a tier call. Bumps the per-agent icon
     *  cost (so the row's cost cell totals every attempt), updates
     *  the global UsageStats ledger with kind="icon" attributed to
     *  the actual (provider, model) that billed, and appends an
     *  [IconCallRecord] for the export's per-call All-tab. */
    private suspend fun recordTierCall(
        context: Context, reportId: String, agentId: String, tier: Int,
        provider: AppService, model: String,
        inT: Int, outT: Int, durationMs: Long, success: Boolean,
        type: String? = null
    ) {
        val pricing = PricingCache.getPricing(context, provider, model)
        val inC = inT * pricing.promptPrice
        val outC = outT * pricing.completionPrice
        if (inT > 0 || outT > 0) {
            ReportStorage.bumpReportAgentIconCost(
                context, reportId, agentId,
                inputTokens = inT, outputTokens = outT,
                inputCost = inC, outputCost = outC
            )
            appViewModel.settingsPrefs.updateUsageStatsAsync(
                provider, model, inT, outT, kind = "icon"
            )
        }
        ReportStorage.appendIconCall(
            context, reportId,
            IconCallRecord(
                agentId = agentId, tier = tier,
                provider = provider.id, model = model,
                pricingTier = pricing.source,
                inputTokens = inT, outputTokens = outT,
                inputCost = inC, outputCost = outC,
                durationMs = durationMs,
                success = success,
                type = type
            )
        )
    }

    // -----------------------------------------------------------------
    // Fan-out pair icon chain (per-pair 3-tier response-based chain)
    // -----------------------------------------------------------------

    /** Per-fan-out-pair 3-tier icon chain. Tier 1 = chat continuation
     *  with one extra turn beyond the report-icon chain (so the model
     *  sees the question → source response → meta prompt → its own
     *  response, then is asked for an emoji). Tier 2 = one-shot
     *  fan_out template substitution against the pair's own
     *  (provider, model). Tier 3 = fixed-agent fan_out_3
     *  fallback. */
    /** Outcome of one fan-out icon-chain tier. */
    private sealed class TierResult {
        /** Tier produced a usable emoji. */
        data class Emoji(val value: String) : TierResult()
        /** Tier ran but yielded no emoji (or failed for a
         *  non-rate-limit reason) — cascade to the next tier. */
        object Miss : TierResult()
        /** Tier was rate-limited (429) after the in-OkHttp 429
         *  retry loop gave up. Cascading would just hammer the same
         *  throttled host, so the chain stops for this pair — left
         *  icon-less for a later relaunch to retry. */
        object RateLimited : TierResult()
    }

    /** A 429 reaching the icon chain means both the in-OkHttp 429
     *  retry loop and the repository retry exhausted — treat it as
     *  RateLimited, distinct from an emoji miss. The chat / agent
     *  dispatchers format the error as "API error: 429 …". */
    private fun isRateLimitFailure(t: Throwable): Boolean =
        t.message?.contains("API error: 429") == true

    // ============================================================
    // Fan-meta batch — ONE workers/fan-meta call per fan-out pair
    // returns BOTH a title and an icon (a "title:" / "icon:" two-line
    // reply), via the random-pick / 429-fallback worker engine.
    // One Fan Meta call per pair yields both the title and the icon.
    // ============================================================

    fun runFanMetaBatch(
        context: Context,
        reportId: String,
        metaPromptId: String
    ): Job? {
        if (!appViewModel.uiState.value.generalSettings.fanMetaOn()) return null
        rvm.fanMetaJobs[rvm.fanMetaJobKey(reportId, metaPromptId)]?.let { existing ->
            if (existing.isActive) return existing
        }
        val fanMetaPrompt = appViewModel.uiState.value.aiSettings.internalPrompts.firstOrNull {
            it.category == "workers" && it.name == "fan-meta"
        }
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        val fanRunId = java.util.UUID.randomUUID().toString()
        val job = appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            try {
                val aiSettings = appViewModel.uiState.value.aiSettings
                if (fanMetaPrompt == null || fanMetaPrompt.workers.none { aiSettings.resolveWorker(it) != null }) {
                    AppLog.w("FanMeta", "workers/fan-meta not configured — skipping")
                    return@launch
                }
                val pending = SecondaryResultStorage
                    .listForReport(context, reportId, SecondaryKind.META)
                    .filter {
                        it.metaPromptId == metaPromptId &&
                            it.fanOutSourceAgentId != null &&
                            it.fanInOf == null &&
                            !it.content.isNullOrBlank() &&
                            it.title.isNullOrBlank() && it.icon.isNullOrBlank()
                    }
                if (pending.isEmpty()) {
                    AppLog.i("FanMeta", "no pending pairs on $reportId — nothing to do")
                    return@launch
                }
                AppLog.i("FanMeta", "→ start (report=$reportId, ${pending.size} pairs)")
                withTracerTags(reportId = reportId, category = "workers/fan-meta", runId = fanRunId) {
                    // Dynamic-host: each worker call self-throttles its own
                    // provider (the worker chain spans providers); the batch
                    // holds only the fan-meta + global caps.
                    runThrottledBatch(
                        items = pending,
                        hostOf = { null },
                        subCap = ApiCallCaps.fanMeta,
                        onThrottled = { pair -> appViewModel.updateThrottledFanMetaPairs { it + pair.id } },
                        onCleared = { pair -> appViewModel.updateThrottledFanMetaPairs { it - pair.id } },
                        dynamicHost = true
                    ) { pair ->
                        if (!SecondaryResultStorage.exists(context, reportId, pair.id)) return@runThrottledBatch
                        appViewModel.updateRunningFanMetaPairs { it + pair.id }
                        try {
                            runFanMetaForPair(context, reportId, pair, fanMetaPrompt, aiSettings)
                        } finally {
                            appViewModel.updateRunningFanMetaPairs { it - pair.id }
                            // acquireOrWait clears its own wait notification, but
                            // guard against any stuck id on a hard teardown.
                            appViewModel.updateThrottledFanMetaPairs { it - pair.id }
                        }
                    }
                }
                AppLog.i("FanMeta", "← end (report=$reportId)")
            } finally {
                appViewModel.updateUiState {
                    it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0))
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    val running = appViewModel.runningFanMetaPairs.value
                    val leftover = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
                        .filter {
                            it.metaPromptId == metaPromptId && it.fanOutSourceAgentId != null &&
                                it.fanInOf == null && !it.content.isNullOrBlank() &&
                                it.title.isNullOrBlank() && it.icon.isNullOrBlank() && it.id !in running
                        }
                    BatchResume.finalizeLeftover(leftover) {
                        SecondaryResultStorage.setFanOutTitleError(context, reportId, it.id, "Interrupted — run stopped before this finished")
                        SecondaryResultStorage.setFanOutIconError(context, reportId, it.id, "Interrupted — run stopped before this finished")
                    }
                }
            }
        }
        rvm.registerFanMetaJob(reportId, metaPromptId, job)
        return job
    }

    /** One workers/fan-meta call for [pair]: parses the title: / icon:
     *  reply and stores BOTH. Worker engine handles random pick + 429. */
    private suspend fun runFanMetaForPair(
        context: Context, reportId: String, pair: SecondaryResult,
        fanMetaPrompt: InternalPrompt, aiSettings: Settings
    ) {
        val started = System.currentTimeMillis()
        val resolved = fanMetaPrompt.text.replace("@PROMPT@", pair.content.orEmpty())
        // A fan-meta reply is usable when it yields at least a title or an
        // emoji; an empty/garbage 200 is a logical miss → next worker.
        // Surface real provider throttling to the L1 "Throttled" counter:
        // a fan-meta call is dynamic-host, so its rate-limit / concurrency
        // wait happens inside ProviderThrottle.acquireOrWait (via the
        // dispatch host-gate), not at the batch layer's onThrottled hook.
        // Install a wait-observer keyed to this pair; acquireOrWait toggles
        // it as the call parks / is admitted. The element re-installs the
        // value on whatever thread the coroutine resumes on.
        val pairId = pair.id
        val throttleObserver: (Boolean) -> Unit = { waiting ->
            if (waiting) appViewModel.updateThrottledFanMetaPairs { it + pairId }
            else appViewModel.updateThrottledFanMetaPairs { it - pairId }
        }
        val outcome = withContext(
            ProviderThrottle.throttleWaitObserver.asContextElement(throttleObserver)
        ) {
            rvm.workerRunner.run(fanMetaPrompt, resolved, aiSettings, context) { resp ->
                val a = resp.analysis
                val titleRaw = a?.lineSequence()
                    ?.firstOrNull { it.trim().startsWith("title", ignoreCase = true) }
                    ?.substringAfter(":") ?: a
                extractFirstEmoji(a) != null || cleanTitle(titleRaw).isNotBlank()
            }
        }
        when (outcome) {
            is WorkerOutcome.Success -> {
                val analysis = outcome.response.analysis
                val titleRaw = analysis?.lineSequence()
                    ?.firstOrNull { it.trim().startsWith("title", ignoreCase = true) }
                    ?.substringAfter(":") ?: analysis
                val title = cleanTitle(titleRaw)
                val iconLine = analysis?.lineSequence()?.firstOrNull { it.trim().startsWith("icon", ignoreCase = true) }
                val emoji = extractFirstEmoji(iconLine ?: analysis.orEmpty()) ?: "📝"
                val winAgent = aiSettings.resolveWorker(outcome.worker)?.let {
                    it.copy(model = aiSettings.getEffectiveModelForAgent(it))
                }
                val tu = outcome.response.tokenUsage
                val inT = tu?.inputTokens ?: 0
                val outT = tu?.outputTokens ?: 0
                if ((inT > 0 || outT > 0) && winAgent != null) {
                    val pricing = PricingCache.getPricing(context, winAgent.provider, winAgent.model)
                    SecondaryResultStorage.bumpFanOutTitleCost(
                        context, reportId, pair.id,
                        inputTokens = inT, outputTokens = outT,
                        inputCost = inT * pricing.promptPrice, outputCost = outT * pricing.completionPrice,
                        model = "${winAgent.provider.id}/${winAgent.model}"
                    )
                    appViewModel.settingsPrefs.updateUsageStatsAsync(winAgent.provider, winAgent.model, inT, outT, kind = "title")
                }
                if (title.isNotBlank()) {
                    SecondaryResultStorage.setFanOutTitle(
                        context, reportId, pair.id, title,
                        titleRunId = ApiTracer.currentRunId, promptUsed = "fan-meta",
                        durationMs = System.currentTimeMillis() - started
                    )
                } else {
                    SecondaryResultStorage.setFanOutTitleError(context, reportId, pair.id, "no title in reply")
                }
                SecondaryResultStorage.setFanOutIconAndTier(
                    context, reportId, pair.id, emoji, winningTier = null,
                    iconRunId = ApiTracer.currentRunId, promptUsed = "fan-meta"
                )
                appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
            }
            else -> {
                val msg = if (outcome is WorkerOutcome.AllRateLimited) "fan-meta: all workers rate-limited"
                          else "fan-meta: no worker produced a result"
                SecondaryResultStorage.setFanOutTitleError(context, reportId, pair.id, msg)
                SecondaryResultStorage.setFanOutIconError(context, reportId, pair.id, msg)
                appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
            }
        }
    }

    /** Re-fire fan-meta after clearing every pair's title+icon. */
    fun relaunchFanMetaBatch(context: Context, reportId: String, metaPromptId: String): Job? {
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            SecondaryResultStorage
                .listForReport(context, reportId, SecondaryKind.META)
                .filter { it.metaPromptId == metaPromptId && it.fanOutSourceAgentId != null && it.fanInOf == null }
                .forEach {
                    SecondaryResultStorage.clearFanOutTitleState(context, reportId, it.id)
                    SecondaryResultStorage.clearFanOutIconState(context, reportId, it.id)
                }
        }
        return runFanMetaBatch(context, reportId, metaPromptId)
    }

    fun cancelFanMetaBatch(reportId: String, metaPromptId: String): Job? =
        rvm.fanMetaJobs[rvm.fanMetaJobKey(reportId, metaPromptId)]?.also { it.cancel() }

    private fun isFanMetaError(sr: SecondaryResult): Boolean =
        !sr.titleErrorMessage.isNullOrBlank() || !sr.iconErrorMessage.isNullOrBlank()

    private fun erroredFanMetaPairs(context: Context, reportId: String, metaPromptId: String) =
        SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
            .filter { it.metaPromptId == metaPromptId && it.fanOutSourceAgentId != null && it.fanInOf == null && isFanMetaError(it) }

    fun clearFanMetaErrors(context: Context, reportId: String, metaPromptId: String) {
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            withContext(Dispatchers.IO) {
                for (e in erroredFanMetaPairs(context, reportId, metaPromptId)) {
                    SecondaryResultStorage.clearFanOutTitleState(context, reportId, e.id)
                    SecondaryResultStorage.clearFanOutIconState(context, reportId, e.id)
                }
            }
            appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
        }
    }

    fun restartFanMetaErrors(context: Context, reportId: String, metaPromptId: String): Job? {
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            withContext(Dispatchers.IO) {
                for (e in erroredFanMetaPairs(context, reportId, metaPromptId)) {
                    SecondaryResultStorage.clearFanOutTitleState(context, reportId, e.id)
                    SecondaryResultStorage.clearFanOutIconState(context, reportId, e.id)
                }
            }
            appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
        }
        return runFanMetaBatch(context, reportId, metaPromptId)
    }
}
