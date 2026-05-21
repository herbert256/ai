package com.ai.ui.cruds.workers.agents

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.model.Agent
import com.ai.model.Settings
import com.ai.ui.cruds.framework.CrudListPage
import java.util.UUID

private sealed interface Mode {
    data object List : Mode
    data class View(val item: Agent) : Mode
    data class Edit(val item: Agent) : Mode
    data object Add : Mode
}

/**
 * Agents CRUD. Reuses the rich AgentEditScreen form (via [AgentEdit] /
 * [AgentAdd]); copy clones the agent (new id, "-copy" name) and opens it
 * for editing, where Save appends it.
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
            helpTopic = "agents_list",
            items = aiSettings.agents.sortedBy { it.name.lowercase() },
            line = { agent ->
                val active = aiSettings.isProviderActive(agent.provider)
                val model = aiSettings.getEffectiveModelForAgent(agent)
                "${agent.name} · ${agent.provider.id}/$model${if (active) "" else " · (inactive)"}"
            },
            itemKey = { it.id },
            onView = { mode = Mode.View(it) },
            onAdd = { mode = Mode.Add },
            onBack = onBack,
            emptyMessage = "No agents configured"
        )
        is Mode.View -> AgentView(
            agent = m.item, aiSettings = aiSettings,
            onEdit = { mode = Mode.Edit(m.item) },
            onCopy = { mode = Mode.Edit(m.item.copy(id = UUID.randomUUID().toString(), name = "${m.item.name}-copy")) },
            onDelete = { onSave(aiSettings.removeAgent(m.item.id)); toList() },
            onBack = toList
        )
        is Mode.Edit -> AgentEdit(
            agent = m.item, aiSettings = aiSettings, deps = deps,
            onSaved = { saved -> upsert(saved); toList() },
            onBack = toList, onNavigateHome = onNavigateHome
        )
        Mode.Add -> AgentAdd(
            aiSettings = aiSettings, deps = deps,
            onSaved = { saved -> upsert(saved); toList() },
            onBack = toList, onNavigateHome = onNavigateHome
        )
    }
}
