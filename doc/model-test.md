# Test all models

End-to-end probe of every configured model in every active provider,
in parallel, with a fixed prompt and per-(provider, model) trace
capture. Surfaces under **Housekeeping → Test → Test all models** and
backs the auto-fed **Blocked / Test-excluded / Inaccessible** lists
under AI Setup → AI Models setup.

## Engine

[`ModelTestEngine`](../ai/src/main/java/com/ai/viewmodel/ModelTestEngine.kt)
owns the run. It exposes two `StateFlow`s:

| Flow | Use |
|---|---|
| `currentRun: StateFlow<ModelTestRunState?>` | the latest run snapshot — drives every L1/L2/L3 screen |
| `throttledKeys: StateFlow<Set<String>>` | live set of `(provider, model)` pairs blocked inside `ProviderThrottle.acquire`; drives the L1 "Throttled" badge |

A run is built from the user's provider selection. For each selected
provider the engine partitions the catalog at `startRun` time into
four buckets:

| Bucket | What lands here |
|---|---|
| **For testing** | Probed in the run. Reported as Done / Error / Throttled |
| **Inaccessible** | In `Settings.inaccessibleModels` (user-curated + seeded from `assets/inaccessible.json`). Dropped from the sweep entirely; counted in `inaccessibleAtStart` |
| **Test-excluded** | In `Settings.testExcludedModels` (cost > 5¢ auto-rule + manual + seeded from `assets/excluded.json`). Skipped; counted in `excludedAtStart` |
| **No chat** | Non-testable model types (IMAGE, TTS, STT, …). Dropped; counted in `noChatAtStart` |

The four buckets + `forTestingAtStart` always sum to `catalogTotal`
by construction so the stats panel reconciles without rounding errors.

## Run lifecycle

Each probe runs `analyze(provider, model, TEST_PROMPT, ...)` with the
provider's default agent config. The fixed prompt is `"Reply with
exactly: OK"`. Each `ModelTestState` carries the captured trace
filename, the model's raw response text, duration, and per-call cost
broken into input + output.

```
[PENDING 🕓] → permit acquired → [RUNNING ⏳] → response → [PASS ✅]
                                              \→ error → [FAIL ❌]
```

A 429 with Retry-After ≥ 1 h auto-benches the model via
`ModelCooldownStore.markUnavailable(providerId, model, availableAtMs,
trace)`. The benched pair is dropped from the rest of the run and
shows in the **Model cooldowns** picker thereafter. See
[cooldowns.md](cooldowns.md).

State is persisted to `<filesDir>/test_run.json` (single document via
[`ModelTestRunStore`](../ai/src/main/java/com/ai/data/ModelTestRunStore.kt))
after each item completion + once on run end — so a process kill
mid-sweep doesn't lose progress. Hydration backfills empty cells on
the next open; the catalog-stats snapshot stays stable for the
lifetime of the run (only the live progress counters move).

## Concurrency

- The total in-flight cap is `GeneralSettings.maxTestApiCalls`
  (default 40). Read directly by the engine — **not** part of
  `ApiCallCaps`, so it doesn't compete with report-gen / fan-out /
  fan-icons budgets.
- Per-host caps come from `ProviderThrottle` like every other flow
  (30 / min × 3 concurrent default, per-provider overrides apply).
- The engine pre-acquires permits before each probe so the L1
  "Throttled" badge can show a live count of pairs blocked in
  `acquire`.

## L1 / L2 / L3 screens

The drill-in shape mirrors the Fan-out one (so the stats panel +
progress bar pattern reuses).
[`ModelTestScreen`](../ai/src/main/java/com/ai/ui/report/ModelTestScreen.kt)
is the entry point;
[`ModelTestSelectScreen`](../ai/src/main/java/com/ai/ui/report/ModelTestSelectScreen.kt)
picks providers (skipped when there's only one provider eligible —
the flow jumps straight to the picker).

### L1 (`ModelTestL1Screen.kt`)

- **Stats panel** (pinned at the top, split into two rows): catalog
  partition (Total / For-testing / Inaccessible / Excluded / No-chat)
  + live progress (Running / Queued / Throttled / Done / Errors), plus
  total cost in the list header.
- One row per provider, alphabetical, with per-provider Test +
  Cancel buttons.
- "Test all models" + "Cancel test" + "Check current test run"
  (resume) + "Rerun errors" actions.
- Math reconciles by construction; "Run" was renamed to "Running"
  and "Queue" to "Queued" with swapped colours.

### L2 (`ModelTestL2Screen.kt`)

Per-provider list of model rows. Each row shows status icon, model
name, latency, cost, and (on FAIL) an error tooltip with a 🐞 deep
link to the captured trace. The source prompt sits at the top of
the page so the user knows what every row was tested with.

### L3 (`ModelTestL3Screen.kt`)

Single-model detail. HARDCODED green subject row carries the model
name; centred dynamic icon; the captured trace deep-link; the
request prompt; the response body; and chevron nav to walk siblings
in the provider list.

## Auto-feeds

The sweep automatically feeds three Settings lists from its
outcomes — each user-editable under AI Setup → AI Models setup:

- **Blocked models** (`Settings.blockedModels`) — anything that
  returned an error above the user threshold. Counted as FAIL until
  manually un-blocked. CRUD with a reason text.
- **Test-excluded models** (`Settings.testExcludedModels`) — models
  whose probe cost would exceed 5¢ get added automatically; the user
  can add or remove freely. Skipped by the sweep but still picker-
  visible. Seeded from `assets/excluded.json` on every cold start
  via
  [`TestExcludedSeed`](../ai/src/main/java/com/ai/data/TestExcludedSeed.kt).
- **Inaccessible models** (`Settings.inaccessibleModels`) —
  tier-gated catalog entries the user can't actually call (Together
  non-serverless, etc.). **Hidden from every picker** — distinct
  from Test-excluded which only hides from the sweep. Auto-added on
  tier-gating provider errors. Seeded from `assets/inaccessible.json`
  (64 bundled entries today) via
  [`InaccessibleSeed`](../ai/src/main/java/com/ai/data/InaccessibleSeed.kt).

## Seed asset files

Both `assets/inaccessible.json` and `assets/excluded.json` are
delta-merged on every cold start — bundled entries whose
`(providerId, model)` key isn't already in the user's list are
appended; existing entries are never overwritten. So updating an
asset reaches existing installs without destroying user edits.

To curate a new bundled entry: append the row to the asset JSON,
push a build, the next cold start picks it up.

## Files

- `viewmodel/ModelTestEngine.kt` — run logic, partition, persistence
- `data/ModelTestRunModel.kt` — `TestStatus`, `ModelTestState`,
  `ModelTestRunState` (see [datastructures.md](datastructures.md#modeltestrunstate--modelteststate--teststatus))
- `data/ModelTestRunStore.kt` — `<filesDir>/test_run.json`
  single-document writer
- `data/InaccessibleSeed.kt` + `data/TestExcludedSeed.kt` — delta
  merge of bundled assets into Settings on cold start
- `ui/report/ModelTestScreen.kt`,
  `ui/report/ModelTestSelectScreen.kt`,
  `ui/report/ModelTestL1Screen.kt`,
  `ui/report/ModelTestL2Screen.kt`,
  `ui/report/ModelTestL3Screen.kt`
