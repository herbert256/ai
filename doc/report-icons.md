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

Both flows are gated by the grand-master **Generate metadata &
icons** switch (`metadataEnabled`, Settings → Metadata & icons,
under the *Generation & behaviour* section) folded with their own
sub-toggle:

- `iconGenEnabled` (`Generate report icon`, default true) — the
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
agent. Each entry is one of **four kinds** — a **Model**
(provider + model), an **Agent** (by name), a **Flock**, or a
**Swarm** — resolved via `Settings.resolveWorker` and expanded
by `Settings.expandWorker` before the run. A Flock contributes
one candidate per member agent and a Swarm one per
`(provider, model)` member, so each member joins the chain as
its own independent fallback (own cooldown key, own cost
attribution).

In the bundled prompts every worker entry is a single **Swarm
named `"workers"`** (see [The bundled worker swarm](#the-bundled-worker-swarm)
below), which expands into five cheap-provider candidates. That
swarm — not an inline model list baked into each prompt — is the
shared default fallback chain.

`WorkerRunner.run`:

- **Shuffles** the worker order each call — the primary pick (and
  the fallback order after a miss) is **random**, not a
  deterministic rotation.
- On a **429** parks that worker on a short local cooldown
  (`Retry-After`, else `WORKER_429_DEFAULT_MS` = 5 s, declared in
  `data/RateLimitRetry.kt`) and tries the next; it also skips a
  worker whose model is benched in the global
  `ModelCooldownStore`.
- On a **404 / 410** (model does not exist / gone) takes that
  worker out of rotation for the rest of the session
  (`disabledWorkers`) — a retired or mistyped model won't come
  back mid-run, so re-picking it just burns a call. Unlike the
  429 cooldown this never expires until the `WorkerRunner` is
  recreated.
- Takes an `accept` validator so a 200 OK with **no usable
  artifact** (no parseable emoji / no non-blank title / no
  `language:` line) is a **logical miss** — the chain advances to
  the next worker instead of returning a hollow success.
- Calls `AnalysisRepository.analyzeWithAgent` with `retry =
  false` (the engine owns the fallback; the shared OkHttp stack
  still applies the per-provider throttle).
- Returns `WorkerOutcome.Success` / `AllRateLimited` / `Failed`.

`runWorkerBatch` is the reusable batched form: it runs a list of
items through the chain under the shared `ApiCallCaps.workers`
cap in `dynamicHost` mode (each worker call self-throttles its
own provider host). No feature calls it yet — the batched
icon/title flows (e.g. the per-pair Fan-Meta batch,
`runFanMetaBatch`) drive `runThrottledBatch` directly with their
own sub-cap (`ApiCallCaps.fanMeta`) rather than going through
`runWorkerBatch`.

**Per-report worker config** — the report's `workerConfig` (picked
on "Report - select workers", see [workers.md](workers.md)) reroutes
the metadata flows: Report info = `CUSTOM` swaps the report icon /
short+long title / language prompts' chains for the per-report group
(`withReportInfoWorkers`); Model info = `OWN_MODEL` has each answer
model write its own title + icon (`withOwnModelWorker` →
`singleModelWorker`). The `WorkerRunner` shuffle still applies
within a multi-member group. See
[datastructures.md](datastructures.md).

## The bundled worker swarm

The `workers`-category prompts and the `alt`-category
Find-alternative prompts all point their worker list at one
Swarm named **`workers`**, seeded from `assets/workers/swarms/`
by `data/SwarmSeed.kt` (delta merge by case-insensitive name, so
a user-edited swarm is left alone). On the bundled chain its five
members are the cheap-provider fallback:

| Provider | Model |
|---|---|
| Mistral | `mistral-medium-latest` |
| OpenAI | `gpt-4o-mini` |
| Groq | `llama-3.3-70b-versatile` |
| Cerebras | `gpt-oss-120b` |
| DeepSeek | `deepseek-v4-flash` |

The only other bundled swarms `SwarmSeed` seeds are the `Level 1`
/ `Level 2` / `Level 3` reasoning swarms (`level-1.json` …
`level-3.json`); there is **no** separate `tournament` swarm. The
Tournament / Judges / Compare worker judging runs through the
bundled `tournament` *prompt*, whose worker list points at this
same `workers` swarm. To change which models back the
icon/title/language metadata, edit the `workers` swarm under
Settings → AI Setup → Workers → Swarms, or re-point an individual
prompt's worker list under Settings → AI Setup → Prompt management
→ Internal prompts.

## The bundled worker + alt prompts

Seeded from the base-language tree `assets/internal-prompts/English/`
(English is the only bundled language —
`InternalPromptSeed.BASE_LANGUAGE`; other languages are per-report
translation *overlays* generated into `PromptTranslationStore`, not
separate seed sets) on every app start — a delta merge that only
adds missing entries by `(category, name)`, so user edits survive.
The auto-generation prompts live under category `workers`; their
Find-alternative variants under `alt`:

| Name | Category | Substitutions | Used by |
|---|---|---|---|
| `report-icon` | `workers` | `@TITLE_LONG@` | per-report icon |
| `model-icons` | `workers` | `@TITLE@` | per-model icon (from the model title) |
| `report-title-short` | `workers` | `@PROMPT@` | report short title (≤25 chars) |
| `report-title-long` | `workers` | `@PROMPT@` | report long title (≤50 chars) |
| `model-titles` | `workers` | `@RESPONSE@` | per-model title |
| `report-language-name` | `workers` | `@PROMPT@` | detect language (`language:` reply) |
| `report-language-icon` | `workers` | `@LANGUAGE@` | flag/language emoji |
| `fan-meta` | `workers` | `@PROMPT@` | per-fan-out-pair title + icon (one `title:` / `icon:` reply) |
| `second-meta` | `workers` | `@NAME@`, `@TITLE@` | meta-prompt (secondary-row) icon cache |
| `translation-icon` | `workers` | `@LANGUAGE@` | per-language translation icon |
| `user-note` | `workers` | `@PROMPT@` | user-note worker helper |
| `tournament` | `workers` | `@QUESTION@`, `@RESPONSE_A@`, `@RESPONSE_B@` | tournament / judges / compare match judging |
| `main` | `alt` | `@PROMPT@` | Find-alternative report icon |
| `report` | `alt` | `@PROMPT@`, `@RESPONSE@` | Find-alternative per-agent icon |
| `fan_out` | `alt` | `@QUESTION@`, `@SOURCE_RESPONSE@`, `@META_PROMPT@`, `@RESPONSE@` | Find-alternative fan-out-pair icon |
| `language` | `alt` | `@LANGUAGE@` | Find-alternative language icon |
| `meta` | `alt` | `@NAME@`, `@TITLE@` | Find-alternative meta-row icon |
| `translation` | `alt` | `@LANGUAGE@` | Find-alternative translation icon |
| `report_title` / `report_title_long` | `alt` | `@PROMPT@` | Find-alternative report short / long title |
| `model_title` | `alt` | `@RESPONSE@` | Find-alternative model / fan-out-pair title |

The `workers` category holds more than the icon/title/language/meta
prompts above — `fan-in`, `second-rerank`, `second-moderation`,
`translate-text` / `translate-title` / `translate-rank`, and the
`find-translation` model-resolver holder back the secondary and
translation flows documented in
[secondary-results.md](secondary-results.md) /
[translation.md](translation.md). They share the same `workers`
swarm and worker engine.

Every one of these prompts ships with its worker list set to the
single `workers` swarm above. There is **no per-prompt
DeepSeek-pinned variant** — the Find-alternative `alt/*` prompts
draw on the same shared chain when launched without a model pick,
and run against the chosen `(provider, model)` pairs otherwise
(see [Find alternative icons](#find-alternative-icons)).

## Per-report icon flow

`IconGenerationManager.kickOffIconGeneration` runs after the
report title attempt (the icon is derived from the long title):

1. Bail when `reportIconOn()` is false.
2. Read the `workers/report-icon` prompt; bail if missing or no
   worker resolves.
3. Read the report fresh from disk and feed its **long title**
   (fall back to short title, then the prompt) as `@TITLE_LONG@`.
4. Launch on `viewModelScope.launch` —
   `withTracerTags(reportId, category = "report/icon")` so the
   call's trace surfaces on the report's Trace screen, and push
   `"<reportId>|icon"` onto the running-info-jobs set so the
   Get-info screen shows the icon job in flight.
5. `WorkerRunner.run` makes the call(s), accepting only a reply
   that yields a parseable emoji.
6. **Always normalise to exactly one emoji.**
   `extractFirstEmoji` (`data/EmojiExtract.kt`) walks the
   response with `BreakIterator.getCharacterInstance()` and
   returns the first grapheme cluster whose lead codepoint sits
   in a known emoji block (Misc Symbols / Pictographs, Transport,
   Supplemental Symbols, Dingbats, Misc Technical, Geometric
   Shapes, Misc Symbols & Arrows, Regional Indicators, Mahjong /
   Domino / Playing cards, Enclosed Alphanumeric Supplement) —
   plus keycap emoji recognised by the COMBINING ENCLOSING KEYCAP
   mark. ZWJ sequences the system font can't render as one glyph
   are trimmed to the lead emoji. A 200 OK whose body contains
   **no parseable emoji is a logical miss**: the `WorkerRunner`
   chain treats it like a transport miss and advances to the next
   worker (via the `accept` validator —
   `extractFirstEmoji(...) != null`), instead of silently
   accepting an empty reply. Only when *every* worker fails to
   produce an emoji does the row settle. The worker-engine paths
   set `iconErrorMessage` (`"icon-gen: no worker produced an
   icon"`, or `"icon-gen: all workers rate-limited"`) so the row
   renders ❌. `📝` (`MetadataIconsHolder.current.reportIcon`,
   `MetadataDefaults.REPORT_ICON`) is only a defensive last resort
   if a Success reply somehow yields no emoji. The same
   logical-miss rule applies to the title / language / fan-meta
   worker prompts (non-blank title / parseable `language:` line /
   `title:`-or-`icon:` line respectively).
7. Cost is computed from `PricingCache.getPricing(provider,
   model)` × `(inputTokens, outputTokens)` for the **winning
   worker's** model and persisted onto `Report.iconInputCost` /
   `iconOutputCost` (plus tokens) via `updateReportIcon`
   (`promptUsed = "main"`). A `kind="icon"` row is posted to the
   global `UsageStats` ledger, attributed to the worker that
   actually billed.
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

- **title + icon on** → `runModelTitleForAgent(storeTitle = true,
  thenIconFromTitle = true)` generates and stores the model title
  (`workers/model-titles`, `@RESPONSE@`, trace category
  `model/titles`), then chains the icon from it.
- **icon only** → `runModelTitleForAgent(storeTitle = false,
  thenIconFromTitle = true)` still generates a title transiently
  (the icon prompt needs it) but never stores or surfaces it —
  its spend is folded onto the agent's icon cost instead of being
  dropped.
- **title only** → just the title; no icon.

`generateIconFromTitle` then runs `workers/model-icons`
(`@TITLE@` = the model title) through the worker engine:

- Resets the agent's icon fields + `iconCalls` first
  (`clearReportAgentIconState`) so a regenerate replaces rather
  than accumulates.
- Tags traces with `withTracerTags(reportId,
  category = "model/icons")`.
- Accepts only a reply with a parseable emoji; otherwise the
  agent is left icon-less (no further fallback).
- On success writes the emoji via
  `setReportAgentIconAndTier(winningTier = null,
  promptUsed = "report_title_icon")` and records the winning
  worker's spend as an `IconCallRecord` (`tier = 2`,
  `type = "model/icons"`) + `UsageStats` post with `kind="icon"`,
  attributed to the actual worker's `(provider, model)`.

> The legacy response-based **3-tier chain**
> (`runReportIconsForAgent` + `runTier1/2/3` + `commitChainResult`,
> on `icons/report_1/2/3`) has been **removed** — per-model icons
> now come solely from the worker engine. As a result,
> `ReportAgent.iconWinningTier` is now always `null`. Several
> KDoc / inline comments inside `IconGenerationManager.kt` still
> mention a "3-tier chain" fallback; those comments are stale and
> do not reflect the current code path.

## Find alternative icons

Bottom button on the **Icon lookup** detail screen (reached by
tapping any icon-row glyph, e.g. from **Edit icons**). Opens a
model picker; the user picks any number of `(provider, model)`
pairs. Each scope runs its own self-contained `alt/*` template
**directly against the picked models** (via `analyzeWithAgent`,
not the random worker chain — the user chose the models on
purpose).

Whether the picker appears is governed by the alt prompt's own
**model-selection mode** (`altPromptModelSelection`): `*SELECT`
forces the picker every run, while `*CONFIGURED` skips it and lets
`altWorkerModels` resolve the `alt/*` prompt's worker list (the
`workers` swarm) to seed the candidates instead. The user can also
**edit the resolved prompt before picking** — the pre-pick "Edit
prompt" editor stashes its result in `pendingAltEdit`
(`AltEditPayload`), a one-shot consumed by the next `start*FanOut`
call.

| Launched from | Template | Substitutions | Commit (`promptUsed`) |
|---|---|---|---|
| report icon (Hub / Edit) | `alt/main` | `@PROMPT@` | `Report.icon` (`"main_alt"`) |
| per-agent icon | `alt/report` | `@PROMPT@` + `@RESPONSE@` | `ReportAgent.icon` (`"report_alt"`) |
| language icon | `alt/language` | `@LANGUAGE@` | `Report.languageIcon` (`"language_alt"`) |
| meta-row icon | `alt/meta` | `@NAME@` + `@TITLE@` | the row / cache (`"meta_alt"`) |
| fan-out-pair icon | `alt/fan_out` | `@QUESTION@` / `@SOURCE_RESPONSE@` / `@META_PROMPT@` / `@RESPONSE@` | the pair row (`"fan_out_alt"`) |
| translation icon | `alt/translation` | `@LANGUAGE@` | the cache (`"translation_alt"`) |
| report title (short/long) | `alt/report_title` / `alt/report_title_long` | `@PROMPT@` | `Report.title` / `titleLong` |
| model / pair title | `alt/model_title` | `@RESPONSE@` | the agent / pair (`"model_title_alt"`) |

Each fan-out (`startIconFanOut` / `startAgentIconFanOut` /
`startPairIconFanOut` / `startLanguageIconFanOut` /
`startReportTitleFanOut` / `startModelTitleFanOut` /
`startPairTitleFanOut` / `startTranslationIconFanOut` /
`startInternalPromptIconFanOut`):

- Dedupes picks by `"providerId:model"`.
- Pre-populates `IconCandidate.Running` rows so the Alternative
  icons screen shows ⏳ for every pair the moment it opens, before
  any throttle permit is acquired.
- One async per pick; each pre-acquires the per-provider throttle
  permit (`ProviderThrottle.acquire` with `permitPreAcquired =
  true`, so the OkHttp interceptor skips its own acquire and
  doesn't double-count) and runs `analyzeWithAgent` under its own
  `alt/*` trace category.
- Bumps the matching cost field **regardless of success** (the
  user paid for the call either way) and appends an
  `IconCallRecord` carrying the `alt/*` prompt name in `type`;
  the matching `IconCandidate` flips to `Done` (resolved emoji +
  cost) or `Error` (reason + cost).
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

`IconCallRecord.attributedToSecondaryId` links `meta_alt` /
`translation_alt` spend to the tapped `SecondaryResult` so the
cost table can subtract the attributed portion from that row's
own cost cell, avoiding a double-count once the per-call alt rows
are listed below it.

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

Reached from the report result page's bottom-bar ℹ️ icon (the
**View** tile grid) → **Icons** tile (`ui/report/view/Icons.kt`,
`IconsViewScreen`). Minimal
grid of every successful per-agent icon, with the per-report icon
rendered large and centred at the top. Tapping a glyph opens that
agent's **Model response** detail; backing out returns to the grid
(not the result screen — overlay back-stack rule,
`feedback_overlay_back_stack`).

Help topic: `icons_view`. The glyphs lay out in a fixed-size
`FlowRow` (each tile a round 56 dp button), wrapping onto extra
rows as needed.

## Costs surface

Per-icon spend surfaces in three places:

1. **In-app View → Costs** — a `Buckets` ⇄ `Models` toggle
   (the per-call "All API calls" drill-in is deliberately
   omitted in-app). The per-model icon call rolls into the
   "Icons" bucket and into each agent's provider/model split;
   the per-report icon and every Find-alternative `alt/*` call
   fold into the same rollups.
2. **HTML export — Complete and Costs views** — the Costs view
   has `Types` / `Models` / `All` scope tabs, including the
   per-call `All` rendering where each attempt (per-model icon,
   per-report icon, every Find-alternative `alt/*` call) shows as
   its own row. The icon row in the Complete view shows the
   agent's effective icon + cost.
3. **Global AI Usage** — the per-report and per-model icon calls
   post with `kind="icon"`, attributed to the model that actually
   ran. The report **language** icon posts under a distinct
   `kind="language-icon"` (its sibling language-*detection* call is
   `kind="language"`, and titles are `kind="title"`). All are
   filterable in the Usage screen.

`Report.costsFromDeletedItems` (bumped on per-agent / secondary /
fan-out deletions) surfaces as its own line above the Total when
non-zero — so the user sees what the API actually billed for the
run, even after trimming rows. On per-agent delete the rollover
now takes `max(agent icon field, structured icon-call rows of
type null / model/icons / alt/report)`, because some
secondary-attributed `alt/report` rows leave the agent's
`iconInputCost` / `iconOutputCost` at `$0` (see `removeAgent` in
`ReportStorage.kt`).

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
    var iconTraceFile: String? = null,
    var iconModel: String? = null,           // "<providerId>/<modelId>" when alt-picked
    var iconPromptUsed: String? = null,
    var iconDurationMs: Long? = null,
    var iconCalls: MutableList<IconCallRecord> = mutableListOf(),   // per-AGENT icon-call rows live here, keyed by agentId
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
    var iconTraceFile: String? = null,
    var iconWinningTier: Int? = null,        // always null now — set only by the removed 3-tier chain; manual / alt / worker-engine icons leave it null
    var iconPromptUsed: String? = null
    // NB: ReportAgent has NO iconModel / iconDurationMs / iconCalls of its own —
    // the per-agent icon-call audit rows live on Report.iconCalls (keyed by agentId)
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
    val durationMs: Long? = null,
    val success: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val type: String? = null,                // "model/icons", "alt/main", "alt/report", … (null = legacy agentId classifier)
    val attributedToSecondaryId: String? = null
)
```

## Files

- `data/EmojiExtract.kt` — `extractFirstEmoji` (grapheme-cluster
  emoji-block scan + keycap + ZWJ sanitiser).
- `data/ReportModels.kt` — `Report` + `ReportAgent` +
  `IconCallRecord` + `ReportApiCallCost` data classes.
- `data/ReportStorage.kt` — the icon writers: `updateReportIcon` /
  `updateReportIconError` / `setReportIconChoice` /
  `setReportAgentIconAndTier` / `setReportAgentIconChoice` /
  `clearReportAgentIconState` / `bumpReportIconCost` /
  `bumpReportAgentIconCost` / `appendIconCall`, plus `removeAgent`'s
  icon-cost rollover.
- `data/SwarmSeed.kt` — seeds the bundled `workers` (and
  `tournament` / `Level 1-3`) swarms from `assets/workers/swarms/`.
- `viewmodel/WorkerRunner.kt` — the random-pick / 429-fallback
  worker engine (`WorkerOutcome`, `run`, `runWorkerBatch`).
- `viewmodel/IconGenerationManager.kt` — `kickOffIconGeneration`,
  `runPerModelEnrichment` / `runModelTitleForAgent` /
  `generateIconFromTitle`, `kickOffLanguageGeneration`,
  `runFanMetaBatch`, and every `start*FanOut` / `pick*` /
  `restart*` Find-alternative helper.
- `viewmodel/ReportViewModelHelpers.kt` — `providerHost` +
  related pure helpers used by the icon flows.
- `ui/report/view/Icons.kt` — the **View → Icons** grid
  (`IconsViewScreen`).
- `ui/report/manage/IconLookup.kt` — the unified `IconLookupScreen`
  (Find-alternative + Manual edit + Select icon) and
  `IconLookupContext`.
- `ui/report/manage/EditIconsList.kt` — the **Edit icons** list of
  every icon in the report.
- `assets/workers/swarms/` — the bundled `workers` swarm
  definition.
- `assets/internal-prompts/English/workers/` +
  `assets/internal-prompts/English/alt/` — the bundled worker +
  Find-alternative prompt definitions (`.json` metadata + `.txt`
  body). English is the only bundled language
  (`InternalPromptSeed.BASE_LANGUAGE`); the per-language directory
  level exists for translation overlays, which only English ships.
- `data/InternalPromptSeed.kt` — the `(category, name)` delta-merge
  loader for the `workers` / `alt` prompt trees.
