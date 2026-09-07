# Fan Meta fixes and additional second results — 7 September 2026

The five findings from [the Fan Meta monitor](fan-meta-monitor-2026-09-07.md) have source fixes. The existing Funny question report was repaired locally, preserving its original answers and paid-call history. Additional live use found and fixed seven related secondary-result/UI defects.

Report: `4757c2cc-36e7-4d2e-9a9c-531bca7162dd` — **Request for a Funny Question**.

## Fan Meta corrections

| Finding | Correction | Verification and limits |
|---|---|---|
| FM01: incorrect work review | Freeze the effective prompt, expanded workers, parameters and endpoints before review. Handle original-answer-model mode explicitly. Review replacements before clearing saved metadata. Save the frozen run evidence. | Source review and successful Android build. A full replacement run was not launched; see limits below. |
| FM02: worker completion blocked by report reloads | Separate the journal flush lock from enqueue; release enqueue before report append; acknowledge only unchanged captured records. Avoid redundant JSON serialization, repeated initialization locking, per-tick hydration and cancelled/restarted blocking reads. Cache unchanged report versions, conflate refreshes, maintain cache byte totals incrementally and prune stale atomic files once per directory/minute. Show preparation progress. | Local repair and new secondary calls completed. File/parent sync and UUID ledger deduplication remain. No claim of a measured large-batch speedup. |
| FM03: malformed titles accepted | Shared parser strips supported Markdown/labels, bounds titles to 30 Unicode code points and requires both a title and emoji. Keep raw replies in traces. | All 1,256 historical titles now fit the limit; zero retained Markdown prefixes. |
| FM04: missing exact metadata traces | Persist every accepted/rejected/failed worker attempt separately from the Fan Out response, expose per-pair API attempts, and remap attempt references during report import. | Recovered 1,237 exact historical attempt links: 1,236 winners and one rejected reply. Twenty winning pair links remain unavailable/ambiguous; the UI does not guess. |
| FM05: rejected costs missing from subtotal | Attribute all attempt costs to their pair and actual worker. Keep ambiguous historical rejected attempts at run level and include their cost in the run subtotal. | Correct Fan Meta total: **$0.105643623**, including both Together rejected replies. One rejected attempt costing $0.0000532 remains explicitly at run level. Existing lifetime ledger was already correct and was not charged again. |

The one-time `FanMetaRepair` uses only saved local evidence. It leaves response content, source references, generation settings and original Fan Out costs unchanged. It does not make provider calls. Interrupted repair can repeat safely without duplicating attempts. Report bundle version remains 2; the new data is nested in existing report/secondary fields.

## New second results

Three additional results now exist on this report:

| Result | Provider / model | HTTP | Duration | Recorded cost |
|---|---|---:|---:|---:|
| Compare agreements/disagreements | Requesty / `openai-responses/gpt-4.1-nano` | 200 | 6.354 s | $0.00041770 |
| Synthesize responses | DeepInfra / `google/gemma-3-12b-it` | 200 | 1.607 s | $0.00007365 |
| Rank by question relevance | Cohere / `rerank-v3.5` | 200 | 0.382 s | $0.00200000 |

Combined additional cost: **$0.00249135** (0.249135¢). Lifetime report cost: **$0.9317316119**, across **2,641 costed calls**. Amounts are app-recorded costs, not provider invoice reconciliation.

The comparison contains agreement/disagreement analysis with numbered source references. The synthesis is the short question “What do you call a cow with no legs?”; this is compatible with the example's request for a funny question. The native ranking covers all 36 saved answers exactly once, with IDs/ranks 1–36 and the same source-agent mapping. Native relevance scores measure relevance to the question; they are not a verified ranking of humor, correctness or overall quality.

## Defects exposed by the additional runs

1. **Provider key was resolved but not handed to direct model workers.** `executeSecondaryTask` now copies the effective key into the actual dispatch agent. The first comparison exhausted its workers before any network request. Its empty, unbilled failed row was removed, and the fresh comparison succeeded.
2. **Single-result retry review showed all primary models.** It now describes the saved result's actual model, endpoint, parameters and prompt. If no recorded provider resolves, it describes the resolved fallback pool. The corrected native rerank retry review was verified live.
3. **Fallback retained the previous provider's execution configuration.** The storage layer now allows explicit replacement of execution settings when staging a provider/model switch, while preserving accumulated costs and source snapshots. Previously the failed Parasail row retained Requesty's endpoint. No request was sent using that mismatched saved configuration.
4. **Native rerank preview showed the chat host.** Cohere's rerank host is `api.cohere.com`; the review had shown `api.cohere.ai`. The endpoint guard blocked the request without a provider charge. Frozen worker previews and native execution now use the actual native endpoint; native rerank/moderation also persist their execution configuration. The corrected Cohere retry succeeded after selecting its displayed endpoint.
5. **Native usage handoff could omit its trace filename.** Native rerank/moderation accounting now explicitly carries the captured attempt trace. The new ranking's missing ledger link was repaired locally after verifying its saved trace's query and all 36 source documents. The row already had its correct trace. No amount or ledger count changed.
6. **Cancelled work review left an unstarted placeholder.** Cancelling a new secondary review now removes only an unstarted, empty, unbilled row with no execution configuration or trace. Existing results and started attempts remain. The optional moderation placeholder left by the old behavior was removed without running it. A comparison review was subsequently prepared and cancelled on the final build; only the three completed added results remained and the ledger count did not change.
7. **Loading statistics looked like real zero values; native ranking was labelled quality.** The statistics line now shows a loading state, and its refreshes reuse unchanged report files. Native ranking rows explicitly say question relevance even if an old saved worker prompt has a generic quality title.

## Validation and boundaries

- Default repository cycle: Android debug build, in-place emulator install, cloud APK copy, launch and foreground verification. No uninstall, data reset, unit suite or instrumented suite.
- Three additional results returned HTTP 200 and have retained trace files. Their costs reconcile to exactly three new ledger entries.
- All 36 original primary answer records and all 2,638 original ledger entries are preserved. All original Fan Out answer content, response errors, generation configurations and response costs are unchanged. The four original Fan Out failures remain outside this task's metadata/new-result scope.
- No fatal exception or storage error appeared in the inspected process-scoped logs. Cold-start loading was observed; the statistics loading display was corrected.
- The full 1,256-item replacement Fan Meta run was not benchmarked. Automatic approval review rejected that rerun because it would replace saved metadata and incur another large paid batch. Source-level concurrency remains parallel; a new large-run speed measurement is still unavailable.
- An optional fourth result, Mistral moderation, was not run. Automatic approval review rejected sending all saved answers to that destination without destination-specific authorization. Its review was cancelled. The three requested additional results above remain complete.
- Future-attempt persistence and the native moderation path are build/source verified; a new large Fan Meta run and a moderation network call were not used to validate them. Exact historical links that cannot be recovered remain explicitly unavailable.

Permanent machine-readable checks are in [the verification record](fan-meta-fixes-2026-09-07-evidence.json). Temporary before/after snapshots are under `/tmp/fanmeta-fix-20260907/`; they contain no copied settings credentials. Provider outputs were treated as data.
