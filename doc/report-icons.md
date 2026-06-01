# Report icons

Every report carries a generated emoji icon. Two independent
flows produce them, and both run through the **worker engine**
(`viewmodel/WorkerRunner.kt`) rather than a hand-rolled
fixed-agent chain:

1. **Per-report icon** — one emoji per `Report`, derived from
   the report's long title via the bundled `workers/report-icon`
   prompt. Surfaces in the AI Reports hub, history rows, search
   hits, and the title bar of every report-scoped screen.
2. **Per-model (per-agent) icon** — one emoji per `ReportAgent`,
   derived from that agent's **model title** via the bundled
   `workers/model-icons` prompt. Surfaces in the result page's
   icon row, the **View → Icons** grid, and the per-call cost
   tables. There is no longer a response-based 3-tier fallback
   chain — when no title is available, or no worker yields an
   emoji, the agent is simply left icon-less.

Both flows are gated by the grand-master **Metadata & icons**
switch (`metadataEnabled`, Settings → AI Setup → App settings)
folded with their own sub-toggle:

- `iconGenEnabled` (`Generate report icons`, default true) — the
  per-report icon (`reportIconOn()` = master AND this flag).
- `perModelIconGenEnabled` (`Generate per model icons`, default
  true) — the per-model icon (`perModelIconOn()`).

When a switch is off, the matching icon column doesn't generate;
existing on-disk icons stay (re-enabling brings them back).

These generated report/agent icons are separate from **Default
icons**. Default icons are the user-editable fallback and navigation
glyphs stored in `GeneralSettings.metadataIcons` and used by cards,
empty metadata rows, and non-generated screen affordances. See
[ui-customization.md](ui-customization.md) for the Default icons and
UI Colors screens.

## The worker engine

A `workers`-category `InternalPrompt` carries a **list of
workers** (`InternalPrompt.workers`) rather than a single pinned
agent — a fallback chain spanning several cheap providers
(gpt-4o-mini, DeepSeek, Groq/Cerebras/SambaNova llama-3.1-8b,
gemini-2.5-flash-lite, gpt-4.1-nano, ministral-8b, grok-fast).

Each `Worker` is one of **four kinds**: a **Model**
(provider + model), an **Agent** (by name), a **Flock**, or a
**Swarm**. Flock / Swarm entries are **expanded** by
`Settings.expandWorker` before the run — a Flock contributes one
candidate per member agent, a Swarm one per `(provider, model)`
member — so each member joins the chain as its own independent
fallback (own cooldown key, own cost attribution).
`WorkerRunner.run`:

- **Shuffles** the worker order each call — the primary pick (and
  the fallback order after a miss) is **random**, not a
  deterministic rotation.
- On a **429** parks that worker on a short local cooldown
  (`Retry-After`, else `WORKER_429_DEFAULT_MS` = 5 s) and tries
  the next; also skips a worker whose model is benched in the
  global `ModelCooldownStore`.
- Takes an `accept` validator so a 200 OK with **no usable
  artifact** (no parseable emoji / no non-blank title / no
  `language:` line) is a **logical miss** — the chain advances to
  the next worker instead of returning a hollow success.
- Returns `WorkerOutcome.Success` / `AllRateLimited` / `Failed`.

## The bundled worker + alt prompts

Seeded from `assets/internal-prompts/` on every app start (delta
merge — only adds missing entries by `(category, name)`, so user
edits survive). The auto-generation prompts live under category
`workers`; their Find-alternative variants under `alt`:

| Name | Category | Substitutions | Used by |
|---|---|---|---|
| `report-icon` | `workers` | `@TITLE_LONG@` | per-report icon |
| `model-icons` | `workers` | `@TITLE@` | per-model icon (from the model title) |
| `report-title` | `workers` | `@PROMPT@` | report short + long title |
| `model-titles` | `workers` | `@RESPONSE@` | per-model title |
| `report-language` | `workers` | `@PROMPT@` | language + flag emoji (one call, `language:` / `icon:` reply) |
| `fan-meta` | `workers` | `@PROMPT@` | per-fan-out-pair title + icon (one call) |
| `second-meta` | `workers` | `@NAME@`, `@TITLE@` | meta-prompt (secondary-row) icon cache |
| `translation-icon` | `workers` | `@LANGUAGE@` | per-language translation icon |
| `main` | `alt` | `@PROMPT@` | Find-alternative report icon (pinned `DeepSeek`) |
| `report` | `alt` | `@PROMPT@`, `@RESPONSE@` | Find-alternative per-agent icon |
| `fan_out` | `alt` | `@QUESTION@`, `@SOURCE_RESPONSE@`, `@META_PROMPT@`, `@RESPONSE@` | Find-alternative fan-out-pair icon |
| `language` | `alt` | `@LANGUAGE@` | Find-alternative language icon (pinned `DeepSeek`) |
| `meta` | `alt` | `@NAME@`, `@TITLE@` | Find-alternative meta-row icon (pinned `DeepSeek`) |
| `translation` | `alt` | `@LANGUAGE@` | Find-alternative translation icon (pinned `DeepSeek`) |
| `report_title` / `report_title_long` | `alt` | `@PROMPT@` | Find-alternative report short / long title |
| `model_title` | `alt` | `@RESPONSE@` | Find-alternative model / fan-out-pair title |

The `workers`-category prompts ship the same default worker list
(the cheap-provider chain above). Users can edit / re-order the
workers per prompt via Settings → AI Setup → Prompt management →
Internal prompts.

## Per-report icon flow

`IconGenerationManager.kickOffIconGeneration` runs after the
report title attempt (the icon is derived from the long title):

1. Bail when `reportIconOn()` is false.
2. Read the `workers/report-icon` prompt; bail if missing or no
   worker resolves.
3. Read the report fresh from disk and feed its **long title**
   (fall back to short title, then the prompt) as `@TITLE_LONG@`.
4. Launch on `viewModelScope.launch` —
   `withTracerTags(reportId, category="workers/report-icon")` so
   the call's trace surfaces on the report's Trace screen.
5. `WorkerRunner.run` makes the call(s), accepting only a reply
   that yields a parseable emoji.
6. **Always normalise to exactly one emoji.**
   `extractFirstEmoji` (`data/EmojiExtract.kt`) walks the
   response with `BreakIterator.getCharacterInstance()` and
   returns the first grapheme cluster whose lead codepoint sits
   in a known emoji block (Misc Symbols, Pictographs, Transport,
   Dingbats, Misc Technical, Regional Indicators, Mahjong /
   Domino / Playing cards, Supplemental Symbols). A 200 OK whose
   body contains **no parseable emoji is a logical miss**: the
   `WorkerRunner` chain treats it like a transport miss and
   advances to the next worker (via the `accept` validator —
   `extractFirstEmoji(...) != null`), instead of silently
   accepting an empty reply. Only when *every* worker fails to
   produce an emoji does the row settle — `📝` is now just a
   defensive last resort, and the worker-engine paths set
   `iconErrorMessage` ("no worker produced an icon", or "all
   workers rate-limited") so the row renders ❌. The same
   logical-miss rule applies to the title / language / fan-meta
   worker prompts (non-blank title / parseable `language:` line /
   title-or-emoji respectively).
7. Cost is computed from `PricingCache.getPricing(provider,
   model)` × `(inputTokens, outputTokens)` for the **winning
   worker's** model and persisted onto `Report.iconInputCost` /
   `iconOutputCost` (plus tokens).
8. `iconRefreshTick` on `UiState` is bumped to force recomposition
   of every list and title bar reading the icon.

The call is launched on `viewModelScope` (`AppViewModel`'s), not
`ReportViewModel`'s — so navigating away from the result screen
doesn't cancel it.

## Per-model icon flow

The per-model icon is **derived from the model title**, not from
a response-based chain. `IconGenerationManager.runPerModelEnrichment`
fires after an agent's primary call settles to `SUCCESS` (on both
fresh generation and regenerate), gated by the two per-model
toggles:

- **title + icon on** → `runModelTitleForAgent` generates and
  stores the model title (`workers/model-titles`, `@RESPONSE@`),
  then chains the icon from it.
- **icon only** → still generates a title transiently (the icon
  prompt needs it) but never stores or surfaces it.
- **title only** → just the title; no icon.

`generateIconFromTitle` then runs `workers/model-icons`
(`@TITLE@` = the model title) through the worker engine:

- Resets the agent's icon fields + `iconCalls` first so a
  regenerate replaces rather than accumulates.
- Tags traces with `withTracerTags(reportId,
  category="workers/model-icons")`.
- Accepts only a reply with a parseable emoji; otherwise the
  agent is left icon-less (no further fallback).
- On success writes the emoji via
  `setReportAgentIconAndTier(winningTier = null,
  promptUsed = "report_title_icon")` and records the winning
  worker's spend as an `IconCallRecord` (tier 2) +
  `UsageStats` post with `kind="icon"`, attributed to the actual
  worker's `(provider, model)`.

> The legacy response-based **3-tier chain** (`runReportIconsForAgent`
> + `runTier1/2/3` against `report_icon_chat` / `report_icon` /
> `report_icon_3th`) has been **removed** — per-model icons now come
> solely from the worker engine.

## Find alternative icons

Bottom button on the **Icon lookup** detail screen (reached by
tapping any icon-row glyph, e.g. from **Edit icons**). Opens a
model picker; the user picks any number of `(provider, model)`
pairs. Each scope runs its own self-contained `alt/*` template:

| Launched from | Template | Substitutions | Commit |
|---|---|---|---|
| report icon (Hub / Edit) | `alt/main` | `@PROMPT@` | `Report.icon` (`promptUsed = "main_alt"`) |
| per-agent icon | `alt/report` | `@PROMPT@` + `@RESPONSE@` | `ReportAgent.icon` (`promptUsed = "report_alt"`) |
| language icon | `alt/language` | `@LANGUAGE@` | `Report.languageIcon` |
| meta-row icon | `alt/meta` | `@NAME@` + `@TITLE@` | the row / cache (`promptUsed = "meta_alt"`) |
| fan-out-pair icon | `alt/fan_out` | `@QUESTION@` / `@SOURCE_RESPONSE@` / `@META_PROMPT@` / `@RESPONSE@` | the pair row (`promptUsed = "fan_out_alt"`) |
| translation icon | `alt/translation` | `@LANGUAGE@` | the cache (`promptUsed = "translation_alt"`) |

Each fan-out (`startIconFanOut` / `startAgentIconFanOut` /
`startPairIconFanOut` / …):

- Dedupes picks by `"providerId:model"`.
- Pre-populates `IconCandidate.Running` rows so the Alternative
  icons screen shows ⏳ for every pair the moment it opens.
- One async per pick; each pre-acquires the per-provider throttle
  permit and runs `analyzeWithAgent`.
- Bumps the matching cost field regardless of success; the
  matching `IconCandidate` flips to `Done` (resolved emoji) or
  `Error`.
- The per-fan-out-pair flow (`startPairIconFanOut`) is
  **generalised to any `SecondaryResult` row** — keyed on the
  row id — so a Rerank or Moderation row's icon is refindable
  through exactly the same path (the `@SOURCE_RESPONSE@` /
  `@META_PROMPT@` tokens just resolve empty for a sourceless row).

The user taps any returned emoji to commit it. The per-call cost
maps live in-memory on `AppViewModel` (`iconFanOutByReport`,
`agentIconFanOutByAgent`, `pairIconFanOutByPair`, …); navigating
away mid-flight preserves the in-flight list (the outer
`appViewModel.viewModelScope.launch` keeps running). A **Restart**
button re-fires the picker from a clean slate.

## Manual edit / Select icon

Besides Find-alternative, the **Icon lookup** screen offers two
direct ways to set the glyph (both wired through
`IconLookupContext.onApplyIcon`, available on every icon scope —
report, language, per-model, meta, rerank, moderation, translation,
fan-out pair):

- **Manual edit icon** — a small popup to type / paste a glyph.
- **Select icon** — the same `EmojiPickerView` bottom sheet used
  by Settings → Default icons.

Both apply straight to that scope's storage (bumping
`iconRefreshTick`) without firing any LLM call.

## The icons grid

Top-bar `Create` action on the report result screen → **View →
Icons**. Minimal grid of every successful per-agent icon, plus
a top-left thumb of the per-report icon. Tapping a glyph opens
that agent's **Model response** detail; backing out returns to
the grid (not the result screen — overlay back-stack rule,
`feedback_overlay_back_stack`).

Help topic: `report_icons_grid`. Grid spacing adapts down when
not every icon fits at the default size, so a 12-agent report
still lays out without wrapping.

## Costs surface

Per-icon spend surfaces in three places:

1. **In-app View → Costs** — `By type` / `By model` / `All`
   tabs. The per-model icon call appears under each agent's
   provider/model split (with a `Calls` column); the per-report
   icon appears as its own row; every Find-alternative fan-out
   call appears as its own `alt/*`-typed row.
2. **HTML export — Complete and Costs views** — same three
   tabs, same per-call All rendering. The icon row in the
   Complete view shows the agent's effective icon + cost.
3. **Global AI Usage** — each icon call posts with
   `kind="icon"`, attributed to the model that actually ran.
   Filterable in the Usage screen.

`Report.costsFromDeletedItems` (also bumped on per-agent /
secondary / fan-out deletions) surfaces as its own line above
the Total when non-zero — so the user sees what the API
actually billed for the run, even after trimming rows.

## Data model

```kotlin
data class Report(
    …,
    var icon: String? = null,
    var iconErrorMessage: String? = null,
    var iconInputTokens: Int = 0,
    var iconOutputTokens: Int = 0,
    var iconInputCost: Double = 0.0,
    var iconOutputCost: Double = 0.0,
    var iconModel: String? = null,           // "<providerId>/<modelId>" when alt-picked
    var iconCalls: MutableList<IconCallRecord> = …,
    var costsFromDeletedItems: Double = 0.0
)

data class ReportAgent(
    …,
    var icon: String? = null,
    var iconErrorMessage: String? = null,
    var iconInputTokens: Int = 0,
    var iconOutputTokens: Int = 0,
    var iconInputCost: Double = 0.0,
    var iconOutputCost: Double = 0.0,
    var iconWinningTier: Int? = null         // always null now — set by the removed 3-tier chain; manual / alt / worker-engine icons leave it null
)

data class IconCallRecord(
    val agentId: String,
    val tier: Int,
    val provider: String,
    val model: String,
    val pricingTier: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val inputCost: Double,
    val outputCost: Double,
    val durationMs: Long?,
    val success: Boolean,
    val timestamp: Long
)
```

## Files

- `data/EmojiExtract.kt` — `extractFirstEmoji`.
- `data/ReportStorage.kt` — `Report` + `ReportAgent` + `IconCallRecord` + the
  `updateReportIcon` / `updateReportIconError` /
  `setReportIconChoice` / `setReportAgentIconAndTier` /
  `clearReportAgentIconState` writers.
- `viewmodel/WorkerRunner.kt` — the random-pick / 429-fallback
  worker engine (`WorkerOutcome`, `run`, `runWorkerBatch`).
- `viewmodel/IconGenerationManager.kt` — `kickOffIconGeneration`,
  `runPerModelEnrichment` / `runModelTitleForAgent` /
  `generateIconFromTitle`, `kickOffLanguageGeneration`,
  `runFanMetaBatch`, and every `start*FanOut` / `pick*` /
  `restart*` Find-alternative helper.
- `viewmodel/ReportViewModelHelpers.kt` — `providerHost` +
  related pure helpers used by the icon flows.
- `ui/report/manage/IconLookup.kt` — the unified `IconLookupScreen`
  (Find-alternative + Manual edit + Select icon) and
  `IconLookupContext`.
- `ui/report/manage/EditIconsList.kt` — the **Edit icons** list of
  every icon in the report.
- `assets/internal-prompts/workers/` + `assets/internal-prompts/alt/`
  — the bundled worker + Find-alternative prompt definitions.
