# Audit "fable" summary — PARTIAL, UNVERIFIED

Date: 2026-06-10 · HEAD `842f476f4` · run interrupted to save usage limits.

8 of 31 planned finders completed (the data layer + view-model core); the
adversarial-verification pass never ran. **All 69 entries are unverified
candidates** — expect a meaningful false-positive rate (prior rounds: ~⅓).

## Candidate counts (unverified)

| Severity | Count |
|---|---|
| CRITICAL | 0 |
| HIGH | 8 |
| MEDIUM | 30 |
| LOW | 31 |
| **Total** | **69** |

Per shard: D1-storage 6 · D2-dispatch 11 · D3-interceptors 7 ·
D4-pricing-registry 10 · D5-rag-local-backup 7 · D6-models-prefs 8 ·
V1-appvm 9 · V2-reportvm 11.

## The 8 HIGH candidates (check these first)

1. **ReportViewModel.kt:952-964, 2799, 2925** — a background-continued
   report's tasks clobber whatever report is currently on screen (progress
   counter + shared `_agentResults` map). [V2]
2. **ReportViewModel.kt:2779-2845** — `regenerateAgent` replays with the
   wrong generation config: live UiState override, no captured
   presets/selection params, no system prompt for swarm rows. [V2]
3. **ApiDispatch.kt:943-960** — report streaming wraps the ENTIRE SSE drain
   in `withApiCallTimeout`, killing any streamed report longer than ~5
   minutes (contrast the per-open wrapping in ApiStreaming). [D2]
4. **PricingCache.kt:285-297 (+ ApiModels.kt:881-893)** — Gemini hidden
   thinking tokens (`TokenUsage.reasoningTokens`) are never billed by
   `computeInOutCost` — Gemini 2.5/3.x calls under-report cost. [D2]
5. **ApiDispatch.kt:639-649 (same in ApiStreaming.kt:326-336)** —
   Responses-API chat with an image encodes assistant history turns as
   `input_text`, so every turn after the first image 400s. [D2]
6. **ui/admin/BackupRestoreScreen.kt:95-143** — restore relies on a process
   restart that is never enforced; stale in-memory state can overwrite the
   just-restored data. [D5]
7. **AppViewModel.kt:1287-1291** — fire-and-forget settings saves on
   Dispatchers.IO can persist a stale snapshot; `resetApplication` can lose
   restored API keys on disk. [V1]
8. **BackupManager.kt:106-108** — `provider_field_timestamps` is not backed
   up, so after a restore the startup asset-sync silently reverts every
   user-edited provider field. [D4]

Full detail for all 69: `bugs_data_candidates.md`.
