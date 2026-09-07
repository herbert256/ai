# Parallel provider refresh — 7 September 2026

Provider model-list retrieval and default-model checks run concurrently again
in both **Refresh all** and **Providers / models / default agents**. Startup's
parallel model-list refresh uses the same safe publication path.

## Changes

- Each provider computes capabilities and prices outside the retryable
  `StateFlow.update` operation, then merges only its fetched fields into the
  current settings. Another provider completing does not force the expensive
  calculation to repeat or replace newer agents, keys, or overrides.
- Pricing/capability catalogs have indexes for normalized IDs, provider
  prefixes, routing suffixes, and bare-name fallbacks. Indexes retain the old
  matching priorities and first-entry tie breaking, are bounded, and follow
  immutable catalog identity. Reloading a catalog cannot repopulate an old
  result cache into the new generation.
- Applying OpenRouter type labels no longer recomputes capabilities and
  pricing for otherwise unchanged model lists. Batch refresh applies those
  labels once after workers join. Full refresh recomputes derived values once
  after both external catalogs and provider workers join.
- Per-worker checkpoints save only provider states, agents, and flocks. Their
  small persistence lock reads the latest snapshot inside the lock. Model
  requests and processing remain concurrent. One complete settings save runs
  after all workers and checkpoints finish, before restart is offered.
- An explicit HTTP 503 during a refresh's default-model check receives one
  provider-local retry. Authentication and invalid-model errors are not retried
  by this policy.
- CloudPrice's required pages retry HTTP 429/503 up to twice and respect
  `Retry-After`. The response closes before a cancellable coroutine delay, so
  other providers and catalog jobs keep progressing. A failed/incomplete page
  still preserves the previous complete catalog. Optional raw model-list
  sidecars keep their existing no-retry behavior.

## Emulator validation

The first provider-only run refreshed all **31 API-backed model lists in
5.5 seconds**, including parsing, capabilities/pricing computation, state
merging, and per-provider model-list persistence. All **36 default-model
checks passed**, and the workers joined in **26.789 seconds**. The five
manual/fallback providers correctly skipped model discovery.

For comparison, the prior sequential provider run documented in
`provider-defaults-2026-09-07.md` took approximately **43 minutes**. The observed
provider workflow improved by roughly **96×** on this emulator; network and
upstream model latency can vary between runs.

After using the app's Restart application button, provider catalogs, prices,
freshness timestamps, all 36 agents, provider states, flock membership, and API
keys matched their saved pre-restart values. All **4,197 model-price records
present before and after the provider refresh were identical**; two Vercel
models had disappeared from the live catalog.

A full-refresh validation then ran all provider requests alongside the eleven
external information catalogs. All 31 model lists refreshed; provider workers
joined in 36.061 seconds. This exposed two explicit upstream errors rather
than a local model-list failure: NVIDIA returned HTTP 503 during its model
probe, and CloudPrice returned HTTP 429 at page 21 with `Retry-After: 60`.
These observations led to the bounded, provider-local retry fixes above.

## Final build verification

The final full-refresh run started its 36 parallel workers at 09:16:52 local
time. All **31 API-backed model lists** refreshed, with the last provider's
fetch/processing/persistence completing in **41.723 seconds** while the much
larger external catalogs were also refreshing. Provider workers joined in
**46.203 seconds**.

All **11 external information catalogs** completed. CloudPrice's page 21
returned HTTP 429 at 09:17:32; the app waited its advertised 60 seconds and
continued from the same page. At 09:18:34 it successfully parsed and saved
**2,996 entries across 30 pages**. Other provider workers finished while that
catalog was waiting. The UI reached **47 / 47** and offered restart only after
the final recomputation and settings save.

NVIDIA's model catalog returned HTTP 200 throughout, but its unchanged default
model `nvidia/nemotron-3-super-120b-a12b` returned HTTP 503 twice during the final
run, including the new bounded retry. A separate check at 09:22:16, after
restart and without concurrent refresh work, also returned **Service
temporarily overloaded**. This establishes a remaining upstream generation
availability issue, not a model-list retrieval or parallel-state failure.
The final full run therefore saved **35 passing provider checks/default
agents and NVIDIA in error**. The earlier provider-only run passed all 36.
No default model was changed as part of this task.

After the final run's Restart application action, all provider catalogs,
prices, freshness timestamps, provider states, agent IDs, flock memberships,
and API keys matched the saved pre-restart state exactly. No catalog result or
agent was lost through a late settings write. Startup completed in 5.340
seconds on that restart. No application crash or ANR was observed during the
refresh checks.

The default development cycle was used: debug build, in-place emulator
installation, cloud APK copy, launch/foreground verification, live refresh
checks, restart verification, and commit. No unit/instrumented test suites,
uninstall, or app-data snapshot/restore were run. The local debug APK and cloud
APK have SHA-256:

`38c654be7fe1eea562e6473039bb69aaafaf2fc8fda8ac80ece13e38263abc7c`

Evidence was collected from the progress UI, sanitized settings comparisons,
API traces, and `RefreshAll`/`ApiCall`/`PricingCache` logs. Credentials and raw
settings dumps are not included in this record.
