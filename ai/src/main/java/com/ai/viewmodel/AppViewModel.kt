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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// General app settings
/** How combined provider+model labels render across UI rows.
 *  MODEL_ONLY shows just the model id (the dense default); PROVIDER_AND_MODEL
 *  shows both, joined by " · ", for users who run the same model on
 *  multiple providers and want to disambiguate at a glance. */
enum class ModelNameLayout { MODEL_ONLY, PROVIDER_AND_MODEL }

/** How a detail screen's title bar combines its static label with its
 *  dynamic subject (model id, KB name, agent name, …).
 *  HARDCODED: title bar shows only the static label; the subject
 *  renders below as the green sub-header (legacy default).
 *  SUBJECT: title bar shows the subject; green sub-header hidden.
 *  BOTH: title bar shows "<static label> / <subject>"; green sub-header
 *  hidden. */
enum class SubjectToTitleBarMode { HARDCODED, SUBJECT, BOTH }

data class GeneralSettings(
    val userName: String = "user",
    val huggingFaceApiKey: String = "",
    val openRouterApiKey: String = "",
    /** Free-tier API key for artificialanalysis.ai/api/v2/data/llms/models —
     *  empty until the user pastes one in External Services. The Refresh
     *  screen disables the AA button while this is blank. */
    val artificialAnalysisApiKey: String = "",
    val defaultEmail: String = "",
    /** Default API path per model type. Used when a provider doesn't declare a
     *  per-type override in its typePaths. Falls back to ModelType.DEFAULT_PATHS
     *  when this map is empty for a given type. */
    val defaultTypePaths: Map<String, String> = emptyMap(),
    /** Master switch for API tracing. When false, no new traces are
     *  written, the Hub "AI API Traces" card is hidden, and every 🐞
     *  ladybug icon disappears from the per-result screens. Mirrored
     *  to [com.ai.data.ApiTracer.isTracingEnabled] so non-UI call
     *  sites consult a single global. */
    val tracingEnabled: Boolean = true,
    /** Controls whether combined provider+model labels (Fan out drill-in
     *  rows, secondary picker buttons, agent rows on Report Result,
     *  chat headers, …) show only the model or both. Provided to the
     *  composition tree via LocalModelNameLayout in the AppNavHost. */
    val modelNameLayout: ModelNameLayout = ModelNameLayout.MODEL_ONLY,
    /** Compact-header mode (tri-state). HARDCODED keeps the legacy
     *  layout: fixed label in the title bar + green dynamic-subject
     *  sub-header below. SUBJECT folds the subject into the title bar
     *  and drops the green line. BOTH puts both ("<fixed> / <subject>")
     *  in the title bar and drops the green line. Provided via
     *  LocalSubjectToTitleBarMode. */
    val subjectToTitleBarMode: SubjectToTitleBarMode = SubjectToTitleBarMode.BOTH,
    /** Master switch for the per-report icon-gen feature. When true
     *  (default) every new report kicks off a background LLM call that
     *  generates a fitting emoji, the icon-row appears on the result
     *  page, the dynamic emoji shows in title bars / hub list / history
     *  / search hits, and the 📝 memo icon mirrors. When false the
     *  call is skipped, the icon row is hidden, the leftmost title-bar
     *  icon (and its tied 📝 memo) is hidden, and every per-row icon
     *  prefix falls back to the static 🕘 / 📌. Persisted icon /
     *  iconCost values on existing reports stay on disk — re-enabling
     *  brings them back. */
    val iconGenEnabled: Boolean = true,
    /** Master switch for the per-agent 3-tier icon chain
     *  ([com.ai.viewmodel.ReportViewModel.runReportIcons]). When true
     *  (default) every report that finishes generation — initial
     *  generation AND regenerate — auto-fires the chain on
     *  AppViewModel.viewModelScope so it survives the user
     *  navigating away from the result screen. Each successful
     *  agent's leftmost ✅ flips to a returned emoji once the
     *  chain finishes for that row. When false the chain never
     *  runs automatically; per-agent rows keep their plain ✅. */
    val perModelIconGenEnabled: Boolean = true,
    /** Master switch for the per-internal-prompt icon cache. When true
     *  (default), every secondary-result row on the report result page
     *  whose `metaPromptId` resolves to a known InternalPrompt gets a
     *  leading emoji generated once via the bundled
     *  `internal/prompt_icon` prompt and persisted in
     *  [com.ai.data.InternalPromptIconCache]. The cache is keyed on
     *  `(InternalPrompt.name, InternalPrompt.title)` so editing
     *  either field re-fires generation; cache hits cost nothing.
     *  When false, no new icons are generated and rows fall back to
     *  the plain text type label; already-cached entries stay on disk
     *  for re-enable. */
    val useInternalPromptsIcons: Boolean = true,
    /** Last 3 (provider, model) pairs the user picked from the Report
     *  section's model pickers, most-recent first. Encoded as
     *  `"providerId|model"` strings for trivial round-trip through
     *  SharedPreferences. Surfaced by the picker as a "Recent" section
     *  above the main list; AppViewModel.recordRecentReportModel
     *  pushes new picks onto the front, deduplicates, and trims to 3. */
    val recentReportModels: List<String> = emptyList(),
    /** Read timeout (seconds) applied to streaming API calls — chat /
     *  report streams where the response trickles in via SSE. The
     *  built-in default (10 min) is generous enough for slow-reasoning
     *  Claude / Gemini sessions; users on flaky networks running fast
     *  models can shrink it. Mirrored to
     *  [com.ai.data.NetworkSettings.streamingReadTimeoutSec] so the
     *  per-call OkHttp interceptor reads the live value. */
    val streamingReadTimeoutSec: Int = com.ai.BuildConfig.NETWORK_READ_TIMEOUT_SEC,
    /** Read timeout (seconds) applied to non-streaming calls — meta /
     *  rerank / translate / model-list fetches / individual analyze
     *  calls that block waiting for the full response body. Much
     *  shorter than the streaming timeout by default so a hung
     *  provider can't gate a whole batch for 10 minutes. */
    val nonStreamingReadTimeoutSec: Int = com.ai.BuildConfig.NETWORK_NONSTREAMING_READ_TIMEOUT_SEC,
    /** Sliding-window rate cap per provider hostname. The OkHttp
     *  interceptor [com.ai.data.ProviderThrottleInterceptor] consults
     *  [com.ai.data.NetworkSettings.maxCallsPerProviderPerMinute] —
     *  this field feeds that singleton on bootstrap and on every
     *  GeneralSettings update. */
    val maxCallsPerProviderPerMinute: Int = 30,
    /** Per-provider concurrency cap. Replaces the prior hardcoded
     *  fan-out semaphore and applies globally across every flow
     *  (report, meta, fan-out, chat, translate, model fetch …) hitting
     *  the same provider host. Mirrored to
     *  [com.ai.data.NetworkSettings.maxConcurrentCallsPerProvider]. */
    val maxConcurrentCallsPerProvider: Int = 3,
    /** Global hard ceiling on in-flight API calls across the whole
     *  app — report-gen + translation + fan-out dispatchers all
     *  withPermit-wrap each per-call coroutine in
     *  [com.ai.data.ApiCallCaps.global] before going through the
     *  per-kind / per-host caps. Surfaced in Settings → Network
     *  settings → Maximal API calls. */
    val maxConcurrentApiCalls: Int = 30,
    /** Cap on concurrent primary report-gen calls (per-agent calls
     *  fired during a new-report run). Replaces the legacy
     *  hardcoded `REPORT_CONCURRENCY_LIMIT = 4`. */
    val maxConcurrentReportCalls: Int = 15,
    /** Cap on concurrent translation calls (each item × language
     *  inside a translation run). With multi-model translation
     *  runs, the cap is on the total across models, not per
     *  model. */
    val maxConcurrentTranslationCalls: Int = 15,
    /** Cap on concurrent fan-out pair calls. Applied per-pair on
     *  top of the per-host throttle, so a fan-out against a
     *  single 3-per-host provider still bottlenecks at 3. */
    val maxConcurrentFanOutCalls: Int = 15,
    /** Cap on concurrent fan-icons batch calls. The fan-icons batch
     *  generates emojis for completed fan-out pair responses; gated
     *  separately so it doesn't compete with the parent fan-out's
     *  budget. Mirrors the per-host cap structure used by fan-out. */
    val maxConcurrentFanIconsCalls: Int = 15,
    /** Cap on concurrent calls in the "Test all models" run
     *  (Housekeeping → Test). Read directly by
     *  [com.ai.viewmodel.ModelTestEngine] to size its in-flight
     *  semaphore — not mirrored into [com.ai.data.NetworkSettings]. */
    val maxTestApiCalls: Int = 8,
    /** Maximum number of in-line retries the OkHttp client performs on
     *  a 429 response from a single provider host. Defaults to 3 —
     *  three retries × the backoff below = ~3 s of in-line waiting.
     *  Mirrored to [com.ai.data.NetworkSettings.maxRetriesOn429] so
     *  the interceptor can read the live value without threading a
     *  Settings reference through its constructor. Set to 0 to
     *  disable in-line retries entirely (the outer withRetry layer
     *  still gets a chance on transient 4xx). */
    val maxRetriesOn429: Int = 3,
    /** Wait between successive 429 retry attempts, in milliseconds.
     *  Defaults to 1000 (1 s). Mirrored to
     *  [com.ai.data.NetworkSettings.retryBackoffMs429]. */
    val retryBackoffMs429: Long = 1_000L,
    /** Maximum number of in-line 529 (server overloaded) retries the
     *  OkHttp client performs per call. 0 disables in-line 529 retries
     *  entirely (the outer withRetry layer still gets one more try).
     *  Mirrored to [com.ai.data.NetworkSettings.maxRetriesOn529]. */
    val maxRetriesOn529: Int = 3,
    /** Wait between successive 529 retry attempts, in milliseconds.
     *  Mirrored to [com.ai.data.NetworkSettings.retryBackoffMs529]. */
    val retryBackoffMs529: Long = 1_000L,
    /** Threshold for the in-app file logger
     *  ([com.ai.data.AppLog]). Calls at this level or higher land in
     *  `<filesDir>/applog/applog_<yyyyMMdd>.log` in addition to
     *  logcat. OFF disables the file appender entirely. Default INFO:
     *  noisy enough to capture every API call + batch start/end without
     *  flooding the device with per-token streaming chatter. */
    val logLevel: com.ai.data.LogLevel = com.ai.data.LogLevel.INFO,
    /** Whether the AI Knowledge card appears on the home Hub. Default
     *  false — Knowledge / RAG is an advanced flow that most users
     *  don't need; hiding it on a fresh install keeps the Hub
     *  approachable. Surfaces a toggle under Settings → "Show AI
     *  Knowledge card on home page" once a user wants it. The
     *  Knowledge subsystem itself stays fully functional whether or
     *  not the card is visible — KBs attached to a chat / report
     *  still work, share-target Knowledge ingest still works. */
    val showKnowledgeCard: Boolean = false
)

// Prompt history entry
data class PromptHistoryEntry(
    val timestamp: Long,
    val title: String,
    val prompt: String
)

/** Captured failure from a Fetch-models call. The trace filename, if any,
 *  points at the request/response pair recorded by [com.ai.data.ApiTracer]
 *  during this attempt — the model picker renders a 🐞 next to the error
 *  text that deep-links into the Trace screen for that exact call. */
data class FetchModelsError(val message: String, val traceFile: String?)

// Main UI state
data class UiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val generalSettings: GeneralSettings = GeneralSettings(),
    val aiSettings: Settings = Settings(),
    val loadingModelsFor: Set<AppService> = emptySet(),
    /** Most recent Fetch-models failure per provider (keyed by service.id).
     *  Cleared when the next Fetch-models call for that provider starts;
     *  populated from the catch arm of [AppViewModel.fetchModels]. The
     *  model-picker UI consults this to render an inline error + 🐞 link
     *  to the captured trace. */
    val fetchModelsErrors: Map<String, FetchModelsError> = emptyMap(),
    // Reports
    val showGenericAgentSelection: Boolean = false,
    val showGenericReportsDialog: Boolean = false,
    val genericPromptTitle: String = "",
    val genericPromptText: String = "",
    // Optional image attachment for the next report — set on NewReportScreen
    // by the 📎 button, consumed once by generateGenericReports, then cleared.
    val reportImageBase64: String? = null,
    val reportImageMime: String? = null,
    // Per-report web-search toggle. ORs with each agent's pinned default; the
    // dispatch layer also auto-falls-back to no-tool on a 400 tool-rejection.
    val reportWebSearchTool: Boolean = false,
    // Per-report reasoning-effort hint (low / medium / high). Set on the
    // New AI Report screen by the 🧠 pulldown, applied on top of any
    // preset reasoningEffort. Non-reasoning models silently ignore the
    // field at dispatch time.
    val reportReasoningEffort: String? = null,
    /** Knowledge bases attached to the next report run. The selection
     *  screen toggles entries here; ReportViewModel.generateGenericReports
     *  copies the snapshot onto the new Report's knowledgeBaseIds.
     *  AnalysisRepository.analyzeWithAgent reads it via the per-call
     *  Report it loads to inject the context block. */
    val attachedKnowledgeBaseIds: List<String> = emptyList(),
    /** Initial user-input text staged by the share-target chooser
     *  so a freshly-opened chat session pre-fills its input box.
     *  ChatSessionScreen consumes this once on first composition
     *  and clears it via clearChatStarterText() so navigating
     *  away and back doesn't re-stuff the box. */
    val chatStarterText: String? = null,
    /** Initial vision attachment staged by the AI Chat hub's
     *  "📸 Start with photo" entry (and any future flow that wants
     *  to drop a chat session in with an image already attached).
     *  Consumed by ChatSessionScreen on first composition the same
     *  way chatStarterText is. */
    val chatStarterImageBase64: String? = null,
    val chatStarterImageMime: String? = null,
    /** SAF Uri strings staged for ingestion by the AI Knowledge
     *  screen. The list screen drops them into the active KB
     *  after the user picks one (or creates a new KB) and clears
     *  the queue. */
    val pendingKnowledgeUris: List<String> = emptyList(),
    /** Non-image SAF Uri strings staged by the share-target
     *  chooser when the user picked "New Report". The New Report
     *  screen surfaces a banner that lets the user auto-create a
     *  one-shot knowledge base from these files and attach it to
     *  the report being composed. Drained on attach / skip. */
    val pendingReportKnowledgeUris: List<String> = emptyList(),
    val genericReportsProgress: Int = 0,
    val genericReportsTotal: Int = 0,
    val genericReportsSelectedAgents: Set<String> = emptySet(),
    val currentReportId: String? = null,
    val reportAdvancedParameters: AgentParameters? = null,
    /** Per-report system prompt override picked on the model-selection
     *  screen. When non-null, replaces the per-agent / per-flock /
     *  external-intent system prompt at dispatch (see
     *  ReportViewModel.buildReportTasks). Null = use the existing
     *  resolution chain. */
    val reportSystemPromptId: String? = null,
    // One-shot signal: when non-empty, the Reports selection screen pre-fills its model
    // list from this and clears it via clearPendingReportModels(). Used for Edit-models
    // and Regenerate flows kicked off from a finished report.
    val pendingReportModels: List<ReportModel> = emptyList(),
    // When non-null, the Reports selection screen is in "edit mode" for that reportId —
    // the bottom button reads "Update model list" and only stages the new model list
    // instead of running. Set by ReportViewModel.prepareEditModels and cleared once
    // the user taps Update or backs out.
    val editModeReportId: String? = null,
    // Model list staged by the Edit-models flow. When non-empty, regenerateReport uses
    // it instead of rebuilding from the on-disk report.agents.
    val stagedReportModels: List<ReportModel> = emptyList(),
    // Set when Edit / Prompt or Edit / Parameters changes the saved title/prompt or
    // reportAdvancedParameters after a report has finished, so the Result screen can
    // surface a "changes pending" banner. Both flags are cleared when regenerateReport
    // kicks off the new run, or when the report is dismissed.
    val hasPendingPromptChange: Boolean = false,
    val hasPendingParametersChange: Boolean = false,
    /** Incremented every time the icon-gen helper writes a new emoji
     *  (or error) onto a Report. Screens that render Report.icon key
     *  their disk-reload effect on this so a mid-flight resolution
     *  recomposes immediately rather than waiting for the next
     *  ON_RESUME refresh. */
    val iconRefreshTick: Int = 0,
    val externalIntent: ExternalIntent = ExternalIntent(),
    // Number of Rerank/Summarize/Compare batches currently running.
    // Each runSecondary() launch increments this on entry and decrements
    // on completion; multiple batches can be in flight at once. The
    // Meta button's hourglass and the Meta screen's poll loop key off
    // this being > 0.
    val activeSecondaryBatches: Int = 0,
    // Chat
    val chatParameters: ChatParameters = ChatParameters(),
    val dualChatConfig: DualChatConfig? = null
) {
    // Flat accessors preserved so call sites don't need updating. Grouping the 13 external
    // fields into a nested ExternalIntent struct makes "is anything external set?" checks
    // and reset operations trivial, and shrinks the top-level UiState surface.
    val externalSystemPrompt: String? get() = externalIntent.systemPrompt
    val externalCloseHtml: String? get() = externalIntent.closeHtml
    val externalReportType: String? get() = externalIntent.reportType
    val externalEmail: String? get() = externalIntent.email
    val externalNextAction: String? get() = externalIntent.nextAction
    val externalReturn: Boolean get() = externalIntent.returnAfterNext
    val externalEdit: Boolean get() = externalIntent.edit
    val externalSelect: Boolean get() = externalIntent.select
    val externalOpenHtml: String? get() = externalIntent.openHtml
    val externalAgentNames: List<String> get() = externalIntent.agentNames
    val externalFlockNames: List<String> get() = externalIntent.flockNames
    val externalSwarmNames: List<String> get() = externalIntent.swarmNames
    val externalModelSpecs: List<String> get() = externalIntent.modelSpecs
}

data class ExternalIntent(
    val systemPrompt: String? = null,
    val closeHtml: String? = null,
    val reportType: String? = null,
    val email: String? = null,
    val nextAction: String? = null,
    val returnAfterNext: Boolean = false,
    val edit: Boolean = false,
    val select: Boolean = false,
    val openHtml: String? = null,
    val agentNames: List<String> = emptyList(),
    val flockNames: List<String> = emptyList(),
    val swarmNames: List<String> = emptyList(),
    val modelSpecs: List<String> = emptyList()
)

// ===== Refresh-all state (lives on AppViewModel so the run survives
// navigation away from the Refresh-all screen and so re-entry sees
// the live progress instead of restarting the chain). =====

sealed class RefreshStepStatus {
    object Pending : RefreshStepStatus()
    data class Running(val detail: String? = null) : RefreshStepStatus()
    data class Done(val detail: String? = null) : RefreshStepStatus()
    data class Failed(val detail: String? = null) : RefreshStepStatus()
    object Skipped : RefreshStepStatus()
}

data class CatalogStep(val id: String, val label: String, val status: RefreshStepStatus = RefreshStepStatus.Pending)

sealed class WorkerStage {
    object Pending : WorkerStage()
    object TestingKey : WorkerStage()
    object FetchingModels : WorkerStage()
    object WritingAgent : WorkerStage()
    object Done : WorkerStage()
    data class Failed(val reason: String) : WorkerStage()
}

data class WorkerRow(val serviceId: String, val stage: WorkerStage = WorkerStage.Pending)

data class RefreshAllState(
    val catalogSteps: List<CatalogStep>,
    val workerRows: List<WorkerRow>,
    /** Screen title for the progress overlay. "Refresh all" for the
     *  full catalog + worker flow; "Providers / models / default
     *  agents" for the worker-only variant launched from the dedicated
     *  Housekeeping card. */
    val title: String = "Refresh all",
    val overallError: String? = null,
    val isFinished: Boolean = false
)

/** One row in the "Alternative icons" screen — the live state of a
 *  single per-(provider, model) icon-prompt call kicked off by
 *  [ReportViewModel.startIconFanOut]. Sealed so the screen can match
 *  on the three states without a "isRunning" flag bag. */
sealed interface IconCandidate {
    val provider: com.ai.data.AppService
    val model: String
    data class Running(override val provider: com.ai.data.AppService, override val model: String) : IconCandidate
    /** Done / Error carry the per-call USD cost (input + output)
     *  computed against the (provider, model) pricing tier at write
     *  time — same value the report's icon-cost field was bumped by
     *  for this call. Surfaces on the Alternative icons row so the
     *  user can see what each candidate cost. */
    data class Done(override val provider: com.ai.data.AppService, override val model: String, val emoji: String, val cost: Double = 0.0) : IconCandidate
    data class Error(override val provider: com.ai.data.AppService, override val model: String, val reason: String, val cost: Double = 0.0) : IconCandidate
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    internal val repository = AnalysisRepository()
    internal val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    internal val settingsPrefs = SettingsPreferences(prefs, application.filesDir)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Hot-mutating fan-out pair set lives outside UiState so per-task
    // start/finish updates (5–15 Hz during a Fan out batch) don't
    // recompose every consumer that reads any other UiState field.
    // Subscribers that actually care (the Fan out L1 / L2 / L3 screens,
    // the Run-button hourglass) collect this flow directly.
    private val _runningFanOutPairs = MutableStateFlow<Set<String>>(emptySet())
    val runningFanOutPairs: StateFlow<Set<String>> = _runningFanOutPairs.asStateFlow()
    internal fun updateRunningFanOutPairs(block: (Set<String>) -> Set<String>) {
        _runningFanOutPairs.update(block)
    }

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

    /** Pair ids currently mid-icon-chain. Parallel to
     *  [runningFanOutPairs] but for the fan-icons batch — the
     *  L1 ICONS-mode stats panel reads from this. */
    private val _runningFanIconsPairs = MutableStateFlow<Set<String>>(emptySet())
    val runningFanIconsPairs: StateFlow<Set<String>> = _runningFanIconsPairs.asStateFlow()
    internal fun updateRunningFanIconsPairs(block: (Set<String>) -> Set<String>) {
        _runningFanIconsPairs.update(block)
    }

    /** Pair ids whose fan-icons attempt is blocked inside
     *  [com.ai.data.ProviderThrottle.acquire]. Same role as
     *  [throttledFanOutPairs] for the icons batch. */
    private val _throttledFanIconsPairs = MutableStateFlow<Set<String>>(emptySet())
    val throttledFanIconsPairs: StateFlow<Set<String>> = _throttledFanIconsPairs.asStateFlow()
    internal fun updateThrottledFanIconsPairs(block: (Set<String>) -> Set<String>) {
        _throttledFanIconsPairs.update(block)
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

        viewModelScope.launch(Dispatchers.IO) {
            val startTag = "App.start"
            val bs = bootstrap(application)

            AppLog.d(startTag, "→ Apply general settings to global singletons")
            ModelType.userDefaults = bs.first.defaultTypePaths
            AppLog.v(startTag, "  ModelType.userDefaults set (${bs.first.defaultTypePaths.size} entries)")
            ApiTracer.isTracingEnabled = bs.first.tracingEnabled
            AppLog.v(startTag, "  ApiTracer.isTracingEnabled=${bs.first.tracingEnabled}")
            NetworkSettings.streamingReadTimeoutSec = bs.first.streamingReadTimeoutSec
            NetworkSettings.nonStreamingReadTimeoutSec = bs.first.nonStreamingReadTimeoutSec
            NetworkSettings.maxCallsPerProviderPerMinute = bs.first.maxCallsPerProviderPerMinute
            NetworkSettings.maxConcurrentCallsPerProvider = bs.first.maxConcurrentCallsPerProvider
            NetworkSettings.maxRetriesOn429 = bs.first.maxRetriesOn429
            NetworkSettings.retryBackoffMs429 = bs.first.retryBackoffMs429
            NetworkSettings.maxRetriesOn529 = bs.first.maxRetriesOn529
            NetworkSettings.retryBackoffMs529 = bs.first.retryBackoffMs529
            ApiCallCaps.resetForNewLimits(
                globalMax = bs.first.maxConcurrentApiCalls,
                reportMax = bs.first.maxConcurrentReportCalls,
                translationMax = bs.first.maxConcurrentTranslationCalls,
                fanOutMax = bs.first.maxConcurrentFanOutCalls,
                fanIconsMax = bs.first.maxConcurrentFanIconsCalls
            )
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
            AppLog.i(
                "App",
                "App started — $appLabel v${com.ai.BuildConfig.VERSION_NAME} " +
                    "(built ${com.ai.BuildConfig.BUILD_TIMESTAMP}) " +
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
        AppLog.v(tag, "  init ChatHistoryManager"); ChatHistoryManager.init(application)
        AppLog.v(tag, "  init ReportStorage"); ReportStorage.init(application)
        AppLog.v(tag, "  init SecondaryResultStorage"); SecondaryResultStorage.init(application)
        AppLog.v(tag, "  init ProviderRegistry"); ProviderRegistry.init(application)
        AppLog.v(tag, "  init ProviderFieldTimestamps"); ProviderFieldTimestamps.init(application)
        AppLog.v(tag, "  init PromptCache"); PromptCache.init(application)
        AppLog.v(tag, "  init InternalPromptIconCache"); InternalPromptIconCache.init(application)
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

        // One-shot migration: nine bundled icon prompts moved from
        // category "internal" to a new "icons" category. Rewrite any
        // persisted row that still carries the old category so the
        // upcoming delta-merge dedupe key (category, name) stays
        // stable — otherwise the bundled ("icons", "report_icon")
        // would be appended next to the legacy ("internal",
        // "report_icon") and the user would see a duplicate.
        // Idempotent: a no-op once every row already reads "icons".
        run {
            val iconNames = setOf(
                "icon", "report_icon", "report_icon_chat", "report_icon_3th",
                "fan_out_icon", "fan_out_icon_chat", "fan_out_icon_3th",
                "prompt_icon", "translation_icon"
            )
            val migrated = ai.internalPrompts.map { p ->
                if (p.category.equals("internal", ignoreCase = true) &&
                    p.name.lowercase() in iconNames) p.copy(category = "icons") else p
            }
            if (migrated != ai.internalPrompts) {
                AppLog.i(tag, "Migrated ${migrated.zip(ai.internalPrompts).count { (a, b) -> a !== b }} icon prompts from category 'internal' → 'icons'")
                ai = ai.copy(internalPrompts = migrated)
                settingsPrefs.saveSettings(ai)
            }
        }

        // One-shot text upgrade: the tier-1 chat-continuation icon
        // prompts ("fan_out_icon_chat", "report_icon_chat") used to say
        // "give your previous response back as an emoji" — models read
        // that literally and echoed the prior content instead of
        // producing a fitting emoji. The delta-merge below only appends
        // missing rows, never overwrites, so rewrite any persisted row
        // that still carries the verbatim old default. A user's manual
        // edit (text != old default) is left untouched. Idempotent.
        run {
            val oldText = "Please give your previous response back as an emoji, just one emoji, nothing more."
            val newText = "For your last response above, reply with a single emoji that best captures it. Output only that one emoji, nothing else."
            val targets = setOf("fan_out_icon_chat", "report_icon_chat")
            val upgraded = ai.internalPrompts.map { p ->
                if (p.name.lowercase() in targets && p.text == oldText) p.copy(text = newText) else p
            }
            if (upgraded != ai.internalPrompts) {
                AppLog.i(tag, "Upgraded ${upgraded.zip(ai.internalPrompts).count { (a, b) -> a !== b }} tier-1 icon prompt(s) to the new wording")
                ai = ai.copy(internalPrompts = upgraded)
                settingsPrefs.saveSettings(ai)
            }
        }

        // Every-start delta-merge of bundled prompts. Appends any
        // (category, name) pair not already present; never overwrites
        // existing rows. New prompts shipped in an APK upgrade get
        // picked up here without the user having to tap 'Read new
        // prompts' in Settings. Already on Dispatchers.IO via the
        // viewModelScope.launch wrapping this bootstrap call.
        AppLog.d(tag, "→ prompts.json delta-merge")
        val tPrompts = System.currentTimeMillis()
        runCatching {
            val bundled = com.ai.data.InternalPromptSeed.loadFromAssets(application)
            AppLog.v(tag, "  bundled prompts.json entries: ${bundled.size}")
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
                AppLog.d(tag, "← prompts.json delta-merge done in ${System.currentTimeMillis() - tPrompts}ms (added=$added)")
            } else {
                AppLog.d(tag, "← prompts.json delta-merge done in ${System.currentTimeMillis() - tPrompts}ms (empty asset)")
            }
        }.onFailure {
            AppLog.w(tag, "← prompts.json delta-merge failed in ${System.currentTimeMillis() - tPrompts}ms", it)
        }

        // Mirror of the prompts.json delta-merge for examples.json:
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

        AppLog.d(tag, "bootstrap total ${System.currentTimeMillis() - bootStart}ms")
        return gs to ai
    }

    /** On-demand merge of the prompts declared in `assets/prompts.json`
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
     *  load of `assets/prompts.json`. Returns the number of rows loaded
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
        AppLog.i("Housekeeping", "← Clear all configuration: localLlms=$llms embedders=$embedders")
        return ConfigWipeResult(llms, embedders)
    }

    /** Factory-style reset that preserves API keys. Runs the cascade
     *  documented in the Reset application dialog: snapshot the keys
     *  to a temp file in cacheDir, wipe usage stats / runtime data /
     *  provider registry, reload providers.json + prompts.json from
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
                // 7. Reload prompts.json from assets
                loadBundledInternalPrompts()
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
        if (settings.maxConcurrentApiCalls != previous.maxConcurrentApiCalls
            || settings.maxConcurrentReportCalls != previous.maxConcurrentReportCalls
            || settings.maxConcurrentTranslationCalls != previous.maxConcurrentTranslationCalls
            || settings.maxConcurrentFanOutCalls != previous.maxConcurrentFanOutCalls
            || settings.maxConcurrentFanIconsCalls != previous.maxConcurrentFanIconsCalls
        ) {
            ApiCallCaps.resetForNewLimits(
                globalMax = settings.maxConcurrentApiCalls,
                reportMax = settings.maxConcurrentReportCalls,
                translationMax = settings.maxConcurrentTranslationCalls,
                fanOutMax = settings.maxConcurrentFanOutCalls,
                fanIconsMax = settings.maxConcurrentFanIconsCalls
            )
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
        viewModelScope.launch(Dispatchers.IO) { settingsPrefs.saveSettings(settings) }
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
                // Match the trace bracketed by withTraceCategory("Retrieve
                // models list") in ApiDispatch.fetchModelsWithKinds. Filtering
                // by category as well as timestamp keeps concurrent fetches
                // from clobbering each other's pointers.
                val traceFile = if (ApiTracer.isTracingEnabled) {
                    // Multiple parallel fetches all share the
                    // "Retrieve models list" category, so the
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
                                it.category == "Retrieve models list" &&
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
                            _uiState.update { state -> state.copy(aiSettings = state.aiSettings.withModels(service, fetched.ids, fetched.types, fetched.visionModels, fetched.capabilities, fetched.rawResponse)) }
                            // Persist with the freshly-merged visionModels (auto + user override), capability map, and raw response snapshot.
                            val cfg = _uiState.value.aiSettings.getProvider(service)
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
    fun setReportSystemPromptId(id: String?) { _uiState.update { it.copy(reportSystemPromptId = id) } }

    // ===== Internal helpers =====

    internal fun updateUiState(block: (UiState) -> UiState) { _uiState.update(block) }

    companion object {
        const val PREFS_NAME = "eval_prefs"
        fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)
        internal const val AI_REPORT_AGENTS_KEY = "ai_report_agents_v2"
        internal const val AI_REPORT_MODELS_KEY = "ai_report_models_v2"
        internal val USER_TAG_REGEX = Regex("""<user>(.*?)</user>""", RegexOption.DOT_MATCHES_ALL)

        // First-run marker. Absent on a fresh install and after a data
        // clear / reinstall, present after the very first successful
        // bootstrap. Survives app updates (SharedPreferences is part of
        // user data, untouched by APK upgrades), so the bundled
        // providers.json + prompts.json import is one-shot per install.
        internal const val KEY_FIRST_RUN_BOOTSTRAPPED = "first_run_bootstrapped"
    }
}
