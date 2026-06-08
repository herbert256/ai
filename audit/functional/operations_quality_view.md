# Operations and Quality View

This file describes the operating model, quality surfaces, and extension
points around the app.

## Observation 1 - Build and Deploy Conventions Are Explicit

Functional view: development docs define debug/release builds, APK
install, cloud copy, launch commands, logcat tags, and cycle
conventions.

Source view: `doc/development.md`, `AGENTS.md` instructions,
Gradle files.

Design implication: operational behavior is documented as part of the
repo contract. Audit-only documentation work can skip build/deploy when
requested, but source changes normally follow the default cycle.

## Observation 2 - The Test Suite Is Mixed JVM and Instrumented

Functional view: JVM tests cover pure parsing, helpers, exports, pricing,
storage helpers, settings graph logic, report helper behavior, and
viewmodel policies. Instrumented tests cover storage managers, tracer,
provider registry, pricing, report export, Compose screens, and report
manage/view surfaces.

Source view: `ai/src/test/**`, `ai/src/androidTest/**`.

Design implication: new pure logic should usually get JVM tests; storage
or Compose behavior may need instrumented coverage.

## Observation 3 - Mock API Testing Is Already a Pattern

Functional view: API behavior can be exercised through mock server tests
without hitting real providers. This supports request shape, auth,
usage parsing, URL routing, and provider-format behavior.

Source view: `ApiMockWebServerTest`, `ApiDispatchHelpersTest`,
`BuildChatUrlTest`, `ResponsesUrlTest`, `DefaultClaudeMaxTokensTest`.

Design implication: provider and dispatch changes should prefer local
mocked API tests over manual real-key testing where possible.

## Observation 4 - Observability Is Designed for Runtime Inspection

Functional view: users can inspect trace files, application logs, audit
logs, usage statistics, live call caps, throttle state, and crash
reports in-app.

Source view: `ApiTracer`, `AppLog`, Monitor screens, `throttle.md`,
`applog.md`, `log-details.md`.

Design implication: a feature that makes API calls should set trace tags,
record costs/usage where applicable, and integrate with logs enough to
debug real user runs.

## Observation 5 - Provider Onboarding Is Mostly Data-Driven

Functional view: adding a standard cloud provider is primarily an asset
definition plus model/capability/pricing behavior, not a new screen.

Source view: `assets/providers/*.json`, `ProviderDefinitionTest`,
`ProviderRegistry`, `ProviderFieldTimestamps`, `providers.md`.

Design implication: provider growth scales because behavior is grouped
by API format. A provider that does not fit the three existing formats
is a larger architecture decision.

## Observation 6 - Model States Are Operational Controls

Functional view: Blocked, Cooldowns, Test-excluded, Inaccessible, and
manual model-type overrides control which models appear, which models
are dimmed, and which models participate in tests or execution.

Source view: `model-states.md`, model-state CRUD screens,
`ModelCooldownStore`, `ModelTypeOverride`.

Design implication: model availability is a managed state, not only a
provider API response. UI and engines should honor these states
consistently.

## Observation 7 - Network Controls Are User-Tunable

Functional view: read timeouts, per-provider call rate, per-provider
concurrency, global call caps, 429/529 retry budgets, and per-provider
overrides are settings-visible behavior.

Source view: `NetworkSettings`, `ApiCallCaps`, `ProviderThrottling`,
`RateLimitRetry`, `OverloadedRetry`, `SettingsScreen`, `throttle.md`.

Design implication: performance and reliability knobs are part of the
product. Batch engines should acquire caps in the documented order and
surface wait/running state where users can see it.

## Observation 8 - Help and Documentation Are Embedded Product Surfaces

Functional view: users can open Help, screen-specific help, provider
pages, repository pages, the manual, and technical documentation from
inside the app.

Source view: `ui/admin/HelpScreen`, `ReportsHelp`, documentation WebView
routes, `help.md`, `doc/README.md`.

Design implication: new screens and icons should include help topics or
reuse existing ones. Documentation is not only external maintainer
material.

## Observation 9 - Extension Seams Are Documented

Functional view: docs explain how to add providers, parameters, pricing
tiers, secondary kinds, internal prompt categories, report icons, model
states, and local runtime behavior.

Source view: `doc/development.md`, `doc/api-formats.md`,
`doc/secondary-results.md`, `doc/parameters.md`, `doc/costs.md`,
`doc/local-runtime.md`.

Design implication: extension should follow established seams. When a
change does not fit a documented seam, that is a sign the architecture
map should be updated too.

## Observation 10 - Audits Are Used as Work Planning Artifacts

Functional view: dated audit folders capture static review snapshots,
fix status, recommended targets, and now functional/architecture maps.

Source view: `audit/2026-05-08`, `audit/2026-05-24`,
`audit/2026-06-06`, `audit/2026-06-08`, `audit/functional`.

Design implication: audits should be written so a future session can
resume without reconstructing the investigation. This functional audit
is intended as a map, not a bug queue.

