# Application log — every write, by severity

> Generated reference. Lists every call site that writes to the in-app
> application log (`AppLog`, `data/AppLog.kt`), which mirrors
> `android.util.Log` and appends to `<filesDir>/applog/applog_<yyyyMMdd>.log`
> for any call at or above the active `threshold` (default `WARN`).
> Grouped by severity, then by source file. Each row is
> `Lnnn` `Tag` — message (message interpolations shown verbatim as written
> in source, including the `+ "…"` string concatenations on multi-line calls).
> For *how the logger works* (levels, rotation, redaction, viewer), see
> **[applog.md](applog.md)**.

**559 call sites** — 103 ERROR, 199 WARN, 93 INFO, 164 DEBUG.

> **Four severities — TRACE is gone.** `LogLevel` is now
> `DEBUG / INFO / WARN / ERROR` (plus the `OFF` sentinel). The old
> `AppLog.v` / TRACE level was removed and every former `AppLog.v` call
> now logs at DEBUG via `AppLog.d` — there is no separate TRACE section.
> Separately, the five secondary-engine batch start/end INFO lines
> (Tournament / Judge-the-judges / Fan Out / Compare / Translation) were
> dropped because the per-report **audit log** already records the same
> events — see [applog.md](applog.md).

Severity is chosen at the call site by which method is invoked:

| Method | Level | Priority | Toast? |
|---|---|---|---|
| `AppLog.d` | DEBUG | 3 | no |
| `AppLog.i` | INFO | 4 | no |
| `AppLog.w` | WARN | 5 | yes (debounced) |
| `AppLog.e` | ERROR | 6 | yes (debounced) |

`AppLog.w` also has a `w(tag, t: Throwable)` overload that derives the
message from the throwable; there is no `e(tag, t)` overload, so every
`AppLog.e` call passes an explicit message string (the throwable, when
present, rides in the optional third argument).

WARN/ERROR Toasts are debounced per `(level, tag)` key — at most one
Toast per key per `TOAST_MIN_INTERVAL_MS` (1500 ms) so a burst of retries
(e.g. fan-out icon workers all rate-limiting at once) can't flood the
screen, while an unrelated tag's later ERROR still gets through.

A handful of call sites pass a `tag` / `startTag` / `logTag` local variable
rather than a string literal. These resolve to:

| Variable | File | Value |
|---|---|---|
| `tag` | `ui/helpers/PdfExport.kt` | `"PdfExport"` |
| `tag` | `data/TracingInterceptor.kt` | `"ApiCall"` |
| `tag` | `viewmodel/AppViewModel.kt` (bootstrap region) | `"App.bootstrap"` |
| `startTag` | `viewmodel/AppViewModel.kt` (start region) | `"App.start"` |
| `logTag` | `viewmodel/SecondaryBatchEngine.kt` (abstract `protected val`) | `"Tournament"` / `"Compare"` / `"JudgeEval"` / `"TransRank"` — whichever concrete engine subclass is running |

Rows tagged `"Crash"` (one ERROR in `data/CrashReporter.kt`) write the
captured crash report — the message is the `report` string, not a literal.

> **Maintenance.** Counts and line numbers track HEAD and drift on every
> commit that touches a logging call. Regenerate by walking
> `ai/src/main/java/com/ai` for `AppLog.(d|i|w|e)(`, grouping by severity
> then file. Exclude the three string-literal examples embedded in
> `data/IconUsageData.kt` (which quote call sites as documentation).

---

## ERROR (103)

### `data/ApiTracer.kt`

- **L198** `"ApiTracer"` — "Failed to save trace ($resolvedFilename): ${e.message}"
- **L243** `"ApiTracer"` — "Cache update failed for $resolvedFilename — invalidating cache: ${e.message}"

### `data/AtomicFileWrite.kt`

- **L71** `"AtomicFileWrite"` — "Failed to write $absolutePath: ${e.message}"

### `data/BackupManager.kt`

- **L294** `"Backup"` — "Restore failed AFTER wipe: ${e.message}"

### `data/ChatHistoryManager.kt`

- **L42** `"ChatHistory"` — "Refusing to save session with suspect id ${session.id}"
- **L51** `"ChatHistory"` — "Refusing to save session that escapes historyDir: ${session.id}"
- **L70** `"ChatHistory"` — "Failed to save: ${e.message}"
- **L80** `"ChatHistory"` — "Refusing to load session with unsafe id: $sessionId"
- **L93** `"ChatHistory"` — "Failed to load: ${e.message}"
- **L108** `"ChatHistory"` — "Failed to parse: ${e.message}"
- **L140** `"ChatHistory"` — "Refusing to delete session with unsafe id: $sessionId"
- **L179** `"ChatHistory"` — "Refusing to $operation with unsafe id: $sessionId"
- **L198** `"ChatHistory"` — "Failed to $operation: ${e.message}"
- **L292** `"ChatHistory"` — "Failed to parse header: ${e.message}"

### `data/CrashReporter.kt`

- **L107** `"Crash"` — report

### `data/Knowledge.kt`

- **L209** `"Knowledge"` — "Refusing to save source with suspect id ${source.id}"
- **L216** `"Knowledge"` — "Refusing to save source that escapes chunks dir: ${source.id}"
- **L315** `"Knowledge"` — "Refusing to resolve KB dir for suspect id $kbId"
- **L327** `"Knowledge"` — "Refusing to resolve KB dir that escapes root: $kbId"

### `data/PricingCache.kt`

- **L428** `"PricingCache"` — "ensureLoadedBlocking invoked on the main thread — refusing to mark preload complete. " + "Move the call to Dispatchers.IO."
- **L1002** `"PricingCache"` — "Online LITELLM refresh failed: ${e.message}"
- **L1041** `"PricingCache"` — "models.dev refresh failed: ${e.message}"
- **L1210** `"PricingCache"` — "Helicone refresh failed: ${e.message}"
- **L1299** `"PricingCache"` — "llm-prices refresh failed: ${e.message}"
- **L1376** `"PricingCache"` — "Artificial Analysis refresh failed: ${e.message}"
- **L1440** `"PricingCache"` — "Requesty refresh failed: ${e.message}"
- **L1532** `"PricingCache"` — "llm-stats refresh failed: ${e.message}"
- **L1597** `"PricingCache"` — "genai-prices refresh failed: ${e.message}"
- **L1664** `"PricingCache"` — "TrueFoundry refresh failed: ${e.message}"
- **L1827** `"PricingCache"` — "CloudPrice refresh failed: ${e.message}"
- **L2303** `"PricingCache"` — "Failed: ${e.message}"

### `data/ProviderRegistry.kt`

- **L51** `"ProviderRegistry"` — "Error loading from prefs: ${e.message}"

### `data/RegenerateBatchStorage.kt`

- **L36** `"RegenerateBatchStorage"` — "Refusing to resolve job file for suspect id $reportId"
- **L41** `"RegenerateBatchStorage"` — "Refusing to resolve job file that escapes root: $reportId"

### `data/ReportStorage.kt`

- **L561** `"ReportStorage"` — "Failed to load report $reportId: ${e.message}"
- **L606** `"ReportStorage"` — "Failed to load notes for report $reportId: ${e.message}"
- **L624** `"ReportStorage"` — "Failed to load ${file.name}: $message"
- **L642** `"ReportStorage"` — "Dropping report with null id (corrupt file)"
- **L726** `"ReportStorage"` — "Refusing to save report with suspect id ${report.id}"
- **L731** `"ReportStorage"` — "Refusing to save report that escapes reportsDir: ${report.id}"
- **L740** `"ReportStorage"` — "Failed to save report ${report.id} (writeTextAtomic returned false)"
- **L2666** `"ReportStorage"` — "Refusing to overwrite existing report ${report.id} via persistNewReport"

### `data/SecondaryResult.kt`

- **L73** `"SecondaryResultStorage"` — "Refusing to resolve report dir for suspect id $reportId"
- **L78** `"SecondaryResultStorage"` — "Refusing to resolve report dir that escapes root: $reportId"
- **L96** `"SecondaryResultStorage"` — "Refusing to resolve report dir for suspect id $reportId"
- **L101** `"SecondaryResultStorage"` — "Refusing to resolve report dir that escapes root: $reportId"
- **L171** `"SecondaryResultStorage"` — "Refusing to save result with suspect id ${result.id}"
- **L183** `"SecondaryResultStorage"` — "Refusing to save result that escapes report dir: ${result.id}"
- **L187** `"SecondaryResultStorage"` — "Failed to save result ${result.id}"
- **L237** `"SecondaryResultStorage"` — "Refusing to save result that escapes report dir: ${result.id}"
- **L241** `"SecondaryResultStorage"` — "Failed to save result ${result.id}"
- **L372** `"SecondaryResultStorage"` — "Refusing to update result with suspect id $resultId"
- **L381** `"SecondaryResultStorage"` — "Refusing to update result that escapes report dir: $resultId"
- **L387** `"SecondaryResultStorage"` — "Failed to update result $resultId"
- **L510** `"SecondaryResultStorage"` — "Refusing to save result with suspect id ${result.id}"
- **L526** `"SecondaryResultStorage"` — "Refusing to save result that escapes report dir: ${result.id}"
- **L539** `"SecondaryResultStorage"` — "Failed to save result ${result.id}"
- **L623** `"SecondaryResultStorage"` — "Failed to write result $resultId"
- **L654** `"SecondaryResultStorage"` — "Failed to write result $resultId"
- **L688** `"SecondaryResultStorage"` — "Failed to write result $resultId"
- **L718** `"SecondaryResultStorage"` — "Failed to write result $resultId"
- **L750** `"SecondaryResultStorage"` — "Failed to write result $resultId"
- **L777** `"SecondaryResultStorage"` — "Failed to write result $resultId"
- **L810** `"SecondaryResultStorage"` — "Failed to write result $resultId"
- **L845** `"SecondaryResultStorage"` — "Failed to write result $resultId"
- **L878** `"SecondaryResultStorage"` — "Failed to write result $resultId"
- **L909** `"SecondaryResultStorage"` — "Failed to write result $resultId"
- **L938** `"SecondaryResultStorage"` — "Failed to write result $resultId"
- **L991** `"SecondaryResultStorage"` — "Failed to write result $resultId"
- **L1031** `"SecondaryResultStorage"` — "Failed to write result $resultId"
- **L1062** `"SecondaryResultStorage"` — "Failed to write result $resultId"
- **L1096** `"SecondaryResultStorage"` — "Failed to write result $resultId"
- **L1127** `"SecondaryResultStorage"` — "Failed to write result $resultId"
- **L1170** `"SecondaryResultStorage"` — "Failed to write result $resultId"
- **L1210** `"SecondaryResultStorage"` — "Failed to write result $resultId"

### `data/local/LlmRuntime.kt`

- **L80** `"LlmRuntime"` — "load failed: ${t.message}"
- **L124** `"LlmRuntime"` — "AAR did not contain $AAR_ENTRY"
- **L144** `"LlmRuntime"` — "download failed: ${e.message}"

### `data/local/LocalEmbedder.kt`

- **L143** `"LocalEmbedder"` — "model ${spec.name} download failed: ${e.message}"
- **L279** `"LocalEmbedder"` — "embed failed: ${e.message}"

### `data/local/LocalLlm.kt`

- **L193** `"LocalLlm"` — "generate failed: ${e.message}"

### `ui/helpers/PdfExport.kt`

- **L592** `tag` — "PDF render failed"
- **L695** `tag` — "onReceivedError code=${error.errorCode} desc='${error.description}' url=${request.url} at +${elapsedMs()}ms"
- **L698** `tag` — "onReceivedHttpError status=${errorResponse.statusCode} url=${request.url} at +${elapsedMs()}ms"
- **L711** `tag` — "renderHtmlToPdfFile failed at +${elapsedMs()}ms: ${e.javaClass.simpleName}: ${e.message}"

### `ui/helpers/ReportExportScreen.kt`

- **L140** `"ReportExport"` — "Export failed"
- **L180** `"ReportExport"` — "Export all failed"

### `ui/settings/ImportExportScreen.kt`

- **L902** `"ImportExport"` — "Import file read error"
- **L1180** `"ImportExport"` — "AI Report import error"
- **L1220** `"ImportExport"` — "API keys import parse error"
- **L1223** `"ImportExport"` — "API keys import error"

### `ui/settings/LocalRuntimeScreens.kt`

- **L373** `"LocalRuntime"` — "tflite import: openInputStream returned null for $uri"
- **L381** `"LocalRuntime"` — "tflite import: copy produced empty file for $sanitized"
- **L387** `"LocalRuntime"` — "tflite import: rename failed for $sanitized"
- **L403** `"LocalRuntime"` — "tflite import: '$sanitized' is not a usable MediaPipe text embedder"
- **L409** `"LocalRuntime"` — "tflite import failed: ${e.message}"
- **L438** `"LocalRuntime"` — "LLM import: unsupported file type '$displayName' (expected .task, .zip, .tar, .tar.gz, or .tgz)"
- **L475** `"LocalRuntime"` — "LLM import: openInputStream returned null for $uri"
- **L501** `"LocalRuntime"` — "LLM import: staged file empty for $displayName"
- **L506** `"LocalRuntime"` — "LLM import: rename failed for $displayName"
- **L512** `"LocalRuntime"` — "LLM import failed: ${e.message}"

### `ui/settings/ReportExportScreen.kt`

- **L75** `"ImportExport"` — "Report folder-export failed for $id"

### `viewmodel/AppViewModel.kt`

- **L1208** `"Housekeeping"` — "← Reset application FAILED"


## WARN (199)

### `data/AnalysisRepository.kt`

- **L323** `"AiAnalysis"` — "Tool fallback also failed for ${agent.name}: " + "first=${first.httpStatusCode}/${first.error?.take(120)}; " + "fallback=${retried.httpStatusCode}/${retried.error?.take(120)}"
- **L436** `"AiAnalysis"` — "Streaming attempt failed for ${agent.name} (${e.message}); using non-streaming"
- **L483** `"AiAnalysis"` — "$label first attempt permanent failure, skipping retry"
- **L486** `"AiAnalysis"` — "$label first attempt failed, retrying..."
- **L508** `"AiAnalysis"` — "$label first attempt I/O failure: ${e.message}, retrying…"

### `data/ApiClient.kt`

- **L344** `"ApiClient"` — "fetchUrlAsString non-2xx ${resp.code} for $url — raw snapshot skipped"
- **L351** `"ApiClient"` — "fetchUrlAsString failed for $url: ${e.message}"
- **L368** `"ApiClient"` — "fetchUrlAsBytes non-2xx ${resp.code} for $url"
- **L373** `"ApiClient"` — "fetchUrlAsBytes failed for $url: ${e.message}"

### `data/ApiDispatch.kt`

- **L132** `"ApiDispatch"` — "Unable to resolve throttle host for baseUrl=$baseUrl; proceeding without coroutine host gate"

### `data/ApiDispatchBuilders.kt`

- **L295** `"ApiDispatch"` — "Anthropic reasoning override: max_tokens raised from $baseMax to $effectiveMax (thinking budget=$budget)"

### `data/ApiDispatchModels.kt`

- **L31** `"ApiDispatch"` — "OpenRouter listModelsDetailed threw: ${e.javaClass.simpleName}: ${e.message}"
- **L121** `"ApiDispatch"` — "Native capability listModels HTTP ${resp.code()}: ${body ?: "(no body)"}"
- **L125** `"ApiDispatch"` — "Native capability listModels threw: ${e.javaClass.simpleName}: ${e.message}"
- **L222** `"ApiDispatch"` — "Anthropic listModels threw: ${e.javaClass.simpleName}: ${e.message}"
- **L227** `"ApiDispatch"` — "Anthropic listModels HTTP ${response.code()}: ${body ?: "(no body)"}"
- **L232** `"ApiDispatch"` — "Anthropic listModels returned 200 but no claude-* entries (data size=${response.body()?.data?.size ?: 0})"
- **L313** `"ApiDispatch"` — "Gemini listModels HTTP ${response.code()}: ${body ?: "(no body)"}"
- **L321** `"ApiDispatch"` — "Gemini listModels threw: ${e.javaClass.simpleName}: ${e.message}"

### `data/ApiTracer.kt`

- **L202** `"ApiTracer"` — "writeTextAtomic returned false for $resolvedFilename — skipping cache update"
- **L213** `"ApiTracer"` — "Trace $resolvedFilename was removed before cache update — skipping cache entry"

### `data/AtomicFileWrite.kt`

- **L74** `"AtomicFileWrite"` — "Failed to delete temp file ${tmp.absolutePath}"
- **L77** `"AtomicFileWrite"` — "Failed to delete temp file ${tmp.absolutePath}: ${cleanupError.message}"
- **L91** `"AtomicFileWrite"` — "Failed to prune stale temp file ${stale.absolutePath}"
- **L94** `"AtomicFileWrite"` — "Failed to prune stale temp file ${stale.absolutePath}: ${e.message}"

### `data/BackupManager.kt`

- **L218** `"Backup"` — "Backup skipped $filesSkipped unreadable file(s); see earlier warnings for paths"
- **L344** `"Backup"` — "Skipping zip entry that escapes filesDir: $name"
- **L355** `"Backup"` — "Skipping zip entry that escapes cacheDir: $name"
- **L426** `"Backup"` — "Skipping prefs entry not in allowlist: $prefsName"
- **L574** `"Backup"` — "applyPrefs($name): unknown type tag '$tag' for key '$k' — entry skipped"
- **L596** `"Backup"` — "Skipping unreadable directory during backup: ${dir.absolutePath}"
- **L628** `"Backup"` — "Skipping symlink that escapes ${dir.absolutePath}: ${child.absolutePath} → $childCanonical"
- **L633** `"Backup"` — "Skipping path that cannot be resolved during backup: ${child.absolutePath}"
- **L648** `"Backup"` — "Skipping unreadable file during backup: ${child.absolutePath}"

### `data/ChatHistoryManager.kt`

- **L36** `"ChatHistory"` — "Not initialized"
- **L78** `"ChatHistory"` — "Not initialized"
- **L177** `"ChatHistory"` — "Not initialized"

### `data/DefaultMetaItemSeed.kt`

- **L49** `"DefaultMetaItemSeed"` — "Failed to load meta.json: ${e.message}"

### `data/EmbeddingsStore.kt`

- **L69** `"EmbeddingsStore"` — "get($docId, $providerId, $model) ignored empty cached vector"
- **L72** `"EmbeddingsStore"` — "get($docId, $providerId, $model) invalidated dim ${vector.size} cached vector; expected $expectedDim"
- **L90** `"EmbeddingsStore"` — "put($docId, $providerId, $model) refused empty vector"
- **L99** `"EmbeddingsStore"` — "put($docId, $providerId, $model) failed to write ${f.absolutePath}"
- **L136** `"EmbeddingsStore"` — "cosine: dim mismatch a=${a.size} b=${b.size} — embedder swapped without re-embed?"
- **L160** `"EmbeddingsStore"` — "cosine: dim mismatch a=${a.size} b=${b.size} — embedder swapped without re-embed?"

### `data/ExamplePromptSeed.kt`

- **L44** `"ExamplePromptSeed"` — "Skipped example file $file: ${ex.message}"
- **L49** `"ExamplePromptSeed"` — "Failed to load $DIR/: ${e.message}"
- **L95** `"ExamplePromptSeed"` — "upsertFromJson failed: ${e.message}"

### `data/FlockSeed.kt`

- **L56** `"FlockSeed"` — "Skipped flock file $file: ${e.message}"
- **L61** `"FlockSeed"` — "Failed to load $DIR/: ${e.message}"

### `data/InaccessibleSeed.kt`

- **L42** `"InaccessibleSeed"` — "Failed to load inaccessible.json: ${e.message}"

### `data/InternalPromptIconCache.kt`

- **L115** `"InternalPromptIcon"` — "load failed: ${e.message}"
- **L308** `"InternalPromptIcon"` — "save failed: ${e.message}"

### `data/InternalPromptSeed.kt`

- **L126** `"InternalPromptSeed"` — "Missing body for $dir/$stem.txt: ${e.message}"
- **L150** `"InternalPromptSeed"` — "Failed to load internal-prompts/: ${e.message}"
- **L230** `"InternalPromptSeed"` — "upsertFromJson failed: ${e.message}"

### `data/Knowledge.kt`

- **L245** `"Knowledge"` — "Embedding dim mismatch on saveSource: kb=$kbId, " + "manifest=${current.embeddingDim}, new=$embeddingDim. " + "Manifest dim retained; cosine queries against this " + "source will silent-zero against chunks from other " + "embedders. Re-create the KB to mix embedders cleanly."
- **L262** `"Knowledge"` — "Refusing to delete source with suspect id $sourceId"
- **L269** `"Knowledge"` — "Refusing to delete source that escapes chunks dir: $sourceId"
- **L298** `"Knowledge"` — "forEachChunk: skipped unreadable chunk file ${f.name}"
- **L308** `"Knowledge"` — "forEachChunk: skipped $skipped corrupt chunk(s) in ${f.name}"
- **L368** `"Knowledge"` — "Dropping invalid source from KB manifest ${kbDir.name}"

### `data/KnowledgeService.kt`

- **L228** `"Knowledge"` — "Embedder mismatch across attached KBs (${first.name} vs ${mismatch.name}); using ${first.name}'s"
- **L278** `"KnowledgeService"` — "KB '${kb.name}' (${kb.id}) has chunks with dim=$it; query dim=${queryVec.size}. " + "Re-index the KB with the current embedder."

### `data/MetaCache.kt`

- **L56** `"MetaCache"` — "load failed: ${e.message}"
- **L129** `"MetaCache"` — "save failed: ${e.message}"

### `data/ModelCooldownStore.kt`

- **L136** `"ModelCooldown"` — "$providerId/$model benched until ${java.util.Date(availableAtMs)}" + (traceFile?.let { " (trace $it)" } ?: "")

### `data/ModelListCache.kt`

- **L67** `"ModelListCache"` — "save($providerId) failed: ${e.message}"
- **L91** `"ModelListCache"` — "read($providerId) failed: ${e.message}"

### `data/ModelTestRunStore.kt`

- **L32** `"ModelTestRunStore"` — "save failed: ${e.message}"
- **L44** `"ModelTestRunStore"` — "load failed: ${e.message}"

### `data/OverloadedRetry.kt`

- **L95** `"Overloaded"` — "529 still present after $attempt retries on ${request.url.host}"

### `data/PricingCache.kt`

- **L1024** `"PricingCache"` — "models.dev refresh: empty / failed response"
- **L1194** `"PricingCache"` — "Helicone refresh: empty / failed response"
- **L1351** `"PricingCache"` — "Artificial Analysis refresh skipped: missing API key"
- **L1360** `"PricingCache"` — "Artificial Analysis refresh: empty / failed response"
- **L1424** `"PricingCache"` — "Requesty refresh: empty / failed response"
- **L1500** `"PricingCache"` — "llm-stats refresh skipped: missing API key"
- **L1581** `"PricingCache"` — "genai-prices refresh: empty / failed response"
- **L1648** `"PricingCache"` — "TrueFoundry refresh: empty / failed download"

### `data/PromptTranslationStore.kt`

- **L54** `"PromptTranslationStore"` — "put failed: ${e.message}"

### `data/ProviderFieldTimestamps.kt`

- **L46** `"ProviderFieldTimestamps"` — "load failed: ${e.message}"

### `data/ProviderRegistry.kt`

- **L77** `"ProviderRegistry"` — "Skipped bundled provider file $file: ${e.message}"
- **L82** `"ProviderRegistry"` — "readBundledProviderDefs failed: ${e.message}"
- **L103** `"ProviderRegistry"` — "Skipped bundled provider ${def.id}: ${e.message}"
- **L129** `"ProviderRegistry"` — "Skipped imported provider ${def.id}: ${e.message}"
- **L140** `"ProviderRegistry"` — "upsertFromJson failed: ${e.message}"
- **L159** `"ProviderRegistry"` — "Skipping malformed provider entry (id=$id, baseUrl=$baseUrl)"
- **L164** `"ProviderRegistry"` — "Skipping provider $id — toAppService threw: ${e.message}"
- **L193** `"ProviderRegistry"` — "Refusing to add duplicate provider id ${service.id}; existing entry kept"
- **L267** `"ProviderRegistry"` — "syncFromAsset failed: ${e.message}"

### `data/RateLimitRetry.kt`

- **L108** `"RateLimit"` — "long 429 on $host but provider/model unresolved (provider=$providerId model=$model)"
- **L193** `"RateLimit"` — "429 still present after $attempt retries on ${request.url.host}"

### `data/RegenerateBatchStorage.kt`

- **L55** `"RegenerateBatchStorage"` — "parse failed for $reportId: ${e.message}"
- **L82** `"RegenerateBatchStorage"` — "parse failed for $reportId: ${e.message}"

### `data/ReportBundle.kt`

- **L292** `"ImportExport"` — "skipped bad secondary $key: ${e.message}"
- **L323** `"ImportExport"` — "skipped bad trace $key: ${e.message}"

### `data/ReportStorage.kt`

- **L483** `"ReportStorage"` — "Refusing to delete report with suspect id $reportId"
- **L490** `"ReportStorage"` — "Refusing to delete report that escapes reportsDir: $reportId"
- **L496** `"ReportStorage"` — "Failed to delete report file for $reportId; skipping cascade"
- **L554** `"ReportStorage"` — "Rejected reportId with path traversal markers: $reportId"
- **L571** `"ReportStorage"` — "Rejected reportId with path traversal markers: $reportId"

### `data/SecondaryResult.kt`

- **L161** `"SecondaryResultStorage"` — "Skipping save for deleted report ${result.reportId}"
- **L177** `"SecondaryResultStorage"` — "Skipping save for deleted report ${result.reportId}"
- **L193** `"SecondaryResultStorage"` — "Removed late save for deleted report ${result.reportId}"

### `data/SwarmSeed.kt`

- **L39** `"SwarmSeed"` — "Skipped swarm file $file: ${e.message}"
- **L44** `"SwarmSeed"` — "Failed to load $DIR/: ${e.message}"

### `data/SystemPromptSeed.kt`

- **L43** `"SystemPromptSeed"` — "Skipped system-prompt file $file: ${ex.message}"
- **L48** `"SystemPromptSeed"` — "Failed to load $DIR/: ${e.message}"

### `data/TestExcludedSeed.kt`

- **L36** `"TestExcludedSeed"` — "Failed to load excluded.json: ${e.message}"

### `data/TracingInterceptor.kt`

- **L97** `tag` — "${MetadataIconsHolder.current.crossMark} $callLabel — ${e.javaClass.simpleName}: ${e.message ?: ""} (${System.currentTimeMillis() - callStart}ms)"
- **L155** `tag` — "← ${response.code} $callLabel in ${durationMs}ms$tail"
- **L169** `tag` — "← ${response.code} $callLabel in ${durationMs}ms"

### `data/local/LocalEmbedder.kt`

- **L183** `"LocalEmbedder"` — "removed $removed stale partial download${if (removed == 1) "" else "s"}"

### `ui/helpers/BulkExport.kt`

- **L142** `"BulkExport"` — "PDF render produced no output: ${f.name}"

### `ui/settings/ImportExportScreen.kt`

- **L114** `"ImportExport"` — "Skipped $name entry: ${e.message}"
- **L159** `"ImportExport"` — "Skipped flock entry: ${e.message}"
- **L255** `"ImportExport"` — "Skipped runtime report entry: ${e.message}"
- **L275** `"ImportExport"` — "Skipped secondary row: ${e.message}"
- **L301** `"ImportExport"` — "Skipped chat session entry: ${e.message}"
- **L552** `"ImportExport"` — "Skipped model list for unknown provider $key"
- **L640** `"ImportExport"` — "Skipped endpoints for unknown provider $key"
- **L646** `"ImportExport"` — "Skipped endpoint entry: ${e.message}"
- **L666** `"ImportExport"` — "Skipped parameters entry: ${e.message}"
- **L682** `"ImportExport"` — "Skipped model type override entry: ${e.message}"
- **L701** `"ImportExport"` — "Skipped model cooldowns blob: ${e.message}"
- **L715** `"ImportExport"` — "Skipped system prompt entry: ${e.message}"
- **L732** `"ImportExport"` — "Skipped blocked model entry: ${e.message}"
- **L749** `"ImportExport"` — "Skipped test-excluded model entry: ${e.message}"
- **L766** `"ImportExport"` — "Skipped inaccessible model entry: ${e.message}"
- **L1550** `"ImportExport"` — "Bundle apiKeys section failed: ${e.message}"
- **L1576** `"ImportExport"` — "Bundle costs section failed: ${e.message}"

### `viewmodel/AppViewModel.kt`

- **L493** `"CapsWatch"` — "POSSIBLE STALL — throttle state frozen ${stalledTicks * 15}s — $line"
- **L659** `tag` — "First-run providers.json import failed"
- **L690** `tag` — "← providers.json delta-sync failed in ${System.currentTimeMillis() - tSync}ms"
- **L740** `tag` — "← internal-prompts/ delta-merge failed in ${System.currentTimeMillis() - tPrompts}ms"
- **L769** `tag` — "← prompts/examples/ delta-merge failed in ${System.currentTimeMillis() - tExamples}ms"
- **L796** `tag` — "← prompts/system/ delta-merge failed in ${System.currentTimeMillis() - tSystemPrompts}ms"
- **L821** `tag` — "← workers/swarms/ delta-merge failed in ${System.currentTimeMillis() - tSwarms}ms"
- **L844** `tag` — "← workers/flocks/ delta-merge failed in ${System.currentTimeMillis() - tFlocks}ms"
- **L870** `tag` — "← excluded.json delta-merge failed in ${System.currentTimeMillis() - tExcluded}ms"
- **L898** `tag` — "← inaccessible.json delta-merge failed in ${System.currentTimeMillis() - tInaccessible}ms"
- **L920** `tag` — "← meta.json delta-merge failed in ${System.currentTimeMillis() - tMeta}ms"
- **L1162** `"App"` — "providers.json reload failed during reset"
- **L1564** `"App"` — "Failed to fetch models for ${service.id}: ${e.message}"
- **L2061** `"RefreshAll"` — "model fetch failed for ${service.id}: ${it.message}"

### `viewmodel/ChatViewModel.kt`

- **L102** `"Chat.RAG"` — "Retrieval failed for kbs=$knowledgeBaseIds: ${e.javaClass.simpleName}: ${e.message}"

### `viewmodel/CompareEngine.kt`

- **L193** `"Compare"` — "meta_compare prompt not configured / no runnable workers — aborting"
- **L206** `"Compare"` — "nothing to compare (answers=${successful.size}, meta=${metaRows.size})"

### `viewmodel/FanOutEngine.kt`

- **L1424** `"FanOut"` — "pair ans=$answererAgentId src=$sourceAgentId timed out after ${ceilingSec}s" (the "Batch item" timeout setting, default 180 s)
- **L1443** `"FanOut"` — "pair ans=$answererAgentId src=$sourceAgentId threw ${e.javaClass.simpleName}: ${e.message}"
- **L1636** `"FanOut"` — "continue broken batch failed runKey=$runKey: ${e.javaClass.simpleName}: ${e.message}"
- **L2115** `"FanOut"` — "rerun pairs failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"
- **L2129** `"FanOut"` — "resume stale runs failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"

### `viewmodel/IconGenerationManager.kt`

- **L1180** `"InternalPromptIcon"` — "second/meta not configured — skipping"
- **L1223** `"InternalPromptIcon"` — "no worker produced an icon for name='${prompt.name}'"
- **L1286** `"InternalPromptIconAlt"` — "alt/meta not configured — skipping fan-out"
- **L1404** `"InternalPromptIconAlt"` — "exception for ${item.provider.id}/${item.model}: ${e.message}"
- **L1512** `"PairIconAlt"` — "alt/fan_out prompt not found — skipping (pair=$pairId)"
- **L1691** `"PairTitleAlt"` — "alt/model_title prompt not found — skipping (pair=$pairId)"
- **L1836** `"TranslationIcon"` — "translation/icon not configured — skipping"
- **L1875** `"TranslationIcon"` — "no worker produced an icon for language='$language'"
- **L1909** `"TranslationIconAlt"` — "alt/translation not configured — skipping fan-out"
- **L2015** `"TranslationIconAlt"` — "exception for ${item.provider.id}/${item.model}: ${e.message}"
- **L2400** `"LanguageIconAlt"` — "no detected language on report=$reportId — skipping fan-out"
- **L2542** `"AgentIconAlt"` — "alt/report prompt not found — skipping (agent=$agentId)"
- **L2843** `"FanMeta"` — "fan/meta not configured — skipping"
- **L3144** `"FanMeta"` — "continue broken batch failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"

### `viewmodel/JudgeEvalEngine.kt`

- **L221** `"JudgeEval"` — "tournament prompt not configured — aborting"
- **L230** `"JudgeEval"` — "no completed Tournament judges to evaluate — aborting"
- **L440** `"JudgeEval"` — "recompute aggregate failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"

### `viewmodel/RegenerateBatchEngine.kt`

- **L276** `"RegenBatch"` — "orchestrator crashed for $reportId: ${e.message}"
- **L359** `"RegenBatch"` — "phase $phase timed out for $reportId — pausing"

### `viewmodel/ReportViewModel.kt`

- **L821** `"Report"` — "skip benched ${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} — marking agent ${task.resultId} errored"
- **L1816** `"Report"` — "background report skipped — no active models for swarm $swarmId"

### `viewmodel/SecondaryBatchEngine.kt`

- **L342** `logTag` — "hydrate failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"
- **L378** `logTag` — "resume stale runs failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"
- **L454** `logTag` — "continue broken batch failed report=${reportIdOf(runKey)}: ${e.javaClass.simpleName}: ${e.message}"

### `viewmodel/SecondaryRunManager.kt`

- **L55** `"BrokenScan"` — "debounced refresh failed: ${e.javaClass.simpleName}: ${e.message}"
- **L607** `"SecondaryResume"` — "resume stale runs failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"
- **L643** `"BrokenScan"` — "startup finalize failed: ${e.javaClass.simpleName}: ${e.message}"
- **L651** `"BrokenScan"` — "iteration failed: ${e.javaClass.simpleName}: ${e.message}"
- **L1546** `"Secondary"` — "skip benched ${provider.id}/$model — marking row ${placeholder.id} errored"

### `viewmodel/StressTestEngine.kt`

- **L135** `"StressTest"` — "failed: ${e.javaClass.simpleName}: ${e.message}"

### `viewmodel/TournamentEngine.kt`

- **L194** `"Tournament"` — "workers/tournament not configured — aborting"
- **L408** `"Tournament"` — "recompute aggregate failed report=$reportId method=${run.selectedMethod}: ${e.javaClass.simpleName}: ${e.message}"

### `viewmodel/TranslationRunManager.kt`

- **L396** `"Translation"` — "no workers/translate-text|title prompt — marking all items error"
- **L1133** `"Translate"` — "continue broken batch failed run=$runId: ${e.javaClass.simpleName}: ${e.message}"
- **L1615** `"Meta-xlate"` — "No existing translation run for $targetLanguageName — skipping cross-translate"
- **L1666** `"Meta-xlate"` — "Could not rebuild persisted state for run $runId — aborting cross-translate"
- **L1773** `"Translate-missing"` — "Could not rebuild persisted state for run $runId — aborting"

### `viewmodel/TranslatorRankEngine.kt`

- **L262** `"TransRank"` — "translate-rank prompt not configured — aborting"
- **L268** `"TransRank"` — "no translators to rank in the connected Translation batch — aborting"
- **L277** `"TransRank"` — "nothing to rank (judges=${judges.size})"
- **L463** `"TransRank"` — "recompute aggregate failed report=${run.reportId}: ${e.javaClass.simpleName}: ${e.message}"

### `viewmodel/WorkerRunner.kt`

- **L152** `"Workers"` — "prompt '${prompt.name}' has no runnable workers — nothing to run"
- **L225** `"Workers"` — "429 '${prompt.name}' via ${agent.name} — cooling ${waitMs}ms, next worker"
- **L233** `"Workers"` — "${resp.httpStatusCode ?: "model-gone"} '${prompt.name}' via ${agent.name} — model unavailable, disabling this worker for the session"
- **L238** `"Workers"` — "no usable result '${prompt.name}' via ${agent.name} — next worker"
- **L239** `"Workers"` — "miss '${prompt.name}' via ${agent.name}: ${resp.error?.take(80)}"


## INFO (93)

### `data/AnalysisRepository.kt`

- **L328** `"AiAnalysis"` — "Tool fallback succeeded for ${agent.name} " + "after first=${first.httpStatusCode}/${first.error?.take(120)}"

### `data/ApiTracer.kt`

- **L247** `"ApiTracer"` — "Pruned $pruned old trace file(s)"

### `data/BackupManager.kt`

- **L171** `"Backup"` — "→ backup start"
- **L220** `"Backup"` — "← backup done in ${System.currentTimeMillis() - t0}ms (filesDir=$filesWritten cacheDir=$cacheWritten skipped=$filesSkipped)"
- **L230** `"Backup"` — "→ restore start"
- **L291** `"Backup"` — "← restore done in ${System.currentTimeMillis() - t0}ms (prefs=$prefsRestored files=$filesRestored)"

### `data/InternalPromptIconCache.kt`

- **L278** `"InternalPromptIcon"` — "clearAll dropped $n cached icons"

### `data/KnowledgeService.kt`

- **L122** `"Knowledge"` — "→ index \"$displayName\" type=$type kb=$kbId textLen=${text.length}"
- **L198** `"Knowledge"` — "← index \"$displayName\" kb=$kbId chunks=${chunks.size} chars=${src.charCount} dim=$embeddingDim in ${System.currentTimeMillis() - indexStart}ms"

### `data/MetaCache.kt`

- **L112** `"MetaCache"` — "clearAll dropped $n entries"

### `data/PricingCache.kt`

- **L1028** `"PricingCache"` — "models.dev parse: ${pricing.size} priced, ${meta.size} meta entries (raw ${json.length} bytes)"
- **L1198** `"PricingCache"` — "Helicone parse: ${exact.size} exact, ${patterns.size} patterns"
- **L1289** `"PricingCache"` — "llm-prices parse: ${combined.size} entries from ${llmPricesVendors.size} vendors"
- **L1364** `"PricingCache"` — "Artificial Analysis parse: ${pricing.size} priced, ${meta.size} meta entries"
- **L1428** `"PricingCache"` — "Requesty parse: ${pricing.size} priced, ${meta.size} meta entries"
- **L1520** `"PricingCache"` — "llm-stats parse: ${pricing.size} priced, ${meta.size} meta entries ($pages pages)"
- **L1585** `"PricingCache"` — "genai-prices parse: ${pricing.size} priced, ${meta.size} meta entries"
- **L1652** `"PricingCache"` — "TrueFoundry parse: ${pricing.size} priced, ${meta.size} meta entries (${bytes.size} archive bytes)"
- **L1817** `"PricingCache"` — "CloudPrice parse: ${meta.size} meta entries ($pages pages)"

### `data/PromptTranslationStore.kt`

- **L81** `"PromptTranslationStore"` — "deleted $n files for $lang"
- **L128** `"PromptTranslationStore"` — "translated $done/${baseline.size} prompts into $targetLanguage from $sourceLanguage"

### `data/ProviderRegistry.kt`

- **L199** `"ProviderRegistry"` — "added ${service.id} (baseUrl=${service.baseUrl})"
- **L219** `"ProviderRegistry"` — "updated ${service.id} changed=${changed.joinToString(",")}"
- **L227** `"ProviderRegistry"` — "removed $id"
- **L258** `"ProviderRegistry"` — "syncFromAsset: ${asset.id} pulled ${take.joinToString()}"

### `data/TracingInterceptor.kt`

- **L92** `tag` — "→ $callLabel"
- **L157** `tag` — "← ${response.code} $callLabel in ${durationMs}ms"
- **L171** `tag` — "← ${response.code} $callLabel in ${durationMs}ms"

### `data/local/LlmRuntime.kt`

- **L77** `"LlmRuntime"` — "loaded ${runtimeFile(context).absolutePath}"
- **L140** `"LlmRuntime"` — "downloaded $SO_NAME (${target.length() / (1024 * 1024)} MiB)"

### `data/local/LocalLlm.kt`

- **L136** `"LocalLlm"` — "→ load $modelName from ${file.name} (${file.length() / (1024 * 1024)} MiB)"
- **L146** `"LocalLlm"` — "← loaded $modelName in ${System.currentTimeMillis() - loadStart}ms"

### `ui/admin/AppLogScreen.kt`

- **L188** `"Housekeeping"` — "Cleared $it log file(s)"
- **L215** `"Housekeeping"` — "Deleted $it log file(s) older than 7 days"

### `ui/helpers/PdfExport.kt`

- **L517** `tag` — "renderHtmlToPdfFile: starting, html=${html.length} chars, out=${output.absolutePath}, withToc=$withTocPage, thread=${Thread.currentThread().name}, timeoutMs=$timeoutMs"
- **L539** `tag` — "contentHeightCss=${view.contentHeight}, contentPx=$contentPx, totalHeight=$totalHeight"
- **L589** `tag` — "rendered ${pageNum - 1} pages to ${output.length()} bytes at +${elapsedMs()}ms"
- **L674** `tag` — "onPageStarted url=$url at +${elapsedMs()}ms"
- **L677** `tag` — "onPageFinished url=$url, contentHeight=${view.contentHeight} at +${elapsedMs()}ms"
- **L701** `tag` — "loading HTML into WebView at +${elapsedMs()}ms"
- **L709** `tag` — "render complete at +${elapsedMs()}ms"

### `ui/settings/LocalRuntimeScreens.kt`

- **L484** `"LocalRuntime"` — "Extracted $entry from $displayName"
- **L489** `"LocalRuntime"` — "Extracted $entry from $displayName"
- **L494** `"LocalRuntime"` — "Extracted $entry from $displayName"

### `viewmodel/AppViewModel.kt`

- **L495** `"CapsWatch"` — line
- **L561** `"App"` — "App started — $appLabel v${com.ai.BuildConfig.VERSION_NAME} " + "(built $builtAt, installed $installedAt) " + "logLevel=${bs.first.logLevel}, tracing=${bs.first.tracingEnabled}"
- **L706** `tag` — "Seeding ${needsSeed.size} default-inactive provider state(s): ${needsSeed.joinToString { it.id }}"
- **L1067** `"Housekeeping"` — "→ Clear logs / chats / traces / reports / audit / prompts / usage stats / test run"
- **L1093** `"Housekeeping"` — "→ Clear Info-provider caches"
- **L1095** `"Housekeeping"` — "← Clear Info-provider caches done"
- **L1099** `"Housekeeping"` — "→ Clear all configuration"
- **L1108** `"Housekeeping"` — "← Clear all configuration: localLlms=$llms embedders=$embedders"
- **L1124** `"Housekeeping"` — "→ Reset application (preserve API keys)"
- **L1204** `"Housekeeping"` — "← Reset application: $count API keys restored"
- **L1255** `"Settings"` — "Log level changed: ${previous.logLevel} → ${settings.logLevel}"
- **L1395** `"ModelTest"` — "→ test-run flush: ${snapshot.blockedModels.size} blocked, ${snapshot.testExcludedModels.size} test-excluded, ${snapshot.inaccessibleModels.size} inaccessible"

### `viewmodel/ChatViewModel.kt`

- **L86** `"Chat.RAG"` — "Skipping KB retrieval for image-only chat turn; text embedder requires a text query"

### `viewmodel/IconGenerationManager.kt`

- **L2858** `"FanMeta"` — "no pending pairs on $reportId — nothing to do"
- **L2870** `"FanMeta"` — "→ start (report=$reportId, ${pending.size} pairs)"
- **L2895** `"FanMeta"` — "← end (report=$reportId)"

### `viewmodel/JudgeEvalEngine.kt`

- **L486** `"JudgeEval"` — "Removed judge $providerId/$model from swarm '$swarmName'"
- **L514** `"JudgeEval"` — "Removed judge $judgeKey from run on $reportId (${cells.size} cells)"
- **L552** `"JudgeEval"` — "Added judge ${provider.id}/$model to swarm '$swarmName'"
- **L611** `"JudgeEval"` — "Added judge $judgeKey to run on $reportId (${matches.size} cells)"

### `viewmodel/ModelTestEngine.kt`

- **L116** `"ModelTest"` — "→ hydrate backfill: catalog=${stats.total}, inacc=${stats.inaccessible}, excl=${stats.excluded}, noChat=${stats.noChat}, items=${updated.items.size}"
- **L213** `"ModelTest"` — "→ startRun ${items.size} models (catalog=${stats.total}, inacc=${stats.inaccessible}, excl=${stats.excluded}, noChat=${stats.noChat})"
- **L247** `"ModelTest"` — "↻ resumeRun ${unfinished.size} unfinished models"
- **L294** `"ModelTest"` — "↻ rerunErrors dropped ${staleKeys.size} stale, nothing to rerun"
- **L312** `"ModelTest"` — "↻ rerunErrors dropped ${staleKeys.size} stale items"
- **L314** `"ModelTest"` — "↻ rerunErrors ${toRerunKeys.size} previously-failed models"
- **L353** `"ModelTest"` — "← run done (${items.size} models)"
- **L430** `"ModelTest"` — "${com.ai.data.MetadataIconsHolder.current.closeMark} run cancelled"

### `viewmodel/RegenerateBatchEngine.kt`

- **L200** `"RegenBatch"` — "reviving stale RUNNING orchestrator for $reportId"
- **L208** `"RegenBatch"` — "auto-resuming PAUSED batch for $reportId — error cleared"
- **L333** `"RegenBatch"` — "phase $phase paused on error for $reportId"

### `viewmodel/ReportViewModel.kt`

- **L498** `"Report"` — "→ start \"${title.ifBlank { "AI Report" }}\" (id=$reportId, ${reportTasks.size} agent(s))"
- **L515** `"Report"` — "← end \"${title.ifBlank { "AI Report" }}\" ok=$ok fail=$fail in ${System.currentTimeMillis() - reportStartMs}ms"
- **L763** `"Report"` — "auto-moderation skipped: no moderation-capable model"
- **L788** `"Report"` — "auto-meta skipped: no meta prompt '${item.metaName}'"
- **L1829** `"Report"` — "→ start (bg) \"${title.ifBlank { "AI Report" }}\" (id=$reportId, ${reportTasks.size} agent(s))"
- **L1840** `"Report"` — "← end (bg) id=$reportId ok=$ok fail=$fail in ${System.currentTimeMillis() - startMs}ms"

### `viewmodel/SecondaryRunManager.kt`

- **L261** `"Rerank"` — "→ start report=$reportId via the Rerank worker swarm"
- **L341** `"Moderation"` — "→ start report=$reportId via the Moderation worker swarm"
- **L871** `"Resume"` — "→ re-issue ${kind.name} \"${metaPrompt.name}\" report=$reportId row=${placeholder.id}"
- **L1022** `"FanIn"` — "→ start \"${metaPrompt.name}\" report=$reportId via the Fan-in worker swarm"
- **L1303** `"Meta"` — "→ start \"${metaPrompt.name}\" report=$reportId via the Meta worker swarm"

### `viewmodel/StressTestEngine.kt`

- **L121** `"StressTest"` — "→ start: submitting $total report(s) with swarm '$SWARM_NAME'"
- **L129** `"StressTest"` — "← submitted $total report(s) — generating in the background"

### `viewmodel/TranslationRunManager.kt`

- **L343** `"Translation"` — "← cancelled $targetLanguageName for report=$sourceReportId"
- **L855** `"Translation"` — "reconciling stalled translation runId=$runId — rebuilding in-memory state from disk"
- **L877** `"Translation"` — "reconcile runId=$runId — placeholders present, re-dispatching via startMissingTranslations"

### `viewmodel/WorkerRunner.kt`

- **L216** `"Workers"` — "${com.ai.data.MetadataIconsHolder.current.checkMark} '${prompt.name}' via ${agent.name} (worker ${idx + 1}/$n)"
- **L250** `"Workers"` — "'${prompt.name}' all workers cooling — retrying in ${delayMs}ms (pass $pass/$ALL_RATE_LIMITED_MAX_RETRIES)"


## DEBUG (164)

### `data/ApiDispatch.kt`

- **L160** `"ApiDispatch"` — "analyze ${service.id}/$model fmt=${service.apiFormat} promptLen=${prompt.length} img=${imageBase64 != null}"
- **L202** `"ApiDispatch"` — "sendChat ${service.id}/$model fmt=${service.apiFormat} msgs=${messages.size}"
- **L247** `"ApiDispatch"` — "fetchModels ${service.id} fmt=${service.apiFormat}"
- **L258** `"ApiDispatch"` — "fetchModels ${service.id} → ${result.ids.size} models in ${System.currentTimeMillis() - t0}ms"
- **L300** `"ApiDispatch"` — "embed ${service.id}/$model — ${texts.size} input(s)"
- **L892** `"ApiDispatch"` — "testApiConnectionWithJson ${service.id} bodyLen=${jsonBody.length}"

### `data/ApiStreaming.kt`

- **L97** `"SSE"` — "stream open"
- **L128** `"SSE"` — "[DONE] terminator (event=$eventType)"
- **L144** `"SSE"` — "chunk event=${eventType ?: "(none)"} dataBytes=${data.length} contentBytes=${content.length}"
- **L148** `"SSE"` — "final chunk (event=$eventType)"
- **L197** `"SSE"` — "stream closed — $chunkCount chunks in ${System.currentTimeMillis() - parseStartMs}ms"

### `data/ApiTracer.kt`

- **L210** `"ApiTracer"` — "trace written $resolvedFilename status=${normalizedTrace.response.statusCode} partial=${normalizedTrace.partial}"

### `data/BackupManager.kt`

- **L187** `"Backup"` — "manifest written"
- **L193** `"Backup"` — "prefs section written (${PREFS_TO_BACKUP.size} files)"
- **L202** `"Backup"` — "filesDir mirrored — $filesWritten entries, skipped=${summary.skipped}"
- **L214** `"Backup"` — "cacheDir mirrored — $cacheWritten entries, skipped=${summary.skipped}"
- **L256** `"Backup"` — "manifest version=$version, staged ${staged.size} entries (${staged.values.sumOf { it.size }} bytes)"
- **L274** `"Backup"` — "prefs applied: $prefsRestored file(s)"
- **L282** `"Backup"` — "filesDir wiped (except excludes)"
- **L288** `"Backup"` — "cacheDir wiped (preserving ${tempZip.name})"
- **L290** `"Backup"` — "files applied: $filesRestored entries"

### `data/ChatHistoryManager.kt`

- **L63** `"ChatHistory"` — "save ${session.id} msgs=${session.messages.size} bytes=${json.length}"
- **L92** `"ChatHistory"` — "load ${it.id} msgs=${it.messages.size}"
- **L158** `"ChatHistory"` — "delete $sessionId"

### `data/EmbeddingsStore.kt`

- **L102** `"EmbeddingsStore"` — "put $providerId/$model dim=${vector.size}"

### `data/InternalPromptIconCache.kt`

- **L113** `"InternalPromptIcon"` — "loaded ${map.size} cached icons"
- **L173** `"InternalPromptIcon"` — "recordInitial name='$name' -> $emoji via $providerId/$model" + " (in=$inputTokens out=$outputTokens cost=${inputCost + outputCost})"
- **L254** `"InternalPromptIcon"` — "pickAlternative name='$name' -> $emoji via $providerId/$model"

### `data/KnowledgeService.kt`

- **L312** `"Knowledge"` — "retrieve kbs=${kbs.size} topK=$topK queryLen=${query.length} → hits=${out.size}" + (out.firstOrNull()?.score?.let { " topScore=${String.format(Locale.US, "%.3f", it)}" } ?: "")
- **L321** `"Knowledge"` — "  cand[$i] kb=${s.hit.kbName} src=${s.hit.sourceName} score=${String.format(Locale.US, "%.3f", s.score)} chars=${s.hit.text.length}"

### `data/MetaCache.kt`

- **L54** `"MetaCache"` — "loaded ${map.size} live entries"

### `data/ModelCooldownStore.kt`

- **L88** `"ModelCooldown"` — "$providerId/$model short-benched ${availableAtMs - now}ms"

### `data/ModelListCache.kt`

- **L65** `"ModelListCache"` — "save $providerId bytes=${rawResponse.length}"
- **L84** `"ModelListCache"` — "hit $providerId age=${ageMs / 1000}s size=${f.length()}"
- **L87** `"ModelListCache"` — "miss $providerId (no cached file)"

### `data/OverloadedRetry.kt`

- **L61** `"Overloaded"` — "529 received on ${request.url.host}, starting retry loop (max=$maxRetries, backoff=${backoffMs}ms)"
- **L91** `"Overloaded"` — "529 retry $attempt/$maxRetries after ${sleepMs}ms on ${request.url.host}"
- **L97** `"Overloaded"` — "recovered after $attempt retry (status=${current.code})"

### `data/PricingCache.kt`

- **L400** `"PricingCache"` — "preload start"
- **L403** `"PricingCache"` — "preload done in ${System.currentTimeMillis() - t0}ms" + " (litellm=${litellmPricing?.size ?: 0}, modelsDev=${modelsDevPricing?.size ?: 0}," + " llmPrices=${llmPricesPricing?.size ?: 0}, aa=${aaPricing?.size ?: 0}," + " llmStats=${llmStatsPricing?.size ?: 0}, openrouter=${openRouterPricing?.size ?: 0}," + " requesty=${requestyPricing?.size ?: 0}, genaiPrices=${genaiPricesPricing?.size ?: 0}," + " trueFoundry=${trueFoundryPricing?.size ?: 0}, cloudPrice=${cloudPriceMeta?.size ?: 0}," + " helicone=${heliconePricing?.size ?: 0}, manual=${manualPricing?.size ?: 0})"
- **L452** `"PricingCache"` — "miss ${provider.id}/$model → DEFAULT"
- **L459** `"PricingCache"` — "match ${provider.id}/$model → $tier in=${p.promptPrice * 1_000_000} out=${p.completionPrice * 1_000_000}"

### `data/ProviderRegistry.kt`

- **L290** `"ProviderRegistry"` — "host index rebuilt — ${map.size} host(s) across ${providers.size} provider(s)"

### `data/ProviderThrottling.kt`

- **L232** `"Throttle"` — "concurrent-cap wait ${System.currentTimeMillis() - concurrentWaitStart}ms on $host (cap=$concurrentLimit)"
- **L253** `"Throttle"` — "rate-limit wait ${sleepMs}ms on $host (queue=${window.size}/$perMinuteLimit)"

### `data/RateLimitRetry.kt`

- **L146** `"RateLimit"` — "429 on ${request.url.host} has no backoff yielder; returning for coroutine-level retry"
- **L148** `"RateLimit"` — "429 received on ${request.url.host}, starting retry loop (max=$maxRetries, backoff=${backoffMs}ms)"
- **L189** `"RateLimit"` — "429 retry $attempt/$maxRetries after ${sleepMs}ms on ${request.url.host}"
- **L195** `"RateLimit"` — "recovered after $attempt retry (status=${current.code})"
- **L215** `"RateLimit"` — "Retry-After=${trimmed}s on $hostForLog → sleeping ${ms}ms"
- **L226** `"RateLimit"` — "Retry-After=\"$trimmed\" on $hostForLog → sleeping ${it}ms"

### `data/SecondaryResult.kt`

- **L521** `"SecondaryResultStorage"` — "saveIfStillPresent: row ${result.id} no longer on disk, skipping save"

### `data/TagPropagation.kt`

- **L144** `"TagPropagation"` — "submit reportId=${captured.reportId} cat=${captured.category}"

### `data/local/LocalEmbedder.kt`

- **L259** `"LocalEmbedder"` — "→ embed $modelName n=${inputs.size} avgLen=${if (inputs.isNotEmpty()) inputs.sumOf { it.length } / inputs.size else 0}"
- **L276** `"LocalEmbedder"` — "← embed $modelName n=${out.size} dim=${out.firstOrNull()?.size ?: 0} ${System.currentTimeMillis() - started}ms"

### `data/local/LocalLlm.kt`

- **L181** `"LocalLlm"` — "→ generate $modelName promptChars=${prompt.length}"
- **L190** `"LocalLlm"` — "← generate $modelName outChars=$outLen ${durMs}ms (${String.format(Locale.US, "%.1f", rate)} chars/s)"

### `data/preferences/SettingsPreferences.kt`

- **L176** `"SettingsPrefs"` — "loadGeneralSettings logLevel=${it.logLevel} tracing=${it.tracingEnabled} " + "streamRT=${it.streamingReadTimeoutSec}s nonStreamRT=${it.nonStreamingReadTimeoutSec}s " + "maxPerMin=${it.maxCallsPerProviderPerMinute} maxConc=${it.maxConcurrentCallsPerProvider} " + "recentReportModels=${it.recentReportModels.size}"
- **L251** `"SettingsPrefs"` — "saveGeneralSettings logLevel=${settings.logLevel} tracing=${settings.tracingEnabled} " + "streamRT=${settings.streamingReadTimeoutSec}s nonStreamRT=${settings.nonStreamingReadTimeoutSec}s " + "maxPerMin=${settings.maxCallsPerProviderPerMinute} maxConc=${settings.maxConcurrentCallsPerProvider}"

### `viewmodel/AppViewModel.kt`

- **L464** `"App.start"` — "→ Prewarm caches (ApiTracer + PricingCache)"
- **L467** `"App.start"` — "← Prewarm caches dispatched (background)"
- **L503** `startTag` — "→ Apply general settings to global singletons"
- **L505** `startTag` — "  ModelType.userDefaults set (${bs.first.defaultTypePaths.size} entries)"
- **L511** `startTag` — "  ApiTracer.isTracingEnabled=${bs.first.effectiveTracingEnabled()} (master=${bs.first.loggingMasterEnabled})"
- **L514** `startTag` — "  SettingsPreferences.usageStatsEnabled=${bs.first.effectiveUsageStatsEnabled()}"
- **L516** `startTag` — "  AnalysisRepository.TEST_PROMPT=${com.ai.data.AnalysisRepository.TEST_PROMPT}"
- **L530** `startTag` — "  NetworkSettings: streamRT=${bs.first.streamingReadTimeoutSec}s nonStreamRT=${bs.first.nonStreamingReadTimeoutSec}s " + "batchItemTO=${bs.first.batchItemTimeoutSec}s " + "maxPerMin=${bs.first.maxCallsPerProviderPerMinute} maxConc=${bs.first.maxConcurrentCallsPerProvider} " + "maxRetries429=${bs.first.maxRetriesOn429} retryBackoff=${bs.first.retryBackoffMs429}ms " + "maxRetries529=${bs.first.maxRetriesOn529} retryBackoff529=${bs.first.retryBackoffMs529}ms"
- **L539** `startTag` — "  AppLog.threshold=${bs.first.effectiveLogLevel()}"
- **L540** `startTag` — "← Apply general settings done"
- **L570** `startTag` — "→ ProviderThrottle reset"
- **L572** `startTag` — "← ProviderThrottle reset done"
- **L579** `startTag` — "→ Publish initial UiState"
- **L581** `startTag` — "← Publish initial UiState done"
- **L583** `startTag` — "→ refreshAllModelLists (cache-respecting)"
- **L586** `startTag` — "  refreshed ${refreshed.size} provider(s): ${refreshed.entries.joinToString { "${it.key}=${it.value}" }}"
- **L587** `startTag` — "← refreshAllModelLists done in ${System.currentTimeMillis() - tRefresh}ms"
- **L623** `tag` — "→ Singletons init"
- **L624** `tag` — "  init AppLog"
- **L625** `tag` — "  init ApiTracer"
- **L626** `tag` — "  init AuditLog"
- **L627** `tag` — "  init ChatHistoryManager"
- **L628** `tag` — "  init ReportStorage"
- **L629** `tag` — "  init SecondaryResultStorage"
- **L630** `tag` — "  init ProviderRegistry"
- **L631** `tag` — "  init ProviderFieldTimestamps"
- **L632** `tag` — "  init PromptCache"
- **L633** `tag` — "  init InternalPromptIconCache"
- **L634** `tag` — "  init MetaCache"
- **L635** `tag` — "  init LastReportTracker"
- **L636** `tag` — "← Singletons init done in ${System.currentTimeMillis() - bootStart}ms"
- **L638** `tag` — "→ Load prefs"
- **L641** `tag` — "  GeneralSettings loaded (logLevel=${gs.logLevel}, tracing=${gs.tracingEnabled})"
- **L643** `tag` — "  providers=${ai.providers.size} agents=${ai.agents.size} flocks=${ai.flocks.size} swarms=${ai.swarms.size}"
- **L644** `tag` — "  internalPrompts=${ai.internalPrompts.size} examplePrompts=${ai.examplePrompts.size} parameters=${ai.parameters.size} systemPrompts=${ai.systemPrompts.size}"
- **L645** `tag` — "← Load prefs done in ${System.currentTimeMillis() - tLoad}ms"
- **L650** `tag` — "→ First-run seed"
- **L654** `tag` — "  first run; isEmptyInstall=$isEmptyInstall"
- **L657** `tag` — "  providers.json seed: added=$providersAdded"
- **L664** `tag` — "  not a first run; skipping seed"
- **L666** `tag` — "← First-run seed done in ${System.currentTimeMillis() - tFirst}ms"
- **L681** `tag` — "→ providers.json delta-sync"
- **L685** `tag` — "  syncFromAsset: $syncCount unedited fields refreshed"
- **L687** `tag` — "  importFromAsset: $addCount new providers appended"
- **L688** `tag` — "← providers.json delta-sync done in ${System.currentTimeMillis() - tSync}ms (synced=$syncCount, added=$addCount)"
- **L720** `tag` — "→ internal-prompts/ delta-merge"
- **L724** `tag` — "  bundled internal-prompts/ entries: ${bundled.size}"
- **L729** `tag` — "  merge: before=$before merged=${merged.size} added=$added"
- **L733** `tag` — "  settings saved with $added new prompts"
- **L735** `tag` — "← internal-prompts/ delta-merge done in ${System.currentTimeMillis() - tPrompts}ms (added=$added)"
- **L737** `tag` — "← internal-prompts/ delta-merge done in ${System.currentTimeMillis() - tPrompts}ms (empty asset)"
- **L749** `tag` — "→ prompts/examples/ delta-merge"
- **L753** `tag` — "  bundled prompts/examples/ entries: ${bundled.size}"
- **L758** `tag` — "  merge: before=$before merged=${merged.size} added=$added"
- **L762** `tag` — "  settings saved with $added new example prompts"
- **L764** `tag` — "← prompts/examples/ delta-merge done in ${System.currentTimeMillis() - tExamples}ms (added=$added)"
- **L766** `tag` — "← prompts/examples/ delta-merge done in ${System.currentTimeMillis() - tExamples}ms (empty asset)"
- **L776** `tag` — "→ prompts/system/ delta-merge"
- **L780** `tag` — "  bundled prompts/system/ entries: ${bundled.size}"
- **L785** `tag` — "  merge: before=$before merged=${merged.size} added=$added"
- **L789** `tag` — "  settings saved with $added new system prompts"
- **L791** `tag` — "← prompts/system/ delta-merge done in ${System.currentTimeMillis() - tSystemPrompts}ms (added=$added)"
- **L793** `tag` — "← prompts/system/ delta-merge done in ${System.currentTimeMillis() - tSystemPrompts}ms (empty asset)"
- **L804** `tag` — "→ workers/swarms/ delta-merge"
- **L816** `tag` — "← workers/swarms/ delta-merge done in ${System.currentTimeMillis() - tSwarms}ms (added=$added)"
- **L818** `tag` — "← workers/swarms/ delta-merge done in ${System.currentTimeMillis() - tSwarms}ms (empty asset)"
- **L827** `tag` — "→ workers/flocks/ delta-merge"
- **L839** `tag` — "← workers/flocks/ delta-merge done in ${System.currentTimeMillis() - tFlocks}ms (added=$added)"
- **L841** `tag` — "← workers/flocks/ delta-merge done in ${System.currentTimeMillis() - tFlocks}ms (empty asset)"
- **L851** `tag` — "→ excluded.json delta-merge"
- **L855** `tag` — "  bundled excluded.json entries: ${bundled.size}"
- **L863** `tag` — "  settings saved with $added new test-excluded entries"
- **L865** `tag` — "← excluded.json delta-merge done in ${System.currentTimeMillis() - tExcluded}ms (added=$added)"
- **L867** `tag` — "← excluded.json delta-merge done in ${System.currentTimeMillis() - tExcluded}ms (empty asset)"
- **L879** `tag` — "→ inaccessible.json delta-merge"
- **L883** `tag` — "  bundled inaccessible.json entries: ${bundled.size}"
- **L891** `tag` — "  settings saved with $added new inaccessible entries"
- **L893** `tag` — "← inaccessible.json delta-merge done in ${System.currentTimeMillis() - tInaccessible}ms (added=$added)"
- **L895** `tag` — "← inaccessible.json delta-merge done in ${System.currentTimeMillis() - tInaccessible}ms (empty asset)"
- **L901** `tag` — "→ meta.json delta-merge"
- **L905** `tag` — "  bundled meta.json entries: ${bundled.size}"
- **L913** `tag` — "  settings saved with $added new default meta items"
- **L915** `tag` — "← meta.json delta-merge done in ${System.currentTimeMillis() - tMeta}ms (added=$added)"
- **L917** `tag` — "← meta.json delta-merge done in ${System.currentTimeMillis() - tMeta}ms (empty asset)"
- **L924** `tag` — "bootstrap total ${System.currentTimeMillis() - bootStart}ms"
- **L1268** `"RecentModels"` — "record $providerId/$model"
- **L1662** `"RefreshAll"` — "→ ${toRefresh.size} provider(s): ${toRefresh.joinToString { it.id }}"
- **L1718** `"RefreshAll"` — "← ok=${successful.size}/${toRefresh.size} in ${System.currentTimeMillis() - t0}ms"

### `viewmodel/ChatViewModel.kt`

- **L34** `"Chat"` — "sendChatMessageStream ${service.id}/$model msgs=${messages.size} kbs=${knowledgeBaseIds.size} web=$webSearchTool reasoning=$reasoningEffort"
- **L90** `"Chat.RAG"` — "retrieving for kbs=${knowledgeBaseIds.joinToString(",")} queryLen=${lastUser.length}"
- **L105** `"Chat.RAG"` — "retrieved ${hits.size} hit(s)"
- **L156** `"Chat"` — "sendDualChatMessage ${service.id}/$model msgs=${messages.size}"

### `viewmodel/FanOutEngine.kt`

- **L1373** `"FanOut"` — "queued pair ans=$answererAgentId src=$sourceAgentId ${provider.id}/$answererModel"
- **L1375** `"FanOut"` — "skip pair $placeholderId — deleted before launch"
- **L1479** `"FanOut"` — "← pair ans=$answererAgentId src=$sourceAgentId ${System.currentTimeMillis() - pairStart}ms"

### `viewmodel/RegenerateBatchEngine.kt`

- **L139** `"RegenBatch"` — "restart no-op: row $pausedRowId still errored"

### `viewmodel/ReportViewModel.kt`

- **L815** `"Report"` — "→ task ${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} agent=${task.resultId}${if (isRegeneration) " (regen)" else ""}"
- **L945** `"Report"` — "skip UI publish for deleted agent=${task.resultId} report=$reportId"
- **L965** `"Report"` — "← task ${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} agent=${task.resultId} " + (if (response.isSuccess) "ok" else "err") + " ${durationMs}ms" + (response.tokenUsage?.let { " in=${it.inputTokens} out=${it.outputTokens}" } ?: "") + (cost?.let { " cost=${"%.5f".format(it)}" } ?: "")

### `viewmodel/SecondaryRunManager.kt`

- **L670** `"BrokenScan"` — "scanned ${recent.size} report${if (recent.size == 1) "" else "s"} (7d) → ${batches.size} broken batch${if (batches.size == 1) "" else "es"}"
- **L740** `"BrokenScan"` — "startup: finalized $marked abandoned leftover cell(s)"

### `viewmodel/TranslationRunManager.kt`

- **L495** `"Translation"` — "→ item ${item.id} \"${item.label}\" kind=${item.kind} srcLen=${item.sourceText.length}"
- **L519** `"Translation"` — "← item ${item.id} err — ${pooled.message}"
- **L557** `"Translation"` — "← item ${item.id} ok ${callDurationMs}ms via ${provider.id}/$model" + (tu?.let { " in=${it.inputTokens} out=${it.outputTokens}" } ?: "") + " cost=${"%.5f".format(costDollars)}"
- **L852** `"Translation"` — "reconcile skipped — runId=$runId has active dispatch job"
