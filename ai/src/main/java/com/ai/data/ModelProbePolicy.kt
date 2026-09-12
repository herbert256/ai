package com.ai.data

/** Health checks establish reachability, not report completeness or quality. */
object ModelProbePolicy {
    const val VERSION = 4
    const val TIMEOUT_MS = 60_000L

    fun reachable(response: AnalysisResponse): Boolean = response.isSuccess ||
        (response.httpStatusCode in 200..299 &&
            (!response.analysis.isNullOrBlank() ||
                (response.tokenUsage?.outputTokens ?: 0) > 0 ||
                (response.tokenUsage?.reasoningTokens ?: 0) > 0))

    fun usesCompletionTokenLimit(service: AppService, model: String): Boolean {
        val name = model.substringAfterLast('/').lowercase()
        return (service.id == "OpenAI" || service.id == "GMI-Cloud") &&
            (name.startsWith("gpt-5") || name.startsWith("gpt-6") ||
                Regex("o[134](?:-|$)").containsMatchIn(name))
    }

    /** Check before spending: these require a modality/API/budget this probe cannot supply. */
    fun unsupportedReason(service: AppService, model: String, type: String?): String? {
        val name = model.substringAfterLast('/').lowercase()
        return when {
            service.id == "OpenRouter" && name.endsWith(":batch") ->
                "This model requires the Batch API; no synchronous health probe is available."
            name.startsWith("relace-apply-") ->
                "This code-editing model requires code and update inputs; no code-editing health probe is available."
            service.id == "Google" && ("omni" in name || name.startsWith("deep-research-") || name.startsWith("antigravity-")) ->
                "This model requires the Interactions API; this app's health probe does not support it."
            service.id == "Cohere" && "embed" in name && "image" in name ->
                "This model requires image embedding input; this probe supplies text."
            service.id == "Cohere" && name.startsWith("parse-") ->
                "This model requires the document parsing API; no document probe is available."
            service.id == "Groq" && model.startsWith("groq/compound") ->
                "Compound can exceed the requested token budget; a bounded health probe is unavailable."
            type in ModelType.NON_TESTABLE_TYPES -> "No health probe is available for model type $type."
            type == ModelType.RERANK && service.nativeRerankUrl.isNullOrBlank() ->
                "No native rerank endpoint is configured for ${service.id}."
            type == ModelType.MODERATION && service.nativeModerationUrl.isNullOrBlank() ->
                "No native moderation endpoint is configured for ${service.id}."
            else -> null
        }
    }

    /** Explicit API-contract errors are integration failures, never evidence of bad model health. */
    fun isUnsupportedError(error: String?): Boolean {
        val e = error?.lowercase() ?: return false
        return listOf("not a language model", "not a chat model", "does not support text embeddings", "not compatible with this api",
            "only supports the interactions", "only supported in the interactions", "interactions api",
            "rerank api not wired", "moderation api not wired", "does not support the responses api",
            "not supported in the v1/responses", "unsupported parameter", "the requested operation is unsupported", "not supported with the responses api",
            "multi agent requests are not allowed", "input_type", "api error: 415", "not supported on this endpoint",
            "multi agent requests are not supported", "unsupported media type", "only available through the batch api",
            "isn't supported on this route", "expected <code>...</code> and <update>...</update> tags").any { it in e }
    }

    /** A bare 404 can mean a wrong URL. Require an explicit model/access signal. */
    fun isInaccessibleError(error: String?): Boolean {
        val e = error?.lowercase() ?: return false
        return listOf("non-serverless", "is not available on", "model_unavailable_in_region",
            "not available in your region", "model not found", "model does not exist",
            "model_not_found", "invalid model name", "model is not available", "no longer available",
            "not found, inaccessible", "not found for account", "no matching target server found for model", "no endpoints found for",
            "not found the model", "has been deprecated", "model version has reached the end of its life",
            "does not exist or you do not have access", "doesn't exist or isn't accessible", "no endpoints available for",
            "not supported in current workspace", "invalid_model", "router not found",
            "api deployment for this resource does not exist", "0 endpoints out of").any { it in e } ||
            ("dedicated endpoint" in e && "is not running" in e) ||
            Regex("\\bmodel\\b.{0,200} is not available[.]").containsMatchIn(e)
    }
}
