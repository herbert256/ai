# Architecture and ownership suggestions

## A01 - Split `AppViewModel` into domain delegates

Priority: P0

Evidence:

- `AppViewModel` constructs `SettingsPreferences` directly at
  `AppViewModel.kt:30`.
- It seeds UI state at `AppViewModel.kt:38`.
- It owns broken-work state, pending batch opens, throttled fan-out pairs, and
  build progress near `AppViewModel.kt:41`, `AppViewModel.kt:71`,
  `AppViewModel.kt:87`, and `AppViewModel.kt:100`.
- It starts cache prewarming and throttle stall monitoring in `init` at
  `AppViewModel.kt:402`.
- It applies settings to global singletons at `AppViewModel.kt:451`.
- It mirrors `aiSettings` into a static holder at `AppViewModel.kt:544`.
- It flushes usage stats with `GlobalScope` in `onCleared` at
  `AppViewModel.kt:549`.

Suggestion:

Keep `AppViewModel` as the Android lifecycle entry point, but split its current
responsibilities into constructor-owned delegates:

- `AppBootstrapCoordinator`: cache prewarm, asset seeding, registry init, model
  list refresh.
- `RuntimeSettingsApplier`: applies `GeneralSettings` to `NetworkSettings`,
  tracing, audit, model cooldowns, and cap stores.
- `BatchProgressStore`: `BuildProgress`, pending batch open, broken-work badge
  state, throttled sets.
- `RefreshAllCoordinator`: refresh-all state machine and model-list refresh
  progress.
- `AppUsageStatsFlusher`: owns usage-stat flush scheduling and shutdown flush.

Expected benefit:

`AppViewModel` becomes a thin composition of domain owners. Startup changes,
settings changes, and report runtime changes stop colliding in one file.

First slice:

Extract `RuntimeSettingsApplier.apply(general, ai)` with no behavior change.
Unit-test it with fake holders where possible, or at least test the pure mapping
from `GeneralSettings` to expected runtime values.

## A02 - Convert startup asset seeding into a declarative pipeline

Priority: P1

Evidence:

The bootstrap block starting at `AppViewModel.kt:564` performs many similar
load/merge/save operations for persisted configuration and bundled assets.
The shape is repeated: load current state, load asset defaults or deltas, merge,
persist, log.

Suggestion:

Introduce a small `AssetSeedStep<T>` abstraction:

```kotlin
data class AssetSeedStep<T>(
    val name: String,
    val loadPersisted: suspend () -> T,
    val loadBundled: suspend () -> T,
    val merge: (persisted: T, bundled: T) -> T,
    val save: suspend (T) -> Unit
)
```

Then run a list of steps in `AppBootstrapCoordinator`. Each step can expose
whether it changed anything and how many rows were added/updated.

Expected benefit:

Adding one new settings-backed catalog no longer requires copying another long
try/log/merge block. It also makes startup timing and failures easier to report.

## A03 - Define package boundaries around UI, domain, and storage

Priority: P0

Evidence:

`SettingsPreferences` is under `com.ai.ui.settings` (`SettingsPreferences.kt:1`)
but it imports data-layer storage and is instantiated from `AppViewModel`
(`AppViewModel.kt:30`). It also owns usage-stat caches and writes report cost
reconciliation through `ReportStorage` (`SettingsPreferences.kt:597`).

Suggestion:

Move persistence classes below UI:

- `com.ai.data.preferences.GeneralSettingsStore`
- `com.ai.data.preferences.AiSettingsStore`
- `com.ai.data.usage.UsageStatsStore`
- `com.ai.data.prompts.PromptHistoryStore`

The UI package should render and call save callbacks. It should not own the
persistent schema.

Expected benefit:

This turns settings into a data contract instead of a UI implementation detail.
It also allows JVM tests to cover settings without importing UI package classes.

## A04 - Replace static settings mirrors with explicit runtime context

Priority: P1

Evidence:

`AppViewModel` writes `SettingsHolder.current` on every `uiState` emission at
`AppViewModel.kt:539`. The comment explains that dispatcher helpers cannot
easily thread settings through their call stack.

Suggestion:

Introduce a narrow `RuntimeModelCapabilities` or `DispatchContext` interface
that provider dispatch and model selection code can receive explicitly. Keep
the static holder temporarily as a compatibility shim, but move new call sites
to the interface.

Expected benefit:

Provider behavior becomes deterministic in tests. It also prevents subtle bugs
where a background job reads a later settings value than the one active when the
job was launched.

## A05 - Make runtime state ownership explicit with "single writer" rules

Priority: P1

Evidence:

Multiple runtime owners publish overlapping state:

- `AppViewModel` publishes batch progress and throttled sets.
- `ReportViewModel` owns primary jobs, regenerate jobs, replay jobs, engine
  instances, and agent results (`ReportViewModel.kt:184` to
  `ReportViewModel.kt:321`).
- `FanOutEngine` owns its own state plus replay state maps
  (`FanOutEngine.kt:108`).
- UI runtime state recomputes many report-level values locally in
  `rememberReportRuntimeState` (`RuntimeState.kt:115`).

Suggestion:

For each runtime concept, document and enforce one writer:

- Primary report generation: `ReportRunCoordinator`.
- Secondary batch runtime: the relevant engine.
- Report persisted state: storage/repository only.
- UI-derived display summary: screen state adapter only.

Add package-level KDoc or simple `README.md` files inside source packages if the
rules are not obvious from class names.

Expected benefit:

When a row is wrong in the UI, there is one state source to inspect, not a mix
of disk, local remember state, engine flow, and view model sets.

## A06 - Turn large files into feature modules, not just smaller files

Priority: P1

Evidence:

Several files exceed 2,000 LOC and mix model state, side effects, rendering,
and helpers. Examples from the source snapshot include `SettingsScreen.kt`,
`IconGenerationManager.kt`, `ReportViewModel.kt`, `AiDashboardScreen.kt`,
`SharedComponents.kt`, and `ReportStorage.kt`.

Suggestion:

When splitting files, split by responsibility rather than by arbitrary line
count. For example:

- `SettingsScreen.kt` -> route shell, top page, route registry, edit-session
  state, sub-screen renderers.
- `IconGenerationManager.kt` -> report title/icon, language metadata, per-model
  metadata, alternative candidate fan-outs.
- `ReportStorage.kt` -> report CRUD, report mutation commands, cost ledger,
  import/export helpers, migrations.

Expected benefit:

Reviewers can reason about one domain slice at a time, and tests can target
smaller public APIs.

## A07 - Introduce typed command objects for high-argument operations

Priority: P1

Evidence:

`ReportStorage.createReport` has many parameters (`ReportStorage.kt:81`).
`ReportStorage.updateAgentStatus` also has a large optional parameter list
(`ReportStorage.kt:161`). `SettingsScreen` has a very large composable
signature (`SettingsScreen.kt:78`).

Suggestion:

Replace high-argument functions with typed request objects:

- `CreateReportRequest`
- `AgentStatusPatch`
- `SettingsScreenActions`
- `SettingsInitialRoute`

Expected benefit:

Call sites become self-documenting and safer to extend. Optional fields become
grouped by meaning instead of appended to long parameter lists.

## A08 - Standardize coroutine ownership for non-UI shutdown work

Priority: P2

Evidence:

`AppViewModel.onCleared` uses `GlobalScope + NonCancellable` to flush usage
stats (`AppViewModel.kt:549`). The comment explains why it was chosen.

Suggestion:

Move this into an application-level `AppShutdownScope` or `DurableFlushQueue`
that is created once by the app. The view model can enqueue a flush without
owning the global coroutine decision.

Expected benefit:

The behavior remains durable, but the exceptional coroutine scope is isolated
and testable.

## A09 - Reduce source comments that encode migration history

Priority: P2

Evidence:

Some source comments still describe phases or legacy migration windows:

- `ReportViewModel.kt:290` describes phase-based fan-out migration.
- `FanOutEngine.kt:82` describes Phase C/D/E/F.
- `Secondary.kt:66` and `Secondary.kt:718` describe a legacy fan-out path.

Suggestion:

Keep comments that state current invariants. Move history-like comments to
issue links, commit messages, or a short in-code "compatibility path" note with
an owner and removal condition.

Expected benefit:

Fresh readers can tell what the code does now without parsing past migration
plans.

## A10 - Add a small architecture test layer

Priority: P2

Suggestion:

Add tests that enforce simple source-level boundaries:

- UI packages should not define persistent storage classes.
- Data packages should not import Compose.
- Provider dispatch packages should not import UI classes.
- View model packages should not import concrete composables.

These can be simple JVM tests that scan Kotlin source imports.

Expected benefit:

Boundary drift is caught cheaply before it becomes another large refactor.
