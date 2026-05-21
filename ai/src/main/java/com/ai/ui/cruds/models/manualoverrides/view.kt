package com.ai.ui.cruds.models.manualoverrides

import androidx.compose.runtime.Composable
import com.ai.model.ModelTypeOverride
import com.ai.ui.cruds.framework.CrudField
import com.ai.ui.cruds.framework.CrudViewPage

@Composable
internal fun ManualOverrideView(
    item: ModelTypeOverride,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit
) {
    val caps = buildList {
        if (item.supportsVision) add("vision 👁")
        if (item.supportsWebSearch) add("web-search 🌐")
        if (item.supportsReasoning) add("thinking 🧠")
    }.joinToString(", ").ifBlank { "(none)" }
    CrudViewPage(
        title = "Manual override",
        onEdit = onEdit, onCopy = onCopy, onDelete = onDelete, onBack = onBack,
        deleteName = "${item.providerId} / ${item.modelId}",
        helpTopic = "manual_model_types_list"
    ) {
        CrudField("Provider", item.providerId)
        CrudField("Model", item.modelId)
        CrudField("Type", item.type)
        CrudField("Capabilities", caps)
    }
}
