# Report Bugs

### Bug 1 - Severity: High - Category: TransRank deletion scope
**Location:** `ai/src/main/java/com/ai/viewmodel/TranslatorRankEngine.kt:447-472` (`deleteRun`)

**Symptom:** Deleting one "Rank translators" run can delete every ranking
attempt for the same source translation run/language, including older or
parallel attempts that are not the selected run.

**Root cause:** The disk cleanup filters rows only by
`translationRunId == sourceRunId`. It ignores the current
`tournamentJudgeRunId` even though that id is the actual TransRank run
identity.

**Reproduction:** Run TransRank for a translation run, trigger a second
TransRank run for the same language, then delete one run. The cleanup sweep
matches both runs' rows.

**Proposed fix:** Filter by both `translationRunId` and
`tournamentJudgeRunId == run.runId`, or delete the exact aggregate/cell ids
captured from the selected run.

**Status:** Fixed — ae5d1d9c (scope deleteRun to the selected run id)

### Bug 2 - Severity: High - Category: TransRank retry fidelity
**Location:** `ai/src/main/java/com/ai/viewmodel/TranslatorRankEngine.kt:421-442` (`restartFailedCells`)

**Symptom:** Retrying failed TransRank cells can run with different
parameters, system prompt, or worker-source behavior than the original run.

**Root cause:** The retry path reconstructs a judge as
`Worker(provider = c.judgeProviderId, model = c.judgeModel)`. That minimal
worker loses any prompt worker metadata that resolved the original call,
including parameter presets, system prompt id, source selectors, or future
worker fields.

**Reproduction:** Configure `translate-rank` workers with non-default
parameter presets or system prompts, run TransRank until a cell fails, then
restart failed cells. The retried call is rebuilt from provider/model only.

**Proposed fix:** Persist resolved worker configuration on each cell, or
re-resolve the original prompt worker by stable worker id/key instead of
constructing a new minimal `Worker`.

**Status:** Fixed — 43fb24e5 (replay original judge worker on retry)

### Bug 3 - Severity: High - Category: TransRank accounting
**Location:** `ai/src/main/java/com/ai/viewmodel/TranslatorRankEngine.kt:344-357`, `ai/src/main/java/com/ai/data/SecondaryResult.kt:893-918` (`recordTournamentMatch`)

**Symptom:** TransRank rows lose cached-input tokens, cache-creation tokens,
reasoning tokens, and API-reported cost after persistence.

**Root cause:** `runOneCell` computes cost from the full `TokenUsage`, but
`recordTournamentMatch` persists `TokenUsage(inputTokens, outputTokens)`
only.

**Reproduction:** Run TransRank against a provider/model that reports
reasoning or cache tokens. Inspect the in-memory cost update and then the
stored secondary JSON. The stored `tokenUsage` lacks those fields.

**Proposed fix:** Change `recordTournamentMatch` to accept and persist the
full `TokenUsage`, or add a TransRank-specific commit helper that writes the
complete usage object.

**Status:** Fixed — 2b15b66a (persist full TokenUsage on scored cells)

### Bug 4 - Severity: High - Category: TransRank persistence
**Location:** `ai/src/main/java/com/ai/viewmodel/TranslatorRankEngine.kt:392-415` (`hydrate`)

**Symptom:** Persisted TransRank runs disappear from the UI if the
`translate-rank` internal prompt is missing, renamed, or imported
incorrectly.

**Root cause:** Hydration returns immediately when `rankPrompt(aiSettings)`
returns null. The rows themselves carry `metaPromptId`/`metaPromptName`, but
there is no fallback state.

**Reproduction:** Create a TransRank run, remove or rename the
`translate-rank` internal prompt, restart or reopen the report. The
persisted run is not hydrated.

**Proposed fix:** Hydrate a read-only/error run from row metadata when the
prompt cannot be resolved, and only block restart/new calls that need a live
prompt.

**Status:** Fixed — 21b05de4 (hydrate persisted runs read-only when prompt is missing)

### Bug 5 - Severity: Medium - Category: TransRank determinism
**Location:** `ai/src/main/java/com/ai/viewmodel/TranslatorRankEngine.kt:129-133` (`cappedItems`)

**Symptom:** Re-running or recreating a TransRank job can judge a different
sample of translated items for the same translator while reporting the same
planned call count.

**Root cause:** The per-translator cap uses `list.shuffled()` with default
randomness and does not persist selected item ids.

**Reproduction:** Run TransRank on a translation run with more than
`TRANSRANK_ITEMS_PER_TRANSLATOR` rows per translator. Delete/recreate or
retry the run. The judged item set can change.

**Proposed fix:** Use a deterministic seed from report id, source run id,
translator key, and prompt/run id, or persist the sampled translation row ids
when placeholders are created.

**Status:** Open

### Bug 6 - Severity: Medium - Category: TransRank confirmation
**Location:** `ai/src/main/java/com/ai/ui/report/manage/TranslatorRank.kt:127-129`, `ai/src/main/java/com/ai/ui/report/manage/Run.kt:837-852`, `ai/src/main/java/com/ai/ui/report/manage/Main.kt:1407-1416`

**Symptom:** The "Rank translators?" confirmation dialog can show the wrong
number of scoring calls when `translate-rank` uses `MODEL_SELECTION_SELECT`.

**Root cause:** `RankTranslatorsConfirmHost` computes the count before the
runtime worker picker opens and never passes selected workers to
`plannedCellCount`. The actual `startRun` can use a different worker list.

**Reproduction:** Set `translate-rank` to select workers at runtime. Open a
translation run, start TransRank, confirm the displayed count, then pick a
different number of workers. The actual call count differs from the dialog.

**Proposed fix:** Move the count dialog after worker selection, or calculate
and refresh the count with `overrideWorkers` after the picker returns.

**Status:** Fixed — e6b32c95 (pick workers before the confirm so the count matches)

### Bug 7 - Severity: Medium - Category: TransRank display ordering
**Location:** `ai/src/main/java/com/ai/ui/report/manage/TranslatorRank.kt:327-348` (`TranslatorRankL2`)

**Symptom:** The per-translator item detail view can show items in a
different order after hydration or storage rewrites.

**Root cause:** L2 groups `run.cells.values` by `translationRowId` and uses
the map/list iteration order. Hydrated cells come from persisted row order,
not from a saved sample order or source translation order.

**Reproduction:** Run TransRank, restart the app, then open a translator
detail page. The same source items may be numbered differently from the live
run.

**Proposed fix:** Persist an explicit item order on each cell/run, or sort
groups by source translation row timestamp/order before rendering.

**Status:** Fixed — 1fd0b9f9 (stable item ordering in the L2 detail)

### Bug 8 - Severity: Low - Category: TransRank Compose keys
**Location:** `ai/src/main/java/com/ai/ui/report/manage/TranslatorRank.kt:337-348` (`LazyColumn` item cells)

**Symptom:** During live cell updates or row reordering, Compose can reuse
item row state for the wrong judge row.

**Root cause:** Cell rows are emitted with `items(itemCells.size)` instead
of stable keys such as cell id or judge key.

**Reproduction:** Open the L2 screen while a TransRank run is still filling
cells, then trigger retries or hydration that changes row ordering.

**Proposed fix:** Use `items(itemCells, key = { it.id })` and key group
headers by stable translation row id.

**Status:** Fixed — 62e5e422 (stable Compose keys in the L2 cell list)

### Bug 9 - Severity: Medium - Category: TransRank score parsing
**Location:** `ai/src/main/java/com/ai/data/TranslatorRankModel.kt:147-150` (`parseScoreAndReason`)

**Symptom:** A malformed judge reply can receive a numeric score from the
reason text even when the first line did not contain a score.

**Root cause:** If no number is found in line 1, the parser searches the
entire response and uses the first number anywhere in the body.

**Reproduction:** Feed `Score: high\nReason: 2 strong points but weak tone`
to the parser. It can score the cell as `2` instead of treating the score as
missing.

**Proposed fix:** Only parse a score from an explicit score field/line, or
require a clear `score` label before falling back beyond the first line.

**Status:** Fixed — 59aa2aba (only read score from the score line, not reason text)

### Bug 10 - Severity: Medium - Category: Value View cost attribution
**Location:** `ai/src/main/java/com/ai/ui/report/view/ValueView.kt:235-247` (`modelKey`, fan-out fold-in)

**Symptom:** Fan-out response costs may not fold into Value View when model
ids differ only by case.

**Root cause:** The comment says matching is case-insensitive and
alias-resolved, but `modelKey` lowercases only the provider id and leaves
the model string unchanged.

**Reproduction:** Produce report/fan-out rows where the same model appears
as `GPT-4.1` in one row and `gpt-4.1` in another. The model sets do not
match.

**Proposed fix:** Normalize the model id in `modelKey` with
`model.trim().lowercase()` or the same canonical model resolver used by the
dispatch layer.

**Status:** Open

### Bug 11 - Severity: High - Category: Value View duplicate models
**Location:** `ai/src/main/java/com/ai/ui/report/view/ValueView.kt:240-247` (fan-out cost grouping)

**Symptom:** Fan-out costs can be assigned to multiple report agents when
two successful report agents use the same provider/model.

**Root cause:** `answererKeys` and `successKeys` are sets, and costs are
grouped by provider/model key. Duplicate agents collapse to one key, then
the grouped fan-out cost is assigned to every matching success agent.

**Reproduction:** Create a report with two successful agents using the same
provider/model, then run fan-out with matching answerers. Value View can
double-assign that model-level fan-out spend.

**Proposed fix:** Match by stable agent/source ids or compare counted
multisets rather than sets. If only model keys are available, do not fold in
cost when duplicates exist.

**Status:** Fixed — 56d128cc (don't fold fan-out cost when models are duplicated)

### Bug 12 - Severity: Medium - Category: Value View best-value ranking
**Location:** `ai/src/main/java/com/ai/ui/report/view/ValueView.kt:148-151` (`buildValuePoints`)

**Symptom:** Unknown or zero-cost models can dominate "Best value" even for
tiny quality differences.

**Root cause:** Best value is `quality / maxOf(costCents, 1e-6)`. A missing
or zero price becomes nearly infinite value rather than "unknown cost".

**Reproduction:** Include one model with missing/zero cost and one priced
model with slightly better quality. The unpriced model can receive the best
value badge.

**Proposed fix:** Separate zero/unknown cost from real zero-cost pricing,
hide the badge for unknown pricing, or rank unknown-cost points in a
separate category.

**Status:** Open

### Bug 13 - Severity: Medium - Category: Value View system UI
**Location:** `ai/src/main/java/com/ai/ui/report/view/ValueView.kt:590-598` (`FullGraphDialog`)

**Symptom:** Dismissing the expanded Value graph can leave system bars hidden
until another screen resets them.

**Root cause:** The dialog hides system bars in `DisposableEffect`, but
`onDispose` is empty.

**Reproduction:** Open the expanded Value graph and dismiss it on a device
where the dialog window does not restore bars automatically.

**Proposed fix:** Call `WindowInsetsControllerCompat(...).show(Type.systemBars())`
in `onDispose`, or explicitly save and restore the prior system UI state.

**Status:** Open

### Bug 14 - Severity: Low - Category: Value View gestures
**Location:** `ai/src/main/java/com/ai/ui/report/view/ValueView.kt:611-620` (`FullGraphDialog`)

**Symptom:** Tap-to-cycle ranking and pinch/drag zoom can conflict in the
expanded graph.

**Root cause:** Two separate `pointerInput` modifiers are installed on the
same box. One detects taps and one detects transforms, so pointer consumption
order can produce accidental rank cycling during transform gestures.

**Reproduction:** Open the expanded Value graph, pinch or drag near the
start/end of a gesture. A tap cycle can fire unexpectedly.

**Proposed fix:** Use one gesture detector that differentiates tap versus
transform, or disable tap cycling once movement/zoom exceeds a threshold.

**Status:** Open

### Bug 15 - Severity: High - Category: Compare persistence
**Location:** `ai/src/main/java/com/ai/viewmodel/CompareEngine.kt:94-108` (`hydrate`)

**Symptom:** Persisted Compare runs can disappear from the report UI when
the associated internal prompt is missing.

**Root cause:** Hydration drops the run when it cannot resolve
`group.first().metaPromptId` or a fallback compare prompt.

**Reproduction:** Create a Compare run, remove/import-overwrite the internal
prompt, then reopen the report. The persisted cells are no longer exposed.

**Proposed fix:** Hydrate a read-only run from row metadata and mark restart
actions unavailable until a runnable prompt is selected.

**Status:** Fixed — fbe2faea (Compare: hydrate persisted runs read-only when prompt is missing)

### Bug 16 - Severity: High - Category: Tournament persistence
**Location:** `ai/src/main/java/com/ai/viewmodel/TournamentEngine.kt:108-123` (`hydrate`)

**Symptom:** Persisted tournament rows can disappear from the UI after
internal prompt changes.

**Root cause:** The tournament hydrate path returns after failing to resolve
the stored/fallback tournament prompt, even though aggregate and match rows
exist on disk.

**Reproduction:** Run a tournament, remove or rename the tournament prompt,
restart/reopen the report. The tournament screen no longer shows the stored
run.

**Proposed fix:** Hydrate stored matches and aggregate with a missing-prompt
state; require a prompt only for restart/additional judging.

**Status:** Fixed — 9594bbd3 (Tournament: hydrate persisted runs read-only when prompt is missing)

### Bug 17 - Severity: High - Category: JudgeEval persistence
**Location:** `ai/src/main/java/com/ai/viewmodel/JudgeEvalEngine.kt:139-152` (`hydrate`)

**Symptom:** Judge-the-judges run history can become invisible after the
judge prompt or swarm is edited away.

**Root cause:** Hydration requires `judgePrompt(aiSettings)` or the stored
prompt id to resolve, otherwise it removes the run from state.

**Reproduction:** Run "Judge the judges", delete/rename the judge prompt,
then reopen the report.

**Proposed fix:** Render persisted rows read-only with a missing-prompt
warning and disable only actions that need live prompt resolution.

**Status:** Fixed — 0ca406bf (JudgeEval: hydrate persisted runs read-only when prompt is missing)

### Bug 18 - Severity: Medium - Category: Report agent chat cancellation
**Location:** `ai/src/main/java/com/ai/ui/report/manage/AgentChat.kt:158-176` (`sendTurn`)

**Symptom:** Leaving a report-agent chat while a stream is active can append
a generic failure message instead of treating navigation as cancellation.

**Root cause:** The coroutine catches `Exception`, which includes
`CancellationException`, and does not rethrow cancellation.

**Reproduction:** Start streaming in a report agent chat, then press Back.
The cancellation path can be handled as a model-call failure.

**Proposed fix:** Add a dedicated `catch (e: CancellationException) { throw e }`
before the generic exception handler.

**Status:** Fixed — 1b70bdd7 (treat cancellation as cancellation, not failure)

### Bug 19 - Severity: Medium - Category: Report agent chat persistence
**Location:** `ai/src/main/java/com/ai/ui/report/manage/AgentChat.kt:173-176` (`sendTurn`)

**Symptom:** A failed report-agent chat turn can show an assistant failure
message in memory but lose it after reopening.

**Root cause:** The exception handler appends the failure message to
`messages` but does not call `onSaveMessages`.

**Reproduction:** Force the report-agent chat call to fail, observe the
failure bubble, close and reopen the chat. The failure bubble may be gone.

**Proposed fix:** Persist `messages.toList()` after appending the failure
message, ideally on `Dispatchers.IO`.

**Status:** Fixed — 1111d75c (persist the failure bubble)

### Bug 20 - Severity: Medium - Category: Report agent chat state
**Location:** `ai/src/main/java/com/ai/ui/report/manage/AgentChat.kt:92-103` (`AgentChatScreen`)

**Symptom:** Opening agent chat for a different row in the same composition
can reuse the previous conversation and streaming state.

**Root cause:** `messages`, `params`, `isStreaming`, and related state are
plain `remember` values without keys tied to report id, agent id, or row id.

**Reproduction:** Navigate between two agent-chat contexts without fully
destroying the composable host. The second chat can inherit the first chat's
state.

**Proposed fix:** Key state with the stable chat target id, or hoist the
state into the caller keyed by target.

**Status:** Fixed — 48292c9c (key conversation/streaming state on the target)

### Bug 21 - Severity: Low - Category: TransRank confirmation state
**Location:** `ai/src/main/java/com/ai/ui/report/manage/Run.kt:151-162`, `ai/src/main/java/com/ai/ui/report/manage/Main.kt:403-407`

**Symptom:** The pending TransRank confirmation can disappear on
configuration change or process recreation.

**Root cause:** `pendingRank` / `rankPending` are plain `remember`
mutable states, not saveable.

**Reproduction:** Open the "Rank translators?" confirmation and rotate the
device. The pending confirmation can reset.

**Proposed fix:** Use `rememberSaveable` with a saver for the triple, keyed
by report id.

**Status:** Fixed — 65e46a81 (keep the rank confirm across rotation)

### Bug 22 - Severity: Low - Category: Secondary scope language state
**Location:** `ai/src/main/java/com/ai/ui/report/manage/SecondaryScope.kt:98-102`, `ai/src/main/java/com/ai/ui/report/manage/SecondaryScope.kt:140-157`

**Symptom:** A scope screen can submit stale language selections if the
available translation-language list changes while the screen is open.

**Root cause:** `pickedLanguages` is initialized from `languages` once and
is not keyed by the language list.

**Reproduction:** Open a secondary scope screen, create/delete translation
rows through another path or after a live run completes, then continue from
the stale scope screen.

**Proposed fix:** Key `pickedLanguages` by a stable language-list key and
intersect submitted selections with the current language set.

**Status:** Fixed — 0bf50625 (re-seed + intersect picked languages)

### Bug 23 - Severity: Medium - Category: Report launch language scope
**Location:** `ai/src/main/java/com/ai/ui/report/manage/Main.kt:1196-1235` (`showRerankPicker`, `showModerationPicker`)

**Symptom:** A runtime worker picker for rerank/moderation can run with a
stale language scope if the pending language scope changes while the picker
is open.

**Root cause:** The code captures `val ls = pendingLanguageScope` before
installing `RuntimeWorkerPick`, then resets `pendingLanguageScope`. The
picker confirm callback later uses the captured value.

**Reproduction:** Start rerank/moderation for a selected language, open the
runtime worker picker, then trigger another scope/language launch before
confirming the first picker.

**Proposed fix:** Store a dedicated immutable launch request object for the
picker, clear it only after completion/cancel, and prevent concurrent scope
launch requests.

**Status:** Fixed — 41a60af7 (snapshot launch language scope inside the effect)

### Bug 24 - Severity: Low - Category: Report manage copy confirmation
**Location:** `ai/src/main/java/com/ai/ui/report/manage/Run.kt:295`, `ai/src/main/java/com/ai/ui/report/manage/Run.kt:433-435`

**Symptom:** The "copy report" confirmation can be lost on rotation.

**Root cause:** `showCopyConfirm` is plain `remember`, not
`rememberSaveable`, while the surrounding Manage screen already preserves
many other overlay states.

**Reproduction:** Open the copy confirmation and rotate the device.

**Proposed fix:** Use `rememberSaveable(currentReportId)` for the
confirmation state.

**Status:** Fixed — cfff150b (keep copy-report confirm across rotation)

### Bug 25 - Severity: Medium - Category: Translation run delete state
**Location:** `ai/src/main/java/com/ai/ui/report/manage/TranslationRun.kt:265-278`

**Symptom:** Reload/delete confirmations for translation runs can disappear
if the screen recomposes after a configuration change.

**Root cause:** `confirmReload`, `confirmDelete`, and `deleting` are plain
`remember` values even though the route state is saveable.

**Reproduction:** Open a translation-run delete confirmation and rotate the
device.

**Proposed fix:** Use `rememberSaveable(runId)` for confirmation and
deleting state.

**Status:** Fixed — 622a1ced (keep delete/reload confirms across rotation)

### Bug 26 - Severity: Medium - Category: Report last-modified lookup
**Location:** `ai/src/main/java/com/ai/data/ReportStorage.kt:399-402` (`reportLastModified`)

**Symptom:** A caller can request the last-modified time for a non-flat
report id path, unlike the safer load/delete paths.

**Root cause:** `reportLastModified` constructs `File(dir, "$reportId.json")`
without `isSafeFlatId` validation or a canonical-child check.

**Reproduction:** Call `reportLastModified(context, "../x")` from a future
UI or import path. The helper checks outside the intended flat-id namespace.

**Proposed fix:** Apply the same `isSafeFlatId` guard and canonical-child
check used by `deleteReport` and save paths.

**Status:** Fixed — 57f4146f (guard reportLastModified flat-id like delete/save)

### Bug 27 - Severity: Low - Category: Value View row overflow
**Location:** `ai/src/main/java/com/ai/ui/report/view/ValueView.kt:647-659` (`ValueListRow`)

**Symptom:** Long provider/model labels plus the "Best value" badge can
crowd or overlap on narrow devices.

**Root cause:** The left column takes `weight(1f)` and the badge is fixed
text with no width cap or wrapping policy beyond the model label itself.

**Reproduction:** Use a long custom provider id and long model id on a small
screen, then open Value View.

**Proposed fix:** Give the badge a bounded width and ellipsis, or move it
to a second line when available width is small.

**Status:** Fixed — f15db8f6 (stop long labels crowding the badge in the list)

### Bug 28 - Severity: Medium - Category: TransRank cost deletion
**Location:** `ai/src/main/java/com/ai/viewmodel/TranslatorRankEngine.kt:467-472` (`deleteRun`)

**Symptom:** Deleting a TransRank run can subtract the wrong cost amount
from report totals when the broad deletion filter matches multiple runs.

**Root cause:** The cost delta is summed from every row matching
`translationRunId == sourceRunId`, so the same broad scope as Bug 1 feeds
`ReportStorage.bumpCostsFromDeletedItems`.

**Reproduction:** Create two TransRank attempts for the same source
translation run, then delete one. `costDelta` includes rows from both.

**Proposed fix:** After fixing deletion scope to selected run ids, compute
the cost delta from that same exact victim set.

**Status:** Fixed — ae5d1d9c (scope deleteRun to the selected run id (bugs 1, 28))

