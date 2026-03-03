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

/** OpenAI native API (Chat Completions + Responses API + model list). */
interface OpenAiApi {
    @POST("v1/chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiRequest
    ): Response<OpenAiResponse>

    @POST("v1/responses")
    suspend fun createResponse(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiResponsesRequest
    ): Response<OpenAiResponsesApiResponse>

    @Streaming
    @POST("v1/responses")
    suspend fun createResponseStream(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiResponsesRequest
    ): Response<okhttp3.ResponseBody>

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
}

/** OpenRouter detailed model list. */
interface OpenRouterModelsApi {
    @GET("v1/models")
    suspend fun listModelsDetailed(
        @Header("Authorization") authorization: String
    ): Response<OpenRouterModelsDetailedResponse>
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
        .addInterceptor(TracingInterceptor())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
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

    fun createOpenAiApi(baseUrl: String): OpenAiApi = getRetrofit(baseUrl).create(OpenAiApi::class.java)
    fun createClaudeApi(baseUrl: String): ClaudeApi = getRetrofit(baseUrl).create(ClaudeApi::class.java)
    fun createGeminiApi(baseUrl: String): GeminiApi = getRetrofit(baseUrl).create(GeminiApi::class.java)
    fun createOpenAiCompatibleApi(baseUrl: String): OpenAiCompatibleApi = getRetrofit(baseUrl).create(OpenAiCompatibleApi::class.java)
    fun createOpenRouterModelsApi(baseUrl: String): OpenRouterModelsApi = getRetrofit(baseUrl).create(OpenRouterModelsApi::class.java)
    fun createHuggingFaceApi(): HuggingFaceApi = getRetrofit("https://huggingface.co/api/").create(HuggingFaceApi::class.java)
}
