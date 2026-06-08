# Source Structure

This file describes how the repository is organized and what each major
area owns.

## Observation 1 - Kotlin Source Is Compactly Rooted

Structure: all main Kotlin source lives under
`ai/src/main/java/com/ai`, with one root entry file and four main
packages: `data`, `model`, `viewmodel`, and `ui`.

Counts verified in this worktree:

| Area | Files |
|---|---:|
| `MainActivity.kt` | 1 |
| `data/` | 88 |
| `model/` | 2 |
| `viewmodel/` | 24 |
| `ui/` | 273 |
| **Total** | **388** |

Functional implication: most feature work will cross at least two
packages: UI plus either viewmodel/data or settings/model definitions.

## Observation 2 - `data/` Owns Core Services and Domain Persistence

Structure: `data/` includes API dispatch, Retrofit models, streaming,
tracing, interceptors, provider registry, pricing, model list cache,
report storage, secondary storage, chat storage, RAG, backup/restore,
prompt seeds, app logs, and domain models for judged batches.

Functional implication: this package is the closest thing to the app's
backend. It owns both external integrations and durable local state.

## Observation 3 - `data/local/` Is the On-Device Runtime Boundary

Structure: `data/local/` holds `LocalLlm`, `LocalEmbedder`,
`LlmRuntime`, and `LocalRuntime`.

Functional implication: the synthetic `Local` provider is intentionally
isolated from cloud dispatch. Local LLM and local embedding features can
appear in provider/model pickers while routing through different runtime
code.

## Observation 4 - `model/` Is Narrow and Settings-Oriented

Structure: `model/` contains `SettingsModels.kt` and
`SettingsHolder.kt`. Settings data classes include providers, agents,
flocks, swarms, parameters, system prompts, model states, endpoints,
and related config types.

Functional implication: most persisted user configuration has one of
these data classes as its shape, even when the storage code lives in
`ui/settings/SettingsPreferences.kt`.

## Observation 5 - `viewmodel/` Is Engine-Oriented

Structure: `viewmodel/` includes the one true `AppViewModel`, wrapper
classes for report/chat, report helper types, batch infrastructure,
and extracted engines/managers.

Functional implication: this folder is where long-running behavior and
coordination live. It is the main bridge between Compose screens and
data-layer services.

## Observation 6 - `ui/` Is Domain-Oriented

Structure: `ui/` has no direct files at the root. It is grouped by
domain:

| Folder | Files |
|---|---:|
| `report` | 98 |
| `cruds` | 48 |
| `admin` | 35 |
| `settings` | 22 |
| `shared` | 17 |
| `helpers` | 16 |
| `navigation` | 7 |
| `other` | 6 |
| `chat` | 5 |
| `hub` | 5 |
| `search` | 4 |
| `history` | 3 |
| `models` | 3 |
| `share` | 2 |
| `knowledge` | 1 |
| `theme` | 1 |

Functional implication: UI ownership follows product areas more than
technical component type. Shared widgets are concentrated under
`ui/shared` and export helpers under `ui/helpers`.

## Observation 7 - `ui/report/` Is Fully Nested

Structure: report UI is split into `manage`, `view`, `start`, `other`,
and `info`:

| Folder | Files |
|---|---:|
| `report/manage` | 66 |
| `report/view` | 22 |
| `report/start` | 7 |
| `report/other` | 2 |
| `report/info` | 1 |

Functional implication: "report" changes should first be classified by
mode. A Manage action, View tile, Start selection option, and Info page
usually have different owners.

## Observation 8 - Navigation Is Isolated to Seven Files

Structure: `ui/navigation` contains `AppNavHost`, `NavRoutes`, and five
route group files for reports, settings/admin, knowledge/search,
developer, and chat.

Functional implication: route additions have a predictable home. This
keeps the large navigation graph searchable despite the app's screen
count.

## Observation 9 - Assets Carry Product Catalog Data

Structure: `ai/src/main/assets` contains provider definitions, bundled
example report zips, model-state seed lists, metadata repository seed
files, and app metadata. Current provider assets include 42 provider
JSON files under `assets/providers/`.

Functional implication: assets are not passive media. They define
startup catalogs, examples, initial model exclusions, and external
metadata snapshots.

## Observation 10 - Documentation Is a Maintainer Index

Structure: `doc/` has subsystem docs for manual usage, architecture,
development, data structures, API formats, secondaries, parameters,
system prompts, workers, knowledge, local runtime, model states,
regenerate, icons, costs, throttling, translation, share target,
backup/restore, persistence, providers, repositories, help, app logs,
value view, rank translators, and more.

Functional implication: the docs are broad enough to onboard a feature
change before opening source files. They also make stale statements
noticeable when source behavior changes.

## Observation 11 - Tests Cover Data, UI Helpers, Reports, and Compose

Structure: there are 86 test files across `ai/src/test` and
`ai/src/androidTest`. The JVM tests cover API helpers, pricing,
storage helpers, export helpers, report markdown/html naming, report
manage helpers, settings graphs, and viewmodel policies. Android tests
cover Compose screens, storage managers, tracer, pricing, provider
registry, report export, and report manage/view screens.

Functional implication: the suite is strongest around pure helpers,
storage, export generation, and selected Compose surfaces. Large
end-to-end report workflows still depend heavily on static reasoning
and focused tests.

## Observation 12 - Audit History Is a Parallel Knowledge Base

Structure: `audit/` holds date-stamped snapshots and now this
functional audit. The older snapshots are bug inventories with
summary-plus-domain files; this pack mirrors the multi-file format but
changes the taxonomy to observations.

Functional implication: audits function as handoff artifacts. They
should be readable without replaying the whole codebase investigation.

