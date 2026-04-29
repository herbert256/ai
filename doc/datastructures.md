# Data Structures

All non-trivial data classes shipped by the app, grouped by domain. Field
types follow Kotlin notation; `?` marks nullable. Classes inside
`com.ai.data.ApiModels` (raw provider request/response shapes) are listed
in their own section.

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
| prompts | `List<Prompt>` | "Internal Prompts" — agent-bound templates |
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
| reasoningEffort | `String?` |

### `Endpoint`
| id, name, url | `String` |
| isDefault | `Boolean` |

### `SystemPrompt`
| id, name, prompt | `String` |

### `Prompt` (Internal Prompts)
| id, name, agentId, promptText | `String` |
Resolves variables `@MODEL@`, `@PROVIDER@`, `@AGENT@`, `@SWARM@`, `@NOW@`.

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
| kind | `String` | `report`, `rerank`, `summarize`, or `compare` |

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
Rerank / Summarize / Compare meta-result tied to a parent report.

| Field | Type |
|---|---|
| id | `String` (UUID) |
| reportId | `String` |
| kind | `SecondaryKind` (`RERANK`, `SUMMARIZE`, `COMPARE`) |
| providerId, model, agentName | `String` |
| timestamp | `Long` |
| content | `String?` |
| errorMessage | `String?` |
| tokenUsage | `TokenUsage?` |
| inputCost, outputCost | `Double?` |
| durationMs | `Long?` |

### `SecondaryScope` (sealed)
- `AllReports` — every successful agent feeds the meta-result
- `TopRanked(count: Int, rerankResultId: String)` — input narrowed to the top-N entries of a chosen rerank

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
`Parameters` minus `id`/`name`.

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

### `EndpointRule`
| modelPrefix, endpointType | `String` |

### `ApiFormat` (enum)
`OPENAI_COMPATIBLE`, `ANTHROPIC`, `GOOGLE`. All dispatch keys off this —
provider identity is never used for routing.

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
| imageBase64, imageMime | `String?` |
| timestamp | `Long` |

### `ChatSession`
| id, title | `String` |
| messages | `MutableList<ChatMessage>` |
| provider | `AppService` |
| model | `String` |
| createdAt, updatedAt | `Long` |

### `ChatParameters`
Per-chat overrides — same shape as `Parameters` minus id/name.

### `DualChatConfig`
| firstProvider, secondProvider | `AppService` |
| firstModel, secondModel | `String` |
| firstSystemPrompt, secondSystemPrompt | `String` |
| subject | `String` |
| firstPrompt, secondPrompt | `String` (templates with `%subject%` / `%answer%`) |

---

## Tracing (`com.ai.data.ApiTracer`)

### `ApiTrace`
| timestamp, hostname | `Long`, `String` |
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

## Export / Import (`com.ai.model.ExportModels`)

### `ConfigExport` (current version `21`)
- `version: Int`
- `providers: Map<String, ProviderConfigExport>`
- `agents: List<AgentExport>`
- `flocks, swarms, parameters, systemPrompts, aiPrompts: List<...>?`
- `huggingFaceApiKey, openRouterApiKey, artificialAnalysisApiKey: String?`
- `manualPricing: List<ManualPricingExport>?`
- `providerEndpoints: List<ProviderEndpointsExport>?`
- `providerDefinitions: List<ProviderDefinition>?`
- `providerStates: Map<String, String>?`
- `modelTypeOverrides: List<ModelTypeOverride>?`
- `defaultTypePaths: Map<String, String>?`
- `rerankPrompt, summarizePrompt, comparePrompt: String?`

### `ConfigImportResult`
Mirror of the above — what `processImportedConfig` returns to the caller.

### `ApiKeysExport`
Standalone API-keys export (separate from full config).
- `type = "api_keys"`
- `keys: List<ApiKeyEntry(service, apiKey)>`
- `huggingFaceApiKey, openRouterApiKey, artificialAnalysisApiKey: String?`

### `AgentExport`, `FlockExport`, `SwarmExport`, `SwarmMemberExport`, `ParametersExport`, `SystemPromptExport`, `PromptExport`, `EndpointExport`, `ProviderEndpointsExport`, `ManualPricingExport`, `ProviderConfigExport`
Wire-format twins of the runtime classes — same fields stripped of any
runtime-only state. See `ExportModels.kt` for the exact field list.

---

## ViewModel state (`com.ai.viewmodel`)

### `GeneralSettings`
| Field | Type |
|---|---|
| userName | `String` |
| huggingFaceApiKey, openRouterApiKey, artificialAnalysisApiKey | `String` |
| defaultEmail | `String` |
| defaultTypePaths | `Map<String, String>` |
| rerankPrompt, summarizePrompt, comparePrompt | `String` (empty → built-in default) |

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
  `reportAdvancedParameters`
- `secondaryRun: SecondaryRunState?`
- `externalIntent: ExternalIntent`
- `chatParameters: ChatParameters`
- `dualChatConfig: DualChatConfig?`

### `ExternalIntent`
Bundle of all 13 fields a launching intent can stuff into UiState.

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
