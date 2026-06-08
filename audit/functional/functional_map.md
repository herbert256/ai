# Functional Map

This file describes the app from the user's point of view. Each
observation identifies the functional surface, the source owners, and
the architectural implication.

## Observation 1 - First Run Is a Seeded Setup Flow

Functional view: a fresh install is not an empty shell. The app seeds
provider definitions, internal prompts, examples, inaccessible/test-
excluded model lists, and enough defaults for the user to start from a
catalog rather than typing endpoints manually.

Source view: `ProviderRegistry`, `InternalPromptSeed`, `ExamplePromptSeed`,
asset files under `ai/src/main/assets/providers`, `internal-prompts`,
`examples`, `excluded.json`, and `inaccessible.json`.

Why it matters: provider and prompt defaults are product data. Changing
them is closer to changing onboarding behavior than changing code.

## Observation 2 - Home Has Two Product Shells

Functional view: the app supports a classic card hub and a persistent
home-bar shell. The default home-bar mode makes Reports, Chat, Monitor,
Setup, Housekeeping, Settings, Help, traces, and app logs globally
reachable.

Source view: `AppHomeMode`, `AppNavHost`, `HubScreen`, `HomeIconBar`,
`BottomIconBar`, and `SettingsPreferences`.

Why it matters: navigation changes need to account for both shells. A
screen can be reachable from the hub, from the persistent bar, from a
bottom bar, and from contextual report actions.

## Observation 3 - Reports Are the Primary Work Product

Functional view: a report is a saved multi-model answer set with prompt,
models, metadata, titles, icons, costs, traces, notes, and downstream
analysis. It is the central artifact users create, revisit, export, and
compare.

Source view: `ui/report/**`, `ReportViewModel`, `ReportStorage`,
`ReportAgent`, `Report`, `ReportStorage` import/export helpers.

Why it matters: report ids are the major join key across storage, view
state, secondary rows, traces, costs, and export paths.

## Observation 4 - Model Selection Is a Composition Surface

Functional view: a report can be built from direct models, agents,
flocks, swarms, previous reports, parameter presets, system prompts,
vision images, reasoning controls, and web-search toggles.

Source view: `ui/report/start/**`, `ui/report/manage/**`,
`SettingsModels.kt`, `workers.md`, `parameters.md`, `system-prompts.md`.

Why it matters: a report selection is not just a provider/model list.
It is a resolved execution plan produced by multiple user-curated
configuration layers.

## Observation 5 - Generation Continues as a Background Workspace

Functional view: after tapping Generate, the user can navigate away.
The run continues on the shared app scope, placeholders recover when the
screen returns, and completion can be surfaced later.

Source view: `ReportViewModel`, `AppViewModel.viewModelScope`,
`BuildProgress`, `ReportStorage`, `BrokenWorkPolicy`.

Why it matters: screen lifetime and work lifetime are intentionally
separate. Moving report work into route-local scope would change product
behavior.

## Observation 6 - Manage, View, Edit, and Create Are Separate Modes

Functional view: the report result page is a management console. Manage
shows answer rows and actions. View is a tile hub for read-only
representations. Edit changes prompts/models/titles/icons. Create starts
secondary analysis.

Source view: `ui/report/manage/**`, `ui/report/view/**`,
`ui/report/start/**`, `MetaEditManager`, `SecondaryRunManager`,
`RegenerateBatchEngine`.

Why it matters: "report screen" is not one screen. Functional ownership
is split by mode, and each mode has its own state-preservation and
navigation expectations.

## Observation 7 - Secondary Results Are a Feature Platform

Functional view: a finished report can produce reranks, meta prompts,
moderation, translations, fan-out/fan-in, tournaments, judge-evaluation,
compare-with-meta, and rank-the-translators runs.

Source view: `SecondaryKind`, `SecondaryResult`, `SecondaryResultStorage`,
`SecondaryRunManager`, `FanOutEngine`, `TranslationRunManager`,
`TournamentEngine`, `JudgeEvalEngine`, `CompareEngine`,
`TranslatorRankEngine`.

Why it matters: secondary features should usually reuse the existing row
store, run-state conventions, and view/export integration instead of
inventing parallel storage.

## Observation 8 - Worker-Judged Batches Are a Product Family

Functional view: Tournament, Judge the judges, Compare with meta, and
Rank the translators all use selected workers to evaluate other model
outputs and then persist cell-level or aggregate judgments.

Source view: `BatchEngine`, `TournamentRunModel`, `JudgeEvalRunModel`,
`CompareRunModel`, `TranslatorRankModel`, `tournament-judges-compare.md`,
`rank-translators.md`.

Why it matters: these features are not one-off UI tools. They share
batch throttling, hot cell state, run ids, recovery, and export needs.

## Observation 9 - Value View Turns Stored Judgments Into Decisions

Functional view: Value view plots quality versus cost and identifies a
best-value model and Pareto frontier using stored rerank, tournament,
judges, and translator-rank data.

Source view: `ui/report/view/ValueView.kt`, `value-view.md`,
`GeneralSettings.rankingWeights`, `RankingWeightsSubScreen`.

Why it matters: this is a local analytical layer. It should not trigger
API calls; correctness depends on cost attribution and ranking-source
alignment.

## Observation 10 - Chat Is Secondary but Still First-Class

Functional view: users can run single-model chat, dual chat, chat
history, generated chat titles, RAG context injection, and local LLM
chat through the same provider/model concepts used by reports.

Source view: `ui/chat/**`, `ChatViewModel`, `ChatHistoryManager`,
`DualChatScreen`, `KnowledgeService`, `LocalLlm`.

Why it matters: chat shares providers, parameters, system prompts, and
RAG, but its persistence and conversation state are separate from
reports.

## Observation 11 - Knowledge Is an Optional Experimental Domain

Functional view: AI Knowledge provides RAG knowledge bases, extraction,
chunking, embedding, retrieval, and local semantic search. It is hidden
behind the Experimental features gate.

Source view: `ui/knowledge/KnowledgeScreens.kt`, `Knowledge*`,
`KnowledgeExtractors`, `KnowledgeService`, `EmbeddingsStore`,
`LocalEmbedder`, `experimental.md`.

Why it matters: the feature is architecturally present but intentionally
gated. UI entry points should respect both experimental mode and
knowledge-specific visibility settings.

## Observation 12 - Monitor Is a User-Facing Control Room

Functional view: Monitor exposes live dashboard, traces, app log, audit,
statistics, crash reports, models, and usage/spend. It gives users
operational visibility into AI calls rather than hiding it in developer
tools.

Source view: `ui/admin/**`, `ApiTracer`, `AppLog`, crash storage,
usage/cost helpers, `applog.md`, `log-details.md`.

Why it matters: diagnostics are part of the UX contract. Changes to
networking, dispatch, costs, or storage should preserve traceability.

## Observation 13 - Setup Is the Control Plane

Functional view: AI Setup configures providers, API keys, model lists,
workers, prompts, parameters, model states, external metadata keys,
pricing overrides, local runtime, and app settings.

Source view: `ui/settings/**`, `ui/cruds/**`, `SettingsScreen`,
`SettingsPreferences`, `ProviderRegistry`, `PricingCache`.

Why it matters: the setup screens are not administrative leftovers.
They define the runtime graph that Reports and Chat later execute.

## Observation 14 - Share, Export, Backup, and Restore Are Part of the
Artifact Story

Functional view: reports can be created from Android share intents,
exported in multiple formats, imported/exported in bundles, and backed
up/restored with app state.

Source view: `MainActivity`, `SharedContent`, `ui/share/**`,
`ReportExport`, `ZippedHtmlExport`, `WordOdtExport`, `BackupManager`,
`ReportStorage` bundle helpers.

Why it matters: the app treats AI outputs as portable artifacts. Any new
report feature should ask whether it belongs in exports, backup, import,
and share flows.

