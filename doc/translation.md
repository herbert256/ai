# Translation

The Translate flow is a `SecondaryKind` (`TRANSLATE`, one of the
eight kinds in `data/SecondaryModels.kt` — `RERANK`, `META`,
`MODERATION`, `TRANSLATE`, `TOURNAMENT`, `JUDGES`, `COMPARE`,
`TRANSRANK`) that operates on a finished report's content —
translating the original prompt, every successful agent response,
the report / model / fan-out titles, and every chat-type Meta row
into **one** target language per run, fanning out one API call per
translatable item. Each Translate click is its own run; tap Translate
again to add another language. It is owned by `TranslationRunManager`
(`viewmodel/TranslationRunManager.kt`), reached from
`ReportViewModel` as `reportViewModel.translation`.

A sibling feature, **Rank the translators** (`TRANSRANK`), grades and
ranks which translator model produced the best translation of a run —
see [rank-translators.md](rank-translators.md). The rest of this doc
covers the Translate flow itself.

## Triggering a Translate run

From the result-phase Actions row on a finished report, tap
**Translate** (`onTranslate` → `showTranslateLanguagePicker`, wired
in `GenerationHandlers.kt`). The flow has **no scope picker and no
model picker** — the language picker launches the run directly
(`ui/report/manage/Main.kt`, "Order: language picker → progress
screen"):

1. **Language picker** (`ui/report/other/LanguageSelection.kt`,
   `LanguageSelectionScreen`, title "Pick target language", help
   `translation_language`) — a **single-select** picker over the
   curated `TARGET_LANGUAGES` list (~55 entries; English name as the
   `@LANGUAGE@` key, native rendering on a second line). It has a
   search box and a **Recent** block (`RecentTargetLanguages`, an MRU
   of the last 3 picks persisted in `eval_prefs`). Tapping a row
   confirms one language; pick more languages by re-running Translate.
2. **Worker pick (conditional)** — decided by the report's Worker-batches
   mode (`Report.workerConfig.batches`, see [workers.md](workers.md)):
   `REPORT_MODELS` uses the report's own answer models
   (`reportModelWorkers`) with no picker — optionally round-robin
   scheduled (`workerSelection`); `SELECT_EACH` (or `PROMPT` with the
   driving `translate-text` prompt set to `*SELECT`) shows the
   `RuntimeWorkerPick` overlay ("Translate — pick workers", passed as
   `overrideWorkers`); `SELECT_ONCE` shows it only for the report's
   first type-B batch and then reuses the persisted group. In every
   other case the run goes straight to the configured swarm.
3. **Run** — `TranslationRunManager.startTranslation` allocates a
   fresh `runId`, snapshots the report's translatable items, and fires
   the runner behind a blocking build-stage popup ("Preparing N / M…"
   / "Translating to <language>", keyed by `buildKey`) that covers the
   up-front placeholder persistence, then lands on the Translation L1
   screen. Each item is dispatched through the **translate worker
   swarm** (see below), so the model is chosen by the `WorkerRunner`
   fallback chain rather than the user. Each settled item writes a
   `SecondaryResult` with `kind = TRANSLATE`, attributed to the worker
   that actually answered. Every item is **persisted as it settles**
   (`saveOneTranslationItem`), not in one bulk flush at the end, so a
   crash mid-run keeps the completed translations.

Up front, `startTranslation` writes one empty placeholder
`SecondaryResult` per planned item (stamped with its eventual
`persistedRowId`). That on-disk target list is what
`startMissingTranslations` later diffs against — items added to the
report *after* the run started don't get spuriously translated.

## How the runner schedules work

Translation is a **Mode-B worker-swarm batch** — the same failure
model tournament / fan-meta / icons use. There is no user-picked
model and no per-model work queue:

- Each item is dispatched via `runThrottledBatch(... subCap =
  ApiCallCaps.translation, dynamicHost = true)`, and inside the body
  `runOneTranslation` calls `rvm.workerRunner.run(prompt, resolved,
  …)`. Body kind picks the `workers/translate-text` prompt
  (`@TEXT@`); the four title kinds pick `workers/translate-title`
  (`@TITLE@`). Both reference the shared **`workers`** swarm by
  default (see below).
- The `WorkerRunner` owns model selection and fallback: it shuffles
  the swarm each call, and on a **429** parks that worker on a short
  cooldown and tries the next; on **404/410** it disables the worker
  for the session; a 200 with a blank translation is a logical miss
  that also advances. The call only fails when the whole chain is
  exhausted (`WorkerOutcome.AllRateLimited` / `Failed`), at which
  point `finalizeTranslationError` marks the item `ERROR` with a
  blank provider/model. There is no cross-model requeue or per-item
  attempt budget anymore — the worker chain *is* the retry.
- `dynamicHost = true` means each worker call self-throttles its own
  provider host through `ProviderThrottleInterceptor` (sub → global
  → host order), so a swarm spanning several providers isn't
  bottlenecked on one host's rate limit. The per-item "Batch item"
  ceiling (`withTimeoutOrNull`, `NetworkSettings.batchItemTimeoutSec`,
  default 180 s, Settings → Network) bounds a whole chain so a wedged
  call can't strand the run.
- The winning worker's `(provider, model)` is recorded on the
  `SecondaryResult` (and the live `TranslationItem`) for cost
  attribution and the 🐜 Translation-workers per-model grouping;
  usage posts to AI Usage under the item's per-kind `translate/*`
  type.

## Multiple concurrent translation runs

Each Translate batch gets its own `translationRunId` (a UUID shared
by every row of one click). The Result screen renders one aggregate
"run" row per batch under the Translations block, expandable into
the individual per-(source, language) rows.

`TranslationRunManager` extends the shared `BatchEngine` base
(`viewmodel/BatchEngine.kt`) — the same collaborator the fan-out /
fan-meta / tournament engines share. The base owns the run-state map
(`MutableStateFlow<Map<String, TranslationRunState>>`, exposed here as
`translationRuns`, an alias of the base `runs` flow) keyed by `runId`,
plus the per-run / per-item `Job` registries (`runJobs` / `itemJobs`)
and the deleting-run set. Firing off a second Translate batch while
the first is still in flight doesn't overwrite the first's progress
state, and `cancelTranslation(runId)` can target one specific run.

## What gets translated

A Translate run covers the whole report — there is no scope subset to
pick. For the chosen language, one TRANSLATE call is made per:

- **The prompt** — `translateSourceKind = "PROMPT"`,
  `translateSourceTargetId = "prompt"`.
- **Each successful agent response** (`SUCCESS` + non-blank body) —
  `translateSourceKind = "AGENT"`,
  `translateSourceTargetId = agent.agentId`.
- **Each chat-type Meta result** (`kind = META`, non-blank
  content — a "Compare" / "Critique" / "Synthesize" row, whatever
  the user named the Meta prompt) —
  `translateSourceKind = "META"`,
  `translateSourceTargetId = secondary.id`. Rerank and Moderation
  rows are **never** translated; their content is structured JSON.

### Titles

Four short title fields are also translated, each as its own
TRANSLATE call. These use the **`translate-title`** prompt rather
than the body **`translate-text`** prompt (see below):

- **Report short title** — `translateSourceKind = "TITLE"`,
  `translateSourceTargetId = "title"`.
- **Report long title** (`Report.titleLong`) —
  `translateSourceKind = "TITLE_LONG"`, `translateSourceTargetId = "titleLong"`.
- **Each model response title** (`ReportAgent.modelTitle`, when set) —
  `translateSourceKind = "AGENT_TITLE"`,
  `translateSourceTargetId = agent.agentId`.
- **Each fan-out pair response title** (`SecondaryResult.title` on
  fan-out pair rows, when set) — `translateSourceKind = "FANOUT_TITLE"`,
  `translateSourceTargetId = secondary.id`.

Internally the runner models these with the
`TranslationKind` enum (`viewmodel/TranslationTypes.kt`):
`TITLE`, `TITLE_LONG`, `AGENT_TITLE`, `FANOUT_TITLE` (the four
short-title kinds, `TranslationKind.isTitle`), plus `PROMPT`,
`AGENT_RESPONSE`, and `META` for the long-form bodies. The
persisted `translateSourceKind` string is the on-disk projection of
that enum (`AGENT_RESPONSE` ↔ `"AGENT"`).

### Prompts

Two `InternalPrompt` rows in the **`workers`** category drive the
substitution (both seeded from `assets/internal-prompts/<Language>/workers/`
and delta-merged into existing installs on launch, editable via
Settings → AI Setup → Prompt management → Internal prompts →
**Worker prompts**). Each references the shared **`workers`** swarm
(`assets/workers/swarms/workers.json`) — at time of writing it has 12
members: Mistral `mistral-medium-latest`, OpenAI `gpt-4o-mini`, Groq
`llama-3.3-70b-versatile`, Cerebras `gpt-oss-120b`, DeepSeek
`deepseek-v4-flash`, Google `gemini-3.5-flash`, Anthropic
`claude-haiku-4-5-20251001`, xAI `grok-4.20-0309-non-reasoning`,
Cohere `command-r-08-2024`, DeepInfra `google/gemma-3-12b-it`,
Together `Qwen/Qwen3-235B-A22B-Instruct-2507-tput`, and SiliconFlow
`Qwen/Qwen3-14B`. Re-curate that swarm to change which models
translate:

| Prompt | Used for | Placeholders |
|---|---|---|
| `translate-text` | prompt / agent / meta bodies | `@LANGUAGE@`, `@TEXT@` |
| `translate-title` | the four title kinds | `@LANGUAGE@`, `@TITLE@` |

The bundled body prompt asks for: "Translate the following text to
@LANGUAGE@. Preserve markdown formatting (headings, bold, italic,
lists, code blocks, tables) exactly. Preserve citation references
like [1] or [N]. Preserve URLs and code identifiers untouched. Do
NOT add commentary, preface, or explanation — output only the
translation." followed by `TEXT TO TRANSLATE:` and `@TEXT@`. The
title prompt is terser: "Translate the following text to
@LANGUAGE@, give only the translation back, nothing else." followed
by `@TITLE@`. The main runner needs the `translate-title` row present
(a missing prompt fails the item); the **Find alternative
translation** path additionally falls back to a hard-coded
`DEFAULT_TRANSLATE_TITLE_TEMPLATE` mirroring that asset when the row
hasn't been delta-merged yet.

Like every other `workers`-category prompt, the translate prompts
run through `WorkerRunner`, which dispatches each worker call with no
explicit parameter / system-prompt preset — so `max_tokens` falls
back to the per-provider `defaultMaxTokens`, the same as the icon /
title / tournament workers.

## Multi-language fan-out for chat-type Meta runs

When a report already has TRANSLATE rows, **any chat-type Meta
prompt** can also be run across the present languages. The scope
screen carries a `SecondaryLanguageScope`
(`data/SecondaryScopes.kt`: `AllPresent`, or `Selected(languages: Set<String>)`).

The implementation is **not** an N×M fan-out of independent META
rows. To save spend, `runMetaPrompt` runs the meta **once in a
single "seed" language** (preferring Original when it's in the
selection, else the first non-original language) via the Meta
worker swarm, producing **one** META row in that seed language —
Meta has no user-picked model anymore (it went through the same
Mode-B worker-swarm conversion as Translate: a fallback chain, not a
per-model pick). It then **cross-translates** that completed seed
META row into every other selected language by *appending*
cross-translation items onto those languages' existing translation
runs (`translation.addCrossTranslationItems`). The seed run pulls
its `@QUESTION@`, `@RESULTS@`, and `@TITLE@` from the matching
per-language TRANSLATE rows (falling back to the original text
per-item if a translation is missing).

So asking for a "Compare" in three languages gives you **one**
seed-language META row plus its cross-translations in the two other
languages — not three independent Compare calls. An errored seed row
is skipped (nothing useful to cross-translate). Every row carries the
same `metaPromptName`, so the UI and exports group them under the
user-given name regardless of language.

## UI screens

- **`LanguageSelectionScreen`** (`ui/report/other/LanguageSelection.kt`)
  — single-select language picker (search + Recent MRU), feeds into
  the Translate flow.
- **`SecondaryResultsScreen`** (`ui/report/manage/view/Secondary.kt`,
  help `secondary_list`, title "Secondary results") — list of every
  secondary row of one `kind` (Rerank / Moderation) or every row
  sharing one multi-language chat-type Meta-prompt name. Translate
  runs don't route through this screen — a Translate tile / row
  always opens straight into `TranslationRunScreen` via
  `onOpenTranslationRun(runId)`.
- **`ReportTranslationsScreen`** (`ui/report/manage/Translations.kt`,
  help `report_translations`, title "Translations") — reached from
  the Manage hub's 🌐 bottom-bar icon. A plain, static list: the
  **Original** language row first (returns to the report), then one
  row per finished translation run (`TranslationRunSummary`, built by
  `buildTranslationRunSummaries` — one per `translationRunId`,
  carrying the target language, the run's (first-item) model, the
  call count, and cost), with any still-running runs shown above them
  with a green progress bar. Tapping a run row opens
  `TranslationRunScreen`; the 🆕 action starts a new translation (the
  same language-picker flow as [Triggering a Translate
  run](#triggering-a-translate-run)).
- **`TranslationL1Screen` / `TranslationRunScreen`**
  (`ui/report/manage/TranslationL1.kt` / `TranslationRun.kt`, help
  `translation_run_l1`, title "Translation") — drill into a run. L1
  lists translation **types** (per trace/cost-type rows, e.g.
  `model_response`, `report_prompt`). Above the list it shows the
  shared `BatchStatsRow` panel (Total / Done / Error / Run / Wait /
  Queue / Costs — worker-pool batch, so there is no Bench bucket;
  failed items stay normal failed rows). The title-bar actions are
  👁 **View**, 🐜 **Translation workers**, 🏅 **Rank the translators**,
  🔄 **Redo every entry** (deletes every row and re-dispatches the
  full set), 🐞 **trace**, and 🗑 **delete run**. The per-model
  grouping moved off L1 into its own **`TranslationWorkersScreen`**
  (same file, help `translation_workers`, title "Translation
  workers"), reached via the 🐜 action. Failure recovery (restart /
  remove failed, continue broken) is handled by the shared Broken-work
  batch screen, not by L1 buttons. `TranslationL2Screen`
  (`TranslationL2.kt`, help `translation_run_l2`) is the per-group
  sub-drill (works in either Types or Workers mode).
- **`TranslationL3Screen`** (`ui/report/manage/TranslationL3.kt`,
  help `translation_run_l3`, title "Translation call") — one
  specific TRANSLATE row, with the source text, target language,
  model, full translated body, a **Find alternative translation**
  button (see below), and a raw HTTP trace (🐞) link. The 🐞 link
  uses only the row's own captured `traceFile` (`item.traceFile`) —
  there is no longer a category-scan fallback, so a legacy row written
  before that field existed has no trace link.
- **`TranslationCompareScreen`** (`ui/helpers/TranslationCompare.kt`,
  help `translation_compare`, title "Translation compare") —
  side-by-side comparison of the same source across translations.
  On the Secondary-detail / agent / prompt screens it is driven live
  by the title-bar 🌐 compare icon (`onTranslationCompare`) when a
  translation of the result is in scope — there is no stored
  `translatedFromSecondaryId` back-pointer; the picker-driven live
  compare is the only path.

The Translate detail Actions card uses the layout setting (Model
only / Provider and model) to derive row labels, and pending / live
translation rows on the Report Result are clickable.

## Find alternative translation

From a Translation-call (L3) screen, **Find alternative translation**
re-translates that one item's source text on each model the user
picks (`AltTranslateTarget` hoists the item identity to the
report-manage screen so the shared model picker + candidate screen,
`ui/report/manage/FindAlternativeTranslations.kt`, render over the
yielded run screen). It mirrors the Find-alt icon / title fan-out:
`TranslationRunManager.startAltTranslationFanOut` fires one
non-persisting probe call per picked model, collecting candidates in
`AppViewModel.altTranslationByItem`; tapping a candidate calls
`applyAltTranslation`, which overwrites that item's persisted
TRANSLATE row in place (content + model + cost + trace + duration).
Only the picked candidate lands on disk — the probe spend still shows
on AI Usage under the item's `translate/*` type.

## Viewing a translated report

A translated report is **the same `Report` object** as the
original — translations are stored as TRANSLATE secondaries, not as
a copy. The Result screen (`ui/report/view/Main.kt`) surfaces:

- A language pulldown at the top — switching it re-renders agent
  bodies and chat-type Meta rows from the matching TRANSLATE rows
  for that language.
- **Titles follow the active language too.** `ViewTitleBar`
  (`ui/report/view/helpers/ViewTitleBar.kt`) swaps the orange report
  title to its `TITLE_LONG` (or `TITLE`) translation when given a
  `reportId` + non-blank `activeLanguage`; the green model-response
  card title (`Agent.kt`, `ModelReportCard`) uses the `AGENT_TITLE`
  translation, and the fan-out responder card title (`Fan.kt`,
  `FanOutResponderCard`) uses `FANOUT_TITLE`. Each falls back to the
  original when no translation row exists for the active language.
- The original (`null`) language option always renders the
  untranslated content.
- **"Language missing" popup** — tapping an item that has no
  translation for the active language opens a picker; choosing a
  source language collects `TranslateMissingItem`s (PROMPT / AGENT /
  META) and calls `onTranslateMissingItems`, which translates just
  those items into the active language (reusing the same runner).
- The Zipped HTML export creates one folder/view per language
  (`HtmlLanguageView`), with the original (untranslated) content in
  its own `original/` folder; cross-anchored links navigate between
  languages.

## Cost tracking & per-kind Type

Each TRANSLATE call carries a **per-kind Type** rather than one flat
`internal/translate`, so traces, the cost table, and AI Usage all
break out by what was translated. The single classifier
`translateTraceType(srcKind, sourceIsFanOut, sourceIsFanIn)`
(`data/SecondaryModels.kt`) maps the row's `translateSourceKind`
(and, for `META`, the source row's `fanOutSourceAgentId` / `fanInOf`,
looked up by `translateSourceTargetId`) to one of:

| Source | Type |
|---|---|
| report prompt | `translate/report_prompt` |
| report title | `translate/report_title` |
| report long title | `translate/report_title_long` |
| model response | `translate/model_response` |
| model title | `translate/model_title` |
| fan-out pair response | `translate/fan/out/response` |
| fan-out pair title | `translate/fan/out/title` |
| fan-in | `translate/fan/in` |
| chat-type meta | `translate/meta` |
| (unrecognised fallback) | `translate/translate` |

(RERANK / MODERATION are never translated, so they have no translate
type.) This string is the **trace category** (set per-item by
`withTraceCategory(item.traceType)` in
`TranslationRunManager.runOneTranslation`, computed at enumeration
via `traceTypeFor` and stored on `TranslationItem.traceType`), the
**AI Usage `kind`** (`updateUsageStatsAsync(..., kind = item.traceType)`),
and the **Report cost-table Type** column (`ContentDisplay` plus the
HTML / Zipped / Word-ODT exports, which resolve the META split by
looking the source row up by `translateSourceTargetId`). The
persisted `translateSourceKind` field itself is unchanged —
display-swap logic still keys on `"AGENT"` / `"META"` / etc. Each
item also captures its trace filename via `withTraceFilenameSink`
so the per-item screens can deep-link a 🐞 straight to the call.

## Editing the translation prompt

Settings → AI Setup → Prompt management → Internal prompts →
**Worker prompts** lists the fixed-name `workers`-category templates,
including `translate-text` and `translate-title` alongside the icon /
title / language / tournament / `translate-rank` workers. Edit the `text` field of the `translate-text`
row (bodies) or `translate-title` row (titles), and edit each row's
**worker swarm** to change which models translate; the name is not
user-editable for these fixed-list templates. Defaults are seeded from
`assets/internal-prompts/` on a fresh install and delta-merged on
later launches; existing entries are never overwritten by a re-seed
unless the user runs Housekeeping → Reset → **"back to
assets/internal-prompts/"**, which drops every internal prompt and
reloads the bundled tree fresh.

## See also

- [rank-translators.md](rank-translators.md) for the **Rank the
  translators** (`TRANSRANK`) batch — the 🏅 flow that scores and
  ranks which translator model produced the best translation of a run.
- [secondary-results.md](secondary-results.md) for the full
  secondary-result lifecycle, prompt resolution, and the
  `@RESULTS@` block.
- [parameters.md](parameters.md) for `resolveSecondaryParams` and
  the secondary-call parameter precedence.
- [datastructures.md](datastructures.md) for the `SecondaryResult`
  translate-only fields, `TranslationRunState` / `TranslationItem`,
  and `SecondaryLanguageScope`.
- [throttle.md](throttle.md) for `ProviderThrottle` per-host caps
  and the bench / cooldown behaviour the runner relies on.
