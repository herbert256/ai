package com.ai.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.data.*
import com.ai.model.*
import com.ai.ui.helpers.translationRunGroupingId
import com.ai.ui.shared.shortModelName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Persisted `translateSourceKind` string for a [TranslationKind]. */
private fun translateSrcKindOf(kind: TranslationKind): String = when (kind) {
    TranslationKind.TITLE -> "TITLE"
    TranslationKind.TITLE_LONG -> "TITLE_LONG"
    TranslationKind.AGENT_TITLE -> "AGENT_TITLE"
    TranslationKind.FANOUT_TITLE -> "FANOUT_TITLE"
    TranslationKind.PROMPT -> "PROMPT"
    TranslationKind.AGENT_RESPONSE -> "AGENT"
    TranslationKind.META -> "META"
}

/** Persisted `translateSourceTargetId` for an item. Singletons
 *  (report title / long title / prompt) use a fixed key; per-target
 *  kinds carry the agent id / secondary id in [TranslationItem.target]. */
private fun translateSrcTargetIdOf(item: TranslationItem): String = when (item.kind) {
    TranslationKind.TITLE -> "title"
    TranslationKind.TITLE_LONG -> "titleLong"
    TranslationKind.PROMPT -> "prompt"
    else -> item.target ?: ""
}

/** Per-kind trace/cost/usage Type for [item]. For META items the source
 *  secondary (by [TranslationItem.target]) decides fan-out / fan-in / plain
 *  meta; other kinds map purely from the kind. */
private fun traceTypeFor(item: TranslationItem, secondaries: List<SecondaryResult>): String {
    val src = if (item.kind == TranslationKind.META) secondaries.firstOrNull { it.id == item.target } else null
    return com.ai.data.translateTraceType(
        translateSrcKindOf(item.kind),
        sourceIsFanOut = src?.fanOutSourceAgentId != null,
        sourceIsFanIn = src?.fanInOf != null
    )
}

/** Inverse of [translateSrcKindOf] — maps a persisted
 *  `translateSourceKind` string back to a [TranslationKind].
 *  Null for unrecognised / non-translate rows. */
private fun translateKindOf(srcKind: String?): TranslationKind? = when (srcKind) {
    "TITLE" -> TranslationKind.TITLE
    "TITLE_LONG" -> TranslationKind.TITLE_LONG
    "AGENT_TITLE" -> TranslationKind.AGENT_TITLE
    "FANOUT_TITLE" -> TranslationKind.FANOUT_TITLE
    "PROMPT" -> TranslationKind.PROMPT
    "AGENT" -> TranslationKind.AGENT_RESPONSE
    "META" -> TranslationKind.META
    else -> null
}

/** Fallback `translate-title` body used when the bundled internal
 *  prompt hasn't been delta-merged into settings yet. Mirrors
 *  assets/internal-prompts/internal/translate-title.txt. */
private const val DEFAULT_TRANSLATE_TITLE_TEMPLATE =
    "Translate the following text to @LANGUAGE@, give only the translation back, nothing else.\n\n@TITLE@"

/** Translation-run orchestration extracted from [ReportViewModel].
 *  Owns the live translation-run state + jobs and the full run
 *  lifecycle. Back-references [rvm] for a few shared helpers
 *  (e.g. reportLogContext) and [appViewModel] for settings /
 *  storage / coroutine scope. Mirrors the FanOutEngine collaborator. */
class TranslationRunManager(
    private val appViewModel: AppViewModel,
    private val rvm: ReportViewModel
) : BatchEngine<String, String, TranslationItem, TranslationRunState>() {
    // ===== Translate =====

    override fun copyWithItems(
        run: TranslationRunState,
        items: Map<String, TranslationItem>
    ): TranslationRunState = run.copy(items = items)

    // Multiple concurrent translation runs: each Translate click allocates a
    // fresh runId and runs in parallel with any others already in flight. The
    // runId-keyed run map (`runs`/`_runs`), the per-run / per-item Job
    // registries, and the deleting-run set all live in the BatchEngine base.

    /** Alias of the base [runs] flow under the historical name so the UI /
     *  view-model consumers keep using `translation.translationRuns`. */
    val translationRuns: StateFlow<Map<String, TranslationRunState>> get() = runs

    /** True while [runId]'s run job is still alive in this process. */
    fun hasActiveRunJob(runId: String): Boolean = runJobOf(runId)?.isActive == true

    /** Mutate the item whose DISK row id is [rowId] (vs its logical map key) —
     *  the resume terminalize only has the persisted row id in hand. */
    private fun transitionItemByRowId(runId: String, rowId: String, update: (TranslationItem) -> TranslationItem) {
        _runs.update { runs ->
            val cur = runs[runId] ?: return@update runs
            val t = cur.items.values.firstOrNull { it.persistedRowId == rowId } ?: return@update runs
            runs + (runId to copyWithItems(cur, cur.items + (t.key to update(t))))
        }
    }

    /** The "workers"-category translate prompt — body uses
     *  `translate-text` (`@TEXT@`), the four title kinds use
     *  `translate-title` (`@TITLE@`). Both carry their own worker
     *  swarm now, so a translation run dispatches through the
     *  [WorkerRunner] fallback chain exactly like tournament /
     *  fan-meta — no user-picked model. Returns null when the prompt
     *  isn't present (or was moved out of the workers category). */
    private fun workerTranslatePrompt(aiSettings: Settings, title: Boolean): InternalPrompt? {
        val name = if (title) "translate-title" else "translate-text"
        return aiSettings.internalPrompts.firstOrNull {
            it.category == "workers" && it.name.equals(name, ignoreCase = true)
        }
    }

    /** Snapshot the report's translatable items, kick off the runner.
     *  Concurrency capped at 3 — translations are often the slowest
     *  operation in the app and respecting provider rate limits is
     *  more important than maximum throughput. Each translated piece
     *  is persisted as a TRANSLATE [SecondaryResult] on the SOURCE
     *  report, tagged with the language. The viewer / HTML exports
     *  group those rows by language to render Original | Dutch | …
     *  views. Structured-JSON meta results (rerank, moderation) are
     *  skipped. */
    fun startTranslation(
        context: Context,
        sourceReportId: String,
        targetLanguageName: String,
        targetLanguageNative: String,
        /** Build-stage key (UUID the UI minted) for the blocking
         *  "Preparing…" popup. Non-null only from the manual Translate
         *  launch; resume / cross-translate paths pass null and skip the
         *  popup. The build phase = persisting the placeholder rows below. */
        buildKey: String? = null,
        /** When non-null (the driving "translate-text" prompt is *SELECT), run
         *  the whole translation against these user-picked workers instead of
         *  the configured translate-text / translate-title chains. */
        overrideWorkers: List<com.ai.model.Worker>? = null
    ): Pair<String, Job> {
        val runId = java.util.UUID.randomUUID().toString()
        val job = appViewModel.viewModelScope.launch(rvm.reportLogContext(sourceReportId)) {
            val state = appViewModel.uiState.value
            val aiSettings = state.aiSettings
            val sourceReport = ReportStorage.getReport(context, sourceReportId) ?: run {
                _runs.update { it - runId }
                if (buildKey != null) appViewModel.clearBuild(buildKey)  // dismiss the build popup (no run to open)
                return@launch
            }
            val secondaries = SecondaryResultStorage.listForReport(context, sourceReportId)

            // Build the work list. Order: title → prompt → agent
            // responses (in success order) → summaries → compares.
            // Reranks and moderation results are skipped (structured
            // JSON, no human-language content to translate). The title
            // is only included when non-blank — a blank title has
            // nothing meaningful to translate.
            val items = mutableListOf<TranslationItem>()
            if (sourceReport.title.isNotBlank()) {
                items += TranslationItem(
                    id = "title",
                    label = "Report title",
                    kind = TranslationKind.TITLE,
                    sourceText = sourceReport.title
                )
            }
            sourceReport.titleLong?.takeIf { it.isNotBlank() }?.let { longTitle ->
                items += TranslationItem(
                    id = "titleLong",
                    label = "Report long title",
                    kind = TranslationKind.TITLE_LONG,
                    sourceText = longTitle
                )
            }
            items += TranslationItem(
                id = "prompt",
                label = "Report prompt",
                kind = TranslationKind.PROMPT,
                sourceText = sourceReport.prompt
            )
            sourceReport.agents
                .forEach { agent ->
                    val body = agent.responseBody?.takeIf(String::isNotBlank) ?: return@forEach
                    if (agent.reportStatus != ReportStatus.SUCCESS) return@forEach
                    val provDisplay = AppService.findById(agent.provider)?.id ?: agent.provider
                    items += TranslationItem(
                        id = "agent:${agent.agentId}",
                        label = "$provDisplay / ${shortModelName(agent.model)}",
                        kind = TranslationKind.AGENT_RESPONSE,
                        sourceText = body,
                        target = agent.agentId
                    )
                }
            // Per-model response titles (ReportAgent.modelTitle), one
            // per success agent that has a generated title.
            sourceReport.agents
                .forEach { agent ->
                    val title = agent.modelTitle?.takeIf(String::isNotBlank) ?: return@forEach
                    if (agent.reportStatus != ReportStatus.SUCCESS) return@forEach
                    val provDisplay = AppService.findById(agent.provider)?.id ?: agent.provider
                    items += TranslationItem(
                        id = "agentTitle:${agent.agentId}",
                        label = "Title: $provDisplay / ${shortModelName(agent.model)}",
                        kind = TranslationKind.AGENT_TITLE,
                        sourceText = title,
                        target = agent.agentId
                    )
                }
            // Per-fan-out-pair response titles (SecondaryResult.title on
            // fan-out pair rows), one per pair that has a generated title.
            secondaries
                .forEach { s ->
                    val title = s.title?.takeIf(String::isNotBlank) ?: return@forEach
                    if (s.kind != SecondaryKind.META || s.fanOutSourceAgentId == null) return@forEach
                    val provDisplay = AppService.findById(s.providerId)?.id ?: s.providerId
                    items += TranslationItem(
                        id = "fanoutTitle:${s.id}",
                        label = "Fan title: $provDisplay / ${shortModelName(s.model)}",
                        kind = TranslationKind.FANOUT_TITLE,
                        sourceText = title,
                        target = s.id
                    )
                }
            // Every chat-type Meta result is a candidate for translation.
            // Label the row by the user-given Meta prompt name so the
            // progress screen / per-call detail show "Compare 1: …" or
            // "Critique 2: …" — driven entirely by the CRUD prompt name,
            // not a hardcoded "Summary" / "Compare".
            secondaries.filter { it.kind == SecondaryKind.META && !it.content.isNullOrBlank() }
                .forEachIndexed { idx, s ->
                    val content = s.content?.takeIf(String::isNotBlank) ?: return@forEachIndexed
                    val provDisplay = AppService.findById(s.providerId)?.id ?: s.providerId
                    val name = s.metaPromptName?.takeIf { it.isNotBlank() }
                        ?: com.ai.data.legacyKindDisplayName(s.kind)
                    items += TranslationItem(
                        id = "meta:${s.id}",
                        label = "$name ${idx + 1}: $provDisplay / ${shortModelName(s.model)}",
                        kind = TranslationKind.META,
                        sourceText = content,
                        target = s.id
                    )
                }

            // Stamp each item with the SecondaryResultStorage row id it
            // will eventually write to. Persisting an empty placeholder
            // for that id up front records the run's original target
            // list on disk — startMissingTranslations later compares
            // against THIS set rather than recomputing from current
            // report state, so items added to the report AFTER this
            // run completes don't get spuriously translated.
            var itemsWithIds = items.map {
                it.copy(
                    persistedRowId = java.util.UUID.randomUUID().toString(),
                    traceType = traceTypeFor(it, secondaries)
                )
            }
            // Build stage: persist one placeholder per item up front. Use a
            // batched save so large translation runs bump storage observers
            // once; runBatchBuild brackets the "Preparing N / M…" popup (begin
            // + finish-on-exit) around the disk-heavy save.
            val placeholderRows = itemsWithIds.map { item ->
                val rowId = item.persistedRowId ?: java.util.UUID.randomUUID().toString()
                val srcKind = translateSrcKindOf(item.kind)
                val srcTargetId = translateSrcTargetIdOf(item)
                SecondaryResult(
                    id = rowId,
                    reportId = sourceReportId,
                    kind = SecondaryKind.TRANSLATE,
                    providerId = "",
                    model = "",
                    agentName = "Translate: ${item.label.ifBlank { item.kind.name.lowercase() }}",
                    timestamp = System.currentTimeMillis(),
                    content = null,
                    errorMessage = null,
                    translateSourceKind = srcKind,
                    translateSourceTargetId = srcTargetId,
                    targetLanguage = targetLanguageName,
                    targetLanguageNative = targetLanguageNative,
                    translationRunId = runId,
                    runId = runId,
                )
            }
            val savedIds = appViewModel.runBatchBuild(buildKey, itemsWithIds.size, "Translating to $targetLanguageName") {
                SecondaryResultStorage.saveAll(context, placeholderRows).mapTo(HashSet()) { it.id }
            }
            itemsWithIds = itemsWithIds.filter { it.persistedRowId in savedIds }
            if (itemsWithIds.isEmpty()) return@launch
            _runs.update { it + (runId to TranslationRunState(
                runId = runId,
                sourceReportId = sourceReportId,
                targetLanguageName = targetLanguageName,
                targetLanguageNative = targetLanguageNative,
                items = itemsWithIds.associateBy { i -> i.id }
            )) }
            AuditLog.append(sourceReportId, "Start Translation to $targetLanguageName ($targetLanguageNative) — ${itemsWithIds.size} item(s) via worker swarm")

            // Translation now runs through the WorkerRunner swarm — the
            // same Mode-B fallback chain tournament / fan-meta use. No
            // user-picked model: each item is handed to the
            // workers/translate-text (body) or workers/translate-title
            // (title) prompt, which shuffles its swarm and falls back
            // on a 429 / miss. An item only ERRORs when the whole chain
            // is exhausted.
            // ♻️ When the source report's flag is on, the whole translation runs
            // against its own answer models (winning over a *SELECT pick); the
            // swarm spreads across every report-model.
            val reportSwarm = if (sourceReport.useReportModelsAsWorkers) reportModelWorkers(sourceReport) else null
            val textPrompt = workerTranslatePrompt(aiSettings, title = false)
                ?.let { p -> reportSwarm?.let { p.copy(workers = it) } ?: overrideWorkers?.let { p.copy(workers = it) } ?: p }
            val titlePrompt = workerTranslatePrompt(aiSettings, title = true)
                ?.let { p -> reportSwarm?.let { p.copy(workers = it) } ?: overrideWorkers?.let { p.copy(workers = it) } ?: p }
            if (textPrompt == null && titlePrompt == null) {
                AppLog.w("Translation", "no workers/translate-text|title prompt — marking all items error")
                itemsWithIds.forEach {
                    finalizeTranslationError(context, runId, it, "translate worker prompt missing — add it under AI Setup → Prompt management → Worker prompts")
                }
                _runs.update { runs ->
                    val cur = runs[runId] ?: return@update runs
                    runs + (runId to cur.copy(finished = true))
                }
                return@launch
            }

            // Per-item dispatch under the translation flow cap in
            // dynamic-host mode — each worker call self-throttles its
            // own provider host (the chain spans providers and changes
            // on 429-fallback). A per-item 180s ceiling bounds a whole
            // chain so a wedged call can't strand the run; a timeout
            // finalizes that item as ERROR.
            withTracerTags(reportId = sourceReportId, runId = runId) {
                runThrottledBatch(
                    items = itemsWithIds,
                    hostOf = { null },
                    subCap = ApiCallCaps.translation,
                    dynamicHost = true,
                    // Hook each item's coroutine into the base per-item Job
                    // registry (keyed by the disk row id) so cancel / delete /
                    // the resume `!hasItemJob` guard see exactly what's live.
                    register = { item, d -> item.persistedRowId?.let { registerItemJob(it, d) } },
                ) { item ->
                    // Skip if the report / placeholder row was deleted mid-run.
                    if (!SecondaryResultStorage.exists(context, sourceReportId, item.persistedRowId ?: "")) {
                        return@runThrottledBatch
                    }
                    val outcome = kotlinx.coroutines.withTimeoutOrNull(180_000) {
                        runOneTranslation(runId, context, item, targetLanguageName, textPrompt, titlePrompt)
                    } ?: TranslationOutcome.Failed("translation timed out after 180s")
                    if (outcome is TranslationOutcome.Failed) {
                        finalizeTranslationError(context, runId, item, outcome.message)
                    }
                }

                // Per-item rows were already persisted inside
                // runOneTranslation as each call settled, so the batch
                // survives a redeploy / OS kill mid-run. Just bump the
                // parent report's timestamp once at the end so History
                // resorts. Skipped on cancel.
                val finalState = _runs.value[runId] ?: return@withTracerTags
                if (finalState.cancelled) {
                    AppLog.i("Translation", "← cancelled $targetLanguageName for report=$sourceReportId")
                    return@withTracerTags
                }
                ReportStorage.bumpReportTimestamp(context, sourceReportId)
                _runs.update { runs ->
                    val cur = runs[runId] ?: return@update runs
                    runs + (runId to cur.copy(finished = true))
                }
                val okCount = finalState.items.values.count { it.translatedText?.isNotBlank() == true }
                val failCount = finalState.items.values.count { it.errorMessage != null }
                AuditLog.append(sourceReportId, "End Translation to $targetLanguageName — ok=$okCount fail=$failCount")
            }
        }
        registerRunJob(runId, job)
        return runId to job
    }

    /** Result of a single translation call. A [Failed] is non-terminal
     *  at the [runOneTranslation] level — the caller decides whether to
     *  retry the item on another model or finalize it as ERROR. */
    private sealed interface TranslationOutcome {
        /** [costDollars] is the call's billed cost — the work queue
         *  feeds it into a per-model running average to bias future
         *  items toward cheaper models. */
        data class Success(val costDollars: Double) : TranslationOutcome
        data class Failed(val message: String) : TranslationOutcome
    }

    /** Mark a translation item ERROR and persist its TRANSLATE row.
     *  Terminal failure path — used when the worker chain is exhausted
     *  (every translate worker rate-limited or missed) or the per-item
     *  budget runs out. There's no single model to attribute the
     *  failure to now, so the row carries a blank provider/model. */
    private fun finalizeTranslationError(
        context: Context,
        runId: String,
        item: TranslationItem,
        message: String
    ) {
        transitionItem(runId, item.id) {
            it.copy(status = TranslationStatus.ERROR, errorMessage = message, providerId = null, model = null)
        }
        val freshRun = _runs.value[runId]
        val freshItem = freshRun?.items?.get(item.id)
        if (freshRun != null && freshItem != null) {
            saveOneTranslationItem(context, runId, freshRun, freshItem, null, "", null)
        }
    }

    /** Translate one item through the translate worker swarm. The body
     *  kind picks [textPrompt] (workers/translate-text, `@TEXT@`); the
     *  four title kinds pick [titlePrompt] (workers/translate-title,
     *  `@TITLE@`). The [WorkerRunner] owns model selection + 429 / miss
     *  fallback across the swarm, so there's no user-picked model and no
     *  cross-model retry here — the item only fails when the whole chain
     *  is exhausted. On success the row is attributed to the worker that
     *  actually answered. */
    private suspend fun runOneTranslation(
        runId: String,
        context: Context,
        item: TranslationItem,
        targetLanguageName: String,
        textPrompt: InternalPrompt?,
        titlePrompt: InternalPrompt?
    ): TranslationOutcome {
        val aiSettings = appViewModel.uiState.value.aiSettings
        val prompt = (if (item.kind.isTitle) titlePrompt else textPrompt)
            ?: return TranslationOutcome.Failed("translate worker prompt missing for ${item.kind}")
        if (prompt.workers.isEmpty())
            return TranslationOutcome.Failed("translate prompt '${prompt.name}' has no workers configured")

        // RUNNING; provider/model stay null until a worker wins it.
        transitionItem(runId, item.id) {
            it.copy(status = TranslationStatus.RUNNING, providerId = null, model = null)
        }
        // Demote RUNNING → PENDING on cancellation so the item isn't
        // stranded in the in-memory state; a resume/reload re-picks it.
        try {
            AppLog.d("Translation", "→ item ${item.id} \"${item.label}\" kind=${item.kind} srcLen=${item.sourceText.length}")
            val resolved = if (item.kind.isTitle)
                prompt.text.replace("@LANGUAGE@", targetLanguageName).replace("@TITLE@", item.sourceText)
            else
                prompt.text.replace("@LANGUAGE@", targetLanguageName).replace("@TEXT@", item.sourceText)

            val callStart = System.currentTimeMillis()
            // Surface the per-provider throttle wait to the L1 "Throttled"
            // counter — a worker call is dynamic-host, so its wait happens
            // inside ProviderThrottle.acquire, not at the batch layer.
            val observer: (Boolean) -> Unit = { waiting ->
                if (waiting) appViewModel.updateThrottledTranslationItems { it + item.id }
                else appViewModel.updateThrottledTranslationItems { it - item.id }
            }
            // Capture the winning call's trace filename for the per-item 🐞.
            val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val outcome = withContext(ProviderThrottle.throttleWaitObserver.asContextElement(observer)) {
                withTraceCategory(item.traceType) {
                    withTraceFilenameSink(traceSink) {
                        rvm.workerRunner.run(prompt, resolved, aiSettings, context) { resp ->
                            !resp.analysis.isNullOrBlank()
                        }
                    }
                }
            }
            val callDurationMs = System.currentTimeMillis() - callStart

            if (outcome !is WorkerOutcome.Success) {
                val msg = if (outcome is WorkerOutcome.AllRateLimited)
                    "translate: all workers rate-limited"
                else "translate: no worker produced a translation"
                AppLog.d("Translation", "← item ${item.id} err ${callDurationMs}ms — $msg")
                return TranslationOutcome.Failed(msg)
            }

            // Attribute the row to the worker that actually answered.
            val winAgent = aiSettings.resolveWorker(outcome.worker)?.let {
                it.copy(model = aiSettings.getEffectiveModelForAgent(it))
            }
            val provider = winAgent?.provider ?: outcome.response.service
            val model = winAgent?.model.orEmpty()
            val tu = outcome.response.tokenUsage
            val pricing = if (model.isNotBlank()) PricingCache.getPricing(context, provider, model) else null
            val costDollars = if (tu != null && pricing != null) PricingCache.computeCost(tu, pricing) else 0.0

            transitionItem(runId, item.id) {
                it.copy(
                    status = TranslationStatus.DONE,
                    translatedText = outcome.response.analysis,
                    costDollars = costDollars,
                    tokenUsage = tu,
                    durationMs = callDurationMs,
                    providerId = provider.id,
                    model = model,
                    traceFile = traceSink.get()
                )
            }
            // Persist as soon as the call settles so a process kill mid-batch
            // keeps the rows that did complete.
            val freshRun = _runs.value[runId]
            val freshItem = freshRun?.items?.get(item.id)
            if (freshRun != null && freshItem != null) {
                saveOneTranslationItem(context, runId, freshRun, freshItem, provider, model, pricing)
            }
            // Roll the winning worker's spend into AI Usage under this
            // item's per-kind translate/* type.
            if (tu != null && model.isNotBlank()) {
                appViewModel.settingsPrefs.updateUsageStatsAsync(provider, model, tu, kind = item.traceType, durationMs = callDurationMs)
            }
            AppLog.d(
                "Translation",
                "← item ${item.id} ok ${callDurationMs}ms via ${provider.id}/$model" +
                    (tu?.let { " in=${it.inputTokens} out=${it.outputTokens}" } ?: "") +
                    " cost=${"%.5f".format(costDollars)}"
            )
            return TranslationOutcome.Success(costDollars)
        } catch (t: Throwable) {
            transitionItem(runId, item.id) {
                if (it.status == TranslationStatus.RUNNING)
                    it.copy(status = TranslationStatus.PENDING, providerId = null, model = null)
                else it
            }
            throw t
        }
    }

    /** Persist one TRANSLATE [SecondaryResult] for a single completed
     *  (or errored) translation item. Replaces the all-at-once flush
     *  that used to happen at the end of [startTranslation] — moving
     *  the save inline lets a half-finished batch survive process
     *  death with the rows that did complete still on disk. */
    private fun saveOneTranslationItem(
        context: Context,
        runId: String,
        run: TranslationRunState,
        item: TranslationItem,
        translateProvider: AppService?,
        translateModel: String,
        translatePricing: PricingCache.ModelPricing?
    ) {
        val tu = item.tokenUsage
        // Tier-aware split — runOneTranslation already computes the
        // total via PricingCache.computeCost. The persisted in / out
        // halves now go through computeInOutCost so a long-context
        // translation (>200k tokens) doesn't drift from the canonical
        // total recorded in the parent run. An ERROR row carries no
        // provider/pricing (the worker chain was exhausted), so the
        // split is simply null then.
        val (inCost, outCost) = if (tu != null && translatePricing != null)
            PricingCache.computeInOutCost(tu, translatePricing).let { it.first to it.second }
        else (null to null)
        val labelPrefix = "Translate: ${item.label.ifBlank { item.kind.name.lowercase() }}"
        val srcKind = translateSrcKindOf(item.kind)
        val srcTargetId = translateSrcTargetIdOf(item)
        val row = SecondaryResult(
            // Reuse the placeholder's id (stashed at startTranslation /
            // restart time) so this save OVERWRITES the placeholder
            // row instead of creating a parallel record. That keeps
            // exactly one row per (runId, kind, targetId) so the
            // auto-resume can tell "originally targeted" from "newly
            // added to the report later".
            id = item.persistedRowId ?: java.util.UUID.randomUUID().toString(),
            reportId = run.sourceReportId,
            kind = SecondaryKind.TRANSLATE,
            providerId = translateProvider?.id ?: "",
            model = translateModel,
            agentName = labelPrefix,
            timestamp = System.currentTimeMillis(),
            content = item.translatedText,
            errorMessage = item.errorMessage,
            tokenUsage = tu,
            inputCost = inCost,
            outputCost = outCost,
            durationMs = item.durationMs,
            translateSourceKind = srcKind,
            translateSourceTargetId = srcTargetId,
            targetLanguage = run.targetLanguageName,
            targetLanguageNative = run.targetLanguageNative,
            translationRunId = runId,
            runId = runId,
            traceFile = item.traceFile
        )
        if (item.persistedRowId != null) {
            // Placeholder was written up front, so use the present-guarded
            // write: if the report was deleted mid-translation the row is
            // gone and saveIfStillPresent skips it instead of recreating
            // the report's storage dir (a zombie report). The placeholder
            // is zero-cost, so the merge-on-save is a plain overwrite.
            SecondaryResultStorage.saveIfStillPresent(context, row)
        } else {
            SecondaryResultStorage.save(context, row)
        }
    }

    // saveTranslationSecondaries (the bulk all-at-end flush) was
    // replaced by saveOneTranslationItem, called inline as each
    // translation call settles — so a half-finished batch persists
    // the rows it did complete instead of losing everything on a
    // redeploy / OS kill.

    // ===== Find alternative translation =====
    // Mirrors the Find-alt icon / title fan-out: re-translate ONE L3
    // item's source text on each picked model, collect the candidates
    // in [AppViewModel.altTranslationByItem] (keyed by itemId), and let
    // the user tap one to overwrite the persisted row in place. The
    // probe calls are NON-persisting — only the picked candidate lands
    // on disk (via [applyAltTranslation]).

    /** Launch one re-translation per picked model for [itemId]. */
    fun startAltTranslationFanOut(
        context: Context, reportId: String, itemId: String,
        targetLanguageName: String, isTitleKind: Boolean, sourceText: String,
        traceType: String, models: List<ReportModel>, aiSettings: Settings,
        paramsIds: List<String> = emptyList(), systemPromptId: String? = null
    ) {
        val unique = models.distinctBy { "${it.provider.id}:${it.model}" }
        if (unique.isEmpty() || sourceText.isBlank()) return
        val promptName = if (isTitleKind) "translate-title" else "translate-text"
        val prompt = aiSettings.getInternalPromptByName(promptName)
        val rawTemplate = prompt?.text?.takeIf { it.isNotBlank() }
            ?: if (isTitleKind) DEFAULT_TRANSLATE_TITLE_TEMPLATE else ""
        if (rawTemplate.isBlank()) return
        val resolved = rvm.iconGen.consumeAltEdit()?.edited ?: if (isTitleKind)
            rawTemplate.replace("@LANGUAGE@", targetLanguageName).replace("@TITLE@", sourceText)
        else
            rawTemplate.replace("@LANGUAGE@", targetLanguageName).replace("@TEXT@", sourceText)
        appViewModel.updateAltTranslationFanOut(itemId) { unique.map { TranslationCandidate.Running(it.provider, it.model) } }
        val outer = appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            unique.forEach { item ->
                launch { runAltTranslationCandidate(context, reportId, itemId, item, resolved, traceType, aiSettings, paramsIds, systemPromptId, prompt) }
            }
        }
        rvm.registerIconFanOutJob("alttr:$itemId", outer)
    }

    /** One alternative-translation candidate call (non-persisting). */
    private suspend fun runAltTranslationCandidate(
        context: Context, reportId: String, itemId: String,
        item: ReportModel, resolved: String, traceType: String, aiSettings: Settings,
        paramsIds: List<String>, systemPromptId: String?, prompt: InternalPrompt?
    ) {
        fun place(c: TranslationCandidate) = appViewModel.updateAltTranslationFanOut(itemId) { list ->
            list.map { if (it.provider.id == item.provider.id && it.model == item.model) c else it }
        }
        val releaser = ProviderThrottle.acquire(providerHost(item.provider))
        try {
            withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                withTracerTags(reportId = reportId, category = traceType) {
                    runCatching {
                        val syntheticAgent = Agent(
                            id = "translate-alt-${item.provider.id}-${item.model}",
                            name = item.model, provider = item.provider, model = item.model,
                            apiKey = aiSettings.getApiKey(item.provider)
                        )
                        val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(syntheticAgent)
                        val params = resolveSecondaryParams(
                            appViewModel.uiState.value.generalSettings, aiSettings, paramsIds, systemPromptId, prompt
                        )
                        // Capture this candidate's own trace + duration so a
                        // pick can replace the persisted row's stale trace /
                        // time instead of keeping the prior translation's.
                        val callStart = System.currentTimeMillis()
                        val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
                        val response = withTraceFilenameSink(traceSink) {
                            appViewModel.repository.analyzeWithAgent(
                                syntheticAgent, "", resolved, params, null, context, baseUrl
                            )
                        }
                        val callDurationMs = System.currentTimeMillis() - callStart
                        val tu = response.tokenUsage
                        val pricing = PricingCache.getPricing(context, item.provider, item.model)
                        val cost = if (tu != null) PricingCache.computeCost(tu, pricing) else 0.0
                        if (tu != null && (tu.inputTokens > 0 || tu.outputTokens > 0)) {
                            // Probe-call spend shows on AI Usage; the report
                            // cost table only gains the picked candidate's
                            // cost (written in applyAltTranslation).
                            appViewModel.settingsPrefs.updateUsageStatsAsync(
                                item.provider, item.model, tu, kind = traceType, durationMs = callDurationMs
                            )
                        }
                        val text = response.analysis.orEmpty()
                        if (response.error == null && text.isNotBlank())
                            place(TranslationCandidate.Done(item.provider, item.model, text, cost, tu, traceSink.get(), callDurationMs))
                        else
                            place(TranslationCandidate.Error(item.provider, item.model, response.error ?: "empty response", cost))
                    }.onFailure { e ->
                        place(TranslationCandidate.Error(item.provider, item.model, e.message ?: "translate failed", 0.0))
                    }
                }
            }
        } finally {
            releaser.release()
        }
    }

    fun restartAltTranslationFanOut(itemId: String) {
        rvm.iconFanOutJobs.remove("alttr:$itemId")?.cancel()
        appViewModel.clearAltTranslationFanOut(itemId)
    }

    /** Apply a picked alternative: overwrite the item's persisted
     *  TRANSLATE row (content + model + cost) and update the live run,
     *  then clear the candidate list. */
    fun applyAltTranslation(
        context: Context, reportId: String, runId: String, itemId: String,
        persistedRowId: String?, candidate: TranslationCandidate.Done
    ) {
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val tu = candidate.tokenUsage
            if (persistedRowId != null) {
                val existing = SecondaryResultStorage.get(context, reportId, persistedRowId)
                if (existing != null) {
                    val pricing = PricingCache.getPricing(context, candidate.provider, candidate.model)
                    val (inCost, outCost) = tu?.let { PricingCache.computeInOutCost(it, pricing) }
                        ?: (0.0 to candidate.cost)
                    SecondaryResultStorage.save(context, existing.copy(
                        providerId = candidate.provider.id,
                        model = candidate.model,
                        content = candidate.text,
                        errorMessage = null,
                        tokenUsage = tu,
                        inputCost = inCost,
                        outputCost = outCost,
                        // Overwrite the previous translation's trace + time
                        // with the picked candidate's (null when the alt run
                        // didn't capture one — better blank than misattributed).
                        traceFile = candidate.traceFile,
                        durationMs = candidate.durationMs,
                        timestamp = System.currentTimeMillis()
                    ))
                }
            }
            // Update the live run (if any) so an in-flight L3 reflects it.
            transitionItem(runId, itemId) {
                it.copy(
                    status = TranslationStatus.DONE,
                    translatedText = candidate.text,
                    providerId = candidate.provider.id,
                    model = candidate.model,
                    costDollars = candidate.cost,
                    tokenUsage = tu,
                    errorMessage = null
                )
            }
            appViewModel.clearAltTranslationFanOut(itemId)
            ReportStorage.bumpReportTimestamp(context, reportId)
        }
    }

    fun cancelTranslation(runId: String) {
        cancelRun(runId)   // cancels the run job; its item-async children cascade
        _runs.update { runs ->
            val cur = runs[runId] ?: return@update runs
            runs + (runId to cur.copy(cancelled = true))
        }
    }

    /** Cancel every in-flight translation run targeting [reportId].
     *  Called by [ReportViewModel.deleteReport] so a completing
     *  translation can't write a secondary row — and resurrect the
     *  just-deleted report's storage dir — after the report is gone. */
    fun cancelAllForReport(reportId: String) {
        _runs.value
            .filterValues { it.sourceReportId == reportId && !it.isFinished && !it.cancelled }
            .keys
            .toList()
            .forEach { cancelTranslation(it) }
    }

    /** Reconcile a stalled translation run by dropping the stale
     *  in-memory state and re-seeding it from the persisted disk
     *  rows.
     *
     *  Why it exists: certain failure modes (an `addCrossTranslationItems`
     *  / `startMissingTranslations` coroutine cancelled mid-dispatch)
     *  leave `_runs[runId]` with `finished = false`,
     *  `completed == total` in its items list, but on-disk rows that
     *  never got their `saveOneTranslationItem` update — so the
     *  manage screen's animated hourglass keeps spinning over a row
     *  whose Done count already matches Total. The fix runs the
     *  same disk-rebuild the L1 screen falls back to when liveRun is
     *  null ([buildPersistedTranslationRunState]): it produces a
     *  state whose `items` cover every persisted row (DONE / ERROR /
     *  placeholder-as-PENDING) and whose `finished` flag is true so
     *  the hourglass clears.
     *
     *  Guard: skips when a dispatch job is currently alive for the
     *  same runId. Reconciling on top of an active worker would race
     *  the worker's pending `saveOneTranslationItem` write — and
     *  there's nothing stalled if a worker is still moving items
     *  toward terminal anyway. */
    fun reconcileStalledTranslationRun(
        context: Context,
        sourceReportId: String,
        runId: String
    ): Job = appViewModel.viewModelScope.launch(rvm.reportLogContext(sourceReportId)) {
        if (runJobOf(runId)?.isActive == true) {
            AppLog.d("Translation", "reconcile skipped — runId=$runId has active dispatch job")
            return@launch
        }
        AppLog.i("Translation", "reconciling stalled translation runId=$runId — rebuilding in-memory state from disk")
        val translateRows = withContext(Dispatchers.IO) {
            SecondaryResultStorage
                .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
                .filter { com.ai.ui.helpers.translationRunGroupingId(it) == runId }
        }
        if (translateRows.isEmpty()) {
            // No rows on disk for this runId — the run is gone. Drop
            // the stale in-memory entry so the hourglass stops.
            _runs.update { it - runId }
            return@launch
        }
        val hasPlaceholders = translateRows.any {
            it.content.isNullOrBlank() && it.errorMessage == null && it.durationMs == null
        }
        if (hasPlaceholders) {
            // Drop the stale in-memory entry so startMissingTranslations'
            // null-cur seed path re-builds from disk (includes the
            // 14 errors + 28 placeholder PENDING items in the
            // canonical Portuguese-style scenario). The dispatch
            // then flips finished=true at the end so the hourglass
            // clears once the placeholders settle.
            AppLog.i("Translation", "reconcile runId=$runId — placeholders present, re-dispatching via startMissingTranslations")
            _runs.update { it - runId }
            startMissingTranslations(context, sourceReportId, runId)
        } else {
            // No placeholders on disk — nothing to dispatch. Just
            // rebuild with finished=true so the manage hourglass
            // clears immediately.
            val rebuilt = buildPersistedTranslationRunState(context, sourceReportId, runId)
                ?: return@launch
            _runs.update { it + (runId to rebuilt) }
        }
    }

    fun consumeTranslationRun(runId: String) {
        _runs.update { it - runId }
    }

    /** Delete a whole translation run. Routed through the shared
     *  [deleteRunDeferred]: the cheap part (mark the run deleting, cancel the
     *  run + per-item jobs, drop the in-memory run) runs synchronously so the
     *  UI can leave at once; the per-row disk sweep runs in the background after
     *  the cancelled coroutines are joined (so no late `saveOneTranslationItem`
     *  resurrects a deleted row). The Second-results list hides the row via the
     *  base [deletingRuns] set. */
    fun deleteTranslationRun(context: Context, sourceReportId: String, runId: String): Job {
        val runJob = runJobOf(runId)
        val itemJobs = _runs.value[runId]?.items?.values
            ?.mapNotNull { it.persistedRowId?.let(::itemJobOf) } ?: emptyList()
        return deleteRunDeferred(appViewModel.viewModelScope, runId, runJob, itemJobs) {
            val rows = SecondaryResultStorage
                .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
                .filter { translationRunGroupingId(it) == runId }
            var costDelta = 0.0
            rows.forEach {
                costDelta += (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0)
                SecondaryResultStorage.delete(context, sourceReportId, it.id)
            }
            ReportStorage.removeIconCallsForSecondaryIds(context, sourceReportId, rows.map { it.id }.toSet())
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, sourceReportId, costDelta)
            ReportStorage.bumpReportTimestamp(context, sourceReportId)
        }
    }

    /** Drop one pending/running item from an in-flight translation
     *  run. Removes the item from [_runs] so its row
     *  disappears on the detail screen; the [saveOneTranslationItem]
     *  guard inside [runOneTranslation] will then skip the disk
     *  write for any call that lands after this point (the guard
     *  looks the item up by id and bails if it's gone). The
     *  in-flight call itself is allowed to finish — there's no
     *  per-item Job to cancel — but its result is discarded. */
    fun cancelTranslationItem(runId: String, itemId: String) {
        dropItem(runId, itemId)
    }

    /** Re-run every errored translation row in [runId]: deletes the
     *  failed [SecondaryResult]s on disk, rebuilds [TranslationItem]s
     *  from the current report state, and dispatches them through
     *  [runOneTranslation]. The runId is preserved so the rerun rows
     *  group under the same translation run on the result screen. */
    fun restartFailedTranslations(
        context: Context,
        sourceReportId: String,
        runId: String
    ): Job = appViewModel.viewModelScope.launch(rvm.reportLogContext(sourceReportId)) {
        val rows = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
            .filter { translationRunGroupingId(it) == runId && it.errorMessage != null }
        if (rows.isEmpty()) return@launch
        // Explicit user re-fire — clear any resume-cap attempt counts so
        // these rows get a fresh BatchResume.MAX_ATTEMPTS budget.
        BatchResume.resetAttempts(rows.map { it.id })
        runTranslationSubset(
            context, sourceReportId, runId,
            rows.map { it.translateSourceTargetId.orEmpty() to it.translateSourceKind.orEmpty() },
            deleteRowIds = rows.map { it.id }
        )
    }

    /** Drop every errored translation row from [runId] without
     *  re-firing. Wired to the run detail screen's "Remove failed
     *  items" button so the user can clear failures without burning
     *  more tokens. */
    fun removeFailedTranslations(
        context: Context,
        sourceReportId: String,
        runId: String
    ): Job = appViewModel.viewModelScope.launch(rvm.reportLogContext(sourceReportId)) {
        val failed = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
            .filter {
                translationRunGroupingId(it) == runId && it.errorMessage != null
            }
        if (failed.isEmpty()) return@launch
        val costDelta = failed.sumOf { it.fullCost() }
        failed.forEach { SecondaryResultStorage.delete(context, sourceReportId, it.id) }
        ReportStorage.removeIconCallsForSecondaryIds(context, sourceReportId, failed.map { it.id }.toSet())
        if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, sourceReportId, costDelta)
        // Also drop the items from any live state so the detail
        // screen's row count updates immediately instead of waiting
        // for the next list refresh.
        val failedTargetKeys = failed
            .map { (it.translateSourceKind ?: "") + ":" + (it.translateSourceTargetId ?: "") }
            .toSet()
        _runs.update { runs ->
            val cur = runs[runId] ?: return@update runs
            runs + (runId to copyWithItems(cur, cur.items.filterValues { item ->
                !(item.status == TranslationStatus.ERROR &&
                    "${translateSrcKindOf(item.kind)}:${translateSrcTargetIdOf(item)}" in failedTargetKeys)
            }))
        }
        ReportStorage.bumpReportTimestamp(context, sourceReportId)
    }

    /** Drop every unfinished (stranded, never-ran) translation row from
     *  [runId] without re-firing — the Broken-work "delete unfinished"
     *  action. Mirror of [removeFailedTranslations], narrowed to blank
     *  placeholders. */
    fun removeUnfinishedTranslations(
        context: Context,
        sourceReportId: String,
        runId: String
    ): Job = appViewModel.viewModelScope.launch(rvm.reportLogContext(sourceReportId)) {
        val stranded = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
            .filter {
                translationRunGroupingId(it) == runId &&
                    it.content.isNullOrBlank() && it.errorMessage == null && it.durationMs == null
            }
        if (stranded.isEmpty()) return@launch
        val costDelta = stranded.sumOf { it.fullCost() }
        stranded.forEach { SecondaryResultStorage.delete(context, sourceReportId, it.id) }
        ReportStorage.removeIconCallsForSecondaryIds(context, sourceReportId, stranded.map { it.id }.toSet())
        if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, sourceReportId, costDelta)
        val strandedKeys = stranded
            .map { (it.translateSourceKind ?: "") + ":" + (it.translateSourceTargetId ?: "") }
            .toSet()
        _runs.update { runs ->
            val cur = runs[runId] ?: return@update runs
            runs + (runId to copyWithItems(cur, cur.items.filterValues { item ->
                !(item.status == TranslationStatus.PENDING &&
                    "${translateSrcKindOf(item.kind)}:${translateSrcTargetIdOf(item)}" in strandedKeys)
            }))
        }
        ReportStorage.bumpReportTimestamp(context, sourceReportId)
    }

    fun restartTranslationRowsByIds(
        context: Context,
        sourceReportId: String,
        runId: String,
        rowIds: Set<String>
    ): Job = appViewModel.viewModelScope.launch(rvm.reportLogContext(sourceReportId)) {
        val rows = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
            .filter { translationRunGroupingId(it) == runId && it.id in rowIds }
        if (rows.isEmpty()) return@launch
        BatchResume.resetAttempts(rows.map { it.id })
        runTranslationSubset(
            context, sourceReportId, runId,
            rows.map { it.translateSourceTargetId.orEmpty() to it.translateSourceKind.orEmpty() },
            deleteRowIds = rows.filter { it.errorMessage != null }.map { it.id }
        )
    }

    fun removeTranslationRowsByIds(
        context: Context,
        sourceReportId: String,
        runId: String,
        rowIds: Set<String>
    ): Job = appViewModel.viewModelScope.launch(rvm.reportLogContext(sourceReportId)) {
        val rows = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
            .filter { translationRunGroupingId(it) == runId && it.id in rowIds }
        if (rows.isEmpty()) return@launch
        val costDelta = rows.sumOf { it.fullCost() }
        rows.forEach { SecondaryResultStorage.delete(context, sourceReportId, it.id) }
        ReportStorage.removeIconCallsForSecondaryIds(context, sourceReportId, rows.map { it.id }.toSet())
        if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, sourceReportId, costDelta)
        val rowKeys = rows.map { (it.translateSourceKind ?: "") + ":" + (it.translateSourceTargetId ?: "") }.toSet()
        _runs.update { runs ->
            val cur = runs[runId] ?: return@update runs
            runs + (runId to copyWithItems(cur, cur.items.filterValues { item ->
                "${translateSrcKindOf(item.kind)}:${translateSrcTargetIdOf(item)}" !in rowKeys
            }))
        }
        ReportStorage.bumpReportTimestamp(context, sourceReportId)
    }

    /** Re-fire every expected entry in [runId]: deletes all existing
     *  rows (success or error) and re-dispatches the full prompt +
     *  agent + meta set from the current report state. The existing
     *  Semaphore(3) throttle inside [runTranslationSubset] still
     *  applies, so a large run shows a mix of RUNNING + PENDING rows
     *  in the detail screen rather than firing N calls in parallel. */
    fun restartAllTranslations(
        context: Context,
        sourceReportId: String,
        runId: String
    ): Job = appViewModel.viewModelScope.launch(rvm.reportLogContext(sourceReportId)) {
        // Cancel any in-flight run for this runId so its already-
        // dispatched coroutines don't keep writing fresh rows under
        // the about-to-be-restarted runId. Cancellation is co-operative;
        // in-flight API calls finish but the post-call writes are
        // gated by the run/item Job cancellation (BatchEngine cancelRun).
        cancelTranslation(runId)
        _runs.update { it - runId }

        val existing = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
            .filter { translationRunGroupingId(it) == runId }
        if (existing.isEmpty()) return@launch

        val report = ReportStorage.getReport(context, sourceReportId) ?: return@launch
        val secondaries = SecondaryResultStorage.listForReport(context, sourceReportId)
        val pairs = buildList<Pair<String, String>> {
            if (report.title.isNotBlank()) add("title" to "TITLE")
            if (!report.titleLong.isNullOrBlank()) add("titleLong" to "TITLE_LONG")
            add("prompt" to "PROMPT")
            report.agents
                .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                .forEach { add(it.agentId to "AGENT") }
            report.agents
                .filter { it.reportStatus == ReportStatus.SUCCESS && !it.modelTitle.isNullOrBlank() }
                .forEach { add(it.agentId to "AGENT_TITLE") }
            secondaries
                .filter { it.kind == SecondaryKind.META && !it.content.isNullOrBlank() }
                .forEach { add(it.id to "META") }
            secondaries
                .filter { it.kind == SecondaryKind.META && it.fanOutSourceAgentId != null && !it.title.isNullOrBlank() }
                .forEach { add(it.id to "FANOUT_TITLE") }
        }
        if (pairs.isEmpty()) return@launch

        // Explicit user re-fire — clear any resume-cap attempt counts so
        // these rows get a fresh BatchResume.MAX_ATTEMPTS budget.
        BatchResume.resetAttempts(existing.map { it.id })
        runTranslationSubset(
            context, sourceReportId, runId, pairs,
            deleteRowIds = existing.map { it.id }
        )
    }

    /** Broken-work "Continue" for a translation run: stop the in-flight run,
     *  drop its stale (possibly cancelled) in-memory state, then re-queue every
     *  broken row in one dispatch — errored rows are deleted + re-persisted
     *  fresh, stranded placeholders are reused — keeping finished rows. Mirrors
     *  [restartAllTranslations]' teardown so the run's `cancelled` flag can't
     *  stick, but only touches the broken rows (not the finished ones).
     *  [buildKey] drives the build-stage popup. */
    fun continueBrokenTranslation(
        context: Context,
        sourceReportId: String,
        runId: String,
        buildKey: String?
    ): Job = appViewModel.viewModelScope.launch(rvm.reportLogContext(sourceReportId)) {
        try {
            cancelTranslation(runId)
            _runs.update { it - runId }
            val rows = SecondaryResultStorage
                .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
                .filter { translationRunGroupingId(it) == runId }
            val errored = rows.filter { it.errorMessage != null }
            val placeholders = rows.filter {
                it.content.isNullOrBlank() && it.errorMessage == null && it.durationMs == null
            }
            val broken = errored + placeholders
            if (broken.isNotEmpty()) {
                BatchResume.resetAttempts(broken.map { it.id })
                runTranslationSubset(
                    context, sourceReportId, runId,
                    broken.map { it.translateSourceTargetId.orEmpty() to it.translateSourceKind.orEmpty() },
                    deleteRowIds = errored.map { it.id },
                    buildKey = buildKey
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            buildKey?.let { appViewModel.clearBuild(it) }
            throw e
        } catch (e: Exception) {
            AppLog.w("Translate", "continue broken batch failed run=$runId: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            // Release the popup so the overlay still opens when there was
            // nothing to re-queue (the normal path finishes it before dispatch).
            buildKey?.let {
                if (appViewModel.batchBuildProgress.value[it]?.done != true) appViewModel.finishBuild(it)
            }
        }
    }

    /** Re-dispatch translation items whose placeholder rows are
     *  still empty (no content, no error, no durationMs). The
     *  original target set lives on disk as one row per item
     *  (written up-front by [startTranslation] / [runTranslationSubset])
     *  so we never extend coverage to items added to the report
     *  AFTER the run completed — those have no placeholder for
     *  this runId and are intentionally skipped. */
    fun startMissingTranslations(
        context: Context,
        sourceReportId: String,
        runId: String
    ): Job = appViewModel.viewModelScope.launch(rvm.reportLogContext(sourceReportId)) {
        // Dedupe the 30s background sweep racing a screen-reopen relaunch
        // (replaces the old activeTranslationRunIds snapshot guard).
        if (!beginResumeScan(runId)) return@launch
        try {
        val existing = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
            .filter { translationRunGroupingId(it) == runId }
        // Only placeholders need re-dispatch — completed (has content)
        // and errored (errorMessage non-null) rows are terminal. The
        // ERROR rows are addressed separately by
        // restartFailedTranslations when the user opts in. Skip rows whose
        // worker is still live in THIS process (hasItemJob) so a sweep that
        // fires while a run is in flight doesn't double-dispatch them.
        val placeholders = existing.filter {
            it.content.isNullOrBlank() && it.errorMessage == null && it.durationMs == null &&
                !hasItemJob(it.id)
        }
        if (placeholders.isEmpty()) {
            // Nothing to dispatch but the in-memory state may still
            // be flagged in-flight from an earlier partial pass.
            // Flip finished=true so the manage hourglass clears.
            _runs.update { runs ->
                val cur = runs[runId] ?: return@update runs
                if (cur.finished) return@update runs
                runs + (runId to cur.copy(finished = true))
            }
            return@launch
        }
        // Bound auto-resume: a placeholder that never settles (repeated
        // app-kill / cancel before it completes) would otherwise re-dispatch
        // on every sweep forever. Cap it at BatchResume.MAX_ATTEMPTS the same
        // way the Tournament / Judge / Compare engines do — exhausted
        // placeholders are stamped ERROR instead of re-fired. The placeholder
        // row id is stable across passes (deleteRowIds is empty here), so the
        // per-row attempt count accumulates correctly.
        val retryRows = BatchResume.capForRetry(placeholders) { row ->
            val msg = "Interrupted — no result after ${BatchResume.MAX_ATTEMPTS} resume attempts"
            SecondaryResultStorage.saveIfStillPresent(context, row.copy(errorMessage = msg, durationMs = 0L))
            transitionItemByRowId(runId, row.id) {
                it.copy(status = TranslationStatus.ERROR, errorMessage = msg)
            }
        }
        if (retryRows.isEmpty()) {
            _runs.update { runs ->
                val cur = runs[runId] ?: return@update runs
                if (cur.finished) return@update runs
                runs + (runId to cur.copy(finished = true))
            }
            return@launch
        }
        val missing = retryRows.map {
            (it.translateSourceTargetId.orEmpty()) to (it.translateSourceKind.orEmpty())
        }
        runTranslationSubset(context, sourceReportId, runId, missing, deleteRowIds = emptyList())
        // After the subset's awaitAll() returns, every dispatched
        // item has settled (DONE or ERROR). Flip finished=true so
        // the manage hourglass clears and the next reconcile
        // doesn't re-fire us pointlessly.
        _runs.update { runs ->
            val cur = runs[runId] ?: return@update runs
            if (cur.finished) return@update runs
            runs + (runId to cur.copy(finished = true))
        }
        } finally { endResumeScan(runId) }
    }

    /** Rebuild [TranslationItem]s from the persisted TRANSLATE rows of
     *  a run. Used by [runTranslationSubset] to seed live state on a
     *  post-kill resume, and by [buildPersistedTranslationRunState] to
     *  render a finished run in the 3-level run screen. Each item is
     *  stamped with the provider/model that produced the row and the
     *  on-disk row id. Rows in [deleteSet] and stale placeholders (no
     *  content, no error) are skipped. */
    private fun reconstructTranslationItemsFromDisk(
        translateRows: List<SecondaryResult>,
        report: Report,
        secondaries: List<SecondaryResult>,
        deleteSet: Set<String>,
        /** When true, placeholder rows (no content, no error, no
         *  durationMs) materialise as PENDING items rather than
         *  being dropped. Display path (L1 fallback) wants this so
         *  the screen shows the full run size + Queue count even
         *  before the resume dispatch has re-seeded in-memory
         *  state. Resume seeding path leaves the default false so
         *  the placeholders aren't double-counted alongside the
         *  fresh items runTranslationSubset is about to build for
         *  them. */
        includePlaceholders: Boolean = false
    ): List<TranslationItem> = translateRows
        .filter { it.id !in deleteSet }
        .mapNotNull { row ->
            val kind = translateKindOf(row.translateSourceKind) ?: return@mapNotNull null
            val targetId = row.translateSourceTargetId.orEmpty()
            val (itemId, label) = when (kind) {
                TranslationKind.TITLE -> "title" to "Report title"
                TranslationKind.TITLE_LONG -> "titleLong" to "Report long title"
                TranslationKind.PROMPT -> "prompt" to "Report prompt"
                TranslationKind.AGENT_RESPONSE -> {
                    val ag = report.agents.firstOrNull { it.agentId == targetId }
                    val prov = AppService.findById(ag?.provider.orEmpty())?.id ?: ag?.provider.orEmpty()
                    "agent:$targetId" to "$prov / ${ag?.model.orEmpty()}"
                }
                TranslationKind.AGENT_TITLE -> {
                    val ag = report.agents.firstOrNull { it.agentId == targetId }
                    val prov = AppService.findById(ag?.provider.orEmpty())?.id ?: ag?.provider.orEmpty()
                    "agentTitle:$targetId" to "Title: $prov / ${ag?.model.orEmpty()}"
                }
                TranslationKind.META -> {
                    val s = secondaries.firstOrNull { it.id == targetId }
                    val prov = AppService.findById(s?.providerId.orEmpty())?.id ?: s?.providerId.orEmpty()
                    val name = s?.metaPromptName?.takeIf { it.isNotBlank() }
                        ?: s?.let { com.ai.data.legacyKindDisplayName(it.kind) } ?: ""
                    "meta:$targetId" to "$name: $prov / ${s?.model.orEmpty()}"
                }
                TranslationKind.FANOUT_TITLE -> {
                    val s = secondaries.firstOrNull { it.id == targetId }
                    val prov = AppService.findById(s?.providerId.orEmpty())?.id ?: s?.providerId.orEmpty()
                    "fanoutTitle:$targetId" to "Fan title: $prov / ${s?.model.orEmpty()}"
                }
            }
            val status = when {
                row.errorMessage != null -> TranslationStatus.ERROR
                !row.content.isNullOrBlank() -> TranslationStatus.DONE
                includePlaceholders -> TranslationStatus.PENDING
                else -> return@mapNotNull null  // resume dispatch covers it
            }
            TranslationItem(
                id = itemId, label = label, kind = kind,
                sourceText = "",
                target = targetId.takeIf {
                    kind != TranslationKind.PROMPT && kind != TranslationKind.TITLE &&
                        kind != TranslationKind.TITLE_LONG
                },
                status = status,
                translatedText = row.content,
                errorMessage = row.errorMessage,
                costDollars = (row.inputCost ?: 0.0) + (row.outputCost ?: 0.0),
                tokenUsage = row.tokenUsage,
                durationMs = row.durationMs,
                // Placeholders carry blank providerId/model (no model
                // has picked them up yet); leave the item's fields
                // null so translationModelKey returns null and the
                // L1 keeps them in the Queue rather than grouping them
                // under a phantom "" / "" model row.
                providerId = row.providerId.takeIf { it.isNotBlank() },
                model = row.model.takeIf { it.isNotBlank() },
                persistedRowId = row.id
            )
        }
        // Stamp the per-kind trace/cost Type — the same value the live
        // run carries (line ~236) — so the display path's Types grouping
        // works. Without this every reconstructed item keeps the data-
        // class default ("translate/translate") and Types collapses to a
        // single "translate" row.
        .map { it.copy(traceType = traceTypeFor(it, secondaries)) }

    /** Reconstruct a finished / persisted translation run as a
     *  [TranslationRunState] so the 3-level run screen can render it
     *  exactly like a live run. Returns null when the run has no
     *  persisted TRANSLATE rows on disk. */
    suspend fun buildPersistedTranslationRunState(
        context: Context,
        reportId: String,
        runId: String
    ): TranslationRunState? = withContext(Dispatchers.IO) {
        val rows = SecondaryResultStorage
            .listForReport(context, reportId, SecondaryKind.TRANSLATE)
            .filter { translationRunGroupingId(it) == runId }
        if (rows.isEmpty()) return@withContext null
        val anchor = rows.first()
        val report = ReportStorage.getReport(context, reportId) ?: return@withContext null
        val secondaries = SecondaryResultStorage.listForReport(context, reportId)
        // includePlaceholders=true: a run interrupted by app restart
        // has placeholder rows on disk that the L1 should surface as
        // PENDING (Queue) items so the Total + per-status counts match
        // what the manage page shows for the same runId. The resume
        // dispatch later replaces these in the live state with real
        // items as workers pick them up.
        val items = reconstructTranslationItemsFromDisk(rows, report, secondaries, emptySet(), includePlaceholders = true)
        TranslationRunState(
            runId = runId,
            sourceReportId = reportId,
            targetLanguageName = anchor.targetLanguage ?: "",
            targetLanguageNative = anchor.targetLanguageNative ?: anchor.targetLanguage ?: "",
            items = items.associateBy { it.id },
            finished = true,
            cancelled = false,
            // Distinct (providerId, model) tuples from the disk rows
            // — skipping the blank-provider placeholder rows that
            // haven't been claimed by a worker yet.
            models = rows
                .filter { it.providerId.isNotBlank() && it.model.isNotBlank() }
                .map { "${it.providerId}|${it.model}" }
                .distinct()
        )
    }

    /** Shared core for [restartFailedTranslations] /
     *  [startMissingTranslations]. Reads the existing run rows to
     *  pick up provider / model / language, deletes any rows in
     *  [deleteRowIds] (used by the restart path so failed rows
     *  don't double up), builds [TranslationItem]s for each
     *  (target, kind) pair, populates [_runs] under
     *  [runId], and dispatches via [runOneTranslation]. */
    private suspend fun runTranslationSubset(
        context: Context,
        sourceReportId: String,
        runId: String,
        targetKindPairs: List<Pair<String, String>>,
        deleteRowIds: List<String>,
        /** Optional per-pair source-text overrides. When a (kind,
         *  targetId) key is present the override string is used as
         *  the translation input instead of the default
         *  `report.prompt` / `agent.responseBody` / `meta.content`.
         *  Used by [translateMissingItems] to translate FROM an
         *  arbitrary source language (e.g. an existing Spanish
         *  TRANSLATE row's content) instead of always from Original.
         *  Null preserves the original behavior for the failed-
         *  restart and start-missing callers. */
        sourceTextOverrides: Map<Pair<String, String>, String>? = null,
        buildKey: String? = null
    ) {
        if (targetKindPairs.isEmpty()) return
        val translateRows = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
            .filter { translationRunGroupingId(it) == runId }
        val anchor = translateRows.firstOrNull() ?: return
        val targetLanguageName = anchor.targetLanguage ?: return
        val targetLanguageNative = anchor.targetLanguageNative ?: targetLanguageName

        // Index the existing rows by (kind, targetId) so we can reuse
        // each placeholder / prior row's id — the re-dispatch's save
        // then OVERWRITES it rather than spawning a parallel record.
        val rowByKindTarget: Map<Pair<String, String>, SecondaryResult> = translateRows
            .mapNotNull { r ->
                val k = r.translateSourceKind ?: return@mapNotNull null
                val t = r.translateSourceTargetId ?: return@mapNotNull null
                (k to t) to r
            }
            .toMap()

        val report = ReportStorage.getReport(context, sourceReportId) ?: return
        val secondaries = SecondaryResultStorage.listForReport(context, sourceReportId)

        var items = targetKindPairs.mapNotNull { (targetId, kind) ->
            // Reuse the existing row's id as the item's persistedRowId
            // so the re-dispatch's save overwrites the placeholder
            // (or the prior failed row, in the restart-failed flow)
            // rather than spawning a parallel record.
            val rowId = rowByKindTarget[(kind to targetId)]?.id
            // Honor caller-supplied source-text overrides ahead of
            // the default report/agent/meta derivation — used to
            // translate from a non-Original source language.
            val sourceOverride = sourceTextOverrides?.get(kind to targetId)
            when (kind) {
                "TITLE" -> TranslationItem(
                    id = "title", label = "Report title",
                    kind = TranslationKind.TITLE,
                    sourceText = sourceOverride ?: report.title,
                    persistedRowId = rowId
                )
                "TITLE_LONG" -> TranslationItem(
                    id = "titleLong", label = "Report long title",
                    kind = TranslationKind.TITLE_LONG,
                    sourceText = sourceOverride ?: report.titleLong.orEmpty(),
                    persistedRowId = rowId
                )
                "PROMPT" -> TranslationItem(
                    id = "prompt", label = "Report prompt",
                    kind = TranslationKind.PROMPT,
                    sourceText = sourceOverride ?: report.prompt,
                    persistedRowId = rowId
                )
                "AGENT" -> {
                    val ag = report.agents.firstOrNull { it.agentId == targetId } ?: return@mapNotNull null
                    val prov = AppService.findById(ag.provider)?.id ?: ag.provider
                    TranslationItem(
                        id = "agent:${ag.agentId}",
                        label = "$prov / ${ag.model}",
                        kind = TranslationKind.AGENT_RESPONSE,
                        sourceText = sourceOverride ?: ag.responseBody.orEmpty(),
                        target = ag.agentId,
                        persistedRowId = rowId
                    )
                }
                "AGENT_TITLE" -> {
                    val ag = report.agents.firstOrNull { it.agentId == targetId } ?: return@mapNotNull null
                    val prov = AppService.findById(ag.provider)?.id ?: ag.provider
                    TranslationItem(
                        id = "agentTitle:${ag.agentId}",
                        label = "Title: $prov / ${ag.model}",
                        kind = TranslationKind.AGENT_TITLE,
                        sourceText = sourceOverride ?: ag.modelTitle.orEmpty(),
                        target = ag.agentId,
                        persistedRowId = rowId
                    )
                }
                "META" -> {
                    val s = secondaries.firstOrNull { it.id == targetId } ?: return@mapNotNull null
                    val prov = AppService.findById(s.providerId)?.id ?: s.providerId
                    val name = s.metaPromptName?.takeIf { it.isNotBlank() }
                        ?: com.ai.data.legacyKindDisplayName(s.kind)
                    TranslationItem(
                        id = "meta:${s.id}", label = "$name: $prov / ${s.model}",
                        kind = TranslationKind.META,
                        sourceText = sourceOverride ?: s.content.orEmpty(),
                        target = s.id,
                        persistedRowId = rowId
                    )
                }
                "FANOUT_TITLE" -> {
                    val s = secondaries.firstOrNull { it.id == targetId } ?: return@mapNotNull null
                    val prov = AppService.findById(s.providerId)?.id ?: s.providerId
                    TranslationItem(
                        id = "fanoutTitle:${s.id}",
                        label = "Fan title: $prov / ${s.model}",
                        kind = TranslationKind.FANOUT_TITLE,
                        sourceText = sourceOverride ?: s.title.orEmpty(),
                        target = s.id,
                        persistedRowId = rowId
                    )
                }
                else -> null
            }
        }.map { it.copy(traceType = traceTypeFor(it, secondaries)) }
        if (items.isEmpty()) return

        // Delete the rows we're replacing so the rerun doesn't double
        // up under the same (target, kind) pair.
        val deletedCostDelta = deleteRowIds.sumOf { rowId ->
            SecondaryResultStorage.get(context, sourceReportId, rowId)?.fullCost() ?: 0.0
        }
        deleteRowIds.forEach { SecondaryResultStorage.delete(context, sourceReportId, it) }
        ReportStorage.removeIconCallsForSecondaryIds(context, sourceReportId, deleteRowIds.toSet())
        if (deletedCostDelta > 0.0) {
            ReportStorage.bumpCostsFromDeletedItems(context, sourceReportId, deletedCostDelta)
        }

        // Re-persist a PENDING placeholder for each reused row up front, before
        // dispatching. The prior code wrote a row only when its call completed,
        // so an interruption between the delete above and completion (e.g. a
        // process kill mid-restart, or a cancelled batch) left NOTHING on disk
        // — the whole translation vanished from Manage. Writing placeholders
        // now (same shape as startTranslation's build phase) means an
        // interruption leaves resumable PENDING rows that Broken-work /
        // reconcile can pick up, and the run keeps showing on Manage.
        // runOneTranslation overwrites each placeholder on completion.
        // Build stage: re-persisting each PENDING placeholder is the
        // "Preparing N / M…" phase the Broken-work Continue popup covers.
        if (buildKey != null) appViewModel.beginBuild(buildKey, items.size, "Re-queuing translation")
        val placeholderRows = items.mapNotNull { item ->
            val pid = item.persistedRowId ?: return@mapNotNull null
            SecondaryResult(
                id = pid,
                reportId = sourceReportId,
                kind = SecondaryKind.TRANSLATE,
                providerId = "",
                model = "",
                agentName = "Translate: ${item.label.ifBlank { item.kind.name.lowercase() }}",
                timestamp = System.currentTimeMillis(),
                content = null,
                errorMessage = null,
                translateSourceKind = translateSrcKindOf(item.kind),
                translateSourceTargetId = translateSrcTargetIdOf(item),
                targetLanguage = targetLanguageName,
                targetLanguageNative = targetLanguageNative,
                translationRunId = runId,
                runId = runId,
            )
        }
        val savedIds = SecondaryResultStorage.saveAll(context, placeholderRows).mapTo(HashSet()) { it.id }
        items = items.filter { it.persistedRowId in savedIds }
        if (buildKey != null) {
            appViewModel.updateBuild(buildKey, items.size)
        }
        // Build phase complete — release the popup so the UI navigates to the
        // batch screen while the dispatch below keeps running in the background.
        if (buildKey != null) appViewModel.finishBuild(buildKey)
        if (items.isEmpty()) return

        val aiSettings = appViewModel.uiState.value.aiSettings
        // ♻️ Models-as-workers must hold on restart / resume too — mirror
        // startTranslation's swap, or the re-dispatch would translate with
        // the CONFIGURED swarm instead of the report's own models.
        val reportSwarm = if (report.useReportModelsAsWorkers) reportModelWorkers(report) else null
        val textPrompt = workerTranslatePrompt(aiSettings, title = false)
            ?.let { p -> reportSwarm?.let { p.copy(workers = it) } ?: p }
        val titlePrompt = workerTranslatePrompt(aiSettings, title = true)
            ?.let { p -> reportSwarm?.let { p.copy(workers = it) } ?: p }

        // When the in-memory run state is gone (post-kill resume or
        // manual reload on a finished run), seed the fresh state with
        // already-settled rows from disk so the detail screen shows
        // every entry — the done + the about-to-retry — not just the
        // ones currently being re-dispatched.
        val deleteSet = deleteRowIds.toSet()
        val persistedItems: List<TranslationItem> = if (_runs.value[runId] == null) {
            reconstructTranslationItemsFromDisk(translateRows, report, secondaries, deleteSet)
        } else emptyList()

        // Merge our items into _runs under this runId so
        // runOneTranslation can read the active TranslationRunState.
        _runs.update { runs ->
            val cur = runs[runId]
            // Dedupe by persistedRowId — if cur.items already has an
            // entry for the same disk row (typical after the reconcile
            // sweep rebuilt the in-memory state with placeholders as
            // PENDING and then handed the same placeholders to us to
            // dispatch), the new TranslationItem must REPLACE the old one
            // rather than be appended. Also re-opens the run
            // (`finished = false`) because the append is new work.
            val merged = if (cur != null) {
                val newIds = items.mapNotNull { it.persistedRowId }.toSet()
                val keptOld = cur.items.filterValues { it.persistedRowId !in newIds }
                cur.copy(items = keptOld + items.associateBy { it.id }, finished = false)
            } else TranslationRunState(
                runId = runId,
                sourceReportId = sourceReportId,
                targetLanguageName = targetLanguageName,
                targetLanguageNative = targetLanguageNative,
                items = (persistedItems + items).associateBy { it.id }
            )
            runs + (runId to merged)
        }

        if (textPrompt == null && titlePrompt == null) {
            items.forEach { finalizeTranslationError(context, runId, it, "translate worker prompt missing — add it under AI Setup → Prompt management → Worker prompts") }
            _runs.update { runs ->
                val cur = runs[runId] ?: return@update runs
                runs + (runId to cur.copy(finished = true))
            }
            return
        }

        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        try {
            // Per-item dispatch through the translate worker swarm under
            // the translation flow cap (dynamic host — each worker call
            // self-throttles its own provider). A per-item 180s ceiling
            // bounds a whole chain; a timeout finalizes that item ERROR.
            withTracerTags(reportId = sourceReportId, runId = runId) {
                runThrottledBatch(
                    items = items,
                    hostOf = { null },
                    subCap = ApiCallCaps.translation,
                    dynamicHost = true,
                    register = { item, d -> item.persistedRowId?.let { registerItemJob(it, d) } },
                ) { item ->
                    val outcome = kotlinx.coroutines.withTimeoutOrNull(180_000) {
                        runOneTranslation(runId, context, item, targetLanguageName, textPrompt, titlePrompt)
                    } ?: TranslationOutcome.Failed("translation timed out after 180s")
                    if (outcome is TranslationOutcome.Failed) {
                        finalizeTranslationError(context, runId, item, outcome.message)
                    }
                }
            }
            ReportStorage.bumpReportTimestamp(context, sourceReportId)
        } finally {
            appViewModel.updateUiState {
                it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0))
            }
        }
    }

    /** Multi-language meta cross-translate dispatch. For each non-seed
     *  language, append one TRANSLATE item per just-completed seed META
     *  to that language's existing translation run, reopen the run
     *  (`finished = false`), dispatch via [runTranslationSubset], and
     *  close it again. The seed META rows aren't touched — only the
     *  target-language translation runs are reopened. The translate
     *  prompt (`internal/translate`) has only `@LANGUAGE@` / `@TEXT@`,
     *  no source-language placeholder, so cross-language source text
     *  (French → Dutch in the canonical scenario) works without any
     *  prompt change — the model auto-detects the source. */
    internal suspend fun addCrossTranslationItems(
        context: Context,
        reportId: String,
        targetLanguageName: String,
        targetLanguageNative: String,
        sourceMetas: List<SecondaryResult>,
        allSecondaries: List<SecondaryResult>
    ) {
        if (sourceMetas.isEmpty()) return
        // The meta language picker only offers languages that already
        // have a translation run, so a missing runId here means
        // something raced (e.g. the run was deleted between picker
        // open and meta execution). Skip rather than synthesising a
        // fresh run — that would surface as an unexpected new run
        // row in the manage list.
        val runId = allSecondaries
            .firstOrNull { it.kind == SecondaryKind.TRANSLATE && it.targetLanguage == targetLanguageName }
            ?.let { translationRunGroupingId(it) }
        if (runId == null) {
            AppLog.w("Meta-xlate", "No existing translation run for $targetLanguageName — skipping cross-translate")
            return
        }

        // Build placeholder TRANSLATE rows on disk so a process kill
        // mid-cross-translate leaves rows that startMissingTranslations
        // can pick up on resume — same pattern as startTranslation's
        // up-front placeholder persistence.
        val placeholderRows = sourceMetas.map { meta ->
            val prov = AppService.findById(meta.providerId)?.id ?: meta.providerId
            val name = meta.metaPromptName?.takeIf { it.isNotBlank() }
                ?: com.ai.data.legacyKindDisplayName(meta.kind)
            val label = "$name: $prov / ${shortModelName(meta.model)}"
            val placeholderId = java.util.UUID.randomUUID().toString()
            SecondaryResult(
                id = placeholderId,
                reportId = reportId,
                kind = SecondaryKind.TRANSLATE,
                providerId = "",
                model = "",
                agentName = "Translate: $label",
                timestamp = System.currentTimeMillis(),
                content = null,
                errorMessage = null,
                translateSourceKind = "META",
                translateSourceTargetId = meta.id,
                targetLanguage = targetLanguageName,
                targetLanguageNative = targetLanguageNative,
                translationRunId = runId,
                runId = runId,
            )
        }
        val savedIds = SecondaryResultStorage.saveAll(context, placeholderRows).mapTo(HashSet()) { it.id }
        val placeholders = sourceMetas.zip(placeholderRows)
            .filter { (_, row) -> row.id in savedIds }
            .map { (meta, _) -> meta.id }
        if (placeholders.isEmpty()) return

        // Reopen the run: flip `finished` to false so the manage screen
        // (which filters by !it.isFinished && !it.cancelled) surfaces
        // the live ⏳ row again. Rebuild from disk if the run was
        // already evicted from memory (consumeTranslationRun after the
        // original run completed).
        val current = _runs.value[runId]
        if (current != null) {
            _runs.update { runs ->
                val c = runs[runId] ?: return@update runs
                runs + (runId to c.copy(finished = false))
            }
        } else {
            val rebuilt = buildPersistedTranslationRunState(context, reportId, runId) ?: run {
                AppLog.w("Meta-xlate", "Could not rebuild persisted state for run $runId — aborting cross-translate")
                return
            }
            _runs.update { it + (runId to rebuilt.copy(finished = false)) }
        }

        // runTranslationSubset reads the placeholders (rowByKindTarget
        // lookup), builds the TranslationItems from the seed metas in
        // `secondaries`, merges them into _runs, and
        // dispatches. It awaits all items before returning.
        runTranslationSubset(
            context = context,
            sourceReportId = reportId,
            runId = runId,
            targetKindPairs = placeholders.map { it to "META" },
            deleteRowIds = emptyList()
        )

        // All cross-translate items have settled — close the run again
        // so the manage row reverts from live ⏳ to summary.
        _runs.update { runs ->
            val cur = runs[runId] ?: return@update runs
            runs + (runId to cur.copy(finished = true))
        }
        ReportStorage.bumpReportTimestamp(context, reportId)
    }


    /** Translate one or more items into [targetLanguageName] using
     *  the source text the caller already resolved from the user's
     *  picked source language. Attaches to the existing target-
     *  language translation run; reuses that run's model set so the
     *  user doesn't have to pick. Fires the View-screen "Language
     *  missing" popup's chosen action. */
    fun translateMissingItems(
        context: Context,
        reportId: String,
        items: List<TranslateMissingItem>,
        targetLanguageName: String,
        targetLanguageNative: String
    ): Job? {
        if (items.isEmpty()) return null
        return appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            val allSecondaries = SecondaryResultStorage.listForReport(context, reportId)
            val existingRunId = allSecondaries
                .firstOrNull { it.kind == SecondaryKind.TRANSLATE && it.targetLanguage == targetLanguageName }
                ?.let { translationRunGroupingId(it) }
            // Bootstrap a new run when the target language has none.
            // This is the "back-translation to Original" case: the
            // user picked Original on the View screen for a META they
            // only have in a non-Original language, and the helper
            // translates into report.languageName which has no prior
            // run yet. The translate worker swarm is always available,
            // so a new run needs only a fresh id — no model to inherit.
            val runId: String = existingRunId ?: java.util.UUID.randomUUID().toString()

            // Persist placeholder TRANSLATE rows — same pattern as
            // addCrossTranslationItems. Map the (sourceKind, targetId)
            // back to the placeholder row id so runTranslationSubset's
            // rowByKindTarget lookup picks it up and saveOneTranslationItem
            // overwrites this row in place.
            val placeholderRows = items.map { item ->
                SecondaryResult(
                    id = java.util.UUID.randomUUID().toString(),
                    reportId = reportId,
                    kind = SecondaryKind.TRANSLATE,
                    providerId = "",
                    model = "",
                    agentName = "Translate: ${item.label}",
                    timestamp = System.currentTimeMillis(),
                    content = null,
                    errorMessage = null,
                    translateSourceKind = item.sourceKind,
                    translateSourceTargetId = item.targetId,
                    targetLanguage = targetLanguageName,
                    targetLanguageNative = targetLanguageNative,
                    translationRunId = runId,
                    runId = runId,
                )
            }
            val savedIds = SecondaryResultStorage.saveAll(context, placeholderRows).mapTo(HashSet()) { it.id }
            val savedItems = items.zip(placeholderRows)
                .filter { (_, row) -> row.id in savedIds }
                .map { (item, _) -> item }
            if (savedItems.isEmpty()) return@launch

            // Reopen the run so the live row reverts to ⏳ while the
            // new items dispatch. Rebuild from disk if the run was
            // evicted from memory after the original finished. For
            // a brand-new (bootstrapped) run we let
            // runTranslationSubset seed _runs on first
            // touch — no pre-flip needed.
            if (existingRunId != null) {
                if (_runs.value[runId] != null) {
                    _runs.update { runs ->
                        val c = runs[runId] ?: return@update runs
                        runs + (runId to c.copy(finished = false))
                    }
                } else {
                    val rebuilt = buildPersistedTranslationRunState(context, reportId, runId) ?: run {
                        AppLog.w("Translate-missing", "Could not rebuild persisted state for run $runId — aborting")
                        return@launch
                    }
                    _runs.update { it + (runId to rebuilt.copy(finished = false)) }
                }
            }

            // Dispatch via runTranslationSubset with per-item source
            // overrides — passes our caller-resolved sourceText instead
            // of the default Original-derivation.
            val pairs = savedItems.map { it.targetId to it.sourceKind }
            val overrides: Map<Pair<String, String>, String> = savedItems.associate {
                (it.sourceKind to it.targetId) to it.sourceText
            }
            runTranslationSubset(
                context = context,
                sourceReportId = reportId,
                runId = runId,
                targetKindPairs = pairs,
                deleteRowIds = emptyList(),
                sourceTextOverrides = overrides
            )

            _runs.update { runs ->
                val cur = runs[runId] ?: return@update runs
                runs + (runId to cur.copy(finished = true))
            }
            ReportStorage.bumpReportTimestamp(context, reportId)
        }
    }
}
