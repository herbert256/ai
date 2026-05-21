package com.ai.ui.cruds.workers.flocks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.model.Flock
import com.ai.model.Settings
import com.ai.ui.cruds.framework.CrudListPage
import java.util.UUID

private sealed interface Mode {
    data object List : Mode
    data class View(val item: Flock) : Mode
    data class Edit(val item: Flock) : Mode
    data object Add : Mode
}

@Composable
fun FlocksCrud(
    aiSettings: Settings,
    onSave: (Settings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var mode by remember { mutableStateOf<Mode>(Mode.List) }
    val toList = { mode = Mode.List }
    val upsert: (Flock) -> Unit = { saved ->
        val list = aiSettings.flocks
        val updated = if (list.any { it.id == saved.id }) list.map { if (it.id == saved.id) saved else it }
                      else list + saved
        onSave(aiSettings.copy(flocks = updated))
    }
    val remove: (Flock) -> Unit = { onSave(aiSettings.copy(flocks = aiSettings.flocks.filter { f -> f.id != it.id })) }

    when (val m = mode) {
        Mode.List -> CrudListPage(
            title = "Flocks",
            helpTopic = "flocks_list",
            items = aiSettings.flocks.sortedBy { it.name.lowercase() },
            line = { "${it.name} · ${aiSettings.getAgentsForFlock(it).size} agents" },
            itemKey = { it.id },
            onView = { mode = Mode.View(it) },
            onAdd = { mode = Mode.Add },
            onBack = onBack,
            emptyMessage = "No flocks configured"
        )
        is Mode.View -> FlockView(
            flock = m.item, aiSettings = aiSettings,
            onEdit = { mode = Mode.Edit(m.item) },
            onCopy = { mode = Mode.Edit(m.item.copy(id = UUID.randomUUID().toString(), name = "${m.item.name}-copy")) },
            onDelete = { remove(m.item); toList() },
            onBack = toList
        )
        is Mode.Edit -> FlockEdit(
            flock = m.item, aiSettings = aiSettings,
            onSaved = { saved -> upsert(saved); toList() },
            onBack = toList, onNavigateHome = onNavigateHome
        )
        Mode.Add -> FlockAdd(
            aiSettings = aiSettings,
            onSaved = { saved -> upsert(saved); toList() },
            onBack = toList, onNavigateHome = onNavigateHome
        )
    }
}
