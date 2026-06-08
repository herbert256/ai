# Functional Audit Summary

Date: 2026-06-08

Worktree audited: `/Users/herbert/ai-codex`

Branch observed: `codex`

Status: static functional audit complete, 74 observations across seven
files. No build, deploy, unit tests, or instrumented tests were run.

## Executive View

1. The app is best understood as a report-first AI workbench, not as a
   chat app with extra screens. Reports are the durable document root:
   primary answers, secondaries, translations, exports, cost views,
   traces, and value analysis all hang from a report id.
2. The main product domains are Reports, Chat, Knowledge, Monitor, Setup,
   Housekeeping, Settings, Help/About, and Share ingest. Reports dominate
   both source size and functional complexity.
3. The runtime architecture is single-Activity Compose with one real
   Android `ViewModel`: `AppViewModel`. `ReportViewModel` and
   `ChatViewModel` are plain wrapper classes around shared state and
   shared long-running scopes.
4. Provider integration is format-first. Forty-two cloud providers are
   loaded from asset definitions and collapse into three API families:
   OpenAI-compatible, Anthropic, and Google. The synthetic `Local`
   provider bypasses Retrofit and routes to on-device runtime code.
5. Secondary results use one flat storage row type across eight kinds:
   `RERANK`, `META`, `MODERATION`, `TRANSLATE`, `TOURNAMENT`, `JUDGES`,
   `COMPARE`, and `TRANSRANK`. That unification is the core reason the
   report section can keep adding analysis tools without creating a new
   persistence model for each one.
6. The UI uses two navigation models: Jetpack Navigation for top-level
   routes and enum-driven in-screen navigation for Settings. Many report
   subflows use full-screen overlays with early `return`, preserving the
   parent screen's local Compose state.
7. Persistence is intentionally plain: SharedPreferences for settings
   and small maps, JSON/text files under `filesDir` for durable content,
   and cache files for large metadata. There is no DataStore runtime
   pattern despite the dependency being present.
8. Observability is a product surface, not just developer tooling. API
   traces, application logs, audit logs, usage statistics, crash reports,
   live throttle state, and cost screens are all reachable from the UI.
9. Source structure is domain-oriented in UI and service-oriented in data:
   388 Kotlin files total, with `ui/report` alone holding 98 files and
   `data` holding 88 files.
10. The documentation set is now an operational map of the app. It covers
    product behavior, architecture, persistent storage, pricing, model
    states, local runtime, secondary results, value view, and extension
    instructions.

## Product Domains

| Domain | Functional role | Primary owners |
|---|---|---|
| Reports | Multi-model report generation, secondaries, views, export, value/cost analysis | `ui/report/**`, `ReportViewModel`, report engines, `ReportStorage`, `SecondaryResultStorage` |
| Chat | Single chat, dual chat, chat history, optional RAG context | `ui/chat/**`, `ChatViewModel`, `ChatHistoryManager` |
| Knowledge | RAG knowledge bases, source extraction, embedding, retrieval | `ui/knowledge/**`, `Knowledge*`, `EmbeddingsStore`, `LocalEmbedder` |
| Monitor | Traces, app log, audit, usage stats, live dashboard, crash reports | `ui/admin/**`, `ApiTracer`, `AppLog`, usage/cost stores |
| Setup | Providers, models, workers, prompts, parameters, model states, prices | `ui/settings/**`, `ui/cruds/**`, `ProviderRegistry`, `SettingsPreferences` |
| Housekeeping | Backup/restore, export/import, reset, trim, diagnostics | `ui/admin/**`, `BackupManager`, import/export helpers |
| Share | Android share-target landing into Report, Chat, or Knowledge | `MainActivity`, `SharedContent`, `ui/share/**`, `AppNavHost` |
| Local runtime | On-device LLM and embedder as synthetic provider | `data/local/**`, `AppService.LOCAL`, local setup screens |

## Source Snapshot

| Area | Count | Functional meaning |
|---|---:|---|
| Kotlin files | 388 | Main app source under `ai/src/main/java/com/ai` |
| `data/` | 88 | Networking, provider registry, storage, tracing, pricing, RAG, local runtime |
| `model/` | 2 | Settings and model data classes |
| `viewmodel/` | 24 | One Android VM plus wrapper VMs, engines, managers, batch helpers |
| `ui/` | 273 | Compose screens, grouped by product domain |
| `ui/report/` | 98 | Largest UI surface and main functional center |
| Tests | 86 | JVM and Android tests across data, report helpers, Compose, storage |

## Architectural Strengths

- Durable report-centered design gives every downstream tool a stable
  anchor.
- Provider format dispatch keeps forty cloud providers from becoming
  forty independent integrations.
- The worker/swarm abstraction lets metadata, rerank, moderation, meta,
  fan-in, and judged batches use a consistent fallback model.
- The flat `SecondaryResult` row makes export, view tiles, cost folding,
  broken-work recovery, and deletion logic share one storage system.
- The app exposes operational state to the user: traces, logs, model
  state lists, pricing sources, cooldowns, and costs.
- Static docs are extensive enough to function as a maintainer map.

## Architectural Pressure Points

- `AppViewModel` remains the central state owner for many domains. This
  simplifies cross-screen coordination but raises the cost of changing
  shared state shape.
- `SecondaryResult` is powerful but dense. New secondary kinds need
  careful documentation because fields are reused by convention.
- Settings has an internal enum router with many values. It avoids route
  explosion but concentrates navigation and back-stack behavior in one
  large file.
- Report UI is intentionally rich and split across many files. Finding
  the owning screen usually requires following Manage/View/Create/Edit
  terminology rather than one route per feature.
- JSON-file persistence is transparent and backup-friendly, but schema
  changes depend on compatibility discipline rather than a migration
  framework.

## Documentation Alignment Note

The source is the authority. During this audit, one documentation caveat
in `doc/persistent.md` and `doc/value-view.md` still described
`rankingWeights` and `showLadybugIcons` as session-only. Current source
in `SettingsPreferences.kt` reads and writes both settings. This audit
treats the source behavior as current.

## Recommended Review Questions

1. When adding a feature, is it a new top-level domain, a report view
   tile, a secondary result, a Settings sub-screen, or an admin tool?
2. Does the feature need durable storage, or can it be derived from
   existing report/secondary rows?
3. Should it run through provider format dispatch, worker/swarm fallback,
   local runtime, or a purely local derivation?
4. Does it belong in Jetpack Navigation, Settings' enum router, or an
   in-place full-screen overlay?
5. Which observable artifacts should it emit: trace tags, app logs,
   audit log entries, cost rows, usage stats, or export sections?

