# Secondary Results: Rerank, Summarize, Compare

A "secondary result" is a meta-result that operates on a finished
report's per-agent outputs. Three kinds exist (`SecondaryKind`):

| Kind | Purpose | Default prompt asks for |
|---|---|---|
| `RERANK` | Rank the responses 1..N | Strict JSON: `[{id, rank, score, reason}, ...]` |
| `SUMMARIZE` | Synthesise a single best answer | Free-form prose |
| `COMPARE` | Identify agreements, disagreements, unique points | Free-form prose with sections |

Each is the work of a single chosen model and is persisted
independently — a report can accumulate any combination, and each
entry is independently viewable and deletable.

## Lifecycle

```
[ Report finishes ]
        │
        ▼
[ Tap Rerank / Summarize / Compare in Actions ]
        │
        ├── If kind ∈ {Summarize, Compare} AND report has at least one rerank
        │   ▼
        │  [ Scope screen ]
        │     • All model reports
        │     • Only top ranked reports → Number of reports + Rank dropdown
        │     ▼
        ▼
[ Model picker (multi-select) ]
   • Rerank picker has a "rerank models only" toggle
   • Pre-checks the previous run's selection (per-(reportId, kind))
        │
        ▼
[ Run — N independent calls in parallel, up to REPORT_CONCURRENCY_LIMIT ]
        │
        ▼
[ Each result saved as <filesDir>/secondary/<reportId>/<id>.json ]
```

## Prompt resolution

Three layers, first hit wins:

1. An **Internal Prompt** in AI Setup → Internal Prompts whose name is
   `rerank`, `summarize`, or `compare` (case-insensitive).
2. The dedicated **GeneralSettings** field
   (`rerankPrompt` / `summarizePrompt` / `comparePrompt`) — edited
   under AI Setup → Rerank, Summarize, Compare.
3. The hardcoded fallback in `SecondaryPrompts.DEFAULT_*`.

Templates substitute these variables:

| Variable | Substituted with |
|---|---|
| `@QUESTION@` | Original report prompt |
| `@RESULTS@` | Pre-formatted block, see below |
| `@COUNT@` | Number of results being processed |
| `@TITLE@` | Report title (or empty) |
| `@DATE@` | Current date/time, `yyyy-MM-dd HH:mm` |

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

When a TopRanked scope filters the input, the original `[N]`
numbering is preserved — the result block becomes sparse (e.g. `[1]`,
`[4]`, `[7]` only) so the export anchors still resolve.

## Storage

```
<filesDir>/secondary/<reportId>/<resultId>.json   ← SecondaryResult
```

Cascades on parent-report deletion: `ReportStorage.deleteReport`
calls `SecondaryResultStorage.deleteAllForReport` so the directory
goes with the parent.

## Cost tracking

Every secondary call is tagged with `kind = "rerank" | "summarize" |
"compare"` in `usage-stats.json`. The AI Usage screen shows the kind
as a small pill on the per-model row (orange for rerank, indigo for
summarize, purple for compare). The Report cost summary and HTML
export have a Type column that breaks down the same way.

## HTML export

After the main report sections, three blocks (in this order):

1. **Reranks** — each entry rendered as a linked rank table
   (`Rank | Result | Score | Reason`). The Result column is a
   `<a href="#result-N">[N]</a>` link back to that agent's card.
2. **Summaries** — markdown rendering of the response.
3. **Compares** — markdown rendering, with `[N]` references
   automatically linkified back to the corresponding agent anchor.

## Concurrency and gating

- The Rerank / Summarize / Compare buttons are **disabled** while:
  - The parent report is still streaming (the button row only shows on
    the result-phase UI, after `isComplete = true`).
  - A secondary run is already in flight for this report (controlled
    by `UiState.secondaryRun`).
- Each run runs its picks in parallel, up to
  `AppViewModel.REPORT_CONCURRENCY_LIMIT = 4` permits.
- Per-(report, kind) last-selection is persisted under
  `secondary_last_<kind>_<reportId>` in `eval_prefs` so the next click
  on Rerank/Summarize/Compare for the same report pre-checks the same
  models.

## Adding a fourth kind

The `when (kind)` blocks throughout the codebase are exhaustive, so
the Kotlin compiler will list every site you need to touch. See
[development.md](development.md) → "A new SecondaryKind".
