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
| endpoints | `Map<AppService, List<Endpoint>>` | per-provider endpoint URLs |
| providerStates | `Map<String, String>` | "ok" / "error" / "inactive" / "not-used" per provider id |
| modelTypeOverrides | `List<ModelTypeOverride>` | manual per-model type assignments |

> **Note:** the older `prompts: List<Prompt>` field ("Internal
> Prompts") has been removed. Intro / model-info / translate
> templates now live on `GeneralSettings`.

### `ProviderConfig`
| Field | Type | Notes |
|---|---|---|
| apiKey | `String` | empty until user pastes one |
| model | `String` | default model selected for this provider |
| modelSource | `ModelSource` | `API` or `MANUAL` |
| models | `List<String>` | model ids (from API list or hardcoded fallback) |
| modelTypes | `Map<String, String>` | id → type ("chat", "embedding", "rerank", ...) |
| visionModels | `Set<String>` | user-flagged vision-capable ids |
| modelCapabilities | `Map<String, ModelCapabilities>` | provider's own self-report |
| modelListRawJson | `String?` | raw `/models` response for future parsing |
| webSearchModels | `Set<String>` | user-flagged web-search-capable ids |
| visionCapableComputed | `Set<String>` | precomputed layered lookup result |
| webSearchCapableComputed | `Set<String>` | precomputed layered lookup result |
| modelPricing | `Map<String, PricingCache.ModelPricing>` | precomputed per-model price |
| adminUrl | `String` | provider's admin/console URL |
| modelListUrl | `String` | optional override for the /models URL |
| parametersIds | `List<String>` | default param presets |

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
| returnCitations | `Boolean` |
| searchRecency | `String?` |
| webSearchTool | `Boolean` |
| reasoningEffort | `String?` (`"low"`, `"medium"`, `"high"`, or null) |

### `Endpoint`
| id, name, url | `String` |
| isDefault | `Boolean` |

### `SystemPrompt`
| id, name, prompt | `String` |

### `ModelTypeOverride`
| Field | Type |
|---|---|
| id, providerId, modelId, type | `String` |
| supportsVision | `Boolean` |
| supportsWebSearch | `Boolean` |

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
| kind | `String` | `report`, `rerank`, `summarize`, `compare`, `moderate`, or `translate` |

### `ModelCapabilities`
| supportsVision | `Boolean?` |
| supportsFunctionCalling | `Boolean?` |
| contextLength | `Int?` |
| maxOutputTokens | `Int?` |

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
| imageBase64, imageMime | `String?` (vision attachments) |
| webSearchTool | `Boolean` (per-report 🌐 toggle) |
| reasoningEffort | `String?` (per-report 🧠 hint) |
| sourceReportId | `String?` (set when this report is a translated copy of another) |
| knowledgeBaseIds | `List<String>` (KBs attached to this report) |
| pinned | `Boolean` (user-pinned, surfaces on the Reports hub above Recent) |

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

### `SecondaryResult`
Rerank / Summarize / Compare / Moderate / Translate meta-result tied
to a parent report.

| Field | Type | Notes |
|---|---|---|
| id | `String` (UUID) | |
| reportId | `String` | |
| kind | `SecondaryKind` | `RERANK`, `SUMMARIZE`, `COMPARE`, `MODERATION`, `TRANSLATE` |
| providerId, model, agentName | `String` | |
| timestamp | `Long` | |
| content | `String?` | the model output (Compare's content has a deterministic `## References` legend appended at storage time) |
| errorMessage | `String?` | |
| tokenUsage | `TokenUsage?` | |
| inputCost, outputCost | `Double?` | |
| durationMs | `Long?` | |
| translateSourceTargetId | `String?` | TRANSLATE only — id of the item translated (`"prompt"`, `agent.agentId`, or a secondary `id`) |
| translateSourceKind | `String?` | TRANSLATE only — `"PROMPT"`, `"AGENT"`, `"SUMMARY"`, `"COMPARE"` |
| targetLanguage | `String?` | TRANSLATE only — English language name (e.g. `"Dutch"`) |
| targetLanguageNative | `String?` | TRANSLATE only — native rendering (e.g. `"Nederlands"`) |
| translationRunId | `String?` | TRANSLATE only — UUID shared by every row of one Translate batch so the result page can group them |
| translatedFromSecondaryId | `String?` | Legacy field from the old "translation creates a copy" flow — preserved on disk so old reports still load |

### `SecondaryScope` (sealed)
- `AllReports` — every successful agent feeds the meta-result.
- `TopRanked(count: Int, rerankResultId: String)` — input narrowed
  to the top-N entries of a chosen rerank.
- `Manual(agentIds: Set<String>)` — explicit list of agent ids the
  user picked from the existing report; only those rows feed in.

### `SecondaryLanguageScope` (sealed)
For Summarize / Compare / Translate fan-out across translated
content present on the report.

- `AllPresent` — fan out across every language present.
- `Selected(languages: Set<String>)` — restrict to the chosen
  English-name languages.

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
| embedderProviderId | `String` (`"LOCAL"` for on-device, otherwise `AppService.id`) |
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

### `KnowledgeSourceType` (enum)
`TEXT`, `MARKDOWN`, `PDF`, `DOCX`, `ODT`, `XLSX`, `ODS`, `CSV`,
`IMAGE`, `URL`.

---

## Local Runtime (`com.ai.data.LocalLlm`, `com.ai.data.LocalEmbedder`)

### `LocalLlm.RecommendedLlm`
Hand-off link shown on the Housekeeping → Local LLMs card.

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
A registered provider (loaded from `setup.json` + custom additions).
See [providers.md](providers.md) for the full per-provider table.

| Field | Type |
|---|---|
| id, displayName, baseUrl, adminUrl, defaultModel | `String` |
| openRouterName | `String?` |
| apiFormat | `ApiFormat` (`OPENAI_COMPATIBLE`, `ANTHROPIC`, `GOOGLE`) |
| typePaths | `Map<String, String>` |
| modelsPath | `String?` |
| prefsKey | `String` |
| seedFieldName | `String` |
| supportsCitations, supportsSearchRecency, extractApiCost | `Boolean` |
| costTicksDivisor | `Double?` |
| modelListFormat | `String` (`"object"` or `"array"`) |
| modelFilter | `String?` (regex) |
| litellmPrefix | `String?` |
| hardcodedModels | `List<String>?` |
| defaultModelSource | `String?` |
| endpointRules | `List<EndpointRule>` |

There is also a synthetic singleton `AppService.LOCAL` (`id =
"LOCAL"`, `displayName = "Local"`, `baseUrl = "local://"`) **not**
in `ProviderRegistry`. It surfaces only via `findById("LOCAL")` and
routes chat / report / RAG calls through `LocalLlm` /
`LocalEmbedder`.

### `EndpointRule`
| modelPrefix, endpointType | `String` |

### `ApiFormat` (enum)
`OPENAI_COMPATIBLE`, `ANTHROPIC`, `GOOGLE`. All cloud dispatch keys
off this — provider identity is never used for routing. The on-device
runtime dispatches off `provider.id == "LOCAL"` instead.

### `ModelType` (constants)
`CHAT`, `RESPONSES`, `EMBEDDING`, `RERANK`, `IMAGE`, `TTS`, `STT`,
`MODERATION`, `CLASSIFY`, `UNKNOWN`.

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
| imageBase64, imageMime | `String?` (vision attachment as base64) |
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

Computed:
- `preview: String` — first user message, truncated to 50 chars.

### `ChatParameters`
Per-chat overrides — same shape as `Parameters` minus id/name, plus:
- `reasoningEffort: String?` — set per-turn from the chat session
  screen's 🧠 pulldown.

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
| headers | `Map<String, String>` |
| body | `String?` |

### `TraceResponse`
| statusCode | `Int` |
| headers | `Map<String, String>` |
| body | `String?` |

### `TraceFileInfo`
| filename, hostname, reportId, model | `String` / `String?` |
| timestamp | `Long` |
| statusCode | `Int` |

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

## Export / Import (`com.ai.model.ExportModels`)

### `ConfigExport` (current version `21`)
- `version: Int`
- `providers: Map<String, ProviderConfigExport>`
- `agents: List<AgentExport>`
- `flocks, swarms, parameters, systemPrompts, aiPrompts: List<...>?`
  (the latter is a legacy preserved field for old exports — Internal
  Prompts have been removed)
- `huggingFaceApiKey, openRouterApiKey, artificialAnalysisApiKey: String?`
- `manualPricing: List<ManualPricingExport>?`
- `providerEndpoints: List<ProviderEndpointsExport>?`
- `providerDefinitions: List<ProviderDefinition>?`
- `providerStates: Map<String, String>?`
- `modelTypeOverrides: List<ModelTypeOverride>?`
- `defaultTypePaths: Map<String, String>?`
- `rerankPrompt, summarizePrompt, comparePrompt: String?`
- `introPrompt, modelInfoPrompt, translatePrompt: String?`

### `ConfigImportResult`
Mirror of the above — what `processImportedConfig` returns to the
caller.

### `ApiKeysExport`
Standalone API-keys export (separate from full config).
- `type = "api_keys"`
- `keys: List<ApiKeyEntry(service, apiKey)>`
- `huggingFaceApiKey, openRouterApiKey, artificialAnalysisApiKey: String?`

### `AgentExport`, `FlockExport`, `SwarmExport`, `SwarmMemberExport`, `ParametersExport`, `SystemPromptExport`, `EndpointExport`, `ProviderEndpointsExport`, `ManualPricingExport`, `ProviderConfigExport`
Wire-format twins of the runtime classes — same fields stripped of
any runtime-only state. See `ExportModels.kt` for the exact field
list.

---

## ViewModel state (`com.ai.viewmodel`)

### `GeneralSettings`
| Field | Type | Notes |
|---|---|---|
| userName | `String` | |
| huggingFaceApiKey, openRouterApiKey, artificialAnalysisApiKey | `String` | |
| defaultEmail | `String` | |
| defaultTypePaths | `Map<String, String>` | |
| rerankPrompt, summarizePrompt, comparePrompt | `String` | empty → built-in default |
| introPrompt | `String` | empty → built-in default; used by the model self-introduction step in Comprehensive PDF |
| modelInfoPrompt | `String` | empty → built-in default; used by the Model Info screen's "model info" prompt |
| translatePrompt | `String` | empty → built-in default; used by the Report result screen's Translate flow |

### `UiState`
The single immutable bag the entire UI subscribes to. See
`AppViewModel.kt` for all 30+ fields. Notable subset:

- `aiSettings: Settings`
- `generalSettings: GeneralSettings`
- `loadingModelsFor: Set<AppService>`
- Report flow: `showGenericAgentSelection`, `showGenericReportsDialog`,
  `genericPromptTitle/Text`, `currentReportId`, `genericReportsProgress/Total`,
  `pendingReportModels`, `editModeReportId`, `stagedReportModels`,
  `hasPendingPromptChange`, `hasPendingParametersChange`,
  `reportImageBase64`, `reportImageMime`, `reportWebSearchTool`,
  `reportReasoningEffort`, `reportAdvancedParameters`,
  `attachedKnowledgeBaseIds`
- Share-target staging: `chatStarterText: String?`,
  `pendingKnowledgeUris: List<String>`
- `activeSecondaryBatches: Int` — count of in-flight secondary
  batches; the Meta button's hourglass / poll loop key off this
- `externalIntent: ExternalIntent`
- Chat: `chatParameters: ChatParameters`,
  `dualChatConfig: DualChatConfig?`

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

Wire format used by `setup.json` and import/export. Same fields as
`AppService` plus `defaultModelSource: String?`. Translated into a
runtime `AppService` by `ProviderDefinition.toAppService()`.
