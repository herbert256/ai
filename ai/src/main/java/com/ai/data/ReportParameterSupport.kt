package com.ai.data

/** Validate requested controls before dispatch, rather than silently changing an experiment.
 * Unknown gateways/models still reach their provider: absence of metadata is not rejection.
 */
internal fun AnalysisRepository.reportParameterError(service: AppService, model: String, p: AgentParameters?): String? {
    if (p == null) return null
    val errors = mutableListOf<String>()
    val range = temperatureRangeForProvider(service)
    if (p.temperature != null && !range.contains(p.temperature)) errors += "temperature must be ${range.min}..${range.max}"
    if (p.topP != null && (!p.topP.isFinite() || p.topP !in 0f..1f)) errors += "top P must be 0..1"
    if (p.maxTokens != null && p.maxTokens < 1) errors += "max tokens must be positive"
    if (p.topK != null && p.topK < 1) errors += "top K must be positive"
    if (p.frequencyPenalty != null && (!p.frequencyPenalty.isFinite() || p.frequencyPenalty !in -2f..2f)) errors += "frequency penalty must be -2..2"
    if (p.presencePenalty != null && (!p.presencePenalty.isFinite() || p.presencePenalty !in -2f..2f)) errors += "presence penalty must be -2..2"
    fun unsupported(set: Boolean, name: String) { if (set) errors += "$name is not supported on this endpoint" }
    unsupported(p.webSearchTool && service.apiFormat == ApiFormat.OPENAI_COMPATIBLE &&
        !usesResponsesApi(service, model) && openAiChatWebSearchTool() == null, "web search tool")
    unsupported(!p.searchRecency.isNullOrBlank() && !service.supportsSearchRecency, "search recency")
    if (!p.searchRecency.isNullOrBlank() && p.searchRecency !in setOf("day", "week", "month", "year")) {
        errors += "search recency must be day, week, month, or year"
    }
    if (service.apiFormat == ApiFormat.REPLICATE || service == AppService.LOCAL) {
        unsupported(p.frequencyPenalty != null || p.presencePenalty != null, "repetition penalties")
        unsupported(!p.stopSequences.isNullOrEmpty(), "stop sequences")
        unsupported(p.responseFormatJson, "JSON object mode")
        unsupported(p.webSearchTool || p.searchEnabled, "web search")
        if (service == AppService.LOCAL) unsupported(p.maxTokens != null, "per-turn max tokens")
        else {
            unsupported(p.topK != null, "top K")
            unsupported(p.seed != null, "seed")
        }
    }
    unsupported(!p.reasoningEffort.isNullOrBlank() && !isReasoningCapableForDispatch(service, model), "reasoning effort")
    if (usesResponsesApi(service, model)) {
        unsupported(p.topK != null, "top K")
        unsupported(p.seed != null, "seed")
        unsupported(p.frequencyPenalty != null, "frequency penalty")
        unsupported(p.presencePenalty != null, "presence penalty")
        unsupported(!p.stopSequences.isNullOrEmpty(), "stop sequences")
    }
    if (service.id == "OpenAI") {
        unsupported(p.topK != null && !usesResponsesApi(service, model), "top K")
        val modernGpt5 = Regex("^gpt-5\\.[124](?:-|$)").containsMatchIn(model)
        val fixedSampling = Regex("^(?:o[134](?:-|$)|gpt-5(?:-|$))").containsMatchIn(model)
        if (model.startsWith("gpt-5.5") && (p.temperature != null && p.temperature != 1f)) {
            errors += "this model accepts only its fixed default temperature (1)"
        }
        val reasoningOn = !p.reasoningEffort.isNullOrBlank() && p.reasoningEffort != "none"
        if ((p.temperature != null || p.topP != null) && (fixedSampling || modernGpt5 && reasoningOn)) {
            errors += if (modernGpt5) "temperature/top P require reasoning effort none" else "this model does not support temperature/top P"
        }
    }
    if (service.apiFormat == ApiFormat.ANTHROPIC) {
        unsupported(p.seed != null, "seed")
        unsupported(p.frequencyPenalty != null, "frequency penalty")
        unsupported(p.presencePenalty != null, "presence penalty")
        unsupported(p.responseFormatJson, "JSON object mode (use a JSON prompt or a structured-output schema)")
        unsupported(p.searchEnabled, "search flag (use the web search tool)")
        if (Regex("^claude-(?:haiku|sonnet|opus)-4-[56](?:-|$)").containsMatchIn(model) && p.temperature != null && p.topP != null) errors += "choose temperature or top P, not both"
        if (claudeUsesAdaptiveThinking(service, model) && (p.temperature != null && p.temperature != 1f || p.topP != null && p.topP < 0.99f || p.topK != null)) {
            errors += "this model does not support adjustable sampling controls"
        }
        val thinking = anthropicThinkingField(service, model, p.reasoningEffort)
        if (thinking != null) {
            if (p.temperature != null && p.temperature != 1f || p.topK != null) errors += "temperature/top K cannot be combined with extended thinking"
            if (p.topP != null && p.topP < 0.95f) errors += "top P must be at least 0.95 with extended thinking"
            val budget = thinking["budget_tokens"] as? Int
            if (budget != null && p.maxTokens != null && p.maxTokens <= budget) errors += "max tokens must exceed the thinking budget ($budget); the app will not raise your cap"
        }
    }
    if (service.id == "DeepSeek") {
        unsupported(p.topK != null, "top K")
        unsupported(p.seed != null, "seed")
        if (model == "deepseek-reasoner" || model.startsWith("deepseek-v4")) {
            if (p.temperature != null || p.topP != null || p.frequencyPenalty != null || p.presencePenalty != null) {
                errors += "sampling controls have no effect in this model's default thinking mode"
            }
        }
    }
    if (service.id == "Mistral" && model == "mistral-medium-latest") {
        unsupported(p.topK != null, "top K")
        if (!p.reasoningEffort.isNullOrBlank() && p.reasoningEffort !in setOf("none", "high")) {
            errors += "reasoning effort must be none or high"
        }
    }
    // Native rejections observed in the parameter audit; do not generalize to other models.
    if (service.id == "xAI" && model == "grok-4.3") {
        unsupported(p.presencePenalty != null, "presence penalty")
        unsupported(!p.stopSequences.isNullOrEmpty(), "stop sequences")
    }
    if (service.apiFormat == ApiFormat.GOOGLE && model == "gemini-2.5-flash") {
        unsupported(p.frequencyPenalty != null || p.presencePenalty != null, "repetition penalties")
    }
    if (service.apiFormat == ApiFormat.GOOGLE) {
        if (p.reasoningEffort == "none" && !model.startsWith("gemini-2.5-flash")) {
            errors += "thinking cannot be disabled on this model; use its default or a supported effort"
        }
        unsupported(p.searchEnabled, "search flag (use the web search tool)")
        if (p.frequencyPenalty == 2f || p.presencePenalty == 2f) errors += "Gemini penalties must be less than 2"
    }
    return errors.distinct().takeIf { it.isNotEmpty() }?.joinToString("; ")?.let {
        "Parameter configuration for ${service.id}/$model: $it. Change the preset before retrying."
    }
}

internal fun rejectedReportParameters(service: AppService, message: String) =
    AnalysisResponse(service, null, message, generationFailed = true)

internal fun responsesJsonText(params: AgentParameters?): Map<String, Any>? =
    if (params?.responseFormatJson == true) mapOf("format" to mapOf("type" to "json_object")) else null

internal fun ChatParameters.forParameterValidation() = AgentParameters(
    temperature = temperature, maxTokens = maxTokens, topP = topP, topK = topK,
    frequencyPenalty = frequencyPenalty, presencePenalty = presencePenalty,
    reasoningEffort = reasoningEffort, searchEnabled = searchEnabled,
    systemPrompt = systemPrompt, stopSequences = stopSequences, seed = seed,
    responseFormatJson = responseFormatJson, webSearchTool = webSearchTool,
    returnCitations = returnCitations, searchRecency = searchRecency
)

internal fun AnalysisRepository.chatConfigurationError(service: AppService, model: String, params: ChatParameters): String? {
    if (com.ai.model.SettingsHolder.current?.getProviderState(service) == "inactive") {
        return "Provider ${service.id} is inactive. Enable it in AI setup before retrying."
    }
    return reportParameterError(service, model, params.forParameterValidation())
}
