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
        if (!appViewModel.uiState.value.generalSettings.iconGenEnabled) return
        val iconPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "icons" && it.name == "main"
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
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            withTracerTags(reportId = reportId, category = "icon_main") {
                val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
                runCatching {
                    val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(agent)
                    val response = withTraceFilenameSink(traceSink) {
                        appViewModel.repository.analyzeWithAgent(
                            agent, "", resolved, AgentParameters(),
                            null, context, baseUrl
                        )
                    }
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
                            inputCost = inC, outputCost = outC,
                            traceFile = traceSink.get(),
                            promptUsed = "main"
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
    internal fun kickOffReportTitleGeneration(
        context: Context,
        reportId: String,
        promptText: String,
        aiSettings: Settings
    ) {
        // Master switch — MANUAL mode = user typed a title themselves;
        // never run the LLM call.
        if (appViewModel.uiState.value.generalSettings.reportTitleMode != com.ai.viewmodel.ReportTitleMode.AI) return
        val titlePrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "internal" && it.name == "report_title"
        } ?: return
        val rawAgent = aiSettings.agents.firstOrNull {
            it.name.equals(titlePrompt.agent, ignoreCase = true)
        } ?: return
        val agent = rawAgent.copy(
            apiKey = aiSettings.getEffectiveApiKeyForAgent(rawAgent),
            model = aiSettings.getEffectiveModelForAgent(rawAgent)
        )
        val resolved = titlePrompt.text.replace("@PROMPT@", promptText)
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            withTracerTags(reportId = reportId, category = "report_title") {
                val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
                runCatching {
                    val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(agent)
                    val response = withTraceFilenameSink(traceSink) {
                        appViewModel.repository.analyzeWithAgent(
                            agent, "", resolved, AgentParameters(),
                            null, context, baseUrl
                        )
                    }
                    if (response.error == null) {
                        // Defensive trims — some models wrap output in
                        // quotes or include a "Title: " prefix even
                        // when told not to.
                        val raw = (response.analysis ?: "").trim()
                            .removePrefix("Title:").trim()
                            .removeSurrounding("\"").trim()
                            .removeSurrounding("'").trim()
                            .lineSequence().firstOrNull { it.isNotBlank() }
                            ?.trim().orEmpty()
                        val generated = raw.take(30).ifBlank { "AI Report" }
                        val tu = response.tokenUsage
                        val pricing = PricingCache.getPricing(context, agent.provider, agent.model)
                        val inT = tu?.inputTokens ?: 0
                        val outT = tu?.outputTokens ?: 0
                        val inC = inT * pricing.promptPrice
                        val outC = outT * pricing.completionPrice
                        ReportStorage.updateReportTitleFromAi(
                            context, reportId, generated,
                            inputTokens = inT, outputTokens = outT,
                            inputCost = inC, outputCost = outC,
                            traceFile = traceSink.get(),
                            model = "${agent.provider.id}/${agent.model}",
                            promptUsed = "report_title"
                        )
                        // Keep the in-memory UiState in sync so the
                        // green title row on Manage report updates the
                        // moment the call returns, without waiting for
                        // a navigation event to re-read from disk.
                        appViewModel.updateUiState { st ->
                            if (st.currentReportId == reportId) {
                                st.copy(genericPromptTitle = generated)
                            } else st
                        }
                    } else {
                        ReportStorage.updateReportTitleError(
                            context, reportId, response.error
                        )
                    }
                }.onFailure {
                    ReportStorage.updateReportTitleError(
                        context, reportId,
                        it.message ?: "title-gen failed"
                    )
                }
                appViewModel.updateUiState {
                    it.copy(iconRefreshTick = it.iconRefreshTick + 1)
                }
            }
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
        if (!appViewModel.uiState.value.generalSettings.iconGenEnabled) return
        val languagePrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "internal" && it.name == "language"
        } ?: return
        val rawAgent = aiSettings.agents.firstOrNull {
            it.name.equals(languagePrompt.agent, ignoreCase = true)
        } ?: return
        val agent = rawAgent.copy(
            apiKey = aiSettings.getEffectiveApiKeyForAgent(rawAgent),
            model = aiSettings.getEffectiveModelForAgent(rawAgent)
        )
        val resolved = languagePrompt.text.replace("@PROMPT@", promptText)
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            withTracerTags(reportId = reportId, category = "Language") {
                val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
                val detectedName = runCatching {
                    val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(agent)
                    val response = withTraceFilenameSink(traceSink) {
                        appViewModel.repository.analyzeWithAgent(
                            agent, "", resolved, AgentParameters(),
                            null, context, baseUrl
                        )
                    }
                    if (response.error != null) {
                        ReportStorage.updateReportLanguageError(
                            context, reportId, response.error
                        )
                        return@runCatching null
                    }
                    val name = parseLanguageDetectionResponse(response.analysis)
                    val tu = response.tokenUsage
                    val pricing = PricingCache.getPricing(context, agent.provider, agent.model)
                    val inT = tu?.inputTokens ?: 0
                    val outT = tu?.outputTokens ?: 0
                    val inC = inT * pricing.promptPrice
                    val outC = outT * pricing.completionPrice
                    ReportStorage.updateReportLanguageDetect(
                        context, reportId,
                        name = name,
                        inputTokens = inT, outputTokens = outT,
                        inputCost = inC, outputCost = outC,
                        traceFile = traceSink.get(),
                        rawResponse = response.analysis
                    )
                    if (name.isNullOrBlank()) {
                        ReportStorage.updateReportLanguageError(
                            context, reportId, "unparseable response"
                        )
                    }
                    name
                }.onFailure {
                    ReportStorage.updateReportLanguageError(
                        context, reportId,
                        it.message ?: "language-detection failed"
                    )
                }.getOrNull()
                appViewModel.updateUiState {
                    it.copy(iconRefreshTick = it.iconRefreshTick + 1)
                }
                if (!detectedName.isNullOrBlank()) {
                    kickOffLanguageIconForDetected(context, reportId, detectedName, aiSettings)
                }
            }
        }
    }

    /** Second call in the two-step language flow: picks a fitting
     *  emoji for the already-detected [languageName] using the
     *  bundled `icons/language` prompt (template copied from
     *  `icons/translation`, substitutes `@LANGUAGE@`). Persists the
     *  emoji + second-call cost / tokens / trace into the existing
     *  `Report.languageIcon*` fields so the cost table picks it up
     *  as a row of type `"language-icon"`. Errors only update
     *  [Report.languageIconErrorMessage]; the detected language name
     *  from the first call stays intact. */
    private suspend fun kickOffLanguageIconForDetected(
        context: Context,
        reportId: String,
        languageName: String,
        aiSettings: Settings
    ) {
        val iconPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "icons" && it.name == "language"
        } ?: return
        val rawAgent = aiSettings.agents.firstOrNull {
            it.name.equals(iconPrompt.agent, ignoreCase = true)
        } ?: return
        val agent = rawAgent.copy(
            apiKey = aiSettings.getEffectiveApiKeyForAgent(rawAgent),
            model = aiSettings.getEffectiveModelForAgent(rawAgent)
        )
        val resolved = iconPrompt.text.replace("@LANGUAGE@", languageName)
        withTracerTags(reportId = reportId, category = "icon_language") {
            val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
            runCatching {
                val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(agent)
                val response = withTraceFilenameSink(traceSink) {
                    appViewModel.repository.analyzeWithAgent(
                        agent, "", resolved, AgentParameters(),
                        null, context, baseUrl
                    )
                }
                if (response.error != null) {
                    ReportStorage.updateReportLanguageError(
                        context, reportId, response.error
                    )
                    return@runCatching
                }
                val emoji = extractFirstEmoji(response.analysis.orEmpty()) ?: "🌐"
                val tu = response.tokenUsage
                val pricing = PricingCache.getPricing(context, agent.provider, agent.model)
                val inT = tu?.inputTokens ?: 0
                val outT = tu?.outputTokens ?: 0
                val inC = inT * pricing.promptPrice
                val outC = outT * pricing.completionPrice
                ReportStorage.updateReportLanguageIcon(
                    context, reportId,
                    icon = emoji,
                    inputTokens = inT, outputTokens = outT,
                    inputCost = inC, outputCost = outC,
                    traceFile = traceSink.get(),
                    rawResponse = response.analysis,
                    promptUsed = "language"
                )
            }.onFailure {
                ReportStorage.updateReportLanguageError(
                    context, reportId,
                    it.message ?: "language-icon failed"
                )
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
        if (!appViewModel.uiState.value.generalSettings.useInternalPromptsIcons) return
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
            withTracerTags(category = "icon_meta") {
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
        reportId: String? = null
    ) {
        if (prompt.name.isBlank()) return
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
    // bundled `fan_out_alt` (the nudge) FIRST, then `fan_out` (the
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
            it.category == "icons" && it.name == "fan_out"
        } ?: run {
            AppLog.w("PairIconAlt", "internal/fan_out prompt not found — skipping (pair=$pairId)")
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
        if (!appViewModel.uiState.value.generalSettings.useInternalPromptsIcons) return
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
            withTracerTags(category = "icon_translation") {
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
        // Find-alternative-icons composes `report` (the base
        // template — tier-2 of the per-agent 3-tier chain, with
        // @PROMPT@ + @RESPONSE@ slots) + blank line + `report_alt`
        // (the "pick something distinct" nudge) so the alt stays
        // short.
        val basePrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "icons" && it.name == "report"
        } ?: run {
            AppLog.w("AgentIconAlt", "internal/report prompt not found — skipping (agent=$agentId)")
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
            it.category == "icons" && it.name == "report_2"
        }
        val tier2Prompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "icons" && it.name == "report"
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
        reportPrompt: String, agentResponse: String, aiSettings: Settings
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

    /** Tier 2 of [runReportIcons]: one-shot icons/report
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
     *  whichever Agent matches the report_3 prompt's pinned
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
                withTracerTags(reportId = reportId, category = "icon_report_3") {
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
        // Map tier number to the bundled prompt name that produced
        // the icon — surfaces on the Icon lookup screen's subject row.
        val promptUsed = when (winningTier) {
            1 -> "report_2"
            2 -> "report"
            3 -> "report_3"
            else -> null
        }
        ReportStorage.setReportAgentIconAndTier(
            context, reportId, agentId, emoji, winningTier, promptUsed = promptUsed
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

    /** Per-pair 3-tier icon chain. Returns when the chain has
     *  committed a result (emoji + winning tier, or 📝 fallback)
     *  to disk for [pair] — OR early, without committing, when a
     *  tier is rate-limited (429): the chain stops, the pair's host
     *  is added to [rateLimitedHosts] so the batch skips its other
     *  pairs, and the pair is left icon-less for a later relaunch.
     *  Suspending — the caller is responsible for outer cap
     *  acquisition. */
    private suspend fun runIconChainForPair(
        context: Context, reportId: String,
        pair: SecondaryResult,
        metaPromptText: String,
        reportPrompt: String,
        sourceResponse: String,
        aiSettings: Settings,
        rateLimitedHosts: MutableSet<String>
    ) {
        val chatPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "icons" && it.name == "fan_out_2"
        }
        val tier2Prompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "icons" && it.name == "fan_out"
        }
        val tier3Prompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "icons" && it.name == "fan_out_3"
        }
        if (chatPrompt == null && tier2Prompt == null && tier3Prompt == null) {
            AppLog.w("FanOutIcons", "no icon prompts configured — skipping (pair=${pair.id})")
            return
        }
        val pairProvider = AppService.findById(pair.providerId) ?: return
        val pairContent = pair.content
        if (pairContent.isNullOrBlank()) return
        val pairHost = providerHost(pairProvider)

        val tier1 = chatPrompt?.let { p ->
            runFanOutTier1(
                context, reportId, pairProvider, pair, p,
                reportPrompt, sourceResponse, metaPromptText, pairContent, aiSettings
            )
        } ?: TierResult.Miss
        when (tier1) {
            is TierResult.Emoji -> {
                commitFanOutIconResult(context, reportId, pair.id, tier1.value, winningTier = 1)
                return
            }
            TierResult.RateLimited -> {
                rateLimitedHosts.add(pairHost)
                AppLog.w("FanOutIcons", "tier 1 rate-limited (429) for pair=${pair.id} on $pairHost — chain stopped")
                // Persist an error so the L1/L2/L3 row flips to ❌
                // instead of sitting at 🕓 forever. Relaunching the
                // fan-icons batch will retry this pair (pending
                // filter gates on icon == null).
                SecondaryResultStorage.setFanOutIconError(
                    context, reportId, pair.id,
                    "rate-limited at tier 1 (chat-continuation) — host $pairHost hit 429, relaunch to retry"
                )
                return
            }
            TierResult.Miss -> { /* cascade to tier 2 */ }
        }

        val tier2 = tier2Prompt?.let { p ->
            runFanOutTier2(
                context, reportId, pairProvider, pair, p,
                reportPrompt, sourceResponse, metaPromptText, pairContent, aiSettings
            )
        } ?: TierResult.Miss
        when (tier2) {
            is TierResult.Emoji -> {
                commitFanOutIconResult(context, reportId, pair.id, tier2.value, winningTier = 2)
                return
            }
            TierResult.RateLimited -> {
                rateLimitedHosts.add(pairHost)
                AppLog.w("FanOutIcons", "tier 2 rate-limited (429) for pair=${pair.id} on $pairHost — chain stopped")
                SecondaryResultStorage.setFanOutIconError(
                    context, reportId, pair.id,
                    "rate-limited at tier 2 (one-shot) — host $pairHost hit 429, relaunch to retry"
                )
                return
            }
            TierResult.Miss -> { /* cascade to tier 3 */ }
        }

        val tier3 = tier3Prompt?.let { p ->
            runFanOutTier3(context, reportId, pair, p, pairContent, aiSettings)
        } ?: TierResult.Miss
        when (tier3) {
            is TierResult.Emoji -> {
                commitFanOutIconResult(context, reportId, pair.id, tier3.value, winningTier = 3)
                return
            }
            TierResult.RateLimited -> {
                // Tier 3 is the shared fixed agent — don't mark its
                // host (that would wrongly skip pairs whose own model
                // is that provider). Just stop this pair's chain.
                AppLog.w("FanOutIcons", "tier 3 rate-limited (429) for pair=${pair.id} — chain stopped")
                SecondaryResultStorage.setFanOutIconError(
                    context, reportId, pair.id,
                    "rate-limited at tier 3 (fixed agent) — relaunch to retry"
                )
                return
            }
            TierResult.Miss -> { /* fall through to the 📝 fallback */ }
        }

        commitFanOutIconResult(context, reportId, pair.id, "📝", winningTier = null)
    }

    /** Launch a fan-icons batch — generate emojis for every
     *  successful pair of the fan-out identified by
     *  ([reportId], [metaPromptId]) that doesn't have one yet.
     *  Dispatched with the same suspending-semaphore + per-host
     *  throttle plumbing as a primary fan-out, gated by the
     *  dedicated [ApiCallCaps.fanIcons] cap. Pairs that already
     *  have an icon are skipped (use [relaunchFanIconsBatch] to
     *  re-fire everything). De-duped on a second launch attempt:
     *  the existing job is returned if one is already in flight
     *  for the same (reportId, metaPromptId). */
    fun runFanIconsBatch(
        context: Context,
        reportId: String,
        metaPromptId: String
    ): Job? {
        rvm.fanIconsJobs[rvm.fanIconsJobKey(reportId, metaPromptId)]?.let { existing ->
            if (existing.isActive) return existing
        }
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        val iconRunId = java.util.UUID.randomUUID().toString()
        val job = appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            try {
                val state = appViewModel.uiState.value
                val aiSettings = state.aiSettings
                val metaPrompt = aiSettings.internalPrompts.firstOrNull { it.id == metaPromptId }
                    ?: return@launch
                val report = ReportStorage.getReport(context, reportId) ?: return@launch
                val sourceBodies = report.agents
                    .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                    .associate { it.agentId to it.responseBody!! }
                val resolvedBase = resolveSecondaryPrompt(
                    metaPrompt.text,
                    question = report.prompt,
                    results = "",
                    count = sourceBodies.size,
                    title = report.title
                )

                // Pairs to process: same metaPromptId, has fan-out source,
                // has content (chain needs something to look at), AND no
                // emoji yet (skip already-done pairs).
                val pending = SecondaryResultStorage
                    .listForReport(context, reportId, SecondaryKind.META)
                    .filter {
                        it.metaPromptId == metaPromptId &&
                            it.fanOutSourceAgentId != null &&
                            it.fanInOf == null &&
                            !it.content.isNullOrBlank() &&
                            it.icon.isNullOrBlank()
                    }
                if (pending.isEmpty()) {
                    AppLog.i("FanIcons", "no pending pairs for ${metaPrompt.name} on $reportId — nothing to do")
                    return@launch
                }
                AppLog.i("FanIcons", "→ start ${metaPrompt.name} (report=$reportId, ${pending.size} pairs)")

                // Per-host caps mirror the fan-out path.
                val perHostCaps: Map<String, kotlinx.coroutines.sync.Semaphore> = pending
                    .mapNotNull { AppService.findById(it.providerId) }
                    .map { providerHost(it) }
                    .distinct()
                    .associateWith { host ->
                        val (_, concurrent) = ProviderThrottle.limitsFor(host)
                        kotlinx.coroutines.sync.Semaphore(concurrent)
                    }
                // Hosts that returned a 429 during this batch. Once a
                // host is in here, the icon chain stopped a pair on it;
                // remaining pairs on that host skip immediately rather
                // than firing another doomed call. Thread-safe — many
                // per-pair coroutines read/write it concurrently.
                val rateLimitedHosts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

                withTracerTags(reportId = reportId, category = "icon_fan_out", runId = iconRunId) {
                    coroutineScope {
                        interleaveByHost(pending) { p ->
                            AppService.findById(p.providerId)?.let { providerHost(it) }
                        }.map { pair ->
                            async(start = CoroutineStart.LAZY) {
                                val provider = AppService.findById(pair.providerId) ?: return@async
                                val host = providerHost(provider)
                                if (host in rateLimitedHosts) {
                                    AppLog.d("FanIcons", "skip pair ${pair.id} — host $host rate-limited earlier this batch")
                                    // Persist a sentinel so the UI flips
                                    // from PENDING (🕓 forever) to ERROR
                                    // (❌). Relaunching the batch picks
                                    // these up (the `pending` filter
                                    // gates on `icon == null`, not on
                                    // errorMessage), so the user can
                                    // retry without first clearing.
                                    SecondaryResultStorage.setFanOutIconError(
                                        context, reportId, pair.id,
                                        "rate-limited — host $host hit 429 mid-batch, relaunch to retry"
                                    )
                                    return@async
                                }
                                val hostCap = perHostCaps[host]
                                    ?: kotlinx.coroutines.sync.Semaphore(1)
                                val sourceBody = sourceBodies[pair.fanOutSourceAgentId.orEmpty()].orEmpty()
                                val resolvedMeta = resolvedBase.replace("@RESPONSE@", sourceBody)

                                // Acquire order mirrors runFanOutPrompt: hostCap
                                // first, then the non-blocking ProviderThrottle
                                // gate (yields on a capped host instead of
                                // Thread.sleep), then global, then fan-icons cap.
                                hostCap.withPermit {
                                    val releaser = acquireOrRequeue(
                                        host,
                                        onThrottled = { appViewModel.updateThrottledFanIconsPairs { it + pair.id } },
                                        onCleared = { appViewModel.updateThrottledFanIconsPairs { it - pair.id } }
                                    )
                                    try {
                                        ApiCallCaps.global.withPermit {
                                            ApiCallCaps.fanIcons.withPermit {
                                                if (!SecondaryResultStorage.exists(context, reportId, pair.id)) {
                                                    AppLog.d("FanIcons", "skip pair ${pair.id} — deleted before launch")
                                                    return@async
                                                }
                                                withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                                                    appViewModel.updateRunningFanIconsPairs { it + pair.id }
                                                    val pairStart = System.currentTimeMillis()
                                                    try {
                                                        runIconChainForPair(
                                                            context, reportId, pair,
                                                            metaPromptText = resolvedMeta,
                                                            reportPrompt = report.prompt,
                                                            sourceResponse = sourceBody,
                                                            aiSettings = aiSettings,
                                                            rateLimitedHosts = rateLimitedHosts
                                                        )
                                                    } finally {
                                                        appViewModel.updateRunningFanIconsPairs { it - pair.id }
                                                        AppLog.d("FanIcons", "← pair ${pair.id} ${System.currentTimeMillis() - pairStart}ms")
                                                    }
                                                }
                                            }
                                        }
                                    } finally {
                                        releaser.release()
                                    }
                                }
                            }.also { it.start() }
                        }.awaitAll()
                    }
                }
                AppLog.i("FanIcons", "← end ${metaPrompt.name} (report=$reportId)")
            } finally {
                appViewModel.updateUiState {
                    it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0))
                }
            }
        }
        rvm.registerFanIconsJob(reportId, metaPromptId, job)
        return job
    }

    /** Re-fire the fan-icons chain on every pair of this fan-out,
     *  including ones that already have an emoji. Clears each
     *  pair's prior icon / iconError fields first via
     *  [SecondaryResultStorage.clearFanOutIconState] so the new
     *  run starts from a clean slate. */
    fun relaunchFanIconsBatch(
        context: Context,
        reportId: String,
        metaPromptId: String
    ): Job? {
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            val existing = SecondaryResultStorage
                .listForReport(context, reportId, SecondaryKind.META)
                .filter {
                    it.metaPromptId == metaPromptId &&
                        it.fanOutSourceAgentId != null &&
                        it.fanInOf == null
                }
            for (e in existing) SecondaryResultStorage.clearFanOutIconState(context, reportId, e.id)
        }.let { /* fire-and-forget */ }
        // Kick off the regular batch — it now sees every pair as
        // "icon-less" and dispatches them.
        return runFanIconsBatch(context, reportId, metaPromptId)
    }

    /** Cancel the in-flight fan-icons batch for this fan-out, if
     *  any. The currently-running per-pair chains finish their
     *  HTTP call; queued pairs are dropped. */
    fun cancelFanIconsBatch(reportId: String, metaPromptId: String) {
        rvm.fanIconsJobs[rvm.fanIconsJobKey(reportId, metaPromptId)]?.cancel()
    }

    /** Wipe the icon state on every fan-out pair of [metaPromptId]
     *  whose icon-chain failed (iconErrorMessage != null). Doesn't
     *  drop the pair row — just the icon + iconError + tier info,
     *  so the L1/L2/L3 classifier reads the pair as "no icon yet"
     *  rather than ❌. A subsequent fan-icons batch will pick them
     *  up via the standard `icon == null` pending filter. */
    /** A pair counts as "in error from the icon-chain's POV"
     *  whenever it has either an explicit iconErrorMessage stamp
     *  OR landed as a "no content, but the original call
     *  finished" SR (Gemini safety filter, etc.). The latter
     *  can't ever produce an icon — runFanIconsBatch skips
     *  no-content pairs at its pending filter — but iconStatus
     *  still surfaces them as ERROR, so the L1 stats counter
     *  treats them as errors and so should Remove / Restart. */
    private fun isFanIconError(sr: SecondaryResult): Boolean =
        !sr.iconErrorMessage.isNullOrBlank() ||
            (sr.content.isNullOrBlank() && (sr.errorMessage != null || sr.durationMs != null))

    fun clearFanIconErrors(context: Context, reportId: String, metaPromptId: String) {
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            withContext(Dispatchers.IO) {
                val errored = SecondaryResultStorage
                    .listForReport(context, reportId, SecondaryKind.META)
                    .filter {
                        it.metaPromptId == metaPromptId &&
                            it.fanOutSourceAgentId != null &&
                            it.fanInOf == null &&
                            isFanIconError(it)
                    }
                for (e in errored) {
                    if (e.content.isNullOrBlank()) {
                        // No-content pair — the icon chain can never
                        // run on it, so the user pressing Remove or
                        // Restart on this row should commit the 📝
                        // sentinel as a permanent "no source content
                        // to inspect" marker. The pair flips to DONE
                        // in iconStatus and stops appearing as an
                        // error on subsequent loads.
                        SecondaryResultStorage.setFanOutIconAndTier(
                            context, reportId, e.id,
                            icon = "📝", winningTier = null,
                            promptUsed = null
                        )
                    } else {
                        SecondaryResultStorage.clearFanOutIconState(context, reportId, e.id)
                    }
                }
                AppLog.i(
                    "FanIcons",
                    "cleared icon state on ${errored.size} errored pair(s) for ${metaPromptId.take(8)}"
                )
            }
            appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
        }
    }

    /** Clear errors via the [isFanIconError] filter, then re-fire
     *  the fan-icons batch. Pairs with content get their icon
     *  state cleared and a fresh chain attempt; no-content pairs
     *  get the 📝 fallback stamped directly because the batch's
     *  pending filter would skip them anyway. */
    fun restartFanIconErrors(context: Context, reportId: String, metaPromptId: String): Job? {
        appViewModel.viewModelScope.launch(rvm.reportLogContext(reportId)) {
            withContext(Dispatchers.IO) {
                val errored = SecondaryResultStorage
                    .listForReport(context, reportId, SecondaryKind.META)
                    .filter {
                        it.metaPromptId == metaPromptId &&
                            it.fanOutSourceAgentId != null &&
                            it.fanInOf == null &&
                            isFanIconError(it)
                    }
                var cleared = 0
                var stamped = 0
                for (e in errored) {
                    if (e.content.isNullOrBlank()) {
                        SecondaryResultStorage.setFanOutIconAndTier(
                            context, reportId, e.id,
                            icon = "📝", winningTier = null,
                            promptUsed = null
                        )
                        stamped++
                    } else {
                        SecondaryResultStorage.clearFanOutIconState(context, reportId, e.id)
                        cleared++
                    }
                }
                AppLog.i(
                    "FanIcons",
                    "restart: $cleared pair(s) cleared for re-chain, $stamped no-content pair(s) stamped 📝"
                )
            }
            appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
        }
        return runFanIconsBatch(context, reportId, metaPromptId)
    }

    private suspend fun runFanOutTier1(
        context: Context, reportId: String, provider: AppService,
        pair: SecondaryResult, chatPrompt: InternalPrompt,
        reportPrompt: String, sourceResponse: String,
        metaPromptText: String, pairContent: String,
        aiSettings: Settings
    ): TierResult {
        // ProviderThrottle for this pair's host is already held by
        // runFanIconsBatch (acquireOrRequeue) — re-acquiring the
        // non-reentrant per-host semaphore here deadlocked the batch.
        // permitPreAcquired is inherited from the batch's context.
        return run {
            withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                withTracerTags(reportId = reportId, category = "icon_fan_out_2") {
                    val started = System.currentTimeMillis()
                    runCatching {
                        // Reproduce the pair's actual conversation: the
                        // pair was sent ONE user message — the resolved
                        // meta prompt with @RESPONSE@ substituted to the
                        // source body — and produced ONE assistant
                        // response (pairContent). Then we add the chat-
                        // continuation icon prompt. The previous 5-turn
                        // shape (report prompt → source response → meta
                        // → pair → ask) prepended a 2-turn exchange the
                        // pair never actually had — confusing the model
                        // and yielding a duplicate `metaPromptText` ==
                        // sourceResponse cell when the meta template
                        // was bare `@RESPONSE@`.
                        val messages = listOf(
                            ChatMessage(role = "user", content = metaPromptText),
                            ChatMessage(role = "assistant", content = pairContent),
                            ChatMessage(role = "user", content = chatPrompt.text)
                        )
                        val apiKey = aiSettings.getApiKey(provider)
                        val baseUrl = aiSettings.getEffectiveEndpointUrl(provider)
                        // Sink captures the filename of this call's trace
                        // so a tier-1 miss can tag it "-miss" afterwards.
                        val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
                        val responseText = withTraceFilenameSink(traceSink) {
                            appViewModel.repository.sendChat(
                                service = provider, apiKey = apiKey, model = pair.model,
                                messages = messages, params = ChatParameters(), baseUrl = baseUrl
                            )
                        }
                        val durationMs = System.currentTimeMillis() - started
                        val inT = messages.sumOf { AppViewModel.estimateTokens(it.content) }
                        val outT = AppViewModel.estimateTokens(responseText)
                        val emoji = extractFirstEmoji(responseText)
                        // Tier-1 miss → tag this call's trace "-miss" so
                        // tier-1 misses can be filtered / analysed later.
                        if (emoji == null) {
                            traceSink.get()?.let { ApiTracer.appendCategorySuffix(it, "-miss") }
                        }
                        recordFanOutTierCall(
                            context, reportId, pair, tier = 1,
                            provider = provider, model = pair.model,
                            inT = inT, outT = outT, durationMs = durationMs,
                            success = emoji != null
                        )
                        if (emoji != null) TierResult.Emoji(emoji) else TierResult.Miss
                    }.getOrElse { e ->
                        AppLog.w("FanOutIcons", "tier 1 failed for pair=${pair.id}: ${e.message}")
                        if (isRateLimitFailure(e)) TierResult.RateLimited else TierResult.Miss
                    }
                }
            }
        }
    }

    private suspend fun runFanOutTier2(
        context: Context, reportId: String, provider: AppService,
        pair: SecondaryResult, tier2Prompt: InternalPrompt,
        reportPrompt: String, sourceResponse: String,
        metaPromptText: String, pairContent: String,
        aiSettings: Settings
    ): TierResult {
        // ProviderThrottle for this pair's host is already held by
        // runFanIconsBatch (acquireOrRequeue) — re-acquiring the
        // non-reentrant per-host semaphore here deadlocked the batch.
        // permitPreAcquired is inherited from the batch's context.
        return run {
            withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                withTracerTags(reportId = reportId, category = "icon_fan_out") {
                    val started = System.currentTimeMillis()
                    runCatching {
                        val syntheticAgent = Agent(
                            id = "fan-out-icon-tier2-${pair.id}",
                            name = pair.agentName,
                            provider = provider,
                            model = pair.model,
                            apiKey = aiSettings.getApiKey(provider)
                        )
                        val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(syntheticAgent)
                        val resolved = tier2Prompt.text
                            .replace("@QUESTION@", reportPrompt)
                            .replace("@SOURCE_RESPONSE@", sourceResponse)
                            .replace("@META_PROMPT@", metaPromptText)
                            .replace("@RESPONSE@", pairContent)
                        val response = appViewModel.repository.analyzeWithAgent(
                            syntheticAgent, "", resolved, AgentParameters(),
                            null, context, baseUrl
                        )
                        val durationMs = System.currentTimeMillis() - started
                        val tu = response.tokenUsage
                        val inT = tu?.inputTokens ?: 0
                        val outT = tu?.outputTokens ?: 0
                        val emoji = if (response.error == null) extractFirstEmoji(response.analysis) else null
                        recordFanOutTierCall(
                            context, reportId, pair, tier = 2,
                            provider = provider, model = pair.model,
                            inT = inT, outT = outT, durationMs = durationMs,
                            success = emoji != null
                        )
                        when {
                            emoji != null -> TierResult.Emoji(emoji)
                            response.httpStatusCode == 429 -> TierResult.RateLimited
                            else -> TierResult.Miss
                        }
                    }.getOrElse { e ->
                        AppLog.w("FanOutIcons", "tier 2 failed for pair=${pair.id}: ${e.message}")
                        if (isRateLimitFailure(e)) TierResult.RateLimited else TierResult.Miss
                    }
                }
            }
        }
    }

    private suspend fun runFanOutTier3(
        context: Context, reportId: String,
        pair: SecondaryResult, tier3Prompt: InternalPrompt,
        pairContent: String, aiSettings: Settings
    ): TierResult {
        val rawAgent = aiSettings.agents.firstOrNull {
            it.name.equals(tier3Prompt.agent, ignoreCase = true)
        } ?: run {
            AppLog.w("FanOutIcons", "tier 3 skipped — no agent matching '${tier3Prompt.agent}' configured")
            return TierResult.Miss
        }
        val effectiveAgent = rawAgent.copy(
            apiKey = aiSettings.getEffectiveApiKeyForAgent(rawAgent),
            model = aiSettings.getEffectiveModelForAgent(rawAgent)
        )
        // ProviderThrottle is already held by runFanIconsBatch
        // (acquireOrRequeue) for this pair — re-acquiring the
        // non-reentrant per-host semaphore here deadlocked the batch.
        // permitPreAcquired is inherited from the batch's context.
        return run {
            withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                withTracerTags(reportId = reportId, category = "icon_fan_out_3") {
                    val started = System.currentTimeMillis()
                    runCatching {
                        val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(effectiveAgent)
                        val resolved = tier3Prompt.text.replace("@RESPONSE@", pairContent)
                        val response = appViewModel.repository.analyzeWithAgent(
                            effectiveAgent, "", resolved, AgentParameters(),
                            null, context, baseUrl
                        )
                        val durationMs = System.currentTimeMillis() - started
                        val tu = response.tokenUsage
                        val inT = tu?.inputTokens ?: 0
                        val outT = tu?.outputTokens ?: 0
                        val emoji = if (response.error == null) extractFirstEmoji(response.analysis) else null
                        recordFanOutTierCall(
                            context, reportId, pair, tier = 3,
                            // Cost attribution goes to the actual model
                            // that ran (DeepSeek), matching the report-
                            // icon tier 3 behaviour.
                            provider = effectiveAgent.provider, model = effectiveAgent.model,
                            inT = inT, outT = outT, durationMs = durationMs,
                            success = emoji != null
                        )
                        when {
                            emoji != null -> TierResult.Emoji(emoji)
                            response.httpStatusCode == 429 -> TierResult.RateLimited
                            else -> TierResult.Miss
                        }
                    }.getOrElse { e ->
                        AppLog.w("FanOutIcons", "tier 3 failed for pair=${pair.id}: ${e.message}")
                        if (isRateLimitFailure(e)) TierResult.RateLimited else TierResult.Miss
                    }
                }
            }
        }
    }

    /** Shared write-side of a fan-out icon tier call. Bumps the per-
     *  pair iconInput/OutputCost on the SecondaryResult so the row's
     *  L2 / L1 cost cells absorb the cost, updates the global
     *  UsageStats ledger with kind="icon" attributed to the actual
     *  (provider, model) that billed, and appends an IconCallRecord
     *  to Report.iconCalls for the export's per-call All-tab.
     *
     *  IconCallRecord.agentId is set to the pair's UUID so the audit
     *  log can distinguish fan-out icon rows from per-agent icon
     *  rows (which use the agentId of the parent ReportAgent). */
    private suspend fun recordFanOutTierCall(
        context: Context, reportId: String, pair: SecondaryResult, tier: Int,
        provider: AppService, model: String,
        inT: Int, outT: Int, durationMs: Long, success: Boolean
    ) {
        val pricing = PricingCache.getPricing(context, provider, model)
        val inC = inT * pricing.promptPrice
        val outC = outT * pricing.completionPrice
        if (inT > 0 || outT > 0) {
            SecondaryResultStorage.bumpFanOutIconCost(
                context, reportId, pair.id,
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
                agentId = pair.id, tier = tier,
                provider = provider.id, model = model,
                pricingTier = pricing.source,
                inputTokens = inT, outputTokens = outT,
                inputCost = inC, outputCost = outC,
                durationMs = durationMs,
                success = success
            )
        )
    }

    private suspend fun commitFanOutIconResult(
        context: Context, reportId: String, pairId: String,
        emoji: String, winningTier: Int?
    ) {
        // Map tier number to the bundled prompt name for the Icon
        // lookup screen's subject row.
        val promptUsed = when (winningTier) {
            1 -> "fan_out_2"
            2 -> "fan_out"
            3 -> "fan_out_3"
            else -> null
        }
        SecondaryResultStorage.setFanOutIconAndTier(
            context, reportId, pairId, emoji, winningTier,
            iconRunId = ApiTracer.currentRunId,
            promptUsed = promptUsed
        )
        appViewModel.updateUiState {
            it.copy(iconRefreshTick = it.iconRefreshTick + 1)
        }
    }
}
