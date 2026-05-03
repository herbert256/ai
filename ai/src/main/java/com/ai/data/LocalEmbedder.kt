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
