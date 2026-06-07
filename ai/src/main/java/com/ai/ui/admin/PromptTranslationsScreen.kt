package com.ai.ui.admin

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.InternalPromptSeed
import com.ai.data.PromptTranslationStore
import com.ai.model.Settings
import com.ai.ui.report.other.LanguageSelectionScreen
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.LocalMetadataIcons
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Housekeeping → Prompt translations. Lists every language the internal
 * prompts are available in (the English baseline, bundled translations,
 * and on-device generated ones), and lets the user generate, redo, and
 * delete the generated set. The 🆕 bar action picks a source language
 * (skipped when only English exists) then a target language, and runs a
 * batch AI translation of every prompt from source into target.
 */
@Composable
fun PromptTranslationsScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    aiSettings: Settings,
    onAskModelText: suspend (AppService, String, String) -> String?,
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mi = LocalMetadataIcons.current

    var refreshTick by remember { mutableStateOf(0) }
    // Languages present (baseline + bundled + generated), recomputed on tick.
    val languages = remember(refreshTick) { InternalPromptSeed.listLanguages(context) }
    val storedSet = remember(refreshTick) { PromptTranslationStore.storedLanguages(context).toSet() }

    var busyMessage by remember { mutableStateOf<String?>(null) }
    // The language currently being translated + its (done, total) progress,
    // shown inline on that language's own row rather than at the top.
    var translatingLang by remember { mutableStateOf<String?>(null) }
    var translateProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    // New-translation flow: pick source (skip if only English), then target.
    var pickSource by remember { mutableStateOf(false) }
    var pickTargetFromSource by remember { mutableStateOf<String?>(null) }

    fun runTranslation(source: String, target: String) {
        if (target.equals(source, ignoreCase = true)) {
            Toast.makeText(context, "Source and target are the same", Toast.LENGTH_SHORT).show(); return
        }
        if (target.equals(InternalPromptSeed.BASE_LANGUAGE, ignoreCase = true)) {
            Toast.makeText(context, "English is the editable baseline; choose another target language", Toast.LENGTH_LONG).show(); return
        }
        scope.launch {
            busyMessage = "Translating into $target…"
            translatingLang = target
            translateProgress = null
            val n = withContext(Dispatchers.IO) {
                PromptTranslationStore.translateInto(
                    context, source, target, aiSettings, onAskModelText,
                    onProgress = { done, total -> translateProgress = done to total }
                )
            }
            busyMessage = null
            translatingLang = null
            translateProgress = null
            refreshTick++
            Toast.makeText(
                context,
                if (n < 0) "No active model or translate-text prompt available"
                else "Translated $n prompts into $target",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun startNewFlow() {
        // Source languages you can translate FROM = everything present.
        if (languages.size <= 1) {
            // Only English — skip the source step.
            pickTargetFromSource = InternalPromptSeed.BASE_LANGUAGE
        } else {
            pickSource = true
        }
    }

    // ---- Target language picker overlay (reuses the report flow's picker) ----
    pickTargetFromSource?.let { source ->
        LanguageSelectionScreen(
            onConfirm = { target -> pickTargetFromSource = null; runTranslation(source, target.name) },
            onBack = { pickTargetFromSource = null },
            onNavigateHome = onNavigateHome
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "prompt_translations",
            title = "Prompt translations",
            subject = "Generate and manage internal-prompt translations",
            onBackClick = onBack,
            // Always publish the 🆕 add action so it's reliably visible the
            // moment the screen loads; the handler no-ops while a translation
            // is already running.
            onAdd = { if (busyMessage == null) startNewFlow() }
        )

        // A brand-new target language has no row yet (it isn't stored until
        // the run finishes) — surface it now so its progress shows inline.
        val displayLanguages = if (translatingLang != null && languages.none { it.equals(translatingLang, ignoreCase = true) })
            languages + translatingLang!!
        else languages
        val storedCounts by produceState<Map<String, Int>>(initialValue = emptyMap(), refreshTick, displayLanguages) {
            value = withContext(Dispatchers.IO) {
                displayLanguages.associateWith { lang -> PromptTranslationStore.count(context, lang) }
            }
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(
                    "The internal prompts ship in English (the editable baseline). Generate a translation into another language and reports detected in that language use it automatically, with English as fallback.",
                    fontSize = 12.sp, color = AppColors.TextTertiary, lineHeight = 17.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            items(displayLanguages, key = { it }) { lang ->
                val isBase = lang.equals(InternalPromptSeed.BASE_LANGUAGE, ignoreCase = true)
                val storedCount = storedCounts[lang] ?: 0
                val isTranslating = lang.equals(translatingLang, ignoreCase = true)
                Card(
                    colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(lang, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                            if (isTranslating) {
                                Text(
                                    translateProgress?.let { (done, total) -> "Translating… $done/$total" } ?: "Translating…",
                                    fontSize = 12.sp, color = AppColors.InfoAccent
                                )
                            } else {
                                Text(
                                    when {
                                        isBase -> "Editable baseline"
                                        lang in storedSet -> "$storedCount generated prompts"
                                        else -> "Bundled translation"
                                    },
                                    fontSize = 12.sp, color = AppColors.TextTertiary
                                )
                            }
                        }
                        if (!isBase) {
                            // 🔄 redo the translation (English → this language).
                            Text(
                                mi.reload, fontSize = 20.sp,
                                modifier = Modifier
                                    .clickable(enabled = busyMessage == null) {
                                        runTranslation(InternalPromptSeed.BASE_LANGUAGE, lang)
                                    }
                                    .padding(horizontal = 6.dp)
                            )
                            // ❌ delete the generated translation for this language.
                            if (lang in storedSet) {
                                Text(
                                    mi.crossMark, fontSize = 20.sp, color = AppColors.DangerAccent,
                                    modifier = Modifier
                                        .clickable(enabled = busyMessage == null) { confirmDelete = lang }
                                        .padding(horizontal = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // ---- Source-language picker dialog ----
    if (pickSource) {
        AlertDialog(
            onDismissRequest = { pickSource = false },
            title = { Text("Translate from") },
            text = {
                Column {
                    languages.forEach { lang ->
                        Text(
                            lang, color = AppColors.TextPrimary, fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth().clickable {
                                pickSource = false; pickTargetFromSource = lang
                            }.padding(vertical = 10.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickSource = false }) { Text("Cancel") } }
        )
    }

    // ---- Delete confirmation ----
    confirmDelete?.let { lang ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete $lang translation?") },
            text = { Text("Remove the generated $lang translation of every internal prompt? Bundled translations and the English baseline are untouched.") },
            confirmButton = {
                TextButton(onClick = {
                    val n = PromptTranslationStore.deleteLanguage(context, lang)
                    confirmDelete = null
                    refreshTick++
                    Toast.makeText(context, "Deleted $n $lang prompts", Toast.LENGTH_SHORT).show()
                }) { Text("Delete", color = AppColors.DangerAccent) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
}
