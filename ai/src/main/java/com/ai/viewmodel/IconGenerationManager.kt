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
        // (@TITLE_LONG@) and runs through the round-robin / 429-fallback
        // worker chain. Bail if the prompt or every worker is unresolvable.
        val iconPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "workers" && it.name == "report-icon"
        } ?: return
        if (iconPrompt.workers.none { aiSettings.resolveWorker(it) != null }) return
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            withTracerTags(reportId = reportId, category = "icon_main") {
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
                    rvm.workerRunner.run(iconPrompt, resolved, aiSettings, context)
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
        // Worker-based: round-robin / 429-fallback over workers/report-title.
        val titlePrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "workers" && it.name == "report-title"
        } ?: return
        if (titlePrompt.workers.none { aiSettings.resolveWorker(it) != null }) return
        val resolved = titlePrompt.text.replace("@PROMPT@", promptText)
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            withTracerTags(reportId = reportId, category = "report_title") {
                val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
                appViewModel.updateRunningInfoJobs { it + "$reportId|title" }
                val started = System.currentTimeMillis()
                val outcome = withTraceFilenameSink(traceSink) {
                    rvm.workerRunner.run(titlePrompt, resolved, aiSettings, context)
                }
                val durationMs = System.currentTimeMillis() - started
                when (outcome) {
                    is WorkerOutcome.Success -> {
                        // The prompt asks for two lines — a short title then a
                        // long one. Clean each line defensively (some models
                        // wrap output in quotes or add a "Title: " prefix even
                        // when told not to), then take the first two non-blank.
                        val lines = (outcome.response.analysis ?: "")
                            .lineSequence()
                            .map { cleanTitleLine(it) }
                            .filter { it.isNotBlank() }
                            .toList()
                        // Short title (≤25) drives list cards; long title (≤50)
                        // the orange line. Single-line replies leave long blank
                        // → barTitle falls back to the short title.
                        val generated = lines.getOrNull(0).orEmpty().take(25).ifBlank { "AI Report" }
                        val generatedLong = lines.getOrNull(1).orEmpty().take(50)
                        val winAgent = aiSettings.resolveWorker(outcome.worker)?.let {
                            it.copy(model = aiSettings.getEffectiveModelForAgent(it))
                        }
                        val tu = outcome.response.tokenUsage
                        val inT = tu?.inputTokens ?: 0
                        val outT = tu?.outputTokens ?: 0
                        val pricing = winAgent?.let { PricingCache.getPricing(context, it.provider, it.model) }
                        val inC = inT * (pricing?.promptPrice ?: 0.0)
                        val outC = outT * (pricing?.completionPrice ?: 0.0)
                        ReportStorage.updateReportTitleFromAi(
                            context, reportId, generated,
                            titleLong = generatedLong.ifBlank { null },
                            durationMs = durationMs,
                            inputTokens = inT, outputTokens = outT,
                            inputCost = inC, outputCost = outC,
                            traceFile = traceSink.get(),
                            model = winAgent?.let { "${it.provider.id}/${it.model}" },
                            promptUsed = "report_title"
                        )
                        // Keep the in-memory UiState in sync so the
                        // title row on Manage report updates the moment
                        // the call returns, without waiting for a
                        // navigation event to re-read from disk.
                        appViewModel.updateUiState { st ->
                            if (st.currentReportId == reportId) {
                                st.copy(genericPromptTitle = generated, genericPromptTitleLong = generatedLong)
                            } else st
                        }
                    }
                    else -> ReportStorage.updateReportTitleError(
                        context, reportId,
                        if (outcome is WorkerOutcome.AllRateLimited) "title-gen: all workers rate-limited"
                        else "title-gen: no worker produced a title"
                    )
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
        // Worker-based: round-robin / 429-fallback over workers/model-titles.
        val titlePrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "workers" && it.name == "model-titles"
        } ?: return
        if (titlePrompt.workers.none { aiSettings.resolveWorker(it) != null }) return
        val agentResponse = ra.responseBody.orEmpty()
        if (agentResponse.isBlank()) return
        val resolved = titlePrompt.text.replace("@RESPONSE@", agentResponse)
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            var generatedTitle: String? = null
            withTracerTags(reportId = reportId, category = "model_title") {
                val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
                val started = System.currentTimeMillis()
                val outcome = withTraceFilenameSink(traceSink) {
                    rvm.workerRunner.run(titlePrompt, resolved, aiSettings, context)
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
            // Chain the icon: derive it from the title, falling back to the
            // response-based 3-tier chain when there's no usable title (or the
            // title→icon call yields no emoji).
            if (thenIconFromTitle) {
                val t = generatedTitle
                val iconOk = if (t != null) generateIconFromTitle(context, reportId, ra, t, aiSettings) else false
                if (!iconOk) runReportIconsForAgent(context, reportId, ra, reportPrompt, aiSettings)
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
        // Worker-based: round-robin / 429-fallback over workers/model-icons.
        val prompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "workers" && it.name == "model-icons"
        } ?: return false
        if (prompt.workers.none { aiSettings.resolveWorker(it) != null }) return false
        // Reset this agent's icon fields + iconCalls so a re-fire
        // (regenerate) replaces rather than accumulates — matches the
        // 3-tier chain's clearReportAgentIconState at its own start.
        ReportStorage.clearReportAgentIconState(context, reportId, ra.agentId)
        return withTracerTags(reportId = reportId, category = "icon_report_title") {
            val started = System.currentTimeMillis()
            val resolved = prompt.text.replace("@TITLE@", title)
            val outcome = rvm.workerRunner.run(prompt, resolved, aiSettings, context)
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
                        success = emoji != null
                    )
                }
                if (emoji != null) {
                    ReportStorage.setReportAgentIconAndTier(
                        context, reportId, ra.agentId, emoji,
                        winningTier = null, promptUsed = "report_title_icon"
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
        // Worker-based: a single workers/language call returns BOTH the
        // language name and a fitting emoji (the prompt asks for a
        // "language:" / "icon:" two-line reply), via the round-robin /
        // 429-fallback engine — no chained second call.
        val languagePrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "workers" && it.name == "language"
        } ?: return
        if (languagePrompt.workers.none { aiSettings.resolveWorker(it) != null }) return
        val resolved = languagePrompt.text.replace("@PROMPT@", promptText)
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            withTracerTags(reportId = reportId, category = "Language") {
                val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
                appViewModel.updateRunningInfoJobs { it + "$reportId|language" }
                val started = System.currentTimeMillis()
                val outcome = withTraceFilenameSink(traceSink) {
                    rvm.workerRunner.run(languagePrompt, resolved, aiSettings, context)
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
                            // One call → attribute its cost to the detection row;
                            // the icon row stays 0 (the Get-Info row sums both).
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
                                durationMs = durationMs
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

    /** Background helper that resolves the bundled `icons/meta`
     *  prompt against its pinned agent and caches a one-emoji result for
     *  [prompt] in [InternalPromptIconCache]. Idempotent: bails when the
     *  master switch is off, when the cache already has a value, or when
     *  another call for the same `(name, title)` is already in flight.
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
            it.category == "icons" && it.name.equals("meta", ignoreCase = true)
        }
        if (iconPrompt == null) {
            AppLog.w("InternalPromptIcon", "internal/meta not configured — skipping")
            InternalPromptIconCache.clearInFlight(prompt.name, prompt.title)
            return
        }
        val rawAgent = aiSettings.resolvePromptAgent(iconPrompt)
        if (rawAgent == null) {
            AppLog.w("InternalPromptIcon", "agent '${iconPrompt.agent}' not found — skipping")
            InternalPromptIconCache.clearInFlight(prompt.name, prompt.title)
            return
        }
        val agent = rawAgent.copy(
            apiKey = aiSettings.getEffectiveApiKeyForAgent(rawAgent),
            model = aiSettings.getEffectiveModelForAgent(rawAgent)
        )
        val resolved = iconPrompt.text
            .replace("@NAME@", prompt.name)
            .replace("@TITLE@", prompt.title)
        val secParams = resolveSecondaryParams(appViewModel.uiState.value.generalSettings, aiSettings, emptyList(), null, prompt, agent)

        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            withTracerTags(category = "icon_meta") {
                runCatching {
                    val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(agent)
                    val response = appViewModel.repository.analyzeWithAgent(
                        agent, "", resolved, secParams,
                        null, context, baseUrl
                    )
                    if (response.error == null) {
                        val emoji = extractFirstEmoji(response.analysis) ?: "📝"
                        // Compute cost from this call's token usage ×
                        // the (provider, model) pricing tier. Same
                        // shape as kickOffIconGeneration.
                        val tu = response.tokenUsage
                        val pricing = PricingCache.getPricing(context, agent.provider, agent.model)
                        val inT = tu?.inputTokens ?: 0
                        val outT = tu?.outputTokens ?: 0
                        val inC = inT * pricing.promptPrice
                        val outC = outT * pricing.completionPrice
                        InternalPromptIconCache.recordInitial(
                            name = prompt.name, title = prompt.title,
                            emoji = emoji,
                            providerId = agent.provider.id, model = agent.model,
                            promptText = resolved,
                            responseText = response.analysis.orEmpty(),
                            inputTokens = inT, outputTokens = outT,
                            inputCost = inC, outputCost = outC,
                            promptName = "meta"
                        )
                        // Post to global UsageStats with kind="icon"
                        // — matches the per-agent 3-tier chain. Only
                        // post when the call actually used tokens
                        // (some providers report 0 on error).
                        if (inT > 0 || outT > 0) {
                            appViewModel.settingsPrefs.updateUsageStatsAsync(
                                agent.provider, agent.model, inT, outT, kind = "icon"
                            )
                        }
                        appViewModel.updateUiState {
                            it.copy(iconRefreshTick = it.iconRefreshTick + 1)
                        }
                    } else {
                        AppLog.w(
                            "InternalPromptIcon",
                            "call failed for name='${prompt.name}': ${response.error}"
                        )
                    }
                }.onFailure { e ->
                    AppLog.w(
                        "InternalPromptIcon",
                        "exception generating icon for name='${prompt.name}': ${e.message}"
                    )
                }
                InternalPromptIconCache.clearInFlight(prompt.name, prompt.title)
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
        // Find-alternative-icons composes the `_alt` variant's text
        // FIRST, then a blank line, then the base prompt's text —
        // the alt carries the "give me a different emoji" nudge up
        // front so the model reads the constraint before the
        // template body, and the base doesn't need to duplicate
        // the nudge wording.
        val basePrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "icons" && it.name.equals("meta", ignoreCase = true)
        } ?: run {
            AppLog.w("InternalPromptIconAlt", "internal/meta not configured — skipping fan-out")
            return
        }
        val altPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "icons" && it.name.equals("meta_alt", ignoreCase = true)
        } ?: run {
            AppLog.w("InternalPromptIconAlt", "internal/meta_alt not configured — skipping fan-out")
            return
        }
        val unique = models.distinctBy { "${it.provider.id}:${it.model}" }
        if (unique.isEmpty()) return
        val resolved = (altPrompt.text + "\n\n" + basePrompt.text)
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
                    withTracerTags(category = "icon_meta_alt") {
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
                                        type = "icon_meta_alt",
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
        val basePrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "icons" && it.name == "fan_out_2"
        } ?: run {
            AppLog.w("PairIconAlt", "internal/fan_out_2 prompt not found — skipping (pair=$pairId)")
            return
        }
        val altPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "icons" && it.name == "fan_out_alt"
        } ?: run {
            AppLog.w("PairIconAlt", "internal/fan_out_alt prompt not found — skipping (pair=$pairId)")
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
            val sourceAgentId = pair.fanOutSourceAgentId ?: return@launch
            val report = ReportStorage.getReport(context, reportId) ?: return@launch
            val sourceAgent = report.agents.firstOrNull { it.agentId == sourceAgentId }
            val metaPrompt = pair.metaPromptId?.let { mid ->
                aiSettings.internalPrompts.firstOrNull { it.id == mid }
            }
            val resolved = (altPrompt.text + "\n\n" + basePrompt.text)
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
                            withTracerTags(reportId = reportId, category = "icon_fan_out_alt") {
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
                                            type = "icon_fan_out_alt",
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

    // ── Translation icons ───────────────────────────────────────
    // Sibling flow to the per-`InternalPrompt` icon flow above.
    // Stores per-language entries in [InternalPromptIconCache]
    // under a synthetic `(name = "translation_icon", title =
    // language)` key, reusing the cache + fan-out maps verbatim.
    // The bundled `internal/translation_icon` prompt substitutes
    // `@LANGUAGE@` with the row's target language name.

    private fun translationIconKey(language: String): String =
        "translation_icon" + "" + language

    /** Background helper that resolves the bundled
     *  `icons/translation` prompt against its pinned agent
     *  and caches a one-emoji result for [language] in
     *  [InternalPromptIconCache]. Idempotent (same dedupe rules as
     *  [kickOffInternalPromptIcon]). Bails when
     *  [com.ai.viewmodel.GeneralSettings.useInternalPromptsIcons]
     *  is off — the master switch covers every internal-prompt
     *  icon flow. */
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
            it.category == "icons" && it.name.equals("translation", ignoreCase = true)
        }
        if (iconPrompt == null) {
            AppLog.w("TranslationIcon", "internal/translation not configured — skipping")
            InternalPromptIconCache.clearInFlight("translation_icon", language)
            return
        }
        val rawAgent = aiSettings.resolvePromptAgent(iconPrompt)
        if (rawAgent == null) {
            AppLog.w("TranslationIcon", "agent '${iconPrompt.agent}' not found — skipping")
            InternalPromptIconCache.clearInFlight("translation_icon", language)
            return
        }
        val agent = rawAgent.copy(
            apiKey = aiSettings.getEffectiveApiKeyForAgent(rawAgent),
            model = aiSettings.getEffectiveModelForAgent(rawAgent)
        )
        val resolved = iconPrompt.text.replace("@LANGUAGE@", language)
        val secParams = resolveSecondaryParams(appViewModel.uiState.value.generalSettings, aiSettings, emptyList(), null, iconPrompt, agent)

        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            withTracerTags(category = "icon_translation") {
                runCatching {
                    val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(agent)
                    val response = appViewModel.repository.analyzeWithAgent(
                        agent, "", resolved, secParams,
                        null, context, baseUrl
                    )
                    if (response.error == null) {
                        val emoji = extractFirstEmoji(response.analysis) ?: "📝"
                        val tu = response.tokenUsage
                        val pricing = PricingCache.getPricing(context, agent.provider, agent.model)
                        val inT = tu?.inputTokens ?: 0
                        val outT = tu?.outputTokens ?: 0
                        val inC = inT * pricing.promptPrice
                        val outC = outT * pricing.completionPrice
                        InternalPromptIconCache.recordInitial(
                            name = "translation_icon", title = language,
                            emoji = emoji,
                            providerId = agent.provider.id, model = agent.model,
                            promptText = resolved,
                            responseText = response.analysis.orEmpty(),
                            inputTokens = inT, outputTokens = outT,
                            inputCost = inC, outputCost = outC,
                            promptName = "translation"
                        )
                        if (inT > 0 || outT > 0) {
                            appViewModel.settingsPrefs.updateUsageStatsAsync(
                                agent.provider, agent.model, inT, outT, kind = "icon"
                            )
                        }
                        appViewModel.updateUiState {
                            it.copy(iconRefreshTick = it.iconRefreshTick + 1)
                        }
                    } else {
                        AppLog.w(
                            "TranslationIcon",
                            "call failed for language='$language': ${response.error}"
                        )
                    }
                }.onFailure { e ->
                    AppLog.w(
                        "TranslationIcon",
                        "exception generating icon for language='$language': ${e.message}"
                    )
                }
                InternalPromptIconCache.clearInFlight("translation_icon", language)
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
        // Find-alternative-icons composes `translation` (the base
        // template) + blank line + `translation_alt` (the "don't pick
        // a flag" nudge). The alt template stays short.
        val basePrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "icons" && it.name.equals("translation", ignoreCase = true)
        } ?: run {
            AppLog.w("TranslationIconAlt", "internal/translation not configured — skipping fan-out")
            return
        }
        val altPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "icons" && it.name.equals("translation_alt", ignoreCase = true)
        } ?: run {
            AppLog.w("TranslationIconAlt", "internal/translation_alt not configured — skipping fan-out")
            return
        }
        val unique = models.distinctBy { "${it.provider.id}:${it.model}" }
        if (unique.isEmpty()) return
        val resolved = (altPrompt.text + "\n\n" + basePrompt.text).replace("@LANGUAGE@", language)
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
                    withTracerTags(category = "icon_translation_alt") {
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
                                        type = "icon_translation_alt",
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
        // Find-alternative-icons composes `main` (the base template)
        // + blank line + `main_alt` (the "pick something distinct"
        // nudge). The alt template stays short.
        val basePrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "icons" && it.name == "main"
        } ?: return
        val altPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "icons" && it.name == "main_alt"
        } ?: return
        // Dedupe by "provider:model" so picking the same pair via two
        // different sources (e.g. an agent + a direct +Model) only
        // fires one API call.
        val unique = models.distinctBy { "${it.provider.id}:${it.model}" }
        if (unique.isEmpty()) return
        val resolved = (altPrompt.text + "\n\n" + basePrompt.text).replace("@PROMPT@", promptText)
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
                            withTracerTags(reportId = reportId, category = "icon_main_alt") {
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
                                    val emoji = response.analysis?.trim().orEmpty().take(8)
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
                                            type = "icon_main_alt"
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
        val altPromptName = if (long) "report_title_alt_long" else "report_title_alt"
        val altPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "info" && it.name == altPromptName
        } ?: return
        val unique = models.distinctBy { "${it.provider.id}:${it.model}" }
        if (unique.isEmpty()) return
        val resolved = altPrompt.text.replace("@PROMPT@", promptText)
        appViewModel.updateReportTitleFanOut(reportId) { unique.map { TitleCandidate.Running(it.provider, it.model) } }
        val outer = appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            unique.forEach { item ->
                launch { runTitleCandidate(context, reportId, null, item, resolved, "title_report_alt", aiSettings, paramsIds, systemPromptId, altPrompt) }
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
            it.category == "info" && it.name == "model_title_alt"
        } ?: return
        val unique = models.distinctBy { "${it.provider.id}:${it.model}" }
        if (unique.isEmpty()) return
        appViewModel.updateAgentTitleFanOut(agentId) { unique.map { TitleCandidate.Running(it.provider, it.model) } }
        val outer = appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            val report = ReportStorage.getReport(context, reportId) ?: return@launch
            val ra = report.agents.firstOrNull { it.agentId == agentId } ?: return@launch
            val resolved = altPrompt.text.replace("@RESPONSE@", ra.responseBody.orEmpty())
            unique.forEach { item ->
                launch { runTitleCandidate(context, reportId, agentId, item, resolved, "title_model_alt", aiSettings, paramsIds, systemPromptId, altPrompt) }
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
        val baseLanguagePrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "icons" && it.name == "language"
        } ?: return
        val altLanguagePrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "icons" && it.name == "language_alt"
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
        val resolved = (baseLanguagePrompt.text + "\n\n" + altLanguagePrompt.text).replace("@LANGUAGE@", languageName)
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
                            withTracerTags(reportId = reportId, category = "icon_language_alt") {
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
                                            type = "icon_language_alt"
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
        // Find-alternative-icons composes `report_2` (the base
        // template — tier-2 of the per-agent 3-tier chain, with
        // @PROMPT@ + @RESPONSE@ slots) + blank line + `report_alt`
        // (the "pick something distinct" nudge) so the alt stays
        // short.
        val basePrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "icons" && it.name == "report_2"
        } ?: run {
            AppLog.w("AgentIconAlt", "internal/report_2 prompt not found — skipping (agent=$agentId)")
            return
        }
        val altPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "icons" && it.name == "report_alt"
        } ?: run {
            AppLog.w("AgentIconAlt", "internal/report_alt prompt not found — skipping (agent=$agentId)")
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
            val resolved = (altPrompt.text + "\n\n" + basePrompt.text)
                .replace("@PROMPT@", reportPrompt)
                .replace("@RESPONSE@", agentResponse)
            unique.forEach { item ->
                launch {
                    val host = providerHost(item.provider)
                    val releaser = ProviderThrottle.acquire(host)
                    try {
                        withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                            withTracerTags(reportId = reportId, category = "icon_report_alt") {
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
                                            type = "icon_report_alt"
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

    /** 3-tier fallback chain for ONE agent's report icon. Fires
     *  immediately when an agent's primary call settles to SUCCESS
     *  (per-task auto-fire hook in generateGenericReports /
     *  regenerateReport), so a fast row's icon search starts while
     *  a slow row is still generating its response.
     *
     *  Each call runs in sequence on the agent's own dispatch path;
     *  the first one that returns an extractable emoji wins:
     *
     *    Tier 1 — chat continuation against the agent's own
     *      (provider, model). user→assistant→user message chain with
     *      the third turn = icons/report_2.text.
     *    Tier 2 — one-shot icons/report template (@PROMPT@ +
     *      @RESPONSE@) against the agent's own (provider, model).
     *    Tier 3 — fixed bundled-agent (DeepSeek) running
     *      icons/report_3 with @RESPONSE@ only.
     *
     *  Each call's cost bumps the per-agent ReportAgent.iconInputCost
     *  / iconOutputCost so the row's cost cell shows the cumulative
     *  spend, AND the global UsageStats ledger with kind="icon"
     *  attributed to the actual provider/model that ran. Every
     *  attempt — including failed earlier tiers — appends an
     *  [IconCallRecord] to [Report.iconCalls] so the export's per-
     *  call All-tab can render each one as its own row.
     *
     *  All three tiers fail → 📝 fallback (icon set, iconWinningTier
     *  null — matches the existing "result must always be just one
     *  emoji" rule for the rest of the icon system).
     *
     *  The job registers in [rvm.reportIconsJobs] under
     *  "$reportId|$agentId" so deleteReport's prefix sweep cancels
     *  it; a re-fire for the same agent (regenerate path) cancels
     *  the previous run. */
    fun runReportIconsForAgent(
        context: Context, reportId: String,
        ra: ReportAgent, reportPrompt: String, aiSettings: Settings
    ) {
        val chatPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "icons" && it.name == "report_1"
        }
        val tier2Prompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "icons" && it.name == "report_2"
        }
        val tier3Prompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "icons" && it.name == "report_3"
        }
        if (chatPrompt == null && tier2Prompt == null && tier3Prompt == null) {
            AppLog.w("ReportIcons", "no icon prompts configured — skipping (agent=${ra.agentId})")
            return
        }
        val outer = appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            val agentProvider = AppService.findById(ra.provider) ?: return@launch
            val agentResponse = ra.responseBody.orEmpty()
            if (agentResponse.isBlank()) return@launch
            // Per-agent state reset — wipes this agent's icon fields
            // and removes its rows from the iconCalls audit log so a
            // regenerate re-fire starts clean. No-op on initial gen
            // (everything's already null). Other agents' state is
            // untouched.
            ReportStorage.clearReportAgentIconState(context, reportId, ra.agentId)
            appViewModel.updateUiState {
                it.copy(iconRefreshTick = it.iconRefreshTick + 1)
            }
            // Each tier overwrites this sink; the winning (last-run) tier
            // leaves its trace filename here, stored on the agent so the
            // per-model viewer's icon 🐞 points at the exact call.
            val iconTraceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)

            // Tier 1 — chat continuation.
            val tier1Emoji = chatPrompt?.let { p ->
                runTier1(context, reportId, agentProvider, ra, p, reportPrompt, agentResponse, aiSettings, iconTraceSink)
            }
            if (tier1Emoji != null) {
                commitChainResult(context, reportId, ra.agentId, tier1Emoji, winningTier = 1, traceFile = iconTraceSink.get())
                return@launch
            }

            // Tier 2 — one-shot report_icon template.
            val tier2Emoji = tier2Prompt?.let { p ->
                runTier2(context, reportId, agentProvider, ra, p, reportPrompt, agentResponse, aiSettings, iconTraceSink)
            }
            if (tier2Emoji != null) {
                commitChainResult(context, reportId, ra.agentId, tier2Emoji, winningTier = 2, traceFile = iconTraceSink.get())
                return@launch
            }

            // Tier 3 — fixed bundled-agent fallback.
            val tier3Emoji = tier3Prompt?.let { p ->
                runTier3(context, reportId, ra, p, agentResponse, aiSettings, iconTraceSink)
            }
            if (tier3Emoji != null) {
                commitChainResult(context, reportId, ra.agentId, tier3Emoji, winningTier = 3, traceFile = iconTraceSink.get())
                return@launch
            }

            // All three tiers failed — final 📝 fallback (no real call trace).
            commitChainResult(context, reportId, ra.agentId, "📝", winningTier = null)
        }
        rvm.registerReportIconForAgentJob(reportId, ra.agentId, outer)
    }

    /** Tier 1 of [runReportIcons]: continue the conversation as a
     *  chat. Returns the extracted first emoji on success, null
     *  otherwise (network error, no emoji in the response). Costs +
     *  IconCallRecord are written regardless of emoji extraction
     *  success — the user paid for the call either way. */
    private suspend fun runTier1(
        context: Context, reportId: String, provider: AppService,
        ra: ReportAgent, chatPrompt: InternalPrompt,
        reportPrompt: String, agentResponse: String, aiSettings: Settings,
        traceSink: java.util.concurrent.atomic.AtomicReference<String?>
    ): String? {
        val host = providerHost(provider)
        val releaser = ProviderThrottle.acquire(host)
        return try {
            withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                withTracerTags(reportId = reportId, category = "icon_report_2") {
                    val started = System.currentTimeMillis()
                    runCatching {
                        val messages = listOf(
                            ChatMessage(role = "user", content = reportPrompt),
                            ChatMessage(role = "assistant", content = agentResponse),
                            ChatMessage(role = "user", content = chatPrompt.text)
                        )
                        val apiKey = aiSettings.getApiKey(provider)
                        val baseUrl = aiSettings.getEffectiveEndpointUrl(provider)
                        val responseText = withTraceFilenameSink(traceSink) {
                            appViewModel.repository.sendChat(
                                service = provider, apiKey = apiKey, model = ra.model,
                                messages = messages, params = ChatParameters(), baseUrl = baseUrl
                            )
                        }
                        val durationMs = System.currentTimeMillis() - started
                        // sendChat returns plain text — no wire token
                        // counts. Char-length heuristic, same one
                        // ChatViewModel.sendDualChatMessage uses for
                        // usage-stats accounting.
                        val inT = messages.sumOf { AppViewModel.estimateTokens(it.content) }
                        val outT = AppViewModel.estimateTokens(responseText)
                        val emoji = extractFirstEmoji(responseText)
                        recordTierCall(
                            context, reportId, ra.agentId, tier = 1,
                            provider = provider, model = ra.model,
                            inT = inT, outT = outT, durationMs = durationMs,
                            success = emoji != null
                        )
                        emoji
                    }.getOrElse { e ->
                        AppLog.w("ReportIcons", "tier 1 failed for ${ra.agentId}: ${e.message}")
                        null
                    }
                }
            }
        } finally {
            releaser.release()
        }
    }

    /** Tier 2 of [runReportIcons]: one-shot icons/report
     *  template substitution against the agent's own (provider, model). */
    private suspend fun runTier2(
        context: Context, reportId: String, provider: AppService,
        ra: ReportAgent, tier2Prompt: InternalPrompt,
        reportPrompt: String, agentResponse: String, aiSettings: Settings,
        traceSink: java.util.concurrent.atomic.AtomicReference<String?>
    ): String? {
        val host = providerHost(provider)
        val releaser = ProviderThrottle.acquire(host)
        return try {
            withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                withTracerTags(reportId = reportId, category = "icon_report") {
                    val started = System.currentTimeMillis()
                    runCatching {
                        val syntheticAgent = Agent(
                            id = "report-icon-tier2-${ra.agentId}",
                            name = ra.agentName,
                            provider = provider,
                            model = ra.model,
                            apiKey = aiSettings.getApiKey(provider)
                        )
                        val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(syntheticAgent)
                        val resolved = tier2Prompt.text
                            .replace("@PROMPT@", reportPrompt)
                            .replace("@RESPONSE@", agentResponse)
                        val tierParams = resolveSecondaryParams(
                            appViewModel.uiState.value.generalSettings, aiSettings, emptyList(), null, tier2Prompt
                        )
                        val response = withTraceFilenameSink(traceSink) {
                            appViewModel.repository.analyzeWithAgent(
                                syntheticAgent, "", resolved, tierParams,
                                null, context, baseUrl
                            )
                        }
                        val durationMs = System.currentTimeMillis() - started
                        val tu = response.tokenUsage
                        val inT = tu?.inputTokens ?: 0
                        val outT = tu?.outputTokens ?: 0
                        val emoji = if (response.error == null) extractFirstEmoji(response.analysis) else null
                        recordTierCall(
                            context, reportId, ra.agentId, tier = 2,
                            provider = provider, model = ra.model,
                            inT = inT, outT = outT, durationMs = durationMs,
                            success = emoji != null
                        )
                        emoji
                    }.getOrElse { e ->
                        AppLog.w("ReportIcons", "tier 2 failed for ${ra.agentId}: ${e.message}")
                        null
                    }
                }
            }
        } finally {
            releaser.release()
        }
    }

    /** Tier 3 of [runReportIcons]: bundled fixed-agent fallback. Uses
     *  whichever Agent matches the report_3 prompt's pinned
     *  agent name (case-insensitive). When the user has no such
     *  agent configured, this returns null instantly — no API call,
     *  no IconCallRecord — and the chain falls through to 📝. */
    private suspend fun runTier3(
        context: Context, reportId: String,
        ra: ReportAgent, tier3Prompt: InternalPrompt,
        agentResponse: String, aiSettings: Settings,
        traceSink: java.util.concurrent.atomic.AtomicReference<String?>
    ): String? {
        val rawAgent = aiSettings.resolvePromptAgent(tier3Prompt) ?: run {
            AppLog.w("ReportIcons", "tier 3 skipped — no agent matching '${tier3Prompt.agent}' configured")
            return null
        }
        val effectiveAgent = rawAgent.copy(
            apiKey = aiSettings.getEffectiveApiKeyForAgent(rawAgent),
            model = aiSettings.getEffectiveModelForAgent(rawAgent)
        )
        val host = providerHost(effectiveAgent.provider)
        val releaser = ProviderThrottle.acquire(host)
        return try {
            withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                withTracerTags(reportId = reportId, category = "icon_report_3") {
                    val started = System.currentTimeMillis()
                    runCatching {
                        val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(effectiveAgent)
                        val resolved = tier3Prompt.text.replace("@RESPONSE@", agentResponse)
                        val tierParams = resolveSecondaryParams(
                            appViewModel.uiState.value.generalSettings, aiSettings, emptyList(), null, tier3Prompt, effectiveAgent
                        )
                        val response = withTraceFilenameSink(traceSink) {
                            appViewModel.repository.analyzeWithAgent(
                                effectiveAgent, "", resolved, tierParams,
                                null, context, baseUrl
                            )
                        }
                        val durationMs = System.currentTimeMillis() - started
                        val tu = response.tokenUsage
                        val inT = tu?.inputTokens ?: 0
                        val outT = tu?.outputTokens ?: 0
                        val emoji = if (response.error == null) extractFirstEmoji(response.analysis) else null
                        recordTierCall(
                            context, reportId, ra.agentId, tier = 3,
                            // Cost attribution for tier 3 goes to the
                            // ACTUAL model that ran (DeepSeek), not the
                            // agent's own provider/model. Surfaces in
                            // the global UsageStats and the export's
                            // All / Models tabs against DeepSeek.
                            provider = effectiveAgent.provider, model = effectiveAgent.model,
                            inT = inT, outT = outT, durationMs = durationMs,
                            success = emoji != null
                        )
                        emoji
                    }.getOrElse { e ->
                        AppLog.w("ReportIcons", "tier 3 failed for ${ra.agentId}: ${e.message}")
                        null
                    }
                }
            }
        } finally {
            releaser.release()
        }
    }

    /** Shared write-side of a tier call. Bumps the per-agent icon
     *  cost (so the row's cost cell totals every attempt), updates
     *  the global UsageStats ledger with kind="icon" attributed to
     *  the actual (provider, model) that billed, and appends an
     *  [IconCallRecord] for the export's per-call All-tab. */
    private suspend fun recordTierCall(
        context: Context, reportId: String, agentId: String, tier: Int,
        provider: AppService, model: String,
        inT: Int, outT: Int, durationMs: Long, success: Boolean
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
                success = success
            )
        )
    }

    /** Final commit step at the end of a chain — writes the emoji +
     *  winning-tier marker and bumps the icon-refresh tick so the
     *  result-screen row picks up the new value. */
    private suspend fun commitChainResult(
        context: Context, reportId: String, agentId: String,
        emoji: String, winningTier: Int?, traceFile: String? = null
    ) {
        // Map tier number to the bundled prompt name that produced
        // the icon — surfaces on the Icon lookup screen's subject row.
        val promptUsed = when (winningTier) {
            1 -> "report_1"
            2 -> "report_2"
            3 -> "report_3"
            else -> null
        }
        ReportStorage.setReportAgentIconAndTier(
            context, reportId, agentId, emoji, winningTier, promptUsed = promptUsed, traceFile = traceFile
        )
        appViewModel.updateUiState {
            it.copy(iconRefreshTick = it.iconRefreshTick + 1)
        }
    }

    // -----------------------------------------------------------------
    // Fan-out pair icon chain (mirrors runReportIconsForAgent)
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
    // reply), via the round-robin / 429-fallback worker engine.
    // Replaces the old separate fan-titles + fan-icons batches.
    // ============================================================

    fun runFanMetaBatch(
        context: Context,
        reportId: String,
        metaPromptId: String
    ): Job? {
        if (!appViewModel.uiState.value.generalSettings.fanIconsTitlesOn()) return null
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
                withTracerTags(reportId = reportId, category = "fan_meta", runId = fanRunId) {
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
     *  reply and stores BOTH. Worker engine handles round-robin + 429. */
    private suspend fun runFanMetaForPair(
        context: Context, reportId: String, pair: SecondaryResult,
        fanMetaPrompt: InternalPrompt, aiSettings: Settings
    ) {
        val started = System.currentTimeMillis()
        val resolved = fanMetaPrompt.text.replace("@PROMPT@", pair.content.orEmpty())
        val outcome = rvm.workerRunner.run(fanMetaPrompt, resolved, aiSettings, context)
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
                        inputCost = inT * pricing.promptPrice, outputCost = outT * pricing.completionPrice
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
