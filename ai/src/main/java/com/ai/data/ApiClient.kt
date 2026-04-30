package com.ai.data

import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// ============================================================================
// Retrofit API interfaces
// ============================================================================

/** OpenAI native API (Chat Completions + model list). Responses API lives on
 *  OpenAiCompatibleApi as a dynamic-URL endpoint so each provider can declare its
 *  own responsesPath in setup.json. */
interface OpenAiApi {
    @POST("v1/chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiRequest
    ): Response<OpenAiResponse>

    @GET("v1/models")
    suspend fun listModels(
        @Header("Authorization") authorization: String
    ): Response<OpenAiModelsResponse>
}

/** Anthropic Messages API. */
interface ClaudeApi {
    @POST("v1/messages")
    suspend fun createMessage(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        @Body request: ClaudeRequest
    ): Response<ClaudeResponse>

    @Streaming
    @POST("v1/messages")
    suspend fun createMessageStream(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        @Body request: ClaudeRequest
    ): Response<okhttp3.ResponseBody>

    @GET("v1/models")
    suspend fun listModels(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01"
    ): Response<ClaudeModelsResponse>
}

/** Google Gemini GenerativeAI API. */
interface GeminiApi {
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): Response<GeminiResponse>

    @Streaming
    @POST("v1beta/models/{model}:streamGenerateContent")
    suspend fun streamGenerateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Query("alt") alt: String = "sse",
        @Body request: GeminiRequest
    ): Response<okhttp3.ResponseBody>

    @GET("v1beta/models")
    suspend fun listModels(
        @Query("key") apiKey: String
    ): Response<GeminiModelsResponse>
}

/** Unified interface for all 28 OpenAI-compatible providers (dynamic @Url). */
interface OpenAiCompatibleApi {
    @POST
    suspend fun chat(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: OpenAiRequest
    ): Response<OpenAiResponse>

    @Streaming
    @POST
    suspend fun chatStream(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: OpenAiRequest
    ): Response<okhttp3.ResponseBody>

    @POST
    suspend fun responses(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: OpenAiResponsesRequest
    ): Response<OpenAiResponsesApiResponse>

    @Streaming
    @POST
    suspend fun responsesStream(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: OpenAiResponsesRequest
    ): Response<okhttp3.ResponseBody>

    @GET
    suspend fun listModels(
        @Url url: String,
        @Header("Authorization") authorization: String
    ): Response<OpenAiModelsResponse>

    @GET
    suspend fun listModelsArray(
        @Url url: String,
        @Header("Authorization") authorization: String
    ): Response<List<OpenAiModel>>

    @POST
    suspend fun embeddings(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: OpenAiEmbeddingRequest
    ): Response<OpenAiEmbeddingResponse>
}

/** OpenRouter detailed model list. */
interface OpenRouterModelsApi {
    @GET("v1/models")
    suspend fun listModelsDetailed(
        @Header("Authorization") authorization: String
    ): Response<OpenRouterModelsDetailedResponse>
}

/** Cohere native model list — has the `endpoints` field per model that the
 *  /compatibility endpoint strips. Always points at api.cohere.com regardless
 *  of which Cohere base URL the user has configured. */
interface CohereNativeApi {
    @GET("v1/models")
    suspend fun listModels(
        @Header("Authorization") authorization: String,
        @retrofit2.http.Query("page_size") pageSize: Int = 1000
    ): Response<CohereModelsResponse>
}

/** Cohere v2 rerank API. Lives on api.cohere.com (not the compatibility
 *  shim, which doesn't forward /rerank). */
interface CohereRerankApi {
    @POST("v2/rerank")
    suspend fun rerank(
        @Header("Authorization") authorization: String,
        @Body request: CohereRerankRequest
    ): Response<CohereRerankResponse>
}

/** Mistral moderation API. Returns per-input category booleans + scores
 *  against ~9 harmful-content categories. Plain HTTP — no streaming. */
interface MistralModerationApi {
    @POST("v1/moderations")
    suspend fun moderate(
        @Header("Authorization") authorization: String,
        @Body request: MistralModerationRequest
    ): Response<MistralModerationResponse>
}

/** Hugging Face model info. */
interface HuggingFaceApi {
    @GET("models/{modelId}")
    suspend fun getModelInfo(
        @Path("modelId", encoded = true) modelId: String,
        @Header("Authorization") authorization: String
    ): Response<HuggingFaceModelInfo>
}

// ============================================================================
// ApiFactory — creates cached Retrofit instances
// ============================================================================

object ApiFactory {
    private val retrofitCache = ConcurrentHashMap<String, Retrofit>()
    private val gsonConverterFactory = GsonConverterFactory.create()

    private val okHttpClient = OkHttpClient.Builder()
        // Rate-limit retry first so each attempt (including retries) flows
        // through the trace recorder below — visible on the Trace screen.
        .addInterceptor(RateLimitRetryInterceptor())
        .addInterceptor(TracingInterceptor())
        .connectTimeout(com.ai.BuildConfig.NETWORK_CONNECT_TIMEOUT_SEC.toLong(), TimeUnit.SECONDS)
        .readTimeout(com.ai.BuildConfig.NETWORK_READ_TIMEOUT_SEC.toLong(), TimeUnit.SECONDS)
        .writeTimeout(com.ai.BuildConfig.NETWORK_WRITE_TIMEOUT_SEC.toLong(), TimeUnit.SECONDS)
        .build()

    private fun getRetrofit(baseUrl: String): Retrofit {
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return retrofitCache.getOrPut(normalizedUrl) {
            Retrofit.Builder()
                .baseUrl(normalizedUrl)
                .client(okHttpClient)
                .addConverterFactory(gsonConverterFactory)
                .build()
        }
    }

    /** Plain GET that returns the raw response body as a String — used by
     *  the model-list snapshot capture in [com.ai.data.fetchModelsWithKinds]
     *  so we can keep the unparsed JSON alongside the typed parse. Goes
     *  through the same TracingInterceptor as Retrofit calls. */
    fun fetchUrlAsString(url: String, headers: Map<String, String> = emptyMap()): String? {
        return try {
            val builder = okhttp3.Request.Builder().url(url).get()
            headers.forEach { (k, v) -> builder.addHeader(k, v) }
            okHttpClient.newCall(builder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (_: Exception) { null }
    }

    fun createOpenAiApi(baseUrl: String): OpenAiApi = getRetrofit(baseUrl).create(OpenAiApi::class.java)
    fun createClaudeApi(baseUrl: String): ClaudeApi = getRetrofit(baseUrl).create(ClaudeApi::class.java)
    fun createGeminiApi(baseUrl: String): GeminiApi = getRetrofit(baseUrl).create(GeminiApi::class.java)
    fun createOpenAiCompatibleApi(baseUrl: String): OpenAiCompatibleApi = getRetrofit(baseUrl).create(OpenAiCompatibleApi::class.java)
    fun createOpenRouterModelsApi(baseUrl: String): OpenRouterModelsApi = getRetrofit(baseUrl).create(OpenRouterModelsApi::class.java)
    fun createCohereNativeApi(): CohereNativeApi = getRetrofit("https://api.cohere.com/").create(CohereNativeApi::class.java)
    fun createCohereRerankApi(): CohereRerankApi = getRetrofit("https://api.cohere.com/").create(CohereRerankApi::class.java)
    fun createMistralModerationApi(): MistralModerationApi = getRetrofit("https://api.mistral.ai/").create(MistralModerationApi::class.java)
    fun createHuggingFaceApi(): HuggingFaceApi = getRetrofit("https://huggingface.co/api/").create(HuggingFaceApi::class.java)
}
