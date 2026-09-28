package com.ai.data

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One export boundary for current, imported and legacy request diagnostics.
 *  Only secrets change — sensitive header lines, bearer tokens, URL
 *  credentials, sensitive query values and sensitive JSON keys. Every other
 *  byte of a string (answers, prompts, bodies) is kept exactly. */
object ReportExportRedaction {
    private const val MASK = "[REDACTED]"
    private val keys = setOf("authorization", "proxyauthorization", "apikey", "xapikey", "xgoogapikey", "token", "accesstoken", "refreshtoken", "secret", "clientsecret", "password", "cookie", "setcookie", "credential", "credentials", "key")
    private fun sensitive(key: String) = key.lowercase().filter { it.isLetterOrDigit() } in keys
    private val headers = Regex("(?im)^((?:authorization|proxy-authorization|x-api-key|api-key|x-goog-api-key|cookie|set-cookie)\\s*:)\\s*[^\\r\\n]*")
    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")
    private val urls = Regex("https?://[^\\s<>\\\"]+")
    private val urlUserInfo = Regex("^(https?://)[^/?#\\s]*@", RegexOption.IGNORE_CASE)
    private fun text(value: String, depth: Int): String {
        if (depth < 8 && (value.trimStart().startsWith('{') || value.trimStart().startsWith('['))) {
            runCatching { JsonParser.parseString(value) }.getOrNull()?.takeIf { it.isJsonObject || it.isJsonArray }?.let { parsed ->
                // Re-serialise only when something was actually masked. A
                // JSON-looking answer / body with nothing sensitive stays
                // byte-identical — re-serialising compacted its formatting
                // and rewrote leniently-parsed text.
                val cleaned = clean(parsed.deepCopy(), depth + 1)
                return if (cleaned == parsed) value else cleaned.toString()
            }
        }
        val masked = headers.replace(value) { "${it.groupValues[1]} $MASK" }
        return urls.replace(bearer.replace(masked, "Bearer $MASK")) { match -> redactUrl(match.value) }
    }

    /** Mask credentials in one URL without re-normalising it. Rebuilding
     *  every URL through HttpUrl added trailing slashes, re-encoded paths
     *  and lower-cased hosts in ordinary answer text; now only the userinfo
     *  and the values of sensitive query parameters change. */
    private fun redactUrl(raw: String): String {
        val url = raw.toHttpUrlOrNull() ?: return raw
        val hasUserInfo = url.username.isNotEmpty() || url.password.isNotEmpty()
        if (!hasUserInfo && url.queryParameterNames.none(::sensitive)) return raw
        var out = if (hasUserInfo) urlUserInfo.replace(raw) { it.groupValues[1] } else raw
        val queryStart = out.indexOf('?')
        if (queryStart >= 0) {
            val queryEnd = out.indexOf('#', queryStart).let { if (it < 0) out.length else it }
            // Decide per raw segment, decoding only the NAME, so a
            // percent-encoded sensitive name can't slip past the mask.
            val query = out.substring(queryStart + 1, queryEnd).split('&').joinToString("&") { part ->
                val eq = part.indexOf('=')
                if (eq < 0) return@joinToString part
                val rawName = part.substring(0, eq)
                val name = runCatching { java.net.URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
                if (sensitive(name)) part.substring(0, eq + 1) + MASK else part
            }
            out = out.substring(0, queryStart + 1) + query + out.substring(queryEnd)
        }
        return out
    }
    private fun clean(value: JsonElement, depth: Int = 0): JsonElement = when {
        value.isJsonObject -> value.asJsonObject.apply { entrySet().toList().forEach { (k,v) ->
            add(k, if (sensitive(k)) JsonPrimitive(MASK) else clean(v,depth))
        } }
        value.isJsonArray -> value.asJsonArray.apply { for (i in 0 until size()) set(i,clean(get(i),depth)) }
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> JsonPrimitive(text(value.asString,depth))
        else -> value
    }
    fun plainText(value: String): String = text(value, 0)
    /** Same "only if masked" rule for a whole document: a file with nothing
     *  to redact (e.g. a hash-named evidence snapshot) is exported verbatim. */
    fun json(json: String): String {
        val parsed = JsonParser.parseString(json)
        val cleaned = clean(parsed.deepCopy())
        return if (cleaned == parsed) json else cleaned.toString()
    }
}
