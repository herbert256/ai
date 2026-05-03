# Secondary Results: Rerank, Summarize, Compare, Moderate, Translate

A "secondary result" is a meta-result that operates on a finished
report's per-agent outputs. Five kinds exist (`SecondaryKind`):

| Kind | Purpose | Default prompt asks for |
|---|---|---|
| `RERANK` | Rank the responses 1..N | Strict JSON: `[{id, rank, score, reason}, ...]` |
| `SUMMARIZE` | Synthesise a single best answer | Free-form prose |
| `COMPARE` | Identify agreements, disagreements, unique points | Free-form prose with sections; references restricted to bare `[N]` |
| `MODERATION` | Per-response policy classification | Structured JSON from OpenAI's `/v1/moderations` (no chat prompt) |
| `TRANSLATE` | Translate prompt + responses to one or more languages | Free-form prose, one row per (source × language) — see [translation.md](translation.md) |

Each is the work of a single chosen model and is persisted
independently — a report can accumulate any combination, and each
entry is independently viewable and deletable.

## Lifecycle

```
[ Report finishes ]
        │
        ▼
[ Tap Rerank / Summarize / Compare / Moderate / Translate ]
        │
        ├── Rerank: skips the scope screen entirely
        │           (always operates on the full set)
        │
        ├── Summarize / Compare / Moderate / Translate:
        │   ▼
        │  [ SecondaryScopeScreen ]
        │     • All model reports
        │     • Only top ranked reports → Number + Rank dropdown
        │     • Manual selection → checklist of agents
        │     • Summarize/Compare/Translate also: language scope
        │       (All present / Selected) when translations exist
        │     ▼
        ▼
[ Model picker (multi-select) ]
   • Rerank picker has a "rerank models only" toggle
   • Pre-checks the previous run's selection (per-(reportId, kind))
   • Includes the synthetic Local provider when at least one .task
     LLM is installed
        │
        ▼
[ Run — N independent calls in parallel, up to REPORT_CONCURRENCY_LIMIT ]
   • Multi-language fan-out for SUMMARIZE / COMPARE / TRANSLATE:
     one batch per (language, model-pick)
   • Multiple TRANSLATE batches can run concurrently (one runId each)
        │
        ▼
[ Each result saved as <filesDir>/secondary/<reportId>/<id>.json ]
   • For COMPARE: a deterministic "## References" legend mapping
     [N] = Provider / Model is appended at storage time
```

## Prompt resolution

Two layers, first hit wins:

1. The dedicated **GeneralSettings** field
   (`rerankPrompt` / `summarizePrompt` / `comparePrompt` /
   `translatePrompt`) — edited under AI Setup → Rerank, Summarize,
   Compare, Translate.
2. The hardcoded fallback in `SecondaryPrompts.DEFAULT_*`.

(MODERATION runs through OpenAI's `/v1/moderations` endpoint which
takes no chat prompt — there's nothing to substitute.)

Templates substitute these variables:

| Variable | Substituted with |
|---|---|
| `@QUESTION@` | Original report prompt (or its translation when fan-out language ≠ original) |
| `@RESULTS@` | Pre-formatted block, see below |
| `@COUNT@` | Number of results being processed |
| `@TITLE@` | Report title (or empty) |
| `@DATE@` | Current date/time, `yyyy-MM-dd HH:mm` |
| `@LANGUAGE@` | TRANSLATE only — target language English name |
| `@TEXT@` | TRANSLATE only — the source text being translated |

## The @RESULTS@ block

`buildResultsBlock` produces:

```
[1] provider=openai model=gpt-5
<full response text from agent #1>

[2] provider=anthropic model=claude-opus-4
<full response text from agent #2>

…
```

The bracketed `[N]` is the **stable id** rerank models echo back in
their JSON output. It also matches the `result-N` HTML anchors in the
report export, so summary / compare references like "as [3] noted"
auto-link back to that agent's card.

When a TopRanked or Manual scope filters the input, the original
`[N]` numbering is preserved — the result block becomes sparse
(e.g. `[1]`, `[4]`, `[7]` only) so the export anchors still resolve.

For multi-language Summarize / Compare batches the agent response
bodies are pulled from the matching TRANSLATE rows when present (so
each language's batch sees translated content), with the
prompt-side `@QUESTION@` likewise translated.

## Compare's auto-appended reference legend

After the model returns its Compare output, `executeSecondaryTask`
appends a deterministic reference legend to the persisted content:

```
…model output…

---

## References

[1] = OpenAI / gpt-5
[2] = Anthropic / claude-opus-4
[3] = Google / gemini-2.5-pro
```

The legend is built once per batch via `buildCompareLegend(report,
includeIds)` so it honours the same Manual / TopRanked filter as the
results block, and is written before save so subsequent renders /
exports include it without further work. The default Compare prompt
also tells the model to use **only** bare `[N]` references and to
say "all responses agree" without enumerating when literally
everyone agrees on a point.

## Storage

```
<filesDir>/secondary/<reportId>/<resultId>.json   ← SecondaryResult
```

Cascades on parent-report deletion: `ReportStorage.deleteReport`
calls `SecondaryResultStorage.deleteAllForReport` so the directory
goes with the parent.

## Cost tracking

Every secondary call is tagged with `kind = "rerank" | "summarize" |
"compare" | "moderate" | "translate"` in `usage-stats.json`. The AI
Usage screen shows the kind as a small pill on the per-model row
(orange for rerank, indigo for summarize, purple for compare, etc.).
The Report cost summary and HTML export have a Type column that
breaks down the same way.

## HTML export

After the main report sections, secondary blocks render in this
order (when present):

1. **Reranks** — each entry rendered as a linked rank table
   (`Rank | Result | Score | Reason`). The Result column is a
   `<a href="#result-N">[N]</a>` link back to that agent's card.
2. **Summaries** — markdown rendering of the response.
3. **Compares** — markdown rendering, with `[N]` references
   automatically linkified back to the corresponding agent anchor;
   the appended `## References` legend renders as a compact list.
4. **Moderations** — flagged-categories table, one row per source
   response with category pills coloured by severity.
5. **Translations** — see [translation.md](translation.md) for the
   per-language tab + per-call detail layout.

The Zipped HTML export builds a self-contained site with a
`Reports/`, `Summaries/`, `Compares/`, `Moderations/`,
`Translations/<lang>/` directory tree and cross-anchored links so
the whole thing browses offline.

## Concurrency and gating

- The Rerank / Summarize / Compare / Moderate / Translate buttons
  are **disabled** while:
  - The parent report is still streaming (the button row only
    shows on the result-phase UI, after `isComplete = true`).
- Each run runs its picks in parallel, up to
  `AppViewModel.REPORT_CONCURRENCY_LIMIT = 4` permits.
- Multiple Translate batches can be in flight concurrently — each
  has its own `translationRunId` and its rows are grouped under
  their own row on the result screen.
- Per-(report, kind) last-selection is persisted under
  `secondary_last_<kind>_<reportId>` in `eval_prefs` so the next
  click on Rerank / Summarize / Compare / Moderate for the same
  report pre-checks the same models.

## Adding a sixth kind

The `when (kind)` blocks throughout the codebase are exhaustive, so
the Kotlin compiler will list every site you need to touch. See
[development.md](development.md) → "A new SecondaryKind".
