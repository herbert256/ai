package com.ai.ui.cruds.framework

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.DeleteConfirmationDialog
import com.ai.ui.shared.TitleBar

/**
 * Uniform CRUD view (read-only detail) page. Edit / Copy / Delete live in
 * the bottom icon bar (✏️ / 👯 / 🗑, published via [TitleBar]; Copy /
 * Delete omitted when null), then the entity's read-only [content].
 */
@Composable
fun CrudViewPage(
    title: String,
    onEdit: () -> Unit,
    onBack: () -> Unit,
    deleteName: String,
    helpTopic: String? = null,
    onCopy: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    BackHandler { onBack() }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        TitleBar(
            helpTopic = helpTopic, title = title, onBackClick = onBack,
            onEdit = onEdit,
            onCopyReport = onCopy,
            onDelete = onDelete?.let { { confirmDelete = true } }
        )
        Spacer(modifier = Modifier.height(12.dp))
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            content()
        }
    }
    if (confirmDelete && onDelete != null) {
        DeleteConfirmationDialog(
            entityType = title,
            entityName = deleteName,
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false }
        )
    }
}

/** Labelled read-only field for CRUD view screens: a dim label above
 *  the value, with breathing room below. */
@Composable
fun CrudField(label: String, value: String) {
    Text(label, color = AppColors.TextTertiary, fontSize = 12.sp)
    Text(value, color = androidx.compose.ui.graphics.Color.White, fontSize = 15.sp)
    Spacer(modifier = Modifier.height(12.dp))
}
