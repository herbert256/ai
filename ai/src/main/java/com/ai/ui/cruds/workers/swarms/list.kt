package com.ai.ui.cruds.workers.swarms

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.model.Settings
import com.ai.model.Swarm
import com.ai.ui.cruds.framework.CrudListPage

private sealed interface Mode {
    data object List : Mode
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
    var confirmDelete by remember { mutableStateOf<Swarm?>(null) }
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
            subject = "Multi-step agent pipelines",
            helpTopic = "crud_swarms",
            items = aiSettings.swarms.sortedBy { it.name.lowercase() },
            line = { "${it.name} · ${it.members.size} members" },
            itemKey = { it.id },
            // Tapping a swarm jumps straight to the edit screen — the read-only
            // view is skipped; 👯 copy + 🗑 delete live on the edit bar.
            onView = { mode = Mode.Edit(it) },
            onAdd = { mode = Mode.Add },
            onBack = onBack,
            emptyMessage = "No swarms configured"
        )
        is Mode.Edit -> SwarmEdit(
            swarm = m.item, aiSettings = aiSettings,
            onSaved = { saved -> upsert(saved) },
            onDelete = { confirmDelete = m.item },
            onBack = toList, onNavigateHome = onNavigateHome
        )
        Mode.Add -> SwarmAdd(
            aiSettings = aiSettings,
            onSaved = { saved -> upsert(saved) },
            onBack = toList, onNavigateHome = onNavigateHome
        )
    }

    confirmDelete?.let { sw ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete swarm?") },
            text = { Text("Delete “${sw.name}”? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; remove(sw); toList() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }
}
