package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/** Single-select picker over [TARGET_LANGUAGES]. Tap a row → onConfirm
 *  fires with the chosen language and the caller closes the picker. */
@Composable
internal fun LanguageSelectionScreen(
    onConfirm: (TargetLanguage) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    var search by remember { mutableStateOf("") }
    val filtered = remember(search) {
        if (search.isBlank()) TARGET_LANGUAGES
        else {
            val q = search.lowercase()
            TARGET_LANGUAGES.filter { it.name.lowercase().contains(q) || it.native.lowercase().contains(q) }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Pick target language", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search languages...") }, singleLine = true, colors = AppColors.outlinedFieldColors(),
            trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Text("✕", color = AppColors.TextTertiary, fontSize = 12.sp) } })
        Text("${filtered.size} of ${TARGET_LANGUAGES.size} languages", fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(top = 4.dp))

        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filtered, key = { it.name }) { lang ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onConfirm(lang) }
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
        }
    }
}
