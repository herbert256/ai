package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ApiDispatchHelpersTest {
    @Test fun buildOpenAiRequest_maps_seed_and_provider_specific_flags() {
        val service = AppService(
            id = "UNIT_OPENAI_REQUEST",
            displayName = "Unit OpenAI Request",
            baseUrl = "https://unit.example.com/",
            adminUrl = "",
            defaultModel = "model",
            seedFieldName = "random_seed",
            supportsCitations = true,
            supportsSearchRecency = true
        )

        val request = buildOpenAiRequest(
            service = service,
            model = "unknown-json-model",
            messages = listOf(OpenAiMessage("user", "hello")),
            params = AgentParameters(
                maxTokens = 100,
                seed = 42,
                responseFormatJson = true,
                returnCitations = false,
                searchRecency = "month",
                searchEnabled = true,
                stopSequences = listOf("END")
            ),
            stream = true
        )

        assertThat(request.stream).isTrue()
        assertThat(request.max_tokens).isEqualTo(100)
        assertThat(request.seed).isNull()
        assertThat(request.random_seed).isEqualTo(42)
        assertThat(request.response_format?.type).isEqualTo("json_object")
        assertThat(request.return_citations).isFalse()
        assertThat(request.search_recency_filter).isEqualTo("month")
        assertThat(request.search).isTrue()
        assertThat(request.stop).containsExactly("END")
    }

    @Test fun chat_messages_convert_vision_payloads_per_provider() {
        val message = ChatMessage("user", "describe", imageBase64 = "abc123", imageMime = "image/jpeg")

        val openAi = message.toOpenAiMessage()
        val openAiParts = openAi.content as List<*>
        assertThat(openAiParts).hasSize(2)
        assertThat(openAiParts[0].toString()).contains("describe")
        assertThat(openAiParts[1].toString()).contains("data:image/jpeg;base64,abc123")

        val claude = message.toClaudeMessage()
        val claudeBlocks = claude.content as List<*>
        assertThat(claudeBlocks[0].toString()).contains("media_type=image/jpeg")
        assertThat(claudeBlocks[0].toString()).contains("data=abc123")

        val gemini = message.toGeminiContent()
        assertThat(gemini.role).isEqualTo("user")
        assertThat(gemini.parts[0].inlineData?.mimeType).isEqualTo("image/jpeg")
        assertThat(gemini.parts[0].inlineData?.data).isEqualTo("abc123")
        assertThat(gemini.parts[1].text).isEqualTo("describe")
    }

    @Test fun responses_content_extraction_falls_back_across_output_shapes() {
        val response = OpenAiResponsesApiResponse(
            id = "r1",
            status = "completed",
            error = null,
            output = listOf(
                OpenAiResponsesOutputMessage(
                    type = "web_search_call",
                    id = "search",
                    status = "completed",
                    role = null,
                    content = null
                ),
                OpenAiResponsesOutputMessage(
                    type = "message",
                    id = "msg",
                    status = "completed",
                    role = "assistant",
                    content = listOf(OpenAiResponsesOutputContent(type = "text", text = "final answer"))
                )
            ),
            usage = null
        )

        assertThat(extractResponsesApiContent(response)).isEqualTo("final answer")
    }

    @Test fun web_search_extractors_deduplicate_urls_and_queries() {
        val responses = OpenAiResponsesApiResponse(
            id = "r1",
            status = "completed",
            error = null,
            output = listOf(
                OpenAiResponsesOutputMessage(
                    type = "web_search_call",
                    id = "s1",
                    status = "completed",
                    role = null,
                    content = null,
                    action = OpenAiResponsesAction(query = "latest models")
                ),
                OpenAiResponsesOutputMessage(
                    type = "web_search_call",
                    id = "s2",
                    status = "completed",
                    role = null,
                    content = null,
                    action = OpenAiResponsesAction(query = "latest models")
                ),
                OpenAiResponsesOutputMessage(
                    type = "message",
                    id = "m1",
                    status = "completed",
                    role = "assistant",
                    content = listOf(
                        OpenAiResponsesOutputContent(
                            type = "output_text",
                            text = "answer",
                            annotations = listOf(
                                OpenAiResponsesAnnotation(type = "url_citation", url = "https://example.com/a", title = "A"),
                                OpenAiResponsesAnnotation(type = "url_citation", url = "https://example.com/a", title = "A again")
                            )
                        )
                    )
                )
            ),
            usage = null
        )

        val extracted = extractResponsesWebSearch(responses)

        assertThat(extracted.queries).containsExactly("latest models")
        assertThat(extracted.citations).containsExactly("https://example.com/a")
        assertThat(extracted.searchResults!!.map { it.url }).containsExactly("https://example.com/a")
    }

    @Test fun reasoning_and_tool_helpers_emit_expected_wire_shapes() {
        val service = AppService(
            id = "UNIT_REASONING",
            displayName = "Unit Reasoning",
            baseUrl = "https://unit.example.com/",
            adminUrl = "",
            defaultModel = "model"
        )

        assertThat(reasoningField(service, "unknown-model", "high")).containsEntry("effort", "high")
        assertThat(reasoningField(service, "unknown-model", "")).isNull()
        assertThat(anthropicThinkingField(service, "unknown-model", "medium"))
            .containsExactly("type", "enabled", "budget_tokens", 4096)
        assertThat(geminiThinkingConfigField(service, "unknown-model", "low"))
            .containsExactly("thinkingBudget", 1024)
        assertThat(responsesWebSearchTool().first().toString()).contains("web_search_preview")
        assertThat(anthropicWebSearchTool().first().toString()).contains("web_search_20250305")
        assertThat(geminiWebSearchTool().first().toString()).contains("google_search")
        assertThat(openAiChatWebSearchTool()).isNull()
    }
}
