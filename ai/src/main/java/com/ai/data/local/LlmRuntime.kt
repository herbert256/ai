package com.ai.data.local

import android.content.Context
import com.ai.data.ApiTrace
import com.ai.data.ApiTracer
import com.ai.data.AppLog
import com.ai.data.TraceRequest
import com.ai.data.TraceResponse
import com.ai.data.createAppGson
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Manages the MediaPipe LLM Inference native runtime
 * (`libllm_inference_engine_jni.so`). We deliberately do **not** ship
 * this `.so` in the APK — at 26 MB for arm64-v8a it inflates the
 * baseline install for every user, including those who never run a
 * local model. Instead the user downloads it on demand from
 * AI Setup → Local LLMs.
 *
 * Download source is the MediaPipe AAR on Google Maven; we stream the
 * AAR (it's a zip), pluck the arm64-v8a entry, and write it to
 * `<filesDir>/native/`. Subsequent `System.loadLibrary("…")` calls
 * inside the MediaPipe Java code find the library already loaded by
 * the matching name and short-circuit, so we don't need to fork
 * MediaPipe or wire up `nativeLibraryDirectories` reflection.
 *
 * Lifecycle:
 *   - [isInstalled]: file present on disk
 *   - [ensureLoaded]: idempotent `System.load` for the present file
 *   - [download]: HTTP fetch + zip extract + atomic rename
 *   - [delete]: remove the on-disk file (process keeps the loaded
 *     copy until restart — System.load can't be undone)
 */
object LlmRuntime {
    private const val NATIVE_DIR = "native"
    private const val SO_NAME = "libllm_inference_engine_jni.so"

    /** MediaPipe Tasks GenAI AAR. The version must match the
     *  `tasks-genai` Gradle dependency so the Java glue and the native
     *  ABI agree — bumping one without the other is how MediaPipe
     *  surfaces "JNI method not found" errors at first generate(). */
    const val AAR_VERSION = "0.10.35"
    const val AAR_URL =
        "https://dl.google.com/dl/android/maven2/com/google/mediapipe/tasks-genai/$AAR_VERSION/tasks-genai-$AAR_VERSION.aar"
    private const val AAR_ENTRY = "jni/arm64-v8a/libllm_inference_engine_jni.so"

    /** Rough hint for the UI button label. The .so is 26 MB
     *  uncompressed; the wrapping AAR ships it deflated to ~10 MB,
     *  but we stream the whole AAR (40 MB) to reach the entry. */
    const val DOWNLOAD_SIZE_MB_HINT = 40

    @Volatile private var loaded = false

    fun nativeDir(context: Context): File =
        File(context.filesDir, NATIVE_DIR).also { if (!it.exists()) it.mkdirs() }

    fun runtimeFile(context: Context): File = File(nativeDir(context), SO_NAME)

    fun isInstalled(context: Context): Boolean {
        val f = runtimeFile(context)
        return f.exists() && f.length() > 0
    }

    /** Idempotent. Loads the on-disk `.so` into the process so that
     *  the next reference to a MediaPipe `LlmInference` type
     *  succeeds. Returns false if the runtime isn't installed or the
     *  load failed. */
    fun ensureLoaded(context: Context): Boolean {
        if (loaded) return true
        if (!isInstalled(context)) return false
        return try {
            System.load(runtimeFile(context).absolutePath)
            loaded = true
            AppLog.i("LlmRuntime", "loaded ${runtimeFile(context).absolutePath}")
            true
        } catch (t: Throwable) {
            AppLog.e("LlmRuntime", "load failed: ${t.message}", t)
            false
        }
    }

    /** Streams the MediaPipe AAR over HTTP, extracts the arm64-v8a
     *  `.so` into `<filesDir>/native/`, and atomic-renames into
     *  place. Reports (bytesDownloaded, totalAarBytes) via
     *  [onProgress]; totalAarBytes is the AAR size, not the .so
     *  length, so the UI shows the actual transfer progress. */
    fun download(context: Context, onProgress: (Long, Long) -> Unit): Boolean {
        val started = System.currentTimeMillis()
        val target = runtimeFile(context)
        val tmp = File(target.parentFile, "${target.name}.part")
        return try {
            val conn = (URL(AAR_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                requestMethod = "GET"
            }
            val total = conn.contentLengthLong
            var extracted = 0L
            conn.inputStream.use { rawInput ->
                val counting = ProgressInputStream(rawInput) { soFar -> onProgress(soFar, total) }
                ZipInputStream(counting).use { zin ->
                    var entry = zin.nextEntry
                    while (entry != null) {
                        if (entry.name == AAR_ENTRY) {
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var read: Int
                                while (zin.read(buf).also { read = it } > 0) {
                                    out.write(buf, 0, read)
                                    extracted += read
                                }
                            }
                            break
                        }
                        entry = zin.nextEntry
                    }
                }
            }
            if (!tmp.exists() || tmp.length() == 0L) {
                tmp.delete()
                AppLog.e("LlmRuntime", "AAR did not contain $AAR_ENTRY")
                recordTrace(bytes = -1, durationMs = System.currentTimeMillis() - started, error = "entry-missing")
                return false
            }
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
            AppLog.i("LlmRuntime", "downloaded $SO_NAME (${target.length() / (1024 * 1024)} MiB)")
            recordTrace(bytes = target.length(), durationMs = System.currentTimeMillis() - started, error = null)
            true
        } catch (e: Exception) {
            AppLog.e("LlmRuntime", "download failed: ${e.message}", e)
            tmp.delete()
            recordTrace(bytes = -1, durationMs = System.currentTimeMillis() - started, error = e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /** Delete the on-disk file. The in-process copy stays loaded
     *  until the next app start — `System.load` can't be undone — so
     *  callers should remind the user to restart for a complete
     *  removal. */
    fun delete(context: Context): Boolean = runtimeFile(context).delete()

    private fun recordTrace(bytes: Long, durationMs: Long, error: String?) {
        if (!ApiTracer.isTracingEnabled) return
        val host = runCatching { URL(AAR_URL).host }.getOrDefault("download")
        ApiTracer.saveTrace(ApiTrace(
            timestamp = System.currentTimeMillis(),
            hostname = host,
            reportId = ApiTracer.currentReportId,
            model = SO_NAME,
            category = "LLM runtime download",
            request = TraceRequest(
                url = AAR_URL,
                method = "GET",
                headers = emptyMap(),
                body = null
            ),
            response = TraceResponse(
                statusCode = if (error == null) 200 else 500,
                headers = emptyMap(),
                body = createAppGson().toJson(mapOf("bytes" to bytes, "durationMs" to durationMs, "error" to error))
            )
        ))
    }

    /** Counts bytes read from an upstream input stream and calls
     *  [onBytes] after each read. Used to feed AAR download progress
     *  to the UI even while ZipInputStream is sitting on the same
     *  underlying connection. */
    private class ProgressInputStream(
        private val upstream: java.io.InputStream,
        private val onBytes: (Long) -> Unit
    ) : java.io.InputStream() {
        private var soFar = 0L
        override fun read(): Int {
            val b = upstream.read()
            if (b >= 0) { soFar += 1; onBytes(soFar) }
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = upstream.read(b, off, len)
            if (n > 0) { soFar += n; onBytes(soFar) }
            return n
        }
        override fun close() = upstream.close()
    }
}
