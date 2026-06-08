# Data, persistence, and settings suggestions

## D01 - Split settings persistence by domain

Priority: P0

Evidence:

`SettingsPreferences` is a single 1,158 LOC class under the UI package
(`SettingsPreferences.kt:1`, `SettingsPreferences.kt:32`). It owns general
settings load/save (`SettingsPreferences.kt:61`, `SettingsPreferences.kt:174`),
AI settings, prompt history, usage stats (`SettingsPreferences.kt:500`), report
cost reconciliation (`SettingsPreferences.kt:597`), static caches, and key
constants (`SettingsPreferences.kt:1059`).

Suggestion:

Split into:

- `GeneralSettingsStore`
- `AiSettingsStore`
- `PromptHistoryStore`
- `UsageStatsStore`
- `SettingsMigrationStore`

Keep a facade named `SettingsPreferences` temporarily if that lowers migration
risk, but have it delegate to the new stores.

Expected benefit:

Each settings domain gets focused tests and ownership. Adding a new preference
stops touching one central mega-class.

## D02 - Add load/save parity tests for `GeneralSettings`

Priority: P0

Evidence:

`loadGeneralSettings` and `saveGeneralSettings` manually map many fields
(`SettingsPreferences.kt:91` to `SettingsPreferences.kt:163`,
`SettingsPreferences.kt:178` onward). The current source does include fields
such as `showLadybugIcons` and `rankingWeights`, which is good, but the mapping
is still manual and easy to drift.

Suggestion:

Create a test fixture that:

1. Builds a non-default `GeneralSettings` with every field set to a distinctive
   value.
2. Saves it.
3. Loads it.
4. Asserts equality for every field that should persist.

Also test absence/default behavior for fields that intentionally seed defaults
only when keys are absent.

Expected benefit:

Future settings additions fail tests when not persisted.

## D03 - Create a guarded JSON file store abstraction

Priority: P0

Evidence:

The source has several custom file-backed stores with overlapping needs:

- `ReportStorage` stores reports under `/files/reports` with a lock
  (`ReportStorage.kt:38`).
- `SecondaryResultStorage` has similar responsibilities for secondary rows.
- `SettingsPreferences` writes file-based usage stats and prompt history.
- Other source areas use JSON files, caches, path guards, and atomic writes.

Suggestion:

Introduce a generic store:

```kotlin
class JsonFileStore<Id, T>(
    val dirName: String,
    val idCodec: IdCodec<Id>,
    val serializer: JsonSerializer<T>,
    val versionBus: VersionBus? = null
)
```

It should provide:

- safe flat-id validation
- atomic writes
- lock discipline
- list/get/create/update/delete
- optional per-file cache
- version bumping
- load failure reporting

Expected benefit:

Storage hardening improvements happen once. Report, secondary, knowledge, chat,
trace metadata, and usage stores can share behavior without losing their domain
APIs.

## D04 - Replace broad global version ticks with scoped invalidation

Priority: P1

Evidence:

`ReportDataVersion` exposes one global incrementing state flow
(`ReportStorage.kt:18`). UI code also uses manual refresh ticks and local
mutable states in report runtime screens.

Suggestion:

Move toward scoped invalidation:

- `ReportVersion(reportId)`
- `SecondaryVersion(reportId, kind?)`
- `TraceVersion(reportId?)`
- `UsageVersion`

Expose flows from repositories rather than making UI remember which global tick
to observe.

Expected benefit:

Updating one report does not force unrelated screens to recompute broad state.
It also makes refresh behavior more deterministic.

## D05 - Turn report mutations into command methods with patch objects

Priority: P1

Evidence:

`ReportStorage` has many specialized update methods, each loading, mutating,
recomputing cost or timestamp, saving, and sometimes appending audit lines.
Examples include `updateAgentStatus` (`ReportStorage.kt:161`), title updates
(`ReportStorage.kt:691`), icon/title updates (`ReportStorage.kt:790`,
`ReportStorage.kt:844`), and ledger updates (`ReportStorage.kt:1464`).

Suggestion:

Introduce typed patch commands:

- `UpdateAgentStatusPatch`
- `UpdateReportMetadataPatch`
- `AppendApiCostPatch`
- `SetReportFlagPatch`

Implement common mutation plumbing:

```kotlin
mutateReport(context, reportId, audit = ...) { report ->
    patch.apply(report)
}
```

Expected benefit:

Cost recalculation, timestamp bumping, version bumping, and audit logging can be
centralized while preserving explicit domain methods.

## D06 - Make cost ledger reconciliation a standalone service

Priority: P1

Evidence:

Report cost ledger reconciliation lives in `ReportStorage`
(`ReportStorage.kt:1502`) and usage-stat reconciliation reaches into it from
`SettingsPreferences` (`SettingsPreferences.kt:597`).

Suggestion:

Extract:

- `ReportCostLedgerService`
- `UsageStatsReconciler`

The storage layer should load and save. The reconciliation service should know
how to rebuild rows from structured fields and traces.

Expected benefit:

Cost-accounting behavior becomes testable with pure fixtures and less coupled
to settings persistence.

## D07 - Store run metadata as first-class records

Priority: P2

Evidence:

Reports and secondaries carry many run-related fields: run IDs, trace files,
prompt IDs, generated metadata costs, duration, model, provider, and source
links. Some of that is duplicated across report agents, icon calls, secondary
rows, and audit traces.

Suggestion:

Add a `RunRecord` or `ApiCallRecord` store that can be referenced by report
agents, secondaries, metadata jobs, and usage stats. Existing fields can remain
for compatibility, but new writes can create normalized records.

Expected benefit:

Export, cost views, trace views, and future reproducibility features become
easier because all API calls have the same durable shape.

## D08 - Separate "current settings" from "settings edit session"

Priority: P1

Evidence:

Settings UI manages many edit targets and selected IDs inside
`SettingsScreen` with `rememberSaveable` state (`SettingsScreen.kt:144` to
`SettingsScreen.kt:211`). The persistence store handles saved state, but there
is not a clear edit-session model between UI and save.

Suggestion:

Introduce edit-session models for complex settings screens:

- provider edit session
- model edit session
- agent/flock/swarm edit session
- prompt edit session
- network settings edit session

Each session can validate, expose dirty state, and produce a save command.

Expected benefit:

Back behavior, discard confirmation, validation, and deep-link editing become
more consistent.

## D09 - Centralize safe identifier policy

Priority: P2

Evidence:

Report and secondary stores use safe ID checks in multiple places, such as
`ReportStorage.appendApiCallCost` guarding `reportId` (`ReportStorage.kt:1468`
to `ReportStorage.kt:1471`).

Suggestion:

Create one `SafeId` utility with typed wrappers:

- `ReportId`
- `SecondaryResultId`
- `TraceFileName`
- `ProviderId`
- `PromptId`

Use wrappers at storage boundaries first, not everywhere at once.

Expected benefit:

Path-safety assumptions become visible in signatures.

## D10 - Add import/export schema tests for every persisted top-level concept

Priority: P1

Suggestion:

For each durable object, keep a golden JSON fixture and assert:

- current parser accepts it
- serializer writes expected fields
- missing older fields are backfilled
- unknown newer fields do not break current loads when intentionally allowed

The highest-value fixtures are reports, secondary results, settings, usage
stats, knowledge bases, chat sessions, and provider definitions.

Expected benefit:

Schema evolution becomes routine rather than risky.

## D11 - Move usage-stat flush policy out of settings storage

Priority: P2

Evidence:

`SettingsPreferences` owns usage-stat caches, flush interval, and global
enabled flag (`SettingsPreferences.kt:1059` to `SettingsPreferences.kt:1074`).
`AppViewModel` mirrors settings into `SettingsPreferences.usageStatsEnabled`
(`AppViewModel.kt:465`) and flushes on clear (`AppViewModel.kt:549`).

Suggestion:

Create `UsageStatsRecorder`:

- enabled flag
- in-memory counters
- flush interval
- durable store
- final flush

Expected benefit:

The statistics subsystem becomes independent from settings persistence.

## D12 - Represent persisted migrations explicitly

Priority: P2

Evidence:

Several source comments describe legacy data shape handling, Gson null
backfills, and ledger rebuilds. These are important, but the migration state is
spread through load paths.

Suggestion:

Add a small migration registry per store:

```kotlin
interface StoreMigration<T> {
    val fromVersion: Int
    val toVersion: Int
    fun apply(value: JsonObject): JsonObject
}
```

Do not overbuild it; start with report and secondary result stores.

Expected benefit:

Load paths become shorter, and old-data handling is easier to test.
