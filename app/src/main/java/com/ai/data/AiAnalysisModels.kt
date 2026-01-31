package com.ai.data

import com.ai.ui.AiAgentParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Test if a model is accessible with the given API key.
 * Makes a minimal API call to verify the configuration works.
 * @return null if successful, error message if failed
 */
suspend fun AiAnalysisRepository.testModel(
    service: AiService,
    apiKey: String,
    model: String
): String? = withContext(Dispatchers.IO) {
    try {
        val response = when (service) {
            AiService.OPENAI -> analyzeWithChatGpt(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.ANTHROPIC -> analyzeWithClaude(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.GOOGLE -> analyzeWithGemini(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            else -> analyzeWithOpenAiCompatible(service, apiKey, AiAnalysisRepository.TEST_PROMPT, model)
        }

        if (response.isSuccess) {
            null // Success
        } else {
            response.error ?: "Unknown error"
        }
    } catch (e: Exception) {
        e.message ?: "Connection error"
    }
}

/**
 * Test API connection with custom settings.
 * Used by the API Test screen to test arbitrary API configurations.
 */
suspend fun AiAnalysisRepository.testApiConnection(
    service: AiService,
    apiKey: String,
    model: String,
    baseUrl: String,
    prompt: String,
    params: AiAgentParameters? = null
): AiAnalysisResponse = withContext(Dispatchers.IO) {
    try {
        // Create a custom API instance with the provided base URL
        val api = AiApiFactory.createOpenAiApiWithBaseUrl(baseUrl)

        // Build messages with optional system prompt
        val messages = buildList {
            params?.systemPrompt?.let { systemPrompt ->
                if (systemPrompt.isNotBlank()) {
                    add(OpenAiMessage(role = "system", content = systemPrompt))
                }
            }
            add(OpenAiMessage(role = "user", content = prompt))
        }

        val request = OpenAiRequest(
            model = model,
            messages = messages,
            max_tokens = params?.maxTokens,
            temperature = params?.temperature,
            top_p = params?.topP,
            frequency_penalty = params?.frequencyPenalty,
            presence_penalty = params?.presencePenalty,
            stop = params?.stopSequences?.takeIf { it.isNotEmpty() },
            seed = params?.seed,
            response_format = if (params?.responseFormatJson == true) OpenAiResponseFormat(type = "json_object") else null,
            search = if (params?.searchEnabled == true) true else null
        )

        // Use appropriate auth header based on service
        val authHeader = when (service) {
            AiService.ANTHROPIC -> apiKey  // Anthropic uses x-api-key
            else -> "Bearer $apiKey"
        }

        val response = api.createChatCompletion(authHeader, request)
        val headers = formatHeaders(response.headers())
        val statusCode = response.code()

        if (response.isSuccessful) {
            val body = response.body()
            val content = body?.choices?.firstOrNull()?.message?.content
            val rawUsageJson = formatUsageJson(body?.usage)
            val usage = body?.usage?.let {
                TokenUsage(
                    inputTokens = it.prompt_tokens ?: it.input_tokens ?: 0,
                    outputTokens = it.completion_tokens ?: it.output_tokens ?: 0,
                    apiCost = extractApiCost(it, service)
                )
            }
            if (content != null) {
                AiAnalysisResponse(service, content, null, usage, rawUsageJson = rawUsageJson, httpHeaders = headers, httpStatusCode = statusCode)
            } else {
                AiAnalysisResponse(service, null, "No response content", httpHeaders = headers, httpStatusCode = statusCode)
            }
        } else {
            AiAnalysisResponse(service, null, "API error: ${response.code()} ${response.message()}", httpHeaders = headers, httpStatusCode = statusCode)
        }
    } catch (e: Exception) {
        AiAnalysisResponse(service, null, "Error: ${e.message}")
    }
}

/**
 * Test API connection with a raw JSON body.
 * Used by the Edit API Request screen to send user-edited JSON.
 */
suspend fun AiAnalysisRepository.testApiConnectionWithJson(
    service: AiService,
    apiKey: String,
    baseUrl: String,
    jsonBody: String
): AiAnalysisResponse = withContext(Dispatchers.IO) {
    try {
        // Create a custom OkHttp client with tracing interceptor
        val client = OkHttpClient.Builder()
            .addInterceptor(TracingInterceptor())
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        // Build the request
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonBody.toRequestBody(mediaType)

        // Use appropriate auth header based on service
        val authHeader = when (service) {
            AiService.ANTHROPIC -> apiKey  // Anthropic uses x-api-key
            else -> "Bearer $apiKey"
        }

        val request = okhttp3.Request.Builder()
            .url(baseUrl)
            .post(body)
            .addHeader("Authorization", authHeader)
            .addHeader("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        val headers = formatHeaders(response.headers)
        val statusCode = response.code

        if (response.isSuccessful) {
            val responseBody = response.body?.string()
            val gson = com.google.gson.Gson()
            try {
                val openAiResponse = gson.fromJson(responseBody, OpenAiResponse::class.java)
                val content = openAiResponse?.choices?.firstOrNull()?.message?.content
                val usage = openAiResponse?.usage?.let {
                    TokenUsage(
                        inputTokens = it.prompt_tokens ?: it.input_tokens ?: 0,
                        outputTokens = it.completion_tokens ?: it.output_tokens ?: 0,
                        apiCost = extractApiCost(it, service)
                    )
                }
                if (content != null) {
                    AiAnalysisResponse(service, content, null, usage, httpHeaders = headers, httpStatusCode = statusCode)
                } else {
                    AiAnalysisResponse(service, responseBody, null, httpHeaders = headers, httpStatusCode = statusCode)
                }
            } catch (e: Exception) {
                // If we can't parse as OpenAI format, just return the raw response
                AiAnalysisResponse(service, responseBody, null, httpHeaders = headers, httpStatusCode = statusCode)
            }
        } else {
            val errorBody = response.body?.string()
            AiAnalysisResponse(service, null, "API error: $statusCode - $errorBody", httpHeaders = headers, httpStatusCode = statusCode)
        }
    } catch (e: Exception) {
        AiAnalysisResponse(service, null, "Error: ${e.message}")
    }
}

/**
 * Fetch available Gemini models that support generateContent.
 */
suspend fun AiAnalysisRepository.fetchGeminiModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        val response = geminiApi.listModels(apiKey)
        if (response.isSuccessful) {
            val models = response.body()?.models ?: emptyList()
            // Filter models that support generateContent and return their names without "models/" prefix
            models
                .filter { model ->
                    model.supportedGenerationMethods?.contains("generateContent") == true
                }
                .mapNotNull { model ->
                    model.name?.removePrefix("models/")
                }
                .sorted()
        } else {
            android.util.Log.e("GeminiAPI", "Failed to fetch models: ${response.code()}")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("GeminiAPI", "Error fetching models: ${e.message}")
        emptyList()
    }
}

/**
 * Unified model fetching for all providers.
 * Dispatches to provider-specific methods for unique APIs (OpenAI, Anthropic, Google),
 * uses OpenAiCompatibleApi for the 28 OpenAI-compatible providers.
 */
suspend fun AiAnalysisRepository.fetchModels(service: AiService, apiKey: String): List<String> = withContext(Dispatchers.IO) {
    when (service) {
        AiService.OPENAI -> fetchChatGptModels(apiKey)
        AiService.ANTHROPIC -> fetchClaudeModels(apiKey)
        AiService.GOOGLE -> fetchGeminiModels(apiKey)
        else -> fetchModelsOpenAiCompatible(service, apiKey)
    }
}

/**
 * Fetch available ChatGPT/OpenAI models.
 */
suspend fun AiAnalysisRepository.fetchChatGptModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        val response = openAiApi.listModels("Bearer $apiKey")
        if (response.isSuccessful) {
            val models = response.body()?.data ?: emptyList()
            models
                .mapNotNull { it.id }
                .filter { it.startsWith("gpt") }
                .sorted()
        } else {
            android.util.Log.e("ChatGptAPI", "Failed to fetch models: ${response.code()}")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("ChatGptAPI", "Error fetching models: ${e.message}")
        emptyList()
    }
}

suspend fun AiAnalysisRepository.fetchClaudeModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        val response = claudeApi.listModels(apiKey)
        if (response.isSuccessful) {
            val models = response.body()?.data ?: emptyList()
            models
                .mapNotNull { it.id }
                .filter { it.startsWith("claude") }
                .sorted()
        } else {
            android.util.Log.e("ClaudeAPI", "Failed to fetch models: ${response.code()}")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("ClaudeAPI", "Error fetching models: ${e.message}")
        emptyList()
    }
}

/**
 * Unified model fetching for all 28 OpenAI-compatible providers.
 * Handles provider-specific model filters and response formats.
 */
suspend fun AiAnalysisRepository.fetchModelsOpenAiCompatible(service: AiService, apiKey: String): List<String> = withContext(Dispatchers.IO) {
    val tag = "${service.displayName}API"
    try {
        // Providers with hardcoded model lists (no API endpoint)
        when (service) {
            AiService.REPLICATE -> return@withContext com.ai.ui.REPLICATE_MODELS
            AiService.HUGGINGFACE -> return@withContext com.ai.ui.HUGGINGFACE_INFERENCE_MODELS
            AiService.LEPTON -> return@withContext com.ai.ui.LEPTON_MODELS
            AiService.DOUBAO -> return@withContext com.ai.ui.DOUBAO_MODELS
            AiService.REKA -> return@withContext com.ai.ui.REKA_MODELS
            else -> { /* fetch from API */ }
        }

        val api = AiApiFactory.createOpenAiCompatibleApi(service.baseUrl)
        val normalizedBase = if (service.baseUrl.endsWith("/")) service.baseUrl else "${service.baseUrl}/"
        val modelsUrl = normalizedBase + service.modelsPath

        // Together returns a raw array, not {data: [...]}
        val modelIds = if (service == AiService.TOGETHER) {
            val response = api.listModelsArray(modelsUrl, "Bearer $apiKey")
            if (response.isSuccessful) {
                response.body()?.mapNotNull { it.id } ?: emptyList()
            } else {
                android.util.Log.e(tag, "Failed to fetch models: ${response.code()}")
                return@withContext emptyList()
            }
        } else {
            val response = api.listModels(modelsUrl, "Bearer $apiKey")
            if (response.isSuccessful) {
                response.body()?.data?.mapNotNull { it.id } ?: emptyList()
            } else {
                android.util.Log.e(tag, "Failed to fetch models: ${response.code()}")
                return@withContext emptyList()
            }
        }

        // Apply provider-specific model filter
        val filtered = when (service) {
            AiService.XAI -> modelIds.filter { it.startsWith("grok") }
            AiService.DEEPSEEK -> modelIds.filter { it.startsWith("deepseek") }
            AiService.MISTRAL -> modelIds.filter { it.startsWith("mistral") || it.startsWith("open-mistral") || it.startsWith("codestral") || it.startsWith("pixtral") }
            AiService.PERPLEXITY -> modelIds.filter { it.contains("sonar") || it.contains("llama") }
            AiService.TOGETHER -> modelIds.filter { it.contains("chat") || it.contains("instruct", ignoreCase = true) || it.contains("llama", ignoreCase = true) }
            AiService.ZAI -> modelIds.filter { it.startsWith("glm") || it.startsWith("codegeex") || it.startsWith("charglm") }
            else -> modelIds  // No filter for most providers
        }

        filtered.sorted()
    } catch (e: Exception) {
        android.util.Log.e(tag, "Error fetching models: ${e.message}")
        emptyList()
    }
}

// Backward-compatible wrappers (used by ViewModel per-provider methods, will be consolidated in later steps)
suspend fun AiAnalysisRepository.fetchGrokModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.XAI, apiKey)
suspend fun AiAnalysisRepository.fetchGroqModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.GROQ, apiKey)
suspend fun AiAnalysisRepository.fetchDeepSeekModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.DEEPSEEK, apiKey)
suspend fun AiAnalysisRepository.fetchMistralModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.MISTRAL, apiKey)
suspend fun AiAnalysisRepository.fetchPerplexityModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.PERPLEXITY, apiKey)
suspend fun AiAnalysisRepository.fetchTogetherModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.TOGETHER, apiKey)
suspend fun AiAnalysisRepository.fetchOpenRouterModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.OPENROUTER, apiKey)
suspend fun AiAnalysisRepository.fetchSiliconFlowModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.SILICONFLOW, apiKey)
suspend fun AiAnalysisRepository.fetchZaiModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.ZAI, apiKey)
suspend fun AiAnalysisRepository.fetchMoonshotModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.MOONSHOT, apiKey)
suspend fun AiAnalysisRepository.fetchCohereModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.COHERE, apiKey)
suspend fun AiAnalysisRepository.fetchAi21Models(apiKey: String) = fetchModelsOpenAiCompatible(AiService.AI21, apiKey)
suspend fun AiAnalysisRepository.fetchDashScopeModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.DASHSCOPE, apiKey)
suspend fun AiAnalysisRepository.fetchFireworksModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.FIREWORKS, apiKey)
suspend fun AiAnalysisRepository.fetchCerebrasModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.CEREBRAS, apiKey)
suspend fun AiAnalysisRepository.fetchSambaNovaModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.SAMBANOVA, apiKey)
suspend fun AiAnalysisRepository.fetchBaichuanModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.BAICHUAN, apiKey)
suspend fun AiAnalysisRepository.fetchStepFunModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.STEPFUN, apiKey)
suspend fun AiAnalysisRepository.fetchMiniMaxModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.MINIMAX, apiKey)
suspend fun AiAnalysisRepository.fetchNvidiaModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.NVIDIA, apiKey)
suspend fun AiAnalysisRepository.fetchReplicateModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.REPLICATE, apiKey)
suspend fun AiAnalysisRepository.fetchHuggingFaceInferenceModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.HUGGINGFACE, apiKey)
suspend fun AiAnalysisRepository.fetchLambdaModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.LAMBDA, apiKey)
suspend fun AiAnalysisRepository.fetchLeptonModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.LEPTON, apiKey)
suspend fun AiAnalysisRepository.fetchYiModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.YI, apiKey)
suspend fun AiAnalysisRepository.fetchDoubaoModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.DOUBAO, apiKey)
suspend fun AiAnalysisRepository.fetchRekaModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.REKA, apiKey)
suspend fun AiAnalysisRepository.fetchWriterModels(apiKey: String) = fetchModelsOpenAiCompatible(AiService.WRITER, apiKey)
