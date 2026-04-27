package com.ai.model

import com.ai.data.AgentParameters
import com.ai.data.AppService

enum class ModelSource { API, MANUAL }

data class ProviderConfig(
    val apiKey: String = "",
    val model: String = "",
    val modelSource: ModelSource = ModelSource.API,
    val models: List<String> = emptyList(),
    /** Type classification per model id ("chat", "embedding", "rerank", ...). Sidecar
     *  to `models` rather than embedded so existing screens can keep treating models
     *  as a plain id list. Sourced from native list APIs where possible, naming
     *  heuristic otherwise. */
    val modelTypes: Map<String, String> = emptyMap(),
    /** Set of model ids the user has explicitly flagged as vision-capable
     *  (accept images in the request). Empty by default — vision is
     *  user-curated rather than heuristic so we don't surprise the user
     *  with a hidden filter. Toggled per-model on the Model Info screen. */
    val visionModels: Set<String> = emptySet(),
    /** Set of model ids the user has explicitly flagged as supporting the
     *  web-search tool descriptor (Anthropic web_search_20250305 / Gemini
     *  google_search / OpenAI Responses web_search_preview). Same pattern
     *  as visionModels — user override layered on top of a name heuristic. */
    val webSearchModels: Set<String> = emptySet(),
    val adminUrl: String = "",
    val modelListUrl: String = "",
    val parametersIds: List<String> = emptyList()
)

fun defaultProviderConfig(service: AppService): ProviderConfig {
    val defaultModels = service.hardcodedModels ?: emptyList()
    val defaultModelSource = service.defaultModelSource?.let {
        try { ModelSource.valueOf(it) } catch (_: IllegalArgumentException) { null }
    } ?: if (defaultModels.isNotEmpty()) ModelSource.MANUAL else ModelSource.API
    return ProviderConfig(model = service.defaultModel, modelSource = defaultModelSource, models = defaultModels, adminUrl = service.adminUrl)
}

fun defaultProvidersMap(): Map<AppService, ProviderConfig> = AppService.entries.associateWith { defaultProviderConfig(it) }

enum class Parameter {
    TEMPERATURE, MAX_TOKENS, TOP_P, TOP_K, FREQUENCY_PENALTY, PRESENCE_PENALTY,
    SYSTEM_PROMPT, STOP_SEQUENCES, SEED, RESPONSE_FORMAT, SEARCH_ENABLED, RETURN_CITATIONS, SEARCH_RECENCY,
    WEB_SEARCH_TOOL
}

val ALL_AGENT_PARAMETERS: Set<Parameter> = Parameter.entries.toSet()

private val BUILT_IN_ENDPOINTS: Map<String, List<Endpoint>> = mapOf(
    "OPENAI" to listOf(
        Endpoint("openai-chat", "Chat Completions", "https://api.openai.com/v1/chat/completions", true),
        Endpoint("openai-responses", "Responses API", "https://api.openai.com/v1/responses", false)
    ),
    "MISTRAL" to listOf(
        Endpoint("mistral-chat", "Chat Completions", "https://api.mistral.ai/v1/chat/completions", true),
        Endpoint("mistral-codestral", "Codestral", "https://codestral.mistral.ai/v1/chat/completions", false)
    ),
    "DEEPSEEK" to listOf(
        Endpoint("deepseek-chat", "Chat Completions", "https://api.deepseek.com/chat/completions", true),
        Endpoint("deepseek-beta", "Beta (FIM)", "https://api.deepseek.com/beta/completions", false)
    ),
    "ZAI" to listOf(
        Endpoint("zai-chat", "Chat Completions", "https://api.z.ai/api/paas/v4/chat/completions", true),
        Endpoint("zai-coding", "Coding", "https://api.z.ai/api/coding/paas/v4/chat/completions", false)
    )
)

data class Endpoint(val id: String, val name: String, val url: String, val isDefault: Boolean = false)

data class Agent(
    val id: String, val name: String, val provider: AppService, val model: String,
    val apiKey: String, val endpointId: String? = null,
    val paramsIds: List<String> = emptyList(), val systemPromptId: String? = null
)

data class Flock(
    val id: String, val name: String, val agentIds: List<String> = emptyList(),
    val paramsIds: List<String> = emptyList(), val systemPromptId: String? = null
)

data class SwarmMember(val provider: AppService, val model: String)

data class Swarm(
    val id: String, val name: String, val members: List<SwarmMember> = emptyList(),
    val paramsIds: List<String> = emptyList(), val systemPromptId: String? = null
)

data class Parameters(
    val id: String, val name: String,
    val temperature: Float? = null, val maxTokens: Int? = null,
    val topP: Float? = null, val topK: Int? = null,
    val frequencyPenalty: Float? = null, val presencePenalty: Float? = null,
    val systemPrompt: String? = null, val stopSequences: List<String>? = null,
    val seed: Int? = null, val responseFormatJson: Boolean = false,
    val searchEnabled: Boolean = false, val returnCitations: Boolean = true,
    val searchRecency: String? = null,
    val webSearchTool: Boolean = false
) {
    fun toAgentParameters() = AgentParameters(
        temperature, maxTokens, topP, topK, frequencyPenalty, presencePenalty,
        systemPrompt, stopSequences, seed, responseFormatJson, searchEnabled, returnCitations, searchRecency,
        webSearchTool
    )
}

data class SystemPrompt(val id: String, val name: String, val prompt: String)

/**
 * Manual user-supplied type assignment for a single (provider, model) pair. Wins
 * over the type stored on ProviderConfig.modelTypes (which comes from native
 * list-API metadata or the heuristic). Lives at the Settings root rather than
 * inside ProviderConfig because it's a cross-provider CRUD list — one entry per
 * override, not one map per provider.
 */
data class ModelTypeOverride(
    val id: String,
    val providerId: String,
    val modelId: String,
    val type: String
)

data class Prompt(val id: String, val name: String, val agentId: String, val promptText: String) {
    fun resolvePrompt(model: String? = null, provider: String? = null, agent: String? = null, swarm: String? = null): String {
        var resolved = promptText
        if (model != null) resolved = resolved.replace("@MODEL@", model)
        if (provider != null) resolved = resolved.replace("@PROVIDER@", provider)
        if (agent != null) resolved = resolved.replace("@AGENT@", agent)
        if (swarm != null) resolved = resolved.replace("@SWARM@", swarm)
        resolved = resolved.replace("@NOW@", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()))
        return resolved
    }
}

data class Settings(
    val providers: Map<AppService, ProviderConfig> = defaultProvidersMap(),
    val agents: List<Agent> = emptyList(),
    val flocks: List<Flock> = emptyList(),
    val swarms: List<Swarm> = emptyList(),
    val parameters: List<Parameters> = emptyList(),
    val systemPrompts: List<SystemPrompt> = emptyList(),
    val prompts: List<Prompt> = emptyList(),
    val endpoints: Map<AppService, List<Endpoint>> = emptyMap(),
    val providerStates: Map<String, String> = emptyMap(),
    val modelTypeOverrides: List<ModelTypeOverride> = emptyList()
) {
    fun getProviderState(service: AppService): String {
        val stored = providerStates[service.id]
        if (stored == "inactive") return "inactive"
        if (getApiKey(service).isBlank()) return "not-used"
        return stored ?: "ok"
    }

    fun isProviderActive(service: AppService) = getProviderState(service) == "ok"
    fun getActiveServices() = AppService.entries.filter { isProviderActive(it) }
    fun withProviderState(service: AppService, state: String) = copy(providerStates = providerStates + (service.id to state))

    fun getProvider(service: AppService) = providers[service] ?: defaultProviderConfig(service)
    fun withProvider(service: AppService, config: ProviderConfig) = copy(providers = providers + (service to config))
    fun getApiKey(service: AppService) = getProvider(service).apiKey
    fun withApiKey(service: AppService, apiKey: String) = withProvider(service, getProvider(service).copy(apiKey = apiKey))
    fun getModel(service: AppService) = getProvider(service).model
    fun withModel(service: AppService, model: String) = withProvider(service, getProvider(service).copy(model = model))
    fun getModelSource(service: AppService) = getProvider(service).modelSource
    fun getModels(service: AppService) = getProvider(service).models
    fun withModels(service: AppService, models: List<String>) = withProvider(service, getProvider(service).copy(models = models))
    fun withModels(service: AppService, models: List<String>, types: Map<String, String>) =
        withProvider(service, getProvider(service).copy(models = models, modelTypes = types))
    /** Same as [withModels] but also unions [autoVisionModels] (auto-detected
     *  by the fetcher, e.g. OpenRouter input_modalities) into the per-provider
     *  visionModels set. The set is union-only — refetching never removes a
     *  user-toggled tick. */
    fun withModels(service: AppService, models: List<String>, types: Map<String, String>, autoVisionModels: Set<String>): Settings {
        val cfg = getProvider(service)
        val merged = cfg.visionModels + autoVisionModels
        return withProvider(service, cfg.copy(models = models, modelTypes = types, visionModels = merged))
    }
    /** User-supplied manual override always wins; otherwise read the stored
     *  classification (from native list APIs or the heuristic). */
    fun getModelType(service: AppService, modelId: String): String? {
        modelTypeOverrides.firstOrNull { it.providerId == service.id && it.modelId == modelId }?.let { return it.type }
        return getProvider(service).modelTypes[modelId]
    }

    fun withModelTypeOverrides(overrides: List<ModelTypeOverride>) = copy(modelTypeOverrides = overrides)

    /** Returns true when (provider, modelId) accepts image input. Three
     *  signals, in order:
     *   1. User override on Model Info — stored in ProviderConfig.visionModels.
     *   2. Auto-flagged on the last fetch (OpenRouter input_modalities).
     *      Also stored in visionModels — fetch unions into the set.
     *   3. Naming heuristic — covers gpt-4o, claude-3.x/4.x, gemini-1.5+,
     *      llava/pixtral/qwen-vl/etc. False on miss. */
    fun isVisionCapable(service: AppService, modelId: String): Boolean =
        modelId in getProvider(service).visionModels || com.ai.data.ModelType.inferVision(modelId)

    fun withVisionCapable(service: AppService, modelId: String, enabled: Boolean): Settings {
        val cfg = getProvider(service)
        val newSet = if (enabled) cfg.visionModels + modelId else cfg.visionModels - modelId
        return withProvider(service, cfg.copy(visionModels = newSet))
    }

    /** Returns true when (provider, modelId) supports the web-search tool
     *  descriptor injected by the dispatch layer when the 🌐 toggle is on.
     *  Same three-layer logic as isVisionCapable: explicit user override,
     *  then ModelType.inferWebSearch on the model id + apiFormat. */
    fun isWebSearchCapable(service: AppService, modelId: String): Boolean =
        modelId in getProvider(service).webSearchModels ||
            com.ai.data.ModelType.inferWebSearch(service, modelId)

    fun withWebSearchCapable(service: AppService, modelId: String, enabled: Boolean): Settings {
        val cfg = getProvider(service)
        val newSet = if (enabled) cfg.webSearchModels + modelId else cfg.webSearchModels - modelId
        return withProvider(service, cfg.copy(webSearchModels = newSet))
    }

    /**
     * Cross-pollinate per-provider type labels from OpenRouter's catalog: for any model
     * we currently treat as plain CHAT (the heuristic / unknown default), look up
     * `${service.openRouterName}/${modelId}` in OpenRouter's labeled list and adopt
     * its type if it's something more specific. Native non-chat kinds we already
     * inferred from the provider itself (Cohere endpoints, Gemini methods) are left
     * untouched. Re-call this after any OpenRouter or per-provider fetch.
     */
    fun applyOpenRouterTypes(): Settings {
        val orProvider = AppService.findById("OPENROUTER") ?: return this
        val orTypes = getProvider(orProvider).modelTypes
        if (orTypes.isEmpty()) return this
        var updated = this
        for (service in AppService.entries) {
            if (service.id == "OPENROUTER") continue
            val orPrefix = service.openRouterName ?: continue
            val cfg = getProvider(service)
            if (cfg.models.isEmpty()) continue
            val newTypes = cfg.models.associateWith { id ->
                val current = cfg.modelTypes[id] ?: com.ai.data.ModelType.CHAT
                val orKind = orTypes["$orPrefix/$id"]
                if (current == com.ai.data.ModelType.CHAT && orKind != null && orKind != com.ai.data.ModelType.CHAT) orKind else current
            }
            if (newTypes != cfg.modelTypes) {
                updated = updated.withModels(service, cfg.models, newTypes)
            }
        }
        return updated
    }
    fun hasAnyApiKey() = providers.values.any { it.apiKey.isNotBlank() }

    fun getAgentById(id: String) = agents.find { it.id == id }
    fun resolveAgentParameters(agent: Agent) = mergeParameters(agent.paramsIds) ?: AgentParameters()
    fun getEffectiveApiKeyForAgent(agent: Agent) = agent.apiKey.ifBlank { getApiKey(agent.provider) }
    fun getEffectiveModelForAgent(agent: Agent) = agent.model.ifBlank { getModel(agent.provider) }
    fun getConfiguredAgents() = agents.filter { it.apiKey.isNotBlank() || getApiKey(it.provider).isNotBlank() }

    fun getFlockById(id: String) = flocks.find { it.id == id }
    fun getAgentsForFlock(flock: Flock) = flock.agentIds.mapNotNull { getAgentById(it) }
    fun getAgentsForFlocks(flockIds: Set<String>) = flockIds.flatMap { id -> getFlockById(id)?.let { getAgentsForFlock(it) } ?: emptyList() }.distinctBy { it.id }

    fun getSwarmById(id: String) = swarms.find { it.id == id }
    fun getMembersForSwarm(swarm: Swarm) = swarm.members
    fun getMembersForSwarms(swarmIds: Set<String>) = swarmIds.flatMap { id -> getSwarmById(id)?.members ?: emptyList() }.distinctBy { "${it.provider.id}:${it.model}" }

    fun getPromptByName(name: String) = prompts.find { it.name.equals(name, ignoreCase = true) }
    fun getSystemPromptById(id: String) = systemPrompts.find { it.id == id }
    fun getParametersById(id: String) = parameters.find { it.id == id }
    fun getParametersByName(name: String) = parameters.find { it.name.equals(name, ignoreCase = true) }
    fun getPromptById(id: String) = prompts.find { it.id == id }
    fun getAgentForPrompt(prompt: Prompt) = getAgentById(prompt.agentId)

    fun getEndpointsForProvider(provider: AppService): List<Endpoint> =
        endpoints[provider]?.ifEmpty { getBuiltInEndpoints(provider) } ?: getBuiltInEndpoints(provider)

    private fun getBuiltInEndpoints(provider: AppService) =
        BUILT_IN_ENDPOINTS[provider.id] ?: defaultEndpointForProvider(provider)

    private fun defaultEndpointForProvider(provider: AppService): List<Endpoint> {
        val base = if (provider.baseUrl.endsWith("/")) provider.baseUrl else "${provider.baseUrl}/"
        return listOf(Endpoint("${provider.id.lowercase()}-chat", "Chat Completions", base + provider.chatPath, true))
    }

    fun getEndpointById(provider: AppService, endpointId: String) = getEndpointsForProvider(provider).find { it.id == endpointId }
    fun getDefaultEndpoint(provider: AppService) = getEndpointsForProvider(provider).find { it.isDefault } ?: getEndpointsForProvider(provider).firstOrNull()
    fun getEffectiveEndpointUrl(provider: AppService) = getDefaultEndpoint(provider)?.url ?: provider.baseUrl
    fun getEffectiveEndpointUrlForAgent(agent: Agent): String {
        agent.endpointId?.let { getEndpointById(agent.provider, it)?.let { e -> return e.url } }
        return getEffectiveEndpointUrl(agent.provider)
    }

    fun withEndpoints(provider: AppService, newEndpoints: List<Endpoint>) = copy(endpoints = endpoints + (provider to newEndpoints))

    fun removeProvider(service: AppService): Settings {
        val removedAgentIds = agents.filter { it.provider.id == service.id }.map { it.id }.toSet()
        return copy(
            providers = providers - service, endpoints = endpoints - service, providerStates = providerStates - service.id,
            agents = agents.filter { it.provider.id != service.id },
            flocks = flocks.map { it.copy(agentIds = it.agentIds.filter { id -> id !in removedAgentIds }) },
            swarms = swarms.map { it.copy(members = it.members.filter { m -> m.provider.id != service.id }) },
            prompts = prompts.filter { it.agentId !in removedAgentIds }
        )
    }

    fun removeSystemPrompt(systemPromptId: String) = copy(
        systemPrompts = systemPrompts.filter { it.id != systemPromptId },
        agents = agents.map { if (it.systemPromptId == systemPromptId) it.copy(systemPromptId = null) else it },
        flocks = flocks.map { if (it.systemPromptId == systemPromptId) it.copy(systemPromptId = null) else it },
        swarms = swarms.map { if (it.systemPromptId == systemPromptId) it.copy(systemPromptId = null) else it }
    )

    fun removeParameters(parametersId: String) = copy(
        parameters = parameters.filter { it.id != parametersId },
        agents = agents.map { it.copy(paramsIds = it.paramsIds.filter { id -> id != parametersId }) },
        flocks = flocks.map { it.copy(paramsIds = it.paramsIds.filter { id -> id != parametersId }) },
        swarms = swarms.map { it.copy(paramsIds = it.paramsIds.filter { id -> id != parametersId }) },
        providers = providers.mapValues { (_, c) -> c.copy(parametersIds = c.parametersIds.filter { it != parametersId }) }
    )

    fun removeAgent(agentId: String) = copy(
        agents = agents.filter { it.id != agentId },
        flocks = flocks.map { it.copy(agentIds = it.agentIds.filter { id -> id != agentId }) },
        prompts = prompts.filter { it.agentId != agentId }
    )

    fun getModelListUrl(service: AppService) = getProvider(service).modelListUrl
    fun getDefaultModelListUrl(service: AppService): String {
        val base = if (service.baseUrl.endsWith("/")) service.baseUrl else "${service.baseUrl}/"
        return base + service.modelsPath
    }
    fun getEffectiveModelListUrl(service: AppService): String {
        val custom = getModelListUrl(service)
        return if (custom.isNotBlank()) custom else getDefaultModelListUrl(service)
    }

    fun getParametersIds(service: AppService) = getProvider(service).parametersIds
    fun withParametersIds(service: AppService, paramsIds: List<String>) = withProvider(service, getProvider(service).copy(parametersIds = paramsIds))
    fun getParametersByIds(ids: List<String>) = ids.mapNotNull { getParametersById(it) }

    fun mergeParameters(ids: List<String>): AgentParameters? {
        if (ids.isEmpty()) return null
        val presets = getParametersByIds(ids)
        if (presets.isEmpty()) return null
        return presets.map { it.toAgentParameters() }.reduce { acc, p ->
            AgentParameters(
                p.temperature ?: acc.temperature, p.maxTokens ?: acc.maxTokens,
                p.topP ?: acc.topP, p.topK ?: acc.topK,
                p.frequencyPenalty ?: acc.frequencyPenalty, p.presencePenalty ?: acc.presencePenalty,
                p.systemPrompt ?: acc.systemPrompt, p.stopSequences ?: acc.stopSequences,
                p.seed ?: acc.seed, p.responseFormatJson || acc.responseFormatJson, p.searchEnabled || acc.searchEnabled, p.returnCitations || acc.returnCitations,
                p.searchRecency ?: acc.searchRecency,
                p.webSearchTool || acc.webSearchTool
            )
        }
    }
}

// Report model expansion helpers
data class ReportModel(
    val provider: AppService, val model: String, val type: String, val sourceType: String,
    val sourceName: String, val sourceId: String? = null, val agentId: String? = null, val endpointId: String? = null,
    val agentApiKey: String? = null, val paramsIds: List<String> = emptyList()
) {
    val deduplicationKey: String get() = "${provider.id}:$model"
}

fun expandFlockToModels(flock: Flock, s: Settings) = flock.agentIds.mapNotNull { id ->
    val agent = s.getAgentById(id) ?: return@mapNotNull null
    if (!s.isProviderActive(agent.provider)) return@mapNotNull null
    ReportModel(agent.provider, s.getEffectiveModelForAgent(agent), "agent", "flock", flock.name,
        flock.id, agent.id, agent.endpointId, s.getEffectiveApiKeyForAgent(agent), flock.paramsIds + agent.paramsIds)
}

fun expandAgentToModel(agent: Agent, s: Settings): ReportModel? {
    if (!s.isProviderActive(agent.provider)) return null
    return ReportModel(agent.provider, s.getEffectiveModelForAgent(agent), "agent", "agent", agent.name,
        agent.id, agent.id, agent.endpointId, s.getEffectiveApiKeyForAgent(agent), agent.paramsIds)
}

fun expandSwarmToModels(swarm: Swarm, s: Settings) = swarm.members.filter { s.isProviderActive(it.provider) }.map {
    ReportModel(it.provider, it.model, "model", "swarm", swarm.name, swarm.id, paramsIds = swarm.paramsIds)
}

fun toReportModel(provider: AppService, model: String) = ReportModel(provider, model, "model", "model", "")

fun deduplicateModels(models: List<ReportModel>): List<ReportModel> {
    val seen = mutableSetOf<String>()
    return models.filter { seen.add(it.deduplicationKey) }
}

data class UsageStats(
    val provider: AppService, val model: String, val callCount: Int = 0,
    val inputTokens: Long = 0, val outputTokens: Long = 0
) {
    val totalTokens: Long get() = inputTokens + outputTokens
    val key: String get() = "${provider.id}::$model"
}
