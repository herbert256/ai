package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.model.Settings
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

/**
 * Multi-model accumulator for the Translate flow. Lets the user
 * pick several (provider, model) tuples before kicking off a
 * translation run — work is then spread round-robin across the
 * picked set by [com.ai.viewmodel.ReportViewModel.startTranslation].
 *
 * Mirrors the new-report SelectionPhase / Find Alternative Icons
 * UX: a list with X delete buttons, a +Model button that opens
 * [ReportSelectModelsScreen] as a sub-overlay, and a "Start
 * translation" button that's enabled once the list is non-empty.
 */
@Composable
internal fun TranslateSelectModelsScreen(
    targetLanguage: TargetLanguage,
    aiSettings: Settings,
    recentEntries: List<Pair<AppService, String>>,
    onRecordRecent: (String, String) -> Unit,
    onStart: (List<Pair<AppService, String>>) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }

    // Encode picks as "providerId|model" newline-joined for the
    // Saver so the picked list survives rotation / process death.
    // AppService.findById tolerates unknown ids (returns null) —
    // we drop those on restore so a deleted provider doesn't crash
    // the screen.
    val pickSaver = remember {
        Saver<List<Pair<AppService, String>>, String>(
            save = { list -> list.joinToString("\n") { "${it.first.id}|${it.second}" } },
            restore = { encoded ->
                if (encoded.isBlank()) emptyList()
                else encoded.split("\n").mapNotNull { line ->
                    val parts = line.split("|", limit = 2)
                    if (parts.size != 2) return@mapNotNull null
                    val svc = AppService.findById(parts[0]) ?: return@mapNotNull null
                    svc to parts[1]
                }
            }
        )
    }
    var picked by rememberSaveable(stateSaver = pickSaver) {
        mutableStateOf<List<Pair<AppService, String>>>(emptyList())
    }
    var showSubPicker by rememberSaveable { mutableStateOf(false) }

    if (showSubPicker) {
        ReportSelectModelsScreen(
            aiSettings = aiSettings,
            alreadyAdded = picked.toSet(),
            titleText = "Pick translation model",
            recentEntries = recentEntries,
            onRecordRecent = { (p, m) -> onRecordRecent(p.id, m) },
            onConfirm = { (p, m) ->
                picked = picked + (p to m)
                showSubPicker = false
            },
            onBack = { showSubPicker = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(
            helpTopic = "translation_models",
            title = "Pick translation models",
            subject = "${targetLanguage.name} (${targetLanguage.native})",
            onBackClick = onBack
        )
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Translation work spreads round-robin across the picked models.",
            fontSize = 12.sp, color = AppColors.TextTertiary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (picked.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No models picked yet — tap +Model below.",
                    color = AppColors.TextTertiary, fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(picked, key = { (p, m) -> "${p.id}|$m" }) { (prov, mdl) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                prov.id, fontSize = 12.sp, color = AppColors.Blue,
                                fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                mdl, fontSize = 14.sp, color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .clickable { picked = picked.filterNot { it.first == prov && it.second == mdl } },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✕", fontSize = 18.sp, color = AppColors.Red)
                        }
                    }
                    HorizontalDivider(color = AppColors.DividerDark)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showSubPicker = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("+Model", fontSize = 13.sp, maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { if (picked.isNotEmpty()) onStart(picked) },
            enabled = picked.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
        ) {
            val label = if (picked.size <= 1) "Start translation"
                        else "Start translation — ${picked.size} models"
            Text(label, fontSize = 13.sp, maxLines = 1, softWrap = false)
        }
    }
}
