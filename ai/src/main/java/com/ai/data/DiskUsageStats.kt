package com.ai.data

import android.content.Context
import java.io.File

/**
 * On-disk footprint of the app's bulkiest caches, for the Live Dashboard's
 * System-health card. Computed on demand (a recursive size walk), so callers
 * must invoke [snapshot] off the main thread / on a slow tick — never on the
 * 750 ms ticker.
 */
object DiskUsageStats {

    data class Snapshot(
        val traceBytes: Long = 0,
        val embeddingsBytes: Long = 0,
        val knowledgeBytes: Long = 0,
    )

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val children = stack.removeLast().listFiles() ?: continue
            for (c in children) if (c.isDirectory) stack.addLast(c) else total += c.length()
        }
        return total
    }

    fun snapshot(context: Context): Snapshot {
        val base = context.filesDir
        return Snapshot(
            traceBytes = dirSize(File(base, "trace")),
            embeddingsBytes = dirSize(File(base, "embeddings")),
            knowledgeBytes = dirSize(File(base, "knowledge")),
        )
    }
}
