# Translation

The Translate flow is a fifth `SecondaryKind` (`TRANSLATE`) that
operates on a finished report's content — translating the original
prompt and every successful agent response into one or more target
languages, fanning out one API call per (source × language) pair.

## Triggering a Translate run

From the result-phase Actions row on a finished report, tap
**Translate**. The flow:

1. **Language picker** (`ui/report/LanguageSelectionScreen.kt`) —
   pick one or more target languages from a comprehensive English-name
   list (with native renderings as subtitles, e.g. "Dutch /
   Nederlands"). Multi-select.
2. **Scope picker** (`ui/report/SecondaryScopeScreen.kt`) — same
   scope screen as Summarize / Compare:
   - All model reports
   - Top-N from a chosen rerank
   - Manual selection
3. **Model picker** — pick the chat model(s) to do the translating
   (any provider; the synthetic Local provider works too if a
   `.task` LLM is installed).
4. **Run** — fans out one batch per (language, model-pick) and one
   API call per (source × language) within that batch. Each
   `executeSecondaryTask` call writes a `SecondaryResult` with
   `kind = TRANSLATE`.

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
- **Each in-scope Summary or Compare result** (when included via
  language scope) — `translateSourceKind = "SUMMARY"` or `"COMPARE"`,
  `translateSourceTargetId = secondary.id`.

The TRANSLATE prompt template (`SecondaryPrompts.DEFAULT_TRANSLATE`,
overridable via `GeneralSettings.translatePrompt`) substitutes:

| Variable | Meaning |
|---|---|
| `@LANGUAGE@` | English name of the target language |
| `@TEXT@` | The source text being translated |

The default prompt asks for: "Translate the following text to
@LANGUAGE@. Preserve markdown formatting (headings, bold, italic,
lists, code blocks, tables) exactly. Preserve citation references
like [1] or [N]. Preserve URLs and code identifiers untouched. Do
NOT add commentary, preface, or explanation — output only the
translation."

## Multi-language fan-out for Summarize / Compare

When a report has TRANSLATE rows, **Summarize** and **Compare** can
also fan out across the present languages. The scope screen picks
up a `SecondaryLanguageScope` (`AllPresent` or `Selected(...)`) and
the run produces one batch per language; inside each batch, the
agent response bodies are pulled from the matching TRANSLATE rows
(falling back to the original text per-item if a translation is
missing) and the prompt-side `@QUESTION@` is the translated prompt.

The result is one Summary / Compare per (language, model-pick) — so
asking for a Summary in three languages from two models gives you
six Summary rows.

## UI screens

- **`LanguageSelectionScreen`** — multi-select language picker,
  feeds into the Translate flow.
- **`SecondaryResultsScreen`** — list of all Reranks / Summaries /
  Compares / Moderations / Translations on a report. The
  Translations section groups rows by `translationRunId`; each
  group surfaces as a single "run" row with the model name, the
  language list, and the count.
- **`TranslationRunDetailScreen`** — drill into a run: shows the
  per-(source, language) calls.
- **`TranslationCallDetailScreen`** — one specific TRANSLATE row,
  with the source text, target language, model, full translated
  body, raw HTTP trace link, and a 🐞 Trace tree pull-up.
- **`TranslationCompareScreen`** — side-by-side comparison view of
  the same source across multiple translations / languages.

## Viewing a translated report

A translated report is **the same `Report` object** as the
original — translations are stored as TRANSLATE secondaries, not
as a copy. The Result screen surfaces:

- A language pulldown at the top — switching it re-renders agent
  bodies and summaries / compares from the matching TRANSLATE rows
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

Settings → AI Setup → **Translate** opens the prompt editor for
`GeneralSettings.translatePrompt`. Empty falls back to
`SecondaryPrompts.DEFAULT_TRANSLATE`. The editor shares the
`SecondaryPromptsScreen` codebase with Rerank / Summarize / Compare /
Intro / Model Info.

## See also

- [secondary-results.md](secondary-results.md) for the full
  secondary-result lifecycle, prompt resolution, and the
  `@RESULTS@` block.
- [datastructures.md](datastructures.md) for the
  `SecondaryResult` translate-only fields and
  `SecondaryLanguageScope`.
