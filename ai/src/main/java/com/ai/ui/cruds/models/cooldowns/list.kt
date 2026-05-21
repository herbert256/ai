package com.ai.ui.cruds.models.cooldowns

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.data.ModelCooldownStore
import com.ai.model.Settings
import com.ai.ui.cruds.framework.CrudListPage

private sealed interface Mode {
    data object List : Mode
    data class View(val item: ModelCooldownStore.CooldownEntry) : Mode
    data class Edit(val item: ModelCooldownStore.CooldownEntry) : Mode
    data class Add(val prefill: ModelCooldownStore.CooldownEntry?) : Mode
}

/**
 * Model-cooldowns CRUD. Writes straight to [ModelCooldownStore] (the
 * cooldown map isn't part of Settings, so no onSave). Copy duplicates an
 * entry onto a different model; edit re-points the existing key.
 */
@Composable
fun ModelCooldownsCrud(
    aiSettings: Settings,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToTrace: (String) -> Unit = {}
) {
    val cooldownMap by ModelCooldownStore.cooldowns.collectAsState()
    val items = remember(cooldownMap) {
        ModelCooldownStore.entries().sortedBy { "${it.providerId}/${it.model}".lowercase() }
    }
    var mode by remember { mutableStateOf<Mode>(Mode.List) }
    val toList = { mode = Mode.List }

    when (val m = mode) {
        Mode.List -> CrudListPage(
            title = "Model cooldowns",
            helpTopic = "model_cooldowns_list",
            items = items,
            line = { "${it.providerId} / ${com.ai.ui.shared.shortModelName(it.model)}" },
            itemKey = { "${it.providerId}:${it.model}" },
            onView = { mode = Mode.View(it) },
            onAdd = { mode = Mode.Add(null) },
            onBack = onBack,
            emptyMessage = "No cooldowns. Models rate-limited by a >1h 429 land here automatically; you can also add one manually."
        )
        is Mode.View -> CooldownView(
            item = m.item,
            onEdit = { mode = Mode.Edit(m.item) },
            onCopy = { mode = Mode.Add(m.item) },
            onDelete = { ModelCooldownStore.remove(m.item.providerId, m.item.model); toList() },
            onBack = toList,
            onNavigateToTrace = onNavigateToTrace
        )
        is Mode.Edit -> CooldownEdit(
            item = m.item, aiSettings = aiSettings,
            onSaved = { providerId, model, untilMs ->
                // Edit may re-point the key — drop the old one first.
                if (providerId != m.item.providerId || model != m.item.model) {
                    ModelCooldownStore.remove(m.item.providerId, m.item.model)
                }
                ModelCooldownStore.markUnavailable(providerId, model, untilMs)
                toList()
            },
            onBack = toList
        )
        is Mode.Add -> CooldownAdd(
            prefill = m.prefill, aiSettings = aiSettings,
            onSaved = { providerId, model, untilMs ->
                ModelCooldownStore.markUnavailable(providerId, model, untilMs)
                toList()
            },
            onBack = toList
        )
    }
}
