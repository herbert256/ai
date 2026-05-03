package com.ai.data

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Type of source stored in a knowledge base. Drives which extractor
 *  the indexing pipeline picks. */
enum class KnowledgeSourceType { TEXT, MARKDOWN, PDF, DOCX, ODT, XLSX, ODS, CSV, IMAGE, URL }

/** A single source ingested into a knowledge base. Persisted on the
 *  KB's manifest; the chunk array for it lives in a sibling file. */
data class KnowledgeSource(
    val id: String,
    val type: KnowledgeSourceType,
    /** Display label — file name without extension, or the URL host
     *  for web pages. */
    val name: String,
    /** Where the source came from: a SAF Uri string for files or the
     *  full URL for web pages. Stored so re-index can refetch. */
    val origin: String,
    val addedAt: Long,
    val chunkCount: Int = 0,
    val charCount: Int = 0,
    /** Set when the most recent index attempt failed. UI can surface
     *  this on the source row. */
    val errorMessage: String? = null
)

/** Metadata for a knowledge base. The chunks live next door under
 *  filesDir/knowledge/<id>/chunks/. */
data class KnowledgeBase(
    val id: String,
    val name: String,
    /** Embedder identity. "LOCAL" plus a model name → on-device
     *  TextEmbedder via [LocalEmbedder]. Otherwise the AppService.id
     *  + model name → remote /v1/embeddings call via
     *  [AnalysisRepository.embed]. The KB's chunks are valid only
     *  for queries from the same embedder, so this is fixed at
     *  creation time and changing it requires deleting + recreating. */
    val embedderProviderId: String,
    val embedderModel: String,
    val embeddingDim: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val sources: List<KnowledgeSource> = emptyList()
) {
    val totalChunks: Int get() = sources.sumOf { it.chunkCount }
    val totalChars: Long get() = sources.sumOf { it.charCount.toLong() }
}

/** One chunk of indexed text + its embedding vector. Embedding is a
 *  primitive FloatArray rather than List<Double> — for a typical KB
 *  with thousand-dim vectors that's a ~6× heap reduction over a boxed
 *  List<Double> (4 bytes per dim vs 24 bytes including object header
 *  and reference), and for cosine ranking the float-precision math
 *  is indistinguishable from double. JSON storage is unchanged: Gson
 *  reads any numeric array into FloatArray, so existing chunk files
 *  written with full-double precision keep working — they're just
 *  truncated to ~7 significant digits on read. */
data class KnowledgeChunk(
    val id: String,
    val sourceId: String,
    val ordinal: Int,
    val text: String,
    val embedding: FloatArray
)

/**
 * Per-KB directory layout:
 *
 *   filesDir/knowledge/<kbId>/
 *     manifest.json    — KnowledgeBase + sources
 *     chunks/
 *       <sourceId>.json — JSON array of KnowledgeChunk for that source
 *
 * One JSON file per source keeps add / remove / re-index cheap (no
 * full-KB rewrite for a single-source change), while loading a whole
 * KB for retrieval still scans only the directory.
 */
object KnowledgeStore {
    private const val ROOT_DIR = "knowledge"
    private const val MANIFEST = "manifest.json"
    private const val CHUNKS_DIR = "chunks"
    private val gson = createAppGson()
    private val lock = ReentrantLock()
    @Volatile private var rootDir: File? = null

    fun init(context: Context) {
        if (rootDir == null) lock.withLock {
            if (rootDir == null) {
                val dir = File(context.filesDir, ROOT_DIR)
                if (!dir.exists()) dir.mkdirs()
                rootDir = dir
            }
        }
    }

    fun listKnowledgeBases(context: Context): List<KnowledgeBase> {
        init(context)
        val dir = rootDir ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { kbDir ->
                runCatching { loadKb(kbDir) }.getOrNull()
            }
            ?.sortedByDescending { it.createdAt }
            .orEmpty()
    }

    fun loadKnowledgeBase(context: Context, kbId: String): KnowledgeBase? {
        init(context)
        val kbDir = kbDirOrNull(kbId) ?: return null
        return runCatching { loadKb(kbDir) }.getOrNull()
    }

    fun createKnowledgeBase(context: Context, name: String, embedderProviderId: String, embedderModel: String): KnowledgeBase {
        init(context)
        val kb = KnowledgeBase(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Knowledge base" },
            embedderProviderId = embedderProviderId,
            embedderModel = embedderModel
        )
        val dir = File(rootDir!!, kb.id).also { it.mkdirs() }
        File(dir, CHUNKS_DIR).mkdirs()
        saveManifest(dir, kb)
        return kb
    }

    fun renameKnowledgeBase(context: Context, kbId: String, newName: String) {
        init(context)
        val kbDir = kbDirOrNull(kbId) ?: return
        val current = loadKb(kbDir)
        saveManifest(kbDir, current.copy(name = newName.ifBlank { current.name }))
    }

    fun deleteKnowledgeBase(context: Context, kbId: String) {
        init(context)
        kbDirOrNull(kbId)?.deleteRecursively()
    }

    /** Persist a freshly indexed source's chunks + add the source to
     *  the KB manifest. If a source with [source.id] already exists,
     *  its chunks file is replaced (re-index). */
    fun saveSource(context: Context, kbId: String, source: KnowledgeSource, chunks: List<KnowledgeChunk>, embeddingDim: Int) {
        init(context)
        val kbDir = kbDirOrNull(kbId) ?: return
        lock.withLock {
            val chunksDir = File(kbDir, CHUNKS_DIR).also { it.mkdirs() }
            val chunkFile = File(chunksDir, "${source.id}.json")
            chunkFile.writeText(gson.toJson(chunks))
            val current = loadKb(kbDir)
            val replaced = current.sources.filter { it.id != source.id } + source.copy(
                chunkCount = chunks.size,
                charCount = chunks.sumOf { it.text.length }
            )
            val newDim = if (current.embeddingDim == 0) embeddingDim else current.embeddingDim
            saveManifest(kbDir, current.copy(sources = replaced, embeddingDim = newDim))
        }
    }

    fun deleteSource(context: Context, kbId: String, sourceId: String) {
        init(context)
        val kbDir = kbDirOrNull(kbId) ?: return
        lock.withLock {
            File(kbDir, "$CHUNKS_DIR/$sourceId.json").delete()
            val current = loadKb(kbDir)
            saveManifest(kbDir, current.copy(sources = current.sources.filter { it.id != sourceId }))
        }
    }

    /** Stream every chunk for [kbId] across all its sources through
     *  [block]. Per-source files are parsed one at a time and the
     *  decoded array goes out of scope between files, so peak heap is
     *  bounded by the largest source's chunks rather than the whole
     *  KB. Retrieval uses this to do a bounded-heap top-K cosine
     *  sweep without ever materialising the full chunk list. */
    fun forEachChunk(context: Context, kbId: String, block: (KnowledgeChunk) -> Unit) {
        init(context)
        val kbDir = kbDirOrNull(kbId) ?: return
        val chunksDir = File(kbDir, CHUNKS_DIR)
        if (!chunksDir.exists()) return
        chunksDir.listFiles { f -> f.extension == "json" }?.forEach { f ->
            runCatching {
                val arr = gson.fromJson(f.readText(), Array<KnowledgeChunk>::class.java)
                arr.forEach(block)
            }
        }
    }

    private fun kbDirOrNull(kbId: String): File? {
        val dir = File(rootDir ?: return null, kbId)
        return if (dir.isDirectory) dir else null
    }

    private fun loadKb(kbDir: File): KnowledgeBase {
        val text = File(kbDir, MANIFEST).readText()
        return gson.fromJson(text, KnowledgeBase::class.java)
    }

    private fun saveManifest(kbDir: File, kb: KnowledgeBase) {
        File(kbDir, MANIFEST).writeText(gson.toJson(kb))
    }
}
