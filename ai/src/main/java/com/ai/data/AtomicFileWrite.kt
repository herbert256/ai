package com.ai.data

import java.io.File

private const val ATOMIC_TMP_MAX_AGE_MS = 30L * 60L * 1000L
private val atomicPruneTimes = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 256
}
private val atomicTempName = Regex(".+\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.tmp")

/**
 * Atomically writes [content] to [this] file via a temp file + rename.
 * A crash or power loss between [writeText] and [renameTo] leaves the
 * destination file in its previous state, never half-written.
 *
 * Uses [java.nio.file.Files.move] with REPLACE_EXISTING + ATOMIC_MOVE so the
 * destination is replaced as a single filesystem operation. The legacy
 * delete-then-rename path was not crash-safe — a process death between the
 * delete and the rename left no file at all. Falls back to the rename-
 * over-existing form when ATOMIC_MOVE is unsupported (rare on Android's
 * internal storage but possible on some external mounts).
 *
 * Returns true on success.
 */
fun File.writeTextAtomic(content: String): Boolean {
    val parent = parentFile
    // Unique per-writer tmp name (Bug 23): a deterministic "<name>.tmp"
    // means two threads writing the SAME destination concurrently (callers
    // that don't all serialize on a per-path lock — ModelListCache,
    // EmbeddingsStore, PromptCache, PricingCache.saveBlob, etc.) share one
    // staging file and can interleave bytes before the rename. A UUID
    // suffix gives each writer its own staging file.
    val tmp = File(parent, "$name.${java.util.UUID.randomUUID()}.tmp")
    return try {
        // Make sure the parent exists before we try to drop a tmp file
        // into it. Without this, a fresh install or a freshly-cleared
        // sub-directory throws FileNotFoundException on the FOS open
        // and writeTextAtomic returned false with nothing on disk.
        if (parent != null && !parent.exists()) parent.mkdirs()
        pruneOldAtomicTempSiblings(parent)
        // Write the bytes AND fsync the file descriptor before the
        // atomic move. ext4 with delayed allocation can hold the
        // tmp file's data in the page cache past the rename — a power
        // loss between the rename and the implicit kernel flush can
        // surface either old content or an empty file at the
        // destination, defeating the "atomic" promise.
        java.io.FileOutputStream(tmp).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.flush()
            try { fos.fd.sync() } catch (_: java.io.IOException) { /* best effort */ }
        }
        try {
            java.nio.file.Files.move(
                tmp.toPath(), toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                tmp.toPath(), toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        }
        // fsync the PARENT DIRECTORY so the rename itself is durable — fsyncing
        // the file content above doesn't persist the new directory entry, and
        // POSIX needs a dir fsync to survive a power loss after the move.
        // Best-effort: some filesystems / platforms reject opening a dir.
        try {
            toPath().parent?.let { dir ->
                java.nio.channels.FileChannel.open(dir, java.nio.file.StandardOpenOption.READ).use { it.force(true) }
            }
        } catch (_: Exception) { /* best effort */ }
        true
    } catch (e: Exception) {
        AppLog.e("AtomicFileWrite", "Failed to write $absolutePath: ${e.message}")
        try {
            if (tmp.exists() && !tmp.delete()) {
                AppLog.w("AtomicFileWrite", "Failed to delete temp file ${tmp.absolutePath}")
            }
        } catch (cleanupError: Exception) {
            AppLog.w("AtomicFileWrite", "Failed to delete temp file ${tmp.absolutePath}: ${cleanupError.message}")
        }
        false
    }
}

private fun File.pruneOldAtomicTempSiblings(parent: File?) {
    parent ?: return
    val now = System.currentTimeMillis()
    synchronized(atomicPruneTimes) {
        val previous = atomicPruneTimes[parent.absolutePath]
        if (previous != null && now - previous in 0 until 60_000L) return
        atomicPruneTimes[parent.absolutePath] = now
    }
    val cutoff = now - ATOMIC_TMP_MAX_AGE_MS
    parent.listFiles { f ->
        // One scan per directory/minute, not per row. Only our UUID temporary
        // names qualify; keep file and directory fsyncs on every actual write.
        f.name.endsWith(".tmp") && atomicTempName.matches(f.name) && f.isFile && f.lastModified() < cutoff
    }?.forEach { stale ->
        try {
            if (!stale.delete()) {
                AppLog.w("AtomicFileWrite", "Failed to prune stale temp file ${stale.absolutePath}")
            }
        } catch (e: Exception) {
            AppLog.w("AtomicFileWrite", "Failed to prune stale temp file ${stale.absolutePath}: ${e.message}")
        }
    }
}
