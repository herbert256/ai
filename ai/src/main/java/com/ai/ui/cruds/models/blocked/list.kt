package com.ai.ui.cruds.models.blocked

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.model.BlockedModel
import com.ai.model.Settings
import com.ai.ui.cruds.framework.CrudListPage

private sealed interface Mode {
    data object List : Mode
    data class View(val item: BlockedModel) : Mode
    data class Edit(val item: BlockedModel) : Mode
    data class Add(val prefill: BlockedModel?) : Mode
}

/**
 * Blocked-models CRUD entry. Self-contained: manages its own
 * view/edit/add overlays so the host only needs to mount this once.
 */
@Composable
fun BlockedModelsCrud(
    aiSettings: Settings,
    onSave: (Settings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var mode by remember { mutableStateOf<Mode>(Mode.List) }
    val toList = { mode = Mode.List }
    val remove: (BlockedModel) -> Unit = { onSave(aiSettings.removeBlockedModel(it.providerId, it.model)) }
    // Upsert dropping the original composite key first (it may have changed).
    val upsert: (BlockedModel?, BlockedModel) -> Unit = { original, saved ->
        val pruned = original?.let { aiSettings.removeBlockedModel(it.providerId, it.model) } ?: aiSettings
        onSave(pruned.upsertBlockedModel(saved))
    }

    when (val m = mode) {
        Mode.List -> CrudListPage(
            title = "Blocked models",
            helpTopic = "blocked_models",
            items = aiSettings.blockedModels.sortedBy { it.key.lowercase() },
            line = { "${it.providerId} · ${it.model}" },
            itemKey = { it.key },
            onView = { mode = Mode.View(it) },
            onAdd = { mode = Mode.Add(null) },
            onBack = onBack,
            emptyMessage = "No blocked models"
        )
        is Mode.View -> BlockedModelView(
            item = m.item,
            onEdit = { mode = Mode.Edit(m.item) },
            onCopy = { mode = Mode.Add(m.item) },
            onDelete = { remove(m.item); toList() },
            onBack = toList
        )
        is Mode.Edit -> BlockedModelEdit(
            item = m.item, aiSettings = aiSettings,
            onSaved = { saved -> upsert(m.item, saved); toList() },
            onBack = toList, onNavigateHome = onNavigateHome
        )
        is Mode.Add -> BlockedModelAdd(
            prefill = m.prefill, aiSettings = aiSettings,
            onSaved = { saved -> upsert(null, saved); toList() },
            onBack = toList, onNavigateHome = onNavigateHome
        )
    }
}
