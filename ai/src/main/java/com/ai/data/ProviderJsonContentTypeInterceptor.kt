package com.ai.data

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/** NVIDIA's strict NIM endpoints reject Gson's JSON charset parameter. */
internal class ProviderJsonContentTypeInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body
        val host = request.url.host
        if (body == null || !(host == "api.nvidia.com" || host.endsWith(".api.nvidia.com")) ||
            body.contentType()?.subtype != "json") return chain.proceed(request)
        val jsonBody = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = body.contentLength()
            override fun isDuplex() = body.isDuplex()
            override fun isOneShot() = body.isOneShot()
            override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
        }
        return chain.proceed(request.newBuilder().method(request.method, jsonBody)
            .header("Content-Type", "application/json").build())
    }
}
