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
            AiService.XAI -> analyzeWithGrok(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.GROQ -> analyzeWithGroq(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.DEEPSEEK -> analyzeWithDeepSeek(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.MISTRAL -> analyzeWithMistral(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.PERPLEXITY -> analyzeWithPerplexity(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.TOGETHER -> analyzeWithTogether(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.OPENROUTER -> analyzeWithOpenRouter(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.SILICONFLOW -> analyzeWithSiliconFlow(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.ZAI -> analyzeWithZai(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.MOONSHOT -> analyzeWithMoonshot(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.COHERE -> analyzeWithCohere(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.AI21 -> analyzeWithAi21(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.DASHSCOPE -> analyzeWithDashScope(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.FIREWORKS -> analyzeWithFireworks(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.CEREBRAS -> analyzeWithCerebras(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.SAMBANOVA -> analyzeWithSambaNova(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.BAICHUAN -> analyzeWithBaichuan(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.STEPFUN -> analyzeWithStepFun(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.MINIMAX -> analyzeWithMiniMax(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.NVIDIA -> analyzeWithNvidia(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.REPLICATE -> analyzeWithReplicate(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.HUGGINGFACE -> analyzeWithHuggingFaceInference(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.LAMBDA -> analyzeWithLambda(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.LEPTON -> analyzeWithLepton(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.YI -> analyzeWithYi(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.DOUBAO -> analyzeWithDoubao(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.REKA -> analyzeWithReka(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
            AiService.WRITER -> analyzeWithWriter(apiKey, AiAnalysisRepository.TEST_PROMPT, model)
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
 * Fetch available Grok models.
 */
suspend fun AiAnalysisRepository.fetchGrokModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        val response = grokApi.listModels("Bearer $apiKey")
        if (response.isSuccessful) {
            val models = response.body()?.data ?: emptyList()
            models
                .mapNotNull { it.id }
                .filter { it.startsWith("grok") }  // Only include grok models
                .sorted()
        } else {
            android.util.Log.e("GrokAPI", "Failed to fetch models: ${response.code()}")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("GrokAPI", "Error fetching models: ${e.message}")
        emptyList()
    }
}

/**
 * Fetch available Groq models.
 */
suspend fun AiAnalysisRepository.fetchGroqModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        val response = groqApi.listModels("Bearer $apiKey")
        if (response.isSuccessful) {
            val models = response.body()?.data ?: emptyList()
            models
                .mapNotNull { it.id }
                .sorted()
        } else {
            android.util.Log.e("GroqAPI", "Failed to fetch models: ${response.code()}")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("GroqAPI", "Error fetching models: ${e.message}")
        emptyList()
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
                .filter { it.startsWith("gpt") }  // Only include GPT models
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

/**
 * Fetch available DeepSeek models.
 */
suspend fun AiAnalysisRepository.fetchDeepSeekModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        val response = deepSeekApi.listModels("Bearer $apiKey")
        if (response.isSuccessful) {
            val models = response.body()?.data ?: emptyList()
            models
                .mapNotNull { it.id }
                .filter { it.startsWith("deepseek") }  // Only include DeepSeek models
                .sorted()
        } else {
            android.util.Log.e("DeepSeekAPI", "Failed to fetch models: ${response.code()}")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("DeepSeekAPI", "Error fetching models: ${e.message}")
        emptyList()
    }
}

/**
 * Fetch available Mistral models.
 */
suspend fun AiAnalysisRepository.fetchMistralModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        val response = mistralApi.listModels("Bearer $apiKey")
        if (response.isSuccessful) {
            val models = response.body()?.data ?: emptyList()
            models
                .mapNotNull { it.id }
                .filter { it.startsWith("mistral") || it.startsWith("open-mistral") || it.startsWith("codestral") || it.startsWith("pixtral") }
                .sorted()
        } else {
            android.util.Log.e("MistralAPI", "Failed to fetch models: ${response.code()}")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("MistralAPI", "Error fetching models: ${e.message}")
        emptyList()
    }
}

/**
 * Fetch available Perplexity models.
 */
suspend fun AiAnalysisRepository.fetchPerplexityModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        val response = perplexityApi.listModels("Bearer $apiKey")
        if (response.isSuccessful) {
            val models = response.body()?.data ?: emptyList()
            models
                .mapNotNull { it.id }
                .filter { it.contains("sonar") || it.contains("llama") }
                .sorted()
        } else {
            android.util.Log.e("PerplexityAPI", "Failed to fetch models: ${response.code()}")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("PerplexityAPI", "Error fetching models: ${e.message}")
        emptyList()
    }
}

/**
 * Fetch available Together AI models.
 * Note: Together API returns a raw array, not {"data": [...]}
 */
suspend fun AiAnalysisRepository.fetchTogetherModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        val response = togetherApi.listModels("Bearer $apiKey")
        if (response.isSuccessful) {
            val models = response.body() ?: emptyList()
            models
                .mapNotNull { it.id }
                .filter { it.contains("chat") || it.contains("instruct", ignoreCase = true) || it.contains("llama", ignoreCase = true) }
                .sorted()
        } else {
            android.util.Log.e("TogetherAPI", "Failed to fetch models: ${response.code()}")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("TogetherAPI", "Error fetching models: ${e.message}")
        emptyList()
    }
}

/**
 * Fetch available OpenRouter models.
 */
suspend fun AiAnalysisRepository.fetchOpenRouterModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        val response = openRouterApi.listModels("Bearer $apiKey")
        if (response.isSuccessful) {
            val models = response.body()?.data ?: emptyList()
            models
                .mapNotNull { it.id }
                .sorted()
        } else {
            android.util.Log.e("OpenRouterAPI", "Failed to fetch models: ${response.code()}")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("OpenRouterAPI", "Error fetching models: ${e.message}")
        emptyList()
    }
}

suspend fun AiAnalysisRepository.fetchClaudeModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        android.util.Log.d("ClaudeAPI", "Fetching models from Anthropic API...")
        val response = claudeApi.listModels(apiKey)
        android.util.Log.d("ClaudeAPI", "Response code: ${response.code()}")
        if (response.isSuccessful) {
            val body = response.body()
            android.util.Log.d("ClaudeAPI", "Response body: $body")
            val models = body?.data ?: emptyList()
            val result = models
                .mapNotNull { it.id }
                .filter { it.startsWith("claude") }  // Only include Claude models
                .sorted()
            android.util.Log.d("ClaudeAPI", "Found ${result.size} Claude models")
            result
        } else {
            val errorBody = response.errorBody()?.string()
            android.util.Log.e("ClaudeAPI", "Failed to fetch models: ${response.code()} - $errorBody")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("ClaudeAPI", "Error fetching models: ${e.message}", e)
        emptyList()
    }
}

suspend fun AiAnalysisRepository.fetchSiliconFlowModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        android.util.Log.d("SiliconFlowAPI", "Fetching models from SiliconFlow API...")
        val response = siliconFlowApi.listModels("Bearer $apiKey")
        android.util.Log.d("SiliconFlowAPI", "Response code: ${response.code()}")
        if (response.isSuccessful) {
            val body = response.body()
            android.util.Log.d("SiliconFlowAPI", "Response body data count: ${body?.data?.size ?: 0}")
            val models = body?.data ?: emptyList()
            val result = models
                .mapNotNull { it.id }
                .sorted()
            android.util.Log.d("SiliconFlowAPI", "Found ${result.size} SiliconFlow models")
            result
        } else {
            val errorBody = response.errorBody()?.string()
            android.util.Log.e("SiliconFlowAPI", "Failed to fetch models: ${response.code()} - $errorBody")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("SiliconFlowAPI", "Error fetching models: ${e.message}", e)
        emptyList()
    }
}

suspend fun AiAnalysisRepository.fetchZaiModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        android.util.Log.d("ZaiAPI", "Fetching models from Z.AI API...")
        val response = zaiApi.listModels("Bearer $apiKey")
        android.util.Log.d("ZaiAPI", "Response code: ${response.code()}")
        if (response.isSuccessful) {
            val body = response.body()
            android.util.Log.d("ZaiAPI", "Response body data count: ${body?.data?.size ?: 0}")
            val models = body?.data ?: emptyList()
            val result = models
                .mapNotNull { it.id }
                .filter { it.startsWith("glm") || it.startsWith("codegeex") || it.startsWith("charglm") }  // Only include GLM models
                .sorted()
            android.util.Log.d("ZaiAPI", "Found ${result.size} Z.AI models")
            result
        } else {
            val errorBody = response.errorBody()?.string()
            android.util.Log.e("ZaiAPI", "Failed to fetch models: ${response.code()} - $errorBody")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("ZaiAPI", "Error fetching models: ${e.message}", e)
        emptyList()
    }
}

suspend fun AiAnalysisRepository.fetchMoonshotModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        android.util.Log.d("MoonshotAPI", "Fetching models from Moonshot API...")
        val response = moonshotApi.listModels("Bearer $apiKey")
        android.util.Log.d("MoonshotAPI", "Response code: ${response.code()}")
        if (response.isSuccessful) {
            val body = response.body()
            android.util.Log.d("MoonshotAPI", "Response body data count: ${body?.data?.size ?: 0}")
            val models = body?.data ?: emptyList()
            val result = models
                .mapNotNull { it.id }
                .sorted()
            android.util.Log.d("MoonshotAPI", "Found ${result.size} Moonshot models")
            result
        } else {
            val errorBody = response.errorBody()?.string()
            android.util.Log.e("MoonshotAPI", "Failed to fetch models: ${response.code()} - $errorBody")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("MoonshotAPI", "Error fetching models: ${e.message}", e)
        emptyList()
    }
}

suspend fun AiAnalysisRepository.fetchCohereModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        android.util.Log.d("CohereAPI", "Fetching models from Cohere API...")
        val response = cohereApi.listModels("Bearer $apiKey")
        android.util.Log.d("CohereAPI", "Response code: ${response.code()}")
        if (response.isSuccessful) {
            val body = response.body()
            android.util.Log.d("CohereAPI", "Response body data count: ${body?.data?.size ?: 0}")
            val models = body?.data ?: emptyList()
            val result = models
                .mapNotNull { it.id }
                .sorted()
            android.util.Log.d("CohereAPI", "Found ${result.size} Cohere models")
            result
        } else {
            val errorBody = response.errorBody()?.string()
            android.util.Log.e("CohereAPI", "Failed to fetch models: ${response.code()} - $errorBody")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("CohereAPI", "Error fetching models: ${e.message}", e)
        emptyList()
    }
}

suspend fun AiAnalysisRepository.fetchAi21Models(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        android.util.Log.d("AI21API", "Fetching models from AI21 API...")
        val response = ai21Api.listModels("Bearer $apiKey")
        android.util.Log.d("AI21API", "Response code: ${response.code()}")
        if (response.isSuccessful) {
            val body = response.body()
            android.util.Log.d("AI21API", "Response body data count: ${body?.data?.size ?: 0}")
            val models = body?.data ?: emptyList()
            val result = models
                .mapNotNull { it.id }
                .sorted()
            android.util.Log.d("AI21API", "Found ${result.size} AI21 models")
            result
        } else {
            val errorBody = response.errorBody()?.string()
            android.util.Log.e("AI21API", "Failed to fetch models: ${response.code()} - $errorBody")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("AI21API", "Error fetching models: ${e.message}", e)
        emptyList()
    }
}

suspend fun AiAnalysisRepository.fetchDashScopeModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        android.util.Log.d("DashScopeAPI", "Fetching models from DashScope API...")
        val response = dashScopeApi.listModels("Bearer $apiKey")
        android.util.Log.d("DashScopeAPI", "Response code: ${response.code()}")
        if (response.isSuccessful) {
            val body = response.body()
            android.util.Log.d("DashScopeAPI", "Response body data count: ${body?.data?.size ?: 0}")
            val models = body?.data ?: emptyList()
            val result = models
                .mapNotNull { it.id }
                .sorted()
            android.util.Log.d("DashScopeAPI", "Found ${result.size} DashScope models")
            result
        } else {
            val errorBody = response.errorBody()?.string()
            android.util.Log.e("DashScopeAPI", "Failed to fetch models: ${response.code()} - $errorBody")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("DashScopeAPI", "Error fetching models: ${e.message}", e)
        emptyList()
    }
}

suspend fun AiAnalysisRepository.fetchFireworksModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        android.util.Log.d("FireworksAPI", "Fetching models from Fireworks API...")
        val response = fireworksApi.listModels("Bearer $apiKey")
        android.util.Log.d("FireworksAPI", "Response code: ${response.code()}")
        if (response.isSuccessful) {
            val body = response.body()
            android.util.Log.d("FireworksAPI", "Response body data count: ${body?.data?.size ?: 0}")
            val models = body?.data ?: emptyList()
            val result = models
                .mapNotNull { it.id }
                .sorted()
            android.util.Log.d("FireworksAPI", "Found ${result.size} Fireworks models")
            result
        } else {
            val errorBody = response.errorBody()?.string()
            android.util.Log.e("FireworksAPI", "Failed to fetch models: ${response.code()} - $errorBody")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("FireworksAPI", "Error fetching models: ${e.message}", e)
        emptyList()
    }
}

suspend fun AiAnalysisRepository.fetchCerebrasModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        android.util.Log.d("CerebrasAPI", "Fetching models from Cerebras API...")
        val response = cerebrasApi.listModels("Bearer $apiKey")
        android.util.Log.d("CerebrasAPI", "Response code: ${response.code()}")
        if (response.isSuccessful) {
            val body = response.body()
            android.util.Log.d("CerebrasAPI", "Response body data count: ${body?.data?.size ?: 0}")
            val models = body?.data ?: emptyList()
            val result = models
                .mapNotNull { it.id }
                .sorted()
            android.util.Log.d("CerebrasAPI", "Found ${result.size} Cerebras models")
            result
        } else {
            val errorBody = response.errorBody()?.string()
            android.util.Log.e("CerebrasAPI", "Failed to fetch models: ${response.code()} - $errorBody")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("CerebrasAPI", "Error fetching models: ${e.message}", e)
        emptyList()
    }
}

suspend fun AiAnalysisRepository.fetchSambaNovaModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        android.util.Log.d("SambaNovaAPI", "Fetching models from SambaNova API...")
        val response = sambaNovaApi.listModels("Bearer $apiKey")
        android.util.Log.d("SambaNovaAPI", "Response code: ${response.code()}")
        if (response.isSuccessful) {
            val body = response.body()
            android.util.Log.d("SambaNovaAPI", "Response body data count: ${body?.data?.size ?: 0}")
            val models = body?.data ?: emptyList()
            val result = models
                .mapNotNull { it.id }
                .sorted()
            android.util.Log.d("SambaNovaAPI", "Found ${result.size} SambaNova models")
            result
        } else {
            val errorBody = response.errorBody()?.string()
            android.util.Log.e("SambaNovaAPI", "Failed to fetch models: ${response.code()} - $errorBody")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("SambaNovaAPI", "Error fetching models: ${e.message}", e)
        emptyList()
    }
}

suspend fun AiAnalysisRepository.fetchBaichuanModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        android.util.Log.d("BaichuanAPI", "Fetching models from Baichuan API...")
        val response = baichuanApi.listModels("Bearer $apiKey")
        android.util.Log.d("BaichuanAPI", "Response code: ${response.code()}")
        if (response.isSuccessful) {
            val body = response.body()
            android.util.Log.d("BaichuanAPI", "Response body data count: ${body?.data?.size ?: 0}")
            val models = body?.data ?: emptyList()
            val result = models
                .mapNotNull { it.id }
                .sorted()
            android.util.Log.d("BaichuanAPI", "Found ${result.size} Baichuan models")
            result
        } else {
            val errorBody = response.errorBody()?.string()
            android.util.Log.e("BaichuanAPI", "Failed to fetch models: ${response.code()} - $errorBody")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("BaichuanAPI", "Error fetching models: ${e.message}", e)
        emptyList()
    }
}

suspend fun AiAnalysisRepository.fetchStepFunModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        android.util.Log.d("StepFunAPI", "Fetching models from StepFun API...")
        val response = stepFunApi.listModels("Bearer $apiKey")
        android.util.Log.d("StepFunAPI", "Response code: ${response.code()}")
        if (response.isSuccessful) {
            val body = response.body()
            android.util.Log.d("StepFunAPI", "Response body data count: ${body?.data?.size ?: 0}")
            val models = body?.data ?: emptyList()
            val result = models
                .mapNotNull { it.id }
                .sorted()
            android.util.Log.d("StepFunAPI", "Found ${result.size} StepFun models")
            result
        } else {
            val errorBody = response.errorBody()?.string()
            android.util.Log.e("StepFunAPI", "Failed to fetch models: ${response.code()} - $errorBody")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("StepFunAPI", "Error fetching models: ${e.message}", e)
        emptyList()
    }
}

suspend fun AiAnalysisRepository.fetchMiniMaxModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        android.util.Log.d("MiniMaxAPI", "Fetching models from MiniMax API...")
        val response = miniMaxApi.listModels("Bearer $apiKey")
        android.util.Log.d("MiniMaxAPI", "Response code: ${response.code()}")
        if (response.isSuccessful) {
            val body = response.body()
            android.util.Log.d("MiniMaxAPI", "Response body data count: ${body?.data?.size ?: 0}")
            val models = body?.data ?: emptyList()
            val result = models
                .mapNotNull { it.id }
                .sorted()
            android.util.Log.d("MiniMaxAPI", "Found ${result.size} MiniMax models")
            result
        } else {
            val errorBody = response.errorBody()?.string()
            android.util.Log.e("MiniMaxAPI", "Failed to fetch models: ${response.code()} - $errorBody")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("MiniMaxAPI", "Error fetching models: ${e.message}", e)
        emptyList()
    }
}

suspend fun AiAnalysisRepository.fetchNvidiaModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        android.util.Log.d("NvidiaAPI", "Fetching models from NVIDIA API...")
        val response = nvidiaApi.listModels("Bearer $apiKey")
        android.util.Log.d("NvidiaAPI", "Response code: ${response.code()}")
        if (response.isSuccessful) {
            val body = response.body()
            val models = body?.data ?: emptyList()
            val result = models
                .mapNotNull { it.id }
                .sorted()
            android.util.Log.d("NvidiaAPI", "Found ${result.size} NVIDIA models")
            result
        } else {
            val errorBody = response.errorBody()?.string()
            android.util.Log.e("NvidiaAPI", "Failed to fetch models: ${response.code()} - $errorBody")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("NvidiaAPI", "Error fetching models: ${e.message}", e)
        emptyList()
    }
}

suspend fun AiAnalysisRepository.fetchReplicateModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    com.ai.ui.REPLICATE_MODELS
}

suspend fun AiAnalysisRepository.fetchHuggingFaceInferenceModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    com.ai.ui.HUGGINGFACE_INFERENCE_MODELS
}

suspend fun AiAnalysisRepository.fetchLambdaModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        android.util.Log.d("LambdaAPI", "Fetching models from Lambda API...")
        val response = lambdaApi.listModels("Bearer $apiKey")
        android.util.Log.d("LambdaAPI", "Response code: ${response.code()}")
        if (response.isSuccessful) {
            val body = response.body()
            val models = body?.data ?: emptyList()
            val result = models
                .mapNotNull { it.id }
                .sorted()
            android.util.Log.d("LambdaAPI", "Found ${result.size} Lambda models")
            result
        } else {
            val errorBody = response.errorBody()?.string()
            android.util.Log.e("LambdaAPI", "Failed to fetch models: ${response.code()} - $errorBody")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("LambdaAPI", "Error fetching models: ${e.message}", e)
        emptyList()
    }
}

suspend fun AiAnalysisRepository.fetchLeptonModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    com.ai.ui.LEPTON_MODELS
}

suspend fun AiAnalysisRepository.fetchYiModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        android.util.Log.d("YiAPI", "Fetching models from 01.AI API...")
        val response = yiApi.listModels("Bearer $apiKey")
        android.util.Log.d("YiAPI", "Response code: ${response.code()}")
        if (response.isSuccessful) {
            val body = response.body()
            val models = body?.data ?: emptyList()
            val result = models
                .mapNotNull { it.id }
                .sorted()
            android.util.Log.d("YiAPI", "Found ${result.size} Yi models")
            result
        } else {
            val errorBody = response.errorBody()?.string()
            android.util.Log.e("YiAPI", "Failed to fetch models: ${response.code()} - $errorBody")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("YiAPI", "Error fetching models: ${e.message}", e)
        emptyList()
    }
}

suspend fun AiAnalysisRepository.fetchDoubaoModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    com.ai.ui.DOUBAO_MODELS
}

suspend fun AiAnalysisRepository.fetchRekaModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    com.ai.ui.REKA_MODELS
}

suspend fun AiAnalysisRepository.fetchWriterModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        android.util.Log.d("WriterAPI", "Fetching models from Writer API...")
        val response = writerApi.listModels("Bearer $apiKey")
        android.util.Log.d("WriterAPI", "Response code: ${response.code()}")
        if (response.isSuccessful) {
            val body = response.body()
            val models = body?.data ?: emptyList()
            val result = models
                .mapNotNull { it.id }
                .sorted()
            android.util.Log.d("WriterAPI", "Found ${result.size} Writer models")
            result
        } else {
            val errorBody = response.errorBody()?.string()
            android.util.Log.e("WriterAPI", "Failed to fetch models: ${response.code()} - $errorBody")
            emptyList()
        }
    } catch (e: Exception) {
        android.util.Log.e("WriterAPI", "Error fetching models: ${e.message}", e)
        emptyList()
    }
}
