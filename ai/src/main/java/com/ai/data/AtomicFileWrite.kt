package com.ai.data

import java.io.File

/**
 * Atomically writes [content] to [this] file via a temp file + rename.
 * A crash or power loss between [writeText] and [renameTo] leaves the
 * destination file in its previous state (or absent), never half-written.
 *
 * Returns true on success.
 */
fun File.writeTextAtomic(content: String): Boolean {
    val tmp = File(parentFile, "$name.tmp")
    return try {
        tmp.writeText(content)
        if (exists()) delete()
        tmp.renameTo(this)
    } catch (e: Exception) {
        android.util.Log.e("AtomicFileWrite", "Failed to write $absolutePath: ${e.message}")
        try { if (tmp.exists()) tmp.delete() } catch (_: Exception) {}
        false
    }
}
