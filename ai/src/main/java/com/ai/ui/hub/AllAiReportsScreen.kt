package com.ai.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.ReportListRow
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Paginated browser of every saved report, no vertical scroll.
 *  The body height is measured by [BoxWithConstraints], split into
 *  fixed pages of [ReportListRow]s, and rendered through a
 *  [HorizontalPager] so the user swipes between pages instead of
 *  scrolling. A small "Page X of Y" header sits above the pager.
 *
 *  Row taps + the per-row 🔧 / 👁 / 🗑 icons consume
 *  [com.ai.ui.shared.LocalReportListIconBundle] — wired by
 *  AppNavHost at the route mount.  */
@Composable
fun AllAiReportsScreen(
    onNavigateBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val refreshTick = com.ai.ui.shared.resumeRefreshTick()
    // Bumped after a row 🗑 delete completes — re-fires the disk
    // scan so the deleted row drops from the page immediately.
    var deleteTick by remember { mutableStateOf(0) }
    val reports by produceState(initialValue = emptyList<Report>(), refreshTick, deleteTick) {
        value = withContext(Dispatchers.IO) { ReportStorage.getAllReports(context) }
    }
    val bundle = com.ai.ui.shared.LocalReportListIconBundle.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            title = "All AI reports",
            helpTopic = "all_ai_reports_screen",
            onBackClick = onNavigateBack
        )
        if (reports.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📚", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No reports yet", color = AppColors.TextTertiary, fontSize = 14.sp)
                }
            }
            return@Column
        }
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Per-row height ≈ 56 dp (matches History row math).
            // Reserve a touch of vertical headroom for the page header.
            val rowHeightDp = 56
            val headerReserveDp = 36
            val rowsPerPage = (((maxHeight.value - headerReserveDp) / rowHeightDp).toInt()).coerceAtLeast(1)
            val totalPages = ((reports.size + rowsPerPage - 1) / rowsPerPage).coerceAtLeast(1)
            val pagerState = rememberPagerState(initialPage = 0, pageCount = { totalPages })
            // Re-clamp the active page when the dataset shrinks (e.g.
            // after a 🗑 delete drops the last row of the last page).
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
                    val to = (from + rowsPerPage).coerceAtMost(reports.size)
                    val slice = if (from < to) reports.subList(from, to) else emptyList()
                    Column(modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Top
                    ) {
                        slice.forEach { r ->
                            ReportListRow(
                                report = r,
                                onOpenManage = bundle.onOpenManage,
                                onOpenView = bundle.onOpenView,
                                onDelete = { rid ->
                                    scope.launch(Dispatchers.IO) {
                                        ReportStorage.deleteReport(context, rid)
                                        deleteTick++
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
