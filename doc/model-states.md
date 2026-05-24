# Model-state lists + manual model-type overrides

Five parallel lists keyed on the `"providerId:model"` pair decide
whether a (provider, model) tuple is benched, blocked, skipped by the
test sweep, marked unreachable, or has its API type forced by hand.
Four are advisory/exclusion lists; the fifth (manual overrides) is a
classification list. The four exclusion lists each have a CRUD screen
under **AI Setup → AI Models** (the `ModelsSetupScreen` cards) and a
matching `Settings.*` field; cooldowns are a separate runtime store.

All keys share the shape `"${providerId}:${model}"` — the same form as
`ReportModel.deduplicationKey`.

| List | Field / store | Auto-populated by | Picker effect | CRUD |
|---|---|---|---|---|
| Cooldowns | `ModelCooldownStore` (own prefs) | 429 with retry-after > 1h | dim + ⏳ caption | `cruds/models/cooldowns/` |
| Blocked | `Settings.blockedModels` | sweep FAIL → block, PASS → un-block | dim + 🚫 caption | `cruds/models/blocked/` |
| Test-excluded | `Settings.testExcludedModels` | probe cost > 5¢; `excluded.json` seed | none (sweep-only) | `cruds/models/testexcluded/` |
| Inaccessible | `Settings.inaccessibleModels` | tier-gate probe error; `inaccessible.json` seed | dim + 🔒 caption | `cruds/models/inaccessible/` |
| Manual types | `Settings.modelTypeOverrides` | (manual only) | wins over autodetection | `cruds/models/manualoverrides/` |

The three advisory states (cooldown / blocked / inaccessible) are
collapsed into one per-row lookup, `ModelAdvisoryState`, hoisted once
per picker via `rememberModelAdvisoryLookup`
([ui/shared/ModelAdvisory.kt:59](../ai/src/main/java/com/ai/ui/shared/ModelAdvisory.kt)).
A model can be in zero, one, two, or all three independently. The dim
treatment is identical for all three: `rowAlpha = 0.4f`, a leading
badge (⏳ / 🚫 / 🔒), and a one-line reason caption — but the row stays
**clickable** so the user can still pick it deliberately.

## Cooldowns

Transient, time-based benches. A provider that answers a 429 with a
`retry-after` hint longer than `LONG_RETRY_THRESHOLD_MS` (1 hour —
Google's exhausted-quota case) gets the pair benched until the hint
expires. See [throttle.md](throttle.md) for the 429 retry path that
fires this.

- **Stored** in `ModelCooldownStore`
  ([data/ModelCooldownStore.kt:27](../ai/src/main/java/com/ai/data/ModelCooldownStore.kt))
  — a plain `object` singleton (both the OkHttp interceptor, which has
  no `Context`, and the Compose pickers read it) with its **own**
  SharedPreferences (`model_cooldowns`, key `map`). A sibling `traces`
  map records the API-trace filename whose 429 caused each bench; it's
  device-local and **not** exported.
- **Populated** by `markUnavailable(providerId, model, availableAtMs,
  traceFile)`. Expiry is lazy — `isUnavailable` and `availableAt` drop
  expired entries on read; `init` prunes on load.
- **Picker effect**: dimmed with an orange caption from
  `cooldownCaption`, e.g. `rate-limited · back 14:30`.
- **Import** merges via `importMerge` (trace filenames don't travel).
- The `cooldowns` `StateFlow` drives recomposition; the CRUD screen
  reads `entries()` (raw, **not** expiry-pruned, so stale rows can be
  cleared by hand).

## Blocked models

Manually flagged pairs the app treats as failing. Identity is the
`(providerId, model)` pair — no UUID, optional `reason`
([model/SettingsModels.kt:158](../ai/src/main/java/com/ai/model/SettingsModels.kt)).

- **Populated** mostly by the "Test all models" sweep:
  `syncBlockedModelsFromTestRun` drops every key the run tested (so a
  PASS un-blocks) then appends the run's failures (so a FAIL blocks /
  refreshes its reason). Untested entries are left alone. Hand-curable
  via the `blocked/` CRUD (`upsertBlockedModel` / `removeBlockedModel`).
- **Picker effect**: dimmed in every picker with a red `🚫 Blocked: …`
  caption (`blockedReasonByKey` feeds the advisory lookup).
- **Stored** in `Settings.blockedModels`, prefs key `ai_blocked_models`.

## Test-excluded models

A pure skip-set for the "Test all models" sweep — no reason field
([model/SettingsModels.kt:170](../ai/src/main/java/com/ai/model/SettingsModels.kt)).
`testExcludedKeys` is the O(1) filter `ModelTestEngine.startRun`
consults.

- **Populated** automatically when a probe's computed cost exceeds the
  5¢ ceiling (`COSTLY_PROBE_USD_THRESHOLD = 0.05` in
  [viewmodel/AppViewModel.kt:2443](../ai/src/main/java/com/ai/viewmodel/AppViewModel.kt))
  — the model is added on run completion so the next sweep won't pay
  for it again. Also **seeded** from `assets/excluded.json` on app
  start (`TestExcludedSeed.ensureAllPresent`,
  [data/TestExcludedSeed.kt:18](../ai/src/main/java/com/ai/data/TestExcludedSeed.kt)),
  a delta-merge that never touches existing keys. Hand-curable via the
  `testexcluded/` CRUD.
- **Picker effect**: **none** — these models stay fully visible and
  selectable everywhere; the list only gates the sweep.
- **Stored** in `Settings.testExcludedModels`, prefs key
  `ai_test_excluded_models`.

## Inaccessible models

Pairs genuinely unreachable on the user's account/tier (e.g. Together
or OpenRouter non-serverless catalog entries). Carries a `reason`
([model/SettingsModels.kt:184](../ai/src/main/java/com/ai/model/SettingsModels.kt)).

- **Populated** by the test engine when a probe returns a tier-gating
  error ("Unable to access non-serverless" or similar) — dropped from
  sweep results rather than counted as FAIL. Also **seeded** from
  `assets/inaccessible.json` on start
  (`InaccessibleSeed.ensureAllPresent`,
  [data/InaccessibleSeed.kt:20](../ai/src/main/java/com/ai/data/InaccessibleSeed.kt));
  blank-reason seed rows default to "Unable to access non-serverless
  (bundled)". Hand-curable via the `inaccessible/` CRUD.
- **Picker effect**: dimmed with a tertiary `🔒 Inaccessible: …`
  caption (`inaccessibleReasonByKey`). Per `Selection.kt` (line ~204)
  inaccessible rows **dim and stay selectable** like the other two
  advisory states — they are not hidden.
- **Stored** in `Settings.inaccessibleModels`, prefs key
  `ai_inaccessible_models`.

## Manual model-type overrides

Per-model API-type assignments that win over autodetection — a fan
out-provider CRUD list living at the `Settings` root (one entry per
override, identified by UUID `id`)
([model/SettingsModels.kt:250](../ai/src/main/java/com/ai/model/SettingsModels.kt)).
Each entry sets a `type` (one of `ModelType.ALL`) plus three optional
capability flags: `supportsVision` 👁, `supportsWebSearch` 🌐,
`supportsReasoning` 🧠.

- **Type precedence** (`getModelType`,
  [model/SettingsModels.kt:393](../ai/src/main/java/com/ai/model/SettingsModels.kt)):
  a matching override returns first and short-circuits everything —
  ahead of the LiteLLM type, the per-provider `modelTypes` map (native
  list-API metadata), and the naming heuristic.
- **Capability precedence**: each of `isVisionCapable` /
  `isWebSearchCapable` / `isReasoningCapable` checks the per-provider
  set first (e.g. `ProviderConfig.visionModels`, populated by Model
  Info edits and fetch unions), then the matching override flag, then
  the LiteLLM flag, then the naming heuristic. An override flag can
  only **add** a capability — it never clears one already implied by
  the per-provider set or the catalog. This is how a user forces a
  model the catalog mis-classifies (e.g. a vision/web-search/reasoning
  model the autodetect missed).
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

The four `ai_*` keys live in the main settings prefs
([ui/settings/SettingsPreferences.kt:571](../ai/src/main/java/com/ai/ui/settings/SettingsPreferences.kt));
each is written `null` when its list is empty. All four lists
round-trip through Import/Export.

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
