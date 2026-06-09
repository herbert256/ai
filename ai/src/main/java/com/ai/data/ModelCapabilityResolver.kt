package com.ai.data

import com.ai.model.SettingsHolder

/**
 * One home for the dispatch capability question "does this (service, model)
 * accept a controllable `reasoning_effort` / `thinking` parameter?" —
 * previously answered inline by `isReasoningCapableForDispatch`. Same logic
 * (a published [SettingsHolder] reference wins; otherwise a catalog-only
 * fallback chain with the xAI always-on gate inlined), but it now also reports
 * WHICH layer decided, so UI / dispatch / replay can share the resolver and an
 * "explain this state" surface can name the reason (audit P02).
 *
 * Behaviour is identical to the previous inline chain — this is a relocation
 * plus a source tag, not a policy change.
 */
object ModelCapabilityResolver {

    /** The layer that settled the reasoning-effort-param answer. */
    enum class Source {
        /** A published Settings reference answered — the authoritative runtime
         *  path (user override / manual override / precomputed snapshot /
         *  provider self-report / catalog all collapse into this). */
        SETTINGS,
        /** No Settings published; LiteLLM catalog reported reasoning support. */
        CATALOG_LITELLM,
        /** No Settings published; models.dev catalog reported reasoning support. */
        CATALOG_MODELS_DEV,
        /** No Settings published; resolved by the model-naming heuristic. */
        HEURISTIC,
        /** Reasoning-capable, but the provider's reasoning signal is untrusted
         *  (xAI ships always-on models that reject the param), so acceptance was
         *  narrowed by [ModelType.inferAcceptsReasoningEffortParam]. */
        PROVIDER_NARROWED,
        /** Not reasoning-capable by any layer. */
        NOT_CAPABLE
    }

    data class Resolution(val accepted: Boolean, val source: Source)

    /** Definitive dispatch check plus the deciding layer. */
    fun reasoningEffortParam(service: AppService, model: String): Resolution {
        SettingsHolder.current?.let {
            return Resolution(it.acceptsReasoningEffortParam(service, model), Source.SETTINGS)
        }
        // No Settings reference (early startup, unit tests): catalog-only chain.
        // A non-null `false` from a tier stops the chain, exactly like the `?:`
        // fallback it replaces.
        val capable: Pair<Boolean, Source> =
            PricingCache.liteLLMSupportsReasoning(service, model)?.let { it to Source.CATALOG_LITELLM }
                ?: PricingCache.modelsDevSupportsReasoning(service, model)?.let { it to Source.CATALOG_MODELS_DEV }
                ?: (ModelType.inferReasoning(service, model) to Source.HEURISTIC)
        if (!capable.first) return Resolution(false, Source.NOT_CAPABLE)
        if (ModelType.externalReasoningSignalUntrusted(service)) {
            val narrowed = ModelType.inferAcceptsReasoningEffortParam(service, model)
            return Resolution(narrowed, if (narrowed) Source.PROVIDER_NARROWED else Source.NOT_CAPABLE)
        }
        return Resolution(true, capable.second)
    }

    /** Boolean-only shortcut for the dispatch hot path. */
    fun acceptsReasoningEffortParam(service: AppService, model: String): Boolean =
        reasoningEffortParam(service, model).accepted
}
