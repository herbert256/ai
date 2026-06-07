package com.ai.ui.helpers
import com.ai.ui.report.manage.*
import com.ai.ui.helpers.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.ai.ui.shared.CollapsibleCard
import com.ai.ui.shared.TitleBar
import java.util.Locale
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.horizontalSwipeNavigation
import com.ai.ui.shared.modelInfoClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext

internal data class ModerationRow(
    val id: Int,
    val flagged: Boolean,
    val firedCategories: List<String>,
    /** All non-zero scores, sorted high → low so the table can show the
     *  top contributors even when nothing crossed the boolean threshold. */
    val topScores: List<Pair<String, Double>>,
    /** Full per-category boolean map as returned by the API — the
     *  per-row detail screen iterates this to render every category
     *  (fired and not) with a check / cross. */
    val allCategories: Map<String, Boolean> = emptyMap(),
    /** Full per-category score map (0.0–1.0), unsorted. The detail
     *  screen renders these sorted high → low alongside [allCategories]. */
    val allScores: Map<String, Double> = emptyMap()
)

/** True when at least one moderation row across [rows] has at
 *  least one fired category. Walks every MODERATION secondary's
 *  content through [parseModerationRows] and short-circuits on
 *  the first flagged hit. Used by the View screen to flip the
 *  moderation tile's accent red ↔ green. */
internal fun anyModerationFlagged(rows: List<SecondaryResult>): Boolean =
    rows.filter { it.kind == SecondaryKind.MODERATION }
        .any { row ->
            val content = row.content ?: return@any false
            parseModerationRows(content)?.any { it.flagged } == true
        }

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
    // A valid but empty array is "no rows", not a parse failure — return
    // an empty list so callers render an empty table instead of falling
    // back to raw rendering.
    if (arr.size() == 0) return emptyList()
    val rows = arr.mapNotNull { el ->
        if (!el.isJsonObject) return@mapNotNull null
        val obj = el.asJsonObject
        val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
        val flagged = obj.get("flagged")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val cats = obj.getAsJsonObject("categories")
        val scores = obj.getAsJsonObject("scores")
        val allCats = cats?.entrySet()?.associate {
            it.key to (try { it.value.asBoolean } catch (_: Exception) { false })
        } ?: emptyMap()
        val allScores = scores?.entrySet()?.mapNotNull {
            // Tolerate string-encoded numbers ("0.5") before dropping —
            // some models return scores as JSON strings, and a strict
            // asDouble would silently lose the whole category.
            val v = try { it.value.asDouble } catch (_: Exception) {
                it.value?.asString?.toDoubleOrNull() ?: return@mapNotNull null
            }
            it.key to v
        }?.toMap() ?: emptyMap()
        val fired = allCats.filterValues { it }.keys.toList()
        val scoreList = allScores.entries
            .sortedByDescending { it.value }.take(3).map { it.key to it.value }
        ModerationRow(id, flagged, fired, scoreList, allCats, allScores)
    }
    if (rows.isEmpty()) return null
    return rows
}

@Composable
internal fun ModerationTable(
    rows: List<ModerationRow>,
    agentLabels: Map<Int, String>,
    onRowClick: (ModerationRow) -> Unit = {}
) {
    val hColor = AppColors.InfoAccent
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
                // Some moderation APIs flag a row from scores without setting
                // any per-category boolean. In that case surface the top
                // scoring category (with a "(by score)" note) so a flagged
                // 🚩 row never reads as "nothing fired".
                val firedText = when {
                    r.firedCategories.isNotEmpty() -> r.firedCategories.joinToString(", ")
                    r.flagged && r.topScores.isNotEmpty() -> "${r.topScores.first().first} (by score)"
                    else -> "—"
                }
                val scoresText = r.topScores.joinToString(", ") { (k, v) -> "$k=${String.format(Locale.US, "%.3f", v)}" }
                Row(modifier = Modifier.clickable { onRowClick(r) }.padding(vertical = 6.dp)) {
                    Text(if (r.flagged) com.ai.data.MetadataIconsHolder.current.validatePrompt else com.ai.data.MetadataIconsHolder.current.checkMark, fontSize = 13.sp,
                        color = if (r.flagged) AppColors.DangerAccent else AppColors.SuccessAccent,
                        modifier = Modifier.width(40.dp))
                    Text(label, fontSize = 12.sp, color = AppColors.TextPrimary,
                        modifier = Modifier.width(220.dp).padding(start = 8.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(firedText, fontSize = 12.sp,
                        color = if (r.flagged) AppColors.DangerAccent else AppColors.TextTertiary,
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

/**
 * Full-screen drill-in for a single moderation API call's result row.
 * Reached by tapping a row in [ModerationTable]. Shows the moderated
 * agent's label, flag status, full per-category breakdown (every
 * category — fired or not — with its score), and the original text
 * that was moderated.
 */
@Composable
internal fun ModerationCallDetailScreen(
    row: ModerationRow,
    agentLabel: String,
    agentResponse: String,
    moderationModelLabel: String,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val flagColor = if (row.flagged) AppColors.DangerAccent else AppColors.SuccessAccent
    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "moderation_call_detail",
            title = "Moderation result",
            subject = agentLabel,
            reportIcon = com.ai.ui.shared.LocalReportIcon.current,
            onBackClick = onBack,
            onCopy = if (agentResponse.isNotBlank()) {
                { com.ai.ui.shared.copyToClipboard(context, agentResponse, "moderated text") }
            } else null,
            onShare = if (agentResponse.isNotBlank()) {
                { com.ai.ui.shared.shareText(context, agentResponse, "Moderated text — $agentLabel") }
            } else null
        )

        Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            // Flag headline + meta line.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (row.flagged) "${com.ai.data.MetadataIconsHolder.current.validatePrompt} Flagged" else "${com.ai.data.MetadataIconsHolder.current.checkMark} Clean",
                    fontSize = 18.sp, color = flagColor, fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Moderated by $moderationModelLabel",
                fontSize = 11.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace
            )

            // Per-category breakdown — every category the API returned,
            // sorted by score (descending). Fired rows are red; the
            // rest stay dim. Score is rendered to 4 decimals; absent
            // scores show "—".
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Categories",
                fontSize = 13.sp, color = AppColors.InfoAccent, fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            val categoryOrder = remember(row.allCategories, row.allScores) {
                val names = (row.allCategories.keys + row.allScores.keys).distinct()
                names.sortedWith(
                    compareByDescending<String> { row.allScores[it] ?: -1.0 }
                        .thenBy { it.lowercase() }
                )
            }
            if (categoryOrder.isEmpty()) {
                Text("(no categories returned)", fontSize = 12.sp, color = AppColors.TextTertiary)
            } else {
                HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
                categoryOrder.forEach { cat ->
                    val fired = row.allCategories[cat] == true
                    val score = row.allScores[cat]
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (fired) com.ai.data.MetadataIconsHolder.current.validatePrompt else "·",
                            fontSize = 13.sp, color = if (fired) AppColors.DangerAccent else AppColors.TextTertiary,
                            modifier = Modifier.width(24.dp)
                        )
                        Text(
                            cat,
                            fontSize = 13.sp,
                            color = if (fired) AppColors.DangerAccent else AppColors.TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            score?.let { String.format(Locale.US, "%.4f", it) } ?: "—",
                            fontSize = 12.sp,
                            color = if (fired) AppColors.DangerAccent else AppColors.TextTertiary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
                }
            }

            // Original moderated text — what the moderation API actually
            // classified. Sits at the bottom so the verdict + category
            // breakdown stay above the fold.
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Moderated text",
                fontSize = 13.sp, color = AppColors.InfoAccent, fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (agentResponse.isBlank()) {
                Text("(source response no longer available)", fontSize = 12.sp, color = AppColors.TextTertiary)
            } else {
                ContentWithThinkSections(analysis = agentResponse)
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
