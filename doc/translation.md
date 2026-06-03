# Translation

The Translate flow is a `SecondaryKind` (`TRANSLATE`, one of the
seven kinds in `data/SecondaryModels.kt`) that operates on a
finished report's content — translating the original prompt, every
successful agent response, and any chat-type Meta rows in scope
into one or more target languages, fanning out one API call per
(source × language) pair. It is owned by `TranslationRunManager`
(`viewmodel/TranslationRunManager.kt`), reached from
`ReportViewModel` as `reportViewModel.translation`.

## Triggering a Translate run

From the result-phase Actions row on a finished report, tap
**Translate** (`onTranslate` → `showTranslateLanguagePicker`). The
flow:

1. **Language picker** (`ui/report/other/LanguageSelection.kt`,
   `LanguageSelectionScreen`) — pick one or more target languages
   from a comprehensive English-name list (with native renderings as
   subtitles, e.g. "Dutch / Nederlands"). Multi-select.
2. **Scope picker** (`ui/report/manage/SecondaryScope.kt`,
   `SecondaryScopeScreen`) — the same scope screen the chat-type
   Meta runs use:
   - All model reports
   - Top-N from a chosen rerank
   - Manual selection
3. **Model picker** — pick the chat model(s) to do the translating.
4. **Run** — `TranslationRunManager.startTranslation` allocates a
   fresh `runId`, snapshots the report's translatable items, and
   fires the runner. Each settled item writes a `SecondaryResult`
   with `kind = TRANSLATE`. Every item is **persisted as it
   settles** (`saveOneTranslationItem`), not in one bulk flush at
   the end, so a crash mid-run keeps the completed translations.

Up front, `startTranslation` writes one empty placeholder
`SecondaryResult` per planned item (stamped with its eventual
`persistedRowId`). That on-disk target list is what
`startMissingTranslations` later diffs against — items added to the
report *after* the run started don't get spuriously translated.

## How the runner schedules work

Translation is often the slowest operation in the app, so the
runner is built around a **shared work queue**, not a fixed
per-model slice:

- All items go into one unbounded `Channel`. Each distinct picked
  `(provider, model)` gets a worker that pulls the next item,
  translates it, and pulls again — so a fast model keeps grabbing
  work instead of idling.
- Each provider host gets its own `Semaphore` sized from
  `ProviderThrottle.limitsFor(host)`, so a multi-model run on
  (OpenAI + Anthropic + Google) doesn't bottleneck one host on
  another's rate limit. Workers run with
  `ProviderThrottle.permitPreAcquired = true`.
- A failed call is **non-terminal**: the item is re-queued and
  retried on a *different* model (tracked per-item in `triedBy`),
  up to a small attempt budget, before `finalizeTranslationError`
  marks it `ERROR`. With more than three distinct models the
  inline OkHttp 429/529 retry loops are suppressed
  (`ProviderThrottle.suppressInlineRetry`) because cross-model
  requeue recovers faster than a same-model back-off sleep; the
  long-Retry-After bench check still parks a >1h-rate-limited model
  on `ModelCooldownStore`.

### Cost-vs-speed mode

The L1 screen exposes a per-run **Speed | Mixed | Cost** chip
(`TranslationMode`, persisted per-`runId` via
`data/TranslationModeStore`, prefs file `translation_modes` —
which is *not* included in backups). It tunes a cost-aware
hesitation each worker applies before pulling its next item:

- **Cost** (default): full bias — penalty
  `≈ (myAvg / cheapest − 1) × 100ms`, capped at 120 s. Expensive
  models pull only what cheap ones can't keep up with.
- **Mixed**: softened bias (multiplier 20, cap 5 s) — still favours
  cheap models but keeps expensive ones engaged.
- **Speed**: no hesitation — every model pulls as fast as its
  per-host caps allow. Highest throughput, highest spend.

The knob is mutable mid-run: workers re-read it before each pull,
so flipping it takes effect on the next item (in-flight calls keep
running). The chips are hidden once the run has nothing left to
schedule.

## Multiple concurrent translation runs

Each Translate batch gets its own `translationRunId` (a UUID shared
by every row of one click). The Result screen renders one aggregate
"run" row per batch under the Translations block, expandable into
the individual per-(source, language) rows.

`TranslationRunManager` tracks active batches in a
`MutableStateFlow<Map<String, TranslationRunState>>` (`_translationRuns`)
keyed by `runId`, with a sibling
`ConcurrentHashMap<String, Job>` (`translationJobs`). Firing off a
second Translate batch while the first is still in flight doesn't
overwrite the first's progress state, and `cancelTranslation(runId)`
can target one specific run.

## What gets translated

For each selected language, one TRANSLATE call is made per:

- **The prompt** — `translateSourceKind = "PROMPT"`,
  `translateSourceTargetId = "prompt"`.
- **Each in-scope agent response** (`SUCCESS` + non-blank body) —
  `translateSourceKind = "AGENT"`,
  `translateSourceTargetId = agent.agentId`.
- **Each in-scope chat-type Meta result** (`kind = META`, non-blank
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

Two `InternalPrompt` rows in the **`internal`** category drive the
substitution (both seeded from `assets/internal-prompts/<lang>/internal/`
and delta-merged into existing installs on launch, editable via
Settings → AI Setup → Internal prompts → **Other internal prompts**):

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
by `@TITLE@`. If the `translate-title` row hasn't been delta-merged
yet, the runner falls back to a hard-coded
`DEFAULT_TRANSLATE_TITLE_TEMPLATE` mirroring that asset.

Parameter / system-prompt resolution for the translation call goes
through the shared `resolveSecondaryParams` (see
[parameters.md](parameters.md)); the `translate-text` prompt seeds
`*NONE` for both, so by default the call carries no preset and the
App-wide fallbacks apply.

## Multi-language fan-out for chat-type Meta runs

When a report already has TRANSLATE rows, **any chat-type Meta
prompt** can also be run across the present languages. The scope
screen carries a `SecondaryLanguageScope`
(`data/SecondaryScopes.kt`: `AllPresent`, or `Selected(languages: Set<String>)`).

The implementation is **not** an N×M fan-out of independent META
rows. To save spend, `runMetaPrompt` runs the meta **once in a
single "seed" language** (preferring Original when it's in the
selection, else the first non-original language), producing **M**
META rows — one per model pick — in that seed language. It then
**cross-translates** each completed seed META row into every other
selected language by *appending* cross-translation items onto those
languages' existing translation runs
(`translation.addCrossTranslationItems`). The seed run pulls its
`@QUESTION@`, `@RESULTS@`, and `@TITLE@` from the matching
per-language TRANSLATE rows (falling back to the original text
per-item if a translation is missing).

So asking for a "Compare" in three languages from two models gives
you **two** seed-language META rows plus their cross-translations in
the two other languages — not six independent Compare calls. Errored
seed rows are skipped (nothing useful to cross-translate). Every row
carries the same `metaPromptName`, so the UI and exports group them
under the user-given name regardless of language.

## UI screens

- **`LanguageSelectionScreen`** (`ui/report/other/LanguageSelection.kt`)
  — multi-select language picker, feeds into the Translate flow.
- **`SecondaryResultsScreen`** (`ui/report/manage/view/Secondary.kt`,
  help `secondary_list`) — list of every secondary row on the
  report, scoped to whichever Meta-prompt name (or structured kind:
  Rerank / Moderation / Translate) the user tapped on the View row.
  The Translations branch groups rows by `translationRunId`; each
  group surfaces as a single "run" row with the model name(s), the
  language list, and the count.
- **`TranslationL1Screen` / `TranslationRunScreen`**
  (`ui/report/manage/TranslationL1.kt` / `TranslationRun.kt`, help
  `translation_run_l1`, title "Translation") — drill into a run.
  Above a per-model progress list it shows the **Speed | Mixed |
  Cost** mode chips (while work remains), a stats panel (Total /
  Done / Errors / Bench / Run / Throttled / Queue / Costs), a
  **Translation workers ↔ Translation types** grouping toggle, and
  whole-run failure controls: **Remove failed**, **Restart failed**,
  **Remove benched**, and **Redo every entry**. `TranslationL2Screen`
  (`TranslationL2.kt`, help `translation_run_l2`) is the per-model
  sub-drill.
- **`TranslationL3Screen`** (`ui/report/manage/TranslationL3.kt`,
  help `translation_run_l3`, title "Translation call") — one
  specific TRANSLATE row, with the source text, target language,
  model, full translated body, and a raw HTTP trace (🐞) link. The
  legacy-row trace fallback filters `ApiTracer.getTraceFiles()` by
  `reportId`, `model`, and `category.startsWith("translate")`
  (translation categories are `translate/...`-prefixed, e.g.
  `translate/model_response`), taking the newest — so a row
  reconstructed from disk without its own `traceFile` still gets a
  working 🐞 link.
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
  (`HtmlLanguageView`) plus a `source/` folder with the originals;
  cross-anchored links navigate between languages.

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

Settings → AI Setup → Internal prompts → **Other internal prompts**
lists the fixed-name `internal`-category templates (`chat-title` /
`model-info` / `model-intro` / `translate-text` / `translate-title`
/ `second-rerank` / `second-moderation` / `test-model`). Edit the
`text` field of the `translate-text` row (bodies) or
`translate-title` row (titles); the name is not user-editable for
these fixed-list templates. Defaults are seeded from
`assets/internal-prompts/` on a fresh install and delta-merged on
later launches; existing entries are never overwritten by a re-seed
unless the user runs Housekeeping → Reset → **"back to
assets/internal-prompts/"**, which drops every internal prompt and
reloads the bundled tree fresh.

## See also

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
