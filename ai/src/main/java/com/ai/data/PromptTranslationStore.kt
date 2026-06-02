package com.ai.data

import android.content.Context
import com.ai.model.Settings
import java.io.File

/**
 * Writable overlay of internal-prompt translations, generated on-device
 * by the Housekeeping → Prompt translations screen. Bundled translations
 * ship read-only under `assets/internal-prompts/<Language>/`; the ones a
 * user generates live here, under
 * `<filesDir>/prompt-translations/<Language>/<category>/<name>.txt`, so
 * they can be added, redone, and deleted at runtime.
 *
 * [InternalPromptSeed.localizedBody] consults this store first, then the
 * bundled asset, then falls back to the English editable text — so a
 * generated translation transparently overrides (or fills in for) the
 * bundled one.
 */
object PromptTranslationStore {
    private const val ROOT = "prompt-translations"

    private fun rootDir(context: Context) = File(context.filesDir, ROOT)
    private fun langDir(context: Context, language: String) = File(rootDir(context), language)

    /** Generated-translation body for one prompt, or null when this
     *  language has no stored translation of `<category>/<name>`. */
    fun body(context: Context, language: String, category: String, name: String): String? {
        val f = File(File(langDir(context, language), category), "$name.txt")
        return if (f.exists()) try { f.readText() } catch (_: Exception) { null } else null
    }

    fun put(context: Context, language: String, category: String, name: String, text: String) {
        val dir = File(langDir(context, language), category)
        if (!dir.exists()) dir.mkdirs()
        try { File(dir, "$name.txt").writeTextAtomic(text) }
        catch (e: Exception) { AppLog.w("PromptTranslationStore", "put failed: ${e.message}") }
    }

    /** Languages that have at least one stored translation file. */
    fun storedLanguages(context: Context): List<String> {
        val root = rootDir(context)
        if (!root.exists()) return emptyList()
        return root.listFiles { f -> f.isDirectory }?.filter { count(context, it.name) > 0 }
            ?.map { it.name }?.sorted() ?: emptyList()
    }

    /** Number of stored `.txt` translation files for [language]. */
    fun count(context: Context, language: String): Int {
        val dir = langDir(context, language)
        if (!dir.exists()) return 0
        return dir.walkTopDown().count { it.isFile && it.extension == "txt" }
    }

    /** Remove every stored translation for [language]. Returns the file
     *  count removed. Bundled (asset) translations are untouched. */
    fun deleteLanguage(context: Context, language: String): Int {
        val dir = langDir(context, language)
        val n = count(context, language)
        try { dir.deleteRecursively() } catch (_: Exception) {}
        AppLog.i("PromptTranslationStore", "deleted $n files for $language")
        return n
    }

    fun clearAll(context: Context): Int {
        val root = rootDir(context)
        val n = if (root.exists()) root.walkTopDown().count { it.isFile } else 0
        try { root.deleteRecursively() } catch (_: Exception) {}
        return n
    }

    /** Translate every bundled English baseline prompt from [sourceLanguage]
     *  into [targetLanguage] via [ask], storing each result. The source body
     *  is the source language's text (its stored/bundled translation, else
     *  the English baseline). Uses the first active provider's default model.
     *  Returns the number of prompts translated, or -1 when no active model /
     *  translate-text template is available. */
    suspend fun translateInto(
        context: Context,
        sourceLanguage: String,
        targetLanguage: String,
        aiSettings: Settings,
        ask: suspend (AppService, String, String) -> String?,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Int {
        val service = aiSettings.getActiveServices().firstOrNull() ?: return -1
        val model = aiSettings.getModel(service)
        if (model.isBlank()) return -1
        val template = aiSettings.getInternalPromptByName("translate-text")?.text ?: return -1
        val baseline = InternalPromptSeed.loadFromAssets(context)
        var done = 0
        baseline.forEachIndexed { index, prompt ->
            onProgress(index, baseline.size)
            val sourceBody = if (sourceLanguage.equals(InternalPromptSeed.BASE_LANGUAGE, ignoreCase = true)) {
                prompt.text
            } else {
                InternalPromptSeed.localizedBody(context, sourceLanguage, prompt.category, prompt.name) ?: prompt.text
            }
            if (sourceBody.isBlank()) return@forEachIndexed
            val resolved = template.replace("@LANGUAGE@", targetLanguage).replace("@TEXT@", sourceBody)
            val translated = ask(service, model, resolved)?.trim()
            if (!translated.isNullOrBlank()) {
                put(context, targetLanguage, prompt.category, prompt.name, translated)
                done++
            }
        }
        onProgress(baseline.size, baseline.size)
        AppLog.i("PromptTranslationStore", "translated $done/${baseline.size} prompts into $targetLanguage from $sourceLanguage")
        return done
    }
}
