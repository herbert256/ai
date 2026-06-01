package com.ai.ui.cruds.workers.flocks

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.model.Flock
import com.ai.model.Settings
import com.ai.ui.cruds.framework.CrudListPage

private sealed interface Mode {
    data object List : Mode
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
    var confirmDelete by remember { mutableStateOf<Flock?>(null) }
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
            subject = "Named groups of agents",
            helpTopic = "crud_flocks",
            items = aiSettings.flocks.sortedBy { it.name.lowercase() },
            line = { "${it.name} · ${aiSettings.getAgentsForFlock(it).size} agents" },
            itemKey = { it.id },
            // Tapping a flock jumps straight to the edit screen — the read-only
            // view is skipped; 👯 copy + 🗑 delete live on the edit bar.
            onView = { mode = Mode.Edit(it) },
            onAdd = { mode = Mode.Add },
            onBack = onBack,
            emptyMessage = "No flocks configured"
        )
        is Mode.Edit -> FlockEdit(
            flock = m.item, aiSettings = aiSettings,
            onSaved = { saved -> upsert(saved); toList() },
            onDelete = { confirmDelete = m.item },
            onBack = toList, onNavigateHome = onNavigateHome
        )
        Mode.Add -> FlockAdd(
            aiSettings = aiSettings,
            onSaved = { saved -> upsert(saved); toList() },
            onBack = toList, onNavigateHome = onNavigateHome
        )
    }

    confirmDelete?.let { fl ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete flock?") },
            text = { Text("Delete “${fl.name}”? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; remove(fl); toList() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }
}
