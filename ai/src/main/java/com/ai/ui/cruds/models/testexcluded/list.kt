package com.ai.ui.cruds.models.testexcluded

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.model.Settings
import com.ai.model.TestExcludedModel
import com.ai.ui.cruds.framework.CrudListPage

private sealed interface Mode {
    data object List : Mode
    data class View(val item: TestExcludedModel) : Mode
    data class Edit(val item: TestExcludedModel) : Mode
    data class Add(val prefill: TestExcludedModel?) : Mode
}

@Composable
fun TestExcludedModelsCrud(
    aiSettings: Settings,
    onSave: (Settings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var mode by remember { mutableStateOf<Mode>(Mode.List) }
    val toList = { mode = Mode.List }
    val remove: (TestExcludedModel) -> Unit = { onSave(aiSettings.removeTestExcluded(it.providerId, it.model)) }
    val upsert: (TestExcludedModel?, TestExcludedModel) -> Unit = { original, saved ->
        val pruned = original?.let { aiSettings.removeTestExcluded(it.providerId, it.model) } ?: aiSettings
        onSave(pruned.upsertTestExcluded(saved))
    }

    when (val m = mode) {
        Mode.List -> CrudListPage(
            title = "Test-excluded models",
            helpTopic = "test_excluded_models",
            items = aiSettings.testExcludedModels.sortedBy { it.key.lowercase() },
            line = { "${it.providerId} · ${it.model}" },
            itemKey = { it.key },
            onView = { mode = Mode.View(it) },
            onEdit = { mode = Mode.Edit(it) },
            onAdd = { mode = Mode.Add(null) },
            onCopy = { mode = Mode.Add(it) },
            onDelete = { remove(it) },
            deleteName = { "${it.providerId} · ${it.model}" },
            onBack = onBack,
            addLabel = "Add test-excluded model",
            emptyMessage = "No test-excluded models"
        )
        is Mode.View -> TestExcludedModelView(
            item = m.item,
            onEdit = { mode = Mode.Edit(m.item) },
            onCopy = { mode = Mode.Add(m.item) },
            onDelete = { remove(m.item); toList() },
            onBack = toList
        )
        is Mode.Edit -> TestExcludedModelEdit(
            item = m.item, aiSettings = aiSettings,
            onSaved = { saved -> upsert(m.item, saved); toList() },
            onBack = toList, onNavigateHome = onNavigateHome
        )
        is Mode.Add -> TestExcludedModelAdd(
            prefill = m.prefill, aiSettings = aiSettings,
            onSaved = { saved -> upsert(null, saved); toList() },
            onBack = toList, onNavigateHome = onNavigateHome
        )
    }
}
