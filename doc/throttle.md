# Per-provider throttle, 429 retry, read timeouts

Network-side rate control. Everything here is configurable per
provider (overrides in `assets/providers.json` + Settings →
Provider edit) and falls through to a global default
(`GeneralSettings.*`, exposed under Settings → Network).

## Singletons

`data/ApiTracer.kt` carries the three load-bearing singletons:

- **`NetworkSettings`** — live mirror of the user-tunable
  network knobs. The OkHttp interceptors read from here so
  they don't have to thread a `Settings` reference through
  their constructors. `AppViewModel.applyGeneralSettings`
  writes on bootstrap and on every Settings update.

  | Field | Default | Use |
  |---|---|---|
  | `streamingReadTimeoutSec` | `BuildConfig.NETWORK_READ_TIMEOUT_SEC` (10 min) | SSE chat / report streams |
  | `nonStreamingReadTimeoutSec` | `BuildConfig.NETWORK_NONSTREAMING_READ_TIMEOUT_SEC` | analyze, meta, rerank, translate, model-list, individual analyze calls |
  | `maxCallsPerProviderPerMinute` | 30 | sliding-window rate cap |
  | `maxConcurrentCallsPerProvider` | 3 | concurrency cap |
  | `maxRetriesOn429` | 3 | in-line 429 retries |
  | `retryBackoffMs429` | 1000 | wait between retries |
  | `maxRetriesOn529` | 3 | in-line 529 (server overloaded) retries |
  | `retryBackoffMs529` | 1000 | wait between 529 retries |

- **`ProviderThrottle`** — per-hostname rate + concurrency
  gate. One `Semaphore` per host caps in-flight calls; a
  sibling `ConcurrentLinkedDeque` of call timestamps enforces
  the 60-second sliding window.

  `acquire(host)` is **synchronous** — it blocks the calling
  thread (an OkHttp dispatcher worker, never the main thread)
  and returns a `Releaser` that must be invoked in a `finally`
  so the permit isn't leaked.

  Caps are resolved per acquire from per-provider override
  (`AppService.maxCalls…` / `maxConcurrent…`) → global default
  (`NetworkSettings.*`). Provider edits go through
  `ProviderRegistry.save`, which calls
  `ProviderThrottle.resetForNewLimits()` — the next acquire
  builds fresh per-host semaphores at the new caps.

  Each rate-limit / concurrency wait emits a DEBUG line under
  the `Throttle` tag with the host, queue depth, and elapsed
  wait, so the AppLog viewer shows exactly where time is
  going when an action feels slow.

- **`ProviderThrottle.permitPreAcquired`** —
  `ThreadLocal<Boolean>`. True on threads where the calling
  flow already acquired a permit explicitly (`FanOutEngine`,
  fan-icons batch, `ModelTestEngine`, Find alternative icons, the
  per-agent report-icon chain). `ProviderThrottleInterceptor`
  reads it on the OkHttp worker and skips its own acquire —
  without the flag the interceptor would double-count and halve
  effective concurrency for those flows.

  Propagated across coroutine dispatcher hops via
  `asContextElement` and copied onto OkHttp workers by
  `TagPropagatingExecutor`.

- **`ApiCallCaps`** — six application-level per-kind semaphores
  layered on top of the per-host throttle. Each dispatcher
  `withPermit`-wraps through the relevant kind's semaphore first,
  then through `ProviderThrottle.acquire(host)`.

  | Cap | Default | Wraps |
  |---|---|---|
  | `global` | 50 | every API call across the app |
  | `report` | 15 | per-agent calls inside one report-gen run |
  | `translation` | 15 | total in-flight Translate calls (across runs) |
  | `fanOut` | 15 | fan-out pair calls (FanOutEngine) |
  | `fanIcons` | 15 | fan-icons batch calls (separate from fanOut) |

  A seventh knob, `GeneralSettings.maxTestApiCalls` (default 40), is
  read directly by `ModelTestEngine` to size its in-flight semaphore
  and is **not** mirrored into `ApiCallCaps`.

  Mirrored from `GeneralSettings` on every Settings update.
  Surfaced under **Settings → Network → Maximal API calls**.

- **`ModelCooldownStore`** — separate from the throttle layer
  but the same network surface. A 429 with a Retry-After hint
  longer than `LONG_RETRY_THRESHOLD_MS = 1 h` benches the
  `(providerId, model)` pair until the hint expires. The 429
  interceptor calls `markUnavailable(providerId, model,
  availableAtMs, traceFile)`; pickers dim the row with a
  "rate-limited · back HH:mm" caption; engines skip benched
  pairs for the rest of the run. CRUD under AI Setup → AI
  Models setup → Model cooldowns. Persisted in its own
  SharedPreferences file (`model_cooldowns`) and round-tripped
  through backup. See [cooldowns.md](cooldowns.md).

## Interceptor chain

OkHttp application interceptors, outer → inner:

```
RateLimitRetryInterceptor   (handles 429 retries)
  → ProviderThrottleInterceptor   (acquires/releases per-host permits)
    → ReadTimeoutInterceptor   (per-call read timeout swap)
      → TestCallTimeoutInterceptor   (Provider-test 30 s window)
        → TracingInterceptor   (writes the ApiTracer JSON)
          → upstream
```

### `RateLimitRetryInterceptor`

Loops on 429, sleeping `backoffMs` between attempts up to
`maxRetries`. Caps are resolved per 429 via
`ProviderThrottle.retryLimitsFor(host)` so a settings change
while a call is in flight takes effect on the next iteration.
`maxRetries == 0` is a valid "no in-line retries" — the outer
`withRetry` layer still gets a chance.

Hard guards:
- Main-thread check — if anything ever sneaks through, the
  interceptor returns the 429 directly instead of sleeping
  (would ANR otherwise).
- `chain.call().isCanceled()` — bails the loop the moment the
  caller cancels.
- Closes the previous response before reissuing — a left-open
  body leaks an OkHttp connection.
- `408 / 425 / 429` are treated as transient by the outer
  `withRetry` layer too, so the suspend-level retry can layer
  on top.

### `ProviderThrottleInterceptor`

Skips its own acquire when `permitPreAcquired` is set.
Otherwise calls `ProviderThrottle.acquire(host)` pre-`proceed`,
releases in `finally`. Same main-thread bypass as above.

### `ReadTimeoutInterceptor`

Per-call read-timeout shim. Without it every call would
inherit the OkHttp client's static streaming timeout (10 min),
which is fine for SSE but disastrous for short non-streaming
calls — a single hung provider then gates the whole batch for
minutes.

Streaming detection runs pre-proceed against the request:

- Gemini URL contains `:streamGenerateContent` → streaming;
  `:generateContent` → non-streaming.
- POST request body contains `"stream": true` → streaming.
- Otherwise (GET model-list calls, …) → non-streaming.

The body bytes are read off a `Buffer.snapshot()` so the
original request body stays untouched.

### `TestCallTimeoutInterceptor`

When the calling thread is inside a
`withTraceCategory("Provider test")` block (Refresh All's
per-provider Test step), overrides connect + read timeouts
down to 30 s. Sits ahead of `ReadTimeoutInterceptor` so the
test-specific window wins regardless of which branch
`ReadTimeoutInterceptor` would have picked.

## Per-provider overrides

Four nullable fields on `AppService`:

```kotlin
val maxCallsPerProviderPerMinute: Int? = null,
val maxConcurrentCallsPerProvider: Int? = null,
val maxRetriesOn429: Int? = null,
val retryBackoffMs429: Long? = null,
val maxRetriesOn529: Int? = null,
val retryBackoffMs529: Long? = null
```

Null = inherit the global default. Resolved by
`ProviderThrottle.limitsFor(host)` /
`retryLimitsFor(host)` (each clamps to a safe minimum — 1 for
rate / concurrency, 0 for retries, 1 ms for backoff).

The provider Settings → Network card exposes all four with
"inherit" placeholders next to the global value, and a
**Refresh All** never silently overwrites a user-set value —
the asset-driven sync paths (`importFromAsset`,
`upsertFromJson`, `syncFromAsset`) check
`ProviderFieldTimestamps` and skip fields the user has
already edited.

## Pre-acquire flows

Several flows pre-acquire to keep the request slot from being
held across an idle window. In every case the pre-acquired
permit is held over the actual network call (not the planning
code), and the `permitPreAcquired` context element tells the
inline interceptor to skip.

1. **`FanOutEngine`** — pre-acquires per pair before the HTTP
   call, releases after the response settles. The engine's
   internal IO semaphore (`maxConcurrentFanOutCalls`) bounds
   total in-flight pair coroutines. Pairs whose per-host
   throttle is full surface as `Throttled` on the L1 stats
   panel.
2. **Fan-icons batch** — same shape, separate budget
   (`maxConcurrentFanIconsCalls`).
3. **`ModelTestEngine`** — pre-acquires per probe; the
   `throttledKeys: StateFlow<Set<String>>` exposes which pairs
   are currently blocked in `ProviderThrottle.acquire` so the L1
   list can render the live "Throttled" badge.
4. **Per-agent report-icon chain** (`runReportIconsForAgent`)
   — each of the three tiers acquires its own permit on entry,
   releases on exit. See [report-icons.md](report-icons.md).
5. **Find alternative icons** (`startIconFanOut`) — same
   pattern.

## Files

- `data/ApiTracer.kt` — `NetworkSettings`, `ProviderThrottle`,
  `ProviderThrottleInterceptor`, `RateLimitRetryInterceptor`,
  `OverloadedRetryInterceptor`, `ReadTimeoutInterceptor`,
  `TestCallTimeoutInterceptor`, `ApiCallCaps` (the six
  per-kind semaphores).
- `data/ModelCooldownStore.kt` — singleton store + persisted
  `model_cooldowns` SharedPreferences file.
- `data/AppService.kt` — the four nullable override fields.
- `data/ProviderRegistry.kt` — calls
  `ProviderThrottle.resetForNewLimits()` from `save`.
- `data/ProviderFieldTimestamps.kt` — per-provider per-field
  edit timestamps; the asset-sync paths consult these to
  decide which fields to refresh from `providers.json`.
- `viewmodel/AppViewModel.kt` — `GeneralSettings.*` mirror,
  bootstrap + on-update propagation, the six `maxConcurrent*`
  knobs.
- `viewmodel/FanOutEngine.kt` / `viewmodel/ModelTestEngine.kt` —
  the largest pre-acquire consumers.
- `ui/settings/SettingsScreen.kt` — the **Network** card on
  the Settings → AI Setup → Provider edit screen, and the
  global **Maximal API calls** card on Settings → Network.
