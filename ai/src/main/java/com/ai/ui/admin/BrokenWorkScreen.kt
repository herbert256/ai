package com.ai.ui.admin

import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.LocalMetadataIcons
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.BatchFamilyKind
import com.ai.viewmodel.BrokenBatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which state of a [BrokenBatch] a row / detail screen acts on. */
enum class BrokenItemMode { UNFINISHED, ERRORS }

/** One row in the [BrokenItemsScreen] detail list. */
data class BrokenItemRow(val id: String, val label: String, val detail: String)

/** Batch families that carry per-line view/delete/restart actions. The
 *  others (Regenerate, single Meta/Rerank/Moderation) are surfaced for
 *  visibility only — tap the card to open the report. */
private val ACTIONABLE_KINDS = setOf(
    BatchFamilyKind.FAN_OUT, BatchFamilyKind.FAN_META, BatchFamilyKind.TOURNAMENT,
    BatchFamilyKind.JUDGES, BatchFamilyKind.COMPARE, BatchFamilyKind.TRANSLATION,
)

/** Full-screen list of batches that carry work needing attention —
 *  unfinished (stranded by an app-kill) and/or errored items — that the
 *  read-only background scan detected but did NOT fix. Reached from the
 *  ⚠️ that replaces the top-bar AI logo while [items] is non-empty. One
 *  card per batch, with per-line view/delete/restart actions; tapping the
 *  [view] icon opens [BrokenItemsScreen] as a full-screen overlay. */
@Composable
fun BrokenWorkScreen(
    items: List<BrokenBatch>,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenReport: (String) -> Unit,
    onRestart: (BrokenBatch, BrokenItemMode) -> Unit,
    onDelete: (BrokenBatch, BrokenItemMode) -> Unit,
    loadItems: suspend (BrokenBatch, BrokenItemMode) -> List<BrokenItemRow>,
) {
    var viewing by remember { mutableStateOf<Pair<BrokenBatch, BrokenItemMode>?>(null) }
    var confirmDelete by remember { mutableStateOf<Pair<BrokenBatch, BrokenItemMode>?>(null) }

    // Full-screen overlay: the [view] detail screen. Returning here preserves
    // the list's remember state (overlay pattern used across the app).
    val v = viewing
    if (v != null) {
        BrokenItemsScreen(
            batch = v.first,
            mode = v.second,
            loadItems = loadItems,
            canDelete = !(v.first.kind == BatchFamilyKind.FAN_META && v.second == BrokenItemMode.UNFINISHED),
            onBack = { viewing = null },
            onRestart = { onRestart(v.first, v.second); viewing = null },
            onDelete = { onDelete(v.first, v.second); viewing = null },
        )
        return
    }

    BackHandler { onBack() }
    val warningGlyph = LocalMetadataIcons.current.statusWarning

    Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "broken_work",
            title = "Broken work",
            subject = "Batch work that needs attention",
            onBackClick = onBack,
            reportIcon = warningGlyph,
            onReportIconClick = onNavigateHome,
            onTitleClick = onNavigateHome
        )
        Text(
            "Batches with unfinished (app-kill) or errored items. Use the per-line view / delete / restart actions, or tap a card to open the report.",
            fontSize = 11.sp, color = AppColors.TextTertiary
        )
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("(nothing broken)", color = AppColors.TextTertiary)
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                itemsIndexed(items, key = { _, b -> "${b.reportId}|${b.kind}|${b.key}" }) { index, batch ->
                    BrokenWorkItem(
                        batch, warningGlyph, index,
                        onOpen = { onOpenReport(batch.reportId) },
                        onView = { mode -> viewing = batch to mode },
                        onRestart = { mode -> onRestart(batch, mode) },
                        onDelete = { mode -> confirmDelete = batch to mode },
                    )
                }
            }
        }
    }

    confirmDelete?.let { (batch, mode) ->
        val count = if (mode == BrokenItemMode.ERRORS) batch.errorCount else batch.unfinishedCount
        val noun = if (mode == BrokenItemMode.ERRORS) "errored" else "unfinished"
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete items?") },
            text = { Text("Drops $count $noun item${if (count == 1) "" else "s"} from ${batch.batchName}. No API calls are made; finished items are kept.") },
            confirmButton = {
                TextButton(onClick = { onDelete(batch, mode); confirmDelete = null }) {
                    Text("Delete", color = AppColors.DangerAccent)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun BrokenWorkItem(
    batch: BrokenBatch,
    warningGlyph: String,
    index: Int,
    onOpen: () -> Unit,
    onView: (BrokenItemMode) -> Unit,
    onRestart: (BrokenItemMode) -> Unit,
    onDelete: (BrokenItemMode) -> Unit,
) {
    val background = if (index % 2 == 0) AppColors.CardBackground else AppColors.CardBackgroundAlt
    val actionable = batch.kind in ACTIONABLE_KINDS
    Card(
        colors = CardDefaults.cardColors(containerColor = background),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(warningGlyph, fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    batch.reportTitle.ifBlank { "(untitled report)" },
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    batch.batchName,
                    fontSize = 12.sp, color = AppColors.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (batch.unfinishedCount > 0) {
                    CountActionLine(
                        text = "${batch.unfinishedCount} unfinished",
                        color = AppColors.WarningAccent,
                        actionable = actionable,
                        // Fan Meta "unfinished" is a fan-out pair missing its
                        // title/icon — there's no item row to delete.
                        canDelete = batch.kind != BatchFamilyKind.FAN_META,
                        onView = { onView(BrokenItemMode.UNFINISHED) },
                        onDelete = { onDelete(BrokenItemMode.UNFINISHED) },
                        onRestart = { onRestart(BrokenItemMode.UNFINISHED) },
                    )
                }
                if (batch.errorCount > 0) {
                    CountActionLine(
                        text = "${batch.errorCount} error${if (batch.errorCount == 1) "" else "s"}",
                        color = AppColors.DangerAccent,
                        actionable = actionable,
                        canDelete = true,
                        onView = { onView(BrokenItemMode.ERRORS) },
                        onDelete = { onDelete(BrokenItemMode.ERRORS) },
                        onRestart = { onRestart(BrokenItemMode.ERRORS) },
                    )
                }
                Text(
                    DateUtils.getRelativeTimeSpanString(batch.timestamp).toString(),
                    fontSize = 10.sp, color = AppColors.TextTertiary, maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun CountActionLine(
    text: String,
    color: Color,
    actionable: Boolean,
    canDelete: Boolean,
    onView: () -> Unit,
    onDelete: () -> Unit,
    onRestart: () -> Unit,
) {
    val icons = LocalMetadataIcons.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(text, fontSize = 11.sp, color = color, modifier = Modifier.weight(1f))
        if (actionable) {
            IconGlyph(icons.view, onView)
            if (canDelete) IconGlyph(icons.delete, onDelete)
            IconGlyph(icons.reload, onRestart)
        }
    }
}

@Composable
private fun IconGlyph(glyph: String, onClick: () -> Unit) {
    Text(
        glyph, fontSize = 16.sp, color = AppColors.TextSecondary,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** Detail screen behind the [view] icon: the individual items of one batch
 *  in one state. Delete + restart live in the title bar and act on the
 *  whole listed set (same handlers as the card). */
@Composable
fun BrokenItemsScreen(
    batch: BrokenBatch,
    mode: BrokenItemMode,
    loadItems: suspend (BrokenBatch, BrokenItemMode) -> List<BrokenItemRow>,
    canDelete: Boolean,
    onBack: () -> Unit,
    onRestart: () -> Unit,
    onDelete: () -> Unit,
) {
    BackHandler { onBack() }
    var rows by remember(batch, mode) { mutableStateOf<List<BrokenItemRow>?>(null) }
    LaunchedEffect(batch, mode) {
        rows = withContext(Dispatchers.IO) { loadItems(batch, mode) }
    }
    var confirmDelete by remember { mutableStateOf(false) }
    val title = if (mode == BrokenItemMode.ERRORS) "Errors" else "Unfinished"

    Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "broken_items",
            title = title,
            subject = "${batch.reportTitle} · ${batch.batchName}",
            onBackClick = onBack,
            onReload = onRestart,
            onDelete = if (canDelete) ({ confirmDelete = true }) else null,
        )
        when (val list = rows) {
            null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("…", color = AppColors.TextTertiary)
            }
            else -> if (list.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("(no items)", color = AppColors.TextTertiary)
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(list, key = { it.id }) { row ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(
                                    row.label, fontSize = 13.sp, color = AppColors.TextPrimary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                if (row.detail.isNotBlank()) {
                                    Text(
                                        row.detail, fontSize = 11.sp, color = AppColors.TextSecondary,
                                        maxLines = 3, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete these items?") },
            text = { Text("Drops the listed ${title.lowercase()} items from ${batch.batchName}. No API calls are made.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = AppColors.DangerAccent)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

/** Read the individual rows of one [batch] in one [mode] from disk, for the
 *  detail screen. Mirrors the per-kind predicates the scan
 *  (SecondaryRunManager.detectBrokenBatchesForReport) groups by. Pure disk
 *  read — call off the main thread. */
fun loadBrokenItems(context: Context, batch: BrokenBatch, mode: BrokenItemMode): List<BrokenItemRow> {
    val rows = SecondaryResultStorage.listForReport(context, batch.reportId)
    val errors = mode == BrokenItemMode.ERRORS
    val matching = rows.filter { r ->
        val inBatch = when (batch.kind) {
            BatchFamilyKind.FAN_OUT, BatchFamilyKind.FAN_META ->
                r.kind == SecondaryKind.META && r.fanOutSourceAgentId != null && r.fanInOf == null &&
                    "${batch.reportId}|${r.metaPromptId}" == batch.key
            BatchFamilyKind.TOURNAMENT -> r.kind == SecondaryKind.TOURNAMENT && r.tournamentRole == "MATCH"
            BatchFamilyKind.JUDGES -> r.kind == SecondaryKind.JUDGES && r.tournamentRole == "MATCH"
            BatchFamilyKind.COMPARE -> r.kind == SecondaryKind.COMPARE
            BatchFamilyKind.TRANSLATION -> r.kind == SecondaryKind.TRANSLATE && r.translationRunId == batch.key
            else -> false
        }
        if (!inBatch) return@filter false
        if (batch.kind == BatchFamilyKind.FAN_META) {
            val fmError = !r.titleErrorMessage.isNullOrBlank() || !r.iconErrorMessage.isNullOrBlank()
            if (errors) fmError
            else (!r.content.isNullOrBlank() || r.durationMs != null) &&
                r.title.isNullOrBlank() && r.icon.isNullOrBlank() && !fmError
        } else {
            if (errors) r.errorMessage != null
            else r.content.isNullOrBlank() && r.errorMessage == null && r.durationMs == null
        }
    }
    return matching.map { r ->
        val label = r.model.ifBlank { r.agentName }.ifBlank { "item" }
        val detail = if (errors) {
            if (batch.kind == BatchFamilyKind.FAN_META) (r.titleErrorMessage ?: r.iconErrorMessage ?: "")
            else (r.errorMessage ?: "")
        } else "Queued — never ran"
        BrokenItemRow(r.id, label, detail)
    }
}
