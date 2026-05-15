# Persistent Storage

Everything the app keeps on disk, where it lives, and what's in each
slot. All of this rounds-trip through `BackupManager` (Settings →
Housekeeping → Backup & Restore) into a single `.zip`.

## SharedPreferences (6 files)

All under `/data/data/com.ai/shared_prefs/<name>.xml`. All six are
captured in `BackupManager.PREFS_TO_BACKUP`.

### `eval_prefs` — main settings
By far the largest. Loaded by `SettingsPreferences`.

#### General settings
| Key | Type | Notes |
|---|---|---|
| `user_name` | String | display name shown in UI (default `"user"`) |
| `huggingface_api_key` | String | for HF Model Info lookups |
| `openrouter_api_key` | String | for OpenRouter pricing tier |
| `artificial_analysis_api_key` | String | for AA pricing/scores tier |
| `default_email` | String | default email for the report email export |
| `default_type_paths` | JSON Map<String,String> | global per-type API path defaults |
| `tracing_enabled` | Boolean (default true) | master switch for `ApiTracer.isTracingEnabled` |
| `model_name_layout` | String | enum name (`MODEL_ONLY` / `PROVIDER_AND_MODEL`) |
| `subject_to_title_bar_mode` | String (default `BOTH`) | tri-state enum (`HARDCODED` / `SUBJECT` / `BOTH`). `HARDCODED` keeps the legacy fixed label + green sub-header; `SUBJECT` replaces with the dynamic subject; `BOTH` joins them with `/`. Replaces the legacy boolean `subject_to_title_bar` |
| `icon_gen_enabled` | Boolean (default true) | master switch for the per-report icon-gen feature (background `internal/icon` call on every new report — see [report-icons.md](report-icons.md)) |
| `per_model_icon_gen_enabled` | Boolean (default true) | master switch for the per-agent 3-tier icon chain (auto-fires `runReportIconsForAgent` on every successful agent call) |
| `recent_report_models` | String (newline-separated) | last 3 (provider, model) picks from the Report section's model pickers, most-recent first. Encoded as `"providerId|model"` strings |
| `streaming_read_timeout_sec` | Int | read timeout for streaming API calls (SSE chat/report). Default = `BuildConfig.NETWORK_READ_TIMEOUT_SEC` |
| `nonstreaming_read_timeout_sec` | Int | read timeout for non-streaming calls. Default = `BuildConfig.NETWORK_NONSTREAMING_READ_TIMEOUT_SEC` |
| `max_calls_per_provider_per_minute` | Int (default 30) | per-host sliding-window rate cap mirrored to `NetworkSettings.maxCallsPerProviderPerMinute`. See [throttle.md](throttle.md) |
| `max_concurrent_calls_per_provider` | Int (default 3) | per-host concurrency cap |
| `max_retries_on_429` | Int (default 3) | in-line 429 retries; 0 disables |
| `retry_backoff_ms_429` | Long (default 1000) | wait between 429 retry attempts in milliseconds |
| `max_retries_on_529` | Int (default 3) | in-line 529 (server overloaded) retries; 0 disables |
| `retry_backoff_ms_529` | Long (default 1000) | wait between 529 retry attempts in milliseconds |
| `log_level` | String (default `INFO`) | threshold for the in-app file logger (`com.ai.data.AppLog`). One of `TRACE` / `DEBUG` / `INFO` / `WARN` / `ERROR` / `OFF`. Read directly by `AppLog.init` from `eval_prefs` so DEBUG calls inside bootstrap are admitted on cold start |
| `show_knowledge_card` | Boolean (default false) | gates the AI Knowledge card on the home Hub. The Knowledge subsystem itself stays fully functional whether the card is visible or not |
| `first_run_bootstrapped` | Boolean | gates the first-run providers + prompts seed (the every-start delta-merge still runs on subsequent starts — see [architecture.md](architecture.md)) |

> The intro / model_info / translate / rerank / moderation prompt
> templates that used to live as dedicated `*_prompt` keys now live
> as `InternalPrompt` rows under `ai_meta_prompts` (with
> `category="internal"`) — see below.

#### Per-provider config
For every provider id (`<key> = service.id`, e.g. `OpenAI`):
| Key | Type | Notes |
|---|---|---|
| `<key>_api_key` | String | provider API key |
| `<key>_model` | String | default model id |
| `<key>_model_source` | String | `API` or `MANUAL` |
| `<key>_manual_models` | JSON List<String> | persisted model list |
| `<key>_model_types` | JSON Map<String,String> | id → "chat"/"embedding"/... |
| `<key>_vision_models` | JSON List<String> | user-flagged vision-capable ids |
| `<key>_web_search_models` | JSON List<String> | user-flagged web-search-capable ids |
| `<key>_reasoning_models` | JSON List<String> | user-flagged reasoning-capable ids |
| `<key>_vision_capable_computed` | JSON List<String> | precomputed layered lookup result |
| `<key>_web_search_capable_computed` | JSON List<String> | precomputed layered lookup result |
| `<key>_reasoning_capable_computed` | JSON List<String> | precomputed layered lookup result |
| `<key>_model_pricing` | JSON Map<String, ModelPricing> | precomputed prices |
| `<key>_model_capabilities` | JSON Map<String, ModelCapabilities> | provider self-report |
| `<key>_models_response_raw` | String | raw last `/models` response |
| `<key>_parameters_id` | JSON List<String> | default param presets |

> The legacy per-provider `_admin_url` and `_model_list_url` override
> keys have been dropped — admin URLs come from the bundled
> provider definition only.

#### Top-level lists
| Key | Type | Notes |
|---|---|---|
| `ai_agents` | JSON List<Agent> | |
| `ai_flocks` | JSON List<Flock> | |
| `ai_swarms` | JSON List<Swarm> | |
| `ai_parameters` | JSON List<Parameters> | |
| `ai_system_prompts` | JSON List<SystemPrompt> | |
| `ai_meta_prompts` | JSON List<InternalPrompt> | despite the legacy `meta` name in the key, this holds **every** Internal Prompt — Meta / Fan-out / Fan-in / Other internal categories — so users who already have seeded entries don't lose them across the rename to InternalPrompt |
| `ai_example_prompts` | JSON List<ExamplePrompt> | starter library for the New Report flow |
| `ai_endpoints` | JSON Map<String, List<Endpoint>> | keyed by provider id |
| `provider_states` | JSON Map<String, String> | "ok"/"error"/"inactive"/"not-used" |
| `ai_model_type_overrides` | JSON List<ModelTypeOverride> | |

#### Caches and bookkeeping
| Key | Type | Notes |
|---|---|---|
| `model_list_timestamp_<providerId>` | Long | last successful `/models` fetch — drives 24h cache validity |
| `caps_precomputed_version` | Int | bootstrap-migration version flag for the precompute pass |
| `ai_report_agents_v2` | StringSet | last-used agent selection for the Reports flow |
| `ai_report_models_v2` | StringSet | last-used direct-model selection for the Reports flow |
| `secondary_last_<promptId>_<reportId>` | StringSet | per-(report, prompt) last-selected models for the Meta / Translate pickers |
| `last_ai_report_title` | String | most recent report title (used by external-intent flows) |
| `last_ai_report_prompt` | String | most recent report prompt |

### `provider_registry`
Custom provider definitions added by the user (or imported via
`assets/providers.json`). Keyed by provider id; serialized as
`ProviderDefinition` JSON. Read by `ProviderRegistry` at startup;
merges with the bundled `assets/providers.json` definitions.

### `provider_field_timestamps`
Per-provider, per-field "user-touched-at" timestamps that the
every-start `assets/providers.json` sync consults to decide
which fields to refresh.

| Key | Type | Notes |
|---|---|---|
| `ts` | JSON Map<String, Map<String, Long>> | `{ "OpenAI": { "baseUrl": 1715…, "modelFilter": 1716… }, … }`. Set by `ProviderRegistry.update` whenever the new value differs from the existing one; asset-driven paths don't bump |

Field names match `AppService` property names. A null lookup
means "never user-touched, refresh on next start"; a non-null
timestamp means "user edited this field, the asset sync should
leave it alone". See [throttle.md](throttle.md) and
[architecture.md](architecture.md).

### `pricing_cache`
Bookkeeping (timestamps) plus the small manual-override map. The
large tier blobs themselves used to live here but were moved to
`<filesDir>/pricing/` (see below) — SharedPreferences loads its
entire map into memory at process start and keeps it there for
the lifetime of the process, so a multi-MB JSON string in a prefs
file pays that cost forever even though it's only consulted on
demand.

| Key | Type | Notes |
|---|---|---|
| `litellm_timestamp` | Long | last LiteLLM fetch ms |
| `openrouter_timestamp` | Long | last OpenRouter fetch ms |
| `together_timestamp` | Long | last Together native fetch ms |
| `models_dev_timestamp` | Long | last models.dev fetch ms |
| `helicone_timestamp` | Long | last Helicone fetch ms |
| `llmprices_timestamp` | Long | last llm-prices.com fetch ms |
| `aa_timestamp_v2` | Long | last Artificial Analysis fetch ms |
| `manual_pricing` | JSON Map<String, ModelPricing> | per-(provider, model) user overrides |

The `_v2` suffix on the AA timestamp exists to invalidate older
UUID-keyed entries from a previous parser revision.

### `dual_chat_prefs`
Last-used Dual Chat configuration.

| Key | Type |
|---|---|
| `config_v1` | JSON DualChatConfig |
| `subjects_recent` | JSON List<String> (recent subjects) |
| `prompts_first_recent`, `prompts_second_recent` | JSON List<String> |

### `huggingface_cache`
Cached HuggingFace model-info lookups (positive + negative). 7-day TTL
keyed on `<providerId>::<modelId>`.

| Key | Type |
|---|---|
| `entries_json` | JSON Map<String, Entry(ts, info?)> |

`info = null` is meaningful — a cached miss that short-circuits the
network call until the TTL expires. Concurrent load-modify-save
is serialised so two simultaneous misses don't tear the JSON blob.

## Files (under `<filesDir>`)

The app's `filesDir` is `/data/data/com.ai/files/`. The tree is
captured by the backup zip with two top-level exceptions —
`local_llms/` and `local_models/`, which hold user-supplied
multi-GB on-device model bundles. See
[backup-restore.md](backup-restore.md) for the exclude-and-preserve
contract.

Almost every JSON write goes through `AtomicFileWrite.writeTextAtomic`
— a `Files.move(ATOMIC_MOVE)` of an fsync'd temp file, with
parent-dir auto-mkdir. Most writes are also wrapped in a
`ReentrantLock` per storage object.

### `pricing/<key>.json`
Tier blobs for `PricingCache`. One file per (tier, payload):

| File | Tier |
|---|---|
| `openrouter_pricing.json` | OpenRouter |
| `together_pricing.json` | Together AI native |
| `litellm_pricing.json` | LiteLLM (BerriAI) |
| `litellm_meta.json` | LiteLLM capabilities sidecar |
| `models_dev_pricing.json` | models.dev |
| `models_dev_meta.json` | models.dev capabilities sidecar |
| `helicone_pricing.json` | Helicone exact-match prices |
| `helicone_patterns.json` | Helicone pattern rules (`startsWith` / `includes`) |
| `llmprices_pricing.json` | llm-prices.com |
| `aa_pricing_v2.json` | Artificial Analysis |
| `aa_meta_v2.json` | Artificial Analysis intelligence/speed scores |

Reads go through `PricingCache.loadBlob`, which falls back to the
legacy `pricing_cache` SharedPreferences key once on the first
read after the upgrade, copies the JSON to the file, and removes
the prefs entry. Subsequent reads hit the file directly. Saves
go straight to disk via `writeTextAtomic` and clear the legacy
prefs key as a belt-and-braces guard.

### `reports/<reportId>.json`
One file per generated report. Holds the prompt, every agent's
request/response/headers/usage/citations/cost, status, durations,
plus `knowledgeBaseIds`, `imageBase64/Mime` (vision), `webSearchTool`
/ `reasoningEffort` (regen state), `sourceReportId` (translated
copies), and `pinned`. Written atomically; protected by
`ReportStorage`'s `ReentrantLock`. Save failures log a warning
instead of being silently swallowed.

### `secondary/<reportId>/<resultId>.json`
One file per `SecondaryResult` row — RERANK, META (every chat-type
Meta / Fan-out / Fan-in prompt), MODERATION, or TRANSLATE. META
rows carry the user-given `metaPromptName` (and `metaPromptId`)
so the UI / exports group them under the prompt name regardless
of how many or which prompts the user has configured. The
`secondaryScope` field encodes the SecondaryScope used at run
time so a cascade re-runs at the same scope. Subdirectory per
parent report so deleting a report cascades cleanly. Translate
rows additionally carry `translateSourceTargetId/Kind`,
`targetLanguage/Native`, and a shared `translationRunId`. Fan-out
rows carry `fanOutSourceAgentId`; Fan-in rows carry `fanInOf`.

`SecondaryResultStorage.save` validates that the resolved file
path stays inside the configured directory (defence against
`..`-traversal in a corrupt id), keeps a per-(reportId)
fingerprint cache (`(name, mtime, length)` joined across every
JSON file) to catch in-place edits, and invalidates the cache
on delete.

### `applog/applog_<yyyyMMdd>.log`
Daily-rotating plain-text log files produced by
`com.ai.data.AppLog`. One line per call, format:

```
yyyy-MM-dd HH:mm:ss.SSS LEVEL TAG: message
```

The writer is held open across calls and flushed per line so a
process kill never loses the last few lines. Sensitive headers
(`Bearer …`, raw `sk-/xai-/gsk_/key-` keys, Google `?key=`
params) are redacted inline before write. An in-memory
`AppLog.cachedFiles` list mirrors the directory listing so the
viewer's list screen is O(1) once warm.

Reachable from Hub → AI App log. Threshold persisted in
`eval_prefs` as `log_level`. See [applog.md](applog.md).

### `trace/<hostname>_<timestamp>_<seq>.json`
One file per outbound API call (when `ApiTracer.isTracingEnabled` is
true — on by default; toggleable in Settings). Each holds the full
request (URL, method, headers, body) and the response (status,
headers, body — except streaming responses, which note that they
were not captured to avoid breaking the response stream). Tagged
with the `reportId` and `model` of the originating call where
applicable.

Auth headers are redacted at write time, not just on Copy / Share
— a leaked filesystem dump never carries plain keys. Body capture
caps at 8 MiB so a runaway streaming response doesn't blow up.
Hostnames are sanitised before being used as filename components
(no path-traversal injection from a malicious URL).

On-device LLM (`LocalLlm.generate`) and on-device embedder
(`LocalEmbedder.embed`) calls write traces too, with
`hostname = "local"` and url like `local://generate/<modelFile>` or
`local://embed/<modelFile>`.

`ApiTracer` keeps an in-memory `cachedTraceFiles` list, prewarmed
off the main thread on `init`, so the Trace list / detail
prev-next nav is O(1) once the cache is populated.

### `chat-history.json`
Single JSON file with every persisted chat session. Managed by
`ChatHistoryManager`. Sessions are auto-saved as messages arrive.
Holds `pinned` and `knowledgeBaseIds` per session. Atomic writes;
delete + cache invalidation are taken under a single lock.

### `prompt-history.json`
Up to 100 most-recently-used report prompts. Managed by
`SettingsPreferences.savePromptHistory`. The Hub's "Prompt history"
card reads it.

### `usage-stats.json`
List of `UsageStats` entries — one per `(provider, model, kind)`
triple. Updated in-memory by every successful API call; disk-flushed
on a 2-second debounce. `ViewModel.onCleared` forces a flush off
the main thread on `NonCancellable` so a Refresh-all auto-restart
can't drop in-flight stats. Read by the AI Usage screen.

### `test_run.json`
The single most-recent "Test all models" run (Housekeeping → Test →
Test all models). One JSON document — `ModelTestRunState` with a
per-`(provider, model)` `ModelTestState` map. Managed by
`ModelTestRunStore`: the `ModelTestEngine` flushes it on each item
completion (crash-safe partial results) and once on run end. A fresh
run overwrites it; Housekeeping → Reset → Clear runtime data drops it.
Atomic writes under a `ReentrantLock`. Not in
`FILES_DIR_BACKUP_EXCLUDES`, so it round-trips through backup/restore.

### `prompt_cache/`
Cached `PromptCache` entries — per-prompt cached responses with TTL.
Used by `PromptCache` to short-circuit repeat lookups (e.g. the
Model Info "model info" prompt). Cache keys are length-prefixed
hashes so a `|` separator collision can't conflate two distinct
keys.

### `model_lists/<providerId>.json`
Most recent `/models` raw JSON per provider. Used by the Model Info
screen and by `ModelListCache`. Atomic writes; provider id is
sanitised before use as a filename.

### `model_pricing.json`, `model_supported_parameters.json`
Supplementary catalogs (atomic writes).

### `knowledge/<kbId>/`
Knowledge base data. Per-KB layout:

```
knowledge/<kbId>/
  manifest.json     — KnowledgeBase + KnowledgeSource[] list
  chunks/
    <sourceId>.json — JSON array of KnowledgeChunk for that source
  files/
    <localCopy>     — locally-persisted source file
```

One JSON file per source keeps add / remove / re-index cheap (no
full-KB rewrite for a single-source change), while loading a whole
KB for retrieval still scans only one directory. Chunk + manifest
writes are taken under a single lock so a crash mid-write can't
leave the manifest pointing at half a chunk file. `clearAll` and
`deleteKnowledgeBase` also take the store lock so a full-reset
and an in-flight ingest can't race. See [knowledge.md](knowledge.md).

### `embeddings/<sha256>.json`
Per-document embedding cache, keyed by SHA-256 of `(providerId, model,
docId, contentHash)`. Used by the layered Local / Remote semantic
search screens to short-circuit re-embedding. Dim mismatches log a
warning instead of silently zeroing.

### `local_models/<name>.tflite`
On-device text-embedder models for `LocalEmbedder`. The default
Universal Sentence Encoder is downloaded on first use of the Local
Semantic Search screen. Custom user-supplied `.tflite` files (with
proper MediaPipe Tasks metadata) can be added via the SAF picker on
**AI Setup → Local Models → Local LiteRT models**.

**Backup behaviour:** this directory is in
`BackupManager.FILES_DIR_BACKUP_EXCLUDES` — its contents are
skipped by the backup zip and preserved through the restore wipe.
See [backup-restore.md](backup-restore.md).

### `local_llms/<name>.task`
On-device LLM bundles for `LocalLlm`. User-supplied — most models
require accepting model-card terms in a browser before download. The
**AI Setup → Local Models → Local LLMs** card carries hand-off
links and a SAF picker that accepts `.task`, `.zip`, `.tar.gz`,
`.tgz`, and `.tar` archives.

**Backup behaviour:** same as `local_models/` above — excluded
from the backup zip, preserved through the restore wipe.

## What's NOT persisted

- Raw streaming SSE bodies (response body in trace files reads
  `[streaming response - not captured]`).
- WebView Chromium cookies and process state — intentionally
  excluded from the backup zip; doesn't make sense to restore on a
  different device.
- The `assets/providers.json` provider catalog — this ships in the
  APK and is consumed once at first run; the merged result lives
  in `provider_registry` prefs from then on. Restore re-reads the
  asset and grafts in any provider id missing from the restored
  prefs (handles "old backup, new app version, new provider").

## What's NOT in the backup zip

- `local_llms/<name>.task` and `local_models/<name>.tflite` —
  user-supplied multi-GB on-device model bundles. Listed in
  `BackupManager.FILES_DIR_BACKUP_EXCLUDES`; skipped on backup,
  preserved through the restore wipe so a settings/data restore
  doesn't destroy them.
- In-flight cacheDir temp files matching
  `CACHE_TOPLEVEL_SKIP_PREFIXES` (`ai-restore-`, `reset_keys_`,
  `ai-backup-`) — these would self-contain the in-flight backup,
  yank the file out from under the in-flight restore, or leak API
  keys.

`cacheDir` itself **is** mirrored into the backup zip
(`cache/...`) so exports / shared-trace handoffs / camera captures
round-trip — only the in-flight temp prefixes are skipped.

See [backup-restore.md](backup-restore.md) for the full backup
format and restore semantics.

## Cleanup paths

`SettingsPreferences` exposes:
- `clearPromptHistory()` — empties `prompt-history.json`
- `clearLastReportPrompt()` — clears the two `last_ai_report_*` keys
- `clearUsageStats()` — empties `usage-stats.json` and the in-memory cache
- `clearTraces()` — `ApiTracer.clearTraces()` deletes every file under `trace/`
- `AppLog.clearLogs()` — deletes every file under `applog/`

**Housekeeping → Reset** is split into five dedicated sub-screens
(each in its own `CollapsibleCard`, collapsed by default):

- **Clear all runtime data** — wipes logs, chats, traces, usage
  stats, plus AI reports and prompt history. Narrower than the
  legacy single button: knowledge / pricing / model-list caches
  stay put.
- **Clear Info providers** — wipes the seven external-info
  repository caches (LiteLLM, OpenRouter, models.dev, Helicone,
  llm-prices, Artificial Analysis, HuggingFace) and their
  per-tier timestamps in `pricing_cache`.
- **Clear all configuration** — wipes provider config, prompts,
  Local LLMs, LiteRT models. Asks before destructive actions.
- **Restore bundled assets** — re-merges `providers.json` /
  `prompts.json` / `examples.json` from the APK. User edits
  on existing rows are preserved.
- **Reset application** — factory-style reset that preserves
  API keys (written to a temp file under `cacheDir/reset_keys_*`,
  restored after the wipe). No longer runs a trailing
  Refresh-all chain; the user can fire it from the Refresh
  screen if they want it.

After any wholesale-state-replace op, the **Restart-app dialog**
prompts the user to relaunch so the in-memory state matches the
freshly written on-disk state.

None of these touch the on-device `.task` / `.tflite` files unless
the "Clear all configuration" path is taken — those persist across
runtime resets and have their own per-row Remove on the matching
Local LLMs / Local Models cards.
