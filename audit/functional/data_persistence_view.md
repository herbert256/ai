# Data and Persistence View

This file describes how user-visible state is stored, cached, traced,
and moved between devices.

## Observation 1 - Persistence Uses SharedPreferences Plus Files

Functional view: settings and small maps are stored in
SharedPreferences; durable content and larger caches are stored as JSON
or text files under `filesDir` and selected `cacheDir` locations.

Source view: `SettingsPreferences`, `ReportStorage`,
`SecondaryResultStorage`, `ChatHistoryManager`, `PricingCache`,
`BackupManager`, `persistent.md`.

Design implication: persistence is transparent and inspectable. Schema
discipline must be maintained in code and docs because there is no
DataStore or database migration framework.

## Observation 2 - Settings Are Product Configuration, Not Just
Preferences

Functional view: settings include API keys, provider state, workers,
parameters, prompts, model states, external metadata keys, UI colors,
logging, network caps, metadata toggles, home mode, and ranking weights.

Source view: `SettingsPreferences.kt`, `SettingsModels.kt`,
`AppViewModelTypes.kt`, `SettingsScreen.kt`.

Design implication: settings changes can alter execution plans,
navigation, costs, observability, and view behavior. They should be
tested as functional configuration.

## Observation 3 - Provider Registry Is Runtime Data Seeded From Assets

Functional view: providers are loaded from asset JSON, then persisted and
user-edited. Keys, states, manual models, capabilities, endpoints, and
sync timestamps live outside the static asset definitions.

Source view: `ProviderRegistry`, `ProviderFieldTimestamps`,
`assets/providers/*.json`, `providers.md`.

Design implication: a provider definition is both packaged product data
and mutable user state. Asset sync must preserve user-touched fields.

## Observation 4 - ReportStorage Is the Main Document Store

Functional view: reports are stored as durable JSON documents with
agents, prompt, titles, icons, parameters, costs, usage, notes, and
export/import metadata.

Source view: `ReportStorage.kt`, `ReportBundle.kt`,
`reports_section_analysis.md`, report export helpers.

Design implication: report schema changes are high-impact. They affect
old saved reports, backups, exports, imports, history, search, and view
screens.

## Observation 5 - SecondaryResultStorage Is the Analysis Store

Functional view: one file per secondary result under a report stores
rerank, meta, moderation, translation, fan-out/fan-in, tournament,
judges, compare, and transrank rows.

Source view: `SecondaryResult.kt`, `SecondaryModels.kt`,
`secondary-results.md`, `persistent.md`.

Design implication: secondary rows are appendable, independently
deletable, and shared by many views. Any new secondary kind must define
row identity, grouping, run ids, and deletion semantics.

## Observation 6 - Pricing and Metadata Use Layered Caches

Functional view: pricing and model capabilities combine provider
self-report, manual overrides, external repositories, cached tier blobs,
and defaults into a per-provider/model view.

Source view: `PricingCache.kt`, `ModelListCache.kt`,
`HuggingFaceCache.kt`, `repositories.md`, `costs.md`.

Design implication: model/cost displays are layered conclusions, not
single-source facts. UI should preserve source labels and unknown/default
states.

## Observation 7 - Chat History Is Separate From Report Storage

Functional view: chat sessions, dual chat configuration, generated
titles, and chat message history persist independently from reports.

Source view: `ChatHistoryManager`, `dual_chat_prefs`, `ChatViewModel`,
`ui/chat/**`.

Design implication: chat can share providers, parameters, and prompts
with reports while maintaining its own artifact lifecycle.

## Observation 8 - Knowledge Bases Have Their Own File Model

Functional view: knowledge bases, imported sources, chunks, embeddings,
and retrieval indexes are stored separately from reports and chat.

Source view: `Knowledge*`, `KnowledgeService`, `KnowledgeExtractors`,
`EmbeddingsStore`, `knowledge.md`.

Design implication: RAG data can be attached to chat/report flows, but
the knowledge lifecycle remains its own domain with its own backup and
storage considerations.

## Observation 9 - Traces, Logs, Audit, Usage, and Crashes Are Durable
Observability Data

Functional view: API traces, app logs, audit logs, usage stats, and
crash reports are stored and surfaced in the app so users can inspect
what happened.

Source view: `ApiTracer`, `TracingInterceptor`, `AppLog`, `AuditLog`,
crash reporting files, `applog.md`, `log-details.md`.

Design implication: observability artifacts need redaction, retention,
filtering, and backup/export decisions just like user-created reports.

## Observation 10 - Backup/Restore Is a Cross-Domain Contract

Functional view: backup zips include selected prefs and files, validate
before writing, preserve excluded local-model directories, and rebuild
provider/runtime state on next launch.

Source view: `BackupManager.kt`, `backup-restore.md`, `persistent.md`.

Design implication: adding persistent data requires deciding whether it
belongs in backup, is device-local, is recomputable, or is too large to
preserve.

## Observation 11 - Atomic Writes Are the File-Safety Primitive

Functional view: JSON/text persistence writes through atomic helper
paths that fsync and move temp files into place.

Source view: `AtomicFileWrite.kt`, storage managers that call
`writeTextAtomic`.

Design implication: file persistence should use the helper consistently.
Bypassing it changes crash-safety guarantees.

## Observation 12 - Export/Import Defines Portable Report Schema

Functional view: report bundles, HTML/zip exports, documents, trace zips,
and bulk exports turn internal rows into portable artifacts.

Source view: `ReportBundle.kt`, `ReportStorage.kt`, `ReportExport.kt`,
`ZippedHtmlExport.kt`, `WordOdtExport.kt`, `ReportExportScreen`.

Design implication: internal schema changes should include a portability
review. A field that matters to users usually belongs in at least one
export path.

