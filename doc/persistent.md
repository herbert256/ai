# Persistent Storage

Everything the app keeps on disk, where it lives, and what's in each
slot. All of this rounds-trip through `BackupManager` (Settings →
Housekeeping → Backup) into a single `.zip`.

## SharedPreferences (5 files)

All under `/data/data/com.ai/shared_prefs/<name>.xml`. All five are
captured in `BackupManager.PREFS_TO_BACKUP`.

### `eval_prefs` — main settings
By far the largest. Loaded by `SettingsPreferences`.

#### General settings
| Key | Type | Notes |
|---|---|---|
| `user_name` | String | display name shown in UI |
| `huggingface_api_key` | String | for HF Model Info lookups |
| `openrouter_api_key` | String | for OpenRouter pricing tier |
| `artificial_analysis_api_key` | String | for AA pricing/scores tier |
| `default_email` | String | default email for the report email export |
| `default_type_paths` | JSON Map<String,String> | global per-type API path defaults |
| `intro_prompt` | String | Self-introduction template (blank → seeded default from `assets/prompts.json`) |
| `model_info_prompt` | String | Model Info template (blank → seeded default) |
| `translate_prompt` | String | Translate template (blank → seeded default) |

> Rerank and every chat-type Meta prompt (Compare, Critique,
> Summarize, …) used to live here as dedicated keys; they now
> live as `InternalPrompt` rows under
> `eval_prefs.internal_prompts` and are CRUD-managed via Settings
> → AI Setup → Prompt management.

#### Per-provider config
For every provider id (`<key> = service.prefsKey`, e.g. `ai_openai`):
| Key | Type | Notes |
|---|---|---|
| `<key>_api_key` | String | provider API key |
| `<key>_model` | String | default model id |
| `<key>_model_source` | String | `API` or `MANUAL` |
| `<key>_manual_models` | JSON List<String> | persisted model list |
| `<key>_model_types` | JSON Map<String,String> | id → "chat"/"embedding"/... |
| `<key>_vision_models` | JSON List<String> | user-flagged vision-capable ids |
| `<key>_web_search_models` | JSON List<String> | user-flagged web-search-capable ids |
| `<key>_vision_capable_computed` | JSON List<String> | precomputed layered lookup result |
| `<key>_web_search_capable_computed` | JSON List<String> | precomputed layered lookup result |
| `<key>_model_pricing` | JSON Map<String, ModelPricing> | precomputed prices |
| `<key>_model_capabilities` | JSON Map<String, ModelCapabilities> | provider self-report |
| `<key>_models_response_raw` | String | raw last `/models` response |
| `<key>_admin_url` | String | provider's admin URL (overridable) |
| `<key>_model_list_url` | String | optional `/models` URL override |
| `<key>_parameters_id` | JSON List<String> | default param presets |

#### Top-level lists
| Key | Type |
|---|---|
| `ai_agents` | JSON List<Agent> |
| `ai_flocks` | JSON List<Flock> |
| `ai_swarms` | JSON List<Swarm> |
| `ai_parameters` | JSON List<Parameters> |
| `ai_system_prompts` | JSON List<SystemPrompt> |
| `ai_endpoints` | JSON Map<String, List<Endpoint>> (keyed by provider id) |
| `provider_states` | JSON Map<String, String> ("ok"/"error"/"inactive"/"not-used") |
| `ai_model_type_overrides` | JSON List<ModelTypeOverride> |

> **Note:** the older `ai_prompts` (Internal Prompts) key is no
> longer written. The intro / model-info / translate templates have
> moved to dedicated `*_prompt` keys above.

#### Caches and bookkeeping
| Key | Type | Notes |
|---|---|---|
| `model_list_timestamp_<providerId>` | Long | last successful `/models` fetch — drives 24h cache validity |
| `caps_precomputed_version` | Int | bootstrap-migration version flag for the precompute pass |
| `ai_report_agents_v2` | StringSet | last-used agent selection for the Reports flow |
| `ai_report_models_v2` | StringSet | last-used direct-model selection for the Reports flow |
| `secondary_last_<kind>_<reportId>` | StringSet | per-(report, kind) last-selected models for the secondary pickers |
| `last_ai_report_title` | String | most recent report title (used by external-intent flows) |
| `last_ai_report_prompt` | String | most recent report prompt |

### `provider_registry`
Custom provider definitions added by the user (or imported via
`setup.json`). Keyed by provider id; serialized as `ProviderDefinition`
JSON. Read by `ProviderRegistry` at startup; merges with the bundled
`assets/setup.json` definitions.

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
network call until the TTL expires.

## Files (under `<filesDir>`)

The app's `filesDir` is `/data/data/com.ai/files/`. The tree is
captured by the backup zip with two top-level exceptions —
`local_llms/` and `local_models/`, which hold user-supplied
multi-GB on-device model bundles. See
[backup-restore.md](backup-restore.md) for the exclude-and-preserve
contract.

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
`ReportStorage`'s `ReentrantLock`.

### `secondary/<reportId>/<resultId>.json`
One file per `SecondaryResult` row — RERANK, META (every chat-type
Meta prompt), MODERATION, or TRANSLATE. META rows carry the
user-given `metaPromptName` (and `metaPromptId`) so the UI /
exports group them under the Meta-prompt name regardless of how
many or which prompts the user has configured. Subdirectory per
parent report so deleting a report cascades cleanly. Translate
rows additionally carry `translateSourceTargetId/Kind`,
`targetLanguage/Native`, and a shared `translationRunId` so the
result viewer can group rows from one batch.

### `trace/<hostname>_<timestamp>_<seq>.json`
One file per outbound API call (when `ApiTracer.isTracingEnabled` is
true — debug builds always have it on). Each holds the full request
(URL, method, headers, body) and the response (status, headers,
body — except streaming responses, which note that they were not
captured to avoid breaking the response stream). Tagged with the
`reportId` and `model` of the originating call where applicable.

On-device LLM (`LocalLlm.generate`) and on-device embedder
(`LocalEmbedder.embed`) calls write traces too, with
`hostname = "local"` and url like `local://generate/<modelFile>` or
`local://embed/<modelFile>`.

### `chat-history.json`
Single JSON file with every persisted chat session. Managed by
`ChatHistoryManager`. Sessions are auto-saved as messages arrive.
Holds `pinned` and `knowledgeBaseIds` per session.

### `prompt-history.json`
Up to 100 most-recently-used report prompts. Managed by
`SettingsPreferences.savePromptHistory`. The Hub's "Prompt history"
card reads it.

### `usage-stats.json`
List of `UsageStats` entries — one per `(provider, model, kind)`
triple. Updated in-memory by every successful API call; disk-flushed
on a 2-second debounce. Read by the AI Usage screen.

### `prompt-cache.json`
Cached `PromptCache` entries — per-prompt cached responses with TTL.
Used by `PromptCache` to short-circuit repeat lookups (e.g. the
Model Info "model info" prompt).

### `knowledge/<kbId>/`
Knowledge base data. Per-KB layout:

```
knowledge/<kbId>/
  manifest.json     — KnowledgeBase + KnowledgeSource[] list
  chunks/
    <sourceId>.json — JSON array of KnowledgeChunk for that source
```

One JSON file per source keeps add / remove / re-index cheap (no
full-KB rewrite for a single-source change), while loading a whole
KB for retrieval still scans only one directory. See
[knowledge.md](knowledge.md).

### `embeddings/<sha256>.json`
Per-document embedding cache, keyed by SHA-256 of `(providerId, model,
docId, contentHash)`. Doubles instead of Floats — half the size
benefit isn't worth the precision loss for cosine over short
documents. Used by the layered Local / Remote semantic search
screens to short-circuit re-embedding.

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

### `datastore/*` (Jetpack DataStore proto files)
Used for atomic flags like `setup_imported`. See
`com.ai.data.AppDataStore`.

## What's NOT persisted

- Raw streaming SSE bodies (response body in trace files reads
  `[streaming response - not captured]`).
- WebView Chromium cookies and process state — intentionally
  excluded from the backup zip; doesn't make sense to restore on a
  different device.
- The `assets/setup.json` provider catalog — this ships in the APK
  and is consumed once at first run; the merged result lives in
  `provider_registry` prefs from then on. Restore re-reads the
  asset and grafts in any provider id missing from the restored
  prefs (handles "old backup, new app version, new provider").

## What's NOT in the backup zip

- `local_llms/<name>.task` and `local_models/<name>.tflite` —
  user-supplied multi-GB on-device model bundles. Listed in
  `BackupManager.FILES_DIR_BACKUP_EXCLUDES`; skipped on backup,
  preserved through the restore wipe so a settings/data restore
  doesn't destroy them.
- Anything under `cacheDir` (exports, shared traces, the restore
  temp file) — regeneratable and non-portable.

See [backup-restore.md](backup-restore.md) for the full backup
format and restore semantics.

## Cleanup paths

`SettingsPreferences` exposes:
- `clearPromptHistory()` — empties `prompt-history.json`
- `clearLastReportPrompt()` — clears the two `last_ai_report_*` keys
- `clearUsageStats()` — empties `usage-stats.json` and the in-memory cache
- `clearTraces()` — `ApiTracer.clearTraces()` deletes every file under `trace/`

`Housekeeping → Full reset` runs all of the above plus deletes every
chat session, every report (and cascaded secondary results), every
knowledge base, every cached embedding, and every trace older than
the cutoff timestamp. (It does not delete on-device `.task` /
`.tflite` files — those persist across full resets and have their
own per-row Remove on the matching Local LLMs / Local Models
cards.)
