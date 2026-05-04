# Secondary Results: Meta prompts, Rerank, Moderate, Translate

A "secondary result" is a meta-result that operates on a finished
report's per-agent outputs. Four kinds exist (`SecondaryKind`):

| Kind | Purpose | Default prompt asks for |
|---|---|---|
| `RERANK` | Rank the responses 1..N | Strict JSON: `[{id, rank, score, reason}, ...]` |
| `META` | Any user-defined chat-type Meta prompt — "Compare", "Critique", "Synthesize", anything the user names in the Meta-prompt CRUD | Free-form prose; the prompt body is whatever the user wrote |
| `MODERATION` | Per-response policy classification | Structured JSON from a provider's `/moderations` endpoint (no chat prompt) |
| `TRANSLATE` | Translate prompt + responses to one or more languages | Free-form prose, one row per (source × language) — see [translation.md](translation.md) |

Every chat-type Meta prompt routes through the single `META` kind;
the user-given prompt name carried on the row (`metaPromptName`) is
what the UI and exports bucket by. The kind decides which API path
handles the call (rerank endpoint / moderation endpoint / chat); the
name decides how the result is grouped, labelled, and exported.

Each result is the work of a single chosen model and is persisted
independently — a report can accumulate any combination, and each
entry is independently viewable and deletable.

## Meta-prompt CRUD

Chat-type Meta prompts live as `InternalPrompt` rows with
`category = "meta"`. Settings → AI Setup → **Prompt management →
Report Meta Prompts** is the CRUD surface — add / rename / delete.
Each entry has:

- **name** — user-facing label that ends up as `metaPromptName` on
  every row it produces ("Compare", "Critique", "Synthesize", …).
- **type** — routing label: `"chat"` → kind=META, `"rerank"` →
  kind=RERANK, `"moderation"` → kind=MODERATION.
- **reference** — when true on a chat-type entry, the run appends a
  deterministic `## References` legend mapping `[N]` to provider /
  model. See "Reference legend" below.
- **text** — the prompt template, with `@QUESTION@` / `@RESULTS@` /
  `@COUNT@` / `@TITLE@` / `@DATE@` placeholders.

## Lifecycle

```
[ Report finishes ]
        │
        ▼
[ Tap a Meta button on the result page                      ]
[ (one button per Meta prompt, plus Translate)              ]
        │
        ├── type=rerank / moderation: skips the scope screen
        │   (always operates on the full set)
        │
        ├── type=chat / Translate:
        │   ▼
        │  [ SecondaryScopeScreen ]
        │     • All model reports
        │     • Only top ranked reports → Number + Rank dropdown
        │     • Manual selection → checklist of agents
        │     • chat-type Meta and Translate also: language scope
        │       (All present / Selected) when translations exist
        │     ▼
        ▼
[ Model picker (multi-select) ]
   • Rerank picker has a "rerank models only" toggle
   • Pre-checks the previous run's selection (per-(reportId, prompt))
   • Includes the synthetic Local provider when at least one .task
     LLM is installed
        │
        ▼
[ Run — N independent calls in parallel, up to REPORT_CONCURRENCY_LIMIT ]
   • Multi-language fan-out for chat-type META and TRANSLATE:
     one batch per (language, model-pick)
   • Multiple TRANSLATE batches can run concurrently (one runId each)
        │
        ▼
[ Each result saved as <filesDir>/secondary/<reportId>/<id>.json ]
   • metaPromptId / metaPromptName stamped on every chat-type META row
   • For META rows with reference=true: a deterministic
     "## References" legend mapping [N] = Provider / Model is
     appended at storage time
```

## Prompt resolution

Every prompt — Rerank, every chat-type Meta entry, Translate, plus
the system prompts (Intro, Model info) — lives as an
`InternalPrompt` row seeded from `assets/prompts.json` on first
launch. The Meta-prompt CRUD lets the user add / rename / delete
freely after that; the JSON only gates fresh-install seeding (no
in-code `DEFAULT_*` constants exist).

For a chat-type Meta run the prompt template is the
`InternalPrompt.text` of whichever Meta-prompt button the user
tapped. For RERANK runs the template is the `InternalPrompt.text`
of the rerank-typed Meta entry the user picked (defaults to the
seeded "Rerank" entry). For TRANSLATE runs the runtime looks up
the `InternalPrompt` named `"Translate"`. MODERATION runs through
a provider's `/moderations` endpoint which takes no chat prompt —
there's nothing to substitute.

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
report export, so chat-type Meta references like "as [3] noted"
auto-link back to that agent's card.

When a TopRanked or Manual scope filters the input, the original
`[N]` numbering is preserved — the result block becomes sparse
(e.g. `[1]`, `[4]`, `[7]` only) so the export anchors still resolve.

For multi-language chat-type Meta batches the agent response bodies
are pulled from the matching TRANSLATE rows when present (so each
language's batch sees translated content), with the prompt-side
`@QUESTION@` likewise translated.

## Reference legend (chat-type Meta only)

When the Meta prompt's `reference` flag is true, after the model
returns its output `executeSecondaryTask` appends a deterministic
reference legend to the persisted content:

```
…model output…

---

## References

[1] = OpenAI / gpt-5
[2] = Anthropic / claude-opus-4
[3] = Google / gemini-2.5-pro
```

The legend is built once per batch via `buildReferenceLegend(report,
includeIds)` so it honours the same Manual / TopRanked filter as the
results block, and is written before save so subsequent renders /
exports include it without further work.

## Storage

```
<filesDir>/secondary/<reportId>/<resultId>.json   ← SecondaryResult
```

Cascades on parent-report deletion: `ReportStorage.deleteReport`
calls `SecondaryResultStorage.deleteAllForReport` so the directory
goes with the parent.

## Cost tracking

Every secondary call is tagged in `usage-stats.json` with the
`kind` it ran under: `"rerank"`, `"meta"`, `"moderation"`, or
`"translate"`. The AI Usage screen shows the kind as a small pill
on the per-model row.

In the Report cost summary and HTML export the **Type** column
prefers the `metaPromptName` (lowercased) over the kind, so a
"Compare" row reads `compare`, a "Critique" row reads `critique`,
etc. Rerank / Moderation / Translate keep their fixed labels.

## HTML export

After the main report sections, the view-picker offers one tab per
content type, in this order (when present):

1. **Reports** — agents in either One-by-one or All-together layout.
2. **One tab per chat-type Meta prompt name** — one tab per unique
   `metaPromptName` on the report. The tab label IS the user-given
   name. Multiple Meta prompts on the same report each get their own
   tab; tab order matches first-seen chronological order.
3. **Reranks** — each entry rendered as a linked rank table
   (`Rank | Result | Score | Reason`). The Result column is a
   `<a href="#result-N">[N]</a>` link back to that agent's card.
4. **Moderations** — flagged-categories table, one row per source
   response with category pills coloured by severity.
5. **Prompt / Costs / JSON** — original prompt, per-call cost
   table, captured API traces (Original-only).

`[N]` references inside chat-type Meta content are linkified back
to the corresponding agent anchor.

## Zipped HTML export

The Zipped HTML export builds a self-contained site with one
directory per content type. Inside each language directory:

```
original/
  index.html
  Reports/
    index.html
    01_provider_model.html
    …
  <metaPromptName>/      ← one folder per chat-type Meta prompt name
    index.html
    01_provider_model.html
    …
  Reranks/, Moderations/  (Original only — structured JSON)
  Prompt/, Costs/, JSON/  (Original only)
```

The directory name is the `metaPromptName` filtered through the
filesystem-safe regex (so a name like "Pro/Con" becomes "Pro_Con").
Trace-link lookup keys off `"Report meta: <name>"` — the same
category tag `runMetaPrompt` writes — so each row's 🐞 link
points at the right captured API trace.

## Concurrency and gating

- The Meta / Translate buttons are **disabled** while the parent
  report is still streaming (the button row only shows on the
  result-phase UI, after `isComplete = true`).
- Each run runs its picks in parallel, up to
  `AppViewModel.REPORT_CONCURRENCY_LIMIT = 4` permits.
- Multiple chat-type Meta batches AND multiple Translate batches
  can be in flight concurrently — each batch has its own
  `metaPromptId` (chat-type) or `translationRunId` (translate) and
  its rows surface independently in the UI.
- Per-(report, prompt-name) last-selection is persisted under
  `secondary_last_<promptId>_<reportId>` in `eval_prefs` so the
  next click on the same Meta button pre-checks the same models.

## Adding a fifth kind

The `when (kind)` blocks throughout the codebase are exhaustive, so
the Kotlin compiler will list every site you need to touch. See
[development.md](development.md) → "A new SecondaryKind". For most
new behaviour you don't need a new kind — adding a Meta-prompt
entry covers any new chat-type analysis without code changes.
