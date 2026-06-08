# Testing and quality suggestions

## Current test shape

Source inspection found 86 Kotlin test files:

- 59 JVM test files under `ai/src/test`.
- 27 instrumented test files under `ai/src/androidTest`.

The existing coverage appears strongest around data helpers, storage helpers,
model/provider utilities, report helper behavior, export helpers, and some
Compose screens. The main gap is direct coverage for runtime orchestration:
batch engines, primary report generation state, replay flows, provider dispatch
contracts, and settings persistence parity.

## T01 - Add settings persistence parity tests

> **Status (2026-06-08): ✅ done** — `GeneralSettingsParityTest` (`293102d8`).

Priority: P1

What to test:

- every persisted `GeneralSettings` field round-trips
- absent keys seed intended defaults
- empty lists/maps stay empty when intentionally saved
- unknown enum values fall back safely
- UI color overrides normalize correctly

Why:

`loadGeneralSettings` and `saveGeneralSettings` are manual mirrors. Manual
mirrors are cheap to write once and expensive to trust forever.

## T02 - Add batch engine lifecycle unit tests

> **Status (2026-06-08): ✅ done** — `BatchEngineTest` (`5ef0a8d3`).

Priority: P1

What to test for each engine:

- hydrate from persisted rows
- start creates expected placeholders
- duplicate start dedupes or returns the existing job
- delete cancels run and item jobs
- delete item removes runtime state and persisted row
- restart failed reruns only failed items
- resume stale rows respects attempt caps
- successful row transitions update flow and disk
- failed row stores structured error and stops running

How:

Use fake storage and fake call runners where possible. If current engines are
too coupled to Android `Context`, first extract smaller pure collaborators.

## T03 - Add `runThrottledBatch` and `PermitHold` tests

> **Status (2026-06-08): ✅ done** — `PermitHoldTest` / `BatchResumeTest` / `RunThrottledBatchTest` (`f5083e38`).

Priority: P1

What to test:

- items register before start
- host-less items are skipped in fixed-host mode
- dynamic-host mode does not set `permitPreAcquired`
- fixed-host mode sets `permitPreAcquired`
- backoff yield releases all permits
- backoff reacquires in canonical order
- cancellation during yield does not double-release
- timeout releases all permits
- Type-A bench retry resets item and stops after max attempts

Why:

This helper protects the app from deadlocks. It should have direct tests because
many high-cost workflows depend on it.

## T04 - Add provider dispatch golden tests

> **Status (2026-06-08): ✅ done** — `ApiDispatchGoldenTest` + `ApiStreamingGoldenTest` (`448dde10`, `efed50d0`).

Priority: P1

What to test:

- OpenAI-compatible chat request bodies
- OpenAI responses request bodies, including image content
- Anthropic request bodies with max tokens and system prompt
- Gemini request bodies with system instruction, tools, and thinking config
- successful text parsing
- empty-content fallback parsing
- usage parsing
- error response formatting
- streaming SSE parsing for each provider family
- audit URL reconstruction

How:

Use mock web server tests or fake Retrofit APIs. Keep fixtures in test
resources and avoid live API keys.

## T05 - Add report execution plan tests

> **Status (2026-06-08): ✅ done** — `ReportExecutionPlanTest` (`c5ee76e2`).

Priority: P1

After adding `ReportExecutionPlan`, test:

- selected model count
- skipped model reasons
- provider/model capability flags
- primary call count
- metadata call count
- secondary call count
- estimated cost range
- selected prompt and parameter IDs
- local/cloud model mix

Why:

This gives high confidence that UI selection and dispatch will agree before
network calls are launched.

## T06 - Add storage command tests

> **Status (2026-06-08): ✅ done** — `ReportStorage` already had 11 instrumented tests; added cost-ledger dedup + corrupted-JSON tolerance (`c13cacc0`).

Priority: P1

What to test:

- report create/update/delete under safe IDs
- agent status transitions
- additive cost writes are idempotent for duplicate trace records
- title/icon metadata updates clear or preserve errors correctly
- cost ledger append dedupes rows
- ledger reconciliation preserves legacy structured costs
- version flows bump once per mutation
- corrupted JSON load failures are reported and do not crash list calls

Why:

`ReportStorage` is a critical data contract and currently contains many
special-case behaviors that should be locked down.

## T07 - Add secondary lineage and export tests

Priority: P2

What to test:

- every `SecondaryKind` serializes and loads
- fan-out rows group into runs correctly
- fan-in rows link to their source run
- translations link to prompt/agent source rows
- delete/rerun behavior preserves or removes lineage intentionally
- export includes expected secondary rows and run metadata

Why:

The secondary domain is where many functional workflows meet. A small lineage
mistake can break view, manage, export, delete, or rerun behavior.

## T08 - Add external/share command parser tests

Priority: P2

What to test:

- bare prompt intent only prefills
- instruction-bearing intent requires confirmation
- tags parse correctly
- unknown tags are ignored
- share with both URL text and URI attachments preserves both
- share routes to report/chat/knowledge as intended

Why:

The source already contains important safeguards for external intents. Unit
tests make sure those safeguards survive refactors.

## T09 - Add Compose tests for high-value navigation contracts

Priority: P2

What to test:

- settings deep links return to the correct parent/caller
- settings edit target survives recomposition and process recreation where
  saveable state is expected
- report manage secondary drill-ins return to the correct report
- bottom/top bar icons route to the expected hubs
- action icons expose content descriptions

Why:

The UI is route-heavy and icon-heavy. These tests prevent regressions that are
hard to catch with pure unit tests.

## T10 - Add source-boundary tests

Priority: P2

What to test:

- `ui` package does not define durable stores
- `data` package does not import Compose
- provider dispatch does not import UI
- viewmodel package does not import composables
- no new production file exceeds a chosen size threshold without an allowlist

Why:

The current source shows boundary drift. Cheap source tests can stop future
drift while refactors are underway.

## T11 - Add fake provider infrastructure for report-section tests

> **Status (2026-06-08): ✅ done** — reused / extended the MockWebServer harness (`448dde10`).

Priority: P1

Suggestion:

Create a reusable fake provider runtime:

- deterministic text response
- configurable latency
- configurable failures by provider/model/status
- configurable token usage
- streaming chunks
- trace filename capture

Use it for primary reports, metadata, fan-out, compare, translations, and
replay flows.

Expected benefit:

The report section can be tested deeply without network access, API keys, or
instrumented device tests.

## T12 - Add performance guard tests for large report scenarios

Priority: P2

Scenarios:

- report with many primary models
- fan-out with N(N-1) rows
- many secondary results on disk
- large trace directory
- large knowledge-base list
- local runtime directory with many model files

What to assert:

- hydrate time stays below a threshold on test hardware
- list calls avoid repeated full-file reads where possible
- UI state snapshots do not allocate unexpectedly large structures

Why:

The app's functional power encourages large runs. Performance tests keep the
large-run path healthy.

## T13 - Add mutation tests around cost accounting edge cases

Priority: P2

Targets:

- duplicate trace file success
- retry followed by success
- failed call with tokens but no cost
- metadata-only cost updates
- alternative title/icon candidate cost
- deleted secondary cost retention
- usage-stat rebuild after ledger reconciliation

Why:

Cost accuracy is both user-facing and hard to manually verify.
