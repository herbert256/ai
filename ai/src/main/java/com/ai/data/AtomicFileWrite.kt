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
    val parent = parentFile
    val tmp = File(parent, "$name.tmp")
    return try {
        // Make sure the parent exists before we try to drop a tmp file
        // into it. Without this, a fresh install or a freshly-cleared
        // sub-directory throws FileNotFoundException on the FOS open
        // and writeTextAtomic returned false with nothing on disk.
        if (parent != null && !parent.exists()) parent.mkdirs()
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
