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

/** Replicate async predictions API. The model id (owner/name) is part of
 *  the path, so we pass the full URL via [@Url]. `Prefer: wait` makes the
 *  POST block until the prediction completes and returns it inline — no
 *  separate poll. */
interface ReplicateApi {
    @POST
    suspend fun createPrediction(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Header("Prefer") prefer: String,
        @Body request: ReplicatePredictionRequest
    ): Response<ReplicatePredictionResponse>
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

    @POST("v1beta/models/{model}:batchEmbedContents")
    suspend fun batchEmbedContents(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GeminiBatchEmbedRequest
    ): Response<GeminiBatchEmbedResponse>
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
    @GET
    suspend fun listModels(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @retrofit2.http.Query("page_size") pageSize: Int = 1000
    ): Response<CohereModelsResponse>
}

/** Native rerank API (Cohere v2 shape). URL comes from
 *  ProviderDefinition.nativeRerankUrl so the dispatch is config-driven. */
interface CohereRerankApi {
    @POST
    suspend fun rerank(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: CohereRerankRequest
    ): Response<CohereRerankResponse>
}

/** Native moderation API (Mistral v1/moderations shape). URL comes from
 *  ProviderDefinition.nativeModerationUrl so the dispatch is config-driven. */
interface MistralModerationApi {
    @POST
    suspend fun moderate(
        @Url url: String,
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

/** CloudPrice per-model detail (capabilities, modalities, context window,
 *  deprecation, provider ids). Keyless. Returns the raw JSON body — the
 *  object is large and we only display it on Model Info, so there's no typed
 *  model. A non-2xx (e.g. 404 for a model CloudPrice doesn't carry) surfaces
 *  as `Response.isSuccessful == false` with no log/toast — the caller treats
 *  it as a cached miss, exactly like the HuggingFace lookup. */
interface CloudPriceApi {
    @GET("api/v1/models/{id}")
    suspend fun getModel(
        @Path("id", encoded = true) id: String
    ): Response<okhttp3.ResponseBody>
}

// ============================================================================
// ApiFactory — creates cached Retrofit instances
// ============================================================================

object ApiFactory {
    private val retrofitCache = ConcurrentHashMap<String, Retrofit>()
    private val gsonConverterFactory = GsonConverterFactory.create()

    private val okHttpClient = OkHttpClient.Builder()
        // Restore per-call trace/throttle context captured at OkHttp
        // Call construction time. This makes queued-call promotion
        // race-free: the request carries its originating context even
        // if OkHttp later submits it from a different worker thread.
        .addInterceptor(OkHttpCallContextInterceptor())
        // Rate-limit retry first so each attempt (including retries) flows
        // through the trace recorder below — visible on the Trace screen.
        .addInterceptor(RateLimitRetryInterceptor())
        // 529 (server overloaded) retry sibling. Independent budget so a
        // 529 burst can't eat the 429 retry count. Sits outside throttle
        // / timeout / tracing for the same reason as the 429 retry.
        .addInterceptor(OverloadedRetryInterceptor())
        // Per-provider concurrency + per-minute rate gate. Sits inside
        // the retry interceptors so each 429 / 529 retry re-acquires its
        // own slot; sits outside the timeout / tracing interceptors so a
        // throttle wait doesn't count against the read-timeout window.
        .addInterceptor(ProviderThrottleInterceptor())
        // Sets the per-call read timeout from the user-tunable
        // NetworkSettings — streaming requests (SSE chat/report) get
        // the long streamingReadTimeoutSec; everything else gets the
        // shorter nonStreamingReadTimeoutSec. Without this every call
        // would inherit the OkHttp client's static read timeout below,
        // which is the streaming default and far too long for a hung
        // non-streaming call.
        .addInterceptor(ReadTimeoutInterceptor())
        // Provider-test calls (Refresh-all per-provider tests, Test
        // button, raw-JSON submit) need a much shorter read timeout
        // than streaming chat/report calls. This interceptor overrides
        // per-call when the trace category is "Provider test"; every
        // other call keeps the 10-minute streaming default. Placed
        // ahead of TracingInterceptor so a timeout cancellation still
        // produces a captured trace.
        .addInterceptor(TestCallTimeoutInterceptor())
        .addInterceptor(TracingInterceptor())
        // Innermost: tally every network response code (and failures as 0)
        // into HttpStatusStats for the Live Dashboard's HTTP-responses card.
        // Sits below the retries so each per-attempt 429 is counted, and
        // below tracing so it runs regardless of the tracing toggle.
        .addInterceptor(HttpStatusStatsInterceptor())
        // Propagate ApiTracer.currentTags from the calling coroutine
        // onto the dispatcher worker thread so concurrent flows don't
        // race a process-wide tag pair.
        .dispatcher(okhttp3.Dispatcher(TagPropagatingExecutor(
            java.util.concurrent.Executors.newCachedThreadPool { r ->
                Thread(r, "OkHttp Dispatcher").apply { isDaemon = false }
            }
        )).apply {
            // The app's ProviderThrottle (per-host concurrency + per-minute
            // window + global cap) is the SOLE intended throttle. OkHttp's
            // dispatcher must never be the binding limit — its default
            // maxRequestsPerHost=5 deadlocked against ProviderThrottle:
            // the per-host throttle is acquired INSIDE the interceptor, so a
            // call waiting for an app permit still occupies an OkHttp per-host
            // slot. Metadata/secondary calls (no pre-acquire) filled all 5
            // slots blocked on the app permit, while pre-acquired report calls
            // HELD the app permits but couldn't get an OkHttp slot to run —
            // a cross-layer deadlock. Set both ceilings well above the global
            // cap so OkHttp gates nothing.
            maxRequests = 512
            maxRequestsPerHost = 512
        })
        .connectTimeout(com.ai.BuildConfig.NETWORK_CONNECT_TIMEOUT_SEC.toLong(), TimeUnit.SECONDS)
        .readTimeout(com.ai.BuildConfig.NETWORK_READ_TIMEOUT_SEC.toLong(), TimeUnit.SECONDS)
        .writeTimeout(com.ai.BuildConfig.NETWORK_WRITE_TIMEOUT_SEC.toLong(), TimeUnit.SECONDS)
        // Total-call ceiling: a per-read timeout never fires on a hung stream
        // that the server keeps alive with keepalives, so without this a wedged
        // call holds its ProviderThrottle permit forever and the run never
        // finishes (the frozen-throttle stall). Bounds the whole call so a hang
        // eventually errors the agent, releases the permit, and lets the run
        // complete (and surface as broken work). Generous — see the BuildConfig
        // comment — so legitimate slow streams are never clipped.
        .callTimeout(com.ai.BuildConfig.NETWORK_CALL_TIMEOUT_SEC.toLong(), TimeUnit.SECONDS)
        .build()

    private val callFactory = ContextTaggingCallFactory(okHttpClient)
    private val rawFetchClient = okHttpClient.newBuilder().apply {
        // Raw model-list snapshot fetches are best-effort sidecars.
        // Keep tracing/throttle visibility, but drop the sleeping retry
        // interceptors so a 429/529 does not pin the caller while the
        // typed model-list request is already handling the real result.
        interceptors().clear()
        addInterceptor(OkHttpCallContextInterceptor())
        addInterceptor(ProviderThrottleInterceptor())
        addInterceptor(ReadTimeoutInterceptor())
        addInterceptor(TestCallTimeoutInterceptor())
        addInterceptor(TracingInterceptor())
        addInterceptor(HttpStatusStatsInterceptor())
    }.build()

    private fun getRetrofit(baseUrl: String, cacheNamespace: String): Retrofit {
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val cacheKey = "$cacheNamespace|$normalizedUrl"
        return retrofitCache.getOrPut(cacheKey) {
            Retrofit.Builder()
                .baseUrl(normalizedUrl)
                .callFactory(callFactory)
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
            rawFetchClient.newCall(builder.build().withCapturedOkHttpCallContext()).execute().use { resp ->
                if (resp.isSuccessful) resp.body.string()
                else {
                    AppLog.w("ApiClient", "fetchUrlAsString non-2xx ${resp.code} for $url — raw snapshot skipped")
                    null
                }
            }
        } catch (e: Exception) {
            // Leave a breadcrumb (Bug 67): the blanket swallow previously
            // stored a null raw snapshot with no clue why.
            AppLog.w("ApiClient", "fetchUrlAsString failed for $url: ${e.message}")
            null
        }
    }

    /** Plain GET that returns the raw response body as a ByteArray — used by
     *  the TrueFoundry tier to download the whole-repo `.tar.gz` archive for
     *  on-device unpacking. Follows redirects (the GitHub archive URL bounces
     *  to codeload) and goes through the same TracingInterceptor as
     *  [fetchUrlAsString]. */
    fun fetchUrlAsBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray? {
        return try {
            val builder = okhttp3.Request.Builder().url(url).get()
            headers.forEach { (k, v) -> builder.addHeader(k, v) }
            rawFetchClient.newCall(builder.build().withCapturedOkHttpCallContext()).execute().use { resp ->
                if (resp.isSuccessful) resp.body.bytes()
                else {
                    AppLog.w("ApiClient", "fetchUrlAsBytes non-2xx ${resp.code} for $url")
                    null
                }
            }
        } catch (e: Exception) {
            AppLog.w("ApiClient", "fetchUrlAsBytes failed for $url: ${e.message}")
            null
        }
    }

    fun createOpenAiApi(baseUrl: String): OpenAiApi = getRetrofit(baseUrl, OpenAiApi::class.java.name).create(OpenAiApi::class.java)
    fun createClaudeApi(baseUrl: String): ClaudeApi = getRetrofit(baseUrl, ClaudeApi::class.java.name).create(ClaudeApi::class.java)
    fun createReplicateApi(baseUrl: String): ReplicateApi = getRetrofit(baseUrl, ReplicateApi::class.java.name).create(ReplicateApi::class.java)
    fun createGeminiApi(baseUrl: String): GeminiApi = getRetrofit(baseUrl, GeminiApi::class.java.name).create(GeminiApi::class.java)
    fun createOpenAiCompatibleApi(baseUrl: String): OpenAiCompatibleApi = getRetrofit(baseUrl, OpenAiCompatibleApi::class.java.name).create(OpenAiCompatibleApi::class.java)
    fun createOpenRouterModelsApi(baseUrl: String): OpenRouterModelsApi = getRetrofit(baseUrl, OpenRouterModelsApi::class.java.name).create(OpenRouterModelsApi::class.java)
    fun createCohereNativeApi(): CohereNativeApi = getRetrofit("https://api.cohere.com/", CohereNativeApi::class.java.name).create(CohereNativeApi::class.java)
    fun createCohereRerankApi(): CohereRerankApi = getRetrofit("https://api.cohere.com/", CohereRerankApi::class.java.name).create(CohereRerankApi::class.java)
    fun createMistralModerationApi(): MistralModerationApi = getRetrofit("https://api.mistral.ai/", MistralModerationApi::class.java.name).create(MistralModerationApi::class.java)
    fun createHuggingFaceApi(): HuggingFaceApi = getRetrofit("https://huggingface.co/api/", HuggingFaceApi::class.java.name).create(HuggingFaceApi::class.java)
    fun createCloudPriceApi(): CloudPriceApi = getRetrofit("https://ai.cloudprice.net/", CloudPriceApi::class.java.name).create(CloudPriceApi::class.java)
}
