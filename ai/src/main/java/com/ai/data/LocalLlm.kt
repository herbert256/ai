package com.ai.data

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * On-device LLM via MediaPipe Tasks GenAI (LiteRT under the hood).
 *
 * Models are user-supplied `.task` bundles — most worthwhile ones
 * (Gemma, Phi, Llama) require accepting the model card terms in a
 * browser before download, so the AI Housekeeping → Local LLMs card
 * leads with hand-off links and the SAF "Add LLM from file…" picker
 * rather than direct in-app downloads. Files land in
 * `<filesDir>/local_llms/`.
 *
 * Each [generate] call writes a synthetic [ApiTrace] entry — hostname
 * "local", url "local://generate/<modelFile>" — so on-device LLM
 * traffic shows up on the Trace screen alongside HTTP traces.
 */
object LocalLlm {
    private const val LOCAL_LLMS_DIR = "local_llms"
    private val instances = ConcurrentHashMap<String, LlmInference>()

    fun localLlmsDir(context: Context): File =
        File(context.filesDir, LOCAL_LLMS_DIR).also { if (!it.exists()) it.mkdirs() }

    fun availableLlms(context: Context): List<String> =
        localLlmsDir(context).listFiles { f -> f.extension.equals("task", ignoreCase = true) }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            .orEmpty()

    fun llmFile(context: Context, modelName: String): File? {
        val f = File(localLlmsDir(context), "$modelName.task")
        return if (f.exists()) f else null
    }

    /** Hand-off links shown on the Housekeeping card. The user opens
     *  one of these in a browser, accepts the model's terms, downloads
     *  the .task file, and then comes back to import via SAF. */
    val recommendedLinks: List<RecommendedLlm> = listOf(
        RecommendedLlm(
            name = "Gemma 3 1B (Kaggle)",
            url = "https://www.kaggle.com/models/google/gemma-3/tfLite",
            sizeHint = "~530 MB",
            description = "Google's 1B int4 — best balance of quality and speed for phone chat."
        ),
        RecommendedLlm(
            name = "Gemma 3 (HuggingFace community)",
            url = "https://huggingface.co/litert-community",
            sizeHint = "~530 MB - 2.5 GB",
            description = "litert-community has 1B / 4B Gemma 3 .task bundles ready to download."
        ),
        RecommendedLlm(
            name = "Gemma 2 2B (Kaggle)",
            url = "https://www.kaggle.com/models/google/gemma-2/tfLite",
            sizeHint = "~1.5 GB",
            description = "Older but well-tested 2B-it int4."
        ),
        RecommendedLlm(
            name = "Phi-3 / Phi-3.5 mini",
            url = "https://www.kaggle.com/models/microsoft/phi-3",
            sizeHint = "~2 GB",
            description = "Microsoft's 3.8B — strong reasoning for its size, slower than 1B."
        ),
        RecommendedLlm(
            name = "MediaPipe LLM Inference docs",
            url = "https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android",
            sizeHint = null,
            description = "Official model list, conversion guide, performance notes."
        )
    )

    data class RecommendedLlm(
        val name: String,
        val url: String,
        val sizeHint: String?,
        val description: String
    )

    private fun getEngine(context: Context, modelName: String): LlmInference {
        return instances.getOrPut(modelName) {
            val file = llmFile(context, modelName)
                ?: throw IllegalStateException("Local LLM $modelName.task not found in local_llms/")
            // Conservative defaults — keeps memory in check on phones
            // without flagship RAM. The user can re-tune later if we
            // expose advanced options.
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file.absolutePath)
                .setMaxTokens(2048)
                .build()
            LlmInference.createFromOptions(context, options)
        }
    }

    /** Drop the cached engine for [modelName]. Used after the user
     *  removes the .task file. */
    fun release(modelName: String) {
        instances.remove(modelName)?.close()
    }

    fun releaseAll() {
        instances.values.forEach { runCatching { it.close() } }
        instances.clear()
    }

    /** Release every engine and delete every `.task` file under
     *  [localLlmsDir]. Returns the count of files removed. Used by
     *  the housekeeping "clear all configuration" flow. */
    fun clearAll(context: Context): Int {
        releaseAll()
        var removed = 0
        localLlmsDir(context).listFiles { f -> f.extension.equals("task", ignoreCase = true) }
            ?.forEach { if (it.delete()) removed++ }
        return removed
    }

    /** Synchronously generate a response for [prompt]. Records one
     *  trace entry per call. Returns null on failure. The native
     *  LlmInference handle is not thread-safe, so calls are
     *  serialised per-engine — two parallel report agents pointing at
     *  the same `.task` file would otherwise corrupt the runtime
     *  state. */
    fun generate(context: Context, modelName: String, prompt: String): String? {
        val started = System.currentTimeMillis()
        return try {
            val engine = getEngine(context, modelName)
            val out = synchronized(engine) { engine.generateResponse(prompt) }
            recordTrace(modelName, prompt, out, durationMs = System.currentTimeMillis() - started, error = null)
            out
        } catch (e: Exception) {
            android.util.Log.e("LocalLlm", "generate failed: ${e.message}", e)
            recordTrace(modelName, prompt, null, durationMs = System.currentTimeMillis() - started, error = e.message ?: e.javaClass.simpleName)
            null
        }
    }

    private fun recordTrace(modelName: String, prompt: String, response: String?, durationMs: Long, error: String?) {
        if (!ApiTracer.isTracingEnabled) return
        val gson = createAppGson()
        ApiTracer.saveTrace(ApiTrace(
            timestamp = System.currentTimeMillis(),
            hostname = "local",
            reportId = ApiTracer.currentReportId,
            model = modelName,
            category = ApiTracer.currentCategory,
            request = TraceRequest(
                url = "local://generate/$modelName",
                method = "POST",
                headers = emptyMap(),
                body = gson.toJson(mapOf("promptPreview" to prompt.take(500), "promptChars" to prompt.length))
            ),
            response = TraceResponse(
                statusCode = if (error == null) 200 else 500,
                headers = emptyMap(),
                body = gson.toJson(mapOf(
                    "responsePreview" to (response?.take(500)),
                    "responseChars" to (response?.length ?: 0),
                    "durationMs" to durationMs,
                    "error" to error
                ))
            )
        ))
    }
}
