package com.ai.ui.cruds.workers.flocks

import androidx.compose.runtime.Composable
import com.ai.model.Flock
import com.ai.model.Settings
import com.ai.ui.cruds.framework.CrudField
import com.ai.ui.cruds.framework.CrudViewPage
import com.ai.ui.settings.WorkerSharedCards

@Composable
internal fun FlockView(
    flock: Flock,
    aiSettings: Settings,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit
) {
    val agents = aiSettings.getAgentsForFlock(flock)
    CrudViewPage(
        title = "Flock",
        onEdit = onEdit, onCopy = onCopy, onDelete = onDelete, onBack = onBack,
        deleteName = flock.name,
        helpTopic = "flock_view"
    ) {
        CrudField("Name", flock.name)
        CrudField("Agents (${agents.size})", agents.joinToString(", ") { it.name }.ifBlank { "(none)" })
        WorkerSharedCards(
            aiSettings = aiSettings,
            paramsIds = flock.paramsIds,
            systemPromptId = flock.systemPromptId
        )
    }
}
