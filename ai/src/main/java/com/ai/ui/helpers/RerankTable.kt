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
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.horizontalSwipeNavigation
import com.ai.ui.shared.modelInfoClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import java.util.Locale

internal data class RerankRow(val id: Int, val rank: Int?, val score: Double?, val reason: String?)

/** Render a rerank score without a trailing ".0" for whole numbers
 *  (integer-scale models) but with up to 3 decimals for fractional ones. */
internal fun formatRerankScore(score: Double): String =
    if (score == score.toLong().toDouble()) score.toLong().toString()
    else String.format(Locale.US, "%.3f", score).trimEnd('0').trimEnd('.')

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
    // A valid but empty array is "no rows", not a parse failure.
    if (arr.size() == 0) return emptyList()
    val rows = arr.mapNotNull { el ->
        if (!el.isJsonObject) return@mapNotNull null
        val obj = el.asJsonObject
        // asInt throws NumberFormatException on non-numeric primitives
        // (e.g. {"id": "gpt-4o"}) — deviant rows must fall back, not crash.
        val id = obj.get("id")?.takeIf { it.isJsonPrimitive }
            ?.let { try { it.asInt } catch (_: Exception) { null } }
            ?: return@mapNotNull null
        val rank = obj.get("rank")?.takeIf { it.isJsonPrimitive }
            ?.let { try { it.asInt } catch (_: Exception) { null } }
        // Keep the score as a Double — some models return a fractional
        // score (0.87); the old toInt() truncated those to 0.
        val score = obj.get("score")?.takeIf { it.isJsonPrimitive }?.let {
            try { it.asDouble } catch (_: Exception) { it.asString.toDoubleOrNull() }
        }
        val reason = obj.get("reason")?.takeIf { it.isJsonPrimitive }?.asString
        RerankRow(id, rank, score, reason)
    }
    if (rows.isEmpty()) return null
    return rows.sortedWith(
        compareBy<RerankRow> { it.rank ?: Int.MAX_VALUE }.thenBy { it.id }
    )
}

@Composable
internal fun RerankTable(
    rows: List<RerankRow>,
    agentLabels: Map<Int, String>,
    /** When non-null, each ranking row becomes clickable and fires this with
     *  the tapped row (used by the Tournament result screen to drill into a
     *  model's head-to-heads). Null → rows are inert (the default). */
    onRowClick: ((RerankRow) -> Unit)? = null,
    /** Show the Reason column (default true). Tournament hides it. */
    showReason: Boolean = true,
    /** When non-null, format every score to exactly this many decimals
     *  (e.g. Tournament Davidson always 1dp, even 100.0).
     *  Null → the default trim-trailing-zeros [formatRerankScore]. */
    scoreDecimals: Int? = null
) {
    val hColor = AppColors.InfoAccent
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
                if (showReason) Text("Reason", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp))
            }
            HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
            rows.forEach { r ->
                val label = agentLabels[r.id] ?: "[${r.id}] (unknown)"
                Row(modifier = Modifier
                    .then(if (onRowClick != null) Modifier.clickable { onRowClick(r) } else Modifier)
                    .padding(vertical = 6.dp)) {
                    Text(r.rank?.toString() ?: "—", fontSize = 12.sp, color = AppColors.TextSecondary,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text(label, fontSize = 12.sp, color = AppColors.TextPrimary,
                        modifier = Modifier.width(220.dp).padding(start = 8.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        r.score?.let { s -> scoreDecimals?.let { String.format(Locale.US, "%.${it}f", s) } ?: formatRerankScore(s) } ?: "",
                        fontSize = 12.sp, color = AppColors.SuccessAccent,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    if (showReason) Text(r.reason.orEmpty(), fontSize = 12.sp, color = AppColors.TextTertiary,
                        modifier = Modifier.widthIn(min = 200.dp, max = 360.dp).padding(start = 8.dp))
                }
                HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
            }
        }
    }
}
