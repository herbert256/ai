# Throttling, retries, and read timeouts

Network-side rate control. There are **two independent throttle
layers** plus two retry interceptors and a per-call read-timeout shim:

1. **`ProviderThrottle`** — a per-**hostname** gate (concurrency
   semaphore + sliding-window rate cap). Everything here is
   configurable per provider (overrides in
   `assets/providers/` + Settings → Provider edit) and falls
   through to a global default (`NetworkSettings.*`, exposed under
   Settings → Network settings).
2. **`ApiCallCaps`** — a set of process-wide coroutine
   `Semaphore` pools (`global` + one per flow: report, translation,
   fan-out, fan-meta, workers) that caps how many calls run at
   once, independent of which host they hit. The per-flow pools
   are no longer independently configurable or binding: the
   "Maximal API calls" screen now exposes a single global
   **Concurrent API calls** knob, and every sub-cap is sized to
   it. Configured under Settings → Network settings → **Maximal
   API calls**.

The two layers compose: a batch acquires its flow sub-cap →
`global` → the per-host gate, in that order. See
[Acquisition order](#acquisition-order-the-canonical-path).

> CLAUDE.md historically described only the per-host layer and
> claimed "429s retry up to 5× with 3s back-off". Both are
> wrong: the `ApiCallCaps` layer is separate and the real 429
> default is **3 retries / 1000 ms backoff** (user-tunable,
> per-provider-overridable).

## Rolling batch admission

`runThrottledBatch` interleaves hosts and admits at most
`max(64, globalMax)` jobs, bounded by the report work-item limit. Each
completion immediately admits another item; there are no fixed-window
barriers. Existing per-flow, global and host permits still control
actual HTTP concurrency. Cancelling one registered item leaves its
siblings running; cancelling the batch cancels the whole scope.

## Singletons

### `NetworkSettings` (`data/ApiTracer.kt`)

Live mirror of the user-tunable network knobs. The OkHttp
interceptors and `ProviderThrottle` read from here so they don't
have to thread a `Settings` reference through their constructors.
`AppViewModel` writes it on bootstrap (its `init` block's
`viewModelScope.launch`) and on every Settings update
(`updateGeneralSettings`).

| Field | Default | Use |
|---|---|---|
| `streamingReadTimeoutSec` | `BuildConfig.NETWORK_READ_TIMEOUT_SEC` = **240 s** | SSE chat / report streams |
| `nonStreamingReadTimeoutSec` | `BuildConfig.NETWORK_NONSTREAMING_READ_TIMEOUT_SEC` = **120 s** | analyze, meta, rerank, translate, model-list calls |
| `batchItemTimeoutSec` | `BuildConfig.BATCH_ITEM_TIMEOUT_SEC` = **180 s** | wall-clock ceiling for ONE batch item (fan-out pair, translation item, tournament match, judge / compare / transrank cell) — the whole per-item call incl. worker-chain fallbacks and retries, enforced by the engines' own `withTimeout`, not OkHttp |
| `maxCallsPerProviderPerMinute` | 60 | per-host sliding-window rate cap |
| `maxConcurrentCallsPerProvider` | 5 | per-host concurrency cap |
| `maxRetriesOn429` | 3 | in-line 429 retries |
| `retryBackoffMs429` | 1000 | base wait between 429 retries |
| `maxRetriesOn529` | 3 | in-line 529 (server-overloaded) retries |
| `retryBackoffMs529` | 1000 | base wait between 529 retries |

> Stale in-code comments in `ReadTimeout.kt` / `TestCallTimeout.kt`
> still mention a "10-minute" streaming read default. The actual
> static `OkHttpClient` read timeout is **240 s**
> (`NETWORK_READ_TIMEOUT_SEC`); `ReadTimeoutInterceptor` swaps in
> 120 s for non-streaming calls per-request.

### `ApiCallCaps` (`data/ApiTracer.kt`)

A flow-level layer of coroutine `Semaphore`s, entirely separate
from the per-host `ProviderThrottle`. There is now a **single
configurable cap** — the global one (`maxConcurrentApiCalls`,
default **100**). The five per-flow semaphores still exist
(`report`, `translation`, `fanOut`, `fanMeta`, `workers`) because
the throttle framework still acquires one alongside `global`, but
they are no longer independently configurable: `resetForNewLimits`
sizes every sub-cap to the global cap, so only the global ceiling
ever binds.

| Pool | Cap | Configurable |
|---|---|---|
| `global` | `maxConcurrentApiCalls` (default 100) | yes — the only knob |
| `report` | = global | no (kept for the acquisition contract) |
| `translation` | = global | no |
| `fanOut` | = global | no |
| `fanMeta` | = global | no |
| `workers` | = global | no |

`fanMeta` and `workers` still get **separate** semaphores from
each other (so a worker batch can't starve, or be starved by, the
fan-meta pool), but all six are now sized identically. All caps
are `@Volatile` and rebuilt at runtime via
`ApiCallCaps.resetForNewLimits(globalMax: Int)`, wired from
`AppViewModel` on bootstrap and whenever `maxConcurrentApiCalls`
changes. `snapshot()` / `diagnosticLine()` / `isBusy()` expose
in-flight-vs-max for the stall watchdog and the Live Dashboard.

> Note: the `ApiCallCaps` object's own field initialisers (100
> global / 50 per-flow) are only the cold-start values used before
> `AppViewModel` finishes loading settings; `resetForNewLimits` then
> re-sizes every sub-cap to the loaded `maxConcurrentApiCalls`. A
> fresh install and an in-app "Reset application" used to disagree
> on that loaded value — `loadGeneralSettings`'s absent-key
> fallbacks were hardcoded to 30/3/50 while the reset path used the
> `GeneralSettings` data-class defaults of 60/5/100 — until commit
> `7bdfb5f66` sourced every absent-key fallback from a
> `GeneralSettings()` instance instead, so both paths now converge
> on the same values (global cap **100**).

### `ProviderThrottle` (`data/ProviderThrottling.kt`)

Per-hostname rate + concurrency gate. One `Semaphore` per host
caps in-flight calls; a sibling `ConcurrentLinkedDeque` of call
timestamps enforces the 60-second sliding window. The gate is
keyed by **hostname**, so all providers sharing a host (and a
provider's `auxHosts`) share one set of limits; the host →
`AppService` mapping is `ProviderRegistry.findByHost`.

Three acquire entry points:

- **`acquire(host): Releaser`** — **blocking** (`Thread.sleep`).
  Used by the OkHttp `ProviderThrottleInterceptor`, which always
  runs on a dispatcher worker, never the main thread. Gates the
  **concurrency permit first** (`sem.acquire()`), then loops on the
  per-minute rate window, appending a timestamp on admission; if the
  rate loop throws (interrupt) the concurrency permit is released so
  nothing leaks. Returns a `Releaser` whose `release()` must run in a
  `finally`.
- **`tryAcquire(host): Outcome`** — **non-blocking**. Returns
  `Outcome.Acquired(Releaser)` or `Outcome.Blocked(availableAtMs)`.
  Checks the concurrency permit first and releases it on a
  rate-window miss so nothing leaks. This is what the batch
  flows poll.
- **`acquireOrWait(host): Releaser`** — **suspend**. Polls
  `tryAcquire` + `delay` so it never pins a thread; used by
  `ApiDispatch.withHostGate`. Supports a `throttleWaitObserver`
  ThreadLocal for surfacing "waiting" state in the Fan-Meta UI.

Caps are resolved per acquire by `limitsFor(host)`:
per-provider override (`AppService.maxCallsPerProviderPerMinute` /
`maxConcurrentCallsPerProvider`, matched via
`ProviderRegistry.findByHost`) → global default
(`NetworkSettings.*`), each `coerceAtLeast(1)`. A blank host
returns the global pair (no gate). Provider edits go through
`ProviderRegistry.save`, which calls
`ProviderThrottle.resetForNewLimits()` so the next acquire builds
fresh per-host semaphores at the new caps.

Each rate-limit / concurrency wait emits a DEBUG line under the
`Throttle` AppLog tag with the host and queue depth
(`rate-limit wait …ms on <host> (queue=N/limit)` /
`concurrent-cap wait …ms on <host> (cap=N)`), so the AppLog
viewer shows exactly where time goes when an action feels slow.

### `permitPreAcquired` (`ThreadLocal<Boolean>`)

True on threads where the calling flow already acquired the
per-host permit explicitly (every batch flow). The
`ProviderThrottleInterceptor` reads it on the OkHttp worker and
**skips its own `acquire`** — without the flag the interceptor
would double-count and halve effective concurrency for those
flows. Propagated across coroutine dispatcher hops via
`asContextElement` and copied onto OkHttp workers by
`TagPropagatingExecutor` (see [api-formats.md](api-formats.md)).

A sibling `backoffPermitYielder` ThreadLocal lets the 429/529
backoff sleep release the batch's held permits while it waits (so
shared capacity isn't pinned during a retry pause); see
[`PermitHold`](#acquirethrottledpermits-and-permithold).

## Interceptor chain

OkHttp application interceptors, outer → inner (assembled in
`data/ApiClient.kt`):

```
OkHttpCallContextInterceptor (restores per-call trace/throttle context)
  → RateLimitRetryInterceptor    (429 retries)
    → OverloadedRetryInterceptor   (529 server-overloaded retries)
      → ProviderThrottleInterceptor  (per-host acquire/release)
        → ReadTimeoutInterceptor   (per-call read-timeout swap)
          → TestCallTimeoutInterceptor  (Provider-test 30 s window)
            → TracingInterceptor   (writes the ApiTracer JSON)
              → HttpStatusStatsInterceptor  (tallies every response code)
                → upstream
```

`OkHttpCallContextInterceptor` (`data/OkHttpCallContext.kt`) is the
**outermost** interceptor. It restores the trace/throttle context
captured at OkHttp `Call` construction time, so a call OkHttp later
promotes from its queue on a different worker thread still carries
its originating tags / `permitPreAcquired` flag — making queued-call
promotion race-free.

Both retry interceptors sit **outside** the throttle / timeout /
tracing layers, so each retry re-acquires its own per-host permit,
a throttle wait doesn't count against the read-timeout window, and
each retry attempt produces its own trace and HTTP-status tally.
The 429 and 529 budgets are independent — a 529 burst can't eat
the 429 retry count. `HttpStatusStatsInterceptor` is innermost so
it counts every per-attempt response (and failures as `0`)
regardless of the tracing toggle.

A second client (`rawFetchClient`, used by the model-list snapshot
fetch `fetchUrlAsString`) reuses the same builder but **drops both
retry interceptors** — its chain is `OkHttpCallContext →
ProviderThrottle → ReadTimeout → TestCallTimeout → Tracing →
HttpStatusStats` — so a 429/529 on a best-effort sidecar fetch
doesn't pin the caller while the typed model-list request already
handles the real result.

The shared `OkHttpClient` deliberately sets the dispatcher's
`maxRequests = 512` and `maxRequestsPerHost = 512` so OkHttp gates
**nothing** — `ProviderThrottle` is the sole intended throttle.
(The old `maxRequestsPerHost = 5` default deadlocked against
`ProviderThrottle`: a call waiting on the app permit still
occupied an OkHttp per-host slot.)

### `RateLimitRetryInterceptor` (`data/RateLimitRetry.kt`)

Loops on HTTP **429**, sleeping between attempts up to
`maxRetries`. Caps resolve per 429 via
`ProviderThrottle.retryLimitsFor429(host)` (override → global;
`maxRetries` coerced ≥ 0, `backoffMs` coerced ≥ 1) so a settings
change while a call is in flight takes effect on the next
iteration. `maxRetries == 0` is a valid "no in-line retries".

The in-line retry loop **only runs when a `backoffPermitYielder` is
registered** — i.e. the call is part of a throttled batch that can
release its held permits during the sleep. For any other call (chat,
single non-batch dispatches), `maxRetries` is forced to `0` and the
429 is returned immediately so the coroutine layer can retry without
a thread sitting on a sleeping OkHttp worker. The `suppressInlineRetry`
ThreadLocal (the "Test all models" sweep) also forces `maxRetries = 0`.

Backoff is **exponential with equal jitter**: `(backoffMs shl
attempt)` capped at 30 000 ms, jittered ±50 %; a server
`Retry-After` (seconds or HTTP-date, clamped 1 ms…5 min) wins when
present. The sleep delegates to `ProviderThrottle.backoffSleep`,
which routes through `backoffPermitYielder` (releasing batch
permits during the pause) when one is registered, else plain
`Thread.sleep`. Each attempt is recorded to `RetryStats`
(`data/RetryStats.kt`) — `record()` + `enterBackoff()`/`exitBackoff()`
brackets — for the Live Dashboard's live retry-pressure readout
(attempts in the trailing 5 min + calls currently parked in backoff).

Hard guards:

- **Main-thread check** — `Looper.myLooper() == getMainLooper()`
  returns the 429 immediately instead of looping (would ANR).
- **`chain.call().isCanceled()`** — bails the loop the moment the
  caller cancels.
- **Closes the previous response** before reissuing — a left-open
  body leaks an OkHttp connection.
- **`suppressInlineRetry`** ThreadLocal (set by the "Test all
  models" sweep) forces `maxRetries = 0`.

It also **benches the model** (`ModelCooldownStore.markUnavailable`)
instead of retrying for: Gemini per-day quota (resets next Pacific
midnight), Cohere trial-key monthly cap (next month), out-of-credits
/ spending-limit billing 429s (6 h), and any `Retry-After` longer
than `LONG_RETRY_THRESHOLD_MS`. See [model-states.md](model-states.md).

#### Short-bench-and-requeue (type-A fixed-model batches)

When a **type-A** batch (Fan Out, Judge the judges, Rank the
translators — where a model can't be swapped for another) runs an
item, `runThrottledBatch` installs a per-item `ProviderThrottle.benchSignal` (a fresh
`AtomicBoolean` per attempt). On a transient 429/529 (the long-bench
cases above didn't fire) and when `ModelCooldownStore.typeABenchEnabled`,
the interceptor **short-benches** the model via
`ModelCooldownStore.markShortBench` — for the response's `Retry-After`
hint, or `ModelCooldownStore.typeABenchBaseMs` (default **10 000 ms**)
when there's none, clamped to **1 s … 5 min** — sets the signal, and
returns immediately (no in-line sleep). The batch loop then **re-queues**
the item instead of erroring, and **gates its same-model siblings** on the
bench so they don't fire doomed calls; everything returns to Queue
when the bench lifts. After `typeABenchMaxAttempts` (default 5)
consecutive benches the item is left errored. The short-bench map is
session-only (not persisted) and **separate** from the long `cooldownMap`
so model pickers don't flicker "rate-limited" for a 10 s blip; it drives
the **Bench** stat column (parked items, not just errored ones).

`AnalysisRepository.withRetry` returns a signalled bench failure to this
outer scheduler without an extra immediate retry. A successful result or
permanent failure clears an earlier bench signal, so a recovered answer
cannot be discarded and generated again.

The three knobs live on `GeneralSettings` (`typeABenchEnabled` /
`typeABenchSeconds` (10) / `typeABenchMaxAttempts` (5)) and are mirrored
onto `ModelCooldownStore` (`typeABenchEnabled` / `typeABenchBaseMs` =
`typeABenchSeconds` × 1000 / `typeABenchMaxAttempts`) by `AppViewModel`.
Tunable under Settings → Network settings → **Model bench
(fixed-model batches)** ("Bench fixed-model batches on 429 / 529");
off → the in-line retry loop runs as before. Worker-swarm (type-B)
batches don't use it — they fall back to another model instead.

### `OverloadedRetryInterceptor` (`data/OverloadedRetry.kt`)

The HTTP **529** (server-overloaded, Anthropic `overloaded_error`)
sibling of the 429 path, with an **independent** retry budget
(`maxRetriesOn529` / `retryBackoffMs529`). Same main-thread guard,
cancellation check, close-before-reissue, exponential-jitter
backoff, `Retry-After` honoring, `RetryStats` accounting, and the
same type-A bench-and-requeue (it reads `benchSignal` and benches
via `ModelCooldownStore.markShortBench`). Caps resolve per 529 via
`ProviderThrottle.retryLimitsFor529(host)`.

**One asymmetry with the 429 path**: the 529 loop does *not* gate on
`backoffPermitYielder` presence — it runs the in-line retry whenever
`maxRetries > 0` (off the main thread), suppressed only by
`suppressInlineRetry`. So a non-batch 529 is retried in line, whereas
a non-batch 429 is handed back for coroutine-level retry.

### `ProviderThrottleInterceptor`

Passes through on the main thread (ANR guard) and skips its own
acquire when `permitPreAcquired == true`. Otherwise calls
`ProviderThrottle.acquire(request.url.host)` pre-`proceed` and
releases in `finally`.

### `ReadTimeoutInterceptor` (`data/ReadTimeout.kt`)

Per-call read-timeout shim. Without it every call would inherit
the client's static streaming read timeout (240 s), which is fine
for SSE but disastrous for a short non-streaming call — one hung
provider would then gate the slot for four minutes. Streaming
detection runs pre-`proceed` against the request:

- Gemini URL contains `:streamGenerateContent` → streaming;
  `:generateContent` → non-streaming.
- POST body matches `"stream"\s*:\s*true` → streaming.
- Otherwise (GET model-list calls, …) → non-streaming.

The body bytes are read off a `Buffer.snapshot()` so the original
request body stays untouched.

### `TestCallTimeoutInterceptor` (`data/TestCallTimeout.kt`)

When the calling thread is inside a
`withTraceCategory("Provider test")` block (Refresh-All's
per-provider Test step, the Test button, raw-JSON submit), it
overrides connect + read timeouts to
`BuildConfig.TEST_CONNECTION_READ_TIMEOUT_SEC` (**30 s**). Sits
ahead of `ReadTimeoutInterceptor` so the test window wins
regardless of which branch `ReadTimeoutInterceptor` would have
picked.

## Coroutine-layer guards (`ApiDispatch.kt`)

Beyond the OkHttp interceptors, single non-streaming dispatches
and stream-opens are wrapped at the coroutine layer:

- **`withHostGate(baseUrl) { … }`** acquires the per-host
  `ProviderThrottle` gate via the suspend `acquireOrWait`, unless
  `permitPreAcquired` is already set (the batch flows pre-acquire).
  It sits **outside** `withApiCallTimeout` so a legitimate
  per-minute-window wait doesn't trip the DNS-hang timeout, and so
  the wait doesn't occupy an OkHttp dispatcher slot.
- **`withApiCallTimeout { … }`** wraps each single request / stream
  *open* (not the SSE read loop) in `withTimeout(ceiling)` to guard
  against indefinite DNS hangs (which OkHttp's connect/read/write
  timeouts don't catch — they only start after DNS resolves).
  Ceiling = `nonStreamingReadTimeoutSec + NETWORK_CONNECT_TIMEOUT_SEC
  + 30 s`; a timeout rethrows as a plain `java.io.IOException` so
  existing `catch (Exception)` paths treat it as transient.

## Acquisition order (the canonical path)

Every batch flow acquires permits in one canonical order:

```
flow sub-cap  →  ApiCallCaps.global  →  per-host gate
```

The private sub-cap is taken **before** `global` so a flow queued
on its own cap holds nothing shared; `global` is always taken
before the host gate (the reverse deadlocked report-vs-metadata
calls).

The **key invariant** (hardened by commit `6ab023c9c`): while
**parked** on a busy per-host gate, the flow holds **neither** the
sub-cap **nor** `global` — both are released before each back-off
`delay` and re-taken on the next poll. So a per-flow cap (e.g.
`report = 50`) counts only items holding a **live provider slot**
(real in-flight TCP calls), not items queued behind a saturated
provider that would otherwise starve other hosts' work.

### `acquireThrottledPermits` and `PermitHold`

`acquireThrottledPermits(subCap, host, onThrottled, onCleared)`
(`viewmodel/ReportViewModelHelpers.kt`) loops:

1. `subCap.acquire()`
2. `ApiCallCaps.global.acquire()`
3. under a per-host fairness `Mutex`, `ProviderThrottle.tryAcquire(host)`

On `Acquired` it hands all three permits to a `PermitHold`
(`viewmodel/ThrottledBatch.kt`) and returns. On `Blocked` it
releases sub + global, fires `onThrottled` once, and `delay`s
until `availableAtMs`. `global` is hard-coded internally as the
shared cap, so callers pass only the flow sub-cap.

`PermitHold` owns the three nested permits for the item's whole
lifetime:

- **`yieldFor(ms)`** — the 429/529 backoff hook (registered as
  `backoffPermitYielder`): releases all three, sleeps `ms` holding
  nothing, then re-acquires in sub → global → host order.
- **`dispose()`** — the per-item `finally` release; gives back
  whatever is still held, exactly once.

Both run under one lock with `held` / `done` flags so a
cancellation racing a mid-flight backoff can neither double-release
(which would inflate a semaphore's permit count and break the cap)
nor leak.

### `runThrottledBatch`

`runThrottledBatch(items, hostOf, subCap, …, dynamicHost, body)`
(`viewmodel/ThrottledBatch.kt`) is the shared driver for the
per-row batch flows. It `interleaveByHost`es the items, then per
item acquires via `acquireThrottledPermits` and runs `body` with
`permitPreAcquired = true` + `backoffPermitYielder` set. Two modes:

- **Fixed-host** (default) — `host = hostOf(item)`, `permitPreAcquired`
  set so each inner call skips the interceptor's own gate. Used by
  Fan-out, Judge-the-judges and Rank-the-translators — each item's
  judge/answerer provider is resolved up front (a direct
  `AnalysisRepository.analyzeWithAgent` / `runFixedJudgeCall`, not
  the worker round-robin), so the batch throttles per-host like any
  fixed-model call.
- **Dynamic-host** (`dynamicHost = true`, used by the worker-pick
  flows — Fan-Meta, Tournament, Compare, Translation) — the host is
  unknown until the worker chain picks one inside `body`, so it
  acquires only `subCap` + `global` (blank host = no-op gate) and
  does **not** set `permitPreAcquired`; each inner worker call
  self-throttles its own provider host via the interceptor.

The legacy single-host `acquireOrRequeue`
(`ReportViewModelHelpers.kt`) still exists for a few call sites.

## Who uses which path

- **Report-primary** generation — all 7 dispatch sites in
  `ReportViewModel` use
  `acquireThrottledPermits(ApiCallCaps.report, providerHost(provider))`
  + `permitHold.dispose()` in `finally`. Commit `6ab023c9c`
  replaced the old nested `ApiCallCaps.global.withPermit {
  ApiCallCaps.report.withPermit { acquireOrRequeue(host) … } }`
  pattern, which held `global` + `report` idle while parked on a
  saturated host's gate.
- **Fan-out** (`FanOutEngine`) — the batched per-pair path uses
  `runThrottledBatch(subCap = ApiCallCaps.fanOut)`; the single-call
  path uses `ApiCallCaps.fanOut.withPermit { … permitPreAcquired … }`.
  The `fanOut` sub-cap is also taken by `MetaEditManager` and
  `SecondaryModelSwitchManager`.
- **Fan-Meta / icon generation** (`IconGenerationManager` — per-pair
  title+icon, per-report / per-model / per-language / per-agent icon
  fan-outs) — `runThrottledBatch` in dynamic-host mode,
  `subCap = ApiCallCaps.fanMeta`; each worker self-throttles its host.
- **Worker swarms** — `subCap = ApiCallCaps.workers` via
  `runThrottledBatch`, but the four engines split into two shapes:
  - **Tournament** (`TournamentEngine`) and **Compare**
    (`CompareEngine`) run **dynamic-host**: each match / cell
    dispatches through the shared `WorkerRunner` round-robin chain
    (`runPooledItemCall`), so the provider is unknown up front and
    each worker self-throttles its own host.
  - **Judge-the-judges** (`JudgeEvalEngine`) and **Rank-the-translators**
    (`TranslatorRankEngine`) run **fixed-host** instead: each cell
    names one specific judge model up front (`runFixedJudgeCall`,
    not the worker chain) and is a type-A bench batch like Fan-out —
    a 429/529 short-benches that judge and re-queues the cell rather
    than falling back to another model.

  See [report-icons.md](report-icons.md) for the icon chains. Rerank
  / Moderation / chat-type Meta / Fan-in
  (see [secondary-results.md](secondary-results.md)) do **not** go
  through this pool at all: `SecondaryRunManager.runSecondaryViaSwarm`
  runs their fallback-chain loop by hand (not `WorkerRunner`, not
  `runThrottledBatch`) and gates each attempt on
  `ApiCallCaps.global.withPermit` alone — no dedicated sub-cap.
- **Translation** — `runThrottledBatch` in dynamic-host mode,
  `subCap = ApiCallCaps.translation` (`TranslationRunManager`); each
  item runs the same `WorkerRunner` chain as Tournament/Compare, just
  under its own sub-cap instead of `workers`. See
  [translation.md](translation.md).
- **Chat** and single non-batched calls go through `withHostGate`
  + `withApiCallTimeout` and let `ProviderThrottleInterceptor`
  acquire the host gate inline (no pre-acquire).

## Live batch stats (Bench / Wait surfaces)

Every running-batch screen renders the same strip via the shared
**`BatchStatsRow`** (`ui/report/manage/BatchStats.kt`) — a two-row
label/value block, canonical column order:

```
Total · Done · Error · Run · [Bench] · Wait · Queue · Costs
```

The counts come from **`deriveBatchCounts` / `deriveBatchSummary`**
(`ui/report/manage/BatchCounts.kt`), which carve each item into
exactly one bucket with this precedence:

```
DONE → Bench (per benchMode) → ERROR → Wait (throttled) → Run (RUNNING) → Queue (PENDING)
```

Two of the columns are direct surfaces of the throttle layer:

- **Wait** — items currently parked on a per-host / sub-cap / global
  gate (the `throttledIds` set the batch flow tracks). A throttled
  item counts only under Wait, never also under Run — uniform across
  every batch kind.
- **Bench** — items whose model is short-benched
  (`ModelCooldownStore.markShortBench`). Shown **only** for
  fixed-model batches (`BatchFamily.FIXED_MODEL` → `BenchMode.MODEL_PARKED`:
  Fan Out, Judge the judges, Rank the translators); a benched model
  carves **all** of its non-done items out of Error/Run/Wait/Queue
  into Bench. Worker-swarm batches (`BatchFamily.WORKER_POOL` →
  `BenchMode.NONE`: Tournament, Compare, Translation, Fan Meta) omit
  the column — a cooldown there is a worker-selection concern, not a
  terminal result.

## Fan Out HTTP statistics

Fan Out surfaces a per-run **HTTP statistics** breakdown
(`ui/report/manage/FanStats.kt`, rendered through the same
`BatchStatsRow`). The data is a session-only per-run tally,
**`RunHttpStats`** (`data/RunHttpStats.kt`), keyed by
`(runId, "providerId|model")` and recorded by
`HttpStatusStatsInterceptor` — the innermost interceptor — so the
**429/529 retry loops re-run it on every attempt**: a 429 that's
retried then succeeds shows as one 429 **+** one 200, surfacing the
rate-limit pressure the per-item *final* status would hide. Buckets
(`FanOutHttpStatusCounts` in `data/FanOutHttpStats.kt`):
`ok200` / `rate429` / `overloaded529` / `client4xx` / `server5xx` /
`other`. Memory-only; `hasRun(runId)` gates the icon so nothing shows
after a restart.

This is distinct from the process-wide **`HttpStatusStats`**
(`data/HttpStatusStats.kt`) the same innermost interceptor also feeds:
a rolling 5-minute ring of bucketed codes (`OK2XX` / `R429` / `C4XX` /
`S5XX` / `OTHER`), response-time percentiles, recent-error feed and
slowest-call list, for the Live Dashboard.

## Per-provider overrides

Six nullable fields on `AppService` (declared in
`assets/providers/`, editable in the UI):

```kotlin
val maxCallsPerProviderPerMinute: Int? = null,
val maxConcurrentCallsPerProvider: Int? = null,
val maxRetriesOn429: Int? = null,
val retryBackoffMs429: Long? = null,
val maxRetriesOn529: Int? = null,
val retryBackoffMs529: Long? = null,
```

`null` = inherit the global default. Resolved by
`ProviderThrottle.limitsFor(host)` (rate / concurrency) and
`retryLimitsFor429(host)` / `retryLimitsFor529(host)` (retries),
each clamping to a safe minimum — 1 for rate / concurrency, 0 for
retry counts (0 = no in-line retries), 1 ms for backoff.

The override fields live on the **Provider edit** screen
(`ui/settings/ServiceSettingsScreens.kt`, the "Throttle & retry
overrides" card; reached via Settings → AI Setup → Providers →
edit a provider). Each field shows "blank = default"; a saved
value `≤ 0` for rate/concurrency, or non-numeric for retries,
falls back to `null` (inherit). **Refresh All** never silently
overwrites a user-set value: the asset-sync paths
(`importFromAsset`, `upsertFromJson`, `syncFromAsset`) consult
`ProviderFieldTimestamps` and skip fields the user has already
edited.

The **Per-provider throttling** list (`PerProviderThrottlingSubScreen`
in `SettingsScreen.kt`) sorts providers that have a rate or
concurrency override (`maxCallsPerProviderPerMinute` /
`maxConcurrentCallsPerProvider` non-null) to the top, then
alphabetically by id (commit `9fe78b89`).

## Files

| File | Holds |
|---|---|
| `data/ApiTracer.kt` | `NetworkSettings`, `ApiCallCaps` |
| `data/ProviderThrottling.kt` | `ProviderThrottle` (+ `permitPreAcquired`, `backoffPermitYielder`, `benchSignal`, `throttleWaitObserver`) and `ProviderThrottleInterceptor` |
| `data/RateLimitRetry.kt` | `RateLimitRetryInterceptor` (429) |
| `data/OverloadedRetry.kt` | `OverloadedRetryInterceptor` (529) |
| `data/ReadTimeout.kt` | `ReadTimeoutInterceptor` |
| `data/TestCallTimeout.kt` | `TestCallTimeoutInterceptor` |
| `data/TracingInterceptor.kt` | `TracingInterceptor` |
| `data/OkHttpCallContext.kt` | `OkHttpCallContextInterceptor` (outermost; restores per-call context for queued-call promotion) |
| `data/HttpStatusStats.kt` | `HttpStatusStats` (process-wide 5-min ring) + `HttpStatusStatsInterceptor` (innermost) |
| `data/RunHttpStats.kt` | per-run Fan Out HTTP-response tally |
| `data/FanOutHttpStats.kt` | Fan Out HTTP bucket data classes |
| `data/RetryStats.kt` | `RetryStats` — live retry-pressure counters for the dashboard |
| `data/ModelCooldownStore.kt` | long `cooldownMap` + session-only short-bench map; `typeABench*` mirror fields |
| `data/ApiClient.kt` | assembles the interceptor chain + 512/512 dispatcher on the shared `OkHttpClient`; `rawFetchClient` (no retries) |
| `data/ApiDispatch.kt` | `withHostGate`, `withApiCallTimeout` |
| `viewmodel/ThrottledBatch.kt` | `runThrottledBatch`, `PermitHold` |
| `viewmodel/ReportViewModelHelpers.kt` | `acquireThrottledPermits`, `acquireOrRequeue` |
| `ui/report/manage/BatchStats.kt` | shared `BatchStatsRow` strip |
| `ui/report/manage/BatchCounts.kt` | `deriveBatchCounts` / `deriveBatchSummary`, `BenchMode`, `BatchFamily` |
| `ui/report/manage/FanStats.kt` | Fan Out HTTP-statistics screen |
| `data/AppService.kt` | the six nullable override fields |
| `data/ProviderRegistry.kt` | calls `ProviderThrottle.resetForNewLimits()` from `save`; `findByHost` host index |
| `data/ProviderFieldTimestamps.kt` | per-provider per-field edit timestamps the asset-sync paths consult |
| `viewmodel/AppViewModel.kt` | mirrors `GeneralSettings.*` into `NetworkSettings` + `ApiCallCaps.resetForNewLimits` + `ModelCooldownStore.typeABench*` on bootstrap and on update |
| `ui/settings/SettingsScreen.kt` | the **Network settings** sub-screen (incl. the **Model bench** section) + its **Maximal API calls** child (`SETTINGS_NETWORK` / `SETTINGS_NETWORK_API_CALLS`) |
| `ui/settings/ServiceSettingsScreens.kt` | the per-provider "Throttle & retry overrides" card |

## See also

- [api-formats.md](api-formats.md) — the dispatch / streaming layer
  these throttles wrap, and `TagPropagatingExecutor`.
- [costs.md](costs.md) — `PricingCache` and cost tracking (shares
  `ApiCallCaps` documentation).
- [model-states.md](model-states.md) — cooldowns the 429 path writes.
- [report-icons.md](report-icons.md), [translation.md](translation.md),
  [secondary-results.md](secondary-results.md) — the batch flows that
  pre-acquire.
