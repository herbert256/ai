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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    items: List<T>,
    line: (T) -> String,
    itemKey: (T) -> Any,
    onView: (T) -> Unit,
    onBack: () -> Unit,
    helpTopic: String? = null,
    onAdd: (() -> Unit)? = null,
    onHousekeeping: (() -> Unit)? = null,
    emptyMessage: String = "Nothing here yet."
) {
    BackHandler { onBack() }
    var page by remember(items.size) { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        TitleBar(helpTopic = helpTopic, title = title, onBackClick = onBack, onAdd = onAdd, onHousekeeping = onHousekeeping)

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
                    CrudRow(text = line(item), onClick = { onView(item) })
                }
            }
        }
    }
}

@Composable
private fun CrudRow(text: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
