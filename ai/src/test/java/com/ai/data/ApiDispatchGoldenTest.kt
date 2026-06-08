package com.ai.data

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Golden request-shape tests for the provider dispatch builders (audit
 * P03/T04/T11) — the nuanced paths the existing ApiMockWebServerTest doesn't
 * cover: per-family system-prompt PLACEMENT and vision image blocks. These are
 * exactly the spots where a refactor of `analyze*` could silently break one
 * provider format while the others keep working.
 *
 * Same MockWebServer harness as ApiMockWebServerTest: drive a real request
 * through the dispatch and assert the bytes that actually went on the wire.
 */
class ApiDispatchGoldenTest {
    private lateinit var server: MockWebServer
    private lateinit var saved: SavedNetworkSettings

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        saved = SavedNetworkSettings.capture()
        NetworkSettings.maxRetriesOn429 = 0
        NetworkSettings.maxRetriesOn529 = 0
        NetworkSettings.retryBackoffMs429 = 1L
        NetworkSettings.retryBackoffMs529 = 1L
        NetworkSettings.maxCallsPerProviderPerMinute = 1_000
    }

    @After fun tearDown() {
        saved.restore()
        server.close()
    }

    @Test fun anthropic_putsSystemPromptInTopLevelSystemField_notInMessages() = runBlocking {
        server.enqueue(jsonResponse("""{"content":[{"type":"text","text":"ok"}],"usage":{"input_tokens":1,"output_tokens":1}}"""))

        AnalysisRepository().analyze(
            service = anthropicService(), apiKey = "k", prompt = "user question", model = "claude-test",
            params = AgentParameters(systemPrompt = "be terse"),
        )

        val body = takeRequest().jsonBody()
        // Anthropic carries the system prompt as a TOP-LEVEL `system` field, not
        // as a role=system message.
        assertThat(body["system"].asString).isEqualTo("be terse")
        val messages = body["messages"].asJsonArray
        assertThat(messages).hasSize(1)
        assertThat(messages[0].asJsonObject["role"].asString).isEqualTo("user")
        // The system text must not have leaked into the message list.
        assertThat(messages.toString()).doesNotContain("be terse")
    }

    @Test fun gemini_putsSystemPromptInSystemInstruction() = runBlocking {
        server.enqueue(jsonResponse("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""))

        AnalysisRepository().analyze(
            service = geminiService(), apiKey = "k", prompt = "user question", model = "gemini-test",
            params = AgentParameters(systemPrompt = "be terse"),
        )

        val body = takeRequest().jsonBody()
        val sysText = body["systemInstruction"].asJsonObject["parts"].asJsonArray[0]
            .asJsonObject["text"].asString
        assertThat(sysText).isEqualTo("be terse")
        // The prompt rides in `contents`, separate from systemInstruction.
        assertThat(body.has("contents")).isTrue()
    }

    @Test fun openAiChat_prependsSystemRoleMessageBeforeUser() = runBlocking {
        server.enqueue(jsonResponse("""{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""))

        // "mock-chat" is not a Responses-API model → Chat Completions path.
        AnalysisRepository().analyze(
            service = openAiService(), apiKey = "k", prompt = "user question", model = "mock-chat",
            params = AgentParameters(systemPrompt = "be terse"),
        )

        val request = takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/v1/chat/completions")
        val messages = request.jsonBody()["messages"].asJsonArray
        assertThat(messages).hasSize(2)
        assertThat(messages[0].asJsonObject["role"].asString).isEqualTo("system")
        assertThat(messages[0].asJsonObject["content"].asString).isEqualTo("be terse")
        assertThat(messages[1].asJsonObject["role"].asString).isEqualTo("user")
    }

    @Test fun openAiResponses_image_buildsInputImageDataUrlAndInstructions() = runBlocking {
        server.enqueue(jsonResponse("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}"""))

        // "gpt-5-mini" routes to the Responses API; an image becomes a typed
        // input array (input_text + input_image as a data: URL).
        AnalysisRepository().analyze(
            service = openAiService(), apiKey = "k", prompt = "describe", model = "gpt-5-mini",
            params = AgentParameters(systemPrompt = "be terse"),
            imageBase64 = "QUJD", imageMime = "image/png",
        )

        val request = takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/v1/responses")
        val body = request.jsonBody()
        assertThat(body["instructions"].asString).isEqualTo("be terse")
        val content = body["input"].asJsonArray[0].asJsonObject["content"].asJsonArray
            .map { it.asJsonObject }
        val textPart = content.single { it["type"].asString == "input_text" }
        val imagePart = content.single { it["type"].asString == "input_image" }
        assertThat(textPart["text"].asString).isEqualTo("describe")
        assertThat(imagePart["image_url"].asString).isEqualTo("data:image/png;base64,QUJD")
    }

    // ---- harness (mirrors ApiMockWebServerTest) ----

    private fun openAiService() = AppService(
        id = "MockOpenAIGolden", baseUrl = server.url("/").toString(), adminUrl = "", defaultModel = "mock-chat",
    )

    private fun anthropicService() = AppService(
        id = "MockAnthropicGolden", baseUrl = server.url("/").toString(), adminUrl = "",
        defaultModel = "claude-test", apiFormat = ApiFormat.ANTHROPIC,
    )

    private fun geminiService() = AppService(
        id = "MockGeminiGolden", baseUrl = server.url("/").toString(), adminUrl = "",
        defaultModel = "gemini-test", apiFormat = ApiFormat.GOOGLE,
    )

    private fun jsonResponse(body: String, code: Int = 200): MockResponse =
        MockResponse(code = code, headers = headersOf("Content-Type", "application/json"), body = body)

    private fun takeRequest() =
        checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "Timed out waiting for request" }

    private fun mockwebserver3.RecordedRequest.jsonBody(): JsonObject =
        JsonParser.parseString(checkNotNull(body).utf8()).asJsonObject

    private operator fun com.google.gson.JsonElement.get(member: String): com.google.gson.JsonElement =
        asJsonObject.get(member)

    private data class SavedNetworkSettings(
        val r429: Int, val b429: Long, val r529: Int, val b529: Long, val perMin: Int,
    ) {
        fun restore() {
            NetworkSettings.maxRetriesOn429 = r429
            NetworkSettings.retryBackoffMs429 = b429
            NetworkSettings.maxRetriesOn529 = r529
            NetworkSettings.retryBackoffMs529 = b529
            NetworkSettings.maxCallsPerProviderPerMinute = perMin
        }
        companion object {
            fun capture() = SavedNetworkSettings(
                NetworkSettings.maxRetriesOn429, NetworkSettings.retryBackoffMs429,
                NetworkSettings.maxRetriesOn529, NetworkSettings.retryBackoffMs529,
                NetworkSettings.maxCallsPerProviderPerMinute,
            )
        }
    }
}
