# Persistent Storage

Everything the app keeps on disk, where it lives, and what's in each
slot. All of this rounds-trip through `BackupManager` (Settings →
Housekeeping → Backup) into a single `.zip`.

## SharedPreferences (5 files)

All under `/data/data/com.ai/shared_prefs/<name>.xml`.

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
| `rerank_prompt` | String | Rerank template (blank → built-in default) |
| `summarize_prompt` | String | Summarize template (blank → built-in default) |
| `compare_prompt` | String | Compare template (blank → built-in default) |

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
| `ai_prompts` | JSON List<Prompt> |
| `ai_endpoints` | JSON Map<String, List<Endpoint>> (keyed by provider id) |
| `provider_states` | JSON Map<String, String> ("ok"/"error"/"inactive"/"not-used") |
| `ai_model_type_overrides` | JSON List<ModelTypeOverride> |

#### Caches and bookkeeping
| Key | Type | Notes |
|---|---|---|
| `model_list_timestamp_<providerId>` | Long | last successful `/models` fetch — drives 24h cache validity |
| `caps_precomputed_version` | Int | bootstrap-migration version flag for the precompute pass |
| `ai_report_agents_v2` | StringSet | last-used agent selection for the Reports flow |
| `ai_report_models_v2` | StringSet | last-used direct-model selection for the Reports flow |
| `secondary_last_<kind>_<reportId>` | StringSet | per-(report, kind) last-selected models for Rerank/Summarize/Compare pickers |
| `last_ai_report_title` | String | most recent report title (used by external-intent flows) |
| `last_ai_report_prompt` | String | most recent report prompt |

### `provider_registry`
Custom provider definitions added by the user (or imported via
`setup.json`). Keyed by provider id; serialized as `ProviderDefinition`
JSON. Read by `ProviderRegistry` at startup; merges with the bundled
`assets/setup.json` definitions.

### `pricing_cache`
Six tiers of pricing data plus manual overrides.

| Key | Type | Notes |
|---|---|---|
| `litellm_pricing` | JSON Map<String, ModelPricing> | LiteLLM tier |
| `litellm_meta` | JSON Map<String, …> | capabilities + context length harvested from the same payload |
| `litellm_timestamp` | Long | last fetch ms |
| `openrouter_pricing` | JSON Map<String, ModelPricing> | OpenRouter tier |
| `openrouter_timestamp` | Long | |
| `modelsdev_pricing` | JSON Map<String, ModelPricing> | models.dev tier |
| `modelsdev_meta` | JSON Map<String, …> | tool/vision flags from models.dev |
| `modelsdev_timestamp` | Long | |
| `helicone_pricing` | JSON List<HeliconeRule> | Helicone tier (kept as rules to honour startsWith/includes operators) |
| `helicone_timestamp` | Long | |
| `llmprices_pricing` | JSON Map<String, ModelPricing> | llm-prices tier |
| `llmprices_timestamp` | Long | |
| `aa_pricing_v2` | JSON Map<String, ModelPricing> | Artificial Analysis tier |
| `aa_meta_v2` | JSON Map<String, …> | intelligence + speed scores |
| `aa_timestamp_v2` | Long | |
| `manual_pricing` | JSON Map<String, ModelPricing> | per-(provider, model) user overrides |

The `_v2` keys on the AA tier exist to invalidate older UUID-keyed
entries from a previous parser revision.

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

The app's `filesDir` is `/data/data/com.ai/files/`.

### `reports/<reportId>.json`
One file per generated report. Holds the prompt, every agent's
request/response/headers/usage/citations/cost, status, durations, etc.
Written atomically; protected by `ReportStorage`'s `ReentrantLock`.

### `secondary/<reportId>/<resultId>.json`
One file per Rerank / Summarize / Compare result. Subdirectory per
parent report so deleting a report cascades cleanly. Holds the
chosen kind, provider+model, content, error, token usage, costs,
duration.

### `trace/<hostname>_<timestamp>_<seq>.json`
One file per outbound API call (when `ApiTracer.isTracingEnabled` is
true — debug builds always have it on). Each holds the full request
(URL, method, headers, body) and the response (status, headers,
body — except streaming responses, which note that they were not
captured to avoid breaking the response stream). Tagged with the
`reportId` and `model` of the originating call where applicable.

### `chat-history.json`
Single JSON file with every persisted chat session. Managed by
`ChatHistoryManager`. Sessions are auto-saved as messages arrive.

### `prompt-history.json`
Up to 100 most-recently-used report prompts. Managed by
`SettingsPreferences.savePromptHistory`. The Hub's "Prompt history"
card reads it.

### `usage-stats.json`
List of `UsageStats` entries — one per `(provider, model, kind)`
triple. Updated in-memory by every successful API call;
disk-flushed on a 2-second debounce. Read by the AI Usage screen.

### `prompt-cache.json`
Cached `PromptCache` entries — per-prompt cached responses with TTL.
Used by `PromptCache` to short-circuit repeat lookups (e.g. the
Model Info "model info" prompt).

### `datastore/*` (Jetpack DataStore proto files)
Used for atomic flags like `setup_imported`. See
`com.ai.data.AppDataStore`.

## What's NOT persisted

- Raw streaming SSE bodies (response body in trace files reads
  `[streaming response - not captured]`).
- WebView Chromium cookies and process state — intentionally excluded
  from the backup zip; doesn't make sense to restore on a different
  device.
- The bundled `assets/model_prices_and_context_window.json` — this
  ships in the APK as a fallback for when LiteLLM hasn't been fetched
  yet. It's not "user data" so the backup zip skips it.

## Cleanup paths

`SettingsPreferences` exposes:
- `clearPromptHistory()` — empties `prompt-history.json`
- `clearLastReportPrompt()` — clears the two `last_ai_report_*` keys
- `clearUsageStats()` — empties `usage-stats.json` and the in-memory cache
- `clearTraces()` — `ApiTracer.clearTraces()` deletes every file under `trace/`

`Housekeeping → Start clean` runs all of the above plus deletes every
chat session, every report (and cascaded secondary results), and every
trace older than the cutoff timestamp.
