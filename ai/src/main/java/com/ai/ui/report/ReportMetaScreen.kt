package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.SecondaryRunState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Unified Meta screen reached from the Actions card. Lists every
 * Rerank/Summarize/Compare entry the report has — combined, no per-kind
 * tabs — newest first. Tapping a row opens [SecondaryResultDetailScreen].
 *
 * The bottom "Add" card carries the three launchers that used to live on
 * the Report result screen's Actions card. They stay enabled even while
 * a batch is in flight; the running entry's row shows a spinning
 * hourglass until each result lands. (Note: kicking off a second batch
 * cancels the previous one — see [com.ai.viewmodel.ReportViewModel.runSecondary].)
 */
@Composable
internal fun ReportMetaScreen(
    reportId: String,
    secondaryRun: SecondaryRunState?,
    onRerank: () -> Unit,
    onSummarize: () -> Unit,
    onCompare: () -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    var openId by remember { mutableStateOf<String?>(null) }
    // Re-read storage whenever a batch ticks (new placeholder, completion,
    // batch end) so running rows pick up their final ✅ / ❌ glyph
    // without the user having to leave & re-enter the screen.
    val runKey = secondaryRun?.let { "${it.reportId}:${it.kind}:${it.completed}/${it.total}" } ?: "idle"
    val results = remember(reportId, refreshTick, runKey) {
        SecondaryResultStorage.listForReport(context, reportId)
            .sortedByDescending { it.timestamp }
    }

    val openResult = openId?.let { id -> results.firstOrNull { it.id == id } }
    if (openResult != null) {
        SecondaryResultDetailScreen(
            result = openResult,
            onDelete = {
                onDelete(openResult.id)
                openId = null
                refreshTick++
            },
            onBack = { openId = null },
            onNavigateHome = onNavigateHome
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Meta", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(8.dp))

        if (results.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No meta results yet — pick one below.", color = AppColors.TextSecondary, fontSize = 14.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(results, key = { it.id }) { r ->
                    MetaRow(
                        r,
                        onClick = { openId = r.id },
                        onDelete = { onDelete(r.id); refreshTick++ }
                    )
                    HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceDark)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Add", fontSize = 11.sp, color = AppColors.TextTertiary, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = onRerank,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange),
                        contentPadding = PaddingValues(horizontal = 2.dp)
                    ) { Text("Rerank", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                    Button(
                        onClick = onSummarize,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange),
                        contentPadding = PaddingValues(horizontal = 2.dp)
                    ) { Text("Summarize", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                    Button(
                        onClick = onCompare,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange),
                        contentPadding = PaddingValues(horizontal = 2.dp)
                    ) { Text("Compare", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

@Composable
private fun MetaRow(r: SecondaryResult, onClick: () -> Unit, onDelete: () -> Unit) {
    val provider = AppService.findById(r.providerId)?.displayName ?: r.providerId
    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(r.timestamp))
    val kindLabel = when (r.kind) {
        SecondaryKind.RERANK -> "Rerank"
        SecondaryKind.SUMMARIZE -> "Summary"
        SecondaryKind.COMPARE -> "Compare"
    }
    var confirmDelete by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            r.errorMessage != null -> Text("❌", fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
            r.content.isNullOrBlank() -> {
                val transition = rememberInfiniteTransition(label = "meta-row-hourglass")
                val angle by transition.animateFloat(
                    initialValue = 0f, targetValue = 360f,
                    animationSpec = infiniteRepeatable(animation = tween(1500, easing = LinearEasing)),
                    label = "meta-row-rotation"
                )
                Text("⏳", fontSize = 16.sp,
                    modifier = Modifier.padding(end = 8.dp).rotate(angle))
            }
            else -> Text("✅", fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(kindLabel, fontSize = 11.sp, color = AppColors.Orange, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 6.dp))
                Text("$provider · ${r.model}", fontSize = 13.sp, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(ts, fontSize = 11.sp, color = AppColors.TextTertiary)
        }
        IconButton(onClick = { confirmDelete = true }) {
            Text("🗑", fontSize = 16.sp, color = AppColors.Red)
        }
    }

    if (confirmDelete) {
        val noun = when (r.kind) {
            SecondaryKind.RERANK -> "rerank"
            SecondaryKind.SUMMARIZE -> "summary"
            SecondaryKind.COMPARE -> "compare"
        }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this $noun?") },
            text = { Text("$provider · ${r.model}") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }
}
