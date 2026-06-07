package com.ai.data

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ApiMockWebServerTest {
    private lateinit var server: MockWebServer
    private lateinit var savedNetworkSettings: SavedNetworkSettings

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        savedNetworkSettings = SavedNetworkSettings.capture()
        NetworkSettings.maxRetriesOn429 = 0
        NetworkSettings.maxRetriesOn529 = 0
        NetworkSettings.retryBackoffMs429 = 1L
        NetworkSettings.retryBackoffMs529 = 1L
        NetworkSettings.maxCallsPerProviderPerMinute = 1_000
    }

    @After
    fun tearDown() {
        savedNetworkSettings.restore()
        server.close()
    }

    @Test
    fun openAiCompatibleChat_postsExpectedRequestAndParsesResponse() = runBlocking {
        server.enqueue(jsonResponse(
            """
            {
              "id": "chat-1",
              "choices": [
                { "index": 0, "message": { "role": "assistant", "content": "chat ok" } }
              ],
              "usage": { "prompt_tokens": 3, "completion_tokens": 2, "total_tokens": 5 }
            }
            """.trimIndent()
        ))

        val response = AnalysisRepository().sendChat(
            service = openAiService(),
            apiKey = "test-key",
            model = "mock-chat",
            messages = listOf(ChatMessage("user", "hello")),
            params = ChatParameters(maxTokens = 16)
        )

        assertThat(response).isEqualTo("chat ok")
        val request = takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/v1/chat/completions")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer test-key")

        val body = request.jsonBody()
        assertThat(body["model"].asString).isEqualTo("mock-chat")
        assertThat(body["max_tokens"].asInt).isEqualTo(16)
        assertThat(body["messages"].asJsonArray[0].asJsonObject["content"].asString)
            .isEqualTo("hello")
    }

    @Test
    fun openAiCompatibleResponsesApi_postsResponsesEndpointAndParsesOutputText() = runBlocking {
        server.enqueue(jsonResponse(
            """
            {
              "id": "resp-1",
              "status": "completed",
              "output": [
                {
                  "type": "message",
                  "id": "msg-1",
                  "status": "completed",
                  "role": "assistant",
                  "content": [
                    { "type": "output_text", "text": "responses ok" }
                  ]
                }
              ],
              "usage": { "prompt_tokens": 4, "completion_tokens": 2, "total_tokens": 6 }
            }
            """.trimIndent()
        ))

        val response = AnalysisRepository().analyze(
            service = openAiService(),
            apiKey = "test-key",
            prompt = "summarize",
            model = "gpt-5-mini",
            params = AgentParameters(systemPrompt = "be concise")
        )

        assertThat(response.analysis).isEqualTo("responses ok")
        assertThat(response.error).isNull()
        assertThat(response.httpStatusCode).isEqualTo(200)

        val request = takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/v1/responses")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer test-key")

        val body = request.jsonBody()
        assertThat(body["model"].asString).isEqualTo("gpt-5-mini")
        assertThat(body["input"].asString).isEqualTo("summarize")
        assertThat(body["instructions"].asString).isEqualTo("be concise")
    }

    @Test
    fun openAiCompatibleEmbeddings_postsEmbeddingRequestAndSortsVectorsByIndex() = runBlocking {
        server.enqueue(jsonResponse(
            """
            {
              "data": [
                { "index": 1, "embedding": [0.3, 0.4] },
                { "index": 0, "embedding": [0.1, 0.2] }
              ],
              "usage": { "prompt_tokens": 3, "completion_tokens": 0, "total_tokens": 3 }
            }
            """.trimIndent()
        ))

        val result = AnalysisRepository().embedWithStatus(
            service = openAiService(),
            apiKey = "test-key",
            model = "mock-embed",
            texts = listOf("first", "second")
        )

        assertThat(result.errorMessage).isNull()
        assertThat(result.httpStatusCode).isEqualTo(200)
        assertThat(result.vectors).containsExactly(
            listOf(0.1, 0.2),
            listOf(0.3, 0.4)
        ).inOrder()
        assertThat(result.tokenUsage?.inputTokens).isEqualTo(3)

        val request = takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/v1/embeddings")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer test-key")

        val body = request.jsonBody()
        assertThat(body["model"].asString).isEqualTo("mock-embed")
        assertThat(body["input"].asJsonArray.map { it.asString }).containsExactly("first", "second").inOrder()
        assertThat(body["encoding_format"].asString).isEqualTo("float")
    }

    @Test
    fun openAiCompatibleModelList_usesModelsEndpointAndDropsInactiveModels() = runBlocking {
        val responseBody = """
            {
              "data": [
                { "id": "z-inactive", "active": false },
                { "id": "b-embed", "context_length": 2048 },
                { "id": "a-chat", "context_length": 8192, "supports_image_input": true }
              ]
            }
        """.trimIndent()
        server.enqueue(jsonResponse(responseBody))
        server.enqueue(jsonResponse(responseBody))

        val models = AnalysisRepository().fetchModelsWithKinds(openAiService(), "test-key")

        assertThat(models.ids).containsExactly("a-chat", "b-embed").inOrder()
        assertThat(models.types["a-chat"]).isEqualTo(ModelType.CHAT)
        assertThat(models.types["b-embed"]).isEqualTo(ModelType.EMBEDDING)
        assertThat(models.visionModels).containsExactly("a-chat")
        assertThat(models.capabilities["a-chat"]?.contextLength).isEqualTo(8192)
        assertThat(models.rawResponse).isEqualTo(responseBody)

        val rawRequest = takeRequest()
        val typedRequest = takeRequest()
        assertThat(rawRequest.method).isEqualTo("GET")
        assertThat(typedRequest.method).isEqualTo("GET")
        assertThat(rawRequest.url.encodedPath).isEqualTo("/v1/models")
        assertThat(typedRequest.url.encodedPath).isEqualTo("/v1/models")
        assertThat(rawRequest.headers["Authorization"]).isEqualTo("Bearer test-key")
        assertThat(typedRequest.headers["Authorization"]).isEqualTo("Bearer test-key")
    }

    @Test
    fun openAiCompatibleChat_surfacesHttpErrorBodyWhenRetryDisabled() = runBlocking {
        server.enqueue(jsonResponse("""{"error":{"message":"bad request","type":"invalid_request"}}""", code = 400))

        val thrown = runCatching {
            AnalysisRepository().sendChat(
                service = openAiService(),
                apiKey = "test-key",
                model = "mock-chat",
                messages = listOf(ChatMessage("user", "bad?")),
                params = ChatParameters(maxTokens = 16)
            )
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(Exception::class.java)
        assertThat(thrown?.message).contains("API error: 400")
        assertThat(thrown?.message).contains("bad request")
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(takeRequest().url.encodedPath).isEqualTo("/v1/chat/completions")
    }

    @Test
    fun anthropicAnalyze_usesMessagesEndpointHeadersAndMaxTokens() = runBlocking {
        server.enqueue(jsonResponse(
            """
            {
              "id": "msg-1",
              "content": [
                { "type": "text", "text": "claude ok" }
              ],
              "usage": { "input_tokens": 5, "output_tokens": 3 }
            }
            """.trimIndent()
        ))

        val response = AnalysisRepository().analyze(
            service = anthropicService(),
            apiKey = "anthropic-key",
            prompt = "hello",
            model = "claude-test",
            params = AgentParameters(temperature = 0.2f)
        )

        assertThat(response.analysis).isEqualTo("claude ok")
        assertThat(response.error).isNull()

        val request = takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/v1/messages")
        assertThat(request.headers["x-api-key"]).isEqualTo("anthropic-key")
        assertThat(request.headers["anthropic-version"]).isEqualTo("2023-06-01")

        val body = request.jsonBody()
        assertThat(body["model"].asString).isEqualTo("claude-test")
        assertThat(body["max_tokens"].asInt).isEqualTo(4096)
        assertThat(body["temperature"].asFloat).isEqualTo(0.2f)
    }

    @Test
    fun geminiAnalyze_usesModelPathQueryKeyAndGenerationConfig() = runBlocking {
        server.enqueue(jsonResponse(
            """
            {
              "candidates": [
                {
                  "content": {
                    "role": "model",
                    "parts": [
                      { "text": "gemini ok" }
                    ]
                  }
                }
              ],
              "usageMetadata": {
                "promptTokenCount": 5,
                "candidatesTokenCount": 2,
                "totalTokenCount": 7
              }
            }
            """.trimIndent()
        ))

        val response = AnalysisRepository().analyze(
            service = geminiService(),
            apiKey = "gemini-key",
            prompt = "hello",
            model = "gemini-test",
            params = AgentParameters(maxTokens = 32, frequencyPenalty = 0.3f, presencePenalty = 0.4f)
        )

        assertThat(response.analysis).isEqualTo("gemini ok")
        assertThat(response.error).isNull()

        val request = takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/v1beta/models/gemini-test:generateContent")
        assertThat(request.url.queryParameter("key")).isEqualTo("gemini-key")
        assertThat(request.headers["Authorization"]).isNull()

        val generationConfig = request.jsonBody()["generationConfig"].asJsonObject
        assertThat(generationConfig["maxOutputTokens"].asInt).isEqualTo(32)
        assertThat(generationConfig["frequencyPenalty"].asFloat).isEqualTo(0.3f)
        assertThat(generationConfig["presencePenalty"].asFloat).isEqualTo(0.4f)
    }

    private fun openAiService(): AppService = AppService(
        id = "MockOpenAI",
        baseUrl = server.url("/").toString(),
        adminUrl = "",
        defaultModel = "mock-chat"
    )

    private fun anthropicService(): AppService = AppService(
        id = "MockAnthropic",
        baseUrl = server.url("/").toString(),
        adminUrl = "",
        defaultModel = "claude-test",
        apiFormat = ApiFormat.ANTHROPIC
    )

    private fun geminiService(): AppService = AppService(
        id = "MockGemini",
        baseUrl = server.url("/").toString(),
        adminUrl = "",
        defaultModel = "gemini-test",
        apiFormat = ApiFormat.GOOGLE
    )

    private fun jsonResponse(body: String, code: Int = 200): MockResponse =
        MockResponse(
            code = code,
            headers = headersOf("Content-Type", "application/json"),
            body = body
        )

    private fun takeRequest() =
        checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "Timed out waiting for request" }

    private fun mockwebserver3.RecordedRequest.jsonBody(): com.google.gson.JsonObject =
        JsonParser.parseString(checkNotNull(body).utf8()).asJsonObject

    private operator fun JsonElement.get(memberName: String): JsonElement =
        asJsonObject.get(memberName)

    private data class SavedNetworkSettings(
        val maxRetriesOn429: Int,
        val retryBackoffMs429: Long,
        val maxRetriesOn529: Int,
        val retryBackoffMs529: Long,
        val maxCallsPerProviderPerMinute: Int
    ) {
        fun restore() {
            NetworkSettings.maxRetriesOn429 = maxRetriesOn429
            NetworkSettings.retryBackoffMs429 = retryBackoffMs429
            NetworkSettings.maxRetriesOn529 = maxRetriesOn529
            NetworkSettings.retryBackoffMs529 = retryBackoffMs529
            NetworkSettings.maxCallsPerProviderPerMinute = maxCallsPerProviderPerMinute
        }

        companion object {
            fun capture(): SavedNetworkSettings = SavedNetworkSettings(
                maxRetriesOn429 = NetworkSettings.maxRetriesOn429,
                retryBackoffMs429 = NetworkSettings.retryBackoffMs429,
                maxRetriesOn529 = NetworkSettings.maxRetriesOn529,
                retryBackoffMs529 = NetworkSettings.retryBackoffMs529,
                maxCallsPerProviderPerMinute = NetworkSettings.maxCallsPerProviderPerMinute
            )
        }
    }
}
