package com.ai.ui.admin

import android.content.Context
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
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
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
data class BrokenItemRow(val id: String, val label: String, val detail: String, val traceFile: String? = null)

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
    /** Card tap → open this broken item's OWN detail/batch screen (not the
     *  report overview): restores the report then opens the item's screen via a
     *  view-only PendingBatchOpen. */
    onOpenItem: (BrokenBatch) -> Unit = {},
    onRestart: (BrokenBatch, BrokenItemMode) -> Unit,
    onDelete: (BrokenBatch, BrokenItemMode) -> Unit,
    onRestartItems: (BrokenBatch, BrokenItemMode, Set<String>) -> Unit,
    onDeleteItems: (BrokenBatch, BrokenItemMode, Set<String>) -> Unit,
    // Open a model's Model response screen — (reportId, agentId). Used by the
    // RESPONSES detail rows and the single-broken-agent card (which skips the
    // list and taps straight through).
    onOpenModel: (String, String) -> Unit = { _, _ -> },
    /** Open the API trace for an errored item — the 🐞 on each Errors-screen
     *  row that carries a trace file (gated by ApiTracer.ladybugLinksEnabled). */
    onOpenTrace: (String) -> Unit = {},
    loadItems: suspend (BrokenBatch, BrokenItemMode) -> List<BrokenItemRow>,
) {
    var viewing by remember { mutableStateOf<Pair<BrokenBatch, BrokenItemMode>?>(null) }
    var confirmDelete by remember { mutableStateOf<Pair<BrokenBatch, BrokenItemMode>?>(null) }

    // Full-screen overlay: the [view] detail screen. Returning here preserves
    // the list's remember state (overlay pattern used across the app).
    val v = viewing
    if (v != null) {
        val busy = busyKeys.any { it.startsWith(brokenWorkActionPrefix(v.first, v.second)) }
        if (v.first.kind == BatchFamilyKind.RESPONSES) {
            // Each broken agent is a tappable row — tapping opens that
            // model's Model response screen; the per-row 🗑 / ↻ act on just
            // that agent (drop it from the report / regenerate it).
            BrokenAgentsScreen(
                batch = v.first,
                mode = v.second,
                loadItems = loadItems,
                busy = busy,
                onBack = { viewing = null },
                onOpenModel = { agentId -> onOpenModel(v.first.reportId, agentId) },
                onRestartItem = { id -> if (!busy) onRestartItems(v.first, v.second, setOf(id)) },
                onDeleteItem = { id -> if (!busy) onDeleteItems(v.first, v.second, setOf(id)) },
            )
            return
        }
        BrokenItemsScreen(
            batch = v.first,
            mode = v.second,
            loadItems = loadItems,
            canDelete = !(v.first.kind == BatchFamilyKind.FAN_META && v.second == BrokenItemMode.UNFINISHED),
            busy = busy,
            onBack = { viewing = null },
            onRestart = { if (!busy) onRestart(v.first, v.second) },
            onDelete = { if (!busy) onDelete(v.first, v.second) },
            onRestartItems = { ids -> if (!busy) onRestartItems(v.first, v.second, ids) },
            onDeleteItems = { ids -> if (!busy) onDeleteItems(v.first, v.second, ids) },
            onOpenTrace = onOpenTrace,
        )
        return
    }

    // All broken work resolved → return to the screen that opened this one
    // instead of showing an empty "(nothing broken)" list. Composed only on
    // the main list (the detail-view path returns above), so it can't fire
    // while a detail overlay is open; items going empty means the last
    // recover + refreshBrokenBatches already completed.
    LaunchedEffect(items.isEmpty()) {
        if (items.isEmpty()) onBack()
    }

    BackHandler { onBack() }
    val warningGlyph = LocalMetadataIcons.current.statusWarning

    Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        // Both top-bar icons (the ⚠️ left glyph and the right logo) and the
        // title go Home. Suppress the global broken-work badge here so the
        // right icon is the Home AI-logo instead of a ⚠️ that would just
        // re-open the screen we're already on.
        CompositionLocalProvider(com.ai.ui.shared.LocalBrokenWork provides null) {
            TitleBar(
                helpTopic = "broken_work",
                title = "Broken work",
                subject = "Batch work that needs attention",
                onBackClick = onBack,
                reportIcon = warningGlyph,
                onReportIconClick = onNavigateHome,
                onTitleClick = onNavigateHome
            )
        }
        Text(
            "Batches with unfinished (app-kill) or errored items. Use the per-line view / delete / restart actions, or tap a card to open the report.",
            fontSize = 11.sp, color = AppColors.TextTertiary
        )
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) {
            // No "(nothing broken)" text — the LaunchedEffect above navigates
            // back to the calling screen as soon as the list empties.
            Box(Modifier.fillMaxWidth().weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                itemsIndexed(items, key = { _, b -> "${b.reportId}|${b.kind}|${b.key}" }) { index, batch ->
                    // A single broken agent (RESPONSES, one item) skips the
                    // agents list: its card taps straight to the model's
                    // Model response screen ([batch.key] holds the agentId).
                    val responsesSingle = batch.kind == BatchFamilyKind.RESPONSES &&
                        (batch.unfinishedCount + batch.errorCount) == 1
                    BrokenWorkItem(
                        batch, warningGlyph, index,
                        busyKeys = busyKeys,
                        onOpen = {
                            // Card tap → straight to the item, never an overview.
                            val mode = if (batch.errorCount > 0) BrokenItemMode.ERRORS else BrokenItemMode.UNFINISHED
                            when (batch.kind) {
                                // One broken agent → its Model response; several → the agents list.
                                BatchFamilyKind.RESPONSES ->
                                    if (responsesSingle) onOpenModel(batch.reportId, batch.key)
                                    else viewing = batch to mode
                                // Independent Meta/Rerank/Moderation: one → its detail; several → the list.
                                BatchFamilyKind.OTHER ->
                                    if (batch.errorCount + batch.unfinishedCount == 1) onOpenItem(batch)
                                    else viewing = batch to mode
                                // Batch screens (+ Regenerate fallback) → open the screen itself.
                                else -> onOpenItem(batch)
                            }
                        },
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
    // Regenerate and Info have no item list to open; every other kind does.
    val canView = batch.kind != BatchFamilyKind.REGENERATE && batch.kind != BatchFamilyKind.INFO
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
                // A single broken agent keeps the whole-card tap-through to
                // its Model response screen, so the view icon is redundant —
                // but its count line still carries the 🗑 / ↻ actions.
                val responsesSingle = batch.kind == BatchFamilyKind.RESPONSES &&
                    (batch.unfinishedCount + batch.errorCount) == 1
                if (batch.unfinishedCount > 0) {
                    val mode = BrokenItemMode.UNFINISHED
                    CountActionLine(
                        text = "${batch.unfinishedCount} ${if (batch.kind == BatchFamilyKind.RESPONSES) "interrupted" else "unfinished"}",
                        color = AppColors.WarningAccent,
                        busy = busyKeys.any { it.startsWith(brokenWorkActionPrefix(batch, mode)) },
                        canView = canView && !responsesSingle,
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
                    // A stamped 429 means the pool was cooling, not broken —
                    // tell the user a restart will likely clear those.
                    val rateLimitedHint = when {
                        batch.rateLimitedCount == 0 -> ""
                        batch.rateLimitedCount == batch.errorCount -> " (rate-limited)"
                        else -> " (${batch.rateLimitedCount} rate-limited)"
                    }
                    CountActionLine(
                        text = "${batch.errorCount} error${if (batch.errorCount == 1) "" else "s"}$rateLimitedHint",
                        color = AppColors.DangerAccent,
                        busy = busyKeys.any { it.startsWith(brokenWorkActionPrefix(batch, mode)) },
                        canView = canView && !responsesSingle,
                        // Info jobs are restart-only (nothing sensible to drop).
                        canDelete = batch.kind != BatchFamilyKind.INFO,
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
    canRestart: Boolean = true,
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
            if (canRestart) IconGlyph(icons.reload, onRestart)
        }
    }
}

@Composable
private fun IconGlyph(glyph: String, onClick: () -> Unit) {
    Text(
        glyph, fontSize = 22.sp, color = AppColors.TextSecondary,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 2.dp)
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
    onOpenTrace: (String) -> Unit = {},
) {
    BackHandler { onBack() }
    var rows by remember(batch, mode) { mutableStateOf<List<BrokenItemRow>?>(null) }
    var selectedIds by remember(batch, mode) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(batch, mode, busy) {
        if (busy) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { loadItems(batch, mode) }
        rows = loaded
        selectedIds = selectedIds.intersect(loaded.map { it.id }.toSet())
        if (loaded.isEmpty()) onBack()
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
                                // 🐞 trace link for an errored row that captured a
                                // trace (gated like every other ladybug in the app).
                                row.traceFile?.let { tf ->
                                    if (com.ai.data.ApiTracer.ladybugLinksEnabled) {
                                        Text(
                                            com.ai.data.MetadataIconsHolder.current.traces,
                                            fontSize = 20.sp, color = AppColors.TextSecondary,
                                            modifier = Modifier
                                                .clickable { onOpenTrace(tf) }
                                                .padding(start = 6.dp, top = 2.dp)
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

/** Detail for a RESPONSES batch with more than one broken agent: each model
 *  is a tappable row — tapping opens that model's Model response screen —
 *  with per-row 🗑 (drop the agent from the report, confirmed) and ↻
 *  (regenerate just that agent) icons. */
@Composable
fun BrokenAgentsScreen(
    batch: BrokenBatch,
    mode: BrokenItemMode,
    loadItems: suspend (BrokenBatch, BrokenItemMode) -> List<BrokenItemRow>,
    busy: Boolean,
    onBack: () -> Unit,
    onOpenModel: (String) -> Unit,
    onRestartItem: (String) -> Unit,
    onDeleteItem: (String) -> Unit,
) {
    BackHandler { onBack() }
    var rows by remember(batch, mode) { mutableStateOf<List<BrokenItemRow>?>(null) }
    var confirmDelete by remember(batch, mode) { mutableStateOf<BrokenItemRow?>(null) }
    // Re-keyed on [busy] so the list reloads after a per-row action lands —
    // and pops back once the last broken agent is recovered/removed.
    LaunchedEffect(batch, mode, busy) {
        if (busy) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { loadItems(batch, mode) }
        rows = loaded
        if (loaded.isEmpty()) onBack()
    }
    val title = if (mode == BrokenItemMode.ERRORS) "Errored models" else "Interrupted models"
    val icons = LocalMetadataIcons.current

    Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "broken_items",
            title = title,
            subject = "${batch.reportTitle} · ${batch.batchName}",
            onBackClick = onBack,
        )
        Text(
            "Tap a model to open its Model response screen; 🗑 drops it from the report, ↻ regenerates it.",
            fontSize = 11.sp, color = AppColors.TextTertiary
        )
        Spacer(Modifier.height(8.dp))
        if (busy) {
            Text("Working...", fontSize = 11.sp, color = AppColors.TextTertiary)
            Spacer(Modifier.height(6.dp))
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
                        Card(
                            colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                            modifier = Modifier.fillMaxWidth().clickable { onOpenModel(row.id) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
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
                                if (!busy) {
                                    IconGlyph(icons.delete) { confirmDelete = row }
                                    IconGlyph(icons.reload) { onRestartItem(row.id) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove model from report?") },
            text = { Text("Drops ${row.label} from the report. No API calls are made; the other models are kept.") },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { onDeleteItem(row.id); confirmDelete = null }
                ) {
                    Text("Delete", color = AppColors.DangerAccent)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
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

/** Display rows for the detail list. RESPONSES rows are primary report
 *  agents (read from [ReportStorage], not SecondaryResult storage); every
 *  other kind reuses [matchingBrokenRows]. */
fun loadBrokenItems(context: Context, batch: BrokenBatch, mode: BrokenItemMode): List<BrokenItemRow> {
    val errors = mode == BrokenItemMode.ERRORS
    if (batch.kind == BatchFamilyKind.RESPONSES) {
        val report = ReportStorage.getReport(context, batch.reportId) ?: return emptyList()
        return report.agents.filter {
            if (errors) it.reportStatus == ReportStatus.ERROR || it.reportStatus == ReportStatus.STOPPED
            else it.reportStatus == ReportStatus.PENDING || it.reportStatus == ReportStatus.RUNNING
        }.map { a ->
            val label = a.model.ifBlank { a.agentName }.ifBlank { "model" }
            val detail = if (errors) (a.errorMessage ?: "(no message)") else "Interrupted — never finished"
            BrokenItemRow(a.agentId, label, detail)
        }
    }
    return matchingBrokenRows(context, batch, mode).map { r ->
        val label = r.model.ifBlank { r.agentName }.ifBlank { "item" }
        val detail = if (errors) {
            if (batch.kind == BatchFamilyKind.FAN_META) (r.titleErrorMessage ?: r.iconErrorMessage ?: "")
            else (r.errorMessage ?: "")
        } else "Queued — never ran"
        BrokenItemRow(r.id, label, detail, r.traceFile)
    }
}
