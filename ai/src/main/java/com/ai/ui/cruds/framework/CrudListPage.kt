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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.ai.ui.shared.DeleteConfirmationDialog
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.horizontalSwipeNavigation

/**
 * Uniform CRUD list page. Compact one-line rows; only as many rows as
 * fit the screen are shown, with swipe left/right paging through the
 * rest. An "Add" button sits on top (hidden in fixed-list mode). Each
 * row carries ✏️ edit / 👯 copy / 🗑 delete icons on the right; tapping
 * the row anywhere else opens the view screen.
 *
 * Null [onAdd] / [onCopy] / [onDelete] hide those affordances (fixed
 * lists / entities that can't be copied).
 */
@Composable
fun <T> CrudListPage(
    title: String,
    items: List<T>,
    line: (T) -> String,
    itemKey: (T) -> Any,
    onView: (T) -> Unit,
    onEdit: (T) -> Unit,
    onBack: () -> Unit,
    deleteName: (T) -> String,
    helpTopic: String? = null,
    addLabel: String = "Add",
    onAdd: (() -> Unit)? = null,
    onCopy: ((T) -> Unit)? = null,
    onDelete: ((T) -> Unit)? = null,
    emptyMessage: String = "Nothing here yet."
) {
    BackHandler { onBack() }
    var pendingDelete by remember { mutableStateOf<T?>(null) }
    var page by remember(items.size) { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        TitleBar(helpTopic = helpTopic, title = title, onBackClick = onBack)
        if (onAdd != null) {
            Button(
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
            ) { Text(addLabel, maxLines = 1, softWrap = false) }
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(emptyMessage, color = AppColors.TextTertiary, fontSize = 16.sp)
            }
            return@Column
        }

        val rowHeight = 56.dp
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val pageSize = maxOf(1, (maxHeight / rowHeight).toInt())
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
                    ),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                pageItems.forEach { item ->
                    CrudRow(
                        text = line(item),
                        onClick = { onView(item) },
                        onEdit = { onEdit(item) },
                        onCopy = onCopy?.let { c -> { c(item) } },
                        onDelete = onDelete?.let { { pendingDelete = item } }
                    )
                }
                if (totalPages > 1) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "Page ${safePage + 1} / $totalPages · ${items.size} items · swipe to page",
                        color = AppColors.TextTertiary, fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    )
                }
            }
        }
    }

    pendingDelete?.let { item ->
        DeleteConfirmationDialog(
            entityType = title,
            entityName = deleteName(item),
            onConfirm = { onDelete?.invoke(item); pendingDelete = null },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun CrudRow(
    text: String,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onCopy: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
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
            Text("✏️", fontSize = 18.sp, modifier = Modifier
                .clickable(onClick = onEdit).padding(horizontal = 6.dp))
            if (onCopy != null) {
                Text("👯", fontSize = 18.sp, modifier = Modifier
                    .clickable(onClick = onCopy).padding(horizontal = 6.dp))
            }
            if (onDelete != null) {
                Text("🗑", fontSize = 18.sp, color = AppColors.Red, modifier = Modifier
                    .clickable(onClick = onDelete).padding(start = 6.dp))
            }
        }
    }
}
