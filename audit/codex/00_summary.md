# Summary

## Executive view

The current source is a mature report-centered AI workbench with broad provider
support, durable report storage, secondary-analysis engines, local runtime
support, traces, logs, usage accounting, and rich Compose UI surfaces.

The main technical pressure is not missing functionality. It is ownership
concentration. Several central files still coordinate many domains at once:

- `AppViewModel` owns bootstrap, settings application, global runtime mirrors,
  batch progress, broken-work state, refresh-all state, and final usage flush.
- `ReportViewModel` owns primary generation, regeneration, replay flows,
  multiple job maps, engine construction, and secondary orchestration.
- `SettingsPreferences` sits in the data package, but still owns general
  settings, AI settings, usage-stat caches, cost-ledger reconciliation hooks,
  and many preference keys.
- Some UI screens still perform storage scans or own reload policy inside
  composition.

## Highest-value findings

1. Split `AppViewModel` domain ownership.
   Current source still has startup, settings application, global singleton
   sync, refresh-all, broken-work state, batch-progress state, and usage flush
   in the Android view model.

2. Finish settings persistence extraction.
   `SettingsPreferences` is in the data package now, but it still owns general
   settings, AI settings, usage-stat caches, report-cost reconciliation hooks,
   and key constants.

3. Finish replay/runtime extractions.
   `ReplayTrack` exists, but report-answer replay and fan-out replay still keep
   separate job maps and state-flow plumbing.

4. Move report/manage derived state out of Compose.
   `rememberReportRuntimeState` still holds many local mutable values and owns
   reload policy inside composition.

5. Normalize secondary lineage and run metadata.
   The flat `SecondaryResult` row is powerful but dense; source grouping still
   relies on per-kind conventions such as prompt IDs, run IDs, roles, and
   language fields.

6. Add source-boundary and high-value UI/navigation tests.
   Current tests cover many lower-layer contracts, but source-boundary,
   settings-route, external/share parser, secondary lineage/export, and
   performance guard tests are not present.

## Finding counts

| Area | Findings |
|---|---:|
| Architecture and ownership | 9 |
| Report and batch runtime | 9 |
| Data, persistence, and settings | 11 |
| API, provider, and runtime | 7 |
| UI, navigation, and functional product | 13 |
| Tests and quality gates | 6 |
| **Total findings** | **55** |

## Recommended first pass

1. Extract `GeneralSettingsStore` and `UsageStatsStore` from
   `SettingsPreferences`.
2. Migrate `ReportViewModel` and `FanOutEngine` replay state onto
   `ReplayTrack`.
3. Extract `ReportManageStateStore` for report/manage derived state.
4. Add source-boundary tests for package ownership.
5. Convert settings routing to a `SettingsRouteSpec` registry.

## Current strengths

- Provider integration is format-oriented instead of provider-by-provider.
- The flat `SecondaryResult` model lets new analysis tools share storage,
  export, cost, trace, and recovery paths.
- Batch engines have a shared lifecycle base for the judged-cell engines.
- Mock provider and golden-dispatch tests cover important API request/response
  contracts.
- The app exposes operational state to users through traces, logs, usage,
  pricing, cooldowns, diagnostics, and cost views.
