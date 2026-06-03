# Throttling, retries, and read timeouts

Network-side rate control. There are **two independent throttle
layers** plus two retry interceptors and a per-call read-timeout shim:

1. **`ProviderThrottle`** — a per-**hostname** gate (concurrency
   semaphore + sliding-window rate cap). Everything here is
   configurable per provider (overrides in
   `assets/providers.json` + Settings → Provider edit) and falls
   through to a global default (`NetworkSettings.*`, exposed under
   Settings → Network settings).
2. **`ApiCallCaps`** — a set of process-wide coroutine
   `Semaphore` pools (`global` + one per flow: report, translation,
   fan-out, fan-meta, workers). This caps how many calls a *flow*
   runs at once, independent of which host they hit. Configured
   under Settings → Network settings → **Maximal API calls**.

The two layers compose: a batch acquires its flow sub-cap →
`global` → the per-host gate, in that order. See
[Acquisition order](#acquisition-order-the-canonical-path).

> CLAUDE.md historically described only the per-host layer and
> claimed "429s retry up to 5× with 3s back-off". Both are
> wrong: the `ApiCallCaps` layer is separate and the real 429
> default is **3 retries / 1000 ms backoff** (user-tunable,
> per-provider-overridable).

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
from the per-host `ProviderThrottle`. Six pools:

| Pool | Default cap | Backed by `GeneralSettings.*` |
|---|---|---|
| `global` | 100 | `maxConcurrentApiCalls` |
| `report` | 50 | `maxConcurrentReportCalls` |
| `translation` | 50 | `maxConcurrentTranslationCalls` |
| `fanOut` | 50 | `maxConcurrentFanOutCalls` |
| `fanMeta` | 50 | `maxConcurrentFanMetaCalls` |
| `workers` | 50 | `maxConcurrentFanMetaCalls` (shared) |

`fanMeta` and `workers` share the **same** persisted knob
(`maxConcurrentFanMetaCalls`) but get **separate** semaphores, so
a worker batch can't starve (or be starved by) the fan-meta pool.
All caps are `@Volatile` and rebuilt at runtime via
`ApiCallCaps.resetForNewLimits(globalMax, reportMax,
translationMax, fanOutMax, fanMetaMax)` (`workersMax = fanMetaMax`),
wired from `AppViewModel` on bootstrap and whenever any of the five
concurrency settings change. `snapshot()` / `diagnosticLine()` /
`isBusy()` expose in-flight-vs-max for the stall watchdog and the
Live Dashboard.

> Note: the `ApiCallCaps` object's own field initialisers are
> 100/50, but `SettingsPreferences` reads different fallbacks when
> the prefs key is absent on a fresh install
> (`maxConcurrentApiCalls` 50, `maxConcurrentReportCalls` 15).
> The canonical defaults shown above are the `GeneralSettings`
> data-class defaults that get fed to `resetForNewLimits` once
> settings load.

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
  rate window **first** (appends a timestamp on admission even if
  the concurrency permit then blocks — the safe, over-throttle
  direction), then the concurrency permit. Returns a `Releaser`
  whose `release()` must run in a `finally`.
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
RateLimitRetryInterceptor    (429 retries)
  → OverloadedRetryInterceptor   (529 server-overloaded retries)
    → ProviderThrottleInterceptor  (per-host acquire/release)
      → ReadTimeoutInterceptor   (per-call read-timeout swap)
        → TestCallTimeoutInterceptor  (Provider-test 30 s window)
          → TracingInterceptor   (writes the ApiTracer JSON)
            → HttpStatusStatsInterceptor  (tallies every response code)
              → upstream
```

Both retry interceptors sit **outside** the throttle / timeout /
tracing layers, so each retry re-acquires its own per-host permit,
a throttle wait doesn't count against the read-timeout window, and
each retry attempt produces its own trace and HTTP-status tally.
The 429 and 529 budgets are independent — a 529 burst can't eat
the 429 retry count. `HttpStatusStatsInterceptor` is innermost so
it counts every per-attempt response (and failures as `0`)
regardless of the tracing toggle.

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

Backoff is **exponential with equal jitter**: `(backoffMs shl
attempt)` capped at 30 000 ms, jittered ±50 %; a server
`Retry-After` (seconds or HTTP-date, clamped 1 ms…5 min) wins when
present. The sleep delegates to `ProviderThrottle.backoffSleep`,
which routes through `backoffPermitYielder` (releasing batch
permits during the pause) when one is registered, else plain
`Thread.sleep`.

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

### `OverloadedRetryInterceptor` (`data/OverloadedRetry.kt`)

The HTTP **529** (server-overloaded, Anthropic `overloaded_error`)
sibling of the 429 path, with an **independent** retry budget
(`maxRetriesOn529` / `retryBackoffMs529`). Same main-thread guard,
cancellation check, close-before-reissue, exponential-jitter
backoff, and `Retry-After` honoring. Caps resolve per 529 via
`ProviderThrottle.retryLimitsFor529(host)`.

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
  set so each inner call skips the interceptor's own gate.
- **Dynamic-host** (`dynamicHost = true`, used by the worker-pick
  flows — Fan-Meta, Tournament, Judges, Compare) — the host is
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
- **Fan-Meta / Tournament / Judges / Compare** — `runThrottledBatch`
  in dynamic-host mode (`subCap = ApiCallCaps.fanMeta` /
  `ApiCallCaps.workers`); each worker self-throttles its host.
- **Worker engine** (per-report icon, per-model title/icon,
  Find-alternative icons) — worker chains throttle through the
  same `ApiCallCaps.workers` + per-host gate; see
  [report-icons.md](report-icons.md).
- **Translation** — `ApiCallCaps.translation`; see
  [translation.md](translation.md).
- **Chat** and single non-batched calls go through `withHostGate`
  + `withApiCallTimeout` and let `ProviderThrottleInterceptor`
  acquire the host gate inline (no pre-acquire).

## Per-provider overrides

Six nullable fields on `AppService` (declared in
`assets/providers.json`, editable in the UI):

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

## Files

| File | Holds |
|---|---|
| `data/ApiTracer.kt` | `NetworkSettings`, `ApiCallCaps` |
| `data/ProviderThrottling.kt` | `ProviderThrottle` (+ `permitPreAcquired`, `backoffPermitYielder`, `throttleWaitObserver`) and `ProviderThrottleInterceptor` |
| `data/RateLimitRetry.kt` | `RateLimitRetryInterceptor` (429) |
| `data/OverloadedRetry.kt` | `OverloadedRetryInterceptor` (529) |
| `data/ReadTimeout.kt` | `ReadTimeoutInterceptor` |
| `data/TestCallTimeout.kt` | `TestCallTimeoutInterceptor` |
| `data/TracingInterceptor.kt` | `TracingInterceptor` |
| `data/ApiClient.kt` | assembles the interceptor chain + 512/512 dispatcher on the shared `OkHttpClient` |
| `data/ApiDispatch.kt` | `withHostGate`, `withApiCallTimeout` |
| `viewmodel/ThrottledBatch.kt` | `runThrottledBatch`, `PermitHold` |
| `viewmodel/ReportViewModelHelpers.kt` | `acquireThrottledPermits`, `acquireOrRequeue` |
| `data/AppService.kt` | the six nullable override fields |
| `data/ProviderRegistry.kt` | calls `ProviderThrottle.resetForNewLimits()` from `save`; `findByHost` host index |
| `data/ProviderFieldTimestamps.kt` | per-provider per-field edit timestamps the asset-sync paths consult |
| `viewmodel/AppViewModel.kt` | mirrors `GeneralSettings.*` into `NetworkSettings` + `ApiCallCaps.resetForNewLimits` on bootstrap and on update |
| `ui/settings/SettingsScreen.kt` | the **Network settings** sub-screen + its **Maximal API calls** child (`SETTINGS_NETWORK` / `SETTINGS_NETWORK_API_CALLS`) |
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
