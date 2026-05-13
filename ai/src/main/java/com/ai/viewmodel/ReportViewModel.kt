package com.ai.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.data.*
import com.ai.model.*
import com.ai.ui.report.translationRunGroupingId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * ViewModel for AI report generation: task building, concurrent execution, cost calculation.
 * Delegates to AppViewModel for shared state and settings.
 */
class ReportViewModel(private val appViewModel: AppViewModel) {

    private var reportGenerationJob: Job? = null
    @Volatile private var reportRunningInBackground = false

    // Active fan-out jobs keyed by "$reportId|$metaPromptId". Used
    // by rerunCompleteFanOut / rerunFailedFanOutPairs / resumeStaleFanOutPairs
    // so the destructive "wipe + rerun" paths can cancel and join the
    // existing run before deleting placeholders. Without this, surviving
    // coroutines from the previous run kept calling
    // SecondaryResultStorage.save on the just-deleted ids, resurrecting
    // zombie rows alongside the freshly-created placeholders and
    // double-billing the user.
    private val fanOutJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private fun fanOutJobKey(reportId: String, metaPromptId: String) = "$reportId|$metaPromptId"
    private fun registerFanOutJob(reportId: String, metaPromptId: String, job: Job) {
        val key = fanOutJobKey(reportId, metaPromptId)
        fanOutJobs[key] = job
        job.invokeOnCompletion { fanOutJobs.remove(key, job) }
    }

    // Outer Jobs for "Find alternative icons" fan-outs, keyed by
    // reportId. Cancelling the entry cascades to every per-pair child
    // launch inside startIconFanOut so a deleteReport can stop the
    // whole search in one call instead of leaving N orphan HTTP-calls
    // running on viewModelScope.
    private val iconFanOutJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private fun registerIconFanOutJob(reportId: String, job: Job) {
        // Cancel any prior in-flight run for the same report — a user
        // who hits Find Icons twice in a row should get the latest
        // selection, not two overlapping searches.
        iconFanOutJobs.put(reportId, job)?.cancel()
        job.invokeOnCompletion { iconFanOutJobs.remove(reportId, job) }
    }

    // Sibling map for the per-agent "Report icons" run (Create →
    // Report icons). Same cancel-prior-run-on-retap semantics as
    // iconFanOutJobs; cleaned up by deleteReport.
    // Per-agent "Report icons" 3-tier chain jobs. Each successful
    // agent gets its own entry the moment its primary call finishes
    // (so a fast row's icon search can start while a slow row is
    // still generating). Keyed by "$reportId|$agentId" — same shape
    // as the existing agentIconFanOutJobs map — so deleteReport's
    // prefix sweep on "$reportId|" cancels them too.
    private val reportIconsJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private fun reportIconJobKey(reportId: String, agentId: String) = "$reportId|$agentId"
    private fun registerReportIconForAgentJob(reportId: String, agentId: String, job: Job) {
        val key = reportIconJobKey(reportId, agentId)
        reportIconsJobs.put(key, job)?.cancel()
        job.invokeOnCompletion { reportIconsJobs.remove(key, job) }
    }

    // Per-agent alternative-icons fan-out jobs (Agent icon detail →
    // Find alternative icons). Keyed by "$reportId|$agentId" so
    // deleteReport's prefix cancel sweeps them too.
    private val agentIconFanOutJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private fun agentIconJobKey(reportId: String, agentId: String) = "$reportId|$agentId"
    private fun registerAgentIconFanOutJob(reportId: String, agentId: String, job: Job) {
        val key = agentIconJobKey(reportId, agentId)
        agentIconFanOutJobs.put(key, job)?.cancel()
        job.invokeOnCompletion { agentIconFanOutJobs.remove(key, job) }
    }

    // Tracks in-flight resumeStaleFanOutPairs scans per (reportId,
    // metaPromptId). The L1 screen fires resumeStaleFanOutPairs from a
    // LaunchedEffect that re-keys whenever fanOutPrompt changes
    // identity — and fanOutPrompt is recomputed on every aiSettings
    // change (i.e., any settings save, even unrelated ones). Without a
    // guard, touching Settings while a Fan out is running would re-issue
    // the listForReport scan + recovery enqueue, stacking duplicate
    // work on the executor's semaphore.
    private val staleResumeScans = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Per-pair fan-out coroutines, keyed by SecondaryResult.id (the
    // placeholder id). Populated inside runFanOutPrompt /
    // rerunFanOutPlaceholders right before the async block enters its
    // HTTP path, removed via invokeOnCompletion. deleteFanOutModel
    // and rerunCompleteFanOut cancelAndJoin the relevant entries
    // BEFORE deleting their rows, so a coroutine mid-flight can't
    // land a completion via saveIfStillPresent after the delete —
    // which would either silently drop the just-purchased result
    // (exists() returns false) or, worse, resurrect the row in a
    // half-written state. Concurrent map for cross-thread access
    // from the UI-thread Delete handler.
    private val fanOutPairJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    // Tracks single-call Meta/Rerank/Moderation placeholders the
    // report-open auto-resume sweep is currently re-issuing, so a
    // rapid back-then-forward navigation can't double-fire the same
    // row. Keyed by SecondaryResult.id; entries removed in the
    // finally of [resumeStaleMetaPlaceholder].
    private val resumingMetaIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Separate flow from UiState so per-task completions don't force the UiState equality
    // checker to re-compare every other field. UI subscribers observe this independently.
    private val _agentResults = MutableStateFlow<Map<String, AnalysisResponse>>(emptyMap())
    val agentResults: StateFlow<Map<String, AnalysisResponse>> = _agentResults.asStateFlow()

    private data class ReportTask(
        val resultId: String,
        val reportAgent: ReportAgent,
        val runtimeAgent: Agent,
        val resolvedParams: AgentParameters
    )

    fun showGenericAgentSelection(
        title: String, prompt: String,
        imageBase64: String? = null, imageMime: String? = null,
        webSearchTool: Boolean = false,
        reasoningEffort: String? = null
    ) {
        _agentResults.value = emptyMap()
        appViewModel.updateUiState { it.copy(
            genericPromptTitle = title, genericPromptText = prompt,
            reportImageBase64 = imageBase64, reportImageMime = imageMime,
            reportWebSearchTool = webSearchTool,
            reportReasoningEffort = reasoningEffort,
            showGenericAgentSelection = true, showGenericReportsDialog = false,
            genericReportsProgress = 0, genericReportsTotal = 0,
            genericReportsSelectedAgents = emptySet(),
            currentReportId = null
        ) }
    }

    fun dismissGenericAgentSelection() {
        appViewModel.updateUiState { it.copy(showGenericAgentSelection = false) }
    }

    fun generateGenericReports(
        context: Context,
        selectedAgentIds: Set<String>,
        selectedSwarmIds: Set<String> = emptySet(),
        directModelIds: Set<String> = emptySet(),
        parametersIds: List<String> = emptyList(),
        selectionParamsById: Map<String, List<String>> = emptyMap(),
        reportType: ReportType = ReportType.CLASSIC
    ) {
        reportGenerationJob?.cancel()
        // Outer launch on viewModelScope so navigating away from the
        // result screen doesn't cancel the in-flight OkHttp calls.
        // A screen-scoped scope here previously turned every
        // still-running agent into ERROR on disk: the cancellation
        // surfaced as IOException("Canceled"), executeReportTask's
        // catch (Exception) converted it to a real error response,
        // and the NonCancellable terminal write persisted that error.
        // continueReportInBackground() only sets a flag — without
        // viewModelScope here, "background" can't actually happen.
        reportGenerationJob = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val state = appViewModel.uiState.value
            val aiSettings = state.aiSettings
            val prompt = state.genericPromptText
            val title = state.genericPromptTitle
            val externalSystemPrompt = state.externalSystemPrompt
            val imageBase64 = state.reportImageBase64
            val imageMime = state.reportImageMime
            // Drop the per-report image / per-report flags from UiState
            // as soon as we've captured them into local vals. Otherwise
            // a megabyte-sized base64 photo stays resident on UiState
            // until the report finishes (or forever if the user navigates
            // away and never comes back). The locals here keep the
            // bytes alive for the agents that need them.
            appViewModel.updateUiState { it.copy(
                reportImageBase64 = null, reportImageMime = null,
                reportWebSearchTool = false, reportReasoningEffort = null
            ) }
            // Layer the per-report advanced overlay on top of any preset
            // merge — "later non-null wins" (matches Settings.mergeParameters
            // semantics for the preset chain). Either layer alone produces
            // the right result; together the user's explicit per-report
            // tweaks win over preset defaults instead of being shadowed by
            // them. Bool fields OR upward.
            val mergedParams = aiSettings.mergeParameters(parametersIds)
            val advanced = state.reportAdvancedParameters
            val baseOverride = when {
                mergedParams == null && advanced == null -> null
                mergedParams == null -> advanced
                advanced == null -> mergedParams
                else -> AgentParameters(
                    temperature = advanced.temperature ?: mergedParams.temperature,
                    maxTokens = advanced.maxTokens ?: mergedParams.maxTokens,
                    topP = advanced.topP ?: mergedParams.topP,
                    topK = advanced.topK ?: mergedParams.topK,
                    frequencyPenalty = advanced.frequencyPenalty ?: mergedParams.frequencyPenalty,
                    presencePenalty = advanced.presencePenalty ?: mergedParams.presencePenalty,
                    systemPrompt = advanced.systemPrompt ?: mergedParams.systemPrompt,
                    stopSequences = advanced.stopSequences ?: mergedParams.stopSequences,
                    seed = advanced.seed ?: mergedParams.seed,
                    responseFormatJson = advanced.responseFormatJson || mergedParams.responseFormatJson,
                    searchEnabled = advanced.searchEnabled || mergedParams.searchEnabled,
                    // returnCitations defaults to true and combines with
                    // AND so an explicit opt-out anywhere in the chain is
                    // honoured — same semantic as Settings.mergeParameters.
                    // Previously the overlay clobbered to advanced's value
                    // (true on a fresh dialog open), silently re-enabling
                    // citations on every preset-disabled run.
                    returnCitations = advanced.returnCitations && mergedParams.returnCitations,
                    searchRecency = advanced.searchRecency ?: mergedParams.searchRecency,
                    webSearchTool = advanced.webSearchTool || mergedParams.webSearchTool,
                    reasoningEffort = advanced.reasoningEffort ?: mergedParams.reasoningEffort
                )
            }
            // The per-report 🌐 toggle adds webSearchTool=true on top of any
            // existing override so it ORs onto each agent's pinned default.
            // Same overlay for the per-report 🧠 reasoning level — non-
            // reasoning models drop the field at dispatch.
            val withWeb = if (state.reportWebSearchTool) {
                (baseOverride ?: AgentParameters()).copy(webSearchTool = true)
            } else baseOverride
            val overrideParams = if (state.reportReasoningEffort != null) {
                (withWeb ?: AgentParameters()).copy(reasoningEffort = state.reportReasoningEffort)
            } else withWeb

            val agents = selectedAgentIds.mapNotNull { aiSettings.getAgentById(it) }
            val swarmMembers = aiSettings.getMembersForSwarms(selectedSwarmIds)
            val swarmMemberIds = swarmMembers.map { "swarm:${it.provider.id}:${it.model}" }.toSet()
            val uniqueDirectModelIds = directModelIds.filter { it !in swarmMemberIds }.toSet()

            val directModels = uniqueDirectModelIds.mapNotNull { modelId ->
                val parts = modelId.removePrefix("swarm:").split(":", limit = 2)
                val provider = AppService.findById(parts.getOrNull(0) ?: return@mapNotNull null) ?: return@mapNotNull null
                SwarmMember(provider, parts.getOrNull(1) ?: return@mapNotNull null)
            }

            val allModelMembers = swarmMembers + directModels
            val allModelIds = swarmMemberIds + uniqueDirectModelIds

            val reportLevelSystemPrompt = state.reportSystemPromptId
                ?.let { aiSettings.getSystemPromptById(it)?.prompt }
            val reportTasks = buildReportTasks(aiSettings, agents, allModelMembers, selectionParamsById, externalSystemPrompt, reportLevelSystemPrompt)

            _agentResults.value = emptyMap()
            appViewModel.updateUiState { it.copy(
                showGenericAgentSelection = false, showGenericReportsDialog = true,
                genericReportsProgress = 0, genericReportsTotal = reportTasks.size,
                genericReportsSelectedAgents = selectedAgentIds + allModelIds,
                currentReportId = null
            ) }

            val userMatch = AppViewModel.USER_TAG_REGEX.find(prompt)
            val rapportText = userMatch?.groupValues?.get(1)?.trim() ?: state.externalOpenHtml
            val aiPrompt = if (userMatch != null) prompt.replace(userMatch.value, "").trim() else prompt

            val report = ReportStorage.createReportAsync(
                context = context, title = title.ifBlank { "AI Report" },
                prompt = aiPrompt, agents = reportTasks.map { it.reportAgent },
                rapportText = rapportText, reportType = reportType, closeText = state.externalCloseHtml,
                imageBase64 = imageBase64, imageMime = imageMime,
                webSearchTool = state.reportWebSearchTool,
                reasoningEffort = state.reportReasoningEffort,
                knowledgeBaseIds = state.attachedKnowledgeBaseIds
            )
            val reportId = report.id
            val reportStartMs = System.currentTimeMillis()
            AppLog.i("Report", "→ start \"${title.ifBlank { "AI Report" }}\" (id=$reportId, ${reportTasks.size} agent(s))")

            withTracerTags(reportId = reportId, category = "Report") {
                appViewModel.updateUiState { it.copy(currentReportId = reportId) }

                kickOffIconGeneration(context, reportId, aiPrompt, aiSettings)

                val semaphore = Semaphore(AppViewModel.REPORT_CONCURRENCY_LIMIT)
                try {
                    coroutineScope {
                        reportTasks.map { task ->
                            async {
                                semaphore.withPermit {
                                    executeReportTask(context, reportId, aiPrompt, overrideParams, task, imageBase64, imageMime)
                                }
                                // Per-task auto-fire: kick off this
                                // agent's 3-tier icon chain the moment
                                // its primary call settles to SUCCESS,
                                // instead of waiting for every other
                                // agent in the report. The chain
                                // launches on viewModelScope, registers
                                // in reportIconsJobs, and runs
                                // independently — the outer async
                                // here returns as soon as the chain is
                                // scheduled, so awaitAll below still
                                // tracks only the primary calls.
                                val perModelOn = appViewModel.uiState.value.generalSettings.perModelIconGenEnabled
                                if (perModelOn) {
                                    val ra = ReportStorage.getReport(context, reportId)
                                        ?.agents?.firstOrNull { it.agentId == task.reportAgent.agentId }
                                    if (ra?.reportStatus == ReportStatus.SUCCESS && !ra.responseBody.isNullOrBlank()) {
                                        runReportIconsForAgent(context, reportId, ra, aiPrompt, aiSettings)
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                    val finalReport = ReportStorage.getReport(context, reportId)
                    val ok = finalReport?.agents?.count { it.reportStatus == ReportStatus.SUCCESS } ?: 0
                    val fail = finalReport?.agents?.count { it.reportStatus == ReportStatus.ERROR } ?: 0
                    AppLog.i("Report", "← end \"${title.ifBlank { "AI Report" }}\" ok=$ok fail=$fail in ${System.currentTimeMillis() - reportStartMs}ms")
                    if (reportRunningInBackground) {
                        reportRunningInBackground = false
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Report \"$title\" is ready", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                } finally {
                    // Reset the background flag on cancel paths too —
                    // without this, a Stop mid-run leaves the flag
                    // stuck at true and the next "background" toast
                    // fires spuriously when an unrelated job
                    // completes.
                    reportRunningInBackground = false
                }
            }
        }
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
    private fun kickOffIconGeneration(
        context: Context,
        reportId: String,
        promptText: String,
        aiSettings: Settings
    ) {
        // Master switch — when the user disabled per-report icon-gen
        // in Settings, skip the LLM call entirely. Existing on-disk
        // icon values stay intact.
        if (!appViewModel.uiState.value.generalSettings.iconGenEnabled) return
        val iconPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "internal" && it.name == "icon"
        } ?: return
        // Case-insensitive match so a user who has the agent registered
        // as "DeepSeek" still resolves the bundled prompt's
        // (lowercase-tail) "Deepseek" pin without manual editing. Same
        // safety against future bundled-vs-user casing drift.
        val rawAgent = aiSettings.agents.firstOrNull {
            it.name.equals(iconPrompt.agent, ignoreCase = true)
        } ?: return
        // The Agent stored in aiSettings.agents carries an empty apiKey
        // field — keys live on the Provider. Resolve the same way
        // buildReportTasks does so the dispatch sees a real key (and a
        // real model when the agent is pinned to a default-model alias).
        val agent = rawAgent.copy(
            apiKey = aiSettings.getEffectiveApiKeyForAgent(rawAgent),
            model = aiSettings.getEffectiveModelForAgent(rawAgent)
        )
        val resolved = iconPrompt.text.replace("@PROMPT@", promptText)
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            withTracerTags(reportId = reportId, category = "Report icon") {
                runCatching {
                    val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(agent)
                    val response = appViewModel.repository.analyzeWithAgent(
                        agent, "", resolved, AgentParameters(),
                        null, context, baseUrl
                    )
                    // Always end with exactly one emoji glyph:
                    //  - many emojis: pick the first one.
                    //  - emoji + extra text: strip the prose.
                    //  - 200 OK with no emoji in the body: fall back to 📝.
                    // Non-200 / network failures still take the error path.
                    if (response.error == null) {
                        val emoji = extractFirstEmoji(response.analysis) ?: "📝"
                        val tu = response.tokenUsage
                        val pricing = PricingCache.getPricing(context, agent.provider, agent.model)
                        val inT = tu?.inputTokens ?: 0
                        val outT = tu?.outputTokens ?: 0
                        val inC = inT * pricing.promptPrice
                        val outC = outT * pricing.completionPrice
                        ReportStorage.updateReportIcon(
                            context, reportId, emoji,
                            inputTokens = inT, outputTokens = outT,
                            inputCost = inC, outputCost = outC
                        )
                    } else {
                        ReportStorage.updateReportIconError(
                            context, reportId, response.error
                        )
                    }
                }.onFailure {
                    ReportStorage.updateReportIconError(
                        context, reportId,
                        it.message ?: "icon-gen failed"
                    )
                }
                appViewModel.updateUiState {
                    it.copy(iconRefreshTick = it.iconRefreshTick + 1)
                }
            }
        }
    }

    /** Background helper that resolves the bundled `internal/prompt_icon`
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
        if (!appViewModel.uiState.value.generalSettings.useInternalPromptsIcons) return
        if (prompt.name.isBlank()) return
        if (InternalPromptIconCache.get(prompt.name, prompt.title) != null) return
        // Atomically claim the slot; if another caller is already
        // working on the same (name, title) key, bail.
        if (!InternalPromptIconCache.markInFlight(prompt.name, prompt.title)) return

        val iconPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "internal" && it.name.equals("prompt_icon", ignoreCase = true)
        }
        if (iconPrompt == null) {
            AppLog.w("InternalPromptIcon", "internal/prompt_icon not configured — skipping")
            InternalPromptIconCache.clearInFlight(prompt.name, prompt.title)
            return
        }
        val rawAgent = aiSettings.agents.firstOrNull {
            it.name.equals(iconPrompt.agent, ignoreCase = true)
        }
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

        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            withTracerTags(category = "Internal prompt icon") {
                runCatching {
                    val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(agent)
                    val response = appViewModel.repository.analyzeWithAgent(
                        agent, "", resolved, AgentParameters(),
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
                            inputCost = inC, outputCost = outC
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
     *  [agentIconFanOutJobs] for per-agent runs. */
    private val internalPromptIconFanOutJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private fun internalPromptIconKey(prompt: InternalPrompt): String =
        prompt.name + "\u001F" + prompt.title

    /** Fan-out of `internal/prompt_icon` across user-picked [models]
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
        aiSettings: Settings
    ) {
        if (prompt.name.isBlank()) return
        val iconPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "internal" && it.name.equals("prompt_icon", ignoreCase = true)
        } ?: run {
            AppLog.w("InternalPromptIconAlt", "internal/prompt_icon not configured — skipping fan-out")
            return
        }
        val unique = models.distinctBy { "${it.provider.id}:${it.model}" }
        if (unique.isEmpty()) return
        val resolved = iconPrompt.text
            .replace("@NAME@", prompt.name)
            .replace("@TITLE@", prompt.title)
        val key = internalPromptIconKey(prompt)

        // Pre-populate Running rows so the Alternative icons screen
        // shows ⏳ for every pair the moment it opens.
        appViewModel.updateInternalPromptIconFanOut(key) {
            unique.map { IconCandidate.Running(it.provider, it.model) }
        }

        val outer = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            unique.forEach { item ->
                launch(Dispatchers.IO) {
                    withTracerTags(category = "Internal prompt icon (alt)") {
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
                                    prompt.name, prompt.title, inT, outT, inC, outC
                                )
                                appViewModel.settingsPrefs.updateUsageStatsAsync(
                                    item.provider, item.model, inT, outT, kind = "icon"
                                )
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
            responseText = captured.second
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
     *  `internal/translation_icon` prompt against its pinned agent
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
        if (!appViewModel.uiState.value.generalSettings.useInternalPromptsIcons) return
        if (language.isBlank()) return
        if (InternalPromptIconCache.get("translation_icon", language) != null) return
        if (!InternalPromptIconCache.markInFlight("translation_icon", language)) return

        val iconPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "internal" && it.name.equals("translation_icon", ignoreCase = true)
        }
        if (iconPrompt == null) {
            AppLog.w("TranslationIcon", "internal/translation_icon not configured — skipping")
            InternalPromptIconCache.clearInFlight("translation_icon", language)
            return
        }
        val rawAgent = aiSettings.agents.firstOrNull {
            it.name.equals(iconPrompt.agent, ignoreCase = true)
        }
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

        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            withTracerTags(category = "Translation icon") {
                runCatching {
                    val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(agent)
                    val response = appViewModel.repository.analyzeWithAgent(
                        agent, "", resolved, AgentParameters(),
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
                            inputCost = inC, outputCost = outC
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

    /** Fan-out of `internal/translation_icon` across user-picked
     *  [models] for one [language]. Mirrors
     *  [startInternalPromptIconFanOut] — same dedupe, throttle,
     *  cost-accumulation, and call-text capture rules. */
    fun startTranslationIconFanOut(
        context: Context,
        language: String,
        models: List<ReportModel>,
        aiSettings: Settings
    ) {
        if (language.isBlank()) return
        val iconPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "internal" && it.name.equals("translation_icon", ignoreCase = true)
        } ?: run {
            AppLog.w("TranslationIconAlt", "internal/translation_icon not configured — skipping fan-out")
            return
        }
        val unique = models.distinctBy { "${it.provider.id}:${it.model}" }
        if (unique.isEmpty()) return
        val resolved = iconPrompt.text.replace("@LANGUAGE@", language)
        val key = translationIconKey(language)

        appViewModel.updateInternalPromptIconFanOut(key) {
            unique.map { IconCandidate.Running(it.provider, it.model) }
        }

        val outer = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            unique.forEach { item ->
                launch(Dispatchers.IO) {
                    withTracerTags(category = "Translation icon (alt)") {
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
            responseText = captured.second
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
        val iconPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "internal" && it.name == "icon"
        } ?: return
        // Dedupe by "provider:model" so picking the same pair via two
        // different sources (e.g. an agent + a direct +Model) only
        // fires one API call.
        val unique = models.distinctBy { "${it.provider.id}:${it.model}" }
        if (unique.isEmpty()) return
        val resolved = iconPrompt.text.replace("@PROMPT@", promptText)
        // Pre-populate Running rows so the Alternative icons screen
        // shows ⏳ for every pair the moment the screen opens, before
        // any throttle permit is acquired.
        appViewModel.updateIconFanOut(reportId) {
            unique.map { IconCandidate.Running(it.provider, it.model) }
        }
        val outer = appViewModel.viewModelScope.launch(Dispatchers.IO) {
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
                            withTracerTags(reportId = reportId, category = "Report icon (alt)") {
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
        registerIconFanOutJob(reportId, outer)
    }

    /** Cancel any in-flight "Find alternative icons" fan-out for
     *  [reportId] and drop every candidate from the in-memory map.
     *  Costs already bumped on the Report by completed pair calls
     *  stay — additive cost bookkeeping is the whole point. Wired
     *  to the Restart button on the Alternative icons screen so the
     *  user can wipe the list and re-open the picker with a fresh
     *  model selection without losing what they've already paid for. */
    fun restartIconFanOut(reportId: String) {
        iconFanOutJobs.remove(reportId)?.cancel()
        appViewModel.clearIconFanOut(reportId)
    }

    /** Per-agent counterpart of [startIconFanOut]. Drives the Agent
     *  icon detail screen's "Find alternative icons" button: the user
     *  picks alternative models, and each one is asked to iconify
     *  THIS agent's (provider, model) answer to the report's prompt
     *  via the bundled internal/report_icon template (two
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
        val iconPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "internal" && it.name == "report_icon"
        } ?: run {
            AppLog.w("AgentIconAlt", "internal/report_icon prompt not found — skipping (agent=$agentId)")
            return
        }
        val unique = models.distinctBy { "${it.provider.id}:${it.model}" }
        if (unique.isEmpty()) return
        appViewModel.updateAgentIconFanOut(agentId) {
            unique.map { IconCandidate.Running(it.provider, it.model) }
        }
        val outer = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val report = ReportStorage.getReport(context, reportId) ?: return@launch
            val ra = report.agents.firstOrNull { it.agentId == agentId } ?: return@launch
            val reportPrompt = report.prompt
            val agentResponse = ra.responseBody.orEmpty()
            val resolved = iconPrompt.text
                .replace("@PROMPT@", reportPrompt)
                .replace("@RESPONSE@", agentResponse)
            unique.forEach { item ->
                launch {
                    val host = providerHost(item.provider)
                    val releaser = ProviderThrottle.acquire(host)
                    try {
                        withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                            withTracerTags(reportId = reportId, category = "Report icon (alt agent)") {
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
        registerAgentIconFanOutJob(reportId, agentId, outer)
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
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            ReportStorage.setReportAgentIconChoice(context, reportId, agentId, emoji)
            appViewModel.updateUiState {
                it.copy(iconRefreshTick = it.iconRefreshTick + 1)
            }
        }
    }

    /** Per-agent counterpart of [restartIconFanOut]. Wired to the
     *  Alternative icons screen's Restart button when the active flow
     *  is per-agent. */
    fun restartAgentIconFanOut(reportId: String, agentId: String) {
        agentIconFanOutJobs.remove(agentIconJobKey(reportId, agentId))?.cancel()
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
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            ReportStorage.setReportIconChoice(context, reportId, emoji, iconModel)
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
     *      the third turn = internal/report_icon_chat.text.
     *    Tier 2 — one-shot internal/report_icon template (@PROMPT@ +
     *      @RESPONSE@) against the agent's own (provider, model).
     *    Tier 3 — fixed bundled-agent (DeepSeek) running
     *      internal/report_icon_3th with @RESPONSE@ only.
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
     *  The job registers in [reportIconsJobs] under
     *  "$reportId|$agentId" so deleteReport's prefix sweep cancels
     *  it; a re-fire for the same agent (regenerate path) cancels
     *  the previous run. */
    fun runReportIconsForAgent(
        context: Context, reportId: String,
        ra: ReportAgent, reportPrompt: String, aiSettings: Settings
    ) {
        val chatPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "internal" && it.name == "report_icon_chat"
        }
        val tier2Prompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "internal" && it.name == "report_icon"
        }
        val tier3Prompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "internal" && it.name == "report_icon_3th"
        }
        if (chatPrompt == null && tier2Prompt == null && tier3Prompt == null) {
            AppLog.w("ReportIcons", "no icon prompts configured — skipping (agent=${ra.agentId})")
            return
        }
        val outer = appViewModel.viewModelScope.launch(Dispatchers.IO) {
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

            // Tier 1 — chat continuation.
            val tier1Emoji = chatPrompt?.let { p ->
                runTier1(context, reportId, agentProvider, ra, p, reportPrompt, agentResponse, aiSettings)
            }
            if (tier1Emoji != null) {
                commitChainResult(context, reportId, ra.agentId, tier1Emoji, winningTier = 1)
                return@launch
            }

            // Tier 2 — one-shot report_icon template.
            val tier2Emoji = tier2Prompt?.let { p ->
                runTier2(context, reportId, agentProvider, ra, p, reportPrompt, agentResponse, aiSettings)
            }
            if (tier2Emoji != null) {
                commitChainResult(context, reportId, ra.agentId, tier2Emoji, winningTier = 2)
                return@launch
            }

            // Tier 3 — fixed bundled-agent fallback.
            val tier3Emoji = tier3Prompt?.let { p ->
                runTier3(context, reportId, ra, p, agentResponse, aiSettings)
            }
            if (tier3Emoji != null) {
                commitChainResult(context, reportId, ra.agentId, tier3Emoji, winningTier = 3)
                return@launch
            }

            // All three tiers failed — final 📝 fallback.
            commitChainResult(context, reportId, ra.agentId, "📝", winningTier = null)
        }
        registerReportIconForAgentJob(reportId, ra.agentId, outer)
    }

    /** Tier 1 of [runReportIcons]: continue the conversation as a
     *  chat. Returns the extracted first emoji on success, null
     *  otherwise (network error, no emoji in the response). Costs +
     *  IconCallRecord are written regardless of emoji extraction
     *  success — the user paid for the call either way. */
    private suspend fun runTier1(
        context: Context, reportId: String, provider: AppService,
        ra: ReportAgent, chatPrompt: InternalPrompt,
        reportPrompt: String, agentResponse: String, aiSettings: Settings
    ): String? {
        val host = providerHost(provider)
        val releaser = ProviderThrottle.acquire(host)
        return try {
            withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                withTracerTags(reportId = reportId, category = "Report icons tier 1 (chat)") {
                    val started = System.currentTimeMillis()
                    runCatching {
                        val messages = listOf(
                            ChatMessage(role = "user", content = reportPrompt),
                            ChatMessage(role = "assistant", content = agentResponse),
                            ChatMessage(role = "user", content = chatPrompt.text)
                        )
                        val apiKey = aiSettings.getApiKey(provider)
                        val baseUrl = aiSettings.getEffectiveEndpointUrl(provider)
                        val responseText = appViewModel.repository.sendChat(
                            service = provider, apiKey = apiKey, model = ra.model,
                            messages = messages, params = ChatParameters(), baseUrl = baseUrl
                        )
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

    /** Tier 2 of [runReportIcons]: one-shot internal/report_icon
     *  template substitution against the agent's own (provider, model). */
    private suspend fun runTier2(
        context: Context, reportId: String, provider: AppService,
        ra: ReportAgent, tier2Prompt: InternalPrompt,
        reportPrompt: String, agentResponse: String, aiSettings: Settings
    ): String? {
        val host = providerHost(provider)
        val releaser = ProviderThrottle.acquire(host)
        return try {
            withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                withTracerTags(reportId = reportId, category = "Report icons tier 2 (one-shot)") {
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
                        val response = appViewModel.repository.analyzeWithAgent(
                            syntheticAgent, "", resolved, AgentParameters(),
                            null, context, baseUrl
                        )
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
     *  whichever Agent matches the report_icon_3th prompt's pinned
     *  agent name (case-insensitive). When the user has no such
     *  agent configured, this returns null instantly — no API call,
     *  no IconCallRecord — and the chain falls through to 📝. */
    private suspend fun runTier3(
        context: Context, reportId: String,
        ra: ReportAgent, tier3Prompt: InternalPrompt,
        agentResponse: String, aiSettings: Settings
    ): String? {
        val rawAgent = aiSettings.agents.firstOrNull {
            it.name.equals(tier3Prompt.agent, ignoreCase = true)
        } ?: run {
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
                withTracerTags(reportId = reportId, category = "Report icons tier 3 (fixed agent)") {
                    val started = System.currentTimeMillis()
                    runCatching {
                        val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(effectiveAgent)
                        val resolved = tier3Prompt.text.replace("@RESPONSE@", agentResponse)
                        val response = appViewModel.repository.analyzeWithAgent(
                            effectiveAgent, "", resolved, AgentParameters(),
                            null, context, baseUrl
                        )
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
        emoji: String, winningTier: Int?
    ) {
        ReportStorage.setReportAgentIconAndTier(
            context, reportId, agentId, emoji, winningTier
        )
        appViewModel.updateUiState {
            it.copy(iconRefreshTick = it.iconRefreshTick + 1)
        }
    }

    private fun buildReportTasks(
        aiSettings: Settings, agents: List<Agent>, modelMembers: List<SwarmMember>,
        selectionParamsById: Map<String, List<String>>, externalSystemPrompt: String?,
        /** Optional per-report system prompt picked on the model-selection
         *  screen. When non-null, wins over agent / flock / external; when
         *  null, the existing per-agent → per-flock → external resolution
         *  chain applies. */
        reportLevelSystemPrompt: String? = null
    ): List<ReportTask> {
        val agentTasks = agents.map { agent ->
            val ea = agent.copy(
                apiKey = aiSettings.getEffectiveApiKeyForAgent(agent),
                model = aiSettings.getEffectiveModelForAgent(agent)
            )
            val selParams = aiSettings.mergeParameters(selectionParamsById[agent.id] ?: emptyList())
            var params = selParams ?: aiSettings.resolveAgentParameters(agent)
            val spText = reportLevelSystemPrompt
                ?: resolveSystemPromptText(aiSettings, agent.systemPromptId, findFlockSystemPromptIdForAgent(aiSettings, agent.id))
                ?: externalSystemPrompt
            if (spText != null) params = params.copy(systemPrompt = spText)

            ReportTask(agent.id, ReportAgent(agent.id, agent.name, ea.provider.id, ea.model, ReportStatus.PENDING), ea, params)
        }

        val modelTasks = modelMembers.map { member ->
            val sid = "swarm:${member.provider.id}:${member.model}"
            val spText = reportLevelSystemPrompt
                ?: findSwarmSystemPromptIdForMember(aiSettings, member.provider, member.model)?.let { aiSettings.getSystemPromptById(it)?.prompt }
                ?: externalSystemPrompt
            var params = aiSettings.mergeParameters(selectionParamsById[sid] ?: emptyList()) ?: AgentParameters()
            if (spText != null) params = params.copy(systemPrompt = spText)

            ReportTask(sid,
                ReportAgent(sid, "${member.provider.id} / ${member.model}", member.provider.id, member.model, ReportStatus.PENDING),
                Agent(sid, "${member.provider.id} / ${member.model}", member.provider, member.model, aiSettings.getApiKey(member.provider)),
                params
            )
        }
        return agentTasks + modelTasks
    }

    private suspend fun executeReportTask(
        context: Context, reportId: String, aiPrompt: String, overrideParams: AgentParameters?, task: ReportTask,
        imageBase64: String? = null, imageMime: String? = null,
        // Skip the genericReportsProgress increment when re-running a
        // single agent on a finished report — the agent was already
        // counted as complete the first time around, so bumping the
        // counter again would push past total and break the progress
        // bar / completion-equality check.
        isRegeneration: Boolean = false
    ) {
        AppLog.d("Report", "→ task ${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} agent=${task.resultId}${if (isRegeneration) " (regen)" else ""}")
        ReportStorage.markAgentRunningAsync(context, reportId, task.resultId, aiPrompt)

        // Pull the report's attached KB ids so analyzeWithAgent can
        // do RAG retrieval on this turn. Cheap re-read; the
        // alternative (caching on the ReportTask) is more surface
        // area than payoff.
        val knowledgeBaseIds = ReportStorage.getReport(context, reportId)?.knowledgeBaseIds.orEmpty()
        val startTime = System.currentTimeMillis()
        val response = try {
            val baseUrl = appViewModel.uiState.value.aiSettings.getEffectiveEndpointUrlForAgent(task.runtimeAgent)
            appViewModel.repository.analyzeWithAgent(
                task.runtimeAgent,
                "",
                aiPrompt,
                task.resolvedParams,
                overrideParams,
                context,
                baseUrl,
                imageBase64,
                imageMime,
                knowledgeBaseIds = knowledgeBaseIds,
                aiSettings = appViewModel.uiState.value.aiSettings
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Honor structured cancellation (Stop / nav-away) instead of
            // persisting a fake error onto the agent row.
            throw e
        } catch (e: Exception) {
            // Cap the persisted error string — OutOfMemoryError /
            // StackOverflowError can carry kilobyte-sized messages
            // that bloat the report JSON file with no diagnostic
            // value beyond the first line.
            AnalysisResponse(service = task.runtimeAgent.provider, analysis = null,
                error = (e.message ?: "Unknown error").take(2000))
        }
        val durationMs = System.currentTimeMillis() - startTime
        val cost = calculateResponseCost(context, task.runtimeAgent.provider, task.runtimeAgent.model, response.tokenUsage)

        // Persist the terminal state under NonCancellable so a Stop /
        // navigate-away that arrives between the API return and this
        // disk write doesn't strand the agent row in RUNNING on disk.
        // The async helpers themselves marshal the I/O off-thread.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            if (response.isSuccess) {
                ReportStorage.markAgentSuccessAsync(context, reportId, task.resultId,
                    response.httpStatusCode ?: 200, response.httpHeaders, response.analysis,
                    response.tokenUsage, cost, response.citations, response.searchResults,
                    response.relatedQuestions, response.rawUsageJson, durationMs)
            } else {
                ReportStorage.markAgentErrorAsync(context, reportId, task.resultId,
                    response.httpStatusCode, response.error, response.httpHeaders, response.analysis, durationMs)
            }
        }

        if (response.error == null && response.tokenUsage != null) {
            val usage = response.tokenUsage
            appViewModel.settingsPrefs.updateUsageStatsAsync(task.runtimeAgent.provider, task.runtimeAgent.model,
                usage.inputTokens, usage.outputTokens, usage.totalTokens)
        }

        _agentResults.update { it + (task.resultId to response) }
        if (!isRegeneration) {
            appViewModel.updateUiState { state ->
                state.copy(genericReportsProgress = state.genericReportsProgress + 1)
            }
        }
        AppLog.d(
            "Report",
            "← task ${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} agent=${task.resultId} " +
                (if (response.isSuccess) "ok" else "err") +
                " ${durationMs}ms" +
                (response.tokenUsage?.let { " in=${it.inputTokens} out=${it.outputTokens}" } ?: "") +
                (cost?.let { " cost=${"%.5f".format(it)}" } ?: "")
        )
    }



    /**
     * Tear down the current finished-report state and pre-fill the selection screen with
     * the prompt + model list from a saved report so the user can edit which models run
     * before re-generating.
     */
    suspend fun prepareEditModels(context: Context, reportId: String) {
        val report = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) } ?: return
        val ai = appViewModel.uiState.value.aiSettings
        // Prefer an already-staged list (so the user comes back to whatever they were
        // editing), fall back to the report's persisted agent set.
        val rebuilt = appViewModel.uiState.value.stagedReportModels
            .ifEmpty { reportToModels(report, ai) }
        _agentResults.value = emptyMap()
        appViewModel.updateUiState { it.copy(
            showGenericReportsDialog = false,
            genericPromptTitle = report.title, genericPromptText = report.prompt,
            genericReportsProgress = 0, genericReportsTotal = 0,
            genericReportsSelectedAgents = emptySet(),
            currentReportId = null,
            pendingReportModels = rebuilt,
            editModeReportId = reportId
        ) }
    }

    /**
     * Save the user's edited model list as the staged set for a future Regenerate, then
     * restore the Result-phase state for `reportId` so the user lands back on the Report
     * Result screen. Called from the selection screen's "Update model list" button.
     */
    suspend fun stageModelListForRegenerate(context: Context, reportId: String, models: List<ReportModel>) {
        appViewModel.updateUiState { it.copy(
            stagedReportModels = models,
            pendingReportModels = emptyList(),
            editModeReportId = null
        ) }
        restoreCompletedReport(context, reportId)
    }

    /**
     * Update the saved report's prompt (and the matching UiState) without
     * triggering generation. Used by the Edit-prompt overlay — the user reviews the new
     * prompt on the result screen and re-runs via the Actions / Regenerate button when
     * they're ready. The model list and parameter set on disk are untouched.
     */
    suspend fun updateReportPrompt(context: Context, reportId: String, newPrompt: String) {
        withContext(Dispatchers.IO) {
            ReportStorage.updateReportPromptText(context, reportId, newPrompt)
            ReportStorage.bumpReportTimestamp(context, reportId)
        }
        appViewModel.updateUiState { it.copy(
            genericPromptText = newPrompt,
            hasPendingPromptChange = true
        ) }
    }

    /**
     * Update the report's title in place. Title is metadata only — no
     * outbound API call references it — so this never sets
     * [com.ai.model.UiState.hasPendingPromptChange] and the user does
     * not need to regenerate to see the new title applied.
     */
    suspend fun updateReportTitle(context: Context, reportId: String, newTitle: String) {
        withContext(Dispatchers.IO) {
            ReportStorage.updateReportTitle(context, reportId, newTitle)
            ReportStorage.bumpReportTimestamp(context, reportId)
        }
        appViewModel.updateUiState { it.copy(genericPromptTitle = newTitle) }
    }

    /**
     * Re-run a previously generated report end-to-end with the same prompt, agent set,
     * and parameter selections.
     */
    fun regenerateReport(context: Context, reportId: String) {
        // viewModelScope so navigating away mid-regenerate doesn't
        // cancel in-flight calls and persist them as ERROR. Same
        // bug class fixed in generateGenericReports.
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val report = ReportStorage.getReport(context, reportId) ?: return@launch
            val state = appViewModel.uiState.value
            val ai = state.aiSettings
            val staged = state.stagedReportModels
            // Prefer a staged list from Edit Models — falls back to the on-disk agent set.
            val rebuilt = if (staged.isNotEmpty()) staged else reportToModels(report, ai)
            val agentIds = rebuilt.filter { it.type == "agent" }.mapNotNull { it.agentId }.toSet()
            val swarmIds = rebuilt.filter { it.sourceType == "swarm" && it.type == "model" }.mapNotNull { it.sourceId }.toSet()
            val directIds = rebuilt.filter { it.sourceType == "model" }.map { "swarm:${it.provider.id}:${it.model}" }.toSet()
            val agents = agentIds.mapNotNull { ai.getAgentById(it) }
            val swarmMembers = ai.getMembersForSwarms(swarmIds)
            val swarmMemberIds = swarmMembers.map { "swarm:${it.provider.id}:${it.model}" }.toSet()
            val uniqueDirectIds = directIds.filter { it !in swarmMemberIds }.toSet()
            val directModels = uniqueDirectIds.mapNotNull { mid ->
                val parts = mid.removePrefix("swarm:").split(":", limit = 2)
                val provider = AppService.findById(parts.getOrNull(0) ?: return@mapNotNull null) ?: return@mapNotNull null
                SwarmMember(provider, parts.getOrNull(1) ?: return@mapNotNull null)
            }
            val reportLevelSystemPrompt = state.reportSystemPromptId
                ?.let { ai.getSystemPromptById(it)?.prompt }
            val tasks = buildReportTasks(ai, agents, swarmMembers + directModels, emptyMap(), state.externalSystemPrompt, reportLevelSystemPrompt)
            val existingIds = report.agents.map { it.agentId }.toSet()
            val newTasks = tasks.filter { it.resultId !in existingIds }
            val removedIds = existingIds - tasks.map { it.resultId }.toSet()

            // Decide what gets refreshed:
            //  - prompt or parameters changed → cascade everything: every
            //    agent, then every existing meta, then every translation.
            //  - only the model list changed → additive: add the new
            //    agents, drop the removed ones, leave everything else
            //    alone. Existing meta runs and translations still
            //    reference the old agent set; the user can re-pick
            //    individually if they want them refreshed.
            //  - nothing changed → no-op.
            val cascadeAll = state.hasPendingPromptChange || state.hasPendingParametersChange
            val tasksToRun = if (cascadeAll) tasks else newTasks
            if (tasksToRun.isEmpty() && removedIds.isEmpty() && !cascadeAll) return@launch

            withTracerTags(reportId = reportId, category = "Report regenerate") {
                // Re-run icon-gen only when the user edited the prompt.
                // A pure model-list / parameters regenerate keeps the
                // existing icon — the report's content didn't change.
                if (state.hasPendingPromptChange) {
                    ReportStorage.clearReportIcon(context, reportId)
                    appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
                    kickOffIconGeneration(context, reportId, report.prompt, ai)
                }
                for (id in removedIds) ReportStorage.removeAgent(context, reportId, id)
                if (newTasks.isNotEmpty()) ReportStorage.appendAgents(context, reportId, newTasks.map { it.reportAgent })
                // Reset existing-but-rerunning agents to PENDING so the
                // result row shows the spinning hourglass while the new
                // call is in flight. New agents are PENDING already via
                // appendAgents.
                for (task in tasksToRun) {
                    if (task.resultId in existingIds) ReportStorage.resetAgentToPending(context, reportId, task.resultId)
                }
                val tasksToRunIds = tasksToRun.map { it.resultId }.toSet()
                _agentResults.update { existing ->
                    existing.filterKeys { k -> k !in removedIds && k !in tasksToRunIds }
                }
                ReportStorage.bumpReportTimestamp(context, reportId)
                // The result-row list is driven by genericReportsSelectedAgents;
                // sync it with the post-mutation agent set so newly-added rows
                // appear (with the spinning hourglass via empty _agentResults)
                // and removed rows disappear. Reset progress to count only the
                // agents not being re-run — each task-to-run will bump progress
                // on completion (isRegeneration = false below) until it equals
                // total again. Without this, additive regenerate would silently
                // drop new rows from the UI.
                val finalAgentIds = tasks.map { it.resultId }.toSet()
                appViewModel.updateUiState { s -> s.copy(
                    stagedReportModels = emptyList(),
                    pendingReportModels = emptyList(),
                    hasPendingPromptChange = false,
                    hasPendingParametersChange = false,
                    genericReportsSelectedAgents = finalAgentIds,
                    genericReportsTotal = finalAgentIds.size,
                    genericReportsProgress = finalAgentIds.size - tasksToRunIds.size
                ) }

                if (tasksToRun.isNotEmpty()) {
                    val finalReport = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    val baseOverride = state.reportAdvancedParameters
                    val withWeb = if (finalReport.webSearchTool) (baseOverride ?: AgentParameters()).copy(webSearchTool = true) else baseOverride
                    val overrideParams = if (finalReport.reasoningEffort != null) (withWeb ?: AgentParameters()).copy(reasoningEffort = finalReport.reasoningEffort) else withWeb
                    val sem = Semaphore(AppViewModel.REPORT_CONCURRENCY_LIMIT)
                    coroutineScope {
                        tasksToRun.map { task ->
                            async {
                                sem.withPermit {
                                    executeReportTask(context, reportId, finalReport.prompt, overrideParams, task,
                                        finalReport.imageBase64, finalReport.imageMime, isRegeneration = false)
                                }
                                // Per-task auto-fire — same shape as
                                // generateGenericReports. Each agent's
                                // chain kicks off the moment its primary
                                // call settles to SUCCESS; the agent's
                                // own clearReportAgentIconState (at the
                                // top of runReportIconsForAgent) wipes
                                // any stale icon + iconCalls rows so
                                // the re-fire is clean.
                                val perModelOn = appViewModel.uiState.value.generalSettings.perModelIconGenEnabled
                                if (perModelOn) {
                                    val ra = ReportStorage.getReport(context, reportId)
                                        ?.agents?.firstOrNull { it.agentId == task.reportAgent.agentId }
                                    if (ra?.reportStatus == ReportStatus.SUCCESS && !ra.responseBody.isNullOrBlank()) {
                                        runReportIconsForAgent(context, reportId, ra, finalReport.prompt, ai)
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }

                // Cascade: prompt / params change invalidates every meta
                // result and every translation. Re-fire each meta kind
                // with its original picks (RERANK first because chat-
                // type META runs may consume it as Top-Ranked scope),
                // then re-fire translations sequentially (translation
                // jobs are mutually exclusive — startTranslation cancels
                // the previous one). Picks come from the persisted rows so
                // the user gets the same coverage they had before.
                if (cascadeAll) cascadeMetasAndTranslations(context, reportId)
            }
        }
    }

    private suspend fun cascadeMetasAndTranslations(
        context: Context, reportId: String
    ) {
        val all = SecondaryResultStorage.listForReport(context, reportId)
        if (all.isEmpty()) return

        // Group non-translate rows by their Meta prompt id so we can
        // re-run each one. Legacy rows (no metaPromptId) are skipped:
        // we don't have enough info to regenerate them under the new
        // CRUD-driven flow, so leaving them in place preserves their
        // history.
        val nonTranslate = all.filter { it.kind != SecondaryKind.TRANSLATE }
        val groups = nonTranslate
            .filter { !it.metaPromptId.isNullOrBlank() }
            .groupBy { it.metaPromptId!! }
        val metaPromptsLookup = appViewModel.uiState.value.aiSettings.internalPrompts.associateBy { it.id }
        for ((metaPromptId, rows) in groups) {
            val mp = metaPromptsLookup[metaPromptId] ?: continue
            val picks = rows
                .mapNotNull { meta ->
                    val provider = AppService.findById(meta.providerId) ?: return@mapNotNull null
                    provider to meta.model
                }
                .distinct()
            if (picks.isEmpty()) continue
            // Recover the scope the user originally ran with (persisted
            // on the row via secondaryScope). For TopRanked, only honour
            // it if the referenced rerank still exists on the (post-
            // cascade) report — otherwise the rerank itself may have
            // been wiped or be mid-rerun, and we fall back to AllReports
            // so the cascade doesn't reference a stale id. Manual scope
            // is keyed on agentIds which survive a prompt-only edit.
            val sampleScope = rows.firstOrNull { !it.secondaryScope.isNullOrBlank() }?.secondaryScope
            val decoded = SecondaryScope.decodeOrAllReports(sampleScope)
            val safeScope: SecondaryScope = when (decoded) {
                is SecondaryScope.TopRanked -> {
                    val rerankStillThere = SecondaryResultStorage.get(context, reportId, decoded.rerankResultId) != null
                    if (rerankStillThere) decoded else SecondaryScope.AllReports
                }
                else -> decoded
            }
            for (m in rows) SecondaryResultStorage.delete(context, reportId, m.id)
            runMetaPrompt(context, reportId, mp, picks, safeScope)?.join()
        }

        val byKind = all.groupBy { it.kind }

        val translates = byKind[SecondaryKind.TRANSLATE].orEmpty()
        if (translates.isNotEmpty()) {
            data class TranslateRun(val lang: String, val native: String, val provider: AppService, val model: String)
            val translateRuns = translates
                .mapNotNull { meta ->
                    val lang = meta.targetLanguage ?: return@mapNotNull null
                    val native = meta.targetLanguageNative ?: lang
                    val provider = AppService.findById(meta.providerId) ?: return@mapNotNull null
                    TranslateRun(lang, native, provider, meta.model)
                }
                .distinct()
            for (t in translates) SecondaryResultStorage.delete(context, reportId, t.id)
            for (run in translateRuns) {
                startTranslation(context, reportId, run.lang, run.native, run.provider, run.model).join()
            }
        }
    }

    /** Delete a report file and, if it's the one currently shown, dismiss the screen state. */
    fun deleteReport(context: Context, reportId: String) {
        val cleared = appViewModel.uiState.value.currentReportId == reportId
        // Cancel every in-flight coroutine attached to this report
        // BEFORE deleting it from disk. Otherwise:
        //   - Fan-out pair coroutines (up to N×(N-1) of them) keep
        //     consuming the per-provider throttle + Dispatchers.IO
        //     threads, racing to write to a SecondaryResultStorage row
        //     that's already gone. With a 33-model fan-out that's
        //     >1000 orphan coroutines, enough to starve the dispatcher
        //     so the next Generate button press queues forever.
        //   - The "Find alternative icons" fan-out has the same shape
        //     and gets the same treatment.
        //   - reportGenerationJob is the agent-fanout for the initial
        //     generation; if the user trashes mid-generation it needs
        //     to die too. We only cancel it when the deleted report
        //     is the currently-active one — a delete from the hub
        //     while a different report is generating mustn't kill the
        //     active run.
        if (cleared) reportGenerationJob?.cancel()
        val fanOutPrefix = "$reportId|"
        fanOutJobs.entries.filter { it.key.startsWith(fanOutPrefix) }.forEach { it.value.cancel() }
        iconFanOutJobs.remove(reportId)?.cancel()
        // reportIconsJobs is now keyed by "$reportId|$agentId" (one
        // job per per-agent chain) — sweep by prefix like the fan-out
        // pair jobs above.
        reportIconsJobs.entries.filter { it.key.startsWith(fanOutPrefix) }.forEach { it.value.cancel() }
        // Per-agent alt-icon jobs also live under the same reportId
        // prefix — collect and cancel them by agentId so their
        // candidate maps clear too. Same prefix key as the fan-out
        // pair jobs, scoped by a different ConcurrentHashMap.
        agentIconFanOutJobs.entries
            .filter { it.key.startsWith(fanOutPrefix) }
            .forEach { entry ->
                entry.value.cancel()
                // key format is "$reportId|$agentId"; split once and
                // drop the per-agent candidate map slot too.
                val agentId = entry.key.removePrefix(fanOutPrefix)
                appViewModel.clearAgentIconFanOut(agentId)
            }
        appViewModel.clearIconFanOut(reportId)
        ReportStorage.deleteReport(context, reportId)
        if (cleared) dismissGenericReportsDialog()
    }

    /** Toggle the persisted pinned flag for [reportId]. Pinned reports
     *  surface as their own section on the AI Reports hub. */
    fun toggleReportPinned(context: Context, reportId: String, scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val r = ReportStorage.getReport(context, reportId) ?: return@launch
            ReportStorage.setReportPinned(context, reportId, !r.pinned)
        }
    }

    /** Duplicate [reportId] (new id, " (Copy)" title suffix, every agent
     *  result preserved) and open the copy on the result screen so the
     *  user lands on the duplicate ready to edit / regenerate without
     *  losing the original. Returns false (silently) when the source
     *  report can't be loaded. */
    fun copyReport(context: Context, reportId: String, scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            val newId = withContext(Dispatchers.IO) { ReportStorage.copyReport(context, reportId) } ?: return@launch
            restoreCompletedReport(context, newId)
        }
    }

    fun clearPendingReportModels() {
        val cur = appViewModel.uiState.value
        if (cur.pendingReportModels.isEmpty()) return
        appViewModel.updateUiState { it.copy(pendingReportModels = emptyList()) }
    }

    /**
     * Open a previously generated report on the Reports result screen. Pulls the report
     * back out of ReportStorage, rebuilds _agentResults, and seeds the UiState fields the
     * Reports screen reads (currentReportId, genericReports* counters, prompt/title) so
     * the screen renders as if the report had just finished — agents listed, View / Share
     * / Browser / Email / Trace action row, etc.
     */
    suspend fun restoreCompletedReport(context: Context, reportId: String) {
        val report = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) } ?: return
        val rebuilt = report.agents.mapNotNull { ra ->
            val service = AppService.findById(ra.provider) ?: return@mapNotNull null
            ra.agentId to AnalysisResponse(
                service = service, analysis = ra.responseBody, error = ra.errorMessage,
                agentName = ra.agentName, tokenUsage = ra.tokenUsage,
                citations = ra.citations, searchResults = ra.searchResults,
                relatedQuestions = ra.relatedQuestions, rawUsageJson = ra.rawUsageJson,
                httpHeaders = ra.responseHeaders, httpStatusCode = ra.httpStatus
            )
        }.toMap()
        _agentResults.value = rebuilt
        appViewModel.updateUiState { it.copy(
            currentReportId = report.id,
            genericReportsTotal = report.agents.size,
            genericReportsProgress = report.agents.size,
            genericReportsSelectedAgents = report.agents.map { ra -> ra.agentId }.toSet(),
            genericPromptTitle = report.title,
            genericPromptText = report.prompt,
            showGenericReportsDialog = true
        ) }
    }

    /**
     * Rebuild _agentResults from a persisted ReportStorage entry. Called when the screen
     * comes back to a finished report whose in-memory results were lost (e.g. after Activity
     * recreation or process death) — UiState still has currentReportId and the
     * genericReports* counters, but our StateFlow restarted empty.
     */
    suspend fun hydrateAgentResultsFromStorage(context: Context, reportId: String) {
        // Merge with the in-memory map instead of skipping when ANY
        // agents are already populated. The previous early-return
        // left a half-finished restore on the screen — rows that
        // weren't in _agentResults stayed missing until a manual
        // refresh.
        val report = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) } ?: return
        // Only restore agents that have actually finished (SUCCESS / ERROR / STOPPED).
        // PENDING and RUNNING entries stay missing so the screen renders the spinning
        // hourglass instead of a stale ❌ during a fresh generation.
        val rebuilt = report.agents.filter {
            it.reportStatus == ReportStatus.SUCCESS ||
                it.reportStatus == ReportStatus.ERROR ||
                it.reportStatus == ReportStatus.STOPPED
        }.mapNotNull { ra ->
            val service = AppService.findById(ra.provider) ?: return@mapNotNull null
            ra.agentId to AnalysisResponse(
                service = service,
                analysis = ra.responseBody,
                error = ra.errorMessage,
                agentName = ra.agentName,
                tokenUsage = ra.tokenUsage,
                citations = ra.citations,
                searchResults = ra.searchResults,
                relatedQuestions = ra.relatedQuestions,
                rawUsageJson = ra.rawUsageJson,
                httpHeaders = ra.responseHeaders,
                httpStatusCode = ra.httpStatus
            )
        }.toMap()
        if (rebuilt.isNotEmpty()) {
            // Merge: prefer in-memory entries over disk so a fresh
            // success that hasn't been written yet isn't overwritten
            // by a stale RUNNING agent's still-on-disk state. Rows
            // missing from memory get the rebuilt entry.
            val merged = rebuilt + _agentResults.value
            _agentResults.value = merged
        }
    }

    fun dismissGenericReportsDialog() {
        // The report job's withTracerTags block restores tags on its
        // own when the job ends or is cancelled — no manual clear here.
        _agentResults.value = emptyMap()
        appViewModel.updateUiState { it.copy(
            showGenericReportsDialog = false, genericPromptTitle = "", genericPromptText = "",
            genericReportsProgress = 0, genericReportsTotal = 0,
            genericReportsSelectedAgents = emptySet(),
            currentReportId = null, reportAdvancedParameters = null,
            reportSystemPromptId = null,
            stagedReportModels = emptyList(), editModeReportId = null,
            pendingReportModels = emptyList(),
            hasPendingPromptChange = false, hasPendingParametersChange = false
        ) }
    }

    fun continueReportInBackground() {
        reportRunningInBackground = true
        appViewModel.updateUiState { it.copy(showGenericReportsDialog = false) }
    }

    fun cancel() {
        reportGenerationJob?.cancel()
        reportGenerationJob = null
        // Cancellation triggers the report job's finally inside
        // withTracerTags, which restores the previous (reportId,
        // category) — no manual clear here.
    }

    // ===== Meta prompt results =====

    /** Kick off a Rerank or Summarize run for [reportId] across [picks]
     *  (provider/model pairs). One [SecondaryResult] per pick is persisted
     *  independently — multi-model picks produce N separate viewable
     *  results. Each call launches its own coroutine; multiple batches
     *  can run concurrently, so a second click while a first is still
     *  in flight does NOT cancel the earlier one. UiState's
     *  [com.ai.viewmodel.UiState.activeSecondaryBatches] counter is
     *  bumped on entry and decremented in a finally block so the Meta
     *  screen's hourglass / poll loop reflects "anything running" no
     *  matter how many overlap. */
    /** Rerank the current report's responses using a locally-installed
     *  MediaPipe TextEmbedder. Embed the prompt + each successful
     *  agent response, score by cosine similarity to the prompt, and
     *  emit the same JSON shape the chat-model / Cohere rerank flows
     *  produce so downstream code (Top-Ranked scope, HTML export,
     *  detail screen) keeps working unchanged. SecondaryResult is
     *  saved with providerId="LOCAL" so cost / usage rows stay
     *  separate from remote provider activity. */
    fun runLocalRerank(
        context: Context,
        reportId: String,
        modelName: String
    ): Job {
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        return appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                withTracerTags(reportId = reportId, category = "Report rerank (local)") {
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    val responses = report.agents
                        .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                        .map { it.responseBody!! }
                    if (responses.isEmpty()) return@withTracerTags
                    val agentName = "Local / $modelName"
                    val placeholder = SecondaryResultStorage.create(context, reportId, SecondaryKind.RERANK, "LOCAL", modelName, agentName)
                    ReportStorage.bumpReportTimestamp(context, reportId)

                    val started = System.currentTimeMillis()
                    val queryVec = com.ai.data.local.LocalEmbedder.embed(context, modelName, listOf(report.prompt))?.firstOrNull()
                    val docVecs = com.ai.data.local.LocalEmbedder.embed(context, modelName, responses)
                    val durationMs = System.currentTimeMillis() - started

                    if (queryVec == null || docVecs == null) {
                        SecondaryResultStorage.save(context, placeholder.copy(
                            errorMessage = "Local embedder failed — check that $modelName is installed (Housekeeping → Local LiteRT models).",
                            durationMs = durationMs
                        ))
                        return@withTracerTags
                    }

                    // Cosine score per doc, descending. Scores rescaled
                    // 0-100 to match the chat-model rerank output.
                    val scored = docVecs.mapIndexed { idx, vec ->
                        val sim = com.ai.data.EmbeddingsStore.cosine(queryVec, vec)
                        Triple(idx + 1, sim, ((sim.coerceIn(-1.0, 1.0) + 1.0) * 50.0).toInt().coerceIn(0, 100))
                    }.sortedByDescending { it.second }

                    val arr = com.google.gson.JsonArray()
                    scored.forEachIndexed { rank, (originalId, sim, score) ->
                        arr.add(com.google.gson.JsonObject().apply {
                            addProperty("id", originalId)
                            addProperty("rank", rank + 1)
                            addProperty("score", score)
                            addProperty("reason", "Cosine similarity: %.4f".format(sim))
                        })
                    }
                    SecondaryResultStorage.save(context, placeholder.copy(
                        content = arr.toString(),
                        errorMessage = null,
                        durationMs = durationMs
                    ))
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
    }

    /** Single-pick Rerank runner exposed to the report action row. For
     *  the synthetic LOCAL provider this delegates to [runLocalRerank];
     *  for any other provider it resolves the `rerank` Internal prompt,
     *  builds the @RESULTS@ block from the report's successful agent
     *  responses, and dispatches through [executeSecondaryTask] with
     *  [SecondaryKind.RERANK]. The dispatch already routes RERANK-typed
     *  models (Cohere rerank-v3.5 etc.) to the dedicated rerank API and
     *  routes chat models through the standard analyse path. */
    fun runRerank(
        context: Context,
        reportId: String,
        pick: Pair<AppService, String>
    ): Job? {
        val (provider, model) = pick
        AppLog.i("Rerank", "→ start report=$reportId via ${provider.id}/$model")
        if (provider.id == AppService.LOCAL.id) {
            return runLocalRerank(context, reportId, model)
        }
        val aiSettings = appViewModel.uiState.value.aiSettings
        val rerankPrompt = aiSettings.getInternalPromptByName("rerank")
            ?: return null
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        return appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                withTracerTags(reportId = reportId, category = "Report rerank") {
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    val successfulCount = report.agents.count {
                        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                    }
                    if (successfulCount == 0) return@withTracerTags
                    val resultsBlock = buildResultsBlock(report)
                    val resolvedPrompt = resolveSecondaryPrompt(
                        rerankPrompt.text, question = report.prompt, results = resultsBlock,
                        count = successfulCount, title = report.title
                    )
                    executeSecondaryTask(
                        context, reportId, SecondaryKind.RERANK, rerankPrompt,
                        provider, model, resolvedPrompt, aiSettings, report
                    )
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
    }

    /** Fan-out Meta runner: for each successful report-model
     *  (the "answerer") and each source model in [scopeChoice], runs
     *  the prompt with `@RESPONSE@` substituted by the source's
     *  response body. Self-pairs are skipped. Always runs on the
     *  Original language; fan out does not fan out to translations.
     *
     *  Persists one [SecondaryResult] per (answerer, source) with
     *  kind=META and fanOutSourceAgentId=source.agentId so the result
     *  drill-in can group by answerer then by source.
     */
    fun runFanOutPrompt(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        scopeChoice: SecondaryScope = SecondaryScope.AllReports,
        /** Subset of report-agent ids that should act as "answerers"
         *  (the model receiving the prompt with @RESPONSE@ filled in).
         *  Null = use every successful agent — preserves the pre-feature
         *  default for callers that haven't been updated. The fan-out
         *  confirmation screen passes the user's checked Responder set
         *  so picking 2 responders × 3 initiators yields exactly the
         *  6-minus-self pairs the user expects, instead of always
         *  fanning out every successful agent on the answerer side. */
        responderAgentIds: Set<String>? = null
    ): Job? {
        // Dedupe against an already-running fan out for this
        // (report, metaPrompt) — a UI double-tap on the launch
        // button or a second caller via a separate path would
        // otherwise create a parallel batch with its own
        // placeholders, doubling pairs and cost.
        fanOutJobs[fanOutJobKey(reportId, metaPrompt.id)]?.let { existing ->
            if (existing.isActive) return existing
        }
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        val fanOutStartMs = System.currentTimeMillis()
        val job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val cat = "Report meta: ${metaPrompt.name}"
            try {
                withTracerTags(reportId = reportId, category = cat) {
                    val state = appViewModel.uiState.value
                    val aiSettings = state.aiSettings
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    val successful = report.agents.filter {
                        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                    }
                    if (successful.size < 2) return@withTracerTags
                    AppLog.i("FanOut", "→ start \"${metaPrompt.name}\" (report=$reportId, ${successful.size} successful agents)")
                    val sources = when (scopeChoice) {
                        SecondaryScope.AllReports -> successful
                        is SecondaryScope.TopRanked -> {
                            val rerank = SecondaryResultStorage.get(context, reportId, scopeChoice.rerankResultId)
                            val topIds = extractTopRankedIds(rerank?.content, scopeChoice.count)
                            if (topIds.isNullOrEmpty()) successful
                            else topIds.mapNotNull { idx -> successful.getOrNull(idx - 1) }
                        }
                        is SecondaryScope.Manual -> successful.filter { it.agentId in scopeChoice.agentIds }
                    }
                    if (sources.isEmpty()) return@withTracerTags
                    // Apply the per-side Responder selection (when the
                    // caller passed one). Null falls back to "every
                    // successful agent" — the pre-feature default that
                    // other call sites (e.g. rerunFanOutPlaceholders)
                    // still rely on.
                    val answerers = if (responderAgentIds == null) successful
                        else successful.filter { it.agentId in responderAgentIds }
                    if (answerers.isEmpty()) return@withTracerTags
                    // Pre-create every (answerer, source) placeholder
                    // up-front so the Report Result screen's fan out
                    // summary row and the fan out detail screen's L1/L2
                    // counts read the *full* expected work immediately
                    // (e.g. "30 pairs · 30 pending" for 6×5) and tick
                    // down as calls complete — instead of waking up
                    // with "6 pairs · 6 pending" because only the first
                    // source per answerer had been seeded.
                    data class PendingPair(val answerer: ReportAgent, val source: ReportAgent, val placeholder: SecondaryResult)
                    val pending = mutableListOf<PendingPair>()
                    for (answerer in answerers) {
                        val provider = AppService.findById(answerer.provider) ?: continue
                        for (source in sources) {
                            if (source.agentId == answerer.agentId) continue
                            val agentName = "${provider.id} / ${answerer.model}"
                            val placeholder = SecondaryResultStorage.create(
                                context, reportId, SecondaryKind.META, provider.id, answerer.model, agentName
                            ) {
                                it.copy(
                                    metaPromptId = metaPrompt.id,
                                    metaPromptName = metaPrompt.name,
                                    fanOutSourceAgentId = source.agentId
                                )
                            }
                            pending.add(PendingPair(answerer, source, placeholder))
                        }
                    }
                    // Launch one coroutine per pair. Per-provider
                    // concurrency + per-minute rate are enforced through
                    // ProviderThrottle, but we acquire the permit here
                    // (not inside the OkHttp interceptor) so the UI's
                    // queued / running distinction lines up with the
                    // throttle state:
                    //   - pair enters async, hasn't called acquire yet
                    //     → not in runningFanOutPairs → reads as "queued"
                    //   - acquire returns (permit + per-minute slot held)
                    //     → flip to runningFanOutPairs → reads as "running"
                    // The OkHttp interceptor sees permitPreAcquired=true
                    // on the worker (propagated via TagPropagatingExecutor)
                    // and skips its own acquire — no double-counting.
                    // Cap concurrent in-flight pair coroutines to prevent
                    // Dispatchers.IO starvation. ProviderThrottle.acquire
                    // is blocking (java.util.concurrent.Semaphore.acquire)
                    // — on a 90-pair fan-out with per-host concurrent limit
                    // 3, 87 pairs would otherwise pin an IO thread each in
                    // the blocking acquire(). Dispatchers.IO defaults to
                    // 64 threads; once pinned, new coroutines (including
                    // the next generateGenericReports call) can't get a
                    // thread → the app appears frozen and the user "can't
                    // create new AI reports until they restart". The outer
                    // suspending semaphore here only permits 8 pairs into
                    // the blocking-acquire region at a time; the rest are
                    // suspended via withPermit (releasing their IO thread)
                    // until a slot frees up.
                    val pairThreadCap = kotlinx.coroutines.sync.Semaphore(8)
                    coroutineScope {
                        pending.map { item ->
                            // CoroutineStart.LAZY so the async body
                            // doesn't run until we've registered the
                            // Deferred in fanOutPairJobs below. Without
                            // LAZY there's a microsecond window between
                            // `async {}` returning and the registration
                            // line where the pair is mid-flight but
                            // can't be looked up by deleteFanOutModel,
                            // letting it slip past cancellation and
                            // either land a result on a row that's
                            // about to be deleted (silent drop) or
                            // worse, burn the API call entirely.
                            val deferred = async(start = CoroutineStart.LAZY) {
                                pairThreadCap.withPermit {
                                val provider = AppService.findById(item.answerer.provider) ?: return@async
                                val host = providerHost(provider)
                                AppLog.d("FanOut", "queued pair ans=${item.answerer.agentId} src=${item.source.agentId} ${provider.id}/${item.answerer.model}")
                                val releaser = ProviderThrottle.acquire(host)
                                try {
                                    // Bail if the user deleted this pair (via the
                                    // L2 trash icon or deleteFanOutModel) while
                                    // we were queued on the throttle. Without
                                    // this, executeSecondaryTask still fires the
                                    // HTTP call and the saveIfStillPresent check
                                    // below would suppress the disk write — but
                                    // we'd still burn the API call and the
                                    // tokens. Checking here skips the call too.
                                    if (!SecondaryResultStorage.exists(context, reportId, item.placeholder.id)) {
                                        AppLog.d("FanOut", "skip pair ${item.placeholder.id} — deleted before launch")
                                        return@async
                                    }
                                    withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                                        appViewModel.updateRunningFanOutPairs { it + item.placeholder.id }
                                        val pairStart = System.currentTimeMillis()
                                        AppLog.d("FanOut", "→ pair ans=${item.answerer.agentId} src=${item.source.agentId} ${provider.id}/${item.answerer.model}")
                                        try {
                                            val resolvedBase = resolveSecondaryPrompt(
                                                metaPrompt.text,
                                                question = report.prompt,
                                                results = "",
                                                count = sources.size,
                                                title = report.title
                                            )
                                            val resolved = resolvedBase.replace("@RESPONSE@", item.source.responseBody ?: "")
                                            executeSecondaryTask(
                                                context, reportId, SecondaryKind.META, metaPrompt,
                                                provider, item.answerer.model, resolved, aiSettings, report,
                                                fanOutSourceAgentId = item.source.agentId,
                                                existingPlaceholder = item.placeholder
                                            )
                                        } finally {
                                            appViewModel.updateRunningFanOutPairs { it - item.placeholder.id }
                                            AppLog.d("FanOut", "← pair ans=${item.answerer.agentId} src=${item.source.agentId} ${System.currentTimeMillis() - pairStart}ms")
                                        }
                                    }
                                } finally {
                                    releaser.release()
                                }
                                } // pairThreadCap.withPermit
                            }
                            // Register the per-pair Job so deleteFanOutModel /
                            // rerunCompleteFanOut can target it for cancelAndJoin
                            // before deleting the row, closing the "result lands
                            // after delete and is silently dropped" race.
                            // Registration happens BEFORE start() so a concurrent
                            // delete can always find the Job.
                            fanOutPairJobs[item.placeholder.id] = deferred
                            deferred.invokeOnCompletion {
                                fanOutPairJobs.remove(item.placeholder.id, deferred)
                            }
                            deferred.start()
                            deferred
                        }.awaitAll()
                    }
                    AppLog.i("FanOut", "← end \"${metaPrompt.name}\" (${pending.size} pairs in ${System.currentTimeMillis() - fanOutStartMs}ms)")
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
        registerFanOutJob(reportId, metaPrompt.id, job)
        return job
    }

    /** Re-launch a list of fan-out pair placeholders. Re-uses the per-
     *  provider semaphore + running-pair bookkeeping pattern from
     *  [runFanOutPrompt], so the L1 progress bar / stats keep
     *  reflecting "running" once a permit is held and "queued" while a
     *  pair is waiting. The caller has already cleared each
     *  placeholder's content/errorMessage on disk (so the row reads as
     *  pending again). */
    private fun rerunFanOutPlaceholders(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        placeholders: List<SecondaryResult>
    ): Job? {
        if (placeholders.isEmpty()) return null
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        val job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val cat = "Report meta: ${metaPrompt.name}"
            try {
                withTracerTags(reportId = reportId, category = cat) {
                    val state = appViewModel.uiState.value
                    val aiSettings = state.aiSettings
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    val successful = report.agents.filter {
                        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                    }
                    val sourceCount = successful.size
                    // Per-pair pre-acquire mirrors runFanOutPrompt so the
                    // queued / running flip on the UI lines up with the
                    // permit being held. See that function for the full
                    // explanation. Cap concurrent in-flight pair coroutines
                    // via a suspending semaphore — see runFanOutPrompt for
                    // why this prevents Dispatchers.IO starvation.
                    val pairThreadCap = kotlinx.coroutines.sync.Semaphore(8)
                    coroutineScope {
                        placeholders.map { ph ->
                            // CoroutineStart.LAZY mirrors runFanOutPrompt
                            // — see the comment there for why the start
                            // is gated on the post-registration .start()
                            // call below.
                            val deferred = async(start = CoroutineStart.LAZY) {
                                pairThreadCap.withPermit {
                                val provider = AppService.findById(ph.providerId) ?: return@async
                                val source = successful.firstOrNull { it.agentId == ph.fanOutSourceAgentId }
                                    ?: return@async
                                val host = providerHost(provider)
                                AppLog.d("FanOut", "queued rerun ph=${ph.id} src=${source.agentId} ${provider.id}/${ph.model}")
                                val releaser = ProviderThrottle.acquire(host)
                                try {
                                    if (!SecondaryResultStorage.exists(context, reportId, ph.id)) {
                                        AppLog.d("FanOut", "skip rerun ${ph.id} — deleted before launch")
                                        return@async
                                    }
                                    withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                                        appViewModel.updateRunningFanOutPairs { it + ph.id }
                                        val rerunStart = System.currentTimeMillis()
                                        AppLog.d("FanOut", "→ rerun pair ph=${ph.id} src=${source.agentId} ${provider.id}/${ph.model}")
                                        try {
                                            val resolvedBase = resolveSecondaryPrompt(
                                                metaPrompt.text,
                                                question = report.prompt,
                                                results = "",
                                                count = sourceCount,
                                                title = report.title
                                            )
                                            val resolved = resolvedBase.replace("@RESPONSE@", source.responseBody ?: "")
                                            executeSecondaryTask(
                                                context, reportId, SecondaryKind.META, metaPrompt,
                                                provider, ph.model, resolved, aiSettings, report,
                                                fanOutSourceAgentId = source.agentId,
                                                existingPlaceholder = ph
                                            )
                                        } finally {
                                            appViewModel.updateRunningFanOutPairs { it - ph.id }
                                            AppLog.d("FanOut", "← rerun pair ph=${ph.id} ${System.currentTimeMillis() - rerunStart}ms")
                                        }
                                    }
                                } finally {
                                    releaser.release()
                                }
                                } // pairThreadCap.withPermit
                            }
                            // Per-pair Job registration mirrors runFanOutPrompt
                            // — see the comment there for the cancel-on-delete
                            // race the registration closes.
                            fanOutPairJobs[ph.id] = deferred
                            deferred.invokeOnCompletion {
                                fanOutPairJobs.remove(ph.id, deferred)
                            }
                            deferred.start()
                            deferred
                        }.awaitAll()
                    }
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
        registerFanOutJob(reportId, metaPrompt.id, job)
        return job
    }

    /** Reset the given rows on disk so they look queued again (clears
     *  content / errorMessage / token usage / costs / duration), then
     *  feed them into [rerunFanOutPlaceholders]. The cleared rows reuse
     *  their original placeholder ids so the UI doesn't see them
     *  vanish-and-reappear. */
    private fun resetAndRelaunch(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        rows: List<SecondaryResult>
    ): Job? {
        if (rows.isEmpty()) return null
        val reset = rows.map { r ->
            r.copy(
                content = null,
                errorMessage = null,
                tokenUsage = null,
                inputCost = null,
                outputCost = null,
                durationMs = null
            ).also { SecondaryResultStorage.save(context, it) }
        }
        return rerunFanOutPlaceholders(context, reportId, metaPrompt, reset)
    }

    /** Auto-resume every interrupted Translation, Fan-out, and
     *  single-call Meta/Rerank/Moderation run on the report. Fires
     *  on report open (replaces the previous mark-as-errored sweep).
     *
     *  - **Translation**: groups disk rows by `translationRunId`; for
     *    each runId that isn't already in [_translationRuns], calls
     *    [startMissingTranslations] which dispatches every expected
     *    item (prompt + successful agents + meta secondaries) that
     *    doesn't yet have a row.
     *  - **Fan-out**: groups stale fan-out placeholder rows by
     *    `metaPromptId`; for each one whose [com.ai.model.InternalPrompt]
     *    still exists in settings, calls [resumeStaleFanOutPairs].
     *  - **Single Meta/Rerank/Moderation**: walks stale rows where
     *    `fanOutSourceAgentId == null && fanInOf == null &&
     *    translationRunId == null` and re-issues each via
     *    [resumeStaleMetaPlaceholder] using the placeholder's persisted
     *    `metaPromptId`, `providerId`, `model`, and `secondaryScope`.
     *  - **Unrecoverable** (legacy rows missing fields, prompts since
     *    deleted, fan-in / model-fan-in rows whose substitution data
     *    can't be reconstructed): falls back to the existing
     *    "Interrupted by app restart" marker so the row renders ❌
     *    and the user can manually retry.
     *
     *  Only rows interrupted by app death (content blank, errorMessage
     *  null, durationMs null) are touched — previously-errored rows are
     *  left as-is. Translation runs use the same `translationRunId`
     *  that was assigned at start, so the resume queues under the
     *  original group on the result page. */
    fun resumeStaleRunsForReport(
        context: Context,
        reportId: String
    ): Job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
        val running = appViewModel.runningFanOutPairs.value
        val activeTranslationRunIds = _translationRuns.value.keys
        val rows = SecondaryResultStorage.listForReport(context, reportId)
        val aiSettings = appViewModel.uiState.value.aiSettings

        // 1. Translation: walk every distinct translationRunId on disk
        //    that isn't actively running in memory and queue the
        //    missing items. startMissingTranslations needs at least one
        //    anchor row for the run; any runId with zero rows on disk
        //    can't be resumed (no provider/model/language to read).
        val translationRunIds = rows
            .filter { it.kind == SecondaryKind.TRANSLATE && it.translationRunId != null }
            .map { it.translationRunId!! }
            .distinct()
        translationRunIds.forEach { runId ->
            if (runId in activeTranslationRunIds) return@forEach
            startMissingTranslations(context, reportId, runId)
        }

        // 2. Fan-out pairs: group stale per-pair rows by metaPromptId
        //    and dispatch resumeStaleFanOutPairs once per prompt.
        //    resumeStaleFanOutPairs is itself a no-op when the in-flight
        //    set already covers the run (defence-in-depth dedupe).
        val stalePairsByPromptId = rows
            .filter { it.kind == SecondaryKind.META &&
                it.fanOutSourceAgentId != null &&
                it.content.isNullOrBlank() &&
                it.errorMessage == null &&
                it.durationMs == null &&
                it.id !in running }
            .groupBy { it.metaPromptId }
        stalePairsByPromptId.forEach { (promptId, pairs) ->
            val prompt = promptId?.let { pid ->
                aiSettings.internalPrompts.firstOrNull { it.id == pid }
            }
            if (prompt != null) {
                resumeStaleFanOutPairs(context, reportId, prompt)
            } else {
                // Prompt deleted: mark each as ❌ so the row stops
                // spinning. The user can drop the row and re-pick a
                // prompt from scratch.
                pairs.forEach { markRowAsInterrupted(context, reportId, it.id, "Interrupted — fan-out prompt deleted") }
            }
        }

        // 3. Single-call Meta/Rerank/Moderation: re-issue each stale
        //    placeholder via executeSecondaryTask. Fan-in single
        //    (fanInOf != null) and model-fan-in (scopeProviderId != null)
        //    rows are skipped here — they go to the legacy mark-as-❌
        //    branch below since their substitution inputs aren't
        //    derivable from the placeholder alone.
        val staleSingleMeta = rows.filter {
            it.kind != SecondaryKind.TRANSLATE &&
                it.content.isNullOrBlank() &&
                it.errorMessage == null &&
                it.durationMs == null &&
                it.fanOutSourceAgentId == null &&
                it.fanInOf == null &&
                it.scopeProviderId == null &&
                it.scopeModel == null &&
                it.translationRunId == null &&
                it.id !in running &&
                it.metaPromptId != null &&
                aiSettings.internalPrompts.any { p -> p.id == it.metaPromptId } &&
                AppService.findById(it.providerId) != null
        }
        staleSingleMeta.forEach { row ->
            resumeStaleMetaPlaceholder(context, reportId, row)
        }

        // 4. Legacy fallback: any other stale row we can't reconstruct
        //    (fan-in single, model-fan-in, deleted prompt for a non-
        //    fan-out single meta, deleted provider) gets the honest ❌
        //    so it stops spinning.
        val handledIds = staleSingleMeta.map { it.id }.toSet() +
            stalePairsByPromptId.values.flatten().map { it.id }.toSet()
        rows.forEach { row ->
            if (row.errorMessage != null) return@forEach
            if (!row.content.isNullOrBlank()) return@forEach
            if (row.durationMs != null) return@forEach
            if (row.id in running) return@forEach
            if (row.id in handledIds) return@forEach
            // Translation rows in an active or newly-resumed run are
            // covered by startMissingTranslations; skip them here.
            if (row.kind == SecondaryKind.TRANSLATE &&
                row.translationRunId != null &&
                (row.translationRunId in activeTranslationRunIds ||
                    row.translationRunId in translationRunIds)) return@forEach
            // Anything still standing here is unrecoverable — mark ❌.
            markRowAsInterrupted(context, reportId, row.id, "Interrupted by app restart")
        }
    }

    /** TOCTOU-safe re-read + save pair that flips a stuck placeholder
     *  into a terminal errored row. Used by [resumeStaleRunsForReport]'s
     *  fallback branch for rows that can't be reconstructed (legacy
     *  rows missing fields, prompts since deleted, fan-in single
     *  rows). Re-reads the row right before saving so an in-flight
     *  completion landing between our list scan and the save isn't
     *  clobbered. */
    private fun markRowAsInterrupted(
        context: Context,
        reportId: String,
        rowId: String,
        message: String
    ) {
        val current = SecondaryResultStorage.get(context, reportId, rowId) ?: return
        if (current.errorMessage != null) return
        if (!current.content.isNullOrBlank()) return
        if (current.durationMs != null) return
        SecondaryResultStorage.save(context, current.copy(
            errorMessage = message,
            durationMs = 0
        ))
    }

    /** Re-issue a single interrupted META / RERANK / MODERATION
     *  placeholder. Looks up the [com.ai.model.InternalPrompt] by
     *  [SecondaryResult.metaPromptId] and the [AppService] by
     *  [SecondaryResult.providerId], decodes the persisted
     *  [SecondaryResult.secondaryScope], rebuilds the resolved prompt
     *  from the current report state, and calls [executeSecondaryTask]
     *  with the same placeholder so the in-place row transitions
     *  from ⏳ to ✅/❌ rather than being replaced by a fresh row. */
    private fun resumeStaleMetaPlaceholder(
        context: Context,
        reportId: String,
        placeholder: SecondaryResult
    ): Job? {
        if (!resumingMetaIds.add(placeholder.id)) return null
        val promptId = placeholder.metaPromptId ?: run {
            resumingMetaIds.remove(placeholder.id); return null
        }
        val state = appViewModel.uiState.value
        val aiSettings = state.aiSettings
        val metaPrompt = aiSettings.internalPrompts.firstOrNull { it.id == promptId } ?: run {
            resumingMetaIds.remove(placeholder.id); return null
        }
        val provider = AppService.findById(placeholder.providerId) ?: run {
            resumingMetaIds.remove(placeholder.id); return null
        }
        val model = placeholder.model
        val scope = com.ai.data.SecondaryScope.decodeOrAllReports(placeholder.secondaryScope)
        val kind = placeholder.kind
        val lang = placeholder.targetLanguage
        val langNative = placeholder.targetLanguageNative
        val cat = "Report ${kind.name.lowercase()}: ${metaPrompt.name}"

        AppLog.i("Resume", "→ re-issue ${kind.name} \"${metaPrompt.name}\" report=$reportId row=${placeholder.id} via ${provider.id}/$model")
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        return appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                withTracerTags(reportId = reportId, category = cat) {
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    val allSecondaries = SecondaryResultStorage.listForReport(context, reportId)
                    // Same scope → includeIds resolution as runMetaPrompt.
                    val includeIds: Set<Int>? = when (scope) {
                        com.ai.data.SecondaryScope.AllReports -> null
                        is com.ai.data.SecondaryScope.TopRanked -> {
                            val rerank = SecondaryResultStorage.get(context, reportId, scope.rerankResultId)
                            com.ai.data.extractTopRankedIds(rerank?.content, scope.count)?.toSet()
                        }
                        is com.ai.data.SecondaryScope.Manual -> {
                            val successful = report.agents.filter {
                                it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                            }
                            val ids = successful.mapIndexedNotNull { idx, a ->
                                if (a.agentId in scope.agentIds) idx + 1 else null
                            }
                            if (ids.isEmpty()) null else ids.toSet()
                        }
                    }
                    val successfulCount = if (includeIds != null) includeIds.size
                        else report.agents.count { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                    val (translatedPrompt, resultsBlock) = buildLanguageInputs(report, allSecondaries, lang, includeIds)
                    val resolvedPrompt = resolveSecondaryPrompt(
                        metaPrompt.text, question = translatedPrompt, results = resultsBlock,
                        count = successfulCount, title = report.title
                    )
                    val referenceLegend = if (metaPrompt.reference) buildReferenceLegend(report, includeIds) else null
                    executeSecondaryTask(
                        context, reportId, kind, metaPrompt,
                        provider, model, resolvedPrompt, aiSettings, report,
                        lang, langNative, referenceLegend,
                        existingPlaceholder = placeholder,
                        scopeEncoded = placeholder.secondaryScope
                    )
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
                resumingMetaIds.remove(placeholder.id)
            }
        }
    }

    /** Re-enqueue fan-out pair placeholders that look stuck — the row is
     *  on disk as a pending placeholder (no content, no errorMessage)
     *  but its id is *not* in [AppViewModel.runningFanOutPairs]. This is the
     *  case after the app process is killed mid-run: the placeholders
     *  survive on disk but the launching coroutines are gone. The Fan out
     *  L1 screen calls this on entry. */
    fun resumeStaleFanOutPairs(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt
    ): Job? {
        val key = fanOutJobKey(reportId, metaPrompt.id)
        // Drop the call if a previous resume scan for the same
        // (report, prompt) is in flight OR has dispatched a rerun
        // that hasn't finished yet. The lifetime of the key extends
        // past the scan body all the way through the dispatched
        // rerun Job — without that, a second resume scan firing
        // milliseconds after the first scan body exits could re-read
        // the same stale placeholders (still blank on disk, the
        // rerun hasn't filled them yet) and dispatch a duplicate
        // rerun, double-billing the user. Both
        // [resumeStaleRunsForReport] (report-open orchestrator) and
        // any direct caller share the same guard.
        if (!staleResumeScans.add(key)) return null
        val scanJob = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val running = appViewModel.runningFanOutPairs.value
            val stale = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
                .filter {
                    it.metaPromptId == metaPrompt.id &&
                        it.fanOutSourceAgentId != null &&
                        it.fanInOf == null &&
                        it.content.isNullOrBlank() &&
                        it.errorMessage == null &&
                        // durationMs is stamped on every successful + errored
                        // save and cleared by resetAndRelaunch. A row with
                        // durationMs set but blank content is a successful
                        // empty-body completion — re-firing it would
                        // duplicate-bill the user (same fix as the L1 stats
                        // classifier in SecondaryResultsScreen).
                        it.durationMs == null &&
                        it.id !in running
                }
            val rerunJob = if (stale.isNotEmpty()) {
                rerunFanOutPlaceholders(context, reportId, metaPrompt, stale)
            } else null
            // Wait for the rerun to fully complete before releasing
            // the dedup key — only at that point can we safely admit
            // a new scan; until then a fresh scan would re-read the
            // same placeholders (still blank) and re-dispatch.
            rerunJob?.join()
        }
        scanJob.invokeOnCompletion { staleResumeScans.remove(key) }
        return scanJob
    }

    /** Re-run a single fan-out pair row. Resets it on disk (clears
     *  content / errorMessage / token usage / cost / duration so it
     *  reads as queued again) and dispatches via [resetAndRelaunch].
     *  Used by the L3 "Fan out - pair" TitleBar's 🔄 reload icon. */
    fun rerunSingleFanOutPair(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        pair: SecondaryResult
    ): Job? = resetAndRelaunch(context, reportId, metaPrompt, listOf(pair))

    /** Re-run fan-out pair rows that errored. Resets the rows on disk
     *  (clears errorMessage so they read as queued again) and dispatches
     *  via [rerunFanOutPlaceholders]. */
    fun rerunFailedFanOutPairs(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt
    ): Job? {
        val failed = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
            .filter {
                it.metaPromptId == metaPrompt.id &&
                    it.fanOutSourceAgentId != null &&
                    it.fanInOf == null &&
                    it.errorMessage != null
            }
        return resetAndRelaunch(context, reportId, metaPrompt, failed)
    }

    /** Drop every errored fan-out pair row for this metaPromptId
     *  without re-firing. Wired to the L1 Fan out detail screen's
     *  "Remove failed items" button. */
    fun removeFailedFanOutPairs(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt
    ): Job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
        val failed = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
            .filter {
                it.metaPromptId == metaPrompt.id &&
                    it.fanOutSourceAgentId != null &&
                    it.fanInOf == null &&
                    it.errorMessage != null
            }
        if (failed.isEmpty()) return@launch
        val costDelta = failed.sumOf { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) }
        failed.forEach { SecondaryResultStorage.delete(context, reportId, it.id) }
        // Removed rows weren't in runningFanOutPairs (errored is a
        // terminal state), so no need to update that set.
        if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
        ReportStorage.bumpReportTimestamp(context, reportId)
    }

    /** L2-scoped "Restart failed items". Re-fires only the errored
     *  pair rows for this metaPromptId where the active (provider,
     *  model) is the answerer — pairs where another model is the
     *  answerer are left alone so a partial failure in one row of L1
     *  doesn't drag in other models' calls. Throttled through the
     *  same [rerunFanOutPlaceholders] semaphore as the original run. */
    fun rerunFailedFanOutPairsForModel(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        providerId: String,
        model: String
    ): Job? {
        val failed = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
            .filter {
                it.metaPromptId == metaPrompt.id &&
                    it.fanOutSourceAgentId != null &&
                    it.fanInOf == null &&
                    it.errorMessage != null &&
                    it.providerId.equals(providerId, ignoreCase = true) &&
                    it.model == model
            }
        if (failed.isEmpty()) return null
        return resetAndRelaunch(context, reportId, metaPrompt, failed)
    }

    /** L2-scoped "Remove failed items". Mirrors
     *  [removeFailedFanOutPairs] but only drops rows where the
     *  active (provider, model) is the answerer. */
    fun removeFailedFanOutPairsForModel(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        providerId: String,
        model: String
    ): Job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
        val failed = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
            .filter {
                it.metaPromptId == metaPrompt.id &&
                    it.fanOutSourceAgentId != null &&
                    it.fanInOf == null &&
                    it.errorMessage != null &&
                    it.providerId.equals(providerId, ignoreCase = true) &&
                    it.model == model
            }
        if (failed.isEmpty()) return@launch
        val costDelta = failed.sumOf { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) }
        failed.forEach { SecondaryResultStorage.delete(context, reportId, it.id) }
        if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
        ReportStorage.bumpReportTimestamp(context, reportId)
    }

    /** Drop every fan_out pair-row + every fan_in combine-row for this
     *  metaPromptId on this report, then kick a fresh
     *  [runFanOutPrompt]. Used by the Fan out L1 "Rerun the complete
     *  Fan out" button. */
    fun rerunCompleteFanOut(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt
    ): Job? {
        // Cancel + join any in-flight fan out run for this (report,
        // metaPrompt) before deleting placeholders. Without this,
        // surviving coroutines would call SecondaryResultStorage.save
        // on the just-deleted ids, resurrecting zombie rows beside
        // the freshly-created placeholders and double-billing.
        return appViewModel.viewModelScope.launch(Dispatchers.IO) {
            fanOutJobs[fanOutJobKey(reportId, metaPrompt.id)]?.let { existing ->
                existing.cancelAndJoin()
            }
            // Wipe every per-pair fan-out row tied to this metaPromptId
            // so the rerun starts from a clean grid. The previous filter
            // additionally tried to clean up fan_in rows via
            // `fanInOf == metaPrompt.id`, but fanInOf is set
            // to the FAN_IN prompt's id (line ~1326), never the
            // fan out prompt's id, so that clause was always false — dead
            // code disguised as cleanup. Fan-in rows on the same
            // report are left alone here; rerunning the fan out doesn't
            // know which fan-in derivatives came from this fan out
            // (no explicit link is stored), so deleting them all would
            // be over-aggressive. Users wanting a fully clean slate
            // can delete the fan-in rows individually.
            SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
                .filter {
                    it.metaPromptId == metaPrompt.id && it.fanOutSourceAgentId != null
                }
                .forEach { SecondaryResultStorage.delete(context, reportId, it.id) }
            runFanOutPrompt(context, reportId, metaPrompt)?.join()
        }
    }

    /** Drop every fan-out pair row for this report's metaPromptId where
     *  the model appears as the answerer (providerId, model match) OR
     *  as the source (the row's fanOutSourceAgentId points at an agent
     *  whose (provider, model) matches). Other Fan out runs on the same
     *  report (different metaPromptId) are untouched.
     *
     *  Returns a Job so the caller (or tests) can await completion of
     *  the cancellation sweep + disk delete. The cancellation phase
     *  cancelAndJoins every per-pair coroutine for the rows we're about
     *  to delete (registered in [fanOutPairJobs]), so a coroutine that's
     *  mid-HTTP or mid-save can't land a completion via
     *  saveIfStillPresent AFTER the delete (silently dropping a result
     *  the user paid for) or interleave a write with the delete. */
    fun deleteFanOutModel(
        context: Context,
        reportId: String,
        metaPromptId: String,
        providerId: String,
        model: String
    ): Job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
        val report = ReportStorage.getReport(context, reportId) ?: return@launch
        // Provider ids are looked up via AppService.findById (case-
        // insensitive); the on-disk values can be either case
        // depending on when the row was written. Compare equalsIgnoreCase
        // so a UI delete with a re-cased id still matches.
        val matchingAgentIds = report.agents
            .filter { it.provider.equals(providerId, ignoreCase = true) && it.model == model }
            .map { it.agentId }
            .toSet()
        val toDelete = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
            .filter {
                it.metaPromptId == metaPromptId &&
                    it.fanOutSourceAgentId != null &&
                    it.fanInOf == null &&
                    (
                        (it.providerId.equals(providerId, ignoreCase = true) && it.model == model) ||
                            (it.fanOutSourceAgentId in matchingAgentIds)
                    )
            }
        if (toDelete.isEmpty()) return@launch
        // Cancel + join the per-pair coroutines BEFORE deleting their
        // rows. Each cancelled coroutine either bails inside its
        // ProviderThrottle.acquire suspend point (no HTTP fired) or
        // throws CancellationException out of the HTTP suspend (no
        // saveIfStillPresent call). Either way, no zombie write lands
        // after we delete the row. join() waits for the finally
        // blocks (running-set removal + permit release) to run.
        val toDeleteIds = toDelete.map { it.id }
        toDeleteIds.forEach { id ->
            fanOutPairJobs[id]?.cancelAndJoin()
        }
        val costDelta = toDelete.sumOf { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) }
        toDeleteIds.forEach { SecondaryResultStorage.delete(context, reportId, it) }
        // The per-pair finally blocks already removed these ids from
        // runningFanOutPairs during join, but a stale-cache window
        // could leave the UI showing them as "running" until the next
        // refresh tick. Force-prune the set so the trash icon's
        // visual effect is immediate.
        val deletedIds = toDeleteIds.toSet()
        appViewModel.updateRunningFanOutPairs { it - deletedIds }
        if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
        ReportStorage.bumpReportTimestamp(context, reportId)
    }

    /** Combines a fan-out run's per-pair responses plus every
     *  successful agent's response body into a single combined report
     *  via a single chat call on [pick]. The template's iterable block
     *  `\n\n***Report*** @REPORT@@RESPONSES@` is expanded once per
     *  successful (source) agent, with its `@RESPONSES@` populated
     *  from the latest fan-out response row of every other answerer.
     *
     *  Persists one [SecondaryResult] with kind=META and
     *  fanInOf=metaPrompt.id, so the fan out detail screen can show
     *  it inline above the L1 list while the View bucket still buckets
     *  by `metaPromptName`.
     */
    fun runFanInPrompt(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        pick: Pair<AppService, String>
    ): Job? {
        AppLog.i("FanIn", "→ start \"${metaPrompt.name}\" report=$reportId via ${pick.first.id}/${pick.second}")
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        return appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val cat = "Report meta: ${metaPrompt.name}"
            try {
                withTracerTags(reportId = reportId, category = cat) {
                    val state = appViewModel.uiState.value
                    val aiSettings = state.aiSettings
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    val successful = report.agents.filter {
                        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                    }
                    // Wait for any in-flight fan out runs on this report
                    // to finish before reading their per-pair rows —
                    // otherwise the combine call would build its
                    // payload from an arbitrary partial subset of
                    // responses while the user thinks the report is
                    // "all of them". Each fan out run is keyed by its
                    // own metaPromptId; a snapshot of the active jobs
                    // for THIS report is enough.
                    val fanOutJobsForReport = fanOutJobs.entries
                        .filter { it.key.startsWith("$reportId|") }
                        .map { it.value }
                    fanOutJobsForReport.forEach { it.join() }
                    val fanOutRows = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
                        .filter { it.fanOutSourceAgentId != null }
                        // Drop errored rows before bucketing — without
                        // this, the firstOrNull pick below grabs the
                        // OLDEST row (we sort ascending), which is the
                        // failed first attempt. A successful retry
                        // landed afterwards is then ignored. Filtering
                        // up front leaves only valid responses in the
                        // bucket, so the firstOrNull picks the oldest
                        // successful one — closer to the user's
                        // expected "completed run" semantics.
                        .filter { it.errorMessage == null && !it.content.isNullOrBlank() }
                    // Bucket fan out rows by (providerId, model, sourceAgentId).
                    // Two report rows can legitimately share (provider,
                    // model) — e.g. an Agent UUID row and a swarm:provider:model
                    // row pointing at the same model under different
                    // agentIds. Bucketing into a list keeps every
                    // matching row, and the answerer-side resolution
                    // below picks the best-fit row per (other.provider,
                    // other.model, other.agentId, source.agentId).
                    val byPair = LinkedHashMap<String, MutableList<SecondaryResult>>()
                    fanOutRows.sortedBy { it.timestamp }.forEach { r ->
                        val src = r.fanOutSourceAgentId ?: return@forEach
                        byPair.getOrPut("${r.providerId}|${r.model}|$src") { mutableListOf() }.add(r)
                    }
                    val consumed = HashSet<String>()
                    val perReport: List<Pair<String, List<String>>> = successful.map { source ->
                        val fanOutResponses = successful.mapNotNull other@{ other ->
                            if (other.agentId == source.agentId) return@other null
                            // Pick the next un-consumed row for this
                            // (provider, model, source) bucket so two
                            // distinct other-agents sharing (provider,
                            // model) each get their own response.
                            val bucket = byPair["${other.provider}|${other.model}|${source.agentId}"]
                                ?: return@other null
                            val row = bucket.firstOrNull { it.id !in consumed }
                                ?: return@other null
                            consumed += row.id
                            if (row.errorMessage != null) return@other null
                            val c = row.content
                            if (c.isNullOrBlank()) null else c.trim()
                        }
                        (source.responseBody?.trim().orEmpty()) to fanOutResponses
                    }
                    if (perReport.all { it.second.isEmpty() }) {
                        val (provider, model) = pick
                        val agentName = "${provider.id} / $model"
                        SecondaryResultStorage.create(
                            context, reportId, SecondaryKind.META, provider.id, model, agentName
                        ) {
                            it.copy(
                                metaPromptId = metaPrompt.id,
                                metaPromptName = metaPrompt.name,
                                fanInOf = metaPrompt.id,
                                errorMessage = "No fan-out responses available — run the fan-out prompt first."
                            )
                        }
                        return@withTracerTags
                    }
                    val resolved = resolveFanInPrompt(
                        template = metaPrompt.text,
                        question = report.prompt,
                        count = perReport.size,
                        fanOutCount = (perReport.size - 1).coerceAtLeast(0),
                        perReport = perReport,
                        title = report.title
                    )
                    val (provider, model) = pick
                    executeSecondaryTask(
                        context, reportId, SecondaryKind.META, metaPrompt,
                        provider, model, resolved, aiSettings, report,
                        fanInOf = metaPrompt.id
                    )
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
    }

    /** Model-scoped variant of [runFanInPrompt]. Combines only the
     *  fan-out entries that involve the specified active model
     *  (provider, modelName) into one combined-report row, instead
     *  of the legacy "total" fan_in that combines every entry on the
     *  report. Driven by the three new categories `initiator`
     *  (active is source), `requester` (active is answerer), and
     *  `model` (both). The resulting row carries scopeProviderId /
     *  scopeModel so the L2 page can filter to its own model's rows.
     *
     *  Math for 10 models with active = A:
     *    initiator — 9 fan-out responses where A is the source.
     *               @RESPONDERS@ holds those bodies; @INITIATOR@ is
     *               A's own report response.
     *    requester — 9 pairs (other_i's report response, A's fan-out
     *               response to other_i). @RESPONDER_PAIRS@ holds them.
     *    model — both blocks populated. */
    fun runModelFanInPrompt(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        pick: Pair<AppService, String>,
        activeProviderId: String,
        activeModel: String
    ): Job? {
        AppLog.i("ModelFanIn", "→ start \"${metaPrompt.name}\" report=$reportId active=$activeProviderId/$activeModel via ${pick.first.id}/${pick.second}")
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        return appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val cat = "Report meta: ${metaPrompt.name}"
            try {
                withTracerTags(reportId = reportId, category = cat) {
                    val state = appViewModel.uiState.value
                    val aiSettings = state.aiSettings
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    // Wait for in-flight fan-out runs on this report —
                    // same race-free pattern as runFanInPrompt.
                    val fanOutJobsForReport = fanOutJobs.entries
                        .filter { it.key.startsWith("$reportId|") }
                        .map { it.value }
                    fanOutJobsForReport.forEach { it.join() }

                    // Resolve the active model's agents on this report.
                    // Swarm with duplicate (provider, model) members can
                    // produce multiple agentIds for the same model — we
                    // count rows from any of them.
                    val activeAgents = report.agents.filter {
                        it.reportStatus == ReportStatus.SUCCESS
                            && it.provider == activeProviderId
                            && it.model == activeModel
                            && !it.responseBody.isNullOrBlank()
                    }
                    val activeAgentIds = activeAgents.map { it.agentId }.toHashSet()
                    val initiatorBody = activeAgents.firstOrNull()?.responseBody?.trim().orEmpty()

                    // Per-pair fan-out rows on this report.
                    val fanOutRows = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
                        .filter { it.fanOutSourceAgentId != null && it.fanInOf == null }
                        .filter { it.errorMessage == null && !it.content.isNullOrBlank() }
                        .sortedBy { it.timestamp }

                    // The unified fan-in-model template can use both
                    // @RESPONDERS@ and @RESPONDER_PAIRS@; resolve both
                    // unconditionally and let the prompt body opt in
                    // by reference.

                    // @RESPONDERS@: rows where the active model is the
                    // SOURCE (others responded TO active's report). One
                    // row per other answerer; bucket and pick the
                    // freshest non-errored row per (other-provider,
                    // other-model, source-agent) triple.
                    val responders: List<String> = fanOutRows
                        .filter { it.fanOutSourceAgentId in activeAgentIds }
                        .groupBy { "${it.providerId}|${it.model}" }
                        .values
                        .mapNotNull { bucket -> bucket.lastOrNull()?.content?.trim()?.takeIf { it.isNotBlank() } }

                    // @RESPONDER_PAIRS@: rows where the active model is
                    // the ANSWERER (active responded to others). Each
                    // pair = (other's report response, active's fan-out
                    // response). Bucket by source agent.
                    val responderPairs: List<Pair<String, String>> = fanOutRows
                        .filter { it.providerId == activeProviderId && it.model == activeModel }
                        .groupBy { it.fanOutSourceAgentId.orEmpty() }
                        .mapNotNull { (srcAgentId, bucket) ->
                            if (srcAgentId.isBlank()) return@mapNotNull null
                            val source = report.agents.firstOrNull { it.agentId == srcAgentId } ?: return@mapNotNull null
                            val srcBody = source.responseBody?.trim().orEmpty()
                            val resp = bucket.lastOrNull()?.content?.trim().orEmpty()
                            if (srcBody.isBlank() || resp.isBlank()) null else srcBody to resp
                        }

                    val (provider, model) = pick

                    // Bail with an error placeholder when there's
                    // nothing to combine — same shape as the legacy
                    // fan_in does for empty fan-out states.
                    val nothingToCombine = responders.isEmpty() && responderPairs.isEmpty()
                    if (nothingToCombine) {
                        val agentName = "${provider.id} / $model"
                        SecondaryResultStorage.create(
                            context, reportId, SecondaryKind.META, provider.id, model, agentName
                        ) {
                            it.copy(
                                metaPromptId = metaPrompt.id,
                                metaPromptName = metaPrompt.name,
                                fanInOf = metaPrompt.id,
                                scopeProviderId = activeProviderId,
                                scopeModel = activeModel,
                                errorMessage = "No fan-out responses available for ${activeProviderId} / ${activeModel} — run the fan-out prompt first."
                            )
                        }
                        return@withTracerTags
                    }

                    val resolved = com.ai.data.resolveModelFanInPrompt(
                        template = metaPrompt.text,
                        question = report.prompt,
                        title = report.title,
                        initiatorBody = initiatorBody,
                        responders = responders,
                        responderPairs = responderPairs
                    )

                    // Pre-create the placeholder so the scope fields
                    // are persisted from the start (executeSecondaryTask
                    // doesn't take scopeProviderId / scopeModel — we
                    // pass the staged row in via existingPlaceholder).
                    val agentName = "${provider.id} / $model"
                    val placeholder = SecondaryResultStorage.create(
                        context, reportId, SecondaryKind.META, provider.id, model, agentName
                    ) {
                        it.copy(
                            metaPromptId = metaPrompt.id,
                            metaPromptName = metaPrompt.name,
                            fanInOf = metaPrompt.id,
                            scopeProviderId = activeProviderId,
                            scopeModel = activeModel
                        )
                    }

                    executeSecondaryTask(
                        context, reportId, SecondaryKind.META, metaPrompt,
                        provider, model, resolved, aiSettings, report,
                        fanInOf = metaPrompt.id,
                        existingPlaceholder = placeholder
                    )
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
    }

    /** Build a fresh AI Report from the L2 active model's fan-out
     *  conversation. Promotes "active model said X, the others
     *  responded" into a standalone report:
     *
     *  - prompt = active model's report response on the source
     *    report (verbatim)
     *  - title  = "From: <orig title> · <provider> / <model>"
     *  - agents = one synthesised [ReportAgent] per fan-out row
     *    where active is the source (i.e. fanOutSourceAgentId in
     *    activeAgentIds). Carries the answerer's body, error,
     *    tokens, cost, and durationMs straight through so the
     *    new result page reads like a normal completed report.
     *
     *  Buckets per (other-provider, other-model, source-agent),
     *  keeping the latest row — same dedup the L2 page uses for
     *  its on-screen list, so retries don't pile up.
     *
     *  Returns the new report id on success, null when there's
     *  nothing to copy (active agent missing / no fan-out rows /
     *  active's responseBody blank). */
    suspend fun createReportFromFanOut(
        context: Context,
        sourceReportId: String,
        activeProviderId: String,
        activeModel: String
    ): String? = withContext(Dispatchers.IO) {
        val source = ReportStorage.getReport(context, sourceReportId) ?: return@withContext null
        val activeAgents = source.agents.filter {
            it.reportStatus == ReportStatus.SUCCESS
                && it.provider == activeProviderId && it.model == activeModel
                && !it.responseBody.isNullOrBlank()
        }
        val active = activeAgents.firstOrNull() ?: return@withContext null
        val activeAgentIds = activeAgents.map { it.agentId }.toHashSet()

        val raw = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.META)
            .filter { it.fanOutSourceAgentId in activeAgentIds && it.fanInOf == null }
        val bucketed = LinkedHashMap<String, SecondaryResult>()
        raw.sortedBy { it.timestamp }.forEach { r ->
            bucketed["${r.providerId}|${r.model}|${r.fanOutSourceAgentId}"] = r
        }
        val rows = bucketed.values.toList()
        if (rows.isEmpty()) return@withContext null

        val newAgents = rows.map { row ->
            ReportAgent(
                agentId = java.util.UUID.randomUUID().toString(),
                agentName = "${row.providerId} / ${row.model}",
                provider = row.providerId,
                model = row.model,
                reportStatus = if (row.errorMessage != null) ReportStatus.ERROR else ReportStatus.SUCCESS,
                responseBody = row.content,
                errorMessage = row.errorMessage,
                tokenUsage = row.tokenUsage,
                cost = ((row.inputCost ?: 0.0) + (row.outputCost ?: 0.0)).takeIf { it > 0.0 },
                durationMs = row.durationMs
            )
        }

        val now = System.currentTimeMillis()
        val srcTitle = source.title.ifBlank { "AI Report" }
        val newTitle = "From: $srcTitle · $activeProviderId / $activeModel"
        val newReport = Report(
            id = java.util.UUID.randomUUID().toString(),
            timestamp = now,
            title = newTitle,
            prompt = active.responseBody.orEmpty(),
            agents = newAgents.toMutableList(),
            completedAt = now,
            sourceReportId = sourceReportId,
            totalCost = newAgents.mapNotNull { it.cost }.sum()
        )
        ReportStorage.persistReport(context, newReport)
        newReport.id
    }

    fun runMetaPrompt(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        picks: List<Pair<AppService, String>>,
        scopeChoice: SecondaryScope = SecondaryScope.AllReports,
        languageScope: SecondaryLanguageScope = SecondaryLanguageScope.AllPresent
    ): Job? {
        if (picks.isEmpty()) return null
        val kind = SecondaryKind.META
        AppLog.i("Meta", "→ start \"${metaPrompt.name}\" report=$reportId — ${picks.size} pick(s)")
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }

        return appViewModel.viewModelScope.launch(Dispatchers.IO) {
            // Tag every API call this batch makes with the parent
            // report's id and a Meta-prompt-name category. Without the
            // reportId tag the resulting trace files would land with
            // reportId=null and the report's JSON export wouldn't
            // pull them in. withTracerTags saves and restores both
            // values so this works correctly when nested.
            val cat = "Report meta: ${metaPrompt.name}"
            try {
              withTracerTags(reportId = reportId, category = cat) {
                val state = appViewModel.uiState.value
                val aiSettings = state.aiSettings
                val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                val allSecondaries = SecondaryResultStorage.listForReport(context, reportId)
                // Bump the parent report's timestamp so it sorts to the top
                // of the History list — adding a meta result is a real
                // update to the report, not a passive read.
                ReportStorage.bumpReportTimestamp(context, reportId)
                // Resolve scope: AllReports → no filter; TopRanked → parse
                // the chosen rerank, take the top-N original ids. If parsing
                // fails (legacy / malformed rerank output) fall back to
                // AllReports rather than blocking the user.
                val includeIds: Set<Int>? = when (scopeChoice) {
                    SecondaryScope.AllReports -> null
                    is SecondaryScope.TopRanked -> {
                        val rerank = SecondaryResultStorage.get(context, reportId, scopeChoice.rerankResultId)
                        val ids = extractTopRankedIds(rerank?.content, scopeChoice.count)
                        if (ids.isNullOrEmpty()) null else ids.toSet()
                    }
                    is SecondaryScope.Manual -> {
                        // Manual is expressed as agentIds; convert to the
                        // 1-based original-id indices used by buildLanguageInputs
                        // / buildResultsBlock.
                        val successful = report.agents.filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                        val ids = successful.mapIndexedNotNull { idx, a ->
                            if (a.agentId in scopeChoice.agentIds) idx + 1 else null
                        }
                        if (ids.isEmpty()) null else ids.toSet()
                    }
                }
                val successfulCount = if (includeIds != null) includeIds.size
                    else report.agents.count { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }

                // Multi-language fan-out: one batch per language present
                // on the report. The Original language is encoded as null
                // and the SecondaryResult.targetLanguage stays null for
                // it; translations get the human English name.
                val translationLanguages = run {
                    val nativeByLang = LinkedHashMap<String, String?>()
                    allSecondaries
                        .filter { it.kind == SecondaryKind.TRANSLATE && !it.targetLanguage.isNullOrBlank() }
                        .forEach { tr ->
                            val l = tr.targetLanguage!!
                            if (l !in nativeByLang) nativeByLang[l] = tr.targetLanguageNative
                        }
                    when (languageScope) {
                        SecondaryLanguageScope.AllPresent -> nativeByLang
                        is SecondaryLanguageScope.Selected -> {
                            val filtered = LinkedHashMap<String, String?>()
                            nativeByLang.forEach { (k, v) -> if (k in languageScope.languages) filtered[k] = v }
                            filtered
                        }
                    }
                }
                // The original (untranslated) source is included by
                // default and when the user kept it ticked under
                // Selected. The empty-string sentinel "" in
                // SecondaryLanguageScope.Selected.languages signals
                // "include original".
                val includeOriginal = when (languageScope) {
                    SecondaryLanguageScope.AllPresent -> true
                    is SecondaryLanguageScope.Selected -> "" in languageScope.languages
                }
                val languages: List<Pair<String?, String?>> =
                    (if (includeOriginal) listOf<Pair<String?, String?>>(null to null) else emptyList()) +
                    translationLanguages.map { (lang, native) -> lang to native }

                // Per-batch semaphore: bounds the in-batch fan-out across
                // every (language, pick) tuple. Overlapping batches are
                // not capped against each other (intentional; that's the
                // whole point of allowing concurrent runs).
                val sem = Semaphore(AppViewModel.REPORT_CONCURRENCY_LIMIT)
                // Reference legend — built when the prompt's reference
                // flag is on. Computed once per batch.
                val referenceLegend = if (metaPrompt.reference)
                    buildReferenceLegend(report, includeIds) else null
                coroutineScope {
                    languages.flatMap { (lang, langNative) ->
                        val (translatedPrompt, resultsBlock) = buildLanguageInputs(report, allSecondaries, lang, includeIds)
                        val resolvedPrompt = resolveSecondaryPrompt(
                            metaPrompt.text, question = translatedPrompt, results = resultsBlock,
                            count = successfulCount, title = report.title
                        )
                        picks.map { (provider, model) ->
                            async {
                                sem.withPermit {
                                    executeSecondaryTask(
                                        context, reportId, kind, metaPrompt,
                                        provider, model, resolvedPrompt, aiSettings, report,
                                        lang, langNative, referenceLegend,
                                        scopeEncoded = scopeChoice.encode()
                                    )
                                }
                            }
                        }
                    }.awaitAll()
                }
              }
            } finally {
                // activeSecondaryBatches must drop even if the tag
                // block throws or is cancelled mid-batch — otherwise
                // the Meta screen's hourglass / poll loop would think
                // a batch is still in flight forever.
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
    }


    private suspend fun executeSecondaryTask(
        context: Context, reportId: String, kind: SecondaryKind,
        metaPrompt: com.ai.model.InternalPrompt,
        provider: AppService, model: String, resolvedPrompt: String, aiSettings: Settings,
        report: Report,
        targetLanguage: String? = null,
        targetLanguageNative: String? = null,
        referenceLegend: String? = null,
        fanOutSourceAgentId: String? = null,
        fanInOf: String? = null,
        /** Optional pre-created placeholder. When the caller has staged
         *  a row up-front (fan-out does this so all N×(M-1) pair
         *  rows surface as ⏳ on the fan out detail screen the moment the
         *  run starts) we run against that row instead of creating a
         *  fresh one — otherwise the placeholder duplicates and the
         *  pre-created row never gets a result. */
        existingPlaceholder: SecondaryResult? = null,
        scopeEncoded: String? = null
    ) {
        val apiKey = aiSettings.getApiKey(provider)
        val langSuffix = targetLanguage?.let { " [$it]" } ?: ""
        val agentName = "${provider.id} / $model$langSuffix"
        val placeholder = existingPlaceholder ?: SecondaryResultStorage.create(
            context, reportId, kind, provider.id, model, agentName
        ) {
            it.copy(
                targetLanguage = targetLanguage,
                targetLanguageNative = targetLanguageNative,
                metaPromptId = metaPrompt.id,
                metaPromptName = metaPrompt.name,
                fanOutSourceAgentId = fanOutSourceAgentId,
                fanInOf = fanInOf,
                secondaryScope = scopeEncoded
            )
        }

        // Moderation runs through the dedicated /v1/moderations
        // endpoint — one batch call classifying every report response.
        // No chat prompt, no per-response loop here (the API takes the
        // input array and returns one result per input). The structured
        // JSON content is rendered as a flagged-categories table by the
        // detail screen.
        if (kind == SecondaryKind.MODERATION) {
            val responses = report.agents
                .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                .map { it.responseBody!! }
            val (_, r) = com.ai.data.callModerationApi(provider, apiKey, model, responses)
            // Persist Mistral's reported token usage + per-token cost
            // so the result row shows cents like the other meta runs.
            // Falls through to no-cost when the API didn't report usage.
            val tu = r.tokenUsage
            val pricing = PricingCache.getPricing(context, provider, model)
            val inCost = tu?.let { it.inputTokens * pricing.promptPrice }
            val outCost = tu?.let { it.outputTokens * pricing.completionPrice }
            val saved = SecondaryResultStorage.saveIfStillPresent(context, placeholder.copy(
                content = r.content,
                errorMessage = r.errorMessage,
                tokenUsage = tu,
                inputCost = inCost,
                outputCost = outCost,
                durationMs = r.durationMs
            ))
            // Skip usage-stats too if the row was deleted while in
            // flight — the user dropped this run, so we shouldn't bill
            // the per-provider token counters for it either.
            if (saved && r.errorMessage == null) {
                val inT = tu?.inputTokens ?: 0
                val outT = tu?.outputTokens ?: 0
                appViewModel.settingsPrefs.updateUsageStatsAsync(
                    provider, model, inT, outT, inT + outT, kind = "moderation"
                )
            }
            return
        }

        // Rerank-typed models (Cohere rerank-v3.5 etc.) don't have a chat
        // endpoint — they take query + documents and return relevance
        // scores. Detect that and route to the dedicated rerank API,
        // converting the response back to the structured JSON the rest
        // of the system already consumes (HTML export, Top-Ranked scope).
        val isRerankApiPath = kind == SecondaryKind.RERANK
            && aiSettings.getModelType(provider, model) == com.ai.data.ModelType.RERANK
        if (isRerankApiPath) {
            val docs = report.agents
                .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                .map { it.responseBody!! }
            val r = com.ai.data.callRerankApi(provider, apiKey, model, report.prompt, docs)
            // Per-query pricing: cost = billedSearchUnits × perQueryPrice.
            // Stored on inputCost so the report cost table renders
            // alongside chat/summarize rows. The provider may omit the
            // billed-units block on an error response — fall back to 1
            // unit per call so cost is at least roughly tracked.
            val pricing = PricingCache.getPricing(context, provider, model)
            val units = r.billedSearchUnits ?: if (r.errorMessage == null) 1 else 0
            val rerankCost = if (units > 0) units * pricing.perQueryPrice else null
            val saved = SecondaryResultStorage.saveIfStillPresent(context, placeholder.copy(
                content = r.content,
                errorMessage = r.errorMessage,
                inputCost = rerankCost,
                durationMs = r.durationMs
            ))
            if (saved && r.errorMessage == null) {
                appViewModel.settingsPrefs.updateUsageStatsAsync(
                    provider, model, 0, 0, 0, kind = "rerank", searchUnits = units
                )
            }
            return
        }

        val agent = Agent(
            id = "secondary:${kind.name}:${provider.id}:$model",
            name = agentName, provider = provider, model = model, apiKey = apiKey
        )
        val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(agent)
        val start = System.currentTimeMillis()
        val response = try {
            appViewModel.repository.analyzeWithAgent(
                agent, "", resolvedPrompt, AgentParameters(), null, context, baseUrl
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Don't translate cancellation into a fake error stored on
            // the placeholder — re-throw so structured concurrency
            // works. (Used to surface as "StandaloneCoroutine was
            // canceled" on the Meta detail screen because the generic
            // catch below swallowed it.)
            throw e
        } catch (e: Exception) {
            AnalysisResponse(provider, null, e.message ?: "Unknown error", agentName = agentName)
        }
        val duration = System.currentTimeMillis() - start
        val pricing = PricingCache.getPricing(context, provider, model)
        val tu = response.tokenUsage
        val inCost = tu?.let { it.inputTokens * pricing.promptPrice }
        val outCost = tu?.let { it.outputTokens * pricing.completionPrice }

        // For chat-type Meta prompts with reference=true, append the
        // deterministic legend so each [N] in the model's prose has a
        // "[N] = Provider / Model" entry at the bottom regardless of
        // whether the model bothered to include one. Only when the
        // call actually produced content — an error response is left
        // untouched so the failure is visible.
        val finalContent = if (response.error == null
                && !response.analysis.isNullOrBlank() && !referenceLegend.isNullOrBlank()) {
            "${response.analysis.trimEnd()}\n\n---\n\n## References\n\n$referenceLegend\n"
        } else response.analysis
        val saved = SecondaryResultStorage.saveIfStillPresent(context, placeholder.copy(
            content = finalContent,
            errorMessage = response.error,
            tokenUsage = tu,
            inputCost = inCost,
            outputCost = outCost,
            durationMs = duration
        ))

        if (saved && response.error == null && tu != null) {
            appViewModel.settingsPrefs.updateUsageStatsAsync(
                provider, model, tu.inputTokens, tu.outputTokens, tu.totalTokens,
                kind = when (kind) {
                    SecondaryKind.RERANK -> "rerank"
                    SecondaryKind.META -> "meta"
                    SecondaryKind.MODERATION -> "moderation"
                    SecondaryKind.TRANSLATE -> "translate"
                }
            )
        }
    }

    fun deleteSecondaryResult(context: Context, reportId: String, resultId: String) {
        // Read the row's cost BEFORE deleting so we can carry it
        // into the report's costsFromDeletedItems tally. The user
        // dropped the row from the report; the API spend is real
        // and should still surface on the result page.
        val cost = SecondaryResultStorage.get(context, reportId, resultId)?.let {
            (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0)
        } ?: 0.0
        SecondaryResultStorage.delete(context, reportId, resultId)
        if (cost > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, cost)
        // Bump the parent report's timestamp — removing a meta /
        // translation row is a real change to what the report contains
        // and should sort the report to the top of History, same as an
        // additive change does.
        ReportStorage.bumpReportTimestamp(context, reportId)
    }

    /** Bulk-delete every secondary result row in [resultIds] off the
     *  UI thread on viewModelScope. Survives the screen scope being
     *  cancelled mid-loop — the previous screen-scoped sweep
     *  abandoned hundreds of rows when the user navigated away
     *  during a Fan-out delete. Returns once the sweep finishes. */
    fun bulkDeleteSecondaryResults(context: Context, reportId: String, resultIds: List<String>, onComplete: () -> Unit = {}) {
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            // Snapshot the per-id costs before deleting so we can
            // bump costsFromDeletedItems with the total.
            var costDelta = 0.0
            resultIds.forEach { id ->
                runCatching {
                    SecondaryResultStorage.get(context, reportId, id)?.let { r ->
                        costDelta += (r.inputCost ?: 0.0) + (r.outputCost ?: 0.0)
                    }
                    SecondaryResultStorage.delete(context, reportId, id)
                }
            }
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
            ReportStorage.bumpReportTimestamp(context, reportId)
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    /** Re-run the API call for a single agent on a finished report,
     *  replacing its persisted result. Mirrors the flow [generateGenericReports]
     *  uses for a fresh run (rebuild ReportTask → executeReportTask) but
     *  scoped to one agent so the rest of the report's results stay
     *  intact. The in-memory _agentResults entry is cleared first so the
     *  Report row reverts to ⏳ while the call is in flight, then
     *  populated again when the new response lands.
     *
     *  Handles both row types: real-Agent ids (UUID, looked up in
     *  aiSettings.agents) and "swarm:provider:model" ids (rebuilt
     *  on-the-fly from the parsed parts). When a real-agent row points
     *  at an agent that's since been deleted, falls back to the direct
     *  shape so the regenerate still goes through. */
    fun regenerateAgent(context: Context, reportId: String, agentId: String) {
        // viewModelScope: same survival rationale as
        // generateGenericReports — a screen-scoped scope here would
        // turn the in-flight call into ERROR on disk if the user
        // navigates away before the new response lands.
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            withTracerTags(reportId = reportId, category = "Report regenerate agent") {
            val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
            val ra = report.agents.find { it.agentId == agentId } ?: return@withTracerTags
            val provider = AppService.findById(ra.provider) ?: return@withTracerTags
            val state = appViewModel.uiState.value
            val aiSettings = state.aiSettings

            val task = if (agentId.startsWith("swarm:")) {
                val runtimeAgent = Agent(agentId, ra.agentName, provider, ra.model, aiSettings.getApiKey(provider))
                ReportTask(agentId, ra, runtimeAgent, AgentParameters())
            } else {
                val savedAgent = aiSettings.getAgentById(agentId)
                if (savedAgent != null) {
                    val ea = savedAgent.copy(
                        apiKey = aiSettings.getEffectiveApiKeyForAgent(savedAgent),
                        model = aiSettings.getEffectiveModelForAgent(savedAgent)
                    )
                    val params = aiSettings.resolveAgentParameters(savedAgent)
                    ReportTask(agentId, ra, ea, params)
                } else {
                    val runtimeAgent = Agent(agentId, ra.agentName, provider, ra.model, aiSettings.getApiKey(provider))
                    ReportTask(agentId, ra, runtimeAgent, AgentParameters())
                }
            }

            // Drop the old result so the report row reverts to ⏳ until
            // executeReportTask publishes the new one.
            _agentResults.update { it - agentId }
            // Rebuild the request from the model's CURRENT capabilities
            // rather than blindly replaying the original report's flags.
            // The user may have toggled vision / web-search / reasoning
            // overrides since the report was generated, or the model's
            // /models response may have been refreshed with a different
            // capability set; either way "Call model API again" should
            // produce a request that fits today's view of the model.
            //
            // Negatives only — we never invent flags the report didn't
            // originally carry. Dropping unsupported features avoids
            // 400s like "model X does not support reasoning_effort"
            // even though the dispatcher's static gate would also
            // strip them; pre-stripping keeps cost / token estimates
            // and the per-call trace clean of speculative parameters.
            val effectiveModel = task.runtimeAgent.model
            // acceptsReasoningEffortParam (not isReasoningCapable): an
            // always-on reasoning model like grok-4.3 reasons but rejects
            // the reasoning_effort parameter — keeping the badge on while
            // still stripping the parameter from the request is the right
            // mirror of what a fresh report would send.
            val canReason = aiSettings.acceptsReasoningEffortParam(provider, effectiveModel)
            val canWeb = aiSettings.isWebSearchCapable(provider, effectiveModel)
            val canVision = aiSettings.isVisionCapable(provider, effectiveModel)
            val baseOverride = state.reportAdvancedParameters
            // The "off" branches must always materialise an override so
            // the dispatcher receives an explicit webSearchTool=false /
            // reasoningEffort=null and won't fall back to the agent's
            // default (which may have one or both flags on). The
            // previous fallback `baseOverride?.copy(...) ?: baseOverride`
            // returned null when baseOverride was already null, leaving
            // the dispatcher to use the agent's default and the strip
            // to silently no-op.
            val withWeb = if (report.webSearchTool && canWeb) {
                (baseOverride ?: AgentParameters()).copy(webSearchTool = true)
            } else (baseOverride ?: AgentParameters()).copy(webSearchTool = false)
            val overrideParams = if (report.reasoningEffort != null && canReason) {
                withWeb.copy(reasoningEffort = report.reasoningEffort)
            } else withWeb.copy(reasoningEffort = null)
            val effectiveImage = if (canVision) report.imageBase64 else null
            val effectiveImageMime = if (canVision) report.imageMime else null
            // Bump the parent report's timestamp so it sorts to the top
            // of the History list — re-running an agent is a real
            // update, not a passive read. Mirrors what the meta-run /
            // translate flows already do.
            ReportStorage.bumpReportTimestamp(context, reportId)
            executeReportTask(
                context, reportId, report.prompt, overrideParams, task,
                effectiveImage, effectiveImageMime, isRegeneration = true
            )
            }
        }
    }

    /** Remove a single agent from a report (storage + in-memory results
     *  flow + the genericReportsSelectedAgents set the UI iterates). The
     *  Report screen's row click leads to a single-result viewer with a
     *  "Remove model from report" button — that's this. */
    fun removeAgentFromReport(context: Context, reportId: String, agentId: String) {
        ReportStorage.removeAgent(context, reportId, agentId)
        ReportStorage.bumpReportTimestamp(context, reportId)
        _agentResults.update { it - agentId }
        appViewModel.updateUiState { state ->
            if (state.currentReportId != reportId) state
            else state.copy(
                genericReportsSelectedAgents = state.genericReportsSelectedAgents - agentId,
                genericReportsTotal = (state.genericReportsTotal - 1).coerceAtLeast(0),
                genericReportsProgress = (state.genericReportsProgress - 1).coerceAtLeast(0)
            )
        }
    }

    // ===== Translate =====

    enum class TranslationStatus { PENDING, RUNNING, DONE, ERROR }
    enum class TranslationKind { PROMPT, AGENT_RESPONSE, META }

    /** One row on the translation progress screen. [sourceText] is what
     *  gets fed to the model; [translatedText] is filled in on DONE.
     *  [costDollars] is the per-call cost in USD (input + output) for
     *  the running tally — the live result-list row passes it through
     *  formatCents() which multiplies by 100, same convention every
     *  other cost cell in the app uses. [target] is the
     *  SecondaryResult.id when the source is a chat-type META row —
     *  used so the per-language overlay can re-attach the translated
     *  content to the right meta result. */
    data class TranslationItem(
        val id: String,
        val label: String,
        val kind: TranslationKind,
        val sourceText: String,
        val target: String? = null,
        val status: TranslationStatus = TranslationStatus.PENDING,
        val translatedText: String? = null,
        val errorMessage: String? = null,
        val costDollars: Double = 0.0,
        /** Token usage from the translation API call. Stored so the new
         *  Report can attribute per-row cost to the translation
         *  operation rather than carrying the source-run figures
         *  through unchanged. Null until the call returns. */
        val tokenUsage: com.ai.data.TokenUsage? = null,
        /** Wall-clock duration of the translation API call. Surfaced
         *  in the Costs table so translate rows aren't blank in the
         *  Seconds column. Null until the call returns. */
        val durationMs: Long? = null
    )

    data class TranslationRunState(
        val runId: String,
        val sourceReportId: String,
        val targetLanguageName: String,
        val targetLanguageNative: String,
        val items: List<TranslationItem>,
        val totalCostDollars: Double = 0.0,
        val finished: Boolean = false,
        val cancelled: Boolean = false
    ) {
        val total: Int get() = items.size
        val completed: Int get() = items.count { it.status == TranslationStatus.DONE || it.status == TranslationStatus.ERROR }
        val isRunning: Boolean get() = !finished && !cancelled && completed < total
        val isFinished: Boolean get() = finished
    }

    // Multiple concurrent translation runs: each Translate click
    // allocates a fresh runId and runs in parallel with any others
    // already in flight. Map keyed by runId so the UI can render one
    // live row per run and Cancel can target a specific one.
    private val _translationRuns = MutableStateFlow<Map<String, TranslationRunState>>(emptyMap())
    val translationRuns: StateFlow<Map<String, TranslationRunState>> = _translationRuns.asStateFlow()
    private val translationJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

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
        provider: AppService,
        model: String
    ): Job {
        val runId = java.util.UUID.randomUUID().toString()
        val job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val state = appViewModel.uiState.value
            val aiSettings = state.aiSettings
            val generalSettings = state.generalSettings
            val sourceReport = ReportStorage.getReport(context, sourceReportId) ?: run {
                _translationRuns.update { it - runId }
                return@launch
            }
            val secondaries = SecondaryResultStorage.listForReport(context, sourceReportId)

            // Build the work list. Order: prompt → agent responses (in
            // success order) → summaries → compares. Reranks and
            // moderation results are skipped (structured JSON, no
            // human-language content to translate).
            val items = mutableListOf<TranslationItem>()
            items += TranslationItem(
                id = "prompt",
                label = "Report prompt",
                kind = TranslationKind.PROMPT,
                sourceText = sourceReport.prompt
            )
            sourceReport.agents
                .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                .forEach { agent ->
                    val provDisplay = AppService.findById(agent.provider)?.id ?: agent.provider
                    items += TranslationItem(
                        id = "agent:${agent.agentId}",
                        label = "$provDisplay / ${agent.model}",
                        kind = TranslationKind.AGENT_RESPONSE,
                        sourceText = agent.responseBody!!,
                        target = agent.agentId
                    )
                }
            // Every chat-type Meta result is a candidate for translation.
            // Label the row by the user-given Meta prompt name so the
            // progress screen / per-call detail show "Compare 1: …" or
            // "Critique 2: …" — driven entirely by the CRUD prompt name,
            // not a hardcoded "Summary" / "Compare".
            secondaries.filter { it.kind == SecondaryKind.META && !it.content.isNullOrBlank() }
                .forEachIndexed { idx, s ->
                    val provDisplay = AppService.findById(s.providerId)?.id ?: s.providerId
                    val name = s.metaPromptName?.takeIf { it.isNotBlank() }
                        ?: com.ai.data.legacyKindDisplayName(s.kind)
                    items += TranslationItem(
                        id = "meta:${s.id}",
                        label = "$name ${idx + 1}: $provDisplay / ${s.model}",
                        kind = TranslationKind.META,
                        sourceText = s.content!!,
                        target = s.id
                    )
                }

            _translationRuns.update { it + (runId to TranslationRunState(
                runId = runId,
                sourceReportId = sourceReportId,
                targetLanguageName = targetLanguageName,
                targetLanguageNative = targetLanguageNative,
                items = items
            )) }
            AppLog.i("Translation", "→ start $targetLanguageName ($targetLanguageNative) for report=$sourceReportId — ${items.size} items via ${provider.id}/$model")

            val template = aiSettings.getInternalPromptByName("Translate")?.text.orEmpty()
            val apiKey = aiSettings.getApiKey(provider)
            val baseUrl = aiSettings.getEffectiveEndpointUrl(provider)
            val pricing = PricingCache.getPricing(context, provider, model)

            // Tag every translation call's trace with the SOURCE report
            // id — translations live on that report now, no separate
            // translated copy to keep traces with.
            withTracerTags(reportId = sourceReportId, category = "Translation") {
                // Concurrency 3: translation calls are typically the slowest
                // I/O in the app; cap so a 30-item report doesn't blow past
                // provider rate limits.
                val sem = Semaphore(3)
                coroutineScope {
                    items.map { item ->
                        async {
                            sem.withPermit { runOneTranslation(runId, context, provider, apiKey, model, baseUrl, template, targetLanguageName, item, pricing) }
                        }
                    }.awaitAll()
                }

                // Per-item rows were already persisted inside
                // runOneTranslation as each call settled, so the
                // batch survives a redeploy / OS kill mid-run. Just
                // bump the parent report's timestamp once at the end
                // so the History list resorts. Skipped on cancel —
                // the cancelled item stays unpersisted and the
                // timestamp bump is moot.
                val finalState = _translationRuns.value[runId] ?: return@withTracerTags
                if (finalState.cancelled) {
                    AppLog.i("Translation", "← cancelled $targetLanguageName for report=$sourceReportId")
                    return@withTracerTags
                }
                ReportStorage.bumpReportTimestamp(context, sourceReportId)
                _translationRuns.update { runs ->
                    val cur = runs[runId] ?: return@update runs
                    runs + (runId to cur.copy(finished = true))
                }
                val okCount = finalState.items.count { it.translatedText?.isNotBlank() == true }
                val failCount = finalState.items.count { it.errorMessage != null }
                AppLog.i("Translation", "← done $targetLanguageName for report=$sourceReportId — ok=$okCount fail=$failCount")
            }
        }
        translationJobs[runId] = job
        job.invokeOnCompletion { translationJobs.remove(runId) }
        return job
    }

    private suspend fun runOneTranslation(
        runId: String,
        context: Context,
        provider: AppService,
        apiKey: String,
        model: String,
        baseUrl: String,
        template: String,
        targetLanguageName: String,
        item: TranslationItem,
        pricing: PricingCache.ModelPricing
    ) {
        _translationRuns.update { runs ->
            val cur = runs[runId] ?: return@update runs
            runs + (runId to cur.copy(items = cur.items.map { if (it.id == item.id) it.copy(status = TranslationStatus.RUNNING) else it }))
        }
        AppLog.d("Translation", "→ item ${item.id} \"${item.label}\" kind=${item.kind} srcLen=${item.sourceText.length}")
        val resolved = template
            .replace("@LANGUAGE@", targetLanguageName)
            .replace("@TEXT@", item.sourceText)
        val agent = Agent(
            id = "translate:${provider.id}:$model",
            name = "Translate / ${provider.id} / $model",
            provider = provider, model = model, apiKey = apiKey
        )
        val callStart = System.currentTimeMillis()
        val response = try {
            appViewModel.repository.analyzeWithAgent(
                agent, "", resolved, AgentParameters(), null, context, baseUrl
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AnalysisResponse(provider, null, e.message ?: "Unknown error", agentName = agent.name)
        }
        val callDurationMs = System.currentTimeMillis() - callStart
        val tu = response.tokenUsage
        val costDollars = if (tu != null) PricingCache.computeCost(tu, pricing) else 0.0
        _translationRuns.update { runs ->
            val cur = runs[runId] ?: return@update runs
            val updated = cur.items.map {
                if (it.id != item.id) it
                else if (response.isSuccess) it.copy(
                    status = TranslationStatus.DONE,
                    translatedText = response.analysis,
                    costDollars = costDollars,
                    tokenUsage = tu,
                    durationMs = callDurationMs
                )
                else it.copy(
                    status = TranslationStatus.ERROR,
                    errorMessage = response.error ?: "Empty response",
                    costDollars = costDollars,
                    tokenUsage = tu,
                    durationMs = callDurationMs
                )
            }
            runs + (runId to cur.copy(items = updated, totalCostDollars = updated.sumOf { it.costDollars }))
        }
        // Persist this item as soon as the call settles so the row
        // survives a process kill mid-batch. Previously the whole
        // batch was held in-memory and only flushed to disk via
        // saveTranslationSecondaries after awaitAll(); a redeploy
        // or an OS kill halfway through silently lost everything.
        // Use the in-memory runId as the on-disk translationRunId so
        // every item this batch writes groups under the same run on
        // the result screen and survives restart with the same
        // identity.
        val freshRun = _translationRuns.value[runId]
        val freshItem = freshRun?.items?.firstOrNull { it.id == item.id }
        if (freshRun != null && freshItem != null) {
            saveOneTranslationItem(context, runId, freshRun, freshItem, provider, model, pricing)
        }
        // Roll the translation call into per-(provider, model) usage so
        // it shows up on the AI Usage screen alongside report / chat
        // calls. Skipped on error responses so the row reflects only
        // billable traffic.
        if (response.isSuccess && tu != null) {
            appViewModel.settingsPrefs.updateUsageStatsAsync(
                provider, model, tu.inputTokens, tu.outputTokens, tu.totalTokens,
                kind = "translate"
            )
        }
        AppLog.d(
            "Translation",
            "← item ${item.id} ${if (response.isSuccess) "ok" else "err"} ${callDurationMs}ms" +
                (tu?.let { " in=${it.inputTokens} out=${it.outputTokens}" } ?: "") +
                " cost=${"%.5f".format(costDollars)}"
        )
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
        translateProvider: AppService,
        translateModel: String,
        translatePricing: PricingCache.ModelPricing
    ) {
        val tu = item.tokenUsage
        // Tier-aware split — runOneTranslation already computes the
        // total via PricingCache.computeCost. The persisted in / out
        // halves now go through computeInOutCost so a long-context
        // translation (>200k tokens) doesn't drift from the canonical
        // total recorded in the parent run.
        val (inCost, outCost) = tu?.let { PricingCache.computeInOutCost(it, translatePricing) }
            ?.let { it.first to it.second } ?: (null to null)
        val labelPrefix = "Translate: ${item.label.ifBlank { item.kind.name.lowercase() }}"
        val (srcKind, srcTargetId) = when (item.kind) {
            TranslationKind.PROMPT -> "PROMPT" to "prompt"
            TranslationKind.AGENT_RESPONSE -> "AGENT" to (item.target ?: "")
            TranslationKind.META -> "META" to (item.target ?: "")
        }
        SecondaryResultStorage.save(context, SecondaryResult(
            id = java.util.UUID.randomUUID().toString(),
            reportId = run.sourceReportId,
            kind = SecondaryKind.TRANSLATE,
            providerId = translateProvider.id,
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
            translationRunId = runId
        ))
    }

    // saveTranslationSecondaries (the bulk all-at-end flush) was
    // replaced by saveOneTranslationItem, called inline as each
    // translation call settles — so a half-finished batch persists
    // the rows it did complete instead of losing everything on a
    // redeploy / OS kill.

    fun cancelTranslation(runId: String) {
        translationJobs[runId]?.cancel()
        _translationRuns.update { runs ->
            val cur = runs[runId] ?: return@update runs
            runs + (runId to cur.copy(cancelled = true))
        }
    }

    fun consumeTranslationRun(runId: String) {
        _translationRuns.update { it - runId }
    }

    /** Drop one pending/running item from an in-flight translation
     *  run. Removes the item from [_translationRuns] so its row
     *  disappears on the detail screen; the [saveOneTranslationItem]
     *  guard inside [runOneTranslation] will then skip the disk
     *  write for any call that lands after this point (the guard
     *  looks the item up by id and bails if it's gone). The
     *  in-flight call itself is allowed to finish — there's no
     *  per-item Job to cancel — but its result is discarded. */
    fun cancelTranslationItem(runId: String, itemId: String) {
        _translationRuns.update { runs ->
            val cur = runs[runId] ?: return@update runs
            val filtered = cur.items.filter { it.id != itemId }
            if (filtered.size == cur.items.size) return@update runs
            runs + (runId to cur.copy(
                items = filtered,
                totalCostDollars = filtered.sumOf { it.costDollars }
            ))
        }
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
    ): Job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
        val rows = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
            .filter { translationRunGroupingId(it) == runId && it.errorMessage != null }
        if (rows.isEmpty()) return@launch
        runTranslationSubset(context, sourceReportId, runId, rows.map { it.translateSourceTargetId.orEmpty() to it.translateSourceKind.orEmpty() }, deleteRowIds = rows.map { it.id })
    }

    /** Drop every errored translation row from [runId] without
     *  re-firing. Wired to the run detail screen's "Remove failed
     *  items" button so the user can clear failures without burning
     *  more tokens. */
    fun removeFailedTranslations(
        context: Context,
        sourceReportId: String,
        runId: String
    ): Job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
        val failed = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
            .filter { translationRunGroupingId(it) == runId && it.errorMessage != null }
        if (failed.isEmpty()) return@launch
        failed.forEach { SecondaryResultStorage.delete(context, sourceReportId, it.id) }
        // Also drop the items from any live state so the detail
        // screen's row count updates immediately instead of waiting
        // for the next list refresh.
        _translationRuns.update { runs ->
            val cur = runs[runId] ?: return@update runs
            val failedTargetKeys = failed
                .map { (it.translateSourceKind ?: "") + ":" + (it.translateSourceTargetId ?: "") }
                .toSet()
            val filtered = cur.items.filterNot { item ->
                val srcKind = when (item.kind) {
                    TranslationKind.PROMPT -> "PROMPT"
                    TranslationKind.AGENT_RESPONSE -> "AGENT"
                    TranslationKind.META -> "META"
                }
                val srcId = if (item.kind == TranslationKind.PROMPT) "prompt" else (item.target ?: "")
                item.status == TranslationStatus.ERROR && "$srcKind:$srcId" in failedTargetKeys
            }
            runs + (runId to cur.copy(
                items = filtered,
                totalCostDollars = filtered.sumOf { it.costDollars }
            ))
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
    ): Job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
        // Cancel any in-flight run for this runId so its already-
        // dispatched coroutines don't keep writing fresh rows under
        // the about-to-be-restarted runId. Cancellation is co-operative;
        // in-flight API calls finish but the post-call writes are
        // gated by translationJobs[runId] cancellation.
        cancelTranslation(runId)
        _translationRuns.update { it - runId }

        val existing = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
            .filter { translationRunGroupingId(it) == runId }
        if (existing.isEmpty()) return@launch

        val report = ReportStorage.getReport(context, sourceReportId) ?: return@launch
        val secondaries = SecondaryResultStorage.listForReport(context, sourceReportId)
        val pairs = buildList<Pair<String, String>> {
            add("prompt" to "PROMPT")
            report.agents
                .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                .forEach { add(it.agentId to "AGENT") }
            secondaries
                .filter { it.kind == SecondaryKind.META && !it.content.isNullOrBlank() }
                .forEach { add(it.id to "META") }
        }
        if (pairs.isEmpty()) return@launch

        runTranslationSubset(
            context, sourceReportId, runId, pairs,
            deleteRowIds = existing.map { it.id }
        )
    }

    /** Run every expected translation item that has no row in [runId]
     *  yet: prompt + each successful agent + each chat-type Meta row.
     *  Used after an interrupted batch to fill in the items that
     *  never got persisted. */
    fun startMissingTranslations(
        context: Context,
        sourceReportId: String,
        runId: String
    ): Job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
        val existing = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
            .filter { translationRunGroupingId(it) == runId }
        // Each existing row covers one (kind, target) pair — even
        // errored ones count as "covered" since they're addressed by
        // restartFailedTranslations.
        val covered = existing.map { (it.translateSourceKind.orEmpty()) to (it.translateSourceTargetId.orEmpty()) }.toSet()
        val report = ReportStorage.getReport(context, sourceReportId) ?: return@launch
        val secondaries = SecondaryResultStorage.listForReport(context, sourceReportId)
        val expected = mutableListOf<Pair<String, String>>()
        expected += "PROMPT" to "prompt"
        report.agents
            .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
            .forEach { expected += "AGENT" to it.agentId }
        secondaries.filter { it.kind == SecondaryKind.META && !it.content.isNullOrBlank() }
            .forEach { expected += "META" to it.id }
        val missing = expected.filterNot { it.first to it.second in covered }
            .map { it.second to it.first } // (target, kind) tuples — runTranslationSubset wants that order
        if (missing.isEmpty()) return@launch
        runTranslationSubset(context, sourceReportId, runId, missing, deleteRowIds = emptyList())
    }

    /** Shared core for [restartFailedTranslations] /
     *  [startMissingTranslations]. Reads the existing run rows to
     *  pick up provider / model / language, deletes any rows in
     *  [deleteRowIds] (used by the restart path so failed rows
     *  don't double up), builds [TranslationItem]s for each
     *  (target, kind) pair, populates [_translationRuns] under
     *  [runId], and dispatches via [runOneTranslation]. */
    private suspend fun runTranslationSubset(
        context: Context,
        sourceReportId: String,
        runId: String,
        targetKindPairs: List<Pair<String, String>>,
        deleteRowIds: List<String>
    ) {
        if (targetKindPairs.isEmpty()) return
        val translateRows = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
            .filter { translationRunGroupingId(it) == runId }
        val anchor = translateRows.firstOrNull() ?: return
        val provider = AppService.findById(anchor.providerId) ?: return
        val model = anchor.model
        val targetLanguageName = anchor.targetLanguage ?: return
        val targetLanguageNative = anchor.targetLanguageNative ?: targetLanguageName

        val report = ReportStorage.getReport(context, sourceReportId) ?: return
        val secondaries = SecondaryResultStorage.listForReport(context, sourceReportId)

        val items = targetKindPairs.mapNotNull { (targetId, kind) ->
            when (kind) {
                "PROMPT" -> TranslationItem(
                    id = "prompt", label = "Report prompt",
                    kind = TranslationKind.PROMPT, sourceText = report.prompt
                )
                "AGENT" -> {
                    val ag = report.agents.firstOrNull { it.agentId == targetId } ?: return@mapNotNull null
                    val prov = AppService.findById(ag.provider)?.id ?: ag.provider
                    TranslationItem(
                        id = "agent:${ag.agentId}",
                        label = "$prov / ${ag.model}",
                        kind = TranslationKind.AGENT_RESPONSE,
                        sourceText = ag.responseBody.orEmpty(),
                        target = ag.agentId
                    )
                }
                "META" -> {
                    val s = secondaries.firstOrNull { it.id == targetId } ?: return@mapNotNull null
                    val prov = AppService.findById(s.providerId)?.id ?: s.providerId
                    val name = s.metaPromptName?.takeIf { it.isNotBlank() }
                        ?: com.ai.data.legacyKindDisplayName(s.kind)
                    TranslationItem(
                        id = "meta:${s.id}", label = "$name: $prov / ${s.model}",
                        kind = TranslationKind.META, sourceText = s.content.orEmpty(),
                        target = s.id
                    )
                }
                else -> null
            }
        }
        if (items.isEmpty()) return

        // Delete the rows we're replacing so the rerun doesn't double
        // up under the same (target, kind) pair.
        deleteRowIds.forEach { SecondaryResultStorage.delete(context, sourceReportId, it) }

        val state = appViewModel.uiState.value
        val aiSettings = state.aiSettings
        val template = aiSettings.getInternalPromptByName("Translate")?.text.orEmpty()
        val apiKey = aiSettings.getApiKey(provider)
        val baseUrl = aiSettings.getEffectiveEndpointUrl(provider)
        val pricing = PricingCache.getPricing(context, provider, model)

        // When the in-memory run state is gone (post-kill resume or
        // manual reload on a finished run), seed the fresh state with
        // already-settled rows from disk so the detail screen shows
        // every entry — the 10 done + the 30 about to retry — not just
        // the ones currently being re-dispatched. Skips rows that
        // [deleteRowIds] is about to wipe (those will land afresh as
        // ⏳ via [items] below) and the stale placeholders the resume
        // is replacing (no content + no error + no durationMs).
        val deleteSet = deleteRowIds.toSet()
        val persistedItems: List<TranslationItem> = if (_translationRuns.value[runId] == null) {
            translateRows.filter { it.id !in deleteSet }.mapNotNull { row ->
                val kind = when (row.translateSourceKind) {
                    "PROMPT" -> TranslationKind.PROMPT
                    "AGENT" -> TranslationKind.AGENT_RESPONSE
                    "META" -> TranslationKind.META
                    else -> return@mapNotNull null
                }
                val targetId = row.translateSourceTargetId.orEmpty()
                val (itemId, label) = when (kind) {
                    TranslationKind.PROMPT -> "prompt" to "Report prompt"
                    TranslationKind.AGENT_RESPONSE -> {
                        val ag = report.agents.firstOrNull { it.agentId == targetId }
                        val prov = AppService.findById(ag?.provider.orEmpty())?.id ?: ag?.provider.orEmpty()
                        "agent:$targetId" to "$prov / ${ag?.model.orEmpty()}"
                    }
                    TranslationKind.META -> {
                        val s = secondaries.firstOrNull { it.id == targetId }
                        val prov = AppService.findById(s?.providerId.orEmpty())?.id ?: s?.providerId.orEmpty()
                        val name = s?.metaPromptName?.takeIf { it.isNotBlank() }
                            ?: s?.let { com.ai.data.legacyKindDisplayName(it.kind) } ?: ""
                        "meta:$targetId" to "$name: $prov / ${s?.model.orEmpty()}"
                    }
                }
                val status = when {
                    row.errorMessage != null -> TranslationStatus.ERROR
                    !row.content.isNullOrBlank() -> TranslationStatus.DONE
                    else -> return@mapNotNull null  // stale placeholder; the resume dispatch covers it
                }
                TranslationItem(
                    id = itemId, label = label, kind = kind,
                    sourceText = "",
                    target = targetId.takeIf { kind != TranslationKind.PROMPT },
                    status = status,
                    translatedText = row.content,
                    errorMessage = row.errorMessage,
                    costDollars = (row.inputCost ?: 0.0) + (row.outputCost ?: 0.0),
                    tokenUsage = row.tokenUsage,
                    durationMs = row.durationMs
                )
            }
        } else emptyList()

        // Merge our items into _translationRuns under this runId so
        // runOneTranslation can read the active TranslationRunState.
        // If a state already exists (live run still in flight),
        // append; otherwise create one seeded with already-settled rows
        // so the detail screen displays the full set of entries.
        _translationRuns.update { runs ->
            val cur = runs[runId]
            val merged = if (cur != null) cur.copy(items = cur.items + items)
            else TranslationRunState(
                runId = runId,
                sourceReportId = sourceReportId,
                targetLanguageName = targetLanguageName,
                targetLanguageNative = targetLanguageNative,
                items = persistedItems + items,
                totalCostDollars = persistedItems.sumOf { it.costDollars }
            )
            runs + (runId to merged)
        }

        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        try {
            withTracerTags(reportId = sourceReportId, category = "Translation") {
                val sem = Semaphore(3)
                coroutineScope {
                    items.map { item ->
                        async {
                            sem.withPermit {
                                runOneTranslation(runId, context, provider, apiKey, model, baseUrl, template, targetLanguageName, item, pricing)
                            }
                        }
                    }.awaitAll()
                }
            }
            ReportStorage.bumpReportTimestamp(context, sourceReportId)
        } finally {
            appViewModel.updateUiState {
                it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0))
            }
        }
    }
}
