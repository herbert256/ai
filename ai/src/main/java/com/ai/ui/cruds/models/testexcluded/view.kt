package com.ai.ui.cruds.models.testexcluded

import androidx.compose.runtime.Composable
import com.ai.model.TestExcludedModel
import com.ai.ui.cruds.framework.CrudField
import com.ai.ui.cruds.framework.CrudViewPage

@Composable
internal fun TestExcludedModelView(
    item: TestExcludedModel,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit
) {
    CrudViewPage(
        title = "Test-excluded model",
        onEdit = onEdit, onCopy = onCopy, onDelete = onDelete, onBack = onBack,
        deleteName = "${item.providerId} · ${item.model}",
        helpTopic = "test_excluded_models"
    ) {
        CrudField("Provider", item.providerId)
        CrudField("Model", item.model)
        CrudField("Note", "Skipped by Test all models")
    }
}
