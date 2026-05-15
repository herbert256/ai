package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import android.content.Context
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

/** A target language for the Translate flow. [name] is the English name
 *  used as the @LANGUAGE@ substitution; [native] is what the user sees
 *  in the picker so they can browse without knowing the English name. */
internal data class TargetLanguage(val name: String, val native: String)

/** Curated list — the most-requested target languages plus a long
 *  tail. Not exhaustive; the user can always edit the translation
 *  prompt to use a more specific dialect. Order is by speaker count
 *  for the head, alphabetical for the tail. */
internal val TARGET_LANGUAGES: List<TargetLanguage> = listOf(
    TargetLanguage("English", "English"),
    TargetLanguage("Mandarin Chinese", "中文 (普通话)"),
    TargetLanguage("Spanish", "Español"),
    TargetLanguage("Hindi", "हिन्दी"),
    TargetLanguage("Arabic", "العربية"),
    TargetLanguage("Portuguese", "Português"),
    TargetLanguage("Bengali", "বাংলা"),
    TargetLanguage("Russian", "Русский"),
    TargetLanguage("Japanese", "日本語"),
    TargetLanguage("German", "Deutsch"),
    TargetLanguage("French", "Français"),
    TargetLanguage("Korean", "한국어"),
    TargetLanguage("Italian", "Italiano"),
    TargetLanguage("Turkish", "Türkçe"),
    TargetLanguage("Dutch", "Nederlands"),
    TargetLanguage("Polish", "Polski"),
    TargetLanguage("Vietnamese", "Tiếng Việt"),
    TargetLanguage("Thai", "ไทย"),
    TargetLanguage("Indonesian", "Bahasa Indonesia"),
    TargetLanguage("Greek", "Ελληνικά"),
    TargetLanguage("Hebrew", "עברית"),
    TargetLanguage("Czech", "Čeština"),
    TargetLanguage("Swedish", "Svenska"),
    TargetLanguage("Danish", "Dansk"),
    TargetLanguage("Norwegian", "Norsk"),
    TargetLanguage("Finnish", "Suomi"),
    TargetLanguage("Hungarian", "Magyar"),
    TargetLanguage("Romanian", "Română"),
    TargetLanguage("Ukrainian", "Українська"),
    TargetLanguage("Bulgarian", "Български"),
    TargetLanguage("Serbian", "Српски"),
    TargetLanguage("Croatian", "Hrvatski"),
    TargetLanguage("Slovak", "Slovenčina"),
    TargetLanguage("Slovenian", "Slovenščina"),
    TargetLanguage("Lithuanian", "Lietuvių"),
    TargetLanguage("Latvian", "Latviešu"),
    TargetLanguage("Estonian", "Eesti"),
    TargetLanguage("Catalan", "Català"),
    TargetLanguage("Basque", "Euskara"),
    TargetLanguage("Galician", "Galego"),
    TargetLanguage("Welsh", "Cymraeg"),
    TargetLanguage("Irish", "Gaeilge"),
    TargetLanguage("Icelandic", "Íslenska"),
    TargetLanguage("Persian (Farsi)", "فارسی"),
    TargetLanguage("Urdu", "اُردُو"),
    TargetLanguage("Tamil", "தமிழ்"),
    TargetLanguage("Telugu", "తెలుగు"),
    TargetLanguage("Marathi", "मराठी"),
    TargetLanguage("Gujarati", "ગુજરાતી"),
    TargetLanguage("Punjabi", "ਪੰਜਾਬੀ"),
    TargetLanguage("Malay", "Bahasa Melayu"),
    TargetLanguage("Filipino (Tagalog)", "Filipino"),
    TargetLanguage("Swahili", "Kiswahili"),
    TargetLanguage("Afrikaans", "Afrikaans"),
    TargetLanguage("Esperanto", "Esperanto")
)

/** Persistent MRU of recently-picked target languages — capped at
 *  three, newest first. Stored as a newline-joined list of
 *  "name|native" strings in `eval_prefs` so it survives process death
 *  and app upgrades. Newline is safe — neither field can contain it. */
internal object RecentTargetLanguages {
    private const val PREFS = "eval_prefs"
    private const val KEY = "recent_target_languages"
    private const val MAX = 3

    fun get(context: Context): List<TargetLanguage> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("|", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank()) TargetLanguage(parts[0], parts[1]) else null
        }.take(MAX)
    }

    fun push(context: Context, picked: TargetLanguage) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = get(context)
        // Dedupe by name (the @LANGUAGE@ substitution key) so re-picking
        // the same language doesn't push a near-duplicate.
        val next = (listOf(picked) + current.filterNot { it.name == picked.name }).take(MAX)
        prefs.edit().putString(KEY, next.joinToString("\n") { "${it.name}|${it.native}" }).apply()
    }
}

/** Single-select picker over [TARGET_LANGUAGES]. Tap a row → onConfirm
 *  fires with the chosen language and the caller closes the picker. */
@Composable
internal fun LanguageSelectionScreen(
    onConfirm: (TargetLanguage) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    val recents = remember { RecentTargetLanguages.get(context) }
    val filtered = remember(search) {
        if (search.isBlank()) TARGET_LANGUAGES
        else {
            val q = search.lowercase()
            TARGET_LANGUAGES.filter { it.name.lowercase().contains(q) || it.native.lowercase().contains(q) }
        }
    }
    // Recents are filtered by the same search so typing "ja" hides
    // English from the Recent block too. When the search blanks the
    // recents list, we fall back to hiding the section entirely.
    val visibleRecents = remember(recents, search) {
        if (search.isBlank()) recents
        else {
            val q = search.lowercase()
            recents.filter { it.name.lowercase().contains(q) || it.native.lowercase().contains(q) }
        }
    }

    fun pickAndClose(lang: TargetLanguage) {
        RecentTargetLanguages.push(context, lang)
        onConfirm(lang)
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "translation_language", title = "Pick target language", onBackClick = onBack)

        OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search languages...") }, singleLine = true, colors = AppColors.outlinedFieldColors(),
            trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Text("✕", color = AppColors.TextTertiary, fontSize = 12.sp) } })
        Text("${filtered.size} of ${TARGET_LANGUAGES.size} languages", fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(top = 4.dp))

        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            // Recent block: up to 3 most-recently-picked rows, newest
            // first. Header is hidden when there are no recents (e.g.
            // fresh install) so the screen doesn't start with an empty
            // section. Same row shape as the main list, prefixed with
            // a different LazyColumn key so a language that appears in
            // both lists doesn't collide.
            if (visibleRecents.isNotEmpty()) {
                item(key = "recent-header") {
                    Text("Recent", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.padding(vertical = 6.dp))
                }
                items(visibleRecents, key = { "recent-${it.name}|${it.native}" }) { lang ->
                    LanguageRow(lang, onClick = { pickAndClose(lang) })
                }
                item(key = "recent-divider") {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("All languages", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.padding(vertical = 6.dp))
                }
            }
            // Composite key — two future TARGET_LANGUAGES sharing
            // a display name would otherwise collide on the LazyColumn
            // key and crash with a duplicate-key error.
            items(filtered, key = { "${it.name}|${it.native}" }) { lang ->
                LanguageRow(lang, onClick = { pickAndClose(lang) })
            }
        }
    }
}

/** One row inside the language picker. Extracted so the Recent block
 *  and the full list render identically. */
@Composable
private fun LanguageRow(lang: TargetLanguage, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(lang.name, fontSize = 14.sp, color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (lang.native != lang.name) {
                Text(lang.native, fontSize = 12.sp, color = AppColors.TextTertiary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(">", color = AppColors.Blue, fontSize = 14.sp,
            modifier = Modifier.padding(start = 8.dp))
    }
    HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
}
