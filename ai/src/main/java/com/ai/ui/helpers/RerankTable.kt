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

