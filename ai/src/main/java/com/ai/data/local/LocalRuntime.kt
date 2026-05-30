package com.ai.data.local

/**
 * Read-only snapshot of the on-device runtime for the Live Dashboard's
 * Local-runtime card — which models [LocalLlm] / [LocalEmbedder] have loaded
 * into memory and which one (if any) is running right now.
 */
object LocalRuntime {

    data class Snapshot(
        val llmLoaded: List<String> = emptyList(),
        val llmGenerating: String? = null,
        val embedderLoaded: List<String> = emptyList(),
        val embedding: String? = null,
    ) {
        /** True when there's anything to show (loaded or active). */
        val active: Boolean
            get() = llmLoaded.isNotEmpty() || embedderLoaded.isNotEmpty() ||
                llmGenerating != null || embedding != null
    }

    fun snapshot(): Snapshot = Snapshot(
        llmLoaded = LocalLlm.loadedModelNames(),
        llmGenerating = LocalLlm.currentlyGenerating,
        embedderLoaded = LocalEmbedder.loadedModelNames(),
        embedding = LocalEmbedder.currentlyEmbedding,
    )
}
