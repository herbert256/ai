# TODO

Future work that's been discussed but not scheduled. Add new
items at the top; move resolved items to a commit message and
delete the entry.

## Foreground service for AI Report API calls

Today every long-running AI Report API call launches on
`appViewModel.viewModelScope`. That covers the primary report
fan-out (`ReportViewModel.generateGenericReports` →
`reportGenerationJob`), regeneration
(`RegenerateBatchEngine` — the phased orchestrator that replaced
the legacy one-shot `ReportViewModel.regenerateReport`), and the
secondary kinds: rerank / moderation / meta + fan-out / fan-in
(`SecondaryRunManager.runRerank` / `runModeration` /
`runMetaPrompt` / `runFanInPrompt`, delegating the fan-out
lifecycle to `FanOutEngine`), tournament / judges / compare
(`TournamentEngine` / `JudgeEvalEngine` / `CompareEngine`), and
translation (`TranslationRunManager`, state in `_translationRuns`).
The `viewModelScope` lifetime makes that work survive in-app
navigation, configuration changes, and short backgrounding — but
it does **not** survive:

- the user swiping the app away from Recents,
- the OS killing the process under memory pressure,
- a long phone-locked / Doze period.

When the process dies mid-call the on-disk rows stay as blank
placeholders (content blank, `errorMessage` null, `durationMs`
null). On the next launch — and on a periodic 30s sweep — the
resume machinery picks them up:

- `SecondaryRunManager.resumeStaleRunsForReport` is the
  cross-kind on-open orchestrator. It reconciles stalled
  translation runs, restarts missing translations, then fans out
  to the per-engine `resumeStaleRunsForReport` on `FanOutEngine`,
  `TournamentEngine`, and `JudgeEvalEngine`,
  relaunches interrupted Fan-Meta batches, re-issues
  single-call Meta/Rerank/Moderation placeholders, calls
  `RegenerateBatchEngine.reconcile`, and finally marks anything
  unrecoverable with the `"No data yet"` ❌ marker (the string
  was `"Interrupted by app restart"` before commit d2cbf97c;
  `SharedComponents.kt` still maps the old label to the new one).
- `SecondaryRunManager.startBackgroundResumeSweep` runs that
  orchestrator every 30s for every report newer than 7 days; its
  Job is stored on `AppViewModel.backgroundResumeSweepJob`.

That is a *recovery* (re-dispatch the placeholder rows from
scratch), not real background continuation of the in-flight
calls.

For genuinely OS-backgrounded work we would need a foreground
Service: an ongoing notification ("AI Report running — N agents
left") that gives the process a much higher kill priority and
gives the user a way to dismiss / cancel.

Sketch:

- New `ReportForegroundService` (one instance per active job set,
  not per-job — simpler notification UX).
- Start when `generateGenericReports` / `RegenerateBatchEngine`
  enqueue / `runMetaPrompt` / a fan-out / translation run kicks
  off; stop when the last in-flight job finishes
  (`reportGenerationJob` idle, `FanOutEngine` pair jobs drained,
  `TranslationRunManager` runs all settled, and no
  `RegenerateBatchEngine.orchestratorJobs` still RUNNING).
- Notification body: count of active agents / pairs / translations.
- Tap → open the relevant Report screen.
- POST_NOTIFICATIONS permission prompt on Android 13+.
- Service runs the coroutines on its own scope; the ViewModel
  drives state via the same `_agentResults` / `_translationRuns`
  flows it already exposes.

Tradeoffs / open questions:

- Notification UX: one persistent notification is OK; we
  should not spam one per agent.
- Cancellation: notification action button to Stop?
- Battery: Doze whitelisting is *not* needed — foreground
  Services are exempt while the notification is showing.
- Cost-of-failure: if the user dismisses the notification mid-
  run on Android 14+ (where users can dismiss FGS notifications),
  the Service stops and we fall back to today's resume-on-launch
  behaviour (`resumeStaleRunsForReport` + the 30s sweep).
  Acceptable.

Estimated effort: a day or two, including the notification UI
and the per-screen "is something running?" hook so the Service
starts / stops at the right boundaries.
