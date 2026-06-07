package com.ai.data

import com.google.common.truth.Truth.assertThat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Test

/** [modelForRequest] — pulls the target model out of an outbound request so a
 *  429/bench can name the right model (OpenAI-style JSON body vs Gemini path). */
class RateLimitModelForRequestTest {
    private val json = "application/json".toMediaType()

    private fun post(url: String, body: String) =
        Request.Builder().url(url).post(body.toRequestBody(json)).build()

    @Test fun reads_model_from_json_body() {
        val req = post("https://api.openai.com/v1/chat/completions", "{\"model\":\"gpt-4o\",\"messages\":[]}")
        assertThat(modelForRequest(req)).isEqualTo("gpt-4o")
    }

    @Test fun gemini_model_comes_from_the_url_path() {
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=x")
            .post("{}".toRequestBody(json))
            .build()
        assertThat(modelForRequest(req)).isEqualTo("gemini-2.5-flash")
    }

    @Test fun null_when_request_has_no_body() {
        val req = Request.Builder().url("https://api.openai.com/v1/models").get().build()
        assertThat(modelForRequest(req)).isNull()
    }

    @Test fun null_when_body_has_no_model_field() {
        val req = post("https://api.openai.com/v1/chat/completions", "{\"messages\":[]}")
        assertThat(modelForRequest(req)).isNull()
    }

    @Test fun null_when_body_is_not_json() {
        val req = post("https://api.openai.com/v1/chat/completions", "not json at all")
        assertThat(modelForRequest(req)).isNull()
    }

    @Test fun blank_model_treated_as_null() {
        val req = post("https://api.openai.com/v1/chat/completions", "{\"model\":\"\"}")
        assertThat(modelForRequest(req)).isNull()
    }
}
