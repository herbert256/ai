package com.ai.ui.cruds.models.manualoverrides

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.model.ModelTypeOverride
import com.ai.model.Settings
import com.ai.ui.cruds.framework.CrudListPage

private sealed interface Mode {
    data object List : Mode
    data class View(val item: ModelTypeOverride) : Mode
    data class Edit(val item: ModelTypeOverride) : Mode
    data class Add(val prefill: ModelTypeOverride?) : Mode
}

@Composable
fun ManualOverridesCrud(
    aiSettings: Settings,
    onSave: (Settings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var mode by remember { mutableStateOf<Mode>(Mode.List) }
    val toList = { mode = Mode.List }
    val remove: (ModelTypeOverride) -> Unit = { o ->
        onSave(aiSettings.withModelTypeOverrides(aiSettings.modelTypeOverrides.filter { it.id != o.id }))
    }
    // Upsert by id: replace existing, else append.
    val upsert: (ModelTypeOverride) -> Unit = { saved ->
        val list = aiSettings.modelTypeOverrides
        val updated = if (list.any { it.id == saved.id }) list.map { if (it.id == saved.id) saved else it }
                      else list + saved
        onSave(aiSettings.withModelTypeOverrides(updated))
    }
    val flags: (ModelTypeOverride) -> String = {
        buildString {
            if (it.supportsVision) append(" 👁")
            if (it.supportsWebSearch) append(" 🌐")
            if (it.supportsReasoning) append(" 🧠")
        }
    }

    when (val m = mode) {
        Mode.List -> CrudListPage(
            title = "Manual model types",
            helpTopic = "manual_model_types_list",
            items = aiSettings.modelTypeOverrides.sortedBy { "${it.providerId}/${it.modelId}".lowercase() },
            line = { "${it.providerId} / ${it.modelId} → ${it.type}${flags(it)}" },
            itemKey = { it.id },
            onView = { mode = Mode.View(it) },
            onEdit = { mode = Mode.Edit(it) },
            onAdd = { mode = Mode.Add(null) },
            onCopy = { mode = Mode.Add(it) },
            onDelete = { remove(it) },
            deleteName = { "${it.providerId}/${it.modelId}" },
            onBack = onBack,
            addLabel = "Add override",
            emptyMessage = "No overrides yet. Add one to force a specific type for a provider/model pair."
        )
        is Mode.View -> ManualOverrideView(
            item = m.item,
            onEdit = { mode = Mode.Edit(m.item) },
            onCopy = { mode = Mode.Add(m.item) },
            onDelete = { remove(m.item); toList() },
            onBack = toList
        )
        is Mode.Edit -> ManualOverrideEdit(
            item = m.item, aiSettings = aiSettings,
            onSaved = { saved -> upsert(saved); toList() },
            onBack = toList
        )
        is Mode.Add -> ManualOverrideAdd(
            prefill = m.prefill, aiSettings = aiSettings,
            onSaved = { saved -> upsert(saved); toList() },
            onBack = toList
        )
    }
}
