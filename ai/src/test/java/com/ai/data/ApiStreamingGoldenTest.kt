package com.ai.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/**
 * Golden tests for SSE streaming parse (audit P03/T04, streaming half).
 * Covers the per-family content extractors, Gemini's final-chunk detector, the
 * cross-event usage merge, and parseSseStream end-to-end (OpenAI `data:` + [DONE],
 * Anthropic `event:`/`data:`, Gemini `data:` + finishReason) — the spots where a
 * streaming-parser change could silently truncate or mis-split one provider.
 */
class ApiStreamingGoldenTest {

    private fun sseBody(text: String) = text.toResponseBody("text/event-stream".toMediaType())

    // ---- per-family content extractors ----

    @Test fun openai_chat_extracts_delta_content_and_reasoning_fallback() {
        assertThat(extractOpenAiContent(null, """{"choices":[{"delta":{"content":"hello"}}]}""")).isEqualTo("hello")
        // empty content → null (no spurious empty deltas)
        assertThat(extractOpenAiContent(null, """{"choices":[{"delta":{"content":""}}]}""")).isNull()
        // flat extractor falls back to reasoning_content when content is absent
        assertThat(extractOpenAiContent(null, """{"choices":[{"delta":{"reasoning_content":"thinking"}}]}""")).isEqualTo("thinking")
        assertThat(extractOpenAiContent(null, "not json")).isNull()
    }

    @Test fun openai_stateful_extractor_separates_answer_from_reasoning() {
        val ex = OpenAiContentExtractor()
        // reasoning-only chunks are buffered, not emitted as answer
        assertThat(ex.extract(null, """{"choices":[{"delta":{"reasoning_content":"step1 "}}]}""")).isNull()
        assertThat(ex.sawContent).isFalse()
        // real content is emitted
        assertThat(ex.extract(null, """{"choices":[{"delta":{"content":"answer"}}]}""")).isEqualTo("answer")
        assertThat(ex.sawContent).isTrue()
        // once content was seen, the reasoning fallback stays null
        assertThat(ex.reasoningFallback()).isNull()

        val reasoningOnly = OpenAiContentExtractor()
        reasoningOnly.extract(null, """{"choices":[{"delta":{"reasoning":"only thoughts"}}]}""")
        assertThat(reasoningOnly.reasoningFallback()).isEqualTo("only thoughts")
    }

    @Test fun responses_api_extracts_only_output_text_delta_events() {
        assertThat(extractResponsesApiContent("response.output_text.delta", """{"delta":"hi"}""")).isEqualTo("hi")
        // any other event type contributes no content
        assertThat(extractResponsesApiContent("response.created", """{"delta":"ignored"}""")).isNull()
        assertThat(extractResponsesApiContent(null, """{"delta":"ignored"}""")).isNull()
    }

    @Test fun anthropic_extracts_only_content_block_delta_text() {
        assertThat(extractClaudeContent("content_block_delta", """{"delta":{"text":"claude"}}""")).isEqualTo("claude")
        assertThat(extractClaudeContent("message_stop", "{}")).isNull()
        assertThat(extractClaudeContent("message_start", """{"delta":{"text":"x"}}""")).isNull()
    }

    @Test fun gemini_extracts_and_joins_candidate_parts() {
        assertThat(extractGeminiContent(null, """{"candidates":[{"content":{"parts":[{"text":"a"},{"text":"b"}]}}]}""")).isEqualTo("ab")
        assertThat(extractGeminiContent(null, """{"candidates":[{"content":{"parts":[]}}]}""")).isNull()
    }

    @Test fun gemini_final_chunk_detected_by_finishReason() {
        assertThat(isGeminiFinalChunk(null, """{"candidates":[{"finishReason":"STOP"}]}""")).isTrue()
        assertThat(isGeminiFinalChunk(null, """{"candidates":[{"content":{"parts":[{"text":"x"}]}}]}""")).isFalse()
        assertThat(isGeminiFinalChunk(null, "garbage")).isFalse()
    }

    // ---- usage merge ----

    @Test fun mergeUsage_takes_field_wise_max_and_latest_cost() {
        val a = TokenUsage(inputTokens = 10, outputTokens = 2, apiCost = 0.001, reasoningTokens = 5)
        val b = TokenUsage(inputTokens = 10, outputTokens = 7, apiCost = 0.002, cachedInputTokens = 3)
        val merged = mergeUsage(a, b)
        assertThat(merged.inputTokens).isEqualTo(10)
        assertThat(merged.outputTokens).isEqualTo(7)   // max
        assertThat(merged.cachedInputTokens).isEqualTo(3)
        assertThat(merged.reasoningTokens).isEqualTo(5)
        assertThat(merged.apiCost).isEqualTo(0.002)     // latest non-null
        // null seed merges to the observed event
        assertThat(mergeUsage(null, b)).isEqualTo(b)
    }

    // ---- parseSseStream end-to-end ----

    @Test fun parseSse_openai_chat_accumulates_deltas_until_done() {
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n" +
            "data: [DONE]\n\n"
        val deltas = runBlocking {
            parseSseStream(sseBody(sse), ::extractOpenAiContent).toList()
        }
        assertThat(deltas).containsExactly("Hel", "lo").inOrder()
    }

    @Test fun parseSse_anthropic_uses_event_types() {
        val sse = "event: content_block_delta\ndata: {\"delta\":{\"text\":\"Hel\"}}\n\n" +
            "event: content_block_delta\ndata: {\"delta\":{\"text\":\"lo\"}}\n\n" +
            "event: message_stop\ndata: {}\n\n"
        val deltas = runBlocking {
            parseSseStream(sseBody(sse), ::extractClaudeContent).toList()
        }
        assertThat(deltas).containsExactly("Hel", "lo").inOrder()
    }

    @Test fun parseSse_gemini_accumulates_and_sees_final_chunk() {
        val sse = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hel\"}]}}]}\n\n" +
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"lo\"}]},\"finishReason\":\"STOP\"}]}\n\n"
        val deltas = runBlocking {
            parseSseStream(sseBody(sse), ::extractGeminiContent, isFinalChunk = ::isGeminiFinalChunk).toList()
        }
        assertThat(deltas).containsExactly("Hel", "lo").inOrder()
    }
}
