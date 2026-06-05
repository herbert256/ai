package com.ai.viewmodel

// Top-level data/enum/sealed types used by AppViewModel + the rest of
// the app, extracted from AppViewModel.kt to shrink that file.
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

// General app settings
val DEFAULT_UI_CARD_BACKGROUND_ARGB: Int = 0xFF2A3A4A.toInt()
val DEFAULT_UI_BUTTON_BACKGROUND_ARGB: Int = 0xFF27594E.toInt()

/** The batch families surfaced on the Broken-work screen — one card per
 *  ([BrokenBatch.kind], [BrokenBatch.key]) group the background scan finds. */
enum class BatchFamilyKind(val label: String) {
    FAN_OUT("Fan Out"),
    FAN_META("Fan Meta"),
    TOURNAMENT("Tournament"),
    JUDGES("Judges"),
    COMPARE("Compare"),
    TRANSLATION("Translation"),
    REGENERATE("Regenerate"),
    /** Single Meta / Rerank / Moderation secondary calls (not a fan-out batch). */
    OTHER("Meta / Rerank / Moderation"),
}

/** One batch (run) with work needing attention, produced by the read-only
 *  background scan and rendered one card per entry on the Broken-work
 *  screen. A batch can carry [unfinishedCount] stranded placeholders
 *  (blank, no error, not in flight) and/or [errorCount] errored items;
 *  the card shows a line for each that is > 0.
 *
 *  [key] is the actionable scope used to recover the batch: a fan-out
 *  runKey (`"reportId|metaPromptId"`) for FAN_OUT / FAN_META, a
 *  translation runId for TRANSLATION, or the reportId for the
 *  one-run-per-report kinds (TOURNAMENT / JUDGES / COMPARE / OTHER /
 *  REGENERATE). */
data class BrokenBatch(
    val reportId: String,
    val reportTitle: String,
    val kind: BatchFamilyKind,
    val key: String,
    val batchName: String,
    val unfinishedCount: Int,
    val errorCount: Int,
    val timestamp: Long,
)

/** How combined provider+model labels render across UI rows.
 *  MODEL_ONLY shows just the model id (the dense default); PROVIDER_AND_MODEL
 *  shows both, joined by " · ", for users who run the same model on
 *  multiple providers and want to disambiguate at a glance. */
enum class ModelNameLayout { MODEL_ONLY, PROVIDER_AND_MODEL }

/** Which of the two editable colour sets (Day / Night) the app paints.
 *  AUTO follows the Android system day/night setting; DAY / NIGHT pin it.
 *  Defaults to NIGHT — the app's long-standing dark palette. */
enum class UiColorMode { DAY, NIGHT, AUTO }

/** How a new report's title is set. MANUAL keeps the original
 *  behaviour — the user types a title in the New AI Report screen.
 *  AI hides the input field and a background LLM call (the bundled
 *  `internal/report_title` prompt) fills it from the prompt body,
 *  with the resolved title rendered on the new `title` row of the
 *  main "Manage report" screen. */
enum class ReportTitleMode { Manual, AI }

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
    /** Master gate for the whole Log/trace/audit/statistics page.
     *  Default OFF — a fresh install records nothing. While false the
     *  four diagnostic settings below ([tracingEnabled],
     *  [auditLogEnabled], [usageStatsEnabled], [logLevel]) are forced
     *  off at runtime regardless of their stored values — see the
     *  `effective*` helpers, which every mirror in AppViewModel reads —
     *  and the Settings UI hides them. Flip it on to reveal + apply each
     *  one's individual value. The per-item values are preserved while
     *  the master is off so turning it back on restores prior choices. */
    val loggingMasterEnabled: Boolean = false,
    /** Master switch for API tracing. When false, no new traces are
     *  written, the Hub "AI API Traces" card is hidden, and every 🐞
     *  ladybug icon disappears from the per-result screens. Mirrored
     *  to [com.ai.data.ApiTracer.isTracingEnabled] so non-UI call
     *  sites consult a single global. Gated by [loggingMasterEnabled] —
     *  consume via [effectiveTracingEnabled]. */
    val tracingEnabled: Boolean = true,
    /** When true (default) the 🐞 trace hot-links show throughout the app
     *  while tracing is on. Turn off to hide every 🐞 link and view traces
     *  only from the API Traces screen — tracing itself stays on. Mirrors to
     *  [com.ai.data.ApiTracer.showLadybugIcons]. */
    val showLadybugIcons: Boolean = true,
    /** When true (default) the per-report audit log records every mutating
     *  action, batch start/end and API call. Turn off to stop all audit
     *  writes. Mirrors to [com.ai.data.AuditLog.enabled]. */
    val auditLogEnabled: Boolean = true,
    /** When true (default) cumulative usage statistics — per-provider /
     *  per-model token counts and costs surfaced under AI Usage / Statistics
     *  and the Live Dashboard — are accumulated on every API call. Turn off
     *  to stop all usage-stat recording. Mirrors to
     *  [com.ai.ui.settings.SettingsPreferences.usageStatsEnabled]. */
    val usageStatsEnabled: Boolean = true,
    /** When true hides the Android status bar — clock, signal,
     *  battery — so the app gets the full screen height. Reads via
     *  WindowInsetsControllerCompat in MainActivity on every settings
     *  flush. Defaults to false so first-launch users see the system
     *  bar and can opt into hiding it under Settings → UI tweaks. */
    val fullScreen: Boolean = false,
    /** Controls whether combined provider+model labels (Fan out drill-in
     *  rows, secondary picker buttons, agent rows on Report Result,
     *  chat headers, …) show only the model or both. Provided to the
     *  composition tree via LocalModelNameLayout in the AppNavHost. */
    val modelNameLayout: ModelNameLayout = ModelNameLayout.MODEL_ONLY,
    /** User-selected UI colours, edited under Settings → UI Colors.
     *  Stored as Android ARGB ints so they can round-trip through prefs
     *  without Compose/UI types in the view-model layer. */
    val uiCardBackgroundArgb: Int = DEFAULT_UI_CARD_BACKGROUND_ARGB,
    val uiButtonBackgroundArgb: Int = DEFAULT_UI_BUTTON_BACKGROUND_ARGB,
    /** The Night colour set's per-key overrides (the app's original
     *  palette). Empty keys fall back to the dark factory defaults. */
    val uiColorOverrides: Map<String, Int> = emptyMap(),
    /** The Day colour set's per-key overrides. Empty keys fall back to
     *  the light factory defaults ([AppColors.DefaultUiColorArgbDay]). */
    val uiColorOverridesDay: Map<String, Int> = emptyMap(),
    /** Which colour set is painted — Night (default), Day, or Auto
     *  (follow the system day/night setting). */
    val uiColorMode: com.ai.viewmodel.UiColorMode = com.ai.viewmodel.UiColorMode.NIGHT,
    /** Grand master switch for every optional metadata item — report
     *  icon, report language, AI title, per-model icons / titles, fan
     *  icons / titles, and internal-prompt (meta / rerank / moderate /
     *  translate) icons. When true (default) each individual sub-toggle
     *  below governs its own item as before. When false, ALL of them are
     *  off regardless of the sub-toggles: no generation calls fire, the
     *  sub-toggles are hidden in Settings, the Fan Out Icons / Titles
     *  buttons and the Manage `info` row disappear, and a new report
     *  must be given a manual title. View screens ignore this flag
     *  entirely — they render whatever a report already holds, falling
     *  back to [com.ai.data.MetadataDefaults]. The [reportIconOn] /
     *  [reportLanguageOn] / … helpers fold this master AND each
     *  sub-flag so call sites ask one question. */
    val metadataEnabled: Boolean = true,
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
    /** Master switch for report language detection (+ its flag emoji),
     *  split out of [iconGenEnabled] so the report icon and the language
     *  row can be toggled independently. When true (default) every new
     *  report fires the bundled `info/language` two-step call; when false
     *  the call is skipped and the language row drops from the info
     *  screen. Gated behind [metadataEnabled] via [reportLanguageOn]. */
    val reportLanguageGenEnabled: Boolean = true,
    /** How the title of a new report is decided. `Manual` keeps the
     *  Title input field on the New AI Report screen; `AI` (default)
     *  hides it and fires a background LLM call after report start
     *  via [com.ai.viewmodel.ReportViewModel.kickOffReportTitleGeneration],
     *  which uses the bundled `internal/report_title` prompt + its
     *  pinned agent. The resolved title surfaces on the main
     *  Manage-report screen's new `title` row (sibling of `icon` /
     *  `language`) and replaces the placeholder "AI Report" once the
     *  call returns. */
    val reportTitleMode: ReportTitleMode = ReportTitleMode.AI,
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
    /** When true, after each model response a short Anthropic call
     *  (internal/model_title) titles that response; the title then
     *  replaces the model name on the Manage-report 'report' row and
     *  its cost folds into that row + a "Model titles" Costs category.
     *  Default on. */
    val perModelTitleGenEnabled: Boolean = true,
    /** Master switch for the per-internal-prompt icon cache. When true
     *  (default), every secondary-result row on the report result page
     *  whose `metaPromptId` resolves to a known InternalPrompt gets a
     *  leading emoji generated once via the bundled
     *  `icons/meta` prompt and persisted in
     *  [com.ai.data.InternalPromptIconCache]. The cache is keyed on
     *  `(InternalPrompt.name, InternalPrompt.title)` so editing
     *  either field re-fires generation; cache hits cost nothing.
     *  When false, no new icons are generated and rows fall back to
     *  the plain text type label; already-cached entries stay on disk
     *  for re-enable. */
    val useInternalPromptsIcons: Boolean = true,
    /** Master gate for every autostart item. When false (default) the app
     *  never autostarts anything when a report finishes — auto Rerank /
     *  Moderation and the Default meta items are all skipped — and the
     *  per-item autostart settings are hidden. Flip on to reveal and
     *  enable them. */
    val autostartItemsEnabled: Boolean = false,
    /** When true (default), finishing a Fan Out run with no errored pairs
     *  automatically kicks off that run's Fan Meta batch (one call per
     *  pair produces both title and icon) — no need to tap the Fan Meta
     *  button by hand. A run with any error pair is left alone. */
    val autostartFanMeta: Boolean = true,
    /** When true (default), finishing a report's agent run automatically
     *  creates one Rerank and one Moderation secondary result, each using
     *  the first rerank- / moderation-capable model found across active
     *  providers. Independent of [metadataEnabled] (these are secondary
     *  results, not metadata icons). Skips a kind when no capable model
     *  exists or one is already present; manual creation (with its model
     *  picker) is unaffected. */
    val autoCreateRerankAndModeration: Boolean = true,
    /** User-editable fallback emoji shown on view screens when a report /
     *  secondary result has no generated icon of its own. Defaults to the
     *  [com.ai.data.MetadataDefaults] factory values; edited on Settings →
     *  Default icons. Persisted as one JSON blob. */
    val metadataIcons: com.ai.data.MetadataIcons = com.ai.data.MetadataIcons(),
    /** App-wide default system prompt / parameters — the universal lowest
     *  fallback for every model, used only when nothing more specific
     *  (pre-gen / agent / flock / swarm / provider / report-model) is set.
     *  Edited on AI Setup → App settings. */
    val appWideSystemPromptId: String? = null,
    val appWideParametersIds: List<String> = emptyList(),
    /** Report-model default system prompt / parameters — fallback for
     *  bare/direct models only (not agent/flock/swarm-sourced), and NOT
     *  applied when a pre-generation system prompt / parameters was given
     *  on the New AI Report screen. */
    val reportModelSystemPromptId: String? = null,
    val reportModelParametersIds: List<String> = emptyList(),
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
    val maxCallsPerProviderPerMinute: Int = 60,
    /** Per-provider concurrency cap. Replaces the prior hardcoded
     *  fan-out semaphore and applies globally across every flow
     *  (report, meta, fan-out, chat, translate, model fetch …) hitting
     *  the same provider host. Mirrored to
     *  [com.ai.data.NetworkSettings.maxConcurrentCallsPerProvider]. */
    val maxConcurrentCallsPerProvider: Int = 5,
    /** Global hard ceiling on in-flight API calls across the whole
     *  app — report-gen + translation + fan-out dispatchers all
     *  withPermit-wrap each per-call coroutine in
     *  [com.ai.data.ApiCallCaps.global] before going through the
     *  per-kind / per-host caps. Surfaced in Settings → Network
     *  settings → Maximal API calls. */
    val maxConcurrentApiCalls: Int = 100,
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
    /** Type-A (fixed-model) batch bench-and-requeue: on a 429/529 the
     *  answerer/judge model is parked and its waiting same-model items move
     *  to Bench, then back to Queue when the bench lifts — instead of each
     *  item erroring or retrying in line. Applies to Fan Out + Judge the
     *  judges. Mirrored to [com.ai.data.ModelCooldownStore.typeABenchEnabled]. */
    val typeABenchEnabled: Boolean = true,
    /** Bench duration in SECONDS when a 429/529 carries no Retry-After hint
     *  (the server's hint is used when present). Mirrored to
     *  [com.ai.data.ModelCooldownStore.typeABenchBaseMs] (× 1000). */
    val typeABenchSeconds: Int = 10,
    /** Consecutive benches one item gets before the batch gives up and
     *  leaves it errored. Mirrored to
     *  [com.ai.data.ModelCooldownStore.typeABenchMaxAttempts]. */
    val typeABenchMaxAttempts: Int = 5,
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
     *  still work, share-target Knowledge ingest still works.
     *  Only editable when [experimentalFeaturesEnabled] is true. */
    val showKnowledgeCard: Boolean = false,
    /** Master gate for experimental / advanced surfaces. When false,
     *  hides every UI surface related to on-device models (Local
     *  LLMs, LiteRT embedders, the synthetic AppService.LOCAL
     *  provider), AI Knowledge / RAG (Hub card, attach buttons in
     *  chat + report, share-target "Add to Knowledge" entry,
     *  Knowledge screens), and Local Semantic Search. Installed
     *  model files on disk stay put; flipping this back on reveals
     *  everything intact. KBs already attached to existing chats /
     *  reports keep sending context at API time even while the
     *  attach UI is hidden. */
    val experimentalFeaturesEnabled: Boolean = false,
    /** Live Dashboard layout, persisted (and so backed up via eval_prefs):
     *  the card ids the user pinned (shown on the dashboard, open) and their
     *  custom card order. Defaults to the three at-a-glance cards pinned;
     *  empty order = default order. Edited behind the ✏️ on the dashboard. */
    val pinnedDashboardCards: Set<String> = setOf("live", "spend", "http"),
    val dashboardCardOrder: List<String> = emptyList()
) {
    /** Effective gates — the grand-master [metadataEnabled] ANDed with
     *  each per-item sub-flag. Every generation call site and every
     *  control-surface (Manage info row, Fan Out Icons / Titles buttons,
     *  New report title requirement) asks one of these instead of
     *  re-deriving the AND. View screens must NOT call these — they read
     *  report data directly with [com.ai.data.MetadataDefaults]. */
    fun reportIconOn() = metadataEnabled && iconGenEnabled
    fun reportLanguageOn() = metadataEnabled && reportLanguageGenEnabled
    fun reportTitleAiOn() = metadataEnabled && reportTitleMode == ReportTitleMode.AI
    fun perModelIconOn() = metadataEnabled && perModelIconGenEnabled
    fun perModelTitleOn() = metadataEnabled && perModelTitleGenEnabled
    fun metaIconsOn() = metadataEnabled && useInternalPromptsIcons
    fun fanMetaOn() = metadataEnabled

    /** Runtime gates for the Log/trace/audit/statistics page — each
     *  per-item flag ANDed with the [loggingMasterEnabled] master switch.
     *  AppViewModel mirrors these (not the raw fields) into ApiTracer /
     *  AuditLog / SettingsPreferences / AppLog, so a master that's off
     *  forces every diagnostic off at runtime. [effectiveLogLevel] falls
     *  back to OFF (the file appender disabled) when the master is off. */
    fun effectiveTracingEnabled() = loggingMasterEnabled && tracingEnabled
    fun effectiveAuditLogEnabled() = loggingMasterEnabled && auditLogEnabled
    fun effectiveUsageStatsEnabled() = loggingMasterEnabled && usageStatsEnabled
    fun effectiveLogLevel(): com.ai.data.LogLevel =
        if (loggingMasterEnabled) logLevel else com.ai.data.LogLevel.OFF
}

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
    /** Display-only long title for the Manage report orange line; mirrors
     *  [genericPromptTitle] but holds the AI-generated long title (≤50).
     *  Blank for manual / new-report titles → orange line falls back to
     *  [genericPromptTitle]. */
    val genericPromptTitleLong: String = "",
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
    /** Report-level Parameters PRESET ids picked via the New-Report 🌡️
     *  "Configure API parameters" screen. Kept only so that picker can
     *  show its current selection; the chosen presets are resolved into
     *  [reportAdvancedParameters] (the pre-gen override) by
     *  setReportParametersIds, so generation reads them through the
     *  existing path. */
    val reportParametersIds: List<String> = emptyList(),
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

/** Live candidate for the "Find alternative titles" fan-out — parallel to
 *  [IconCandidate] but carries a title string instead of an emoji. */
sealed interface TitleCandidate {
    val provider: com.ai.data.AppService
    val model: String
    data class Running(override val provider: com.ai.data.AppService, override val model: String) : TitleCandidate
    data class Done(override val provider: com.ai.data.AppService, override val model: String, val title: String, val cost: Double = 0.0) : TitleCandidate
    data class Error(override val provider: com.ai.data.AppService, override val model: String, val reason: String, val cost: Double = 0.0) : TitleCandidate
}

/** Live candidate for the "Find alternative translation" fan-out — parallel to
 *  [TitleCandidate] but carries the translated body text. [Done] keeps the
 *  call's [com.ai.data.TokenUsage] so the apply step can write an accurate
 *  input/output cost split onto the persisted TRANSLATE row. */
sealed interface TranslationCandidate {
    val provider: com.ai.data.AppService
    val model: String
    data class Running(override val provider: com.ai.data.AppService, override val model: String) : TranslationCandidate
    data class Done(
        override val provider: com.ai.data.AppService,
        override val model: String,
        val text: String,
        val cost: Double = 0.0,
        val tokenUsage: com.ai.data.TokenUsage? = null,
        /** Trace filename + wall-clock of THIS candidate's call, so a
         *  picked alternative overwrites the persisted row's stale
         *  trace / duration instead of keeping the previous
         *  translation's (see applyAltTranslation). */
        val traceFile: String? = null,
        val durationMs: Long? = null
    ) : TranslationCandidate
    data class Error(override val provider: com.ai.data.AppService, override val model: String, val reason: String, val cost: Double = 0.0) : TranslationCandidate
}
