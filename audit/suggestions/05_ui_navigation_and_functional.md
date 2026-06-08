# UI, navigation, and functional suggestions

## U01 - Replace manual settings routing with a route registry

Priority: P1

Evidence:

`SettingsSubScreen` is a large enum (`SettingsScreen.kt:32`) and
`SettingsScreen` manages current sub-screen state and many edit targets
(`SettingsScreen.kt:144` onward). Back behavior is a large manual `when` block
starting at `SettingsScreen.kt:222`.

Suggestion:

Create a `SettingsRouteSpec` registry:

```kotlin
data class SettingsRouteSpec(
    val route: SettingsSubScreen,
    val parent: SettingsSubScreen?,
    val title: String,
    val sectionIcon: TopBarLeftIcon?,
    val helpTopic: String?,
    val render: @Composable SettingsRouteScope.() -> Unit
)
```

Back behavior then becomes parent lookup plus a small set of explicit
exceptions. Deep-linked routes can declare whether back exits to caller or
returns to parent.

Expected benefit:

Adding a settings screen becomes a registry entry instead of changes across the
enum, back handler, title/icon logic, help wiring, and rendering block.

## U02 - Introduce a report workflow state machine

Priority: P1

Evidence:

The report area spans new report selection, prompt editing, model selection,
progress, manage, view, secondary drill-ins, overlays, and external/share
entrypoints. Runtime state is spread across `ReportViewModel`, `AppViewModel`,
Compose local state, and navigation routes.

Suggestion:

Create a small state model for the report workflow:

```kotlin
sealed interface ReportWorkflowMode {
    data object Start : ReportWorkflowMode
    data class SelectingModels(...) : ReportWorkflowMode
    data class Generating(val reportId: String) : ReportWorkflowMode
    data class Managing(val reportId: String) : ReportWorkflowMode
    data class Viewing(val reportId: String) : ReportWorkflowMode
    data class SecondaryDetail(val reportId: String, val resultId: String) : ReportWorkflowMode
}
```

Navigation can still be Jetpack Navigation. The state model is for local
workflow rules, overlays, and tests.

Expected benefit:

It becomes easier to reason about "where is the user" independent of URL-like
routes and one-off `rememberSaveable` flags.

## U03 - Move direct storage reads out of composables

Priority: P1

Evidence:

Examples:

- `SelectionPhase` lists knowledge bases with `remember(kbRefreshTick)` and a
  direct store call (`SelectionPhase.kt:202`).
- Dashboard local runtime cards scan installed local models with `remember`
  (`AiDashboardScreen.kt:2635` to `AiDashboardScreen.kt:2637`).
- Report runtime state holds many mutable values and reload triggers in Compose
  (`RuntimeState.kt:115` onward).

Suggestion:

Expose repository/view-model flows for these:

- `KnowledgeListState`
- `LocalRuntimeInventoryState`
- `ReportManageSnapshot`

Compose should collect state and send intents. File I/O and scan policy should
live outside composition.

Expected benefit:

Screens become more responsive and deterministic. It also reduces the chance of
expensive file work happening on the main thread or at awkward recomposition
times.

## U04 - Add a screen spec registry for top bars, section icons, and help topics

Priority: P2

Evidence:

`AppNavHost` computes section icons from route sets and maps
(`AppNavHost.kt:320` to `AppNavHost.kt:405`). It also provides many navigation
lambdas through composition locals (`AppNavHost.kt:250` onward).

Suggestion:

Create `ScreenSpec`:

```kotlin
data class ScreenSpec(
    val navRoute: String,
    val section: AppSection,
    val title: String,
    val defaultHelpTopic: String?,
    val topIconPolicy: TopIconPolicy
)
```

Use the registry for top-bar section icons, help routing, and route grouping.

Expected benefit:

Adding a route no longer requires remembering to update several route sets.

## U05 - Add an execution plan preview before expensive runs

> **Status (2026-06-08): ↩︎ reverted** — shipped a read-only plan summary on the select-models screen (`0ea6535c`), but the user found the "N primary calls across …" text unwanted and asked for it to be removed; the preview and its backing `ReportExecutionPlan` model + tests (R10, which had no other consumer) were deleted.

Priority: P1

Functional suggestion:

Before starting a report, fan-out, tournament, compare, translation, or large
metadata batch, show a compact preview:

- number of API calls
- selected providers/models
- estimated cost range
- active rate caps
- expected skipped/blocked models
- whether web search or reasoning is enabled
- whether local models are included

Let users confirm, edit model selection, or save the plan as a preset.

Technical note:

This should be backed by the `ReportExecutionPlan` object from R10, not by UI
recomputing the same rules.

## U06 - Build a unified job center

Priority: P2

Functional suggestion:

Add one "Activity" or "Jobs" screen that lists all active and recently failed
work:

- report generation
- report metadata
- secondary batches
- translations
- refresh all
- model tests
- knowledge extraction and embedding

Each row should show progress, wait reason, elapsed time, current provider/model
where relevant, estimated cost so far, and actions.

Technical note:

This becomes straightforward after engines publish a common `RuntimeJob` shape.

## U07 - Productize provider/model health

Priority: P2

Functional suggestion:

The source already records usage, cooldowns, test outcomes, inaccessibility,
tracing, and provider failures. Surface that as model health:

- recently successful
- recently failed
- benched/cooldown
- test excluded
- inaccessible
- average latency
- cost trend
- last trace

Use it in model pickers, execution plans, and report diagnostics.

Expected benefit:

Users can pick models based on recent reliability, not just static provider
metadata.

## U08 - Add report templates/presets

Priority: P2

Functional suggestion:

Create saved report workflow presets:

- prompt template
- swarm/model selection
- parameter presets
- system prompt
- default secondaries
- export behavior
- language/translation behavior

This is more powerful than saving only prompt history because it captures the
workflow around the prompt.

Technical note:

Presets should reference stable IDs for agents, swarms, prompts, and parameters,
and validate missing references before execution.

## U09 - Add secondary lineage visualization

Priority: P2

Functional suggestion:

For complex reports, show a lineage view:

```text
Report
  -> primary model answers
  -> rerank/moderation/meta
  -> fan-out pairs
  -> fan-in summaries
  -> tournament/compare/translator-rank
  -> exported/derived reports
```

This helps users understand what a result is based on and what will be affected
by deleting or rerunning a source item.

Technical note:

This pairs with the normalized lineage model suggested in R09.

## U10 - Make reproducible run bundles

Priority: P2

Functional suggestion:

Add a "reproducible bundle" export mode for reports:

- prompt and system prompts
- selected models/providers
- settings that affected dispatch
- parameter presets
- model catalog/capability snapshot
- API traces and cost rows
- secondary lineage

Expected benefit:

Users can share or archive a report with enough context to explain how it was
made.

## U11 - Consolidate alternative candidate pickers

Priority: P1

Evidence:

The source supports multiple "find alternative" flows: report titles, report
icons, language icons, per-model titles/icons, and fan-out pair edits. These
are functionally similar: run multiple candidate calls, display options, accept
one, persist provenance and cost.

Suggestion:

Create a generic `CandidatePicker` UI and runtime model:

- target
- candidate list
- status per candidate
- provider/model
- trace/cost/duration
- accept callback
- retry callback

Expected benefit:

Alternative title/icon/model-response features share UX and code.

## U12 - Improve accessibility and semantics for icon-heavy UI

Priority: P1

Evidence:

The app relies heavily on metadata icons and compact icon buttons. That is a
valid product style, but the source should ensure every actionable icon has a
content description and predictable hit target.

Suggestion:

Create wrappers:

- `IconActionButton`
- `MetadataIconText`
- `StatusIcon`

Enforce content descriptions and tooltips in those wrappers. Add Compose tests
for the most important report and settings actions.

Expected benefit:

The app remains compact while becoming more accessible and testable.

## U13 - Convert dashboard polling into scoped observable state

Priority: P2

Evidence:

Dashboard/report statistics use ticking `produceState` loops
(`AiDashboardScreen.kt:771`, `AiDashboardScreen.kt:2061`,
`AiDashboardScreen.kt:2608`). Some of the comments explicitly note that work is
only active when cards are expanded, which is good.

Suggestion:

Keep short-lived visual tickers for elapsed durations, but move data refresh to
observable runtime stores:

- log writer state
- trace count
- disk usage
- local runtime inventory
- provider throttle state
- report statistics

Expected benefit:

Dashboard cards update when data changes, not only when a timer ticks. This
also lowers idle work on long sessions.

## U14 - Add an "explain this state" affordance

Priority: P2

Functional suggestion:

When a model row is dimmed, a batch is throttled, a report action is disabled,
or a secondary result is waiting, expose a short explanation:

- "waiting for per-provider rate limit"
- "model is short-benched after 429"
- "provider key missing"
- "web search not supported for this model"
- "secondary needs at least two successful primary answers"

Technical note:

This should consume structured status objects, not ad hoc strings.

## U15 - Simplify external/share entry routing with intent command objects

Priority: P2

Evidence:

`MainActivity` handles incoming intents and passes state into `AppNavHost`
(`MainActivity.kt:43` to `MainActivity.kt:148`). `AppNavHost` parses external
report instructions and stages confirmation (`AppNavHost.kt:109` to
`AppNavHost.kt:202`), then handles share-target routing (`AppNavHost.kt:205`).

Suggestion:

Create:

- `ExternalAppCommandParser`
- `ExternalReportCommand`
- `ShareTargetCommand`
- `ExternalCommandRouter`

The activity only captures raw Android intent data; the parser and router own
business rules.

Expected benefit:

External entry behavior can be unit-tested without Compose or Activity setup.
