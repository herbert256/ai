package com.ai.ui.cruds.models.inaccessible

import androidx.compose.runtime.Composable
import com.ai.model.InaccessibleModel
import com.ai.ui.cruds.framework.CrudField
import com.ai.ui.cruds.framework.CrudViewPage

@Composable
internal fun InaccessibleModelView(
    item: InaccessibleModel,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit
) {
    CrudViewPage(
        title = "Inaccessible model",
        onEdit = onEdit, onCopy = onCopy, onDelete = onDelete, onBack = onBack,
        deleteName = "${item.providerId} · ${item.model}",
        helpTopic = "inaccessible_models"
    ) {
        CrudField("Provider", item.providerId)
        CrudField("Model", item.model)
        CrudField("Reason", item.reason.ifBlank { "(none)" })
    }
}
