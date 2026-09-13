package com.ai.data.preferences

import android.content.SharedPreferences
import com.ai.data.AppLog
import com.ai.data.writeTextAtomic
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Large JSON values do not belong in the XML Android loads for every setting.
 * Immutable content-addressed files are published BEFORE their small prefs
 * references. A killed/failed prefs write therefore keeps the previous value
 * readable. Full backups already include filesDir and SharedPreferences. */
internal class CatalogPreferences(private val prefs: SharedPreferences, filesDir: File?) {
    private val dir = filesDir?.let { File(it, "provider_catalogs") }
    private val decoded = HashMap<String, String>()

    fun getString(key: String, fallback: String? = null): String? = synchronized(lock) {
        val value = prefs.getString(key, fallback) ?: return@synchronized null
        if (!isCatalogKey(key) || !value.startsWith(PREFIX)) return@synchronized value
        val hash = value.removePrefix(PREFIX)
        require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid catalog reference" }
        decoded[hash] ?: run {
            val file = dir?.let { File(it, "$hash.json") }
                ?: throw IOException("Catalog directory unavailable")
            val text = file.readText()
            check(digest(text) == hash) { "Catalog integrity check failed: $key" }
            decoded[hash] = text
            text
        }
    }

    /** Immutable references already contain the content revision. */
    fun revision(key: String): String {
        val value = prefs.getString(key, null).orEmpty()
        return if (value.startsWith(PREFIX)) value.removePrefix(PREFIX) else digest(value)
    }

    fun edit(block: SharedPreferences.Editor.() -> Unit) = synchronized(lock) {
        prefs.edit().apply(block).apply()
    }

    /** Backups keep the original inline wire format. Resolve under the same
     * lock as catalog edits/pruning, so no referenced revision disappears
     * halfway through the snapshot. Older app versions can still read it. */
    fun snapshotForBackup(): Map<String, Any?> = synchronized(lock) {
        prefs.all.mapValues { (key, value) ->
            if (value is String && isCatalogKey(key)) getString(key) else value
        }
    }

    fun putString(editor: SharedPreferences.Editor, key: String, value: String?) {
        editor.putString(key, if (dir != null && isCatalogKey(key) && value != null && value.length > 512)
            store(value) else value)
    }

    private fun store(value: String): String {
        val root = dir ?: return value
        val hash = digest(value)
        val file = File(root, "$hash.json")
        try {
            root.mkdirs()
            if (decoded[hash] != value || !file.exists()) {
                if ((!file.exists() || file.readText() != value) && !file.writeTextAtomic(value))
                    throw IOException("Catalog write failed")
            }
            decoded[hash] = value
            return PREFIX + hash
        } catch (e: Exception) {
            // Retain the original inline value if storage is unavailable.
            AppLog.w("CatalogPreferences", "Keeping inline catalog: ${e.javaClass.simpleName}")
            return value
        }
    }

    fun migrateLegacy() = synchronized(lock) {
        if (dir == null) return@synchronized
        val editor = prefs.edit()
        var moved = 0
        prefs.all.forEach { (key, raw) ->
            val value = raw as? String ?: return@forEach
            if (isCatalogKey(key) && value.length > 512 && !value.startsWith(PREFIX)) {
                val stored = store(value)
                if (stored != value) { editor.putString(key, stored); moved++ }
            }
        }
        if (moved > 0) {
            val saved = editor.commit()
            AppLog.d("CatalogPreferences", "Moved $moved catalog values out of XML; committed=$saved")
        }
    }

    /** Flush refs before discarding old immutable revisions; serialized with
     * every catalog writer, including writers from other SettingsPreferences. */
    fun pruneUnused() = synchronized(lock) {
        val root = dir ?: return@synchronized
        if (!prefs.edit().commit()) return@synchronized
        val live = prefs.all.filterKeys(::isCatalogKey).values.filterIsInstance<String>()
            .filter { it.startsWith(PREFIX) }.mapTo(HashSet()) { it.removePrefix(PREFIX) }
        root.listFiles()?.filter { it.extension == "json" && it.nameWithoutExtension !in live }
            ?.forEach { it.delete() }
        decoded.keys.retainAll(live)
    }

    companion object {
        private val lock = Any()
        private const val PREFIX = "@catalog:v1:"
        private val suffixes = listOf("_models_response_raw", "_model_capabilities", "_model_pricing",
            "_manual_models", "_model_types", "_vision_capable_computed",
            "_web_search_capable_computed", "_reasoning_capable_computed")
        private fun isCatalogKey(key: String) = suffixes.any(key::endsWith)
        fun digest(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            val hex = "0123456789abcdef"
            return buildString(64) { bytes.forEach { byte ->
                val b = byte.toInt() and 255
                append(hex[b ushr 4]); append(hex[b and 15])
            } }
        }
    }
}
