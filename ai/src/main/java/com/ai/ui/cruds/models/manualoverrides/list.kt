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
    // Upsert by id, then dedup on the (providerId, modelId) composite so
    // two overrides can't target the same model (which would give the
    // resolver an order-dependent ambiguous entry). Drop any OTHER row
    // sharing the saved row's (provider, model) — mirrors the
    // composite-keyed blocked/test-excluded CRUDs.
    val upsert: (ModelTypeOverride) -> Unit = { saved ->
        val list = aiSettings.modelTypeOverrides
        val replaced = if (list.any { it.id == saved.id }) list.map { if (it.id == saved.id) saved else it }
                       else list + saved
        val deduped = replaced.filter {
            it.id == saved.id || it.providerId != saved.providerId || it.modelId != saved.modelId
        }
        onSave(aiSettings.withModelTypeOverrides(deduped))
    }
    val mi = com.ai.ui.shared.LocalMetadataIcons.current
    val flags: (ModelTypeOverride) -> String = {
        buildString {
            if (it.supportsVision) append(" ${mi.view}")
            if (it.supportsWebSearch) append(" ${mi.web}")
            if (it.supportsReasoning) append(" ${mi.reportModelIcon}")
        }
    }

    when (val m = mode) {
        Mode.List -> CrudListPage(
            title = "Manual model types",
            subject = "Model types you set by hand",
            helpTopic = "crud_model_types",
            items = aiSettings.modelTypeOverrides.sortedBy { "${it.providerId}/${it.modelId}".lowercase() },
            line = { "${it.providerId} / ${it.modelId} → ${it.type}${flags(it)}" },
            itemKey = { it.id },
            onView = { mode = Mode.View(it) },
            onAdd = { mode = Mode.Add(null) },
            onBack = onBack,
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
