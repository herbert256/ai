package com.ai.ui.cruds.workers.swarms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.model.Settings
import com.ai.model.Swarm
import com.ai.ui.cruds.framework.CrudListPage
import java.util.UUID

private sealed interface Mode {
    data object List : Mode
    data class View(val item: Swarm) : Mode
    data class Edit(val item: Swarm) : Mode
    data object Add : Mode
}

@Composable
fun SwarmsCrud(
    aiSettings: Settings,
    onSave: (Settings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var mode by remember { mutableStateOf<Mode>(Mode.List) }
    val toList = { mode = Mode.List }
    val upsert: (Swarm) -> Unit = { saved ->
        val list = aiSettings.swarms
        val updated = if (list.any { it.id == saved.id }) list.map { if (it.id == saved.id) saved else it }
                      else list + saved
        onSave(aiSettings.copy(swarms = updated))
    }
    val remove: (Swarm) -> Unit = { onSave(aiSettings.copy(swarms = aiSettings.swarms.filter { s -> s.id != it.id })) }

    when (val m = mode) {
        Mode.List -> CrudListPage(
            title = "Swarms",
            helpTopic = "swarms_list",
            items = aiSettings.swarms.sortedBy { it.name.lowercase() },
            line = { "${it.name} · ${it.members.size} members" },
            itemKey = { it.id },
            onView = { mode = Mode.View(it) },
            onAdd = { mode = Mode.Add },
            onBack = onBack,
            emptyMessage = "No swarms configured"
        )
        is Mode.View -> SwarmView(
            swarm = m.item, aiSettings = aiSettings,
            onEdit = { mode = Mode.Edit(m.item) },
            onCopy = { mode = Mode.Edit(m.item.copy(id = UUID.randomUUID().toString(), name = "${m.item.name}-copy")) },
            onDelete = { remove(m.item); toList() },
            onBack = toList
        )
        is Mode.Edit -> SwarmEdit(
            swarm = m.item, aiSettings = aiSettings,
            onSaved = { saved -> upsert(saved); toList() },
            onBack = toList, onNavigateHome = onNavigateHome
        )
        Mode.Add -> SwarmAdd(
            aiSettings = aiSettings,
            onSaved = { saved -> upsert(saved); toList() },
            onBack = toList, onNavigateHome = onNavigateHome
        )
    }
}
