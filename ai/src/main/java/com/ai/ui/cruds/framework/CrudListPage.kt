package com.ai.ui.cruds.framework

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.horizontalSwipeNavigation
import com.ai.ui.shared.verticalSwipeNavigation

/**
 * Uniform CRUD list page. Compact one-line text rows; only as many rows
 * as fit the screen are shown. Paging: swipe left / down for the next
 * page, right / up for the previous. The page indicator sits at the top.
 * Tapping a row opens its view page. The 🆕 add action lives in the
 * bottom icon bar (published via [TitleBar]); per-entry edit / copy /
 * delete live on the view page.
 *
 * Null [onAdd] hides the add glyph (fixed lists).
 */
@Composable
fun <T> CrudListPage(
    title: String,
    subject: String? = null,
    items: List<T>,
    line: (T) -> String,
    itemKey: (T) -> Any,
    onView: (T) -> Unit,
    onBack: () -> Unit,
    helpTopic: String? = null,
    onAdd: (() -> Unit)? = null,
    onHousekeeping: (() -> Unit)? = null,
    emptyMessage: String = "Nothing here yet.",
    /** Optional trailing content rendered at the end of each row (e.g. a
     *  🐞 trace-link). Receives the row's item. */
    rowTrailing: (@Composable (T) -> Unit)? = null
) {
    BackHandler { onBack() }
    // Don't key on items.size — that reset the page to 0 on every
    // add/delete, bouncing the user back to page 1. safePage already
    // coerces against the live totalPages, so out-of-range is handled.
    var page by rememberSaveable { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.AppBackground)
            .padding(16.dp)
    ) {
        TitleBar(helpTopic = helpTopic, title = title, subject = subject, onBackClick = onBack, onAdd = onAdd, onHousekeeping = onHousekeeping)

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(emptyMessage, color = AppColors.TextTertiary, fontSize = 16.sp)
            }
            return@Column
        }

        val rowHeight = 56.dp
        val indicatorReserve = 28.dp
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val pageSize = maxOf(1, ((maxHeight - indicatorReserve) / rowHeight).toInt())
            val totalPages = (items.size + pageSize - 1) / pageSize
            val safePage = page.coerceIn(0, totalPages - 1)
            val start = safePage * pageSize
            val end = minOf(start + pageSize, items.size)
            val pageItems = items.subList(start, end)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalSwipeNavigation(
                        key1 = safePage, key2 = totalPages,
                        atFirst = safePage <= 0,
                        atLast = safePage >= totalPages - 1,
                        onSwipeLeft = { if (safePage < totalPages - 1) page = safePage + 1 },
                        onSwipeRight = { if (safePage > 0) page = safePage - 1 }
                    )
                    .verticalSwipeNavigation(
                        key1 = safePage, key2 = totalPages,
                        atFirst = safePage <= 0,
                        atLast = safePage >= totalPages - 1,
                        onSwipeUp = { if (safePage > 0) page = safePage - 1 },
                        onSwipeDown = { if (safePage < totalPages - 1) page = safePage + 1 }
                    ),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (totalPages > 1) {
                    Text(
                        "Page ${safePage + 1} / $totalPages · ${items.size} items · swipe to page",
                        color = AppColors.TextTertiary, fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)
                    )
                }
                pageItems.forEach { item ->
                    CrudRow(
                        text = line(item),
                        onClick = { onView(item) },
                        trailing = rowTrailing?.let { rt -> { rt(item) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun CrudRow(text: String, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        // Fixed height so the per-page row count (computed from a 56.dp
        // row estimate) matches the actual rendered height — otherwise on
        // large-font accessibility settings the content-driven height grew
        // past the estimate and the last row on a page got clipped.
        modifier = Modifier.fillMaxWidth().height(50.dp).clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                color = AppColors.TextPrimary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            trailing?.invoke()
        }
    }
}
