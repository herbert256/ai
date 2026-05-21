package com.ai.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.model.Settings
import com.ai.model.TestExcludedModel
import com.ai.ui.shared.CrudListScreen

/**
 * AI Setup → Test-excluded models — CRUD list of provider/model pairs
 * the "Test all models" sweep skips. Auto-populated when a probe costs
 * more than 5¢; hand-curated here.
 *
 * Add path drops the dedicated edit screen — there's nothing to edit
 * beyond the (provider, model) pair, so tapping Add jumps directly to
 * the model picker and saves on confirm. Edit path is the same: tapping
 * an existing entry opens the picker so the user can swap the model.
 */
@Composable
fun TestExcludedModelsListScreen(
    aiSettings: Settings,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (Settings) -> Unit,
    onNavigateHome: () -> Unit
) {
    var pickerMode by remember { mutableStateOf<PickerMode?>(null) }

    if (pickerMode != null) {
        val mode = pickerMode!!
        com.ai.ui.report.manage.ReportSelectModelsScreen(
            aiSettings = aiSettings,
            titleText = "Pick model to exclude from Test all models",
            onConfirm = { (provider, m) ->
                val cleared = (mode as? PickerMode.Edit)?.let {
                    aiSettings.removeTestExcluded(it.original.providerId, it.original.model)
                } ?: aiSettings
                onSave(cleared.upsertTestExcluded(TestExcludedModel(provider.id, m)))
                pickerMode = null
            },
            onBack = { pickerMode = null },
            onNavigateHome = onNavigateHome
        )
        return
    }

    CrudListScreen(
        title = "Test-excluded models",
        helpTopic = "test_excluded_models",
        items = aiSettings.testExcludedModels,
        addLabel = "Add test-excluded model",
        emptyMessage = "No test-excluded models",
        sortKey = { it.key },
        itemTitle = { "${it.providerId} · ${it.model}" },
        itemSubtitle = { "Skipped by Test all models" },
        onAdd = { pickerMode = PickerMode.Add },
        onEdit = { pickerMode = PickerMode.Edit(it) },
        onDelete = { onSave(aiSettings.removeTestExcluded(it.providerId, it.model)) },
        onBack = onBackToAiSetup,
        onHome = onBackToHome,
        deleteEntityType = "Test-excluded model",
        deleteEntityName = { "${it.providerId} · ${it.model}" },
        itemKey = { it.key }
    )
}

private sealed interface PickerMode {
    data object Add : PickerMode
    data class Edit(val original: TestExcludedModel) : PickerMode
}

