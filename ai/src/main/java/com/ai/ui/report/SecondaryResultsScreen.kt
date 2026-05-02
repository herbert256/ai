package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiTracer
import com.ai.data.AppService
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lists every persisted [SecondaryResult] of a given [kind] for [reportId].
 * Tapping a row opens [SecondaryResultDetailScreen]; the row also exposes a
 * trash icon for direct deletion. When the list reaches zero entries the
 * screen pops itself back to the report.
 */
@Composable
internal fun SecondaryResultsScreen(
    reportId: String,
    kind: SecondaryKind,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    var openId by remember { mutableStateOf<String?>(null) }
    val results by produceState(initialValue = emptyList<SecondaryResult>(), reportId, kind, refreshTick) {
        value = withContext(Dispatchers.IO) {
            SecondaryResultStorage.listForReport(context, reportId, kind)
        }
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

    val title = when (kind) {
        SecondaryKind.RERANK -> "Reranks"
        SecondaryKind.SUMMARIZE -> "Summaries"
        SecondaryKind.COMPARE -> "Compares"
        SecondaryKind.MODERATION -> "Moderations"
        SecondaryKind.TRANSLATE -> "Translations"
    }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = title, onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(8.dp))

        if (results.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No ${title.lowercase()} for this report", color = AppColors.TextSecondary, fontSize = 14.sp)
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(results, key = { it.id }) { r ->
                SecondaryRow(
                    r,
                    onClick = { openId = r.id },
                    onDelete = { onDelete(r.id); refreshTick++ }
                )
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
            }
        }
    }
}

@Composable
private fun SecondaryRow(r: SecondaryResult, onClick: () -> Unit, onDelete: () -> Unit) {
    val provider = AppService.findById(r.providerId)?.displayName ?: r.providerId
    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(r.timestamp))
    var confirmDelete by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val statusEmoji = when {
            r.errorMessage != null -> "❌"
            r.content.isNullOrBlank() -> "⏳"
            else -> "✅"
        }
        Text(statusEmoji, fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("$provider · ${r.model}", fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            SecondaryKind.MODERATION -> "moderation"
            SecondaryKind.TRANSLATE -> "translation"
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

@Composable
internal fun SecondaryResultDetailScreen(
    result: SecondaryResult,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToTraceFile: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> }
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val providerService = AppService.findById(result.providerId)
    val provider = providerService?.displayName ?: result.providerId
    val title = when (result.kind) {
        SecondaryKind.RERANK -> "Rerank"
        SecondaryKind.SUMMARIZE -> "Summary"
        SecondaryKind.COMPARE -> "Compare"
        SecondaryKind.MODERATION -> "Moderation"
        SecondaryKind.TRANSLATE -> "Translate"
    }
    var confirmDelete by remember { mutableStateOf(false) }

    // Find the trace file for this meta call: same report, same model,
    // and timestamp closest to the result. Multiple meta runs of the
    // same model on the same report would otherwise alias — the
    // closest-timestamp tiebreak picks the right one. May be null when
    // tracing was off at the time of the call.
    val traceFilenameState = produceState<String?>(initialValue = null, result.id) {
        value = withContext(Dispatchers.IO) {
            ApiTracer.getTraceFiles()
                .filter { it.reportId == result.reportId && it.model == result.model }
                .minByOrNull { kotlin.math.abs(it.timestamp - result.timestamp) }?.filename
        }
    }
    val traceFilename = traceFilenameState.value

    // Build the same id → "provider / model" map the @RESULTS@ block
    // used (success-ordered, 1-based) so the viewer can show real model
    // names instead of the bracketed [N] ids.
    val agentLabelsState = produceState(initialValue = emptyMap<Int, String>(), result.reportId) {
        value = withContext(Dispatchers.IO) {
            val report = ReportStorage.getReport(context, result.reportId) ?: return@withContext emptyMap<Int, String>()
            report.agents
                .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                .mapIndexed { idx, agent ->
                    val provDisplay = AppService.findById(agent.provider)?.displayName ?: agent.provider
                    (idx + 1) to "$provDisplay / ${agent.model}"
                }.toMap()
        }
    }
    val agentLabels = agentLabelsState.value

    // Translation comparison: only meaningful for SUMMARIZE / COMPARE,
    // and only when this secondary is a translated copy (its
    // translatedFromSecondaryId points at the source's untranslated
    // counterpart). Load the source's content lazily.
    val sourceContentState = produceState<String?>(
        initialValue = null,
        result.id, result.translatedFromSecondaryId
    ) {
        val srcId = result.translatedFromSecondaryId ?: return@produceState
        value = withContext(Dispatchers.IO) {
            val parent = ReportStorage.getReport(context, result.reportId) ?: return@withContext null
            val srcReportId = parent.sourceReportId ?: return@withContext null
            SecondaryResultStorage.get(context, srcReportId, srcId)?.content
        }
    }
    val sourceContent = sourceContentState.value
    val canShowTranslation =
        (result.kind == SecondaryKind.SUMMARIZE || result.kind == SecondaryKind.COMPARE) &&
            !sourceContent.isNullOrBlank() && !result.content.isNullOrBlank()
    var showTranslationCompare by remember { mutableStateOf(false) }

    if (showTranslationCompare && sourceContent != null && result.content != null) {
        TranslationCompareScreen(
            title = "Translation info — $title — $provider / ${result.model}",
            originalLabel = "Original",
            originalContent = sourceContent,
            translatedLabel = "Translation",
            translatedContent = result.content,
            onBack = { showTranslationCompare = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "$title — $provider", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(8.dp))

        Text(result.model, fontSize = 13.sp, color = AppColors.Blue, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        Text(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(result.timestamp)),
            fontSize = 11.sp, color = AppColors.TextTertiary)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when {
                result.errorMessage != null -> {
                    Text("Error", fontSize = 14.sp, color = AppColors.Red, fontWeight = FontWeight.SemiBold)
                    Text(result.errorMessage, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                }
                result.content.isNullOrBlank() -> {
                    Text("(no content)", color = AppColors.TextTertiary, fontSize = 13.sp)
                }
                result.kind == SecondaryKind.RERANK -> {
                    // Try to parse the structured JSON the rerank flow
                    // produces (chat-prompt path or callRerankApi path).
                    // Fall back to raw markdown rendering if the model's
                    // output deviated from the requested schema.
                    val rows = remember(result.content) { parseRerankRows(result.content) }
                    if (rows == null) {
                        ContentWithThinkSections(analysis = result.content)
                    } else {
                        RerankTable(rows = rows, agentLabels = agentLabels)
                    }
                }
                result.kind == SecondaryKind.MODERATION -> {
                    val rows = remember(result.content) { parseModerationRows(result.content) }
                    if (rows == null) {
                        ContentWithThinkSections(analysis = result.content)
                    } else {
                        ModerationTable(rows = rows, agentLabels = agentLabels)
                    }
                }
                else -> {
                    // Summarize / Compare: replace bracketed [N] tags
                    // inline with "[N: provider/model]" so the user sees
                    // who said what without flipping back to the report.
                    val annotated = remember(result.content, agentLabels) {
                        annotateBracketRefs(result.content, agentLabels)
                    }
                    ContentWithThinkSections(analysis = annotated)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        if (canShowTranslation) {
            Button(
                onClick = { showTranslationCompare = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
            ) { Text("Translation info", fontSize = 13.sp, maxLines = 1, softWrap = false) }
            Spacer(modifier = Modifier.height(8.dp))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { confirmDelete = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { Text("Delete", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            Button(
                onClick = { providerService?.let { onNavigateToModelInfo(it, result.model) } },
                enabled = providerService != null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { Text("Model", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            Button(
                onClick = { traceFilename?.let(onNavigateToTraceFile) },
                enabled = traceFilename != null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { Text("Trace", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this ${title.lowercase()}?") },
            text = { Text("$provider · ${result.model}") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }
}

internal data class RerankRow(val id: Int, val rank: Int?, val score: Int?, val reason: String?)

/** Parse the rerank flow's structured JSON output. Both the chat-prompt
 *  path and the dedicated rerank-API path emit the same
 *  `[{id, rank, score, reason}, ...]` shape. ``` fences are tolerated;
 *  any other shape returns null so the caller can fall back to raw
 *  rendering. */
internal fun parseRerankRows(content: String): List<RerankRow>? {
    val cleaned = content.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val arr = try {
        @Suppress("DEPRECATION")
        com.google.gson.JsonParser().parse(cleaned).takeIf { it.isJsonArray }?.asJsonArray
    } catch (_: Exception) { null } ?: return null
    if (arr.size() == 0) return null
    val rows = arr.mapNotNull { el ->
        if (!el.isJsonObject) return@mapNotNull null
        val obj = el.asJsonObject
        val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
        val rank = obj.get("rank")?.takeIf { it.isJsonPrimitive }?.asInt
        val score = obj.get("score")?.takeIf { it.isJsonPrimitive }?.asNumber?.toInt()
        val reason = obj.get("reason")?.takeIf { it.isJsonPrimitive }?.asString
        RerankRow(id, rank, score, reason)
    }
    if (rows.isEmpty()) return null
    return rows.sortedBy { it.rank ?: Int.MAX_VALUE }
}

@Composable
internal fun RerankTable(rows: List<RerankRow>, agentLabels: Map<Int, String>) {
    val hColor = AppColors.Blue
    val hSize = 12.sp
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                Text("Rank", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Text("Model", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(220.dp).padding(start = 8.dp))
                Text("Score", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Text("Reason", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp))
            }
            HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
            rows.forEach { r ->
                val label = agentLabels[r.id] ?: "[${r.id}] (unknown)"
                Row(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(r.rank?.toString() ?: "—", fontSize = 12.sp, color = AppColors.TextSecondary,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text(label, fontSize = 12.sp, color = Color.White,
                        modifier = Modifier.width(220.dp).padding(start = 8.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.score?.toString() ?: "", fontSize = 12.sp, color = AppColors.Green,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text(r.reason.orEmpty(), fontSize = 12.sp, color = AppColors.TextTertiary,
                        modifier = Modifier.widthIn(min = 200.dp, max = 360.dp).padding(start = 8.dp))
                }
                HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
            }
        }
    }
}

/** Replace bracketed `[N]` tags inline with `[N: provider/model]` when
 *  the id is in the agent map. Used by the Summarize / Compare viewers
 *  so a reader can see who's being cited without bouncing back to the
 *  report. Unmatched ids are left as-is. */
internal fun annotateBracketRefs(content: String?, agentLabels: Map<Int, String>): String {
    if (content.isNullOrBlank() || agentLabels.isEmpty()) return content.orEmpty()
    return Regex("""\[(\d+)\]""").replace(content) { m ->
        val id = m.groupValues[1].toIntOrNull() ?: return@replace m.value
        val label = agentLabels[id] ?: return@replace m.value
        "[$id: $label]"
    }
}

internal data class ModerationRow(
    val id: Int,
    val flagged: Boolean,
    val firedCategories: List<String>,
    /** All non-zero scores, sorted high → low so the table can show the
     *  top contributors even when nothing crossed the boolean threshold. */
    val topScores: List<Pair<String, Double>>
)

/** Parse the JSON [callModerationApi] writes into the SecondaryResult.
 *  Same `[{id, flagged, categories, scores}, …]` shape callRerankApi
 *  uses; tolerates ``` fences. Returns null on bad input. */
internal fun parseModerationRows(content: String): List<ModerationRow>? {
    val cleaned = content.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val arr = try {
        @Suppress("DEPRECATION")
        com.google.gson.JsonParser().parse(cleaned).takeIf { it.isJsonArray }?.asJsonArray
    } catch (_: Exception) { null } ?: return null
    if (arr.size() == 0) return null
    val rows = arr.mapNotNull { el ->
        if (!el.isJsonObject) return@mapNotNull null
        val obj = el.asJsonObject
        val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
        val flagged = obj.get("flagged")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val cats = obj.getAsJsonObject("categories")
        val scores = obj.getAsJsonObject("scores")
        val fired = cats?.entrySet()?.filter { it.value.asBoolean }?.map { it.key } ?: emptyList()
        val scoreList = scores?.entrySet()?.mapNotNull {
            val v = try { it.value.asDouble } catch (_: Exception) { return@mapNotNull null }
            it.key to v
        }?.sortedByDescending { it.second }?.take(3) ?: emptyList()
        ModerationRow(id, flagged, fired, scoreList)
    }
    if (rows.isEmpty()) return null
    return rows
}

@Composable
internal fun ModerationTable(rows: List<ModerationRow>, agentLabels: Map<Int, String>) {
    val hColor = AppColors.Blue
    val hSize = 12.sp
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                Text("Flag", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(40.dp))
                Text("Model", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(220.dp).padding(start = 8.dp))
                Text("Categories fired", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(220.dp).padding(start = 8.dp))
                Text("Top scores", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp))
            }
            HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
            rows.forEach { r ->
                val label = agentLabels[r.id] ?: "[${r.id}] (unknown)"
                val firedText = if (r.firedCategories.isEmpty()) "—" else r.firedCategories.joinToString(", ")
                val scoresText = r.topScores.joinToString(", ") { (k, v) -> "$k=${"%.3f".format(v)}" }
                Row(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(if (r.flagged) "🚩" else "✓", fontSize = 13.sp,
                        color = if (r.flagged) AppColors.Red else AppColors.Green,
                        modifier = Modifier.width(40.dp))
                    Text(label, fontSize = 12.sp, color = Color.White,
                        modifier = Modifier.width(220.dp).padding(start = 8.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(firedText, fontSize = 12.sp,
                        color = if (r.flagged) AppColors.Red else AppColors.TextTertiary,
                        modifier = Modifier.width(220.dp).padding(start = 8.dp))
                    Text(scoresText, fontSize = 11.sp, color = AppColors.TextTertiary,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.widthIn(min = 200.dp).padding(start = 8.dp))
                }
                HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
            }
        }
    }
}
