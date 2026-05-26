# Translation

The Translate flow is a `SecondaryKind` (`TRANSLATE`) that operates
on a finished report's content — translating the original prompt
and every successful agent response (plus any chat-type Meta rows
in scope) into one or more target languages, fanning out one API
call per (source × language) pair.

## Triggering a Translate run

From the result-phase Actions row on a finished report, tap
**Translate**. The flow:

1. **Language picker** (`ui/report/LanguageSelectionScreen.kt`) —
   pick one or more target languages from a comprehensive English-name
   list (with native renderings as subtitles, e.g. "Dutch /
   Nederlands"). Multi-select.
2. **Scope picker** (`ui/report/SecondaryScopeScreen.kt`) — same
   scope screen the chat-type Meta runs use:
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
than the body `translate` prompt (see below):

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
| `translate` | prompt / agent / meta bodies | `@LANGUAGE@`, `@TEXT@` |
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

- **`LanguageSelectionScreen`** — multi-select language picker,
  feeds into the Translate flow.
- **`SecondaryResultsScreen`** — list of every meta row on the
  report, scoped to whichever Meta-prompt name (or structured kind:
  Rerank / Moderation / Translate) the user tapped on the View row.
  The Translations branch groups rows by `translationRunId`; each
  group surfaces as a single "run" row with the model name, the
  language list, and the count.
- **`TranslationRunDetailScreen`** — drill into a run: shows the
  per-(source, language) calls, with an **Actions** card carrying
  *Restart failed* / *Start missing* buttons that re-run only the
  rows that need it.
- **`TranslationCallDetailScreen`** — one specific TRANSLATE row,
  with the source text, target language, model, full translated
  body, raw HTTP trace link. Model names render as pane labels;
  the original text wraps to size. Every row carries a source-type
  column.
- **`TranslationCompareScreen`** — side-by-side comparison view of
  the same source across multiple translations / languages.

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
- The original (`null`) language option always renders the
  untranslated content.
- The Zipped HTML export creates one folder per language plus a
  source/ folder with the originals; cross-anchored links navigate
  between languages.

## Cost tracking

Every TRANSLATE call is tagged `kind = "translate"` in
`usage-stats.json` and surfaces with its own pill on the AI Usage
screen + its own row colour in the Report cost table.

## Editing the translation prompt

Settings → AI Setup → **Prompt management → Other internal** lists
the fixed-name internal templates (intro / model_info / translate /
translate-title / rerank / moderation). Edit the `text` field of the
`translate` row (bodies) or `translate-title` row (titles). Defaults
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
