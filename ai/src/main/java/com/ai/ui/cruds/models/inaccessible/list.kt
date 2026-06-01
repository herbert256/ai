package com.ai.ui.cruds.models.inaccessible

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.model.InaccessibleModel
import com.ai.model.Settings
import com.ai.ui.cruds.framework.CrudListPage

private sealed interface Mode {
    data object List : Mode
    data class View(val item: InaccessibleModel) : Mode
    data class Edit(val item: InaccessibleModel) : Mode
    data class Add(val prefill: InaccessibleModel?) : Mode
}

@Composable
fun InaccessibleModelsCrud(
    aiSettings: Settings,
    onSave: (Settings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onHousekeeping: (() -> Unit)? = null
) {
    var mode by remember { mutableStateOf<Mode>(Mode.List) }
    val toList = { mode = Mode.List }
    val remove: (InaccessibleModel) -> Unit = { onSave(aiSettings.removeInaccessibleModel(it.providerId, it.model)) }
    val upsert: (InaccessibleModel?, InaccessibleModel) -> Unit = { original, saved ->
        val pruned = original?.let { aiSettings.removeInaccessibleModel(it.providerId, it.model) } ?: aiSettings
        onSave(pruned.upsertInaccessibleModel(saved))
    }

    when (val m = mode) {
        Mode.List -> CrudListPage(
            title = "Inaccessible models",
            subject = "Models that returned unreachable",
            helpTopic = "crud_inaccessible_models",
            items = aiSettings.inaccessibleModels.sortedBy { it.key.lowercase() },
            line = { "${it.providerId} · ${it.model}" },
            itemKey = { it.key },
            onView = { mode = Mode.View(it) },
            onAdd = { mode = Mode.Add(null) },
            onHousekeeping = onHousekeeping,
            onBack = onBack,
            emptyMessage = "No inaccessible models"
        )
        is Mode.View -> InaccessibleModelView(
            item = m.item,
            onEdit = { mode = Mode.Edit(m.item) },
            onCopy = { mode = Mode.Add(m.item) },
            onDelete = { remove(m.item); toList() },
            onBack = toList
        )
        is Mode.Edit -> {
            // Track the last-saved entry so an auto-save that re-points the
            // (provider, model) key prunes the PREVIOUS save, not just the
            // original — otherwise rapid model changes orphan intermediates.
            var lastSaved by remember(m.item) { mutableStateOf(m.item) }
            InaccessibleModelEdit(
                item = m.item, aiSettings = aiSettings,
                onSaved = { saved -> upsert(lastSaved, saved); lastSaved = saved },
                onBack = toList, onNavigateHome = onNavigateHome
            )
        }
        is Mode.Add -> InaccessibleModelAdd(
            prefill = m.prefill, aiSettings = aiSettings,
            onSaved = { saved -> upsert(null, saved) },
            onBack = toList, onNavigateHome = onNavigateHome
        )
    }
}
