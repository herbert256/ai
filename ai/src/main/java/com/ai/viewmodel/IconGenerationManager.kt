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

/** Identifies one "Find alternative …" fan-out so its alt-prompt can be
 *  resolved (markers replaced) for the pre-pick "Edit prompt" screen, and
 *  later run with the user's edited text. Mirrors the routing in
 *  `FindIconsPickerRouter` + the translate alt flow. */
sealed class AltPromptFlow {
    data class ReportTitle(val reportId: String, val promptText: String, val long: Boolean) : AltPromptFlow()
    data class ModelTitle(val reportId: String, val agentId: String) : AltPromptFlow()
    data class PairTitle(val reportId: String, val pairId: String) : AltPromptFlow()
    data class ReportIcon(val reportId: String, val promptText: String) : AltPromptFlow()
    data class AgentIcon(val reportId: String, val agentId: String) : AltPromptFlow()
    data class PairIcon(val reportId: String, val pairId: String) : AltPromptFlow()
    data class MetaIcon(val promptId: String) : AltPromptFlow()
    data class LanguageIcon(val reportId: String) : AltPromptFlow()
    data class TranslationIcon(val language: String) : AltPromptFlow()
    data class TranslationText(val isTitleKind: Boolean, val targetLanguageName: String, val sourceText: String) : AltPromptFlow()
}

internal fun metaCacheVariantForInternalPrompt(prompt: InternalPrompt?, aiSettings: Settings): String {
    if (prompt == null) return ""
    fun promptParams(name: String): String =
        name.takeIf { it.isNotBlank() && it != "*NONE" }
            ?.let { aiSettings.getParametersByName(it) }
            ?.let { "${it.id}:${it}" }
            .orEmpty()
    fun promptSystem(name: String): String =
        name.takeIf { it.isNotBlank() && it != "*NONE" }
            ?.let { promptName ->
                aiSettings.systemPrompts.find { it.name.equals(promptName, ignoreCase = true) }
            }
            ?.let { "${it.id}:${it.prompt}" }
            .orEmpty()
    fun agentParams(agent: Agent): String =
        agent.paramsIds.mapNotNull { aiSettings.getParametersById(it) }
            .joinToString("|") { "${it.id}:${it}" }
    fun agentSystem(agent: Agent): String =
        agent.systemPromptId
            ?.let { id -> aiSettings.getSystemPromptById(id)?.let { "${it.id}:${it.prompt}" } }
            .orEmpty()
    val workers = prompt.workers
        .flatMap { worker -> aiSettings.expandWorker(worker).ifEmpty { listOf(worker) } }
        .joinToString("||") { worker ->
            val agent = aiSettings.resolveWorker(worker)
            listOf(
                worker.agent, worker.provider, worker.model, worker.flock, worker.swarm,
                agent?.id.orEmpty(),
                agent?.provider?.id.orEmpty(),
                agent?.let { aiSettings.getEffectiveModelForAgent(it) }.orEmpty(),
                agent?.let(::agentParams).orEmpty(),
                agent?.let(::agentSystem).orEmpty()
            ).joinToString("|")
        }
    return listOf(
        prompt.id,
        prompt.name,
        prompt.text,
        prompt.parameters,
        promptParams(prompt.parameters),
        prompt.systemPrompt,
        promptSystem(prompt.systemPrompt),
        workers
    ).joinToString("\u001f")
}

/** The resolved alt prompt for a flow: the underlying prompt id (for
 *  persistence), the fully marker-replaced text shown in the editor, and
 *  the ordered marker→value substitutions that produced it (so a saved
 *  edit can be reverse-substituted back into the template). */
data class ResolvedAltPrompt(
    val promptId: String,
    val resolved: String,
    val subs: List<Pair<String, String>>,
)

/** What the pre-pick editor hands back: the (possibly edited) resolved
 *  prompt plus the metadata needed to persist it onto the alt template. */
data class AltEditPayload(
    val promptId: String,
    val edited: String,
    val subs: List<Pair<String, String>>,
)

/** The category=="alt" internal-prompt name a find-alternative [flow] composes
 *  with. Single source of truth for the run-time worker-skip check; the
 *  `alt(...)` lookups in [IconGenerationManager.resolveAltPrompt] must use the
 *  same names. Null for flows that don't draw on an "alt" template
 *  (TranslationText uses the translate-title / translate-text prompt directly,
 *  and never reaches the find-icons picker). */
fun altPromptNameFor(flow: AltPromptFlow): String? = when (flow) {
    is AltPromptFlow.ReportTitle -> if (flow.long) "report_title_long" else "report_title"
    is AltPromptFlow.ModelTitle -> "model_title"
    is AltPromptFlow.PairTitle -> "model_title"
    is AltPromptFlow.ReportIcon -> "main"
    is AltPromptFlow.AgentIcon -> "report"
    is AltPromptFlow.PairIcon -> "fan_out"
    is AltPromptFlow.MetaIcon -> "meta"
    is AltPromptFlow.LanguageIcon -> "language"
    is AltPromptFlow.TranslationIcon -> "translation"
    is AltPromptFlow.TranslationText -> null
}

/** The configured worker models for a find-alternative [flow]: resolve the alt
 *  prompt's [InternalPrompt.workers] chain (Model / Agent / Flock / Swarm) to
 *  concrete provider/model [ReportModel]s. Empty when the alt prompt carries no
 *  resolvable worker — the caller then shows the model-selection screen. A
 *  flock/swarm expands to several models (several candidates); a single
 *  agent/model yields one. */
fun altWorkerModels(aiSettings: Settings, flow: AltPromptFlow): List<ReportModel> {
    val name = altPromptNameFor(flow) ?: return emptyList()
    val prompt = aiSettings.internalPrompts.firstOrNull {
        it.category == "alt" && it.name.equals(name, ignoreCase = true)
    } ?: return emptyList()
    return prompt.workers
        .flatMap { aiSettings.expandWorker(it) }
        .mapNotNull { aiSettings.resolveWorker(it) }
        .map { toReportModel(it.provider, it.model) }
        .distinctBy { "${it.provider.id}/${it.model}" }
}

/** The Model-selection mode of the "alt" prompt a [flow] composes (or
 *  *CONFIGURED when none). When *SELECT, the caller forces the model-selection
 *  screen (empties the auto-resolved workers) so the user picks per run. */
fun altPromptModelSelection(aiSettings: Settings, flow: AltPromptFlow): String {
    val name = altPromptNameFor(flow) ?: return com.ai.model.MODEL_SELECTION_CONFIGURED
    return aiSettings.internalPrompts.firstOrNull {
        it.category == "alt" && it.name.equals(name, ignoreCase = true)
    }?.modelSelection ?: com.ai.model.MODEL_SELECTION_CONFIGURED
}

/** The worker models for the Find-alternative-translation flow: resolve
 *  the workers/find-translation holder prompt's swarm to concrete
 *  provider/model [ReportModel]s (one candidate each). Empty when the
 *  prompt is missing or has no resolvable worker — the caller then falls
 *  back to the model-selection screen. Mirrors [altWorkerModels], but for
 *  the TranslationText flow, which composes the translate-text /
 *  translate-title prompt rather than an "alt" template. */
fun findAltTranslationModels(aiSettings: Settings): List<ReportModel> {
    val prompt = aiSettings.internalPrompts.firstOrNull {
        it.category == "workers" && it.name.equals("find-translation", ignoreCase = true)
    } ?: return emptyList()
    return prompt.workers
        .flatMap { aiSettings.expandWorker(it) }
        .mapNotNull { aiSettings.resolveWorker(it) }
        .map { toReportModel(it.provider, it.model) }
        .distinctBy { "${it.provider.id}/${it.model}" }
}

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
    private fun costSplit(
        usage: TokenUsage?,
        pricing: PricingCache.ModelPricing?
    ): Pair<Double, Double> =
        if (usage != null && pricing != null) PricingCache.computeInOutCost(usage, pricing)
        else 0.0 to 0.0

    // ===== Find-alternative: pre-pick "Edit prompt" support =====
    // The user edits the resolved alt prompt BEFORE picking models. The
    // edited text is stashed here the instant they tap Next and consumed
    // by the next start*FanOut call (the only thing the model picker's
    // confirm can trigger). One-shot — cleared on consume.
    @Volatile
    var pendingAltEdit: AltEditPayload? = null

    /** Take the stashed edit (if any), clear it, and kick off a
     *  best-effort persist back to the template. Called once at the top
     *  of every start*FanOut. */
    internal fun consumeAltEdit(): AltEditPayload? {
        val e = pendingAltEdit
        pendingAltEdit = null
        e?.let { persistAltEdit(it) }
        return e
    }

    /** Resolve the alt (or translate-) prompt for [flow] with its
     *  @VAR@ markers replaced, for the pre-pick editor. Reads the same
     *  templates + values the matching start*FanOut would, so the editor
     *  shows exactly what hits the wire. */
    suspend fun resolveAltPrompt(
        context: Context,
        aiSettings: Settings,
        flow: AltPromptFlow
    ): ResolvedAltPrompt? = withContext(Dispatchers.IO) {
        // Names here must match [altPromptNameFor] (the run-time worker-skip
        // check resolves the same alt prompt by that map).
        fun alt(name: String) = aiSettings.internalPrompts.firstOrNull {
            it.category == "alt" && it.name.equals(name, ignoreCase = true)
        }
        fun build(p: InternalPrompt?, subs: List<Pair<String, String>>): ResolvedAltPrompt? {
            p ?: return null
            val resolved = subs.fold(p.text) { acc, (m, v) -> acc.replace(m, v) }
            return ResolvedAltPrompt(p.id, resolved, subs)
        }
        when (flow) {
            is AltPromptFlow.ReportTitle ->
                build(alt(if (flow.long) "report_title_long" else "report_title"),
                    listOf("@PROMPT@" to flow.promptText))
            is AltPromptFlow.ModelTitle -> {
                val ra = ReportStorage.getReport(context, flow.reportId)
                    ?.agents?.firstOrNull { it.agentId == flow.agentId }
                build(alt("model_title"), listOf("@RESPONSE@" to ra?.responseBody.orEmpty()))
            }
            is AltPromptFlow.PairTitle -> {
                val pair = SecondaryResultStorage.listForReport(context, flow.reportId)
                    .firstOrNull { it.id == flow.pairId }
                build(alt("model_title"), listOf("@RESPONSE@" to pair?.content.orEmpty()))
            }
            is AltPromptFlow.ReportIcon ->
                build(alt("main"), listOf("@PROMPT@" to flow.promptText))
            is AltPromptFlow.AgentIcon -> {
                val report = ReportStorage.getReport(context, flow.reportId)
                val ra = report?.agents?.firstOrNull { it.agentId == flow.agentId }
                build(alt("report"), listOf(
                    "@PROMPT@" to report?.prompt.orEmpty(),
                    "@RESPONSE@" to ra?.responseBody.orEmpty(),
                ))
            }
            is AltPromptFlow.PairIcon -> {
                val report = ReportStorage.getReport(context, flow.reportId)
                val pair = SecondaryResultStorage.listForReport(context, flow.reportId)
                    .firstOrNull { it.id == flow.pairId }
                val sourceAgent = pair?.fanOutSourceAgentId?.let { sid ->
                    report?.agents?.firstOrNull { it.agentId == sid }
                }
                val metaPrompt = pair?.metaPromptId?.let { mid ->
                    aiSettings.internalPrompts.firstOrNull { it.id == mid }
                }
                build(alt("fan_out"), listOf(
                    "@QUESTION@" to report?.prompt.orEmpty(),
                    "@SOURCE_RESPONSE@" to sourceAgent?.responseBody.orEmpty(),
                    "@META_PROMPT@" to metaPrompt?.text.orEmpty(),
                    "@RESPONSE@" to pair?.content.orEmpty(),
                ))
            }
            is AltPromptFlow.MetaIcon -> {
                val p = aiSettings.internalPrompts.firstOrNull { it.id == flow.promptId }
                    ?: return@withContext null
                build(alt("meta"), listOf("@NAME@" to p.name, "@TITLE@" to p.title))
            }
            is AltPromptFlow.LanguageIcon -> {
                val report = ReportStorage.getReport(context, flow.reportId)
                build(alt("language"), listOf("@LANGUAGE@" to report?.languageName.orEmpty()))
            }
            is AltPromptFlow.TranslationIcon ->
                build(alt("translation"), listOf("@LANGUAGE@" to flow.language))
            is AltPromptFlow.TranslationText -> {
                // Translate alt reuses the translate-title / translate-text
                // prompt itself (not an alt template).
                val p = aiSettings.getInternalPromptByName(
                    if (flow.isTitleKind) "translate-title" else "translate-text"
                )
                val subs = if (flow.isTitleKind)
                    listOf("@LANGUAGE@" to flow.targetLanguageName, "@TITLE@" to flow.sourceText)
                else
                    listOf("@LANGUAGE@" to flow.targetLanguageName, "@TEXT@" to flow.sourceText)
                build(p, subs)
            }
        }
    }

    /** Best-effort: reverse-substitute the user's edited (resolved) prompt
     *  back into a template and save it onto the prompt, so a future Find-
     *  alternative starts from the edit. Skips silently when the edit can't
     *  be faithfully re-abstracted (a data region was edited, or a
     *  substituted value was blank) — never corrupts the shared template. */
    private fun persistAltEdit(payload: AltEditPayload) {
        val template = recoverTemplate(payload.edited, payload.subs) ?: return
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val cur = appViewModel.uiState.value.aiSettings
            val existing = cur.internalPrompts.firstOrNull { it.id == payload.promptId } ?: return@launch
            if (existing.text == template) return@launch
            val updated = cur.internalPrompts.map {
                if (it.id == payload.promptId) it.copy(text = template) else it
            }
            appViewModel.updateSettings(cur.copy(internalPrompts = updated))
        }
    }

    /** Re-abstract [edited] back into a template by replacing each
     *  substituted value with its marker. Returns null when it can't be
     *  done faithfully — a blank value, a value the user edited away, or a
     *  reconstruction that doesn't re-resolve to [edited] exactly. */
    private fun recoverTemplate(edited: String, subs: List<Pair<String, String>>): String? {
        if (subs.isEmpty()) return edited
        var t = edited
        // Longest values first so a short value that's a substring of a
        // longer one doesn't grab the wrong span.
        for ((marker, value) in subs.sortedByDescending { it.second.length }) {
            if (value.isBlank()) return null
            val idx = t.indexOf(value)
            if (idx < 0) return null
            t = t.substring(0, idx) + marker + t.substring(idx + value.length)
        }
        // Faithfulness check: re-resolving (in the original apply order)
        // must reproduce the edited text exactly.
        val reResolved = subs.fold(t) { acc, (m, v) -> acc.replace(m, v) }
        return if (reResolved == edited) t else null
    }

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
            withTracerTags(reportId = reportId, category = "report/icon") {
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
                // ♻️ When the report flag is on, retrieve the icon from one of the
                // report's own models (workerRunner shuffles → random report-model).
                val effIconPrompt = if (report?.useReportModelsAsWorkers == true)
                    iconPrompt.copy(workers = reportModelWorkers(report)) else iconPrompt
                val started = System.currentTimeMillis()
                val outcome = withTraceFilenameSink(traceSink) {
                    // A worker reply with no parseable emoji is a logical miss —
                    // fall through to the next worker instead of accepting an
                    // empty 200 and storing the 📝 fallback.
                    rvm.workerRunner.run(effIconPrompt, resolved, aiSettings, context) {
                        extractFirstEmoji(it.analysis) != null
                    }
                }
                val durationMs = System.currentTimeMillis() - started
                when (outcome) {
                    is WorkerOutcome.Success -> {
                        // Always end with exactly one emoji glyph (first emoji,
                        // strip prose, 📝 fallback on an empty 200).
                        val emoji = extractFirstEmoji(outcome.response.analysis) ?: MetadataIconsHolder.current.reportIcon
                        val winAgent = aiSettings.resolveWorker(outcome.worker)?.let {
                            it.copy(model = aiSettings.getEffectiveModelForAgent(it))
                        }
                        val tu = outcome.response.tokenUsage
                        val inT = tu?.inputTokens ?: 0
                        val outT = tu?.outputTokens ?: 0
                        val pricing = winAgent?.let { PricingCache.getPricing(context, it.provider, it.model) }
                        val (inC, outC) = costSplit(tu, pricing)
                        ReportStorage.updateReportIcon(
                            context, reportId, emoji,
                            inputTokens = inT, outputTokens = outT,
                            inputCost = inC, outputCost = outC,
                            traceFile = traceSink.get(),
                            promptUsed = "main",
                            durationMs = durationMs
                        )
                        if (tu != null && winAgent != null && (inT > 0 || outT > 0)) {
                            appViewModel.settingsPrefs.updateUsageStatsAsync(
                                winAgent.provider, winAgent.model, tu, kind = "icon"
                            )
                        }
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
     *  ("report/title-short" / "-long") so each title editor's 🐞
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
        // Same prompt text → same title; serve from the 7-day meta cache.
        // Keyed per trace-category so the short and long titles don't
        // collide on the same input text.
        val cacheVariant = metaCacheVariantForInternalPrompt(prompt, aiSettings)
        com.ai.data.MetaCache.get(traceCategory, promptText, cacheVariant)?.let { cached ->
            return TitleGenResult(
                title = cached.take(cap),
                inputTokens = 0, outputTokens = 0,
                inputCost = 0.0, outputCost = 0.0,
                durationMs = 0L, traceFile = null, model = null
            )
        }
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
        com.ai.data.MetaCache.put(traceCategory, promptText, title, cacheVariant)
        val winAgent = aiSettings.resolveWorker(outcome.worker)?.let {
            it.copy(model = aiSettings.getEffectiveModelForAgent(it))
        }
        val tu = outcome.response.tokenUsage
        val inT = tu?.inputTokens ?: 0
        val outT = tu?.outputTokens ?: 0
        val pricing = winAgent?.let { PricingCache.getPricing(context, it.provider, it.model) }
        val (inC, outC) = costSplit(tu, pricing)
        if (tu != null && winAgent != null && (inT > 0 || outT > 0)) {
            appViewModel.settingsPrefs.updateUsageStatsAsync(
                winAgent.provider, winAgent.model, tu, kind = "title"
            )
        }
        return TitleGenResult(
            title = title,
            inputTokens = inT, outputTokens = outT,
            inputCost = inC,
            outputCost = outC,
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
         *  attempt — so report/icon sees the freshly-stored long
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
                val s = async { runTitlePrompt(context, reportId, shortPrompt, promptText, aiSettings, cap = 25, traceCategory = "report/title-short") }
                val l = async { runTitlePrompt(context, reportId, longPrompt, promptText, aiSettings, cap = 50, traceCategory = "report/title-long") }
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
                // Persist each call's spend into its own cost/token block so
                // the cost table shows two rows: report/title-short and
                // report/title-long.
                ReportStorage.updateReportTitleFromAi(
                    context, reportId, shortTitle,
                    titleLong = longTitle?.takeIf { it.isNotBlank() },
                    promptUsed = "report_title",
                    shortInputTokens = short?.inputTokens ?: 0,
                    shortOutputTokens = short?.outputTokens ?: 0,
                    shortInputCost = short?.inputCost ?: 0.0,
                    shortOutputCost = short?.outputCost ?: 0.0,
                    shortTraceFile = short?.traceFile,
                    shortModel = short?.model,
                    shortDurationMs = short?.durationMs,
                    longInputTokens = long?.inputTokens ?: 0,
                    longOutputTokens = long?.outputTokens ?: 0,
                    longInputCost = long?.inputCost ?: 0.0,
                    longOutputCost = long?.outputCost ?: 0.0,
                    longTraceFile = long?.traceFile,
                    longModel = long?.model,
                    longDurationMs = long?.durationMs,
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

    /** Generate a short AI title for one user note via the bundled
     *  `workers/user-note` prompt and persist it onto the [UserNote].
     *  Fired whenever a note is saved (add/edit). Best-effort: bails
     *  silently when the prompt is missing or no worker resolves, so a
     *  note without configured providers simply keeps no title. The
     *  worker spend is logged as a `note/title` [IconCallRecord] so it
     *  surfaces in the cost table (grouped under "note") and the
     *  lifetime total. No master-switch gate — the user asked for it on
     *  every save. */
    internal fun kickOffUserNoteTitle(
        context: Context,
        reportId: String,
        noteId: String,
        noteText: String,
        aiSettings: Settings
    ) {
        val prompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "workers" && it.name == "user-note"
        } ?: return
        if (prompt.workers.none { aiSettings.resolveWorker(it) != null }) return
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            val res = runTitlePrompt(
                context, reportId, prompt, noteText, aiSettings,
                cap = 40, traceCategory = "note/title"
            ) ?: return@launch
            ReportStorage.setUserNoteTitle(context, reportId, noteId, res.title)
            // Record the worker spend so the cost table + total account for it.
            val parts = res.model?.split("/", limit = 2)
            val providerId = parts?.firstOrNull().orEmpty()
            val modelId = parts?.getOrNull(1) ?: res.model.orEmpty()
            val tier = AppService.findById(providerId)
                ?.let { PricingCache.getPricing(context, it, modelId)?.source }
                .orEmpty()
            if (res.inputCost > 0.0 || res.outputCost > 0.0 || res.inputTokens > 0 || res.outputTokens > 0) {
                ReportStorage.appendIconCall(
                    context, reportId,
                    IconCallRecord(
                        agentId = noteId,
                        tier = 0,
                        provider = providerId,
                        model = modelId,
                        pricingTier = tier,
                        inputTokens = res.inputTokens,
                        outputTokens = res.outputTokens,
                        inputCost = res.inputCost,
                        outputCost = res.outputCost,
                        durationMs = res.durationMs,
                        success = true,
                        type = "note/title"
                    )
                )
            }
            // Nudge any open card to re-read the note (the storage save
            // already bumped ReportDataVersion; this also refreshes Manage's
            // icon-tick-keyed reads).
            appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
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
            // Icon-only → still derive the icon from a title (model/icons
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
         *  FROM the title (model/icons); fall back to the response-
         *  based 3-tier chain when no usable title is produced. */
        thenIconFromTitle: Boolean = false,
        /** When false the resolved title is used ONLY to feed the icon
         *  (@TITLE@) and is never written to [ReportAgent.modelTitle] — the
         *  icon-only config, where the per-model title row is hidden. */
        storeTitle: Boolean = true
    ) {
        // Worker-based: random-pick / 429-fallback over model/titles.
        val titlePrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "workers" && it.name == "model-titles"
        } ?: return
        if (titlePrompt.workers.none { aiSettings.resolveWorker(it) != null }) return
        val agentResponse = ra.responseBody.orEmpty()
        if (agentResponse.isBlank()) return
        val resolved = titlePrompt.text.replace("@RESPONSE@", agentResponse)
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            var generatedTitle: String? = null
            withTracerTags(reportId = reportId, category = "model/titles") {
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
                            // Cost/usage are recorded in BOTH configs — the
                            // call is billed regardless of whether the title is
                            // surfaced. With storeTitle the spend lands on the
                            // model-title row; in the icon-only config the title
                            // is transient (it only feeds @TITLE@), so attribute
                            // its spend to the agent's icon cost instead of
                            // dropping it from the report total.
                            val winAgent = aiSettings.resolveWorker(outcome.worker)?.let {
                                it.copy(model = aiSettings.getEffectiveModelForAgent(it))
                            }
                            val tu = outcome.response.tokenUsage
                            val inT = tu?.inputTokens ?: 0
                            val outT = tu?.outputTokens ?: 0
                            val pricing = winAgent?.let { PricingCache.getPricing(context, it.provider, it.model) }
                            val (inC, outC) = costSplit(tu, pricing)
                            if (storeTitle) {
                                ReportStorage.updateReportAgentModelTitle(
                                    context, reportId, ra.agentId, generated,
                                    model = winAgent?.let { "${it.provider.id}/${it.model}" },
                                    inputTokens = inT, outputTokens = outT,
                                    inputCost = inC, outputCost = outC,
                                    traceFile = traceSink.get(),
                                    promptUsed = "model_title",
                                    durationMs = durationMs
                                )
                            } else if (inT > 0 || outT > 0 || inC > 0.0 || outC > 0.0) {
                                ReportStorage.bumpReportAgentIconCost(
                                    context, reportId, ra.agentId, inT, outT, inC, outC
                                )
                            }
                            if ((inT > 0 || outT > 0) && winAgent != null && tu != null) {
                                appViewModel.settingsPrefs.updateUsageStatsAsync(
                                    winAgent.provider, winAgent.model, tu, kind = "title"
                                )
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
            // engine (model/icons). No usable title — or no worker
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
        // Worker-based: random-pick / 429-fallback over model/icons.
        val prompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "workers" && it.name == "model-icons"
        } ?: return false
        if (prompt.workers.none { aiSettings.resolveWorker(it) != null }) return false
        // Reset this agent's icon fields + iconCalls so a re-fire
        // (regenerate) replaces rather than accumulates — matches the
        // 3-tier chain's clearReportAgentIconState at its own start.
        ReportStorage.clearReportAgentIconState(context, reportId, ra.agentId)
        return withTracerTags(reportId = reportId, category = "model/icons") {
            val started = System.currentTimeMillis()
            val resolved = prompt.text.replace("@TITLE@", title)
            // ♻️ report flag → the agent icon is retrieved from a report-model.
            val report = ReportStorage.getReport(context, reportId)
            val effPrompt = if (report?.useReportModelsAsWorkers == true)
                prompt.copy(workers = reportModelWorkers(report)) else prompt
            // Capture the trace filename of the winning icon call so the
            // Model-response screen's 🐞 next to the big icon can deep-link
            // to the exact call that decided this icon (the worker runs on
            // its own model, so a category+agent-model lookup can't find it).
            val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
            // No parseable emoji is a logical miss — advance to the next worker
            // rather than accepting a 200 that leaves the agent icon-less.
            val outcome = withTraceFilenameSink(traceSink) {
                rvm.workerRunner.run(effPrompt, resolved, aiSettings, context) {
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
                        tokenUsage = tu,
                        type = "model/icons"
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
        // Two chained worker calls now: report-language-name detects the
        // language NAME from the prompt, then report-language-icon picks a
        // fitting emoji for that name. Each call's cost / duration is
        // attributed to its own row (detect vs icon). Both run through the
        // random-pick / 429-fallback worker engine.
        val namePrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "workers" && it.name == "report-language-name"
        } ?: return
        if (namePrompt.workers.none { aiSettings.resolveWorker(it) != null }) return
        val iconPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "workers" && it.name == "report-language-icon"
        }
        val resolvedName = namePrompt.text.replace("@PROMPT@", promptText)
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            appViewModel.updateRunningInfoJobs { it + "$reportId|language" }
            // ♻️ When the report flag is on, language detect + icon run on the
            // report's own models too (workerRunner shuffles → random model).
            val langReport = ReportStorage.getReport(context, reportId)
            val effNamePrompt = if (langReport?.useReportModelsAsWorkers == true)
                namePrompt.copy(workers = reportModelWorkers(langReport)) else namePrompt
            val effIconPrompt = iconPrompt?.let {
                if (langReport?.useReportModelsAsWorkers == true) it.copy(workers = reportModelWorkers(langReport)) else it
            }
            // ---- 1) Language name ----
            val detectedName = try {
                withTracerTags(reportId = reportId, category = "report/language") {
                    val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
                    val started = System.currentTimeMillis()
                    val outcome = withTraceFilenameSink(traceSink) {
                        rvm.workerRunner.run(effNamePrompt, resolvedName, aiSettings, context) {
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
                                null
                            } else {
                                val winAgent = aiSettings.resolveWorker(outcome.worker)?.let {
                                    it.copy(model = aiSettings.getEffectiveModelForAgent(it))
                                }
                                val tu = outcome.response.tokenUsage
                                val inT = tu?.inputTokens ?: 0
                                val outT = tu?.outputTokens ?: 0
                                val pricing = winAgent?.let { PricingCache.getPricing(context, it.provider, it.model) }
                                val (inC, outC) = costSplit(tu, pricing)
                                ReportStorage.updateReportLanguageDetect(
                                    context, reportId,
                                    name = name,
                                    inputTokens = inT, outputTokens = outT,
                                    inputCost = inC, outputCost = outC,
                                    traceFile = traceSink.get(),
                                    model = winAgent?.let { "${it.provider.id}/${it.model}" },
                                    rawResponse = analysis,
                                    durationMs = durationMs
                                )
                                if (tu != null && winAgent != null && (inT > 0 || outT > 0)) {
                                    appViewModel.settingsPrefs.updateUsageStatsAsync(
                                        winAgent.provider, winAgent.model, tu, kind = "language"
                                    )
                                }
                                name
                            }
                        }
                        else -> {
                            ReportStorage.updateReportLanguageError(
                                context, reportId,
                                if (outcome is WorkerOutcome.AllRateLimited) "language: all workers rate-limited"
                                else "language: no worker produced a result"
                            )
                            null
                        }
                    }
                }
            } finally {
                appViewModel.updateRunningInfoJobs { it - "$reportId|language" }
            }
            // ---- 2) Language icon (only once we have a name) ----
            if (detectedName != null) {
                // Same language → same emoji; serve from the 7-day meta
                // cache when present and skip the LLM call entirely.
                val iconCacheVariant = metaCacheVariantForInternalPrompt(effIconPrompt, aiSettings)
                val cachedIcon = com.ai.data.MetaCache.get("language-icon", detectedName, iconCacheVariant)
                val iconRunnable = effIconPrompt != null &&
                    effIconPrompt.workers.any { aiSettings.resolveWorker(it) != null }
                if (cachedIcon != null) {
                    ReportStorage.updateReportLanguageIcon(
                        context, reportId,
                        icon = cachedIcon, model = null,
                        inputTokens = 0, outputTokens = 0,
                        inputCost = 0.0, outputCost = 0.0,
                        traceFile = null, rawResponse = null,
                        promptUsed = "language-icon", durationMs = 0L
                    )
                } else if (iconRunnable) {
                    appViewModel.updateRunningInfoJobs { it + "$reportId|language-icon" }
                    try {
                        withTracerTags(reportId = reportId, category = "report/language-icon") {
                            val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
                            val resolvedIcon = effIconPrompt!!.text.replace("@LANGUAGE@", detectedName)
                            val started = System.currentTimeMillis()
                            val outcome = withTraceFilenameSink(traceSink) {
                                rvm.workerRunner.run(effIconPrompt, resolvedIcon, aiSettings, context) {
                                    extractFirstEmoji(it.analysis.orEmpty()) != null
                                }
                            }
                            val durationMs = System.currentTimeMillis() - started
                            if (outcome is WorkerOutcome.Success) {
                                val analysis = outcome.response.analysis
                                // The accept predicate guarantees a parseable emoji
                                // on Success, so this fallback is belt-and-braces.
                                val emoji = extractFirstEmoji(analysis.orEmpty()) ?: MetadataIconsHolder.current.languageIcon
                                // Cache the real parsed emoji for this language (7-day).
                                com.ai.data.MetaCache.put("language-icon", detectedName, emoji, iconCacheVariant)
                                val winAgent = aiSettings.resolveWorker(outcome.worker)?.let {
                                    it.copy(model = aiSettings.getEffectiveModelForAgent(it))
                                }
                                val tu = outcome.response.tokenUsage
                                val inT = tu?.inputTokens ?: 0
                                val outT = tu?.outputTokens ?: 0
                                val pricing = winAgent?.let { PricingCache.getPricing(context, it.provider, it.model) }
                                val (inC, outC) = costSplit(tu, pricing)
                                ReportStorage.updateReportLanguageIcon(
                                    context, reportId,
                                    icon = emoji,
                                    model = winAgent?.let { "${it.provider.id}/${it.model}" },
                                    inputTokens = inT, outputTokens = outT,
                                    inputCost = inC, outputCost = outC,
                                    traceFile = traceSink.get(),
                                    rawResponse = analysis,
                                    promptUsed = "language-icon",
                                    durationMs = durationMs
                                )
                                if (tu != null && winAgent != null && (inT > 0 || outT > 0)) {
                                    appViewModel.settingsPrefs.updateUsageStatsAsync(
                                        winAgent.provider, winAgent.model, tu, kind = "language-icon"
                                    )
                                }
                            } else {
                                // Rate-limited / no-result: record an error row
                                // instead of silently storing the default glyph as
                                // a success (which would clear the error and mask
                                // the failure). The detected language name is left
                                // untouched.
                                ReportStorage.updateReportLanguageError(
                                    context, reportId,
                                    if (outcome is WorkerOutcome.AllRateLimited) "language-icon: all workers rate-limited"
                                    else "language-icon: no worker produced an icon"
                                )
                            }
                        }
                    } finally {
                        appViewModel.updateRunningInfoJobs { it - "$reportId|language-icon" }
                    }
                } else {
                    // No icon prompt available — fall back to the default
                    // language glyph so the icon row isn't left blank.
                    ReportStorage.updateReportLanguageIcon(
                        context, reportId,
                        icon = MetadataIconsHolder.current.languageIcon,
                        model = null,
                        inputTokens = 0, outputTokens = 0,
                        inputCost = 0.0, outputCost = 0.0,
                        traceFile = null,
                        rawResponse = null,
                        promptUsed = "language-icon",
                        durationMs = 0L
                    )
                }
            }
            appViewModel.updateUiState {
                it.copy(iconRefreshTick = it.iconRefreshTick + 1)
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

    /** Background helper that runs the bundled `second/meta` prompt
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
            AppLog.w("InternalPromptIcon", "second/meta not configured — skipping")
            InternalPromptIconCache.clearInFlight(prompt.name, prompt.title)
            return
        }
        val resolved = iconPrompt.text
            .replace("@NAME@", prompt.name)
            .replace("@TITLE@", prompt.title)

        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            withTracerTags(category = "second/meta") {
              try {
                val outcome = rvm.workerRunner.run(iconPrompt, resolved, aiSettings, context) {
                    extractFirstEmoji(it.analysis) != null
                }
                if (outcome is WorkerOutcome.Success) {
                    val emoji = extractFirstEmoji(outcome.response.analysis) ?: MetadataIconsHolder.current.meta
                    val winAgent = aiSettings.resolveWorker(outcome.worker)?.let {
                        it.copy(model = aiSettings.getEffectiveModelForAgent(it))
                    }
                    val tu = outcome.response.tokenUsage
                    val inT = tu?.inputTokens ?: 0
                    val outT = tu?.outputTokens ?: 0
                    val pricing = winAgent?.let { PricingCache.getPricing(context, it.provider, it.model) }
                    val (inC, outC) = costSplit(tu, pricing)
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
                    if ((inT > 0 || outT > 0) && winAgent != null && tu != null) {
                        appViewModel.settingsPrefs.updateUsageStatsAsync(
                            winAgent.provider, winAgent.model, tu, kind = "icon"
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
        val altEdit = consumeAltEdit()
        val resolved = altEdit?.edited ?: altPrompt.text
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
                            val (inC, outC) = costSplit(tu, pricing)
                            if (inT > 0 || outT > 0) {
                                InternalPromptIconCache.bumpCost(
                                    prompt.name, prompt.title, inT, outT, inC, outC
                                )
                                tu?.let {
                                    appViewModel.settingsPrefs.updateUsageStatsAsync(
                                        item.provider, item.model, it, kind = "icon"
                                    )
                                }
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
                                extractFirstEmoji(response.analysis) ?: MetadataIconsHolder.current.meta
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
        val altEdit = consumeAltEdit()
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
            val resolved = altEdit?.edited ?: altPrompt.text
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
                                    val (inC, outC) = costSplit(tu, pricing)
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
                                        tu?.let {
                                            appViewModel.settingsPrefs.updateUsageStatsAsync(
                                                item.provider, item.model, it, kind = "icon"
                                            )
                                        }
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
                                        val emoji = extractFirstEmoji(response.analysis) ?: MetadataIconsHolder.current.fanOutRow
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
        val altEdit = consumeAltEdit()
        appViewModel.updatePairTitleFanOut(pairId) {
            unique.map { TitleCandidate.Running(it.provider, it.model) }
        }
        val outer = appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            val pair = SecondaryResultStorage.listForReport(context, reportId)
                .firstOrNull { it.id == pairId } ?: return@launch
            val resolved = altEdit?.edited ?: altPrompt.text.replace("@RESPONSE@", pair.content.orEmpty())
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
                                    val (inC, outC) = costSplit(tu, pricing)
                                    if (inT > 0 || outT > 0) {
                                        SecondaryResultStorage.bumpFanOutTitleCost(
                                            context, reportId, pairId,
                                            inputTokens = inT, outputTokens = outT,
                                            inputCost = inC, outputCost = outC,
                                            model = "${item.provider.id}/${item.model}"
                                        )
                                        tu?.let {
                                            appViewModel.settingsPrefs.updateUsageStatsAsync(
                                                item.provider, item.model, it, kind = "title"
                                            )
                                        }
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

    /** Background helper that runs the bundled `translation/icon`
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
            AppLog.w("TranslationIcon", "translation/icon not configured — skipping")
            InternalPromptIconCache.clearInFlight("translation_icon", language)
            return
        }
        val resolved = iconPrompt.text.replace("@LANGUAGE@", language)

        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            withTracerTags(category = "translation/icon") {
              try {
                val outcome = rvm.workerRunner.run(iconPrompt, resolved, aiSettings, context) {
                    extractFirstEmoji(it.analysis) != null
                }
                if (outcome is WorkerOutcome.Success) {
                    val emoji = extractFirstEmoji(outcome.response.analysis) ?: MetadataIconsHolder.current.translationRow
                    val winAgent = aiSettings.resolveWorker(outcome.worker)?.let {
                        it.copy(model = aiSettings.getEffectiveModelForAgent(it))
                    }
                    val tu = outcome.response.tokenUsage
                    val inT = tu?.inputTokens ?: 0
                    val outT = tu?.outputTokens ?: 0
                    val pricing = winAgent?.let { PricingCache.getPricing(context, it.provider, it.model) }
                    val (inC, outC) = costSplit(tu, pricing)
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
                    if ((inT > 0 || outT > 0) && winAgent != null && tu != null) {
                        appViewModel.settingsPrefs.updateUsageStatsAsync(
                            winAgent.provider, winAgent.model, tu, kind = "icon"
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
        val resolved = consumeAltEdit()?.edited ?: altPrompt.text.replace("@LANGUAGE@", language)
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
                            val (inC, outC) = costSplit(tu, pricing)
                            if (inT > 0 || outT > 0) {
                                InternalPromptIconCache.bumpCost(
                                    "translation_icon", language, inT, outT, inC, outC
                                )
                                tu?.let {
                                    appViewModel.settingsPrefs.updateUsageStatsAsync(
                                        item.provider, item.model, it, kind = "icon"
                                    )
                                }
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
                                extractFirstEmoji(response.analysis) ?: MetadataIconsHolder.current.translationRow
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
        val resolved = consumeAltEdit()?.edited ?: altPrompt.text.replace("@PROMPT@", promptText)
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
                                    val (inC, outC) = costSplit(tu, pricing)
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
                                        tu?.let {
                                            appViewModel.settingsPrefs.updateUsageStatsAsync(
                                                item.provider, item.model, it, kind = "icon"
                                            )
                                        }
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
        val resolved = consumeAltEdit()?.edited ?: altPrompt.text.replace("@PROMPT@", promptText)
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
        val altEdit = consumeAltEdit()
        appViewModel.updateAgentTitleFanOut(agentId) { unique.map { TitleCandidate.Running(it.provider, it.model) } }
        val outer = appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            val report = ReportStorage.getReport(context, reportId) ?: return@launch
            val ra = report.agents.firstOrNull { it.agentId == agentId } ?: return@launch
            val resolved = altEdit?.edited ?: altPrompt.text.replace("@RESPONSE@", ra.responseBody.orEmpty())
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
                        val (inC, outC) = costSplit(tu, pricing)
                        val cost = inC + outC
                        if (inT > 0 || outT > 0) {
                            tu?.let {
                                appViewModel.settingsPrefs.updateUsageStatsAsync(item.provider, item.model, it, kind = "title")
                            }
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
        val resolved = consumeAltEdit()?.edited ?: altLanguagePrompt.text.replace("@LANGUAGE@", languageName)
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
                                    // Only a parsed emoji counts as a candidate
                                    // — never raw sliced prose (take(8) could
                                    // cut a multi-codepoint emoji mid-sequence
                                    // and let prose become a "pick"). Same fix
                                    // already applied to the report-icon fan-out.
                                    val emoji = extractFirstEmoji(response.analysis.orEmpty()).orEmpty()
                                    val tu = response.tokenUsage
                                    val pricing = PricingCache.getPricing(context, item.provider, item.model)
                                    val inT = tu?.inputTokens ?: 0
                                    val outT = tu?.outputTokens ?: 0
                                    val (inC, outC) = costSplit(tu, pricing)
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
                                        tu?.let {
                                            appViewModel.settingsPrefs.updateUsageStatsAsync(
                                                item.provider, item.model, it, kind = "icon"
                                            )
                                        }
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
        val altEdit = consumeAltEdit()
        appViewModel.updateAgentIconFanOut(agentId) {
            unique.map { IconCandidate.Running(it.provider, it.model) }
        }
        val outer = appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            val report = ReportStorage.getReport(context, reportId) ?: return@launch
            val ra = report.agents.firstOrNull { it.agentId == agentId } ?: return@launch
            val reportPrompt = report.prompt
            val agentResponse = ra.responseBody.orEmpty()
            val resolved = altEdit?.edited ?: altPrompt.text
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
                                    val (inC, outC) = costSplit(tu, pricing)
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
                                        tu?.let {
                                            appViewModel.settingsPrefs.updateUsageStatsAsync(
                                                item.provider, item.model, it, kind = "icon"
                                            )
                                        }
                                    }
                                    val totalCost = inC + outC
                                    if (response.error == null) {
                                        val emoji = extractFirstEmoji(response.analysis) ?: MetadataIconsHolder.current.reportModelIcon
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
     * [generateIconFromTitle] (model/icons, derived from the
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
        tokenUsage: TokenUsage? = null,
        type: String? = null
    ) {
        val pricing = PricingCache.getPricing(context, provider, model)
        val (inC, outC) = costSplit(tokenUsage ?: TokenUsage(inT, outT), pricing)
        if (inT > 0 || outT > 0) {
            ReportStorage.bumpReportAgentIconCost(
                context, reportId, agentId,
                inputTokens = inT, outputTokens = outT,
                inputCost = inC, outputCost = outC
            )
            appViewModel.settingsPrefs.updateUsageStatsAsync(
                provider, model, tokenUsage ?: TokenUsage(inT, outT), kind = "icon"
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
    // Fan-meta batch — ONE fan/meta call per fan-out pair
    // returns BOTH a title and an icon (a "title:" / "icon:" two-line
    // reply), via the random-pick / 429-fallback worker engine.
    // One Fan Meta call per pair yields both the title and the icon.
    // ============================================================

    fun runFanMetaBatch(
        context: Context,
        reportId: String,
        metaPromptId: String,
        rowIds: Set<String>? = null,
        buildKey: String? = null
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
                    AppLog.w("FanMeta", "fan/meta not configured — skipping")
                    buildKey?.let { appViewModel.finishBuild(it) }
                    return@launch
                }
                val pending = SecondaryResultStorage
                    .listForReport(context, reportId, SecondaryKind.META)
                    .filter {
                        it.metaPromptId == metaPromptId &&
                            it.fanOutSourceAgentId != null &&
                            it.fanInOf == null &&
                            !it.content.isNullOrBlank() &&
                            it.title.isNullOrBlank() && it.icon.isNullOrBlank() &&
                            (rowIds == null || it.id in rowIds)
                    }
                if (pending.isEmpty()) {
                    AppLog.i("FanMeta", "no pending pairs on $reportId — nothing to do")
                    buildKey?.let { appViewModel.finishBuild(it) }
                    return@launch
                }
                // Build stage: marking each pending pair "started" is the
                // "Preparing N / M…" phase the Broken-work Continue popup covers.
                if (buildKey != null) appViewModel.beginBuild(buildKey, pending.size, "Re-queuing fan meta")
                pending.forEachIndexed { idx, pair ->
                    SecondaryResultStorage.markFanOutFanMetaStarted(
                        context, reportId, pair.id, fanRunId, promptUsed = "fan-meta"
                    )
                    rvm.fanOutEngine.refreshPairFromDisk(context, reportId, pair.id)
                    if (buildKey != null) appViewModel.updateBuild(buildKey, idx + 1)
                }
                if (buildKey != null) appViewModel.finishBuild(buildKey)
                AppLog.i("FanMeta", "→ start (report=$reportId, ${pending.size} pairs)")
                withTracerTags(reportId = reportId, category = "fan/meta", runId = fanRunId) {
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
                            runFanMetaForPair(context, reportId, pair, fanMetaPrompt, aiSettings, fanRunId)
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
                        SecondaryResultStorage.setFanOutTitleError(
                            context, reportId, it.id, "Interrupted — run stopped before this finished",
                            titleRunId = fanRunId,
                            promptUsed = "fan-meta"
                        )
                        SecondaryResultStorage.setFanOutIconError(
                            context, reportId, it.id, "Interrupted — run stopped before this finished",
                            iconRunId = fanRunId,
                            promptUsed = "fan-meta"
                        )
                    }
                    // Mirror the interrupted-error rows into memory so the L1
                    // counters settle live now that the 3s re-hydrate is gone.
                    leftover.forEach { rvm.fanOutEngine.refreshPairFromDisk(context, reportId, it.id) }
                }
            }
        }
        rvm.registerFanMetaJob(reportId, metaPromptId, job)
        return job
    }

    /** One fan/meta call for [pair]: parses the title: / icon:
     *  reply and stores BOTH. Worker engine handles random pick + 429. */
    private suspend fun runFanMetaForPair(
        context: Context, reportId: String, pair: SecondaryResult,
        fanMetaPrompt: InternalPrompt, aiSettings: Settings, fanRunId: String
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
                val emoji = extractFirstEmoji(iconLine ?: analysis.orEmpty()) ?: MetadataIconsHolder.current.fanOutRow
                val winAgent = aiSettings.resolveWorker(outcome.worker)?.let {
                    it.copy(model = aiSettings.getEffectiveModelForAgent(it))
                }
                val titleModel = winAgent?.let { "${it.provider.id}/${it.model}" }
                val tu = outcome.response.tokenUsage
                val inT = tu?.inputTokens ?: 0
                val outT = tu?.outputTokens ?: 0
                if ((inT > 0 || outT > 0) && winAgent != null && tu != null) {
                    val pricing = PricingCache.getPricing(context, winAgent.provider, winAgent.model)
                    val (inC, outC) = costSplit(tu, pricing)
                    SecondaryResultStorage.bumpFanOutTitleCost(
                        context, reportId, pair.id,
                        inputTokens = inT, outputTokens = outT,
                        inputCost = inC, outputCost = outC,
                        model = titleModel
                    )
                    appViewModel.settingsPrefs.updateUsageStatsAsync(winAgent.provider, winAgent.model, tu, kind = "title")
                }
                if (title.isNotBlank()) {
                    SecondaryResultStorage.setFanOutTitle(
                        context, reportId, pair.id, title,
                        titleRunId = fanRunId, promptUsed = "fan-meta",
                        durationMs = System.currentTimeMillis() - started,
                        model = titleModel
                    )
                } else {
                    SecondaryResultStorage.setFanOutTitleError(
                        context, reportId, pair.id, "no title in reply",
                        titleRunId = fanRunId,
                        promptUsed = "fan-meta",
                        model = titleModel
                    )
                }
                SecondaryResultStorage.setFanOutIconAndTier(
                    context, reportId, pair.id, emoji, winningTier = null,
                    iconRunId = fanRunId, promptUsed = "fan-meta"
                )
                appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
            }
            else -> {
                val msg = if (outcome is WorkerOutcome.AllRateLimited) "fan-meta: all workers rate-limited"
                          else "fan-meta: no worker produced a result"
                SecondaryResultStorage.setFanOutTitleError(
                    context, reportId, pair.id, msg,
                    titleRunId = fanRunId,
                    promptUsed = "fan-meta"
                )
                SecondaryResultStorage.setFanOutIconError(
                    context, reportId, pair.id, msg,
                    iconRunId = fanRunId,
                    promptUsed = "fan-meta"
                )
                appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
            }
        }
        // Mirror the just-persisted title / icon / cost into the
        // FanOutEngine PairState so the Fan Meta L1 Done / Error / Costs
        // counters advance live — the batch writes straight to disk and
        // has no in-memory transition of its own. Reached with no
        // suspension point after the call returns, so a cancel can't
        // sneak in between the disk write and this mirror.
        rvm.fanOutEngine.refreshPairFromDisk(context, reportId, pair.id)
    }

    /** Re-fire fan-meta after clearing every pair's title+icon. */
    fun relaunchFanMetaBatch(context: Context, reportId: String, metaPromptId: String): Job =
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            // Stop any in-flight fan-meta batch BEFORE clearing. join() (not
            // just cancel()) is load-bearing: clearFanMetaTitleIconState blanks
            // EVERY pair's title+icon, and runFanMetaBatch below dedups on a
            // live job — so without the join, a running batch's already-completed
            // pairs get blanked here but never reprocessed (their runId is nulled
            // too, so the resume scan misses them): silent lost work. Mirrors
            // FanOutEngine.clearFanMeta.
            cancelFanMetaBatch(reportId, metaPromptId)?.join()
            withContext(Dispatchers.IO) {
                val rows = SecondaryResultStorage
                    .listForReport(context, reportId, SecondaryKind.META)
                    .filter { it.metaPromptId == metaPromptId && it.fanOutSourceAgentId != null && it.fanInOf == null }
                clearFanMetaTitleIconState(context, reportId, rows)
            }
            appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
            // Run the batch only after the clear is applied (avoids the same
            // clear-vs-scan race as restartFanMetaErrors).
            runFanMetaBatch(context, reportId, metaPromptId)
        }

    fun cancelFanMetaBatch(reportId: String, metaPromptId: String): Job? =
        rvm.fanMetaJobs[rvm.fanMetaJobKey(reportId, metaPromptId)]?.also { it.cancel() }

    private fun isFanMetaError(sr: SecondaryResult): Boolean =
        !sr.titleErrorMessage.isNullOrBlank() || !sr.iconErrorMessage.isNullOrBlank()

    private fun erroredFanMetaPairs(context: Context, reportId: String, metaPromptId: String) =
        SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
            .filter { it.metaPromptId == metaPromptId && it.fanOutSourceAgentId != null && it.fanInOf == null && isFanMetaError(it) }

    private fun clearFanMetaTitleIconState(
        context: Context,
        reportId: String,
        rows: Collection<SecondaryResult>,
    ): Set<String> {
        val affectedIds = rows.map { it.id }.toSet()
        if (affectedIds.isEmpty()) return emptySet()
        // Preserve the title+icon spend in Deleted-items before
        // clearFanOut*State zeroes it; restarts/relaunches record fresh cost
        // on top, so the report total stays whole across repeated attempts.
        val costDelta = rows.sumOf {
            it.titleInputCost + it.titleOutputCost + it.iconInputCost + it.iconOutputCost
        }
        rows.forEach { row ->
            SecondaryResultStorage.clearFanOutTitleState(context, reportId, row.id)
            SecondaryResultStorage.clearFanOutIconState(context, reportId, row.id)
            rvm.fanOutEngine.refreshPairFromDisk(context, reportId, row.id)
        }
        if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
        return affectedIds
    }

    fun clearFanMetaErrors(context: Context, reportId: String, metaPromptId: String): Job =
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            withContext(Dispatchers.IO) {
                val errored = erroredFanMetaPairs(context, reportId, metaPromptId)
                clearFanMetaTitleIconState(context, reportId, errored)
            }
            appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
        }

    fun clearFanMetaRows(context: Context, reportId: String, rowIds: Set<String>): Job =
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            withContext(Dispatchers.IO) {
                val rows = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
                    .filter { it.id in rowIds && it.fanOutSourceAgentId != null && it.fanInOf == null }
                clearFanMetaTitleIconState(context, reportId, rows)
            }
            appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
        }

    fun restartFanMetaErrors(context: Context, reportId: String, metaPromptId: String): Job =
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            withContext(Dispatchers.IO) {
                val errored = erroredFanMetaPairs(context, reportId, metaPromptId)
                clearFanMetaTitleIconState(context, reportId, errored)
            }
            appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
            // Run the batch only after the clear is fully applied. The batch's
            // pending scan keys on blank title+icon; a partial-success errored
            // pair (title OR icon already set) only becomes eligible once the
            // clear has blanked it. Previously the clear ran in a separate
            // coroutine and the scan could win the race — seeing such a pair as
            // "not pending" and clearing its errors without restarting it.
            runFanMetaBatch(context, reportId, metaPromptId)
        }

    /** Broken-work "Continue" for a fan-meta batch: stop any in-flight batch
     *  (join — load-bearing, see [relaunchFanMetaBatch]), clear the errored
     *  pairs' title+icon, then re-run. The batch's pending scan then picks up
     *  both the just-cleared errored pairs AND any never-ran (blank title+icon)
     *  ones while leaving finished pairs alone — so this re-queues every broken
     *  item, keeping finished. [buildKey] drives the build-stage popup. */
    fun continueBrokenFanMeta(context: Context, reportId: String, metaPromptId: String, buildKey: String?): Job =
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            try {
                cancelFanMetaBatch(reportId, metaPromptId)?.join()
                withContext(Dispatchers.IO) {
                    val errored = erroredFanMetaPairs(context, reportId, metaPromptId)
                    clearFanMetaTitleIconState(context, reportId, errored)
                }
                appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
                runFanMetaBatch(context, reportId, metaPromptId, buildKey = buildKey)?.join()
            } catch (e: kotlinx.coroutines.CancellationException) {
                buildKey?.let { appViewModel.clearBuild(it) }
                throw e
            } catch (e: Exception) {
                AppLog.w("FanMeta", "continue broken batch failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                // Release the popup so the overlay still opens when there was
                // nothing to re-queue (runFanMetaBatch finishes it before
                // dispatch on the normal path).
                buildKey?.let {
                    if (appViewModel.batchBuildProgress.value[it]?.done != true) appViewModel.finishBuild(it)
                }
            }
        }

    fun restartFanMetaRows(context: Context, reportId: String, metaPromptId: String, rowIds: Set<String>): Job =
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            withContext(Dispatchers.IO) {
                val rows = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
                    .filter { it.id in rowIds && it.metaPromptId == metaPromptId && it.fanOutSourceAgentId != null && it.fanInOf == null }
                clearFanMetaTitleIconState(context, reportId, rows)
            }
            appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
            runFanMetaBatch(context, reportId, metaPromptId, rowIds = rowIds)
        }
}
