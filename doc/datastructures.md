# Data Structures

All non-trivial data classes shipped by the app, grouped by domain.
Field types follow Kotlin notation; `?` marks nullable. Classes inside
`com.ai.data.ApiModels` (raw provider request/response shapes) are not
listed here — see the file directly.

---

## Settings & Configuration (`com.ai.model`)

### `Settings`
The top-level AI configuration object. Persisted in `eval_prefs`.

| Field | Type | Notes |
|---|---|---|
| providers | `Map<AppService, ProviderConfig>` | one entry per known provider |
| agents | `List<Agent>` | named model configurations |
| flocks | `List<Flock>` | groups of agents |
| swarms | `List<Swarm>` | groups of provider/model pairs |
| parameters | `List<Parameters>` | reusable parameter presets |
| systemPrompts | `List<SystemPrompt>` | reusable system prompts |
| internalPrompts | `List<InternalPrompt>` | user-managed Meta / Fan-out / Fan-in / Other internal templates |
| examplePrompts | `List<ExamplePrompt>` | starter (title, text) pairs surfaced in the New Report flow |
| endpoints | `Map<AppService, List<Endpoint>>` | per-provider endpoint URLs |
| providerStates | `Map<String, String>` | "ok" / "error" / "inactive" / "not-used" per provider id |
| modelTypeOverrides | `List<ModelTypeOverride>` | manual per-model type assignments |

### `ProviderConfig`
| Field | Type | Notes |
|---|---|---|
| apiKey | `String` | empty until user pastes one |
| model | `String` | default model selected for this provider |
| modelSource | `ModelSource` | `API` or `MANUAL` |
| models | `List<String>` | model ids (from API list or hardcoded fallback) |
| modelTypes | `Map<String, String>` | id → type ("chat", "embedding", "rerank", ...) |
| visionModels | `Set<String>` | user-flagged vision-capable ids |
| webSearchModels | `Set<String>` | user-flagged web-search-capable ids |
| reasoningModels | `Set<String>` | user-flagged reasoning-capable ids |
| modelCapabilities | `Map<String, ModelCapabilities>` | provider's own self-report |
| modelListRawJson | `String?` | raw `/models` response for future parsing |
| visionCapableComputed | `Set<String>` | precomputed layered lookup result |
| webSearchCapableComputed | `Set<String>` | precomputed layered lookup result |
| reasoningCapableComputed | `Set<String>` | precomputed layered lookup result |
| modelPricing | `Map<String, PricingCache.ModelPricing>` | precomputed per-model price |
| parametersIds | `List<String>` | default param presets |

> The legacy `adminUrl` and `modelListUrl` per-provider override
> fields have been dropped — admin-URL overrides now live as part
> of the bundled provider definition only.

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

### `Endpoint`
| id, name, url | `String` |
| isDefault | `Boolean` |

### `SystemPrompt`
| id, name, prompt | `String` |

### `InternalPrompt`
User-managed prompt template. Covers Meta-prompt launchers on the
Report Result screen (`category="meta"`), Fan-out / Fan-in
templates (`category="fan_out"` / `"fan_in"`), and the fixed
internal templates (`category="internal"`: intro, model_info,
translate, rerank, moderation).

| Field | Type | Notes |
|---|---|---|
| id | `String` (UUID) | |
| name | `String` | unique within (category, name) |
| reference | `Boolean` (default false) | when true on a meta entry, executor appends `[N] = Provider / Model` legend |
| category | `String` (default `"internal"`) | one of `meta`, `fan_out`, `fan_in`, `internal` |
| agent | `String` (default `"*select"`) | `"*select"` = ask the user; otherwise an `Agent.name` |
| text | `String` | template body. Top-level placeholders: `@QUESTION@`, `@RESULTS@`, `@COUNT@`, `@TITLE@`, `@DATE@`, `@RESPONSE@`, `@PROMPT@`, `@LANGUAGE@`, `@TEXT@`, `@FAN_OUT_COUNT@`, `@MODEL@`, `@PROVIDER@`. Iterable block: `***Report*** @REPORT@@RESPONSES@` (whitespace-tolerant; one expansion per source-report). Model-scoped fan-in (`category` of `initiator` / `requester` / `model`) adds `@INITIATOR@` (active model's own report response), `@RESPONDERS@` (block of fan-out responses where the active model is the source), `@RESPONDER_PAIRS@` (iterable list of `***Report*** {body} ***Response*** {body}` pairs where the active model is the answerer) |
| title | `String` (default empty) | one-line description shown alongside `name` on Fan out and the prompt-edit screen |
| scope | `String` (default `"Default"`) | meta-prompt + fan-out launch scope. `"Default"` runs against every report agent / every present language with no extra picker step; `"Select"` routes the user through `SecondaryScopeScreen` first so they can pick a subset / top-N / language fan-out. Other categories carry the value verbatim so round-tripping through `prompts.json` and the export bundle is lossless |

> The legacy `type` field is gone — routing is now derived from
> `category`. `category="meta"` plus a `name` matching `"rerank"`,
> `"moderation"`, etc. drives `metaTypeToKind` resolution at
> runtime. Categories `initiator` / `requester` / `model` drive
> the model-scoped fan-in flow (`resolveModelFanInPrompt` produces
> their text; rows carry `scopeProviderId` / `scopeModel`).

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
A per-(provider, model, kind) aggregate.

| Field | Type | Notes |
|---|---|---|
| provider | `AppService` | |
| model | `String` | |
| callCount | `Int` | |
| inputTokens | `Long` | |
| outputTokens | `Long` | |
| kind | `String` | `report`, `rerank`, `meta`, `moderate`, or `translate`. (Fan-out rows tag as `meta` since they share the META kind; the Type column reads the prompt name on display.) |

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

---

## Reports (`com.ai.data`)

### `Report`
| Field | Type |
|---|---|
| id | `String` (UUID) |
| timestamp, completedAt | `Long`, `Long?` |
| title, prompt | `String` |
| agents | `MutableList<ReportAgent>` |
| totalCost | `Double` |
| rapportText, closeText | `String?` |
| reportType | `ReportType` (`CLASSIC` or `TABLE`) |
| imageBase64, imageMime | `String?` (vision attachments — downscaled + JPEG-encoded before storage) |
| webSearchTool | `Boolean` (per-report 🌐 toggle) |
| reasoningEffort | `String?` (per-report 🧠 hint) |
| sourceReportId | `String?` (set when this report is a translated copy of another) |
| knowledgeBaseIds | `List<String>` (KBs attached to this report) |
| pinned | `Boolean` (user-pinned, surfaces on the Reports hub above Recent) |
| costsFromDeletedItems | `Double` (sum of input+output cost of every deleted row — agent / secondary / fan-out / fan-in / translation. Surfaced as its own line above Total when non-zero so the user sees what the API actually billed even after trimming visible rows) |
| icon | `String?` (per-report emoji, set by `kickOffIconGeneration` on report start. Null while running, on call failure, or on legacy reports created before the feature shipped. See [report-icons.md](report-icons.md)) |
| iconErrorMessage | `String?` (failure reason from the icon-gen call; set instead of `icon` when the LLM returned an error or empty body) |
| iconInputTokens, iconOutputTokens | `Int` |
| iconInputCost, iconOutputCost | `Double` |
| iconModel | `String?` (set when the current icon was picked manually via Find alternative icons, stored as `"<providerId>/<modelId>"`) |
| iconCalls | `MutableList<IconCallRecord>` (per-call audit log for the 3-tier per-agent chain — every attempt, including failed earlier tiers) |

### `ReportAgent`
| Field | Type |
|---|---|
| agentId, agentName, provider, model | `String` |
| reportStatus | `ReportStatus` (`PENDING`, `RUNNING`, `SUCCESS`, `ERROR`, `STOPPED`) |
| httpStatus | `Int?` |
| requestHeaders, requestBody, responseHeaders, responseBody | `String?` |
| errorMessage | `String?` |
| tokenUsage | `TokenUsage?` |
| cost, durationMs | `Double?`, `Long?` |
| citations | `List<String>?` |
| searchResults | `List<SearchResult>?` |
| relatedQuestions | `List<String>?` |
| rawUsageJson | `String?` |
| icon | `String?` (per-agent emoji produced by `runReportIconsForAgent`. Null until the chain runs; null too on failure — see `iconErrorMessage`) |
| iconErrorMessage | `String?` |
| iconInputTokens, iconOutputTokens | `Int` |
| iconInputCost, iconOutputCost | `Double` (cumulative cost across every tier attempt) |
| iconWinningTier | `Int?` (1 = chat continuation, 2 = one-shot `internal/report_icon`, 3 = fixed-agent fallback against `internal/report_icon_3th`. Null when no tier succeeded and the icon is the 📝 fallback, or when the icon was manually picked via Find alternative icons) |

### `IconCallRecord`
One captured API call from the 3-tier per-agent icon chain
([report-icons.md](report-icons.md)). Stored on
`Report.iconCalls` so the per-call All-tab in the cost export
renders every attempt — including failed earlier tiers the
chain skipped past.

| Field | Type |
|---|---|
| agentId | `String` |
| tier | `Int` (1 / 2 / 3) |
| provider, model, pricingTier | `String` |
| inputTokens, outputTokens | `Int` |
| inputCost, outputCost | `Double` |
| durationMs | `Long?` |
| success | `Boolean` |
| timestamp | `Long` |

### `SecondaryResult`
Meta-result tied to a parent report — rerank, chat-type Meta
(driven by the user's Meta-prompt CRUD entries), moderation,
translation, fan-out per-pair row, or fan-in combined-report row.

| Field | Type | Notes |
|---|---|---|
| id | `String` (UUID) | |
| reportId | `String` | |
| kind | `SecondaryKind` | `RERANK`, `META`, `MODERATION`, `TRANSLATE` |
| providerId, model, agentName | `String` | |
| timestamp | `Long` | |
| content | `String?` | the model output (chat-type META rows whose Meta prompt has `reference=true` get a deterministic `## References` legend appended at storage time) |
| errorMessage | `String?` | |
| tokenUsage | `TokenUsage?` | |
| inputCost, outputCost | `Double?` | |
| durationMs | `Long?` | |
| metaPromptId | `String?` | id of the `InternalPrompt` (`category="meta"` / `"fan_out"` / `"fan_in"`) that produced this row |
| metaPromptName | `String?` | display name of the prompt copied at run time. Drives every UI bucket / export section / cost-row label so renaming or deleting the prompt later doesn't reshape old rows |
| fanOutSourceAgentId | `String?` | Fan-out per-pair rows: agentId of the report-model whose response was substituted into the prompt's `@RESPONSE@` slot. Together with this row's own `(providerId, model)` (the answerer) it forms the (answerer, source) pair the fan-out drill-in keys on |
| fanInOf | `String?` | Fan-in combined-report rows: id of the `InternalPrompt` that produced this combined output. Lets the fan-out detail screen distinguish the single combined output from the per-pair response rows |
| secondaryScope | `String?` | Encoded `SecondaryScope` used when this row was originally produced — `"ALL"` / `"TOP:<rerankResultId>:<count>"` / `"MANUAL:<agentId>,..."`. Cascade-on-prompt-change re-runs at the same scope |
| translateSourceTargetId | `String?` | TRANSLATE only — id of the item translated (`"prompt"`, `agent.agentId`, or a secondary `id`) |
| translateSourceKind | `String?` | TRANSLATE only — `"PROMPT"`, `"AGENT"`, `"META"` |
| targetLanguage | `String?` | TRANSLATE only — English language name (e.g. `"Dutch"`) |
| targetLanguageNative | `String?` | TRANSLATE only — native rendering (e.g. `"Nederlands"`) |
| translationRunId | `String?` | TRANSLATE only — UUID shared by every row of one Translate batch so the result page can group them |
| translatedFromSecondaryId | `String?` | Legacy field from the old "translation creates a copy" flow — preserved on disk so old reports still load |
| scopeProviderId, scopeModel | `String?` | Set on a model-scoped fan-in row (Internal Prompt categories `initiator` / `requester` / `model`). Identifies which (provider, model) pair the L2 page should surface this row under so the per-model drill-in can filter to its own. Null on every other row including the legacy "total" `fan_in` (which combines the whole report and shows on L1's combinedRows section) |

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

### `SecondaryRunState`
In-progress UI state surfaced on the Report screen.

| reportId, kind, total, completed |

### `TokenUsage`
| inputTokens, outputTokens, totalTokens | `Int` |
| cachedTokens | `Int?` |

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

## Knowledge / RAG (`com.ai.data.Knowledge`)

### `KnowledgeBase`
A named collection of indexed documents.

| Field | Type |
|---|---|
| id | `String` (UUID) |
| name | `String` |
| embedderProviderId | `String` (`"Local"` for on-device, otherwise `AppService.id`) |
| embedderModel | `String` |
| embeddingDim | `Int` |
| createdAt | `Long` |
| sources | `List<KnowledgeSource>` |

Computed:
- `totalChunks: Int` — `sources.sumOf { it.chunkCount }`
- `totalChars: Long` — `sources.sumOf { it.charCount.toLong() }`

### `KnowledgeSource`
A single ingested document inside a KB.

| Field | Type |
|---|---|
| id | `String` (UUID) |
| type | `KnowledgeSourceType` |
| name | `String` (display label — file name without extension or URL host) |
| origin | `String` (SAF Uri for files; URL for web pages — used to re-index) |
| addedAt | `Long` |
| chunkCount | `Int` |
| charCount | `Int` |
| errorMessage | `String?` (set when the most recent index attempt failed) |

### `KnowledgeChunk`
| id | `String` (UUID) |
| sourceId | `String` |
| ordinal | `Int` |
| text | `String` |
| embedding | `FloatArray` (primitive — boxed `List<Double>` was ~6× heavier per dim; JSON storage on disk is unchanged because Gson serialises both to the same array of numbers, so existing chunk files keep working) |

`equals` / `hashCode` are overridden so the `FloatArray` field
compares by content, not identity — required for chunk-list
deduplication and assertion equality.

### `KnowledgeSourceType` (enum)
`TEXT`, `MARKDOWN`, `PDF`, `DOCX`, `ODT`, `XLSX`, `ODS`, `CSV`,
`IMAGE`, `URL`.

---

## Local Runtime (`com.ai.data.LocalLlm`, `com.ai.data.LocalEmbedder`)

### `LocalLlm.RecommendedLlm`
Hand-off link shown on the AI Setup → Local Models → Local LLMs card.

| name | `String` |
| url | `String` |
| sizeHint | `String` |
| description | `String` |

### `LocalEmbedder.DownloadableModel`
A `.tflite` text embedder the app can download directly into
`<filesDir>/local_models/`.

| name | `String` (filename stem) |
| displayName | `String` |
| url | `String` |
| sizeMbHint | `Int` |
| description | `String` |

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
| adaptiveThinkingPatterns | `List<ModelPattern>` | opts in to Anthropic's adaptive-thinking shape (claude-opus-4.6 / 4.7) |
| maxTokensDefaults | `List<MaxTokensRule>` | per-family default `max_tokens` (Anthropic). First match wins, default 4096 |
| builtInEndpoints | `List<Endpoint>` | bundled alternate endpoints (DeepSeek main + reasoner; Mistral chat + Codestral; Z.AI mainland + international). User can pick between them on the provider edit screen |
| maxCallsPerProviderPerMinute | `Int?` | per-provider override for `GeneralSettings.maxCallsPerProviderPerMinute`. Null → inherit. Read by `ProviderThrottle.acquire` when this provider's hostname matches. See [throttle.md](throttle.md) |
| maxConcurrentCallsPerProvider | `Int?` | per-provider override for the concurrency cap. Null → inherit |
| maxRetriesOn429 | `Int?` | per-provider override for the 429-retry cap (0 = disable in-line retries). Null → inherit |
| retryBackoffMs429 | `Long?` | per-provider override for the wait between 429 retries. Null → inherit |
| maxRetriesOn529 | `Int?` | per-provider override for the 529 retry cap (0 = disable in-line retries). Null → inherit |
| retryBackoffMs529 | `Long?` | per-provider override for the wait between 529 retries. Null → inherit. Seeded to 5000 ms for Anthropic |

There is also a synthetic singleton `AppService.LOCAL` (`id =
"Local"`, `baseUrl = "local://"`) **not** in `ProviderRegistry`.
It surfaces only via `findById("Local")` (case-insensitive — the
legacy `"LOCAL"` string in persisted data still resolves) and routes
chat / report / RAG / Fan-out calls through `LocalLlm` /
`LocalEmbedder`.

#### `ModelPattern`
Shared by every `*Patterns` field on `AppService`. Wire format is a
JSON object with one or more of three string fields (matched against
the model id, lowercased): `prefix`, `contains`, `regex`. The first
non-null field wins; an empty pattern matches nothing. `anyMatches`
walks the list and returns true on the first hit.

#### `MaxTokensRule`
Per-family override for Anthropic's required `max_tokens`. Wire
format is `{ "match": <ModelPattern>, "value": <Int> }`. Used when
the user hasn't pinned an explicit `max_tokens` on the agent's
parameters.

#### `Endpoint`
Bundled alternate endpoint. See `Endpoint` under "Settings &
Configuration" — same shape, just preloaded from `providers.json`
instead of created by the user.

### `ApiFormat` (enum)
`OPENAI_COMPATIBLE`, `ANTHROPIC`, `GOOGLE`. All cloud dispatch keys
off this — provider identity is never used for routing. The on-device
runtime dispatches off `provider.id == "Local"` instead.

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
| model | `String` |
| promptPrice | `Double` (per token) |
| completionPrice | `Double` (per token) |
| source | `String` (`LITELLM`, `MODELSDEV`, `OVERRIDE`, `OPENROUTER`, `HELICONE`, `LLMPRICES`, `ARTIFICIAL_ANALYSIS`, `DEFAULT`) |

### `PricingCache.TierBreakdown`
Per-(provider, model) snapshot showing every tier's view, used by the
Costs page and the layered-costs CSV export.

| litellm, modelsDev, helicone, llmPrices, artificialAnalysis, override, openrouter | `ModelPricing?` |
| default | `ModelPricing` |

---

## Chat (`com.ai.data`)

### `ChatMessage`
| role | `String` (`user`, `assistant`, `system`) |
| content | `String` |
| imageBase64, imageMime | `String?` (vision attachment as base64; JPEG-encoded after downscale) |
| timestamp | `Long` |

### `ChatSession`
| id | `String` |
| provider | `AppService` |
| model | `String` |
| messages | `List<ChatMessage>` |
| parameters | `ChatParameters` |
| createdAt, updatedAt | `Long` |
| pinned | `Boolean` (surfaces above Recent on the AI Chat hub) |
| knowledgeBaseIds | `List<String>` (KBs attached to this chat) |
| title | `String` (default empty) | Display title. Seeded with the first 10 words of the first user message on send; replaced asynchronously by the bundled `internal/chat_title` prompt against DeepSeek after the first assistant response. Blank for sessions saved before this field existed — display sites fall back to `preview` (first user message, first 50 chars) |

Computed:
- `preview: String` — first user message, truncated to 50 chars.

### `ChatParameters`
Per-chat overrides — same shape as `Parameters` minus id/name, plus:
- `reasoningEffort: String?` — set per-turn from the chat session
  screen's 🧠 pulldown. Clamped to the active model's supported
  range on session resume.

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

### `ApiTrace`
| timestamp, hostname | `Long`, `String` (`"local"` for on-device LLM / embedder traces) |
| reportId | `String?` |
| model | `String?` |
| request | `TraceRequest` |
| response | `TraceResponse` |

### `TraceRequest`
| url, method | `String` |
| headers | `Map<String, String>` (auth headers redacted at write time) |
| body | `String?` |

### `TraceResponse`
| statusCode | `Int` |
| headers | `Map<String, String>` |
| body | `String?` (capped at 8 MiB; streaming responses note "[streaming response - not captured]") |

### `TraceFileInfo`
| filename, hostname, reportId, model | `String` / `String?` |
| timestamp | `Long` |
| statusCode | `Int` |
| category | `String?` (e.g. `"Report meta: Compare"`, `"Translation"`) |

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
| streamingReadTimeoutSec | `Int` (default = BuildConfig) | SSE chat / report streams |
| nonStreamingReadTimeoutSec | `Int` (default = BuildConfig) | analyze, meta, rerank, translate, model-list |
| maxCallsPerProviderPerMinute | `Int` (default 30) | per-host sliding-window rate cap |
| maxConcurrentCallsPerProvider | `Int` (default 3) | per-host concurrency cap |
| maxRetriesOn429 | `Int` (default 3) | in-line 429 retries |
| retryBackoffMs429 | `Long` (default 1000) | wait between retries |
| maxRetriesOn529 | `Int` (default 3) | in-line 529 (server overloaded) retries |
| retryBackoffMs529 | `Long` (default 1000) | wait between 529 retries |

### `ProviderThrottle`
Per-hostname rate + concurrency gate. One `Semaphore` + one
`ConcurrentLinkedDeque<Long>` per host. `acquire(host)` is
synchronous (blocks an OkHttp dispatcher worker, never the main
thread) and returns a `Releaser` that must be called in
`finally` to avoid leaking the permit. Caps are resolved per
acquire from per-provider override → `NetworkSettings` global.

`permitPreAcquired: ThreadLocal<Boolean>` lets coroutine-side
flows (Fan-out, report-icon chain, alternative-icons fan-out)
tell the inline `ProviderThrottleInterceptor` to skip its own
acquire. Propagated across coroutine dispatcher hops via
`asContextElement(true)` and onto OkHttp workers via
`TagPropagatingExecutor`.

See [throttle.md](throttle.md) for the full chain.

---

## Logging (`com.ai.data.AppLog`)

### `LogLevel` (enum)
`TRACE` (priority 2 = `Log.VERBOSE`), `DEBUG` (3), `INFO` (4),
`WARN` (5), `ERROR` (6), `OFF` (99 — sentinel that disables the
file appender; logcat still fires).

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
| tracingEnabled | `Boolean` (default true) | master switch for `ApiTracer.isTracingEnabled` |
| modelNameLayout | `ModelNameLayout` | `MODEL_ONLY` (default) or `PROVIDER_AND_MODEL` |
| showBackButton | `Boolean` (default true) | when false hides the visible `< Back` button on every TitleBar; system back still works |
| subjectToTitleBarMode | `SubjectToTitleBarMode` (default `BOTH`) | tri-state: `HARDCODED` keeps the legacy fixed label + green sub-header; `SUBJECT` folds the dynamic subject into the title bar and drops the green line; `BOTH` joins them with `/` and drops the green line (gracefully falls back to the title when the subject is blank) |
| iconBarAtBottom | `Boolean` (default true) | when true the action icons + back arrow live in a bar pinned at the bottom of the screen and the top bar shows only the title. Bar lives at AppNavHost scope so it survives nav transitions |
| iconGenEnabled | `Boolean` (default true) | master switch for the per-report icon-gen feature. When true, every new report kicks off a background LLM call (the bundled `internal/icon` prompt against its pinned agent) that generates a fitting emoji and writes it onto `Report.icon`. Surfaces in the result page, AI Reports hub, history rows, search hits, and the title bar's leftmost icon. When false the call is skipped, the icon row is hidden, the leftmost title-bar icon and its mirrored 📝 memo are hidden, and per-row icons fall back to the static 🕘 / 📌. Persisted icon / iconCost values on existing reports stay on disk — re-enabling brings them back |
| perModelIconGenEnabled | `Boolean` (default true) | master switch for the per-agent 3-tier icon chain. When true, every successful agent call (initial generation AND regenerate) auto-fires `runReportIconsForAgent` on `appViewModel.viewModelScope`. Each agent's leftmost ✅ flips to a returned emoji once the chain finishes. When false the chain never runs automatically; per-agent rows keep their plain ✅. See [report-icons.md](report-icons.md) |
| recentReportModels | `List<String>` (default empty) | last 3 (provider, model) pairs picked from the Report section's model pickers, most-recent first. Encoded as `"providerId|model"` strings; surfaces in the Report Select Models picker as a "Recent" section (honors the active provider / type / search filters) |
| streamingReadTimeoutSec | `Int` (default `BuildConfig.NETWORK_READ_TIMEOUT_SEC`) | read timeout applied to streaming API calls (SSE chat / report streams). Mirrored to `NetworkSettings.streamingReadTimeoutSec` so the per-call OkHttp interceptor reads the live value |
| nonStreamingReadTimeoutSec | `Int` (default `BuildConfig.NETWORK_NONSTREAMING_READ_TIMEOUT_SEC`) | read timeout applied to non-streaming calls (meta / rerank / translate / model-list / individual analyze). Much shorter than streaming by default so a hung provider can't gate a whole batch for 10 minutes |
| maxCallsPerProviderPerMinute | `Int` (default 30) | sliding-window rate cap per provider hostname. The OkHttp interceptor `ProviderThrottleInterceptor` reads this via `NetworkSettings.maxCallsPerProviderPerMinute`. See [throttle.md](throttle.md) |
| maxConcurrentCallsPerProvider | `Int` (default 3) | per-provider concurrency cap. Replaces the prior hardcoded fan-out semaphore — applies globally across every flow (report, meta, fan-out, chat, translate, model fetch) hitting the same provider host |
| maxRetriesOn429 | `Int` (default 3) | maximum number of in-line retries the OkHttp client performs on a 429. 0 disables in-line retries entirely (the outer `withRetry` layer still gets a chance) |
| retryBackoffMs429 | `Long` (default 1000) | wait between 429 retry attempts in milliseconds |
| maxRetriesOn529 | `Int` (default 3) | maximum number of in-line retries the OkHttp client performs on a 529 (server overloaded). 0 disables in-line retries entirely. Independent of the 429 budget |
| retryBackoffMs529 | `Long` (default 1000) | wait between 529 retry attempts in milliseconds |
| logLevel | `LogLevel` (default `INFO`) | threshold for the in-app file logger ([applog.md](applog.md)). `TRACE` / `DEBUG` / `INFO` / `WARN` / `ERROR` / `OFF`. Persisted in main prefs; `AppLog.init` reads it directly so DEBUG calls inside bootstrap are admitted on cold start |
| showKnowledgeCard | `Boolean` (default false) | gates the AI Knowledge card on the home Hub. Default off keeps the Hub approachable on a fresh install; the Knowledge subsystem itself stays fully functional whether or not the card is visible |

> The intro / model_info / translate / rerank / moderation prompt
> templates that used to live as `GeneralSettings` fields now live
> as `InternalPrompt` rows under `Settings.internalPrompts`
> (category = `"internal"` for the fixed five) and are CRUD'd via
> Settings → AI Setup → Prompt management.

### `ModelNameLayout` (enum)
`MODEL_ONLY`, `PROVIDER_AND_MODEL`. Provided to the composition
tree via `LocalModelNameLayout` in `AppNavHost`.

### `SubjectToTitleBarMode` (enum)
`HARDCODED`, `SUBJECT`, `BOTH`. See `GeneralSettings`. Provided to
the composition tree via `LocalSubjectToTitleBarMode`.

### `FetchModelsError`
| message | `String` |
| traceFile | `String?` (filename of the captured trace, if any) |

Surfaced inline on the model-picker UI with a 🐞 deep-link to the
captured trace.

### `UiState`
The single immutable bag the entire UI subscribes to. See
`AppViewModel.kt` for all 30+ fields. Notable subset:

- `aiSettings: Settings`
- `generalSettings: GeneralSettings`
- `loadingModelsFor: Set<AppService>`
- `fetchModelsErrors: Map<String, FetchModelsError>`
- Report flow: `showGenericAgentSelection`, `showGenericReportsDialog`,
  `genericPromptTitle/Text`, `currentReportId`, `genericReportsProgress/Total`,
  `pendingReportModels`, `editModeReportId`, `stagedReportModels`,
  `hasPendingPromptChange`, `hasPendingParametersChange`,
  `reportImageBase64`, `reportImageMime`, `reportWebSearchTool`,
  `reportReasoningEffort`, `reportAdvancedParameters`,
  `attachedKnowledgeBaseIds`
- Share-target staging: `chatStarterText: String?`,
  `chatStarterImageBase64/Mime: String?` (also fed by the AI Chat
  hub's "📸 Start with photo" entry),
  `pendingKnowledgeUris: List<String>`,
  `pendingReportKnowledgeUris: List<String>`
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

Wire format used by `assets/providers.json` and import/export. Same
fields as `AppService`. Translated into a runtime `AppService` by
`ProviderDefinition.toAppService()`. Custom providers added by the
user are persisted as `ProviderDefinition` JSON in the
`provider_registry` prefs file.
