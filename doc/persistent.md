# Persistent Storage

Everything the app keeps on disk, where it lives, and what's in each
slot. There is **no Jetpack DataStore at runtime** — the
`androidx.datastore.preferences` dependency is declared but unused.
Persistence is exclusively **SharedPreferences + JSON / text files**
under `<filesDir>` (a few under `<cacheDir>`), written through an
atomic `writeTextAtomic` helper. The backup-eligible slots
round-trip through `BackupManager` (Settings → Housekeeping → Backup
& Restore) into a single `.zip` — see [backup-restore.md](backup-restore.md).

## SharedPreferences (10 files)

All under `/data/data/com.ai/shared_prefs/<name>.xml`. **Seven** of
the ten are captured in `BackupManager.PREFS_TO_BACKUP`:

| Prefs file | Owner | In backup? |
|---|---|---|
| `eval_prefs` | `SettingsPreferences` (main settings) | ✅ |
| `provider_registry` | `ProviderRegistry` | ✅ |
| `pricing_cache` | `PricingCache` | ✅ |
| `dual_chat_prefs` | `DualChatScreen` | ✅ |
| `huggingface_cache` | `HuggingFaceCache` | ✅ |
| `model_cooldowns` | `ModelCooldownStore` | ✅ |
| `view_screen_prefs` | View-grid tile order (`tile_order`) | ✅ |
| `provider_field_timestamps` | `ProviderFieldTimestamps` (recomputable) | ❌ |
| `last_report_tracker` | `LastReportTracker` (device-local pointer) | ❌ |
| `update_from_cloud` | `UpdateFromCloudScreen` (local APK pointer) | ❌ |

The three excluded files are either recomputable caches or
device-local pointers that don't make sense to graft onto another
device. `WebViewChromiumPrefs` is also intentionally excluded. (The
old `translation_modes` prefs file is gone — per-report translation
modes are no longer persisted in their own prefs file.)

### `eval_prefs` — main settings
By far the largest. Loaded by `SettingsPreferences`, which defines
70 `KEY_*` constants. (Note: where a value below shows a default, it
is the `prefs.getX(key, default)` *read-fallback* used when the key
is absent on disk — for the throttle / concurrency keys this read
fallback can differ from the `GeneralSettings` data-class field
default; the value applied to a missing key is the one shown here.)

#### General settings
| Key | Type | Notes |
|---|---|---|
| `user_name` | String | display name shown in UI (default `"user"`) |
| `huggingface_api_key` | String | for HF Model Info lookups |
| `openrouter_api_key` | String | for the OpenRouter pricing tier |
| `artificial_analysis_api_key` | String | for the AA pricing/scores tier |
| `default_email` | String | default email for the report email export |
| `default_type_paths` | JSON Map<String,String> | global per-type API path defaults |
| `logging_master_enabled` | Boolean (default true) | grand-master gate for the whole Log/trace/audit/statistics page. When false, tracing / audit log / usage stats / file logger are forced off at runtime regardless of their stored values (the per-item flags below are preserved so re-enabling restores prior choices) |
| `tracing_enabled` | Boolean (default true) | master switch for `ApiTracer.isTracingEnabled`; gated by `logging_master_enabled` |
| `audit_log_enabled` | Boolean (default false) | per-report audit log (`AuditLog`, `audit/<reportId>.log`); gated by `logging_master_enabled` |
| `usage_stats_enabled` | Boolean (default true) | cumulative usage-stat recording (per-provider / per-model token counts + costs); gated by `logging_master_enabled` |
| `full_screen` | Boolean (default true) | hides the Android status bar so the app gets the full screen height; turn off to re-show the system bar |
| `model_name_layout` | String | `ModelNameLayout` name (`MODEL_ONLY` / `PROVIDER_AND_MODEL`); default `MODEL_ONLY` |
| `app_home` | String | `AppHomeMode` name (`HOME_SCREEN` / `HOME_BAR`); default `HOME_BAR` |
| `ui_color_mode` | String | `UiColorMode` name (`DAY` / `NIGHT` / `AUTO`); which colour set the app paints, default `NIGHT` |
| `ui_color_overrides` | JSON Map<String,Int> | functional `AppColors` role overrides (the Night set) edited in Settings → UI Colors |
| `ui_color_overrides_day` | JSON Map<String,Int> | the Day-variant colour overrides |
| `ui_card_background_argb` | Int | legacy mirror for the card colour override (`CardBackgroundAlt`) |
| `ui_button_background_argb` | Int | legacy mirror for the button colour override (`ButtonBackground`) |
| `metadata_enabled` | Boolean (default true) | grand-master switch for optional metadata generation |
| `icon_gen_enabled` | Boolean (default true) | master switch for per-report icon-gen (background `workers/report-icon` call on every new report — see [report-icons.md](report-icons.md)) |
| `report_language_gen_enabled` | Boolean (default true) | gates automatic report language + flag generation |
| `report_title_mode` | String | `ReportTitleMode` name (`Manual` / `AI`) |
| `per_model_icon_gen_enabled` | Boolean (default true) | master switch for per-model icons (derives each model's icon from its title via the worker engine `workers/model-icons`) |
| `per_model_title_gen_enabled` | Boolean (default true) | gates automatic per-model response titles |
| `use_internal_prompts_icons` | Boolean (default true) | gates generated/cached icons for internal-prompt rows |
| `autostart_items_enabled` | Boolean (default false) | auto-starts the configured default secondary/meta items after a report finishes |
| `autostart_fan_meta` | Boolean (default true) | starts Fan Meta automatically after a clean Fan-out run |
| `auto_create_rerank_moderation` | Boolean (default true) | creates default Rerank + Moderation rows after primary report generation when possible |
| `metadata_icons` | JSON `MetadataIcons` | user-editable Default icons (serialised onto `GeneralSettings`) |
| `app_wide_system_prompt_id` | String? | app-wide fallback system prompt id |
| `app_wide_parameters_ids` | JSON List<String> | app-wide fallback parameter preset ids |
| `report_model_system_prompt_id` | String? | fallback system prompt for direct report models |
| `report_model_parameters_ids` | JSON List<String> | fallback parameters for direct report models |
| `show_knowledge_card` | Boolean (default false) | shows the AI Knowledge card on the Hub (only meaningful when `experimental_features` is on) |
| `experimental_features` | Boolean (default false) | master gate for on-device models, AI Knowledge / RAG, and Local Semantic Search — see [experimental.md](experimental.md) |
| `pinned_dashboard_cards` | JSON List<String> | Live Dashboard card ids pinned open on page load |
| `dashboard_card_order` | JSON List<String> | custom Live Dashboard card order |
| `recent_report_models` | String (newline-separated) | last **3** `(provider, model)` picks from the Report section's model pickers, most-recent first, encoded as `"providerId\|model"` |
| `streaming_read_timeout_sec` | Int | read timeout for streaming SSE calls. Read-fallback `BuildConfig.NETWORK_READ_TIMEOUT_SEC` (240) |
| `nonstreaming_read_timeout_sec` | Int | read timeout for non-streaming calls. Read-fallback `BuildConfig.NETWORK_NONSTREAMING_READ_TIMEOUT_SEC` (120) |
| `batch_item_timeout_sec` | Int | wall-clock ceiling for ONE batch item (fan-out pair, translation item, tournament match, judge / compare / transrank cell), worker fallbacks + retries included. Read-fallback `BuildConfig.BATCH_ITEM_TIMEOUT_SEC` (180) |
| `max_calls_per_provider_per_minute` | Int | per-host sliding-window rate cap mirrored to `NetworkSettings.maxCallsPerProviderPerMinute` (read-fallback 30; `GeneralSettings` field default 60). See [throttle.md](throttle.md) |
| `max_concurrent_calls_per_provider` | Int | per-host concurrency cap (read-fallback 3; field default 5) |
| `max_concurrent_api_calls` | Int | global flow-level cap, `ApiCallCaps.global` (read-fallback 50; field default 100). The per-kind caps (report / translation / fan-out / fan-meta / workers) are **not** separately persisted — they derive from this global at runtime |
| `max_retries_on_429` | Int (default 3) | in-line 429 retries; 0 disables |
| `retry_backoff_ms_429` | Long (default 1000) | base back-off between 429 retry attempts (ms) |
| `max_retries_on_529` | Int (default 3) | in-line 529 (server overloaded) retries; 0 disables |
| `retry_backoff_ms_529` | Long (default 1000) | base back-off between 529 retry attempts (ms) |
| `type_a_bench_enabled` | Boolean (default true) | Type-A (fixed-model) batch bench-and-requeue on 429/529 for Fan Out + Judge the judges; mirrored to `ModelCooldownStore.typeABenchEnabled` |
| `type_a_bench_seconds` | Int (default 10) | bench duration (seconds) when a 429/529 carries no Retry-After hint; mirrored to `ModelCooldownStore.typeABenchBaseMs` (× 1000) |
| `type_a_bench_max_attempts` | Int (default 5) | consecutive benches one item gets before the batch leaves it errored; mirrored to `ModelCooldownStore.typeABenchMaxAttempts` |
| `log_level` | String (default `WARN`) | threshold for `com.ai.data.AppLog`. One of `TRACE` / `DEBUG` / `INFO` / `WARN` / `ERROR` / `OFF`. Read directly from `eval_prefs` so DEBUG calls inside bootstrap are admitted on cold start. Forced to `OFF` at runtime when `logging_master_enabled` is false |

> The 429/529 retry defaults are **3 retries with a 1000 ms base
> back-off** (exponential with ±50 % jitter, capped at 30 s), each
> per-provider-overridable. The retry budgets for 429 and 529 are
> independent — see [throttle.md](throttle.md).

> **Two `GeneralSettings` fields with dedicated keys (both backed up via
> `eval_prefs`):**
> - `rankingWeights` (the "Ranking weights" 0–10 sliders map, Settings →
>   Ranking weights) — stored sparsely as JSON under `ranking_weights`
>   (the key is omitted when the map is empty; load falls back to
>   `RANKING_WEIGHT_DEFAULTS`).
> - `showLadybugIcons` (the 🐞 trace hot-link toggle) — stored under
>   `show_ladybug_icons` (default `true`) and mirrored to
>   `ApiTracer.showLadybugIcons` at runtime.

> The chat-title / model-info / model-intro / translate-text /
> second-rerank / second-moderation / test-model prompt templates
> that used to live as dedicated `*_prompt` keys now live as
> `InternalPrompt` rows under `ai_meta_prompts` (category
> `"internal"`) — see below.

#### Per-provider config
For every provider id (`<key> = service.id`, e.g. `OpenAI`). The
default model and its source (`API` / `MANUAL`) are **no longer**
per-provider prefs keys — they come from the bundled provider
definition (`AppService.defaultModel` / `defaultModelSource`), so
`<key>_model` and `<key>_model_source` are gone.

| Key | Type | Notes |
|---|---|---|
| `<key>_api_key` | String | provider API key |
| `<key>_manual_models` | JSON List<String> | persisted model list |
| `<key>_model_types` | JSON Map<String,String> | id → "chat"/"embedding"/"rerank"/... |
| `<key>_vision_models` | JSON List<String> | user-flagged vision-capable ids |
| `<key>_web_search_models` | JSON List<String> | user-flagged web-search-capable ids |
| `<key>_reasoning_models` | JSON List<String> | user-flagged reasoning-capable ids |
| `<key>_vision_capable_computed` | JSON List<String> | precomputed layered-lookup result |
| `<key>_web_search_capable_computed` | JSON List<String> | precomputed layered-lookup result |
| `<key>_reasoning_capable_computed` | JSON List<String> | precomputed layered-lookup result |
| `<key>_model_pricing` | JSON Map<String, ModelPricing> | precomputed prices |
| `<key>_model_capabilities` | JSON Map<String, ModelCapabilities> | provider self-report |
| `<key>_models_response_raw` | String | raw last `/models` response |
| `<key>_parameters_id` | JSON List<String> | default param presets for this provider |
| `<key>_system_prompt_id` | String? | default system prompt for this provider |

> The legacy per-provider `_admin_url` and `_model_list_url`
> override keys have been dropped — admin URLs come from the bundled
> provider definition only.

#### Top-level lists
| Key | Type | Notes |
|---|---|---|
| `ai_agents` | JSON List<Agent> | |
| `ai_flocks` | JSON List<Flock> | includes the reserved `"default agents"` flock |
| `ai_swarms` | JSON List<Swarm> | flat groups of `(provider, model)` pairs |
| `ai_parameters` | JSON List<Parameters> | |
| `ai_system_prompts` | JSON List<SystemPrompt> | |
| `ai_meta_prompts` | JSON List<InternalPrompt> | despite the legacy `meta` name in the key, this holds **every** Internal Prompt — Meta / Fan-out / Fan-in / workers / alt / internal categories — so seeded entries survive the rename to InternalPrompt |
| `ai_example_prompts` | JSON List<ExamplePrompt> | starter library for the New Report flow |
| `ai_endpoints` | JSON Map<String, List<Endpoint>> | keyed by provider id |
| `provider_states` | JSON Map<String, String> | `"ok"` / `"error"` / `"inactive"` / `"not-used"` |
| `ai_model_type_overrides` | JSON List<ModelTypeOverride> | per-model type assignment that wins over autodetection — see [model-states.md](model-states.md) |
| `ai_blocked_models` | JSON List<BlockedModel> | `(providerId, model, reason)`; dimmed `🚫` in every picker — see [model-states.md](model-states.md) |
| `ai_test_excluded_models` | JSON List<String> | skipped by "Test all models"; auto-added when a probe would cost > 5 ¢; seeded from `assets/excluded.json` (sweep-only, no picker effect) |
| `ai_inaccessible_models` | JSON List<String> | not reachable on this account; dimmed `🔒` in pickers; seeded from `assets/inaccessible.json` |
| `ai_default_meta_items` | JSON List<DefaultMetaItem> | configurable default secondary/meta items |

#### Caches and bookkeeping
| Key | Type | Notes |
|---|---|---|
| `model_list_timestamp_<providerId>` | Long | last successful `/models` fetch — drives 24 h cache validity (a key *prefix*, one per provider) |
| `first_run_bootstrapped` | Boolean | gates the one-time first-run providers + prompts seed (the every-start delta-merge still runs on subsequent starts — see [architecture.md](architecture.md)) |
| `ai_report_agents_v2` | StringSet | last-used agent selection for the Reports flow |
| `ai_report_models_v2` | StringSet | last-used direct-model selection for the Reports flow |
| `last_ai_report_title` | String | most recent report title (used by external-intent flows) |
| `last_ai_report_prompt` | String | most recent report prompt |
| `recent_target_languages` | String (newline-separated) | last **3** translation target languages (`RecentTargetLanguages`), each `"name\|native"`, most-recent first |
| `last_test_provider` / `last_test_api_url` / `last_test_model` / `last_test_prompt` / `last_test_system_prompt` / `last_test_temperature` / `last_test_max_tokens` / `last_test_raw_json` | String | sticky form state for the Developer → **Test API** screen (also pre-filled by "open in Test API" from a trace). The API key is **never** stored (`last_test_api_key` is explicitly removed). These live in `eval_prefs`, so they ride along in backups |

### `provider_registry`
The full provider registry, serialised by `ProviderRegistry`. Note
the registry starts **empty** on a fresh install; the 43 bundled
providers are loaded on demand from `assets/providers.json` via
`importFromAsset` and persisted here. Keys: `providers_json` (a JSON
array of `ProviderDefinition`) and `initialized` (Boolean). On
restore, the registry rebuilds straight from this file on the next
launch. See [providers.md](providers.md).

### `provider_field_timestamps`
Per-provider, per-field "user-touched-at" timestamps that the
every-start `assets/providers.json` sync consults to decide which
fields to refresh.

| Key | Type | Notes |
|---|---|---|
| `ts` | JSON Map<String, Map<String, Long>> | `{ "OpenAI": { "baseUrl": 1715…, "modelFilter": 1716… }, … }`. Set by `ProviderRegistry.update` whenever the new value differs; asset-driven paths don't bump |

Field names match `AppService` property names. A null lookup means
"never user-touched, refresh on next start"; a non-null timestamp
means "user edited this field, the asset sync should leave it
alone". Not backed up (recomputable). See [throttle.md](throttle.md)
and [architecture.md](architecture.md).

### `pricing_cache`
Bookkeeping (timestamps) plus the small manual-override map. The
large tier blobs were moved to `<filesDir>/pricing/` (see below) —
SharedPreferences loads its entire map into memory at process start
and keeps it there for the process lifetime, so a multi-MB JSON
string in a prefs file pays that cost forever even when only
consulted on demand.

| Key | Type | Notes |
|---|---|---|
| `litellm_timestamp` | Long | last LiteLLM fetch ms |
| `openrouter_timestamp` | Long | last OpenRouter fetch ms |
| `together_timestamp` | Long | last Together native fetch ms |
| `models_dev_timestamp` | Long | last models.dev fetch ms |
| `helicone_timestamp` | Long | last Helicone fetch ms |
| `llmprices_timestamp` | Long | last llm-prices.com fetch ms |
| `aa_timestamp_v2` | Long | last Artificial Analysis fetch ms |
| `manual_pricing` | JSON Map<String, ModelPricing> | per-`<providerId>:<model>` user overrides (source `"OVERRIDE"`) |

The `_v2` suffix on the AA timestamp exists to invalidate older
UUID-keyed entries from a previous parser revision. Manual overrides
sit ahead of all curated bulk tiers in `PricingCache.getPricing`
precedence — see [costs.md](costs.md).

### `dual_chat_prefs`
Last-used Dual Chat configuration, stored as flat per-field keys (not
a single JSON blob).

| Key | Type |
|---|---|
| `model1_provider`, `model2_provider` | String (provider id) |
| `model1_name`, `model2_name` | String (model) |
| `model1_params_ids`, `model2_params_ids` | JSON List<String> |
| `model1_system_prompt_id`, `model2_system_prompt_id` | String? |
| `subject` | String |
| `interaction_count` | String |
| `first_prompt`, `second_prompt` | String (templates) |

### `huggingface_cache`
Cached HuggingFace model-info lookups (positive + negative). 7-day
TTL keyed on `<providerId>::<modelId>`.

| Key | Type |
|---|---|
| `entries_json` | JSON Map<String, Entry(ts, info?)> |

`info = null` is meaningful — a cached miss that short-circuits the
network call until the TTL expires. Concurrent load-modify-save is
serialised so two simultaneous misses don't tear the JSON blob.

### `model_cooldowns`
Owned by `ModelCooldownStore` (not part of the main settings). Holds
models auto-benched after a 429 with a long retry-after. See
[model-states.md](model-states.md).

| Key | Type | Notes |
|---|---|---|
| `map` | JSON Map<String, Long> | `"providerId:model"` → epoch-ms the model becomes available again |
| `traces` | JSON Map<String, String> | `"providerId:model"` → trace filename of the 429 that benched it |

### `view_screen_prefs`
The View-grid tile order. A single `tile_order` key holds a
comma-separated list of tile ids (the user explicitly arranges the
grid — e.g. "Costs first"); a new tile id such as `doc:Matrix` (the
Answer matrix tile) is appended to the saved order. No other key is
written. Backed up so layout preferences survive restore.

### Not backed up: `last_report_tracker`, `update_from_cloud`
Device-local pointers — the last viewed report + view mode
(`last_report_tracker`) and the synced-APK update pointer
(`update_from_cloud`, single key `apk_uri`). Excluded from the backup
zip. (`provider_field_timestamps` is the third non-backed-up file —
recomputable; documented above.)

## Files (under `<filesDir>`)

`filesDir` is `/data/data/com.ai/files/`. The tree is captured by the
backup zip except the top-level `FILES_DIR_BACKUP_EXCLUDES` subdirs
(`local_llms/`, `local_models/`, `native/`, `applog/`). See
[backup-restore.md](backup-restore.md) for the contract.

Almost every JSON write goes through `writeTextAtomic` — a
`Files.move(ATOMIC_MOVE)` of an fsync'd temp file, with parent-dir
auto-mkdir. Most writes are also taken under a per-storage-object
`ReentrantLock`.

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

Reads go through `PricingCache.loadBlob`, which looks up the on-disk
`filesDir/pricing/<key>.json` first and falls back to the bundled
`assets/info-providers/<key>.json` snapshot when the file doesn't
exist (so a fresh install ships with working pricing / capability
tiers before the first Refresh). The bundled fallback is **not**
written through to disk — timestamps stay unset, and the next Refresh
overwrites the in-memory state and persists to `filesDir`.

### `reports/<reportId>.json`
One file per generated report (`ReportStorage`, `REPORTS_DIR =
"reports"`). Holds the prompt, every agent's
request/response/headers/usage/citations/cost, status, durations,
plus `imageBase64/Mime` (vision), `webSearchTool` / `reasoningEffort`
(regen state), `sourceReportId` (translated copies), `pinned`, the
per-report `apiCallCosts` ledger (`API_CALL_COST_LEDGER_VERSION = 3`),
and `costsFromDeletedItems`. Written atomically; protected by
`ReportStorage`'s `ReentrantLock`. Save failures log a warning
instead of being silently swallowed.

### `secondary/<reportId>/<resultId>.json`
One file per `SecondaryResult` row — RERANK, META (every chat-type
Meta / Fan-out / Fan-in prompt), MODERATION, TRANSLATE, TOURNAMENT,
JUDGES, COMPARE, or TRANSRANK. (`SecondaryKind` has exactly these 8
values.) TRANSRANK is the "Rank the translators" run — a
tournament-style ranking over translation outputs; like TOURNAMENT it
carries `tournamentRole` (`MATCH` / aggregate via `TRANSRANK_ROLE_AGGREGATE`).
META rows carry the user-given `metaPromptName` (and `metaPromptId`)
so the UI / exports group them under the prompt name. The
`secondaryScope` field encodes the SecondaryScope used at run time so
a cascade re-runs at the same scope. Subdirectory per parent report
so deleting a report cascades cleanly.

Translate rows additionally carry `translateSourceTargetId/Kind`,
`targetLanguage/Native`, and a shared `translationRunId`. Fan-out
rows are identified by `fanOutSourceAgentId != null`; Fan-in rows by
`fanInOf != null` (both carry `kind = META`). Tournament and
Judge-the-judges rows carry match/aggregate metadata such as
`tournamentRole` (`"MATCH"` / `"AGGREGATE"`), `tournamentJudgeRunId`,
`matchResponseAId`, `matchResponseBId`, `matchOrientation`, and the
aggregate `tournamentMatrix`. Compare-with-meta rows carry
`compareRunId`, `compareAgentId`, and `compareToResultId`. See
[secondary-results.md](secondary-results.md) and
[tournament-judges-compare.md](tournament-judges-compare.md).

`SecondaryResultStorage` validates that the resolved file path stays
inside the per-report directory (defence against `..`-traversal in a
corrupt id), keeps a per-`reportId` parse cache keyed by
`(filename, mtime, length)` to catch in-place edits, and bumps
`SecondaryDataVersion` on every mutation so Compose observers reload.

### `regenerate/<reportId>.json`
One file per report holding its `RegenerateJob` — the persisted state
of a multi-phase "regenerate everything" batch (phase cursor, per-row
status, pause-on-error marker). The 10 phases run in fixed order:
TITLE, ICON, LANGUAGE, AGENTS, META, FAN_OUT, FAN_IN, TRANSLATIONS,
FAN_META, TOURNAMENT. Lets a partially-done batch resume after the
app is killed. See [regenerate.md](regenerate.md).

### `knowledge/<kbId>/...`
RAG knowledge bases (`KnowledgeStore`): per-KB `manifest.json`
(`KnowledgeBase` + sources), `chunks/<sourceId>.json` (each chunk
carries its `FloatArray` embedding), and `files/<unique>` (cached
copies of file sources). One chunk file per source so add/remove/
re-index don't rewrite the whole KB. Gated behind Experimental
features but persisted regardless. See [knowledge.md](knowledge.md).

### `chat-history/<sessionId>.json`
One JSON file per persisted chat session (`ChatHistoryManager`,
`HISTORY_DIR = "chat-history"`) — **not** a single file. Sessions are
auto-saved as messages arrive; each holds its `pinned` flag. Atomic
writes; delete + cache invalidation are taken under a single lock.
Session ids are path-safety checked before any file op.

### `applog/applog_<yyyyMMdd>.log`
Daily-rotating plain-text log files produced by `com.ai.data.AppLog`.
One line per call, format:

```
yyyy-MM-dd HH:mm:ss.SSS LEVEL TAG: message
```

The writer is held open across calls and flushed per line so a
process kill never loses the last few lines. Sensitive headers
(`Bearer …`, raw `sk-/xai-/gsk_/key-` keys, Google `?key=` params)
are redacted inline before write. An in-memory `AppLog.cachedFiles`
list mirrors the directory listing so the viewer's list screen is
O(1) once warm.

Reachable from Hub → AI App log. Threshold persisted in `eval_prefs`
as `log_level`. The `applog/` dir is in `FILES_DIR_BACKUP_EXCLUDES`,
so logs are device-local and don't round-trip through
backup/restore. See [applog.md](applog.md).

### `trace/<hostname>_<timestamp>_<seq>.json`
One file per outbound API call (`ApiTracer`, written by
`TracingInterceptor` when `ApiTracer.isTracingEnabled` is true — on by
default; toggleable in Settings). Each holds the full request (URL,
method, headers, body) and the response (status, headers, body).
Hostnames are sanitised (`[^A-Za-z0-9.-]` → `_`) before being used as
a filename component (no path-traversal injection from a malicious
URL).

Streaming responses **are** captured: a placeholder body
`[partial: stream in progress]` is written first, then a teeing
source accumulates the SSE bytes and overwrites the same file on EOF
or close — reads flow through to the application unchanged. Body
capture caps at 8 MiB (`BODY_CAP_BYTES`) so a runaway response can't
OOM the process. Auth headers, `?key=`/token query params, and JSON
body secret fields are redacted at write time — a leaked filesystem
dump never carries plain keys.

`ApiTracer` keeps an in-memory `cachedTraceFiles` list (a
metadata-only mirror, rebuilt via a streaming `JsonReader` parse),
prewarmed off the main thread, so the Trace list / detail prev-next
nav is O(1) once warm. Synthetic local-runtime calls
(`LocalLlm.generate`, `LocalEmbedder.embed`, runtime/model downloads)
also write traces here with hostname `local`.

### `audit/<reportId>.log`
Per-report API-call audit entries (`AuditLog`), appended only while a
report is the active tracing context — token usage, status, and error
per call for the report-scope dispatch.

### `crash/last-crash.txt`
The last uncaught-exception trace, written by `CrashReporter.init`
(called first thing in `MainActivity`). Surfaced in Housekeeping.

### `test_run.json`
The single most-recent "Test all models" run (Housekeeping → Test →
Test all models). One JSON document — `ModelTestRunState` with a
per-`(provider, model)` `ModelTestState` map (`ModelTestRunStore`,
`FILE = "test_run.json"`). `ModelTestEngine` flushes it on each item
completion (crash-safe partial results) and once on run end. A fresh
run overwrites it; Housekeeping → Reset → Clear runtime data drops
it. Not in `FILES_DIR_BACKUP_EXCLUDES`, so it round-trips through
backup/restore.

### `prompt-history.json`
Up to 100 most-recently-used report prompts
(`SettingsPreferences.savePromptHistory`,
`FILE_PROMPT_HISTORY`). The Hub's "Prompt history" card reads it.

### `usage-stats.json`, `usage-category-stats.json`, `usage-report-stats.json`
The three cost/usage stat stores (`SettingsPreferences`):

- `usage-stats.json` — list of `UsageStats` entries, one per
  `(provider, model, kind)` triple.
- `usage-category-stats.json` — totals grouped by category.
- `usage-report-stats.json` — totals grouped by `reportId`.

All three are updated in-memory by every successful API call (the
single `updateUsageStats` chokepoint) and disk-flushed on a 2-second
debounce. `onCleared` forces a flush off the main thread on
`NonCancellable` so a Refresh-all auto-restart can't drop in-flight
stats. Read by the AI Usage screen. See [costs.md](costs.md).

### `prompt_cache/`
Cached `PromptCache` entries — per-prompt cached responses used to
short-circuit repeat internal-prompt lookups (e.g. the Model Info
"model info" prompt). Each entry is a `<key>.json` file holding
`{ "timestamp": Long, "response": String }`, where `<key>` is a
length-prefixed SHA-256 hash of `(prompt, agentId)` — the length
prefix stops a `|` separator collision from conflating two distinct
keys. The TTL is **48 h**: `get()` prunes a stale entry on read
(`getRaw()` is the non-destructive variant for callers wanting a
custom window). The Housekeeping → **Cached prompts** screen lists
every entry (age / size / STALE marker) via `PromptCache.list()` and
deletes one (`delete()`) or all (`clearAll()`).

### `model_lists/<providerId>.json`
Most recent `/models` raw JSON per provider (`ModelListCache`). Used
by the Model Info screen. Atomic writes; provider id is sanitised
before use as a filename.

### `embeddings/<sha256>.json`
Per-document embedding cache (`EmbeddingsStore`), keyed by SHA-256 of
`<providerId>::<model>::<docId>::<SHA-256(content)>`, storing
`List<Double>` (deliberately Doubles for short-document precision).
Used by Local Semantic Search / report semantic flows — **separate**
from KB chunk storage, which lives under `knowledge/<kbId>/chunks/` as
`FloatArray`. Dim mismatches log a warning instead of silently
zeroing.

### `internal_prompt_icons.json`, `meta_cache.json`, `model_supported_parameters.json`
Top-level supplementary catalogs (atomic writes):
`internal_prompt_icons.json` is the generated/cached internal-prompt
icon map (`InternalPromptIconCache`); `meta_cache.json` is the
`MetaCache` map (cached meta titles + the language-flag icon, **7-day
TTL** — the "Meta (titles / lang-icon)" Caches category);
`model_supported_parameters.json` is the flattened supported-parameters
catalog written by `PricingCache`. (A sibling `model_pricing.json` is no
longer written — nothing read it — though the cache-clear path still
deletes one left behind on an older install.)

> **Caches screen** (Housekeeping → Caches) browses 7 categories,
> each backed by one of the on-disk slots above: Prompts
> (`prompt_cache/`, 48 h TTL), Internal-prompt icons
> (`internal_prompt_icons.json`), Meta (`meta_cache.json`, 7 d TTL),
> Model lists (`model_lists/<providerId>.json`), Pricing tiers
> (`pricing/`), Supported params (`model_supported_parameters.json`),
> and Embeddings (`embeddings/<sha256>.json`).

## What's NOT persisted

- WebView Chromium cookies and process state — intentionally excluded
  from the backup zip; doesn't make sense to restore on a different
  device.
- The on-device LLM/embedder model bundles and the MediaPipe native
  runtime — `local_llms/`, `local_models/`, `native/` are kept on
  disk but excluded from backup (device-ABI-tied / multi-GB).
- The `assets/providers.json` provider catalog — this ships in the
  APK and is loaded on demand at first run; the result lives in
  `provider_registry` prefs from then on. Restore re-reads the asset
  and grafts in any provider id missing from the restored prefs
  (handles "old backup, new app version, new provider").

## What's NOT in the backup zip

- The three non-backed-up prefs files above
  (`provider_field_timestamps`, `last_report_tracker`,
  `update_from_cloud`), plus `WebViewChromiumPrefs`.
- The four `FILES_DIR_BACKUP_EXCLUDES` subdirs
  (`local_llms/`, `local_models/`, `native/`, `applog/`).
- In-flight cacheDir temp files matching `CACHE_TOPLEVEL_SKIP_PREFIXES`
  (`ai-restore-`, `reset_keys_`, `ai-backup-`) — these would
  self-contain the in-flight backup, yank the file out from under the
  in-flight restore, or leak plaintext API keys.

`cacheDir` itself **is** mirrored into the backup zip (`cache/...`) so
exports / shared-trace handoffs / camera captures round-trip — only
the in-flight temp prefixes are skipped.

See [backup-restore.md](backup-restore.md) for the full backup format
and restore semantics (`MANIFEST_VERSION = 1`, validate-then-write
restore, zip-bomb caps, path-traversal defence).

## Cleanup paths

`SettingsPreferences` and siblings expose:
- `clearPromptHistory()` — empties `prompt-history.json`
- `clearLastReportPrompt()` — clears the two `last_ai_report_*` keys
- `clearUsageStats()` — empties the usage-stat files + in-memory caches
- `ApiTracer.clearTraces()` — deletes every file under `trace/`
- `AppLog.clearLogs()` — deletes every file under `applog/`
- `PromptCache.clearAll()` — deletes every `<key>.json` under `prompt_cache/`

**Housekeeping → Reset** offers five dedicated sub-screens:

- **Clear runtime data** — wipes logs, chats, traces, usage stats,
  AI reports, and prompt history. Narrower than the legacy single
  button: pricing / model-list caches stay put.
- **Clear Info providers** — wipes the six external pricing-tier
  caches (OpenRouter, LiteLLM, models.dev, Helicone, llm-prices,
  Artificial Analysis) and their per-tier timestamps in
  `pricing_cache`, plus the OpenRouter model-specs files
  (`model_pricing.json` / `model_supported_parameters.json`).
  Preserves manual + Together-native pricing. The `huggingface_cache`
  prefs file is *not* touched by this screen.
- **Clear all configuration** — wipes provider config, agents,
  prompts, parameters, and overrides. Asks before destructive actions.
- **assets/\*.json** — re-merges `providers.json` /
  `internal-prompts/` / `examples.json` / defaults from the APK. User
  edits on existing rows are preserved.
- **Reset application** — factory-style reset that preserves API keys
  (written to a temp file under `cacheDir/reset_keys_*`, restored
  after the wipe).

After any wholesale-state-replace op (including a full restore), the
in-memory singletons are stale; the **Restart-app dialog** kills and
relaunches the process so the next launch reads fresh from disk —
restore does not live-reload.
