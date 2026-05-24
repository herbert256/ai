# Bug audit — executive summary (audit-2)

Fresh, independent deep code review of the current codebase
(2026-05-24). Produced from scratch against the live source — the
earlier `audit/` was used only as a format reference, not as input.
Four domain reviewers ran in parallel; findings cite real `file:line`.

## Severity breakdown

| Severity | Reports | Chat | Settings | Data | **Total** |
|---|---|---|---|---|---|
| CRITICAL | 0 | 0 | 0 | 1 | **1** |
| HIGH | 2 | 10 | 5 | 4 | **21** |
| MEDIUM | 24 | 38 | 14 | 25 | **101** |
| LOW | 44 | 31 | 47 | 50 | **172** |
| **Total** | **70** | **79** | **66** | **80** | **295** |

All entries are **Open** — this is a fresh discovery pass; nothing here
has been re-verified-as-fixed or triaged against prior work.

## Critical-class bugs (crash / data loss / feature broken)

### 1. `createAppGson` has no Kotlin-null handling — NPE on restored/imported/corrupt JSON
`data/ApiModels.kt:16-22`. Plain `GsonBuilder().create()`; Gson's
`UnsafeAllocator` bypasses constructors, so any persisted JSON missing
a field that is a non-null Kotlin property (e.g. `Report.title`,
`Report.agents`) deserializes to `null` in a `String`/`List` typed
non-null. The NPE then fires later in `copyReport` /
`updateAgentStatus` / list iteration — far from the bad read. This is
the same class as the just-fixed `InternalPrompt.parameters` crash, but
systemic across every data class loaded via Gson (reports, chats,
secondary results, settings blobs). (bugs_data Bug 1; related 30/76/77.)
*Fix:* register a null-asserting / default-applying type-adapter factory
(or sanitize on load) so missing non-null fields fall back to defaults.

## HIGH-band, ranked by likely user impact

1. **Tag-propagation leak skips throttle + 429/529 retry** —
   `data/TagPropagation.kt:136-152`. `TagPropagatingExecutor` only
   re-applies `permitPreAcquired` / `suppressInlineRetry` when the
   captured value is `true`, so a stale `true` sticks to a reused
   cached-pool thread; later unrelated calls then skip their throttle
   acquire or skip retry. Blast radius in the test engine and
   translation/fan-out. (bugs_data Bug 12; cf. 64, 74.)
2. **Pricing precedence: snapshot disagrees with billing** —
   `data/PricingCache.kt`. `lookupPricing` orders the manual OVERRIDE
   *after* LiteLLM/models.dev/etc., contradicting `getPricing`'s
   documented OVERRIDE-first precedence; the capability/pricing snapshot
   shown to the user diverges from what is actually billed. (bugs_data 35.)
3. **Get-info / info-cost ignores the Provider+Model pin** —
   `ui/report/manage/GetInfo.kt`. `buildInfoJobs` gates the icon/title
   rows on a named-agent lookup that ignores the new Provider+Model pin,
   while the cost view uses `resolvePromptAgent` which honors it — pinned
   icon/title prompts vanish from Get-info and under-report the info
   total. (Regression from the Provider+Model-pin commit.) (bugs_reports 1.)
4. **Dual-chat "Chat N more" doesn't cancel the prior job** —
   `ui/chat/DualChatScreen.kt`. Relaunches `chatJob` without cancelling
   the previous one → two loops interleave messages and double-count
   cost. (bugs_chat 17.)
5. **Embedding-search index-out-of-bounds crash** — `vecs[j]` in both
   semantic-search batch loops crashes the coroutine when a provider /
   local embedder returns fewer vectors than inputs. (bugs_chat 57, 61.)
6. **Manage "AI Usage" card permanently empty** — looks up a 2-part
   `provider::model` key while usage is stored under 3-part
   `provider::model::kind` keys; the View screen was fixed with a prefix
   match, Manage was not. (bugs_chat 45; cascades to 46.)
7. **Multi-part Gemini chat replies truncated** — `chatGemini` reads
   only `parts[0].text` (the analyze path has the multi-part fallback).
   (bugs_data 5.)
8. **Settings export/import drops ~⅔ of GeneralSettings** —
   `ui/settings/ImportExportScreen.kt:254-312` round-trips ~11 of 30+
   fields; metadata toggles, default icons, all network/retry/concurrency
   caps, and app-wide/report-model prompt+params are silently lost.
   (bugs_settings 5.)
9. **APK copy on the main thread → ANR** —
   `ui/admin/UpdateFromCloudScreen.kt:131-145`. (bugs_settings 16.)
10. **Test-Agent result never shown** — `AgentsScreen.kt:280-318`
    computes the result into a dead `error` local; pass/fail is never
    surfaced. (bugs_settings 1.)
11. **`importType` not `rememberSaveable`** — a process kill while the
    SAF picker is foreground mis-routes the imported file to the default
    "keys" branch. (bugs_settings 6.)
12. **Dual-chat config lost on resume** — config lives only in transient
    UiState while the conversation is persisted, so resume after process
    death discards the run. (bugs_chat 22.)
13. **Manual cost-override edit orphans + duplicates** — editing to a
    different (provider, model) leaves the old key and adds a new one,
    unlike the other model-state CRUDs. (bugs_settings 4; cf. 44.)
14. **Overlay back-stack anti-pattern in report View** — stacked
    positional `if (state != null) { …; return }` sub-overlays plus a
    Rerank→Reports handler flipping two flags in one tap. (bugs_reports 8.)
15. **Process-global tracer tags held across the dual-chat loop** →
    traces from other screens mis-tagged. (bugs_chat 16.)

## Themes worth fixing in batches

- **Gson reflection nullability** — the CRITICAL plus several
  reports/settings nulls: non-null Kotlin fields come back `null`. One
  type-adapter factory fixes the whole class. (data 1/30/76/77.)
- **Thread-local / tag propagation correctness** — stale `true`
  leaking onto pooled threads corrupts throttle + retry decisions.
  (data 12, 64, 74.)
- **Pricing precedence & usage-key shape** — snapshot-vs-billing
  divergence (data 35) and 2-part-vs-3-part usage keys (chat 45/46).
- **Non-atomic load→mutate→save** — report and regenerate-job
  persistence race a concurrent cancel/orchestrator. (data 27/58.)
- **Secret-redaction gaps** — trace request bodies and report-agent
  headers persisted verbatim, then land in the backup zip. (data 17/70.)
- **Main-thread I/O / ANR** — APK copy (settings 16) and other
  blocking reads on click handlers.
- **Compose state-key & overlay issues** — missing `rememberSaveable`
  keys (settings 6), recomputation without `remember`, and the
  recurring overlay back-stack pattern (reports 8).
- **Export/import & restore completeness** — partial GeneralSettings
  round-trip (settings 5), device-only prefs not cleared on restore,
  cost-section parse aborts (settings 7/11).
- **Embedding batch bounds** — `vecs[j]` assumes 1:1 vector:input.
  (chat 57/61.)

## Method & caveats

- Static review only; no runtime reproduction. Severities are the
  reviewers' estimates of impact, not measured.
- Line numbers are accurate as of 2026-05-24 and will drift with edits.
- LOW is the largest bucket by design — it captures suspicious /
  unconfirmed / cosmetic items worth a second look, not confirmed
  defects.
