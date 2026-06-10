# Audit "fable" — INTERRUPTED RUN (partial results)

Date: 2026-06-10. Worktree: `/Users/herbert/ai`, branch `master`, HEAD `842f476f4`.

## What this is

A planned **extreme full-codebase audit**: 31 parallel shard/lens finders
(full-coverage end-to-end reads of every production file), every candidate
finding adversarially verified by an independent agent (default-refute),
cross-shard dedup, a completeness-critic gap round, then synthesis into the
house `bugs_*.md` format.

**The run was stopped after ~20 minutes (65 of a planned ~250+ agents) to
preserve usage limits.** Eight of the 31 finders had completed; the
adversarial-verification wave was aborted mid-flight, so **none of the
salvaged findings are verified**. Prior audit rounds suggest roughly a third
of unverified candidates do not survive verification — treat every entry as
a lead to check by hand, not a confirmed bug.

## Ground rules (same as prior audits)

- Single-user app — no concurrent-multi-user scenarios (in-app coroutine
  races ARE in scope).
- The one user is trusted — no security / hardening / injection /
  path-traversal findings.
- The app itself only — no external-factor findings.
- No backwards-compatibility / legacy-data concerns.
- The owner's device runs a comma-decimal locale (nl-NL) — locale bugs count.

## Coverage

Completed shards (findings salvaged from their transcripts):

| Shard | Scope | Candidates |
|---|---|---|
| D1-storage | ReportStorage, SecondaryResult(+storage), ChatHistoryManager, PromptCache, ModelListCache, EmbeddingsStore, MetaCache, AtomicFileWrite, AuditLog | 6 |
| D2-dispatch | ApiDispatch, ApiStreaming, ApiClient, AnalysisRepository, ApiModels, RerankModerationApi | 11 |
| D3-interceptors | ApiTracer, TracingInterceptor, ProviderThrottling, retry interceptors, TagPropagation, AppLog, CrashReporter, ModelCooldown | 7 |
| D4-pricing-registry | PricingCache, ProviderRegistry, ProviderFieldTimestamps, HuggingFaceCache, seeds, MetadataDefaults | 10 |
| D5-rag-local-backup | Knowledge*, data/local, BackupManager, ReportBundle, SharedContent | 7 |
| D6-models-prefs | model/, data/preferences, run-model data classes | 8 |
| V1-appvm | AppViewModel, AppViewModelTypes | 9 |
| V2-reportvm | ReportViewModel, ReportViewModelHelpers | 11 |

**Not covered** (finders were still running or queued when stopped):
V3-engines, V4-runners, V5-icons-worker, V6-vm-rest, all six `ui/report`
shards, chat, all six settings/admin/shared shards, and the six
cross-cutting lenses (locale, cost-ledger, fresh-commits, build/assets,
doc-drift, Compose sweep). The entire `ui/` tree is effectively unaudited
by this run.

## Files

- `README.md` — this file.
- `00_summary.md` — severity counts + the HIGH candidates.
- `bugs_data_candidates.md` — all 69 salvaged candidates (data layer +
  view-models), grouped by file, **Status: Candidate — NOT verified**.

## Resuming

The workflow script is preserved at the session's
`workflows/scripts/extreme-audit-fable-wf_136690d9-7cf.js`; a future run can
re-execute it (the eight finished shards would need to re-run — the resume
cache belongs to the original session) or simply re-request the audit and
point the fleet at the uncovered shards listed above.
