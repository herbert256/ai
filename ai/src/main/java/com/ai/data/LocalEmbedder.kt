package com.ai.data

import android.content.Context
import com.google.mediapipe.tasks.components.containers.Embedding
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * On-device embedding via MediaPipe Tasks (LiteRT under the hood).
 *
 * Models live as user-supplied .tflite files under
 * <filesDir>/local_models/. The Local Semantic Search screen lists
 * whatever's there and lets the user add more via a file picker.
 *
 * Each [embed] call also writes a synthetic [ApiTrace] entry —
 * hostname "local", url "local://embed/<modelFile>" — so local calls
 * surface on the Trace screen alongside HTTP traces. Tracing respects
 * the same global flag every other call site uses.
 */
object LocalEmbedder {
    private const val LOCAL_MODELS_DIR = "local_models"
    private val instances = ConcurrentHashMap<String, TextEmbedder>()

    /** A model that can be downloaded directly into [localModelsDir].
     *  Only models with proper MediaPipe Tasks metadata baked into the
     *  .tflite belong here — the runtime refuses to load otherwise. */
    data class DownloadableModel(
        /** Filename stem (no extension) used as the on-disk name and
         *  the picker entry. */
        val name: String,
        val displayName: String,
        val url: String,
        val sizeMbHint: Int,
        val description: String
    )

    /** MediaPipe Tasks officially publishes only two text-embedder
     *  models with the metadata the runtime needs. Anything more
     *  exotic has to come through the SAF "Add model from file" path
     *  with its metadata stamped via MediaPipe Model Maker. */
    val downloadable: List<DownloadableModel> = listOf(
        DownloadableModel(
            name = "universal_sentence_encoder_lite",
            displayName = "Universal Sentence Encoder Lite",
            url = "https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/latest/universal_sentence_encoder.tflite",
            sizeMbHint = 25,
            description = "Multilingual general-purpose. Best default for most text."
        ),
        DownloadableModel(
            name = "average_word_embedder",
            displayName = "Average Word Embedder",
            url = "https://storage.googleapis.com/mediapipe-models/text_embedder/average_word_embedder/float32/latest/average_word_embedder.tflite",
            sizeMbHint = 5,
            description = "Tiny + fast English embedder. Lower quality, near-instant."
        )
    )

    /** Default points at the first entry in [downloadable]. */
    const val DEFAULT_MODEL_NAME = "universal_sentence_encoder_lite"
    val DEFAULT_MODEL_DISPLAY_NAME: String
        get() = downloadable.first { it.name == DEFAULT_MODEL_NAME }.displayName

    fun isInstalled(context: Context, modelName: String): Boolean =
        File(localModelsDir(context), "$modelName.tflite").exists()

    fun isDefaultModelInstalled(context: Context): Boolean = isInstalled(context, DEFAULT_MODEL_NAME)

    /** Stream a [DownloadableModel] into [localModelsDir] and record a
     *  synthetic ApiTrace entry so the download surfaces on the Trace
     *  screen alongside HTTP traces. [onProgress] reports
     *  (bytesDownloaded, totalBytes); total may be -1 if the server
     *  didn't send Content-Length. Returns true on success. */
    fun download(
        context: Context,
        spec: DownloadableModel,
        onProgress: (Long, Long) -> Unit
    ): Boolean {
        val started = System.currentTimeMillis()
        val target = File(localModelsDir(context), "${spec.name}.tflite")
        val tmp = File(target.parentFile, "${target.name}.part")
        return try {
            val url = java.net.URL(spec.url)
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                requestMethod = "GET"
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var soFar = 0L
                    while (input.read(buf).also { read = it } > 0) {
                        output.write(buf, 0, read)
                        soFar += read
                        onProgress(soFar, total)
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw java.io.IOException("Could not move ${tmp.name} into place")
            }
            recordDownloadTrace(spec, target.length(), System.currentTimeMillis() - started, error = null)
            true
        } catch (e: Exception) {
            android.util.Log.e("LocalEmbedder", "model ${spec.name} download failed: ${e.message}", e)
            tmp.delete()
            recordDownloadTrace(spec, bytes = -1, durationMs = System.currentTimeMillis() - started, error = e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /** Backwards-compat shim — same as calling [download] with the
     *  default spec. */
    fun downloadDefaultModel(context: Context, onProgress: (Long, Long) -> Unit): Boolean =
        download(context, downloadable.first { it.name == DEFAULT_MODEL_NAME }, onProgress)

    private fun recordDownloadTrace(spec: DownloadableModel, bytes: Long, durationMs: Long, error: String?) {
        if (!ApiTracer.isTracingEnabled) return
        val gson = createAppGson()
        val host = runCatching { java.net.URL(spec.url).host }.getOrDefault("download")
        ApiTracer.saveTrace(ApiTrace(
            timestamp = System.currentTimeMillis(),
            hostname = host,
            reportId = ApiTracer.currentReportId,
            model = spec.name,
            category = "Local model download",
            request = TraceRequest(
                url = spec.url,
                method = "GET",
                headers = emptyMap(),
                body = null
            ),
            response = TraceResponse(
                statusCode = if (error == null) 200 else 500,
                headers = emptyMap(),
                body = gson.toJson(mapOf("bytes" to bytes, "durationMs" to durationMs, "error" to error))
            )
        ))
    }

    fun localModelsDir(context: Context): File =
        File(context.filesDir, LOCAL_MODELS_DIR).also { if (!it.exists()) it.mkdirs() }

    /** Names of every .tflite file in [localModelsDir] (without
     *  extension). Drives the Local Semantic Search picker. */
    fun availableModels(context: Context): List<String> =
        localModelsDir(context).listFiles { f -> f.extension.equals("tflite", ignoreCase = true) }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            .orEmpty()

    /** Resolve [modelName] (no extension) to its file path. Returns
     *  null when the file isn't present in [localModelsDir]. */
    fun modelFile(context: Context, modelName: String): File? {
        val f = File(localModelsDir(context), "$modelName.tflite")
        return if (f.exists()) f else null
    }

    /** Lazily build (and cache) a [TextEmbedder] for [modelName]. The
     *  underlying native runtime keeps memory live while the embedder
     *  exists, so we hold one instance per model and reuse it across
     *  embed calls. */
    private fun getEmbedder(context: Context, modelName: String): TextEmbedder {
        return instances.getOrPut(modelName) {
            val file = modelFile(context, modelName)
                ?: throw IllegalStateException("Local model $modelName.tflite not found in local_models/")
            val options = TextEmbedder.TextEmbedderOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(file.absolutePath).build())
                .setL2Normalize(true)
                .build()
            TextEmbedder.createFromOptions(context, options)
        }
    }

    /** Drop the cached embedder for [modelName] (if any). Used after a
     *  user removes the .tflite file. */
    fun release(modelName: String) {
        instances.remove(modelName)?.close()
    }

    fun releaseAll() {
        instances.values.forEach { runCatching { it.close() } }
        instances.clear()
    }

    /** Release every embedder and delete every `.tflite` file under
     *  [localModelsDir]. Returns the count of files removed. Used by
     *  the housekeeping "clear all configuration" flow. */
    fun clearAll(context: Context): Int {
        releaseAll()
        var removed = 0
        localModelsDir(context).listFiles { f -> f.extension.equals("tflite", ignoreCase = true) }
            ?.forEach { if (it.delete()) removed++ }
        return removed
    }

    /** Run the model on each input string. Records one trace entry per
     *  embed-batch (input array → output dims) so the Trace screen
     *  shows local calls alongside HTTP. Returns null on failure.
     *  Output type is `List<List<Double>>` to match the rest of the
     *  embedding pipeline ([EmbeddingsStore.put] / [cosine]). */
    fun embed(context: Context, modelName: String, inputs: List<String>): List<List<Double>>? {
        if (inputs.isEmpty()) return emptyList()
        val started = System.currentTimeMillis()
        return try {
            val embedder = getEmbedder(context, modelName)
            val out = inputs.map { input ->
                val r = embedder.embed(input)
                val embedding: Embedding = r.embeddingResult().embeddings().first()
                val floats = embedding.floatEmbedding()
                List(floats.size) { i -> floats[i].toDouble() }
            }
            recordLocalTrace(modelName, inputs, outputDims = out.firstOrNull()?.size ?: 0,
                durationMs = System.currentTimeMillis() - started, error = null)
            out
        } catch (e: Exception) {
            android.util.Log.e("LocalEmbedder", "embed failed: ${e.message}", e)
            recordLocalTrace(modelName, inputs, outputDims = 0,
                durationMs = System.currentTimeMillis() - started, error = e.message ?: e.javaClass.simpleName)
            null
        }
    }

    private fun recordLocalTrace(modelName: String, inputs: List<String>, outputDims: Int, durationMs: Long, error: String?) {
        if (!ApiTracer.isTracingEnabled) return
        // Truncate the inputs for the request body so a 50-row batch
        // doesn't blow the trace JSON up. Keep counts + a short
        // preview of each so the user can identify the call.
        val preview = inputs.map { it.take(120) }
        val body = createAppGson().toJson(mapOf("count" to inputs.size, "previews" to preview))
        val responseBody = createAppGson().toJson(mapOf(
            "outputDims" to outputDims,
            "durationMs" to durationMs,
            "error" to error
        ))
        ApiTracer.saveTrace(ApiTrace(
            timestamp = System.currentTimeMillis(),
            hostname = "local",
            reportId = ApiTracer.currentReportId,
            model = modelName,
            category = ApiTracer.currentCategory,
            request = TraceRequest(
                url = "local://embed/$modelName",
                method = "POST",
                headers = emptyMap(),
                body = body
            ),
            response = TraceResponse(
                statusCode = if (error == null) 200 else 500,
                headers = emptyMap(),
                body = responseBody
            )
        ))
    }
}
