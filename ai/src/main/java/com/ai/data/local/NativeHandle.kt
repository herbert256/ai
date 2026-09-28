package com.ai.data.local

import java.util.concurrent.locks.ReentrantLock

/**
 * A cached native MediaPipe engine ([com.google.mediapipe.tasks.genai.llminference.LlmInference]
 * or a TextEmbedder) plus the lock that serialises every use of it.
 *
 * The native handles are not thread-safe, and closing one while a call
 * is running inside it is a native use-after-free (removing a model on
 * the Local runtime screen mid-generation used to do exactly that:
 * release() closed the engine without the lock generate() held). So:
 *
 *  - [useLocked] runs every call under the lock and refuses a handle
 *    that was closed while the caller waited for it;
 *  - [release] never blocks and never closes under a running call — it
 *    closes right away when the handle is idle, otherwise the call that
 *    holds the lock closes it on its way out.
 */
internal class NativeHandle<T>(private val value: T, private val closer: (T) -> Unit) {
    private val lock = ReentrantLock()
    @Volatile private var released = false
    /** Guarded by [lock]. */
    private var closed = false

    fun <R> useLocked(block: (T) -> R): R {
        lock.lock()
        try {
            check(!closed) { "The local model was unloaded while this call waited; retry" }
            return block(value)
        } finally {
            lock.unlock()
            // Every lock holder re-checks after unlocking, so a release()
            // whose tryLock lost to this call still gets its close.
            closeIfReleased()
        }
    }

    /** Mark the handle for closing; closes now when idle. Never blocks. */
    fun release() {
        released = true
        closeIfReleased()
    }

    private fun closeIfReleased() {
        if (!released || !lock.tryLock()) return
        try {
            if (!closed) {
                closed = true
                runCatching { closer(value) }
            }
        } finally {
            lock.unlock()
        }
    }
}
