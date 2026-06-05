# Application log — every write, by severity

> Generated reference. Lists every call site that writes to the in-app
> application log (`AppLog`, `data/AppLog.kt`), which mirrors
> `android.util.Log` and appends to `<filesDir>/applog/applog_<yyyyMMdd>.log`
> for any call at or above the active `threshold` (default `INFO`).
> Grouped by severity, then by source file. Each row is
> `Lnnn` `Tag` — message (message interpolations shown verbatim as written
> in source, including the `+ "…"` string concatenations on multi-line calls).
> For *how the logger works* (levels, rotation, redaction, viewer), see
> **[applog.md](applog.md)**.

**480 call sites** — 62 ERROR, 160 WARN, 97 INFO, 107 DEBUG, 54 TRACE.

Severity is chosen at the call site by which method is invoked:

| Method | Level | Priority | Toast? |
|---|---|---|---|
| `AppLog.v` | TRACE | 2 | no |
| `AppLog.d` | DEBUG | 3 | no |
| `AppLog.i` | INFO | 4 | no |
| `AppLog.w` | WARN | 5 | yes (debounced) |
| `AppLog.e` | ERROR | 6 | yes (debounced) |

`AppLog.w` also has a `w(tag, t: Throwable)` overload that derives the
message from the throwable; there is no `e(tag, t)` overload, so every
`AppLog.e` call passes an explicit message string (the throwable, when
present, rides in the optional third argument).

WARN/ERROR Toasts are debounced — at most one Toast per
`TOAST_MIN_INTERVAL_MS` (1500 ms) so a burst of retries (e.g. fan-out icon
workers all rate-limiting at once) can't flood the screen.

A handful of call sites pass a `tag` / `startTag` local variable rather than
a string literal. These resolve to:

| Variable | File | Value |
|---|---|---|
| `tag` | `ui/helpers/PdfExport.kt` | `"PdfExport"` |
| `tag` | `data/TracingInterceptor.kt` | `"ApiCall"` |
| `tag` | `viewmodel/AppViewModel.kt` (bootstrap region) | `"App.bootstrap"` |
| `startTag` | `viewmodel/AppViewModel.kt` (start region) | `"App.start"` |

Rows tagged `"Crash"` (one ERROR in `data/CrashReporter.kt`) write the
captured crash report — the message is the `report` string, not a literal.

> **Maintenance.** Counts and line numbers track HEAD and drift on every
> commit that touches a logging call. Regenerate by walking
> `ai/src/main/java/com/ai` for `AppLog.(v|d|i|w|e)(` (excluding the
> string-literal examples embedded in `data/IconUsageData.kt`, which quote
> three call sites as documentation), grouping by severity then file.

---

## ERROR (62)

### `data/ApiTracer.kt`

- **L152** `"ApiTracer"` — "Failed to save trace ($resolvedFilename): ${e.message}"
- **L188** `"ApiTracer"` — "Cache update failed for $resolvedFilename — invalidating cache: ${e.message}"

### `data/AtomicFileWrite.kt`

- **L60** `"AtomicFileWrite"` — "Failed to write $absolutePath: ${e.message}"

### `data/ChatHistoryManager.kt`

- **L37** `"ChatHistory"` — "Refusing to save session with suspect id ${session.id}"
- **L45** `"ChatHistory"` — "Refusing to save session that escapes historyDir: ${session.id}"
- **L63** `"ChatHistory"` — "Failed to save: ${e.message}"
- **L71** `"ChatHistory"` — "Refusing to load session with unsafe id: $sessionId"
- **L84** `"ChatHistory"` — "Failed to load: ${e.message}"
- **L96** `"ChatHistory"` — "Failed to parse: ${e.message}"
- **L106** `"ChatHistory"` — "Refusing to delete session with unsafe id: $sessionId"

### `data/CrashReporter.kt`

- **L107** `"Crash"` — report

### `data/Knowledge.kt`

- **L204** `"Knowledge"` — "Refusing to save source with suspect id ${source.id}"
- **L211** `"Knowledge"` — "Refusing to save source that escapes chunks dir: ${source.id}"
- **L292** `"Knowledge"` — "Refusing to resolve KB dir for suspect id $kbId"
- **L304** `"Knowledge"` — "Refusing to resolve KB dir that escapes root: $kbId"

### `data/PricingCache.kt`

- **L351** `"PricingCache"` — "ensureLoadedBlocking invoked on the main thread — refusing to mark preload complete. " + "Move the call to Dispatchers.IO."
- **L846** `"PricingCache"` — "Online LITELLM refresh failed: ${e.message}"
- **L885** `"PricingCache"` — "models.dev refresh failed: ${e.message}"
- **L1021** `"PricingCache"` — "Helicone refresh failed: ${e.message}"
- **L1109** `"PricingCache"` — "llm-prices refresh failed: ${e.message}"
- **L1185** `"PricingCache"` — "Artificial Analysis refresh failed: ${e.message}"
- **L1514** `"PricingCache"` — "Failed: ${e.message}"

### `data/ProviderRegistry.kt`

- **L51** `"ProviderRegistry"` — "Error loading from prefs: ${e.message}"

### `data/RegenerateBatchStorage.kt`

- **L36** `"RegenerateBatchStorage"` — "Refusing to resolve job file for suspect id $reportId"
- **L41** `"RegenerateBatchStorage"` — "Refusing to resolve job file that escapes root: $reportId"

### `data/ReportStorage.kt`

- **L447** `"ReportStorage"` — "Failed to load report $reportId: ${e.message}"
- **L455** `"ReportStorage"` — "Failed to load ${file.name}: ${e.message}"
- **L507** `"ReportStorage"` — "Refusing to save report with suspect id ${report.id}"
- **L512** `"ReportStorage"` — "Refusing to save report that escapes reportsDir: ${report.id}"
- **L521** `"ReportStorage"` — "Failed to save report ${report.id} (writeTextAtomic returned false)"

### `data/SecondaryResult.kt`

- **L55** `"SecondaryResultStorage"` — "Refusing to resolve report dir for suspect id $reportId"
- **L60** `"SecondaryResultStorage"` — "Refusing to resolve report dir that escapes root: $reportId"
- **L78** `"SecondaryResultStorage"` — "Refusing to resolve report dir for suspect id $reportId"
- **L83** `"SecondaryResultStorage"` — "Refusing to resolve report dir that escapes root: $reportId"
- **L102** `"SecondaryResultStorage"` — "Refusing to save result with suspect id ${result.id}"
- **L113** `"SecondaryResultStorage"` — "Refusing to save result that escapes report dir: ${result.id}"
- **L264** `"SecondaryResultStorage"` — "Refusing to save result with suspect id ${result.id}"
- **L280** `"SecondaryResultStorage"` — "Refusing to save result that escapes report dir: ${result.id}"

### `data/local/LlmRuntime.kt`

- **L80** `"LlmRuntime"` — "load failed: ${t.message}"
- **L124** `"LlmRuntime"` — "AAR did not contain $AAR_ENTRY"
- **L144** `"LlmRuntime"` — "download failed: ${e.message}"

### `data/local/LocalEmbedder.kt`

- **L135** `"LocalEmbedder"` — "model ${spec.name} download failed: ${e.message}"
- **L252** `"LocalEmbedder"` — "embed failed: ${e.message}"

### `data/local/LocalLlm.kt`

- **L181** `"LocalLlm"` — "generate failed: ${e.message}"

### `ui/helpers/PdfExport.kt`

- **L592** `tag` — "PDF render failed"
- **L695** `tag` — "onReceivedError code=${error.errorCode} desc='${error.description}' url=${request.url} at +${elapsedMs()}ms"
- **L698** `tag` — "onReceivedHttpError status=${errorResponse.statusCode} url=${request.url} at +${elapsedMs()}ms"
- **L711** `tag` — "renderHtmlToPdfFile failed at +${elapsedMs()}ms: ${e.javaClass.simpleName}: ${e.message}"

### `ui/helpers/ReportExportScreen.kt`

- **L141** `"ReportExport"` — "Export failed"
- **L181** `"ReportExport"` — "Export all failed"

### `ui/settings/ImportExportScreen.kt`

- **L1172** `"ImportExport"` — "AI Report import error"
- **L1210** `"ImportExport"` — "API keys import parse error"
- **L1213** `"ImportExport"` — "API keys import error"

### `ui/settings/LocalRuntimeScreens.kt`

- **L352** `"LocalRuntime"` — "tflite import: openInputStream returned null for $uri"
- **L360** `"LocalRuntime"` — "tflite import: copy produced empty file for $sanitized"
- **L366** `"LocalRuntime"` — "tflite import: rename failed for $sanitized"
- **L372** `"LocalRuntime"` — "tflite import failed: ${e.message}"
- **L430** `"LocalRuntime"` — "LLM import: openInputStream returned null for $uri"
- **L456** `"LocalRuntime"` — "LLM import: staged file empty for $displayName"
- **L461** `"LocalRuntime"` — "LLM import: rename failed for $displayName"
- **L467** `"LocalRuntime"` — "LLM import failed: ${e.message}"

### `viewmodel/AppViewModel.kt`

- **L1087** `"Housekeeping"` — "← Reset application FAILED"

## WARN (160)

### `data/AnalysisRepository.kt`

- **L322** `"AiAnalysis"` — "Tool fallback also failed for ${agent.name}: " + "first=${first.httpStatusCode}/${first.error?.take(120)}; " + "fallback=${retried.httpStatusCode}/${retried.error?.take(120)}"
- **L435** `"AiAnalysis"` — "Streaming attempt failed for ${agent.name} (${e.message}); using non-streaming"
- **L482** `"AiAnalysis"` — "$label first attempt permanent failure, skipping retry"
- **L485** `"AiAnalysis"` — "$label first attempt failed, retrying..."
- **L507** `"AiAnalysis"` — "$label first attempt I/O failure: ${e.message}, retrying…"

### `data/ApiClient.kt`

- **L288** `"ApiClient"` — "fetchUrlAsString non-2xx ${resp.code} for $url — raw snapshot skipped"
- **L295** `"ApiClient"` — "fetchUrlAsString failed for $url: ${e.message}"

### `data/ApiDispatch.kt`

- **L753** `"ApiDispatch"` — "OpenRouter listModelsDetailed threw: ${e.javaClass.simpleName}: ${e.message}"
- **L843** `"ApiDispatch"` — "Native capability listModels HTTP ${resp.code()}: ${body ?: "(no body)"}"
- **L847** `"ApiDispatch"` — "Native capability listModels threw: ${e.javaClass.simpleName}: ${e.message}"
- **L944** `"ApiDispatch"` — "Anthropic listModels threw: ${e.javaClass.simpleName}: ${e.message}"
- **L949** `"ApiDispatch"` — "Anthropic listModels HTTP ${response.code()}: ${body ?: "(no body)"}"
- **L954** `"ApiDispatch"` — "Anthropic listModels returned 200 but no claude-* entries (data size=${response.body()?.data?.size ?: 0})"
- **L1035** `"ApiDispatch"` — "Gemini listModels HTTP ${response.code()}: ${body ?: "(no body)"}"
- **L1043** `"ApiDispatch"` — "Gemini listModels threw: ${e.javaClass.simpleName}: ${e.message}"
- **L1603** `"ApiDispatch"` — "Anthropic reasoning override: max_tokens raised from $baseMax to $effectiveMax (thinking budget=$budget)"

### `data/ApiTracer.kt`

- **L156** `"ApiTracer"` — "writeTextAtomic returned false for $resolvedFilename — skipping cache update"

### `data/BackupManager.kt`

- **L304** `"Backup"` — "Skipping zip entry that escapes filesDir: $name"
- **L315** `"Backup"` — "Skipping zip entry that escapes cacheDir: $name"
- **L518** `"Backup"` — "applyPrefs($name): unknown type tag '$tag' for key '$k' — entry skipped"
- **L565** `"Backup"` — "Skipping symlink that escapes ${dir.absolutePath}: ${child.absolutePath} → $childCanonical"

### `data/ChatHistoryManager.kt`

- **L31** `"ChatHistory"` — "Not initialized"
- **L69** `"ChatHistory"` — "Not initialized"

### `data/DefaultMetaItemSeed.kt`

- **L49** `"DefaultMetaItemSeed"` — "Failed to load meta.json: ${e.message}"

### `data/EmbeddingsStore.kt`

- **L66** `"EmbeddingsStore"` — "put($docId, $providerId, $model) failed to write ${f.absolutePath}"
- **L84** `"EmbeddingsStore"` — "cosine: dim mismatch a=${a.size} b=${b.size} — embedder swapped without re-embed?"
- **L108** `"EmbeddingsStore"` — "cosine: dim mismatch a=${a.size} b=${b.size} — embedder swapped without re-embed?"

### `data/ExamplePromptSeed.kt`

- **L40** `"ExamplePromptSeed"` — "Failed to load examples.json: ${e.message}"
- **L86** `"ExamplePromptSeed"` — "upsertFromJson failed: ${e.message}"

### `data/FlockSeed.kt`

- **L50** `"FlockSeed"` — "Skipped flock entry: ${e.message}"
- **L55** `"FlockSeed"` — "Failed to load workers/flocks/: ${e.message}"

### `data/InaccessibleSeed.kt`

- **L42** `"InaccessibleSeed"` — "Failed to load inaccessible.json: ${e.message}"

### `data/InternalPromptIconCache.kt`

- **L115** `"InternalPromptIcon"` — "load failed: ${e.message}"
- **L291** `"InternalPromptIcon"` — "save failed: ${e.message}"

### `data/InternalPromptSeed.kt`

- **L126** `"InternalPromptSeed"` — "Missing body for $dir/$stem.txt: ${e.message}"
- **L150** `"InternalPromptSeed"` — "Failed to load internal-prompts/: ${e.message}"
- **L230** `"InternalPromptSeed"` — "upsertFromJson failed: ${e.message}"

### `data/Knowledge.kt`

- **L233** `"Knowledge"` — "Embedding dim mismatch on saveSource: kb=$kbId, " + "manifest=${current.embeddingDim}, new=$embeddingDim. " + "Manifest dim retained; cosine queries against this " + "source will silent-zero against chunks from other " + "embedders. Re-create the KB to mix embedders cleanly."
- **L250** `"Knowledge"` — "Refusing to delete source with suspect id $sourceId"
- **L257** `"Knowledge"` — "Refusing to delete source that escapes chunks dir: $sourceId"

### `data/KnowledgeService.kt`

- **L219** `"Knowledge"` — "Embedder mismatch across attached KBs (${first.name} vs ${mismatch.name}); using ${first.name}'s"
- **L269** `"KnowledgeService"` — "KB '${kb.name}' (${kb.id}) has chunks with dim=$it; query dim=${queryVec.size}. " + "Re-index the KB with the current embedder."

### `data/MetaCache.kt`

- **L55** `"MetaCache"` — "load failed: ${e.message}"
- **L100** `"MetaCache"` — "save failed: ${e.message}"

### `data/ModelCooldownStore.kt`

- **L77** `"ModelCooldown"` — "$providerId/$model benched until ${java.util.Date(availableAtMs)}" + (traceFile?.let { " (trace $it)" } ?: "")

### `data/ModelListCache.kt`

- **L54** `"ModelListCache"` — "save($providerId) failed: ${e.message}"
- **L76** `"ModelListCache"` — "read($providerId) failed: ${e.message}"

### `data/ModelTestRunStore.kt`

- **L32** `"ModelTestRunStore"` — "save failed: ${e.message}"
- **L44** `"ModelTestRunStore"` — "load failed: ${e.message}"

### `data/OverloadedRetry.kt`

- **L67** `"Overloaded"` — "529 still present after $attempt retries on ${request.url.host}"

### `data/PricingCache.kt`

- **L868** `"PricingCache"` — "models.dev refresh: empty / failed response"
- **L1005** `"PricingCache"` — "Helicone refresh: empty / failed response"
- **L1160** `"PricingCache"` — "Artificial Analysis refresh skipped: missing API key"
- **L1169** `"PricingCache"` — "Artificial Analysis refresh: empty / failed response"

### `data/PromptTranslationStore.kt`

- **L37** `"PromptTranslationStore"` — "put failed: ${e.message}"

### `data/ProviderFieldTimestamps.kt`

- **L46** `"ProviderFieldTimestamps"` — "load failed: ${e.message}"

### `data/ProviderRegistry.kt`

- **L81** `"ProviderRegistry"` — "Skipped bundled provider ${def.id}: ${e.message}"
- **L89** `"ProviderRegistry"` — "importFromAsset($filename) failed: ${e.message}"
- **L111** `"ProviderRegistry"` — "Skipped imported provider ${def.id}: ${e.message}"
- **L122** `"ProviderRegistry"` — "upsertFromJson failed: ${e.message}"
- **L141** `"ProviderRegistry"` — "Skipping malformed provider entry (id=$id, baseUrl=$baseUrl)"
- **L146** `"ProviderRegistry"` — "Skipping provider $id — toAppService threw: ${e.message}"
- **L175** `"ProviderRegistry"` — "Refusing to add duplicate provider id ${service.id}; existing entry kept"
- **L253** `"ProviderRegistry"` — "syncFromAsset failed: ${e.message}"

### `data/RateLimitRetry.kt`

- **L107** `"RateLimit"` — "long 429 on $host but provider/model unresolved (provider=$providerId model=$model)"
- **L162** `"RateLimit"` — "429 still present after $attempt retries on ${request.url.host}"

### `data/RegenerateBatchStorage.kt`

- **L55** `"RegenerateBatchStorage"` — "parse failed for $reportId: ${e.message}"
- **L82** `"RegenerateBatchStorage"` — "parse failed for $reportId: ${e.message}"

### `data/ReportStorage.kt`

- **L386** `"ReportStorage"` — "Refusing to delete report with suspect id $reportId"
- **L393** `"ReportStorage"` — "Refusing to delete report that escapes reportsDir: $reportId"
- **L440** `"ReportStorage"` — "Rejected reportId with path traversal markers: $reportId"

### `data/SecondaryResult.kt`

- **L92** `"SecondaryResultStorage"` — "Skipping save for deleted report ${result.reportId}"
- **L107** `"SecondaryResultStorage"` — "Skipping save for deleted report ${result.reportId}"

### `data/SwarmSeed.kt`

- **L34** `"SwarmSeed"` — "Skipped swarm entry: ${e.message}"
- **L39** `"SwarmSeed"` — "Failed to load workers/swarms/: ${e.message}"

### `data/SystemPromptSeed.kt`

- **L40** `"SystemPromptSeed"` — "Failed to load system-prompts.json: ${e.message}"

### `data/TestExcludedSeed.kt`

- **L36** `"TestExcludedSeed"` — "Failed to load excluded.json: ${e.message}"

### `data/TracingInterceptor.kt`

- **L97** `tag` — "${MetadataIconsHolder.current.crossMark} $callLabel — ${e.javaClass.simpleName}: ${e.message ?: ""} (${System.currentTimeMillis() - callStart}ms)"
- **L155** `tag` — "← ${response.code} $callLabel in ${durationMs}ms$tail"
- **L169** `tag` — "← ${response.code} $callLabel in ${durationMs}ms"

### `ui/helpers/BulkExport.kt`

- **L137** `"BulkExport"` — "PDF render produced no output: ${f.name}"

### `ui/settings/ImportExportScreen.kt`

- **L103** `"ImportExport"` — "Skipped $name entry: ${e.message}"
- **L148** `"ImportExport"` — "Skipped flock entry: ${e.message}"
- **L244** `"ImportExport"` — "Skipped runtime report entry: ${e.message}"
- **L264** `"ImportExport"` — "Skipped secondary row: ${e.message}"
- **L290** `"ImportExport"` — "Skipped chat session entry: ${e.message}"
- **L514** `"ImportExport"` — "Skipped model list for unknown provider $key"
- **L602** `"ImportExport"` — "Skipped endpoints for unknown provider $key"
- **L608** `"ImportExport"` — "Skipped endpoint entry: ${e.message}"
- **L628** `"ImportExport"` — "Skipped parameters entry: ${e.message}"
- **L643** `"ImportExport"` — "Skipped model type override entry: ${e.message}"
- **L661** `"ImportExport"` — "Skipped model cooldowns blob: ${e.message}"
- **L675** `"ImportExport"` — "Skipped system prompt entry: ${e.message}"
- **L691** `"ImportExport"` — "Skipped blocked model entry: ${e.message}"
- **L707** `"ImportExport"` — "Skipped test-excluded model entry: ${e.message}"
- **L723** `"ImportExport"` — "Skipped inaccessible model entry: ${e.message}"
- **L1530** `"ImportExport"` — "Bundle apiKeys section failed: ${e.message}"
- **L1556** `"ImportExport"` — "Bundle costs section failed: ${e.message}"

### `viewmodel/AppViewModel.kt`

- **L402** `"CapsWatch"` — "POSSIBLE STALL — throttle state frozen ${stalledTicks * 15}s — $line"
- **L562** `tag` — "First-run providers.json import failed"
- **L593** `tag` — "← providers.json delta-sync failed in ${System.currentTimeMillis() - tSync}ms"
- **L643** `tag` — "← internal-prompts/ delta-merge failed in ${System.currentTimeMillis() - tPrompts}ms"
- **L672** `tag` — "← examples.json delta-merge failed in ${System.currentTimeMillis() - tExamples}ms"
- **L699** `tag` — "← system-prompts.json delta-merge failed in ${System.currentTimeMillis() - tSystemPrompts}ms"
- **L723** `tag` — "← workers/swarms/ delta-merge failed in ${System.currentTimeMillis() - tSwarms}ms"
- **L746** `tag` — "← workers/flocks/ delta-merge failed in ${System.currentTimeMillis() - tFlocks}ms"
- **L772** `tag` — "← excluded.json delta-merge failed in ${System.currentTimeMillis() - tExcluded}ms"
- **L800** `tag` — "← inaccessible.json delta-merge failed in ${System.currentTimeMillis() - tInaccessible}ms"
- **L822** `tag` — "← meta.json delta-merge failed in ${System.currentTimeMillis() - tMeta}ms"
- **L1042** `"App"` — "providers.json reload failed during reset"
- **L1424** `"App"` — "Failed to fetch models for ${service.id}: ${e.message}"
- **L1801** `"RefreshAll"` — "model fetch failed for ${service.id}: ${it.message}"

### `viewmodel/ChatViewModel.kt`

- **L94** `"Chat.RAG"` — "Retrieval failed for kbs=$knowledgeBaseIds: ${e.javaClass.simpleName}: ${e.message}"

### `viewmodel/CompareEngine.kt`

- **L182** `"Compare"` — "meta_compare prompt not configured / no runnable workers — aborting"
- **L196** `"Compare"` — "nothing to compare (answers=${successful.size}, meta=${metaRows.size})"

### `viewmodel/FanOutEngine.kt`

- **L1338** `"FanOut"` — "pair ans=$answererAgentId src=$sourceAgentId timed out after 60s"
- **L1796** `"FanOut"` — "rerun pairs failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"
- **L1810** `"FanOut"` — "resume stale runs failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"

### `viewmodel/IconGenerationManager.kt`

- **L1026** `"InternalPromptIcon"` — "second/meta not configured — skipping"
- **L1069** `"InternalPromptIcon"` — "no worker produced an icon for name='${prompt.name}'"
- **L1132** `"InternalPromptIconAlt"` — "alt/meta not configured — skipping fan-out"
- **L1247** `"InternalPromptIconAlt"` — "exception for ${item.provider.id}/${item.model}: ${e.message}"
- **L1355** `"PairIconAlt"` — "alt/fan_out prompt not found — skipping (pair=$pairId)"
- **L1531** `"PairTitleAlt"` — "alt/model_title prompt not found — skipping (pair=$pairId)"
- **L1673** `"TranslationIcon"` — "translation/icon not configured — skipping"
- **L1712** `"TranslationIcon"` — "no worker produced an icon for language='$language'"
- **L1746** `"TranslationIconAlt"` — "alt/translation not configured — skipping fan-out"
- **L1849** `"TranslationIconAlt"` — "exception for ${item.provider.id}/${item.model}: ${e.message}"
- **L2228** `"LanguageIconAlt"` — "no detected language on report=$reportId — skipping fan-out"
- **L2367** `"AgentIconAlt"` — "alt/report prompt not found — skipping (agent=$agentId)"
- **L2650** `"FanMeta"` — "fan/meta not configured — skipping"

### `viewmodel/JudgeEvalEngine.kt`

- **L204** `"JudgeEval"` — "workers/tournament prompt not configured — aborting"
- **L209** `"JudgeEval"` — "no resolvable judges in the prompt's swarm — aborting"
- **L636** `"JudgeEval"` — "hydrate failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"
- **L674** `"JudgeEval"` — "resume stale runs failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"

### `viewmodel/RegenerateBatchEngine.kt`

- **L237** `"RegenBatch"` — "orchestrator crashed for $reportId: ${e.message}"
- **L320** `"RegenBatch"` — "phase $phase timed out for $reportId — pausing"

### `viewmodel/ReportViewModel.kt`

- **L788** `"Report"` — "skip benched ${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} — marking agent ${task.resultId} errored"
- **L1815** `"Report"` — "background report skipped — no active models for swarm $swarmId"

### `viewmodel/SecondaryRunManager.kt`

- **L449** `"SecondaryResume"` — "resume stale runs failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"
- **L480** `"BgResumeSweep"` — "iteration failed: ${e.javaClass.simpleName}: ${e.message}"
- **L1179** `"Secondary"` — "skip benched ${provider.id}/$model — marking row ${placeholder.id} errored"

### `viewmodel/StressTestEngine.kt`

- **L113** `"StressTest"` — "failed: ${e.javaClass.simpleName}: ${e.message}"

### `viewmodel/TournamentEngine.kt`

- **L200** `"Tournament"` — "workers/tournament not configured — aborting"
- **L411** `"Tournament"` — "recompute aggregate failed report=$reportId method=${run.selectedMethod}: ${e.javaClass.simpleName}: ${e.message}"
- **L532** `"Tournament"` — "hydrate failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"
- **L592** `"Tournament"` — "resume stale runs failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"

### `viewmodel/TranslationRunManager.kt`

- **L123** `"Translation"` — "startTranslation called with empty models — skipping"
- **L522** `"Translation"` — "item ${item.id} timed out on ${ctx.provider.id}/${ctx.model} after ${callBudgetMs / 1000}s — reassigning"
- **L686** `"Translation"` — "skip benched ${provider.id}/$model — item ${item.id} failed attempt"
- **L1862** `"Translation"` — "item ${item.id} timed out on ${ctx.provider.id}/${ctx.model} after ${callBudgetMs / 1000}s"
- **L1920** `"Meta-xlate"` — "No existing translation run for $targetLanguageName — skipping cross-translate"
- **L1967** `"Meta-xlate"` — "Could not rebuild persisted state for run $runId — aborting cross-translate"
- **L2035** `"Translate-missing"` — "No existing translation run for $targetLanguageName and no model to bootstrap from — skipping ${items.size} item(s)"
- **L2081** `"Translate-missing"` — "Could not rebuild persisted state for run $runId — aborting"

### `viewmodel/WorkerRunner.kt`

- **L97** `"Workers"` — "prompt '${prompt.name}' has no runnable workers — nothing to run"
- **L135** `"Workers"` — "429 '${prompt.name}' via ${agent.name} — cooling ${waitMs}ms, next worker"
- **L143** `"Workers"` — "${resp.httpStatusCode ?: "model-gone"} '${prompt.name}' via ${agent.name} — model unavailable, disabling this worker for the session"
- **L148** `"Workers"` — "no usable result '${prompt.name}' via ${agent.name} — next worker"
- **L149** `"Workers"` — "miss '${prompt.name}' via ${agent.name}: ${resp.error?.take(80)}"

## INFO (97)

### `data/AnalysisRepository.kt`

- **L327** `"AiAnalysis"` — "Tool fallback succeeded for ${agent.name} " + "after first=${first.httpStatusCode}/${first.error?.take(120)}"

### `data/BackupManager.kt`

- **L159** `"Backup"` — "→ backup start"
- **L200** `"Backup"` — "← backup done in ${System.currentTimeMillis() - t0}ms (filesDir=$filesWritten cacheDir=$cacheWritten)"
- **L209** `"Backup"` — "→ restore start"
- **L256** `"Backup"` — "← restore done in ${System.currentTimeMillis() - t0}ms (prefs=$prefsRestored files=$filesRestored)"

### `data/InternalPromptIconCache.kt`

- **L278** `"InternalPromptIcon"` — "clearAll dropped $n cached icons"

### `data/KnowledgeService.kt`

- **L121** `"Knowledge"` — "→ index \"$displayName\" type=$type kb=$kbId textLen=${text.length}"
- **L190** `"Knowledge"` — "← index \"$displayName\" kb=$kbId chunks=${chunks.size} chars=${src.charCount} dim=$embeddingDim in ${System.currentTimeMillis() - indexStart}ms"

### `data/MetaCache.kt`

- **L84** `"MetaCache"` — "clearAll dropped $n entries"

### `data/PricingCache.kt`

- **L872** `"PricingCache"` — "models.dev parse: ${pricing.size} priced, ${meta.size} meta entries (raw ${json.length} bytes)"
- **L1009** `"PricingCache"` — "Helicone parse: ${exact.size} exact, ${patterns.size} patterns"
- **L1099** `"PricingCache"` — "llm-prices parse: ${combined.size} entries from ${llmPricesVendors.size} vendors"
- **L1173** `"PricingCache"` — "Artificial Analysis parse: ${pricing.size} priced, ${meta.size} meta entries"

### `data/PromptTranslationStore.kt`

- **L61** `"PromptTranslationStore"` — "deleted $n files for $language"
- **L108** `"PromptTranslationStore"` — "translated $done/${baseline.size} prompts into $targetLanguage from $sourceLanguage"

### `data/ProviderRegistry.kt`

- **L181** `"ProviderRegistry"` — "added ${service.id} (baseUrl=${service.baseUrl})"
- **L201** `"ProviderRegistry"` — "updated ${service.id} changed=${changed.joinToString(",")}"
- **L209** `"ProviderRegistry"` — "removed $id"
- **L244** `"ProviderRegistry"` — "syncFromAsset: ${asset.id} pulled ${take.joinToString()}"

### `data/TracingInterceptor.kt`

- **L92** `tag` — "→ $callLabel"
- **L157** `tag` — "← ${response.code} $callLabel in ${durationMs}ms"
- **L171** `tag` — "← ${response.code} $callLabel in ${durationMs}ms"

### `data/local/LlmRuntime.kt`

- **L77** `"LlmRuntime"` — "loaded ${runtimeFile(context).absolutePath}"
- **L140** `"LlmRuntime"` — "downloaded $SO_NAME (${target.length() / (1024 * 1024)} MiB)"

### `data/local/LocalLlm.kt`

- **L124** `"LocalLlm"` — "→ load $modelName from ${file.name} (${file.length() / (1024 * 1024)} MiB)"
- **L134** `"LocalLlm"` — "← loaded $modelName in ${System.currentTimeMillis() - loadStart}ms"

### `ui/admin/AppLogScreen.kt`

- **L185** `"Housekeeping"` — "Cleared $n log file(s)"
- **L208** `"Housekeeping"` — "Deleted $n log file(s) older than 7 days"

### `ui/helpers/PdfExport.kt`

- **L517** `tag` — "renderHtmlToPdfFile: starting, html=${html.length} chars, out=${output.absolutePath}, withToc=$withTocPage, thread=${Thread.currentThread().name}, timeoutMs=$timeoutMs"
- **L539** `tag` — "contentHeightCss=${view.contentHeight}, contentPx=$contentPx, totalHeight=$totalHeight"
- **L589** `tag` — "rendered ${pageNum - 1} pages to ${output.length()} bytes at +${elapsedMs()}ms"
- **L674** `tag` — "onPageStarted url=$url at +${elapsedMs()}ms"
- **L677** `tag` — "onPageFinished url=$url, contentHeight=${view.contentHeight} at +${elapsedMs()}ms"
- **L701** `tag` — "loading HTML into WebView at +${elapsedMs()}ms"
- **L709** `tag` — "render complete at +${elapsedMs()}ms"

### `ui/settings/LocalRuntimeScreens.kt`

- **L439** `"LocalRuntime"` — "Extracted $entry from $displayName"
- **L444** `"LocalRuntime"` — "Extracted $entry from $displayName"
- **L449** `"LocalRuntime"` — "Extracted $entry from $displayName"

### `viewmodel/AppViewModel.kt`

- **L404** `"CapsWatch"` — line
- **L464** `"App"` — "App started — $appLabel v${com.ai.BuildConfig.VERSION_NAME} " + "(built $builtAt, installed $installedAt) " + "logLevel=${bs.first.logLevel}, tracing=${bs.first.tracingEnabled}"
- **L609** `tag` — "Seeding ${needsSeed.size} default-inactive provider state(s): ${needsSeed.joinToString { it.id }}"
- **L950** `"Housekeeping"` — "→ Clear logs / chats / traces / reports / prompts / usage stats / test run"
- **L974** `"Housekeeping"` — "→ Clear Info-provider caches"
- **L976** `"Housekeeping"` — "← Clear Info-provider caches done"
- **L980** `"Housekeeping"` — "→ Clear all configuration"
- **L989** `"Housekeeping"` — "← Clear all configuration: localLlms=$llms embedders=$embedders"
- **L1005** `"Housekeeping"` — "→ Reset application (preserve API keys)"
- **L1083** `"Housekeeping"` — "← Reset application: $count API keys restored"
- **L1138** `"Settings"` — "Log level changed: ${previous.logLevel} → ${settings.logLevel}"
- **L1255** `"ModelTest"` — "→ test-run flush: ${snapshot.blockedModels.size} blocked, ${snapshot.testExcludedModels.size} test-excluded, ${snapshot.inaccessibleModels.size} inaccessible"

### `viewmodel/CompareEngine.kt`

- **L199** `"Compare"` — "→ start report=$reportId (${successful.size} answers × ${metaRows.size} meta = ${cellCountFor(successful.size, metaRows.size)} cells)"
- **L232** `"Compare"` — "← done report=$reportId in ${System.currentTimeMillis() - startMs}ms"

### `viewmodel/FanOutEngine.kt`

- **L1066** `"FanOut"` — "→ start \"${metaPrompt.name}\" (report=$reportId, ${successful.size} successful agents)"
- **L1158** `"FanOut"` — "← end \"${metaPrompt.name}\" (${pending.size} pairs in ${System.currentTimeMillis() - fanOutStartMs}ms)"

### `viewmodel/IconGenerationManager.kt`

- **L2663** `"FanMeta"` — "no pending pairs on $reportId — nothing to do"
- **L2666** `"FanMeta"` — "→ start (report=$reportId, ${pending.size} pairs)"
- **L2691** `"FanMeta"` — "← end (report=$reportId)"

### `viewmodel/JudgeEvalEngine.kt`

- **L226** `"JudgeEval"` — "→ start report=$reportId (${judges.size} judges × ${chosen.size} matches = ${judges.size * chosen.size} cells)"
- **L269** `"JudgeEval"` — "← done report=$reportId in ${System.currentTimeMillis() - startMs}ms"
- **L445** `"JudgeEval"` — "Removed judge $providerId/$model from swarm '$swarmName'"
- **L470** `"JudgeEval"` — "Removed judge $judgeKey from run on $reportId (${cells.size} cells)"
- **L491** `"JudgeEval"` — "Added judge ${provider.id}/$model to swarm '$swarmName'"
- **L537** `"JudgeEval"` — "Added judge $judgeKey to run on $reportId (${matches.size} cells)"

### `viewmodel/ModelTestEngine.kt`

- **L116** `"ModelTest"` — "→ hydrate backfill: catalog=${stats.total}, inacc=${stats.inaccessible}, excl=${stats.excluded}, noChat=${stats.noChat}, items=${updated.items.size}"
- **L213** `"ModelTest"` — "→ startRun ${items.size} models (catalog=${stats.total}, inacc=${stats.inaccessible}, excl=${stats.excluded}, noChat=${stats.noChat})"
- **L247** `"ModelTest"` — "↻ resumeRun ${unfinished.size} unfinished models"
- **L294** `"ModelTest"` — "↻ rerunErrors dropped ${staleKeys.size} stale, nothing to rerun"
- **L312** `"ModelTest"` — "↻ rerunErrors dropped ${staleKeys.size} stale items"
- **L314** `"ModelTest"` — "↻ rerunErrors ${toRerunKeys.size} previously-failed models"
- **L349** `"ModelTest"` — "← run done (${items.size} models)"
- **L426** `"ModelTest"` — "${com.ai.data.MetadataIconsHolder.current.closeMark} run cancelled"

### `viewmodel/RegenerateBatchEngine.kt`

- **L196** `"RegenBatch"` — "reviving stale RUNNING orchestrator for $reportId"
- **L204** `"RegenBatch"` — "auto-resuming PAUSED batch for $reportId — error cleared"
- **L294** `"RegenBatch"` — "phase $phase paused on error for $reportId"

### `viewmodel/ReportViewModel.kt`

- **L450** `"Report"` — "→ start \"${title.ifBlank { "AI Report" }}\" (id=$reportId, ${reportTasks.size} agent(s))"
- **L471** `"Report"` — "← end \"${title.ifBlank { "AI Report" }}\" ok=$ok fail=$fail in ${System.currentTimeMillis() - reportStartMs}ms"
- **L710** `"Report"` — "auto-rerank skipped: no rerank-capable model"
- **L713** `"Report"` — "auto-moderation skipped: no moderation-capable model"
- **L753** `"Report"` — "auto-meta skipped: no meta prompt '${item.metaName}'"
- **L758** `"Report"` — "auto-meta '${item.metaName}': no resolvable model"
- **L1829** `"Report"` — "→ start (bg) \"${title.ifBlank { "AI Report" }}\" (id=$reportId, ${reportTasks.size} agent(s))"
- **L1840** `"Report"` — "← end (bg) id=$reportId ok=$ok fail=$fail in ${System.currentTimeMillis() - startMs}ms"

### `viewmodel/SecondaryRunManager.kt`

- **L144** `"Rerank"` — "→ start report=$reportId via ${provider.id}/$model"
- **L207** `"Moderation"` — "→ start report=$reportId via ${provider.id}/$model"
- **L565** `"Resume"` — "→ re-issue ${kind.name} \"${metaPrompt.name}\" report=$reportId row=${placeholder.id} via ${provider.id}/$model"
- **L667** `"FanIn"` — "→ start \"${metaPrompt.name}\" report=$reportId via ${pick.first.id}/${pick.second}"
- **L926** `"Meta"` — "→ start \"${metaPrompt.name}\" report=$reportId — ${picks.size} pick(s)"

### `viewmodel/StressTestEngine.kt`

- **L99** `"StressTest"` — "→ start: submitting $total report(s) with swarm '$SWARM_NAME'"
- **L107** `"StressTest"` — "← submitted $total report(s) — generating in the background"

### `viewmodel/TournamentEngine.kt`

- **L209** `"Tournament"` — "→ start report=$reportId (${successful.size} responses, ${matchCountFor(successful.size)} matches)"
- **L257** `"Tournament"` — "← done report=$reportId in ${System.currentTimeMillis() - startMs}ms"

### `viewmodel/TranslationRunManager.kt`

- **L273** `"Translation"` — "→ start $targetLanguageName ($targetLanguageNative) for report=$sourceReportId — ${itemsWithIds.size} items via ${models.size} model${if (models.size == 1) "" else "s"}"
- **L605** `"Translation"` — "← cancelled $targetLanguageName for report=$sourceReportId"
- **L615** `"Translation"` — "← done $targetLanguageName for report=$sourceReportId — ok=$okCount fail=$failCount"
- **L1097** `"Translation"` — "reconciling stalled translation runId=$runId — rebuilding in-memory state from disk"
- **L1119** `"Translation"` — "reconcile runId=$runId — placeholders present, re-dispatching via startMissingTranslations"

### `viewmodel/WorkerRunner.kt`

- **L128** `"Workers"` — "${com.ai.data.MetadataIconsHolder.current.checkMark} '${prompt.name}' via ${agent.name} (worker ${idx + 1}/$n)"

## DEBUG (107)

### `data/ApiDispatch.kt`

- **L102** `"ApiDispatch"` — "analyze ${service.id}/$model fmt=${service.apiFormat} promptLen=${prompt.length} img=${imageBase64 != null}"
- **L127** `"ApiDispatch"` — "sendChat ${service.id}/$model fmt=${service.apiFormat} msgs=${messages.size}"
- **L171** `"ApiDispatch"` — "fetchModels ${service.id} fmt=${service.apiFormat}"
- **L181** `"ApiDispatch"` — "fetchModels ${service.id} → ${result.ids.size} models in ${System.currentTimeMillis() - t0}ms"
- **L223** `"ApiDispatch"` — "embed ${service.id}/$model — ${texts.size} input(s)"
- **L1080** `"ApiDispatch"` — "testApiConnectionWithJson ${service.id} bodyLen=${jsonBody.length}"

### `data/ApiStreaming.kt`

- **L62** `"SSE"` — "stream open"
- **L155** `"SSE"` — "stream closed — $chunkCount chunks in ${System.currentTimeMillis() - parseStartMs}ms"

### `data/BackupManager.kt`

- **L174** `"Backup"` — "manifest written"
- **L180** `"Backup"` — "prefs section written (${PREFS_TO_BACKUP.size} files)"
- **L187** `"Backup"` — "filesDir mirrored — $filesWritten entries"
- **L197** `"Backup"` — "cacheDir mirrored — $cacheWritten entries"
- **L235** `"Backup"` — "manifest version=$version, staged ${staged.size} entries (${staged.values.sumOf { it.size }} bytes)"
- **L245** `"Backup"` — "prefs applied: $prefsRestored file(s)"
- **L247** `"Backup"` — "filesDir wiped (except excludes)"
- **L253** `"Backup"` — "cacheDir wiped (preserving ${tempZip.name})"
- **L255** `"Backup"` — "files applied: $filesRestored entries"

### `data/EmbeddingsStore.kt`

- **L69** `"EmbeddingsStore"` — "put $providerId/$model dim=${vector.size}"

### `data/InternalPromptIconCache.kt`

- **L113** `"InternalPromptIcon"` — "loaded ${map.size} cached icons"
- **L173** `"InternalPromptIcon"` — "recordInitial name='$name' -> $emoji via $providerId/$model" + " (in=$inputTokens out=$outputTokens cost=${inputCost + outputCost})"
- **L254** `"InternalPromptIcon"` — "pickAlternative name='$name' -> $emoji via $providerId/$model"

### `data/KnowledgeService.kt`

- **L303** `"Knowledge"` — "retrieve kbs=${kbs.size} topK=$topK queryLen=${query.length} → hits=${out.size}" + (out.firstOrNull()?.score?.let { " topScore=${"%.3f".format(it)}" } ?: "")

### `data/MetaCache.kt`

- **L53** `"MetaCache"` — "loaded ${map.size} live entries"

### `data/ModelListCache.kt`

- **L52** `"ModelListCache"` — "save $providerId bytes=${rawResponse.length}"
- **L69** `"ModelListCache"` — "hit $providerId age=${ageMs / 1000}s size=${f.length()}"
- **L72** `"ModelListCache"` — "miss $providerId (no cached file)"

### `data/OverloadedRetry.kt`

- **L39** `"Overloaded"` — "529 received on ${request.url.host}, starting retry loop (max=$maxRetries, backoff=${backoffMs}ms)"
- **L63** `"Overloaded"` — "529 retry $attempt/$maxRetries after ${sleepMs}ms on ${request.url.host}"
- **L69** `"Overloaded"` — "recovered after $attempt retry (status=${current.code})"

### `data/PricingCache.kt`

- **L325** `"PricingCache"` — "preload start"
- **L328** `"PricingCache"` — "preload done in ${System.currentTimeMillis() - t0}ms" + " (litellm=${litellmPricing?.size ?: 0}, modelsDev=${modelsDevPricing?.size ?: 0}," + " llmPrices=${llmPricesPricing?.size ?: 0}, aa=${aaPricing?.size ?: 0}," + " openrouter=${openRouterPricing?.size ?: 0}, helicone=${heliconePricing?.size ?: 0}," + " manual=${manualPricing?.size ?: 0})"

### `data/ProviderRegistry.kt`

- **L276** `"ProviderRegistry"` — "host index rebuilt — ${map.size} host(s) across ${providers.size} provider(s)"

### `data/ProviderThrottling.kt`

- **L219** `"Throttle"` — "rate-limit wait ${sleepMs}ms on $host (queue=${window.size}/$perMinuteLimit)"
- **L232** `"Throttle"` — "concurrent-cap wait ${System.currentTimeMillis() - concurrentWaitStart}ms on $host (cap=$concurrentLimit)"

### `data/RateLimitRetry.kt`

- **L123** `"RateLimit"` — "429 received on ${request.url.host}, starting retry loop (max=$maxRetries, backoff=${backoffMs}ms)"
- **L158** `"RateLimit"` — "429 retry $attempt/$maxRetries after ${sleepMs}ms on ${request.url.host}"
- **L164** `"RateLimit"` — "recovered after $attempt retry (status=${current.code})"
- **L184** `"RateLimit"` — "Retry-After=${trimmed}s on $hostForLog → sleeping ${ms}ms"
- **L195** `"RateLimit"` — "Retry-After=\"$trimmed\" on $hostForLog → sleeping ${it}ms"

### `data/SecondaryResult.kt`

- **L275** `"SecondaryResultStorage"` — "saveIfStillPresent: row ${result.id} no longer on disk, skipping save"

### `data/local/LocalEmbedder.kt`

- **L232** `"LocalEmbedder"` — "→ embed $modelName n=${inputs.size} avgLen=${if (inputs.isNotEmpty()) inputs.sumOf { it.length } / inputs.size else 0}"
- **L249** `"LocalEmbedder"` — "← embed $modelName n=${out.size} dim=${out.firstOrNull()?.size ?: 0} ${System.currentTimeMillis() - started}ms"

### `data/local/LocalLlm.kt`

- **L169** `"LocalLlm"` — "→ generate $modelName promptChars=${prompt.length}"
- **L178** `"LocalLlm"` — "← generate $modelName outChars=$outLen ${durMs}ms (${"%.1f".format(rate)} chars/s)"

### `ui/settings/SettingsPreferences.kt`

- **L149** `"SettingsPrefs"` — "loadGeneralSettings logLevel=${it.logLevel} tracing=${it.tracingEnabled} " + "streamRT=${it.streamingReadTimeoutSec}s nonStreamRT=${it.nonStreamingReadTimeoutSec}s " + "maxPerMin=${it.maxCallsPerProviderPerMinute} maxConc=${it.maxConcurrentCallsPerProvider} " + "recentReportModels=${it.recentReportModels.size}"
- **L216** `"SettingsPrefs"` — "saveGeneralSettings logLevel=${settings.logLevel} tracing=${settings.tracingEnabled} " + "streamRT=${settings.streamingReadTimeoutSec}s nonStreamRT=${settings.nonStreamingReadTimeoutSec}s " + "maxPerMin=${settings.maxCallsPerProviderPerMinute} maxConc=${settings.maxConcurrentCallsPerProvider}"

### `viewmodel/AppViewModel.kt`

- **L373** `"App.start"` — "→ Prewarm caches (ApiTracer + PricingCache)"
- **L376** `"App.start"` — "← Prewarm caches dispatched (background)"
- **L412** `startTag` — "→ Apply general settings to global singletons"
- **L443** `startTag` — "← Apply general settings done"
- **L473** `startTag` — "→ ProviderThrottle reset"
- **L475** `startTag` — "← ProviderThrottle reset done"
- **L482** `startTag` — "→ Publish initial UiState"
- **L484** `startTag` — "← Publish initial UiState done"
- **L486** `startTag` — "→ refreshAllModelLists (cache-respecting)"
- **L490** `startTag` — "← refreshAllModelLists done in ${System.currentTimeMillis() - tRefresh}ms"
- **L525** `tag` — "→ Singletons init"
- **L539** `tag` — "← Singletons init done in ${System.currentTimeMillis() - bootStart}ms"
- **L541** `tag` — "→ Load prefs"
- **L548** `tag` — "← Load prefs done in ${System.currentTimeMillis() - tLoad}ms"
- **L553** `tag` — "→ First-run seed"
- **L569** `tag` — "← First-run seed done in ${System.currentTimeMillis() - tFirst}ms"
- **L584** `tag` — "→ providers.json delta-sync"
- **L591** `tag` — "← providers.json delta-sync done in ${System.currentTimeMillis() - tSync}ms (synced=$syncCount, added=$addCount)"
- **L623** `tag` — "→ internal-prompts/ delta-merge"
- **L638** `tag` — "← internal-prompts/ delta-merge done in ${System.currentTimeMillis() - tPrompts}ms (added=$added)"
- **L640** `tag` — "← internal-prompts/ delta-merge done in ${System.currentTimeMillis() - tPrompts}ms (empty asset)"
- **L652** `tag` — "→ examples.json delta-merge"
- **L667** `tag` — "← examples.json delta-merge done in ${System.currentTimeMillis() - tExamples}ms (added=$added)"
- **L669** `tag` — "← examples.json delta-merge done in ${System.currentTimeMillis() - tExamples}ms (empty asset)"
- **L679** `tag` — "→ system-prompts.json delta-merge"
- **L694** `tag` — "← system-prompts.json delta-merge done in ${System.currentTimeMillis() - tSystemPrompts}ms (added=$added)"
- **L696** `tag` — "← system-prompts.json delta-merge done in ${System.currentTimeMillis() - tSystemPrompts}ms (empty asset)"
- **L706** `tag` — "→ workers/swarms/ delta-merge"
- **L718** `tag` — "← workers/swarms/ delta-merge done in ${System.currentTimeMillis() - tSwarms}ms (added=$added)"
- **L720** `tag` — "← workers/swarms/ delta-merge done in ${System.currentTimeMillis() - tSwarms}ms (empty asset)"
- **L729** `tag` — "→ workers/flocks/ delta-merge"
- **L741** `tag` — "← workers/flocks/ delta-merge done in ${System.currentTimeMillis() - tFlocks}ms (added=$added)"
- **L743** `tag` — "← workers/flocks/ delta-merge done in ${System.currentTimeMillis() - tFlocks}ms (empty asset)"
- **L753** `tag` — "→ excluded.json delta-merge"
- **L767** `tag` — "← excluded.json delta-merge done in ${System.currentTimeMillis() - tExcluded}ms (added=$added)"
- **L769** `tag` — "← excluded.json delta-merge done in ${System.currentTimeMillis() - tExcluded}ms (empty asset)"
- **L781** `tag` — "→ inaccessible.json delta-merge"
- **L795** `tag` — "← inaccessible.json delta-merge done in ${System.currentTimeMillis() - tInaccessible}ms (added=$added)"
- **L797** `tag` — "← inaccessible.json delta-merge done in ${System.currentTimeMillis() - tInaccessible}ms (empty asset)"
- **L803** `tag` — "→ meta.json delta-merge"
- **L817** `tag` — "← meta.json delta-merge done in ${System.currentTimeMillis() - tMeta}ms (added=$added)"
- **L819** `tag` — "← meta.json delta-merge done in ${System.currentTimeMillis() - tMeta}ms (empty asset)"
- **L826** `tag` — "bootstrap total ${System.currentTimeMillis() - bootStart}ms"
- **L1471** `"RefreshAll"` — "→ ${toRefresh.size} provider(s): ${toRefresh.joinToString { it.id }}"
- **L1527** `"RefreshAll"` — "← ok=${successful.size}/${toRefresh.size} in ${System.currentTimeMillis() - t0}ms"

### `viewmodel/ChatViewModel.kt`

- **L34** `"Chat"` — "sendChatMessageStream ${service.id}/$model msgs=${messages.size} kbs=${knowledgeBaseIds.size} web=$webSearchTool reasoning=$reasoningEffort"
- **L84** `"Chat.RAG"` — "retrieving for kbs=${knowledgeBaseIds.joinToString(",")} queryLen=${lastUser.length}"
- **L97** `"Chat.RAG"` — "retrieved ${hits.size} hit(s)"
- **L124** `"Chat"` — "sendDualChatMessage ${service.id}/$model msgs=${messages.size}"

### `viewmodel/FanOutEngine.kt`

- **L1290** `"FanOut"` — "queued pair ans=$answererAgentId src=$sourceAgentId ${provider.id}/$answererModel"
- **L1292** `"FanOut"` — "skip pair $placeholderId — deleted before launch"
- **L1368** `"FanOut"` — "← pair ans=$answererAgentId src=$sourceAgentId ${System.currentTimeMillis() - pairStart}ms"

### `viewmodel/RegenerateBatchEngine.kt`

- **L138** `"RegenBatch"` — "restart no-op: row $pausedRowId still errored"

### `viewmodel/ReportViewModel.kt`

- **L782** `"Report"` — "→ task ${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} agent=${task.resultId}${if (isRegeneration) " (regen)" else ""}"
- **L912** `"Report"` — "skip UI publish for deleted agent=${task.resultId} report=$reportId"
- **L932** `"Report"` — "← task ${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} agent=${task.resultId} " + (if (response.isSuccess) "ok" else "err") + " ${durationMs}ms" + (response.tokenUsage?.let { " in=${it.inputTokens} out=${it.outputTokens}" } ?: "") + (cost?.let { " cost=${"%.5f".format(it)}" } ?: "")

### `viewmodel/SecondaryRunManager.kt`

- **L499** `"BgResumeSweep"` — "scanning ${recent.size} report${if (recent.size == 1) "" else "s"} (last 7 days)"

### `viewmodel/TranslationRunManager.kt`

- **L708** `"Translation"` — "→ item ${item.id} \"${item.label}\" kind=${item.kind} srcLen=${item.sourceText.length}"
- **L753** `"Translation"` — "← item ${item.id} err ${callDurationMs}ms — ${response.error ?: "Empty response"}"
- **L799** `"Translation"` — "← item ${item.id} ok ${callDurationMs}ms" + (tu?.let { " in=${it.inputTokens} out=${it.outputTokens}" } ?: "") + " cost=${"%.5f".format(costDollars)}"
- **L1094** `"Translation"` — "reconcile skipped — runId=$runId has active dispatch job"

## TRACE (54)

### `data/ApiStreaming.kt`

- **L87** `"SSE"` — "[DONE] terminator (event=$eventType)"
- **L103** `"SSE"` — "chunk event=${eventType ?: "(none)"} dataBytes=${data.length} contentBytes=${content.length}"
- **L107** `"SSE"` — "final chunk (event=$eventType)"

### `data/ApiTracer.kt`

- **L164** `"ApiTracer"` — "trace written $resolvedFilename status=${normalizedTrace.response.statusCode} partial=${normalizedTrace.partial}"

### `data/ChatHistoryManager.kt`

- **L57** `"ChatHistory"` — "save ${session.id} msgs=${session.messages.size} bytes=${json.length}"
- **L83** `"ChatHistory"` — "load ${it.id} msgs=${it.messages.size}"
- **L121** `"ChatHistory"` — "delete $sessionId"

### `data/KnowledgeService.kt`

- **L312** `"Knowledge"` — " cand[$i] kb=${s.hit.kbName} src=${s.hit.sourceName} score=${"%.3f".format(s.score)} chars=${s.hit.text.length}"

### `data/PricingCache.kt`

- **L396** `"PricingCache"` — "miss ${provider.id}/$model → DEFAULT"
- **L401** `"PricingCache"` — "match ${provider.id}/$model → $tier in=${p.promptPrice * 1_000_000} out=${p.completionPrice * 1_000_000}"

### `data/TagPropagation.kt`

- **L142** `"TagPropagation"` — "submit reportId=${captured.reportId} cat=${captured.category}"

### `viewmodel/AppViewModel.kt`

- **L414** `startTag` — " ModelType.userDefaults set (${bs.first.defaultTypePaths.size} entries)"
- **L416** `startTag` — " ApiTracer.isTracingEnabled=${bs.first.tracingEnabled}"
- **L418** `startTag` — " AnalysisRepository.TEST_PROMPT=${com.ai.data.AnalysisRepository.TEST_PROMPT}"
- **L434** `startTag` — " NetworkSettings: streamRT=${bs.first.streamingReadTimeoutSec}s nonStreamRT=${bs.first.nonStreamingReadTimeoutSec}s " + "maxPerMin=${bs.first.maxCallsPerProviderPerMinute} maxConc=${bs.first.maxConcurrentCallsPerProvider} " + "maxRetries429=${bs.first.maxRetriesOn429} retryBackoff=${bs.first.retryBackoffMs429}ms " + "maxRetries529=${bs.first.maxRetriesOn529} retryBackoff529=${bs.first.retryBackoffMs529}ms"
- **L442** `startTag` — " AppLog.threshold=${bs.first.logLevel}"
- **L489** `startTag` — " refreshed ${refreshed.size} provider(s): ${refreshed.entries.joinToString { "${it.key}=${it.value}" }}"
- **L526** `tag` — " init AppLog"
- **L527** `tag` — " init ApiTracer"
- **L528** `tag` — " init AuditLog"
- **L529** `tag` — " init ChatHistoryManager"
- **L530** `tag` — " init ReportStorage"
- **L531** `tag` — " init SecondaryResultStorage"
- **L532** `tag` — " init ProviderRegistry"
- **L533** `tag` — " init ProviderFieldTimestamps"
- **L534** `tag` — " init PromptCache"
- **L535** `tag` — " init InternalPromptIconCache"
- **L536** `tag` — " init MetaCache"
- **L537** `tag` — " init TranslationModeStore"
- **L538** `tag` — " init LastReportTracker"
- **L544** `tag` — " GeneralSettings loaded (logLevel=${gs.logLevel}, tracing=${gs.tracingEnabled})"
- **L546** `tag` — " providers=${ai.providers.size} agents=${ai.agents.size} flocks=${ai.flocks.size} swarms=${ai.swarms.size}"
- **L547** `tag` — " internalPrompts=${ai.internalPrompts.size} examplePrompts=${ai.examplePrompts.size} parameters=${ai.parameters.size} systemPrompts=${ai.systemPrompts.size}"
- **L557** `tag` — " first run; isEmptyInstall=$isEmptyInstall"
- **L560** `tag` — " providers.json seed: added=$providersAdded"
- **L567** `tag` — " not a first run; skipping seed"
- **L588** `tag` — " syncFromAsset: $syncCount unedited fields refreshed"
- **L590** `tag` — " importFromAsset: $addCount new providers appended"
- **L627** `tag` — " bundled internal-prompts/ entries: ${bundled.size}"
- **L632** `tag` — " merge: before=$before merged=${merged.size} added=$added"
- **L636** `tag` — " settings saved with $added new prompts"
- **L656** `tag` — " bundled examples.json entries: ${bundled.size}"
- **L661** `tag` — " merge: before=$before merged=${merged.size} added=$added"
- **L665** `tag` — " settings saved with $added new example prompts"
- **L683** `tag` — " bundled system-prompts.json entries: ${bundled.size}"
- **L688** `tag` — " merge: before=$before merged=${merged.size} added=$added"
- **L692** `tag` — " settings saved with $added new system prompts"
- **L757** `tag` — " bundled excluded.json entries: ${bundled.size}"
- **L765** `tag` — " settings saved with $added new test-excluded entries"
- **L785** `tag` — " bundled inaccessible.json entries: ${bundled.size}"
- **L793** `tag` — " settings saved with $added new inaccessible entries"
- **L807** `tag` — " bundled meta.json entries: ${bundled.size}"
- **L815** `tag` — " settings saved with $added new default meta items"
- **L1151** `"RecentModels"` — "record $providerId/$model"

