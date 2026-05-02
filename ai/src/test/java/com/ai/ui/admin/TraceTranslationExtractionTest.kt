package com.ai.ui.admin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Coverage for the JSON extraction helpers behind the trace detail
 * screen's "Translation result" button — they walk the request +
 * response bodies of a translation trace and pull out (original
 * text, translated text). Three API formats to handle (OpenAI /
 * Anthropic / Gemini), each with its own message shape.
 */
class TraceTranslationExtractionTest {

    private val translatePromptOpenAi = """
        {
          "model": "gpt-5",
          "messages": [
            {"role": "system", "content": "You are a translator."},
            {"role": "user", "content": "Translate the following text to Dutch.\n\nTEXT TO TRANSLATE:\nHello world!"}
          ]
        }
    """.trimIndent()

    private val translateResponseOpenAi = """
        {
          "id": "chatcmpl-1",
          "choices": [
            {"index": 0, "message": {"role": "assistant", "content": "Hallo wereld!"}, "finish_reason": "stop"}
          ]
        }
    """.trimIndent()

    private val translatePromptAnthropic = """
        {
          "model": "claude-haiku-4-5",
          "messages": [
            {"role": "user", "content": [{"type": "text", "text": "Translate the following text to Dutch.\n\nTEXT TO TRANSLATE:\nGood morning."}]}
          ]
        }
    """.trimIndent()

    private val translateResponseAnthropic = """
        {
          "id": "msg_01",
          "type": "message",
          "role": "assistant",
          "content": [{"type": "text", "text": "Goedemorgen."}]
        }
    """.trimIndent()

    private val translatePromptGemini = """
        {
          "contents": [
            {"role": "user", "parts": [{"text": "Translate the following text to Dutch.\n\nTEXT TO TRANSLATE:\nWelcome home."}]}
          ]
        }
    """.trimIndent()

    private val translateResponseGemini = """
        {
          "candidates": [
            {"content": {"role": "model", "parts": [{"text": "Welkom thuis."}]}}
          ]
        }
    """.trimIndent()

    @Test fun extracts_openai_request_response_pair() {
        val parts = extractTranslationParts(translatePromptOpenAi, translateResponseOpenAi)
        assertThat(parts).isNotNull()
        assertThat(parts!!.first).isEqualTo("Hello world!")
        assertThat(parts.second).isEqualTo("Hallo wereld!")
    }

    @Test fun extracts_anthropic_request_response_pair() {
        val parts = extractTranslationParts(translatePromptAnthropic, translateResponseAnthropic)
        assertThat(parts).isNotNull()
        assertThat(parts!!.first).isEqualTo("Good morning.")
        assertThat(parts.second).isEqualTo("Goedemorgen.")
    }

    @Test fun extracts_gemini_request_response_pair() {
        val parts = extractTranslationParts(translatePromptGemini, translateResponseGemini)
        assertThat(parts).isNotNull()
        assertThat(parts!!.first).isEqualTo("Welcome home.")
        assertThat(parts.second).isEqualTo("Welkom thuis.")
    }

    @Test fun missing_marker_falls_back_to_whole_user_prompt() {
        // No "TEXT TO TRANSLATE:" — the helper returns the trimmed
        // whole prompt rather than null.
        val req = """{"messages":[{"role":"user","content":"Just translate: Bonjour"}]}"""
        val rsp = """{"choices":[{"message":{"content":"Hallo"}}]}"""

        val parts = extractTranslationParts(req, rsp)
        assertThat(parts).isNotNull()
        assertThat(parts!!.first).isEqualTo("Just translate: Bonjour")
        assertThat(parts.second).isEqualTo("Hallo")
    }

    @Test fun returns_null_when_request_body_blank() {
        val parts = extractTranslationParts("", translateResponseOpenAi)
        assertThat(parts).isNull()
    }

    @Test fun returns_null_when_response_body_blank() {
        val parts = extractTranslationParts(translatePromptOpenAi, "")
        assertThat(parts).isNull()
    }

    @Test fun returns_null_when_response_has_no_assistant_text() {
        val rsp = """{"choices":[{"message":{"content":""}}]}"""
        val parts = extractTranslationParts(translatePromptOpenAi, rsp)
        // Empty assistant content trims to blank → fall through to null.
        assertThat(parts).isNull()
    }

    @Test fun returns_null_on_unparseable_request_body() {
        val parts = extractTranslationParts("not-valid-json", translateResponseOpenAi)
        assertThat(parts).isNull()
    }

    @Test fun returns_null_on_unparseable_response_body() {
        val parts = extractTranslationParts(translatePromptOpenAi, "{not valid")
        assertThat(parts).isNull()
    }

    @Test fun extractUserPrompt_picks_last_user_in_multi_turn() {
        // Multi-turn conversation: only the last user message matters
        // for the translate prompt (the earlier ones are unrelated).
        val multi = """
            {"messages":[
              {"role":"system","content":"sys"},
              {"role":"user","content":"first user"},
              {"role":"assistant","content":"reply"},
              {"role":"user","content":"final translate request"}
            ]}
        """.trimIndent()
        assertThat(extractUserPrompt(multi)).isEqualTo("final translate request")
    }

    @Test fun extractAssistantContent_falls_through_format_chain() {
        // None of the three shapes match — should return null.
        val obj = """{"unrelated":"shape"}"""
        assertThat(extractAssistantContent(obj)).isNull()
    }

    @Test fun extractAssistantContent_skips_blank_content_in_anthropic_array() {
        // Anthropic content arrays sometimes have a leading
        // "thinking" block with no text — the helper should skip
        // and return the next non-blank text.
        val rsp = """
            {"content":[
              {"type":"thinking","text":""},
              {"type":"text","text":"actual reply"}
            ]}
        """.trimIndent()
        assertThat(extractAssistantContent(rsp)).isEqualTo("actual reply")
    }
}
