# Application log — every write, by severity

> Generated reference. Lists every call site that writes to the in-app
> application log (`AppLog`, `data/AppLog.kt`), which mirrors
> `android.util.Log` and appends to `<filesDir>/applog/applog_<yyyyMMdd>.log`
> for any call at or above the active `threshold` (default `INFO`).
> Grouped by severity, then by source file. Each row is
> `Lnnn` `Tag` — message (message interpolations shown as written in source).
> For *how the logger works* (levels, rotation, redaction, viewer), see
> **[applog.md](applog.md)**.

**453 call sites** — 57 ERROR, 140 WARN, 92 INFO, 111 DEBUG, 53 TRACE.

Severity is chosen at the call site by which method is invoked:

| Method | Level | Priority | Toast? |
|---|---|---|---|
| `AppLog.v` | TRACE | 2 | no |
| `AppLog.d` | DEBUG | 3 | no |
| `AppLog.i` | INFO | 4 | no |
| `AppLog.w` | WARN | 5 | yes (debounced) |
| `AppLog.e` | ERROR | 6 | yes (debounced) |

---

## ERROR (57)

`AppLog.e` — priority 6 (matches `Log.ERROR`). Also posts a debounced Toast.

### `data/ApiTracer.kt`

- **L128** `"ApiTracer"` — "Failed to save trace ($resolvedFilename): ${e.message}"
- **L163** `"ApiTracer"` — "Cache update failed for $resolvedFilename — invalidating cache: ${e.message}"

### `data/AtomicFileWrite.kt`

- **L54** `"AtomicFileWrite"` — "Failed to write $absolutePath: ${e.message}"

### `data/ChatHistoryManager.kt`

- **L36** `"ChatHistory"` — "Refusing to save session with suspect id ${session.id}"
- **L44** `"ChatHistory"` — "Refusing to save session that escapes historyDir: ${session.id}"
- **L62** `"ChatHistory"` — "Failed to save: ${e.message}"
- **L79** `"ChatHistory"` — "Failed to load: ${e.message}"
- **L91** `"ChatHistory"` — "Failed to parse: ${e.message}"

### `data/Knowledge.kt`

- **L196** `"Knowledge"` — "Refusing to save source with suspect id ${source.id}"
- **L203** `"Knowledge"` — "Refusing to save source that escapes chunks dir: ${source.id}"
- **L281** `"Knowledge"` — "Refusing to resolve KB dir for suspect id $kbId"
- **L293** `"Knowledge"` — "Refusing to resolve KB dir that escapes root: $kbId"

### `data/PricingCache.kt`

- **L340** `"PricingCache"` — "ensureLoadedBlocking invoked on the main thread — refusing to mark preload complete. " + "Move the call to Dispatchers.IO."
- **L807** `"PricingCache"` — "Online LITELLM refresh failed: ${e.message}"
- **L845** `"PricingCache"` — "models.dev refresh failed: ${e.message}"
- **L981** `"PricingCache"` — "Helicone refresh failed: ${e.message}"
- **L1069** `"PricingCache"` — "llm-prices refresh failed: ${e.message}"
- **L1145** `"PricingCache"` — "Artificial Analysis refresh failed: ${e.message}"
- **L1480** `"PricingCache"` — "Failed: ${e.message}"

### `data/ProviderRegistry.kt`

- **L51** `"ProviderRegistry"` — "Error loading from prefs: ${e.message}"

### `data/RegenerateBatchStorage.kt`

- **L36** `"RegenerateBatchStorage"` — "Refusing to resolve job file for suspect id $reportId"
- **L41** `"RegenerateBatchStorage"` — "Refusing to resolve job file that escapes root: $reportId"

### `data/ReportStorage.kt`

- **L257** `"ReportStorage"` — "Failed to load report $reportId: ${e.message}"
- **L265** `"ReportStorage"` — "Failed to load ${file.name}: ${e.message}"
- **L279** `"ReportStorage"` — "Refusing to save report with suspect id ${report.id}"
- **L284** `"ReportStorage"` — "Refusing to save report that escapes reportsDir: ${report.id}"
- **L293** `"ReportStorage"` — "Failed to save report ${report.id} (writeTextAtomic returned false)"

### `data/SecondaryResult.kt`

- **L55** `"SecondaryResultStorage"` — "Refusing to resolve report dir for suspect id $reportId"
- **L60** `"SecondaryResultStorage"` — "Refusing to resolve report dir that escapes root: $reportId"
- **L76** `"SecondaryResultStorage"` — "Refusing to save result with suspect id ${result.id}"
- **L83** `"SecondaryResultStorage"` — "Refusing to save result that escapes report dir: ${result.id}"
- **L196** `"SecondaryResultStorage"` — "Refusing to save result with suspect id ${result.id}"
- **L211** `"SecondaryResultStorage"` — "Refusing to save result that escapes report dir: ${result.id}"

### `data/local/LlmRuntime.kt`

- **L80** `"LlmRuntime"` — "load failed: ${t.message}"
- **L124** `"LlmRuntime"` — "AAR did not contain $AAR_ENTRY"
- **L144** `"LlmRuntime"` — "download failed: ${e.message}"

### `data/local/LocalEmbedder.kt`

- **L128** `"LocalEmbedder"` — "model ${spec.name} download failed: ${e.message}"
- **L249** `"LocalEmbedder"` — "embed failed: ${e.message}"

### `data/local/LocalLlm.kt`

- **L173** `"LocalLlm"` — "generate failed: ${e.message}"

### `ui/helpers/PdfExport.kt`

- **L592** `tag` — "PDF render failed"
- **L695** `tag` — "onReceivedError code=${error.errorCode} desc='${error.description}' url=${request.url} at +${elapsedMs()}ms"
- **L698** `tag` — "onReceivedHttpError status=${errorResponse.statusCode} url=${request.url} at +${elapsedMs()}ms"
- **L711** `tag` — "renderHtmlToPdfFile failed at +${elapsedMs()}ms: ${e.javaClass.simpleName}: ${e.message}"

### `ui/helpers/ReportExportScreen.kt`

- **L139** `"ReportExport"` — "Export failed"
- **L179** `"ReportExport"` — "Export all failed"

### `ui/settings/ImportExportScreen.kt`

- **L992** `"ImportExport"` — "AI Report import error"
- **L1030** `"ImportExport"` — "API keys import parse error"
- **L1033** `"ImportExport"` — "API keys import error"

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

- **L1613** `"Housekeeping"` — "← Reset application FAILED"

---

## WARN (140)

`AppLog.w` — priority 5 (matches `Log.WARN`). Also posts a debounced Toast. Includes the `w(tag, t)` throwable-only overload.

### `data/AnalysisRepository.kt`

- **L284** `"AiAnalysis"` — "Tool fallback also failed for ${agent.name}: " + "first=${first.httpStatusCode}/${first.error?.take(120)}; " + "fallback=${retried.httpStatusCode}/${retried.error?.take(120)}"
- **L348** `"AiAnalysis"` — "$label first attempt permanent failure, skipping retry"
- **L351** `"AiAnalysis"` — "$label first attempt failed, retrying..."
- **L373** `"AiAnalysis"` — "$label first attempt I/O failure: ${e.message}, retrying…"

### `data/ApiDispatch.kt`

- **L611** `"ApiDispatch"` — "OpenRouter listModelsDetailed threw: ${e.javaClass.simpleName}: ${e.message}"
- **L701** `"ApiDispatch"` — "Native capability listModels HTTP ${resp.code()}: ${body ?: "(no body)"}"
- **L705** `"ApiDispatch"` — "Native capability listModels threw: ${e.javaClass.simpleName}: ${e.message}"
- **L802** `"ApiDispatch"` — "Anthropic listModels threw: ${e.javaClass.simpleName}: ${e.message}"
- **L807** `"ApiDispatch"` — "Anthropic listModels HTTP ${response.code()}: ${body ?: "(no body)"}"
- **L812** `"ApiDispatch"` — "Anthropic listModels returned 200 but no claude-* entries (data size=${response.body()?.data?.size ?: 0})"
- **L893** `"ApiDispatch"` — "Gemini listModels HTTP ${response.code()}: ${body ?: "(no body)"}"
- **L901** `"ApiDispatch"` — "Gemini listModels threw: ${e.javaClass.simpleName}: ${e.message}"
- **L1281** `"ApiDispatch"` — "Anthropic reasoning override: max_tokens raised from $baseMax to $effectiveMax (thinking budget=$budget)"

### `data/ApiTracer.kt`

- **L132** `"ApiTracer"` — "writeTextAtomic returned false for $resolvedFilename — skipping cache update"

### `data/BackupManager.kt`

- **L304** `"Backup"` — "Skipping zip entry that escapes filesDir: $name"
- **L315** `"Backup"` — "Skipping zip entry that escapes cacheDir: $name"
- **L514** `"Backup"` — "applyPrefs($name): unknown type tag '$tag' for key '$k' — entry skipped"
- **L561** `"Backup"` — "Skipping symlink that escapes ${dir.absolutePath}: ${child.absolutePath} → $childCanonical"

### `data/ChatHistoryManager.kt`

- **L30** `"ChatHistory"` — "Not initialized"
- **L68** `"ChatHistory"` — "Not initialized"

### `data/EmbeddingsStore.kt`

- **L66** `"EmbeddingsStore"` — "put($docId, $providerId, $model) failed to write ${f.absolutePath}"
- **L84** `"EmbeddingsStore"` — "cosine: dim mismatch a=${a.size} b=${b.size} — embedder swapped without re-embed?"
- **L108** `"EmbeddingsStore"` — "cosine: dim mismatch a=${a.size} b=${b.size} — embedder swapped without re-embed?"

### `data/ExamplePromptSeed.kt`

- **L40** `"ExamplePromptSeed"` — "Failed to load examples.json: ${e.message}"
- **L86** `"ExamplePromptSeed"` — "upsertFromJson failed: ${e.message}"

### `data/InaccessibleSeed.kt`

- **L42** `"InaccessibleSeed"` — "Failed to load inaccessible.json: ${e.message}"

### `data/InternalPromptIconCache.kt`

- **L115** `"InternalPromptIcon"` — "load failed: ${e.message}"
- **L291** `"InternalPromptIcon"` — "save failed: ${e.message}"

### `data/InternalPromptSeed.kt`

- **L64** `"InternalPromptSeed"` — "Failed to load prompts.json: ${e.message}"
- **L142** `"InternalPromptSeed"` — "upsertFromJson failed: ${e.message}"

### `data/Knowledge.kt`

- **L222** `"Knowledge"` — "Embedding dim mismatch on saveSource: kb=$kbId, " + "manifest=${current.embeddingDim}, new=$embeddingDim. " + "Manifest dim retained; cosine queries against this " + "source will silent-zero against chunks from other " + "embedders. Re-create the KB to mix embedders cleanly."
- **L239** `"Knowledge"` — "Refusing to delete source with suspect id $sourceId"
- **L246** `"Knowledge"` — "Refusing to delete source that escapes chunks dir: $sourceId"

### `data/KnowledgeService.kt`

- **L219** `"Knowledge"` — "Embedder mismatch across attached KBs (${first.name} vs ${mismatch.name}); using ${first.name}'s"
- **L269** `"KnowledgeService"` — "KB '${kb.name}' (${kb.id}) has chunks with dim=$it; query dim=${queryVec.size}. " + "Re-index the KB with the current embedder."

### `data/ModelCooldownStore.kt`

- **L77** `"ModelCooldown"` — "$providerId/$model benched until ${java.util.Date(availableAtMs)}" + (traceFile?.let { " (trace $it)" } ?: "")

### `data/ModelListCache.kt`

- **L54** `"ModelListCache"` — "save($providerId) failed: ${e.message}"
- **L76** `"ModelListCache"` — "read($providerId) failed: ${e.message}"

### `data/ModelTestRunStore.kt`

- **L32** `"ModelTestRunStore"` — "save failed: ${e.message}"
- **L44** `"ModelTestRunStore"` — "load failed: ${e.message}"

### `data/OverloadedRetry.kt`

- **L59** `"Overloaded"` — "529 still present after $attempt retries on ${request.url.host}"

### `data/PricingCache.kt`

- **L828** `"PricingCache"` — "models.dev refresh: empty / failed response"
- **L965** `"PricingCache"` — "Helicone refresh: empty / failed response"
- **L1120** `"PricingCache"` — "Artificial Analysis refresh skipped: missing API key"
- **L1129** `"PricingCache"` — "Artificial Analysis refresh: empty / failed response"

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

- **L103** `"RateLimit"` — "long 429 on $host but provider/model unresolved (provider=$providerId model=$model)"
- **L153** `"RateLimit"` — "429 still present after $attempt retries on ${request.url.host}"

### `data/RegenerateBatchStorage.kt`

- **L55** `"RegenerateBatchStorage"` — "parse failed for $reportId: ${e.message}"

### `data/ReportStorage.kt`

- **L214** `"ReportStorage"` — "Refusing to delete report with suspect id $reportId"
- **L221** `"ReportStorage"` — "Refusing to delete report that escapes reportsDir: $reportId"
- **L250** `"ReportStorage"` — "Rejected reportId with path traversal markers: $reportId"

### `data/SystemPromptSeed.kt`

- **L40** `"SystemPromptSeed"` — "Failed to load system-prompts.json: ${e.message}"

### `data/TestExcludedSeed.kt`

- **L36** `"TestExcludedSeed"` — "Failed to load excluded.json: ${e.message}"

### `data/TracingInterceptor.kt`

- **L94** `tag` — "✗ $callLabel — ${e.javaClass.simpleName}: ${e.message ?: ""} (${System.currentTimeMillis() - callStart}ms)"
- **L152** `tag` — "← ${response.code} $callLabel in ${durationMs}ms$tail"
- **L166** `tag` — "← ${response.code} $callLabel in ${durationMs}ms"

### `ui/settings/ImportExportScreen.kt`

- **L97** `"ImportExport"` — "Skipped $name entry: ${e.message}"
- **L186** `"ImportExport"` — "Skipped runtime report entry: ${e.message}"
- **L199** `"ImportExport"` — "Skipped secondary row: ${e.message}"
- **L221** `"ImportExport"` — "Skipped chat session entry: ${e.message}"
- **L350** `"ImportExport"` — "Skipped model list for unknown provider $key"
- **L430** `"ImportExport"` — "Skipped endpoints for unknown provider $key"
- **L436** `"ImportExport"` — "Skipped endpoint entry: ${e.message}"
- **L456** `"ImportExport"` — "Skipped parameters entry: ${e.message}"
- **L471** `"ImportExport"` — "Skipped model type override entry: ${e.message}"
- **L489** `"ImportExport"` — "Skipped model cooldowns blob: ${e.message}"
- **L503** `"ImportExport"` — "Skipped system prompt entry: ${e.message}"
- **L519** `"ImportExport"` — "Skipped blocked model entry: ${e.message}"
- **L535** `"ImportExport"` — "Skipped test-excluded model entry: ${e.message}"
- **L551** `"ImportExport"` — "Skipped inaccessible model entry: ${e.message}"
- **L1346** `"ImportExport"` — "Bundle apiKeys section failed: ${e.message}"

### `viewmodel/AppViewModel.kt`

- **L928** `tag` — "First-run providers.json import failed"
- **L959** `tag` — "← providers.json delta-sync failed in ${System.currentTimeMillis() - tSync}ms"
- **L1244** `tag` — "← prompts.json delta-merge failed in ${System.currentTimeMillis() - tPrompts}ms"
- **L1273** `tag` — "← examples.json delta-merge failed in ${System.currentTimeMillis() - tExamples}ms"
- **L1300** `tag` — "← system-prompts.json delta-merge failed in ${System.currentTimeMillis() - tSystemPrompts}ms"
- **L1326** `tag` — "← excluded.json delta-merge failed in ${System.currentTimeMillis() - tExcluded}ms"
- **L1354** `tag` — "← inaccessible.json delta-merge failed in ${System.currentTimeMillis() - tInaccessible}ms"
- **L1574** `"App"` — "providers.json reload failed during reset"
- **L1948** `"App"` — "Failed to fetch models for ${service.id}: ${e.message}"
- **L2320** `"RefreshAll"` — "model fetch failed for ${service.id}: ${it.message}"

### `viewmodel/ChatViewModel.kt`

- **L94** `"Chat.RAG"` — "Retrieval failed for kbs=$knowledgeBaseIds: ${e.javaClass.simpleName}: ${e.message}"

### `viewmodel/FanOutEngine.kt`

- **L480** `"FanOut"` — "pair ans=$answererAgentId src=$sourceAgentId timed out after 60s"

### `viewmodel/IconGenerationManager.kt`

- **L414** `"ReportIcons"` — "title-icon failed for ${ra.agentId}: ${e.message}"
- **L607** `"InternalPromptIcon"` — "internal/meta not configured — skipping"
- **L613** `"InternalPromptIcon"` — "agent '${iconPrompt.agent}' not found — skipping"
- **L668** `"InternalPromptIcon"` — "call failed for name='${prompt.name}': ${response.error}"
- **L674** `"InternalPromptIcon"` — "exception generating icon for name='${prompt.name}': ${e.message}"
- **L734** `"InternalPromptIconAlt"` — "internal/meta not configured — skipping fan-out"
- **L740** `"InternalPromptIconAlt"` — "internal/meta_alt not configured — skipping fan-out"
- **L853** `"InternalPromptIconAlt"` — "exception for ${item.provider.id}/${item.model}: ${e.message}"
- **L961** `"PairIconAlt"` — "internal/fan_out prompt not found — skipping (pair=$pairId)"
- **L967** `"PairIconAlt"` — "internal/fan_out_alt prompt not found — skipping (pair=$pairId)"
- **L1148** `"TranslationIcon"` — "internal/translation not configured — skipping"
- **L1154** `"TranslationIcon"` — "agent '${iconPrompt.agent}' not found — skipping"
- **L1200** `"TranslationIcon"` — "call failed for language='$language': ${response.error}"
- **L1206** `"TranslationIcon"` — "exception generating icon for language='$language': ${e.message}"
- **L1240** `"TranslationIconAlt"` — "internal/translation not configured — skipping fan-out"
- **L1246** `"TranslationIconAlt"` — "internal/translation_alt not configured — skipping fan-out"
- **L1348** `"TranslationIconAlt"` — "exception for ${item.provider.id}/${item.model}: ${e.message}"
- **L1722** `"LanguageIconAlt"` — "no detected language on report=$reportId — skipping fan-out"
- **L1855** `"AgentIconAlt"` — "internal/report prompt not found — skipping (agent=$agentId)"
- **L1861** `"AgentIconAlt"` — "internal/report_alt prompt not found — skipping (agent=$agentId)"
- **L2082** `"ReportIcons"` — "no icon prompts configured — skipping (agent=${ra.agentId})"
- **L2183** `"ReportIcons"` — "tier 1 failed for ${ra.agentId}: ${e.message}"
- **L2241** `"ReportIcons"` — "tier 2 failed for ${ra.agentId}: ${e.message}"
- **L2263** `"ReportIcons"` — "tier 3 skipped — no agent matching '${tier3Prompt.agent}' configured"
- **L2306** `"ReportIcons"` — "tier 3 failed for ${ra.agentId}: ${e.message}"
- **L2435** `"FanOutIcons"` — "no icon prompts configured — skipping (pair=${pair.id})"
- **L2456** `"FanOutIcons"` — "tier 1 rate-limited (429) for pair=${pair.id} on $pairHost — chain stopped"
- **L2483** `"FanOutIcons"` — "tier 2 rate-limited (429) for pair=${pair.id} on $pairHost — chain stopped"
- **L2505** `"FanOutIcons"` — "tier 3 rate-limited (429) for pair=${pair.id} — chain stopped"
- **L2838** `"FanTitles"` — "no fan_out_title prompt configured — skipping"
- **L3086** `"FanTitles"` — "title call failed for pair=${pair.id}: ${e.message}"
- **L3166** `"FanOutIcons"` — "tier 1 failed for pair=${pair.id}: ${e.message}"
- **L3224** `"FanOutIcons"` — "tier 2 failed for pair=${pair.id}: ${e.message}"
- **L3238** `"FanOutIcons"` — "tier 3 skipped — no agent matching '${tier3Prompt.agent}' configured"
- **L3280** `"FanOutIcons"` — "tier 3 failed for pair=${pair.id}: ${e.message}"

### `viewmodel/RegenerateBatchEngine.kt`

- **L226** `"RegenBatch"` — "orchestrator crashed for $reportId: ${e.message}"
- **L299** `"RegenBatch"` — "phase $phase timed out for $reportId — pausing"

### `viewmodel/ReportViewModel.kt`

- **L555** `"Report"` — "skip benched ${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} — marking agent ${task.resultId} errored"

### `viewmodel/SecondaryRunManager.kt`

- **L958** `"BgResumeSweep"` — "iteration failed: ${e.javaClass.simpleName}: ${e.message}"
- **L2016** `"Secondary"` — "skip benched ${provider.id}/$model — marking row ${placeholder.id} errored"

### `viewmodel/TranslationRunManager.kt`

- **L70** `"Translation"` — "startTranslation called with empty models — skipping"
- **L417** `"Translation"` — "item ${item.id} timed out on ${ctx.provider.id}/${ctx.model} after ${callBudgetMs / 1000}s — reassigning"
- **L579** `"Translation"` — "skip benched ${provider.id}/$model — item ${item.id} failed attempt"
- **L1530** `"Translation"` — "item ${item.id} timed out on ${ctx.provider.id}/${ctx.model} after ${callBudgetMs / 1000}s"
- **L1588** `"Meta-xlate"` — "No existing translation run for $targetLanguageName — skipping cross-translate"
- **L1635** `"Meta-xlate"` — "Could not rebuild persisted state for run $runId — aborting cross-translate"
- **L1703** `"Translate-missing"` — "No existing translation run for $targetLanguageName and no model to bootstrap from — skipping ${items.size} item(s)"
- **L1749** `"Translate-missing"` — "Could not rebuild persisted state for run $runId — aborting"

---

## INFO (92)

`AppLog.i` — priority 4 (matches `Log.INFO`). The default `threshold`, so these are the baseline of what lands on disk.

### `data/AnalysisRepository.kt`

- **L289** `"AiAnalysis"` — "Tool fallback succeeded for ${agent.name} " + "after first=${first.httpStatusCode}/${first.error?.take(120)}"

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

### `data/PricingCache.kt`

- **L832** `"PricingCache"` — "models.dev parse: ${pricing.size} priced, ${meta.size} meta entries (raw ${json.length} bytes)"
- **L969** `"PricingCache"` — "Helicone parse: ${exact.size} exact, ${patterns.size} patterns"
- **L1059** `"PricingCache"` — "llm-prices parse: ${combined.size} entries from ${llmPricesVendors.size} vendors"
- **L1133** `"PricingCache"` — "Artificial Analysis parse: ${pricing.size} priced, ${meta.size} meta entries"

### `data/ProviderRegistry.kt`

- **L181** `"ProviderRegistry"` — "added ${service.id} (baseUrl=${service.baseUrl})"
- **L201** `"ProviderRegistry"` — "updated ${service.id} changed=${changed.joinToString("
- **L209** `"ProviderRegistry"` — "removed $id"
- **L244** `"ProviderRegistry"` — "syncFromAsset: ${asset.id} pulled ${take.joinToString()}"

### `data/TracingInterceptor.kt`

- **L89** `tag` — "→ $callLabel"
- **L154** `tag` — "← ${response.code} $callLabel in ${durationMs}ms"
- **L168** `tag` — "← ${response.code} $callLabel in ${durationMs}ms"

### `data/local/LlmRuntime.kt`

- **L77** `"LlmRuntime"` — "loaded ${runtimeFile(context).absolutePath}"
- **L140** `"LlmRuntime"` — "downloaded $SO_NAME (${target.length() / (1024 * 1024)} MiB)"

### `data/local/LocalLlm.kt`

- **L117** `"LocalLlm"` — "→ load $modelName from ${file.name} (${file.length() / (1024 * 1024)} MiB)"
- **L127** `"LocalLlm"` — "← loaded $modelName in ${System.currentTimeMillis() - loadStart}ms"

### `ui/admin/AppLogScreen.kt`

- **L173** `"Housekeeping"` — "Cleared $n log file(s)"
- **L196** `"Housekeeping"` — "Deleted $n log file(s) older than 7 days"

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

- **L832** `"App"` — "App started — $appLabel v${com.ai.BuildConfig.VERSION_NAME} " + "(built $builtAt, installed $installedAt) " + "logLevel=${bs.first.logLevel}, tracing=${bs.first.tracingEnabled}"
- **L975** `tag` — "Seeding ${needsSeed.size} default-inactive provider state(s): ${needsSeed.joinToString { it.id }}"
- **L1024** `tag` — "Migrated ${migrated.zip(ai.internalPrompts).count { (a, b) -> a !== b }} icon prompts from category 'internal' → 'icons'"
- **L1065** `tag` — "Renamed ${migrated.zip(ai.internalPrompts).count { (a, b) -> a !== b }} bundled icon prompts to the new naming scheme"
- **L1106** `tag` — "Stripped `_icon` from ${migrated.zip(ai.internalPrompts).count { (a, b) -> a !== b }} icons-category prompt name(s)"
- **L1142** `tag` — "Trimmed ${trimmed.zip(ai.internalPrompts).count { (a, b) -> a !== b }} bundled `_alt` icon prompt(s) to the nudge-only text"
- **L1170** `tag` — "Upgraded ${upgraded.zip(ai.internalPrompts).count { (a, b) -> a !== b }} tier-1 icon prompt(s) to the new wording"
- **L1189** `tag` — "Upgraded test_model prompt to the directive wording"
- **L1212** `tag` — "Re-categorized ${recat.zip(ai.internalPrompts).count { (a, b) -> a !== b }} metadata prompt(s) to 'info'"
- **L1379** `tag` — "→ Migrated ${togetherInExcluded.size} Together entries from test-excluded to inaccessible"
- **L1484** `"Housekeeping"` — "→ Clear logs / chats / traces / reports / prompts / usage stats / test run"
- **L1508** `"Housekeeping"` — "→ Clear Info-provider caches"
- **L1510** `"Housekeeping"` — "← Clear Info-provider caches done"
- **L1514** `"Housekeeping"` — "→ Clear all configuration"
- **L1522** `"Housekeeping"` — "← Clear all configuration: localLlms=$llms embedders=$embedders"
- **L1538** `"Housekeeping"` — "→ Reset application (preserve API keys)"
- **L1609** `"Housekeeping"` — "← Reset application: $count API keys restored"
- **L1662** `"Settings"` — "Log level changed: ${previous.logLevel} → ${settings.logLevel}"
- **L1779** `"ModelTest"` — "→ test-run flush: ${snapshot.blockedModels.size} blocked, ${snapshot.testExcludedModels.size} test-excluded, ${snapshot.inaccessibleModels.size} inaccessible"

### `viewmodel/FanOutEngine.kt`

- **L263** `"FanOut"` — "→ engine.startRun \"${metaPrompt.name}\" report=$reportId"
- **L371** `"FanOut"` — "← engine.startRun done \"${metaPrompt.name}\" (${pending.size} pairs)"
- **L382** `"FanOut"` — "autostart icons+titles for \"${metaPrompt.name}\""

### `viewmodel/IconGenerationManager.kt`

- **L2572** `"FanIcons"` — "no pending pairs for ${metaPrompt.name} on $reportId — nothing to do"
- **L2575** `"FanIcons"` — "→ start ${metaPrompt.name} (report=$reportId, ${pending.size} pairs)"
- **L2665** `"FanIcons"` — "← end ${metaPrompt.name} (report=$reportId)"
- **L2755** `"FanIcons"` — "cleared icon state on ${errored.size} errored pair(s) for ${metaPromptId.take(8)}"
- **L2795** `"FanIcons"` — "restart: $cleared pair(s) cleared for re-chain, $stamped no-content pair(s) stamped 📝"
- **L2865** `"FanTitles"` — "no pending pairs for ${metaPrompt.name} on $reportId — nothing to do"
- **L2868** `"FanTitles"` — "→ start ${metaPrompt.name} (report=$reportId, ${pending.size} pairs)"
- **L2941** `"FanTitles"` — "← end ${metaPrompt.name} (report=$reportId)"
- **L3001** `"FanTitles"` — "cleared title state on ${errored.size} errored pair(s) for ${metaPromptId.take(8)}"

### `viewmodel/ModelTestEngine.kt`

- **L116** `"ModelTest"` — "→ hydrate backfill: catalog=${stats.total}, inacc=${stats.inaccessible}, excl=${stats.excluded}, noChat=${stats.noChat}, items=${updated.items.size}"
- **L213** `"ModelTest"` — "→ startRun ${items.size} models (catalog=${stats.total}, inacc=${stats.inaccessible}, excl=${stats.excluded}, noChat=${stats.noChat})"
- **L247** `"ModelTest"` — "↻ resumeRun ${unfinished.size} unfinished models"
- **L294** `"ModelTest"` — "↻ rerunErrors dropped ${staleKeys.size} stale, nothing to rerun"
- **L312** `"ModelTest"` — "↻ rerunErrors dropped ${staleKeys.size} stale items"
- **L314** `"ModelTest"` — "↻ rerunErrors ${toRerunKeys.size} previously-failed models"
- **L347** `"ModelTest"` — "← run done (${items.size} models)"
- **L424** `"ModelTest"` — "✕ run cancelled"

### `viewmodel/RegenerateBatchEngine.kt`

- **L185** `"RegenBatch"` — "reviving stale RUNNING orchestrator for $reportId"
- **L193** `"RegenBatch"` — "auto-resuming PAUSED batch for $reportId — error cleared"
- **L273** `"RegenBatch"` — "phase $phase paused on error for $reportId"

### `viewmodel/ReportViewModel.kt`

- **L355** `"Report"` — "→ start \"${title.ifBlank { "AI Report" }}\" (id=$reportId, ${reportTasks.size} agent(s))"
- **L426** `"Report"` — "← end \"${title.ifBlank { "AI Report" }}\" ok=$ok fail=$fail in ${System.currentTimeMillis() - reportStartMs}ms"

### `viewmodel/SecondaryRunManager.kt`

- **L143** `"Rerank"` — "→ start report=$reportId via ${provider.id}/$model"
- **L206** `"Moderation"` — "→ start report=$reportId via ${provider.id}/$model"
- **L308** `"FanOut"` — "→ start \"${metaPrompt.name}\" (report=$reportId, ${successful.size} successful agents)"
- **L543** `"FanOut"` — "← end \"${metaPrompt.name}\" (${pending.size} pairs in ${System.currentTimeMillis() - fanOutStartMs}ms)"
- **L1043** `"Resume"` — "→ re-issue ${kind.name} \"${metaPrompt.name}\" report=$reportId row=${placeholder.id} via ${provider.id}/$model"
- **L1381** `"FanIn"` — "→ start \"${metaPrompt.name}\" report=$reportId via ${pick.first.id}/${pick.second}"
- **L1536** `"ModelFanIn"` — "→ start \"${metaPrompt.name}\" report=$reportId active=$activeProviderId/$activeModel via ${pick.first.id}/${pick.second}"
- **L1788** `"Meta"` — "→ start \"${metaPrompt.name}\" report=$reportId — ${picks.size} pick(s)"

### `viewmodel/TranslationRunManager.kt`

- **L183** `"Translation"` — "→ start $targetLanguageName ($targetLanguageNative) for report=$sourceReportId — ${itemsWithIds.size} items via ${models.size} model${if (models.size == 1) "" else "s"}"
- **L500** `"Translation"` — "← cancelled $targetLanguageName for report=$sourceReportId"
- **L510** `"Translation"` — "← done $targetLanguageName for report=$sourceReportId — ok=$okCount fail=$failCount"
- **L807** `"Translation"` — "reconciling stalled translation runId=$runId — rebuilding in-memory state from disk"
- **L829** `"Translation"` — "reconcile runId=$runId — placeholders present, re-dispatching via startMissingTranslations"

---

## DEBUG (111)

`AppLog.d` — priority 3 (matches `Log.DEBUG`). Written only when the log level is set to DEBUG or TRACE.

### `data/ApiDispatch.kt`

- **L39** `"ApiDispatch"` — "analyze ${service.id}/$model fmt=${service.apiFormat} promptLen=${prompt.length} img=${imageBase64 != null}"
- **L58** `"ApiDispatch"` — "sendChat ${service.id}/$model fmt=${service.apiFormat} msgs=${messages.size}"
- **L98** `"ApiDispatch"` — "fetchModels ${service.id} fmt=${service.apiFormat}"
- **L106** `"ApiDispatch"` — "fetchModels ${service.id} → ${result.ids.size} models in ${System.currentTimeMillis() - t0}ms"
- **L148** `"ApiDispatch"` — "embed ${service.id}/$model — ${texts.size} input(s)"
- **L938** `"ApiDispatch"` — "testApiConnectionWithJson ${service.id} bodyLen=${jsonBody.length}"

### `data/ApiStreaming.kt`

- **L56** `"SSE"` — "stream open"
- **L138** `"SSE"` — "stream closed — $chunkCount chunks in ${System.currentTimeMillis() - parseStartMs}ms"

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

- **L290** `"Knowledge"` — "retrieve kbs=${kbs.size} topK=$topK queryLen=${query.length} → hits=${out.size}" + (out.firstOrNull()?.score?.let { " topScore=${"%.3f".format(it)}" } ?: "")

### `data/ModelListCache.kt`

- **L52** `"ModelListCache"` — "save $providerId bytes=${rawResponse.length}"
- **L69** `"ModelListCache"` — "hit $providerId age=${ageMs / 1000}s size=${f.length()}"
- **L72** `"ModelListCache"` — "miss $providerId (no cached file)"

### `data/OverloadedRetry.kt`

- **L39** `"Overloaded"` — "529 received on ${request.url.host}, starting retry loop (max=$maxRetries, backoff=${backoffMs}ms)"
- **L55** `"Overloaded"` — "529 retry $attempt/$maxRetries after ${sleepMs}ms on ${request.url.host}"
- **L61** `"Overloaded"` — "recovered after $attempt retry (status=${current.code})"

### `data/PricingCache.kt`

- **L314** `"PricingCache"` — "preload start"
- **L317** `"PricingCache"` — "preload done in ${System.currentTimeMillis() - t0}ms" + " (litellm=${litellmPricing?.size ?: 0}, modelsDev=${modelsDevPricing?.size ?: 0}," + " llmPrices=${llmPricesPricing?.size ?: 0}, aa=${aaPricing?.size ?: 0}," + " openrouter=${openRouterPricing?.size ?: 0}, helicone=${heliconePricing?.size ?: 0}," + " manual=${manualPricing?.size ?: 0})"

### `data/ProviderRegistry.kt`

- **L276** `"ProviderRegistry"` — "host index rebuilt — ${map.size} host(s) across ${providers.size} provider(s)"

### `data/ProviderThrottling.kt`

- **L165** `"Throttle"` — "rate-limit wait ${sleepMs}ms on $host (queue=${window.size}/$perMinuteLimit)"
- **L178** `"Throttle"` — "concurrent-cap wait ${System.currentTimeMillis() - concurrentWaitStart}ms on $host (cap=$concurrentLimit)"

### `data/RateLimitRetry.kt`

- **L119** `"RateLimit"` — "429 received on ${request.url.host}, starting retry loop (max=$maxRetries, backoff=${backoffMs}ms)"
- **L149** `"RateLimit"` — "429 retry $attempt/$maxRetries after ${sleepMs}ms on ${request.url.host}"
- **L155** `"RateLimit"` — "recovered after $attempt retry (status=${current.code})"
- **L175** `"RateLimit"` — "Retry-After=${trimmed}s on $hostForLog → sleeping ${ms}ms"
- **L186** `"RateLimit"` — "Retry-After=\"$trimmed\" on $hostForLog → sleeping ${it}ms"

### `data/SecondaryResult.kt`

- **L206** `"SecondaryResultStorage"` — "saveIfStillPresent: row ${result.id} no longer on disk, skipping save"

### `data/local/LocalEmbedder.kt`

- **L230** `"LocalEmbedder"` — "→ embed $modelName n=${inputs.size} avgLen=${if (inputs.isNotEmpty()) inputs.sumOf { it.length } / inputs.size else 0}"
- **L246** `"LocalEmbedder"` — "← embed $modelName n=${out.size} dim=${out.firstOrNull()?.size ?: 0} ${System.currentTimeMillis() - started}ms"

### `data/local/LocalLlm.kt`

- **L162** `"LocalLlm"` — "→ generate $modelName promptChars=${prompt.length}"
- **L170** `"LocalLlm"` — "← generate $modelName outChars=$outLen ${durMs}ms (${"%.1f".format(rate)} chars/s)"

### `ui/settings/SettingsPreferences.kt`

- **L115** `"SettingsPrefs"` — "loadGeneralSettings logLevel=${it.logLevel} tracing=${it.tracingEnabled} " + "streamRT=${it.streamingReadTimeoutSec}s nonStreamRT=${it.nonStreamingReadTimeoutSec}s " + "maxPerMin=${it.maxCallsPerProviderPerMinute} maxConc=${it.maxConcurrentCallsPerProvider} " + "recentReportModels=${it.recentReportModels.size}"
- **L170** `"SettingsPrefs"` — "saveGeneralSettings logLevel=${settings.logLevel} tracing=${settings.tracingEnabled} " + "streamRT=${settings.streamingReadTimeoutSec}s nonStreamRT=${settings.nonStreamingReadTimeoutSec}s " + "maxPerMin=${settings.maxCallsPerProviderPerMinute} maxConc=${settings.maxConcurrentCallsPerProvider}"

### `viewmodel/AppViewModel.kt`

- **L771** `"App.start"` — "→ Prewarm caches (ApiTracer + PricingCache)"
- **L774** `"App.start"` — "← Prewarm caches dispatched (background)"
- **L780** `startTag` — "→ Apply general settings to global singletons"
- **L811** `startTag` — "← Apply general settings done"
- **L841** `startTag` — "→ ProviderThrottle reset"
- **L843** `startTag` — "← ProviderThrottle reset done"
- **L850** `startTag` — "→ Publish initial UiState"
- **L852** `startTag` — "← Publish initial UiState done"
- **L854** `startTag` — "→ refreshAllModelLists (cache-respecting)"
- **L858** `startTag` — "← refreshAllModelLists done in ${System.currentTimeMillis() - tRefresh}ms"
- **L893** `tag` — "→ Singletons init"
- **L905** `tag` — "← Singletons init done in ${System.currentTimeMillis() - bootStart}ms"
- **L907** `tag` — "→ Load prefs"
- **L914** `tag` — "← Load prefs done in ${System.currentTimeMillis() - tLoad}ms"
- **L919** `tag` — "→ First-run seed"
- **L935** `tag` — "← First-run seed done in ${System.currentTimeMillis() - tFirst}ms"
- **L950** `tag` — "→ providers.json delta-sync"
- **L957** `tag` — "← providers.json delta-sync done in ${System.currentTimeMillis() - tSync}ms (synced=$syncCount, added=$addCount)"
- **L1224** `tag` — "→ prompts.json delta-merge"
- **L1239** `tag` — "← prompts.json delta-merge done in ${System.currentTimeMillis() - tPrompts}ms (added=$added)"
- **L1241** `tag` — "← prompts.json delta-merge done in ${System.currentTimeMillis() - tPrompts}ms (empty asset)"
- **L1253** `tag` — "→ examples.json delta-merge"
- **L1268** `tag` — "← examples.json delta-merge done in ${System.currentTimeMillis() - tExamples}ms (added=$added)"
- **L1270** `tag` — "← examples.json delta-merge done in ${System.currentTimeMillis() - tExamples}ms (empty asset)"
- **L1280** `tag` — "→ system-prompts.json delta-merge"
- **L1295** `tag` — "← system-prompts.json delta-merge done in ${System.currentTimeMillis() - tSystemPrompts}ms (added=$added)"
- **L1297** `tag` — "← system-prompts.json delta-merge done in ${System.currentTimeMillis() - tSystemPrompts}ms (empty asset)"
- **L1307** `tag` — "→ excluded.json delta-merge"
- **L1321** `tag` — "← excluded.json delta-merge done in ${System.currentTimeMillis() - tExcluded}ms (added=$added)"
- **L1323** `tag` — "← excluded.json delta-merge done in ${System.currentTimeMillis() - tExcluded}ms (empty asset)"
- **L1335** `tag` — "→ inaccessible.json delta-merge"
- **L1349** `tag` — "← inaccessible.json delta-merge done in ${System.currentTimeMillis() - tInaccessible}ms (added=$added)"
- **L1351** `tag` — "← inaccessible.json delta-merge done in ${System.currentTimeMillis() - tInaccessible}ms (empty asset)"
- **L1384** `tag` — "bootstrap total ${System.currentTimeMillis() - bootStart}ms"
- **L1995** `"RefreshAll"` — "→ ${toRefresh.size} provider(s): ${toRefresh.joinToString { it.id }}"
- **L2046** `"RefreshAll"` — "← ok=${successful.size}/${toRefresh.size} in ${System.currentTimeMillis() - t0}ms"

### `viewmodel/ChatViewModel.kt`

- **L34** `"Chat"` — "sendChatMessageStream ${service.id}/$model msgs=${messages.size} kbs=${knowledgeBaseIds.size} web=$webSearchTool reasoning=$reasoningEffort"
- **L84** `"Chat.RAG"` — "retrieving for kbs=${knowledgeBaseIds.joinToString("
- **L97** `"Chat.RAG"` — "retrieved ${hits.size} hit(s)"
- **L124** `"Chat"` — "sendDualChatMessage ${service.id}/$model msgs=${messages.size}"

### `viewmodel/FanOutEngine.kt`

- **L431** `"FanOut"` — "queued pair ans=$answererAgentId src=$sourceAgentId ${provider.id}/$answererModel"
- **L437** `"FanOut"` — "skip pair $placeholderId — deleted before launch"
- **L508** `"FanOut"` — "← pair ans=$answererAgentId src=$sourceAgentId ${System.currentTimeMillis() - pairStart}ms"

### `viewmodel/IconGenerationManager.kt`

- **L2602** `"FanIcons"` — "skip pair ${pair.id} — host $host rate-limited earlier this batch"
- **L2635** `"FanIcons"` — "skip pair ${pair.id} — deleted before launch"
- **L2652** `"FanIcons"` — "← pair ${pair.id} ${System.currentTimeMillis() - pairStart}ms"
- **L2889** `"FanTitles"` — "skip pair ${pair.id} — host $host rate-limited earlier this batch"
- **L2911** `"FanTitles"` — "skip pair ${pair.id} — deleted before launch"
- **L2928** `"FanTitles"` — "← pair ${pair.id} ${System.currentTimeMillis() - pairStart}ms"
- **L3100** `"FanTitles"` — "title pair=${pair.id} ${System.currentTimeMillis() - started}ms"

### `viewmodel/RegenerateBatchEngine.kt`

- **L137** `"RegenBatch"` — "restart no-op: row $pausedRowId still errored"

### `viewmodel/ReportViewModel.kt`

- **L549** `"Report"` — "→ task ${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} agent=${task.resultId}${if (isRegeneration) " (regen)" else ""}"
- **L659** `"Report"` — "← task ${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} agent=${task.resultId} " + (if (response.isSuccess) "ok" else "err") + " ${durationMs}ms" + (response.tokenUsage?.let { " in=${it.inputTokens} out=${it.outputTokens}" } ?: "") + (cost?.let { " cost=${"%.5f".format(it)}" } ?: "")

### `viewmodel/SecondaryRunManager.kt`

- **L473** `"FanOut"` — "queued pair ans=${item.answerer.agentId} src=${item.source.agentId} ${provider.id}/${item.answerer.model}"
- **L483** `"FanOut"` — "skip pair ${item.placeholder.id} — deleted before launch"
- **L489** `"FanOut"` — "→ pair ans=${item.answerer.agentId} src=${item.source.agentId} ${provider.id}/${item.answerer.model}"
- **L519** `"FanOut"` — "← pair ans=${item.answerer.agentId} src=${item.source.agentId} ${System.currentTimeMillis() - pairStart}ms"
- **L650** `"FanOut"` — "queued rerun ph=${ph.id} src=${source.agentId} ${provider.id}/${ph.model}"
- **L652** `"FanOut"` — "skip rerun ${ph.id} — deleted before launch"
- **L658** `"FanOut"` — "→ rerun pair ph=${ph.id} src=${source.agentId} ${provider.id}/${ph.model}"
- **L684** `"FanOut"` — "← rerun pair ph=${ph.id} ${System.currentTimeMillis() - rerunStart}ms"
- **L977** `"BgResumeSweep"` — "scanning ${recent.size} report${if (recent.size == 1) "" else "s"} (last 7 days)"

### `viewmodel/TranslationRunManager.kt`

- **L601** `"Translation"` — "→ item ${item.id} \"${item.label}\" kind=${item.kind} srcLen=${item.sourceText.length}"
- **L635** `"Translation"` — "← item ${item.id} err ${callDurationMs}ms — ${response.error ?: "Empty response"}"
- **L681** `"Translation"` — "← item ${item.id} ok ${callDurationMs}ms" + (tu?.let { " in=${it.inputTokens} out=${it.outputTokens}" } ?: "") + " cost=${"%.5f".format(costDollars)}"
- **L804** `"Translation"` — "reconcile skipped — runId=$runId has active dispatch job"

---

## TRACE (53)

`AppLog.v` — priority 2 (matches `Log.VERBOSE`). Written only at the TRACE level.

### `data/ApiStreaming.kt`

- **L81** `"SSE"` — "[DONE] terminator (event=$eventType)"
- **L92** `"SSE"` — "chunk event=${eventType ?: "(none)"} dataBytes=${data.length} contentBytes=${content.length}"
- **L96** `"SSE"` — "final chunk (event=$eventType)"

### `data/ApiTracer.kt`

- **L140** `"ApiTracer"` — "trace written $resolvedFilename status=${trace.response.statusCode} partial=${trace.partial}"

### `data/ChatHistoryManager.kt`

- **L56** `"ChatHistory"` — "save ${session.id} msgs=${session.messages.size} bytes=${json.length}"
- **L78** `"ChatHistory"` — "load ${it.id} msgs=${it.messages.size}"
- **L112** `"ChatHistory"` — "delete $sessionId"

### `data/KnowledgeService.kt`

- **L299** `"Knowledge"` — " cand[$i] kb=${s.hit.kbName} src=${s.hit.sourceName} score=${"%.3f".format(s.score)} chars=${s.hit.text.length}"

### `data/PricingCache.kt`

- **L385** `"PricingCache"` — "miss ${provider.id}/$model → DEFAULT"
- **L390** `"PricingCache"` — "match ${provider.id}/$model → $tier in=${p.promptPrice * 1_000_000} out=${p.completionPrice * 1_000_000}"

### `data/TagPropagation.kt`

- **L134** `"TagPropagation"` — "submit reportId=${captured.reportId} cat=${captured.category}"

### `viewmodel/AppViewModel.kt`

- **L782** `startTag` — " ModelType.userDefaults set (${bs.first.defaultTypePaths.size} entries)"
- **L784** `startTag` — " ApiTracer.isTracingEnabled=${bs.first.tracingEnabled}"
- **L786** `startTag` — " AnalysisRepository.TEST_PROMPT=${com.ai.data.AnalysisRepository.TEST_PROMPT}"
- **L802** `startTag` — " NetworkSettings: streamRT=${bs.first.streamingReadTimeoutSec}s nonStreamRT=${bs.first.nonStreamingReadTimeoutSec}s " + "maxPerMin=${bs.first.maxCallsPerProviderPerMinute} maxConc=${bs.first.maxConcurrentCallsPerProvider} " + "maxRetries429=${bs.first.maxRetriesOn429} retryBackoff=${bs.first.retryBackoffMs429}ms " + "maxRetries529=${bs.first.maxRetriesOn529} retryBackoff529=${bs.first.retryBackoffMs529}ms"
- **L810** `startTag` — " AppLog.threshold=${bs.first.logLevel}"
- **L857** `startTag` — " refreshed ${refreshed.size} provider(s): ${refreshed.entries.joinToString { "${it.key}=${it.value}" }}"
- **L894** `tag` — " init AppLog"
- **L895** `tag` — " init ApiTracer"
- **L896** `tag` — " init ChatHistoryManager"
- **L897** `tag` — " init ReportStorage"
- **L898** `tag` — " init SecondaryResultStorage"
- **L899** `tag` — " init ProviderRegistry"
- **L900** `tag` — " init ProviderFieldTimestamps"
- **L901** `tag` — " init PromptCache"
- **L902** `tag` — " init InternalPromptIconCache"
- **L903** `tag` — " init TranslationModeStore"
- **L904** `tag` — " init LastReportTracker"
- **L910** `tag` — " GeneralSettings loaded (logLevel=${gs.logLevel}, tracing=${gs.tracingEnabled})"
- **L912** `tag` — " providers=${ai.providers.size} agents=${ai.agents.size} flocks=${ai.flocks.size} swarms=${ai.swarms.size}"
- **L913** `tag` — " internalPrompts=${ai.internalPrompts.size} examplePrompts=${ai.examplePrompts.size} parameters=${ai.parameters.size} systemPrompts=${ai.systemPrompts.size}"
- **L923** `tag` — " first run; isEmptyInstall=$isEmptyInstall"
- **L926** `tag` — " providers.json seed: added=$providersAdded"
- **L933** `tag` — " not a first run; skipping seed"
- **L954** `tag` — " syncFromAsset: $syncCount unedited fields refreshed"
- **L956** `tag` — " importFromAsset: $addCount new providers appended"
- **L1228** `tag` — " bundled prompts.json entries: ${bundled.size}"
- **L1233** `tag` — " merge: before=$before merged=${merged.size} added=$added"
- **L1237** `tag` — " settings saved with $added new prompts"
- **L1257** `tag` — " bundled examples.json entries: ${bundled.size}"
- **L1262** `tag` — " merge: before=$before merged=${merged.size} added=$added"
- **L1266** `tag` — " settings saved with $added new example prompts"
- **L1284** `tag` — " bundled system-prompts.json entries: ${bundled.size}"
- **L1289** `tag` — " merge: before=$before merged=${merged.size} added=$added"
- **L1293** `tag` — " settings saved with $added new system prompts"
- **L1311** `tag` — " bundled excluded.json entries: ${bundled.size}"
- **L1319** `tag` — " settings saved with $added new test-excluded entries"
- **L1339** `tag` — " bundled inaccessible.json entries: ${bundled.size}"
- **L1347** `tag` — " settings saved with $added new inaccessible entries"
- **L1675** `"RecentModels"` — "record $providerId/$model"

### `viewmodel/SecondaryRunManager.kt`

- **L453** `"Caps"` — "pair=${item.placeholder.id} WAIT hostCap (host=$host)"
- **L468** `"Caps"` — "pair=${item.placeholder.id} WAIT global ${ApiCallCaps.snapshot().let { "${it.globalInFlight}/${it.globalMax}" }}"
- **L471** `"Caps"` — "pair=${item.placeholder.id} WAIT fanOut ${ApiCallCaps.snapshot().let { "${it.fanOutInFlight}/${it.fanOutMax}" }}"

---

