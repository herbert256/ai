package com.ai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

// Provider model-list fetching, split out of ApiDispatch.kt (audit P01).
// Same com.ai.data package — no caller import changes.

internal suspend fun AnalysisRepository.fetchModelsOpenAi(service: AppService, apiKey: String): FetchedModels {
    // Capture the raw /models JSON alongside the typed parse so a later
    // parser revision (new capability fields, etc.) can re-extract from
    // the same response without forcing a re-fetch. Two HTTP calls per
    // refresh — the typed Retrofit call below for parsing, and one raw
    // String call here for the snapshot. The bandwidth cost is small
    // (model lists are tens of KB at most).
    val modelsUrlForRaw = run {
        val pathPart = if (service.crossProviderModelList) "v1/models" else (service.modelsPath ?: "v1/models")
        normalizeUrl(service.baseUrl) + pathPart
    }
    val rawJson = ApiFactory.fetchUrlAsString(modelsUrlForRaw, mapOf("Authorization" to "Bearer $apiKey"))

    // OpenRouter exposes architecture.modality on its detailed list — use that for types.
    if (service.crossProviderModelList) {
        val orApi = ApiFactory.createOpenRouterModelsApi(service.baseUrl)
        val response = try { orApi.listModelsDetailed("Bearer $apiKey") } catch (e: Exception) {
            AppLog.w("ApiDispatch", "OpenRouter listModelsDetailed threw: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        val data = if (response?.isSuccessful == true) response.body()?.data.orEmpty() else emptyList()
        if (data.isNotEmpty()) {
            val filtered = service.modelFilterRegex?.let { regex -> data.filter { regex.containsMatchIn(it.id) } } ?: data
            val ids = filtered.map { it.id }.sorted()
            val types = ids.associateWith { id ->
                val info = filtered.firstOrNull { it.id == id }
                ModelType.fromOpenRouterModality(info?.architecture?.modality) ?: ModelType.infer(id)
            }
            // architecture.input_modalities lists the raw input types — "image"
            // means the model accepts vision content blocks. Union into the
            // user-curated visionModels at apply time (see withModels overload).
            val vision = ids.filter { id ->
                val info = filtered.firstOrNull { it.id == id }
                ModelType.fromOpenRouterInputModalities(info?.architecture?.input_modalities)
            }.toSet()
            val caps = ids.associateWith { id ->
                val info = filtered.firstOrNull { it.id == id }
                ModelCapabilities(
                    supportsVision = info?.architecture?.input_modalities
                        ?.any { it.equals("image", ignoreCase = true) },
                    contextLength = info?.context_length ?: info?.top_provider?.context_length,
                    maxOutputTokens = info?.top_provider?.max_completion_tokens,
                    // OpenRouter's `supported_parameters` array enumerates
                    // the request fields each model honors. "reasoning" /
                    // "include_reasoning" presence ⇒ thinking-capable.
                    supportsReasoning = info?.supported_parameters?.let {
                        it.any { p -> p.equals("reasoning", true) || p.equals("include_reasoning", true) }
                    }
                )
            }.filterValues { it.supportsVision != null || it.contextLength != null || it.maxOutputTokens != null || it.supportsReasoning != null }
            return FetchedModels(ids, types, vision, caps, rawJson)
        }
        // Fall through to the basic /v1/models call below if the detailed call failed.
    }

    val api = ApiFactory.createOpenAiCompatibleApi(service.baseUrl)
    val modelsUrl = normalizeUrl(service.baseUrl) + (service.modelsPath ?: "v1/models")
    // Capture full model objects (not just ids) so per-provider capability
    // fields (Mistral capabilities, Together AI context_length, Groq
    // context_window, Fireworks supports_image_input, etc.) ride through
    // to the FetchedModels.capabilities map.
    val rawModels: List<OpenAiModel> = if (service.modelListFormat == "array") {
        val response = api.listModelsArray(modelsUrl, "Bearer $apiKey")
        if (response.isSuccessful) response.body().orEmpty()
        else throw FetchModelsException(
            "${service.id} listModels HTTP ${response.code()}: " +
                (runCatching { response.errorBody()?.string()?.take(300) }.getOrNull() ?: "(no body)")
        )
    } else {
        val response = api.listModels(modelsUrl, "Bearer $apiKey")
        if (response.isSuccessful) response.body()?.data.orEmpty()
        else throw FetchModelsException(
            "${service.id} listModels HTTP ${response.code()}: " +
                (runCatching { response.errorBody()?.string()?.take(300) }.getOrNull() ?: "(no body)")
        )
    }
    // Drop entries the provider has explicitly marked inactive. Groq
    // ships `active=false` for models its fleet has temporarily
    // unloaded — they round-trip through the listing but return 401
    // on chat calls. Treat null as active (every other provider).
    val activeOnly = rawModels.filter { it.active != false }
    val modelIds = activeOnly.mapNotNull { it.id }
    val filtered = service.modelFilterRegex?.let { regex -> modelIds.filter { regex.containsMatchIn(it) } } ?: modelIds
    val ids = filtered.sorted()

    // Cohere-style compatibility endpoint strips the `endpoints` field; if the
    // provider declares a native capability URL we hit it to recover per-model
    // types AND capability info (context_length, supports_vision). Fall back
    // to heuristic on failure.
    data class CohereCap(val type: String?, val cap: ModelCapabilities)
    val cohereByName: Map<String, CohereCap> = service.nativeCapabilityUrl?.let { capUrl ->
        try {
            val cohere = ApiFactory.createCohereNativeApi()
            val resp = cohere.listModels(capUrl, "Bearer $apiKey")
            if (resp.isSuccessful) {
                resp.body()?.models.orEmpty().mapNotNull { m ->
                    val name = m.name ?: return@mapNotNull null
                    name to CohereCap(
                        type = ModelType.fromCohereEndpoints(m.endpoints),
                        cap = ModelCapabilities(
                            supportsVision = m.supports_vision,
                            contextLength = m.context_length
                        )
                    )
                }.toMap()
            } else {
                val body = runCatching { resp.errorBody()?.string()?.take(300) }.getOrNull()
                AppLog.w("ApiDispatch", "Native capability listModels HTTP ${resp.code()}: ${body ?: "(no body)"}")
                emptyMap()
            }
        } catch (e: Exception) {
            AppLog.w("ApiDispatch", "Native capability listModels threw: ${e.javaClass.simpleName}: ${e.message}")
            emptyMap()
        }
    } ?: emptyMap()

    val types = ids.associateWith { id ->
        cohereByName[id]?.type
            // Mistral capabilities are per-modality booleans; pick the
            // non-chat one when set so the picker auto-tags the right
            // ModelType. Order matters — moderation > stt > tts > ocr
            // > classification > chat (chat is the default; if multiple
            // flags are true we prefer the more specific one).
            ?: rawModels.firstOrNull { it.id == id }?.capabilities?.let { c ->
                when {
                    c.moderation == true -> ModelType.MODERATION
                    c.audio_transcription == true -> ModelType.STT
                    c.audio_speech == true -> ModelType.TTS
                    c.ocr == true -> ModelType.OCR
                    c.classification == true -> ModelType.CLASSIFY
                    else -> null
                }
            }
            ?: ModelType.infer(id)
    }
    val caps = mutableMapOf<String, ModelCapabilities>()
    for (id in ids) {
        val info = rawModels.firstOrNull { it.id == id }
        // Mistral exposes a rich capabilities block; other providers
        // sprinkle individual flags. Pull whatever we can recognize.
        val supportsVision = info?.capabilities?.vision
            ?: info?.supports_image_input
            ?: info?.supports_image_in
        val supportsFn = info?.capabilities?.function_calling
        val ctx = info?.max_context_length ?: info?.context_length ?: info?.context_window
        // Reasoning: prefer Mistral's nested `capabilities.reasoning`
        // boolean; fall back to any `supported_parameters` array
        // (xAI / Together / etc.) carrying a "reasoning" entry.
        val supportsReasoning = info?.capabilities?.reasoning
            ?: info?.supported_parameters?.let {
                it.any { p -> p.equals("reasoning", true) || p.equals("include_reasoning", true) }
            }
        val cohereCap = cohereByName[id]?.cap
        val aliases = info?.aliases?.takeIf { it.isNotEmpty() }
        val deprecationDate = info?.deprecation
        val deprecationReplacement = info?.deprecation_replacement_model
        // Provider-recommended defaults, surfaced on Model Info. Mistral
        // ships per-model temperature, Together ships stop tokens — feed
        // both fields where they're available.
        val defaultTemp = info?.default_model_temperature
        val defaultStops = info?.config?.stop?.takeIf { it.isNotEmpty() }
        val merged = ModelCapabilities(
            supportsVision = supportsVision ?: cohereCap?.supportsVision,
            supportsFunctionCalling = supportsFn,
            contextLength = ctx ?: cohereCap?.contextLength,
            maxOutputTokens = null,
            supportsReasoning = supportsReasoning,
            aliases = aliases,
            deprecationDate = deprecationDate,
            deprecationReplacement = deprecationReplacement,
            defaultTemperature = defaultTemp,
            defaultStopSequences = defaultStops
        )
        if (merged.supportsVision != null || merged.supportsFunctionCalling != null
            || merged.contextLength != null || merged.maxOutputTokens != null
            || merged.supportsReasoning != null || merged.aliases != null
            || merged.deprecationDate != null
            || merged.defaultTemperature != null || merged.defaultStopSequences != null) {
            caps[id] = merged
        }
    }
    val visionFromList = caps.filterValues { it.supportsVision == true }.keys
    // Together AI ships its own pricing block on every model entry.
    // Harvest it here and hand it back through FetchedModels.nativePricing
    // so the AppViewModel fetcher (which has Context) can persist it
    // into the PricingCache TOGETHER tier. Values arrive as USD per 1M
    // tokens; divide for the per-token rate ModelPricing expects.
    val nativePricing = if (service.pricingFromModelList) {
        activeOnly.mapNotNull { m ->
            val id = m.id ?: return@mapNotNull null
            val p = m.pricing ?: return@mapNotNull null
            val pp = p.input?.let { it / 1_000_000.0 } ?: return@mapNotNull null
            val cp = p.output?.let { it / 1_000_000.0 } ?: return@mapNotNull null
            id to PricingCache.ModelPricing(id, pp, cp, "TOGETHER")
        }.toMap()
    } else emptyMap()
    return FetchedModels(ids, types, visionFromList, caps, rawJson, nativePricing = nativePricing)
}

internal suspend fun AnalysisRepository.fetchModelsAnthropic(service: AppService, apiKey: String): FetchedModels {
    val response = try {
        ApiFactory.createClaudeApi(service.baseUrl).listModels(apiKey)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // Network / parse failure — surface as FetchModelsException so
        // AppViewModel.fetchModelsAwait routes it through fetchModelsErrors
        // instead of silently overwriting the cached catalog with empty.
        AppLog.w("ApiDispatch", "Anthropic listModels threw: ${e.javaClass.simpleName}: ${e.message}")
        throw FetchModelsException("Anthropic listModels failed: ${e.javaClass.simpleName}: ${e.message}", e)
    }
    if (!response.isSuccessful) {
        val body = runCatching { response.errorBody()?.string()?.take(300) }.getOrNull()
        AppLog.w("ApiDispatch", "Anthropic listModels HTTP ${response.code()}: ${body ?: "(no body)"}")
        throw FetchModelsException("Anthropic listModels HTTP ${response.code()}: ${body ?: "(no body)"}")
    }
    val entries = response.body()?.data?.filter { it.id?.startsWith("claude") == true }.orEmpty()
    if (entries.isEmpty()) {
        AppLog.w("ApiDispatch", "Anthropic listModels returned 200 but no claude-* entries (data size=${response.body()?.data?.size ?: 0})")
    }
    val ids = entries.mapNotNull { it.id }.sorted()
    // Read Anthropic's per-model capability bundle directly. Provider
    // self-report is authoritative when present; pre-this-change we
    // were falling through to LiteLLM / models.dev / heuristics for
    // every Claude entry's vision + context length even though
    // Anthropic ships them right here.
    val caps = entries.mapNotNull { info ->
        val id = info.id ?: return@mapNotNull null
        val thinking = info.capabilities?.thinking?.supported
        val vision = info.capabilities?.image_input?.supported
        val pdf = info.capabilities?.pdf_input?.supported
        val ctx = info.max_input_tokens
        val maxOut = info.max_tokens
        val effort = info.capabilities?.effort
        val effortLevels = if (effort?.supported == true) buildList {
            if (effort.low?.supported == true) add("low")
            if (effort.medium?.supported == true) add("medium")
            if (effort.high?.supported == true) add("high")
            if (effort.max?.supported == true) add("max")
        }.takeIf { it.isNotEmpty() } else null
        val cap = ModelCapabilities(
            supportsVision = vision,
            contextLength = ctx,
            maxOutputTokens = maxOut,
            supportsReasoning = thinking,
            reasoningEffortLevels = effortLevels,
            supportsPdfInput = pdf
        )
        if (cap.supportsVision == null && cap.contextLength == null
            && cap.maxOutputTokens == null && cap.supportsReasoning == null
            && cap.reasoningEffortLevels == null && cap.supportsPdfInput == null) null
        else id to cap
    }.toMap()
    val visionFlagged = caps.filterValues { it.supportsVision == true }.keys.toSet()
    // Best-effort raw-JSON snapshot for future parser revisions.
    // fetchUrlAsString already swallows its own exceptions and returns
    // null on failure, so it can't poison the success path here — but
    // it's a second HTTP roundtrip that adds latency and another way
    // to fail silently. If it hands back null we just store null;
    // ModelListCache treats that as "no snapshot this run".
    val rawJson = ApiFactory.fetchUrlAsString(
        normalizeUrl(service.baseUrl) + "v1/models",
        mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
    )
    return FetchedModels(ids, ids.associateWith { ModelType.CHAT }, visionModels = visionFlagged, capabilities = caps, rawResponse = rawJson)
}

internal suspend fun AnalysisRepository.fetchModelsGemini(service: AppService, apiKey: String): FetchedModels {
    val rawJson = ApiFactory.fetchUrlAsString("${normalizeUrl(service.baseUrl)}v1beta/models?key=${android.net.Uri.encode(apiKey)}")
    return try {
        val api = ApiFactory.createGeminiApi(service.baseUrl)
        val response = api.listModels(apiKey)
        if (response.isSuccessful) {
            // Gemini's `supportedGenerationMethods` is the native source-of-truth for kind:
            // `generateContent` → CHAT, `embedContent` → EMBEDDING. Drop entries that
            // expose neither (countTokens-only, etc.) since the app can't drive them.
            val rawModels = response.body()?.models.orEmpty()
            val byName = rawModels.mapNotNull { m ->
                val name = m.name?.removePrefix("models/") ?: return@mapNotNull null
                val type = ModelType.fromGeminiMethods(m.supportedGenerationMethods) ?: return@mapNotNull null
                name to (type to m)
            }.toMap()
            val all = byName.mapValues { it.value.first }
            val ids = all.keys.sorted()
            // Gemini exposes inputTokenLimit / outputTokenLimit per model;
            // no explicit vision flag, so leave that null and let the
            // heuristic / catalog fallbacks decide. The top-level
            // `thinking` boolean rides on every 2.5-family entry.
            val caps = byName.mapValues { (_, v) ->
                val m = v.second
                ModelCapabilities(
                    contextLength = m.inputTokenLimit,
                    maxOutputTokens = m.outputTokenLimit,
                    supportsReasoning = m.thinking
                )
            }.filterValues { it.contextLength != null || it.maxOutputTokens != null || it.supportsReasoning != null }
            FetchedModels(ids, all, emptySet(), caps, rawJson)
        } else {
            val body = runCatching { response.errorBody()?.string()?.take(300) }.getOrNull()
            AppLog.w("ApiDispatch", "Gemini listModels HTTP ${response.code()}: ${body ?: "(no body)"}")
            throw FetchModelsException("Gemini listModels HTTP ${response.code()}: ${body ?: "(no body)"}")
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: FetchModelsException) {
        throw e
    } catch (e: Exception) {
        AppLog.w("ApiDispatch", "Gemini listModels threw: ${e.javaClass.simpleName}: ${e.message}")
        throw FetchModelsException("Gemini listModels failed: ${e.javaClass.simpleName}: ${e.message}", e)
    }
}

// ============================================================================
// Test methods
// ============================================================================

