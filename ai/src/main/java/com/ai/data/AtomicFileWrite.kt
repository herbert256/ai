package com.ai.data

import java.io.File

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
    val tmp = File(parentFile, "$name.tmp")
    return try {
        tmp.writeText(content)
        try {
            java.nio.file.Files.move(
                tmp.toPath(), toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
            true
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                tmp.toPath(), toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
            true
        }
    } catch (e: Exception) {
        android.util.Log.e("AtomicFileWrite", "Failed to write $absolutePath: ${e.message}")
        try { if (tmp.exists()) tmp.delete() } catch (_: Exception) {}
        false
    }
}
