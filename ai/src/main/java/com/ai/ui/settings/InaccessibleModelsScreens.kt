package com.ai.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.model.InaccessibleModel
import com.ai.model.Settings
import com.ai.ui.shared.CrudListScreen

/**
 * AI Models setup → Inaccessible models — CRUD list of (provider,
 * model) pairs the user genuinely can't call on their account (Together
 * non-serverless catalog entries are the canonical case). Distinct from
 * [TestExcludedModelsListScreen]: entries here are *hidden* from every
 * model picker (the model is unreachable on this tier) and dropped from
 * Test all models sweep results rather than counted as FAIL.
 * Auto-populated by the test engine when a probe returns "Unable to
 * access non-serverless"; hand-curable here.
 *
 * Add / Edit jumps straight to the picker — same picker-direct flow as
 * TestExcludedModelsListScreen. The picker passed `hideInaccessible =
 * false` so the user can still pick a currently-inaccessible model to
 * delete or to point an entry at a different model.
 */
@Composable
fun InaccessibleModelsListScreen(
    aiSettings: Settings,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (Settings) -> Unit,
    onNavigateHome: () -> Unit
) {
    var pickerMode by remember { mutableStateOf<InaccessiblePickerMode?>(null) }

    if (pickerMode != null) {
        val mode = pickerMode!!
        com.ai.ui.report.ReportSelectModelsScreen(
            aiSettings = aiSettings,
            titleText = "Pick inaccessible model",
            hideInaccessible = false,
            onConfirm = { (provider, m) ->
                val cleared = (mode as? InaccessiblePickerMode.Edit)?.let {
                    aiSettings.removeInaccessibleModel(it.original.providerId, it.original.model)
                } ?: aiSettings
                val reason = (mode as? InaccessiblePickerMode.Edit)?.original?.reason ?: "Manually added"
                onSave(cleared.upsertInaccessibleModel(InaccessibleModel(provider.id, m, reason)))
                pickerMode = null
            },
            onBack = { pickerMode = null },
            onNavigateHome = onNavigateHome
        )
        return
    }

    CrudListScreen(
        title = "Inaccessible models",
        helpTopic = "inaccessible_models",
        items = aiSettings.inaccessibleModels,
        addLabel = "Add inaccessible model",
        emptyMessage = "No inaccessible models",
        sortKey = { it.key },
        itemTitle = { "${it.providerId} · ${it.model}" },
        itemSubtitle = { it.reason.take(80) },
        onAdd = { pickerMode = InaccessiblePickerMode.Add },
        onEdit = { pickerMode = InaccessiblePickerMode.Edit(it) },
        onDelete = { onSave(aiSettings.removeInaccessibleModel(it.providerId, it.model)) },
        onBack = onBackToAiSetup,
        onHome = onBackToHome,
        deleteEntityType = "Inaccessible model",
        deleteEntityName = { "${it.providerId} · ${it.model}" },
        itemKey = { it.key }
    )
}

private sealed interface InaccessiblePickerMode {
    data object Add : InaccessiblePickerMode
    data class Edit(val original: InaccessibleModel) : InaccessiblePickerMode
}
