package com.ai.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ReportStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared "open an example report" action used by both the Reports hub's
 * "Example AI Reports" card and the standalone [AllAiExamplesScreen].
 *
 * Returns the opener lambda — call it from a row with the tapped entry +
 * whether 👁 (View) was the tap (else 🔧 Manage). The matching dialogs
 * ("Report already exists" + the import spinner) are emitted by this
 * composable itself, so a caller only needs to invoke the result.
 *
 * Behaviour: if a report with the same title already exists, ask whether
 * to continue with it or overwrite it from the example; otherwise import
 * the bundle straight away. Both [onOpenReportManage]/[onOpenReportView]
 * receive the resolved report id and navigate to the chosen hub.
 */
@Composable
internal fun rememberExampleOpener(
    onOpenReportManage: (String) -> Unit,
    onOpenReportView: (String) -> Unit
): (com.ai.data.ExampleEntry, Boolean) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Shown while an example is being imported on first open.
    var loadingExample by remember { mutableStateOf(false) }
    // Set when the tapped example collides with an existing same-named
    // report — drives the "Report already exists" dialog. Carries the
    // tapped entry + whether 👁 (view) was the tap.
    var overwriteTarget by remember { mutableStateOf<Pair<com.ai.data.ExampleEntry, Boolean>?>(null) }

    // Import the bundled zip (spinner meanwhile) and route to the chosen
    // hub. When [overwrite] is set, every existing report with that title
    // is deleted first.
    fun importExampleAndOpen(entry: com.ai.data.ExampleEntry, view: Boolean, overwrite: Boolean) {
        scope.launch {
            loadingExample = true
            val rid = withContext(Dispatchers.IO) {
                try {
                    if (overwrite) {
                        ReportStorage.getAllReports(context)
                            .filter { it.title == entry.title }
                            .forEach { ReportStorage.deleteReport(context, it.id) }
                    }
                    com.ai.data.importExampleReport(context, entry.zipFile).newReportId
                } finally { loadingExample = false }
            }
            if (view) onOpenReportView(rid) else onOpenReportManage(rid)
        }
    }

    overwriteTarget?.let { (entry, view) ->
        AlertDialog(
            onDismissRequest = { overwriteTarget = null },
            title = { Text("Report already exists") },
            text = { Text("A report named \"${entry.title}\" already exists.") },
            confirmButton = {
                // Three choices, stacked — the labels are too long for one row.
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        overwriteTarget = null
                        scope.launch {
                            val rid = withContext(Dispatchers.IO) {
                                ReportStorage.getAllReports(context)
                                    .firstOrNull { it.title == entry.title }?.id
                            }
                            if (rid != null) { if (view) onOpenReportView(rid) else onOpenReportManage(rid) }
                        }
                    }) { Text("Continue with existing report") }
                    TextButton(onClick = {
                        overwriteTarget = null
                        importExampleAndOpen(entry, view, true)
                    }) { Text("Overwrite from Examples") }
                    TextButton(onClick = { overwriteTarget = null }) { Text("Cancel") }
                }
            }
        )
    }
    if (loadingExample) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("One moment") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Loading example report…", fontSize = 13.sp)
                }
            },
            confirmButton = {}
        )
    }

    return { entry, view ->
        scope.launch {
            val exists = withContext(Dispatchers.IO) {
                ReportStorage.getAllReports(context).any { it.title == entry.title }
            }
            if (exists) overwriteTarget = entry to view
            else importExampleAndOpen(entry, view, false)
        }
    }
}

/** "Example AI Reports" card on the Reports hub. Rows come from
 *  assets/examples/index.xml ([com.ai.data.ExampleEntry]) — not stored
 *  reports — so it can't reuse ReportsHubListCard/ReportListRow which
 *  take a persisted Report. Full-row tap opens Manage; the 👁 opens View;
 *  no 🗑 (examples aren't deletable). */
@Composable
internal fun ExampleReportsCard(
    examples: List<com.ai.data.ExampleEntry>,
    onOpenManage: (com.ai.data.ExampleEntry) -> Unit,
    onOpenView: (com.ai.data.ExampleEntry) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "💡", fontSize = 18.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Example AI Reports", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = AppColors.Purple, modifier = Modifier.weight(1f)
                )
            }
            examples.forEach { entry ->
                ExampleReportRow(entry = entry, onOpenManage = onOpenManage, onOpenView = onOpenView)
            }
        }
    }
}

/** One example row: report icon + title (tap → Manage) and trailing
 *  👁 (View) / 🔧 (Manage) icons. Mirrors the chrome of
 *  [com.ai.ui.shared.ReportListRow] minus the delete action. */
@Composable
internal fun ExampleReportRow(
    entry: com.ai.data.ExampleEntry,
    onOpenManage: (com.ai.data.ExampleEntry) -> Unit,
    onOpenView: (com.ai.data.ExampleEntry) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenManage(entry) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = entry.icon.ifBlank { "📝" }, fontSize = 22.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = entry.title, fontSize = 14.sp, color = Color.White,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        com.ai.ui.shared.ReportRowActionIcons(
            onOpenManage = { onOpenManage(entry) },
            onOpenView = { onOpenView(entry) }
        )
    }
}

/**
 * Standalone "AI Examples" full screen — the "Example AI Reports" card
 * lifted out of the Reports hub. Shown from the home menu when the user
 * has no agents yet (so the AI Reports hub itself is hidden) as a way to
 * try a real report without configuring a provider.
 *
 * Layout mirrors AllAiReportsScreen: a non-scrolling, height-fit
 * HorizontalPager of [ExampleReportRow]s with a "Page X of Y" header.
 */
@Composable
internal fun AllAiExamplesScreen(
    onNavigateBack: () -> Unit,
    onOpenReportManage: (String) -> Unit,
    onOpenReportView: (String) -> Unit
) {
    val context = LocalContext.current
    val examples by produceState(initialValue = emptyList<com.ai.data.ExampleEntry>(), Unit) {
        value = withContext(Dispatchers.IO) { com.ai.data.loadExampleIndex(context) }
    }
    val openExample = rememberExampleOpener(onOpenReportManage, onOpenReportView)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(title = "AI Examples", subject = "Open ready-made example reports", helpTopic = "ai_examples_screen", onBackClick = onNavigateBack,
            reportIcon = com.ai.ui.shared.LocalMetadataIcons.current.reportIcon,
            onReportIconClick = com.ai.ui.shared.LocalNavigateToReportsHub.current)
        if (examples.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("💡", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No examples available", color = AppColors.TextTertiary, fontSize = 14.sp)
                }
            }
            return@Column
        }
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val rowHeightDp = 44
            val headerReserveDp = 36
            val rowsPerPage = (((maxHeight.value - headerReserveDp) / rowHeightDp).toInt()).coerceAtLeast(1)
            val totalPages = ((examples.size + rowsPerPage - 1) / rowsPerPage).coerceAtLeast(1)
            val pagerState = rememberPagerState(initialPage = 0, pageCount = { totalPages })
            LaunchedEffect(totalPages) {
                if (pagerState.currentPage >= totalPages) {
                    pagerState.scrollToPage((totalPages - 1).coerceAtLeast(0))
                }
            }
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "Page ${pagerState.currentPage + 1} of $totalPages",
                    color = AppColors.TextTertiary, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                )
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 0.dp)
                ) { pageIndex ->
                    val from = pageIndex * rowsPerPage
                    val to = (from + rowsPerPage).coerceAtMost(examples.size)
                    val slice = if (from < to) examples.subList(from, to) else emptyList()
                    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Top) {
                        slice.forEach { entry ->
                            ExampleReportRow(
                                entry = entry,
                                onOpenManage = { openExample(it, false) },
                                onOpenView = { openExample(it, true) }
                            )
                        }
                    }
                }
            }
        }
    }
}
