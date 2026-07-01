# Model-state lists + manual model-type overrides

Five parallel lists keyed on the `"providerId:model"` pair decide
whether a (provider, model) tuple is benched, blocked, skipped by the
test sweep, marked unreachable, or has its API type forced by hand.
Four are advisory/exclusion lists; the fifth (manual overrides) is a
classification list. Each has a CRUD screen under **AI Setup → AI
Models** (the `ModelsSetupScreen` cards,
[ui/settings/SetupScreens.kt:147](../ai/src/main/java/com/ai/ui/settings/SetupScreens.kt)).
The four `Settings.*`-backed lists are stored on the settings object;
cooldowns are a separate runtime store (`ModelCooldownStore`) with its
own SharedPreferences.

All keys share the shape `"${providerId}:${model}"` — the same form as
`ReportModel.deduplicationKey`.

| List | Field / store | Auto-populated by | Picker effect | CRUD |
|---|---|---|---|---|
| Cooldowns | `ModelCooldownStore` (own prefs) | long-bench 429 (>1h hint / quota / billing) | dim + ⏳ caption | `cruds/models/cooldowns/` |
| Blocked | `Settings.blockedModels` | sweep FAIL → block, PASS → un-block | dim + 🚫 caption | `cruds/models/blocked/` |
| Test-excluded | `Settings.testExcludedModels` | probe cost > 5¢; `excluded.json` seed | none (sweep-only) | `cruds/models/testexcluded/` |
| Inaccessible | `Settings.inaccessibleModels` | tier-gate probe error; `inaccessible.json` seed | dim + 🔒 caption | `cruds/models/inaccessible/` |
| Manual types | `Settings.modelTypeOverrides` | (manual only) | wins over autodetection | `cruds/models/manualoverrides/` |

The CRUD screens are reached through the two-tier `SettingsSubScreen`
router: **AI Setup → AI Models** lands on `AI_MODELS_SETUP`, whose cards
push `AI_BLOCKED_MODELS` / `AI_TEST_EXCLUDED_MODELS` /
`AI_INACCESSIBLE_MODELS` / `AI_MANUAL_MODEL_TYPES` / `AI_MODEL_COOLDOWNS`
([ui/settings/SettingsScreen.kt:496](../ai/src/main/java/com/ai/ui/settings/SettingsScreen.kt)).
Each sub-screen is a four-file CRUD (`add` / `edit` / `list` / `view`)
under `ui/cruds/models/<name>/`.

The three **advisory** states (cooldown / blocked / inaccessible) are
collapsed into one per-row lookup, `ModelAdvisoryState`
([ui/shared/ModelAdvisory.kt:25](../ai/src/main/java/com/ai/ui/shared/ModelAdvisory.kt)),
hoisted once per picker via `rememberModelAdvisoryLookup`
([ui/shared/ModelAdvisory.kt:59](../ai/src/main/java/com/ai/ui/shared/ModelAdvisory.kt)).
That helper collects `ModelCooldownStore.cooldowns` (a `StateFlow`,
re-derived on change) plus `Settings.blockedReasonByKey` and
`Settings.inaccessibleReasonByKey`, packaging them so each row pays only
an O(1) map lookup. A model can be in zero, one, two, or all three
states independently — `stateFor` reads all three maps. The dim
treatment is identical: `rowAlpha = 0.4f` when any state is active, a
leading badge (⏳ / 🚫 / 🔒) via `ModelAdvisoryBadges`, and a one-line
reason caption via `ModelAdvisoryCaptions` — but the row stays
**clickable** so the user can still pick it deliberately. The cooldown's
`benchedUntil` is additionally filtered to `> System.currentTimeMillis()`
inside `stateFor`, so an expired bench never dims a row even before the
store prunes it.

## Cooldowns

Transient, time-based benches. A 429 gets the pair benched (until a
computed `benchUntil`) in any of four cases the retry interceptor
recognises ([data/RateLimitRetry.kt:86](../ai/src/main/java/com/ai/data/RateLimitRetry.kt)):
Gemini daily-quota exhausted (retry-after hint, else next Pacific
midnight), Cohere Trial-key monthly cap (next month start), any
provider out of credits / over its spending limit (a billing 429 —
benched 6h), and any provider whose `retry-after` hint exceeds
`LONG_RETRY_THRESHOLD_MS` (1 hour). See [throttle.md](throttle.md) for
the 429 retry path that fires these.

- **Stored** in `ModelCooldownStore`
  ([data/ModelCooldownStore.kt:28](../ai/src/main/java/com/ai/data/ModelCooldownStore.kt))
  — a plain `object` singleton (both the OkHttp 429 interceptor, which
  has no `Context`, and the Compose pickers read it) with its **own**
  SharedPreferences (`model_cooldowns`, key `map`). A sibling `traces`
  map (key `traces`) records the API-trace filename whose 429 caused each
  bench; it's device-local and **not** carried in Import/Export.
- **Populated** by `markUnavailable(providerId, model, availableAtMs,
  traceFile)`, called from `RateLimitRetryInterceptor` once a 429's
  `benchUntil` resolves (any of the four cases above)
  ([data/RateLimitRetry.kt:104](../ai/src/main/java/com/ai/data/RateLimitRetry.kt)).
  Reads are side-effect-free (Bug 48): `isUnavailable` and `availableAt`
  are pure timestamp compares (`until > now`) that **don't** drop the
  expired entry — model pickers call them per row, so they must not write
  SharedPreferences or emit on the `StateFlow` as a side effect of a
  "read". Actual pruning (delete + persist) happens only in `init` (on
  load) and the `pruneExpired` sweep.
- **Picker effect**: dimmed with the `cooldownCaption` string, e.g.
  `rate-limited · back 14:30` (today) or `rate-limited · back Jun 4 14:30`
  (a later day — Bug 47 compares day-of-year *and* year so a one-year-out
  bench doesn't render as a same-day time).
- **Import** merges via `importMerge` (trace filenames don't travel).
- The `cooldowns` `StateFlow` drives recomposition; the CRUD list reads
  `entries()` (raw, **not** expiry-pruned, so stale rows can be cleared
  by hand).

## Blocked models

Manually flagged pairs the app treats as failing. Identity is the
`(providerId, model)` pair — no UUID, optional `reason` (default `""`)
([model/SettingsModels.kt:158](../ai/src/main/java/com/ai/model/SettingsModels.kt)).

- **Populated** by the "Test all models" sweep, **per item, live** as
  the run progresses — `AppViewModel.applyTestItemIncrement(item)`
  ([viewmodel/AppViewModel.kt:1345](../ai/src/main/java/com/ai/viewmodel/AppViewModel.kt))
  fires on each PASS/FAIL transition: PASS drops the key from Blocked;
  FAIL **not** on cooldown upserts it with the error (`take(300)`,
  default `"Test failed"`); FAIL **on** cooldown drops it (the cooldown
  list owns that pair). Writes are in-memory only; the engine flushes
  once at end-of-run / cancel via `flushAiSettingsToDisk`. Untested
  entries are never touched. Hand-curable via the `blocked/` CRUD
  (`upsertBlockedModel` / `removeBlockedModel`).
- **Picker effect**: dimmed in every picker with a red `🚫 Blocked: …`
  caption (`blockedReasonByKey` feeds the advisory lookup).
- **Stored** in `Settings.blockedModels`, prefs key `ai_blocked_models`.

## Test-excluded models

A pure skip-set for the "Test all models" sweep — no reason field, no
UUID ([model/SettingsModels.kt:170](../ai/src/main/java/com/ai/model/SettingsModels.kt)).
`testExcludedKeys` is the `"providerId:model"` set the sweep checks; in
practice `ModelTestEngine.startRun` consults it per-model via
`isTestExcluded` ([viewmodel/ModelTestEngine.kt:192](../ai/src/main/java/com/ai/viewmodel/ModelTestEngine.kt)).

- **Populated** automatically when a probe's cost exceeds the 5¢
  ceiling (`COSTLY_PROBE_USD_THRESHOLD = 0.05` in
  [viewmodel/AppViewModel.kt:2208](../ai/src/main/java/com/ai/viewmodel/AppViewModel.kt))
  — the same per-item `applyTestItemIncrement` hook appends the model
  (no-clobber) when `item.totalCost > COSTLY_PROBE_USD_THRESHOLD`, in
  memory, flushed at end-of-run, so the next sweep won't pay for it
  again. Also **seeded** from
  `assets/excluded.json` on app start (`TestExcludedSeed.ensureAllPresent`,
  [data/TestExcludedSeed.kt:43](../ai/src/main/java/com/ai/data/TestExcludedSeed.kt)),
  a delta-merge that never touches existing keys. Hand-curable via the
  `testexcluded/` CRUD.
- **Picker effect**: **none** — these models stay fully visible and
  selectable everywhere; the list only gates the sweep.
- **Stored** in `Settings.testExcludedModels`, prefs key
  `ai_test_excluded_models`.

## Inaccessible models

Pairs genuinely unreachable on the user's account/tier (e.g. Together
or OpenRouter non-serverless catalog entries). Carries a **required**
`reason` ([model/SettingsModels.kt:184](../ai/src/main/java/com/ai/model/SettingsModels.kt)).

- **Populated** by the test engine when a probe's error matches a
  tier-gating signal — `non-serverless` (Together dedicated-only
  entries), `is not available on` (SambaNova's HTTP 410 wording), or a
  bare HTTP 404 (model id not found anywhere reachable on this account)
  — `upsertInaccessibleModel` records it (reason `take(200)`,
  [viewmodel/ModelTestEngine.kt:551](../ai/src/main/java/com/ai/viewmodel/ModelTestEngine.kt))
  and the item is marked PASS (kept in the run so Total stays stable)
  rather than counted as FAIL.
  Also **seeded** from `assets/inaccessible.json` on start
  (`InaccessibleSeed.ensureAllPresent`,
  [data/InaccessibleSeed.kt:49](../ai/src/main/java/com/ai/data/InaccessibleSeed.kt));
  blank-reason seed rows default to "Unable to access non-serverless
  (bundled)". Hand-curable via the `inaccessible/` CRUD.
- **Picker effect**: dimmed with a tertiary `🔒 Inaccessible: …`
  caption (`inaccessibleReasonByKey`). Per
  [ui/other/Selection.kt:225](../ai/src/main/java/com/ai/ui/other/Selection.kt)
  inaccessible rows **dim and stay selectable** like the other two
  advisory states — they used to hide from this picker but no longer do.
- **Stored** in `Settings.inaccessibleModels`, prefs key
  `ai_inaccessible_models`.

## Manual model-type overrides

Per-model API-type assignments that win over autodetection — a flat,
cross-provider CRUD list living at the `Settings` root (one entry per
override, identified by UUID `id`), rather than one map per provider
([model/SettingsModels.kt:310](../ai/src/main/java/com/ai/model/SettingsModels.kt)).
Each entry sets a `type` — one of the ten type tokens in `ModelType.ALL`
(`chat`, `responses`, `embedding`, `rerank`, `image`, `tts`, `stt`,
`moderation`, `classify`, `ocr`;
[data/ModelType.kt:34](../ai/src/main/java/com/ai/data/ModelType.kt) — note
`ModelType` is an `object` of `const String`s, not an enum) — plus three
optional capability flags: `supportsVision` 👁, `supportsWebSearch` 🌐,
`supportsReasoning` 🧠.

- **Type precedence** (`getModelType`,
  [model/SettingsModels.kt:464](../ai/src/main/java/com/ai/model/SettingsModels.kt)):
  a matching override returns first and short-circuits everything —
  ahead of the LiteLLM type (`PricingCache.liteLLMModelType`, used only
  when it's not plain `CHAT`), the per-provider `modelTypes` map (native
  list-API metadata), and the `ModelType.infer` naming heuristic.
- **Capability precedence**: each of `isVisionCapable` /
  `isWebSearchCapable` / `isReasoningCapable`
  ([model/SettingsModels.kt:489/530/594](../ai/src/main/java/com/ai/model/SettingsModels.kt))
  checks, in order: the per-provider set (e.g. `ProviderConfig.visionModels`,
  populated by Model Info edits and fetch unions) → the matching override
  flag → the per-provider *precomputed* cache (`visionCapableComputed`
  etc., refreshed by `recomputeCapabilities`) → a slow layered lookup
  (provider `/models` self-report → LiteLLM flag → models.dev → further
  catalog flags where the capability has them — Requesty / llm-stats /
  TrueFoundry / CloudPrice, coverage varies per capability →
  `ModelType.infer*` naming heuristic). An override flag can only **add**
  a capability — because each lookup returns `true` early on a positive
  match, it never clears one already implied by the per-provider set or
  the catalog. This is how a user forces a model the catalog
  mis-classifies (e.g. a vision/web-search/reasoning model the autodetect
  missed).
- The edit form pulls the provider's known models from
  `aiSettings.getProvider(...).models`; if the provider hasn't been
  fetched the model dropdown is empty and prompts a fetch first
  ([cruds/models/manualoverrides/edit.kt:67](../ai/src/main/java/com/ai/ui/cruds/models/manualoverrides/edit.kt)).
- **Stored** in `Settings.modelTypeOverrides`, prefs key
  `ai_model_type_overrides`.

## Prefs keys & seed assets

For [persistent.md](persistent.md) cross-reference:

| Key / asset | Holds |
|---|---|
| `ai_blocked_models` | `Settings.blockedModels` JSON |
| `ai_test_excluded_models` | `Settings.testExcludedModels` JSON |
| `ai_inaccessible_models` | `Settings.inaccessibleModels` JSON |
| `ai_model_type_overrides` | `Settings.modelTypeOverrides` JSON |
| `model_cooldowns` (own prefs) | keys `map` (`Map<key,Long>`) + `traces` (`Map<key,String>`) |
| `assets/excluded.json` | seed for test-excluded (delta-merged on start) |
| `assets/inaccessible.json` | seed for inaccessible (delta-merged on start) |

The four `ai_*` keys live in the main `eval_prefs` settings store
(constants at
[data/preferences/SettingsPreferences.kt:1144](../ai/src/main/java/com/ai/data/preferences/SettingsPreferences.kt),
loaded at :316–319, written at :432–435). The three exclusion lists
(`ai_blocked_models` / `ai_test_excluded_models` /
`ai_inaccessible_models`) are each written `null` when empty (so the key
disappears); `ai_model_type_overrides` is written unconditionally as a
JSON array. All four lists round-trip through Import/Export. The
`model_cooldowns` store is its own SharedPreferences file and is **not**
in this set — its `traces` sidecar stays device-local.

## Related docs

- [throttle.md](throttle.md) — the 429 retry path whose >1h
  retry-after hints trigger cooldowns.
- [providers.md](providers.md) — the provider catalog these lists key
  against.
- [datastructures.md](datastructures.md) — `BlockedModel`,
  `TestExcludedModel`, `InaccessibleModel`, `ModelTypeOverride`.
- [persistent.md](persistent.md) — the prefs keys and seed assets above.
- [development.md](development.md) — adding a provider / model-type /
  SecondaryKind.
