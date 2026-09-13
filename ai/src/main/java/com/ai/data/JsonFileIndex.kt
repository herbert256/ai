package com.ai.data

import com.google.gson.reflect.TypeToken
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/** Rebuildable metadata only. File identity, nanosecond mtime and length
 * invalidate a record after atomic replacement, import or restore, including
 * a same-sized rewrite. Source files remain authoritative. */
internal class JsonFileIndex<T : Any>(private val name: String, itemClass: Class<T>,
    private val valid: (File, T) -> Boolean) {
    private data class Entry<T>(val stamp: String, val value: T)
    private val gson = createAppGson()
    private val type = TypeToken.getParameterized(Map::class.java, String::class.java,
        TypeToken.getParameterized(Entry::class.java, itemClass).type).type
    private var sourceRoot: File? = null
    private var entries = mutableMapOf<String, Entry<T>>()

    @Synchronized
    fun clear(root: File) {
        File(File(root.parentFile, "metadata_indexes"), "$name.json").delete()
        sourceRoot = null
        entries.clear()
    }

    private fun stamp(file: File): String? = runCatching {
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attrs.isRegularFile) null else
            "${attrs.fileKey()}:${attrs.size()}:${attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS)}"
    }.getOrNull()

    @Synchronized
    fun read(root: File, files: List<File>, parse: (File) -> T?): List<T> {
        val index = File(File(root.parentFile, "metadata_indexes"), "$name.json")
        if (sourceRoot != root) {
            sourceRoot = root
            entries = runCatching {
                index.bufferedReader().use { gson.fromJson<Map<String, Entry<T>>>(it, type) }.toMutableMap()
            }.getOrDefault(mutableMapOf())
        }
        val next = HashMap<String, Entry<T>>()
        var parsed = 0
        val result = files.mapNotNull { file ->
            val before = stamp(file) ?: return@mapNotNull null
            val cached = entries[file.name]?.takeIf {
                it.stamp == before && runCatching { valid(file, it.value) }.getOrDefault(false)
            }
            val entry = cached ?: run {
                parsed++
                val value = parse(file) ?: return@mapNotNull null
                if (stamp(file) != before) return@mapNotNull null
                Entry(before, value)
            }
            next[file.name] = entry
            entry.value
        }
        if (next != entries || !index.exists()) {
            index.parentFile?.mkdirs()
            index.writeTextAtomic(gson.toJson(next, type))
        }
        entries = next
        AppLog.d("MetadataIndex", "$name: ${result.size} entries, $parsed source files parsed")
        return result
    }
}
