package com.ai.ui.cruds.parameters

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.model.Parameters
import com.ai.model.Settings
import com.ai.ui.cruds.framework.CrudListPage

private sealed interface Mode {
    data object List : Mode
    data class View(val item: Parameters) : Mode
    data class Edit(val item: Parameters) : Mode
    data class Add(val prefill: Parameters?) : Mode
}

@Composable
fun ParametersCrud(
    aiSettings: Settings,
    onSave: (Settings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var mode by remember { mutableStateOf<Mode>(Mode.List) }
    val toList = { mode = Mode.List }
    val upsert: (Parameters) -> Unit = { saved ->
        val list = aiSettings.parameters
        val updated = if (list.any { it.id == saved.id }) list.map { if (it.id == saved.id) saved else it }
                      else list + saved
        onSave(aiSettings.copy(parameters = updated))
    }

    when (val m = mode) {
        Mode.List -> CrudListPage(
            title = "Parameters",
            subject = "Saved temperature / token presets",
            helpTopic = "crud_parameters",
            items = aiSettings.parameters.sortedBy { it.name.lowercase() },
            line = { "${it.name} · ${parameterRows(it).size} set" },
            itemKey = { it.id },
            onView = { mode = Mode.View(it) },
            onAdd = { mode = Mode.Add(null) },
            onBack = onBack,
            emptyMessage = "No parameter presets configured"
        )
        is Mode.View -> ParametersView(
            item = m.item,
            onEdit = { mode = Mode.Edit(m.item) },
            onCopy = { mode = Mode.Add(m.item.copy(name = "${m.item.name}-copy")) },
            onDelete = { onSave(aiSettings.removeParameters(m.item.id)); toList() },
            onBack = toList
        )
        is Mode.Edit -> ParametersEdit(
            item = m.item, aiSettings = aiSettings,
            onSaved = { saved -> upsert(saved) },
            onBack = toList, onNavigateHome = onNavigateHome
        )
        is Mode.Add -> ParametersAdd(
            prefill = m.prefill,
            aiSettings = aiSettings,
            onSaved = { saved -> upsert(saved) },
            onBack = toList, onNavigateHome = onNavigateHome
        )
    }
}
