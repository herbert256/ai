package com.ai.ui.report.manage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.TitleCandidate

/** Live progress list for a title fan-out — one row per (provider, model):
 *  ⏳ while running, the returned title once done, ❌ on error. Tapping a
 *  Done row fills that title back into the editor (no save until Update). */
@Composable
fun AlternativeTitlesScreen(
    candidates: List<TitleCandidate>,
    onPickTitle: (String) -> Unit,
    onRestart: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "alternative_titles", title = "Alternative titles", subject = "Live title ideas from several models", onBackClick = onBack)

        // Stable order so rows don't reshuffle as candidates settle.
        val ordered = remember(candidates) {
            candidates.sortedWith(compareBy({ it.provider.id.lowercase() }, { it.model.lowercase() }))
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ordered.isEmpty()) {
                Text("Nothing here yet. Pick models on the previous screen.", fontSize = 12.sp, color = AppColors.TextTertiary)
            } else {
                ordered.forEach { c -> TitleCandidateRow(c, onPickTitle) }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onRestart, modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedButtonColors()) {
            Text("Restart", maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun TitleCandidateRow(candidate: TitleCandidate, onPickTitle: (String) -> Unit) {
    val iconModel = "${candidate.provider.id} · ${candidate.model}"
    val cost = when (candidate) {
        is TitleCandidate.Done -> candidate.cost
        is TitleCandidate.Error -> candidate.cost
        is TitleCandidate.Running -> 0.0
    }
    val tappable = candidate is TitleCandidate.Done
    Card(
        modifier = Modifier.fillMaxWidth().then(
            if (candidate is TitleCandidate.Done) Modifier.clickable { onPickTitle(candidate.title) } else Modifier
        ),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (candidate) {
                is TitleCandidate.Running -> com.ai.ui.shared.AnimatedHourglass(fontSize = 22.sp, modifier = Modifier.padding(end = 12.dp))
                is TitleCandidate.Done -> Text(com.ai.data.MetadataIconsHolder.current.label, fontSize = 22.sp, modifier = Modifier.padding(end = 12.dp))
                is TitleCandidate.Error -> Text(com.ai.data.MetadataIconsHolder.current.statusFailed, fontSize = 22.sp, modifier = Modifier.padding(end = 12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(iconModel, fontSize = 11.sp, color = AppColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when (candidate) {
                    is TitleCandidate.Done -> Text(candidate.title, fontSize = 14.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    is TitleCandidate.Error -> Text(candidate.reason, fontSize = 11.sp, color = AppColors.DangerAccent, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    is TitleCandidate.Running -> Text("Generating…", fontSize = 11.sp, color = AppColors.TextTertiary)
                }
            }
            if (cost > 0.0) {
                Text("${com.ai.ui.shared.formatCents(cost)} ¢", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AppColors.TextTertiary)
            }
        }
    }
}
