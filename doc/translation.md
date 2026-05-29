# Translation

The Translate flow is a `SecondaryKind` (`TRANSLATE`) that operates
on a finished report's content — translating the original prompt
and every successful agent response (plus any chat-type Meta rows
in scope) into one or more target languages, fanning out one API
call per (source × language) pair.

## Triggering a Translate run

From the result-phase Actions row on a finished report, tap
**Translate**. The flow:

1. **Language picker** (`ui/report/other/LanguageSelection.kt`,
   `LanguageSelectionScreen`) — pick one or more target languages
   from a comprehensive English-name list (with native renderings as
   subtitles, e.g. "Dutch / Nederlands"). Multi-select.
2. **Scope picker** (`ui/report/manage/SecondaryScope.kt`,
   `SecondaryScopeScreen`) — same scope screen the chat-type Meta
   runs use:
   - All model reports
   - Top-N from a chosen rerank
   - Manual selection
3. **Model picker** — pick the chat model(s) to do the translating.
4. **Run** — fans out one batch per (language, model-pick) and one
   API call per (source × language) within that batch. Each
   `executeSecondaryTask` call writes a `SecondaryResult` with
   `kind = TRANSLATE`. Each item is **persisted as it settles**,
   not in one bulk flush at the end, so a crash mid-run keeps the
   completed translations.

## Multiple concurrent translation runs

Each Translate batch gets its own `translationRunId` (a UUID shared
by every row of one click). The Result screen renders one aggregate
"run" row per batch under the Translations block, expandable into
the individual per-(source, language) rows.

`ReportViewModel` tracks active Translate batches in a
`Map<String, TranslationRun>` keyed by `runId`, so firing off a
second Translate batch while the first is still in flight doesn't
overwrite the first's progress state.

## What gets translated

For each selected language, one TRANSLATE call is made per:

- **The prompt** — `translateSourceKind = "PROMPT"`,
  `translateSourceTargetId = "prompt"`.
- **Each in-scope agent response** — `translateSourceKind = "AGENT"`,
  `translateSourceTargetId = agent.agentId`.
- **Each in-scope chat-type Meta result** (e.g. a "Compare" /
  "Critique" / "Synthesize" row — anything with `kind = META`)
  when included via language scope —
  `translateSourceKind = "META"`,
  `translateSourceTargetId = secondary.id`. Rerank and Moderation
  rows are never translated; their content is structured JSON.

### Titles

Four short title fields are also translated, each as its own
TRANSLATE call. These use the **`translate-title`** prompt rather
than the body `translate-text` prompt (see below):

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

### Prompts

Two `InternalPrompt` rows in the `internal` category drive the
substitution (both seeded from `assets/internal-prompts/` and
delta-merged into existing installs on launch, editable via Settings
→ AI Setup → Prompt management → Other internal):

| Prompt | Used for | Placeholders |
|---|---|---|
| `translate-text` | prompt / agent / meta bodies | `@LANGUAGE@`, `@TEXT@` |
| `translate-title` | the four title kinds | `@LANGUAGE@`, `@TITLE@` |

The default body prompt asks for: "Translate the following text to
@LANGUAGE@. Preserve markdown formatting (headings, bold, italic,
lists, code blocks, tables) exactly. Preserve citation references
like [1] or [N]. Preserve URLs and code identifiers untouched. Do
NOT add commentary, preface, or explanation — output only the
translation." The default title prompt is terser: "Translate the
following text to @LANGUAGE@, give only the translation back,
nothing else." followed by `@TITLE@`.

## Multi-language fan-out for chat-type Meta runs

When a report has TRANSLATE rows, **any chat-type Meta prompt** —
"Compare", "Critique", "Synthesize", whatever the user has named
it in the Meta-prompt CRUD — can also fan out across the present
languages. The scope screen picks up a `SecondaryLanguageScope`
(`AllPresent` or `Selected(...)`) and the run produces one batch
per language; inside each batch, the agent response bodies are
pulled from the matching TRANSLATE rows (falling back to the
original text per-item if a translation is missing) and the
prompt-side `@QUESTION@` is the translated prompt.

The result is one Meta row per (language, model-pick) — so asking
for a "Compare" in three languages from two models gives you six
Compare rows. Each row carries the same `metaPromptName` so the UI
and exports group them under the user-given name regardless of
language.

## UI screens

- **`LanguageSelectionScreen`** (`ui/report/other/LanguageSelection.kt`)
  — multi-select language picker, feeds into the Translate flow.
- **`SecondaryResultsScreen`** (`ui/report/manage/view/Secondary.kt`)
  — list of every meta row on the
  report, scoped to whichever Meta-prompt name (or structured kind:
  Rerank / Moderation / Translate) the user tapped on the View row.
  The Translations branch groups rows by `translationRunId`; each
  group surfaces as a single "run" row with the model name, the
  language list, and the count.
- **`TranslationL1Screen` / `TranslationRunScreen`**
  (`ui/report/manage/TranslationL1.kt` / `TranslationRun.kt`, help
  `translation_run_l1`) — drill into a run: per-model progress of
  the per-(source, language) calls, with an **Actions** card
  carrying *Restart failed* / *Start missing* buttons that re-run
  only the rows that need it. `TranslationL2Screen`
  (`TranslationL2.kt`, help `translation_run_l2`) is the per-model
  sub-drill.
- **`TranslationL3Screen`** (`ui/report/manage/TranslationL3.kt`,
  help `translation_run_l3`, title "Translation call") — one
  specific TRANSLATE row, with the source text, target language,
  model, full translated body, raw HTTP trace link. Model names
  render as pane labels; the original text wraps to size. Every row
  carries a source-type column.
- **`TranslationCompareScreen`** (`ui/helpers/TranslationCompare.kt`,
  help `translation_compare`) — side-by-side comparison view of the
  same source across multiple translations / languages. On the
  Secondary-detail screen this is driven live by the title-bar 🌐
  compare icon (`onTranslationCompare`) when a translation of the
  result is in scope — there is no longer a stored
  `translatedFromSecondaryId` back-pointer; the picker-driven live
  compare is the only path.

The Translate detail Actions card uses the layout setting (Model
only / Provider and model) to derive row labels, and pending /
live translation rows on the Report Result are clickable.

## Viewing a translated report

A translated report is **the same `Report` object** as the
original — translations are stored as TRANSLATE secondaries, not
as a copy. The Result screen surfaces:

- A language pulldown at the top — switching it re-renders agent
  bodies and chat-type Meta rows from the matching TRANSLATE rows
  for that language.
- **Titles follow the active language too.** `ViewTitleBar`
  (`ui/report/view/helpers/ViewTitleBar.kt`) swaps the orange report
  title to its `TITLE_LONG` (or `TITLE`) translation when given a
  `reportId` + non-blank `activeLanguage`; the green model-response
  card title (`Agent.kt` `ModelReportCard`) uses the `AGENT_TITLE`
  translation, and the fan-out responder card title (`Fan.kt`
  `FanOutResponderCard`) uses `FANOUT_TITLE`. Each falls back to the
  original when no translation row exists for the active language.
- The original (`null`) language option always renders the
  untranslated content.
- The Zipped HTML export creates one folder per language plus a
  source/ folder with the originals; cross-anchored links navigate
  between languages.

## Cost tracking & per-kind Type

Each TRANSLATE call carries a **per-kind Type** rather than one flat
`internal/translate`, so traces, the cost table, and AI Usage all break
out by what was translated. The single classifier
`translateTraceType(srcKind, sourceIsFanOut, sourceIsFanIn)`
(`data/SecondaryModels.kt`) maps the row's `translateSourceKind` (and, for
`META`, the source row's `fanOutSourceAgentId` / `fanInOf`) to one of:

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

(RERANK / MODERATION are never translated, so they have no translate type.)
This string is the **trace category** (set per-item via
`withTraceCategory` in `TranslationRunManager.runOneTranslation`, computed at
enumeration on `TranslationItem.traceType`), the **AI Usage `kind`**
(`updateUsageStatsAsync`), and the **Report cost-table Type** column
(`ContentDisplay` + the HTML / Zipped / Word-ODT exports, which resolve the
META split by looking the source row up by `translateSourceTargetId`). The
persisted `translateSourceKind` field itself is unchanged — display-swap
logic still keys on `"AGENT"` / `"META"` / etc.

## Editing the translation prompt

Settings → AI Setup → **Prompt management → Other internal** lists
the fixed-name internal templates (model-info / model-intro / chat-title /
translate-text / translate-title / second-rerank / second-moderation / test-model). Edit the `text` field of the
`translate-text` row (bodies) or `translate-title` row (titles). Defaults
are seeded from `assets/internal-prompts/` on
a fresh install; existing entries are never overwritten by re-seeds
unless the user runs Housekeeping → Reset → "Reset Internal Prompts
to assets/internal-prompts/".

## See also

- [secondary-results.md](secondary-results.md) for the full
  secondary-result lifecycle, prompt resolution, and the
  `@RESULTS@` block.
- [datastructures.md](datastructures.md) for the
  `SecondaryResult` translate-only fields and
  `SecondaryLanguageScope`.
