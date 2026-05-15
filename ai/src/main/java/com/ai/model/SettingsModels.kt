package com.ai.model

import com.ai.data.AgentParameters
import com.ai.data.AppService
import com.ai.data.anyMatches

enum class ModelSource { API, MANUAL }

data class ProviderConfig(
    val apiKey: String = "",
    // The previously per-user `model` and `modelSource` fields were
    // dropped — both live as single fields on [AppService]
    // (`defaultModel`, `defaultModelSource`), loaded from
    // assets/providers.json on first install and edited via the
    // provider settings UI (writes through ProviderRegistry.update).
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
    /** Per-model capabilities harvested from the provider's own /models
     *  response (Mistral capabilities object, Gemini token limits, Cohere
     *  context_length, etc.). Authoritative when present — the provider's
     *  self-report wins over LiteLLM / models.dev / heuristic. Refreshed
     *  whenever the model list is fetched. */
    val modelCapabilities: Map<String, com.ai.data.ModelCapabilities> = emptyMap(),
    /** Raw JSON body of the provider's last /models response — preserved
     *  so a future parser revision can extract additional fields without
     *  forcing the user to re-fetch. Lives in eval_prefs and round-trips
     *  through the backup zip. */
    val modelListRawJson: String? = null,
    /** Set of model ids the user has explicitly flagged as supporting the
     *  web-search tool descriptor (Anthropic web_search_20250305 / Gemini
     *  google_search / OpenAI Responses web_search_preview). Same pattern
     *  as visionModels — user override layered on top of a name heuristic. */
    val webSearchModels: Set<String> = emptySet(),
    /** Set of model ids the user has explicitly flagged as supporting a
     *  thinking / reasoning_effort parameter. Mirrors visionModels /
     *  webSearchModels — user-curated; provider /models auto-detect
     *  unions in here at fetch time. */
    val reasoningModels: Set<String> = emptySet(),
    /** Pre-computed result of the layered isVisionCapable lookup for every
     *  id in [models]. Populated by [Settings.recomputeCapabilities]
     *  whenever the model list refreshes or a capability source (LiteLLM,
     *  models.dev) changes. Read directly by [Settings.isVisionCapable]
     *  to avoid re-running ~1k-entry catalog scans on every UI render —
     *  long lists were spending all their render time in those scans.
     *  User overrides ([visionModels]) and [ModelTypeOverride] are still
     *  consulted first, so toggling a single model doesn't require a
     *  full recompute. */
    val visionCapableComputed: Set<String> = emptySet(),
    val webSearchCapableComputed: Set<String> = emptySet(),
    val reasoningCapableComputed: Set<String> = emptySet(),
    /** Pre-computed pricing for every id in [models], keyed by model id —
     *  same idea as [visionCapableComputed]. List rows pull prompt /
     *  completion price (and `source` for the color tag) straight from
     *  this map instead of running PricingCache's catalog scans on every
     *  recomposition. Populated by [Settings.recomputeCapabilities] and
     *  refreshed alongside the capability sets after a catalog refresh. */
    val modelPricing: Map<String, com.ai.data.PricingCache.ModelPricing> = emptyMap(),
    val parametersIds: List<String> = emptyList()
)

fun defaultProviderConfig(service: AppService): ProviderConfig {
    val defaultModels = service.hardcodedModels ?: emptyList()
    return ProviderConfig(models = defaultModels)
}

/** Decode the catalog [AppService.defaultModelSource] string ("API" /
 *  "MANUAL") into the [ModelSource] enum. Falls back to MANUAL when
 *  the provider ships hardcodedModels and no explicit source is set
 *  (the bundled list IS the model catalog), API otherwise. */
fun resolveModelSource(service: AppService): ModelSource {
    service.defaultModelSource?.let {
        try { return ModelSource.valueOf(it) } catch (_: IllegalArgumentException) { /* fall through */ }
    }
    return if (!service.hardcodedModels.isNullOrEmpty()) ModelSource.MANUAL else ModelSource.API
}

fun defaultProvidersMap(): Map<AppService, ProviderConfig> = AppService.entries.associateWith { defaultProviderConfig(it) }

enum class Parameter {
    TEMPERATURE, MAX_TOKENS, TOP_P, TOP_K, FREQUENCY_PENALTY, PRESENCE_PENALTY,
    SYSTEM_PROMPT, STOP_SEQUENCES, SEED, RESPONSE_FORMAT, SEARCH_ENABLED, RETURN_CITATIONS, SEARCH_RECENCY,
    WEB_SEARCH_TOOL
}

val ALL_AGENT_PARAMETERS: Set<Parameter> = Parameter.entries.toSet()

// Per-provider built-in endpoints moved to AppService.builtInEndpoints,
// which is populated from assets/providers.json. Endpoint itself lives
// in com.ai.data — re-exposed via a typealias so existing callers in
// the model + ui layers compile unchanged.
typealias Endpoint = com.ai.data.Endpoint

data class Agent(
    val id: String, val name: String, val provider: AppService, val model: String,
    val apiKey: String, val endpointId: String? = null,
    val paramsIds: List<String> = emptyList(), val systemPromptId: String? = null
)

data class Flock(
    val id: String, val name: String, val agentIds: List<String> = emptyList(),
    val paramsIds: List<String> = emptyList(), val systemPromptId: String? = null
)

/** Name of the auto-managed flock that the per-provider Test button
 *  and the Refresh All → Default agents step populate. Stored here
 *  so the two call sites match exactly. */
const val DEFAULT_AGENTS_FLOCK_NAME = "default agents"

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
    val webSearchTool: Boolean = false,
    val reasoningEffort: String? = null
) {
    fun toAgentParameters() = AgentParameters(
        temperature, maxTokens, topP, topK, frequencyPenalty, presencePenalty,
        systemPrompt, stopSequences, seed, responseFormatJson, searchEnabled, returnCitations, searchRecency,
        webSearchTool, reasoningEffort
    )
}

data class SystemPrompt(val id: String, val name: String, val prompt: String)

/** A provider/model pair the user has flagged as "blocked" — surfaced
 *  dimmed (but still selectable) in every model picker. Identity is the
 *  `(providerId, model)` pair; no UUID. Auto-populated by the "Test all
 *  models" sweep (every FAIL → a block, every PASS → un-block) and
 *  hand-curated via AI Setup → Blocked models. */
data class BlockedModel(
    val providerId: String,
    val model: String,
    val reason: String = ""
) {
    val key: String get() = "$providerId:$model"
}

/** A provider/model pair the user (or the >5¢ auto-add rule) has
 *  flagged as "don't probe in Test all models". Identity is the
 *  `(providerId, model)` pair; no UUID, no reason field — the list is
 *  a simple skip-set. */
data class TestExcludedModel(
    val providerId: String,
    val model: String
) {
    val key: String get() = "$providerId:$model"
}

/** A provider/model pair the user can't actually call on their tier
 *  (Together non-serverless catalog entries, etc.). Distinct from
 *  [TestExcludedModel]: entries here are *hidden* from model pickers
 *  (the model is genuinely unreachable) and dropped from sweep results
 *  rather than counted as FAIL. Auto-populated by the test engine when
 *  a probe returns "Unable to access non-serverless" (or similar
 *  tier-gating errors); hand-curable here. */
data class InaccessibleModel(
    val providerId: String,
    val model: String,
    val reason: String
) {
    val key: String get() = "$providerId:$model"
}

/**
 * User-managed Internal prompt — covers Meta-prompt launchers on the
 * Report Result screen ([category] == "meta"), Fan-out / Fan-in
 * templates ([category] == "fan_out" / "fan_in"), and the fixed
 * internal templates ([category] == "internal": intro, model_info,
 * translate, rerank, moderation). [reference]: when true, the executor
 * appends a `[N] = Provider / Model` legend to the response. [agent]
 * is either the literal sentinel `"*select"` (ask the user which model
 * to run on) or the name of an [Agent] in [Settings.agents]. [text]
 * is the chat template body (substituted with @QUESTION@ / @RESULTS@ /
 * @COUNT@ / @TITLE@ / @DATE@ and category-specific placeholders).
 */
data class InternalPrompt(
    val id: String,
    val name: String,
    val reference: Boolean = false,
    val category: String = "internal",
    val agent: String = "*select",
    val text: String = "",
    /** Short human-readable label of what the prompt does. The
     *  user-given [name] is the identifier; [title] is a one-line
     *  description shown alongside it on Fan out and the prompt-edit
     *  screen. */
    val title: String = ""
)

/** Stand-alone example prompt — pure (title, text) pair the user
 *  curates as a starter library for the New Report screen and similar
 *  flows. Distinct from [InternalPrompt] (which carries category /
 *  agent / placeholder semantics for app-feature templates). */
data class ExamplePrompt(
    val id: String,
    val title: String = "",
    val text: String = ""
)

/**
 * Manual user-supplied type assignment for a single (provider, model) pair. Wins
 * over the type stored on ProviderConfig.modelTypes (which comes from native
 * list-API metadata or the heuristic). Lives at the Settings root rather than
 * inside ProviderConfig because it's a fan out-provider CRUD list — one entry per
 * override, not one map per provider.
 */
data class ModelTypeOverride(
    val id: String,
    val providerId: String,
    val modelId: String,
    val type: String,
    /** When true, isVisionCapable for this (provider, model) returns true
     *  even if the per-provider visionModels set doesn't contain it. */
    val supportsVision: Boolean = false,
    /** When true, isWebSearchCapable for this (provider, model) returns
     *  true even if the per-provider webSearchModels set doesn't contain it. */
    val supportsWebSearch: Boolean = false,
    /** When true, isReasoningCapable for this (provider, model) returns
     *  true even if the per-provider reasoningModels set doesn't contain it. */
    val supportsReasoning: Boolean = false
)

data class Settings(
    val providers: Map<AppService, ProviderConfig> = defaultProvidersMap(),
    val agents: List<Agent> = emptyList(),
    val flocks: List<Flock> = emptyList(),
    val swarms: List<Swarm> = emptyList(),
    val parameters: List<Parameters> = emptyList(),
    val systemPrompts: List<SystemPrompt> = emptyList(),
    val internalPrompts: List<InternalPrompt> = emptyList(),
    val examplePrompts: List<ExamplePrompt> = emptyList(),
    val endpoints: Map<AppService, List<Endpoint>> = emptyMap(),
    val providerStates: Map<String, String> = emptyMap(),
    val modelTypeOverrides: List<ModelTypeOverride> = emptyList(),
    val blockedModels: List<BlockedModel> = emptyList(),
    val testExcludedModels: List<TestExcludedModel> = emptyList(),
    val inaccessibleModels: List<InaccessibleModel> = emptyList()
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
    fun getModel(service: AppService) = service.defaultModel
    /** Persist a new default model for [service] — writes to the
     *  [ProviderRegistry] (single source of truth) and returns this
     *  Settings unchanged. Provider state lookups go through
     *  AppService.findById which reads the registry, so the updated
     *  value is picked up on the next read. */
    fun withModel(service: AppService, model: String): Settings {
        com.ai.data.ProviderRegistry.update(service.copy(defaultModel = model))
        return this
    }
    fun getModelSource(service: AppService) = resolveModelSource(service)

    /** Persist a new model source for [service] — writes the
     *  ModelSource enum's `name` to [AppService.defaultModelSource]
     *  via [com.ai.data.ProviderRegistry.update]. Single source of
     *  truth lives on the catalog; this Settings is unchanged. */
    fun withModelSource(service: AppService, source: ModelSource): Settings {
        if (resolveModelSource(service) == source) return this
        com.ai.data.ProviderRegistry.update(service.copy(defaultModelSource = source.name))
        return this
    }

    fun getModels(service: AppService) = getProvider(service).models
    fun withModels(service: AppService, models: List<String>) =
        withProvider(service, getProvider(service).copy(models = models.distinct())).recomputeCapabilities(service)
    fun withModels(service: AppService, models: List<String>, types: Map<String, String>) =
        withProvider(service, getProvider(service).copy(models = models.distinct(), modelTypes = types)).recomputeCapabilities(service)
    /** Same as [withModels] but also unions [autoVisionModels] (auto-detected
     *  by the fetcher, e.g. OpenRouter input_modalities) into the per-provider
     *  visionModels set. The set is union-only — refetching never removes a
     *  user-toggled tick. */
    fun withModels(service: AppService, models: List<String>, types: Map<String, String>, autoVisionModels: Set<String>): Settings {
        val cfg = getProvider(service)
        val merged = cfg.visionModels + autoVisionModels
        return withProvider(service, cfg.copy(models = models.distinct(), modelTypes = types, visionModels = merged))
            .recomputeCapabilities(service)
    }
    /** Same as the 4-arg overload, plus a per-model capability bundle from
     *  the provider's own /models endpoint (Mistral, Gemini, Cohere, etc.).
     *  Replaces — not merges — the existing capabilities map: a refetch
     *  always reflects the provider's current view.
     *
     *  Every overload of [withModels] funnels its [models] through
     *  [List.distinct] so duplicates can't sneak into ProviderConfig.models
     *  regardless of where they came from (fetch dupe, hardcoded list with
     *  a typo, manual paste). LazyColumns key on the model id and crash
     *  on dupes; centralising the dedup here means callers don't have to
     *  remember it. */
    fun withModels(
        service: AppService, models: List<String>, types: Map<String, String>,
        autoVisionModels: Set<String>, capabilities: Map<String, com.ai.data.ModelCapabilities>,
        rawResponse: String? = null
    ): Settings {
        val cfg = getProvider(service)
        val merged = cfg.visionModels + autoVisionModels
        // OpenAI's /v1/models doesn't enumerate moderation
        // (`omni-moderation-latest`, `text-moderation-latest`),
        // text-to-speech (`tts-1`), transcription (`whisper-1`), or
        // image (`dall-e-3`, `gpt-image-1`) endpoints — they're
        // documented but unlisted. The fallback list in setup.json
        // reinstates them so the Moderation / TTS / Image / STT
        // pickers can find them. Distinct() dedupes overlap when the
        // API does happen to return one.
        //
        // For every other provider the API list is canonical: merging
        // hardcodedModels in would resurrect retired ids the provider's
        // own API correctly omitted (Anthropic claude-3.x, retired
        // OpenRouter aliases, etc.), so the union is gated to OpenAI.
        // Only the fetcher path (this 5-arg overload) merges; manual-
        // edit overloads stay verbatim so the user can still prune.
        val withFallback = if (service.mergeHardcodedModels) {
            (models + (service.hardcodedModels ?: emptyList())).distinct()
        } else {
            models.distinct()
        }
        return withProvider(service, cfg.copy(
            models = withFallback, modelTypes = types,
            visionModels = merged, modelCapabilities = capabilities,
            modelListRawJson = rawResponse ?: cfg.modelListRawJson
        )).recomputeCapabilities(service)
    }
    /** User-supplied manual override always wins; otherwise consult LiteLLM
     *  for a specific (non-CHAT) classification, then layer the stored
     *  classification (native list APIs) against the naming heuristic.
     *  LiteLLM is conservative on model types — it tags gpt-5/o-series
     *  as "chat" even though we route them through the Responses API —
     *  so we only trust LiteLLM when it returns something more specific
     *  than CHAT.
     *
     *  When the stored type and the heuristic *disagree*: a confident
     *  non-CHAT heuristic wins over a generic stored CHAT (Google's
     *  list API tags everything that supports generateContent as
     *  "chat", which mis-buckets deep-research-*, computer-use-*,
     *  tts-preview, etc.). Stored non-CHAT entries (EMBEDDING / RERANK
     *  from richer native lists) still win over CHAT-leaning heuristic
     *  output via the early return. */
    fun getModelType(service: AppService, modelId: String): String? {
        modelTypeOverrides.firstOrNull { it.providerId == service.id && it.modelId == modelId }?.let { return it.type }
        com.ai.data.PricingCache.liteLLMModelType(service, modelId)?.let {
            if (it != com.ai.data.ModelType.CHAT) return it
        }
        val stored = getProvider(service).modelTypes[modelId]
        val inferred = com.ai.data.ModelType.infer(modelId)
        if (inferred != com.ai.data.ModelType.CHAT && stored == com.ai.data.ModelType.CHAT) {
            return inferred
        }
        return stored ?: inferred.takeIf { it != com.ai.data.ModelType.CHAT }
    }

    fun withModelTypeOverrides(overrides: List<ModelTypeOverride>) = copy(modelTypeOverrides = overrides)

    /** Returns true when (provider, modelId) accepts image input. Layered
     *  signals, in order:
     *   1. User override on Model Info — ProviderConfig.visionModels.
     *      Also includes anything auto-flagged on a previous fetch
     *      (OpenRouter input_modalities) since fetch unions into the set.
     *   2. Manual Model Type Override entry with supportsVision = true.
     *   3. LiteLLM supports_vision flag — authoritative when present
     *      (true OR false), falls through when absent.
     *   4. Naming heuristic — covers gpt-4o, claude-3.x/4.x, gemini-1.5+,
     *      llava/pixtral/qwen-vl/etc. False on miss. */
    fun isVisionCapable(service: AppService, modelId: String): Boolean {
        val cfg = getProvider(service)
        if (modelId in cfg.visionModels) return true
        if (modelTypeOverrides.any { it.providerId == service.id && it.modelId == modelId && it.supportsVision }) return true
        // Pre-computed result of the slow layered lookup. Populated by
        // recomputeCapabilities after every model-list / catalog refresh.
        if (modelId in cfg.visionCapableComputed) return true
        // Fall through only when the model isn't in the precomputed set —
        // covers freshly-added manual entries before a recompute fires and
        // the read-only Manual Override CRUD that asks about ids never
        // listed under any provider.
        return computeVisionCapableSlow(service, modelId)
    }

    /** Runs the full layered lookup ignoring [visionModels] and
     *  [modelTypeOverrides] — those are user-curated short-circuits already
     *  handled by [isVisionCapable]. Used both for the per-call fallback
     *  path and to populate [ProviderConfig.visionCapableComputed]. */
    private fun computeVisionCapableSlow(service: AppService, modelId: String): Boolean {
        getProvider(service).modelCapabilities[modelId]?.supportsVision?.let { return it }
        com.ai.data.PricingCache.liteLLMSupportsVision(service, modelId)?.let { return it }
        com.ai.data.PricingCache.modelsDevSupportsVision(service, modelId)?.let { return it }
        return com.ai.data.ModelType.inferVision(modelId)
    }

    fun withVisionCapable(service: AppService, modelId: String, enabled: Boolean): Settings {
        val cfg = getProvider(service)
        val newSet = if (enabled) cfg.visionModels + modelId else cfg.visionModels - modelId
        return withProvider(service, cfg.copy(visionModels = newSet))
    }

    /** Returns true when (provider, modelId) supports the web-search tool
     *  descriptor injected by the dispatch layer when the 🌐 toggle is on.
     *  Layered: ProviderConfig.webSearchModels override, then a Manual Model
     *  Type Override with supportsWebSearch = true, then the LiteLLM
     *  supports_web_search flag (authoritative true/false when present),
     *  then the name + apiFormat heuristic. */
    fun isWebSearchCapable(service: AppService, modelId: String): Boolean {
        val cfg = getProvider(service)
        if (modelId in cfg.webSearchModels) return true
        if (modelTypeOverrides.any { it.providerId == service.id && it.modelId == modelId && it.supportsWebSearch }) return true
        if (modelId in cfg.webSearchCapableComputed) return true
        return computeWebSearchCapableSlow(service, modelId)
    }

    private fun computeWebSearchCapableSlow(service: AppService, modelId: String): Boolean {
        // The supportsFunctionCalling and tool_call proxies say "this
        // model accepts a tools[] array", which only translates to a
        // working 🌐 toggle when the dispatch path actually emits a
        // web-search tool descriptor. ApiDispatch.openAiChatWebSearchTool
        // returns null, so an OpenAI-compatible Chat-Completions model
        // (gpt-4o-mini, Mistral chat, etc.) advertised as capable
        // would surface a no-op toggle. Gate the proxies behind
        // dispatchEmitsWebSearchTool so only Responses / Anthropic /
        // Gemini paths benefit from the shortcut. LiteLLM stays
        // unconditional — its supports_web_search flag is a direct
        // answer about web search, not a tool-calling proxy.
        val emits = dispatchEmitsWebSearchTool(service, modelId)
        if (emits) {
            getProvider(service).modelCapabilities[modelId]?.supportsFunctionCalling?.let { return it }
        }
        com.ai.data.PricingCache.liteLLMSupportsWebSearch(service, modelId)?.let { return it }
        // models.dev exposes tool_call (function-calling) which is a strong
        // proxy for "tool descriptors are supported on this model" — we
        // only fall through to it when LiteLLM has no opinion. The naming
        // heuristic still wins on a true LiteLLM negative.
        if (emits) {
            com.ai.data.PricingCache.modelsDevSupportsToolCall(service, modelId)?.let { return it }
        }
        return com.ai.data.ModelType.inferWebSearch(service, modelId)
    }

    /** True when the dispatch layer would inject a real web-search
     *  tool descriptor on the outbound request. Anthropic and Gemini
     *  always do; OpenAI-compatible providers only do for models that
     *  route through the Responses API. Used to gate the
     *  function-calling capability proxy in computeWebSearchCapableSlow
     *  so the 🌐 toggle isn't advertised for models that would silently
     *  drop it at dispatch. */
    private fun dispatchEmitsWebSearchTool(service: AppService, modelId: String): Boolean = when (service.apiFormat) {
        com.ai.data.ApiFormat.ANTHROPIC, com.ai.data.ApiFormat.GOOGLE -> true
        com.ai.data.ApiFormat.OPENAI_COMPATIBLE ->
            service.responsesApiPatterns.anyMatches(modelId) ||
                com.ai.data.ModelType.infer(modelId) == com.ai.data.ModelType.RESPONSES
    }

    /** Returns true when (provider, modelId) accepts a thinking /
     *  reasoning_effort parameter. Layered the same way as
     *  [isVisionCapable] / [isWebSearchCapable]: user override →
     *  manual override → precomputed snapshot → provider-native
     *  /models response → LiteLLM → models.dev → naming heuristic.
     *  The dispatcher will read this to decide whether to attach a
     *  thinking / reasoning_effort block on outbound requests
     *  (separate change). */
    fun isReasoningCapable(service: AppService, modelId: String): Boolean {
        val cfg = getProvider(service)
        if (modelId in cfg.reasoningModels) return true
        if (modelTypeOverrides.any { it.providerId == service.id && it.modelId == modelId && it.supportsReasoning }) return true
        if (modelId in cfg.reasoningCapableComputed) return true
        return computeReasoningCapableSlow(service, modelId)
    }

    private fun computeReasoningCapableSlow(service: AppService, modelId: String): Boolean {
        // Tier 4: provider's own /models self-report (Anthropic
        // capabilities.thinking.supported, Gemini top-level thinking,
        // Mistral capabilities.reasoning, xAI/OpenRouter
        // supported_parameters).
        getProvider(service).modelCapabilities[modelId]?.supportsReasoning?.let { return it }
        // Tier 5: LiteLLM supports_reasoning / supports_max_reasoning_effort.
        com.ai.data.PricingCache.liteLLMSupportsReasoning(service, modelId)?.let { return it }
        // Tier 6: models.dev `reasoning` boolean.
        com.ai.data.PricingCache.modelsDevSupportsReasoning(service, modelId)?.let { return it }
        return com.ai.data.ModelType.inferReasoning(service, modelId)
    }

    /** Distinct from [isReasoningCapable]: capability says "the model
     *  performs reasoning" (drives the 🧠 badge); this says "the model
     *  exposes a controllable `reasoning_effort` / `thinking` parameter
     *  we can attach to the request". xAI ships several
     *  reasoning-capable but always-on models (grok-4.3,
     *  grok-4.20-multi-agent-0309, grok-code-fast-1) that 400 when
     *  `reasoning_effort` is sent, so for
     *  [ModelType.externalReasoningSignalUntrusted] providers we narrow
     *  via [ModelType.inferAcceptsReasoningEffortParam]. */
    fun acceptsReasoningEffortParam(service: AppService, modelId: String): Boolean {
        if (!isReasoningCapable(service, modelId)) return false
        if (com.ai.data.ModelType.externalReasoningSignalUntrusted(service))
            return com.ai.data.ModelType.inferAcceptsReasoningEffortParam(service, modelId)
        return true
    }

    fun withReasoningCapable(service: AppService, modelId: String, enabled: Boolean): Settings {
        val cfg = getProvider(service)
        val newSet = if (enabled) cfg.reasoningModels + modelId else cfg.reasoningModels - modelId
        return withProvider(service, cfg.copy(reasoningModels = newSet))
    }

    /** Walk every model in [service]'s list, run the slow layered lookup
     *  once per id, and store the resolved booleans in
     *  [ProviderConfig.visionCapableComputed] / [webSearchCapableComputed].
     *  Cheap relative to fetching the model list, dirt-cheap on subsequent
     *  isVisionCapable / isWebSearchCapable calls (single set lookup). */
    fun recomputeCapabilities(service: AppService): Settings {
        val cfg = getProvider(service)
        if (cfg.models.isEmpty()) return this
        val vision = cfg.models.filterTo(mutableSetOf()) { computeVisionCapableSlow(service, it) }
        val web = cfg.models.filterTo(mutableSetOf()) { computeWebSearchCapableSlow(service, it) }
        val reasoning = cfg.models.filterTo(mutableSetOf()) { computeReasoningCapableSlow(service, it) }
        // Snapshot the layered cost lookup so per-row UIs don't re-scan
        // catalogs on every render — same precompute idea, applied to the
        // 2 cost fields. lookupPricing is context-free; PricingCache must
        // have been loaded by an earlier ensureLoadedBlocking / preloadAsync.
        val pricing = cfg.models.associateWith { com.ai.data.PricingCache.lookupPricing(service, it) }
        if (vision == cfg.visionCapableComputed && web == cfg.webSearchCapableComputed
            && reasoning == cfg.reasoningCapableComputed && pricing == cfg.modelPricing) return this
        return withProvider(service, cfg.copy(
            visionCapableComputed = vision, webSearchCapableComputed = web,
            reasoningCapableComputed = reasoning,
            modelPricing = pricing
        ))
    }

    /** Recompute precomputed capability sets for every provider — used after
     *  a LiteLLM or models.dev refresh, where the underlying answers may
     *  have shifted but per-provider model lists haven't. */
    fun recomputeAllCapabilities(): Settings {
        var s: Settings = this
        for (service in AppService.entries) s = s.recomputeCapabilities(service)
        return s
    }

    /** Pre-computed [com.ai.data.PricingCache.ModelPricing] for the
     *  (provider, model) pair, or null when the model isn't in the
     *  provider's stored list (e.g. arbitrary id from the Manual Override
     *  CRUD). Callers should fall back to [com.ai.data.PricingCache.getPricing]
     *  on a null result. Read straight from in-memory state — fast even
     *  inside lazy-list item composables. */
    fun getModelPricing(service: AppService, modelId: String): com.ai.data.PricingCache.ModelPricing? =
        getProvider(service).modelPricing[modelId]

    fun withWebSearchCapable(service: AppService, modelId: String, enabled: Boolean): Settings {
        val cfg = getProvider(service)
        val newSet = if (enabled) cfg.webSearchModels + modelId else cfg.webSearchModels - modelId
        return withProvider(service, cfg.copy(webSearchModels = newSet))
    }

    /**
     * Fan out-pollinate per-provider type labels from OpenRouter's catalog: for any model
     * we currently treat as plain CHAT (the heuristic / unknown default), look up
     * `${service.openRouterName}/${modelId}` in OpenRouter's labeled list and adopt
     * its type if it's something more specific. Native non-chat kinds we already
     * inferred from the provider itself (Cohere endpoints, Gemini methods) are left
     * untouched. Re-call this after any OpenRouter or per-provider fetch.
     */
    fun applyOpenRouterTypes(): Settings {
        val orProvider = AppService.entries.firstOrNull { it.crossProviderModelList } ?: return this
        val orTypes = getProvider(orProvider).modelTypes
        if (orTypes.isEmpty()) return this
        var updated = this
        for (service in AppService.entries) {
            if (service.crossProviderModelList) continue
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

    fun getSystemPromptById(id: String) = systemPrompts.find { it.id == id }
    fun getInternalPromptById(id: String) = internalPrompts.find { it.id == id }
    fun getInternalPromptByName(name: String) = internalPrompts.firstOrNull { it.name.equals(name, ignoreCase = true) }
    fun getExamplePromptById(id: String) = examplePrompts.find { it.id == id }
    fun getParametersById(id: String) = parameters.find { it.id == id }
    fun getParametersByName(name: String) = parameters.find { it.name.equals(name, ignoreCase = true) }

    // ----- Blocked models -----
    /** `"providerId:model"` → reason, for O(1) picker lookups. */
    val blockedReasonByKey: Map<String, String>
        get() = blockedModels.associate { it.key to it.reason }
    fun isBlocked(providerId: String, model: String) =
        blockedModels.any { it.providerId == providerId && it.model == model }
    fun removeBlockedModel(providerId: String, model: String) = copy(
        blockedModels = blockedModels.filterNot { it.providerId == providerId && it.model == model }
    )
    /** Upsert by `(providerId, model)` — incoming wins. */
    fun upsertBlockedModel(bm: BlockedModel) = copy(
        blockedModels = blockedModels.filterNot { it.key == bm.key } + bm
    )
    /** Fold a "Test all models" run into the list: drop every entry the
     *  run actually tested (so passes un-block), then append the run's
     *  failures (so fails block / refresh their reason). Untested
     *  entries are left untouched. */
    fun syncBlockedModelsFromTestRun(failures: List<BlockedModel>, testedKeys: Set<String>) = copy(
        blockedModels = blockedModels.filterNot { it.key in testedKeys } + failures
    )

    // ----- Test-excluded models -----
    /** `"providerId:model"` set, for the O(1) skip-filter in
     *  [com.ai.viewmodel.ModelTestEngine.startRun]. */
    val testExcludedKeys: Set<String>
        get() = testExcludedModels.mapTo(HashSet()) { it.key }
    fun isTestExcluded(providerId: String, model: String) =
        testExcludedModels.any { it.providerId == providerId && it.model == model }
    fun removeTestExcluded(providerId: String, model: String) = copy(
        testExcludedModels = testExcludedModels.filterNot { it.providerId == providerId && it.model == model }
    )
    fun upsertTestExcluded(e: TestExcludedModel) = copy(
        testExcludedModels = testExcludedModels.filterNot { it.key == e.key } + e
    )
    /** Append entries from a test-run sweep, skipping anything whose
     *  key is already present — no clobber, no duplicates. */
    fun addTestExclusionsFromTestRun(extras: List<TestExcludedModel>): Settings {
        if (extras.isEmpty()) return this
        val existing = testExcludedKeys
        val novel = extras.filterNot { it.key in existing }
        if (novel.isEmpty()) return this
        return copy(testExcludedModels = testExcludedModels + novel)
    }

    // ----- Inaccessible models -----
    /** `"providerId:model"` set, for the O(1) skip-filter in the test
     *  engine and the picker. */
    val inaccessibleKeys: Set<String>
        get() = inaccessibleModels.mapTo(HashSet()) { it.key }
    /** `"providerId:model"` → reason map, for surfacing the cause in
     *  the CRUD list rows. */
    val inaccessibleReasonByKey: Map<String, String>
        get() = inaccessibleModels.associate { it.key to it.reason }
    fun isInaccessible(providerId: String, model: String) =
        inaccessibleModels.any { it.providerId == providerId && it.model == model }
    fun removeInaccessibleModel(providerId: String, model: String) = copy(
        inaccessibleModels = inaccessibleModels.filterNot { it.providerId == providerId && it.model == model }
    )
    /** Upsert by `(providerId, model)` — incoming wins. */
    fun upsertInaccessibleModel(m: InaccessibleModel) = copy(
        inaccessibleModels = inaccessibleModels.filterNot { it.key == m.key } + m
    )

    fun getEndpointsForProvider(provider: AppService): List<Endpoint> =
        endpoints[provider]?.ifEmpty { getBuiltInEndpoints(provider) } ?: getBuiltInEndpoints(provider)

    private fun getBuiltInEndpoints(provider: AppService): List<Endpoint> =
        provider.builtInEndpoints.takeIf { it.isNotEmpty() } ?: defaultEndpointForProvider(provider)

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
            // Clear references to the removed agents from internal
            // prompts so they don't keep pointing at IDs that no
            // longer exist. Same "*select" sentinel removeAgent
            // uses.
            internalPrompts = internalPrompts.map {
                if (it.agent in removedAgentIds) it.copy(agent = "*select") else it
            }
        )
    }

    fun removeSystemPrompt(systemPromptId: String) = copy(
        systemPrompts = systemPrompts.filter { it.id != systemPromptId },
        agents = agents.map { if (it.systemPromptId == systemPromptId) it.copy(systemPromptId = null) else it },
        flocks = flocks.map { if (it.systemPromptId == systemPromptId) it.copy(systemPromptId = null) else it },
        swarms = swarms.map { if (it.systemPromptId == systemPromptId) it.copy(systemPromptId = null) else it }
    )

    fun removeInternalPrompt(internalPromptId: String) = copy(
        internalPrompts = internalPrompts.filter { it.id != internalPromptId }
    )

    fun removeExamplePrompt(examplePromptId: String) = copy(
        examplePrompts = examplePrompts.filter { it.id != examplePromptId }
    )

    /** Ensure the "default agents" flock contains an agent for [service].
     *  Called from the per-provider Test button when the test succeeds:
     *  if the user already has a matching agent (by `name == displayName
     *  && provider.id == service.id`) it's just added to the flock if
     *  missing; otherwise a new agent is created with [defaultModel] and
     *  added. The flock itself is created on first use.
     *
     *  Returns the same Settings instance when nothing needs changing,
     *  so callers can compare reference-equally and skip a redundant
     *  save. */
    fun ensureDefaultAgentInFlock(service: AppService, defaultModel: String): Settings {
        val existingAgent = agents.find { it.name == service.id && it.provider.id == service.id }
        val (agentId, withAgent) = if (existingAgent != null) {
            existingAgent.id to this
        } else {
            val newAgent = Agent(
                id = java.util.UUID.randomUUID().toString(),
                name = service.id, provider = service,
                model = defaultModel, apiKey = ""
            )
            newAgent.id to copy(agents = agents + newAgent)
        }
        val flock = withAgent.flocks.find { it.name == DEFAULT_AGENTS_FLOCK_NAME }
        return when {
            flock == null -> withAgent.copy(
                flocks = withAgent.flocks + Flock(
                    id = java.util.UUID.randomUUID().toString(),
                    name = DEFAULT_AGENTS_FLOCK_NAME,
                    agentIds = listOf(agentId)
                )
            )
            agentId !in flock.agentIds -> withAgent.copy(
                flocks = withAgent.flocks.map {
                    if (it.id == flock.id) it.copy(agentIds = it.agentIds + agentId) else it
                }
            )
            else -> withAgent
        }
    }

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
        // Clear references to this agent from internal prompts so a
        // re-pinned prompt doesn't show "DeletedAgent" in the picker
        // (and the picker didn't have a UI path to fix it before
        // because removed agents were already filtered from the
        // active list). Reset to the "*select" sentinel that
        // existing pinning expects to mean "no specific agent".
        internalPrompts = internalPrompts.map {
            if (it.agent == agentId) it.copy(agent = "*select") else it
        }
    )

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
                p.seed ?: acc.seed, p.responseFormatJson || acc.responseFormatJson, p.searchEnabled || acc.searchEnabled,
                // returnCitations defaults to true; combine with AND
                // so an explicit opt-out anywhere in the preset chain
                // is honoured (mirrors AnalysisRepository.mergeParameters).
                p.returnCitations && acc.returnCitations,
                p.searchRecency ?: acc.searchRecency,
                p.webSearchTool || acc.webSearchTool,
                p.reasoningEffort ?: acc.reasoningEffort
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
    // One ReportModel per provider+model. When duplicates collide,
    // an agent-sourced entry (non-null agentId — direct agent or
    // flock member) wins over a bare manual / swarm pick: it
    // carries the agent id, api key and parameter ids. Order
    // follows the first appearance of each key.
    val byKey = LinkedHashMap<String, ReportModel>()
    for (m in models) {
        val existing = byKey[m.deduplicationKey]
        if (existing == null || (existing.agentId == null && m.agentId != null)) {
            byKey[m.deduplicationKey] = m
        }
    }
    return byKey.values.toList()
}

data class UsageStats(
    val provider: AppService, val model: String, val callCount: Int = 0,
    val inputTokens: Long = 0, val outputTokens: Long = 0,
    /** Call type — "report" (default), "rerank", "summarize", or
     *  "compare". Stored per-row so the AI Usage table and CSV export
     *  can split rerank / summarize / compare spend out from the
     *  primary report cost. Existing rows written before this field
     *  landed deserialise to "report". */
    val kind: String = "report",
    /** Number of search units billed by the provider for "rerank"-kind
     *  rows. Cohere bills per search-unit (1 search ≈ 1 query + up to
     *  100 documents), not per token, so the input/output token columns
     *  are zero for these and AI Usage uses searchUnits × perQueryPrice
     *  to surface the actual cost. Always 0 for non-rerank rows. */
    val searchUnits: Long = 0
) {
    val totalTokens: Long get() = inputTokens + outputTokens
    val key: String get() = "${provider.id}::$model::$kind"
}
