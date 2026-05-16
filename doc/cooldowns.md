# Model cooldowns

When a provider answers a 429 with a Retry-After hint longer than
**1 hour**, the model gets benched in
[`ModelCooldownStore`](../ai/src/main/java/com/ai/data/ModelCooldownStore.kt)
until the hint expires. Every flow that picks a (provider, model)
pair — Fan-out, fan-icons, the model-test sweep, the report-icon
chain, every model picker — skips or dims benched pairs.

The threshold is the `LONG_RETRY_THRESHOLD_MS` constant on
`ModelCooldownStore` (= 60 × 60 × 1000 ms = 1 h). Anything below
goes through the normal 429 retry loop in
`RateLimitRetryInterceptor`; only the long-hint cases land here.

## Triggers

A handful of 429 shapes route through `markUnavailable`:

| Trigger | Behaviour |
|---|---|
| **Google quota exhausted** | per-day quota response; the hint resets at Pacific midnight. Benched until that wall clock |
| **Cohere monthly trial cap** | trial keys hit a long per-month cap; benched per provider, not per model |
| **Per-day token quotas** | several providers return Retry-After at Pacific midnight when the daily token bucket is empty |
| **Long Retry-After ≥ 1 h** | the generic catch — any 429 whose Retry-After exceeds the threshold benches the model regardless of source |

The interceptor captures the API trace whose 429 triggered the
cooldown and stores its filename alongside the expiry — the picker
deep-links into it so the user can see exactly what came back.

## Storage

Two maps in a dedicated SharedPreferences file
(`/data/data/com.ai/shared_prefs/model_cooldowns.xml`):

| Key | Type |
|---|---|
| `map` | JSON `Map<String, Long>` — `"$providerId:$model"` → `epoch-ms` expiry |
| `traces` | JSON `Map<String, String>` — `"$providerId:$model"` → trace filename |

The trace filename map is **device-local** — trace files don't travel
with backup/restore, so it's persisted alongside but not part of the
public `cooldowns: StateFlow<Map<String, Long>>` snapshot.

Expired entries are pruned lazily on read (`isUnavailable` /
`availableAt`) and on init.

## Public surface

```kotlin
object ModelCooldownStore {
    val cooldowns: StateFlow<Map<String, Long>>

    fun init(context: Context)
    fun markUnavailable(providerId, model, availableAtMs, traceFile?)
    fun isUnavailable(providerId, model): Boolean
    fun availableAt(providerId, model): Long?
    fun remove(providerId, model)
    fun clearAll()
    fun entries(): List<CooldownEntry>
    fun importMerge(incoming: Map<String, Long>)
    fun cooldownCaption(untilMs: Long): String
}
```

`cooldownCaption` returns "rate-limited · back 14:30" for entries
expiring today, "rate-limited · back May 15 14:30" otherwise — the
caption the picker dims the row with.

## UI

### Model pickers

Every model picker (per-provider list, +Agent, +Model, the secondary
picker, the Fan-out / Fan-out icons launch screens, the Model Info
"in AI config" card) consults `ModelCooldownStore.isUnavailable` via
the consolidated dim+icon+selectable advisory rule and renders the
benched row dimmed with a "rate-limited · back …" caption. Tap-target
stays live so the user can override if they want to try anyway.

### Engines

`FanOutEngine`, the fan-icons batch, `ModelTestEngine`, and the
per-agent report-icon chain all consult `isUnavailable` before
committing a pair to the run — benched pairs are skipped or shifted
to ERROR with a reason. The Fan-out L1 stats panel surfaces them in
the **Throttled** column.

### Cooldowns CRUD

Settings → AI Setup → AI Models setup → **Model cooldowns** lists
every stored cooldown (expired and live), grouped by provider. Each
row shows the model, the expiry, and a 🐞 button that deep-links
into the captured trace. Actions:

- **Remove** — drop one entry (un-bench)
- **Clear all** — drop every entry
- Tap a row → the API trace screen for the 429 that caused it

The store is also part of **Export & Import → Runtime data** and the
full backup zip — round-trips through `BackupManager` like every
other prefs file.

## Files

- `data/ModelCooldownStore.kt` — the singleton store
- `data/ApiTracer.kt` — `RateLimitRetryInterceptor` /
  `OverloadedRetryInterceptor` (the 429/529 paths that route into
  `markUnavailable`)
- `ui/settings/SettingsScreen.kt` — the **Model cooldowns** CRUD
  surface under AI Models setup
- `model/SettingsModels.kt` — `BlockedModel` /
  `TestExcludedModel` / `InaccessibleModel` are the sibling
  CRUD lists (cooldowns is distinct: it's auto-managed by the
  network layer and persists across runs without being part of
  Settings)
