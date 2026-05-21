package com.ai.ui.cruds.models.blocked

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.model.BlockedModel
import com.ai.ui.cruds.framework.CrudViewPage
import com.ai.ui.cruds.framework.CrudField
import com.ai.ui.shared.AppColors

/** Read-only view of a blocked model with Edit / Copy / Delete on top. */
@Composable
internal fun BlockedModelView(
    item: BlockedModel,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit
) {
    CrudViewPage(
        title = "Blocked model",
        onEdit = onEdit, onCopy = onCopy, onDelete = onDelete, onBack = onBack,
        deleteName = "${item.providerId} · ${item.model}",
        helpTopic = "blocked_models"
    ) {
        CrudField("Provider", item.providerId)
        CrudField("Model", item.model)
        CrudField("Reason", item.reason.ifBlank { "(no reason)" })
    }
}
