package com.ai.ui.report.manage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.TranslationCandidate

/** Live progress list for a "Find alternative translation" fan-out — one row
 *  per (provider, model): ⏳ while running, the returned translation once done,
 *  ❌ on error. Tapping a Done row replaces this entry's translation in place
 *  (overwrites the persisted TRANSLATE row). Mirrors [AlternativeTitlesScreen]
 *  but shows multi-line body text rather than a one-line title. */
@Composable
fun AlternativeTranslationsScreen(
    candidates: List<TranslationCandidate>,
    onPick: (TranslationCandidate.Done) -> Unit,
    onRestart: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "alternative_translations", title = "Alternative translations", subject = "Live translations from several models", onBackClick = onBack)

        // Stable order so rows don't reshuffle as candidates settle.
        val ordered = remember(candidates) {
            candidates.sortedWith(compareBy({ it.provider.id.lowercase() }, { it.model.lowercase() }))
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ordered.isEmpty()) {
                Text("Nothing here yet. Pick models on the previous screen.", fontSize = 12.sp, color = AppColors.TextTertiary)
            } else {
                ordered.forEach { c -> TranslationCandidateRow(c, onPick) }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onRestart, modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedButtonColors()) {
            Text("Restart", maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun TranslationCandidateRow(candidate: TranslationCandidate, onPick: (TranslationCandidate.Done) -> Unit) {
    val providerModel = "${candidate.provider.id} · ${candidate.model}"
    val cost = when (candidate) {
        is TranslationCandidate.Done -> candidate.cost
        is TranslationCandidate.Error -> candidate.cost
        is TranslationCandidate.Running -> 0.0
    }
    Card(
        modifier = Modifier.fillMaxWidth().then(
            if (candidate is TranslationCandidate.Done) Modifier.clickable { onPick(candidate) } else Modifier
        ),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            when (candidate) {
                is TranslationCandidate.Running -> com.ai.ui.shared.AnimatedHourglass(fontSize = 22.sp, modifier = Modifier.padding(end = 12.dp))
                is TranslationCandidate.Done -> Text(com.ai.data.MetadataIconsHolder.current.world, fontSize = 22.sp, modifier = Modifier.padding(end = 12.dp))
                is TranslationCandidate.Error -> Text(com.ai.data.MetadataIconsHolder.current.statusFailed, fontSize = 22.sp, modifier = Modifier.padding(end = 12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(providerModel, fontSize = 11.sp, color = AppColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when (candidate) {
                    is TranslationCandidate.Done -> Text(candidate.text, fontSize = 14.sp, color = AppColors.TextPrimary, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    is TranslationCandidate.Error -> Text(candidate.reason, fontSize = 11.sp, color = AppColors.DangerAccent, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    is TranslationCandidate.Running -> Text("Translating…", fontSize = 11.sp, color = AppColors.TextTertiary)
                }
            }
            if (cost > 0.0) {
                Text("${com.ai.ui.shared.formatCents(cost)} ¢", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AppColors.TextTertiary, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
