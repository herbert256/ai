package com.ai.data

import android.content.Context
import android.net.Uri
import com.ai.model.Settings
import java.util.UUID

/**
 * Index + retrieve over a [KnowledgeBase].
 *
 * Indexing pipeline: extract text → chunk → embed (local or remote
 * depending on the KB's [KnowledgeBase.embedderProviderId]) →
 * persist via [KnowledgeStore.saveSource]. Retrieval embeds the
 * query, sweeps every chunk in the requested KBs, returns the top-K
 * by cosine similarity within an optional token budget.
 *
 * The embedder identity is fixed per KB so query and chunk vectors
 * always live in the same space; trying to retrieve with a different
 * embedder would silently mis-rank.
 */
object KnowledgeService {
    /** A retrieval hit — the chunk plus its similarity score and KB
     *  context. Used to build the injected context block. */
    data class Hit(
        val kbId: String,
        val kbName: String,
        val sourceName: String,
        val text: String,
        val score: Double
    )

    /** Status callback for long-running indexing. The UI surfaces
     *  the message verbatim. */
    fun interface IndexProgress {
        fun onProgress(message: String, done: Int, total: Int)
    }

    suspend fun indexFile(
        context: Context,
        repository: AnalysisRepository,
        aiSettings: Settings,
        kbId: String,
        type: KnowledgeSourceType,
        uri: Uri,
        displayName: String,
        progress: IndexProgress = IndexProgress { _, _, _ -> }
    ): Result<KnowledgeSource> = runCatching {
        // Persist the file's bytes locally first — SAF Uris come with
        // a permission that won't survive a process restart, and we
        // want re-index after a relaunch to keep working without
        // asking the user to re-pick the file.
        val storedUri = persistSourceLocally(context, kbId, uri, displayName)
        progress.onProgress("Extracting…", 0, 1)
        val text = KnowledgeExtractors.extract(context, type, storedUri.toString())
        runIndex(context, repository, aiSettings, kbId,
            type = type,
            displayName = displayName,
            origin = storedUri.toString(),
            text = text,
            progress = progress
        )
    }

    suspend fun indexUrl(
        context: Context,
        repository: AnalysisRepository,
        aiSettings: Settings,
        kbId: String,
        url: String,
        progress: IndexProgress = IndexProgress { _, _, _ -> }
    ): Result<KnowledgeSource> = runCatching {
        progress.onProgress("Fetching…", 0, 1)
        val text = KnowledgeExtractors.extract(context, KnowledgeSourceType.URL, url)
        val displayName = runCatching { java.net.URL(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
        runIndex(context, repository, aiSettings, kbId,
            type = KnowledgeSourceType.URL,
            displayName = displayName,
            origin = url,
            text = text,
            progress = progress
        )
    }

    /** Re-extract + re-embed an existing source. Useful after
     *  picking a new embedder model — the chunks file gets replaced
     *  in place, manifest entry updated. */
    suspend fun reindexSource(
        context: Context,
        repository: AnalysisRepository,
        aiSettings: Settings,
        kbId: String,
        source: KnowledgeSource,
        progress: IndexProgress = IndexProgress { _, _, _ -> }
    ): Result<KnowledgeSource> = runCatching {
        progress.onProgress("Re-extracting…", 0, 1)
        val text = KnowledgeExtractors.extract(context, source.type, source.origin)
        runIndex(context, repository, aiSettings, kbId,
            type = source.type,
            displayName = source.name,
            origin = source.origin,
            text = text,
            progress = progress,
            existingSourceId = source.id
        )
    }

    private suspend fun runIndex(
        context: Context,
        repository: AnalysisRepository,
        aiSettings: Settings,
        kbId: String,
        type: KnowledgeSourceType,
        displayName: String,
        origin: String,
        text: String,
        progress: IndexProgress,
        existingSourceId: String? = null
    ): KnowledgeSource {
        val kb = KnowledgeStore.loadKnowledgeBase(context, kbId)
            ?: error("Knowledge base $kbId not found")
        val sourceId = existingSourceId ?: UUID.randomUUID().toString()
        val pieces = KnowledgeChunker.chunk(text)
        if (pieces.isEmpty()) {
            // Empty text → save a zero-chunk source row so the user
            // sees the failed extraction in the list rather than
            // silent disappearance.
            val src = KnowledgeSource(sourceId, type, displayName, origin, System.currentTimeMillis(), 0, 0,
                errorMessage = "No text extracted from source")
            KnowledgeStore.saveSource(context, kbId, src, emptyList(), embeddingDim = kb.embeddingDim)
            return src
        }

        // Embed in batches. Local does one call per text internally;
        // remote /v1/embeddings can take up to ~50 inputs per call.
        val total = pieces.size
        val vectors = mutableListOf<List<Double>>()
        val batchSize = if (kb.embedderProviderId == "LOCAL") 1 else 32
        var done = 0
        for (batch in pieces.chunked(batchSize)) {
            progress.onProgress("Embedding $done / $total…", done, total)
            val out = if (kb.embedderProviderId == "LOCAL") {
                LocalEmbedder.embed(context, kb.embedderModel, batch)
            } else {
                val service = AppService.findById(kb.embedderProviderId)
                    ?: error("Provider ${kb.embedderProviderId} not configured")
                val apiKey = aiSettings.getApiKey(service)
                if (apiKey.isBlank()) error("No API key set for ${service.displayName}")
                repository.embed(service, apiKey, kb.embedderModel, batch)
            } ?: error("Embedder failed on batch of ${batch.size}")
            vectors.addAll(out)
            done += batch.size
        }
        progress.onProgress("Saving…", total, total)

        val embeddingDim = vectors.firstOrNull()?.size ?: 0
        val chunks = pieces.mapIndexed { i, t ->
            KnowledgeChunk(
                id = UUID.randomUUID().toString(),
                sourceId = sourceId,
                ordinal = i,
                text = t,
                embedding = FloatArray(vectors[i].size) { idx -> vectors[i][idx].toFloat() }
            )
        }
        val src = KnowledgeSource(
            id = sourceId, type = type, name = displayName, origin = origin,
            addedAt = System.currentTimeMillis(),
            chunkCount = chunks.size,
            charCount = chunks.sumOf { it.text.length },
            errorMessage = null
        )
        KnowledgeStore.saveSource(context, kbId, src, chunks, embeddingDim)
        return src
    }

    /** Embed [query] using the embedder of the *first* KB in
     *  [kbIds] (all attached KBs must share an embedder; the UI
     *  enforces that), then sweep every chunk across the listed
     *  KBs and return the top hits whose total text fits within
     *  [maxContextChars]. */
    suspend fun retrieve(
        context: Context,
        repository: AnalysisRepository,
        aiSettings: Settings,
        kbIds: List<String>,
        query: String,
        topK: Int = 8,
        maxContextChars: Int = 8000
    ): List<Hit> {
        if (kbIds.isEmpty() || query.isBlank()) return emptyList()
        val kbs = kbIds.mapNotNull { KnowledgeStore.loadKnowledgeBase(context, it) }
        if (kbs.isEmpty()) return emptyList()
        val first = kbs.first()
        // Validate embedder agreement — silent mis-rank is the
        // worst failure mode here, fail loud instead.
        val mismatch = kbs.firstOrNull { it.embedderProviderId != first.embedderProviderId || it.embedderModel != first.embedderModel }
        if (mismatch != null) {
            android.util.Log.w("KnowledgeService", "Embedder mismatch across attached KBs (${first.name} vs ${mismatch.name}); using ${first.name}'s")
        }

        val queryVecRaw = if (first.embedderProviderId == "LOCAL") {
            LocalEmbedder.embed(context, first.embedderModel, listOf(query))?.firstOrNull()
        } else {
            val service = AppService.findById(first.embedderProviderId) ?: return emptyList()
            val apiKey = aiSettings.getApiKey(service)
            if (apiKey.isBlank()) return emptyList()
            repository.embed(service, apiKey, first.embedderModel, listOf(query))?.firstOrNull()
        } ?: return emptyList()
        // Convert once to match the chunk-side FloatArray representation; the
        // primitive-array cosine path is the hot loop now.
        val queryVec = FloatArray(queryVecRaw.size) { idx -> queryVecRaw[idx].toFloat() }

        // Streaming cosine over every chunk in every selected KB
        // that shares the embedder. Mis-matched KBs are dropped to
        // avoid garbage rankings. Bounded min-heap of size topK*2
        // keeps the top candidates without ever materialising all
        // chunks — peak heap is just the heap itself plus one chunk
        // mid-iteration, regardless of total KB size.
        data class Scored(val hit: Hit, val score: Double)
        val cap = topK * 2
        val heap = java.util.PriorityQueue<Scored>(cap + 1, compareBy { it.score })
        for (kb in kbs) {
            if (kb.embedderProviderId != first.embedderProviderId || kb.embedderModel != first.embedderModel) continue
            val sourceById = kb.sources.associateBy { it.id }
            KnowledgeStore.forEachChunk(context, kb.id) { c ->
                val sim = EmbeddingsStore.cosine(queryVec, c.embedding)
                val src = sourceById[c.sourceId]?.name ?: "?"
                val candidate = Scored(Hit(kb.id, kb.name, src, c.text, sim), sim)
                if (heap.size < cap) heap.offer(candidate)
                else if (sim > heap.peek().score) {
                    heap.poll(); heap.offer(candidate)
                }
            }
        }
        // Top-K + token budget. Heap iteration order is undefined, so
        // sort the survivors descending, then walk taking chunks until
        // we'd exceed maxContextChars. That way a single huge chunk
        // can't blow the LLM's context window.
        val sorted = heap.sortedByDescending { it.score }
        val out = mutableListOf<Hit>()
        var charsSoFar = 0
        for (s in sorted) {
            if (s.score <= 0.0) break
            if (charsSoFar + s.hit.text.length > maxContextChars) continue
            out += s.hit
            charsSoFar += s.hit.text.length
            if (out.size >= topK) break
        }
        return out
    }

    /** Format [hits] into a system-message context block ready to be
     *  prepended to the user's prompt. Returns an empty string when
     *  [hits] is empty so callers can unconditionally concatenate. */
    fun formatContextBlock(hits: List<Hit>): String {
        if (hits.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("<context>\n")
        sb.append("Below are excerpts from the user's attached knowledge that may be relevant. ")
        sb.append("Use them when answering the user's question; cite the source name when you do.\n\n")
        hits.forEachIndexed { i, h ->
            sb.append("[${i + 1}] ").append(h.kbName).append(" / ").append(h.sourceName).append('\n')
            sb.append(h.text).append("\n\n")
        }
        sb.append("</context>")
        return sb.toString()
    }

    /** Copy a SAF Uri's bytes into filesDir/knowledge/<kbId>/files/
     *  so subsequent extractions (and re-indexing across launches)
     *  don't depend on the original SAF permission. Returns a
     *  file:// Uri pointing at the copy. */
    private fun persistSourceLocally(context: Context, kbId: String, uri: Uri, displayName: String): Uri {
        val safe = displayName.replace(Regex("[^A-Za-z0-9._-]+"), "_")
        val unique = "${System.currentTimeMillis()}_$safe"
        val dir = java.io.File(context.filesDir, "knowledge/$kbId/files").also { it.mkdirs() }
        val target = java.io.File(dir, unique)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        } ?: error("Could not open $uri")
        return Uri.fromFile(target)
    }
}
