# Architecture View

This file maps runtime ownership and architectural patterns. It avoids
bug classification and focuses on how the app is shaped.

## Observation 1 - Single Activity, Compose Shell

Functional role: `MainActivity` is the only Activity. It initializes
crash capture, handles incoming share intents, enables edge-to-edge UI,
and hosts `AppNavHost` inside the app theme.

Key files: `MainActivity.kt`, `ui/navigation/AppNavHost.kt`,
`ui/theme/**`.

Architecture implication: Android entry behavior is deliberately
centralized. Process-start concerns such as crash capture, share ingest,
theme, and root navigation all meet at one point.

## Observation 2 - Top-Level Navigation Is Route-Based

Functional role: product domains are registered through Jetpack
Navigation Compose routes. Route constants and builders live in
`NavRoutes`; registration is split across five route-extension files.

Key files: `NavRoutes.kt`, `AppNavHost.kt`, `ReportRoutes.kt`,
`SettingsAdminRoutes.kt`, `KnowledgeSearchRoutes.kt`,
`DeveloperRoutes.kt`, `ChatRoutes.kt`.

Architecture implication: adding a top-level screen should usually mean
adding a route constant/builder and placing the composable in the
appropriate route-extension file, not growing `AppNavHost` inline.

## Observation 3 - Settings Uses an Internal Router

Functional role: Settings has its own enum-driven sub-navigation and
hierarchical back behavior inside `SettingsScreen`.

Key files: `ui/settings/SettingsScreen.kt`, `SettingsSubScreen`,
`SetupScreens.kt`, `SettingsPreferences.kt`.

Architecture implication: Settings screens are part of one stateful
subtree. This keeps Settings transitions local but concentrates routing,
edit state, and back behavior in one file.

## Observation 4 - One Real Android ViewModel Owns Shared State

Functional role: `AppViewModel` is the only androidx `AndroidViewModel`.
It owns `UiState`, settings, bootstrap, provider/model refresh,
background resume state, shared hot state for batch work, and general
app-scoped flows.

Key files: `AppViewModel.kt`, `AppViewModelTypes.kt`.

Architecture implication: cross-domain coordination is easy because
there is one source of truth. The tradeoff is that state-shape changes
have a wide blast radius.

## Observation 5 - ReportViewModel and ChatViewModel Are Wrappers

Functional role: `ReportViewModel` and `ChatViewModel` are plain classes
that take `AppViewModel` and delegate shared state. They are named like
view models but are not Android lifecycle owners.

Key files: `ReportViewModel.kt`, `ChatViewModel.kt`.

Architecture implication: work launched from report/chat flows can
survive screen navigation because it uses `appViewModel.viewModelScope`.
Maintainers should not assume these wrappers have independent lifecycle
retention.

## Observation 6 - Report Complexity Is Extracted Into Engines

Functional role: report workflows are split into engines and managers:
regenerate batch, secondary runs, icon generation, fan-out, translation,
tournament, judge-eval, compare, translator-rank, worker runner, and
batch throttling.

Key files: `viewmodel/BatchEngine.kt`, `RegenerateBatchEngine.kt`,
`SecondaryRunManager.kt`, `FanOutEngine.kt`, `TournamentEngine.kt`,
`JudgeEvalEngine.kt`, `CompareEngine.kt`, `TranslatorRankEngine.kt`,
`ThrottledBatch.kt`.

Architecture implication: new long-running report work should look for
an existing engine family first. Shared batch behavior already exists.

## Observation 7 - Data Layer Is a Service Layer, Not a Repository-Only
Layer

Functional role: `data/` contains provider registry, dispatch,
networking, tracing, retries, storage, pricing, RAG, local runtime,
prompt seeding, report models, and export-adjacent data helpers.

Key files: `AnalysisRepository.kt`, `ApiDispatch.kt`, `ApiClient.kt`,
`ApiStreaming.kt`, `ProviderRegistry.kt`, `PricingCache.kt`,
`ReportStorage.kt`, `SecondaryResult.kt`.

Architecture implication: the package is not organized by clean
architecture layers. It is a practical service layer where persistence,
networking, and domain models live together.

## Observation 8 - Provider Dispatch Is Format-Based

Functional role: cloud providers are runtime registry entries with an
`ApiFormat`. Forty providers share OpenAI-compatible code; Anthropic
and Google have format-specific behavior. `Local` is synthetic and
routes around Retrofit.

Key files: `AppService.kt`, `ApiFormat.kt`, `ApiDispatch.kt`,
`ApiStreaming.kt`, `ProviderRegistry.kt`, `data/local/**`.

Architecture implication: adding providers should normally be data work
under assets plus registry sync. Adding a new API family is a dispatch
architecture change.

## Observation 9 - Networking Has Multiple Cross-Cutting Interceptors

Functional role: outgoing calls pass through tracing, tag propagation,
read-timeout handling, provider throttle, 429 retry, 529 retry, and
test-call timeout behavior.

Key files: `TracingInterceptor.kt`, `TagPropagation.kt`,
`ReadTimeout.kt`, `ProviderThrottling.kt`, `RateLimitRetry.kt`,
`OverloadedRetry.kt`, `TestCallTimeout.kt`, `ApiTracer.kt`.

Architecture implication: network behavior should be changed at the
interceptor/cap layer when it applies across providers, not repeated in
individual feature engines.

## Observation 10 - Full-Screen Overlays Are a State-Preservation Tool

Functional role: many report and settings subflows render overlays in
place and `return`, keeping the parent composable's remembered state
alive while the overlay is open.

Key files: `ui/report/view/**`, `ui/report/manage/**`,
`ui/settings/**`, `doc/development.md`.

Architecture implication: this is a deliberate local-navigation pattern.
Converting these overlays to routes can reset scroll positions,
selection state, and screen-local memory.

## Observation 11 - SecondaryResult Is a Shared Event Row

Functional role: every secondary kind persists through one row type with
kind-specific clusters layered onto optional fields. UI grouping,
export, cost views, and broken-work recovery all interpret this shared
shape.

Key files: `SecondaryModels.kt`, `SecondaryResult.kt`,
`SecondaryResultStorage`, `secondary-results.md`,
`tournament-judges-compare.md`, `rank-translators.md`.

Architecture implication: field reuse is powerful but convention-heavy.
New secondary features need explicit docs, run ids, role names, and
view/export rules.

## Observation 12 - Docs Are Part of the Architecture Contract

Functional role: `doc/` explains behavior that is too broad to infer
from one file: navigation, persistence, provider formats, parameters,
system prompts, model states, costs, RAG, local runtime, and workers.

Key files: `doc/README.md` and subsystem docs.

Architecture implication: substantial changes should update docs in the
same conceptual area. The code remains authoritative, but the docs are
the fastest way to preserve system intent across sessions.

