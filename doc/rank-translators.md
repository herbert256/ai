# Rank the translators

**Rank the translators** (`SecondaryKind.TRANSRANK`, the eighth of the
eight kinds in `data/SecondaryModels.kt` — `RERANK, META, MODERATION,
TRANSLATE, TOURNAMENT, JUDGES, COMPARE, TRANSRANK`) grades which
translator **model** produced the best translation of a finished
[Translate](translation.md) run. It does **not** re-translate anything:
it reuses the TRANSLATE rows of one existing translation run (one run =
one target language) and has a panel of judge models score each
long-form translated answer 0–100. Each translator model is then ranked
by the average score its translations received.

It is a worker-judged cell batch in the same family as Tournament /
Judge-the-judges / Compare (see
[tournament-judges-compare.md](tournament-judges-compare.md)): a run
pre-creates a grid of placeholder `SecondaryResult` rows, dispatches the
cells through fixed-host judge calls, persists each cell as it settles,
and hydrates the run back from disk when the report is reopened. Its
runtime owner is `TranslatorRankEngine`
(`viewmodel/TranslatorRankEngine.kt`), reached from `ReportViewModel` as
`reportViewModel.translatorRankEngine`. The engine extends the shared
`BatchEngine<TransRankRunKey, String, TransRankCellState,
TransRankRunState>`, so the L1 status panel, terminal/error counts and
cost roll-ups come from the same `BatchRun` base as the other cell
batches.

## What a run scores

A run is keyed **per language** — one ranking per translation run:

```kotlin
transRankRunKey(reportId, sourceTranslationRunId) = "$reportId|$sourceTranslationRunId"
```

The engine reads back the TRANSLATE rows of the source run
(`SecondaryResultStorage.listForReport(..., SecondaryKind.TRANSLATE)`
filtered by `translationRunGroupingId(row) == sourceTranslationRunId`)
and keeps only the **long-form bodies** — `SCORED_SOURCE_KINDS = {"AGENT",
"META"}`, i.e. model answers and chat-type Meta / fan-out responses. The
report prompt and the four short title kinds (`TITLE`, `TITLE_LONG`,
`AGENT_TITLE`, `FANOUT_TITLE`) are **skipped**. A TRANSLATE row is
scorable only when its `content` is non-blank, its `model` is non-blank,
and its original source text can be recovered:

| `translateSourceKind` | Original recovered from |
|---|---|
| `AGENT` | `report.agents[…].responseBody` (matched by `translateSourceTargetId == agentId`) |
| `META` | the source secondary row's `content` (`SecondaryResultStorage.get(reportId, translateSourceTargetId)`) |

Each kept row becomes a `ScorableItem` carrying the translator's
`(providerId, model)`, the original text, the translated text, the
`languageFrom` (`report.languageName`, else "the original language") and
`languageTo` (the row's `targetLanguage`, else "the target language").

## The judge panel

The panel is resolved from the bundled internal prompt
**`workers/translate-rank`** (category `workers`, name `translate-rank`).
Its asset references the shared **`workers`** swarm, `parameters` and
`systemPrompt` are `*NONE`, and `title` is "Rank the translators".
`resolveJudges` expands the prompt's worker list
(`aiSettings.expandWorker`), de-dupes by `provider/model`, resolves each
to its effective model, and yields a `Judge(worker, providerId, model)`
list. Worker-source precedence at launch mirrors Tournament / Judges:

1. **♻️ `report.useReportModelsAsWorkers`** — the report's own answer
   models become the panel (`reportModelWorkers(report)`), winning over
   everything else.
2. **`*SELECT` swarm** — if the `translate-rank` prompt's
   `modelSelection` is `MODEL_SELECTION_SELECT` and ♻️ is off, a
   one-time `RuntimeWorkerPick` overlay ("Rank translators — pick
   workers") runs **before** the confirm dialog, so the dialog's call
   count matches the chosen judges (audit bug 6). The pick is passed as
   `overrideWorkers`.
3. **Otherwise** the prompt's configured swarm is used as-is.

## The cell grid and the ≤ 25-per-translator cap

The engine builds every `(item × judge)` pair where **the judge is not
the model that produced that item** (`judges.filter { it.key != tk }`),
then caps it **per translator model** to
`TRANSRANK_CELLS_PER_TRANSLATOR = 25`
(`data/TranslatorRankModel.kt`): the candidates are grouped by the
translator's `provider/model`, each group is `shuffled().take(25)`. So
the whole batch is at most **#translators × 25** cells (10 translator
models → ≤ 250). With few items per translator each item still draws
several judges; with many items the budget spreads thinner — not every
translation of a big run is judged by every model. `cappedCandidates`
and `plannedCellCount` share this exact resolution so the confirm
dialog's number can never contradict what the run actually launches.

The cell prompt substitutes four placeholders into `prompt.text`:
`@LANGUAGE_FROM@`, `@LANGUAGE_TO@`, `@ORIGINAL@`, `@TRANSLATION@`. The
bundled prompt asks the judge to reply with **exactly two lines**: line 1
a single 0–100 number, line 2 a one-sentence motivation.

## How a cell is scored

Each cell is a **fixed-model** call (like Judge-the-judges): the row's
provider/model **is** the judge from the moment the placeholder is
created. `runOneCell` resolves the judge worker (effective API key /
model / endpoint) and calls
`repository.analyzeWithAgent(agent, "", resolved, retry = false)` under a
trace-filename sink. The reply is parsed by **`parseScoreAndReason`**
(`data/TranslatorRankModel.kt`):

- A JSON `{"score", "reason"}` form is tried first; a numeric `score` is
  read and `coerceIn(0, 100)`. A structured/array score yields a missing
  score rather than a misread guess — once in a valid JSON object the
  parser commits to it.
- Otherwise the **score is read only from the first line** (or from an
  explicitly "score"-labelled line) — **never** from an arbitrary number
  elsewhere in the reply, so a reason like "2 strong points" can't be
  misread as the score (audit bug 9). Line 2 (or the rest) is the reason.

A cell is committed as `DONE` only when `resp.isSuccess` **and** a score
parses; the winning result is written with
`SecondaryResultStorage.recordTournamentMatch` (reused verbatim —
overwrites the row's content, tokens, cost, duration and trace filename
in one atomic write, and is a no-op when the row was deleted mid-call).
TransRank passes the **full `TokenUsage`** to `recordTournamentMatch`
(its `tokenUsage` parameter) so cached / cache-creation / reasoning
tokens and the API-reported cost survive, rather than the legacy
input/output-only shape. A non-success reply, or a success with no
parseable score, is written as a cell **error** (`recordCellError`).

After the cells settle, `recomputeAndPersistAggregate` folds them into
the per-translator ranking and writes the JSON onto the single aggregate
row.

## Stored rows

Both row kinds reuse the **TOURNAMENT field cluster** on `SecondaryResult`
— there are no TransRank-specific fields (`data/TranslatorRankModel.kt`
documents the reuse):

| Row | `SecondaryKind` | provider / model | Key fields |
|---|---|---|---|
| Cell | `TRANSRANK` | the **judge** (fixed at creation) | `tournamentRole = "MATCH"` (`TRANSRANK_ROLE_CELL`), `tournamentJudgeRunId` / `runId` = transrank run id, `translationRunId` = source translation run, `compareToResultId` = the scored TRANSLATE row id, `matchResponseAId` / `matchResponseBId` = the translator's provider / model, `targetLanguage` / `targetLanguageNative`, `content` = the judge's two-line reply |
| Aggregate | `TRANSRANK` | `*transrank` / `aggregate` (`AGG_PROVIDER` / `AGG_MODEL`) | `tournamentRole = "AGGREGATE"` (`TRANSRANK_ROLE_AGGREGATE`), `content` = the ranking JSON |

The on-disk store is the canonical
`<filesDir>/secondary/<reportId>/<resultId>.json` (one file per row, via
`SecondaryResultStorage`); runtime state is disposable and always
rebuildable. `countForReport` counts a TRANSRANK run by its **AGGREGATE**
row only (the cells are inspection detail);
`legacyKindDisplayName(TRANSRANK)` is **"Rank the translators"** and the
default 🏅 icon is `MetadataDefaults.TRANSLATOR_RANK`.

### The ranking

`aggregateTranslatorRanks(cells)` keeps cells with a parsed score and
`judge ≠ translator`, groups by translator `provider/model`, and emits a
`TranslatorRankRow(providerId, model, avgScore, itemCount, judgedCount)`
— `avgScore` is the mean of every score that translator received,
`itemCount` is the distinct translated items it produced that drew ≥ 1
score, `judgedCount` is the total scores received. Rows sort
**`avgScore` descending, tie-broken by `judgedCount` descending**.
`toTransRankJson` serialises `[{rank, provider, model, avgScore, items,
judged}]` onto the aggregate row's `content`.

## Launching a run (the 🏅 medal)

There is no separate "Rank" button — a run starts from the **🏅 medal**
on a translation row, in two places, both routed through the shared
`onRankMedal` / `onRankTranslators` handler `(translationRunId, langName,
langNative)`:

- **The Translations list** (`ReportTranslationsScreen`, wired in
  `ui/report/manage/Run.kt`) — each run row carries 🏅.
- **The Translation run screen** (`TranslationRunScreen`, wired in
  `ui/report/manage/Main.kt`) — the title-bar 🏅 action, alongside 👁 /
  🐜 / 🔄 / 🐞 / 🗑.

The handler first checks whether a rank run already exists for that
language (`engine.runByKey(key) != null`); if so it just **opens** it
(`transRankOpenState.value = key`). Otherwise it resolves the worker
source (♻️ / `*SELECT` / configured) and arms a `PendingRankRequest`,
which the shared **`RankTranslatorsConfirmHost`** dialog renders. The
dialog calls `engine.plannedCellCount(...)` against exactly the workers
the run will use and shows "This is about N scoring call(s)." (or
"(counting…)"). Cancel creates nothing. On **Rank**, the call site arms a
build-stage popup ("Building translator ranking", keyed by `buildKey`)
and calls `engine.startRun(context, reportId, translationRunId, langName,
langNative, buildKey, overrideWorkers)`.

`startRun`:
- de-dupes an already-active run for the same key,
- bumps `activeSecondaryBatches` on entry / decrements in `finally`
  (keeps the result screen polling while work is live),
- tags traces/usage with a fresh per-run UUID under category
  `transrank/rank`,
- writes the aggregate placeholder, then one cell placeholder per
  capped candidate (build progress ticked every 5 rows), publishes the
  `TransRankRunState`, dispatches the cells, recomputes the aggregate,
  and on exit `finalizeLeftoverCells` terminalizes any cell left
  PENDING/RUNNING (without a live job) as an "Interrupted" error.

`PendingRankRequest` survives a config change via
`PendingRankRequestSaver` (run identity only — a mid-confirm runtime
worker pick is dropped on restore, audit bug 21).

## Run screen and drill-in

`TranslatorRankScreen` (`ui/report/manage/TranslatorRank.kt`,
help `translator_rank`) hydrates the engine for the report on entry and
renders three levels plus a workers mode, switched by local state (back
press unwinds L2 → workers → L1 → close):

- **L1 — leaderboard** (`TranslatorRankL1`). The shared `BatchStatsRow`
  shows **Total / Done / Error / Run / Bench / Wait / Queue / Costs**.
  Counts come from `deriveBatchSummary` with `BatchFamily.FIXED_MODEL`:
  rate-gated cells land in **Wait** (`throttledCells`,
  `appViewModel.throttledTransRankCells`), short-benched judge models in
  **Bench** (`ModelCooldownStore.shortBenches`, column shown only when
  `summary.showBenchColumn`), so a throttled or benched cell never looks
  like a stuck Queue. A progress bar shows while not all-terminal. Below
  it, `aggregateTranslatorRanks(cells)` renders the live leaderboard
  (`#`, translator model, Items, Score; score colour green ≥ 80 / amber ≥
  50 / orange below). Title-bar actions: 🐜 **workers**, 🔄 **restart
  failed** (only when `error > 0`), 🗑 **delete run**.
- **L2 — one translator** (`TranslatorRankL2`). Tapping a leaderboard row
  opens that translator's items grouped by `translationRowId`, each "Item
  N" showing its per-judge scores + motivations (item order is stable
  across hydration: sorted by earliest cell timestamp then row id, audit
  bug 7). A running cell shows the hourglass; an errored cell shows the
  fail icon and its error in red.
- **🐜 Workers** (`TranslatorRankWorkersScreen`, help
  `translator_rank_workers`, title "Rank workers"). The per-judge-model
  breakdown — one row per scoring model with `done/total` and cost, a
  green progress bar while the batch is outstanding. Tap 🐜 again to
  return.

The **Manage row** (`TranslatorRankManageRow`) collapses each ranked
language into **one row** "Rank the translators · <language>", sorted by
language name, showing the 🏅 medal (hourglass while running, fail icon
when `errorCount > 0`) and the run cost. Tapping it opens the run via
`LocalTransRankOpenState`. The overlay is mounted by `Nav.kt`
(`TranslatorRankOverlay`) when the open-state key is non-null, using the
exclusive batch-overlay state so only one batch overlay shows at a time.

## Hydration, restart and delete

- **Hydrate** (`hydrate`) groups the report's TRANSRANK rows by
  `tournamentJudgeRunId`, keeps only the **latest run per
  `translationRunId` (per language)** by timestamp, and rebuilds each
  `TransRankRunState`. If the `translate-rank` prompt was since deleted
  or renamed, the run still hydrates **read-only** behind a *synthetic*
  `InternalPrompt` built from the row metadata (blank text, no workers,
  audit bug 4).
- **Restart failed** (`restartFailedCells`) is gated on a **real**
  prompt — a synthetic run carries blank text and cannot re-run. It
  clears the ERROR cells (content / cost / tokens / duration), recovers
  each judge's **original** worker from the run's prompt so the retry
  replays the same call shape (falling back to a minimal provider/model
  Worker only when the judge is no longer in the swarm, audit bug 2),
  re-dispatches them, and recomputes the aggregate.
- **Auto-resume after an app kill.** Like Tournament / Judges / Fan-out,
  `TranslatorRankEngine.resumeStaleRunsForReport` is wired into
  `SecondaryRunManager.resumeStaleRunsForReport` (the app-start + 30 s
  background sweep) and the Manage-open effect (`ui/report/manage/Nav.kt`).
  It hydrates, then for every run on the report (one per language)
  re-dispatches the cells a kill left PENDING, bounded by
  `BatchResume.capForRetry` so a cell that can never complete is
  terminalized after `MAX_ATTEMPTS` instead of re-dispatched forever. A
  synthetic (prompt-deleted) run is skipped. The single-call Meta resume
  path still skips TRANSRANK rows (each carries a non-null `translationRunId`
  and a null `metaPromptId`), so the engine owns recovery — the user can
  still force a retry of errored cells from L1's 🔄.
- **Broken-work screen.** Errored or kill-stranded cells also surface on the
  ⚠️ Broken-work screen as a `BatchFamilyKind.TRANSRANK` card — one per
  language, keyed `"$reportId|$sourceTranslationRunId"` (the engine run key) so
  the live build isn't falsely flagged (`activeTransRankRunKeys`). The card's
  Continue / restart / delete (whole-batch and per-row) route to
  `continueBrokenBatch` / `restartFailedCells` / `restartCellsByIds` /
  `removeFailedCells` / `removeUnfinishedCells` / `removeCellsByIds`. See
  [secondary-results.md](secondary-results.md).
- **Delete run** (`deleteRun`) is synchronous-on-the-flow: it cancels the
  build/dispatch + cell jobs and drops the run from `_runs` immediately
  (so the live screen and Manage row stop rendering at once and avoid a
  drive-to-ERROR re-render storm), then sweeps disk on `viewModelScope`.
  The sweep is scoped to `translationRunId == sourceRunId` **and**
  narrowed to this run's `tournamentJudgeRunId` when published, so
  deleting one ranking attempt can't take out a sibling/older attempt for
  the same language; the broad per-source-run sweep is the fallback only
  for a mid-build cancel (run never published). The deleted spend is
  summed and rolled into the report's `costsFromDeletedItems`. Deleting
  the report drops these rows with every other secondary.

## Cost, usage and throttling

- **Usage kind** is `transrank`
  (`updateUsageStatsAsync(..., kind = "transrank")`); the **trace
  category** is `transrank/rank`. Costs are computed per cell with
  `PricingCache.computeInOutCost` from the judge's provider/model and
  stored on the cell row; the report cost table groups them under the
  `transrank` group and L1/🐜 sum them live.
- **Throttling.** Cells dispatch through `runThrottledBatch` with
  `subCap = ApiCallCaps.workers` (the workers swarm sub-cap, which shares
  the `fanMeta` concurrency limit), layered over the global
  `ApiCallCaps.global` cap and the per-provider `ProviderThrottle` host
  gate — acquisition order sub-cap → global → host, the same as the other
  cell batches (see [throttle.md](throttle.md)). `hostOf` resolves the
  judge provider's host. Type-A short-bench retry is honored
  (`benchEnabled = ModelCooldownStore.typeABenchEnabled`,
  `onBenchRetry → restoreBenchedCellForRequeue` clears the row and resets
  the cell to PENDING for requeue).

## Relation to other features

- **[Translate](translation.md)** is the prerequisite: a TransRank run
  reuses one TRANSLATE run's rows and never re-translates. One ranking per
  translation run = per language; tap 🏅 again on another language's row
  to rank that one.
- **[Value view](value-view.md)** consumes the rankings: each
  "Rank the translators" run surfaces as its own chip (right after Rerank,
  labelled with the language) and as a contributor to the **Combined**
  score under the `translations` weight. The Value view reads the
  TRANSRANK **cell** rows directly (`tournamentRole == TRANSRANK_ROLE_CELL`,
  grouped by `translationRunId`), re-derives the per-translator averages
  with `aggregateTranslatorRanks`, and maps each translator's average onto
  the report's SUCCESS answer models by provider/model — models that
  weren't translators drop off the plot. The **Ranking weights** screen
  (Settings → Ranking weights, `ui/settings/SettingsScreen.kt`) exposes a
  0–10 `translations` slider (factory default **6**, `RANKING_WEIGHT_DEFAULTS`
  in `viewmodel/AppViewModelTypes.kt`) that feeds **only** the Value
  view's Combined blend (`buildCombinedRows`, averaging each model's
  scores across all present translator runs) — it does **not** affect
  TransRank's own per-run scoring, which is a plain average of received
  judge scores.
- **[Tournament / Judges / Compare](tournament-judges-compare.md)** are
  the sibling cell batches; TransRank borrows their cell-grid /
  `recordTournamentMatch` / aggregate-row machinery and the TOURNAMENT
  field cluster.

## Related files

- `viewmodel/TranslatorRankEngine.kt` — the engine: judge resolution,
  scorable-item recovery, per-translator cap, dispatch, scoring, hydrate,
  restart, delete
- `data/TranslatorRankModel.kt` — `TransRankCellState` /
  `TransRankRunState`, `TRANSRANK_CELLS_PER_TRANSLATOR`,
  `transRankCellKey` / `transRankRunKey`, `parseScoreAndReason`,
  `aggregateTranslatorRanks`, `TranslatorRankRow`, `toTransRankJson`,
  `toTransRankCellState`, the role constants
- `ui/report/manage/TranslatorRank.kt` — the Manage row, confirm host,
  L1 / L2 / 🐜 workers screens
- `data/SecondaryModels.kt` — `SecondaryKind.TRANSRANK`, the TOURNAMENT
  field cluster it reuses
- `data/SecondaryResult.kt` — `recordTournamentMatch` (TokenUsage
  persistence), `countForReport`, `legacyKindDisplayName`
- `assets/internal-prompts/English/workers/translate-rank.{json,txt}` —
  the bundled judge prompt
- `ui/settings/SettingsScreen.kt` — the Ranking weights screen
- `ui/report/view/ValueView.kt` — the TransRank chip + Combined `translations` weight
- `ui/admin/ReportsHelp.kt` — `translator_rank` / `translator_rank_workers` help
