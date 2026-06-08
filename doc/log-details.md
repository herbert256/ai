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

**518 call sites** — 74 ERROR, 193 WARN, 87 INFO, 164 DEBUG.

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
> `ai/src/main/java/com/ai` for `AppLog.(d|i|w|e)(`, grouping by severity
> then file. Exclude the three string-literal examples embedded in
> `data/IconUsageData.kt` (which quote call sites as documentation). Note
> that plain `grep`/`git grep` flags `data/Knowledge.kt` and
> `data/InternalPromptSeed.kt` as binary and silently skips them — use
> `grep -a` or a text-mode scan, or you will miss 13 call sites.

---

## ERROR (74)

### `data/ApiTracer.kt`

- **L166** `"ApiTracer"` — "Failed to save trace ($resolvedFilename): ${e.message}"
- **L206** `"ApiTracer"` — "Cache update failed for $resolvedFilename — invalidating cache: ${e.message}"

### `data/AtomicFileWrite.kt`

- **L71** `"AtomicFileWrite"` — "Failed to write $absolutePath: ${e.message}"

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

- **L361** `"PricingCache"` — "ensureLoadedBlocking invoked on the main thread — refusing to mark preload complete. " + "Move the call to Dispatchers.IO."
- **L847** `"PricingCache"` — "Online LITELLM refresh failed: ${e.message}"
- **L886** `"PricingCache"` — "models.dev refresh failed: ${e.message}"
- **L1025** `"PricingCache"` — "Helicone refresh failed: ${e.message}"
- **L1113** `"PricingCache"` — "llm-prices refresh failed: ${e.message}"
- **L1189** `"PricingCache"` — "Artificial Analysis refresh failed: ${e.message}"
- **L1518** `"PricingCache"` — "Failed: ${e.message}"

### `data/ProviderRegistry.kt`

- **L51** `"ProviderRegistry"` — "Error loading from prefs: ${e.message}"

### `data/RegenerateBatchStorage.kt`

- **L36** `"RegenerateBatchStorage"` — "Refusing to resolve job file for suspect id $reportId"
- **L41** `"RegenerateBatchStorage"` — "Refusing to resolve job file that escapes root: $reportId"

### `data/ReportStorage.kt`

- **L484** `"ReportStorage"` — "Failed to load report $reportId: ${e.message}"
- **L529** `"ReportStorage"` — "Failed to load notes for report $reportId: ${e.message}"
- **L547** `"ReportStorage"` — "Failed to load ${file.name}: $message"
- **L565** `"ReportStorage"` — "Dropping report with null id (corrupt file)"
- **L639** `"ReportStorage"` — "Refusing to save report with suspect id ${report.id}"
- **L644** `"ReportStorage"` — "Refusing to save report that escapes reportsDir: ${report.id}"
- **L653** `"ReportStorage"` — "Failed to save report ${report.id} (writeTextAtomic returned false)"
- **L2522** `"ReportStorage"` — "Refusing to overwrite existing report ${report.id} via persistNewReport"

### `data/SecondaryResult.kt`

- **L59** `"SecondaryResultStorage"` — "Refusing to resolve report dir for suspect id $reportId"
- **L64** `"SecondaryResultStorage"` — "Refusing to resolve report dir that escapes root: $reportId"
- **L82** `"SecondaryResultStorage"` — "Refusing to resolve report dir for suspect id $reportId"
- **L87** `"SecondaryResultStorage"` — "Refusing to resolve report dir that escapes root: $reportId"
- **L106** `"SecondaryResultStorage"` — "Refusing to save result with suspect id ${result.id}"
- **L118** `"SecondaryResultStorage"` — "Refusing to save result that escapes report dir: ${result.id}"
- **L122** `"SecondaryResultStorage"` — "Failed to save result ${result.id}"
- **L282** `"SecondaryResultStorage"` — "Refusing to update result with suspect id $resultId"
- **L291** `"SecondaryResultStorage"` — "Refusing to update result that escapes report dir: $resultId"
- **L421** `"SecondaryResultStorage"` — "Refusing to save result with suspect id ${result.id}"
- **L437** `"SecondaryResultStorage"` — "Refusing to save result that escapes report dir: ${result.id}"

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

- **L895** `"ImportExport"` — "Import file read error"
- **L1240** `"ImportExport"` — "AI Report import error"
- **L1280** `"ImportExport"` — "API keys import parse error"
- **L1283** `"ImportExport"` — "API keys import error"

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

### `viewmodel/AppViewModel.kt`

- **L1138** `"Housekeeping"` — "← Reset application FAILED"


## WARN (193)

### `data/AnalysisRepository.kt`

- **L323** `"AiAnalysis"` — "Tool fallback also failed for ${agent.name}: " + "first=${first.httpStatusCode}/${first.error?.take(120)}; " + "fallback=${retried.httpStatusCode}/${retried.error?.take(120)}"
- **L436** `"AiAnalysis"` — "Streaming attempt failed for ${agent.name} (${e.message}); using non-streaming"
- **L483** `"AiAnalysis"` — "$label first attempt permanent failure, skipping retry"
- **L486** `"AiAnalysis"` — "$label first attempt failed, retrying..."
- **L508** `"AiAnalysis"` — "$label first attempt I/O failure: ${e.message}, retrying…"

### `data/ApiClient.kt`

- **L309** `"ApiClient"` — "fetchUrlAsString non-2xx ${resp.code} for $url — raw snapshot skipped"
- **L316** `"ApiClient"` — "fetchUrlAsString failed for $url: ${e.message}"

### `data/ApiDispatch.kt`

- **L102** `"ApiDispatch"` — "Unable to resolve throttle host for baseUrl=$baseUrl; proceeding without coroutine host gate"
- **L837** `"ApiDispatch"` — "OpenRouter listModelsDetailed threw: ${e.javaClass.simpleName}: ${e.message}"
- **L927** `"ApiDispatch"` — "Native capability listModels HTTP ${resp.code()}: ${body ?: "(no body)"}"
- **L931** `"ApiDispatch"` — "Native capability listModels threw: ${e.javaClass.simpleName}: ${e.message}"
- **L1028** `"ApiDispatch"` — "Anthropic listModels threw: ${e.javaClass.simpleName}: ${e.message}"
- **L1033** `"ApiDispatch"` — "Anthropic listModels HTTP ${response.code()}: ${body ?: "(no body)"}"
- **L1038** `"ApiDispatch"` — "Anthropic listModels returned 200 but no claude-* entries (data size=${response.body()?.data?.size ?: 0})"
- **L1119** `"ApiDispatch"` — "Gemini listModels HTTP ${response.code()}: ${body ?: "(no body)"}"
- **L1127** `"ApiDispatch"` — "Gemini listModels threw: ${e.javaClass.simpleName}: ${e.message}"
- **L1741** `"ApiDispatch"` — "Anthropic reasoning override: max_tokens raised from $baseMax to $effectiveMax (thinking budget=$budget)"

### `data/ApiTracer.kt`

- **L170** `"ApiTracer"` — "writeTextAtomic returned false for $resolvedFilename — skipping cache update"
- **L181** `"ApiTracer"` — "Trace $resolvedFilename was removed before cache update — skipping cache entry"

### `data/AtomicFileWrite.kt`

- **L74** `"AtomicFileWrite"` — "Failed to delete temp file ${tmp.absolutePath}"
- **L77** `"AtomicFileWrite"` — "Failed to delete temp file ${tmp.absolutePath}: ${cleanupError.message}"
- **L91** `"AtomicFileWrite"` — "Failed to prune stale temp file ${stale.absolutePath}"
- **L94** `"AtomicFileWrite"` — "Failed to prune stale temp file ${stale.absolutePath}: ${e.message}"

### `data/BackupManager.kt`

- **L206** `"Backup"` — "Backup skipped $filesSkipped unreadable file(s); see earlier warnings for paths"
- **L321** `"Backup"` — "Skipping zip entry that escapes filesDir: $name"
- **L332** `"Backup"` — "Skipping zip entry that escapes cacheDir: $name"
- **L545** `"Backup"` — "applyPrefs($name): unknown type tag '$tag' for key '$k' — entry skipped"
- **L567** `"Backup"` — "Skipping unreadable directory during backup: ${dir.absolutePath}"
- **L599** `"Backup"` — "Skipping symlink that escapes ${dir.absolutePath}: ${child.absolutePath} → $childCanonical"
- **L604** `"Backup"` — "Skipping path that cannot be resolved during backup: ${child.absolutePath}"
- **L619** `"Backup"` — "Skipping unreadable file during backup: ${child.absolutePath}"

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

- **L869** `"PricingCache"` — "models.dev refresh: empty / failed response"
- **L1009** `"PricingCache"` — "Helicone refresh: empty / failed response"
- **L1164** `"PricingCache"` — "Artificial Analysis refresh skipped: missing API key"
- **L1173** `"PricingCache"` — "Artificial Analysis refresh: empty / failed response"

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

- **L176** `"ImportExport"` — "skipped bad secondary $key: ${e.message}"
- **L204** `"ImportExport"` — "skipped bad trace $key: ${e.message}"

### `data/ReportStorage.kt`

- **L423** `"ReportStorage"` — "Refusing to delete report with suspect id $reportId"
- **L430** `"ReportStorage"` — "Refusing to delete report that escapes reportsDir: $reportId"
- **L477** `"ReportStorage"` — "Rejected reportId with path traversal markers: $reportId"
- **L494** `"ReportStorage"` — "Rejected reportId with path traversal markers: $reportId"

### `data/SecondaryResult.kt`

- **L96** `"SecondaryResultStorage"` — "Skipping save for deleted report ${result.reportId}"
- **L112** `"SecondaryResultStorage"` — "Skipping save for deleted report ${result.reportId}"
- **L128** `"SecondaryResultStorage"` — "Removed late save for deleted report ${result.reportId}"

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

- **L115** `"ImportExport"` — "Skipped $name entry: ${e.message}"
- **L160** `"ImportExport"` — "Skipped flock entry: ${e.message}"
- **L256** `"ImportExport"` — "Skipped runtime report entry: ${e.message}"
- **L276** `"ImportExport"` — "Skipped secondary row: ${e.message}"
- **L302** `"ImportExport"` — "Skipped chat session entry: ${e.message}"
- **L551** `"ImportExport"` — "Skipped model list for unknown provider $key"
- **L639** `"ImportExport"` — "Skipped endpoints for unknown provider $key"
- **L645** `"ImportExport"` — "Skipped endpoint entry: ${e.message}"
- **L665** `"ImportExport"` — "Skipped parameters entry: ${e.message}"
- **L681** `"ImportExport"` — "Skipped model type override entry: ${e.message}"
- **L700** `"ImportExport"` — "Skipped model cooldowns blob: ${e.message}"
- **L714** `"ImportExport"` — "Skipped system prompt entry: ${e.message}"
- **L731** `"ImportExport"` — "Skipped blocked model entry: ${e.message}"
- **L748** `"ImportExport"` — "Skipped test-excluded model entry: ${e.message}"
- **L765** `"ImportExport"` — "Skipped inaccessible model entry: ${e.message}"
- **L1609** `"ImportExport"` — "Bundle apiKeys section failed: ${e.message}"
- **L1635** `"ImportExport"` — "Bundle costs section failed: ${e.message}"

### `viewmodel/AppViewModel.kt`

- **L445** `"CapsWatch"` — "POSSIBLE STALL — throttle state frozen ${stalledTicks * 15}s — $line"
- **L609** `tag` — "First-run providers.json import failed"
- **L640** `tag` — "← providers.json delta-sync failed in ${System.currentTimeMillis() - tSync}ms"
- **L690** `tag` — "← internal-prompts/ delta-merge failed in ${System.currentTimeMillis() - tPrompts}ms"
- **L719** `tag` — "← prompts/examples/ delta-merge failed in ${System.currentTimeMillis() - tExamples}ms"
- **L746** `tag` — "← prompts/system/ delta-merge failed in ${System.currentTimeMillis() - tSystemPrompts}ms"
- **L771** `tag` — "← workers/swarms/ delta-merge failed in ${System.currentTimeMillis() - tSwarms}ms"
- **L794** `tag` — "← workers/flocks/ delta-merge failed in ${System.currentTimeMillis() - tFlocks}ms"
- **L820** `tag` — "← excluded.json delta-merge failed in ${System.currentTimeMillis() - tExcluded}ms"
- **L848** `tag` — "← inaccessible.json delta-merge failed in ${System.currentTimeMillis() - tInaccessible}ms"
- **L870** `tag` — "← meta.json delta-merge failed in ${System.currentTimeMillis() - tMeta}ms"
- **L1093** `"App"` — "providers.json reload failed during reset"
- **L1470** `"App"` — "Failed to fetch models for ${service.id}: ${e.message}"
- **L1889** `"RefreshAll"` — "model fetch failed for ${service.id}: ${it.message}"

### `viewmodel/ChatViewModel.kt`

- **L102** `"Chat.RAG"` — "Retrieval failed for kbs=$knowledgeBaseIds: ${e.javaClass.simpleName}: ${e.message}"

### `viewmodel/CompareEngine.kt`

- **L184** `"Compare"` — "meta_compare prompt not configured / no runnable workers — aborting"
- **L197** `"Compare"` — "nothing to compare (answers=${successful.size}, meta=${metaRows.size})"
- **L400** `"Compare"` — "continue broken batch failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"
- **L587** `"Compare"` — "hydrate failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"
- **L628** `"Compare"` — "resume stale runs failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"

### `viewmodel/FanOutEngine.kt`

- **L1402** `"FanOut"` — "pair ans=$answererAgentId src=$sourceAgentId timed out after 60s"
- **L1588** `"FanOut"` — "continue broken batch failed runKey=$runKey: ${e.javaClass.simpleName}: ${e.message}"
- **L1990** `"FanOut"` — "rerun pairs failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"
- **L2004** `"FanOut"` — "resume stale runs failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"

### `viewmodel/IconGenerationManager.kt`

- **L1124** `"InternalPromptIcon"` — "second/meta not configured — skipping"
- **L1167** `"InternalPromptIcon"` — "no worker produced an icon for name='${prompt.name}'"
- **L1230** `"InternalPromptIconAlt"` — "alt/meta not configured — skipping fan-out"
- **L1345** `"InternalPromptIconAlt"` — "exception for ${item.provider.id}/${item.model}: ${e.message}"
- **L1453** `"PairIconAlt"` — "alt/fan_out prompt not found — skipping (pair=$pairId)"
- **L1629** `"PairTitleAlt"` — "alt/model_title prompt not found — skipping (pair=$pairId)"
- **L1771** `"TranslationIcon"` — "translation/icon not configured — skipping"
- **L1810** `"TranslationIcon"` — "no worker produced an icon for language='$language'"
- **L1844** `"TranslationIconAlt"` — "alt/translation not configured — skipping fan-out"
- **L1947** `"TranslationIconAlt"` — "exception for ${item.provider.id}/${item.model}: ${e.message}"
- **L2326** `"LanguageIconAlt"` — "no detected language on report=$reportId — skipping fan-out"
- **L2465** `"AgentIconAlt"` — "alt/report prompt not found — skipping (agent=$agentId)"
- **L2750** `"FanMeta"` — "fan/meta not configured — skipping"
- **L3056** `"FanMeta"` — "continue broken batch failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"

### `viewmodel/JudgeEvalEngine.kt`

- **L226** `"JudgeEval"` — "workers/tournament prompt not configured — aborting"
- **L231** `"JudgeEval"` — "no resolvable judges in the prompt's swarm — aborting"
- **L634** `"JudgeEval"` — "continue broken batch failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"
- **L834** `"JudgeEval"` — "hydrate failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"
- **L873** `"JudgeEval"` — "resume stale runs failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"

### `viewmodel/RegenerateBatchEngine.kt`

- **L276** `"RegenBatch"` — "orchestrator crashed for $reportId: ${e.message}"
- **L359** `"RegenBatch"` — "phase $phase timed out for $reportId — pausing"

### `viewmodel/ReportViewModel.kt`

- **L829** `"Report"` — "skip benched ${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} — marking agent ${task.resultId} errored"
- **L1856** `"Report"` — "background report skipped — no active models for swarm $swarmId"

### `viewmodel/SecondaryRunManager.kt`

- **L567** `"SecondaryResume"` — "resume stale runs failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"
- **L603** `"BrokenScan"` — "startup finalize failed: ${e.javaClass.simpleName}: ${e.message}"
- **L611** `"BrokenScan"` — "iteration failed: ${e.javaClass.simpleName}: ${e.message}"
- **L1459** `"Secondary"` — "skip benched ${provider.id}/$model — marking row ${placeholder.id} errored"

### `viewmodel/StressTestEngine.kt`

- **L135** `"StressTest"` — "failed: ${e.javaClass.simpleName}: ${e.message}"

### `viewmodel/TournamentEngine.kt`

- **L202** `"Tournament"` — "workers/tournament not configured — aborting"
- **L425** `"Tournament"` — "recompute aggregate failed report=$reportId method=${run.selectedMethod}: ${e.javaClass.simpleName}: ${e.message}"
- **L483** `"Tournament"` — "continue broken batch failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"
- **L691** `"Tournament"` — "hydrate failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"
- **L752** `"Tournament"` — "resume stale runs failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}"

### `viewmodel/TranslationRunManager.kt`

- **L305** `"Translation"` — "no workers/translate-text|title prompt — marking all items error"
- **L1142** `"Translate"` — "continue broken batch failed run=$runId: ${e.javaClass.simpleName}: ${e.message}"
- **L1645** `"Meta-xlate"` — "No existing translation run for $targetLanguageName — skipping cross-translate"
- **L1692** `"Meta-xlate"` — "Could not rebuild persisted state for run $runId — aborting cross-translate"
- **L1787** `"Translate-missing"` — "Could not rebuild persisted state for run $runId — aborting"

### `viewmodel/TranslatorRankEngine.kt`

- **L205** `"TransRank"` — "workers/translate-rank prompt not configured — aborting"
- **L210** `"TransRank"` — "no resolvable judges in the swarm — aborting"
- **L219** `"TransRank"` — "nothing to rank (judges=${judges.size})"

### `viewmodel/WorkerRunner.kt`

- **L97** `"Workers"` — "prompt '${prompt.name}' has no runnable workers — nothing to run"
- **L135** `"Workers"` — "429 '${prompt.name}' via ${agent.name} — cooling ${waitMs}ms, next worker"
- **L143** `"Workers"` — "${resp.httpStatusCode ?: "model-gone"} '${prompt.name}' via ${agent.name} — model unavailable, disabling this worker for the session"
- **L148** `"Workers"` — "no usable result '${prompt.name}' via ${agent.name} — next worker"
- **L149** `"Workers"` — "miss '${prompt.name}' via ${agent.name}: ${resp.error?.take(80)}"


## INFO (87)

### `data/AnalysisRepository.kt`

- **L328** `"AiAnalysis"` — "Tool fallback succeeded for ${agent.name} " + "after first=${first.httpStatusCode}/${first.error?.take(120)}"

### `data/ApiTracer.kt`

- **L210** `"ApiTracer"` — "Pruned $pruned old trace file(s)"

### `data/BackupManager.kt`

- **L159** `"Backup"` — "→ backup start"
- **L208** `"Backup"` — "← backup done in ${System.currentTimeMillis() - t0}ms (filesDir=$filesWritten cacheDir=$cacheWritten skipped=$filesSkipped)"
- **L218** `"Backup"` — "→ restore start"
- **L273** `"Backup"` — "← restore done in ${System.currentTimeMillis() - t0}ms (prefs=$prefsRestored files=$filesRestored)"

### `data/InternalPromptIconCache.kt`

- **L278** `"InternalPromptIcon"` — "clearAll dropped $n cached icons"

### `data/KnowledgeService.kt`

- **L122** `"Knowledge"` — "→ index \"$displayName\" type=$type kb=$kbId textLen=${text.length}"
- **L198** `"Knowledge"` — "← index \"$displayName\" kb=$kbId chunks=${chunks.size} chars=${src.charCount} dim=$embeddingDim in ${System.currentTimeMillis() - indexStart}ms"

### `data/MetaCache.kt`

- **L112** `"MetaCache"` — "clearAll dropped $n entries"

### `data/PricingCache.kt`

- **L873** `"PricingCache"` — "models.dev parse: ${pricing.size} priced, ${meta.size} meta entries (raw ${json.length} bytes)"
- **L1013** `"PricingCache"` — "Helicone parse: ${exact.size} exact, ${patterns.size} patterns"
- **L1103** `"PricingCache"` — "llm-prices parse: ${combined.size} entries from ${llmPricesVendors.size} vendors"
- **L1177** `"PricingCache"` — "Artificial Analysis parse: ${pricing.size} priced, ${meta.size} meta entries"

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

- **L447** `"CapsWatch"` — line
- **L511** `"App"` — "App started — $appLabel v${com.ai.BuildConfig.VERSION_NAME} " + "(built $builtAt, installed $installedAt) " + "logLevel=${bs.first.logLevel}, tracing=${bs.first.tracingEnabled}"
- **L656** `tag` — "Seeding ${needsSeed.size} default-inactive provider state(s): ${needsSeed.joinToString { it.id }}"
- **L999** `"Housekeeping"` — "→ Clear logs / chats / traces / reports / audit / prompts / usage stats / test run"
- **L1025** `"Housekeeping"` — "→ Clear Info-provider caches"
- **L1027** `"Housekeeping"` — "← Clear Info-provider caches done"
- **L1031** `"Housekeeping"` — "→ Clear all configuration"
- **L1040** `"Housekeeping"` — "← Clear all configuration: localLlms=$llms embedders=$embedders"
- **L1056** `"Housekeeping"` — "→ Reset application (preserve API keys)"
- **L1134** `"Housekeeping"` — "← Reset application: $count API keys restored"
- **L1184** `"Settings"` — "Log level changed: ${previous.logLevel} → ${settings.logLevel}"
- **L1301** `"ModelTest"` — "→ test-run flush: ${snapshot.blockedModels.size} blocked, ${snapshot.testExcludedModels.size} test-excluded, ${snapshot.inaccessibleModels.size} inaccessible"

### `viewmodel/ChatViewModel.kt`

- **L86** `"Chat.RAG"` — "Skipping KB retrieval for image-only chat turn; text embedder requires a text query"

### `viewmodel/IconGenerationManager.kt`

- **L2765** `"FanMeta"` — "no pending pairs on $reportId — nothing to do"
- **L2780** `"FanMeta"` — "→ start (report=$reportId, ${pending.size} pairs)"
- **L2805** `"FanMeta"` — "← end (report=$reportId)"

### `viewmodel/JudgeEvalEngine.kt`

- **L494** `"JudgeEval"` — "Removed judge $providerId/$model from swarm '$swarmName'"
- **L519** `"JudgeEval"` — "Removed judge $judgeKey from run on $reportId (${cells.size} cells)"
- **L540** `"JudgeEval"` — "Added judge ${provider.id}/$model to swarm '$swarmName'"
- **L592** `"JudgeEval"` — "Added judge $judgeKey to run on $reportId (${matches.size} cells)"

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

- **L506** `"Report"` — "→ start \"${title.ifBlank { "AI Report" }}\" (id=$reportId, ${reportTasks.size} agent(s))"
- **L523** `"Report"` — "← end \"${title.ifBlank { "AI Report" }}\" ok=$ok fail=$fail in ${System.currentTimeMillis() - reportStartMs}ms"
- **L771** `"Report"` — "auto-moderation skipped: no moderation-capable model"
- **L796** `"Report"` — "auto-meta skipped: no meta prompt '${item.metaName}'"
- **L1869** `"Report"` — "→ start (bg) \"${title.ifBlank { "AI Report" }}\" (id=$reportId, ${reportTasks.size} agent(s))"
- **L1880** `"Report"` — "← end (bg) id=$reportId ok=$ok fail=$fail in ${System.currentTimeMillis() - startMs}ms"

### `viewmodel/SecondaryRunManager.kt`

- **L234** `"Rerank"` — "→ start report=$reportId via the Rerank worker swarm"
- **L310** `"Moderation"` — "→ start report=$reportId via the Moderation worker swarm"
- **L830** `"Resume"` — "→ re-issue ${kind.name} \"${metaPrompt.name}\" report=$reportId row=${placeholder.id} via ${provider.id}/$model"
- **L937** `"FanIn"` — "→ start \"${metaPrompt.name}\" report=$reportId via the Fan-in worker swarm"
- **L1214** `"Meta"` — "→ start \"${metaPrompt.name}\" report=$reportId via the Meta worker swarm"

### `viewmodel/StressTestEngine.kt`

- **L121** `"StressTest"` — "→ start: submitting $total report(s) with swarm '$SWARM_NAME'"
- **L129** `"StressTest"` — "← submitted $total report(s) — generating in the background"

### `viewmodel/TranslationRunManager.kt`

- **L348** `"Translation"` — "← cancelled $targetLanguageName for report=$sourceReportId"
- **L813** `"Translation"` — "reconciling stalled translation runId=$runId — rebuilding in-memory state from disk"
- **L835** `"Translation"` — "reconcile runId=$runId — placeholders present, re-dispatching via startMissingTranslations"

### `viewmodel/WorkerRunner.kt`

- **L128** `"Workers"` — "${com.ai.data.MetadataIconsHolder.current.checkMark} '${prompt.name}' via ${agent.name} (worker ${idx + 1}/$n)"


## DEBUG (164)

### `data/ApiDispatch.kt`

- **L130** `"ApiDispatch"` — "analyze ${service.id}/$model fmt=${service.apiFormat} promptLen=${prompt.length} img=${imageBase64 != null}"
- **L171** `"ApiDispatch"` — "sendChat ${service.id}/$model fmt=${service.apiFormat} msgs=${messages.size}"
- **L215** `"ApiDispatch"` — "fetchModels ${service.id} fmt=${service.apiFormat}"
- **L225** `"ApiDispatch"` — "fetchModels ${service.id} → ${result.ids.size} models in ${System.currentTimeMillis() - t0}ms"
- **L267** `"ApiDispatch"` — "embed ${service.id}/$model — ${texts.size} input(s)"
- **L1174** `"ApiDispatch"` — "testApiConnectionWithJson ${service.id} bodyLen=${jsonBody.length}"

### `data/ApiStreaming.kt`

- **L62** `"SSE"` — "stream open"
- **L93** `"SSE"` — "[DONE] terminator (event=$eventType)"
- **L109** `"SSE"` — "chunk event=${eventType ?: "(none)"} dataBytes=${data.length} contentBytes=${content.length}"
- **L113** `"SSE"` — "final chunk (event=$eventType)"
- **L162** `"SSE"` — "stream closed — $chunkCount chunks in ${System.currentTimeMillis() - parseStartMs}ms"

### `data/ApiTracer.kt`

- **L178** `"ApiTracer"` — "trace written $resolvedFilename status=${normalizedTrace.response.statusCode} partial=${normalizedTrace.partial}"

### `data/BackupManager.kt`

- **L175** `"Backup"` — "manifest written"
- **L181** `"Backup"` — "prefs section written (${PREFS_TO_BACKUP.size} files)"
- **L190** `"Backup"` — "filesDir mirrored — $filesWritten entries, skipped=${summary.skipped}"
- **L202** `"Backup"` — "cacheDir mirrored — $cacheWritten entries, skipped=${summary.skipped}"
- **L244** `"Backup"` — "manifest version=$version, staged ${staged.size} entries (${staged.values.sumOf { it.size }} bytes)"
- **L262** `"Backup"` — "prefs applied: $prefsRestored file(s)"
- **L264** `"Backup"` — "filesDir wiped (except excludes)"
- **L270** `"Backup"` — "cacheDir wiped (preserving ${tempZip.name})"
- **L272** `"Backup"` — "files applied: $filesRestored entries"

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

- **L335** `"PricingCache"` — "preload start"
- **L338** `"PricingCache"` — "preload done in ${System.currentTimeMillis() - t0}ms" + " (litellm=${litellmPricing?.size ?: 0}, modelsDev=${modelsDevPricing?.size ?: 0}," + " llmPrices=${llmPricesPricing?.size ?: 0}, aa=${aaPricing?.size ?: 0}," + " openrouter=${openRouterPricing?.size ?: 0}, helicone=${heliconePricing?.size ?: 0}," + " manual=${manualPricing?.size ?: 0})"
- **L385** `"PricingCache"` — "miss ${provider.id}/$model → DEFAULT"
- **L392** `"PricingCache"` — "match ${provider.id}/$model → $tier in=${p.promptPrice * 1_000_000} out=${p.completionPrice * 1_000_000}"

### `data/ProviderRegistry.kt`

- **L290** `"ProviderRegistry"` — "host index rebuilt — ${map.size} host(s) across ${providers.size} provider(s)"

### `data/ProviderThrottling.kt`

- **L218** `"Throttle"` — "concurrent-cap wait ${System.currentTimeMillis() - concurrentWaitStart}ms on $host (cap=$concurrentLimit)"
- **L239** `"Throttle"` — "rate-limit wait ${sleepMs}ms on $host (queue=${window.size}/$perMinuteLimit)"

### `data/RateLimitRetry.kt`

- **L146** `"RateLimit"` — "429 on ${request.url.host} has no backoff yielder; returning for coroutine-level retry"
- **L148** `"RateLimit"` — "429 received on ${request.url.host}, starting retry loop (max=$maxRetries, backoff=${backoffMs}ms)"
- **L189** `"RateLimit"` — "429 retry $attempt/$maxRetries after ${sleepMs}ms on ${request.url.host}"
- **L195** `"RateLimit"` — "recovered after $attempt retry (status=${current.code})"
- **L215** `"RateLimit"` — "Retry-After=${trimmed}s on $hostForLog → sleeping ${ms}ms"
- **L226** `"RateLimit"` — "Retry-After=\"$trimmed\" on $hostForLog → sleeping ${it}ms"

### `data/SecondaryResult.kt`

- **L432** `"SecondaryResultStorage"` — "saveIfStillPresent: row ${result.id} no longer on disk, skipping save"

### `data/TagPropagation.kt`

- **L144** `"TagPropagation"` — "submit reportId=${captured.reportId} cat=${captured.category}"

### `data/local/LocalEmbedder.kt`

- **L259** `"LocalEmbedder"` — "→ embed $modelName n=${inputs.size} avgLen=${if (inputs.isNotEmpty()) inputs.sumOf { it.length } / inputs.size else 0}"
- **L276** `"LocalEmbedder"` — "← embed $modelName n=${out.size} dim=${out.firstOrNull()?.size ?: 0} ${System.currentTimeMillis() - started}ms"

### `data/local/LocalLlm.kt`

- **L181** `"LocalLlm"` — "→ generate $modelName promptChars=${prompt.length}"
- **L190** `"LocalLlm"` — "← generate $modelName outChars=$outLen ${durMs}ms (${String.format(Locale.US, "%.1f", rate)} chars/s)"

### `ui/settings/SettingsPreferences.kt`

- **L160** `"SettingsPrefs"` — "loadGeneralSettings logLevel=${it.logLevel} tracing=${it.tracingEnabled} " + "streamRT=${it.streamingReadTimeoutSec}s nonStreamRT=${it.nonStreamingReadTimeoutSec}s " + "maxPerMin=${it.maxCallsPerProviderPerMinute} maxConc=${it.maxConcurrentCallsPerProvider} " + "recentReportModels=${it.recentReportModels.size}"
- **L231** `"SettingsPrefs"` — "saveGeneralSettings logLevel=${settings.logLevel} tracing=${settings.tracingEnabled} " + "streamRT=${settings.streamingReadTimeoutSec}s nonStreamRT=${settings.nonStreamingReadTimeoutSec}s " + "maxPerMin=${settings.maxCallsPerProviderPerMinute} maxConc=${settings.maxConcurrentCallsPerProvider}"

### `viewmodel/AppViewModel.kt`

- **L416** `"App.start"` — "→ Prewarm caches (ApiTracer + PricingCache)"
- **L419** `"App.start"` — "← Prewarm caches dispatched (background)"
- **L455** `startTag` — "→ Apply general settings to global singletons"
- **L457** `startTag` — "  ModelType.userDefaults set (${bs.first.defaultTypePaths.size} entries)"
- **L463** `startTag` — "  ApiTracer.isTracingEnabled=${bs.first.effectiveTracingEnabled()} (master=${bs.first.loggingMasterEnabled})"
- **L466** `startTag` — "  SettingsPreferences.usageStatsEnabled=${bs.first.effectiveUsageStatsEnabled()}"
- **L468** `startTag` — "  AnalysisRepository.TEST_PROMPT=${com.ai.data.AnalysisRepository.TEST_PROMPT}"
- **L481** `startTag` — "  NetworkSettings: streamRT=${bs.first.streamingReadTimeoutSec}s nonStreamRT=${bs.first.nonStreamingReadTimeoutSec}s " + "maxPerMin=${bs.first.maxCallsPerProviderPerMinute} maxConc=${bs.first.maxConcurrentCallsPerProvider} " + "maxRetries429=${bs.first.maxRetriesOn429} retryBackoff=${bs.first.retryBackoffMs429}ms " + "maxRetries529=${bs.first.maxRetriesOn529} retryBackoff529=${bs.first.retryBackoffMs529}ms"
- **L489** `startTag` — "  AppLog.threshold=${bs.first.effectiveLogLevel()}"
- **L490** `startTag` — "← Apply general settings done"
- **L520** `startTag` — "→ ProviderThrottle reset"
- **L522** `startTag` — "← ProviderThrottle reset done"
- **L529** `startTag` — "→ Publish initial UiState"
- **L531** `startTag` — "← Publish initial UiState done"
- **L533** `startTag` — "→ refreshAllModelLists (cache-respecting)"
- **L536** `startTag` — "  refreshed ${refreshed.size} provider(s): ${refreshed.entries.joinToString { "${it.key}=${it.value}" }}"
- **L537** `startTag` — "← refreshAllModelLists done in ${System.currentTimeMillis() - tRefresh}ms"
- **L573** `tag` — "→ Singletons init"
- **L574** `tag` — "  init AppLog"
- **L575** `tag` — "  init ApiTracer"
- **L576** `tag` — "  init AuditLog"
- **L577** `tag` — "  init ChatHistoryManager"
- **L578** `tag` — "  init ReportStorage"
- **L579** `tag` — "  init SecondaryResultStorage"
- **L580** `tag` — "  init ProviderRegistry"
- **L581** `tag` — "  init ProviderFieldTimestamps"
- **L582** `tag` — "  init PromptCache"
- **L583** `tag` — "  init InternalPromptIconCache"
- **L584** `tag` — "  init MetaCache"
- **L585** `tag` — "  init LastReportTracker"
- **L586** `tag` — "← Singletons init done in ${System.currentTimeMillis() - bootStart}ms"
- **L588** `tag` — "→ Load prefs"
- **L591** `tag` — "  GeneralSettings loaded (logLevel=${gs.logLevel}, tracing=${gs.tracingEnabled})"
- **L593** `tag` — "  providers=${ai.providers.size} agents=${ai.agents.size} flocks=${ai.flocks.size} swarms=${ai.swarms.size}"
- **L594** `tag` — "  internalPrompts=${ai.internalPrompts.size} examplePrompts=${ai.examplePrompts.size} parameters=${ai.parameters.size} systemPrompts=${ai.systemPrompts.size}"
- **L595** `tag` — "← Load prefs done in ${System.currentTimeMillis() - tLoad}ms"
- **L600** `tag` — "→ First-run seed"
- **L604** `tag` — "  first run; isEmptyInstall=$isEmptyInstall"
- **L607** `tag` — "  providers.json seed: added=$providersAdded"
- **L614** `tag` — "  not a first run; skipping seed"
- **L616** `tag` — "← First-run seed done in ${System.currentTimeMillis() - tFirst}ms"
- **L631** `tag` — "→ providers.json delta-sync"
- **L635** `tag` — "  syncFromAsset: $syncCount unedited fields refreshed"
- **L637** `tag` — "  importFromAsset: $addCount new providers appended"
- **L638** `tag` — "← providers.json delta-sync done in ${System.currentTimeMillis() - tSync}ms (synced=$syncCount, added=$addCount)"
- **L670** `tag` — "→ internal-prompts/ delta-merge"
- **L674** `tag` — "  bundled internal-prompts/ entries: ${bundled.size}"
- **L679** `tag` — "  merge: before=$before merged=${merged.size} added=$added"
- **L683** `tag` — "  settings saved with $added new prompts"
- **L685** `tag` — "← internal-prompts/ delta-merge done in ${System.currentTimeMillis() - tPrompts}ms (added=$added)"
- **L687** `tag` — "← internal-prompts/ delta-merge done in ${System.currentTimeMillis() - tPrompts}ms (empty asset)"
- **L699** `tag` — "→ prompts/examples/ delta-merge"
- **L703** `tag` — "  bundled prompts/examples/ entries: ${bundled.size}"
- **L708** `tag` — "  merge: before=$before merged=${merged.size} added=$added"
- **L712** `tag` — "  settings saved with $added new example prompts"
- **L714** `tag` — "← prompts/examples/ delta-merge done in ${System.currentTimeMillis() - tExamples}ms (added=$added)"
- **L716** `tag` — "← prompts/examples/ delta-merge done in ${System.currentTimeMillis() - tExamples}ms (empty asset)"
- **L726** `tag` — "→ prompts/system/ delta-merge"
- **L730** `tag` — "  bundled prompts/system/ entries: ${bundled.size}"
- **L735** `tag` — "  merge: before=$before merged=${merged.size} added=$added"
- **L739** `tag` — "  settings saved with $added new system prompts"
- **L741** `tag` — "← prompts/system/ delta-merge done in ${System.currentTimeMillis() - tSystemPrompts}ms (added=$added)"
- **L743** `tag` — "← prompts/system/ delta-merge done in ${System.currentTimeMillis() - tSystemPrompts}ms (empty asset)"
- **L754** `tag` — "→ workers/swarms/ delta-merge"
- **L766** `tag` — "← workers/swarms/ delta-merge done in ${System.currentTimeMillis() - tSwarms}ms (added=$added)"
- **L768** `tag` — "← workers/swarms/ delta-merge done in ${System.currentTimeMillis() - tSwarms}ms (empty asset)"
- **L777** `tag` — "→ workers/flocks/ delta-merge"
- **L789** `tag` — "← workers/flocks/ delta-merge done in ${System.currentTimeMillis() - tFlocks}ms (added=$added)"
- **L791** `tag` — "← workers/flocks/ delta-merge done in ${System.currentTimeMillis() - tFlocks}ms (empty asset)"
- **L801** `tag` — "→ excluded.json delta-merge"
- **L805** `tag` — "  bundled excluded.json entries: ${bundled.size}"
- **L813** `tag` — "  settings saved with $added new test-excluded entries"
- **L815** `tag` — "← excluded.json delta-merge done in ${System.currentTimeMillis() - tExcluded}ms (added=$added)"
- **L817** `tag` — "← excluded.json delta-merge done in ${System.currentTimeMillis() - tExcluded}ms (empty asset)"
- **L829** `tag` — "→ inaccessible.json delta-merge"
- **L833** `tag` — "  bundled inaccessible.json entries: ${bundled.size}"
- **L841** `tag` — "  settings saved with $added new inaccessible entries"
- **L843** `tag` — "← inaccessible.json delta-merge done in ${System.currentTimeMillis() - tInaccessible}ms (added=$added)"
- **L845** `tag` — "← inaccessible.json delta-merge done in ${System.currentTimeMillis() - tInaccessible}ms (empty asset)"
- **L851** `tag` — "→ meta.json delta-merge"
- **L855** `tag` — "  bundled meta.json entries: ${bundled.size}"
- **L863** `tag` — "  settings saved with $added new default meta items"
- **L865** `tag` — "← meta.json delta-merge done in ${System.currentTimeMillis() - tMeta}ms (added=$added)"
- **L867** `tag` — "← meta.json delta-merge done in ${System.currentTimeMillis() - tMeta}ms (empty asset)"
- **L874** `tag` — "bootstrap total ${System.currentTimeMillis() - bootStart}ms"
- **L1197** `"RecentModels"` — "record $providerId/$model"
- **L1559** `"RefreshAll"` — "→ ${toRefresh.size} provider(s): ${toRefresh.joinToString { it.id }}"
- **L1615** `"RefreshAll"` — "← ok=${successful.size}/${toRefresh.size} in ${System.currentTimeMillis() - t0}ms"

### `viewmodel/ChatViewModel.kt`

- **L34** `"Chat"` — "sendChatMessageStream ${service.id}/$model msgs=${messages.size} kbs=${knowledgeBaseIds.size} web=$webSearchTool reasoning=$reasoningEffort"
- **L90** `"Chat.RAG"` — "retrieving for kbs=${knowledgeBaseIds.joinToString(",")} queryLen=${lastUser.length}"
- **L105** `"Chat.RAG"` — "retrieved ${hits.size} hit(s)"
- **L156** `"Chat"` — "sendDualChatMessage ${service.id}/$model msgs=${messages.size}"

### `viewmodel/FanOutEngine.kt`

- **L1354** `"FanOut"` — "queued pair ans=$answererAgentId src=$sourceAgentId ${provider.id}/$answererModel"
- **L1356** `"FanOut"` — "skip pair $placeholderId — deleted before launch"
- **L1439** `"FanOut"` — "← pair ans=$answererAgentId src=$sourceAgentId ${System.currentTimeMillis() - pairStart}ms"

### `viewmodel/RegenerateBatchEngine.kt`

- **L139** `"RegenBatch"` — "restart no-op: row $pausedRowId still errored"

### `viewmodel/ReportViewModel.kt`

- **L823** `"Report"` — "→ task ${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} agent=${task.resultId}${if (isRegeneration) " (regen)" else ""}"
- **L953** `"Report"` — "skip UI publish for deleted agent=${task.resultId} report=$reportId"
- **L973** `"Report"` — "← task ${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} agent=${task.resultId} " + (if (response.isSuccess) "ok" else "err") + " ${durationMs}ms" + (response.tokenUsage?.let { " in=${it.inputTokens} out=${it.outputTokens}" } ?: "") + (cost?.let { " cost=${"%.5f".format(it)}" } ?: "")

### `viewmodel/SecondaryRunManager.kt`

- **L630** `"BrokenScan"` — "scanned ${recent.size} report${if (recent.size == 1) "" else "s"} (7d) → ${batches.size} broken batch${if (batches.size == 1) "" else "es"}"
- **L705** `"BrokenScan"` — "startup: finalized $marked abandoned leftover cell(s)"

### `viewmodel/TranslationRunManager.kt`

- **L440** `"Translation"` — "→ item ${item.id} \"${item.label}\" kind=${item.kind} srcLen=${item.sourceText.length}"
- **L471** `"Translation"` — "← item ${item.id} err ${callDurationMs}ms — $msg"
- **L514** `"Translation"` — "← item ${item.id} ok ${callDurationMs}ms via ${provider.id}/$model" + (tu?.let { " in=${it.inputTokens} out=${it.outputTokens}" } ?: "") + " cost=${"%.5f".format(costDollars)}"
- **L810** `"Translation"` — "reconcile skipped — runId=$runId has active dispatch job"
