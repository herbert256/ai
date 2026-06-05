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
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.LocalMetadataIcons
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.BatchFamilyKind
import com.ai.viewmodel.BrokenBatch
import com.ai.viewmodel.BrokenItemMode
import com.ai.viewmodel.BrokenWorkLiveState
import com.ai.viewmodel.BrokenWorkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One row in the [BrokenItemsScreen] detail list. */
data class BrokenItemRow(val id: String, val label: String, val detail: String)

fun brokenWorkActionPrefix(batch: BrokenBatch, mode: BrokenItemMode): String =
    "${batch.reportId}|${batch.kind}|${batch.key}|${mode.name}"

fun brokenWorkActionKey(batch: BrokenBatch, mode: BrokenItemMode, itemIds: Set<String> = emptySet()): String =
    if (itemIds.isEmpty()) brokenWorkActionPrefix(batch, mode)
    else "${brokenWorkActionPrefix(batch, mode)}|items:${itemIds.sorted().joinToString(",")}"

/** Full-screen list of batches that carry work needing attention —
 *  unfinished (stranded by an app-kill) and/or errored items — that the
 *  read-only background scan detected but did NOT fix. Reached from the
 *  ⚠️ that replaces the top-bar AI logo while [items] is non-empty. One
 *  card per batch, with per-line view/delete/restart actions; tapping the
 *  [view] icon opens [BrokenItemsScreen] as a full-screen overlay. */
@Composable
fun BrokenWorkScreen(
    items: List<BrokenBatch>,
    busyKeys: Set<String> = emptySet(),
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenReport: (String) -> Unit,
    onRestart: (BrokenBatch, BrokenItemMode) -> Unit,
    onDelete: (BrokenBatch, BrokenItemMode) -> Unit,
    onRestartItems: (BrokenBatch, BrokenItemMode, Set<String>) -> Unit,
    onDeleteItems: (BrokenBatch, BrokenItemMode, Set<String>) -> Unit,
    loadItems: suspend (BrokenBatch, BrokenItemMode) -> List<BrokenItemRow>,
) {
    var viewing by remember { mutableStateOf<Pair<BrokenBatch, BrokenItemMode>?>(null) }
    var confirmDelete by remember { mutableStateOf<Pair<BrokenBatch, BrokenItemMode>?>(null) }

    // Full-screen overlay: the [view] detail screen. Returning here preserves
    // the list's remember state (overlay pattern used across the app).
    val v = viewing
    if (v != null) {
        val busy = busyKeys.any { it.startsWith(brokenWorkActionPrefix(v.first, v.second)) }
        BrokenItemsScreen(
            batch = v.first,
            mode = v.second,
            loadItems = loadItems,
            canDelete = !(v.first.kind == BatchFamilyKind.FAN_META && v.second == BrokenItemMode.UNFINISHED),
            busy = busy,
            onBack = { viewing = null },
            onRestart = { if (!busy) { onRestart(v.first, v.second); viewing = null } },
            onDelete = { if (!busy) { onDelete(v.first, v.second); viewing = null } },
            onRestartItems = { ids -> if (!busy) { onRestartItems(v.first, v.second, ids); viewing = null } },
            onDeleteItems = { ids -> if (!busy) { onDeleteItems(v.first, v.second, ids); viewing = null } },
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
                        busyKeys = busyKeys,
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
        val busy = busyKeys.any { it.startsWith(brokenWorkActionPrefix(batch, mode)) }
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete items?") },
            text = { Text("Drops $count $noun item${if (count == 1) "" else "s"} from ${batch.batchName}. No API calls are made; finished items are kept.") },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { onDelete(batch, mode); confirmDelete = null }
                ) {
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
    busyKeys: Set<String>,
    onOpen: () -> Unit,
    onView: (BrokenItemMode) -> Unit,
    onRestart: (BrokenItemMode) -> Unit,
    onDelete: (BrokenItemMode) -> Unit,
) {
    val background = if (index % 2 == 0) AppColors.CardBackground else AppColors.CardBackgroundAlt
    // Regenerate has no item list to open; every other kind does.
    val canView = batch.kind != BatchFamilyKind.REGENERATE
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
                    val mode = BrokenItemMode.UNFINISHED
                    CountActionLine(
                        text = "${batch.unfinishedCount} unfinished",
                        color = AppColors.WarningAccent,
                        busy = busyKeys.any { it.startsWith(brokenWorkActionPrefix(batch, mode)) },
                        canView = canView,
                        // Fan Meta "unfinished" is a fan-out pair missing its
                        // title/icon — there's no item row to delete.
                        canDelete = batch.kind != BatchFamilyKind.FAN_META,
                        onView = { onView(mode) },
                        onDelete = { onDelete(mode) },
                        onRestart = { onRestart(mode) },
                    )
                }
                if (batch.errorCount > 0) {
                    val mode = BrokenItemMode.ERRORS
                    CountActionLine(
                        text = "${batch.errorCount} error${if (batch.errorCount == 1) "" else "s"}",
                        color = AppColors.DangerAccent,
                        busy = busyKeys.any { it.startsWith(brokenWorkActionPrefix(batch, mode)) },
                        canView = canView,
                        canDelete = true,
                        onView = { onView(mode) },
                        onDelete = { onDelete(mode) },
                        onRestart = { onRestart(mode) },
                    )
                }
                // Single-item entries (one secondary, a one-error batch, or a
                // paused regenerate) show their failure message inline.
                batch.errorMessage?.let { msg ->
                    Text(
                        msg, fontSize = 11.sp, color = AppColors.DangerAccent,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
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
    busy: Boolean,
    canView: Boolean,
    canDelete: Boolean,
    onView: () -> Unit,
    onDelete: () -> Unit,
    onRestart: () -> Unit,
) {
    val icons = LocalMetadataIcons.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(text, fontSize = 11.sp, color = color, modifier = Modifier.weight(1f))
        if (busy) {
            Text("Working...", fontSize = 11.sp, color = AppColors.TextTertiary)
        } else {
            if (canView) IconGlyph(icons.view, onView)
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
    busy: Boolean,
    onBack: () -> Unit,
    onRestart: () -> Unit,
    onDelete: () -> Unit,
    onRestartItems: (Set<String>) -> Unit,
    onDeleteItems: (Set<String>) -> Unit,
) {
    BackHandler { onBack() }
    var rows by remember(batch, mode) { mutableStateOf<List<BrokenItemRow>?>(null) }
    var selectedIds by remember(batch, mode) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(batch, mode) {
        val loaded = withContext(Dispatchers.IO) { loadItems(batch, mode) }
        rows = loaded
        selectedIds = selectedIds.intersect(loaded.map { it.id }.toSet())
    }
    var confirmDelete by remember { mutableStateOf(false) }
    val title = if (mode == BrokenItemMode.ERRORS) "Errors" else "Unfinished"
    val hasSelection = selectedIds.isNotEmpty()

    Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "broken_items",
            title = title,
            subject = "${batch.reportTitle} · ${batch.batchName}",
            onBackClick = onBack,
            onReload = if (busy) null else ({
                if (hasSelection) onRestartItems(selectedIds) else onRestart()
            }),
            onDelete = if (canDelete && !busy) ({ confirmDelete = true }) else null,
        )
        if (busy) {
            Text("Working...", fontSize = 11.sp, color = AppColors.TextTertiary)
            Spacer(Modifier.height(6.dp))
        }
        val currentRows = rows
        if (currentRows != null && currentRows.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (hasSelection) "${selectedIds.size} selected" else "No selection",
                    fontSize = 11.sp,
                    color = AppColors.TextTertiary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    enabled = !busy,
                    onClick = {
                        selectedIds = if (selectedIds.size == currentRows.size) emptySet()
                        else currentRows.map { it.id }.toSet()
                    }
                ) {
                    Text(if (selectedIds.size == currentRows.size) "Clear" else "Select all", fontSize = 11.sp)
                }
            }
        }
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
                        val selected = row.id in selectedIds
                        Card(
                            colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (!busy) {
                                    selectedIds = if (selected) selectedIds - row.id else selectedIds + row.id
                                }
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { checked ->
                                        selectedIds = if (checked) selectedIds + row.id else selectedIds - row.id
                                    },
                                    enabled = !busy
                                )
                                Column(Modifier.weight(1f).padding(top = 2.dp)) {
                                    Text(
                                        row.label, fontSize = 13.sp, color = AppColors.TextPrimary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    if (row.detail.isNotBlank()) {
                                        Text(
                                            row.detail, fontSize = 11.sp,
                                            color = if (mode == BrokenItemMode.ERRORS) AppColors.DangerAccent else AppColors.TextSecondary,
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
    }

    if (confirmDelete) {
        val deleteIds = selectedIds
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (deleteIds.isEmpty()) "Delete these items?" else "Delete selected items?") },
            text = {
                Text(
                    if (deleteIds.isEmpty()) "Drops the listed ${title.lowercase()} items from ${batch.batchName}. No API calls are made."
                    else "Drops ${deleteIds.size} selected ${title.lowercase()} item${if (deleteIds.size == 1) "" else "s"} from ${batch.batchName}. No API calls are made."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        confirmDelete = false
                        if (deleteIds.isEmpty()) onDelete() else onDeleteItems(deleteIds)
                    }
                ) {
                    Text("Delete", color = AppColors.DangerAccent)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

/** The individual SecondaryResult rows of one [batch] in one [mode], read
 *  from disk. Mirrors the per-kind predicates the scan
 *  (SecondaryRunManager.detectBrokenBatchesForReport) groups by — the one
 *  place that knows which rows belong to a batch, reused by the detail
 *  screen and the OTHER recovery dispatch. Pure disk read — call off the
 *  main thread. */
fun matchingBrokenRows(context: Context, batch: BrokenBatch, mode: BrokenItemMode): List<SecondaryResult> {
    val rows = SecondaryResultStorage.listForReport(context, batch.reportId)
    return BrokenWorkPolicy.matchingRows(rows, batch, mode, BrokenWorkLiveState())
}

/** Display rows for the [BrokenItemsScreen] detail list. */
fun loadBrokenItems(context: Context, batch: BrokenBatch, mode: BrokenItemMode): List<BrokenItemRow> {
    val errors = mode == BrokenItemMode.ERRORS
    return matchingBrokenRows(context, batch, mode).map { r ->
        val label = r.model.ifBlank { r.agentName }.ifBlank { "item" }
        val detail = if (errors) {
            if (batch.kind == BatchFamilyKind.FAN_META) (r.titleErrorMessage ?: r.iconErrorMessage ?: "")
            else (r.errorMessage ?: "")
        } else "Queued — never ran"
        BrokenItemRow(r.id, label, detail)
    }
}
