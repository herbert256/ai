package com.ai.viewmodel

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.data.*
import com.ai.data.local.LocalEmbedder
import com.ai.data.local.LocalLlm
import com.ai.model.*
import com.ai.ui.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class AppViewModel(application: Application) : AndroidViewModel(application) {
    internal val repository = AnalysisRepository()
    internal val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    internal val settingsPrefs = SettingsPreferences(prefs, application.filesDir)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Singleton Job for the app-wide background resume sweep
     *  ([com.ai.viewmodel.ReportViewModel.startBackgroundResumeSweep]).
     *  Lives on AppViewModel so it survives Activity config
     *  changes — the LaunchedEffect that starts the sweep is
     *  inside a Composable whose lifetime is shorter than the
     *  loop's. Cancel-prior pattern inside the start method
     *  guarantees only one loop runs at a time even if Compose
     *  re-fires the effect. */
    @Volatile var backgroundResumeSweepJob: Job? = null

    // NOTE: the legacy `runningFanOutPairs` StateFlow was removed — the
    // FanOutEngine's per-pair PairStatus in its StateFlow is now the single
    // source of truth for "is this pair running". Consumers derive a
    // running-id set from the engine flow where they still need one.

    /** Pair ids currently blocked inside
     *  [com.ai.data.ProviderThrottle.acquire] — i.e. waiting on
     *  the provider's per-minute sliding window before the actual
     *  HTTP call. Surfaces in the L1 stats panel as a "Throttled"
     *  counter so users can tell apart "queued behind a cap" from
     *  "queued behind a provider rate limit". */
    private val _throttledFanOutPairs = MutableStateFlow<Set<String>>(emptySet())
    val throttledFanOutPairs: StateFlow<Set<String>> = _throttledFanOutPairs.asStateFlow()
    internal fun updateThrottledFanOutPairs(block: (Set<String>) -> Set<String>) {
        _throttledFanOutPairs.update(block)
    }

    /** Pair ids currently mid-fan-meta call. Parallel to
     *  [runningFanOutPairs] but for the fan-meta batch — the
     *  L1 META-mode stats panel reads from this. */
    private val _runningFanMetaPairs = MutableStateFlow<Set<String>>(emptySet())
    val runningFanMetaPairs: StateFlow<Set<String>> = _runningFanMetaPairs.asStateFlow()
    internal fun updateRunningFanMetaPairs(block: (Set<String>) -> Set<String>) {
        _runningFanMetaPairs.update(block)
    }

    /** Row ids of single-call secondaries (auto/manual Meta, Rerank,
     *  Moderation) whose call is actively in flight (incl. waiting in the
     *  per-provider rate gate). The resume sweep unions this with
     *  [runningFanOutPairs] so a slow-but-running single meta isn't mistaken
     *  for "stale" and falsely terminalized as "Interrupted". Kept separate
     *  from [runningFanOutPairs] so the fan-out UI's running-pair count
     *  isn't polluted. */
    private val _runningSingleSecondaries = MutableStateFlow<Set<String>>(emptySet())
    val runningSingleSecondaries: StateFlow<Set<String>> = _runningSingleSecondaries.asStateFlow()
    internal fun updateRunningSingleSecondaries(block: (Set<String>) -> Set<String>) {
        _runningSingleSecondaries.update(block)
    }

    /** Keys of report-level info jobs (report icon / language / title) whose
     *  API call is ACTIVELY in flight. Keyed "<reportId>|<type>". The
     *  Report - Get info rows read this so a job shows the clock (queued)
     *  until its call really starts, then the animated hourglass. Cleared
     *  when the call finishes (success or error). */
    private val _runningInfoJobs = MutableStateFlow<Set<String>>(emptySet())
    val runningInfoJobs: StateFlow<Set<String>> = _runningInfoJobs.asStateFlow()
    internal fun updateRunningInfoJobs(block: (Set<String>) -> Set<String>) {
        _runningInfoJobs.update(block)
    }

    /** Pair ids whose fan-meta attempt is blocked inside
     *  [com.ai.data.ProviderThrottle.acquire]. Same role as
     *  [throttledFanOutPairs] for the fan-meta batch. */
    private val _throttledFanMetaPairs = MutableStateFlow<Set<String>>(emptySet())
    val throttledFanMetaPairs: StateFlow<Set<String>> = _throttledFanMetaPairs.asStateFlow()
    internal fun updateThrottledFanMetaPairs(block: (Set<String>) -> Set<String>) {
        _throttledFanMetaPairs.update(block)
    }

    /** Tournament match row ids whose worker call is actively in flight —
     *  the Tournament L1 stats panel reads this (parallel to
     *  [runningFanMetaPairs]). */
    private val _runningTournamentMatches = MutableStateFlow<Set<String>>(emptySet())
    val runningTournamentMatches: StateFlow<Set<String>> = _runningTournamentMatches.asStateFlow()
    internal fun updateRunningTournamentMatches(block: (Set<String>) -> Set<String>) {
        _runningTournamentMatches.update(block)
    }

    /** Tournament match row ids parked inside
     *  [com.ai.data.ProviderThrottle.acquire] (parallel to
     *  [throttledFanMetaPairs]) — the L1 "Throttled" counter. */
    private val _throttledTournamentMatches = MutableStateFlow<Set<String>>(emptySet())
    val throttledTournamentMatches: StateFlow<Set<String>> = _throttledTournamentMatches.asStateFlow()
    internal fun updateThrottledTournamentMatches(block: (Set<String>) -> Set<String>) {
        _throttledTournamentMatches.update(block)
    }

    /** Judge-eval ("Judge the judges") cell row ids whose judge call is
     *  actively in flight — the batch L1 "Run" stat (parallel to
     *  [runningTournamentMatches]). */
    private val _runningJudgeEvalCells = MutableStateFlow<Set<String>>(emptySet())
    val runningJudgeEvalCells: StateFlow<Set<String>> = _runningJudgeEvalCells.asStateFlow()
    internal fun updateRunningJudgeEvalCells(block: (Set<String>) -> Set<String>) {
        _runningJudgeEvalCells.update(block)
    }

    /** Judge-eval cell row ids parked on a provider's rate/concurrency gate
     *  — the batch L1 "Wait" counter (parallel to [throttledTournamentMatches]). */
    private val _throttledJudgeEvalCells = MutableStateFlow<Set<String>>(emptySet())
    val throttledJudgeEvalCells: StateFlow<Set<String>> = _throttledJudgeEvalCells.asStateFlow()
    internal fun updateThrottledJudgeEvalCells(block: (Set<String>) -> Set<String>) {
        _throttledJudgeEvalCells.update(block)
    }

    /** "Compare with meta" cell row ids whose worker call is actively in
     *  flight — the batch L1 "Run" stat (parallel to
     *  [runningTournamentMatches]). */
    private val _runningCompareCells = MutableStateFlow<Set<String>>(emptySet())
    val runningCompareCells: StateFlow<Set<String>> = _runningCompareCells.asStateFlow()
    internal fun updateRunningCompareCells(block: (Set<String>) -> Set<String>) {
        _runningCompareCells.update(block)
    }

    /** Compare cell row ids parked on a provider's rate/concurrency gate —
     *  the batch L1 "Wait" counter (parallel to [throttledTournamentMatches]). */
    private val _throttledCompareCells = MutableStateFlow<Set<String>>(emptySet())
    val throttledCompareCells: StateFlow<Set<String>> = _throttledCompareCells.asStateFlow()
    internal fun updateThrottledCompareCells(block: (Set<String>) -> Set<String>) {
        _throttledCompareCells.update(block)
    }

    /** Translation item ids currently parked on a provider's rate /
     *  concurrency gate (the dispatcher's [acquireOrRequeue] wait). Surfaces
     *  as the Translation L1 "Throttled" column — same role as
     *  [throttledFanMetaPairs] for translation. An item is throttled while
     *  still PENDING (RUNNING is set only after the gate), so the column
     *  carves out of Queue, not Run. */
    private val _throttledTranslationItems = MutableStateFlow<Set<String>>(emptySet())
    val throttledTranslationItems: StateFlow<Set<String>> = _throttledTranslationItems.asStateFlow()
    internal fun updateThrottledTranslationItems(block: (Set<String>) -> Set<String>) {
        _throttledTranslationItems.update(block)
    }

    /** Live state of any "Find alternative icons" fan-out, keyed by
     *  reportId. Lives outside [UiState] for the same reason as
     *  [runningFanOutPairs] — per-call status flips fire faster than
     *  any other UiState field changes and would over-recompose
     *  unrelated screens if bundled with them. Cleared on process
     *  death by design; per-call costs already bumped on the Report
     *  survive. */
    private val _iconFanOutByReport = MutableStateFlow<Map<String, List<IconCandidate>>>(emptyMap())
    val iconFanOutByReport: StateFlow<Map<String, List<IconCandidate>>> = _iconFanOutByReport.asStateFlow()
    internal fun updateIconFanOut(reportId: String, mutator: (List<IconCandidate>) -> List<IconCandidate>) {
        _iconFanOutByReport.update { current ->
            val next = mutator(current[reportId].orEmpty())
            current + (reportId to next)
        }
    }
    /** Drop the entire candidate list for [reportId] — used when the
     *  report is deleted so the map doesn't retain a stale entry for
     *  a no-longer-existing report id. */
    internal fun clearIconFanOut(reportId: String) {
        _iconFanOutByReport.update { it - reportId }
    }

    /** Live state of any "Find alternative icons" run launched from
     *  the per-report language detail screen (the analog of
     *  [iconFanOutByReport] but for the language-detection emoji).
     *  Keyed by reportId. Cleared on process death by design;
     *  picked emoji writes through to disk via
     *  ReportStorage.setReportLanguageChoice so survives independently. */
    private val _languageIconFanOutByReport = MutableStateFlow<Map<String, List<IconCandidate>>>(emptyMap())
    val languageIconFanOutByReport: StateFlow<Map<String, List<IconCandidate>>> = _languageIconFanOutByReport.asStateFlow()
    internal fun updateLanguageIconFanOut(reportId: String, mutator: (List<IconCandidate>) -> List<IconCandidate>) {
        _languageIconFanOutByReport.update { current ->
            val next = mutator(current[reportId].orEmpty())
            current + (reportId to next)
        }
    }
    internal fun clearLanguageIconFanOut(reportId: String) {
        _languageIconFanOutByReport.update { it - reportId }
    }

    /** Per-agent alternative-icons state for the Agent icon detail
     *  screen's "Find alternative icons" button. Keyed by agentId
     *  (UUID, globally unique) so multiple agents under the same
     *  report don't collide. Same shape as [iconFanOutByReport] — a
     *  separate map keeps the report-level and per-agent UIs from
     *  sharing each other's candidates. */
    private val _agentIconFanOutByAgent = MutableStateFlow<Map<String, List<IconCandidate>>>(emptyMap())
    val agentIconFanOutByAgent: StateFlow<Map<String, List<IconCandidate>>> = _agentIconFanOutByAgent.asStateFlow()
    internal fun updateAgentIconFanOut(agentId: String, mutator: (List<IconCandidate>) -> List<IconCandidate>) {
        _agentIconFanOutByAgent.update { current ->
            val next = mutator(current[agentId].orEmpty())
            current + (agentId to next)
        }
    }
    internal fun clearAgentIconFanOut(agentId: String) {
        _agentIconFanOutByAgent.update { it - agentId }
    }

    /** Live "Find alternative titles" candidates for the report title
     *  (keyed by reportId) and per-model titles (keyed by agentId).
     *  Transient — the picked title only fills the editor field, so
     *  nothing persists until the user taps Update. */
    private val _titleFanOutByReport = MutableStateFlow<Map<String, List<TitleCandidate>>>(emptyMap())
    val titleFanOutByReport: StateFlow<Map<String, List<TitleCandidate>>> = _titleFanOutByReport.asStateFlow()
    internal fun updateReportTitleFanOut(reportId: String, mutator: (List<TitleCandidate>) -> List<TitleCandidate>) {
        _titleFanOutByReport.update { current -> current + (reportId to mutator(current[reportId].orEmpty())) }
    }
    internal fun clearReportTitleFanOut(reportId: String) { _titleFanOutByReport.update { it - reportId } }

    /** Live state of any "Find alternative translation" fan-out, keyed by
     *  the translation item's id. Transient — the picked candidate only
     *  overwrites the one TRANSLATE row on apply; nothing else persists. */
    private val _altTranslationByItem = MutableStateFlow<Map<String, List<TranslationCandidate>>>(emptyMap())
    val altTranslationByItem: StateFlow<Map<String, List<TranslationCandidate>>> = _altTranslationByItem.asStateFlow()
    internal fun updateAltTranslationFanOut(itemId: String, mutator: (List<TranslationCandidate>) -> List<TranslationCandidate>) {
        _altTranslationByItem.update { current -> current + (itemId to mutator(current[itemId].orEmpty())) }
    }
    internal fun clearAltTranslationFanOut(itemId: String) { _altTranslationByItem.update { it - itemId } }

    private val _titleFanOutByAgent = MutableStateFlow<Map<String, List<TitleCandidate>>>(emptyMap())
    val titleFanOutByAgent: StateFlow<Map<String, List<TitleCandidate>>> = _titleFanOutByAgent.asStateFlow()
    internal fun updateAgentTitleFanOut(agentId: String, mutator: (List<TitleCandidate>) -> List<TitleCandidate>) {
        _titleFanOutByAgent.update { current -> current + (agentId to mutator(current[agentId].orEmpty())) }
    }
    internal fun clearAgentTitleFanOut(agentId: String) { _titleFanOutByAgent.update { it - agentId } }

    /** Live state of any "Find alternative icons" run launched from
     *  the Meta-icon detail screen for an [com.ai.model.InternalPrompt].
     *  Keyed by the same `name + U+001F + title` join the
     *  [com.ai.data.InternalPromptIconCache] uses. Same shape as
     *  [agentIconFanOutByAgent] / [iconFanOutByReport] — a separate
     *  map keeps each surface's candidates from leaking into the
     *  others. */
    private val _internalPromptIconFanOutByPrompt =
        MutableStateFlow<Map<String, List<IconCandidate>>>(emptyMap())
    val internalPromptIconFanOutByPrompt: StateFlow<Map<String, List<IconCandidate>>> =
        _internalPromptIconFanOutByPrompt.asStateFlow()
    internal fun updateInternalPromptIconFanOut(
        key: String,
        mutator: (List<IconCandidate>) -> List<IconCandidate>
    ) {
        _internalPromptIconFanOutByPrompt.update { current ->
            val next = mutator(current[key].orEmpty())
            current + (key to next)
        }
    }
    internal fun clearInternalPromptIconFanOut(key: String) {
        _internalPromptIconFanOutByPrompt.update { it - key }
        // Drop captured prompt/response texts for every candidate of
        // this prompt — they're only meaningful for the in-flight
        // fan-out, not across restarts.
        val prefix = "$key|"
        internalPromptIconCallTexts.keys
            .filter { it.startsWith(prefix) }
            .forEach { internalPromptIconCallTexts.remove(it) }
    }

    /** Per-fan-out-pair Find-alt candidate state — same shape as
     *  [internalPromptIconFanOutByPrompt] but keyed by the
     *  SecondaryResult id of the specific pair the user launched
     *  the alt run on. Drives the per-pair "View alternative
     *  icons" overlay. */
    private val _pairIconFanOutByPair =
        MutableStateFlow<Map<String, List<IconCandidate>>>(emptyMap())
    val pairIconFanOutByPair: StateFlow<Map<String, List<IconCandidate>>> =
        _pairIconFanOutByPair.asStateFlow()
    internal fun updatePairIconFanOut(
        pairId: String,
        mutator: (List<IconCandidate>) -> List<IconCandidate>
    ) {
        _pairIconFanOutByPair.update { current ->
            val next = mutator(current[pairId].orEmpty())
            current + (pairId to next)
        }
    }
    internal fun clearPairIconFanOut(pairId: String) {
        _pairIconFanOutByPair.update { it - pairId }
    }

    /** Per-fan-out-pair title Find-alt candidates, keyed by the
     *  SecondaryResult id of the pair the user launched the alt run
     *  on. Sibling of [pairIconFanOutByPair] for the L3 META screen's
     *  "Find alternative title" button. */
    private val _pairTitleFanOutByPair =
        MutableStateFlow<Map<String, List<TitleCandidate>>>(emptyMap())
    val pairTitleFanOutByPair: StateFlow<Map<String, List<TitleCandidate>>> =
        _pairTitleFanOutByPair.asStateFlow()
    internal fun updatePairTitleFanOut(
        pairId: String,
        mutator: (List<TitleCandidate>) -> List<TitleCandidate>
    ) {
        _pairTitleFanOutByPair.update { current ->
            current + (pairId to mutator(current[pairId].orEmpty()))
        }
    }
    internal fun clearPairTitleFanOut(pairId: String) {
        _pairTitleFanOutByPair.update { it - pairId }
    }

    /** Per-candidate `(promptText, responseText)` capture used by
     *  [com.ai.viewmodel.ReportViewModel.pickInternalPromptIcon] —
     *  the picked candidate's request + reply land in
     *  [com.ai.data.InternalPromptIconCache.pickAlternative] from
     *  here so the detail screen renders the actual call that
     *  produced the picked emoji. Keyed by
     *  `"$promptKey|$providerId|$model"` (unique because each
     *  candidate is one (provider, model) pair within one prompt's
     *  fan-out). Lives off UiState because it updates at the same
     *  5-15 Hz the candidate map does and is read only on user
     *  pick — recomposing every UI consumer of UiState for these
     *  writes would be wasteful. */
    private val internalPromptIconCallTexts =
        java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
    internal fun setInternalPromptIconCallTexts(
        key: String, providerId: String, model: String,
        promptText: String, responseText: String
    ) {
        internalPromptIconCallTexts["$key|$providerId|$model"] =
            promptText to responseText
    }
    internal fun getInternalPromptIconCallTexts(
        key: String, providerId: String, model: String
    ): Pair<String, String>? =
        internalPromptIconCallTexts["$key|$providerId|$model"]

    // Refresh-all in-flight state. null = idle (nothing running, nothing to
    // resume). When non-null the user can navigate away from the
    // Refresh-all screen and come back to a live view of the same run.
    private val _refreshAllState = MutableStateFlow<RefreshAllState?>(null)
    val refreshAllState: StateFlow<RefreshAllState?> = _refreshAllState.asStateFlow()

    init {
        // Tracing default is true; the bootstrap below overrides it with
        // the persisted GeneralSettings.tracingEnabled. Setting it here as
        // well keeps any pre-bootstrap call (e.g. PricingCache.preloadAsync
        // on the same launch) consistent with the user's last choice
        // rather than always recording.
        ApiTracer.isTracingEnabled = true
        // Warm the trace-file cache off the main thread so the first
        // UI-side getTraceFiles() (Trace screen open, agent test 🐞
        // lookup, fan-out 🐞 lookup) doesn't pay the streaming-parse
        // cost across the whole trace dir.
        // Off-thread cache prewarms. The two below are fire-and-forget on
        // viewModelScope — the bootstrap launch below doesn't depend on
        // either finishing. Logged from inside each function at TRACE.
        AppLog.d("App.start", "→ Prewarm caches (ApiTracer + PricingCache)")
        ApiTracer.prewarmCache(viewModelScope)
        PricingCache.preloadAsync(application, viewModelScope)
        AppLog.d("App.start", "← Prewarm caches dispatched (background)")

        // Stall watchdog. Every 15s WHILE work is in flight, log the cap
        // snapshot + per-host throttle state. If the global in-flight count
        // doesn't change for 60s straight (4 ticks) while still > 0, that's
        // the signature of a deadlock (a big fan-out / Fan Meta / Test-all
        // sweep wedged on the throttle gates) — escalate to a WARN (which
        // also toasts) carrying the exact cap + per-host state so the next
        // occurrence is self-diagnosing. Idle ticks log nothing.
        viewModelScope.launch(Dispatchers.Default + com.ai.data.CrashReporter.coroutineHandler) {
            var lastLine = ""
            var stalledTicks = 0
            while (true) {
                kotlinx.coroutines.delay(15_000)
                if (!ApiCallCaps.isBusy()) { lastLine = ""; stalledTicks = 0; continue }
                // Compare the FULL per-host state, not just global in-flight:
                // a healthy big run keeps global pinned at its cap the whole
                // time (saturation, not a stall), but the per-host conc/window
                // counts move every tick as calls complete and fire. Only a
                // genuine deadlock freezes the per-host state too — so we flag
                // a stall only when this whole line is byte-identical across
                // consecutive ticks.
                val line = "caps: ${ApiCallCaps.diagnosticLine()} | hosts: ${com.ai.data.ProviderThrottle.diagnostics()}"
                stalledTicks = if (line == lastLine) stalledTicks + 1 else 0
                lastLine = line
                if (stalledTicks >= 4)
                    AppLog.w("CapsWatch", "POSSIBLE STALL — throttle state frozen ${stalledTicks * 15}s — $line")
                else
                    AppLog.i("CapsWatch", line)
            }
        }

        viewModelScope.launch(Dispatchers.IO + com.ai.data.CrashReporter.coroutineHandler) {
            val startTag = "App.start"
            val bs = bootstrap(application)

            AppLog.d(startTag, "→ Apply general settings to global singletons")
            ModelType.userDefaults = bs.first.defaultTypePaths
            AppLog.v(startTag, "  ModelType.userDefaults set (${bs.first.defaultTypePaths.size} entries)")
            ApiTracer.isTracingEnabled = bs.first.tracingEnabled
            AppLog.v(startTag, "  ApiTracer.isTracingEnabled=${bs.first.tracingEnabled}")
            syncTestModelPrompt(bs.second)
            AppLog.v(startTag, "  AnalysisRepository.TEST_PROMPT=${com.ai.data.AnalysisRepository.TEST_PROMPT}")
            NetworkSettings.streamingReadTimeoutSec = bs.first.streamingReadTimeoutSec
            NetworkSettings.nonStreamingReadTimeoutSec = bs.first.nonStreamingReadTimeoutSec
            NetworkSettings.maxCallsPerProviderPerMinute = bs.first.maxCallsPerProviderPerMinute
            NetworkSettings.maxConcurrentCallsPerProvider = bs.first.maxConcurrentCallsPerProvider
            NetworkSettings.maxRetriesOn429 = bs.first.maxRetriesOn429
            NetworkSettings.retryBackoffMs429 = bs.first.retryBackoffMs429
            NetworkSettings.maxRetriesOn529 = bs.first.maxRetriesOn529
            NetworkSettings.retryBackoffMs529 = bs.first.retryBackoffMs529
            ApiCallCaps.resetForNewLimits(globalMax = bs.first.maxConcurrentApiCalls)
            AppLog.v(
                startTag,
                "  NetworkSettings: streamRT=${bs.first.streamingReadTimeoutSec}s nonStreamRT=${bs.first.nonStreamingReadTimeoutSec}s " +
                    "maxPerMin=${bs.first.maxCallsPerProviderPerMinute} maxConc=${bs.first.maxConcurrentCallsPerProvider} " +
                    "maxRetries429=${bs.first.maxRetriesOn429} retryBackoff=${bs.first.retryBackoffMs429}ms " +
                    "maxRetries529=${bs.first.maxRetriesOn529} retryBackoff529=${bs.first.retryBackoffMs529}ms"
            )
            AppLog.threshold = bs.first.logLevel
            AppLog.v(startTag, "  AppLog.threshold=${bs.first.logLevel}")
            AppLog.d(startTag, "← Apply general settings done")

            val appLabel = runCatching {
                application.packageManager.getApplicationLabel(application.applicationInfo).toString()
            }.getOrDefault("AI")
            // Read the gradle-generated build stamp from the bundled
            // asset (always fresh per build — the generateBuildStamp
            // task is upToDateWhen { false }). Install time is the
            // most-recent `adb install` time. Both surface here so a
            // user-shared log identifies the exact deployed APK.
            val builtAt = runCatching {
                val millis = application.assets.open("build-timestamp.txt")
                    .bufferedReader().use { it.readText().trim() }.toLong()
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.US)
                    .format(java.util.Date(millis))
            }.getOrDefault("?")
            val installedAt = runCatching {
                val info = application.packageManager.getPackageInfo(application.packageName, 0)
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.US)
                    .format(java.util.Date(info.lastUpdateTime))
            }.getOrDefault("?")
            AppLog.i(
                "App",
                "App started — $appLabel v${com.ai.BuildConfig.VERSION_NAME} " +
                    "(built $builtAt, installed $installedAt) " +
                    "logLevel=${bs.first.logLevel}, tracing=${bs.first.tracingEnabled}"
            )

            // Drop any per-host semaphores left over from the cold-start
            // default (3) so the very first call uses the persisted cap.
            AppLog.d(startTag, "→ ProviderThrottle reset")
            ProviderThrottle.resetForNewLimits()
            AppLog.d(startTag, "← ProviderThrottle reset done")

            // Reload persisted model cooldowns (e.g. Google models
            // benched by a >1h 429) so pickers gray them out and the
            // dispatch layer skips them across restarts.
            com.ai.data.ModelCooldownStore.init(application)

            AppLog.d(startTag, "→ Publish initial UiState")
            _uiState.update { it.copy(generalSettings = bs.first, aiSettings = bs.second) }
            AppLog.d(startTag, "← Publish initial UiState done")

            AppLog.d(startTag, "→ refreshAllModelLists (cache-respecting)")
            val tRefresh = System.currentTimeMillis()
            val refreshed = refreshAllModelLists(bs.second)
            AppLog.v(startTag, "  refreshed ${refreshed.size} provider(s): ${refreshed.entries.joinToString { "${it.key}=${it.value}" }}")
            AppLog.d(startTag, "← refreshAllModelLists done in ${System.currentTimeMillis() - tRefresh}ms")
        }
        // Mirror the latest aiSettings to a static holder so the
        // dispatcher helpers (which can't easily thread Settings
        // through their call stack) can consult capability lookups
        // like Settings.isReasoningCapable. Updated on every uiState
        // emission — the cost is one volatile write per state change.
        viewModelScope.launch {
            uiState.collect { SettingsHolder.current = it.aiSettings }
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class) // GlobalScope is intentional — see below.
    override fun onCleared() {
        // Flush off the main thread — flushUsageStats does a
        // SharedPreferences commit which blocks on disk I/O. Use
        // GlobalScope + NonCancellable so the work survives the
        // ViewModel's scope cancellation that's already in flight.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            runCatching { settingsPrefs.flushUsageStats() }
        }
        // viewModelScope cancellation runs every active flow's
        // withTracerTags finally on the way out — that restores the
        // previous (reportId, category). No manual clear needed.
        super.onCleared()
    }

    private suspend fun bootstrap(application: Application): Pair<GeneralSettings, Settings> {
        // Each background ACTION below is bracketed by DEBUG → / ←
        // log lines (start + end+duration); details inside the action
        // log at TRACE so a default WARN/ERROR threshold stays quiet
        // and a user troubleshooting "what did app startup do?" flips
        // to TRACE for the full picture.
        val tag = "App.bootstrap"
        val bootStart = System.currentTimeMillis()

        AppLog.d(tag, "→ Singletons init")
        AppLog.v(tag, "  init AppLog"); AppLog.init(application)
        AppLog.v(tag, "  init ApiTracer"); ApiTracer.init(application)
        AppLog.v(tag, "  init AuditLog"); AuditLog.init(application)
        AppLog.v(tag, "  init ChatHistoryManager"); ChatHistoryManager.init(application)
        AppLog.v(tag, "  init ReportStorage"); ReportStorage.init(application)
        AppLog.v(tag, "  init SecondaryResultStorage"); SecondaryResultStorage.init(application)
        AppLog.v(tag, "  init ProviderRegistry"); ProviderRegistry.init(application)
        AppLog.v(tag, "  init ProviderFieldTimestamps"); ProviderFieldTimestamps.init(application)
        AppLog.v(tag, "  init PromptCache"); PromptCache.init(application)
        AppLog.v(tag, "  init InternalPromptIconCache"); InternalPromptIconCache.init(application)
        AppLog.v(tag, "  init MetaCache"); com.ai.data.MetaCache.init(application)
        AppLog.v(tag, "  init LastReportTracker"); com.ai.data.LastReportTracker.init(application)
        AppLog.d(tag, "← Singletons init done in ${System.currentTimeMillis() - bootStart}ms")

        AppLog.d(tag, "→ Load prefs")
        val tLoad = System.currentTimeMillis()
        val gs = settingsPrefs.loadGeneralSettings()
        AppLog.v(tag, "  GeneralSettings loaded (logLevel=${gs.logLevel}, tracing=${gs.tracingEnabled})")
        var ai = settingsPrefs.loadSettings()
        AppLog.v(tag, "  providers=${ai.providers.size} agents=${ai.agents.size} flocks=${ai.flocks.size} swarms=${ai.swarms.size}")
        AppLog.v(tag, "  internalPrompts=${ai.internalPrompts.size} examplePrompts=${ai.examplePrompts.size} parameters=${ai.parameters.size} systemPrompts=${ai.systemPrompts.size}")
        AppLog.d(tag, "← Load prefs done in ${System.currentTimeMillis() - tLoad}ms")

        // First-run seeding from bundled assets. Flag wiped on data
        // clear / reinstall (which is exactly when we want to seed
        // again); persists across APK upgrades.
        AppLog.d(tag, "→ First-run seed")
        val tFirst = System.currentTimeMillis()
        if (!prefs.getBoolean(KEY_FIRST_RUN_BOOTSTRAPPED, false)) {
            val isEmptyInstall = ProviderRegistry.getAll().isEmpty() && ai.internalPrompts.isEmpty()
            AppLog.v(tag, "  first run; isEmptyInstall=$isEmptyInstall")
            if (isEmptyInstall) {
                val providersAdded = ProviderRegistry.importFromAsset(application, "providers.json")
                AppLog.v(tag, "  providers.json seed: added=$providersAdded")
                if (providersAdded < 0) {
                    AppLog.w(tag, "First-run providers.json import failed")
                }
            }
            prefs.edit().putBoolean(KEY_FIRST_RUN_BOOTSTRAPPED, true).apply()
        } else {
            AppLog.v(tag, "  not a first run; skipping seed")
        }
        AppLog.d(tag, "← First-run seed done in ${System.currentTimeMillis() - tFirst}ms")

        // Every-start delta-sync from bundled providers.json. Two
        // passes, in order:
        //   1. syncFromAsset refreshes fields the user hasn't edited
        //      on existing entries (timestamp == null) so APK upgrades
        //      pick up catalog corrections — new modelFilter regex,
        //      hardcoded models, mergeHardcodedModels flips, etc. —
        //      without touching user edits.
        //   2. importFromAsset appends any asset id not yet in the
        //      registry. Append-only (existing rows are skipped),
        //      mirrors the prompts delta-merge below — new providers
        //      shipped in an APK upgrade light up without the user
        //      having to hit the manual "Import new providers" button.
        // Already on Dispatchers.IO via viewModelScope.launch.
        AppLog.d(tag, "→ providers.json delta-sync")
        val tSync = System.currentTimeMillis()
        runCatching {
            val syncCount = ProviderRegistry.syncFromAsset(application, "providers.json")
            AppLog.v(tag, "  syncFromAsset: $syncCount unedited fields refreshed")
            val addCount = ProviderRegistry.importFromAsset(application, "providers.json")
            AppLog.v(tag, "  importFromAsset: $addCount new providers appended")
            AppLog.d(tag, "← providers.json delta-sync done in ${System.currentTimeMillis() - tSync}ms (synced=$syncCount, added=$addCount)")
        }.onFailure {
            AppLog.w(tag, "← providers.json delta-sync failed in ${System.currentTimeMillis() - tSync}ms", it)
        }

        // First-time seed of providerStates for providers shipped
        // with defaultInactive=true (Z.AI, Fireworks, StepFun, …).
        // Flips whenever the state slot is empty — including the
        // case where the user already has an API key set but has
        // never explicitly toggled state. Existing installs that
        // have actually flipped state (to "ok", "error", or
        // "inactive") keep their entry untouched — only the
        // missing-slot case gets seeded.
        run {
            val needsSeed = ProviderRegistry.getAll().filter { svc ->
                svc.defaultInactive && svc.id !in ai.providerStates
            }
            if (needsSeed.isNotEmpty()) {
                AppLog.i(tag, "Seeding ${needsSeed.size} default-inactive provider state(s): ${needsSeed.joinToString { it.id }}")
                val newStates = ai.providerStates + needsSeed.associate { it.id to "inactive" }
                ai = ai.copy(providerStates = newStates)
                settingsPrefs.saveSettings(ai)
            }
        }


        // Every-start delta-merge of bundled prompts. Appends any
        // (category, name) pair not already present; never overwrites
        // existing rows. New prompts shipped in an APK upgrade get
        // picked up here without the user having to tap 'Read new
        // prompts' in Settings. Already on Dispatchers.IO via the
        // viewModelScope.launch wrapping this bootstrap call.
        AppLog.d(tag, "→ internal-prompts/ delta-merge")
        val tPrompts = System.currentTimeMillis()
        runCatching {
            val bundled = com.ai.data.InternalPromptSeed.loadFromAssets(application)
            AppLog.v(tag, "  bundled internal-prompts/ entries: ${bundled.size}")
            if (bundled.isNotEmpty()) {
                val before = ai.internalPrompts.size
                val merged = com.ai.data.InternalPromptSeed.ensureAllPresent(ai.internalPrompts, bundled)
                val added = merged.size - before
                AppLog.v(tag, "  merge: before=$before merged=${merged.size} added=$added")
                if (added != 0) {
                    ai = ai.copy(internalPrompts = merged)
                    settingsPrefs.saveSettings(ai)
                    AppLog.v(tag, "  settings saved with $added new prompts")
                }
                AppLog.d(tag, "← internal-prompts/ delta-merge done in ${System.currentTimeMillis() - tPrompts}ms (added=$added)")
            } else {
                AppLog.d(tag, "← internal-prompts/ delta-merge done in ${System.currentTimeMillis() - tPrompts}ms (empty asset)")
            }
        }.onFailure {
            AppLog.w(tag, "← internal-prompts/ delta-merge failed in ${System.currentTimeMillis() - tPrompts}ms", it)
        }

        // Mirror of the internal-prompts/ delta-merge for examples.json:
        // append any bundled title (case-insensitive) not yet present,
        // never touch existing rows. Lets APK upgrades that ship new
        // example prompts surface them automatically without the user
        // hitting Housekeeping → Prompts → "Add new prompts from
        // assets/examples.json".
        AppLog.d(tag, "→ examples.json delta-merge")
        val tExamples = System.currentTimeMillis()
        runCatching {
            val bundled = com.ai.data.ExamplePromptSeed.loadFromAssets(application)
            AppLog.v(tag, "  bundled examples.json entries: ${bundled.size}")
            if (bundled.isNotEmpty()) {
                val before = ai.examplePrompts.size
                val merged = com.ai.data.ExamplePromptSeed.ensureAllPresent(ai.examplePrompts, bundled)
                val added = merged.size - before
                AppLog.v(tag, "  merge: before=$before merged=${merged.size} added=$added")
                if (added != 0) {
                    ai = ai.copy(examplePrompts = merged)
                    settingsPrefs.saveSettings(ai)
                    AppLog.v(tag, "  settings saved with $added new example prompts")
                }
                AppLog.d(tag, "← examples.json delta-merge done in ${System.currentTimeMillis() - tExamples}ms (added=$added)")
            } else {
                AppLog.d(tag, "← examples.json delta-merge done in ${System.currentTimeMillis() - tExamples}ms (empty asset)")
            }
        }.onFailure {
            AppLog.w(tag, "← examples.json delta-merge failed in ${System.currentTimeMillis() - tExamples}ms", it)
        }

        // Mirror of the internal-prompts/ / examples.json delta-merge for
        // system-prompts.json: append any bundled name (case-insensitive)
        // not yet present, never touch existing rows. Surfaces newly
        // bundled System prompts automatically on APK upgrade.
        AppLog.d(tag, "→ system-prompts.json delta-merge")
        val tSystemPrompts = System.currentTimeMillis()
        runCatching {
            val bundled = com.ai.data.SystemPromptSeed.loadFromAssets(application)
            AppLog.v(tag, "  bundled system-prompts.json entries: ${bundled.size}")
            if (bundled.isNotEmpty()) {
                val before = ai.systemPrompts.size
                val merged = com.ai.data.SystemPromptSeed.ensureAllPresent(ai.systemPrompts, bundled)
                val added = merged.size - before
                AppLog.v(tag, "  merge: before=$before merged=${merged.size} added=$added")
                if (added != 0) {
                    ai = ai.copy(systemPrompts = merged)
                    settingsPrefs.saveSettings(ai)
                    AppLog.v(tag, "  settings saved with $added new system prompts")
                }
                AppLog.d(tag, "← system-prompts.json delta-merge done in ${System.currentTimeMillis() - tSystemPrompts}ms (added=$added)")
            } else {
                AppLog.d(tag, "← system-prompts.json delta-merge done in ${System.currentTimeMillis() - tSystemPrompts}ms (empty asset)")
            }
        }.onFailure {
            AppLog.w(tag, "← system-prompts.json delta-merge failed in ${System.currentTimeMillis() - tSystemPrompts}ms", it)
        }

        // Mirror of the prompt delta-merges for the bundled worker pools:
        // workers/swarms.json — append any bundled Swarm whose name isn't
        // present yet (e.g. the "workers" pool the worker prompts point at),
        // so an APK upgrade lands it without the user importing by hand.
        AppLog.d(tag, "→ workers/swarms.json delta-merge")
        val tSwarms = System.currentTimeMillis()
        runCatching {
            val bundled = com.ai.data.SwarmSeed.loadFromAssets(application)
            if (bundled.isNotEmpty()) {
                val before = ai.swarms.size
                val merged = com.ai.data.SwarmSeed.ensureAllPresent(ai.swarms, bundled)
                val added = merged.size - before
                if (added != 0) {
                    ai = ai.copy(swarms = merged)
                    settingsPrefs.saveSettings(ai)
                }
                AppLog.d(tag, "← workers/swarms.json delta-merge done in ${System.currentTimeMillis() - tSwarms}ms (added=$added)")
            } else {
                AppLog.d(tag, "← workers/swarms.json delta-merge done in ${System.currentTimeMillis() - tSwarms}ms (empty asset)")
            }
        }.onFailure {
            AppLog.w(tag, "← workers/swarms.json delta-merge failed in ${System.currentTimeMillis() - tSwarms}ms", it)
        }

        // workers/flocks.json — same, resolving each flock's member agents
        // by NAME against the current agent set (ai.agents is already
        // loaded from prefs at this point).
        AppLog.d(tag, "→ workers/flocks.json delta-merge")
        val tFlocks = System.currentTimeMillis()
        runCatching {
            val bundled = com.ai.data.FlockSeed.loadFromAssets(application, ai.agents)
            if (bundled.isNotEmpty()) {
                val before = ai.flocks.size
                val merged = com.ai.data.FlockSeed.ensureAllPresent(ai.flocks, bundled)
                val added = merged.size - before
                if (added != 0) {
                    ai = ai.copy(flocks = merged)
                    settingsPrefs.saveSettings(ai)
                }
                AppLog.d(tag, "← workers/flocks.json delta-merge done in ${System.currentTimeMillis() - tFlocks}ms (added=$added)")
            } else {
                AppLog.d(tag, "← workers/flocks.json delta-merge done in ${System.currentTimeMillis() - tFlocks}ms (empty asset)")
            }
        }.onFailure {
            AppLog.w(tag, "← workers/flocks.json delta-merge failed in ${System.currentTimeMillis() - tFlocks}ms", it)
        }

        // Mirror of the internal-prompts/ / examples.json delta-merge for
        // excluded.json: append any (provider, model) test-excluded
        // pair not yet present so APK upgrades that ship a curated
        // "never probe these" list surface them automatically.
        AppLog.d(tag, "→ excluded.json delta-merge")
        val tExcluded = System.currentTimeMillis()
        runCatching {
            val bundled = com.ai.data.TestExcludedSeed.loadFromAssets(application)
            AppLog.v(tag, "  bundled excluded.json entries: ${bundled.size}")
            if (bundled.isNotEmpty()) {
                val before = ai.testExcludedModels.size
                val merged = com.ai.data.TestExcludedSeed.ensureAllPresent(ai.testExcludedModels, bundled)
                val added = merged.size - before
                if (added != 0) {
                    ai = ai.copy(testExcludedModels = merged)
                    settingsPrefs.saveSettings(ai)
                    AppLog.v(tag, "  settings saved with $added new test-excluded entries")
                }
                AppLog.d(tag, "← excluded.json delta-merge done in ${System.currentTimeMillis() - tExcluded}ms (added=$added)")
            } else {
                AppLog.d(tag, "← excluded.json delta-merge done in ${System.currentTimeMillis() - tExcluded}ms (empty asset)")
            }
        }.onFailure {
            AppLog.w(tag, "← excluded.json delta-merge failed in ${System.currentTimeMillis() - tExcluded}ms", it)
        }

        // Mirror of excluded.json delta-merge for inaccessible.json:
        // append any (provider, model) pair not yet present so an APK
        // upgrade that ships a curated "not callable on this account"
        // list (Together non-serverless, OpenRouter Arcee, etc.)
        // surfaces them automatically — saves the user from
        // rediscovering them via burned sweep slots.
        AppLog.d(tag, "→ inaccessible.json delta-merge")
        val tInaccessible = System.currentTimeMillis()
        runCatching {
            val bundled = com.ai.data.InaccessibleSeed.loadFromAssets(application)
            AppLog.v(tag, "  bundled inaccessible.json entries: ${bundled.size}")
            if (bundled.isNotEmpty()) {
                val before = ai.inaccessibleModels.size
                val merged = com.ai.data.InaccessibleSeed.ensureAllPresent(ai.inaccessibleModels, bundled)
                val added = merged.size - before
                if (added != 0) {
                    ai = ai.copy(inaccessibleModels = merged)
                    settingsPrefs.saveSettings(ai)
                    AppLog.v(tag, "  settings saved with $added new inaccessible entries")
                }
                AppLog.d(tag, "← inaccessible.json delta-merge done in ${System.currentTimeMillis() - tInaccessible}ms (added=$added)")
            } else {
                AppLog.d(tag, "← inaccessible.json delta-merge done in ${System.currentTimeMillis() - tInaccessible}ms (empty asset)")
            }
        }.onFailure {
            AppLog.w(tag, "← inaccessible.json delta-merge failed in ${System.currentTimeMillis() - tInaccessible}ms", it)
        }

        AppLog.d(tag, "→ meta.json delta-merge")
        val tMeta = System.currentTimeMillis()
        runCatching {
            val bundled = com.ai.data.DefaultMetaItemSeed.loadFromAssets(application)
            AppLog.v(tag, "  bundled meta.json entries: ${bundled.size}")
            if (bundled.isNotEmpty()) {
                val before = ai.defaultMetaItems.size
                val merged = com.ai.data.DefaultMetaItemSeed.ensureAllPresent(ai.defaultMetaItems, bundled)
                val added = merged.size - before
                if (added != 0) {
                    ai = ai.copy(defaultMetaItems = merged)
                    settingsPrefs.saveSettings(ai)
                    AppLog.v(tag, "  settings saved with $added new default meta items")
                }
                AppLog.d(tag, "← meta.json delta-merge done in ${System.currentTimeMillis() - tMeta}ms (added=$added)")
            } else {
                AppLog.d(tag, "← meta.json delta-merge done in ${System.currentTimeMillis() - tMeta}ms (empty asset)")
            }
        }.onFailure {
            AppLog.w(tag, "← meta.json delta-merge failed in ${System.currentTimeMillis() - tMeta}ms", it)
        }


        AppLog.d(tag, "bootstrap total ${System.currentTimeMillis() - bootStart}ms")
        return gs to ai
    }

    /** On-demand merge of the prompts declared in `assets/internal-prompts/`
     *  into [Settings.internalPrompts]. Existing rows are left strictly
     *  alone (no overwrites); only names not yet present are appended.
     *  Returns the count of newly added prompts so the caller can show
     *  feedback ("3 new prompts added", "all prompts already present"). */
    fun loadBundledInternalPrompts(): Int {
        val ctx = getApplication<Application>()
        val bundled = com.ai.data.InternalPromptSeed.loadFromAssets(ctx)
        if (bundled.isEmpty()) return 0
        val current = _uiState.value.aiSettings
        val merged = com.ai.data.InternalPromptSeed.ensureAllPresent(current.internalPrompts, bundled)
        val added = merged.size - current.internalPrompts.size
        if (added > 0) updateSettings(current.copy(internalPrompts = merged))
        return added
    }

    /** Drop every Internal prompt and replace the list with a fresh
     *  load of `assets/internal-prompts/`. Returns the number of rows loaded
     *  (0 if the asset is missing or fails to parse, in which case the
     *  existing list is left untouched). */
    fun resetInternalPromptsFromAssets(): Int {
        val ctx = getApplication<Application>()
        val bundled = com.ai.data.InternalPromptSeed.loadFromAssets(ctx)
        if (bundled.isEmpty()) return 0
        val current = _uiState.value.aiSettings
        updateSettings(current.copy(internalPrompts = bundled))
        return bundled.size
    }

    /** Drop every Example prompt and replace the list with a fresh
     *  load of `assets/examples.json`. Returns the number of rows loaded
     *  (0 if the asset is missing or fails to parse, in which case the
     *  existing list is left untouched). */
    fun resetExamplePromptsFromAssets(): Int {
        val ctx = getApplication<Application>()
        val bundled = com.ai.data.ExamplePromptSeed.loadFromAssets(ctx)
        if (bundled.isEmpty()) return 0
        val current = _uiState.value.aiSettings
        updateSettings(current.copy(examplePrompts = bundled))
        return bundled.size
    }

    /** On-demand append of `assets/system-prompts.json` into
     *  [Settings.systemPrompts] — existing rows untouched, only names not
     *  yet present are added. Used by the factory reset. Returns the
     *  count newly added. */
    fun loadBundledSystemPrompts(): Int {
        val ctx = getApplication<Application>()
        val bundled = com.ai.data.SystemPromptSeed.loadFromAssets(ctx)
        if (bundled.isEmpty()) return 0
        val current = _uiState.value.aiSettings
        val merged = com.ai.data.SystemPromptSeed.ensureAllPresent(current.systemPrompts, bundled)
        val added = merged.size - current.systemPrompts.size
        if (added > 0) updateSettings(current.copy(systemPrompts = merged))
        return added
    }

    /** Drop every System prompt and replace the list with a fresh load
     *  of `assets/system-prompts.json`. Returns the number of rows loaded
     *  (0 if the asset is missing or fails to parse, in which case the
     *  existing list is left untouched). */
    fun resetSystemPromptsFromAssets(): Int {
        val ctx = getApplication<Application>()
        val bundled = com.ai.data.SystemPromptSeed.loadFromAssets(ctx)
        if (bundled.isEmpty()) return 0
        val current = _uiState.value.aiSettings
        updateSettings(current.copy(systemPrompts = bundled))
        return bundled.size
    }

    /** Append every bundled default meta item from `assets/meta.json`
     *  not already present (by composite key). Returns rows added. */
    fun loadBundledDefaultMetaItems(): Int {
        val ctx = getApplication<Application>()
        val bundled = com.ai.data.DefaultMetaItemSeed.loadFromAssets(ctx)
        if (bundled.isEmpty()) return 0
        val current = _uiState.value.aiSettings
        val merged = com.ai.data.DefaultMetaItemSeed.ensureAllPresent(current.defaultMetaItems, bundled)
        val added = merged.size - current.defaultMetaItems.size
        if (added > 0) updateSettings(current.copy(defaultMetaItems = merged))
        return added
    }

    /** Drop every default meta item and replace with a fresh load of
     *  `assets/meta.json`. Returns rows loaded (0 leaves the list as-is). */
    fun resetDefaultMetaItemsFromAssets(): Int {
        val ctx = getApplication<Application>()
        val bundled = com.ai.data.DefaultMetaItemSeed.loadFromAssets(ctx)
        if (bundled.isEmpty()) return 0
        val current = _uiState.value.aiSettings
        updateSettings(current.copy(defaultMetaItems = bundled))
        return bundled.size
    }

    // ===== Housekeeping primitives =====
    //
    // Each Housekeeping → Reset card button (Clear Usage Statistics,
    // Clear all runtime data, Clear all configuration) and the
    // resetApplication() orchestrator route through these helpers, so
    // the wipe sets stay in lockstep when one is later extended.

    data class RuntimeWipeResult(
        val logs: Int, val chats: Int, val traces: Int,
        val reports: Int, val prompts: Int, val testModels: Int
    )
    data class ConfigWipeResult(val localLlms: Int, val embedders: Int)

    /** Wipe the activity / personal-history surface the user almost
     *  always wants gone together: app logs, chat sessions, API
     *  traces, usage statistics, AI reports (incl. cascaded
     *  SecondaryResult rows), prompt history, and the "Test all
     *  models" run. Everything else — configuration (providers,
     *  agents, prompts, parameters, keys), knowledge bases,
     *  Info-provider caches, model-list cache, embeddings — is
     *  preserved. Use Clear all configuration or Reset application for
     *  wider wipes.
     *
     *  Drops only the persisted `test_run.json`; the caller must also
     *  call `ModelTestEngine.clearRun()` to reset the in-memory flow. */
    fun clearAllRuntimeData(context: Context): RuntimeWipeResult {
        AppLog.i("Housekeeping", "→ Clear logs / chats / traces / reports / prompts / usage stats / test run")
        val chats = ChatHistoryManager.deleteAllSessions()
        val traces = ApiTracer.getTraceFiles().size
        ApiTracer.clearTraces()
        val reports = ReportStorage.deleteAllReports(context)
        val prompts = settingsPrefs.clearPromptHistory()
        settingsPrefs.clearUsageStats()
        val testModels = ModelTestRunStore.load(context)?.total ?: 0
        ModelTestRunStore.delete(context)
        // AppLog last — the prior log lines for this method will be
        // dropped along with everything else. Recorded count is what
        // was on disk at clear-time.
        val logs = AppLog.clearLogs()
        return RuntimeWipeResult(
            logs = logs, chats = chats, traces = traces,
            reports = reports, prompts = prompts, testModels = testModels
        )
    }

    /** Drop every cached Info-provider tier (OpenRouter / LiteLLM /
     *  models.dev / Helicone / llm-prices / Artificial Analysis) plus
     *  the OpenRouter model-specs cache. Manual cost overrides and
     *  Together native pricing are preserved. */
    fun clearInfoProviderCaches(context: Context) {
        AppLog.i("Housekeeping", "→ Clear Info-provider caches")
        PricingCache.clearInfoProviderTiers(context)
        AppLog.i("Housekeeping", "← Clear Info-provider caches done")
    }

    fun clearAllConfiguration(context: Context): ConfigWipeResult {
        AppLog.i("Housekeeping", "→ Clear all configuration")
        updateSettings(Settings())
        updateGeneralSettings(GeneralSettings())
        val llms = LocalLlm.clearAll(context)
        val embedders = LocalEmbedder.clearAll(context)
        // Drop the per-(name, title) emoji cache. The prompts themselves
        // are reset to defaults above; the icons should match.
        InternalPromptIconCache.clearAll(context)
        MetaCache.clearAll(context)
        AppLog.i("Housekeeping", "← Clear all configuration: localLlms=$llms embedders=$embedders")
        return ConfigWipeResult(llms, embedders)
    }

    /** Factory-style reset that preserves API keys. Runs the cascade
     *  documented in the Reset application dialog: snapshot the keys
     *  to a temp file in cacheDir, wipe usage stats / runtime data /
     *  provider registry, reload providers.json + internal-prompts/ from
     *  assets, clear configuration (so Settings() is built against
     *  the freshly loaded registry), re-import the keys, and finally
     *  run the same Refresh-all chain the Refresh screen exposes so
     *  the freshly-reset app starts with current catalogs, verified
     *  provider keys, model lists, and default agents. The temp file
     *  is deleted in finally so a mid-cascade crash can't strand the
     *  keys on disk. */
    fun resetApplication(context: Context, onComplete: (success: Boolean, message: String) -> Unit) {
        AppLog.i("Housekeeping", "→ Reset application (preserve API keys)")
        viewModelScope.launch(Dispatchers.IO) {
            val tempFile = java.io.File(context.cacheDir, "reset_keys_${System.currentTimeMillis()}.json")
            val outcome = runCatching {
                // 1. Export keys to temp file
                val snap = _uiState.value
                val keysJson = com.ai.ui.settings.buildApiKeysJson(
                    snap.aiSettings,
                    snap.generalSettings.huggingFaceApiKey,
                    snap.generalSettings.openRouterApiKey,
                    snap.generalSettings.artificialAnalysisApiKey
                )
                tempFile.writeText(keysJson)

                // 2. Wipe activity logs / chats / traces / usage stats.
                clearAllRuntimeData(context)
                // 3. Additional wipes Reset needs that the narrowed
                //    Clear-all-runtime-data button no longer does:
                //    reports, prompt history/cache, knowledge bases,
                //    every cached Info-provider tier, per-provider
                //    /models cache, and the semantic-search embeddings.
                //    Reset is "factory style", so all of this goes.
                ReportStorage.getAllReports(context).forEach { ReportStorage.deleteReport(context, it.id) }
                PromptCache.clearAll()
                InternalPromptIconCache.clearAll(context)
                MetaCache.clearAll(context)
                settingsPrefs.clearPromptHistory()
                settingsPrefs.clearLastReportPrompt()
                KnowledgeStore.clearAll(context)
                PricingCache.clearAll(context)
                ModelListCache.clearAll(context)
                EmbeddingsStore.clearAll(context)
                // 4. Wipe provider registry
                ProviderRegistry.resetToDefaults(context)
                // 5. Reload providers.json from assets
                val providersAdded = ProviderRegistry.importFromAsset(context, "providers.json")
                if (providersAdded < 0) {
                    AppLog.w("App", "providers.json reload failed during reset")
                }
                // 6. Clear configuration (Settings() now keyed against fresh registry)
                clearAllConfiguration(context)
                // Persist the reset Settings synchronously before the
                // import step reads _uiState — updateSettings's IO save
                // is fire-and-forget but the StateFlow update is sync.
                // 7. Reset internal-prompts/ + system-prompts.json + meta.json
                //    from assets — FULL REPLACE (drop every existing row and
                //    reload the bundled set), not the add-only merge. A factory
                //    reset must not leave a user-customized internal prompt
                //    behind, so we don't rely on step 6's clear having emptied
                //    the list first.
                resetInternalPromptsFromAssets()
                resetSystemPromptsFromAssets()
                resetDefaultMetaItemsFromAssets()
                // 8. Re-import keys from temp file
                val readBack = tempFile.readText()
                val result = com.ai.ui.settings.applyApiKeysJson(readBack, _uiState.value.aiSettings)
                if (result != null) {
                    var gs = _uiState.value.generalSettings
                    result.huggingFaceApiKey?.let { gs = gs.copy(huggingFaceApiKey = it) }
                    result.openRouterApiKey?.let { gs = gs.copy(openRouterApiKey = it) }
                    result.artificialAnalysisApiKey?.let { gs = gs.copy(artificialAnalysisApiKey = it) }
                    if (gs != _uiState.value.generalSettings) updateGeneralSettings(gs)
                    updateSettings(result.settings)
                    result.imported
                } else 0
            }
            // 9. Always delete the temp keys file
            runCatching { tempFile.delete() }

            // Refresh-all is no longer chained here — it pulls 6+ remote
            // catalogs serially and made Reset feel hung for minutes on
            // mobile networks. The user can re-trigger it manually from
            // Housekeeping → Refresh if they want the freshly-reset app
            // to start with up-to-date catalogs and a default agents flock.

            withContext(Dispatchers.Main) {
                outcome.fold(
                    onSuccess = { count ->
                        AppLog.i("Housekeeping", "← Reset application: $count API keys restored")
                        onComplete(true, "Reset complete — $count API keys restored")
                    },
                    onFailure = { ex ->
                        AppLog.e("Housekeeping", "← Reset application FAILED", ex)
                        onComplete(false, "Reset failed: ${ex.javaClass.simpleName}: ${ex.message}")
                    }
                )
            }
        }
    }

    // ===== Settings =====

    fun updateGeneralSettings(settings: GeneralSettings) {
        val previous = _uiState.value.generalSettings
        ModelType.userDefaults = settings.defaultTypePaths
        ApiTracer.isTracingEnabled = settings.tracingEnabled
        ApiTracer.showLadybugIcons = settings.showLadybugIcons
        com.ai.data.AuditLog.enabled = settings.auditLogEnabled
        NetworkSettings.streamingReadTimeoutSec = settings.streamingReadTimeoutSec
        NetworkSettings.nonStreamingReadTimeoutSec = settings.nonStreamingReadTimeoutSec
        NetworkSettings.maxCallsPerProviderPerMinute = settings.maxCallsPerProviderPerMinute
        NetworkSettings.maxConcurrentCallsPerProvider = settings.maxConcurrentCallsPerProvider
        NetworkSettings.maxRetriesOn429 = settings.maxRetriesOn429
        NetworkSettings.retryBackoffMs429 = settings.retryBackoffMs429
        NetworkSettings.maxRetriesOn529 = settings.maxRetriesOn529
        NetworkSettings.retryBackoffMs529 = settings.retryBackoffMs529
        AppLog.threshold = settings.logLevel
        // Java's Semaphore can't be resized in place — clear the
        // per-host map so the next acquire builds a fresh semaphore at
        // the new cap. The per-minute window is read on every acquire,
        // so it takes effect immediately and needs no reset.
        if (settings.maxConcurrentCallsPerProvider != previous.maxConcurrentCallsPerProvider) {
            ProviderThrottle.resetForNewLimits()
        }
        // Rebuild the cross-host concurrency semaphores when any of
        // the caps changed. Already-held permits release against
        // their original semaphore (held alive by the holder), so
        // swap-on-change is safe.
        if (settings.maxConcurrentApiCalls != previous.maxConcurrentApiCalls) {
            ApiCallCaps.resetForNewLimits(globalMax = settings.maxConcurrentApiCalls)
        }
        if (settings.logLevel != previous.logLevel) {
            AppLog.i("Settings", "Log level changed: ${previous.logLevel} → ${settings.logLevel}")
        }
        _uiState.update { it.copy(generalSettings = settings) }
        viewModelScope.launch(Dispatchers.IO) { settingsPrefs.saveGeneralSettings(settings) }
    }

    /** Push (provider, model) onto the front of the Report-section
     *  recent-models list, dedupe, and trim to 3. Called by the
     *  Report-section model pickers right before they fire the
     *  caller's onConfirm so the next picker render surfaces this
     *  pick at the top under "Recent". */
    fun recordRecentReportModel(providerId: String, model: String) {
        if (providerId.isBlank() || model.isBlank()) return
        AppLog.v("RecentModels", "record $providerId/$model")
        val entry = "$providerId|$model"
        val current = _uiState.value.generalSettings.recentReportModels
        if (current.firstOrNull() == entry) return  // already at front, nothing to do
        val next = (listOf(entry) + current.filter { it != entry }).take(3)
        updateGeneralSettings(_uiState.value.generalSettings.copy(recentReportModels = next))
    }

    /** Resolve [GeneralSettings.recentReportModels] strings back into
     *  (AppService, model) pairs, dropping any entry whose provider id
     *  no longer maps to a known AppService (e.g. a custom provider
     *  the user deleted). Order preserved. */
    fun recentReportModelPairs(): List<Pair<AppService, String>> {
        return _uiState.value.generalSettings.recentReportModels.mapNotNull { entry ->
            val parts = entry.split("|", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val service = AppService.findById(parts[0]) ?: return@mapNotNull null
            service to parts[1]
        }
    }

    fun updateSettings(settings: Settings) {
        _uiState.update { it.copy(aiSettings = settings) }
        syncTestModelPrompt(settings)
        viewModelScope.launch(Dispatchers.IO) { settingsPrefs.saveSettings(settings) }
    }

    /** Mirror the bundled `test-model` internal prompt's body into
     *  [com.ai.data.AnalysisRepository.TEST_PROMPT] so every OK-probe
     *  call site (ApiDispatch.testModel, ModelTestEngine, the
     *  per-provider tests, the L3 display) uses the live text without
     *  threading aiSettings through. Falls back to whatever was there
     *  if the prompt is missing. */
    private fun syncTestModelPrompt(settings: Settings) {
        val live = settings.internalPrompts.firstOrNull {
            it.name.equals("test-model", ignoreCase = true) && it.category.equals("internal", ignoreCase = true)
        }
        if (live != null && live.text.isNotBlank()) {
            com.ai.data.AnalysisRepository.TEST_PROMPT = live.text
        }
    }

    /** Apply one just-completed "Test all models" item to the
     *  Blocked-models + Test-excluded lists *in memory only* (no disk
     *  write — the engine flushes once at end-of-run via
     *  [flushAiSettingsToDisk]). Called from the engine's per-item
     *  PASS/FAIL transition so the user sees entries appear in those
     *  lists live as the sweep progresses. Semantics match the former
     *  batched [applyTestRunResults]:
     *   - PASS → drop from Blocked.
     *   - FAIL not on cooldown → upsert Blocked with the error as reason.
     *   - FAIL on cooldown → drop from Blocked (the cooldown list owns it).
     *   - totalCost > [COSTLY_PROBE_USD_THRESHOLD] → append to Test-
     *     excluded (no-clobber). */
    fun applyTestItemIncrement(item: com.ai.data.ModelTestState) {
        val status = item.status
        if (status != com.ai.data.TestStatus.PASS && status != com.ai.data.TestStatus.FAIL) return
        val onCooldown = com.ai.data.ModelCooldownStore.isUnavailable(item.providerId, item.model)
        _uiState.update { current ->
            val s = current.aiSettings
            val key = item.key
            var blocked = s.blockedModels
            // Always start by dropping any existing entry for this
            // (provider, model); FAIL-not-on-cooldown re-adds with the
            // fresh error message below.
            if (blocked.any { it.key == key }) {
                blocked = blocked.filterNot { it.key == key }
            }
            if (status == com.ai.data.TestStatus.FAIL && !onCooldown) {
                blocked = blocked + com.ai.model.BlockedModel(
                    item.providerId, item.model,
                    item.errorMessage?.take(300) ?: "Test failed"
                )
            }
            var excluded = s.testExcludedModels
            if (item.totalCost > COSTLY_PROBE_USD_THRESHOLD && excluded.none { it.key == key }) {
                excluded = excluded + com.ai.model.TestExcludedModel(item.providerId, item.model)
            }
            if (blocked === s.blockedModels && excluded === s.testExcludedModels) return@update current
            current.copy(aiSettings = s.copy(blockedModels = blocked, testExcludedModels = excluded))
        }
    }

    /** Append an [InaccessibleModel] entry in-memory (no disk write —
     *  the test engine flushes once at end-of-run via
     *  [flushAiSettingsToDisk]). Called from the engine when a probe
     *  returns a tier-gating error ("Unable to access non-serverless"
     *  on Together, etc.) so the user sees the entry appear in the
     *  Inaccessible CRUD live. Upsert by `(providerId, model)`. */
    fun upsertInaccessibleModel(m: com.ai.model.InaccessibleModel) {
        _uiState.update { current ->
            val s = current.aiSettings
            if (s.inaccessibleModels.any { it.key == m.key && it.reason == m.reason }) return@update current
            current.copy(aiSettings = s.upsertInaccessibleModel(m))
        }
    }

    /** Persist the in-memory Settings to disk once. Used by
     *  [com.ai.viewmodel.ModelTestEngine] at end-of-run / cancel to
     *  capture the cumulative effect of all [applyTestItemIncrement]
     *  calls in a single SharedPreferences write. */
    fun flushAiSettingsToDisk() {
        val snapshot = _uiState.value.aiSettings
        viewModelScope.launch(Dispatchers.IO) { settingsPrefs.saveSettings(snapshot) }
        AppLog.i(
            "ModelTest",
            "→ test-run flush: ${snapshot.blockedModels.size} blocked, ${snapshot.testExcludedModels.size} test-excluded, ${snapshot.inaccessibleModels.size} inaccessible"
        )
    }


    fun updateProviderState(service: AppService, state: String) {
        // Compute the delta inside the StateFlow.update CAS lambda so
        // concurrent calls (e.g. the parallel provider-test sweep
        // inside Refresh All) each apply their change to the latest
        // snapshot — capturing _uiState.value once at function entry
        // and then writing back the closed-over local clobbered every
        // peer's update, leaving the surface bug "Refresh All reported
        // 2 failures but only 1 red cross in AI Setup".
        _uiState.update { current ->
            var updated = current.aiSettings.withProviderState(service, state)
            // When a provider goes inactive, drop its default agent (the one named after the
            // provider's displayName) so flocks/swarms don't keep referencing a disabled path.
            if (state == "inactive") {
                val pruned = updated.agents.filterNot { it.provider.id == service.id && it.name == service.id }
                if (pruned.size != updated.agents.size) {
                    val droppedIds = updated.agents.filter { it !in pruned }.map { it.id }.toSet()
                    val flocks = updated.flocks.map { f -> f.copy(agentIds = f.agentIds.filterNot { it in droppedIds }) }
                    updated = updated.copy(agents = pruned, flocks = flocks)
                }
            }
            current.copy(aiSettings = updated)
        }
        // Save the latest post-update snapshot — picks up any peer
        // updates that landed in the same window. Last writer wins on
        // the persistence layer too, but every concurrent caller's
        // change is in the snapshot we save.
        val final = _uiState.value.aiSettings
        viewModelScope.launch(Dispatchers.IO) { settingsPrefs.saveSettings(final) }
    }

    /** Called by the per-provider Test button when the test passes:
     *  flips the provider state to "ok" AND ensures there's a row for
     *  the provider in the "default agents" flock (creating the agent
     *  + flock if needed). Both edits are applied to the same Settings
     *  copy so the StateFlow update lands atomically and a single save
     *  flushes the result to disk.
     *
     *  Also kicks off a background `/v1/models` fetch — when that
     *  succeeds the provider's `modelSource` flips to API and the
     *  fetched ids replace the manual list. A failed fetch leaves
     *  modelSource untouched (the test itself already passed, so the
     *  provider stays "ok" either way). */
    /** Called by the per-provider settings screen after the user picks
     *  a new default model and the API-key test succeeds. Drops every
     *  agent named after the provider's displayName (and prunes those
     *  ids from every flock), then recreates a fresh default agent
     *  pointing at [defaultModel] and adds it back to the
     *  "default agents" flock. */
    fun replaceDefaultAgent(service: AppService, defaultModel: String) {
        // CAS-style update so a concurrent updateProviderState /
        // markProviderTestedOk call doesn't get clobbered by the
        // closed-over local-snapshot pattern.
        _uiState.update { current ->
            val droppedIds = current.aiSettings.agents
                .filter { it.provider.id == service.id && it.name == service.id }
                .map { it.id }.toSet()
            val pruned = current.aiSettings.copy(
                agents = current.aiSettings.agents.filterNot { it.id in droppedIds },
                flocks = current.aiSettings.flocks.map { f -> f.copy(agentIds = f.agentIds.filterNot { it in droppedIds }) }
            )
            current.copy(aiSettings = pruned.ensureDefaultAgentInFlock(service, defaultModel))
        }
        val final = _uiState.value.aiSettings
        viewModelScope.launch(Dispatchers.IO) { settingsPrefs.saveSettings(final) }
    }

    fun markProviderTestedOk(service: AppService, defaultModel: String, fetchAfter: Boolean = true) {
        // CAS-style update — see updateProviderState for the same
        // race that the closed-over snapshot pattern caused.
        _uiState.update { current ->
            val updated = current.aiSettings
                .withProviderState(service, "ok")
                .ensureDefaultAgentInFlock(service, defaultModel)
            current.copy(aiSettings = updated)
        }
        val final = _uiState.value.aiSettings
        viewModelScope.launch(Dispatchers.IO) { settingsPrefs.saveSettings(final) }
        // Background model-list fetch with API-source flip on success.
        // The activation flow pre-fetches synchronously and passes
        // [fetchAfter] = false to avoid a duplicate request.
        if (fetchAfter) {
            fetchModels(service, _uiState.value.aiSettings.getApiKey(service), flipToApiOnSuccess = true)
        }
    }

    fun clearTraces() = ApiTracer.clearTraces()

    // ===== Report Agents/Models Selection =====

    // SharedPreferences docs forbid mutating the set returned by getStringSet AND the set passed
    // to putStringSet; we defensively copy on both sides so aliasing can't corrupt stored state.
    fun loadReportAgents(): Set<String> = prefs.getStringSet(AI_REPORT_AGENTS_KEY, emptySet())?.toHashSet() ?: emptySet()
    fun saveReportAgents(agentIds: Set<String>) { prefs.edit { putStringSet(AI_REPORT_AGENTS_KEY, agentIds.toHashSet()) } }
    fun loadReportModels(): Set<String> = prefs.getStringSet(AI_REPORT_MODELS_KEY, emptySet())?.toHashSet() ?: emptySet()
    fun saveReportModels(modelIds: Set<String>) { prefs.edit { putStringSet(AI_REPORT_MODELS_KEY, modelIds.toHashSet()) } }

    // ===== Model Fetching =====

    fun fetchModels(service: AppService, apiKey: String, flipToApiOnSuccess: Boolean = false) {
        viewModelScope.launch { fetchModelsAwait(service, apiKey, flipToApiOnSuccess) }
    }

    /** Suspend variant of [fetchModels]. Returns null on success or an
     *  error message on failure. Used by the provider-activation flow,
     *  which needs to await the result before deciding whether to test
     *  the API key and create the default agent. */
    suspend fun fetchModelsAwait(service: AppService, apiKey: String, flipToApiOnSuccess: Boolean = false): String? {
        return withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            _uiState.update { it.copy(
                loadingModelsFor = it.loadingModelsFor + service,
                fetchModelsErrors = it.fetchModelsErrors - service.id
            ) }
            try {
                val fetched = repository.fetchModelsWithKinds(service, apiKey)
                // Persist the raw /models response to disk under
                // files/model_lists/<id>.json for later
                // pricing/capability lookups. Done before the in-memory
                // settings update so a subsequent crash still leaves a
                // valid snapshot on disk.
                ModelListCache.save(getApplication(), service, fetched.rawResponse)
                // Together AI ships per-model pricing inside the
                // /v1/models response itself; the dispatcher harvests
                // it into FetchedModels.nativePricing and we pump it
                // straight into the TOGETHER pricing tier here so a
                // model-list refresh doubles as a pricing refresh.
                if (fetched.nativePricing.isNotEmpty()) {
                    com.ai.data.PricingCache.saveTogetherPricing(getApplication(), fetched.nativePricing)
                }
                _uiState.update { state ->
                    val withSelf = state.aiSettings.withModels(service, fetched.ids, fetched.types, fetched.visionModels, fetched.capabilities, fetched.rawResponse)
                    // When the caller asked for an API-source flip
                    // (per-provider Test button), apply it in the same
                    // state update so model list + source land atomically.
                    val withSource = if (flipToApiOnSuccess && fetched.ids.isNotEmpty()) {
                        if (withSelf.getModelSource(service) != ModelSource.API) {
                            // Writes through ProviderRegistry.update — Settings is unchanged.
                            withSelf.withModelSource(service, ModelSource.API)
                        }
                        withSelf
                    } else withSelf
                    // Fan out-pollinate OpenRouter labels — covers two flows:
                    //   • non-OpenRouter fetch picks up labels OpenRouter already has cached
                    //   • OpenRouter fetch propagates fresh labels to every other provider
                    state.copy(aiSettings = withSource.applyOpenRouterTypes(), loadingModelsFor = state.loadingModelsFor - service)
                }
                val final = _uiState.value.aiSettings
                val cfgSelf = final.getProvider(service)
                settingsPrefs.saveModelsForProvider(service, fetched.ids, cfgSelf.modelTypes, cfgSelf.visionModels, cfgSelf.modelCapabilities, cfgSelf.modelListRawJson)
                // saveModelsForProvider only writes the per-key model
                // set. modelSource was flipped above through
                // ProviderRegistry.update (which auto-persists to its
                // own prefs), so no settings flush needed here.
                if (service.crossProviderModelList) {
                    // Persist the freshly fan out-applied labels for every other provider.
                    AppService.entries.filter { it.id != service.id }.forEach { other ->
                        val cfg = final.getProvider(other)
                        if (cfg.models.isNotEmpty()) settingsPrefs.saveModelsForProvider(other, cfg.models, cfg.modelTypes, cfg.visionModels, cfg.modelCapabilities)
                    }
                }
                null
            } catch (e: Exception) {
                AppLog.w("App", "Failed to fetch models for ${service.id}: ${e.message}")
                val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                // Match the trace bracketed by withTraceCategory("model/list")
                // in ApiDispatch.fetchModelsWithKinds. Filtering by category
                // as well as timestamp keeps concurrent fetches from
                // clobbering each other's pointers.
                val traceFile = if (ApiTracer.isTracingEnabled) {
                    // Multiple parallel fetches all share the
                    // "model/list" category, so the
                    // category filter alone could pick a different
                    // provider's trace. Match on hostname too —
                    // the AppService.baseUrl host is the most
                    // specific identity we have.
                    val providerHost = runCatching {
                        java.net.URI(service.baseUrl).host?.lowercase()
                    }.getOrNull()
                    ApiTracer.getTraceFiles()
                        .firstOrNull {
                            it.timestamp >= startedAt &&
                                it.category == "model/list" &&
                                (providerHost == null || it.hostname.equals(providerHost, ignoreCase = true))
                        }
                        ?.filename
                } else null
                _uiState.update { it.copy(
                    loadingModelsFor = it.loadingModelsFor - service,
                    fetchModelsErrors = it.fetchModelsErrors + (service.id to FetchModelsError(msg, traceFile))
                ) }
                msg
            }
        }
    }

    suspend fun refreshAllModelLists(settings: Settings, forceRefresh: Boolean = false, onProgress: ((String) -> Unit)? = null): Map<String, Int> {
        return withContext(Dispatchers.IO) {
            val toRefresh = AppService.entries.filter { service ->
                // Only refresh model lists for active working providers.
                // isProviderActive == state == "ok", which implies the
                // saved API key already passed a live test — model-list
                // refreshes against an unkeyed / errored / inactive
                // provider would just hit a 401 and pollute the trace
                // log with no diagnostic gain.
                settings.isProviderActive(service) &&
                    settings.getModelSource(service) == ModelSource.API &&
                    (forceRefresh || !settingsPrefs.isModelListCacheValid(service))
            }
            if (toRefresh.isEmpty()) return@withContext emptyMap()
            AppLog.d("RefreshAll", "→ ${toRefresh.size} provider(s): ${toRefresh.joinToString { it.id }}")
            val t0 = System.currentTimeMillis()

            val results = coroutineScope {
                toRefresh.map { service ->
                    async {
                        onProgress?.invoke(service.id)
                        // Clear any previous error for this provider — the
                        // try below either succeeds (no error needed) or
                        // catches and re-stamps a fresh one.
                        _uiState.update { it.copy(fetchModelsErrors = it.fetchModelsErrors - service.id) }
                        try {
                            val fetched = repository.fetchModelsWithKinds(service, settings.getApiKey(service))
                            // Disk-cache the raw response for later
                            // pricing / capability lookups (see
                            // ModelListCache).
                            ModelListCache.save(getApplication(), service, fetched.rawResponse)
                            // Use updateAndGet so the provider config we
                            // persist comes from the exact post-update
                            // snapshot this lambda produced, not a later
                            // _uiState.value that a concurrent provider's
                            // update may have replaced (Bug 54).
                            val updated = _uiState.updateAndGet { state -> state.copy(aiSettings = state.aiSettings.withModels(service, fetched.ids, fetched.types, fetched.visionModels, fetched.capabilities, fetched.rawResponse)) }
                            // Persist with the freshly-merged visionModels (auto + user override), capability map, and raw response snapshot.
                            val cfg = updated.aiSettings.getProvider(service)
                            settingsPrefs.saveModelsForProvider(service, fetched.ids, fetched.types, cfg.visionModels, cfg.modelCapabilities, cfg.modelListRawJson)
                            service to fetched.ids.size
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Surface the failure on UiState.fetchModelsErrors
                            // so the model picker can show "fetch failed"
                            // instead of presenting a stale catalog as if
                            // it were fresh. The per-provider models on
                            // disk are preserved (we never called
                            // saveModelsForProvider for this provider).
                            val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                            _uiState.update {
                                it.copy(fetchModelsErrors = it.fetchModelsErrors + (service.id to FetchModelsError(msg, null)))
                            }
                            service to -1
                        }
                    }
                }.awaitAll()
            }

            val successful = results.filter { it.second > 0 }.map { it.first }
            if (successful.isNotEmpty()) settingsPrefs.updateModelListTimestamps(successful)
            // Final fan out-lookup pass — guarantees that whichever order the parallel
            // fetches finished in, OpenRouter's labels end up applied everywhere.
            _uiState.update { state -> state.copy(aiSettings = state.aiSettings.applyOpenRouterTypes()) }
            val final = _uiState.value.aiSettings
            successful.forEach { service ->
                val cfg = final.getProvider(service)
                if (cfg.models.isNotEmpty()) settingsPrefs.saveModelsForProvider(service, cfg.models, cfg.modelTypes, cfg.visionModels, cfg.modelCapabilities)
            }
            AppLog.d("RefreshAll", "← ok=${successful.size}/${toRefresh.size} in ${System.currentTimeMillis() - t0}ms")
            results.associate { it.first.id to it.second }
        }
    }

    // ===== Refresh-all orchestrator =====

    fun clearRefreshAllState() { _refreshAllState.value = null }

    /** Kick off a Refresh-all run on viewModelScope so the work survives
     *  navigation. Idempotent: a call while a run is in flight is a no-op
     *  (the caller should observe [refreshAllState] instead). The six
     *  catalog fetches run in parallel with the Workers phase (per-provider
     *  key test → optional model-list fetch → default-agent write); both
     *  phases join before the popup-forcing finish flag flips. */
    fun startRefreshAll() {
        if (_refreshAllState.value != null && _refreshAllState.value?.isFinished == false) return

        val app: Application = getApplication()
        val gs0 = _uiState.value.generalSettings
        val openRouterKey = gs0.openRouterApiKey
        val aaKey = gs0.artificialAnalysisApiKey
        val openRouterEnabled = openRouterKey.isNotBlank()
        val aaEnabled = aaKey.isNotBlank()

        val catalogSteps = listOf(
            CatalogStep("openrouter", "OpenRouter", if (openRouterEnabled) RefreshStepStatus.Pending else RefreshStepStatus.Skipped),
            CatalogStep("litellm", "LiteLLM"),
            CatalogStep("modelsdev", "models.dev"),
            CatalogStep("helicone", "Helicone"),
            CatalogStep("llmprices", "llm-prices.com"),
            CatalogStep("aa", "Artificial Analysis", if (aaEnabled) RefreshStepStatus.Pending else RefreshStepStatus.Skipped)
        )
        // Snapshot the testable provider set up-front. The clean-slate
        // step below rewrites flocks/agents, but the testable list is
        // derived purely from API key + provider state, neither of which
        // the clean-slate touches.
        val snapshot0 = _uiState.value.aiSettings
        val testable = AppService.entries
            .sortedBy { it.id }
            .filter { snapshot0.getProviderState(it) != "inactive" && snapshot0.getApiKey(it).isNotBlank() }

        _refreshAllState.value = RefreshAllState(
            catalogSteps = catalogSteps,
            workerRows = testable.map { WorkerRow(it.id) }
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // ---- Clean slate: delete every agent whose name matches a
                // provider id and whose provider id matches the same id
                // (i.e. the "default agent for provider X" rows this run
                // is about to rebuild) and empty the `default agents` flock.
                // Custom agents the user authored survive untouched.
                run {
                    val current = _uiState.value.aiSettings
                    val keptAgents = current.agents.filterNot { it.provider.id == it.name }
                    val droppedIds = current.agents.filter { it !in keptAgents }.map { it.id }.toSet()
                    val flocks = current.flocks.map { f ->
                        when {
                            f.name == com.ai.model.DEFAULT_AGENTS_FLOCK_NAME -> f.copy(agentIds = emptyList())
                            droppedIds.isEmpty() -> f
                            else -> f.copy(agentIds = f.agentIds.filterNot { it in droppedIds })
                        }
                    }
                    val cleaned = current.copy(agents = keptAgents, flocks = flocks)
                    _uiState.update { it.copy(aiSettings = cleaned) }
                    settingsPrefs.saveSettings(cleaned)
                }

                // ---- Run catalogs + workers in parallel.
                coroutineScope {
                    val catJob = launch { runCatalogPhase(app, openRouterKey, aaKey, openRouterEnabled, aaEnabled) }
                    val wrkJob = launch { runWorkerPhase(testable) }
                    catJob.join(); wrkJob.join()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _refreshAllState.update { it?.copy(overallError = e.message?.takeIf { m -> m.isNotBlank() } ?: e.javaClass.simpleName) }
            } finally {
                _refreshAllState.update { it?.copy(isFinished = true) }
            }
        }
    }

    /** Worker-only variant of [startRefreshAll]. Skips every catalog
     *  fetch (OpenRouter / LiteLLM / models.dev / Helicone / llm-prices
     *  / Artificial Analysis) and runs only the per-provider clean-slate
     *  + worker phase (test key → fetch model list → write default
     *  agent). Used by the Housekeeping → Refresh → "Providers / models
     *  / default agents" card so the user can re-seed providers without
     *  paying for every external catalog round-trip. */
    fun startRefreshWorkers() {
        if (_refreshAllState.value != null && _refreshAllState.value?.isFinished == false) return

        val snapshot0 = _uiState.value.aiSettings
        val testable = AppService.entries
            .sortedBy { it.id }
            .filter { snapshot0.getProviderState(it) != "inactive" && snapshot0.getApiKey(it).isNotBlank() }

        _refreshAllState.value = RefreshAllState(
            catalogSteps = emptyList(),
            workerRows = testable.map { WorkerRow(it.id) },
            title = "Providers / models / default agents"
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Same clean-slate as startRefreshAll: drop every
                // auto-generated default agent (name == provider id) and
                // empty the "default agents" flock so this run repopulates
                // both from scratch. User-authored agents survive.
                run {
                    val current = _uiState.value.aiSettings
                    val keptAgents = current.agents.filterNot { it.provider.id == it.name }
                    val droppedIds = current.agents.filter { it !in keptAgents }.map { it.id }.toSet()
                    val flocks = current.flocks.map { f ->
                        when {
                            f.name == com.ai.model.DEFAULT_AGENTS_FLOCK_NAME -> f.copy(agentIds = emptyList())
                            droppedIds.isEmpty() -> f
                            else -> f.copy(agentIds = f.agentIds.filterNot { it in droppedIds })
                        }
                    }
                    val cleaned = current.copy(agents = keptAgents, flocks = flocks)
                    _uiState.update { it.copy(aiSettings = cleaned) }
                    settingsPrefs.saveSettings(cleaned)
                }
                runWorkerPhase(testable)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _refreshAllState.update { it?.copy(overallError = e.message?.takeIf { m -> m.isNotBlank() } ?: e.javaClass.simpleName) }
            } finally {
                _refreshAllState.update { it?.copy(isFinished = true) }
            }
        }
    }

    private fun setCatalogStep(id: String, status: RefreshStepStatus) {
        _refreshAllState.update { st ->
            st ?: return@update null
            st.copy(catalogSteps = st.catalogSteps.map { if (it.id == id) it.copy(status = status) else it })
        }
    }

    private fun setWorkerStage(serviceId: String, stage: WorkerStage) {
        _refreshAllState.update { st ->
            st ?: return@update null
            st.copy(workerRows = st.workerRows.map { if (it.serviceId == serviceId) it.copy(stage = stage) else it })
        }
    }

    private suspend fun runCatalogPhase(
        app: Application,
        openRouterKey: String,
        aaKey: String,
        openRouterEnabled: Boolean,
        aaEnabled: Boolean
    ) {
        // Snapshot every tier's previous cache state BEFORE any fetch
        // starts — so the "kept previous N from Xago" detail on a failed
        // step reflects what was there at refresh-all-start, not what's
        // been overwritten by a sibling success that's already landed.
        fun previousDetail(source: String): String {
            val info = PricingCache.previousCacheInfo(app, source) ?: return "no previous to keep"
            return "kept previous ${info.entryCount} from ${info.ageString()}"
        }
        coroutineScope {
            val jobs = mutableListOf<kotlinx.coroutines.Deferred<*>>()
            if (openRouterEnabled) jobs += async(Dispatchers.IO) {
                setCatalogStep("openrouter", RefreshStepStatus.Running())
                val prev = previousDetail("openrouter")
                try {
                    val pricing = PricingCache.fetchOpenRouterPricing(openRouterKey)
                    if (pricing.isNotEmpty()) PricingCache.saveOpenRouterPricing(app, pricing)
                    val specs = PricingCache.fetchAndSaveModelSpecifications(app, openRouterKey)
                    if (pricing.isEmpty()) setCatalogStep("openrouter", RefreshStepStatus.Failed("no entries · $prev"))
                    else setCatalogStep("openrouter", RefreshStepStatus.Done("${pricing.size} priced · ${specs?.first ?: 0} specs"))
                } catch (e: Exception) {
                    setCatalogStep("openrouter", RefreshStepStatus.Failed("${e.message?.take(60) ?: "failed"} · $prev"))
                }
            }
            jobs += async(Dispatchers.IO) {
                setCatalogStep("litellm", RefreshStepStatus.Running())
                val prev = previousDetail("litellm")
                try {
                    val n = PricingCache.fetchLiteLLMPricingOnline(app)
                    if (n != null && n > 0) setCatalogStep("litellm", RefreshStepStatus.Done("$n priced"))
                    else setCatalogStep("litellm", RefreshStepStatus.Failed("no entries · $prev"))
                } catch (e: Exception) {
                    setCatalogStep("litellm", RefreshStepStatus.Failed("${e.message?.take(60) ?: "failed"} · $prev"))
                }
            }
            jobs += async(Dispatchers.IO) {
                setCatalogStep("modelsdev", RefreshStepStatus.Running())
                val prev = previousDetail("modelsdev")
                try {
                    val n = PricingCache.fetchModelsDevOnline(app)
                    if (n != null && n > 0) setCatalogStep("modelsdev", RefreshStepStatus.Done("$n priced"))
                    else setCatalogStep("modelsdev", RefreshStepStatus.Failed("no entries · $prev"))
                } catch (e: Exception) {
                    setCatalogStep("modelsdev", RefreshStepStatus.Failed("${e.message?.take(60) ?: "failed"} · $prev"))
                }
            }
            jobs += async(Dispatchers.IO) {
                setCatalogStep("helicone", RefreshStepStatus.Running())
                val prev = previousDetail("helicone")
                try {
                    val n = PricingCache.fetchHeliconeOnline(app)
                    if (n != null && n > 0) setCatalogStep("helicone", RefreshStepStatus.Done("$n entries"))
                    else setCatalogStep("helicone", RefreshStepStatus.Failed("no entries · $prev"))
                } catch (e: Exception) {
                    setCatalogStep("helicone", RefreshStepStatus.Failed("${e.message?.take(60) ?: "failed"} · $prev"))
                }
            }
            jobs += async(Dispatchers.IO) {
                setCatalogStep("llmprices", RefreshStepStatus.Running())
                val prev = previousDetail("llmprices")
                try {
                    val n = PricingCache.fetchLLMPricesOnline(app)
                    if (n != null && n > 0) setCatalogStep("llmprices", RefreshStepStatus.Done("$n entries"))
                    else setCatalogStep("llmprices", RefreshStepStatus.Failed("no entries · $prev"))
                } catch (e: Exception) {
                    setCatalogStep("llmprices", RefreshStepStatus.Failed("${e.message?.take(60) ?: "failed"} · $prev"))
                }
            }
            if (aaEnabled) jobs += async(Dispatchers.IO) {
                setCatalogStep("aa", RefreshStepStatus.Running())
                val prev = previousDetail("aa")
                try {
                    val n = PricingCache.fetchArtificialAnalysisOnline(app, aaKey)
                    if (n != null && n > 0) setCatalogStep("aa", RefreshStepStatus.Done("$n entries"))
                    else setCatalogStep("aa", RefreshStepStatus.Failed("no entries · $prev"))
                } catch (e: Exception) {
                    setCatalogStep("aa", RefreshStepStatus.Failed("${e.message?.take(60) ?: "failed"} · $prev"))
                }
            }
            jobs.awaitAll()
        }
        // Catalog answers may have shifted — refresh the precomputed
        // vision / web-search sets so list renders pick up the new state.
        _uiState.update { it.copy(aiSettings = it.aiSettings.recomputeAllCapabilities()) }
        settingsPrefs.saveSettings(_uiState.value.aiSettings)
    }

    /** Per-provider worker phase. Each provider runs in parallel:
     *  test key → (if ModelSource.API) fetch model list → write default
     *  agent + add to `default agents` flock. The settings copy-on-write
     *  is serialised through [_uiState.update]'s CAS lambda which already
     *  handles concurrent mutators (same pattern as updateProviderState). */
    private suspend fun runWorkerPhase(testable: List<AppService>) {
        if (testable.isEmpty()) return
        kotlinx.coroutines.supervisorScope {
            testable.map { service ->
                async(Dispatchers.IO) {
                    val snapshot = _uiState.value.aiSettings
                    val apiKey = snapshot.getApiKey(service)
                    val model = snapshot.getModel(service)

                    setWorkerStage(service.id, WorkerStage.TestingKey)
                    val testError = try { testAiModel(service, apiKey, model) } catch (e: Exception) { e.message ?: "error" }
                    val passed = testError == null
                    updateProviderState(service, if (passed) "ok" else "error")
                    if (!passed) {
                        setWorkerStage(service.id, WorkerStage.Failed(testError ?: "error"))
                        return@async
                    }

                    if (resolveModelSource(service) == ModelSource.API) {
                        setWorkerStage(service.id, WorkerStage.FetchingModels)
                        // Model-list fetch failures are non-fatal — we still
                        // create the default agent against the saved model.
                        runCatching { fetchModelsAwait(service, apiKey, flipToApiOnSuccess = false) }
                            .onFailure { AppLog.w("RefreshAll", "model fetch failed for ${service.id}: ${it.message}") }
                    }

                    setWorkerStage(service.id, WorkerStage.WritingAgent)
                    val currentModel = _uiState.value.aiSettings.getModel(service)
                    val agentId = java.util.UUID.randomUUID().toString()
                    val newAgent = com.ai.model.Agent(agentId, service.id, service, currentModel, "")
                    _uiState.update { st ->
                        val cur = st.aiSettings
                        val withAgent = cur.copy(agents = cur.agents + newAgent)
                        val flocks = withAgent.flocks
                        val existing = flocks.find { it.name == com.ai.model.DEFAULT_AGENTS_FLOCK_NAME }
                        val withFlock = if (existing != null) {
                            withAgent.copy(flocks = flocks.map {
                                if (it.id == existing.id) it.copy(agentIds = it.agentIds + agentId) else it
                            })
                        } else {
                            val flock = com.ai.model.Flock(
                                java.util.UUID.randomUUID().toString(),
                                com.ai.model.DEFAULT_AGENTS_FLOCK_NAME,
                                listOf(agentId)
                            )
                            withAgent.copy(flocks = withAgent.flocks + flock)
                        }
                        st.copy(aiSettings = withFlock)
                    }
                    settingsPrefs.saveSettings(_uiState.value.aiSettings)
                    setWorkerStage(service.id, WorkerStage.Done)
                }
            }.awaitAll()
        }
    }

    // ===== Model Testing =====

    suspend fun testAiModel(service: AppService, apiKey: String, model: String): String? {
        return try {
            val result = repository.testModel(service, apiKey, model)
            if (result == null) settingsPrefs.updateUsageStatsAsync(service, model, 10, 2, 12)
            result
        } catch (e: Exception) { e.message ?: "Test failed" }
    }

    /** Ask one model a free-form [prompt] and return its response text (null
     *  on error / empty) — used by the Default-icons "AI" icon finder to
     *  extract an emoji per model. Tagged with the "settings/icons" category so
     *  the calls land under that bucket in both the API Traces and AI Usage /
     *  Costs (trace category + recorded usage kind), rather than the generic
     *  "Provider test" bucket the plain prompt-test path uses. */
    suspend fun askModelText(service: AppService, model: String, prompt: String): String? = try {
        val apiKey = uiState.value.aiSettings.getApiKey(service)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.ai.data.withTraceCategory(SETTINGS_ICONS_CALL_KIND) {
                val response = repository.analyze(service, apiKey, prompt, model)
                if (response.isSuccess) {
                    response.tokenUsage?.let { u ->
                        settingsPrefs.updateUsageStatsAsync(
                            service, model, u,
                            kind = SETTINGS_ICONS_CALL_KIND
                        )
                    }
                    response.analysis
                } else null
            }
        }
    } catch (_: Exception) { null }

    suspend fun testModelWithPrompt(service: AppService, apiKey: String, model: String, prompt: String): Pair<Boolean, String?> {
        return try {
            val traceCountBefore = ApiTracer.getTraceCount()
            val (responseText, _) = repository.testModelWithPrompt(service, apiKey, model, prompt)
            val traceFile = ApiTracer.getTraceFiles().firstOrNull()?.let {
                if (ApiTracer.getTraceCount() > traceCountBefore) it.filename else null
            } ?: ApiTracer.getTraceFiles().firstOrNull()?.filename
            Pair(responseText != null && responseText.isNotBlank(), traceFile)
        } catch (_: Exception) { Pair(false, ApiTracer.getTraceFiles().firstOrNull()?.filename) }
    }

    /**
     * Per-model test variant safe to run concurrently. Captures startTime before the
     * call and then resolves the trace file by matching model name + timestamp, so
     * five parallel "Test all models" calls don't all collapse onto whichever trace
     * happened to land last globally.
     */
    suspend fun testSpecificModel(service: AppService, apiKey: String, model: String, prompt: String): Pair<Boolean, String?> {
        val startTime = System.currentTimeMillis()
        return try {
            val (responseText, _) = repository.testModelWithPrompt(service, apiKey, model, prompt)
            val traceFile = ApiTracer.getTraceFiles()
                .firstOrNull { it.model == model && it.timestamp >= startTime }
                ?.filename
            Pair(responseText != null && responseText.isNotBlank(), traceFile)
        } catch (_: Exception) {
            val traceFile = ApiTracer.getTraceFiles()
                .firstOrNull { it.model == model && it.timestamp >= startTime }
                ?.filename
            Pair(false, traceFile)
        }
    }

    // ===== External Intent =====

    fun setExternalInstructions(
        closeHtml: String?, reportType: String?, email: String?,
        nextAction: String? = null, returnAfterNext: Boolean = false,
        agentNames: List<String> = emptyList(), flockNames: List<String> = emptyList(),
        swarmNames: List<String> = emptyList(), modelSpecs: List<String> = emptyList(),
        edit: Boolean = false, select: Boolean = false, openHtml: String? = null,
        systemPrompt: String? = null
    ) {
        _uiState.update { it.copy(externalIntent = ExternalIntent(
            systemPrompt = systemPrompt, closeHtml = closeHtml,
            reportType = reportType, email = email,
            nextAction = nextAction, returnAfterNext = returnAfterNext,
            edit = edit, select = select, openHtml = openHtml,
            agentNames = agentNames, flockNames = flockNames,
            swarmNames = swarmNames, modelSpecs = modelSpecs
        )) }
    }

    fun clearExternalInstructions() {
        _uiState.update { it.copy(externalIntent = ExternalIntent()) }
    }

    // ===== Chat Parameters =====

    fun setChatParameters(params: ChatParameters) { _uiState.update { it.copy(chatParameters = params) } }
    fun setDualChatConfig(config: DualChatConfig?) { _uiState.update { it.copy(dualChatConfig = config) } }
    fun setReportAdvancedParameters(params: AgentParameters?) { _uiState.update { it.copy(reportAdvancedParameters = params) } }
    /** Set the report-level Parameters preset ids and resolve them into
     *  the pre-gen override so generation honours them through the
     *  existing [reportAdvancedParameters] path. Empty → clears both. */
    fun setReportParametersIds(ids: List<String>) {
        _uiState.update { it.copy(reportParametersIds = ids, reportAdvancedParameters = it.aiSettings.mergeParameters(ids)) }
    }
    fun setReportSystemPromptId(id: String?) { _uiState.update { it.copy(reportSystemPromptId = id) } }

    // ===== Internal helpers =====

    internal fun updateUiState(block: (UiState) -> UiState) { _uiState.update(block) }

    companion object {
        const val PREFS_NAME = "eval_prefs"
        /** A "Test all models" probe whose cost exceeds this (USD) is
         *  auto-added to [com.ai.model.Settings.testExcludedModels] so
         *  the next sweep doesn't pay for it again. Matches the user-
         *  facing "5¢" wording on the AI Setup card. */
        internal const val COSTLY_PROBE_USD_THRESHOLD = 0.05
        fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)
        internal const val AI_REPORT_AGENTS_KEY = "ai_report_agents_v2"
        internal const val AI_REPORT_MODELS_KEY = "ai_report_models_v2"
        internal val USER_TAG_REGEX = Regex("""<user>(.*?)</user>""", RegexOption.DOT_MATCHES_ALL)

        // First-run marker. Absent on a fresh install and after a data
        // clear / reinstall, present after the very first successful
        // bootstrap. Survives app updates (SharedPreferences is part of
        // user data, untouched by APK upgrades), so the bundled
        // providers.json + internal-prompts/ import is one-shot per install.
        internal const val KEY_FIRST_RUN_BOOTSTRAPPED = "first_run_bootstrapped"

    }
}
