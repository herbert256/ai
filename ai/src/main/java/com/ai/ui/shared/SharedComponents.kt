package com.ai.ui.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Generic title bar used across all screens.
 * Shows a back button on the left, optional title in the middle, and "AI" on the right.
 */
@Composable
fun TitleBar(
    title: String? = null,
    onBackClick: (() -> Unit)? = null,
    onAiClick: () -> Unit = {},
    backText: String = "< Back",
    leftContent: (@Composable RowScope.() -> Unit)? = null,
    centered: Boolean = false
) {
    if (centered) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "AI", fontSize = 36.sp, color = Color.White, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onAiClick() }
            )
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leftContent != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    leftContent()
                }
            } else if (onBackClick != null) {
                TextButton(onClick = onBackClick) {
                    Text(backText, color = Color.White, fontSize = 16.sp)
                }
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }
            if (title != null) {
                Text(
                    text = title, style = MaterialTheme.typography.titleLarge, color = Color.White,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center
                )
            }
            TextButton(onClick = onAiClick) {
                Text("AI", color = Color.White, fontSize = 16.sp)
            }
        }
    }
}

/**
 * Reusable delete confirmation dialog.
 */
@Composable
fun DeleteConfirmationDialog(
    entityType: String,
    entityName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $entityType") },
        text = { Text("Are you sure you want to delete \"$entityName\"?") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = AppColors.Red) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Reusable list item card with title, subtitle, and delete button.
 */
@Composable
fun SettingsListItemCard(
    title: String,
    subtitle: String,
    extraLine: String? = null,
    subtitleColor: Color = AppColors.TextTertiary,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color.White)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = subtitle, fontSize = 14.sp, color = subtitleColor)
                if (extraLine != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = extraLine, fontSize = 12.sp, color = AppColors.TextDim)
                }
            }
            IconButton(onClick = onDelete) {
                Text("X", color = AppColors.Red, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Reusable collapsible card with a clickable title header.
 */
@Composable
fun CollapsibleCard(
    title: String,
    defaultExpanded: Boolean = false,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f)
                )
                if (summary != null && !expanded) {
                    Text(
                        text = summary, style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextTertiary, modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Text(text = if (expanded) "▾" else "▸", color = AppColors.TextTertiary, fontSize = 16.sp)
            }
            if (expanded) { content() }
        }
    }
}

/**
 * Hub navigation card - clickable card with title and description.
 */
@Composable
fun HubCard(
    title: String,
    description: String,
    onClick: () -> Unit,
    containerColor: Color = AppColors.CardBackground
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color.White)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = description, fontSize = 14.sp, color = AppColors.TextTertiary)
        }
    }
}
