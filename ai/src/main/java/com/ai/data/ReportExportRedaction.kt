package com.ai.data

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One export boundary for current, imported and legacy request diagnostics. */
object ReportExportRedaction {
    private const val MASK = "[REDACTED]"
    private val keys = setOf("authorization", "proxyauthorization", "apikey", "xapikey", "xgoogapikey", "token", "accesstoken", "refreshtoken", "secret", "clientsecret", "password", "cookie", "setcookie", "credential", "credentials", "key")
    private fun sensitive(key: String) = key.lowercase().filter { it.isLetterOrDigit() } in keys
    private val headers = Regex("(?im)^((?:authorization|proxy-authorization|x-api-key|api-key|x-goog-api-key|cookie|set-cookie)\\s*:)\\s*[^\\r\\n]*")
    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")
    private val urls = Regex("https?://[^\\s<>\\\"]+")
    private fun text(value: String, depth: Int): String {
        if (depth < 8 && (value.trimStart().startsWith('{') || value.trimStart().startsWith('['))) {
            runCatching { JsonParser.parseString(value) }.getOrNull()?.takeIf { it.isJsonObject || it.isJsonArray }?.let {
                return clean(it, depth + 1).toString()
            }
        }
        val masked = headers.replace(value) { "${it.groupValues[1]} $MASK" }
        return urls.replace(bearer.replace(masked, "Bearer $MASK")) { match ->
            val url = match.value.toHttpUrlOrNull() ?: return@replace match.value
            val builder = url.newBuilder().username("").password("")
            url.queryParameterNames.filter(::sensitive).forEach { builder.setQueryParameter(it, MASK) }
            builder.build().toString()
        }
    }
    private fun clean(value: JsonElement, depth: Int = 0): JsonElement = when {
        value.isJsonObject -> value.asJsonObject.apply { entrySet().toList().forEach { (k,v) ->
            add(k, if (sensitive(k)) JsonPrimitive(MASK) else clean(v,depth))
        } }
        value.isJsonArray -> value.asJsonArray.apply { for (i in 0 until size()) set(i,clean(get(i),depth)) }
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> JsonPrimitive(text(value.asString,depth))
        else -> value
    }
    fun json(json: String): String = clean(JsonParser.parseString(json)).toString()
}
