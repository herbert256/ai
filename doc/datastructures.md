# Data Structures

All non-trivial data classes shipped by the app, grouped by domain.
Field types follow Kotlin notation; `?` marks nullable. Classes inside
`com.ai.data.ApiModels` (raw provider request/response shapes) are not
listed here — see the file directly. Cost / token / trace bookkeeping
fields on big classes (`Report`, `SecondaryResult`) are summarised
rather than exhaustively transcribed; read the source for the full set.

The codebase is ~153,000 LOC across 388 Kotlin files under
`ai/src/main/java/com/ai` (`data` 88, `ui` 273, `viewmodel` 24, `model`
2, plus `MainActivity.kt`). Persistence is SharedPreferences + JSON
files under `<filesDir>` — there is **no** Jetpack DataStore at runtime
(the dependency is declared but unused). See
**[persistent.md](persistent.md)** for every prefs key and file.

---

## Settings & Configuration (`com.ai.model`)

The two files under `com.ai.model` are `SettingsModels.kt` (all the
data classes below) and `SettingsHolder.kt` (the `object SettingsHolder`
that hands the live `Settings` to non-Composable call sites).

### `Settings`
The top-level AI configuration object. Persisted in `eval_prefs`.

| Field | Type | Notes |
|---|---|---|
| providers | `Map<AppService, ProviderConfig>` | one entry per configured provider |
| agents | `List<Agent>` | named model configurations |
| flocks | `List<Flock>` | groups of agents |
| swarms | `List<Swarm>` | groups of provider/model pairs |
| parameters | `List<Parameters>` | reusable parameter presets |
| systemPrompts | `List<SystemPrompt>` | reusable system prompts |
| internalPrompts | `List<InternalPrompt>` | user-managed Meta / Compare / Fan-out / Fan-in / worker / alt / internal templates |
| examplePrompts | `List<ExamplePrompt>` | starter (title, text) pairs surfaced in the New Report flow |
| endpoints | `Map<AppService, List<Endpoint>>` | per-provider endpoint URLs |
| providerStates | `Map<String, String>` | `"ok"` / `"error"` / `"inactive"` per provider id |
| modelTypeOverrides | `List<ModelTypeOverride>` | manual per-model type assignments |
| blockedModels | `List<BlockedModel>` | models the test sweep flagged as failing (🚫) |
| testExcludedModels | `List<TestExcludedModel>` | models skipped by the test sweep (costly probes etc.) |
| inaccessibleModels | `List<InaccessibleModel>` | models gated behind paid tier / approval (🔒) |
| defaultMetaItems | `List<DefaultMetaItem>` | Meta rows auto-created after primary generation |

The four model-state lists (`blockedModels`, `testExcludedModels`,
`inaccessibleModels`, plus the cooldown store) are documented in
**[model-states.md](model-states.md)**.

### `ProviderConfig`
Per-provider, user-curated configuration. The provider's `defaultModel`
/ `defaultModelSource` / `adminUrl` are NOT here — they live on the
`AppService` itself (loaded from `assets/providers.json`, edited through
`ProviderRegistry.update`).

| Field | Type | Notes |
|---|---|---|
| apiKey | `String` | empty until user pastes one |
| models | `List<String>` | model ids (from API list or hardcoded fallback) |
| modelTypes | `Map<String, String>` | id → type (`"chat"`, `"embedding"`, `"rerank"`, ...) |
| visionModels | `Set<String>` | user-flagged vision-capable ids |
| webSearchModels | `Set<String>` | user-flagged web-search-capable ids |
| reasoningModels | `Set<String>` | user-flagged reasoning-capable ids |
| modelCapabilities | `Map<String, ModelCapabilities>` | provider's own `/models` self-report |
| modelListRawJson | `String?` | raw `/models` response, kept for future re-parsing |
| visionCapableComputed | `Set<String>` | precomputed layered lookup result |
| webSearchCapableComputed | `Set<String>` | precomputed layered lookup result |
| reasoningCapableComputed | `Set<String>` | precomputed layered lookup result |
| modelPricing | `Map<String, PricingCache.ModelPricing>` | precomputed per-model price (avoids catalog scans on every render) |
| parametersIds | `List<String>` | provider-level default param presets (bare provider+model selection) |
| systemPromptId | `String?` | provider-level default system prompt |

> The legacy per-provider `model`, `modelSource`, `adminUrl`, and
> `modelListUrl` override fields are gone. `model` / `modelSource`
> moved to `AppService.defaultModel` / `defaultModelSource`; admin-URL
> overrides live in the bundled provider definition only.

### `Agent`
| Field | Type |
|---|---|
| id | `String` (UUID) |
| name | `String` |
| provider | `AppService` |
| model | `String` |
| apiKey | `String` (empty → inherit from provider) |
| endpointId | `String?` |
| paramsIds | `List<String>` |
| systemPromptId | `String?` |

### `Flock`
| id, name | `String` |
| agentIds | `List<String>` |
| paramsIds | `List<String>` |
| systemPromptId | `String?` |

The constant `DEFAULT_AGENTS_FLOCK_NAME = "default agents"` names
the auto-managed flock the per-provider Test button and
Refresh-all populate.

### `Swarm`
| id, name | `String` |
| members | `List<SwarmMember>` |
| paramsIds | `List<String>` |
| systemPromptId | `String?` |

### `SwarmMember`
| provider | `AppService` |
| model | `String` |

### `Parameters`
A named parameter preset.

| Field | Type |
|---|---|
| id, name | `String` |
| temperature | `Float?` |
| maxTokens | `Int?` |
| topP | `Float?` |
| topK | `Int?` |
| frequencyPenalty | `Float?` |
| presencePenalty | `Float?` |
| systemPrompt | `String?` |
| stopSequences | `List<String>?` |
| seed | `Int?` |
| responseFormatJson | `Boolean` |
| searchEnabled | `Boolean` |
| returnCitations | `Boolean` (default true) |
| searchRecency | `String?` |
| webSearchTool | `Boolean` |
| reasoningEffort | `String?` (`"low"`, `"medium"`, `"high"`, or null) |

→ For how these fields are *resolved* at each call site (the agent /
flock / swarm / per-call precedence), see **[parameters.md](parameters.md)**.

### `Endpoint`
| id, name, url | `String` |
| isDefault | `Boolean` |

### `SystemPrompt`
| id, name, prompt | `String` |

→ For which system prompt wins at each kind of API call, see
**[system-prompts.md](system-prompts.md)**.

### `InternalPrompt`
User-managed prompt template. Covers Meta-prompt launchers on the
Report Result screen (`category="meta"`), Compare-with-meta prompts
(`category="meta_compare"`), Fan-out / Fan-in templates
(`category="fan_out"` / `"fan_in"`), worker fallback-chain prompts
(`category="workers"`), alternative icon/title prompts
(`category="alt"`), and fixed internal templates
(`category="internal"`: chat-title, model-info, model-intro,
translate-text, translate-title, second-rerank, second-moderation,
test-model).

| Field | Type | Notes |
|---|---|---|
| id | `String` (UUID) | |
| name | `String` | unique within (category, name) |
| reference | `Boolean` (default false) | when true on a meta entry, executor appends `[N] = Provider / Model` legend |
| category | `String` (default `"internal"`) | common values: `meta`, `meta_compare`, `fan_out`, `fan_in`, `workers`, `alt`, `internal` |
| agent | `String` (default `"*select"`) | `"*select"` = ask the user; otherwise an `Agent.name` |
| text | `String` | template body. Top-level placeholders: `@QUESTION@`, `@RESULTS@`, `@COUNT@`, `@TITLE@`, `@DATE@`, `@RESPONSE@`, `@PROMPT@`, `@LANGUAGE@`, `@TEXT@`, `@FAN_OUT_COUNT@`, `@MODEL@`, `@PROVIDER@`. Iterable block: `***Report*** @REPORT@@RESPONSES@` (whitespace-tolerant; one expansion per source-report) |
| title | `String` (default empty) | one-line description shown alongside `name` on Fan out and the prompt-edit screen |
| provider, model | `String?` | optional alternative to `agent`: pin the prompt directly to a provider id + model (resolved to a synthetic agent, taking precedence over `agent`) |
| parameters, systemPrompt | `String` (default `"*NONE"`) | per-prompt Parameters preset NAME / System-prompt NAME used for THIS prompt's call, overriding the agent/flock/swarm/provider/app-wide levels (unless a runtime 🌡️/🎭 pick was made) |
| workers | `List<Worker>` (default empty) | only the `workers` category: the ordered fallback chain `WorkerRunner` runs in random order until one succeeds. Each `Worker` is a Model / Agent / Flock / Swarm pick |
| modelSelection | `String` (default `"*CONFIGURED"`) | worker-selection mode for the kinds this prompt drives (Meta / Fan-in / Rerank / Moderation / the type-B batches / Find-alternative). `*CONFIGURED` runs against the configured `workers`; `*SELECT` pops the +Agent/+Flock/+Swarm/+Model picker at run time and runs against the user's pick (never written back) |

> The legacy `type` field is gone — routing is derived from
> `category`. There is **no** `metaTypeToKind` function: Rerank,
> Moderation, and Meta are dispatched by three distinct entry methods
> on `SecondaryRunManager` (`runRerank` → `RERANK`, `runModeration` →
> `MODERATION`, `runMetaPrompt` → `META`), wired from
> `ui/report/manage/Nav.kt`. "Compare", "Critique", "Synthesize" etc.
> are just user-given `meta`-category prompt **names** — the kind is
> always `META`; only `summarize` and `compare` ship as bundled seeds.

### `ExamplePrompt`
Stand-alone (title, text) pair the user curates as a starter library
for the New Report screen.

| Field | Type |
|---|---|
| id | `String` (UUID) |
| title | `String` |
| text | `String` |

### `ModelTypeOverride`
| Field | Type | Notes |
|---|---|---|
| id, providerId, modelId, type | `String` | |
| supportsVision | `Boolean` | wins over per-provider visionModels for this id |
| supportsWebSearch | `Boolean` | same idea for web-search |
| supportsReasoning | `Boolean` | same idea for reasoning-capable |

### `ReportModel`
Used during the report selection phase.

| Field | Type |
|---|---|
| provider | `AppService` |
| model, type, sourceType, sourceName | `String` |
| sourceId, agentId, endpointId, agentApiKey | `String?` |
| paramsIds | `List<String>` |

### `UsageStats`
A per-(provider, model, kind) aggregate. Persisted to
`usage-stats.json` under `<filesDir>` (no longer in prefs). The single
write chokepoint is `SettingsPreferences.updateUsageStats`, debounced to
disk once per 2 s.

| Field | Type | Notes |
|---|---|---|
| provider | `AppService` | |
| model | `String` | |
| callCount | `Int` | |
| inputTokens, outputTokens | `Long` | |
| kind | `String` | `report`, `rerank`, `meta`, `moderation`, `translate`, `tournament`, `judges`, `compare`, `transrank`, plus metadata / worker buckets such as `icon`, `model/icons`, `fan/meta`, and the `translate/...` sub-types. The Type column often displays a friendlier prompt / flow label. |
| searchUnits | `Long` | per-search billing units for `rerank` rows (Cohere bills per search-unit, not per token) |
| inputCost, outputCost | `Double?` | persisted USD cost at call time; null on legacy rows (readers fall back to `PricingCache`) |
| pricingSource | `String?` | which tier priced the row; forced to `"API_REPORTED"` for providers that self-report cost |

### `ModelCapabilities`
Per-model capability bundle derived from a provider's own `/models`
endpoint. Authoritative when populated since it's the provider's
self-report; empty fields fall through to LiteLLM / models.dev /
heuristic in the lookup chain.

| Field | Type | Notes |
|---|---|---|
| supportsVision | `Boolean?` | provider self-report on image input |
| supportsFunctionCalling | `Boolean?` | tool / function calling |
| contextLength | `Int?` | input context window |
| maxOutputTokens | `Int?` | per-call output cap |
| supportsReasoning | `Boolean?` | "this model exposes a thinking / reasoning_effort parameter". Surfaces from each provider's `/models` response — Anthropic `capabilities.thinking.supported`, Gemini top-level `thinking`, Mistral `capabilities.reasoning`, xAI / OpenRouter `supported_parameters` containing "reasoning". Null falls through to the `inferReasoning` heuristic |
| reasoningEffortLevels | `List<String>?` | subset of "low" / "medium" / "high" / "max" the model accepts on `reasoning_effort`. Currently from Anthropic `capabilities.effort.{low,medium,high,max}` — Claude 3.7 / 4.x report different sets per tier |
| supportsPdfInput | `Boolean?` | native PDF document blocks (Anthropic `capabilities.pdf_input.supported`). Distinct from vision because Anthropic parses page text + embedded images server-side |
| aliases | `List<String>?` | friendly version-alias ids (Mistral `aliases: [...]`). The picker search filter matches against these so a query for "latest" finds the dated model |
| deprecationDate | `String?` | ISO-8601 deprecation date (Mistral). Pickers can render a ⚠ badge when set |
| deprecationReplacement | `String?` | provider-recommended successor (Mistral `deprecation_replacement_model`) |
| defaultTemperature | `Float?` | provider-recommended default temperature (Mistral `default_model_temperature`). Surfaced on Model Info |
| defaultStopSequences | `List<String>?` | Together's `config.stop` — typically the tokeniser's eos / bos markers |

### `FetchedModels`
Result of a single provider model-list fetch.

| ids | `List<String>` |
| types | `Map<String, String>` |
| visionModels | `Set<String>` |
| capabilities | `Map<String, ModelCapabilities>` |
| rawResponse | `String?` |
| nativePricing | `Map<String, PricingCache.ModelPricing>` (default empty) — per-model prices harvested from the `/models` payload (Together AI only); fed into the `TOGETHER` pricing tier |

---

## Reports (`com.ai.data`)

### `Report`
Persisted one JSON file per report at `<filesDir>/reports/<id>.json` by
the `ReportStorage` object. The class is large — the core fields plus
the families of metadata-generation bookkeeping (icon / title /
titleLong / language) are summarised below.

| Field | Type | Notes |
|---|---|---|
| id | `String` (UUID) | |
| timestamp | `Long` | last-changed time, bumped on (almost) every mutation |
| createdAt | `Long` (default 0) | stable creation time; 0 on legacy reports (falls back to `timestamp`) |
| completedAt | `Long?` | |
| title, prompt | `String` | short title + the user's question |
| agents | `MutableList<ReportAgent>` | one per model in the run |
| totalCost | `Double` | |
| rapportText, closeText | `String?` | optional intro / outro text |
| reportType | `ReportType` (`CLASSIC` / `TABLE`) | |
| imageBase64, imageMime | `String?` | vision attachment — downscaled + JPEG-encoded before storage |
| webSearchTool | `Boolean` | per-report 🌐 toggle, replayed on regenerate |
| reasoningEffort | `String?` | per-report 🧠 hint (`low` / `medium` / `high`) |
| sourceReportId | `String?` | set when this report is a translated copy of another |
| knowledgeBaseIds | `List<String>` | attached RAG knowledge bases ([knowledge.md](knowledge.md)) |
| parameterPresetIds, advancedParameters, selectionParamsById, reportSystemPromptId | resolved generation config | captured at create time so Regenerate replays the SAME selections, not whatever the live UiState holds now |
| pinned | `Boolean` | user-pinned; surfaces above Recent on the Reports hub |
| workerConfig | `ReportWorkerConfig` | per-report worker routing picked on "Report - select workers" (report info `PROMPT`/`CUSTOM` + custom chain; model info `PROMPT`/`OWN_MODEL`; worker batches `PROMPT`/`REPORT_MODELS`/`SELECT_EACH`/`SELECT_ONCE` + persisted one-time group; `WHEN_AVAILABLE`/`ROUND_ROBIN` selection under REPORT_MODELS). Editable later via the Manage 👷 action. Replaces the old ♻️ flag; see [workers.md](workers.md) |
| costsFromDeletedItems | `Double` | input+output cost of every deleted row (agent / secondary / fan-out / fan-in / translation). Uses `SecondaryResult.fullCost()` so a pair's icon+title spend isn't dropped. Surfaced as its own line above Total when non-zero |
| icon, iconErrorMessage, iconModel | `String?` | per-report emoji from `kickOffIconGeneration` (worker engine `workers/report-icon`); error reason on failure; `iconModel` set when picked manually via Find-alternative ([report-icons.md](report-icons.md)) |
| icon{Input,Output}{Tokens,Cost}, iconDurationMs, iconTraceFile | token / USD / time / trace bookkeeping for the icon call |
| titleModel, titlePromptUsed, title{Input,Output}{Tokens,Cost}, titleDurationMs | short-title (≤25 char) AI-gen bookkeeping; `titlePromptUsed="report_title"` doubles as the success sentinel |
| titleLong | `String?` | longer title (≤50 char) for the top-bar orange line; null for manually-set titles |
| titleLong{Input,Output}{Tokens,Cost}, titleLongModel, titleLongTraceFile, titleLongDurationMs | long-title call bookkeeping (separate `report/title-long` cost row) |
| languageName, languageIcon, languageIconModel | `String?` | detected source-language English name + flag emoji + the model that produced the icon |
| language{,Icon}{Input,Output}{Tokens,Cost}, language*TraceFile, language*RawResponse, language*DurationMs, languageIconPromptUsed, languageIconErrorMessage | two-call language flow (detect, then pick emoji) bookkeeping |
| iconCalls | `MutableList<IconCallRecord>` | per-call audit log for every icon / title / Find-alternative attempt |
| apiCallCosts | `MutableList<ReportApiCallCost>` | durable append-only per-report cost ledger (version 3) |
| apiCallCostsComplete, apiCallCostsVersion | `Boolean` / `Int` | ledger completeness flag + schema version |
| runId | `String?` | UUID shared by every trace of this report's initial generation; the L1 🐞 deep-links to it |
| promptHistory | `List<PromptRevision>` | superseded prompt bodies (Edit prompt → Previous prompts) |
| userNotes | `MutableList<UserNote>` | free-text notes pinned to the report / an agent / a secondary / a fan-out run |

`val Report.barTitle: String` = `titleLong` when non-blank, else
`title` — used as the top-bar / Answer-matrix title text.

### `ReportAgent`
| Field | Type | Notes |
|---|---|---|
| agentId, agentName, provider, model | `String` | |
| reportStatus | `ReportStatus` (`PENDING`, `RUNNING`, `SUCCESS`, `ERROR`, `STOPPED`) | |
| httpStatus | `Int?` | |
| requestHeaders, requestBody, responseHeaders, responseBody | `String?` | |
| responseChangeSource, responseChangeValue | `String?` | replacement marker for `responseBody` (`Chat` / `Temperature` / `Reasoning Effort` / `Web Search` / `Edit` / `Model switch`); null for the original response or a plain regenerate |
| errorMessage | `String?` | |
| tokenUsage | `TokenUsage?` | |
| cost, inputCost, outputCost, durationMs | `Double?` × 3, `Long?` | `inputCost`/`outputCost` are the USD split pinned at run completion so a later re-price doesn't shift historical cost |
| traceFile | `String?` | trace filename of this agent's primary response call |
| citations, searchResults, relatedQuestions | `List<String>?` / `List<SearchResult>?` | |
| rawUsageJson | `String?` | |
| icon, iconErrorMessage | `String?` | per-model emoji from the worker engine (`workers/model-icons`), derived from the model **title** not the response. Null until it runs / on failure |
| icon{Input,Output}{Tokens,Cost} | token + USD bookkeeping |
| iconWinningTier | `Int?` | **legacy** from the removed response-based 3-tier chain; always null now (worker-engine, manual, and Find-alternative all leave it null) |
| iconPromptUsed | `String?` | bundled prompt name for the current emoji (`report_title_icon`, or `report_alt` after a Find-alt pick) |
| modelTitle, modelTitleErrorMessage, modelTitleModel, modelTitle*{Tokens,Cost}, modelTitleTraceFile, modelTitleDurationMs, modelTitlePromptUsed | per-model response title (worker engine `workers/model-titles`) + bookkeeping |
| chatMessages | `List<ChatMessage>` | in-report "refine this answer" 🗣️ conversation; applying a reply overwrites `responseBody` |

### `IconCallRecord`
One captured icon / title generation API call — worker-engine calls plus
every Find-alternative attempt ([report-icons.md](report-icons.md)).
Stored on `Report.iconCalls` so the per-call All-tab in the cost export
renders every attempt, including failed earlier tiers.

| Field | Type | Notes |
|---|---|---|
| agentId | `String` | |
| tier | `Int` | retained for cost-row labelling; not a response-based "winning tier" anymore |
| provider, model, pricingTier | `String` | the model that actually billed the call |
| inputTokens, outputTokens | `Int` | |
| inputCost, outputCost | `Double` | |
| durationMs | `Long?` | |
| success | `Boolean` | |
| timestamp | `Long` | |
| type | `String?` | overrides the agentId-based cost classifier; Find-alt fan-out calls set the bundled `_alt` prompt name (`main_alt`, `meta_alt`, `report_alt`, `language_alt`, `translation_alt`) |
| attributedToSecondaryId | `String?` | when set, this cost is attributed to a `SecondaryResult` on the same report (so the cost table subtracts it from that row to avoid double-counting) |

### `ReportApiCallCost`
One row of the durable per-report cost ledger (`Report.apiCallCosts`).
Unlike traces, it is part of the report JSON itself, so report cost
totals don't depend on optional trace files.

| Field | Type |
|---|---|
| id | `String` (UUID) |
| timestamp | `Long` |
| type, provider, model, pricingTier | `String` |
| inputTokens, outputTokens, searchUnits | `Int` |
| inputCost, outputCost | `Double` |
| durationMs | `Long?` |
| traceFile | `String?` |

### `SecondaryResult`
A single flat row used for **every** secondary kind — rerank, chat-type
Meta (driven by the user's Meta-prompt CRUD entries), moderation,
translation, fan-out per-pair row, fan-in combined-report row,
Tournament match / aggregate row, Judge-the-judges cell / aggregate row,
Compare cell, or Translator-rank (TRANSRANK) score cell / aggregate row.
Persisted one JSON file per result at
`<filesDir>/secondary/<reportId>/<resultId>.json` by the
`SecondaryResultStorage` object. The kind-specific fields are mostly
null on rows of other kinds. See
**[secondary-results.md](secondary-results.md)**.

**Common fields:**

| Field | Type | Notes |
|---|---|---|
| id, reportId | `String` | |
| kind | `SecondaryKind` | one of the 8 below (`RERANK`, `META`, `MODERATION`, `TRANSLATE`, `TOURNAMENT`, `JUDGES`, `COMPARE`, `TRANSRANK`) |
| providerId, model, agentName | `String` | |
| timestamp | `Long` | |
| content | `String?` | the model output. A chat-type META row whose Meta prompt has `reference=true` gets a deterministic `## References` legend appended at storage time |
| errorMessage | `String?` | |
| tokenUsage | `TokenUsage?` | |
| inputCost, outputCost | `Double?` | |
| durationMs | `Long?` | |
| httpStatusCode | `Int?` | final HTTP status code for the API call that produced this row; null for legacy rows and non-network failures. Fan-out statistics use this on per-pair rows |
| traceFile | `String?` | trace filename for the call (currently populated for TRANSLATE rows) |

A row's identity within a kind is derived, not stored: a **Fan-out**
per-pair row has `fanOutSourceAgentId != null`; a **Fan-in**
combined-report row has `fanInOf != null` — both carry `kind = META`.

**META / Fan-out / Fan-in fields:**

| Field | Type | Notes |
|---|---|---|
| metaPromptId | `String?` | id of the `InternalPrompt` (`meta` / `meta_compare` / `fan_out` / `fan_in` / `workers`) that produced this row |
| metaPromptName | `String?` | display name copied at run time. Drives every UI bucket / export section / cost-row label so a later rename / delete doesn't reshape old rows |
| fanOutSourceAgentId | `String?` | Fan-out pair: agentId of the report-model whose response was substituted into the prompt's `@RESPONSE@` slot. With this row's own `(providerId, model)` (the answerer) it forms the (answerer, source) pair the drill-in keys on |
| fanInOf | `String?` | Fan-in: id of the `InternalPrompt` that produced the combined output |
| secondaryScope | `String?` | encoded `SecondaryScope` (`"ALL"` / `"TOP:<rerankResultId>:<count>"` / `"MANUAL:<agentId>,..."`) so cascade-on-prompt-change re-runs at the same scope |
| secondaryParameterPresetIds, secondarySystemPromptId | `List<String>?` / `String?` | param/system-prompt selections captured at launch for faithful pair-variation replays |
| responseChangeSource, responseChangeValue | `String?` | user-selected replacement marker for `content` |
| chatMessages | `List<ChatMessage>` | in-report 🗣️ "refine this answer" conversation for a fan-out pair |

**Per-pair Fan-Meta icon + title fields** (the worker call that titles
and icons one fan-out pair — [report-icons.md](report-icons.md)):
`icon`, `iconWinningTier` (legacy, always null), `iconErrorMessage`,
`iconInput/OutputTokens`, `iconInput/OutputCost`, `iconPromptUsed`,
`iconRunId`; and the title twins `title`, `titleErrorMessage`,
`titleInput/OutputTokens`, `titleInput/OutputCost`, `titleModel`,
`titleDurationMs`, `titlePromptUsed`, `titleRunId`. `runId` is the UUID
of the fan-out batch that created the row.
`SecondaryResult.fullCost()` rolls the primary in/out cost **plus** the
icon+title spend together, so delete / re-run paths don't drop the
per-pair metadata cost.

**TRANSLATE fields:**

| Field | Type | Notes |
|---|---|---|
| translateSourceTargetId | `String?` | id of the item translated (`"prompt"`, `agent.agentId`, or a secondary `id`) |
| translateSourceKind | `String?` | `"PROMPT"`, `"AGENT"`, `"META"`, plus title kinds (`"TITLE"`, `"TITLE_LONG"`, `"AGENT_TITLE"`, `"FANOUT_TITLE"`) |
| targetLanguage | `String?` | English language name (e.g. `"Dutch"`) |
| targetLanguageNative | `String?` | native rendering (e.g. `"Nederlands"`) |
| translationRunId | `String?` | UUID shared by every row of one Translate batch |

`translateTraceType(srcKind, sourceIsFanOut, sourceIsFanIn)` maps the
source to the trace category / cost Type / AI Usage kind
(`translate/report_prompt`, `translate/model_response`,
`translate/fan/out/response`, `translate/meta`, …). Rerank / Moderation
are never translated.

**TOURNAMENT / JUDGES fields:**

| Field | Type | Notes |
|---|---|---|
| tournamentRole | `String?` | `"MATCH"` for cell rows, `"AGGREGATE"` for the rollup row |
| tournamentJudgeRunId | `String?` | run key `"${reportId}|${providerId}|${model}"` grouping the run's rows |
| matchResponseAId, matchResponseBId | `String?` | match source agent ids (`@RESPONSE_A@` / `@RESPONSE_B@` slots) |
| matchOrientation | `Int?` | Tournament: 0 (A-vs-B) or 1 (swapped B-vs-A, to cancel position bias). Judges uses 0 |
| tournamentMatrix | `String?` | AGGREGATE row — encoded win matrix + selected ranking method |

A tournament MATCH placeholder starts at sentinel
`providerId="*workers"` / `model="*pending"` (the judging model is
unknown until the round-robin worker chain returns); the AGGREGATE row
uses `providerId="*tournament"` / `model="aggregate"`.

**COMPARE fields:**

| Field | Type | Notes |
|---|---|---|
| compareRunId | `String?` | run id shared by its cells |
| compareAgentId | `String?` | the report answer being scored |
| compareToResultId | `String?` | the Meta row scored against |

A Compare cell placeholder starts at sentinel `providerId="*workers"` /
`model="*pending"`, overwritten with the winning worker on commit.

**TRANSRANK fields** (no new columns — "Rank the translators" reuses
the existing tournament / compare / translate fields):

| Reused field | Meaning on a TRANSRANK row |
|---|---|
| `providerId`, `model` | the **judge** model that scored the translation |
| `tournamentRole` | `"MATCH"` for a score cell, `"AGGREGATE"` for the per-translator ranking row |
| `tournamentJudgeRunId`, `runId` | the transrank run id |
| `translationRunId` | the source translation run being ranked |
| `compareToResultId` | the scored TRANSLATE row id |
| `matchResponseAId`, `matchResponseBId` | the **translator** model's providerId / model |
| `targetLanguage`, `targetLanguageNative` | the language scored |
| `content` | the judge's two-line reply (0–100 score + reason) |

The AGGREGATE row uses sentinel `providerId="*transrank"` / `model="aggregate"`;
its `content` is the JSON ranking from `List<TranslatorRankRow>.toTransRankJson()`.
See the runtime value types under [`TransRankRunState` / `TransRankCellState`](#transrankrunstate--transrankcellstate).

### `SecondaryScope` (sealed)
- `AllReports` — every successful agent feeds the meta-result.
- `TopRanked(count: Int, rerankResultId: String)` — input narrowed
  to the top-N entries of a chosen rerank.
- `Manual(agentIds: Set<String>)` — explicit list of agent ids the
  user picked from the existing report.

`encode()` serialises to `"ALL"` / `"TOP:<id>:<count>"` /
`"MANUAL:<id>,<id>"` for storage on `SecondaryResult.secondaryScope`.
`decodeOrAllReports(s)` is defensive — corrupt or legacy strings
fall back to `AllReports`.

### `SecondaryLanguageScope` (sealed)
For chat-type META and Translate fan-out across translated content
present on the report.

- `AllPresent` — fan out across every language present.
- `Selected(languages: Set<String>)` — restrict to the chosen
  English-name languages (plus the empty string for the original /
  untranslated source).

### `TournamentRunState` / `MatchState`
Runtime state for one Tournament on a report. Hydrated from
`SecondaryResult(kind=TOURNAMENT)` rows.

| Type | Notes |
|---|---|
| `TournamentRunState` | `key`, `reportId`, `runId`, `tournamentPrompt`, `scope`, `matches`, `aggregateRowId`, `selectedMethod`, `cancelled`; derived counts for total/done/error/running/queued/cost/judge models |
| `MatchState` | one ordered head-to-head: row id, response A/B ids, orientation, status, judge model, verdict, confidence, reason, raw content, error, tokens, cost, duration, timestamp |

`TournamentMethod` has 11 ranking methods: `COPELAND`, `ELO`,
`DAVIDSON`, `TIDEMAN`, `MARKOV`, `SCHULZE`, `MINIMAX`, `COLLEY`,
`GLICKO2`, `POINTS`, `TRUESKILL2`. See
[tournament-judges-compare.md](tournament-judges-compare.md).

### `JudgeEvalRunState` / `JudgeCellState`
Runtime state for one Judge-the-judges run on a report. Hydrated from
`SecondaryResult(kind=JUDGES)` rows.

| Type | Notes |
|---|---|
| `JudgeEvalRunState` | `key`, `reportId`, `runId`, prompt, cell map, aggregate row id, cancelled flag; derived judge/match counts and cost |
| `JudgeCellState` | one judge's verdict on one shared match: row id, judge provider/model, response A/B ids, status, verdict, confidence, reason, trace, tokens, cost, duration |
| `JudgeStats` | computed per-judge row (sorted best-first by agreement): judge providerId/model, `matchesJudged`, `errors`, `agreement` (0..1 vs the per-match consensus), `tieRate`, `aLean` (position-bias proxy), `avgConfidence`, `totalCost`, `totalMs` |

### `CompareRunState` / `CompareCellState`
Runtime state for Compare with meta. Hydrated from
`SecondaryResult(kind=COMPARE)` rows.

| Type | Notes |
|---|---|
| `CompareRunState` | `key`, `reportId`, `runId`, selected compare prompt, cell map, cancelled flag; derived counts/cost and per-agent/per-meta averages |
| `CompareCellState` | one answer × meta-item similarity score: row id, agent id, meta result id, status, 0..100 percent, reason, scoring worker, trace, tokens, cost, duration |

### `TransRankRunState` / `TransRankCellState`
Runtime state for one "Rank the translators" run (`SecondaryKind.TRANSRANK`),
defined in `data/TranslatorRankModel.kt`. A run **reuses an existing
translation run**: every long-form translated item is scored 0–100 by a
panel of judge models — every model in the `translate-rank` worker swarm
*except* the one that produced the item. One CELL per (item × judge); one
AGGREGATE row holds the per-translator-model ranking. Hydrated from
`SecondaryResult(kind=TRANSRANK)` rows.

| Type | Notes |
|---|---|
| `TransRankCellState` | one judge's score of one translated item: `id` (= SecondaryResult.id), judge providerId/model, translator providerId/model, `translationRowId`, `sourceTranslationRunId`, `targetLanguage`, `status` (`TransRankCellStatus` = `BatchItemStatus`), parsed `score` (0–100) / `reason`, raw `content`, `errorMessage`, `inputCost`/`outputCost`, `durationMs`, `tokenUsage`, `traceFile`, `timestamp`. Derived `judgeKey` / `translatorKey` (`"provider/model"`), `key` (`"$judge|$translationRowId"`), `totalCost` |
| `TransRankRunState` | `key` (`"$reportId\|$sourceTranslationRunId"` — one ranking per language), `reportId`, `runId`, `sourceTranslationRunId`, `targetLanguageName`, `targetLanguageNative`, `prompt: InternalPrompt`, `cells: Map<String, TransRankCellState>`, `aggregateRowId`, `cancelled`; derived `translatorKeys` |

Caps: `TRANSRANK_CELLS_PER_TRANSLATOR = 25` (≤ 25 score cells per
translator model). Role sentinels `TRANSRANK_ROLE_CELL = "MATCH"` /
`TRANSRANK_ROLE_AGGREGATE = "AGGREGATE"`.

### `TranslatorRankRow`
One row of the aggregated translator ranking, produced by
`aggregateTranslatorRanks(cells)` (averages each translator's scores over
cells where judge ≠ translator, best-first) and serialised to the
AGGREGATE row's `content` via `toTransRankJson()`.

| Field | Type | Notes |
|---|---|---|
| providerId, model | `String` | the translator model |
| avgScore | `Double` | mean of the scores it received |
| itemCount | `Int` | distinct translated items it produced that got ≥1 score |
| judgedCount | `Int` | total scores received |

Derived `translatorKey` = `"providerId/model"`.

### `RerankApiResult`
Result of a provider's dedicated rerank endpoint call (e.g.
Cohere `/v2/rerank`). Mapped into the same `[{id, rank, score, reason}, ...]`
JSON the chat-model rerank flow produces, so the rest of the system
(HTML export, Top-Ranked scope) doesn't need a second code path.

| content | `String?` (JSON in the same shape as the chat prompt) |
| errorMessage | `String?` |
| httpStatusCode | `Int?` |
| billedSearchUnits | `Int?` |
| durationMs | `Long` |

### `ModerationApiResult`
Outcome of a single moderation endpoint call.

| content | `String?` (JSON `[{id, flagged, categories, scores}, ...]`) |
| errorMessage | `String?` |
| httpStatusCode | `Int?` |
| tokenUsage | `TokenUsage?` |
| durationMs | `Long` |

> There is no `SecondaryRunState` data class. In-flight secondary work
> is surfaced via `UiState.activeSecondaryBatches: Int` (incremented on
> entry, decremented in `finally` by every `SecondaryRunManager` runner)
> plus the hot per-row `StateFlow<Set<String>>` sets on `AppViewModel`
> (`runningFanOutPairs`, `runningFanMetaPairs`, `runningFanMetaRowIds`,
> `runningSingleSecondaries`, `runningInfoJobs`). The Tournament /
> Judges / Compare / TransRank engines instead hydrate their respective
> `*RunState` from disk and track in-flight items via the per-item
> `status` on each cell/match.

### `TokenUsage`
| Field | Type | Notes |
|---|---|---|
| inputTokens, outputTokens | `Int` | |
| apiCost | `Double?` | provider-reported cost (OpenRouter / Perplexity / xAI ticks). When non-null it wins over token-math pricing |
| cachedInputTokens, cacheCreationTokens, reasoningTokens | `Int` (default 0) | cache-aware + reasoning token splits used by `computeCost` |

Computed: `totalTokens` = the sum of all five token counts.

### `SearchResult`
| name, url, snippet | `String?` |

### `AnalysisResponse`
Repository-level result for a single API call.

| service | `AppService` |
| analysis | `String?` (full response text) |
| error | `String?` |
| agentName, promptUsed | `String?` |
| tokenUsage | `TokenUsage?` |
| citations | `List<String>?` |
| searchResults | `List<SearchResult>?` |
| relatedQuestions | `List<String>?` |
| rawUsageJson | `String?` |
| httpHeaders | `String?` |
| httpStatusCode | `Int?` |
| isSuccess | `Boolean` (computed) |

### `AgentParameters`
Lower-level twin of `Parameters` used in dispatch. Same fields as
`Parameters` minus `id`/`name`, plus:
- `reasoningEffort: String?` — reasoning hint for models that
  support it (gpt-5.x / o-series via Responses API; Gemini thinking
  models). Non-reasoning models silently drop the field at dispatch.

---

## Provider routing (`com.ai.data`)

### `AppService`
A registered provider (loaded from `assets/providers.json` + custom
additions). See [providers.md](providers.md) for the full
per-provider table.

| Field | Type | Notes |
|---|---|---|
| id | `String` | identifier AND human-readable label. The id-unification refactor collapsed three name-like fields (`id` / `displayName` / `prefsKey`) into one. SharedPreferences key prefixes use `id` directly |
| baseUrl, adminUrl, defaultModel | `String` | |
| openRouterName | `String?` | composite-key prefix for the OpenRouter tier |
| apiFormat | `ApiFormat` | (`OPENAI_COMPATIBLE`, `ANTHROPIC`, `GOOGLE`) |
| typePaths | `Map<String, String>` | per-type API paths overriding the global default; `chatPath` and `responsesPath` are computed views |
| modelsPath | `String?` | default `"v1/models"` |
| seedFieldName | `String` | default `"seed"`, Mistral uses `"random_seed"` |
| supportsCitations, supportsSearchRecency, extractApiCost | `Boolean` | |
| costTicksDivisor | `Double?` | xAI returns ticks; divisor is 1e10 |
| promptTokensIncludeCachedTokens | `Boolean` | for OpenAI-compatible usage normalization; false means flattened `cached_tokens` is separate from fresh `prompt_tokens` |
| modelListFormat | `String` | `"object"` or `"array"` |
| modelFilter | `String?` | regex |
| litellmPrefix | `String?` | composite-key prefix for the LiteLLM tier |
| hardcodedModels | `List<String>?` | fallback list |
| defaultModelSource | `String?` | `"API"` or `"MANUAL"` |
| auxHosts | `List<String>` | alternate API hostnames besides `baseUrl`'s host. The rate-limit-retry interceptor and tracer use this so a Mistral request that lands on `codestral.mistral.ai` is matched as the same logical provider |
| nativeRerankUrl | `String?` | full URL the rerank dispatcher POSTs to. Cohere `/v2/rerank`. Null → no native rerank API; rerank flow falls back to a chat-model JSON prompt |
| nativeModerationUrl | `String?` | full URL the moderation dispatcher POSTs to. Mistral `/v1/moderations`. Null → no native moderation API |
| nativeCapabilityUrl | `String?` | full URL of a Cohere-shaped `/v1/models` capability listing. Drives the per-model context-length / vision flags when populated |
| pricingFromModelList | `Boolean` | provider's `/v1/models` response carries authoritative pricing; harvest into `PricingCache.TOGETHER` tier (currently Together AI only) |
| crossProviderModelList | `Boolean` | provider's `/v1/models` response drives pricing + type fan-out across other providers (currently OpenRouter only) |
| mergeHardcodedModels | `Boolean` | union persisted `hardcodedModels` with the API list when the fetcher refreshes (so OpenAI moderation / TTS / image models survive a refresh that doesn't list them) |
| externalReasoningSignalUntrusted | `Boolean` | ignore the LiteLLM / models.dev "is reasoning" signal — xAI's always-on reasoning models reject the `reasoning_effort` parameter. The 🧠 badge still renders; the dispatcher just skips the parameter |
| responsesApiPatterns | `List<ModelPattern>` | model-id patterns routing dispatch to the OpenAI Responses API (`gpt-5*`, `o3*`, `o4*`, `gpt-4.1*`) |
| reasoningModelPatterns | `List<ModelPattern>` | gates the 🧠 reasoning badge + thinking dispatch |
| reasoningEffortAcceptPatterns | `List<ModelPattern>?` | narrower subset that actually accepts `reasoning_effort`. Null = use `reasoningModelPatterns`; xAI sets a narrower list because its always-on variants reject the parameter |
| webSearchModelPatterns | `List<ModelPattern>` | gates the 🌐 web-search tool descriptor |
| adaptiveThinkingPatterns | `List<ModelPattern>` | opts in to Anthropic's adaptive-thinking shape (`claude-opus-4-7`+); older Claude 3.7 / 4.x use the `budget_tokens` shape |
| maxTokensDefaults | `List<MaxTokensRule>` | per-family default `max_tokens` (Anthropic). First match wins, default 4096 |
| builtInEndpoints | `List<Endpoint>` | bundled alternate endpoints (DeepSeek main + reasoner; Mistral chat + Codestral; Z.AI mainland + international). User can pick between them on the provider edit screen |
| maxCallsPerProviderPerMinute | `Int?` | per-provider override for `GeneralSettings.maxCallsPerProviderPerMinute`. Null → inherit. Read by `ProviderThrottle.acquire` when this provider's hostname matches. See [throttle.md](throttle.md) |
| maxConcurrentCallsPerProvider | `Int?` | per-provider override for the concurrency cap. Null → inherit |
| maxRetriesOn429 | `Int?` | per-provider override for the 429-retry cap (0 = disable in-line retries). Null → inherit |
| retryBackoffMs429 | `Long?` | per-provider override for the wait between 429 retries. Null → inherit |
| maxRetriesOn529 | `Int?` | per-provider override for the 529 retry cap (0 = disable in-line retries). Null → inherit |
| retryBackoffMs529 | `Long?` | per-provider override for the wait between 529 retries. Null → inherit. Seeded to 5000 ms for Anthropic |

#### `ModelPattern`
Shared by every `*Patterns` field on `AppService` (defined in
`ProviderRegistry.kt`). Four optional string fields, all matched against
the model id lowercased: `exact`, `prefix`, `contains`, `suffix`. When
more than one is set they must **all** match (intersection — e.g.
`prefix:"grok-4-" + contains:"reasoning"` matches only the grok-4
reasoning variants); an all-null pattern matches nothing.
`List<ModelPattern>?.anyMatches(modelId)` returns true on the first
pattern in the list that matches (a null / empty list returns false —
"feature off for this provider").

#### `MaxTokensRule`
`MaxTokensRule(pattern: ModelPattern, maxTokens: Int)` — a per-family
default `max_tokens`. `List<MaxTokensRule>?.resolveMaxTokens(modelId)`
returns the first matching rule's value (else null, caller falls back to
4096). Used as the dispatch-time default when the user hasn't pinned an
explicit `max_tokens`. Applied to Anthropic (whose `max_tokens` is
required) and also to OpenAI-compatible calls, to avoid OpenRouter
balance-gating 402s.

#### `Endpoint`
Bundled alternate endpoint. See `Endpoint` under "Settings &
Configuration" — same shape, just preloaded from `providers.json`
instead of created by the user.

### `ApiFormat` (enum)
`OPENAI_COMPATIBLE`, `ANTHROPIC`, `GOOGLE`. The cloud dispatch keys
off this in `when (service.apiFormat)` blocks (analyze / chat /
fetchModels / streaming / auth / endpoint URL). Of the 48 bundled
providers, **40** are `OPENAI_COMPATIBLE` (sharing unified code), 1 is
`ANTHROPIC`, 1 is `GOOGLE` — so only Anthropic and Google have
format-specific branches. (The enum's source comment still says "28
providers using OpenAI-compatible"; that count is stale.)

The synthetic `AppService.LOCAL` is **not** routed by `apiFormat` — its
format is the default `OPENAI_COMPATIBLE` and is never used for a
network call. Callers route on-device work with a
`provider.id == AppService.LOCAL.id` check to `LocalLlm` / `LocalEmbedder`
(see [local-runtime.md](local-runtime.md)).

### `ModelType` (constants)
`CHAT`, `RESPONSES`, `EMBEDDING`, `RERANK`, `IMAGE`, `TTS`, `STT`,
`MODERATION`, `CLASSIFY`, `OCR`, `UNKNOWN`. `OCR` is Mistral-specific
(its `mistral-ocr-*` capability flag); `UNKNOWN` is the runtime
fallback when no source identifies the type. `ModelType.ALL` lists
every type the user can configure paths for in display order — `CHAT`,
`RESPONSES`, `EMBEDDING`, `RERANK`, `IMAGE`, `TTS`, `STT`,
`MODERATION`, `CLASSIFY`, `OCR` — `UNKNOWN` is excluded.

`inferReasoning` and the narrower `inferAcceptsReasoningEffortParam`
are split so the 🧠 badge can fire on always-on reasoning models
(xAI grok-4.x) that reject the parameter at dispatch time.
`inferWebSearch` consults `provider.webSearchModelPatterns`.

---

## Pricing (`com.ai.data.PricingCache`)

### `PricingCache.ModelPricing`
| Field | Type | Notes |
|---|---|---|
| modelId | `String` | |
| promptPrice, completionPrice | `Double` | **per token** (not per million) |
| source | `String` (default `"unknown"`) | which tier priced it — `LITELLM`, `MODELSDEV`, `OVERRIDE`, `OPENROUTER`, `HELICONE`, `LLMPRICES`, `ARTIFICIAL_ANALYSIS`, `TOGETHER`, `DEFAULT`, `API_REPORTED` |
| cachedReadPrice, cachedWritePrice | `Double?` | cache-aware input rates; null = charge full input |
| promptPriceAbove200k, completionPriceAbove200k, cachedReadPriceAbove200k, cachedWritePriceAbove200k | `Double?` | >200k-context tier (Gemini 2.5/3 Pro, etc.) |
| perQueryPrice | `Double` (default 0) | per-search-unit price for rerank models (Cohere bills per search, not per token) |

`DEFAULT_PRICING = ModelPricing("default", 25e-6, 75e-6, "DEFAULT")` —
i.e. $25/M input, $75/M output, **not** zero. It is also what
`getPricing` returns when called on the main thread before the catalog
preload completes (the UI cold window). The full layered-lookup
precedence lives in **[costs.md](costs.md)**; in short, manual `OVERRIDE`
beats every curated catalog tier but loses to the two provider
self-report tiers (OpenRouter-self, Together-self).

### `PricingCache.TierBreakdown`
Per-(provider, model) snapshot showing every tier's independently-computed
view, used by the layered Costs view and the 🐞 pricing trace.

| Field | Type |
|---|---|
| litellm, modelsDev, helicone, llmPrices, artificialAnalysis, override, openrouter, together | `ModelPricing?` |
| default | `ModelPricing` |

---

## Chat (`com.ai.data`)

### `ChatMessage`
| role | `String` (`user`, `assistant`, `system`) |
| content | `String` |
| imageBase64, imageMime | `String?` (vision attachment as base64; JPEG-encoded after downscale) |
| timestamp | `Long` |
| id | `String?` (UUID, default generated) |

### `ChatSession`
| Field | Type | Notes |
|---|---|---|
| id | `String` (UUID) | |
| provider | `AppService` | |
| model | `String` | |
| messages | `List<ChatMessage>` | |
| parameters | `ChatParameters` | |
| createdAt, updatedAt | `Long` | |
| pinned | `Boolean` | surfaces above Recent on the AI Chat hub |
| knowledgeBaseIds | `List<String>` | attached RAG knowledge bases; each user turn prepends a retrieved context block ([knowledge.md](knowledge.md)) |
| title | `String` (default empty) | seeded with the first 10 words of the first user message on send; replaced asynchronously by the `chat_title` internal prompt after the first assistant response. Blank for legacy sessions — display sites fall back to `preview` |

Computed:
- `preview: String` — first user message, truncated to 50 chars (or
  `"Empty chat"`).

### `ChatParameters`
Per-chat generation overrides. A **subset** of `Parameters` — it carries
`systemPrompt`, `temperature`, `maxTokens`, `topP`, `topK`,
`frequencyPenalty`, `presencePenalty`, `searchEnabled`,
`returnCitations`, `searchRecency`, `webSearchTool`, and:
- `reasoningEffort: String?` — set per-turn from the chat session
  screen's 🧠 pulldown. Clamped to the active model's supported range
  on session resume. Only injected at dispatch when the model reports
  reasoning support.

It does **not** have `seed`, `stopSequences`, or `responseFormatJson`.
`webSearchTool` (explicit tool-use) and `searchEnabled` (the older flat
`search:true` flag) are kept distinct so each provider gets the request
shape it expects.

### `DualChatConfig`
Two-models-talk-to-each-other configuration. Persisted to
`dual_chat_prefs`.

| model1Provider, model2Provider | `AppService` |
| model1Name, model2Name | `String` |
| model1SystemPrompt, model2SystemPrompt | `String` |
| model1Params, model2Params | `ChatParameters` |
| subject | `String` |
| interactionCount | `Int` (default 10 — each model answers this many times) |
| firstPrompt | `String` (template, default `"Let's talk about %subject%"`) |
| secondPrompt | `String` (template, default `"What do you think about: %answer%"`) |

---

## Tracing (`com.ai.data.ApiTracer`)

Defined in `data/TraceModels.kt`. Traces are stored as pretty-printed
JSON under `<filesDir>/trace/`; the lightweight `TraceFileInfo` mirror
(parsed streaming, metadata-only) backs the list view. See
**[applog.md](applog.md)** / **[log-details.md](log-details.md)** for the
call-site categories.

### `ApiTrace`
| Field | Type | Notes |
|---|---|---|
| timestamp, hostname | `Long`, `String` | |
| reportId, model | `String?` | |
| category | `String?` | functional call-site tag. Internal-prompt calls use the `"<category>/<prompt>"` form (`"report/prompt"`, `"report/title"`, `"meta/Compare"`, `"after/rerank"`, `"translate/model_response"`, `"pricing/OpenRouter"`); other sites use free-text (`"Chat"`, `"Provider test"`) |
| runId | `String?` | UUID shared by every trace of one user-launched batch (fan-out / Fan-Meta / translation / model-test / report-gen); the L1 🐞 deep-links to it |
| request | `TraceRequest` | |
| response | `TraceResponse` | |
| partial | `Boolean` (default false) | true while a streaming response is still being read (written speculatively before EOF so a mid-stream kill still leaves a record); the EOF/close overwrite resets it |

### `TraceRequest`
| url, method | `String` |
| headers | `Map<String, String>` (auth headers / `?key=` query params redacted at write time) |
| body | `String?` (secrets in JSON body keys redacted) |

### `TraceResponse`
| statusCode | `Int` |
| headers | `Map<String, String>` |
| body | `String?` (captured body capped at 8 MiB; an in-progress streaming response reads `"[partial: stream in progress]"` until the EOF overwrite) |

### `TraceFileInfo`
| Field | Type |
|---|---|
| filename, hostname | `String` |
| reportId, model, category, runId | `String?` |
| timestamp | `Long` |
| statusCode | `Int` |
| partial | `Boolean` |

---

## Throttling (`com.ai.data` / declared in `ApiTracer.kt`)

### `NetworkSettings`
Live mirror of the user-tunable network knobs. Singleton so
OkHttp interceptors can read the current value without
threading a `Settings` reference through their constructors.
`AppViewModel` writes here on bootstrap and on every
`GeneralSettings` update.

| Field | Type | Notes |
|---|---|---|
| streamingReadTimeoutSec | `Int` (default = BuildConfig 240s) | SSE chat / report streams |
| nonStreamingReadTimeoutSec | `Int` (default = BuildConfig 120s) | analyze, meta, rerank, translate, model-list |
| maxCallsPerProviderPerMinute | `Int` (default **60**) | per-host sliding-window rate cap |
| maxConcurrentCallsPerProvider | `Int` (default **5**) | per-host concurrency cap |
| maxRetriesOn429 | `Int` (default 3) | in-line 429 retries |
| retryBackoffMs429 | `Long` (default 1000) | wait between retries |
| maxRetriesOn529 | `Int` (default 3) | in-line 529 (server overloaded) retries |
| retryBackoffMs529 | `Long` (default 1000) | wait between 529 retries |

### `ProviderThrottle`
Per-hostname rate + concurrency gate (in `ProviderThrottling.kt`). One
`Semaphore` (concurrency) + one `ConcurrentLinkedDeque<Long>`
(60 s sliding-window rate) per host. Caps are resolved per acquire from
per-provider override (`AppService.maxCallsPerProviderPerMinute` /
`maxConcurrentCallsPerProvider`, matched via `ProviderRegistry.findByHost`)
→ `NetworkSettings` global, each `coerceAtLeast(1)`. Three acquire paths:
- `acquire(host)` — **blocking** (`Thread.sleep`), used by the OkHttp
  `ProviderThrottleInterceptor`. Never the main thread.
- `tryAcquire(host)` — non-blocking → `Outcome.Acquired(Releaser)` /
  `Outcome.Blocked(availableAtMs)`.
- `acquireOrWait(host)` — suspend, polls `tryAcquire` + `delay`; used by
  `ApiDispatch.withHostGate` (never blocks a thread).

`permitPreAcquired: ThreadLocal<Boolean>` lets coroutine-side batch
flows (report, fan-out, Fan-Meta, translation) tell the inline
`ProviderThrottleInterceptor` to skip its own acquire so a permit isn't
double-counted. Propagated across coroutine dispatcher hops via
`asContextElement` and onto OkHttp workers via `TagPropagatingExecutor`.

### `ApiCallCaps` (declared in `ApiTracer.kt`)
A **separate** flow-level coroutine-`Semaphore` layer, independent of the
per-host `ProviderThrottle`. Six pools with defaults: `global` 100,
`report` 50, `translation` 50, `fanOut` 50, `fanMeta` 50, `workers` 50
(`workers` shares the `fanMeta` limit). Rebuilt at runtime via
`resetForNewLimits(...)` from the `GeneralSettings.maxConcurrent*`
knobs. The canonical batch acquisition order is **sub-cap → global →
per-host gate**; while parked on a saturated host gate the helper
releases both the sub-cap and `global` and re-takes them on the next
poll, so a flow's cap counts only items holding a live provider slot.

See [throttle.md](throttle.md) for the full chain.

---

## Logging (`com.ai.data.AppLog`)

### `LogLevel` (enum)
`DEBUG` (priority 3, also the home of the former `TRACE` calls),
`INFO` (4), `WARN` (5), `ERROR` (6), `OFF` (99 — sentinel that
disables the file appender; logcat still fires). The former `TRACE`
level is gone.

### `AppLogFileInfo`
One row of metadata for a log file under `<filesDir>/applog/`.
Cached in `AppLog.cachedFiles` so the list view doesn't restat
the directory on every navigation.

| Field | Type | Notes |
|---|---|---|
| filename | `String` | `applog_yyyyMMdd.log` |
| date | `String` | `yyyy-MM-dd` derived from filename |
| sizeBytes | `Long` |  |
| lastModified | `Long` |  |

The `AppLog` singleton itself exposes `threshold`,
`lastWriterError`, `droppedLineCount`, plus the `init` / `v` /
`d` / `i` / `w` / `e` / file-management API. See
[applog.md](applog.md).

---

## Per-provider field timestamps (`com.ai.data.ProviderFieldTimestamps`)

Per-provider, per-field "user-touched-at" timestamps.
Persisted in its own SharedPreferences entry
(`provider_field_timestamps`) as a JSON map
(`{ "OpenAI": { "baseUrl": 1715… }, … }`) so the `AppService`
serialization shape stays untouched.

Field names match `AppService` property names (e.g.
`"baseUrl"`, `"modelFilter"`). Timestamps are set by
`ProviderRegistry.update` when the new value differs from the
existing one — the user just edited that field via the Settings
UI. Asset-driven paths (`importFromAsset`, `upsertFromJson`,
`syncFromAsset`) don't bump.

The every-start sync uses these to decide which fields to
refresh from `assets/providers.json`:
- `timestamp == null` → field was never user-touched, refresh
- `timestamp != null` → user edited this field, leave alone

API: `get(providerId, field): Long?`, `bump(providerId,
fields, now)`, `clearAll()`, `clear(providerId)`.

---

## Share-target (`com.ai.data.SharedContent`)

### `SharedContent`
Snapshot of an `ACTION_SEND` / `ACTION_SEND_MULTIPLE` payload.

| Field | Type | Notes |
|---|---|---|
| text | `String?` | `EXTRA_TEXT` |
| subject | `String?` | `EXTRA_SUBJECT` |
| uris | `List<String>` | `EXTRA_STREAM` (single or multiple) as Uri strings |
| mime | `String?` | the intent's overall MIME type |

Computed:
- `isEmpty: Boolean`
- `isUrl: Boolean` — true when `text` is a single non-whitespace
  http(s) URL.

---

## ViewModel state (`com.ai.viewmodel`)

### `GeneralSettings`
| Field | Type | Notes |
|---|---|---|
| userName | `String` (default `"user"`) | |
| huggingFaceApiKey, openRouterApiKey, artificialAnalysisApiKey | `String` | |
| defaultEmail | `String` | |
| defaultTypePaths | `Map<String, String>` | global per-type API path defaults |
| loggingMasterEnabled | `Boolean` (default true) | grand-master gate for the whole Log/trace/audit/statistics page. When false the four diagnostic settings below are forced off at runtime (via the `effective*` helpers) regardless of their stored values |
| tracingEnabled | `Boolean` (default true) | master switch for `ApiTracer.isTracingEnabled`; gated by `loggingMasterEnabled` — consumed via `effectiveTracingEnabled()` |
| showLadybugIcons | `Boolean` (default true) | hides/shows trace hot-link icons without disabling trace capture |
| auditLogEnabled | `Boolean` (default **false**) | master switch for per-report audit-log writes; gated via `effectiveAuditLogEnabled()` |
| usageStatsEnabled | `Boolean` (default true) | accumulate per-provider/per-model usage stats on every API call; gated via `effectiveUsageStatsEnabled()` |
| fullScreen | `Boolean` (default **true**) | hides the Android status bar when enabled |
| modelNameLayout | `ModelNameLayout` | `MODEL_ONLY` (default) or `PROVIDER_AND_MODEL` |
| appHomeMode | `AppHomeMode` | `HOME_BAR` (default) shows the persistent top Home bar and makes Home open the latest report Manage screen or First launch; `HOME_SCREEN` keeps the classic large-card Home hub |
| uiCardBackgroundArgb, uiButtonBackgroundArgb | `Int` | legacy single-color mirrors for card/button customization |
| rankingWeights | `Map<String, Int>` (default empty) | 0–10 sliders from the "Ranking weights" screen. Key = `"rerank"` / `"judges"` / `"translations"` or a `TournamentMethod` name. Stored sparsely; a missing key resolves via `GeneralSettings.rankingWeight(key)` → `RANKING_WEIGHT_DEFAULTS` (`rerank`→3, `judges`→6, `translations`→6, `ELO`/`DAVIDSON`/`TIDEMAN`→4) else 0 |
| uiColorOverrides, uiColorOverridesDay | `Map<String, Int>` | ARGB overrides for functional `AppColors` roles (Night + Day variants); see [ui-customization.md](ui-customization.md) |
| uiColorMode | `UiColorMode` (default `NIGHT`) | which colour set is painted — `NIGHT` / `DAY` / `AUTO` (follow system day/night) |
| metadataEnabled | `Boolean` (default true) | grand-master switch for optional metadata generation |
| iconGenEnabled | `Boolean` (default true) | master switch for the per-report icon-gen feature. When true, every new report kicks off a background worker call (`workers/report-icon`) that generates a fitting emoji and writes it onto `Report.icon`. Surfaces in the result page, AI Reports hub, history rows, search hits, and report title bars. When false the call is skipped and existing on-disk icon values stay intact for re-enable |
| reportLanguageGenEnabled | `Boolean` (default true) | gates automatic report language + flag detection |
| reportTitleMode | `ReportTitleMode` (default `AI`) | `Manual` keeps the title input; `AI` asks a worker prompt to title the report |
| perModelIconGenEnabled | `Boolean` (default true) | master switch for per-model icons. When true, every successful agent call (initial generation AND regenerate) derives the model's icon from its title via the worker engine (`workers/model-icons`). Each agent's leftmost ✅ flips to the returned emoji once it lands. When false the step never runs automatically; per-agent rows keep their plain ✅. See [report-icons.md](report-icons.md) |
| perModelTitleGenEnabled | `Boolean` (default true) | gates automatic per-model response titles |
| useInternalPromptsIcons | `Boolean` (default true) | gates generated/cached icons for internal prompt rows |
| autostartItemsEnabled | `Boolean` (default false) | grand-master gate for every autostart item. When false nothing autostarts when a report finishes (auto Rerank / Moderation + Default meta items are skipped) and the per-item autostart settings are hidden |
| autostartFanMeta | `Boolean` (default true) | starts Fan Meta automatically after a clean Fan-out run |
| autoCreateRerankAndModeration | `Boolean` (default true) | creates default Rerank + Moderation rows after primary report generation when capable models exist |
| metadataIcons | `MetadataIcons` | Default-icons overrides used by view/navigation cards and fallback metadata glyphs |
| appWideSystemPromptId, reportModelSystemPromptId | `String?` | lowest fallback prompt ids; report-model defaults affect direct report models only |
| appWideParametersIds, reportModelParametersIds | `List<String>` | lowest fallback parameter preset ids |
| recentReportModels | `List<String>` (default empty) | last 3 (provider, model) pairs picked from the Report section's model pickers, most-recent first. Encoded as `"providerId|model"` strings; surfaces in the Report Select Models picker as a "Recent" section (honors the active provider / type / search filters) |
| streamingReadTimeoutSec | `Int` (default `BuildConfig.NETWORK_READ_TIMEOUT_SEC`) | read timeout applied to streaming API calls (SSE chat / report streams). Mirrored to `NetworkSettings.streamingReadTimeoutSec` so the per-call OkHttp interceptor reads the live value |
| nonStreamingReadTimeoutSec | `Int` (default `BuildConfig.NETWORK_NONSTREAMING_READ_TIMEOUT_SEC`) | read timeout applied to non-streaming calls (meta / rerank / translate / model-list / individual analyze). Much shorter than streaming by default so a hung provider can't gate a whole batch for 10 minutes |
| maxCallsPerProviderPerMinute | `Int` (default 60) | sliding-window rate cap per provider hostname. The OkHttp interceptor `ProviderThrottleInterceptor` reads this via `NetworkSettings.maxCallsPerProviderPerMinute`. See [throttle.md](throttle.md) |
| maxConcurrentCallsPerProvider | `Int` (default 5) | per-provider concurrency cap. Applies across every flow (report, meta, fan-out, chat, translate, model fetch) hitting the same provider host |
| maxConcurrentApiCalls | `Int` (default 100) | global hard ceiling for in-flight API calls (`ApiCallCaps.global`) — the only concurrency cap; every per-flow sub-cap is sized to this value |
| maxRetriesOn429 | `Int` (default 3) | maximum number of in-line retries the OkHttp client performs on a 429. 0 disables in-line retries entirely (the outer `withRetry` layer still gets a chance) |
| retryBackoffMs429 | `Long` (default 1000) | wait between 429 retry attempts in milliseconds |
| maxRetriesOn529 | `Int` (default 3) | maximum number of in-line retries the OkHttp client performs on a 529 (server overloaded). 0 disables in-line retries entirely. Independent of the 429 budget |
| retryBackoffMs529 | `Long` (default 1000) | wait between 529 retry attempts in milliseconds |
| typeABenchEnabled | `Boolean` (default true) | Type-A (fixed-model) batch bench-and-requeue: on a 429/529 the answerer/judge model is parked and its waiting same-model items move to Bench, then back to Queue when the bench lifts. Applies to Fan Out + Judge the judges. Mirrors to `ModelCooldownStore.typeABenchEnabled` |
| typeABenchSeconds | `Int` (default 10) | bench duration (seconds) when a 429/529 carries no Retry-After hint. Mirrors to `ModelCooldownStore.typeABenchBaseMs` (× 1000) |
| typeABenchMaxAttempts | `Int` (default 5) | consecutive benches one item gets before the batch leaves it errored. Mirrors to `ModelCooldownStore.typeABenchMaxAttempts` |
| logLevel | `LogLevel` (default `WARN`) | threshold for the in-app file logger ([applog.md](applog.md)). `DEBUG` / `INFO` / `WARN` / `ERROR` / `OFF` (no more `TRACE`). Persisted in main prefs; `AppLog.init` reads it directly so DEBUG calls inside bootstrap are admitted on cold start. Forced to `OFF` at runtime when `loggingMasterEnabled` is false (`effectiveLogLevel()`) |
| showKnowledgeCard | `Boolean` (default false) | shows AI Knowledge on the Hub when Experimental features are enabled |
| experimentalFeaturesEnabled | `Boolean` (default false) | gates Local models, Knowledge/RAG, and Local Semantic Search surfaces |
| pinnedDashboardCards, dashboardCardOrder | `Set<String>` / `List<String>` | persisted Live Dashboard layout |

> The chat-title / model-info / model-intro / translate-text / second-rerank / second-moderation / test-model prompt
> templates that used to live as `GeneralSettings` fields now live
> as `InternalPrompt` rows under `Settings.internalPrompts`
> (usually `category = "internal"`) and are CRUD'd via
> Settings → AI Setup → Prompt management.

### `ModelNameLayout` (enum)
`MODEL_ONLY`, `PROVIDER_AND_MODEL`. Provided to the composition
tree via `LocalModelNameLayout` in `AppNavHost`.

### `ReportTitleMode` (enum)
`Manual`, `AI`.

### `FetchModelsError`
| message | `String` |
| traceFile | `String?` (filename of the captured trace, if any) |

Surfaced inline on the model-picker UI with a 🐞 deep-link to the
captured trace.

### `UiState`
The single immutable bag the entire UI subscribes to. Defined in
`AppViewModelTypes.kt` (the top-level types — `GeneralSettings`,
`UiState`, `ModelNameLayout`, `ReportTitleMode`,
`PromptHistoryEntry`, `FetchModelsError`, `ExternalIntent`, the
`Refresh*` state types, and the `IconCandidate` /
`TitleCandidate` / `TranslationCandidate` sealed types — were
split out of `AppViewModel.kt`). See it for all 30+ fields.
Notable subset:

- `aiSettings: Settings`
- `generalSettings: GeneralSettings`
- `loadingModelsFor: Set<AppService>`
- `fetchModelsErrors: Map<String, FetchModelsError>`
- Report flow: `showGenericAgentSelection`, `showGenericReportsDialog`,
  `genericPromptTitle/Text`, `genericPromptTitleLong`, `currentReportId`,
  `genericReportsProgress/Total`, `pendingReportModels`, `editModeReportId`,
  `stagedReportModels`, `hasPendingPromptChange`, `hasPendingParametersChange`,
  `reportImageBase64`, `reportImageMime`, `reportWebSearchTool`,
  `reportReasoningEffort`, `reportUseReportModelsAsWorkers`,
  `reportAdvancedParameters`, `reportParametersIds`, `reportSystemPromptId`,
  `attachedKnowledgeBaseIds`
- Share-target staging: `chatStarterText: String?`,
  `chatStarterImageBase64/Mime: String?` (also fed by the AI Chat
  hub's "📸 Start with photo" entry), `pendingKnowledgeUris`,
  `pendingReportKnowledgeUris`
- `activeSecondaryBatches: Int` — count of in-flight secondary
  batches; the Meta button's hourglass / poll loop key off this
- `iconRefreshTick: Int` — incremented every time the icon-gen
  helper writes a new emoji onto a Report. Screens that render
  `Report.icon` key their disk-reload effect on this so a
  mid-flight resolution recomposes immediately rather than waiting
  for the next ON_RESUME refresh
- `externalIntent: ExternalIntent`
- Chat: `chatParameters: ChatParameters`,
  `dualChatConfig: DualChatConfig?`

Hot per-pair state lives **outside UiState**:
`AppViewModel.runningFanOutPairs: StateFlow<Set<String>>` carries
the 5–15 Hz updates from a Fan-out batch so consumers that don't
care don't recompose.

### `ExternalIntent`
Bundle of all 13 fields a launching intent (`com.ai.ACTION_NEW_REPORT`
or similar) can stuff into UiState.

| systemPrompt, closeHtml, reportType, email, nextAction, openHtml | `String?` |
| returnAfterNext, edit, select | `Boolean` |
| agentNames, flockNames, swarmNames, modelSpecs | `List<String>` |

### `PromptHistoryEntry`
| timestamp | `Long` |
| title, prompt | `String` |

---

## Provider definitions (`com.ai.data.ProviderDefinition`)

Wire format used by `assets/providers.json` and import/export, declared
in `ProviderRegistry.kt`. Mostly the same fields as `AppService`, except
`apiFormat` is a `String?` (default `"OPENAI_COMPATIBLE"`) parsed via
`ApiFormat.valueOf(...)` inside a try/catch that falls back to
`OPENAI_COMPATIBLE` on any invalid value. Translated to a runtime
`AppService` by `toAppService()`; the inverse is `fromAppService(s)`.
Custom providers added by the user round-trip as `ProviderDefinition`
JSON in the `provider_registry` prefs file.

`ProviderRegistry` is a mutable `object` that starts **empty** on a
fresh install — the 48 bundled providers are loaded on demand from
`assets/providers.json` via `importFromAsset` (append-only), not
hardcoded in Kotlin. `parseProvidersJson` filters out entries with a
null/blank id or baseUrl. A `hostIndex` (rebuilt on every `save()` from
`baseUrl` + `auxHosts`) backs `findByHost(host)`, which `ProviderThrottle`
uses to resolve a request hostname to its per-provider throttle
overrides. See [providers.md](providers.md) and
[repositories.md](repositories.md).
