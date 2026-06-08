# Findings

## Architecture and ownership

### COD-A01 - Split `AppViewModel` into domain delegates

Evidence: `AppViewModel.kt:27`, `AppViewModel.kt:30`,
`AppViewModel.kt:58`, `AppViewModel.kt:100`, `AppViewModel.kt:390`,
`AppViewModel.kt:402`, `AppViewModel.kt:451`, `AppViewModel.kt:545`,
`AppViewModel.kt:549`.

Current shape: `AppViewModel` owns app bootstrap, settings persistence facade
construction, broken-work badge state, pending batch-open requests, batch build
progress, refresh-all state, restart lock, global singleton settings
application, the `SettingsHolder` mirror, and shutdown usage flush.

Recommendation: extract `RuntimeSettingsApplier` first, then split bootstrap,
batch progress, refresh-all, broken-work, and usage flush into focused owners.

### COD-A02 - Convert startup seeding into a declarative pipeline

Evidence: `AppViewModel.bootstrap(...)` remains inside `AppViewModel.kt`.

Current shape: bootstrap is an imperative sequence with repeated load, merge,
save, and logging patterns.

Recommendation: introduce `AssetSeedStep<T>` with load, merge, save, and
logging hooks. Migrate one asset catalog first.

### COD-A03 - Replace static `SettingsHolder` reads with explicit runtime context

Evidence: `AppViewModel.kt:539` to `AppViewModel.kt:545`,
`ApiDispatchBuilders.kt:204` to `ApiDispatchBuilders.kt:223`,
`ReportExport.kt:424`, `ContentDisplay.kt:713`, `ContentDisplay.kt:753`.

Current shape: dispatch and UI helper paths still read global settings state.

Recommendation: introduce `RuntimeModelCapabilities` or `DispatchContext` and
pass it through provider dispatch first. Keep the holder as a fallback while
call sites migrate.

### COD-A04 - Define single-writer ownership for runtime state

Evidence: `AppViewModel.kt:58`, `AppViewModel.kt:87`,
`ReportViewModel.kt:184` to `ReportViewModel.kt:288`,
`RuntimeState.kt:115` to `RuntimeState.kt:152`.

Current shape: broken work, throttled sets, primary jobs, replay jobs, metadata
jobs, secondary runtime, and report/manage derived state have multiple owners or
mirrors.

Recommendation: publish a short ownership map for report, secondary, metadata,
broken-work, and UI-derived state before the next runtime refactor.

### COD-A05 - Split very large files by feature ownership

Evidence: current largest files include `SettingsScreen.kt` at 3,222 LOC,
`IconGenerationManager.kt` at 3,096 LOC, `ReportViewModel.kt` at 3,002 LOC,
`AiDashboardScreen.kt` at 2,976 LOC, and `ReportStorage.kt` at 2,620 LOC.

Current shape: several files mix multiple features and side-effect categories.

Recommendation: split by ownership, not line count. A practical first target is
`IconGenerationManager`: report metadata, language metadata, per-model
metadata, and candidate fan-out owners.

### COD-A06 - Replace high-argument operations with command objects

Evidence: `ReportStorage.createReport(...)`,
`ReportStorage.updateAgentStatus(...)`, and `SettingsScreen(...)`.

Current shape: large optional parameter lists encode patches and screen actions.

Recommendation: introduce request/patch objects such as `CreateReportRequest`,
`AgentStatusPatch`, `UpdateReportMetadataPatch`, and `SettingsScreenActions`.

### COD-A07 - Move final flush work out of the Android view model

Evidence: `AppViewModel.kt:549` to `AppViewModel.kt:557`.

Current shape: usage-stat final flush uses `GlobalScope + NonCancellable`
directly inside the view model.

Recommendation: move the behavior into `DurableFlushQueue` or
`UsageStatsRecorder.flushOnShutdown()`.

### COD-A08 - Remove stale migration-phase comments from source

Evidence: `ReportViewModel.kt:290`, `FanOutEngine.kt:82`,
`FanOutEngine.kt:1998`, `Secondary.kt:70`, `Secondary.kt:720`.

Current shape: source comments still reference Phase C/E/F and legacy fan-out
paths, which makes the current runtime shape harder to read.

Recommendation: remove stale phase comments, or rename the path as an explicit
compatibility fallback with a current removal condition.

### COD-A09 - Add architecture/source-boundary tests

Evidence: no current source-boundary test file is present.

Current shape: package ownership is convention-based.

Recommendation: add JVM source-scan tests for boundaries such as "data does not
import Compose", "UI does not define durable stores", and "dispatch does not
import UI".

## Report and batch runtime

### COD-R01 - Route primary report calls and report replay through shared batch throttling

Evidence: `ReportViewModel.kt:1779` to `ReportViewModel.kt:1833`, plus manual
`acquireThrottledPermits` calls around `ReportViewModel.kt:1118`,
`ReportViewModel.kt:1322`, `ReportViewModel.kt:1506`,
`ReportViewModel.kt:1705`, `ReportViewModel.kt:2300`,
`ReportViewModel.kt:2424`.

Current shape: primary report generation and report-answer replay still
hand-roll permit acquisition and job shape.

Recommendation: adapt `runReportPrimaryCalls` to `runThrottledBatch` behind the
existing method signature, then migrate replay loops if the shape fits.

### COD-R02 - Finish the reusable replay runner

Evidence: `ReplayTrack.kt` exists and `MetaEditManager.kt:50` to
`MetaEditManager.kt:55` uses it; `ReportViewModel.kt:192` to
`ReportViewModel.kt:203` and `FanOutEngine` still keep their own replay maps.

Current shape: shared state-flow and job-map plumbing exists, but not a full
replay call runner across report and fan-out replay.

Recommendation: migrate `ReportViewModel` replay maps to `ReplayTrack`, then
extract common replay dispatch and apply logic.

### COD-R03 - Make primary report generation a formal run state

Evidence: `ReportViewModel.kt:184` to `ReportViewModel.kt:225`.

Current shape: primary generation is represented by one job, active report id,
regenerate jobs, and helper predicates.

Recommendation: publish `StateFlow<Map<String, ReportRunState>>` alongside the
existing fields, then migrate broken-work and progress readers.

### COD-R04 - Treat report metadata generation as typed jobs

Evidence: `IconGenerationManager.kt` remains 3,096 LOC and `ReportStorage` has
separate update methods for icon, title, language, and per-model metadata.

Current shape: report title, icon, language, per-model title/icon, and
alternative candidates use separate runtime conventions.

Recommendation: define `ReportMetadataJob` as an adapter over existing fields
before changing persistence.

### COD-R05 - Move report/manage derived state out of Compose

Evidence: `RuntimeState.kt:101` to `RuntimeState.kt:165`.

Current shape: `rememberReportRuntimeState` owns secondary counts, secondary
rows, translation summaries, fan-out summaries, totals, icon/title/language
fields, and loaded report fields in local Compose state.

Recommendation: introduce `ReportManageStateStore` exposing
`StateFlow<ReportManageSnapshot>`.

### COD-R06 - Make broken-work recovery a domain repository

Evidence: `AppViewModel.kt:58`, `AppViewModel.kt:71`,
`ReportViewModel.kt:216`, and broken-work scan paths in `SecondaryRunManager`.

Current shape: broken-work detection and continue behavior cross app state,
report runtime predicates, secondary scans, and UI one-shot requests.

Recommendation: create `BrokenWorkRepository` with scanner, commands, and
live-runtime interfaces.

### COD-R07 - Wrap batch build progress in a scoped helper

Evidence: `AppViewModel.kt:100` to `AppViewModel.kt:112`.

Current shape: engines call `beginBuild`, `updateBuild`, `finishBuild`, and
`clearBuild` manually.

Recommendation: add `BatchBuildScope.run(key, total, label) { step() }`.

### COD-R08 - Normalize secondary lineage

Evidence: `SecondaryResult` carries per-kind fields such as `fanInOf`,
`fanOutSourceAgentId`, `metaPromptId`, `targetLanguage`, roles, and
`tournamentJudgeRunId`.

Current shape: hydration and display group rows by conventions spread across
secondary kinds.

Recommendation: populate an optional `SecondaryLineage` object for new rows
while keeping existing fields.

### COD-R09 - Split provider call execution from result persistence

Evidence: report and secondary runtimes still combine dispatch, trace, cost,
and storage writes in the same methods.

Current shape: provider-call behavior and persisted-result application are
hard to test independently.

Recommendation: introduce `SecondaryCallRunner` and `SecondaryResultWriter`
around one secondary kind first.

## Data, persistence, and settings

### COD-D01 - Finish splitting `SettingsPreferences`

Evidence: `SettingsPreferences.kt:32`, `SettingsPreferences.kt:65`,
`SettingsPreferences.kt:470`, `SettingsPreferences.kt:567`,
`SettingsPreferences.kt:1030`.

Current shape: the class is in `com.ai.data.preferences` and delegates saved
prompt lists, but general settings, AI settings, usage stats, and cost-ledger
hooks still live inside the facade.

Recommendation: extract `GeneralSettingsStore`, then `UsageStatsStore`.

### COD-D02 - Create a reusable guarded JSON store abstraction

Evidence: `ReportStorage`, `SecondaryResultStorage`, chat storage, knowledge,
prompt/cache, model-list, and usage stores each own parts of locking, safe IDs,
atomic writes, caches, and version bumps.

Current shape: there is no shared `JsonFileStore`.

Recommendation: implement the abstraction behind one low-risk store before
touching reports or secondaries.

### COD-D03 - Replace broad global version ticks with scoped invalidation

Evidence: `ReportDataVersion` remains global in `ReportStorage.kt`, while
report UI still uses refresh ticks and derived local state.

Current shape: updates are not scoped by report id or data kind.

Recommendation: add `ReportVersion(reportId)` and
`SecondaryVersion(reportId, kind)` flows while preserving the global version.

### COD-D04 - Turn report mutations into patch commands

Evidence: `ReportStorage` has many specialized mutation methods with repeated
load, mutate, save, cost, audit, and timestamp patterns.

Current shape: no `CreateReportRequest`, `AgentStatusPatch`, or
`UpdateReportMetadataPatch` exists.

Recommendation: introduce patch objects for agent status and metadata writes
first.

### COD-D05 - Extract report cost-ledger reconciliation

Evidence: `ReportStorage.reconcileApiCallCostLedger(...)` and
`SettingsPreferences.reconcileReportCostLedgers(...)` remain coupled.

Current shape: ledger rebuild, usage-stat adjustment, and storage are
interleaved across report storage and settings persistence.

Recommendation: create `ReportCostLedgerService` with fixture tests.

### COD-D06 - Store normalized run/API-call records

Evidence: provider calls are represented through report-agent fields,
secondary fields, icon calls, traces, and usage rows rather than a single
durable `ApiCallRecord`.

Current shape: there is no normalized `RunRecord` or `ApiCallRecord`.

Recommendation: write new records in parallel with existing fields for one call
family.

### COD-D07 - Separate current settings from settings edit sessions

Evidence: `SettingsScreen.kt:144` to `SettingsScreen.kt:211`.

Current shape: settings edit targets and route-specific state live as
screen-level `rememberSaveable` values.

Recommendation: add edit-session models for provider, model, worker, and prompt
edit screens.

### COD-D08 - Centralize safe identifier policy

Evidence: stores use separate safe-id helpers or filename sanitizers.

Current shape: there are no typed `ReportId`, `SecondaryResultId`, or
`TraceFileName` wrappers at storage boundaries.

Recommendation: start with `SecondaryResultId` and `ReportId`.

### COD-D09 - Add schema tests for persisted top-level concepts

Evidence: storage tests exist, but there are not golden round-trip/backfill
fixtures for every persisted concept.

Current shape: reports, secondary rows, settings, usage, knowledge, chat, and
provider schema compatibility are not all covered by golden fixtures.

Recommendation: add golden fixtures for reports and secondary rows first.

### COD-D10 - Move usage-stat recording/flushing out of settings persistence

Evidence: `SettingsPreferences.kt:470` to `SettingsPreferences.kt:620`,
`SettingsPreferences.kt:1030` to `SettingsPreferences.kt:1042`,
`AppViewModel.kt:465`, `AppViewModel.kt:549`.

Current shape: usage stats are static caches under `SettingsPreferences`, and
final flush is driven from `AppViewModel`.

Recommendation: extract `UsageStatsRecorder` with the same locking and flush
policy.

### COD-D11 - Represent store migrations explicitly

Evidence: Gson null backfills and compatibility handling live inside load
paths.

Current shape: there is no migration registry for durable file stores.

Recommendation: only pursue if future schema changes need durable
multi-version support.

## API, provider, and runtime

### COD-P01 - Add a provider call descriptor

Evidence: dispatch still passes service, model, base URL, kind, and streaming
context through separate parameters.

Current shape: there is no `ProviderCallDescriptor`.

Recommendation: wrap tracing, audit URL, timeout, and host-gate metadata in one
descriptor.

### COD-P02 - Centralize model capability resolution

Evidence: `SettingsHolder` fallback exists in `ApiDispatchBuilders.kt:204` to
`ApiDispatchBuilders.kt:223`, and capability checks are spread across UI,
dispatch, and replay flows.

Current shape: UI and dispatch can still use different paths to answer similar
capability questions.

Recommendation: introduce `ModelCapabilityResolver` returning both value and
source/reason.

### COD-P03 - Expose provider diagnostics as structured state

Evidence: throttle/cap diagnostics are primarily strings/logs and dashboard
reads.

Current shape: provider wait/cooldown/failure state is not exposed as one
structured flow.

Recommendation: create `ProviderRuntimeDiagnostics` from caps, throttle,
cooldown, and recent failure state.

### COD-P04 - Isolate blocking backoff yield behind an interface

Evidence: `PermitHold.yieldFor` performs `Thread.sleep` and blocking
`tryAcquire` loops directly (`ThrottledBatch.kt:232` to
`ThrottledBatch.kt:281`).

Current shape: behavior is tested but not isolated behind a small interface.

Recommendation: keep low priority unless retry handling changes again; if it
does, add `BackoffPermitYielder`.

### COD-P05 - Treat local/cloud models behind one backend model

Evidence: local runtime has separate direct UI/runtime paths, such as dashboard
reads in `AiDashboardScreen.kt:2637` to `AiDashboardScreen.kt:2639`.

Current shape: cloud provider, local LLM, and local embedder do not share one
backend abstraction.

Recommendation: define `ModelBackend` for cloud provider, local LLM, and local
embedder.

### COD-P06 - Add provider metadata provenance

Evidence: pricing/capability/source information exists, but there is no general
`ResolvedValue<T>` provenance model.

Current shape: model availability, capability, pricing, and endpoint decisions
do not share one provenance structure.

Recommendation: return provenance from pricing and capability resolvers before
changing UI.

### COD-P07 - Normalize provider errors into typed failures

Evidence: many dispatch paths still return `AnalysisResponse(error = "...")`;
only some model-list failures are typed.

Current shape: retry, benching, UI, and audit logic still depend heavily on
strings for generation failures.

Recommendation: add `ProviderFailure` next to `AnalysisResponse` and fill it
opportunistically while preserving user-facing messages.

## UI, navigation, and functional product

### COD-U01 - Replace manual settings routing with a route registry

Evidence: `SettingsSubScreen` is a large enum at `SettingsScreen.kt:32`;
manual back behavior starts at `SettingsScreen.kt:222`.

Current shape: settings routes, parent relationships, help topics, titles, and
rendering are not represented as route metadata.

Recommendation: add `SettingsRouteSpec` with parent route, help topic, title,
icon, and renderer.

### COD-U02 - Introduce a report workflow state model

Evidence: report workflow spans navigation routes, view-model flags, Compose
overlay state, and storage-derived state.

Current shape: local workflow rules are implicit in many call sites.

Recommendation: define `ReportWorkflowMode` for local workflow rules while
keeping Jetpack Navigation.

### COD-U03 - Move direct storage reads out of composables

Evidence: `SelectionPhase.kt:202`, `AiDashboardScreen.kt:2637` to
`AiDashboardScreen.kt:2639`, and `RuntimeState.kt:115`.

Current shape: composables still trigger knowledge-list reads, local runtime
inventory scans, and report/manage reload policy.

Recommendation: expose knowledge list, local runtime inventory, and report
manage snapshots as flows.

### COD-U04 - Add a screen spec registry

Evidence: route grouping and top-icon logic live in `AppNavHost.kt:320` to
`AppNavHost.kt:405`.

Current shape: section ownership and top-icon behavior are encoded as route
sets and `when` branches.

Recommendation: create `ScreenSpec` for section, help topic, title, and top
icon policy.

### COD-U05 - Build a unified job/activity center

Evidence: active report, secondary, translation, refresh, model-test, and
metadata jobs are surfaced through separate screens/state owners.

Current shape: there is no single runtime job surface.

Recommendation: define a minimal `RuntimeJob` interface before building UI.

### COD-U06 - Productize provider/model health

Evidence: cooldown, test-excluded, inaccessible, usage, trace, and failure data
exist in separate systems.

Current shape: model health is not presented as a unified product surface.

Recommendation: aggregate recent success, failure, cooldown, latency, and cost
per provider/model.

### COD-U07 - Add report templates/presets

Current shape: saved prompts and saved settings exist, but no durable workflow
preset combines prompt, model selection, parameters, system prompt, default
secondaries, and export behavior.

Recommendation: create report workflow presets with validation for missing
referenced IDs.

### COD-U08 - Add secondary lineage visualization

Current shape: there is no normalized lineage model or graph UI for report,
primary answer, secondary, fan-out, fan-in, translation, tournament, compare,
and export relationships.

Recommendation: build the visualization after `SecondaryLineage` exists.

### COD-U09 - Add reproducible run bundles

Current shape: exports exist, but not a bundle that captures prompt, models,
settings, capability/catalog snapshot, traces, costs, and lineage.

Recommendation: create a reproducibility export mode backed by normalized
lineage and API-call records.

### COD-U10 - Consolidate alternative candidate pickers

Current shape: alternative title, icon, language, per-model, and fan-out
response flows have separate UI/runtime conventions.

Recommendation: introduce a generic `CandidatePicker` model and UI.

### COD-U11 - Improve icon-heavy UI accessibility semantics

Current shape: the app uses many compact icon actions, but there is no central
wrapper enforcing content descriptions and tooltips.

Recommendation: add `IconActionButton`, `StatusIcon`, and Compose tests for
report/settings action semantics.

### COD-U12 - Convert dashboard polling into scoped observable state

Evidence: `AiDashboardScreen.kt:772`, `AiDashboardScreen.kt:2063`,
`AiDashboardScreen.kt:2610`.

Current shape: dashboard sections use periodic ticks for several data refresh
paths.

Recommendation: keep visual elapsed-time ticks, but move log, disk, trace, and
report-stat refresh to repository flows.

### COD-U13 - Add "explain this state" affordances

Current shape: disabled, dimmed, waiting, throttled, and model-state rows do not
share one structured explanation source.

Recommendation: surface structured wait/disabled reasons from capability,
throttle, cooldown, key, and secondary precondition resolvers.

### COD-U14 - Simplify external/share entry routing with command objects

Evidence: external report parsing is inline in `AppNavHost.kt:109` to
`AppNavHost.kt:202`.

Current shape: external command parsing and routing are coupled to Compose
navigation.

Recommendation: extract `ExternalAppCommandParser`, `ExternalReportCommand`,
`ShareTargetCommand`, and unit tests.

## Tests and quality gates

### COD-T01 - Add secondary lineage and export tests

Current shape: no lineage model exists, and export tests do not cover lineage
across every secondary kind.

Recommendation: add lineage/export tests when normalized lineage is introduced.

### COD-T02 - Add external/share command parser tests

Current shape: external/share parsing lives in UI/navigation code rather than
testable command objects.

Recommendation: extract parser/command objects and test bare prompts,
instruction-bearing prompts, tag parsing, mixed URL plus URI shares, and route
selection.

### COD-T03 - Add high-value Compose navigation tests

Current shape: the test suite has many Compose tests, but not dedicated
contracts for settings route parentage, settings deep-link/back behavior,
report secondary drill-in return paths, and top/bottom bar section policies.

Recommendation: add route-focused Compose tests after the route/spec registry
exists.

### COD-T04 - Add source-boundary tests

Current shape: package boundaries are not guarded.

Recommendation: add source-scan tests for UI/data/dispatch boundaries.

### COD-T05 - Add performance guard tests for large report scenarios

Current shape: no tests assert hydrate/list/scanning behavior for many reports,
many secondaries, large trace directories, large knowledge lists, or many local
model files.

Recommendation: add threshold-style tests around the largest report and storage
read paths.

### COD-T06 - Add broader cost-accounting mutation tests

Current shape: storage tests cover important cases, but cost-accounting
mutation coverage can still expand.

Recommendation: add cases for duplicate traces, retry then success,
metadata-only cost updates, alternative candidate cost, deleted secondary cost
retention, and usage-stat rebuild after ledger reconciliation.
