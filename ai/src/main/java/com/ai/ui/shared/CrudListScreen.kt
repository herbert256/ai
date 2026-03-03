package com.ai.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Generic CRUD list screen pattern used by agents, flocks, swarms, parameters, prompts, system prompts.
 * Handles the common list layout: title bar, add button, empty state, sorted list with delete dialog.
 *
 * @param T The item type
 * @param title Screen title
 * @param items List of items to display
 * @param addLabel Text for the add button
 * @param emptyMessage Text shown when list is empty
 * @param sortKey Function to extract sort key from item
 * @param itemTitle Function to extract display title from item
 * @param itemSubtitle Function to extract display subtitle from item
 * @param itemExtraLine Optional function for a third line
 * @param onAdd Callback to add a new item
 * @param onEdit Callback to edit an item
 * @param onDelete Callback to delete an item
 * @param onBack Navigate back
 * @param onHome Navigate to home
 * @param deleteEntityType Entity type name for delete dialog (e.g. "Agent")
 * @param deleteEntityName Function to get entity name for delete dialog
 */
@Composable
fun <T> CrudListScreen(
    title: String,
    items: List<T>,
    addLabel: String,
    emptyMessage: String,
    sortKey: (T) -> String,
    itemTitle: (T) -> String,
    itemSubtitle: (T) -> String,
    itemExtraLine: ((T) -> String?)? = null,
    onAdd: () -> Unit,
    onEdit: (T) -> Unit,
    onDelete: (T) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    deleteEntityType: String,
    deleteEntityName: (T) -> String
) {
    var showDeleteDialog by remember { mutableStateOf<T?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        TitleBar(title = title, onBackClick = onBack, onAiClick = onHome)

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
        ) {
            Text(addLabel)
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(text = emptyMessage, color = AppColors.TextTertiary, fontSize = 16.sp)
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val sorted = remember(items) { items.sortedBy { sortKey(it).lowercase() } }
                sorted.forEach { item ->
                    SettingsListItemCard(
                        title = itemTitle(item),
                        subtitle = itemSubtitle(item),
                        extraLine = itemExtraLine?.invoke(item),
                        onClick = { onEdit(item) },
                        onDelete = { showDeleteDialog = item }
                    )
                }
            }
        }
    }

    showDeleteDialog?.let { item ->
        DeleteConfirmationDialog(
            entityType = deleteEntityType,
            entityName = deleteEntityName(item),
            onConfirm = {
                onDelete(item)
                showDeleteDialog = null
            },
            onDismiss = { showDeleteDialog = null }
        )
    }
}
