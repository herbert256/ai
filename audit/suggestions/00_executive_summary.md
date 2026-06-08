# Executive summary

## Main finding

The source already contains a mature, feature-rich app, but the central
coordination layer has grown faster than the supporting abstractions. The code
has moved in the right direction with dedicated batch engines, shared throttle
helpers, typed models, and storage guards. The next improvement cycle should
finish those extractions so feature work can happen in smaller, safer modules.

The most important technical issue is ownership concentration:

- `AppViewModel` owns app bootstrap, settings application, global singleton
  synchronization, batch progress, broken-work state, refresh-all state, and
  many report/secondary runtime mirrors.
- `ReportViewModel` owns primary report generation, regeneration, multiple
  replay flows, many job maps, engine construction, and secondary orchestration.
- Several UI files still directly scan stores or compute runtime state instead
  of subscribing to domain-level state.
- Persistence logic is split across large singleton stores and a settings class
  placed in the UI package.

This does not mean the app needs a rewrite. It needs incremental ownership
extraction around the behavior that already exists.

## Top recommendations

| ID | Priority | Recommendation | Why it matters |
|---|---:|---|---|
| A01 | P0 | Extract `AppViewModel` domain delegates | Reduces risk in startup, settings, global state, and batch progress changes. |
| R01 | P0 | Expand `BatchEngine` into a real lifecycle runtime base | Removes repeated job maps, hydration, resume guards, and item transitions. |
| R02 | P0 | Route more report/batch network loops through `runThrottledBatch` | Prevents throttle-order drift and makes concurrency testable. |
| D01 | P0 | Move `SettingsPreferences` out of `ui/settings` and split it | Fixes architecture direction and unlocks focused settings tests. |
| D02 | P0 | Create a reusable guarded JSON store abstraction | Consolidates locking, cache, path-safety, version bump, and atomic writes. |
| P01 | P1 | Split `ApiDispatch.kt` by provider format and call kind | Makes provider-specific behavior easier to test and change. |
| T01 | P1 | Add settings load/save parity tests | Prevents silent preference loss when `GeneralSettings` changes. |
| T02 | P1 | Add batch-engine lifecycle tests | Covers hydrate/start/delete/resume/retry behavior without full instrumented runs. |
| U01 | P1 | Introduce a typed settings route registry | Replaces large enum/manual back handling with metadata-driven navigation. |
| U02 | P1 | Create a report workflow state model | Makes overlays, selection, generation, and detail modes explicit. |
| U05 | P1 | Add an execution plan preview before expensive runs | Improves user control over cost, provider caps, and call volume. |
| T04 | P1 | Add provider dispatch golden tests | Protects OpenAI-compatible, Anthropic, Google, streaming, vision, and usage parsing. |

## Strengths found in source

- The code is defensively engineered around known runtime hazards. Examples:
  `AppNavHost` confirms external report intents with instructions before side
  effects (`AppNavHost.kt:109`), `withApiCallTimeout` guards provider calls
  (`ApiDispatch.kt:63`), `runThrottledBatch` documents canonical permit order
  (`ThrottledBatch.kt:14`), and `ReportStorage` uses guarded report IDs and
  append-only cost-ledger reconciliation paths (`ReportStorage.kt:1464`).
- The report domain has explicit persisted concepts for primary answers,
  metadata, secondaries, costs, traces, translations, fan-out, fan-in, and
  comparison style workflows. That is a good base for functional expansion.
- Existing comments capture important invariants. The next step is to move
  those invariants into smaller APIs and tests so developers do not have to
  re-read 2,000-3,000 line files to make safe changes.

## Core risks

1. Ownership concentration makes local changes non-local. A settings change can
   touch `SettingsPreferences`, `GeneralSettings`, `AppViewModel`, `AppNavHost`,
   settings UI, global singleton mirrors, and usage statistics.
2. Several runtime patterns are duplicated with small variations: job maps,
   replay state maps, placeholder row creation, hydration from disk, resume
   caps, and refresh ticks.
3. Some cross-layer dependencies point upward. The clearest example is
   `SettingsPreferences` living in `ui/settings` while importing data-layer
   storage and being constructed by `AppViewModel` (`SettingsPreferences.kt:1`,
   `AppViewModel.kt:30`).
4. UI screens still do direct storage work in composition or local state. The
   most obvious examples are knowledge-base listing in `SelectionPhase.kt:202`
   and local runtime model scans in `AiDashboardScreen.kt:2635`.
5. Tests are numerous but skewed toward data helpers and UI snapshots. The
   riskiest orchestration logic has less direct unit coverage.

## Recommended north star

Move toward this shape without disrupting current behavior:

```text
UI screens
  -> route/screen state controllers
  -> view models / domain coordinators
  -> runtime engines and repositories
  -> guarded JSON stores + provider dispatchers
```

The existing code is already close to this in places. The issue is consistency:
some report paths use engines, some paths still live in `ReportViewModel`; some
batch loops use `runThrottledBatch`, some manually acquire permits; some UI
screens observe flows, some poll or scan files directly.

## Suggested first three implementation slices

1. Settings persistence slice:
   move `SettingsPreferences` to a data/preferences package, split usage stats
   into its own store, add round-trip parity tests for `GeneralSettings`.

2. Batch lifecycle slice:
   extend `BatchEngine` to own item job registration, run job registration,
   lifecycle status, resume dedupe, and common cleanup; migrate one engine first.

3. Report UI runtime slice:
   extract a report-screen state holder for overlays and current mode, then move
   direct file scans in report start/manage UI into repository/state-flow APIs.

These slices are small enough to review, but each removes a recurring source of
future defects.
