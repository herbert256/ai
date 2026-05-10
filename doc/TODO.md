# TODO

Future work that's been discussed but not scheduled. Add new
items at the top; move resolved items to a commit message and
delete the entry.

## Foreground service for AI Report API calls

Today every long-running AI Report API call (report generation,
regenerate, secondaries: rerank / fan-out / fan-in / meta /
translate) launches on `appViewModel.viewModelScope`. That makes
the work survive in-app navigation, configuration changes, and
short backgrounding — but it does **not** survive:

- the user swiping the app away from Recents,
- the OS killing the process under memory pressure,
- a long phone-locked / Doze period.

When the process dies mid-call the on-disk rows stay as blank
placeholders; `recoverStaleSecondariesAsync` /
`resumeStaleFanOutPairs` mark them "Interrupted by app restart"
or re-enqueue, which is a recovery, not real background
continuation.

For genuinely OS-backgrounded work we would need a foreground
Service: an ongoing notification ("AI Report running — N agents
left") that gives the process a much higher kill priority and
gives the user a way to dismiss / cancel.

Sketch:

- New `ReportForegroundService` (one instance per active job set,
  not per-job — simpler notification UX).
- Start when `generateGenericReports` / `regenerateReport` /
  `runMetaPrompt` / etc. kicks off; stop when the last in-flight
  job finishes (`reportGenerationJob` + `fanOutJobs` +
  translation jobs all idle).
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
  the Service stops and we're back to today's behaviour. Acceptable.

Estimated effort: a day or two, including the notification UI
and the per-screen "is something running?" hook so the Service
starts / stops at the right boundaries.
