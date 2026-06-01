package com.ai.ui.cruds.workers.agents

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.model.Agent
import com.ai.model.Settings
import com.ai.ui.cruds.framework.CrudListPage

private sealed interface Mode {
    data object List : Mode
    data class Edit(val item: Agent) : Mode
    data object Add : Mode
}

/**
 * Agents CRUD. Reuses the rich AgentEditScreen form (via [AgentEdit] /
 * [AgentAdd]); tapping an agent opens the edit screen directly (the read-only
 * view is skipped), where the 👯 copy and 🗑 delete icons live.
 */
@Composable
fun AgentsCrud(
    aiSettings: Settings,
    onSave: (Settings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    deps: AgentEditDeps
) {
    var mode by remember { mutableStateOf<Mode>(Mode.List) }
    var confirmDelete by remember { mutableStateOf<Agent?>(null) }
    val toList = { mode = Mode.List }
    val upsert: (Agent) -> Unit = { saved ->
        val list = aiSettings.agents
        val updated = if (list.any { it.id == saved.id }) list.map { if (it.id == saved.id) saved else it }
                      else list + saved
        onSave(aiSettings.copy(agents = updated))
    }

    when (val m = mode) {
        Mode.List -> CrudListPage(
            title = "Agents",
            subject = "Saved model + prompt + params combos",
            helpTopic = "crud_agents",
            items = aiSettings.agents.sortedBy { it.name.lowercase() },
            line = { agent ->
                val active = aiSettings.isProviderActive(agent.provider)
                val model = aiSettings.getEffectiveModelForAgent(agent)
                "${agent.name} · ${agent.provider.id}/$model${if (active) "" else " · (inactive)"}"
            },
            itemKey = { it.id },
            // Tapping an agent jumps straight to the edit screen — the read-only
            // view is skipped; 👯 copy + 🗑 delete live on the edit bar.
            onView = { mode = Mode.Edit(it) },
            onAdd = { mode = Mode.Add },
            onBack = onBack,
            emptyMessage = "No agents configured"
        )
        is Mode.Edit -> AgentEdit(
            agent = m.item, aiSettings = aiSettings, deps = deps,
            onSaved = { saved -> upsert(saved) },
            onDelete = { confirmDelete = m.item },
            onBack = toList, onNavigateHome = onNavigateHome
        )
        Mode.Add -> AgentAdd(
            aiSettings = aiSettings, deps = deps,
            onSaved = { saved -> upsert(saved) },
            onBack = toList, onNavigateHome = onNavigateHome
        )
    }

    confirmDelete?.let { ag ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete agent?") },
            text = { Text("Delete “${ag.name}”? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; onSave(aiSettings.removeAgent(ag.id)); toList() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }
}
