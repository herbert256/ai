package com.ai.data

import android.content.Context
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.MessageDigest

/**
 * Per-document embedding cache. Keyed by `(docId, providerId, model)` so the
 * same content embedded by two different models doesn't collide. Stored as
 * one JSON file per cache key under filesDir/embeddings/. Doubles instead of
 * Floats — half the size benefit isn't worth the precision loss for cosine
 * similarity over short documents.
 */
object EmbeddingsStore {
    private const val DIR_NAME = "embeddings"
    private val gson = createAppGson()
    private val type = object : TypeToken<List<Double>>() {}.type

    private fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).also { it.mkdirs() }

    private fun cacheKey(docId: String, providerId: String, model: String): String {
        val raw = "$providerId::$model::$docId"
        val sha = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
        return sha
    }

    private fun fileFor(context: Context, docId: String, providerId: String, model: String): File =
        File(dir(context), "${cacheKey(docId, providerId, model)}.json")

    fun get(context: Context, docId: String, providerId: String, model: String): List<Double>? {
        val f = fileFor(context, docId, providerId, model)
        if (!f.exists()) return null
        return try { gson.fromJson(f.readText(), type) } catch (_: Exception) { null }
    }

    fun put(context: Context, docId: String, providerId: String, model: String, vector: List<Double>) {
        fileFor(context, docId, providerId, model).writeTextAtomic(gson.toJson(vector))
    }

    fun clearAll(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /** Cosine similarity. Returns 0.0 if either vector is empty / mismatched. */
    fun cosine(a: List<Double>, b: List<Double>): Double {
        if (a.isEmpty() || a.size != b.size) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = Math.sqrt(normA) * Math.sqrt(normB)
        return if (denom == 0.0) 0.0 else dot / denom
    }
}
